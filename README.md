# Zombie AI Overhaul (Forge 1.20.1, Java 17)

## Build
1. Install JDK 17.
2. Copy the Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/`) from the official Forge 1.20.1 MDK
   into this folder (or run `gradle wrapper --gradle-version 8.1.1` if you have Gradle installed).
3. Check `voicechat_api_version` in `gradle.properties` against https://maven.maxhenkel.de/repository/public
4. `./gradlew build` -> jar in `build/libs/`. Test with `./gradlew runServer` / `runClient`.
5. Put Simple Voice Chat (Forge 1.20.1) in `run/mods/` to test hearing.

## Commands (permission level configurable, default 2)
- `/zombieai sight <distance>`
- `/zombieai hearing <distance>`
- `/zombieai status`   (anyone)
- `/zombieai reload`
Settings persist in `config/zombieai-common.toml`.

## What is and isn't scaled
- Hitbox width/height and eye height: scaled via EntityEvent.Size (server + any client with the mod).
- Model: scaled in a client render hook (client with the mod only).
- NOT scaled: step height, attack reach, shadow, sounds. Clients without the mod see normal-size models.
- Size is derived from the entity UUID, so it is stable across save/load and cannot be re-rolled.
- Vanilla clients: the mod can be server-only (they just won't see bigger models).

## Senses
- **Sight:** zombies are blind. They only see a player within **1 block** with a clear line of sight (fixed, cannot be raised).
- **Hearing** is their real sense. Noise priority (a zombie goes to the higher one even if a lower one is closer;
  same priority = the closer one wins):
  1. gunshots / Zombie Awareness sounds (TACZ gunfire, mining, doors)
  2. shouting
  3. speakers / radios playing through Simple Voice Chat
  4. normal talking (and sprinting)
  5. walking / whispering
- Speakers/radios are detected from Simple Voice Chat sound packets that do not come from a player's microphone.
  `/zombieai debug` shows how many such packets the server has seen (if it stays 0 next to a playing radio, tell me which radio mod you use).
