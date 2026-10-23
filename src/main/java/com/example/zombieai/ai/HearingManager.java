package com.example.zombieai.ai;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Privacy: the ONLY thing stored is "player X sent a mic packet at time T".
 * No audio data, no transcripts, nothing is read from or kept of the packets.
 *
 * Voice chat packets arrive on voicechat's own thread, so markSpeaking() only touches a
 * concurrent map. The main server thread turns that into a snapshot via refresh().
 * This class has NO voicechat imports, so it loads fine without Simple Voice Chat.
 */
public final class HearingManager {
    private static final ConcurrentHashMap<UUID, Long> LAST_MIC_PACKET = new ConcurrentHashMap<>();
    private static final long ACTIVE_WINDOW_NANOS = 400_000_000L;   // "currently talking" window
    private static final long PRUNE_NANOS = 5_000_000_000L;

    private static volatile List<ServerPlayer> speakers = List.of();
    public static volatile boolean voiceChatPluginLoaded = false;
    private static final AtomicLong TOTAL_PACKETS = new AtomicLong();

    private HearingManager() {}

    /** Called from the voicechat thread. */
    public static void markSpeaking(UUID playerId) {
        TOTAL_PACKETS.incrementAndGet();
        LAST_MIC_PACKET.put(playerId, System.nanoTime());
    }

    /** Debug only: how many mic packets (just a count) the server has seen since start. */
    public static long totalPackets() {
        return TOTAL_PACKETS.get();
    }

    /** Debug only: milliseconds since this player's last mic packet, or -1 if none recorded. */
    public static long millisSinceLastPacket(UUID playerId) {
        Long t = LAST_MIC_PACKET.get(playerId);
        return t == null ? -1 : (System.nanoTime() - t) / 1_000_000L;
    }

    public static void forget(UUID playerId) {
        LAST_MIC_PACKET.remove(playerId);
    }

    /** Called on the main server thread every couple of ticks. */
    public static void refresh(MinecraftServer server) {
        if (LAST_MIC_PACKET.isEmpty()) {
            if (!speakers.isEmpty()) speakers = List.of();
            return;
        }
        long now = System.nanoTime();
        LAST_MIC_PACKET.values().removeIf(t -> now - t > PRUNE_NANOS);
        List<ServerPlayer> list = new ArrayList<>();
        LAST_MIC_PACKET.forEach((id, t) -> {
            if (now - t <= ACTIVE_WINDOW_NANOS) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null && p.isAlive() && !p.isSpectator() && !p.isCreative()) {
                    list.add(p);
                }
            }
        });
        speakers = List.copyOf(list);
    }

    public static List<ServerPlayer> speakers() {
        return speakers;
    }
}
