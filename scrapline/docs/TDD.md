# Scrapline — Technical Design Document

Working title: **Scrapline**. Android-first, first-person survival and automation game with a touch-native logic wiring system.

Status: plan only, no code yet. Build starts 2026-11-11 17:45 GMT+4 (see `ROADMAP.md`).

---

## 0. Scope and positioning

The brief asked for the core experience of the PC game Mechanica on Android. This project does **not** recreate that game. It is an original game in the same genre (survival + factory automation + player-authored logic), with its own name, setting, art direction, HUD layout, item names and level content. What it shares with the reference is genre-level mechanics only:

| Genre mechanic (kept) | Our own take |
|---|---|
| Gather raw resources by hand | Same loop, own resource set and world |
| Place machines that process items without the player | Drill, belt, smelter, assembler, crate |
| Wire sensors to actuators with logic in between | Touch-first "circuit board" editor plus in-world link tool |
| Hostile machines attack the base | "Strays": feral survey drones, night raids |
| Survival vitals with consumables | Health, Charge, **Warmth** (heat comes from your own machines) |

Everything visual and textual is designed from scratch. The uploaded screenshots were used only to understand what kinds of systems the genre needs on screen (vitals, hotbar, machine panels, interaction prompt), not as a layout or art target.

### MVP definition

A vertical slice, playable in one 15–20 minute session on a mid-range Android phone:

1. Walk around a small map, gather wood, stone, ore and coal.
2. Hand-craft tools and machine kits, place them on a build grid.
3. Build a drill → belt → smelter → crate chain that runs unattended.
4. Wire a proximity sensor through a logic node to a turret, and survive one night raid.

Out of scope for the MVP: multiplayer, vehicles, procedural infinite world, save migration, story, audio beyond a handful of SFX, monetisation, iOS.

---

## 1. Research synthesis

### 1.1 What the genre needs on screen

From the reference footage and store material, a game of this type surfaces five UI layers. Each one is redesigned for a 6-inch landscape touchscreen:

| Layer | PC convention | Scrapline on touch |
|---|---|---|
| Vitals | Thin bars in a screen corner | Three segmented gauges top-left, inside the safe area, readable at arm's length |
| Hotbar | 9 slots, number keys | 6 slots bottom-centre, 56 dp each, tap to select, swipe up on the bar to open the bag |
| Interaction prompt | "Press key to ..." text | A single context button above the right thumb whose icon and label change with the target (Gather, Open, Link, Rotate) |
| Machine panel | Mouse drag between slots | Bottom sheet with large slots; tap a stack to move it, tap-hold for half, no drag required |
| Logic wiring | Mouse wires between objects in world | Two entry points: in-world Link tool for simple A→B, and a full-screen circuit board for anything with logic nodes |

### 1.2 Touch node editors: findings

- Ports sized for a mouse are the main failure on touch. Hit areas must be far larger than the drawn dot.
- A finger dragging a wire hides the drop target. A two-step "tap source, then tap destination" flow avoids occlusion; drag can remain as an optional shortcut.
- Panning and node dragging must not fight. Convention: one finger on empty canvas pans, one finger on a node moves it, two fingers pinch-zoom.
- Side panels do not fit; node palettes and inspectors belong in a bottom sheet.
- Existing edges should be selectable (tap) and then deletable or re-routable through an explicit handle, since there is no right-click.

These drive section 5.4.

### 1.3 Mobile WebGL: findings

- Android WebView performance varies widely by device and WebView version; nothing can be assumed from desktop results. Budgets in section 8 are conservative and there is a dynamic resolution fallback.
- The two most effective levers are pixel ratio (cap at 1.0–1.5) and avoiding full-resolution post-processing. We ship with no post-processing at all.
- Instancing is necessary for draw-call count but is not free on every mobile GPU, so instance counts are budgeted as well.
- WebGL context loss happens on Android when the app is backgrounded; the renderer must rebuild from simulation state.

---

## 2. Engine and platform decision

### 2.1 Options evaluated

| Option | Authoring in a cloud-only, text-only workflow | Can be run and verified in the cloud session | Android delivery | Verdict |
|---|---|---|---|---|
| Unity + URP | Poor. Scenes, prefabs and import settings assume the editor; licence activation and multi-GB installs in CI | No | Good once built | Rejected |
| Godot 4 | Fair. Scenes are text but hand-writing `.tscn` is fragile | Headless logic tests only, no visual check | Good; needs export templates + Android SDK in CI | Fallback |
| **Three.js + TypeScript + Vite, wrapped with Capacitor** | Good. Everything is code | **Yes**: headless Chromium with touch emulation, screenshots, and FPS probes | Capacitor Android project built to APK in GitHub Actions; the same build is also playable from a URL in mobile Chrome | **Chosen** |
| 2D-first prototype | Good | Yes | Good | Not needed, see below |

