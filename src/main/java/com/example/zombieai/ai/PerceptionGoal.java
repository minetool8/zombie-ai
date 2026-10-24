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
    private Vec3 investigateCenter;
    private double investigateJitter;
    private int investigateRetries;

    private LivingEntity lastTarget;
    /** The player target we chose ourselves (sight, blood, retaliation). Anything else was set by another mod. */
    private Player ownedTarget;

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
    public void clearInvestigate() {
        investigatePos = null;
        investigateCenter = null;
    }

    /** Idle (no target, nothing to investigate) while it is night/raining: stand still. */
    public boolean isFrozen() {
        return mob.getTarget() == null && investigatePos == null
                && PerceptionUtil.isDormantTime(mob.level());
    }

    private void startInvestigate(Vec3 pos) {
        investigatePos = pos;
        investigateCenter = null;
        investigateExpiry = ticks + ZombieAIConfig.INVESTIGATE_TIMEOUT_TICKS.get();
        // Pathing to a far sound needs a bigger search range (only for this zombie, only while walking there).
        ZombieAIEvents.refreshFollowRange(mob, mob.position().distanceTo(pos) + 10.0);
    }

    /** Investigate a point randomly offset around the real sound source (zombies only know it approximately). */
    private void startInvestigateAround(Vec3 center, double jitter) {
        startInvestigate(jitterPoint(center, jitter));
        investigateCenter = center;
        investigateJitter = jitter;
        investigateRetries = 0;
    }

    private Vec3 jitterPoint(Vec3 center, double jitter) {
        double angle = mob.getRandom().nextDouble() * Math.PI * 2.0;
        double r = Math.sqrt(mob.getRandom().nextDouble()) * jitter;
        return new Vec3(center.x + Math.cos(angle) * r, center.y, center.z + Math.sin(angle) * r);
    }

    /** The chosen spot could not be reached: pick another spot near the sound (max 2 tries). */
    public boolean retryInvestigate() {
        if (investigateCenter == null || investigateRetries >= 2) return false;
        investigateRetries++;
        investigatePos = jitterPoint(investigateCenter, Math.max(investigateJitter, 3.0));
        investigateExpiry = ticks + ZombieAIConfig.INVESTIGATE_TIMEOUT_TICKS.get();
        return true;
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level) || !mob.isAlive()) {
            return;
        }
        ticks++;
        if (ticks % 20 == 0) {
            ZombieAIEvents.refreshFollowRange(mob,
                    investigatePos != null ? mob.position().distanceTo(investigatePos) + 10.0 : 0.0);
        }
        if (investigatePos != null && ticks > investigateExpiry) {
            clearInvestigate();
        }

        // A dormant (night/rain) zombie does not wake up for villagers/golems/turtles. A chase that
        // was already running before night fell is untouched (lastTarget was not null).
        LivingEntity current = mob.getTarget();
        if (current != null && !(current instanceof Player) && lastTarget == null
                && PerceptionUtil.isDormantTime(level)) {
            mob.setTarget(null);
            current = null;
        }
        lastTarget = current;

        if (ticks % INTERVAL != 0) {
            return;
        }

        double sight = ZombieAIConfig.sight();
        handleRetaliation(level, sight);

        LivingEntity target = mob.getTarget();
        if (target instanceof Player player) {
            superviseTarget(level, player, sight);
        } else if (target == null) {
            ownedTarget = null;
            // Blood smell beats sight, field of view and sleep.
            boolean found = acquireByBlood(level);
            // Frozen zombies keep their (fixed) field of view unless dormantCanSee is off.
            boolean mayLook = !PerceptionUtil.isDormantTime(level) || ZombieAIConfig.DORMANT_CAN_SEE.get();
            found = found || (mayLook && acquireTarget(level, sight));
            if (!found) {
                processHearing(level);   // hearing always works, day or night
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
            if (PerceptionUtil.canSee(mob, p, sight, true)) {   // must be in front of the zombie
                best = p;
                bestDist = d;
            }
        }
        if (best == null) return false;
        setVisualTarget(best);
        return true;
    }

    /** Smelling blood: target a bleeding player in smell range, no line of sight or facing needed. */
    private boolean acquireByBlood(ServerLevel level) {
        if (!ZombieAIConfig.BLOOD_SMELL_ENABLED.get()) return false;
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : level.players()) {
            if (!EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(p) || !p.isAlive()) continue;
            double smell = BloodSense.smellRange(p, level.getServer());
            if (smell <= 0.0) continue;
            double d = mob.distanceTo(p);
            if (d > smell || d >= bestDist || !mob.canAttack(p)) continue;
            best = p;
            bestDist = d;
        }
        if (best == null) return false;
        setVisualTarget(best);
        return true;
    }

    private void setVisualTarget(Player p) {
        ownedTarget = p;
        mob.setTarget(p);
        lastSeenPos = p.position();
        unseenTicks = 0;
        firstHeard = -1;
        clearInvestigate();
    }

    private void superviseTarget(ServerLevel level, Player player, double sight) {
        if (!EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(player) || !player.isAlive()
                || player.level() != mob.level()) {
            mob.setTarget(null);
            mob.getNavigation().stop();
            unseenTicks = 0;
            lastSeenPos = null;
            return;
        }
        // Blood: while it smells the player it keeps following, even without seeing them.
        double smell = BloodSense.smellRange(player, level.getServer());
        if (smell > 0.0 && mob.distanceTo(player) <= smell) {
            ownedTarget = player;
            unseenTicks = 0;
            lastSeenPos = player.position();
            return;
        }
        // A target set by ANOTHER mod (e.g. Zombie Awareness' own sight hunting) must pass our vision rules.
        if (player != ownedTarget) {
            if (ZombieAIConfig.VALIDATE_FOREIGN_TARGETS.get()
                    && !PerceptionUtil.canSee(mob, player, sight, true)) {
                mob.setTarget(null);
                mob.getNavigation().stop();
                return;
            }
            ownedTarget = player;
        }
        if (PerceptionUtil.canSee(mob, player, sight, false)) {   // already tracking: it turns to follow
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
            ownedTarget = null;
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
            if (PerceptionUtil.canSee(mob, p, sight, false)) {
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
        startInvestigateAround(center, jitter);
        firstHeard = -1;
    }

    // ----------------------------------------------------------------- hearing

    /** Hears player voice (range depends on how LOUD they are) and movement noise (steps, sprinting, jumping). */
    private void processHearing(ServerLevel level) {
        List<ServerPlayer> speakers = HearingManager.speakers();
        List<ServerPlayer> movers = MovementNoise.noisyPlayers();
        if (speakers.isEmpty() && movers.isEmpty()) return;

        double moveRange = ZombieAIConfig.movementNoiseRange(level);  // smaller while raining
        double perWall = ZombieAIConfig.WALL_ATTENUATION.get();
        double radius = ZombieAIConfig.INVESTIGATE_RADIUS.get();

        ServerPlayer source = null;
        double bestEffective = Double.MAX_VALUE;
        double sourceJitter = radius;
        for (int pass = 0; pass < 2; pass++) {
            List<ServerPlayer> list = pass == 0 ? speakers : movers;
            for (ServerPlayer sp : list) {
                if (sp.level() != level) continue;
                // Voice: a whisper, normal talking and a shout each reach a different distance.
                double range = pass == 0 ? HearingManager.rangeFor(sp, level) : moveRange;
                if (range <= 0.0) continue;
                double dist = mob.distanceTo(sp);
                if (dist > range) continue;
                int walls = PerceptionUtil.countOccluders(level, mob.getEyePosition(), sp.getEyePosition());
                double effective = dist + walls * perWall;
                if (effective <= range && effective < bestEffective) {
                    bestEffective = effective;
                    source = sp;
                    sourceJitter = pass == 0 ? radius : Math.min(radius, 2.0);   // steps are heard more precisely
                }
            }
        }
        if (source == null) return;

        if (firstHeard < 0 || ticks - lastAudible > 20) {
            firstHeard = ticks;
        }
        lastAudible = ticks;
        if (ticks - firstHeard < ZombieAIConfig.REACTION_DELAY_TICKS.get()) return;

        // Approximate location, refreshed every second while the noise continues.
        if (investigatePos == null || ticks - lastInvestigateUpdate >= 20) {
            lastInvestigateUpdate = ticks;
            startInvestigateAround(source.position(), sourceJitter);
        }
    }
}
