package com.example.zombieai.voicechat;

import com.example.zombieai.ZombieAIMod;
import com.example.zombieai.ai.HearingManager;
import com.example.zombieai.ai.RadioSense;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
        // Speakers / radios: sound packets that were NOT made by a player's microphone.
        registerRadioEvent(registration, "de.maxhenkel.voicechat.api.events.LocationalSoundPacketEvent", MODE_LOCATION);
        registerRadioEvent(registration, "de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent", MODE_ENTITY);
        registerRadioEvent(registration, "de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent", MODE_STATIC);
    }

    // ------------------------------------------------------------ speakers / radios
    // A sound packet with no sender player was made by another mod or plugin (speaker, radio, boombox...),
    // not by a player talking. Only the position / entity id is remembered, never the audio.
    //
    // These events are looked up by name and called by reflection on purpose: the mod then always compiles
    // and loads, whatever the exact Simple Voice Chat API version is. If something does not match, a warning
    // is logged once and radios are simply not detected.

    private static final int MODE_LOCATION = 0;
    private static final int MODE_ENTITY = 1;
    private static final int MODE_STATIC = 2;

    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();
    private static volatile boolean warned;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerRadioEvent(EventRegistration registration, String className, int mode) {
        try {
            Class eventClass = Class.forName(className);
            Consumer handler = event -> onSoundPacket(event, mode);
            registration.registerEvent(eventClass, handler);
        } catch (Throwable t) {
            ZombieAIMod.LOGGER.warn("Zombie AI: speaker/radio detection unavailable for {} ({})", className, t.toString());
        }
    }

    private void onSoundPacket(Object event, int mode) {
        try {
            if (call(event, "getSenderConnection") != null) return;   // a player's voice, not a speaker

            if (mode == MODE_ENTITY) {
                Object packet = call(event, "getPacket");
                RadioSense.markEntity((UUID) call(packet, "getEntityUuid"));
                return;
            }
            Object receiver = call(event, "getReceiverConnection");
            if (!(receiver instanceof VoicechatConnection connection) || connection.getPlayer() == null) return;
            UUID receiverId = connection.getPlayer().getUuid();

            if (mode == MODE_LOCATION) {
                Object packet = call(event, "getPacket");
                Object pos = call(packet, "getPosition");
                RadioSense.markLocation(receiverId,
                        ((Number) call(pos, "getX")).doubleValue(),
                        ((Number) call(pos, "getY")).doubleValue(),
                        ((Number) call(pos, "getZ")).doubleValue());
            } else {
                RadioSense.markStatic(receiverId);
            }
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                ZombieAIMod.LOGGER.warn("Zombie AI: could not read a speaker/radio sound packet ({}). "
                        + "Radios will not attract zombies.", t.toString());
            }
        }
    }

    /** Calls a public no-argument method by name, through a public interface (the impl classes are not public). */
    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method m = METHODS.get(target.getClass().getName() + "#" + name);
        if (m == null) {
            m = findMethod(target.getClass(), name, new HashSet<>());
            if (m == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name + "()");
            METHODS.put(target.getClass().getName() + "#" + name, m);
        }
        return m.invoke(target);
    }

    private static Method findMethod(Class<?> type, String name, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) return null;
        if (java.lang.reflect.Modifier.isPublic(type.getModifiers())) {
            try {
                return type.getMethod(name);
            } catch (NoSuchMethodException ignored) {
                // keep looking in the interfaces / superclass
            }
        }
        for (Class<?> i : type.getInterfaces()) {
            Method m = findMethod(i, name, visited);
            if (m != null) return m;
        }
        return findMethod(type.getSuperclass(), name, visited);
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
