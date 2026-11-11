# Scrapline — Implementation Roadmap

Three days, starting **2026-11-11 17:45 GMT+4**. Read `TDD.md` first; this file is the order of work.

Ground rules for every day:

- All work lives in `scrapline/` on branch `claude/mechanica-android-mvp-210979`. Nothing else in the repository is read or touched.
- `core/` is written and unit-tested before the view or UI that shows it.
- Each milestone ends with: tests green, a Playwright phone-viewport smoke run with a screenshot, a commit, and a push.
- Each day ends in a playable build. If a day runs long, cut from the bottom of that day's list, never from the acceptance checks.

---

## Kick-off checklist (first 30 minutes at 17:45)

1. Re-read `TDD.md` sections 2.4, 4, 5.2 and 6.
2. Scaffold `scrapline/` with Vite + TypeScript (strict) + Three.js + Vitest.
3. Add the folder layout from TDD 2.4 with empty modules and the import rule (`core/` imports nothing from `view/`, `ui/`, `input/`).
4. Add `npm run dev`, `build`, `test`, `smoke` scripts. Smoke = Playwright, 844 × 390 landscape, touch enabled.
5. Commit the empty shell and push before writing any feature.

---

## Day 1 — Wed 11 Nov: setup, movement, inventory UI

Goal: stand in the world on a phone-sized screen, walk, look, and see a working HUD and bag.

| # | Milestone | Work | Done when |
|---|---|---|---|
| 1.1 | Project shell | Kick-off checklist; fixed-tick loop (20 Hz) with render interpolation; debug overlay (fps, draw calls, tick ms) | Blank scene renders in the smoke test, overlay shows numbers |
| 1.2 | World | Seeded heightmap, 8 × 8 terrain chunks, vertex-colour flat shading, fog, sky, hemisphere + sun light | Terrain visible, ≤ 20 draw calls |
| 1.3 | Player + touch controls | Capsule vs heightmap, floating left stick, right-side drag look, jump, sprint, pointer-id ownership per control | Synthetic touch in the smoke test moves and turns the camera; both thumbs work at once |
| 1.4 | Item and container core | `items.ts`, `inventory.ts` with add / remove / move / count, typed-array containers | Unit tests cover stacking, overflow, partial moves, empty slots |
| 1.5 | HUD + bag | Vitals gauges, 6-slot hotbar, bag sheet (tap to move, tap-hold for half), context button stub | Items seeded by a debug command show in the hotbar and can be moved in the bag by touch |

End of day 1 build: walk the map, open the bag, rearrange seeded items.

Cut list if short on time: sprint, sky gradient polish, tap-hold split.

---

## Day 2 — Thu 12 Nov: gathering, crafting, building

Goal: the Gather → Build half of the loop, complete and saveable.

| # | Milestone | Work | Done when |
|---|---|---|---|
| 2.1 | Resource nodes | Instanced deadwood, stone, ore, coal; node HP and yield; hold ACT to gather; tool tiers; pickup feedback | Holding ACT on a tree with an axe fills the hotbar with wood; depleted nodes disappear |
| 2.2 | Crafting | `recipes.ts`, `canCraft` / `craft`, hand-craft sheet, timed crafts | Axe, pick and workbench kit can be crafted from gathered items; tests cover insufficient inputs and full inventory |
| 2.3 | Build mode | Grid snapping, ghost preview with valid / invalid tint, rotate, place, pick up; `grid` occupancy array; entity tables | Workbench, crate and wall can be placed, rotated and removed; cannot overlap |
| 2.4 | Stations and containers | Workbench recipe list; crate panel with two-container transfer | Items move between player and crate; workbench-only recipes appear only at the workbench |
| 2.5 | Vitals | Health, Charge, Warmth with day / night cycle, consumables, death and respawn | Warmth drains outdoors and the gauge reacts; a ration restores health |
| 2.6 | Save / load | Versioned IndexedDB save, autosave on background | Reload restores position, inventory, placed entities and depleted nodes |

End of day 2 build: start with nothing, gather, craft tools, build a small base with storage.

Cut list: pick-up of placed entities (destroy instead), death screen polish, timed crafts (make instant).

---

## Day 3 — Fri 13 Nov: automation and logic wiring

Goal: the Automate → Defend half, including the signature wiring mechanic, and an installable build.

| # | Milestone | Work | Done when |
|---|---|---|---|
| 3.1 | Machines | Smelter (fuel, progress, heat radius), drill on deposits, generator; machine panel sheet | Hand-fed smelter turns ore into ingots and warms the player nearby |
| 3.2 | Belts | Belt tiles with 4 slots, cached flow order, machine input / output sides, instanced belt items | Drill → 6 belts → smelter → belt → crate runs for 5 minutes with no player input and no item loss (sim test + in-game) |
| 3.3 | Logic core | `logic/graph.ts`, `nodes.ts`, `eval.ts` per TDD 5.2–5.3 | Unit tests: AND/OR/NOT truth tables, delay timing, fan-in, a two-node latch loop, zero evaluations when idle |
| 3.4 | Link tool | In-world source → target wiring, default ports, wire lines, remove | Switch wired to lamp toggles it; switch wired to a belt stops the line |
| 3.5 | Circuit board | Full-screen board: pan, pinch, move nodes, tap-tap wiring with 56 dp port targets, palette sheet, live signal animation, delete, undo | In the smoke test, synthetic taps build sensor → NOT → lamp and the lamp state follows |
| 3.6 | Threat | Proximity sensor, turret, pooled projectiles, one stray type with simple seek-and-attack, dusk warning, night raid | Sensor → turret wired by the player destroys strays without the player firing; surviving to dawn shows the slice-complete card |
| 3.7 | Packaging | Capacitor Android project, landscape + immersive, GitHub Actions workflow producing a debug APK artifact; performance pass against TDD 8.1 | APK artifact downloadable from the workflow run, or, if CI is blocked, a documented reason plus the hosted web build |

End of day 3 build: the full 15–20 minute slice described in TDD section 0.

Cut list, in order: undo on the board, siren, generator, Delay / Timer nodes, pinch zoom (fixed zoom levels instead). Not cuttable: 3.2, 3.3, 3.4, 3.6.

---

## Verification on every milestone

| Check | Tool |
|---|---|
| Core logic | `vitest` on `core/` |
| It runs on a phone-shaped screen | Playwright, 844 × 390, touch, screenshot kept in `scrapline/smoke/` |
| Frame budget has not regressed | Smoke test reads the debug overlay under 4× CPU throttle |
| Real-device feel | User opens the day's build on their phone and reports the overlay numbers |

## After the slice

Next candidates, unordered: Counter / Compare / Latch nodes, assembler with selectable recipes, second enemy type, power grid, tiered tools, audio pass, larger map with streaming, cloud save.
