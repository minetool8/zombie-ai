package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Footstep noise. Each tick every player who is walking, sprinting, sneaking or has just jumped gets a
 * "noise radius": how far zombies can hear that player right now.
 *   walking   -> walkNoiseRadius   (default 8 blocks)
 *   sprinting -> sprintNoiseRadius (default 20 blocks)
 *   sneaking  -> sneakNoiseRadius  (default 0 = silent)
 *   jumping   -> as loud as walking (or sprinting if sprinting)
 * Runs on the main server thread. Only positions are remembered.
 */
public final class MovementNoise {
    /** Horizontal blocks per tick above which a player counts as "moving" (sneaking is ~0.065, walking ~0.21). */
    private static final double MOVE_THRESHOLD = 0.04;
    private static final int JUMP_NOISE_TICKS = 10;

    private static final Map<UUID, Vec3> LAST_POS = new HashMap<>();
    private static final Map<UUID, Integer> JUMP_UNTIL = new HashMap<>();
    private static final Map<UUID, Double> RADIUS = new HashMap<>();
    private static final Set<UUID> LOUD = new HashSet<>();
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
        RADIUS.remove(id);
    }

    /** Called once per server tick (END phase). */
    public static void refresh(MinecraftServer server) {
        int now = server.getTickCount();
        boolean creativeNoisy = ZombieAIConfig.CREATIVE_MAKES_NOISE.get();
        double walk = ZombieAIConfig.WALK_NOISE_RADIUS.get();
        double sprint = ZombieAIConfig.SPRINT_NOISE_RADIUS.get();
        double sneak = ZombieAIConfig.SNEAK_NOISE_RADIUS.get();

        List<ServerPlayer> list = new ArrayList<>();
        RADIUS.clear();
        LOUD.clear();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Vec3 pos = p.position();
            Vec3 prev = LAST_POS.put(p.getUUID(), pos);
            if (!p.isAlive() || p.isSpectator() || (p.isCreative() && !creativeNoisy)) continue;

            boolean sneaking = p.isCrouching() || p.isShiftKeyDown();
            boolean moving = false;
            if (prev != null && !p.isPassenger() && !p.isInWater() && !p.isFallFlying()
                    && !p.getAbilities().flying) {   // creative flying makes no footsteps
                double dx = pos.x - prev.x;
                double dz = pos.z - prev.z;
                moving = Math.sqrt(dx * dx + dz * dz) > MOVE_THRESHOLD;
            }

            double radius = 0.0;
            if (moving) {
                radius = sneaking ? sneak : (p.isSprinting() ? sprint : walk);
            }
            if (JUMP_UNTIL.getOrDefault(p.getUUID(), -1) >= now) {
                radius = Math.max(radius, p.isSprinting() ? sprint : walk);
            }
            if (radius > 0.0) {
                list.add(p);
                RADIUS.put(p.getUUID(), radius);
                if (p.isSprinting() && !sneaking) LOUD.add(p.getUUID());   // sprinting is loud, walking is not
            }
        }
        noisy = list;
    }

    public static List<ServerPlayer> noisyPlayers() {
        return noisy;
    }

    /** True if this player's movement counts as a LOUD noise (sprinting). */
    public static boolean isLoud(UUID id) {
        return LOUD.contains(id);
    }

    /** How far (blocks, before rain) this player's movement can be heard right now. */
    public static double radiusOf(UUID id) {
        return RADIUS.getOrDefault(id, 0.0);
    }
}
