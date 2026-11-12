# Scrapline (native)

The native Android build of Scrapline: Kotlin + libGDX, no web view. A first-person desert survival and automation game: gather, build, automate with drills and belts, wire sensors to turrets on a touch circuit board, and hold the night.

It is an original game in its genre. Models, map, HUD and names are its own.

## Layout

- `core/` – everything shared: `sim/` (deterministic 20 Hz simulation, no rendering), `view/` (renderer, shaders, models), `ui/` (HUD, menus, circuit board, touch handling)
- `android/` – Android launcher and packaging
- `desktop/` – desktop launcher, also used for automated play-through tests
- `assets/` – the UI font (Inter, SIL Open Font License)

## Build and test

Needs JDK 21 and Gradle 8.14; the Android build also needs the Android SDK (platform 36) with `ANDROID_HOME` set.

```bash
gradle :core:test
```

```bash
gradle :android:assembleDebug
```

The APK lands in `android/build/outputs/apk/debug/android-debug.apk`.

```bash
gradle :desktop:run
```

Desktop controls: WASD, right-drag to look, E to act, 1–6 for the hotbar, F3 for the performance readout, or click the on-screen touch controls.

```bash
xvfb-run -a -s "-screen 0 1700x800x24" gradle :desktop:run --args="--smoke $PWD/shots"
```

That last command plays through the whole loop with synthetic touches on a machine with no screen, checks 53 results and writes screenshots to `shots/`.

## Status

Verified by 23 simulation tests and the 53-check scripted play-through under software rendering. The APK builds and was inspected, but it has not been run on a real phone yet, so first launch, touch feel and frame rate on device are unverified.
