package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Whenever Zombie Awareness creates a NEW sound (gunshots from the TACZ add-on, mining, doors...), every
 * zombie that can hear it turns and walks to it at once, even if it was already on its way to an older
 * sound. So: shot at point A, then immediately a shot at point B -> they drop A and go to B.
 * Works without Zombie Awareness being a compile dependency: the sound is found by its entity type.
 */
public final class SoundRedirect {
    private static final int SOUND_TYPE = 1;   // Zombie Awareness EnumSenseType.SOUND

    private static final List<Entity> PENDING = new ArrayList<>();

    private SoundRedirect() {}

    public static void onEntityJoin(Entity entity) {
        EntityType<?> type = BloodSense.scentType();
        if (type != null && entity.getType() == type && ZombieAIConfig.REDIRECT_TO_NEWEST_SOUND.get()) {
            PENDING.add(entity);   // handled next tick, when the entity is fully set up
        }
    }

    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        List<Entity> batch = new ArrayList<>(PENDING);
        PENDING.clear();
        for (Entity scent : batch) {
            if (!scent.isRemoved() && scent.level() instanceof ServerLevel level) {
                handle(level, scent);
            }
        }
    }

    private static void handle(ServerLevel level, Entity scent) {
        CompoundTag tag = scent.saveWithoutId(new CompoundTag());
        if (tag.getInt("type") != SOUND_TYPE) return;

        // Same range rule as Zombie Awareness (strength / 100 * range scale), shortened by rain.
        double range = tag.getInt("strengthpeak") / 100.0 * ZombieAIConfig.BLOOD_SMELL_RANGE.get()
                * ZombieAIConfig.rainFactor(level);
        if (range <= 0.0) return;

        Vec3 pos = scent.position();
        Vec3 ear = pos.add(0.0, 1.0, 0.0);
        double perWall = ZombieAIConfig.WALL_ATTENUATION.get();
        double jitter = Math.min(ZombieAIConfig.INVESTIGATE_RADIUS.get(), 3.0);

        boolean beatsSight = ZombieAIConfig.LOUD_NOISE_BEATS_SIGHT.get();   // Zombie Awareness sounds are LOUD
        for (Zombie z : level.getEntitiesOfClass(Zombie.class, new AABB(pos, pos).inflate(range),
                ZombieScale::applies)) {
            if (!z.isAlive() || (z.getTarget() != null && !beatsSight)) continue;
            double dist = z.position().distanceTo(pos);
            if (dist > range) continue;
            int walls = 0;
            if (perWall > 0.0) {
                int maxWalls = (int) Math.floor((range - dist) / perWall);
                walls = PerceptionUtil.countOccluders(level, z.getEyePosition(), ear, maxWalls);
                if (walls > maxWalls) continue;
            }
            PerceptionGoal goal = PerceptionGoal.of(z);
            if (goal != null) {
                goal.hear(pos, jitter, true, true, range - (dist + walls * perWall));   // instant: newest sound wins
            }
        }
    }
}
