package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Replaces vanilla player targeting (which follows players through walls).
 *
 *  VISION  : zombies are BLIND. A player is only seen within 1 block and with a clear ray.
 *            Done every 3rd tick, each zombie on its own phase, so the server never does it all at once.
 *  HEARING : the real sense. Never sets a target. SoundBroadcaster / SoundRedirect / ShoutGroan push sounds in
 *            through hear(). Every sound has a NoiseKind (priority): a zombie always goes to the higher
 *            priority noise, even if a lower one is closer. Same priority: the closer source wins.
 *            Nothing is polled here, so hearing costs nothing per zombie.
 *  ALERT   : a zombie hurt by a player it cannot see calls nearby zombies to gather.
 *
 *  PRIORITY (see NoiseKind): gunshot > shout > radio/speaker > normal talking (= sprinting) > walking / whisper.
 */
public class PerceptionGoal extends Goal {
    /** Vision checks run every N ticks per zombie (staggered by entity id). */
    private static final int SIGHT_INTERVAL = 3;

    private static final UUID RAGE_SPEED_UUID = UUID.fromString("5d0b6f3a-7a53-4f1e-9a41-3c1f0a6b7e05");

    private final Mob mob;

    /** Tick (this goal's own counter) until which the zombie is enraged by a shout; 0 = calm. */
    private int rageUntil;
    private int ticks;
    private int unseenTicks;
    private Vec3 lastSeenPos;
    private int handledHurtStamp;

    private Vec3 investigatePos;
    /** Where the zombie really walks to (ground beneath a high sound); null = same as investigatePos. */
    private Vec3 approachPos;
    private int investigateExpiry;
    private int lastInvestigateUpdate = -1000;
    private Vec3 investigateCenter;
    private double investigateJitter;
    private int investigateRetries;

    private LivingEntity lastTarget;
    /** The player target we chose ourselves (sight, blood, retaliation). Anything else was set by another mod. */
    private Player ownedTarget;
    /** Tick until which this zombie is "awake" (heard something recently) and can use its eyes. */
    private int awakeUntil;
    /** What kind of noise the zombie is walking to (null = last seen position / pack alert / nothing). */
    private NoiseKind currentKind;
    /** Effective distance (blocks, walls included) to that noise when it was last heard. */
    private double currentDist = Double.MAX_VALUE;
    private int lastSourceTick = -1000;

    private int firstHeard = -1;
    private int lastAudible = -1000;

    public PerceptionGoal(Mob mob) {
        this.mob = mob;
    }

    /** The PerceptionGoal of a zombie/husk, or null. */
    public static PerceptionGoal of(Mob m) {
        for (WrappedGoal w : m.targetSelector.getAvailableGoals()) {
            if (w.getGoal() instanceof PerceptionGoal g) return g;
        }
        return null;
    }

    @Override public boolean canUse() { return true; }
    @Override public boolean canContinueToUse() { return true; }
    @Override public boolean requiresUpdateEveryTick() { return true; }

    public Vec3 investigatePos() { return investigatePos; }

    // ------------------------------------------------------------------- rage

    public boolean isEnraged() { return rageUntil > 0 && ticks <= rageUntil; }

