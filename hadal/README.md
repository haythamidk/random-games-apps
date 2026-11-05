# Hadal

A native Kotlin + OpenGL ES 3 deep sea survival horror game for Android, with a story. It runs fully offline. See [ABOUT.md](ABOUT.md) for the story, creatures and controls.

## Install
Copy `release/Hadal.apk` to your phone and open it. You need to allow installs from unknown sources. Requires Android 8.0+ and OpenGL ES 3.0.

## Tech
- **Renderer:**
  - HDR scene target with a sampleable depth texture.
  - Per-channel Beer-Lambert water absorption, plus in-scattering that depends on depth.
  - Caustics and an open-water sky that shows Snell's window.
  - Shadowed spot floodlamp.
  - Half-resolution ray-marched volumetrics: lamp beam, sun shafts and flare glow.
  - Marine snow particles and additive glow sprites.
  - Bloom, ACES tonemapping, chromatic fringe, a sonar sweep, FXAA and dynamic resolution.
- **World:**
  - Deterministic ocean floor, streamed in 64 m chunks with 3 LODs on worker threads.
  - 21 instanced flora and resource types, with a current sway in the vertex shader.
  - Procedural wrecks, the station, an outpost, beacons and the Choir.
- **Simulation:**
  - Sub physics with collisions against terrain, structures and rocks.
  - Hull, power and oxygen.
  - Depth ratings, noise and detectability.
  - 12 species: boid schools, ambushers, a sound-hunting charger and the Pale stalker director.
  - A 7-chapter story with 12 logs and 24 echo stones.
  - A JSON save with atomic writes and a backup.
- **Audio:**
  - Software mixer on AudioTrack with distance muffling and positional sounds.
  - Synthesized effect variants and ambience beds that follow the dive.
  - Music track `app/src/main/assets/abyss_theme.ogg`, made with Higgsfield.

## Build
```
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest   # headless world, story and ecology simulation tests
```
Set `HADAL_DUMP=<dir>` (plus `HADAL_X`, `HADAL_Z`, `HADAL_TAG`) and run `SceneDumpTest` to dump real geometry for offline shader previews.