### 2.2 Why not prototype in 2D first

The risk a 2D prototype would retire is "are the systems fun and correct", not "can the phone render it". We get the same risk reduction by making the simulation a **headless TypeScript core with no rendering dependency**, unit-tested on its own. The 3D view is a thin presentation layer over it. If WebView performance on the target device turns out to be unacceptable, the core moves unchanged under a top-down 2D renderer or a Godot front end.

### 2.3 Stack

- TypeScript (strict), Vite, Three.js. No physics engine: collision is heightmap + grid-aligned boxes + capsule player, written by hand.
- UI: plain DOM/CSS overlay for HUD, sheets and the circuit board (Canvas 2D for the board itself). No UI framework.
- Tests: Vitest for the simulation core; Playwright smoke tests with a phone viewport and synthetic touch.
- Packaging: Capacitor (Android), landscape-locked, immersive fullscreen.
- Saves: localStorage, single slot, versioned.

### 2.4 Layering

```
src/
  core/        pure simulation, no DOM, no three.js (deterministic, fixed tick)
    items.ts recipes.ts inventory.ts
    world.ts grid.ts machines.ts belts.ts
    logic/     graph.ts nodes.ts eval.ts
    enemies.ts combat.ts vitals.ts daycycle.ts
    save.ts
  view/        three.js scene built from core state
    renderer.ts terrain.ts instances.ts lod.ts pools.ts fx.ts
  input/       touch → intents (move, look, primary, interact, build)
  ui/          hud.ts hotbar.ts sheets.ts board.ts (circuit editor)
  main.ts      loop: input → core.tick(n) → view.sync → ui.sync
```

Rule: `core/` never imports from `view/`, `input/` or `ui/`. The view reads core state and subscribes to a small event queue (item spawned, machine placed, shot fired).

---

## 3. Core gameplay loop

```
        ┌──────────────────────────────────────────────────────────┐
        ▼                                                          │
   GATHER ──► BUILD ──► AUTOMATE ──► DEFEND ──► (loot + new tier) ─┘
   by hand    kits on    machines     wire sensors
              the grid   feed each    to turrets,
                         other        hold the night
```

| Phase | Player action | System | MVP content |
|---|---|---|---|
| Gather | Hold the action button on a node | Resource nodes with HP and yield, tool tier multipliers | Deadwood, stone outcrop, ore vein, coal seam |
| Build | Craft a kit, enter build mode, place on 1 m grid | Recipes, placement validation, rotation | Workbench, crate, wall, drill, belt, smelter, generator |
| Automate | Lay belts between machines | Fixed-tick machine and belt simulation | Ore → ingot line feeding a crate |
| Defend | Place sensor and turret, wire them | Logic graph evaluation, enemy AI, day/night | Proximity sensor, AND/NOT/Delay/Timer nodes, turret, lamp, siren; one enemy type |

### Session pacing target

| Minute | Beat |
|---|---|
| 0–3 | Gather by hand, craft pick and workbench |
| 3–8 | First smelter, hand-fed; cold starts to matter, so the base forms around the heat |
| 8–13 | Drill and belts, line runs unattended |
| 13–17 | Dusk warning; build and wire the turret |
| 17–20 | Night raid; survive to dawn = slice complete |

### Vitals (the original twist)

- **Health**: damage from strays and falls. Restored by ration packs.
- **Charge**: suit battery. Drained by tools and sprinting. Restored by cells, or by standing near a running generator.
- **Warmth**: drains outdoors, four times faster at night. Restored inside the heat radius of a campfire (available from the first minute), and later of a running smelter or generator. This ties survival directly to the base rather than to a separate consumable loop.

---

## 4. Simulation model

- Fixed tick: **20 Hz** for machines, belts, enemies, vitals. Logic graph evaluates at **10 Hz** (every second tick). Rendering interpolates.
- Deterministic: integer or fixed-point state in the core, one seeded PRNG. Makes tests reproducible and saves small.
- Catch-up: at most 5 ticks per frame; beyond that the sim slows rather than spiralling.
- No allocation inside `tick()`. All per-tick scratch storage is preallocated.
- Exception to the fixed tick: **player movement and camera run once per rendered frame**, so touch input has no added latency. Everything else in the world advances on the tick.

