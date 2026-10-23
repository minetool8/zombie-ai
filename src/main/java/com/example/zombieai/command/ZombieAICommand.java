package com.example.zombieai.command;

import com.example.zombieai.ai.HearingManager;
import com.example.zombieai.ai.ZombieAIEvents;
import com.example.zombieai.config.ZombieAIConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

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
}
