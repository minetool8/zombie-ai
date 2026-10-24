package com.example.zombieai.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.UUID;

/**
 * Height is derived deterministically from the entity UUID, so server and client agree,
 * it survives save/load, and it can never be re-rolled.
 *
 * A vanilla zombie is 1.95 blocks tall. Distribution:
 *   95%  "normal": 1.60 - 1.99 blocks tall (all under 2 blocks, scale ~0.82 - 1.02)
 *    5%  "tall"  : 2.05 - 2.925 blocks tall (scale ~1.05 - 1.5, never above 1.5x)
 */
public final class ZombieScale {
    public static final float VANILLA_HEIGHT = 1.95f;
    public static final double TALL_CHANCE = 0.05;

    private static final float NORMAL_MIN_HEIGHT = 1.60f;
    private static final float NORMAL_MAX_HEIGHT = 1.99f;
    private static final float TALL_MIN_HEIGHT = 2.05f;
    private static final float TALL_MAX_HEIGHT = VANILLA_HEIGHT * 1.5f;

    private ZombieScale() {}

    public static boolean applies(Entity e) {
        EntityType<?> t = e.getType();
        return t == EntityType.ZOMBIE || t == EntityType.HUSK;
    }

    private static double unit(UUID id, long salt) {
        long h = id.getMostSignificantBits() * 0x9E3779B97F4A7C15L ^ id.getLeastSignificantBits()
                ^ (salt * 0xD6E8FEB86659FD93L);
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53; // [0,1)
    }

    /** True for the ~5% of zombies that are taller than 2 blocks. */
    public static boolean isTall(Entity e) {
        return unit(e.getUUID(), 0) < TALL_CHANCE;
    }

    public static float of(Entity e) {
        UUID id = e.getUUID();
        double pick = unit(id, 1);
        float min = isTall(e) ? TALL_MIN_HEIGHT : NORMAL_MIN_HEIGHT;
        float max = isTall(e) ? TALL_MAX_HEIGHT : NORMAL_MAX_HEIGHT;
        float height = (float) (min + (max - min) * pick);
        float scale = height / VANILLA_HEIGHT;
        return Math.max(0.8f, Math.min(1.5f, scale));
    }
}
