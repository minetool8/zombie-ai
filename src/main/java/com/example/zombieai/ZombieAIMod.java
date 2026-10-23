package com.example.zombieai;

import com.example.zombieai.config.ZombieAIConfig;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(ZombieAIMod.MODID)
public class ZombieAIMod {
    public static final String MODID = "zombieai";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ZombieAIMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ZombieAIConfig.SPEC, "zombieai-common.toml");
        FMLJavaModLoadingContext.get().getModEventBus().addListener(ZombieAIConfig::onConfigEvent);
    }
}
