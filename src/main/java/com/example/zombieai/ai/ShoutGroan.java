package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * When a player SHOUTS and zombies far away (beyond normal talking range) hear it, every player inside
 * the shout radius hears loud zombie growls coming from the direction of those far zombies.
 * If only nearby zombies hear the shout, there is no growl.
 */
public final class ShoutGroan {
    /** The growl is placed at most this far from each listener so it is loud, but still sounds "from afar". */
    private static final double APPARENT_DISTANCE = 32.0;

    private static final Map<UUID, Integer> NEXT_ALLOWED = new HashMap<>();

    private ShoutGroan() {}

    public static void forget(UUID id) {
        NEXT_ALLOWED.remove(id);
    }

    public static void tick(MinecraftServer server) {
        if (!ZombieAIConfig.SHOUT_GROWL_ENABLED.get()) return;
        int now = server.getTickCount();

        for (ServerPlayer shouter : HearingManager.speakers()) {
            if (HearingManager.tierOf(shouter.getUUID()) != HearingManager.Tier.SHOUT) continue;
            Integer next = NEXT_ALLOWED.get(shouter.getUUID());
            if (next != null && now < next) continue;

            ServerLevel level = shouter.serverLevel();
            double shoutRange = HearingManager.rangeFor(shouter, level);
            double normalRange = ZombieAIConfig.hearing(level);
            if (shoutRange <= normalRange) {
                NEXT_ALLOWED.put(shouter.getUUID(), now + 20);   // rain: shout no louder than talking
                continue;
            }

            // Zombies that hear the shout from BEYOND normal talking range.
            double perWall = ZombieAIConfig.WALL_ATTENUATION.get();
            List<Zombie> far = new ArrayList<>();
            for (Zombie m : level.getEntitiesOfClass(Zombie.class, shouter.getBoundingBox().inflate(shoutRange),
                    ZombieScale::applies)) {
                double dist = m.distanceTo(shouter);
                if (dist <= normalRange || dist > shoutRange) continue;
                int maxWalls = perWall > 0.0 ? (int) Math.floor((shoutRange - dist) / perWall) : Integer.MAX_VALUE;
                if (PerceptionUtil.countOccluders(level, m.getEyePosition(), shouter.getEyePosition(), maxWalls) <= maxWalls) {
                    far.add(m);
                }
            }
            if (far.isEmpty()) {
                NEXT_ALLOWED.put(shouter.getUUID(), now + 20);   // only close zombies heard it: no growl
                continue;
            }
            NEXT_ALLOWED.put(shouter.getUUID(), now + ZombieAIConfig.SHOUT_GROWL_COOLDOWN_TICKS.get());

            Collections.shuffle(far);   // different zombies -> growls from different directions
            List<Zombie> chosen = far.subList(0, Math.min(ZombieAIConfig.SHOUT_GROWL_MAX_ZOMBIES.get(), far.size()));
            for (ServerPlayer listener : level.players()) {
                if (listener.distanceTo(shouter) > shoutRange) continue;   // everyone inside the shout radius
                for (Zombie zombie : chosen) {
                    playFrom(level, listener, zombie.position());
                }
            }
        }
    }

    private static void playFrom(ServerLevel level, ServerPlayer listener, Vec3 zombiePos) {
        Vec3 from = listener.position();
        Vec3 dir = zombiePos.subtract(from);
        double d = dir.length();
        if (d < 1.0E-3) return;
        Vec3 at = from.add(dir.scale(Math.min(d, APPARENT_DISTANCE) / d));
        float volume = ZombieAIConfig.SHOUT_GROWL_VOLUME.get().floatValue();
        Holder<SoundEvent> sound = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.ZOMBIE_AMBIENT);
        listener.connection.send(new ClientboundSoundPacket(sound, SoundSource.HOSTILE,
                at.x, at.y, at.z, volume, 0.5F, level.getRandom().nextLong()));
    }
}