### 4.1 World

- One hand-tuned 256 × 256 m map generated from a fixed seed (heightmap + scatter). Build grid is 1 m cells on a single build layer (machines sit on terrain height, snapped).
- `grid: Uint16Array(256*256)` holds the entity id occupying each cell (0 = empty). Placement validation and neighbour lookup are O(1).
- Entities (machines, sensors, walls) live in struct-of-arrays tables indexed by entity id, max 2048.

### 4.2 Belts

- A belt tile holds up to 4 item slots along its length. State per tile: `items: Uint16[4]`, direction (2 bits), powered flag.
- Each tick, items advance one slot if the next slot (or the next tile's first slot, or the target machine's input) is free. Tiles are processed in reverse flow order from a cached ordering so a full line moves in one pass; the ordering is rebuilt only when belts are placed or removed.
- Items on belts are **not** entities. They are rendered from the tile arrays through one instanced mesh per item type.

### 4.3 Machines

Common state per machine: `kind`, `recipe`, `progress`, `enabled` (driven by logic), `in[]`, `out[]`, `fuel`.

| Machine | Behaviour |
|---|---|
| Drill | Must sit on a deposit. Emits 1 raw item per N ticks to its output side |
| Smelter | Takes ore + fuel, emits ingot. Emits heat radius while running |
| Assembler | Runs one selected recipe from input buffer |
| Crate | 16-slot container; accepts from belts, feeds belts |
| Generator | Burns coal, provides Charge aura and heat |

Every machine exposes an `enabled` logic input and a `busy`/`full` logic output so it can participate in the wiring system without special cases.

---

## 5. Visual programming system

### 5.1 Concepts

- A **node** is anything with ports: world devices (sensor, turret, door, lamp, siren, belt, any machine) and pure logic nodes that exist only on the board (AND, OR, NOT, Delay, Timer, Counter, Compare, Latch).
- A **port** is an input or output on a node. Max 4 inputs and 4 outputs per node.
- A **wire** connects one output port to one input port. Fan-out is unlimited; fan-in on one input is combined (OR for boolean, max for numeric).
- A **signal** is a single `Int16`. Boolean is 0 / 1. Numeric ports (distance, count) use the full range. One type keeps storage flat and makes every port connectable to every other, which removes a whole class of "why won't this connect" friction on touch.

### 5.2 Memory representation

Capacity is fixed at startup: 1024 nodes, 4096 wires. All storage is typed arrays, allocated once.

```ts
// Nodes (struct of arrays), index = nodeId
nodeType   : Uint8Array(1024)      // enum NodeType, 0 = free slot
nodeEntity : Uint16Array(1024)     // world entity id, 0 for pure logic nodes
nodeParam  : Int16Array(1024 * 2)  // two config values per node (delay ticks, threshold, ...)
nodeState  : Int16Array(1024 * 2)  // two words of private state (timer count, latch bit, ...)
nodeBoardX : Int16Array(1024)      // editor position, board units
nodeBoardY : Int16Array(1024)

// Signals, double buffered
outCur     : Int16Array(1024 * 4)  // output port values visible this tick
outNext    : Int16Array(1024 * 4)  // values being written for next tick
inCache    : Int16Array(1024 * 4)  // combined input values, refreshed when a source changes

// Wires, one packed word each
// bits 0-9 srcNode | 10-11 srcPort | 12-21 dstNode | 22-23 dstPort
wires      : Uint32Array(4096)
wireCount  : number

// Adjacency in CSR form, rebuilt on edit only
outStart   : Uint16Array(1024 * 4 + 1)  // per output port, start index into outList
outList    : Uint16Array(4096)          // packed dst (node << 2 | port)

// Scheduling
dirty      : Uint16Array(1024)     // ring buffer of node ids to evaluate
dirtyFlag  : Uint8Array(1024)      // dedupe
```

Total footprint is about 60 KB regardless of what the player builds. No objects per node, no per-tick allocation, nothing for the garbage collector to find.

### 5.3 Evaluation

