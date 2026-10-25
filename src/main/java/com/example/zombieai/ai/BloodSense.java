package com.example.zombieai.ai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Knows which players are "bleeding", using the blood scents that the Zombie Awareness mod spawns
 * (entity zombieawareness:scent, type 0 = blood). No compile-time dependency on that mod: the scent
 * entity is found by its registry name and read through its saved NBT. Without Zombie Awareness
 * installed nothing is ever found and this class does nothing.
 *
 * A fresh scent next to a player means that player just bled. We then remember "this player smells
 * of blood" for 30 seconds, with a smell range that fades over that time.
 */
public final class BloodSense {
    private static final ResourceLocation SCENT_ID = new ResourceLocation("zombieawareness", "scent");
    private static final int SCENT_MAX_AGE = 30 * 20;                       // Zombie Awareness: EntityScent.MAX_AGE
    private static final int FRESH_AGE = (int) (SCENT_MAX_AGE * 0.85);
    private static final int BLOOD_TYPE = 0;                                // EnumSenseType.SCENT_BLOOD
    private static final double LINK_RADIUS = 4.0;

    private record Bleed(double range, long startTick) {}

    private static final Map<UUID, Bleed> BLEEDING = new HashMap<>();
    private static EntityType<?> scentType;
    private static boolean resolved;

    private BloodSense() {}

    static EntityType<?> scentType() {
        if (!resolved) {
            resolved = true;
            if (ForgeRegistries.ENTITY_TYPES.containsKey(SCENT_ID)) {
                scentType = ForgeRegistries.ENTITY_TYPES.getValue(SCENT_ID);
            }
        }
        return scentType;
    }

    /** Called every server tick; does its work every 5th tick. */
    public static void refresh(MinecraftServer server) {
        long now = server.getTickCount();
        if (now % 5 != 0) return;

        final EntityType<?> type = scentType();
        if (type == null || !ZombieAIConfig.BLOOD_SMELL_ENABLED.get()) {
            BLEEDING.clear();
            return;
        }
        BLEEDING.values().removeIf(b -> now - b.startTick() >= SCENT_MAX_AGE);

        double scale = ZombieAIConfig.BLOOD_SMELL_RANGE.get();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.isAlive() || p.isSpectator() || p.isCreative()) {
                BLEEDING.remove(p.getUUID());
                continue;
            }
            List<Entity> scents = p.level().getEntitiesOfClass(Entity.class,
                    p.getBoundingBox().inflate(LINK_RADIUS), e -> e.getType() == type);
            double best = 0.0;
            for (Entity scent : scents) {
                CompoundTag tag = scent.saveWithoutId(new CompoundTag());
                if (tag.getInt("type") != BLOOD_TYPE || tag.getInt("age") < FRESH_AGE) continue;
                best = Math.max(best, tag.getInt("strengthpeak") / 100.0 * scale);
            }
            if (best > 0.0) {
                BLEEDING.put(p.getUUID(), new Bleed(best, now));   // fresh blood: (re)start the 30 s timer
            }
        }
    }

    /** Current smell range (blocks) of this player's blood, 0 if not bleeding. Fades linearly over 30 s. */
    public static double smellRange(Player player, MinecraftServer server) {
        Bleed b = BLEEDING.get(player.getUUID());
        if (b == null) return 0.0;
        long elapsed = server.getTickCount() - b.startTick();
        if (elapsed >= SCENT_MAX_AGE) return 0.0;
        return b.range() * (1.0 - elapsed / (double) SCENT_MAX_AGE);
    }

    public static void forget(UUID id) {
        BLEEDING.remove(id);
    }
}
