package com.example.zombieai.client;

import com.example.zombieai.ZombieAIMod;
import com.example.zombieai.ai.ZombieScale;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client-only: scales the MODEL. The hitbox is handled by EntityEvent.Size (server + client). */
@Mod.EventBusSubscriber(modid = ZombieAIMod.MODID, value = Dist.CLIENT)
public final class ClientScaleHandler {
    private ClientScaleHandler() {}

    @SubscribeEvent
    public static void onRenderPre(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (!ZombieScale.applies(entity)) return;
        float s = ZombieScale.of(entity);
        event.getPoseStack().scale(s, s, s);
    }
}