- **Event driven.** A node is evaluated only if one of its inputs changed last tick, or it has declared itself time-dependent (Delay, Timer, sensors). An idle base costs nothing.
- **Double buffered, one tick of latency per node.** Nodes read `outCur` via `inCache`, write `outNext`, then the buffers swap. This means:
  - evaluation order does not matter, so no topological sort and no rebuild cost beyond CSR on edit;
  - feedback loops are legal and well defined (latches and oscillators work, the same way they do in real sequential logic);
  - behaviour is deterministic and unit-testable.
- At 10 Hz, one node of latency is 100 ms; a sensor → AND → turret chain reacts in 0.3 s, which reads as instant in play.
- Each node type is one pure function `(inputs, params, state) → outputs` in a table indexed by `nodeType`. Adding a node type is one table entry plus a board icon.
- World coupling goes through two small adapters per tick: sensors write their readings into their output ports before evaluation; actuators read their input ports after evaluation and set `enabled`/`fire` on the entity.

### 5.4 Touch editing

Two entry points, same graph underneath.

**A. Link tool (in world)** — for the 80 % case of "this triggers that".
1. Select the Link tool on the hotbar. Devices in view show a ring.
2. Tap a source device. A line follows the reticle.
3. Tap a target device. A wire is created between default ports (first output → `enabled`/`fire` input). If either side has more than one sensible port, a two-row chip picker appears above the thumb.
4. Existing wires render as thin lines in world while the tool is held; tapping one offers Remove.

**B. Circuit board (full screen)** — for logic.
- Opened from any device's panel ("Open circuit"), showing that device and everything wired to it.
- Canvas 2D, pan with one finger on empty space, pinch to zoom, drag a node body to move it. Positions snap to an 8-unit grid.
- **Ports**: drawn 20 dp, hit area 56 dp, and the hit area extends outward from the node so adjacent ports never overlap. At low zoom, tapping a node expands it into a large port fan before any wiring happens.
- **Wiring is tap-tap**: tap an output port, every compatible input pulses, tap one to connect. Dragging from a port also works and shows the wire end offset 48 dp above the finger with a snap radius, so the target is never under the fingertip.
- **Wires**: tap to select (thick highlight), then a floating chip offers Remove. Wires carrying a non-zero signal animate, so the board doubles as a live debugger.
- **Palette**: bottom sheet with logic nodes; tap to drop at screen centre.
- **Inspector**: tapping a node's gear opens a sheet with its one or two parameters as steppers and sliders, never text entry.
- Undo/redo stack of 32 edits. Edits apply to the live graph immediately; there is no compile step.

### 5.5 MVP node set

| Kind | Node | Ports |
|---|---|---|
| Sensor | Proximity sensor | out: detected, distance |
| Sensor | Switch (player toggled) | out: on |
| Logic | AND, OR | in: a, b → out |
| Logic | NOT | in → out |
| Logic | Delay | in → out, param: ticks |
| Logic | Timer | in: run → out: pulse, param: period |
| Actuator | Turret | in: fire, out: ammo low |
| Actuator | Lamp, Siren | in: on |
| Actuator | Any machine or belt | in: enabled, out: busy |

Counter, Compare and Latch are specified but land after the slice.

---

## 6. Inventory and crafting data

### 6.1 Definitions (static, authored as tables)

```ts
type ItemId = number;            // u16, 0 = empty

interface ItemDef {
  id: ItemId;
  key: string;                   // 'ore_iron'
  name: string;
  stack: number;                 // max per slot, 1 for tools
  kind: 'resource' | 'part' | 'tool' | 'kit' | 'consumable';
  places?: EntityKind;           // for kits: what it becomes when placed
  tool?: { tier: number; gathers: number };   // bitmask of node classes
  use?: { health?: number; charge?: number; warmth?: number };
}

interface Recipe {
  id: number;
  station: 'hand' | 'workbench' | 'smelter' | 'assembler';
  inputs: readonly [ItemId, number][];   // max 3
  output: readonly [ItemId, number];
  ticks: number;                 // duration at 20 Hz, 0 = instant
}
```

Recipes are indexed at load into `byStation` and `byOutput` maps so UI filtering and machine lookup are direct.

### 6.2 Containers (runtime)

```ts
interface Container {
  size: number;
  ids: Uint16Array;      // item id per slot
  counts: Uint16Array;   // count per slot
}
```

- Player: 6 hotbar + 18 bag = one 24-slot container; the hotbar is simply slots 0–5.
- Machine buffers and crates are the same structure, so belts, machines, the player and the UI all move items through one API:
  - `add(c, id, n) → leftover`, `remove(c, id, n) → removed`, `count(c, id)`, `moveSlot(from, i, to, j, n)`, `canCraft(c, recipe)`, `craft(c, recipe)`.
