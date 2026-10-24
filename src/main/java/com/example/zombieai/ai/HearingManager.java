package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Voice hearing.
 *
 * Privacy: for every 20 ms voice frame the plugin computes ONE number (loudness in dB) and throws the
 * audio away immediately. No audio, no words and no recordings are ever stored or sent anywhere.
 * What is kept: "player X sent a voice frame at time T" and "their loudest frame in the last 0.3 s".
 *
 * Voice packets arrive on voicechat's own thread, so markSpeaking() only touches concurrent maps.
 * The main server thread turns that into a snapshot via refresh().
 * This class has NO voicechat imports, so it loads fine without Simple Voice Chat.
 */
public final class HearingManager {
    public enum Tier { WHISPER, NORMAL, SHOUT }

    private static final long ACTIVE_WINDOW_NANOS = 400_000_000L;   // "currently talking" window
    private static final long PRUNE_NANOS = 5_000_000_000L;
    private static final long PEAK_HOLD_NANOS = 300_000_000L;      // loudest frame of the last 0.3 s counts

    private static final class LoudnessState {
        volatile double peakDb = -100.0;
        volatile long peakNanos;

        synchronized void add(double db, long now) {
            if (db >= peakDb || now - peakNanos > PEAK_HOLD_NANOS) {
                peakDb = db;
                peakNanos = now;
            }
        }
    }

    private static final ConcurrentHashMap<UUID, Long> LAST_MIC_PACKET = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, LoudnessState> LEVELS = new ConcurrentHashMap<>();
    private static final Set<UUID> METER = ConcurrentHashMap.newKeySet();
    private static final AtomicLong TOTAL_PACKETS = new AtomicLong();

    private static volatile List<ServerPlayer> speakers = List.of();
    public static volatile boolean voiceChatPluginLoaded = false;

    private HearingManager() {}

    // ------------------------------------------------------------ voice thread

    public static void markSpeaking(UUID playerId) {
        markSpeaking(playerId, Double.NaN);
    }

    /** @param db loudness of this frame in dBFS (0 = loudest possible, -100 = silence), NaN if unknown */
    public static void markSpeaking(UUID playerId, double db) {
        long now = System.nanoTime();
        TOTAL_PACKETS.incrementAndGet();
        LAST_MIC_PACKET.put(playerId, now);
        if (!Double.isNaN(db)) {
            LEVELS.computeIfAbsent(playerId, k -> new LoudnessState()).add(db, now);
        }
    }

    public static void forget(UUID playerId) {
        LAST_MIC_PACKET.remove(playerId);
        LEVELS.remove(playerId);
        METER.remove(playerId);
    }

    // --------------------------------------------------------------- main thread

    /** Called on the main server thread every tick. */
    public static void refresh(MinecraftServer server) {
        long now = System.nanoTime();
        List<ServerPlayer> list = new ArrayList<>();
        if (!LAST_MIC_PACKET.isEmpty()) {
            LAST_MIC_PACKET.values().removeIf(t -> now - t > PRUNE_NANOS);
            LEVELS.values().removeIf(s -> now - s.peakNanos > PRUNE_NANOS);
            LAST_MIC_PACKET.forEach((id, t) -> {
                if (now - t <= ACTIVE_WINDOW_NANOS) {
                    ServerPlayer p = server.getPlayerList().getPlayer(id);
                    if (p != null && p.isAlive() && !p.isSpectator() && !p.isCreative()) {
                        list.add(p);
                    }
                }
            });
        }
        speakers = List.copyOf(list);

        if (!METER.isEmpty() && server.getTickCount() % 4 == 0) {
            showMeters(server, now);
        }
    }

    public static List<ServerPlayer> speakers() {
        return speakers;
    }

    // ------------------------------------------------------------- loudness tiers

    public static Tier tierFromDb(double db) {
        if (db < ZombieAIConfig.WHISPER_MAX_DB.get()) return Tier.WHISPER;
        if (db >= ZombieAIConfig.SHOUT_MIN_DB.get()) return Tier.SHOUT;
        return Tier.NORMAL;
    }

    public static Tier tierOf(UUID playerId) {
        if (!ZombieAIConfig.LOUDNESS_ENABLED.get()) return Tier.NORMAL;
        LoudnessState s = LEVELS.get(playerId);
        if (s == null) return Tier.NORMAL;   // no measurement available -> counts as normal talking
        return tierFromDb(s.peakDb);
    }

    /** How far (blocks) zombies can hear this player's voice right now. Rain shortens every tier. */
    public static double rangeFor(ServerPlayer player, Level level) {
        double talking = ZombieAIConfig.hearing();
        double base = switch (tierOf(player.getUUID())) {
            case WHISPER -> Math.min(ZombieAIConfig.WHISPER_RANGE.get(), talking);
            case NORMAL -> talking;
            case SHOUT -> Math.max(ZombieAIConfig.SHOUT_RANGE.get(), talking);
        };
        return base * ZombieAIConfig.rainFactor(level);
    }

    // ----------------------------------------------------------------- debug tools

    /** Toggles the live mic meter above the player's hotbar. Returns true if it is now on. */
    public static boolean toggleMeter(UUID playerId) {
        if (METER.remove(playerId)) return false;
        METER.add(playerId);
        return true;
    }

    private static void showMeters(MinecraftServer server, long now) {
        for (UUID id : METER) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            LoudnessState s = LEVELS.get(id);
            Long last = LAST_MIC_PACKET.get(id);
            String text;
            if (s == null || last == null || now - last > 1_000_000_000L) {
                text = "Mic: silent";
            } else {
                text = String.format(Locale.ROOT, "Mic: %.0f dB  =  %s  (zombies hear %.0f blocks)",
                        s.peakDb, tierFromDb(s.peakDb), rangeFor(p, p.level()));
            }
            p.displayClientMessage(Component.literal(text), true);
        }
    }

    public static long totalPackets() {
        return TOTAL_PACKETS.get();
    }

    public static long millisSinceLastPacket(UUID playerId) {
        Long t = LAST_MIC_PACKET.get(playerId);
        return t == null ? -1 : (System.nanoTime() - t) / 1_000_000L;
    }
}
