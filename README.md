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
- `/voice calibrate`   (anyone, no op needed)
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

## Getting past walls
- **Wall hop** (`WallHopGoal`): a zombie that wants to go somewhere (chasing, or walking to a sound) but has stopped moving
  leaps onto the ledge in front of it, onto a 1-block ledge only (jump apex ~1.5 blocks; fences and walls can no longer be hopped). It only fires when a free landing
  spot with a solid floor exists on top of the obstacle, so it never jumps blindly. Config: `movement.wallHopEnabled`.
- **Smart approach** (`ApproachResolver`): if a sound is 3+ blocks above the zombie and no walkable path leads up to it
  (player on a roof edge, cliff, tower) the zombie walks to the ground directly **beneath** the sound instead of hunting for
  the way to the same edge. If a real path exists (stairs, ramps) it takes it. Beneath the sound it waits ~5 seconds and
  hops up if the ledge is low enough. Config: `movement.smartApproach`.

## Close noticing, shout rage, no knockback
- A zombie that is awake or walking to a sound targets the nearest player within `combat.noticeRadius` (default 4 blocks)
  that it has a clear line to, even if someone else made the sound. A far sound never pulls it off such a player.
  This also fixes zombies that stopped 1 block from a player without attacking.
- **Shout rage**: a zombie that hears a shout gets +25% speed and breaks blocks in its way (75% faster than its base
  breaking speed) for `combat.rageSeconds` (default 30, refreshed by every shout). Respects `mobGriefing`.
  Obsidian-hard blocks and bedrock are never broken (`combat.breakMaxHardness`).
- **Unstick** (`UnstickGoal`): a zombie that stands still although the straight line to its goal is physically free
  (e.g. modded non-solid blocks the pathfinder treats as blocked) walks straight there.
- Zombies and husks take no knockback.
