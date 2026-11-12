package com.example.zombieai.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Pathfinding fallback. Vanilla pathfinding sometimes refuses to cross blocks that have no collision at all
 * (modded plants, decoration, "air-like" blocks that report themselves as blocked), so a zombie stands still
 * although nothing physically blocks it.
 *
 * When a zombie wants to go somewhere (target or sound) but has not moved for a short while, and the straight
 * line to the goal is physically free (no collision, floor underneath, no lava), it walks straight there with
 * its MoveControl for a moment, ignoring the pathfinder's opinion. Holds no goal flags.
 */
public class UnstickGoal extends Goal {
    private static final int SAMPLE_TICKS = 5;
    private static final int DIRECT_TICKS = 30;

    private final Mob mob;
    private final PerceptionGoal perception;

    private int ticks;
    private Vec3 sampleAt;
    private int stuckSamples;
    private int directLeft;
    private double gx, gy, gz;

    public UnstickGoal(Mob mob, PerceptionGoal perception) {
        this.mob = mob;
        this.perception = perception;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override public boolean canUse() { return true; }
    @Override public boolean canContinueToUse() { return true; }
    @Override public boolean requiresUpdateEveryTick() { return true; }

    private Vec3 goalPoint() {
        LivingEntity target = mob.getTarget();
        if (target != null) return target.position();
        return perception.hasInvestigation() ? perception.approachPos() : null;
    }

    @Override
    public void tick() {
        if (!mob.isAlive() || mob.level().isClientSide()) return;
        ticks++;

        if (directLeft > 0) {
            directLeft--;
            Vec3 goal = goalPoint();
            double dx = gx - mob.getX();
            double dz = gz - mob.getZ();
            if (goal == null || dx * dx + dz * dz < 1.0 || mob.isInWaterOrBubble()) {
                directLeft = 0;
                return;
            }
            mob.getMoveControl().setWantedPosition(gx, gy, gz, 1.0D);
            mob.getLookControl().setLookAt(gx, gy + 1.0, gz);
            if (mob.horizontalCollision && mob.onGround()) {
                mob.getJumpControl().jump();
            }
            return;
        }

        if (ticks % SAMPLE_TICKS != 0) return;
        Vec3 goal = goalPoint();
        if (goal == null) {
            stuckSamples = 0;
            sampleAt = null;
            return;
        }
        Vec3 here = mob.position();
        if (sampleAt == null) {
            sampleAt = here;
            return;
        }
        double mx = here.x - sampleAt.x;
        double mz = here.z - sampleAt.z;
        sampleAt = here;
        double hx = goal.x - here.x;
        double hz = goal.z - here.z;
        boolean moving = mx * mx + mz * mz > 0.08 * 0.08;
        if (moving || hx * hx + hz * hz < 1.5 * 1.5) {
            stuckSamples = 0;
            return;
        }
        if (++stuckSamples < 3) return;   // ~15 ticks standing still
        stuckSamples = 0;
        if (Math.abs(goal.y - here.y) > 1.5 || mob.isPassenger()) return;
        if (!straightLineFree(hx, hz)) return;
        gx = goal.x;
        gy = goal.y;
        gz = goal.z;
        directLeft = DIRECT_TICKS;
    }

    /** True when the zombie could physically walk the straight line: free space, ground below, no lava. */
    private boolean straightLineFree(double hx, double hz) {
        double len = Math.sqrt(hx * hx + hz * hz);
        if (len < 0.5) return false;
        double ux = hx / len;
        double uz = hz / len;
        Level level = mob.level();
        AABB box = mob.getBoundingBox();
        double max = Math.min(len, 16.0);
        for (double d = 0.4; d <= max; d += 0.4) {
            AABB at = box.move(ux * d, 0.0, uz * d);
            if (!level.noCollision(mob, at)) return false;
            BlockPos feet = BlockPos.containing(mob.getX() + ux * d, mob.getY(), mob.getZ() + uz * d);
            if (!level.hasChunkAt(feet)) return false;
            if (level.getFluidState(feet).is(FluidTags.LAVA)) return false;
            boolean ground = false;
            for (int i = 1; i <= 2; i++) {
                BlockPos below = feet.below(i);
                if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                    ground = true;
                    break;
                }
                if (level.getFluidState(below).is(FluidTags.LAVA)) return false;
            }
            if (!ground) return false;
        }
        return true;
    }
}