- All operations are integer-only and allocation-free; they are the most heavily unit-tested part of the core.

### 6.3 MVP content

Items: wood, stone, iron ore, coal, iron ingot, gear, circuit, pick, axe, ration, cell, plus one kit per placeable.

Recipes (indicative, tuned on day 2):

| Output | Inputs | Station |
|---|---|---|
| Axe | 3 wood, 2 stone | hand |
| Pick | 2 wood, 3 stone | hand |
| Workbench kit | 8 wood, 4 stone | hand |
| Crate kit | 6 wood | workbench |
| Smelter kit | 12 stone | workbench |
| Iron ingot | 1 ore (+ coal as fuel) | smelter |
| Gear | 2 ingot | workbench |
| Belt kit ×4 | 1 ingot, 1 gear | workbench |
| Drill kit | 4 ingot, 2 gear | workbench |
| Circuit | 1 ingot, 1 coal | workbench |
| Sensor kit | 1 circuit, 1 ingot | workbench |
| Turret kit | 2 circuit, 4 ingot, 2 gear | workbench |

### 6.4 Save format

One JSON document in localStorage (chosen over IndexedDB because it is synchronous, so a save made while the app is being backgrounded always completes): version, seed, tick, player (position, vitals, container as two base64 arrays), entity table (kind, cell, rotation, buffers), depleted resource nodes, and the logic graph (node arrays up to the high-water mark and the wire words, base64). Target size under 100 KB. Autosave at dawn and on app background.

---

## 7. Input scheme

Landscape only. All controls sit inside safe-area insets. Minimum target 48 dp, primary controls 64–72 dp.

```
┌───────────────────────────────────────────────────────────────┐
│ ▮▮▮▮▯ health                                      [bag] [≡]  │
│ ▮▮▮▯▯ charge                                                 │
│ ▮▮▯▯▯ warmth                 ·                               │
│                           reticle                             │
│                                                     (context) │
│   ╭───╮                                         (jump)        │
│   │ ◉ │  floating stick                               ( ACT ) │
│   ╰───╯            [1][2][3][4][5][6]        (build)          │
└───────────────────────────────────────────────────────────────┘
```

| Control | Gesture | Notes |
|---|---|---|
| Move | Left half: touch down anywhere spawns a floating stick | Push past 85 % radius to sprint |
| Look | Right half: drag on empty area | Sensitivity slider; light aim-slowdown over interactables |
| ACT | Hold | Uses the held item: gather, attack, fire. Hold-to-repeat so mining needs no tapping |
| Context | Tap | Appears only when the reticle is on something usable; label and icon change (Open, Link, Pick up, Rotate) |
| Hotbar | Tap slot; swipe up on bar opens bag | Selected slot is enlarged |
| Build | Select a kit on the hotbar | There is no separate build toggle: holding a kit shows the ghost on the targeted grid cell, a tap on ACT places it, and the context button becomes Rotate. Holding ACT on any placed entity packs it back into its kit |

Multi-touch: each control claims its pointer id on touch-down, so moving, looking and holding ACT work together. Haptic tick on place, on gather completion and on taking damage.

---

## 8. Performance plan

### 8.1 Budgets (mid-range 2022+ Android, WebView)

| Metric | Budget |
|---|---|
| Frame rate | 60 fps target, 30 fps floor |
| Draw calls | ≤ 120 |
| Triangles on screen | ≤ 120 k |
| Simulation | ≤ 2 ms per tick |
| JS heap | ≤ 120 MB, flat during play |
| Pixel ratio | min(device, 1.5), dynamic scale down to 0.7 |
| APK size | ≤ 15 MB |

### 8.2 Visual style chosen for cost

Flat-shaded low-poly with **vertex colours and one shared material**: no textures to sample or load, almost everything batches. Lighting is one directional plus one hemisphere light. No real-time shadows (blob decals under machines and enemies), no post-processing. Distance fog in the sky colour hides the far plane and LOD changes. Palette is cold: slate, teal and bone, with signal-amber reserved for anything interactive, so the art direction is its own.

### 8.3 Rendering

- **Instancing**: one `InstancedMesh` per prop type (trees, rocks, belt tile, each item type on belts, wall). Instance buffers update only for tiles that changed.
- **Terrain**: 8 × 8 chunks of 32 m on a 2 m lattice (about 33 k triangles for the whole map), frustum culled and distance culled past the fog. One resolution is enough at this density.
- **LOD tiers**

