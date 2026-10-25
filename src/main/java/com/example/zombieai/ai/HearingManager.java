package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Voice hearing: who is talking, and how LOUD (whisper / normal / shout).
 *
 * Privacy: for every 20 ms voice frame the plugin computes ONE number (loudness) and throws the audio
 * away immediately. No audio, no words, no recordings are stored or sent anywhere.
 *
 * How loudness is decided: each frame feeds a fast-attack meter. The loudest meter value of the last 0.4 s
 * is compared with two limits that /zombieai calibrate learned from YOUR mic: your whisper limit and your
 * shout limit. Shouting also needs a few loud frames in a row, so a single pop or cough is not a shout.
 *
 * Voice packets arrive on voicechat's own thread, so markSpeaking() only touches concurrent maps.
 * This class has NO voicechat imports, so it loads fine without Simple Voice Chat.
 */
public final class HearingManager {
    public enum Tier { WHISPER, NORMAL, SHOUT }

    /** The dB scale shown by /zombieai meter: your whisper limit reads 60, your shout limit reads 100. */
    public static final double WHISPER_LIMIT_DB = 60.0;
    public static final double SHOUT_LIMIT_DB = 100.0;

    private static final long ACTIVE_WINDOW_NANOS = 400_000_000L;   // "currently talking" window
    private static final long PRUNE_NANOS = 5_000_000_000L;
    private static final long RESET_NANOS = 400_000_000L;           // a pause longer than this = new utterance
    private static final double ATTACK = 0.5;                       // meter rises fast...
    private static final double RELEASE = 0.12;                     // ...and falls slowly
    private static final double HYSTERESIS = 1.5;                   // stops flickering between tiers
    private static final int RING = 20;                             // 20 frames = 0.4 s
    private static final int SHOUT_FRAMES = 4;                      // loud frames in a row needed to count as a shout

    private static final int LEAD_IN_TICKS = 40;
    private static final int PHASE_TICKS = 120;                     // 6 seconds per step
    private static final int SKIP_TICKS = 30;                       // ignore the first 1.5 s of each step
    private static final int MIN_SAMPLES = 15;

    private static final class LoudnessState {
        volatile double window = -100.0;   // loudest meter value of the last 0.4 s (dBFS)
        volatile long lastNanos;
        volatile Tier tier = Tier.NORMAL;
        private double power;
        private int idx;
        private int loudFrames;
        private final double[] ring = new double[RING];

        private static double toDb(double power) {
            return 10.0 * Math.log10(Math.max(power, 1.0E-12));
        }

        synchronized void add(double dbfs, long now, double whisperLimit, double shoutLimit) {
            double p = Math.pow(10.0, dbfs / 10.0);
            if (now - lastNanos > RESET_NANOS) {
                power = p;                                   // new utterance
                Arrays.fill(ring, toDb(p));
                loudFrames = 0;
                tier = Tier.NORMAL;
            } else {
                power += (p > power ? ATTACK : RELEASE) * (p - power);
            }
            lastNanos = now;

            double level = toDb(power);
            ring[idx] = level;
            idx = (idx + 1) % RING;
            double w = -200.0;
            for (double v : ring) {
                if (v > w) w = v;
            }
            window = w;

            loudFrames = level > shoutLimit ? loudFrames + 1 : 0;
            Tier t = tier;
            if (t == Tier.SHOUT) {
                if (w < shoutLimit - HYSTERESIS) t = w < whisperLimit ? Tier.WHISPER : Tier.NORMAL;
            } else if (t == Tier.WHISPER) {
                if (w > whisperLimit + HYSTERESIS) t = Tier.NORMAL;
            } else if (w < whisperLimit) {
                t = Tier.WHISPER;
            }
            if (t != Tier.SHOUT && loudFrames >= SHOUT_FRAMES) t = Tier.SHOUT;
            tier = t;
        }
    }

    private static final class Session {
        final int startTick;
        final List<Double>[] samples;
        int announced = -1;

