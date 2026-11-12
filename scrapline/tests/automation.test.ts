import { describe, expect, it } from 'vitest';
import { BELT_SLOTS, EntityKind, entityAt, placeEntity, removeEntity } from '../src/core/entities';
import { MAX_STRAYS, STRAY_HP, TURRET_RANGE, nightBudget, spawnStray } from '../src/core/enemies';
import { count } from '../src/core/inventory';
import { Item } from '../src/core/items';
import { NodeType, addNode, addWire, findWire } from '../src/core/logic/graph';
import { BELT_PERIOD, DRILL_TICKS, FUEL_TICKS, SMELTER_COAL, SMELTER_ORE, SMELTER_OUT, SMELT_TICKS, loadBelt, machineStatus } from '../src/core/machines';
import { deserialize, serialize } from '../src/core/save';
import {
  CYCLE_TICKS, DAY_TICKS, Ev, SENSOR_RANGE, Sim, TargetType, canBuildAt, createSim, ensureNode, nearHeat, tickSim,
  toggleSwitch, updateTarget,
} from '../src/core/sim';
import { WORLD_SIZE, heightAt } from '../src/core/world';

const SEED = 20261111;

function place(sim: Sim, kind: number, cx: number, cz: number, rot = 0): number {
  const id = placeEntity(sim.entities, kind, cx, cz, rot, heightAt(sim.world, cx + 0.5, cz + 0.5));
  expect(id).toBeGreaterThan(0);
  return id;
}

function run(sim: Sim, ticks: number): void {
  for (let i = 0; i < ticks; i++) tickSim(sim);
}

function events(sim: Sim, code: number): number {
  let n = 0;
  for (let i = 0; i < sim.events.length; i += 3) if (sim.events[i] === code) n++;
  return n;
}

/** Cell coordinates of the first ore deposit. */
function oreCell(sim: Sim): { cx: number; cz: number } {
  const d = sim.nodes.deposits.find((x) => x.item === Item.IronOre)!;
  return { cx: Math.floor(d.x), cz: Math.floor(d.z) };
}

function beltItems(sim: Sim): number {
  let n = 0;
  const es = sim.entities;
  for (let id = 1; id < es.high; id++) {
    if (es.kind[id] !== EntityKind.Belt) continue;
    for (let s = 0; s < BELT_SLOTS; s++) if (es.belt[id * BELT_SLOTS + s]) n++;
  }
  return n;
}

describe('deposits and drills', () => {
  it('marks deposit cells and only lets drills go there', () => {
    const sim = createSim(SEED);
    const { cx, cz } = oreCell(sim);
    expect(sim.deposits[cz * WORLD_SIZE + cx]).toBe(Item.IronOre);
    expect(canBuildAt(sim, cx, cz, EntityKind.Drill)).toBe(true);
    expect(canBuildAt(sim, 128, 120, EntityKind.Drill)).toBe(false);
    expect(canBuildAt(sim, 128, 120, EntityKind.Crate)).toBe(true);
    expect(sim.nodes.deposits.some((d) => d.item === Item.Coal)).toBe(true);
  });

  it('a drill extracts into its buffer and stops when full', () => {
    const sim = createSim(SEED);
    const { cx, cz } = oreCell(sim);
    const drill = place(sim, EntityKind.Drill, cx, cz);
    run(sim, DRILL_TICKS * 3);
    const c = sim.entities.containers[drill]!;
    expect(c.ids[0]).toBe(Item.IronOre);
    expect(c.counts[0]).toBe(3);
    expect(machineStatus(sim, drill)).toMatch(/Drilling/);
    c.counts[0] = 50;
    run(sim, DRILL_TICKS * 2);
    expect(c.counts[0]).toBe(50);
    expect(sim.entities.active[drill]).toBe(0);
  });
});

