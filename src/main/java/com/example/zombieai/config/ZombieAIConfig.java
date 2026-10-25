package com.example.zombieai.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

public final class ZombieAIConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.DoubleValue SIGHT_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue FIELD_OF_VIEW;
    public static final ForgeConfigSpec.DoubleValue SNEAK_SIGHT_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue LOSE_SIGHT_GRACE_TICKS;
    public static final ForgeConfigSpec.BooleanValue INVESTIGATE_LAST_SEEN;
    public static final ForgeConfigSpec.BooleanValue USE_VISIBILITY_MODIFIERS;

    public static final ForgeConfigSpec.DoubleValue HEARING_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue WALL_ATTENUATION;
    public static final ForgeConfigSpec.DoubleValue RAIN_HEARING_MULTIPLIER;
    public static final ForgeConfigSpec.BooleanValue LOUDNESS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue WHISPER_RANGE;
    public static final ForgeConfigSpec.DoubleValue SHOUT_RANGE;
    public static final ForgeConfigSpec.DoubleValue WHISPER_BIAS_DB;
    public static final ForgeConfigSpec.DoubleValue SHOUT_BIAS_DB;
    public static final ForgeConfigSpec.BooleanValue REDIRECT_TO_NEWEST_SOUND;
    public static final ForgeConfigSpec.BooleanValue SHOUT_GROWL_ENABLED;
    public static final ForgeConfigSpec.IntValue SHOUT_GROWL_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue SHOUT_GROWL_MAX_ZOMBIES;
    public static final ForgeConfigSpec.DoubleValue SHOUT_GROWL_VOLUME;
    public static final ForgeConfigSpec.DoubleValue MOVEMENT_NOISE_RADIUS;
    public static final ForgeConfigSpec.IntValue REACTION_DELAY_TICKS;
    public static final ForgeConfigSpec.DoubleValue INVESTIGATE_RADIUS;
    public static final ForgeConfigSpec.IntValue INVESTIGATE_TIMEOUT_TICKS;

    public static final ForgeConfigSpec.BooleanValue DORMANT_AT_NIGHT;
    public static final ForgeConfigSpec.BooleanValue DORMANT_IN_RAIN;
    public static final ForgeConfigSpec.BooleanValue DORMANT_CAN_SEE;

    public static final ForgeConfigSpec.DoubleValue ALERT_RADIUS;
    public static final ForgeConfigSpec.BooleanValue RALLY_AT_ATTACKER;

    public static final ForgeConfigSpec.BooleanValue BLOOD_SMELL_ENABLED;
    public static final ForgeConfigSpec.DoubleValue BLOOD_SMELL_RANGE;
    public static final ForgeConfigSpec.BooleanValue VALIDATE_FOREIGN_TARGETS;
    public static final ForgeConfigSpec.BooleanValue STRIP_ZA_SPEED_BOOST;

    public static final ForgeConfigSpec.DoubleValue MIN_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue MAX_DISTANCE;
    public static final ForgeConfigSpec.IntValue COMMAND_PERMISSION_LEVEL;

    private static volatile ModConfig modConfig;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Visual detection").push("vision");
        SIGHT_DISTANCE = b.comment("Max distance (blocks) at which zombies/husks can SEE a player (needs clear line of sight).")
                .defineInRange("sightDistance", 24.0, 1.0, 1024.0);
        FIELD_OF_VIEW = b.comment("Total field of view in degrees. Zombies only NOTICE players inside this cone in front of them.",
                        "360 = they see all around. Sneaking only shortens range; a sneaking player right in front is still seen.")
                .defineInRange("fieldOfViewDegrees", 120.0, 10.0, 360.0);
        SNEAK_SIGHT_MULTIPLIER = b.comment("Sight range multiplier while the target sneaks (0.1 = only 10% of normal range). 1.0 = sneaking has no effect.")
                .defineInRange("sneakSightMultiplier", 0.1, 0.0, 1.0);
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
        MOVEMENT_NOISE_RADIUS = b.comment("Radius (blocks) at which footsteps, sprinting and jumping can be heard. Sneaking is silent. 0 disables.")
                .defineInRange("movementNoiseRadius", 6.0, 0.0, 64.0);
        REACTION_DELAY_TICKS = b.comment("Ticks a player must be continuously audible before the zombie reacts (20 = 1s).")
                .defineInRange("reactionDelayTicks", 0, 0, 200);
        INVESTIGATE_RADIUS = b.comment("Random error (blocks) applied to the sound location: zombies only know the APPROXIMATE spot.")
                .defineInRange("investigateRadius", 4.0, 0.0, 32.0);
        INVESTIGATE_TIMEOUT_TICKS = b.comment("Give up investigating after this many ticks.")
                .defineInRange("investigateTimeoutTicks", 300, 20, 6000);
        LOUDNESS_ENABLED = b.comment("Voice loudness decides how far zombies hear you: whisper / talking / shouting.",
                        "false = every voice counts as normal talking.")
                .define("loudnessEnabled", true);
        WHISPER_RANGE = b.comment("Hearing range (blocks) for a whisper (voice quieter than whisperMaxDb).")
                .defineInRange("whisperRange", 1.0, 0.0, 32.0);
        SHOUT_RANGE = b.comment("Hearing range (blocks) for shouting (voice louder than shoutMinDb).")
                .defineInRange("shoutRange", 100.0, 1.0, 1024.0);
        WHISPER_BIAS_DB = b.comment("Fine-tune after /zombieai calibrate. Positive = more of your quiet talking counts as a whisper.",
                        "Units are mic level steps (about 1 dB each). 0 = exactly the middle between your whisper and your normal voice.")
                .defineInRange("whisperBias", 0.0, -20.0, 20.0);
        SHOUT_BIAS_DB = b.comment("Fine-tune after /zombieai calibrate. Positive = you have to be louder before it counts as shouting.")
                .defineInRange("shoutBias", 0.0, -20.0, 20.0);
        REDIRECT_TO_NEWEST_SOUND = b.comment("Zombie Awareness sounds (gunshots, mining, doors...): zombies that can hear a NEW sound at once turn and walk to it,",
                        "even if they were already walking to an older one.")
                .define("redirectToNewestSound", true);
        SHOUT_GROWL_ENABLED = b.comment("When you shout and zombies BEYOND normal talking range hear it, everyone in the shout radius hears loud growls from their direction.")
                .define("shoutGrowlEnabled", true);
        SHOUT_GROWL_COOLDOWN_TICKS = b.comment("Minimum ticks between growls per shouting player (20 = 1 second).")
                .defineInRange("shoutGrowlCooldownTicks", 100, 20, 2400);
        SHOUT_GROWL_MAX_ZOMBIES = b.comment("How many far zombies growl per shout.")
                .defineInRange("shoutGrowlMaxZombies", 3, 1, 10);
        SHOUT_GROWL_VOLUME = b.comment("Volume of the growl (above 1 = carries further).")
                .defineInRange("shoutGrowlVolume", 5.0, 1.0, 16.0);
        b.pop();

        b.comment("Dormancy: idle zombies stand perfectly still (no walking, no head turning) but still hear.",
                        "Zombies that are chasing or investigating are never stopped.").push("dormancy");
        DORMANT_AT_NIGHT = b.comment("Idle zombies freeze at night (normal day/night dimensions only).")
                .define("dormantAtNight", true);
        DORMANT_IN_RAIN = b.comment("Idle zombies freeze while it rains.")
                .define("dormantInRain", true);
        DORMANT_CAN_SEE = b.comment("A frozen zombie can still notice players inside its (fixed) field of view.")
                .define("dormantCanSee", true);
        b.pop();

        b.comment("Pack alert: a zombie hurt by a player it cannot see calls its neighbours").push("alert");
        ALERT_RADIUS = b.comment("Radius (blocks) in which other zombies/husks are alerted. 0 disables the alert.")
                .defineInRange("alertRadius", 32.0, 0.0, 128.0);
        RALLY_AT_ATTACKER = b.comment("true = neighbours gather near where the ATTACKER was (approximate, uses investigateRadius).",
                        "false = neighbours gather where the hurt zombie is standing.")
                .define("rallyAtAttacker", true);
        b.pop();

        b.comment("Blood smell (uses the Zombie Awareness mod's blood scents)").push("blood");
        BLOOD_SMELL_ENABLED = b.comment("A bleeding player is smelled and followed even when unseen, through walls, from behind, at night or in rain.")
                .define("bloodSmellEnabled", true);
        BLOOD_SMELL_RANGE = b.comment("Smell range scale in blocks. A fresh Zombie Awareness blood scent (strength 60) reaches about 60% of this, fading over 30 seconds.")
                .defineInRange("bloodSmellRange", 64.0, 1.0, 256.0);
        b.pop();

        b.comment("Compatibility with Zombie Awareness").push("compat");
        VALIDATE_FOREIGN_TARGETS = b.comment("A player targeted by ANOTHER mod must still pass our line-of-sight / field-of-view rules (blood excepted).")
                .define("validateForeignTargets", true);
        STRIP_ZA_SPEED_BOOST = b.comment("Remove Zombie Awareness' random speed bonus from zombies/husks so it does not stack on our speed distribution.")
                .define("stripZombieAwarenessSpeedBoost", true);
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

    /** Range at which steps, sprinting and jumps are heard (also reduced by rain). */
    public static double movementNoiseRange(Level level) {
        double r = MOVEMENT_NOISE_RADIUS.get();
        return level.isRaining() ? r * RAIN_HEARING_MULTIPLIER.get() : r;
    }

    /** 1.0 normally, the rain multiplier (default 0.2) while it rains. */
    public static double rainFactor(Level level) {
        return level.isRaining() ? RAIN_HEARING_MULTIPLIER.get() : 1.0;
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