| Tier | Distance | Machines / props | Belt items | Enemies |
|---|---|---|---|---|
| 0 | < 30 m | Full mesh, animated parts | Drawn | Full, animated |
| 1 | 30–80 m | Simplified mesh, static | Hidden | Simplified, no animation |
| 2 | > 80 m | Hidden in fog | Hidden | Hidden unless targeting the player |

- LOD membership is recomputed for a slice of entities each frame (round robin), not all of them.
- As built so far: resource nodes and placed entities are instanced per kind and drawn without per-instance culling, because the whole map measures about 31 k triangles in 32 draw calls. The tiered LOD above is switched on only if machines and enemies push past the budget.
- Simulation does **not** use LOD: far machines tick normally, because the sim is cheap and correctness matters more.
- **Dynamic resolution**: rolling frame-time average; step pixel ratio down above 20 ms, back up below 14 ms.
- **Context loss**: on `webglcontextrestored` rebuild all GPU resources from core state.

### 8.4 Pools (fixed size, preallocated)

| Pool | Size | Implementation |
|---|---|---|
| Projectiles | 64 | One instanced mesh, swap-remove |
| Particles | 512 | Single `Points` buffer, ring allocation |
| Enemies | 24 | Preallocated rigs, toggled visible |
| Floating pickups text | 16 | Reused DOM nodes |
| Audio voices | 8 | Reused buffer sources |
| Temp vectors / matrices | module-level scratch | Never `new` in a frame |

### 8.5 Measurement

A debug overlay (three-finger tap) shows fps, frame ms, draw calls, triangles, tick ms and heap. The Playwright smoke test records these with 4× CPU throttling as a regression signal. Real numbers must come from a physical device via the debug APK or the hosted URL; cloud results are only a floor check.

---

## 9. Priority features

Built in this order; each is playable and tested before the next starts.

1. **Move, look, gather** — touch controls, terrain, resource nodes, vitals HUD. Proves input feel and the render budget.
2. **Inventory, crafting, placement** — container API, hand and workbench recipes, build mode on the grid. Proves the data model and the touch item UI.
3. **One automation chain** — drill → belt → smelter → crate running unattended, with the machine panel. Proves the fixed-tick sim and instanced belts.
4. **Logic wiring and one threat** — graph core, Link tool, circuit board, proximity sensor → logic → turret, stray raid at night. Proves the signature mechanic on touch.

---

## 10. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| WebView frame rate poor on the user's phone | Medium | Budgets, dynamic resolution, headless core allows renderer swap |
| No physical device in the loop during development | High | Hosted build URL + debug APK from CI every day for the user to try; debug overlay for reporting numbers |
| CI cannot build the APK (workflow permissions, SDK setup) | Medium | The web build is independently playable in mobile Chrome and installable as a PWA; APK is a packaging step, not a dependency |
| Circuit board too fiddly on a small screen | Medium | Link tool covers simple cases without ever opening the board; tap-tap wiring; port fan at low zoom |
| Three-day scope | High | Feature order above is strict; each day ends shippable; cut list in the roadmap |

---

## 11. Sources

- [Node-RED forum: better smartphone support](https://discourse.nodered.org/t/better-smartphone-support/30103)
- [Node-RED forum: changing flows from a tablet touchscreen](https://discourse.nodered.org/t/how-to-change-flows-from-a-tablet-touchscreen/83531)
- [yFiles: supported user interactions](https://docs.yworks.com/yfiles-html/dguide/interaction-support/)
- [yFiles: touch interaction demo](https://www.yfiles.com/demos/input/touchcustomization/)
- [Mobile IDE interaction design notes](https://vectree.io/c/mobile-ide-interaction-design)
- [three.js forum: Android and WebGL](https://discourse.threejs.org/t/why-android-device-is-not-good-for-webgl/20878)
- [three.js forum: handling context lost on mobile](https://discourse.threejs.org/t/how-to-fix-context-lost-android-iphone-ios/56829)
- [WebKit bug 218949: instanced draw call performance](https://bugs.webkit.org/show_bug.cgi?id=218949)
- [Capgo: animation performance in Capacitor apps](https://capgo.app/blog/ultimate-guide-to-animation-performance-in-capacitor-apps/)
- [Three.js mobile performance guide](https://digitalstrategyforce.com/journal/how-do-you-optimize-threejs-performance-for-mobile-devices/)
