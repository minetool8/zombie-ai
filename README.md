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
