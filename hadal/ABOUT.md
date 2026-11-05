# Hadal

*A cozy, scary deep sea survival story for Android.*

**[Download the APK](release/Hadal.apk)** (Android 8.0+, OpenGL ES 3.0, works fully offline)

---

## The story

You wake up in **Lantern Station**, a small research habitat 58 meters under the sea. You slept for nine days. The rest of the crew is gone, and so is the surface ship. All that's left is **ARIA**, the station AI. She is kind, a bit nervous, and very fond of her kettle.

Docked under the station is the **Wren**, a small yellow research sub. The crew left a trail of data cores through the deep. They were following a sound coming up from the trench in the north: a low chord of three notes, repeating every eleven minutes.

Something else lives in the dark. The crew called it **the Pale**.

The story has seven chapters:

1. **Lantern.** Learn the sub, salvage scrap and build your first hull upgrade.
2. **The Relay.** Repair the shelf relay so ARIA can chart the sea again.
3. **Meridian.** Recover the logs from the wreck of the crew's support ship.
4. **Midnight.** Go below the sunlight, scan the strange life down there and meet the Pale.
5. **Cinder.** Find Dr. Brandt's outpost among the black smokers of the vent field.
6. **The Edge.** Wake the three descent beacons on the trench wall.
7. **Hadal.** Go down to the Choir at the bottom of the trench and decide what happens next.

After the ending the sea stays open for free diving. You can keep hunting the 24 **Echo Stones**, each holding one line of an old song. You can also finish the **Biodex** and make the station a home.

## The sea

| Zone | Depth | What it's like |
|---|---|---|
| Kelp Shelf | 40 to 130 m | Sunlight, caustics dancing on the sand, kelp forests, coral, glassfin schools and veil mantas |
| Twilight Slope | 130 to 420 m | The light fades to blue, then black. Sponges, wrecks, moon jellies and songwhales |
| Midnight Plains | 420 to 700 m | Total darkness lit by bioluminescence, manganese nodules, anglers, isopods and the Pale |
| Cinder Vents | 700 to 1000 m | Black smokers glowing orange, thermal crystals, tube worms, ember crabs and gulpers |
| Trench Rim | about 1000 m | Sheer walls, pale alloy and the descent beacons |
| The Hadal Deep | 1500 m+ | The Choir |

## Surviving

- **Hull, power and oxygen.** Thrusters, lamps, sonar and the shock pulse all drain power. Oxygen runs out over time; you can top it up at the surface or at the station.
- **Depth rating.** Each hull upgrade lets you go deeper. If you go below the rating, the hull creaks, groans and eventually implodes.
- **Noise and light.** Fast thrusting, boosting, sonar pings and crashes all make noise. Predators hear it, and the Pale hunts by it. Your lamp lets you see, but it also lets things see you. When the clicking starts, kill your lamp and drift.
- **Tools.** Flares distract predators. The shock pulse drives attackers away. Repair kits patch the hull out in the field.
- **Cargo.** Your sub can only carry so much. Dock to unload, recharge and refill your air. If you die, the station's recall drone brings the Wren home, but whatever was in the cargo hold is lost.

## Creatures (12 to scan)

| Peaceful | Dangerous |
|---|---|
| Glassfin | Moon Jelly |
| Veil Manta | Ribbon Eel |
| Songwhale | Black Angler |
| Giant Isopod | Gulper |
| Lantern Squid | The Pale |
| Ember Crab | |
| Choir Wisp | |

## The cozy part

Lantern Station is your home base.

- **Fabricator.** Build 12 upgrade lines, from hull plating Mk1 to Mk5, plus the battery, thrusters, floodlamp, sonar, cargo hold, oxygen tanks, flare launcher, shock pulse, thermal tap, silent running and bio scanner.
- **Comforts.** Build warm lamps, a hydroponic garden, an aquarium, a record player, a proper bunk, a kettle, pearl chimes and an echo stone shelf. The more you build, the cozier the station feels.
- **Logbook and Biodex.** Read every recovered crew log and every species you have scanned.

The game is meant to last weeks. Materials come from different depths and grow back slowly, and every upgrade opens up somewhere new.

## Controls

| Action | Touch | Controller | Keyboard + mouse |
|---|---|---|---|
| Move | Left stick (fixed) | Left stick | WASD |
| Look | Drag right side | Right stick | Mouse |
| Rise / sink | Arrow buttons | R2 / L2 | Space / Ctrl or C |
| Boost | Chevron button | Press left stick | Shift |
| Grab, dock, interact | Gold button | A | E or left click |
| Scan (hold) | Eye button | X | Q or right click |
| Lamp | Lamp button | Y | F |
| Sonar ping | Rings button | B | R |
| Flare / shock pulse | Buttons | L1 / R1 | G / V |
| Repair kit | Plus button | D-pad down | H |
| Camera (chase / cockpit) | Camera button | Press right stick | T |
| Map / pause | Buttons | D-pad up / Start | M / Esc |

## Graphics settings

| Preset | Resolution | Volumetrics | Lamp shadows | Textures | Notes |
|---|---|---|---|---|---|
| Low | 60% | Off | Off | Flat | No post effects. For old phones |
| Medium | 70% | Normal | Off | Textured | Bloom and light shafts |
| **High** | 80% + dynamic | Normal | On | Detailed | **Tuned for the Galaxy A54** |
| Ultra | 100% | High | Soft (2048) | Detailed | Everything maxed, plus FXAA |

You can also change each setting by hand:

- Frame cap of 30, 45, 60 or 90 FPS
- View distance
- Caustics
- Marine snow
- Sea life density
- Bloom
- Field of view
- FPS counter

## What makes it look real

- **Water absorbs light per color channel**, the way real water does: red disappears first, then green, until only blue is left.
- **Sunlight fades with depth.** The shelf is bright and the plains are pitch black.
- **The floodlamp's beam is ray-marched through the water.** It casts real shadows and lights up drifting marine snow.
- **The surface seen from below** shows Snell's window and total internal reflection.
- **Light shafts and caustics** move with the waves.
- **Bioluminescence and vent glow** bloom in HDR.
- **Post effects:** filmic tonemapping, chromatic fringe on the viewport glass, sensor static when the Pale is close, and a sonar wave that sweeps over the terrain.
- **Every texture is procedural and every sound is synthesized**, so the APK is tiny.

The music was made with Higgsfield.

## Building it yourself

See [README.md](README.md).