        @SuppressWarnings("unchecked")
        Session(int startTick) {
            this.startTick = startTick;
            this.samples = new List[]{new ArrayList<Double>(), new ArrayList<Double>(), new ArrayList<Double>()};
        }
    }

    private static final ConcurrentHashMap<UUID, Long> LAST_MIC_PACKET = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, LoudnessState> LEVELS = new ConcurrentHashMap<>();
    private static final Set<UUID> METER = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Session> CALIBRATING = new ConcurrentHashMap<>();
    private static final AtomicLong TOTAL_PACKETS = new AtomicLong();

    private static volatile List<ServerPlayer> speakers = List.of();
    public static volatile boolean voiceChatPluginLoaded = false;

    private HearingManager() {}

    // ------------------------------------------------------------ voice thread

    public static void markSpeaking(UUID playerId) {
        markSpeaking(playerId, Double.NaN);
    }

    /** @param dbfs digital level of this 20 ms frame (0 = loudest possible, -100 = silence), NaN if unknown */
    public static void markSpeaking(UUID playerId, double dbfs) {
        long now = System.nanoTime();
        TOTAL_PACKETS.incrementAndGet();
        LAST_MIC_PACKET.put(playerId, now);
        if (Double.isNaN(dbfs)) return;

        double whisperLimit = VoiceCalibration.DEFAULT_WHISPER_LIMIT;
        double shoutLimit = VoiceCalibration.DEFAULT_SHOUT_LIMIT;
        try {
            double[] lim = VoiceCalibration.limits(playerId);
            whisperLimit = lim[0] + ZombieAIConfig.WHISPER_BIAS_DB.get();
            shoutLimit = lim[1] + ZombieAIConfig.SHOUT_BIAS_DB.get();
        } catch (RuntimeException ignored) {
            // config not ready: use defaults
        }
        LEVELS.computeIfAbsent(playerId, k -> new LoudnessState()).add(dbfs, now, whisperLimit, shoutLimit);
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
            LEVELS.values().removeIf(s -> now - s.lastNanos > PRUNE_NANOS);
            LAST_MIC_PACKET.forEach((id, t) -> {
                if (now - t <= ACTIVE_WINDOW_NANOS) {
                    ServerPlayer p = server.getPlayerList().getPlayer(id);
                    if (p != null && p.isAlive() && !p.isSpectator()
                            && (!p.isCreative() || ZombieAIConfig.CREATIVE_MAKES_NOISE.get())) {
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

    public static Tier tierOf(UUID playerId) {
        if (!ZombieAIConfig.LOUDNESS_ENABLED.get()) return Tier.NORMAL;
        LoudnessState s = LEVELS.get(playerId);
        return s == null ? Tier.NORMAL : s.tier;   // no measurement -> normal talking
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

    /** The friendly dB scale: your whisper limit = 60, your shout limit = 100. */
    public static double toDecibels(UUID playerId, double dbfs) {
        double[] lim = VoiceCalibration.limits(playerId);
        double whisper = lim[0] + ZombieAIConfig.WHISPER_BIAS_DB.get();
        double shout = lim[1] + ZombieAIConfig.SHOUT_BIAS_DB.get();
        double span = Math.max(shout - whisper, 1.0);
        return WHISPER_LIMIT_DB + (dbfs - whisper) * (SHOUT_LIMIT_DB - WHISPER_LIMIT_DB) / span;
    }

    // ----------------------------------------------------------------- meter

    /** Toggles the live mic meter above the player's hotbar. Returns true if it is now on. */
    public static boolean toggleMeter(UUID playerId) {
        if (METER.remove(playerId)) return false;
        METER.add(playerId);
        return true;
    }

    private static void showMeters(MinecraftServer server, long now) {
        for (UUID id : METER) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || CALIBRATING.containsKey(id)) continue;
            LoudnessState s = LEVELS.get(id);
            Long last = LAST_MIC_PACKET.get(id);
            String text;
            if (s == null || last == null || now - last > 1_000_000_000L) {
                text = "Mic: silent";
            } else {
                text = String.format(Locale.ROOT, "Mic: %.0f dB  =  %s  (zombies hear %.0f blocks)%s",
                        toDecibels(id, s.window), s.tier, rangeFor(p, p.level()),
                        VoiceCalibration.isCalibrated(id) ? "" : "   [not calibrated: /zombieai calibrate]");
            }
            p.displayClientMessage(Component.literal(text), true);
        }
    }

    // ----------------------------------------------------------- guided calibration

    /** Starts the guided calibration: whisper, then normal voice, then shout (about 20 seconds). */
    public static void startCalibration(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            CALIBRATING.put(player.getUUID(), new Session(server.getTickCount()));
        }
    }

    private static final String[] PROMPTS = {
            "WHISPER now: whisper steadily, the way you would next to a zombie",
            "TALK NORMALLY now: speak in your usual voice",
            "SHOUT now: shout as loud as you would in a fight"
    };

    private static void tickCalibration(MinecraftServer server, long nowNanos) {
        int tick = server.getTickCount();
        Iterator<Map.Entry<UUID, Session>> it = CALIBRATING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Session> e = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            Session s = e.getValue();
            int elapsed = tick - s.startTick;
            if (elapsed < LEAD_IN_TICKS) {
                if (elapsed % 10 == 0) {
                    p.displayClientMessage(Component.literal("Voice calibration: get ready..."), true);
                }
                continue;
            }
            int rel = elapsed - LEAD_IN_TICKS;
            int phase = rel / PHASE_TICKS;
            if (phase >= 3) {
                it.remove();
                finishCalibration(p, s);
                continue;
            }
            int within = rel % PHASE_TICKS;
            if (phase != s.announced) {
                s.announced = phase;
                p.sendSystemMessage(Component.literal("Calibration " + (phase + 1) + "/3: " + PROMPTS[phase]));
            }
            if (within % 10 == 0) {
                p.displayClientMessage(Component.literal(String.format(Locale.ROOT, "%d/3  %s  (%d s)",
                        phase + 1, PROMPTS[phase], (PHASE_TICKS - within + 19) / 20)), true);
            }
            LoudnessState st = LEVELS.get(e.getKey());
            Long last = LAST_MIC_PACKET.get(e.getKey());
            if (within >= SKIP_TICKS && st != null && last != null && nowNanos - last <= ACTIVE_WINDOW_NANOS) {
                s.samples[phase].add(st.window);
            }
        }
    }

    private static double median(List<Double> values) {
        List<Double> c = new ArrayList<>(values);
        Collections.sort(c);
        int n = c.size();
        return n % 2 == 1 ? c.get(n / 2) : (c.get(n / 2 - 1) + c.get(n / 2)) / 2.0;
    }

    private static void finishCalibration(ServerPlayer p, Session s) {
        String[] names = {"whisper", "normal voice", "shout"};
        for (int i = 0; i < 3; i++) {
            if (s.samples[i].size() < MIN_SAMPLES) {
                p.sendSystemMessage(Component.literal("Calibration failed: I barely heard your " + names[i]
                        + ". Check that voice chat works (press V) and talk during the whole step. Run /zombieai calibrate again."));
                return;
            }
        }
        double whisper = median(s.samples[0]);
        double normal = median(s.samples[1]);
        double shout = median(s.samples[2]);
        if (normal - whisper < 3.0 || shout - normal < 3.0) {
            p.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                    "Calibration failed: whisper %.0f, normal %.0f, shout %.0f (mic units). They must clearly rise. "
                            + "Something is flattening your volume: in Simple Voice Chat turn OFF automatic gain control / "
                            + "keep microphone amplification at 100%%, and turn off any 'voice enhancement' of your headset "
                            + "or Windows. Then run /zombieai calibrate again and make the three voices very different.",
                    whisper, normal, shout)));
            return;
        }
        VoiceCalibration.set(p.getUUID(), (whisper + normal) / 2.0, (normal + shout) / 2.0);
        p.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "Calibrated! On /zombieai meter: whisper reads up to 60 dB, normal talking 60 to 100 dB, shouting above 100 dB. "
                        + "(whisper to normal gap %.0f, normal to shout gap %.0f)", normal - whisper, shout - normal)));
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
