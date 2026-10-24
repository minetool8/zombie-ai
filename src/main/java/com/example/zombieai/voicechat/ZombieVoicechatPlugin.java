package com.example.zombieai.voicechat;

import com.example.zombieai.ZombieAIMod;
import com.example.zombieai.ai.HearingManager;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loaded by Simple Voice Chat only when it is installed (it scans mods for @ForgeVoicechatPlugin).
 *
 * Privacy: each 20 ms voice frame is decoded in memory ONLY to compute one loudness number (dB).
 * The audio is then discarded. Nothing is recorded, stored, forwarded or analysed for words,
 * and the event is never cancelled, so normal voice chat is unaffected.
 */
@ForgeVoicechatPlugin
public class ZombieVoicechatPlugin implements VoicechatPlugin {
    /** One Opus decoder per player (decoders keep state between frames). */
    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();

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
        UUID id = connection.getPlayer().getUuid();
        HearingManager.markSpeaking(id, measureDb(event, id));
    }

    /** Loudness of this frame in dBFS (0 = loudest, -100 = silent), or NaN if it could not be measured. */
    private double measureDb(MicrophonePacketEvent event, UUID id) {
        try {
            byte[] data = event.getPacket().getOpusEncodedData();
            OpusDecoder decoder = decoders.get(id);
            if (data == null || data.length == 0) {          // end-of-speech marker
                if (decoder != null) {
                    synchronized (decoder) {
                        decoder.resetState();
                    }
                }
                return Double.NaN;
            }
            if (decoder == null) {
                decoder = event.getVoicechat().createDecoder();
                decoders.put(id, decoder);
            }
            short[] pcm;
            synchronized (decoder) {
                pcm = decoder.decode(data);
            }
            if (pcm == null || pcm.length == 0) {
                return Double.NaN;
            }
            double sum = 0.0;
            for (short s : pcm) {
                sum += (double) s * s;
            }
            double rms = Math.sqrt(sum / pcm.length) / 32768.0;
            return rms < 1.0E-5 ? -100.0 : 20.0 * Math.log10(rms);
        } catch (Throwable t) {
            return Double.NaN;   // a measuring problem must never break voice chat
        }
    }
}