describe('smelter', () => {
  it('burns coal to turn ore into ingots and warms the player only while running', () => {
    const sim = createSim(SEED);
    const smelter = place(sim, EntityKind.Smelter, 129, 126);
    const c = sim.entities.containers[smelter]!;
    expect(machineStatus(sim, smelter)).toBe('Needs iron ore');
    c.ids[SMELTER_ORE] = Item.IronOre;
    c.counts[SMELTER_ORE] = 5;
    run(sim, 5);
    expect(machineStatus(sim, smelter)).toBe('Needs coal');
    expect(nearHeat(sim)).toBe(false);
    c.ids[SMELTER_COAL] = Item.Coal;
    c.counts[SMELTER_COAL] = 2;
    run(sim, SMELT_TICKS);
    expect(c.counts[SMELTER_OUT]).toBe(1);
    expect(c.ids[SMELTER_OUT]).toBe(Item.IronIngot);
    expect(c.counts[SMELTER_COAL]).toBe(1);
    expect(nearHeat(sim)).toBe(true);
    run(sim, SMELT_TICKS * 4);
    expect(c.counts[SMELTER_OUT]).toBe(5);
    expect(c.counts[SMELTER_ORE]).toBe(0);
    // One coal is four ingots; the fifth needed the second coal.
    expect(c.counts[SMELTER_COAL]).toBe(0);
    expect(sim.entities.fuel[smelter]).toBe(FUEL_TICKS * 2 - SMELT_TICKS * 5);
    run(sim, 1); // the tick after the last ingot, it is idle and cools off
    expect(nearHeat(sim)).toBe(false);
  });
});

