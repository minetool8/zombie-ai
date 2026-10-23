package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class PerceptionUtil {
    private PerceptionUtil() {}

    /**
     * Pure line-of-sight check: within range AND an unobstructed visual ray from the mob's eyes
     * to one of three points on the target. VISUAL clipping lets sight pass through glass.
     * Hearing never calls this and never grants vision.
     */
    public static boolean canSee(Mob mob, LivingEntity target, double sightDistance) {
        double range = sightDistance;
        if (ZombieAIConfig.USE_VISIBILITY_MODIFIERS.get()) {
            range *= target.getVisibilityPercent(mob);
        }
        if (mob.distanceToSqr(target) > range * range) {
            return false;
        }
        Level level = mob.level();
        Vec3 from = mob.getEyePosition();
        Vec3[] points = {
                target.getEyePosition(),
                target.position().add(0, target.getBbHeight() * 0.5, 0),
                target.position().add(0, 0.2, 0)
        };
        for (Vec3 to : points) {
            BlockHitResult hit = level.clip(new ClipContext(from, to,
                    ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mob));
            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    /** Counts opaque solid blocks along the straight line between two points (for sound muffling). */
    public static int countOccluders(Level level, Vec3 from, Vec3 to) {
        double length = from.distanceTo(to);
        int steps = (int) Math.ceil(length * 2.0);
        if (steps < 2) {
            return 0;
        }
        Vec3 delta = to.subtract(from).scale(1.0 / steps);
        BlockPos last = null;
        int count = 0;
        for (int i = 1; i < steps; i++) {
            Vec3 p = from.add(delta.scale(i));
            BlockPos pos = BlockPos.containing(p);
            if (pos.equals(last)) {
                continue;
            }
            last = pos;
            if (!level.hasChunkAt(pos)) {
                count++; // unloaded = treat as solid, and never force-load chunks
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isSolidRender(level, pos)) {
                count++;
            }
        }
        return count;
    }
}
