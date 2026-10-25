package com.example.zombieai.command;

import com.example.zombieai.ai.HearingManager;
import com.example.zombieai.ai.ZombieAIEvents;
import com.example.zombieai.config.ZombieAIConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.example.zombieai.ai.PerceptionGoal;
import com.example.zombieai.ai.PerceptionUtil;
import com.example.zombieai.ai.ZombieScale;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

public final class ZombieAICommand {
    private ZombieAICommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("zombieai")
                .then(Commands.literal("sight")
                        .requires(ZombieAICommand::canConfigure)
                        .executes(ctx -> show(ctx.getSource(), "Sight", ZombieAIConfig.sight()))
                        .then(Commands.argument("distance", DoubleArgumentType.doubleArg())
                                .executes(ctx -> set(ctx.getSource(), true,
                                        DoubleArgumentType.getDouble(ctx, "distance")))))
                .then(Commands.literal("hearing")
                        .requires(ZombieAICommand::canConfigure)
                        .executes(ctx -> show(ctx.getSource(), "Hearing", ZombieAIConfig.hearing()))
                        .then(Commands.argument("distance", DoubleArgumentType.doubleArg())
                                .executes(ctx -> set(ctx.getSource(), false,
                                        DoubleArgumentType.getDouble(ctx, "distance")))))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("calibrate")
                        .requires(ZombieAICommand::canConfigure)
                        .executes(ctx -> calibrate(ctx.getSource())))
                .then(Commands.literal("meter")
                        .requires(ZombieAICommand::canConfigure)
                        .executes(ctx -> meter(ctx.getSource())))
                .then(Commands.literal("debug")
                        .requires(ZombieAICommand::canConfigure)
                        .executes(ctx -> debug(ctx.getSource())))
                .then(Commands.literal("reload")
                        .requires(ZombieAICommand::canConfigure)
                        .executes(ctx -> reload(ctx.getSource()))));
    }

    private static boolean canConfigure(CommandSourceStack src) {
        return src.hasPermission(ZombieAIConfig.COMMAND_PERMISSION_LEVEL.get());
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static int show(CommandSourceStack src, String label, double value) {
        src.sendSuccess(() -> Component.literal(label + " distance is " + fmt(value) + " blocks."), false);
        return 1;
    }

    private static int set(CommandSourceStack src, boolean sight, double value) {
        double min = ZombieAIConfig.minDistance();
        double max = ZombieAIConfig.maxDistance();
        String name = sight ? "sight" : "hearing";

        if (!Double.isFinite(value) || value < min || value > max) {
            src.sendFailure(Component.literal("Invalid " + name + " distance. Allowed range: "
                    + fmt(min) + " to " + fmt(max) + " blocks."));
            return 0;
        }
        try {
            if (sight) ZombieAIConfig.setSight(value);
            else ZombieAIConfig.setHearing(value);
        } catch (RuntimeException ex) {
            src.sendFailure(Component.literal("Could not save the config file: " + ex.getMessage()));
            return 0;
        }
        ZombieAIEvents.refreshAll(src.getServer());
        src.sendSuccess(() -> Component.literal("Zombie " + name + " distance set to " + fmt(value)
                + " blocks (applied to all zombies and husks, saved to config)."), true);
        return 1;
    }

    private static int status(CommandSourceStack src) {
        String voice = HearingManager.voiceChatPluginLoaded
                ? "Simple Voice Chat detected" : "Simple Voice Chat NOT detected (hearing inactive)";
        src.sendSuccess(() -> Component.literal(
                "Zombie AI status:\n"
                        + " Sight: " + fmt(ZombieAIConfig.sight()) + " blocks\n"
                        + " Hearing: " + fmt(ZombieAIConfig.hearing()) + " blocks ("
                        + fmt(ZombieAIConfig.WALL_ATTENUATION.get()) + " extra per solid block)\n"
                        + " Voice: whisper (up to " + fmt(HearingManager.WHISPER_LIMIT_DB) + " dB) " + fmt(ZombieAIConfig.WHISPER_RANGE.get()) + ", talking " + fmt(ZombieAIConfig.hearing())
                        + ", shouting (above " + fmt(HearingManager.SHOUT_LIMIT_DB) + " dB) " + fmt(ZombieAIConfig.SHOUT_RANGE.get()) + " blocks\n"
                        + " Limits: " + fmt(ZombieAIConfig.minDistance()) + " - " + fmt(ZombieAIConfig.maxDistance()) + " blocks\n"
                        + " " + voice), false);
        return 1;
    }

    private static int reload(CommandSourceStack src) {
        boolean ok;
        try {
            ok = ZombieAIConfig.reload();
        } catch (RuntimeException ex) {
            src.sendFailure(Component.literal("Reload failed: " + ex.getMessage()));
            return 0;
        }
        if (!ok) {
            src.sendFailure(Component.literal("Config reload is not available (config not loaded from a file)."));
            return 0;
        }
        ZombieAIEvents.refreshAll(src.getServer());
        src.sendSuccess(() -> Component.literal("Zombie AI config reloaded. Sight "
                + fmt(ZombieAIConfig.sight()) + ", hearing " + fmt(ZombieAIConfig.hearing()) + " blocks."), true);
        return 1;
    }

    /** Guided calibration: whisper, then normal voice, then shout (about 20 seconds). */
    private static int calibrate(CommandSourceStack src) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            src.sendFailure(Component.literal("Run this command as a player."));
            return 0;
        }
        HearingManager.startCalibration(player);
        src.sendSuccess(() -> Component.literal("Voice calibration started (about 20 seconds). Follow the prompts above "
                + "your hotbar: whisper, then talk normally, then shout. Keep voice chat on and keep talking during each step."), false);
        return 1;
    }

    /** Live mic loudness meter (action bar). */
    private static int meter(CommandSourceStack src) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            src.sendFailure(Component.literal("Run this command as a player."));
            return 0;
        }
        boolean on = HearingManager.toggleMeter(player.getUUID());
        src.sendSuccess(() -> Component.literal(on
                ? "Mic meter ON: it shows above your hotbar while you talk. Run /zombieai meter again to turn it off."
                : "Mic meter OFF."), false);
        return 1;
    }

    private static int debug(CommandSourceStack src) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            src.sendFailure(Component.literal("Run this command as a player."));
            return 0;
        }
        StringBuilder sb = new StringBuilder("Zombie AI debug:\n");

        // Step 1: is the server receiving mic packets at all?
        sb.append(" [1] Mic packets seen by server: ").append(HearingManager.totalPackets()).append("\n");
        long ago = HearingManager.millisSinceLastPacket(player.getUUID());
        sb.append(" [1] Packets from YOU: ")
                .append(ago < 0 ? "NONE recorded (talk, then run this again within 1-2 seconds)"
                        : "last one " + ago + " ms ago").append("\n");

        // Step 2: are you counted as an active speaker?
        boolean active = HearingManager.speakers().contains(player);
        sb.append(" [2] Counted as active speaker: ").append(active ? "yes" : "NO");
        if (player.isCreative() || player.isSpectator()) {
            sb.append("  <-- you are in creative/spectator, switch to survival");
        }
        sb.append("\n");

        // Step 3: what does the nearest zombie/husk think?
        ServerLevel level = player.serverLevel();
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(64), ZombieScale::applies);
        Mob nearest = null;
        for (Mob m : mobs) {
            if (nearest == null || m.distanceToSqr(player) < nearest.distanceToSqr(player)) nearest = m;
        }
        if (nearest == null) {
            sb.append(" [3] No zombie/husk within 64 blocks.");
        } else {
            PerceptionGoal goal = null;
            for (WrappedGoal w : nearest.targetSelector.getAvailableGoals()) {
                if (w.getGoal() instanceof PerceptionGoal g) goal = g;
            }
            double dist = nearest.distanceTo(player);
            int walls = PerceptionUtil.countOccluders(level, nearest.getEyePosition(), player.getEyePosition());
            double effective = dist + walls * ZombieAIConfig.WALL_ATTENUATION.get();
            sb.append(" [3] Nearest ").append(nearest.getType().getDescriptionId().replace("entity.minecraft.", ""))
                    .append(": distance ").append(fmt(dist))
                    .append(", solid blocks between ").append(walls)
                    .append(", effective ").append(fmt(effective))
                    .append(" vs your voice range ").append(fmt(HearingManager.rangeFor(player, level)))
                    .append(effective <= HearingManager.rangeFor(player, level) ? " (AUDIBLE)" : " (too far/muffled)").append("\n");
            sb.append(" [3] AI installed: ").append(goal != null ? "yes" : "NO (mod goal missing!)")
                    .append(", target: ").append(nearest.getTarget() != null
                            ? nearest.getTarget().getType().getDescriptionId() + " (hearing is skipped)" : "none")
                    .append(", investigating: ").append(goal != null && goal.hasInvestigation() ? "yes" : "no");
        }
        String out = sb.toString();
        src.sendSuccess(() -> Component.literal(out), false);
        return 1;
    }
}
