package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Replaces vanilla player targeting (which follows players through walls).
 *
 *  VISION  : only way to set a player as target. Needs range + clear visual ray.
 *  HEARING : never sets a target. It only sets an approximate investigation point.
 *  ALERT   : a zombie hurt by a player it cannot see calls nearby zombies to gather.
 */
public class PerceptionGoal extends Goal {
    /** Perception runs every N ticks (2 = 10x per second). */
    private static final int INTERVAL = 2;

    private final Mob mob;

    private int ticks;
    private int unseenTicks;
    private Vec3 lastSeenPos;
    private int handledHurtStamp;

    private Vec3 investigatePos;
    private int investigateExpiry;
    private int lastInvestigateUpdate = -1000;

    private int firstHeard = -1;
    private int lastAudible = -1000;

    public PerceptionGoal(Mob mob) {
        this.mob = mob;
    }

    @Override public boolean canUse() { return true; }
    @Override public boolean canContinueToUse() { return true; }
    @Override public boolean requiresUpdateEveryTick() { return true; }

    public Vec3 investigatePos() { return investigatePos; }
    public boolean hasInvestigation() { return investigatePos != null; }
    public void clearInvestigate() { investigatePos = null; }

    private void startInvestigate(Vec3 pos) {
        investigatePos = pos;
        investigateExpiry = ticks + ZombieAIConfig.INVESTIGATE_TIMEOUT_TICKS.get();
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level) || !mob.isAlive()) {
            return;
        }
        ticks++;
        if (ticks % 20 == 0) {
            ZombieAIEvents.refreshFollowRange(mob);
        }
        if (investigatePos != null && ticks > investigateExpiry) {
            clearInvestigate();
        }
        if (ticks % INTERVAL != 0) {
            return;
        }

        double sight = ZombieAIConfig.sight();
        handleRetaliation(level, sight);

        LivingEntity target = mob.getTarget();
        if (target instanceof Player player) {
            superviseTarget(player, sight);
        } else if (target == null) {
            if (!acquireTarget(level, sight)) {
                processHearing(level);
            }
        }
        // Non-player targets (villagers, golems, turtles) are left to vanilla goals.
    }

    // ------------------------------------------------------------------ vision

    private boolean acquireTarget(ServerLevel level, double sight) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : level.players()) {
            if (!EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(p) || !p.isAlive()) continue;
            double d = mob.distanceToSqr(p);
            if (d > sight * sight || d >= bestDist) continue;
            if (!mob.canAttack(p)) continue;
            if (PerceptionUtil.canSee(mob, p, sight)) {
                best = p;
                bestDist = d;
            }
        }
        if (best == null) return false;
        setVisualTarget(best);
        return true;
    }

    private void setVisualTarget(Player p) {
        mob.setTarget(p);
        lastSeenPos = p.position();
        unseenTicks = 0;
        firstHeard = -1;
        clearInvestigate();
    }

    private void superviseTarget(Player player, double sight) {
        if (!EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(player) || !player.isAlive()
                || player.level() != mob.level()) {
            mob.setTarget(null);
            mob.getNavigation().stop();
            unseenTicks = 0;
            lastSeenPos = null;
            return;
        }
        if (PerceptionUtil.canSee(mob, player, sight)) {
            unseenTicks = 0;
            lastSeenPos = player.position();
            return;
        }
        if (lastSeenPos == null) {
            lastSeenPos = player.position();
        }
        unseenTicks += INTERVAL;
        if (unseenTicks > ZombieAIConfig.LOSE_SIGHT_GRACE_TICKS.get()) {
            mob.setTarget(null);
            mob.getNavigation().stop();
            if (ZombieAIConfig.INVESTIGATE_LAST_SEEN.get()) {
                startInvestigate(lastSeenPos);
            }
            lastSeenPos = null;
            unseenTicks = 0;
        }
    }

    /** Replaces vanilla HurtByTargetGoal, which kept tracking attackers through walls. */
    private void handleRetaliation(ServerLevel level, double sight) {
        int stamp = mob.getLastHurtByMobTimestamp();
        if (stamp == handledHurtStamp) return;
        handledHurtStamp = stamp;
        if (mob.getTarget() != null) return;
        if (mob.getLastHurtByMob() instanceof Player p
                && EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(p) && p.isAlive()) {
            if (PerceptionUtil.canSee(mob, p, sight)) {
                setVisualTarget(p);
            } else {
                // Hit by someone we cannot see: go to where they hit us from, and call the pack.
                startInvestigate(p.position());
                Vec3 rally = ZombieAIConfig.RALLY_AT_ATTACKER.get() ? p.position() : mob.position();
                alertNearby(level, rally);
            }
        }
    }

    // ------------------------------------------------------------------- alert

    private void alertNearby(ServerLevel level, Vec3 rally) {
        double radius = ZombieAIConfig.ALERT_RADIUS.get();
        if (radius <= 0.0) return;
        double jitter = ZombieAIConfig.INVESTIGATE_RADIUS.get();
        AABB box = mob.getBoundingBox().inflate(radius);
        List<Mob> others = level.getEntitiesOfClass(Mob.class, box,
                m -> m != mob && m.isAlive() && ZombieScale.applies(m));
        for (Mob other : others) {
            if (other.distanceToSqr(mob) > radius * radius) continue;
            for (WrappedGoal w : other.targetSelector.getAvailableGoals()) {
                if (w.getGoal() instanceof PerceptionGoal g) {
                    g.receiveAlert(rally, jitter);
                    break;
                }
            }
        }
    }

    /** Called by a neighbour: gather near the given point (approximate), unless already fighting. */
    public void receiveAlert(Vec3 center, double jitter) {
        if (mob.getTarget() != null) return;
        double angle = mob.getRandom().nextDouble() * Math.PI * 2.0;
        double r = Math.sqrt(mob.getRandom().nextDouble()) * jitter;
        startInvestigate(new Vec3(center.x + Math.cos(angle) * r, center.y, center.z + Math.sin(angle) * r));
        firstHeard = -1;
    }

    // ----------------------------------------------------------------- hearing

    private void processHearing(ServerLevel level) {
        List<ServerPlayer> speakers = HearingManager.speakers();
        if (speakers.isEmpty()) return;

        double hearing = ZombieAIConfig.hearing(level);
        double perWall = ZombieAIConfig.WALL_ATTENUATION.get();

        ServerPlayer loudest = null;
        double bestEffective = Double.MAX_VALUE;
        for (ServerPlayer sp : speakers) {
            if (sp.level() != level) continue;
            double dist = mob.distanceTo(sp);
            if (dist > hearing) continue;
            int walls = PerceptionUtil.countOccluders(level, mob.getEyePosition(), sp.getEyePosition());
            double effective = dist + walls * perWall;
            if (effective <= hearing && effective < bestEffective) {
                bestEffective = effective;
                loudest = sp;
            }
        }
        if (loudest == null) return;

        if (firstHeard < 0 || ticks - lastAudible > 20) {
            firstHeard = ticks;
        }
        lastAudible = ticks;
        if (ticks - firstHeard < ZombieAIConfig.REACTION_DELAY_TICKS.get()) return;

        // Approximate location, refreshed every second while the player keeps talking.
        if (investigatePos == null || ticks - lastInvestigateUpdate >= 20) {
            lastInvestigateUpdate = ticks;
            double radius = ZombieAIConfig.INVESTIGATE_RADIUS.get();
            double angle = mob.getRandom().nextDouble() * Math.PI * 2.0;
            double r = Math.sqrt(mob.getRandom().nextDouble()) * radius;
            startInvestigate(new Vec3(loudest.getX() + Math.cos(angle) * r, loudest.getY(),
                    loudest.getZ() + Math.sin(angle) * r));
        }
    }
}
