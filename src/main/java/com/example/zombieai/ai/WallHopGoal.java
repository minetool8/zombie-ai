package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Lets a zombie that is stuck at a wall leap onto the ledge in front of it (up to wallHopMaxHeight blocks).
 *
 * It holds NO goal flags, so it runs next to whatever else the zombie is doing (chasing, investigating).
 * It only acts when the zombie WANTS to go somewhere (target or investigation point) but has hardly moved
 * for a short while, and a landing spot exists on top of the obstacle in front of it.
 */
public class WallHopGoal extends Goal {
    /** Upward launch speed (blocks/tick) to clear a ledge of height 1..4 with ~0.35 blocks to spare. */
    private static final double[] HOP_V = {0.0, 0.465, 0.598, 0.727, 0.841};
    /** Zombies only leap onto a 1-block ledge (jump apex ~1.5 blocks). Fences / walls (1.5 tall collision) stay impassable. */
    private static final int HARD_MAX_HEIGHT = 1;
    private static final int SAMPLE_TICKS = 5;
    private static final int AIR_ASSIST_TICKS = 24;
    /** Directions tried, as angle offsets (degrees) from the direction to the goal. */
    private static final double[] ANGLES = {0.0, 35.0, -35.0, 70.0, -70.0};

    private final Mob mob;
    private final PerceptionGoal perception;

    private int ticks;
    private Vec3 sampleAt;
    private int stuckSamples;
    private int cooldown;
    private int airLeft;
    private double pushX;
    private double pushZ;

    public WallHopGoal(Mob mob, PerceptionGoal perception) {
        this.mob = mob;
        this.perception = perception;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override public boolean canUse() { return true; }
    @Override public boolean canContinueToUse() { return true; }
    @Override public boolean requiresUpdateEveryTick() { return true; }

    /** Where the zombie is trying to get to right now (null = nowhere). */
    private Vec3 goalPoint() {
        LivingEntity target = mob.getTarget();
        if (target != null) return target.position();
        Vec3 sound = perception.investigatePos();
        if (sound == null) return null;
        // Standing beneath a high sound: leap toward the sound itself. Otherwise toward the spot it walks to.
        double dx = sound.x - mob.getX();
        double dz = sound.z - mob.getZ();
        if (dx * dx + dz * dz < 9.0) return sound;
        return perception.approachPos();
    }

    @Override
    public void tick() {
        if (!mob.isAlive() || mob.level().isClientSide() || !ZombieAIConfig.WALL_HOP_ENABLED.get()) return;
        ticks++;
        if (cooldown > 0) cooldown--;

        if (airLeft > 0) {
            assistInAir();
            return;
        }

        Vec3 goal = goalPoint();
        if (goal == null) {
            stuckSamples = 0;
            sampleAt = null;
            return;
        }
        if (ticks % SAMPLE_TICKS != 0) return;

        Vec3 here = mob.position();
        if (sampleAt == null) {
            sampleAt = here;
            return;
        }
        double mx = here.x - sampleAt.x;
        double mz = here.z - sampleAt.z;
        sampleAt = here;

        boolean moving = mx * mx + mz * mz > 0.08 * 0.08;
        if (moving || here.distanceTo(goal) < 1.6) {
            stuckSamples = 0;
            return;
        }
        if (++stuckSamples < 2 || cooldown > 0) return;
        stuckSamples = 0;
        if (!mob.onGround() || mob.isPassenger() || mob.isInWaterOrBubble()) return;

        if (tryHop(goal.x - here.x, goal.z - here.z)) {
            cooldown = 40;
        } else {
            cooldown = 15;
        }
    }

    private boolean tryHop(double gx, double gz) {
        double len = Math.sqrt(gx * gx + gz * gz);
        double dx;
        double dz;
        if (len < 0.3) {
            Vec3 view = mob.getViewVector(1.0F);   // right below the goal: leap the way it is facing
            double vl = Math.sqrt(view.x * view.x + view.z * view.z);
            if (vl < 1.0E-3) return false;
            dx = view.x / vl;
            dz = view.z / vl;
        } else {
            dx = gx / len;
            dz = gz / len;
        }
        int maxH = Math.min(HARD_MAX_HEIGHT, ZombieAIConfig.WALL_HOP_MAX_HEIGHT.get());
        for (double angle : ANGLES) {
            double rad = Math.toRadians(angle);
            double rx = dx * Math.cos(rad) - dz * Math.sin(rad);
            double rz = dx * Math.sin(rad) + dz * Math.cos(rad);
            int h = findLedge(rx, rz, maxH);
            if (h > 0) {
                launch(rx, rz, h);
                return true;
            }
        }
        return false;
    }

    /**
     * @return height (1..maxH) of the ledge in front of the zombie in direction (dx,dz) that it can land on,
     *         or 0 when there is no wall in front / nothing to land on.
     */
    private int findLedge(double dx, double dz, int maxH) {
        Level level = mob.level();
        AABB box = mob.getBoundingBox();
        double reach = mob.getBbWidth() * 0.5 + 0.7;
        double ox = dx * reach;
        double oz = dz * reach;

        // Nothing blocking at our own height: not a wall problem.
        if (level.noCollision(mob, box.move(ox, 0.05, oz))) return 0;

        for (int h = 1; h <= maxH; h++) {
            // Room to rise h blocks straight up (no overhang, no ceiling).
            if (!level.noCollision(mob, box.expandTowards(0.0, h + 0.05, 0.0))) return 0;
            // Free space on top of the obstacle...
            if (!level.noCollision(mob, box.move(ox, h + 0.05, oz))) continue;
            // ...with something solid to stand on.
            if (level.noCollision(mob, box.move(ox, h - 0.1, oz))) continue;
            return h;
        }
        return 0;
    }

    private void launch(double dx, double dz, int h) {
        double horizontal = 0.20 + 0.03 * h;
        mob.setDeltaMovement(dx * horizontal, HOP_V[h], dz * horizontal);
        mob.hasImpulse = true;
        pushX = dx;
        pushZ = dz;
        airLeft = AIR_ASSIST_TICKS;
    }

    /** Keeps a little forward speed while airborne so the zombie really lands on the ledge, not against its face. */
    private void assistInAir() {
        airLeft--;
        if (airLeft < AIR_ASSIST_TICKS - 4 && mob.onGround()) {
            airLeft = 0;
            return;
        }
        Vec3 d = mob.getDeltaMovement();
        if (d.x * d.x + d.z * d.z < 0.12 * 0.12) {
            mob.setDeltaMovement(d.x + pushX * 0.04, d.y, d.z + pushZ * 0.04);
        }
    }
}
