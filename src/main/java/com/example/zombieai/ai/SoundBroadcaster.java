package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Event-driven hearing. Instead of EVERY zombie checking for noise many times a second, each noisy
 * player (talking / walking / sprinting / jumping) pushes the sound to the zombies in range, once, the
 * moment the noise starts, and then only every few ticks (or when the player has moved a few blocks).
 * Cost depends on the number of noisy players, not on the number of zombies.
 */
public final class SoundBroadcaster {
    private static final class Source {
        Vec3 lastPos;
        int nextScan;
    }

    private static final Map<UUID, Source> VOICE = new HashMap<>();
    private static final Map<UUID, Source> STEPS = new HashMap<>();

    private SoundBroadcaster() {}

    public static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        process(HearingManager.speakers(), VOICE, true, now);
        process(MovementNoise.noisyPlayers(), STEPS, false, now);
    }

    private static void process(List<ServerPlayer> active, Map<UUID, Source> states, boolean voice, int now) {
        if (active.isEmpty()) {
            if (!states.isEmpty()) states.clear();
            return;
        }
        // Sources that went quiet are forgotten, so their NEXT noise is handled at once.
        states.keySet().removeIf(id -> {
            for (ServerPlayer p : active) {
                if (p.getUUID().equals(id)) return false;
            }
            return true;
        });
        for (ServerPlayer p : active) {
            Source s = states.get(p.getUUID());
            Vec3 pos = p.position();
            if (s != null && now < s.nextScan && pos.distanceToSqr(s.lastPos) < 9.0) continue;
            if (s == null) {
                s = new Source();
                states.put(p.getUUID(), s);
            }
            s.lastPos = pos;
            s.nextScan = now + (voice ? 4 : 2);
            scan(p, voice);
        }
    }

    private static void scan(ServerPlayer p, boolean voice) {
        ServerLevel level = p.serverLevel();
        double range = voice ? HearingManager.rangeFor(p, level)
                : MovementNoise.radiusOf(p.getUUID()) * ZombieAIConfig.rainFactor(level);   // rain muffles steps too
        if (range <= 0.0) return;
        double radius = ZombieAIConfig.INVESTIGATE_RADIUS.get();
        double jitter = voice ? radius : Math.min(radius, 1.5);   // footsteps are heard more precisely
        double perWall = ZombieAIConfig.WALL_ATTENUATION.get();
        Vec3 ear = p.getEyePosition();

        // Normal/shouted voice and sprinting are LOUD: they can pull a zombie away from a player it is looking at.
        boolean loud = voice ? HearingManager.tierOf(p.getUUID()) != HearingManager.Tier.WHISPER
                : MovementNoise.isLoud(p.getUUID());
        boolean beatsSight = loud && ZombieAIConfig.LOUD_NOISE_BEATS_SIGHT.get();

        for (Zombie z : level.getEntitiesOfClass(Zombie.class, p.getBoundingBox().inflate(range),
                ZombieScale::applies)) {
            if (!z.isAlive()) continue;
            LivingEntity target = z.getTarget();
            if (target != null && (!beatsSight || target == p)) continue;   // busy, or already after this very player
            double dist = z.distanceTo(p);
            if (dist > range) continue;
            int walls = 0;
            if (perWall > 0.0) {
                int maxWalls = (int) Math.floor((range - dist) / perWall);
                walls = PerceptionUtil.countOccluders(level, z.getEyePosition(), ear, maxWalls);
                if (walls > maxWalls) continue;
            }
            PerceptionGoal goal = PerceptionGoal.of(z);
            if (goal != null) {
                goal.hear(p.position(), jitter, false, loud, range - (dist + walls * perWall));
            }
        }
    }
}
