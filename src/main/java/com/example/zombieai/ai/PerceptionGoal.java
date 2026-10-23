package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Replaces vanilla player targeting (which follows players through walls).
 *
 *  VISION  : only way to set a player as target. Needs range + clear visual ray.
 *  HEARING : never sets a target. It only sets an approximate investigation point.
 *
 * Runs server-side only (goals only exist/run on the server).
 */
public class PerceptionGoal extends Goal {
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
        if (ticks % 5 != 0) {
            return; // perception runs 4x per second, raycasts are not free
        }

        double sight = ZombieAIConfig.sight();
        handleRetaliation(sight);

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
            lastSeenPos = player.position(); // e.g. target set by retaliation: where they hit us from
        }
        unseenTicks += 5;
        if (unseenTicks > ZombieAIConfig.LOSE_SIGHT_GRACE_TICKS.get()) {
            // Lost sight: drop the target so vanilla AI stops homing in on the live position.
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
    private void handleRetaliation(double sight) {
        int stamp = mob.getLastHurtByMobTimestamp();
        if (stamp == handledHurtStamp) return;
        handledHurtStamp = stamp;
        if (mob.getTarget() != null) return;
        if (mob.getLastHurtByMob() instanceof Player p
                && EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(p) && p.isAlive()) {
            if (PerceptionUtil.canSee(mob, p, sight)) {
                setVisualTarget(p);
            } else {
                startInvestigate(p.position()); // we were hit from somewhere unseen
            }
        }
    }

    // ----------------------------------------------------------------- hearing

    private void processHearing(ServerLevel level) {
        List<ServerPlayer> speakers = HearingManager.speakers();
        if (speakers.isEmpty()) return;

        double hearing = ZombieAIConfig.hearing();
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

        // Reaction delay: must stay audible for a while (short gaps between words are tolerated).
        if (firstHeard < 0 || ticks - lastAudible > 20) {
            firstHeard = ticks;
        }
        lastAudible = ticks;
        if (ticks - firstHeard < ZombieAIConfig.REACTION_DELAY_TICKS.get()) return;

        // Only an APPROXIMATE location; refreshed every 2s while the player keeps talking.
        if (investigatePos == null || ticks - lastInvestigateUpdate >= 40) {
            lastInvestigateUpdate = ticks;
            double radius = ZombieAIConfig.INVESTIGATE_RADIUS.get();
            double angle = mob.getRandom().nextDouble() * Math.PI * 2.0;
            double r = Math.sqrt(mob.getRandom().nextDouble()) * radius;
            startInvestigate(new Vec3(loudest.getX() + Math.cos(angle) * r, loudest.getY(),
                    loudest.getZ() + Math.sin(angle) * r));
        }
    }
}