describe('belts', () => {
  it('carries an item one slot per step along a line and into a crate', () => {
    const sim = createSim(SEED);
    // Three belts heading -Z (rot 0) into a crate.
    const b1 = place(sim, EntityKind.Belt, 130, 124, 0);
    place(sim, EntityKind.Belt, 130, 123, 0);
    place(sim, EntityKind.Belt, 130, 122, 0);
    const crate = place(sim, EntityKind.Crate, 130, 121);
    expect(loadBelt(sim.entities, b1, Item.Stone)).toBe(true);
    expect(loadBelt(sim.entities, b1, Item.Stone)).toBe(false);
    // 12 slots to cross, one per belt step.
    run(sim, BELT_PERIOD * 11);
    expect(count(sim.entities.containers[crate]!, Item.Stone)).toBe(0);
    expect(beltItems(sim)).toBe(1);
    run(sim, BELT_PERIOD * 2);
    expect(count(sim.entities.containers[crate]!, Item.Stone)).toBe(1);
    expect(beltItems(sim)).toBe(0);
  });

  it('backs up without losing or merging items when the end is blocked', () => {
    const sim = createSim(SEED);
    const b1 = place(sim, EntityKind.Belt, 130, 124, 0);
    place(sim, EntityKind.Belt, 130, 123, 0); // nothing after it
    let loaded = 0;
    for (let i = 0; i < 400; i++) {
      if (loadBelt(sim.entities, b1, Item.Coal)) loaded++;
      tickSim(sim);
    }
    expect(loaded).toBe(BELT_SLOTS * 2);
    expect(beltItems(sim)).toBe(BELT_SLOTS * 2);
  });

  it('turns corners and never feeds back into the belt behind it', () => {
    const sim = createSim(SEED);
    const b1 = place(sim, EntityKind.Belt, 130, 124, 0); // -Z
    place(sim, EntityKind.Belt, 130, 123, 3); // +X
    const crate = place(sim, EntityKind.Crate, 131, 123);
    // Two belts facing each other must not swap items forever.
    const f1 = place(sim, EntityKind.Belt, 140, 124, 0);
    const f2 = place(sim, EntityKind.Belt, 140, 123, 2);
    loadBelt(sim.entities, b1, Item.Wood);
    loadBelt(sim.entities, f1, Item.Wood);
    loadBelt(sim.entities, f2, Item.Wood);
    run(sim, BELT_PERIOD * 20);
    expect(count(sim.entities.containers[crate]!, Item.Wood)).toBe(1);
    expect(sim.entities.belt[f1 * BELT_SLOTS + BELT_SLOTS - 1]).toBe(Item.Wood);
    expect(sim.entities.belt[f2 * BELT_SLOTS + BELT_SLOTS - 1]).toBe(Item.Wood);
  });

  it('runs a drill, belts, smelter, belt, crate chain unattended with no item loss', () => {
    const sim = createSim(SEED);
    const { cx, cz } = oreCell(sim);
    // drill -> 6 belts heading +X -> smelter (facing +X) -> belt -> crate
    const drill = place(sim, EntityKind.Drill, cx, cz, 3);
    for (let i = 1; i <= 6; i++) place(sim, EntityKind.Belt, cx + i, cz, 3);
    const smelter = place(sim, EntityKind.Smelter, cx + 7, cz, 3);
    place(sim, EntityKind.Belt, cx + 8, cz, 3);
    const crate = place(sim, EntityKind.Crate, cx + 9, cz);
    const sc = sim.entities.containers[smelter]!;
    sc.ids[SMELTER_COAL] = Item.Coal;
    sc.counts[SMELTER_COAL] = 50;

    const minutes = 5;
    run(sim, minutes * 60 * 20);

    const ingots = count(sim.entities.containers[crate]!, Item.IronIngot);
    // The smelter is the bottleneck: one ingot per SMELT_TICKS once ore arrives.
    expect(ingots).toBeGreaterThan(50);
    const extracted = Math.floor((minutes * 60 * 20) / DRILL_TICKS);
    const ore = sim.entities.containers[drill]!.counts[0] + sc.counts[SMELTER_ORE];
    const onBelts = beltItems(sim);
    const smelted = ingots + sc.counts[SMELTER_OUT];
    // Every ore the drill produced is in a buffer, on a belt, or was smelted.
    expect(ore + onBelts + smelted).toBeLessThanOrEqual(extracted);
    expect(ore + onBelts + smelted).toBeGreaterThanOrEqual(smelted + 1);
    expect(50 - sc.counts[SMELTER_COAL]).toBe(Math.ceil((smelted * SMELT_TICKS + sim.entities.progress[smelter]) / FUEL_TICKS));
  });

  it('rebuilds its order when belts are added or removed', () => {
    const sim = createSim(SEED);
    const b1 = place(sim, EntityKind.Belt, 130, 124, 0);
    run(sim, BELT_PERIOD);
    const b2 = place(sim, EntityKind.Belt, 130, 123, 0);
    loadBelt(sim.entities, b1, Item.Stone);
    run(sim, BELT_PERIOD * 5);
    expect(sim.entities.belt[b2 * BELT_SLOTS + 1]).toBe(Item.Stone);
    removeEntity(sim.entities, b2);
    run(sim, BELT_PERIOD * 3);
    expect(sim.beltOrder.count).toBe(1);
  });
});

