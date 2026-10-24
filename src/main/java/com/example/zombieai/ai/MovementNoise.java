package com.example.zombieai.ai;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks which players are currently making movement noise: walking/sprinting steps or a jump.
 * Sneaking is silent. Everything runs on the main server thread, nothing is stored but positions.
 */
public final class MovementNoise {
    /** Horizontal blocks per tick above which a player counts as "moving" (walking is ~0.21). */
    private static final double MOVE_THRESHOLD = 0.08;
    private static final int JUMP_NOISE_TICKS = 10;

    private static final Map<UUID, Vec3> LAST_POS = new HashMap<>();
    private static final Map<UUID, Integer> JUMP_UNTIL = new HashMap<>();
    private static List<ServerPlayer> noisy = List.of();

    private MovementNoise() {}

    public static void markJump(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            JUMP_UNTIL.put(player.getUUID(), server.getTickCount() + JUMP_NOISE_TICKS);
        }
    }

    public static void forget(UUID id) {
        LAST_POS.remove(id);
        JUMP_UNTIL.remove(id);
    }

    /** Called once per server tick (END phase). */
    public static void refresh(MinecraftServer server) {
        int now = server.getTickCount();
        List<ServerPlayer> list = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Vec3 pos = p.position();
            Vec3 prev = LAST_POS.put(p.getUUID(), pos);
            if (!p.isAlive() || p.isSpectator() || p.isCreative()) continue;

            boolean jumped = JUMP_UNTIL.getOrDefault(p.getUUID(), -1) >= now;
            boolean stepping = false;
            if (prev != null && !p.isCrouching() && !p.isShiftKeyDown()
                    && !p.isPassenger() && !p.isInWater() && !p.isFallFlying()) {
                double dx = pos.x - prev.x;
                double dz = pos.z - prev.z;
                stepping = Math.sqrt(dx * dx + dz * dz) > MOVE_THRESHOLD;   // walking or sprinting
            }
            if (jumped || stepping) {
                list.add(p);
            }
        }
        noisy = list;
    }

    public static List<ServerPlayer> noisyPlayers() {
        return noisy;
    }
}
