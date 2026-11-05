# Rustbloom

A native Kotlin + OpenGL ES 3 open-world survival game for Android, with a story. It runs fully offline.

Centuries after the old world fell, its machines still roam the wilds. You wake in the Cradle-7 vault with ECHO, the vault's caretaker AI. Survive, build a home, hunt machines for parts, wake the four Spires and silence HALCYON at the Crown.

## Install
Copy `release/Rustbloom.apk` to your phone and open it. You need to allow installs from unknown sources. Requires Android 8.0+ and OpenGL ES 3.0.

## Features
- A 3 km procedural island. Biomes: meadows, Hollowwood forest, taiga, snowy peaks, the Rust Flats desert and the Sunken Fen marsh. There are 60+ points of interest: spires, the relay, the Crown, 24 ruins with echo logs, supply caches and machine graveyards.
- Graphics:
  - HDR rendering with bloom, filmic tonemapping and time-of-day color grading.
  - Textured terrain with height blending and normal maps. All 16 textures are generated in code, then cached.
  - Leafy trees with wind and translucency.
  - Atmospheric sky with lit clouds and stars. Fog uses the sky color.
  - Gerstner-wave water with refraction and shoreline foam.
  - Soft and cascaded shadows, FXAA, and dynamic resolution.
- Survival stats: health, hunger, thirst, stamina and body temperature.
- Day and night cycle with weather: clear, overcast, rain, storms with lightning, fog, and snow in the north.
- Wildlife and machines:
  - Animals: Grazehorns, Tuskers, hares, foxes, crag goats, wolf packs, bears, songbird flocks, fish and sharks.
  - Machines: Striders, Scuttlers, Prowlers, Wardens, flying Skyrays, amphibious Tidecrawlers and the Colossus.
  - Creatures respect slopes, fleeing animals tire and can be caught, birds fly and fish swim.
- Fishing, plus crafting at 4 stations and grid-snapping base building.
- 13 story quests, 24 echo logs, daily bounties, a 15-skill tree, and leveling.
- Audio is fully synthesized and mixed in real time:
  - Positional stereo sound effects, each with several variants and random pitch so they don't repeat.
  - Footsteps change with the surface.
  - Wind follows exposure, and surf only plays near the coast.
  - Wildlife ambience: birds, crickets, owls, frogs, gulls and wolves.
  - Rare, quiet generative music.
- Controls: touch with a fixed joystick, Xbox/PlayStation controllers (LB/RB switch menu tabs), or keyboard and mouse.
- Graphics presets: Low, Medium, High (realistic look at 60 FPS on a Galaxy A54) and Ultra (maximum realism), plus every option can be set by hand.
- Thread pools are sized to the device's cores. World generation, textures and audio each run on their own background threads.

## Build
```
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest   # headless world + gameplay simulation tests
```
The release build is signed with `keystore/rustbloom.jks` (password `rustbloom`). Because the key stays the same, a new version installs over the old one and keeps your save.
