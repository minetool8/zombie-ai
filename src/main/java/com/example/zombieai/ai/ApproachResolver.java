package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Decides WHERE a zombie should walk to when the sound it heard is high above it
 * (player on a roof edge, on a cliff, on a tower...).
 *
 * Vanilla pathfinding toward an unreachable high point gives a "best effort" path that often leads along
 * the building to some far-away edge. Instead:
 *   1. If a real walkable path to the sound exists (stairs, ramps, ...) the zombie just takes it.
 *   2. Otherwise it walks to the ground spot closest to the point right BENEATH the sound,
 *      and WallHopGoal lets it leap up the last blocks if the ledge is low enough.
 */
public final class ApproachResolver {
    /** Only sounds at least this many blocks above the zombie are treated as "high". */
    private static final double HIGH_RISE = 3.0;
    /** How far (blocks, square rings) around the point beneath the sound we look for a standing spot. */
    private static final int SEARCH_RADIUS = 6;
    private static final int CACHE_TICKS = 100;

    private record Key(ResourceKey<Level> dim, long cell) {}
    private record Entry(Vec3 spot, long expires) {}

    private static final Map<Key, Entry> CACHE = new HashMap<>();

    private ApproachResolver() {}

    /** @return the point to walk to; {@code target} itself (same object) when nothing special is needed */
    public static Vec3 resolve(Mob mob, Vec3 target) {
        if (!ZombieAIConfig.SMART_APPROACH.get()) return target;
        if (target.y - mob.getY() < HIGH_RISE) return target;

        Level level = mob.level();
        BlockPos tp = BlockPos.containing(target);

        // 1. A real path up there? Then walk it (stairs, ramps, ladders of blocks...).
        Path direct = mob.getNavigation().createPath(tp, 1);
        if (direct != null && direct.canReach()) return target;

        // 2. Ground spot beneath it (cached: many zombies hear the same sound).
        long now = level.getGameTime();
        Key key = new Key(level.dimension(), BlockPos.asLong(tp.getX() >> 1, tp.getY() >> 1, tp.getZ() >> 1));
        Entry e = CACHE.get(key);
        if (e == null || e.expires < now) {
            Vec3 spot = search(level, mob, tp);
            e = new Entry(spot, now + CACHE_TICKS);
            if (CACHE.size() > 256) prune(now);
            CACHE.put(key, e);
        }
        return e.spot == null ? target : e.spot;
    }

    private static void prune(long now) {
        Iterator<Map.Entry<Key, Entry>> it = CACHE.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expires < now) it.remove();
        }
        if (CACHE.size() > 512) CACHE.clear();
    }

    private static Vec3 search(Level level, Mob mob, BlockPos tp) {
        // Spots higher than the zombie can climb to are useless: only look at its own level and a bit above.
        int top = Math.min(tp.getY() - 2, (int) Math.floor(mob.getY()) + 3);
        int bottom = Math.max(level.getMinBuildHeight() + 1, (int) Math.floor(mob.getY()) - 6);
        for (int r = 0; r <= SEARCH_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // ring only
                    BlockPos spot = standingSpot(level, tp.getX() + dx, tp.getZ() + dz, top, bottom);
                    if (spot != null) return Vec3.atBottomCenterOf(spot);
                }
            }
        }
        return null;
    }

    /** Highest place in this column (between top and bottom) where a zombie can stand. */
    private static BlockPos standingSpot(Level level, int x, int z, int top, int bottom) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = top; y >= bottom; y--) {
            p.set(x, y, z);
            if (!level.hasChunkAt(p)) return null;
            BlockState feet = level.getBlockState(p);
            if (!feet.getCollisionShape(level, p).isEmpty() || !feet.getFluidState().isEmpty()) continue;
            BlockPos head = p.above();
            if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) continue;
            BlockPos below = p.below();
            BlockState floor = level.getBlockState(below);
            if (floor.getCollisionShape(level, below).isEmpty() || !floor.getFluidState().isEmpty()) continue;
            return p.immutable();
        }
        return null;
    }
}
