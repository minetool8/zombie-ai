package com.example.zombieai.ai;

/**
 * What kind of noise a zombie heard. A zombie always goes to the HIGHER priority noise, even if a
 * lower priority one is much closer. Between noises of the SAME priority, the closer one wins.
 *
 * <pre>
 *   6  GUNSHOT   Zombie Awareness sounds (TACZ gunfire, mining, doors...)
 *   5  SHOUT     a player shouting in voice chat
 *   4  RADIO     a speaker / radio / audio block playing through Simple Voice Chat
 *   3  VOICE     a player talking normally   |   SPRINT: a sprinting player
 *   1  WHISPER   a whisper                   |   WALK:   walking / jumping
 * </pre>
 */
public enum NoiseKind {
    WHISPER(1),
    WALK(1),
    SPRINT(3),
    VOICE(3),
    RADIO(4),
    SHOUT(5),
    GUNSHOT(6);

    /** Priorities from this value up are "loud": they can pull a zombie away from a player it is looking at. */
    public static final int LOUD_FROM = 3;

    private final int priority;

    NoiseKind(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }

    public boolean loud() {
        return priority >= LOUD_FROM;
    }
}
