package com.example.zombieai.ai;

import com.example.zombieai.ZombieAIMod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player voice limits learned by /zombieai calibrate. Every microphone is different, so each player
 * gets their own two numbers: "whisper limit" and "shout limit" (mic level, dBFS).
 * Stored in config/zombieai-voice-calibration.properties: player id + two numbers. No audio, ever.
 */
public final class VoiceCalibration {
    /** Used until a player calibrates: a typical microphone. */
    public static final double DEFAULT_WHISPER_LIMIT = -52.0;
    public static final double DEFAULT_SHOUT_LIMIT = -14.0;

    private static final Map<UUID, double[]> DATA = new ConcurrentHashMap<>();
    private static final double[] DEFAULTS = {DEFAULT_WHISPER_LIMIT, DEFAULT_SHOUT_LIMIT};

    private VoiceCalibration() {}

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("zombieai-voice-calibration.properties");
    }

    public static void load() {
        DATA.clear();
        Path f = file();
        if (!Files.exists(f)) return;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            props.load(in);
        } catch (IOException e) {
            ZombieAIMod.LOGGER.warn("Could not read voice calibration file: {}", e.toString());
            return;
        }
        for (String key : props.stringPropertyNames()) {
            try {
                String[] parts = props.getProperty(key).split(",");
                DATA.put(UUID.fromString(key.trim()),
                        new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())});
            } catch (RuntimeException ignored) {
                // skip a damaged line
            }
        }
    }

    /** {whisperLimit, shoutLimit} in dBFS for this player. */
    public static double[] limits(UUID id) {
        double[] d = DATA.get(id);
        return d != null ? d : DEFAULTS;
    }

    public static boolean isCalibrated(UUID id) {
        return DATA.containsKey(id);
    }

    public static void set(UUID id, double whisperLimit, double shoutLimit) {
        DATA.put(id, new double[]{whisperLimit, shoutLimit});
        save();
    }

    private static synchronized void save() {
        Properties props = new Properties();
        DATA.forEach((id, d) -> props.setProperty(id.toString(), d[0] + "," + d[1]));
        try (OutputStream out = Files.newOutputStream(file())) {
            props.store(out, "Zombie AI: per-player voice loudness limits (numbers only, no audio)");
        } catch (IOException e) {
            ZombieAIMod.LOGGER.warn("Could not save voice calibration file: {}", e.toString());
        }
    }
}
