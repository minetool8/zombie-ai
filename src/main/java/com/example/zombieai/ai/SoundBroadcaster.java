package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Event-driven hearing. Instead of EVERY zombie checking for noise many times a second, each noisy
 * player (talking / walking / sprinting / jumping) and each playing speaker / radio pushes the sound to the
 * zombies in range, once, the moment the noise starts, and then only every few ticks (or when the source
 * has moved a few blocks). Cost depends on the number of noise sources, not on the number of zombies.
 *
 * Every sound is tagged with its NoiseKind (priority) and its effective distance; PerceptionGoal.hear()
 * decides what the zombie does with it.
 */
public final class SoundBroadcaster {
    private static final class Source {
        Vec3 lastPos;
        int nextScan;
    }

    private static final Map<UUID, Source> VOICE = new HashMap<>();
    private static final Map<UUID, Source> STEPS = new HashMap<>();
    private static final Map<String, Source> RADIOS = new HashMap<>();

    private SoundBroadcaster() {}

    public static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        process(HearingManager.speakers(), VOICE, true, now);
        process(MovementNoise.noisyPlayers(), STEPS, false, now);
        processRadios(now);
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

    private static void processRadios(int now) {
        List<RadioSense.Source> active = RadioSense.sources();
        if (active.isEmpty()) {
            if (!RADIOS.isEmpty()) RADIOS.clear();
            return;
        }
        Set<String> keys = new HashSet<>();
        for (RadioSense.Source r : active) {
            String key = r.level().dimension().location() + ":" + (int) Math.floor(r.pos().x / 2.0) + ","
                    + (int) Math.floor(r.pos().y / 2.0) + "," + (int) Math.floor(r.pos().z / 2.0);
            keys.add(key);
            Source s = RADIOS.get(key);
            if (s != null && now < s.nextScan) continue;
            if (s == null) {
                s = new Source();
                RADIOS.put(key, s);
            }
            s.lastPos = r.pos();
            s.nextScan = now + 4;   // same rhythm as voice
            scanRadio(r.level(), r.pos());
        }
        RADIOS.keySet().retainAll(keys);   // a speaker that went quiet is forgotten, its next noise is handled at once
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

        // What kind of noise is this? It decides the priority: shout > normal talking > walking / whisper.
        NoiseKind kind;
        if (voice) {
            kind = switch (HearingManager.tierOf(p.getUUID())) {
                case WHISPER -> NoiseKind.WHISPER;
                case NORMAL -> NoiseKind.VOICE;
                case SHOUT -> NoiseKind.SHOUT;
            };
        } else {
            kind = MovementNoise.isLoud(p.getUUID()) ? NoiseKind.SPRINT : NoiseKind.WALK;
        }
        // Loud noises (talking, shouting, sprinting) can pull a zombie away from a player it is looking at.
        boolean beatsSight = kind.loud() && ZombieAIConfig.LOUD_NOISE_BEATS_SIGHT.get();

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
                goal.hear(p.position(), jitter, false, kind, dist + walls * perWall);
            }
        }
    }

    /** A speaker / radio is playing at pos: every zombie that can hear it gets it as a RADIO noise. */
    private static void scanRadio(ServerLevel level, Vec3 pos) {
        double range = ZombieAIConfig.radioRange() * ZombieAIConfig.rainFactor(level);
        if (range <= 0.0) return;
        double jitter = ZombieAIConfig.INVESTIGATE_RADIUS.get();
        double perWall = ZombieAIConfig.WALL_ATTENUATION.get();
        Vec3 ear = pos.add(0.0, 1.0, 0.0);
        boolean beatsSight = ZombieAIConfig.LOUD_NOISE_BEATS_SIGHT.get();

        for (Zombie z : level.getEntitiesOfClass(Zombie.class, new AABB(pos, pos).inflate(range),
                ZombieScale::applies)) {
            if (!z.isAlive()) continue;
            if (z.getTarget() != null && !beatsSight) continue;
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
                goal.hear(pos, jitter, false, NoiseKind.RADIO, dist + walls * perWall);
            }
        }
    }
}
