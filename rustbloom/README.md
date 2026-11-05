# Rustbloom

A native Kotlin + OpenGL ES 3 open-world survival game for Android, with a story. It runs fully offline.

Centuries after the old world fell, its machines still roam the wilds. You wake in the Cradle-7 vault with ECHO, the vault's caretaker AI. Survive, build a home, hunt machines for parts, wake the four Spires and silence HALCYON at the Crown.

## Install
Copy `release/Rustbloom.apk` to your phone and open it. You need to allow installs from unknown sources. Requires Android 8.0+ and OpenGL ES 3.0.

## Features
- A 3 km procedural island. Biomes: meadows, Hollowwood forest, taiga, snowy peaks, the Rust Flats desert and the Sunken Fen marsh. There are 60+ points of interest: spires, the relay, the Crown, 24 ruins with echo logs, supply caches and machine graveyards.
- Survival stats: health, hunger, thirst, stamina and body temperature. Clothing, fire, shelter, weather and biome all affect temperature.
- Day and night cycle with weather: clear, overcast, rain, storms with lightning, fog, and snow in the north.
- Gathering, crafting at 4 stations, and grid-snapping base building: foundations, walls, doorways, roofs, beds, chests, rain catchers, spikes and shock pylons.
- Wildlife and machines with their own AI: Grazehorn, Tusker, Strider, Scuttler, Prowler, Warden, plus the Colossus boss. Machines have glowing weak spots and come in biome variants.
- Melee weapons and bows, with fire and shock arrows.
- 13 story quests, 24 echo logs, daily bounties, a 15-skill tree, and leveling.
- Autosave. Saves are written atomically with a backup copy.
- Controls: touch, Xbox and PlayStation controllers, or keyboard and mouse with pointer capture. Pick the scheme in Settings.
- Graphics presets (Low, Medium, High, Ultra) plus manual options: render resolution, view distance, soft or hard shadows, shadow resolution, grass density, water quality, terrain detail, clouds, particles, FPS cap (30/60/90/120) and FOV.

The High preset targets 60 FPS on a Galaxy A54. If the phone gets hot, use Medium.

## Build
```
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest   # headless world + gameplay simulation tests
```
The release build is signed with `keystore/rustbloom.jks` (password `rustbloom`). Because the key stays the same, a new version installs over the old one and keeps your save.
