package com.example.zombieai.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.UUID;

/**
 * Size is derived deterministically from the entity UUID (uniform 1.0 - 1.5).
 * Server and client compute the same value, so no networking is needed, it survives
 * chunk save/load automatically, and it can never be re-rolled.
 */
public final class ZombieScale {
    public static final float MIN = 1.0f;
    public static final float MAX = 1.5f;

    private ZombieScale() {}

    public static boolean applies(Entity e) {
        EntityType<?> t = e.getType();
        return t == EntityType.ZOMBIE || t == EntityType.HUSK;
    }

    public static float of(Entity e) {
        UUID id = e.getUUID();
        long h = id.getMostSignificantBits() * 0x9E3779B97F4A7C15L ^ id.getLeastSignificantBits();
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        double unit = (h >>> 11) * 0x1.0p-53; // [0,1)
        float scale = (float) (MIN + (MAX - MIN) * unit);
        return Math.max(MIN, Math.min(MAX, scale));
    }
}
