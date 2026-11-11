package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Speakers, radios and other audio blocks / items that play through Simple Voice Chat.
 *
 * Simple Voice Chat tells our plugin about every sound packet it sends. A packet that does NOT come from a
 * player's microphone (no sender player) is audio made by another mod or plugin: a speaker, a radio, a
 * boombox... The voice plugin reports those here; every tick we turn them into a short list of
 * "this place is making noise right now" sources, which SoundBroadcaster pushes to the zombies in range.
 *
 * Privacy: only a position / entity id is stored. The audio itself is never read.
 * This class has NO voicechat imports, so it loads fine without Simple Voice Chat.
 */
public final class RadioSense {
    /** A place that is playing audio right now. */
    public record Source(ServerLevel level, Vec3 pos) {}

    private enum Mode { LOCATION, ENTITY, STATIC }

    private static final class Raw {
        final Mode mode;
        final UUID ref;          // receiver (LOCATION, STATIC) or entity (ENTITY)
        final double x, y, z;
        volatile long nanos;

        Raw(Mode mode, UUID ref, double x, double y, double z, long nanos) {
            this.mode = mode;
            this.ref = ref;
            this.x = x;
            this.y = y;
            this.z = z;
            this.nanos = nanos;
        }
    }

    private static final long ACTIVE_NANOS = 400_000_000L;   // "playing right now" window
    private static final long PRUNE_NANOS = 5_000_000_000L;

    private static final ConcurrentHashMap<String, Raw> RAW = new ConcurrentHashMap<>();
    private static final AtomicLong PACKETS = new AtomicLong();
    private static volatile List<Source> sources = List.of();

    private RadioSense() {}

    // ------------------------------------------------------------ voice thread

    /** A locational sound (speaker block, radio at a fixed point) played for this receiver. */
    public static void markLocation(UUID receiver, double x, double y, double z) {
        String key = "L" + (int) Math.floor(x / 2.0) + "," + (int) Math.floor(y / 2.0) + "," + (int) Math.floor(z / 2.0);
        put(key, Mode.LOCATION, receiver, x, y, z);
    }

    /** A sound attached to an entity (a carried radio, a mob...). */
    public static void markEntity(UUID entityId) {
        put("E" + entityId, Mode.ENTITY, entityId, 0, 0, 0);
    }

    /** A non-positional plugin sound sent to one player: it plays "where that player is". */
    public static void markStatic(UUID receiver) {
        put("S" + receiver, Mode.STATIC, receiver, 0, 0, 0);
    }

    private static void put(String key, Mode mode, UUID ref, double x, double y, double z) {
        PACKETS.incrementAndGet();
        long now = System.nanoTime();
        Raw r = RAW.get(key);
        if (r != null && r.mode == mode && r.ref.equals(ref)) {
            r.nanos = now;
        } else {
            RAW.put(key, new Raw(mode, ref, x, y, z, now));
        }
    }

    // --------------------------------------------------------------- main thread

    /** Called on the main server thread every tick. */
    public static void refresh(MinecraftServer server) {
        if (RAW.isEmpty()) {
            if (!sources.isEmpty()) sources = List.of();
            return;
        }
        long now = System.nanoTime();
        RAW.values().removeIf(r -> now - r.nanos > PRUNE_NANOS);
        if (!ZombieAIConfig.radioEnabled()) {
            sources = List.of();
            return;
        }

        List<Source> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Raw r : RAW.values()) {
            if (now - r.nanos > ACTIVE_NANOS) continue;
            ServerLevel level = null;
            Vec3 pos = null;
            if (r.mode == Mode.ENTITY) {
                for (ServerLevel l : server.getAllLevels()) {
                    Entity e = l.getEntity(r.ref);
                    if (e != null) {
                        level = l;
                        pos = e.position();
                        break;
                    }
                }
            } else {
                ServerPlayer p = server.getPlayerList().getPlayer(r.ref);
                if (p != null) {
                    level = p.serverLevel();
                    pos = r.mode == Mode.LOCATION ? new Vec3(r.x, r.y, r.z) : p.position();
                }
            }
            if (level == null || pos == null) continue;
            // One source per 2-block cell, so one speaker heard by 10 players is still one speaker.
            String cell = level.dimension().location() + ":" + (int) Math.floor(pos.x / 2.0) + ","
                    + (int) Math.floor(pos.y / 2.0) + "," + (int) Math.floor(pos.z / 2.0);
            if (seen.add(cell)) {
                list.add(new Source(level, pos));
            }
        }
        sources = List.copyOf(list);
    }

    public static List<Source> sources() {
        return sources;
    }

    /** How many speaker / radio sound packets the server has seen so far (for /zombieai debug). */
    public static long totalPackets() {
        return PACKETS.get();
    }

    public static void forget(UUID playerId) {
        RAW.values().removeIf(r -> r.mode != Mode.ENTITY && r.ref.equals(playerId));
    }
}