describe('wiring in the world', () => {
  /** Aims the player at an entity's cell from two metres south of it. */
  function aimAt(sim: Sim, id: number): void {
    const es = sim.entities;
    const p = sim.player;
    p.x = es.cx[id] + 0.5;
    p.z = es.cz[id] + 2.5;
    p.y = heightAt(sim.world, p.x, p.z);
    p.yaw = 0;
    p.pitch = -0.55;
    updateTarget(sim);
    expect(sim.target.type).toBe(TargetType.Entity);
    expect(sim.target.id).toBe(id);
  }

  function withLinker(): Sim {
    const sim = createSim(SEED);
    sim.inventory.ids[5] = Item.Linker;
    sim.inventory.counts[5] = 1;
    sim.selected = 5;
    return sim;
  }

  function press(sim: Sim): void {
    sim.actPressed = true;
    tickSim(sim);
  }

  it('links a switch to a lamp with two presses, and unlinks with two more', () => {
    const sim = withLinker();
    const sw = place(sim, EntityKind.Switch, 120, 120);
    const lamp = place(sim, EntityKind.Lamp, 124, 120);
    sim.entities.enabled[lamp] = 0;
    aimAt(sim, sw);
    press(sim);
    expect(sim.linkFrom).toBe(sw);
    aimAt(sim, lamp);
    press(sim);
    expect(events(sim, Ev.Linked)).toBe(1);
    const g = sim.graph;
    expect(findWire(g, sim.entities.node[sw], 0, sim.entities.node[lamp], 0)).toBe(0);

    run(sim, 4);
    expect(sim.entities.active[lamp]).toBe(0);
    toggleSwitch(sim, sw);
    run(sim, 4);
    expect(sim.entities.active[lamp]).toBe(1);
    toggleSwitch(sim, sw);
    run(sim, 4);
    expect(sim.entities.active[lamp]).toBe(0);

    aimAt(sim, sw);
    press(sim);
    aimAt(sim, lamp);
    press(sim);
    expect(events(sim, Ev.Unlinked)).toBe(1);
    expect(g.wireCount).toBe(0);
  });

  it('refuses links that make no sense', () => {
    const sim = withLinker();
    const lamp = place(sim, EntityKind.Lamp, 120, 120);
    const sw = place(sim, EntityKind.Switch, 124, 120);
    const sw2 = place(sim, EntityKind.Switch, 128, 120);
    aimAt(sim, lamp); // a lamp sends nothing
    press(sim);
    expect(sim.linkFrom).toBe(0);
    aimAt(sim, sw);
    press(sim);
    aimAt(sim, sw2); // a switch receives nothing
    press(sim);
    expect(events(sim, Ev.LinkBad)).toBe(2);
    expect(sim.graph.wireCount).toBe(0);
  });

  it('a switch wired to a belt stops the line; unwired belts run', () => {
    const sim = withLinker();
    const sw = place(sim, EntityKind.Switch, 120, 120);
    const belt = place(sim, EntityKind.Belt, 124, 120, 0);
    loadBelt(sim.entities, belt, Item.Stone);
    run(sim, BELT_PERIOD);
    expect(sim.entities.belt[belt * BELT_SLOTS + 1]).toBe(Item.Stone);
    addWire(sim.graph, ensureNode(sim, sw), 0, ensureNode(sim, belt), 0);
    run(sim, BELT_PERIOD * 4);
    expect(sim.entities.enabled[belt]).toBe(0);
    expect(sim.entities.belt[belt * BELT_SLOTS + 1]).toBe(Item.Stone);
    toggleSwitch(sim, sw);
    run(sim, BELT_PERIOD * 4);
    expect(sim.entities.belt[belt * BELT_SLOTS + BELT_SLOTS - 1]).toBe(Item.Stone);
  });

  it('a sensor sees the player in range, and a NOT node inverts it for a lamp', () => {
    const sim = createSim(SEED);
    const sensor = place(sim, EntityKind.Sensor, 120, 120);
    const lamp = place(sim, EntityKind.Lamp, 121, 120);
    sim.entities.enabled[lamp] = 0;
    const g = sim.graph;
    const not = addNode(g, NodeType.Not);
    addWire(g, ensureNode(sim, sensor), 0, not, 0);
    addWire(g, not, 0, ensureNode(sim, lamp), 0);
    sim.player.x = 120.5 + SENSOR_RANGE + 5;
    sim.player.z = 120.5;
    run(sim, 6);
    expect(sim.entities.active[sensor]).toBe(0);
    expect(sim.entities.active[lamp]).toBe(1);
    sim.player.x = 122;
    run(sim, 6);
    expect(sim.entities.active[sensor]).toBe(1);
    expect(sim.entities.active[lamp]).toBe(0);
    expect(g.outCur[sim.entities.node[sensor] * 4 + 1]).toBe(15); // 1.5 m in decimetres
  });

  it('removing a wired entity removes its node and wires', () => {
    const sim = withLinker();
    const sw = place(sim, EntityKind.Switch, 120, 120);
    const lamp = place(sim, EntityKind.Lamp, 124, 120);
    addWire(sim.graph, ensureNode(sim, sw), 0, ensureNode(sim, lamp), 0);
    sim.selected = 3;
    aimAt(sim, lamp);
    sim.actHeld = true;
    run(sim, 20);
    sim.actHeld = false;
    expect(entityAt(sim.entities, 124, 120)).toBe(0);
    expect(sim.graph.wireCount).toBe(0);
    expect(count(sim.inventory, Item.KitLamp)).toBe(1);
  });
});

