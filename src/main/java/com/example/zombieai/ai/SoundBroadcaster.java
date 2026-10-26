package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import com.example.zombieai.ai.HearingManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Event-driven hearing. Instead of EVERY zombie checking for noise many times a second, each noisy
 * player (talking / walking / sprinting / jumping) pushes the sound to the zombies in range, once, the
 * moment the noise starts, and then only every few ticks (or when the player has moved a few blocks).
 * Cost depends on the number of noisy players, not on the number of zombies.
 *
 * Now also supports Simple Voice Radio / Speaker entities.
 */
public final class SoundBroadcaster {
    private static final class Source {
        Vec3 lastPos;
        int nextScan;
    }

    private static final Map<UUID, Source> VOICE = new HashMap<>();
    private static final Map<UUID, Source> STEPS = new HashMap<>();
    private static final Map<UUID, Source> RADIOS = new HashMap<>();

    private static EntityType<?> RADIO_ENTITY_TYPE = null;
    private static boolean RADIO_TYPE_RESOLVED = false;

    private SoundBroadcaster() {}

    public static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        process(HearingManager.speakers(), VOICE, true, now);
        process(MovementNoise.noisyPlayers(), STEPS, false, now);
        if (ZombieAIConfig.radioEnabled()) {
            processRadios(server, now);
        }
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

    /** Resolves the Simple Voice Chat radio/speaker entity type via reflection. */
    private static EntityType<?> getRadioEntityType() {
        if (RADIO_TYPE_RESOLVED) return RADIO_ENTITY_TYPE;
        RADIO_TYPE_RESOLVED = true;

        try {
            // Simple Voice Chat registers entities under "voicechat" namespace
            // Common entity names: "speaker", "radio", "voicechat_speaker"
            String[] possibleNames = {"speaker", "radio", "voicechat_speaker", "voice_chat_speaker"};
            for (String name : possibleNames) {
                ResourceLocation rl = new ResourceLocation("voicechat", name);
                if (BuiltInRegistries.ENTITY_TYPES.containsKey(rl)) {
                    RADIO_ENTITY_TYPE = BuiltInRegistries.ENTITY_TYPES.getValue(rl);
                    break;
                }
            }
            // Also try without namespace (some mods register without namespace)
            if (RADIO_ENTITY_TYPE == null) {
                for (String name : possibleNames) {
                    ResourceLocation rl = new ResourceLocation(name);
                    if (BuiltInRegistries.ENTITY_TYPES.containsKey(rl)) {
                        RADIO_ENTITY_TYPE = BuiltInRegistries.ENTITY_TYPES.getValue(rl);
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
            // Reflection failed, radio support disabled
        }
        return RADIO_ENTITY_TYPE;
    }

    private static void processRadios(MinecraftServer server, int now) {
        EntityType<?> radioType = getRadioEntityType();
        if (radioType == null) return;

        List<Entity> activeRadios = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            activeRadios.addAll(level.getEntitiesOfClass(Entity.class, e -> e.getType() == radioType));
        }

        if (activeRadios.isEmpty()) {
            if (!RADIOS.isEmpty()) RADIOS.clear();
            return;
        }

        // Sources that went quiet are forgotten
        RADIOS.keySet().removeIf(id -> {
            for (Entity e : activeRadios) {
                if (e.getUUID().equals(id)) return false;
            }
            return true;
        });

        for (Entity radio : activeRadios) {
            Source s = RADIOS.get(radio.getUUID());
            Vec3 pos = radio.position();
            if (s != null && now < s.nextScan && pos.distanceToSqr(s.lastPos) < 9.0) continue;
            if (s == null) {
                s = new Source();
                RADIOS.put(radio.getUUID(), s);
            }
            s.lastPos = pos;
            s.nextScan = now + 4; // same as voice
            scanRadio(radio, (ServerLevel) radio.level());
        }
    }

    private static void scanRadio(Entity radio, ServerLevel level) {
        double range = ZombieAIConfig.radioRange() * ZombieAIConfig.rainFactor(level);
        if (range <= 0.0) return;
        double radius = ZombieAIConfig.INVESTIGATE_RADIUS.get();
        double jitter = radius; // radios are heard with same precision as voice
        double perWall = ZombieAIConfig.WALL_ATTENUATION.get();
        Vec3 ear = radio.position().add(0.0, 1.0, 0.0); // radio position + 1 block up

        // Radios are priority 2 (like shouting) if priority boost is enabled
        boolean beatsSight = ZombieAIConfig.radioPriorityBoost() && ZombieAIConfig.LOUD_NOISE_BEATS_SIGHT.get();

        for (Zombie z : level.getEntitiesOfClass(Zombie.class, radio.getBoundingBox().inflate(range),
                ZombieScale::applies)) {
            if (!z.isAlive()) continue;
            LivingEntity target = z.getTarget();
            if (target != null && (!beatsSight || target == radio)) continue;
            double dist = z.distanceTo(radio);
            if (dist > range) continue;
            int walls = 0;
            if (perWall > 0.0) {
                int maxWalls = (int) Math.floor((range - dist) / perWall);
                walls = PerceptionUtil.countOccluders(level, z.getEyePosition(), ear, maxWalls);
                if (walls > maxWalls) continue;
            }
            PerceptionGoal goal = PerceptionGoal.of(z);
            if (goal != null) {
                double audibility = range - (dist + walls * perWall);
                goal.hearRadio(radio.position(), jitter, audibility);
            }
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

        // Determine priority and beatsSight based on voice/movement
        HearingManager.Tier tier = voice ? HearingManager.tierOf(p.getUUID()) : null;
        boolean isSprinting = !voice && MovementNoise.isLoud(p.getUUID());
        boolean beatsSight = false;
        if (voice) {
            beatsSight = (tier == HearingManager.Tier.SHOUT || tier == HearingManager.Tier.NORMAL) && ZombieAIConfig.LOUD_NOISE_BEATS_SIGHT.get();
        } else {
            beatsSight = isSprinting && ZombieAIConfig.LOUD_NOISE_BEATS_SIGHT.get();
        }

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
                double audibility = range - (dist + walls * perWall);
                if (voice) {
                    goal.hear(p.position(), jitter, tier, false, audibility);
                } else {
                    goal.hear(p.position(), jitter, isSprinting, false, audibility);
                }
            }
        }
    }
}
