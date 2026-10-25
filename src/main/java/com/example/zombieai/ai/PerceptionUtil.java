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
     * Vision check: within range, (optionally) inside the mob's field of view, and an unobstructed
     * visual ray from the mob's eyes to one of three points on the target.
     * VISUAL clipping lets sight pass through glass. Hearing never calls this.
     *
     * @param useFov true when first noticing someone (must be in front of the zombie);
     *               false when it is already tracking the target (it turns to follow it).
     */
    public static boolean canSee(Mob mob, LivingEntity target, double sightDistance, boolean useFov) {
        double range = sightDistance;
        if (ZombieAIConfig.USE_VISIBILITY_MODIFIERS.get()) {
            double vis = target.getVisibilityPercent(mob);   // invisibility, mob heads, ...
            if (target.isDiscrete()) {
                vis /= 0.8;                                  // drop vanilla's own sneak factor, we use ours below
            }
            range *= vis;
        }
        if (target.isDiscrete()) {
            range *= ZombieAIConfig.SNEAK_SIGHT_MULTIPLIER.get();   // sneaking: only ~10% range, but not invisible
        }
        if (mob.distanceToSqr(target) > range * range) {
            return false;
        }
        Level level = mob.level();
        Vec3 from = mob.getEyePosition();
        Vec3 view = mob.getViewVector(1.0F);   // where the head is facing
        double cosHalfFov = Math.cos(Math.toRadians(ZombieAIConfig.FIELD_OF_VIEW.get() * 0.5));

        Vec3[] points = {
                target.getEyePosition(),
                target.position().add(0, target.getBbHeight() * 0.5, 0),
                target.position().add(0, 0.2, 0)
        };
        for (Vec3 to : points) {
            if (useFov) {
                Vec3 dir = to.subtract(from);
                double len = dir.length();
                if (len > 1.0E-4 && view.dot(dir) / len < cosHalfFov) {
                    continue; // outside the field of view
                }
            }
            BlockHitResult hit = level.clip(new ClipContext(from, to,
                    ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mob));
            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    /** True while idle zombies should be frozen: night or rain (only in dimensions that have them). */
    public static boolean isDormantTime(Level level) {
        if (ZombieAIConfig.DORMANT_IN_RAIN.get() && level.dimensionType().hasSkyLight() && level.isRaining()) {
            return true;
        }
        if (ZombieAIConfig.DORMANT_AT_NIGHT.get() && !level.dimensionType().hasFixedTime()) {
            long t = level.getDayTime() % 24000L;
            return t >= 13000L && t < 23000L;
        }
        return false;
    }

    /** True while idle zombies must stand completely still: always in "still until sound" mode, else night/rain. */
    public static boolean isStillTime(Level level) {
        return ZombieAIConfig.STILL_UNTIL_SOUND.get() || isDormantTime(level);
    }

    /** Counts opaque solid blocks along the straight line between two points (for sound muffling). */
    public static int countOccluders(Level level, Vec3 from, Vec3 to) {
        return countOccluders(level, from, to, Integer.MAX_VALUE);
    }

    /** Same, but stops counting as soon as the result is above maxCount (saves block lookups). */
    public static int countOccluders(Level level, Vec3 from, Vec3 to, int maxCount) {
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
                if (count > maxCount) return count;
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isSolidRender(level, pos)) {
                count++;
                if (count > maxCount) return count;
            }
        }
        return count;
    }
}