describe('the night', () => {
  it('spawns strays only at night, within the budget', () => {
    const sim = createSim(SEED);
    run(sim, 600);
    expect(sim.strays.count).toBe(0);
    sim.tick = DAY_TICKS;
    run(sim, 100 * 12);
    const budget = nightBudget(0);
    expect(sim.strays.count).toBeGreaterThan(0);
    expect(sim.strays.count).toBeLessThanOrEqual(budget.atOnce);
    expect(sim.strays.spawned).toBeLessThanOrEqual(budget.total);
  });

  it('strays chase and hurt the player', () => {
    const sim = createSim(SEED);
    sim.tick = DAY_TICKS + 5;
    const i = spawnStray(sim, sim.player.x + 12, sim.player.z);
    const d0 = Math.hypot(sim.strays.x[i] - sim.player.x, sim.strays.z[i] - sim.player.z);
    run(sim, 40);
    const d1 = Math.hypot(sim.strays.x[i] - sim.player.x, sim.strays.z[i] - sim.player.z);
    expect(d1).toBeLessThan(d0 - 5);
    run(sim, 80);
    expect(sim.vitals.health).toBeLessThan(1000);
    expect(events(sim, Ev.Bite)).toBeGreaterThan(0);
  });

  it('a turret does nothing unwired, and kills strays once a sensor is wired to it', () => {
    const sim = createSim(SEED);
    sim.tick = DAY_TICKS + 5;
    const turret = place(sim, EntityKind.Turret, 128, 122);
    sim.entities.enabled[turret] = 0;
    const sensor = place(sim, EntityKind.Sensor, 127, 122);
    ensureNode(sim, turret);
    ensureNode(sim, sensor);
    sim.player.x = 128.5;
    sim.player.z = 160;
    const i = spawnStray(sim, 128.5, 122 - TURRET_RANGE + 3);
    sim.strays.spawned = 99; // no further spawns
    run(sim, 30);
    expect(sim.strays.hp[i]).toBe(STRAY_HP);
    expect(events(sim, Ev.Shot)).toBe(0);

    addWire(sim.graph, sim.entities.node[sensor], 0, sim.entities.node[turret], 0);
    // The stray heads for the player and passes the sensor on the way.
    run(sim, 120);
    expect(sim.strays.alive[i]).toBe(0);
    expect(events(sim, Ev.Shot)).toBe(4);
    expect(events(sim, Ev.StrayDown)).toBe(1);
    expect(sim.vitals.health).toBe(1000);
  });

  it('the player can fight a stray by holding the action button', () => {
    const sim = createSim(SEED);
    sim.tick = DAY_TICKS + 5;
    sim.strays.spawned = 99;
    sim.inventory.ids[4] = Item.Axe;
    sim.inventory.counts[4] = 1;
    sim.selected = 4;
    const p = sim.player;
    const i = spawnStray(sim, p.x, p.z - 1.4);
    p.yaw = 0;
    p.pitch = -0.2;
    sim.actHeld = true;
    for (let k = 0; k < 40 && sim.strays.alive[i]; k++) {
      // Keep it pinned in front so only the fighting is under test.
      sim.strays.x[i] = p.x;
      sim.strays.z[i] = p.z - 1.4;
      updateTarget(sim);
      expect(sim.target.type).toBe(TargetType.Stray);
      tickSim(sim);
    }
    expect(sim.strays.alive[i]).toBe(0);
    expect(events(sim, Ev.StrayHit)).toBe(3);
  });

  it('warns at dusk, clears strays at dawn and counts a survived night', () => {
    const sim = createSim(SEED);
    sim.tick = DAY_TICKS - 60 * 20 - 1;
    tickSim(sim);
    expect(events(sim, Ev.Dusk)).toBe(1);
    sim.tick = CYCLE_TICKS - 3;
    for (let k = 0; k < MAX_STRAYS; k++) spawnStray(sim, 30 + k, 30);
    sim.vitals.warmth = 1000;
    run(sim, 5);
    expect(sim.strays.count).toBe(0);
    expect(sim.nights).toBe(1);
    expect(events(sim, Ev.Dawn)).toBe(1);
  });
});

