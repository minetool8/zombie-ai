package com.example.zombieai.voicechat;

import com.example.zombieai.ZombieAIMod;
import com.example.zombieai.ai.HearingManager;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;

/**
 * Loaded by Simple Voice Chat only when it is installed (it scans mods for @ForgeVoicechatPlugin).
 *
 * Privacy: we never call getPacket(), never read the opus data, never cancel the event.
 * We only note WHO sent a packet and WHEN (see HearingManager).
 */
@ForgeVoicechatPlugin
public class ZombieVoicechatPlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return ZombieAIMod.MODID;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        HearingManager.voiceChatPluginLoaded = true;
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    private void onMicrophone(MicrophonePacketEvent event) {
        VoicechatConnection connection = event.getSenderConnection();
        if (connection == null || connection.getPlayer() == null) {
            return;
        }
        HearingManager.markSpeaking(connection.getPlayer().getUuid());
    }
}
