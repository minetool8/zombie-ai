package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Voice hearing.
 *
 * Privacy: for every 20 ms voice frame the plugin computes ONE number (loudness) and throws the
 * audio away immediately. No audio, no words and no recordings are ever stored or sent anywhere.
 * What is kept: "player X sent a voice frame at time T" and a smoothed (about 125 ms) loudness number.
 *
 * Loudness scale: the plugin measures the digital level of the mic signal (dBFS, 0 = loudest, negative below).
 * A game cannot know real sound pressure, so we show it on a familiar "dB" scale:
 *     dB = dBFS + micOffsetDecibels      (set the offset once with /zombieai calibrate)
 *
 * Voice packets arrive on voicechat's own thread, so markSpeaking() only touches concurrent maps.
 * This class has NO voicechat imports, so it loads fine without Simple Voice Chat.
 */
public final class HearingManager {
    public enum Tier { WHISPER, NORMAL, SHOUT }

    private static final long ACTIVE_WINDOW_NANOS = 400_000_000L;   // "currently talking" window
    private static final long PRUNE_NANOS = 5_000_000_000L;
    private static final double SMOOTHING_ALPHA = 0.15;            // about 125 ms response, like a sound meter's "fast" mode
    private static final long RESET_NANOS = 400_000_000L;          // a pause longer than this starts a new utterance

    /** Calibration: the player's normal speaking voice is mapped to this many dB. */
    public static final double CALIBRATION_TARGET_DB = 85.0;
    private static final int CALIBRATION_TICKS = 100;               // 5 seconds

    /** Smoothed loudness. (Field names kept: peakDb = current smoothed level in dBFS, peakNanos = last frame time.) */
    private static final class LoudnessState {
        volatile double peakDb = -100.0;
        volatile long peakNanos;
        private double smoothedPower;

        synchronized void add(double dbfs, long now) {
            double power = Math.pow(10.0, dbfs / 10.0);
            if (now - peakNanos > RESET_NANOS) {
                smoothedPower = power;                                   // new utterance
            } else {
                smoothedPower += SMOOTHING_ALPHA * (power - smoothedPower);   // average power, like a sound meter
            }
            peakNanos = now;
            peakDb = 10.0 * Math.log10(Math.max(smoothedPower, 1.0E-12));
        }
    }

    private static final class Calibration {
        final int startTick;
        final double target;
        double sum;
        int count;

        Calibration(int startTick, double target) {
            this.startTick = startTick;
            this.target = target;
        }
    }

    private static final ConcurrentHashMap<UUID, Long> LAST_MIC_PACKET = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, LoudnessState> LEVELS = new ConcurrentHashMap<>();
    private static final Set<UUID> METER = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Calibration> CALIBRATING = new ConcurrentHashMap<>();
    private static final AtomicLong TOTAL_PACKETS = new AtomicLong();

    private static volatile List<ServerPlayer> speakers = List.of();
    public static volatile boolean voiceChatPluginLoaded = false;

    private HearingManager() {}

    // ------------------------------------------------------------ voice thread

    public static void markSpeaking(UUID playerId) {
        markSpeaking(playerId, Double.NaN);
    }

    /** @param dbfs digital level of this frame (0 = loudest possible, -100 = silence), NaN if unknown */
    public static void markSpeaking(UUID playerId, double dbfs) {
        long now = System.nanoTime();
        TOTAL_PACKETS.incrementAndGet();
        LAST_MIC_PACKET.put(playerId, now);
        if (!Double.isNaN(dbfs)) {
            LEVELS.computeIfAbsent(playerId, k -> new LoudnessState()).add(dbfs, now);
        }
    }

    public static void forget(UUID playerId) {
        LAST_MIC_PACKET.remove(playerId);
        LEVELS.remove(playerId);
        METER.remove(playerId);
        CALIBRATING.remove(playerId);
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
        if (!CALIBRATING.isEmpty()) {
            tickCalibration(server, now);
        }
    }

    public static List<ServerPlayer> speakers() {
        return speakers;
    }

    // ------------------------------------------------------------- loudness tiers

    /** Converts the digital mic level to the "dB" scale used by the config (0..~140). */
    public static double toDecibels(double dbfs) {
        return dbfs + ZombieAIConfig.MIC_OFFSET_DB.get();
    }

    /** Whisper: up to whisperMaxDecibels. Shout: above shoutMinDecibels. Everything in between is normal talking. */
    public static Tier tierFromDb(double dbfs) {
        double db = toDecibels(dbfs);
        if (db < ZombieAIConfig.WHISPER_MAX_DB.get()) return Tier.WHISPER;
        if (db > ZombieAIConfig.SHOUT_MIN_DB.get()) return Tier.SHOUT;
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
        return base * ZombieAIConfig.rainFactor(level);   // rain muffling (default -80%) applies to every tier
    }

    // ----------------------------------------------------------------- meter / calibration

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
                        toDecibels(s.peakDb), tierFromDb(s.peakDb), rangeFor(p, p.level()));
            }
            p.displayClientMessage(Component.literal(text), true);
        }
    }

    /** Starts a 5 second calibration: the player's steady voice is mapped to targetDb. */
    public static void startCalibration(ServerPlayer player, double targetDb) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            CALIBRATING.put(player.getUUID(), new Calibration(server.getTickCount(), targetDb));
        }
    }

    private static void tickCalibration(MinecraftServer server, long nowNanos) {
        int tick = server.getTickCount();
        Iterator<Map.Entry<UUID, Calibration>> it = CALIBRATING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Calibration> e = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            Calibration c = e.getValue();
            LoudnessState s = LEVELS.get(e.getKey());
            Long last = LAST_MIC_PACKET.get(e.getKey());
            if (s != null && last != null && nowNanos - last <= ACTIVE_WINDOW_NANOS) {
                c.sum += s.peakDb;
                c.count++;
            }
            if (tick - c.startTick >= CALIBRATION_TICKS) {
                it.remove();
                finishCalibration(p, c);
            }
        }
    }

    private static void finishCalibration(ServerPlayer p, Calibration c) {
        if (c.count < 20) {   // less than about one second of speech
            p.sendSystemMessage(Component.literal("Calibration failed: I barely heard you. Check that voice chat works "
                    + "(press V), then run /zombieai calibrate again and keep talking for the full 5 seconds."));
            return;
        }
        double meanDbfs = c.sum / c.count;
        double offset = Math.max(0.0, Math.min(200.0, c.target - meanDbfs));
        try {
            ZombieAIConfig.setMicOffset(offset);
        } catch (RuntimeException ex) {
            p.sendSystemMessage(Component.literal("Calibration measured, but saving the config failed: " + ex.getMessage()));
            return;
        }
        p.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "Calibrated: your voice now reads about %.0f dB. Whisper is under %.0f dB, shouting is over %.0f dB. "
                        + "Use /zombieai meter to check.", c.target,
                ZombieAIConfig.WHISPER_MAX_DB.get(), ZombieAIConfig.SHOUT_MIN_DB.get())));
    }

    // ----------------------------------------------------------------- debug

    public static long totalPackets() {
        return TOTAL_PACKETS.get();
    }

    public static long millisSinceLastPacket(UUID playerId) {
        Long t = LAST_MIC_PACKET.get(playerId);
        return t == null ? -1 : (System.nanoTime() - t) / 1_000_000L;
    }
}
