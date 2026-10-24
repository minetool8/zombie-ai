package com.example.zombieai.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

public final class ZombieAIConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.DoubleValue SIGHT_DISTANCE;
    public static final ForgeConfigSpec.IntValue LOSE_SIGHT_GRACE_TICKS;
    public static final ForgeConfigSpec.BooleanValue INVESTIGATE_LAST_SEEN;
    public static final ForgeConfigSpec.BooleanValue USE_VISIBILITY_MODIFIERS;

    public static final ForgeConfigSpec.DoubleValue HEARING_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue WALL_ATTENUATION;
    public static final ForgeConfigSpec.DoubleValue RAIN_HEARING_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue REACTION_DELAY_TICKS;
    public static final ForgeConfigSpec.DoubleValue INVESTIGATE_RADIUS;
    public static final ForgeConfigSpec.IntValue INVESTIGATE_TIMEOUT_TICKS;

    public static final ForgeConfigSpec.DoubleValue ALERT_RADIUS;
    public static final ForgeConfigSpec.BooleanValue RALLY_AT_ATTACKER;

    public static final ForgeConfigSpec.DoubleValue MIN_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue MAX_DISTANCE;
    public static final ForgeConfigSpec.IntValue COMMAND_PERMISSION_LEVEL;

    private static volatile ModConfig modConfig;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Visual detection").push("vision");
        SIGHT_DISTANCE = b.comment("Max distance (blocks) at which zombies/husks can SEE a player (needs clear line of sight).")
                .defineInRange("sightDistance", 24.0, 1.0, 1024.0);
        LOSE_SIGHT_GRACE_TICKS = b.comment("Ticks a target may be hidden before the zombie drops it (prevents flicker at corners).",
                        "During this short window vanilla attack movement still uses the real position.")
                .defineInRange("loseSightGraceTicks", 10, 0, 200);
        INVESTIGATE_LAST_SEEN = b.comment("After losing sight, walk to the last position the player was SEEN (not the live position).")
                .define("investigateLastSeen", true);
        USE_VISIBILITY_MODIFIERS = b.comment("Scale sight distance by vanilla visibility (sneaking, invisibility, mob heads).")
                .define("useVisibilityModifiers", true);
        b.pop();

        b.comment("Microphone hearing (Simple Voice Chat)").push("hearing");
        HEARING_DISTANCE = b.comment("Max distance (blocks) at which a talking player can be heard.")
                .defineInRange("hearingDistance", 32.0, 1.0, 1024.0);
        WALL_ATTENUATION = b.comment("Extra 'effective distance' (blocks) added per solid block between zombie and speaker.")
                .defineInRange("wallAttenuationPerBlock", 4.0, 0.0, 128.0);
        RAIN_HEARING_MULTIPLIER = b.comment("Hearing range multiplier while it is raining (0.2 = range reduced by 80%).")
                .defineInRange("rainHearingMultiplier", 0.2, 0.0, 1.0);
        REACTION_DELAY_TICKS = b.comment("Ticks a player must be continuously audible before the zombie reacts (20 = 1s).")
                .defineInRange("reactionDelayTicks", 0, 0, 200);
        INVESTIGATE_RADIUS = b.comment("Random error (blocks) applied to the sound location: zombies only know the APPROXIMATE spot.")
                .defineInRange("investigateRadius", 4.0, 0.0, 32.0);
        INVESTIGATE_TIMEOUT_TICKS = b.comment("Give up investigating after this many ticks.")
                .defineInRange("investigateTimeoutTicks", 300, 20, 6000);
        b.pop();

        b.comment("Pack alert: a zombie hurt by a player it cannot see calls its neighbours").push("alert");
        ALERT_RADIUS = b.comment("Radius (blocks) in which other zombies/husks are alerted. 0 disables the alert.")
                .defineInRange("alertRadius", 32.0, 0.0, 128.0);
        RALLY_AT_ATTACKER = b.comment("true = neighbours gather near where the ATTACKER was (approximate, uses investigateRadius).",
                        "false = neighbours gather where the hurt zombie is standing.")
                .define("rallyAtAttacker", true);
        b.pop();

        b.comment("Limits and permissions for /zombieai").push("commands");
        MIN_DISTANCE = b.comment("Smallest distance the commands accept.")
                .defineInRange("minDistance", 1.0, 0.0, 1024.0);
        MAX_DISTANCE = b.comment("Largest distance the commands accept. Large values make pathfinding expensive.")
                .defineInRange("maxDistance", 128.0, 1.0, 1024.0);
        COMMAND_PERMISSION_LEVEL = b.comment("Permission level required to change settings (2 = operator, 4 = owner).")
                .defineInRange("permissionLevel", 2, 0, 4);
        b.pop();

        SPEC = b.build();
    }

    private ZombieAIConfig() {}

    public static void onConfigEvent(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            modConfig = event.getConfig();
        }
    }

    // ---- accessors (always clamped to the configured limits) ----
    public static double minDistance() { return Math.min(MIN_DISTANCE.get(), MAX_DISTANCE.get()); }
    public static double maxDistance() { return Math.max(MIN_DISTANCE.get(), MAX_DISTANCE.get()); }
    private static double clamp(double v) { return Math.max(minDistance(), Math.min(maxDistance(), v)); }

    public static double sight() { return clamp(SIGHT_DISTANCE.get()); }
    public static double hearing() { return clamp(HEARING_DISTANCE.get()); }

    /** Hearing range in this level: reduced while it is raining. */
    public static double hearing(Level level) {
        double h = hearing();
        return level.isRaining() ? h * RAIN_HEARING_MULTIPLIER.get() : h;
    }

    // ---- setters used by the command (caller validates the range) ----
    public static void setSight(double v) { SIGHT_DISTANCE.set(v); SPEC.save(); }
    public static void setHearing(double v) { HEARING_DISTANCE.set(v); SPEC.save(); }

    /** Re-reads the TOML file from disk. Returns false if the config file is not available. */
    public static boolean reload() {
        ModConfig mc = modConfig;
        if (mc == null || !(mc.getConfigData() instanceof CommentedFileConfig fileConfig)) {
            return false;
        }
        fileConfig.load();
        if (!SPEC.isCorrect(fileConfig)) {
            SPEC.correct(fileConfig);
        }
        SPEC.afterReload();
        return true;
    }
}