    /** Heard a shout: +speed (transient attribute) and faster block breaking (read by RageBreakGoal). */
    private void enrage() {
        if (!ZombieAIConfig.RAGE_ENABLED.get()) return;
        rageUntil = ticks + ZombieAIConfig.RAGE_SECONDS.get() * 20;
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null && speed.getModifier(RAGE_SPEED_UUID) == null) {
            speed.addTransientModifier(new AttributeModifier(RAGE_SPEED_UUID, "zombieai rage",
                    ZombieAIConfig.RAGE_SPEED_BONUS.get(), AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
    }

    private void calm() {
        rageUntil = 0;
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(RAGE_SPEED_UUID);
    }
    public boolean hasInvestigation() { return investigatePos != null; }
    public Vec3 approachPos() { return approachPos != null ? approachPos : investigatePos; }
    public void setApproachPos(Vec3 pos) { approachPos = pos; }
    public void clearInvestigate() {
        investigatePos = null;
        approachPos = null;
        investigateCenter = null;
        currentKind = null;
        currentDist = Double.MAX_VALUE;
    }

    /** Idle (no target, nothing to investigate) while it is night/raining: stand still. */
    public boolean isFrozen() {
        return mob.getTarget() == null && investigatePos == null
                && PerceptionUtil.isStillTime(mob.level());
    }

    private void startInvestigate(Vec3 pos) {
        investigatePos = pos;
        approachPos = null;
        investigateCenter = null;
        investigateExpiry = ticks + ZombieAIConfig.INVESTIGATE_TIMEOUT_TICKS.get();
        wake();
        currentKind = null;
        currentDist = Double.MAX_VALUE;
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
        approachPos = null;
        investigateExpiry = ticks + ZombieAIConfig.INVESTIGATE_TIMEOUT_TICKS.get();
        return true;
    }

    // ----------------------------------------------------------------- hearing (pushed in)

    /**
     * A sound reached this zombie.
     *
     * Rules:
     *  - HIGHER PRIORITY WINS, whatever the distance: gunshots > shouting > speakers/radios > normal talking
     *    (= sprinting) > walking / whispers. A zombie walking to a normal talker turns to a far-away shout.
     *  - SAME PRIORITY: the closer source wins (two people talking: it goes to the nearer one). It only
     *    switches if the other one is clearly closer, so it never flip-flops.
     *  - A one-shot sound (gunshot) at the same priority: the newest one wins.
     *  - While heading to a noise, quieter noises do not distract it.
     *  - A LOUD noise (priority 3 and up) also beats sight: it drops the player it is looking at.
     *
     * @param instant true for one-shot sounds (gunshots): no reaction delay, newest wins
     * @param kind    what made the noise (decides the priority)
     * @param dist    effective distance to the source in blocks (walls count extra): smaller = closer
     */
    public void hear(Vec3 source, double jitter, boolean instant, NoiseKind kind, double dist) {
        if (kind == NoiseKind.SHOUT) enrage();
        LivingEntity target = mob.getTarget();
        if (target != null) {
            // A player already within noticing distance is never abandoned for a far-away noise.
            double nr = ZombieAIConfig.NOTICE_RADIUS.get();
            if (target instanceof Player && mob.distanceToSqr(target) <= nr * nr) return;
            if (!kind.loud() || !ZombieAIConfig.LOUD_NOISE_BEATS_SIGHT.get() || !(target instanceof Player)) return;
            if (target.position().distanceToSqr(source) < 36.0) return;   // the noise is the target itself
            dropTarget();
        } else if (investigatePos != null && currentKind != null && kind.priority() < currentKind.priority()) {
            return;   // already heading to something more important
        }

        if (!instant) {
            if (firstHeard < 0 || ticks - lastAudible > 20) {
                firstHeard = ticks;
            }
            lastAudible = ticks;
            if (ticks - firstHeard < ZombieAIConfig.REACTION_DELAY_TICKS.get()) return;
        }

        boolean sameSource = investigateCenter != null && investigateCenter.distanceToSqr(source) < 100.0;
        boolean upgrade = currentKind == null || kind.priority() > currentKind.priority();
        if (investigatePos != null && !sameSource && !upgrade && !instant) {
            // Same priority, another place: switch only if it is clearly closer, or the old one went quiet.
            boolean oldSourceQuiet = ticks - lastSourceTick > 20;
            if (!oldSourceQuiet && dist >= currentDist - 1.0) return;
        }
        lastSourceTick = ticks;
        currentDist = dist;

        boolean moved = investigateCenter == null || investigateCenter.distanceToSqr(source) > 9.0;   // > 3 blocks
        boolean due = investigatePos == null
                || upgrade
                || (instant && moved)
                || (moved && ticks - lastInvestigateUpdate >= 10)
                || ticks - lastInvestigateUpdate >= 20;
        if (due) {
            lastInvestigateUpdate = ticks;
            startInvestigateAround(source, jitter);
            currentKind = kind;
            currentDist = dist;
        }
    }

    private void dropTarget() {
        mob.setTarget(null);
        mob.getNavigation().stop();
        unseenTicks = 0;
        lastSeenPos = null;
        ownedTarget = null;
    }

    // ------------------------------------------------------------------ tick

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
        if (rageUntil > 0 && ticks > rageUntil) {
            calm();
        }

        // A dormant (night/rain) zombie does not wake up for villagers/golems/turtles. A chase that
        // was already running before night fell is untouched (lastTarget was not null).
        LivingEntity current = mob.getTarget();
        if (current != null && !(current instanceof Player) && lastTarget == null
                && PerceptionUtil.isStillTime(level)) {
            mob.setTarget(null);
            current = null;
        }
        lastTarget = current;

        // Being hurt is handled the very tick it happens (just an int compare when nothing happened).
        if (mob.getLastHurtByMobTimestamp() != handledHurtStamp) {
            handleRetaliation(level, ZombieAIConfig.sight());
        }

        // Vision is spread over ticks: every zombie has its own phase.
        if ((ticks + mob.getId()) % SIGHT_INTERVAL != 0) {
            return;
        }

        double sight = ZombieAIConfig.sight();   // zombies are blind: 1 block
        LivingEntity target = mob.getTarget();
        if (target instanceof Player player) {
            superviseTarget(level, player, sight);
        } else if (target == null) {
            ownedTarget = null;
            // Blood smell beats sight, field of view and sleep.
            boolean found = acquireByBlood(level);
            // Frozen zombies keep their (fixed) field of view unless dormantCanSee is off.
            boolean mayLook = visionAllowed(level);
            if (!found && (investigatePos != null || ticks < awakeUntil)) {
                found = noticeNearby(level);   // someone close beats the sound it was walking to
            }
            if (!found && mayLook) {
                acquireTarget(level, sight);
            }
        }
        // Non-player targets (villagers, golems, turtles) are left to vanilla goals.
    }

    // ------------------------------------------------------------------ vision

    /** Keeps this zombie awake (able to see) for a while after it heard / smelled / felt something. */
    private void wake() {
        awakeUntil = Math.max(awakeUntil, ticks + ZombieAIConfig.AWAKE_SECONDS.get() * 20);
    }

    /**
     * "Still until sound" mode: a zombie that has not heard anything cannot see either. Once woken it can
     * use its eyes (field of view + line of sight as before) for awakeSeconds. Otherwise the old night/rain rule.
     */
    private boolean visionAllowed(ServerLevel level) {
        if (currentKind != null && currentKind.loud() && investigatePos != null) {
            return false;   // heading to a loud noise: what it sees does not matter
        }
        if (ZombieAIConfig.STILL_UNTIL_SOUND.get()) {
            return ticks < awakeUntil;
        }
        return !PerceptionUtil.isDormantTime(level) || ZombieAIConfig.DORMANT_CAN_SEE.get();
    }

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

    /**
     * A zombie that is awake or walking to a sound targets the nearest player within noticeRadius (default 4)
     * that it has a clear line to, no matter who made the sound. Also fixes zombies that stopped next to a
     * player without hitting them (investigation ended before the 1-block eyes could see the player).
     */
    private boolean noticeNearby(ServerLevel level) {
        double radius = ZombieAIConfig.NOTICE_RADIUS.get();
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : level.players()) {
            if (!EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(p) || !p.isAlive()) continue;
            double d = mob.distanceToSqr(p);
            if (d > radius * radius || d >= bestDist || !mob.canAttack(p)) continue;
            if (PerceptionUtil.canSee(mob, p, radius, false)) {
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
        wake();
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
                    && (!visionAllowed(level) || !PerceptionUtil.canSee(mob, player, sight, true))) {
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
        unseenTicks += SIGHT_INTERVAL;
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
        List<Zombie> others = level.getEntitiesOfClass(Zombie.class, box,
                m -> m != mob && m.isAlive() && ZombieScale.applies(m));
        for (Zombie other : others) {
            if (other.distanceToSqr(mob) > radius * radius) continue;
            PerceptionGoal g = of(other);
            if (g != null) {
                g.receiveAlert(rally, jitter);
            }
        }
    }

    /** Called by a neighbour: gather near the given point (approximate), unless already fighting. */
    public void receiveAlert(Vec3 center, double jitter) {
        if (mob.getTarget() != null) return;
        if (investigatePos != null && currentKind != null) return;   // already heading to a noise
        startInvestigateAround(center, jitter);
        firstHeard = -1;
    }
}
