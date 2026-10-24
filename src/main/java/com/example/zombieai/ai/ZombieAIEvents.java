package com.example.zombieai.ai;

import com.example.zombieai.ZombieAIMod;
import com.example.zombieai.command.ZombieAICommand;
import com.example.zombieai.config.ZombieAIConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.UUID;

@Mod.EventBusSubscriber(modid = ZombieAIMod.MODID)
public final class ZombieAIEvents {
    private static final UUID SPEED_UUID = UUID.fromString("5d0b6f3a-7a53-4f1e-9a41-3c1f0a6b7e01");
    private static final UUID HEALTH_UUID = UUID.fromString("5d0b6f3a-7a53-4f1e-9a41-3c1f0a6b7e02");
    private static final UUID RANGE_UUID = UUID.fromString("5d0b6f3a-7a53-4f1e-9a41-3c1f0a6b7e03");
    private static final String TAG_ROLLED = "zombieai_rolled_v2";

    private ZombieAIEvents() {}

    // ------------------------------------------------------------ entity join

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (!ZombieScale.applies(entity) || !(entity instanceof Mob mob)) return;

        if (!event.getLevel().isClientSide()) {
            rollIfNeeded(mob);
            installGoals(mob);
            refreshFollowRange(mob);
        }
        // Both sides: re-evaluate the hitbox so EntityEvent.Size applies the UUID-derived scale.
        mob.refreshDimensions();
    }

    // ------------------------------------------------------ random attributes

    private static void rollIfNeeded(Mob mob) {
        CompoundTag data = mob.getPersistentData();   // saved with the entity -> no reroll after reload
        if (data.getBoolean(TAG_ROLLED)) return;

        RandomSource r = mob.getRandom();
        double speed = rollSpeed(r, ZombieScale.isTall(mob));
        double health = rollHealth(r);

        // MULTIPLY_TOTAL multiplies the final value, so "1.5x" really means 1.5x vanilla
        // (including baby-zombie / leader bonuses already applied).
        setModifier(mob, Attributes.MOVEMENT_SPEED, SPEED_UUID, "zombieai speed", speed - 1.0);
        setModifier(mob, Attributes.MAX_HEALTH, HEALTH_UUID, "zombieai health", health - 1.0);
        mob.setHealth(mob.getMaxHealth());

        data.putBoolean(TAG_ROLLED, true);
    }

    private static void setModifier(Mob mob, net.minecraft.world.entity.ai.attributes.Attribute attr,
                                UUID id, String name, double amount) {
        AttributeInstance inst = mob.getAttribute(attr);
        if (inst != null) {
            inst.removeModifier(id);   // replace, never stack duplicates (also upgrades v1 zombies once)
            inst.addPermanentModifier(new AttributeModifier(id, name, amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
    }

    static double rollSpeed(RandomSource r, boolean tall) {
        // Tall zombies (>2 blocks, ~5%) are always faster than any normal zombie (normal max is 2.0x).
        if (tall) return 2.1 + r.nextDouble() * 0.4;              // 2.1 - 2.5x
        double roll = r.nextDouble();
        if (roll < 0.50) return 1.5;                              // 50%
        if (roll < 0.70) return 1.2 + r.nextDouble() * 0.2;       // 20%: 1.2-1.4
        if (roll < 0.85) return 1.6 + r.nextDouble() * 0.4;       // 15%: 1.6-2.0
        if (roll < 0.95) return 1.05 + r.nextDouble() * 0.10;     // 10%: 1.05-1.15
        return 0.75 + r.nextDouble() * 0.20;                      //  5%: 0.75-0.95
    }

    static double rollHealth(RandomSource r) {
        double roll = r.nextDouble();
        if (roll < 0.50) return 1.0;   // 50%
        if (roll < 0.75) return 1.5;   // 25%
        if (roll < 0.90) return 2.0;   // 15%
        if (roll < 0.98) return 3.0;   //  8%
        return 4.0;                    //  2%
    }

    // --------------------------------------------------------------- AI hooks

    private static void installGoals(Mob mob) {
        boolean installed = mob.goalSelector.getAvailableGoals().stream()
                .anyMatch(w -> w.getGoal() instanceof InvestigateSoundGoal);
        if (installed) return;

        // Remove vanilla goals that track players regardless of walls:
        //  priority 1 = HurtByTargetGoal, priority 2 = NearestAttackableTargetGoal<Player>.
        // Villager/golem/turtle goals (priority 3 and 5) are left intact.
        mob.targetSelector.getAvailableGoals().removeIf(w ->
                (w.getPriority() == 1 && w.getGoal() instanceof HurtByTargetGoal)
                        || (w.getPriority() == 2 && w.getGoal() instanceof NearestAttackableTargetGoal<?>));

        PerceptionGoal perception = new PerceptionGoal(mob);
        mob.targetSelector.addGoal(1, perception);
        mob.goalSelector.addGoal(3, new InvestigateSoundGoal(mob, perception));
    }

    /**
     * Pathfinding range is limited by FOLLOW_RANGE (35 by default). If configured distances are
     * larger, raise it with a transient (non-saved) modifier so zombies can actually path that far.
     */
    public static void refreshFollowRange(Mob mob) {
        AttributeInstance inst = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (inst == null) return;
        AttributeModifier existing = inst.getModifier(RANGE_UUID);
        double current = inst.getValue() - (existing != null ? existing.getAmount() : 0.0);
        double needed = Math.max(ZombieAIConfig.sight(),
                ZombieAIConfig.hearing() + ZombieAIConfig.INVESTIGATE_RADIUS.get()) + 2.0;
        double add = Math.max(0.0, needed - current);

        if (existing != null && Math.abs(existing.getAmount() - add) < 0.01) return;
        if (existing != null) inst.removeModifier(RANGE_UUID);
        if (add > 0.0) {
            inst.addTransientModifier(new AttributeModifier(RANGE_UUID, "zombieai range", add,
                    AttributeModifier.Operation.ADDITION));
        }
    }

    /** Applies current config to every already-loaded zombie/husk (used by commands). */
    public static void refreshAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (ZombieScale.applies(e) && e instanceof Mob mob) {
                    refreshFollowRange(mob);
                }
            }
        }
    }

    // ------------------------------------------------------------ hitbox size

    /** Server AND client (when the mod is installed there): scale hitbox + eye height. */
    @SubscribeEvent
    public static void onSize(EntityEvent.Size event) {
        Entity e = event.getEntity();
        if (!ZombieScale.applies(e)) return;
        float s = ZombieScale.of(e);
        event.setNewSize(event.getNewSize().scale(s));
        event.setNewEyeHeight(event.getNewEyeHeight() * s);
    }

    // -------------------------------------------------------- misc / commands

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            HearingManager.refresh(server);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        HearingManager.forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        ZombieAICommand.register(event.getDispatcher());
    }
}