describe('saving a factory', () => {
  it('round-trips machines, belt contents, circuits and switch state', () => {
    const sim = createSim(SEED);
    const { cx, cz } = oreCell(sim);
    const drill = place(sim, EntityKind.Drill, cx, cz, 3);
    const belt = place(sim, EntityKind.Belt, cx + 1, cz, 3);
    const sw = place(sim, EntityKind.Switch, 120, 120);
    const lamp = place(sim, EntityKind.Lamp, 122, 120);
    sim.entities.enabled[lamp] = 0;
    // An entity removed before saving leaves a gap in the ids.
    removeEntity(sim.entities, place(sim, EntityKind.Wall, 110, 110));
    const g = sim.graph;
    const not = addNode(g, NodeType.Not);
    const delay = addNode(g, NodeType.Delay);
    g.param[delay * 2] = 6;
    addWire(g, ensureNode(sim, sw), 0, not, 0);
    addWire(g, not, 0, delay, 0);
    addWire(g, delay, 0, ensureNode(sim, lamp), 0);
    toggleSwitch(sim, sw);
    run(sim, DRILL_TICKS * 2 + 7);

    const copy = deserialize(JSON.parse(JSON.stringify(serialize(sim))))!;
    expect(copy).not.toBeNull();
    const ces = copy.entities;
    const cDrill = entityAt(ces, cx, cz);
    const cBelt = entityAt(ces, cx + 1, cz);
    expect(ces.progress[cDrill]).toBe(sim.entities.progress[drill]);
    expect(Array.from(ces.belt.subarray(cBelt * BELT_SLOTS, cBelt * BELT_SLOTS + BELT_SLOTS))).toEqual(
      Array.from(sim.entities.belt.subarray(belt * BELT_SLOTS, belt * BELT_SLOTS + BELT_SLOTS)),
    );
    expect(copy.graph.wireCount).toBe(3);
    expect(copy.graph.param[delay * 2]).toBe(6);
    const cSw = entityAt(ces, 120, 120);
    const cLamp = entityAt(ces, 122, 120);
    expect(ces.active[cSw]).toBe(1);
    expect(copy.graph.entity[ces.node[cLamp]]).toBe(cLamp);

    // Both worlds keep behaving identically after the load.
    run(sim, 40);
    run(copy, 40);
    expect(ces.active[cLamp]).toBe(sim.entities.active[lamp]);
    toggleSwitch(sim, sw);
    toggleSwitch(copy, cSw);
    run(sim, 40);
    run(copy, 40);
    expect(sim.entities.active[lamp]).toBe(1);
    expect(ces.active[cLamp]).toBe(1);
    expect(ces.containers[cDrill]!.counts[0] + beltItems(copy)).toBe(sim.entities.containers[drill]!.counts[0] + beltItems(sim));
  });
});
