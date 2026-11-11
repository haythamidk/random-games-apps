import { describe, expect, it } from 'vitest';
import { ENTITY_DEFS, EntityKind, entityAt } from '../src/core/entities';
import { add, count, createContainer } from '../src/core/inventory';
import { Item } from '../src/core/items';
import { NODE_DEFS, NodeKind } from '../src/core/nodes';
import { RECIPES, craft, hasInputs, recipesFor } from '../src/core/recipes';
import { deserialize, serialize } from '../src/core/save';
import {
  CYCLE_TICKS, DAY_TICKS, Ev, Sim, TargetType, VITAL_MAX, canBuildAt, createSim, daylight, isNight,
  nearHeat, respawn, stepPlayer, tickSim, updateTarget,
} from '../src/core/sim';
import { MoveIntent } from '../src/core/player';
import { heightAt } from '../src/core/world';

const SEED = 20261111;
const idle = (): MoveIntent => ({ moveX: 0, moveY: 0, sprint: false, jump: false, lookYaw: 0, lookPitch: 0 });

/** Stands the player 1.6 m south of a node, looking straight at its middle. */
function faceNode(sim: Sim, i: number): void {
  const n = sim.nodes;
  const p = sim.player;
  p.x = n.x[i];
  p.z = n.z[i] + 1.6;
  p.y = heightAt(sim.world, p.x, p.z);
  p.yaw = 0;
  p.pitch = -0.35;
  updateTarget(sim);
}

function firstNode(sim: Sim, kind: number): number {
  for (let i = 0; i < sim.nodes.count; i++) if (sim.nodes.kind[i] === kind) return i;
  throw new Error('no node of kind ' + kind);
}

function hold(sim: Sim, ticks: number): void {
  sim.actHeld = true;
  for (let i = 0; i < ticks; i++) tickSim(sim);
  sim.actHeld = false;
  tickSim(sim);
}

function events(sim: Sim, code: number): number {
  let n = 0;
  for (let i = 0; i < sim.events.length; i += 3) if (sim.events[i] === code) n++;
  return n;
}

/** Aims at a flat, empty cell near spawn and returns it. */
function aimAtGround(sim: Sim): { cx: number; cz: number } {
  const p = sim.player;
  p.yaw = 0;
  p.pitch = -0.6;
  updateTarget(sim);
  expect(sim.target.type).toBe(TargetType.Ground);
  return { cx: sim.target.cx, cz: sim.target.cz };
}

describe('resource nodes', () => {
  it('generates every kind, deterministically, on the ground', () => {
    const a = createSim(SEED);
    const b = createSim(SEED);
    expect(a.nodes.count).toBeGreaterThan(150);
    expect(Array.from(a.nodes.x)).toEqual(Array.from(b.nodes.x));
    for (const kind of Object.values(NodeKind)) expect(Array.from(a.nodes.kind)).toContain(kind);
    for (let i = 0; i < a.nodes.count; i++) {
      expect(a.nodes.y[i]).toBeCloseTo(heightAt(a.world, a.nodes.x[i], a.nodes.z[i]), 4);
    }
  });

  it('targets the node the player is looking at', () => {
    const sim = createSim(SEED);
    const i = firstNode(sim, NodeKind.Deadwood);
    faceNode(sim, i);
    expect(sim.target.type).toBe(TargetType.Node);
    expect(sim.target.id).toBe(i);
  });

  it('gathers wood by hand, slowly, until the node is used up', () => {
    const sim = createSim(SEED);
    sim.selected = 3; // empty slot: bare hands
    const i = firstNode(sim, NodeKind.Deadwood);
    faceNode(sim, i);
    hold(sim, 14);
    expect(count(sim.inventory, Item.Wood)).toBe(1);
    hold(sim, 14 * 20);
    expect(count(sim.inventory, Item.Wood)).toBe(NODE_DEFS[NodeKind.Deadwood].hp);
    expect(sim.nodes.hp[i]).toBe(0);
    expect(events(sim, Ev.NodeGone)).toBe(1);
    updateTarget(sim);
    expect(sim.target.type).not.toBe(TargetType.Node);
  });

  it('gathers faster with the right tool and spends charge', () => {
    const sim = createSim(SEED);
    sim.inventory.ids[0] = Item.Axe;
    sim.inventory.counts[0] = 1;
    sim.selected = 0;
    faceNode(sim, firstNode(sim, NodeKind.Deadwood));
    hold(sim, 15);
    expect(count(sim.inventory, Item.Wood)).toBe(3);
    expect(sim.vitals.charge).toBeLessThan(VITAL_MAX);
  });

  it('refuses ore without a pick and says so once', () => {
    const sim = createSim(SEED);
    sim.selected = 3; // empty slot
    const i = firstNode(sim, NodeKind.Ore);
    faceNode(sim, i);
    hold(sim, 60);
    expect(count(sim.inventory, Item.IronOre)).toBe(0);
    expect(events(sim, Ev.NeedTool)).toBe(1);
    sim.inventory.ids[3] = Item.Pick;
    sim.inventory.counts[3] = 1;
    hold(sim, 6);
    expect(count(sim.inventory, Item.IronOre)).toBe(1);
  });

  it('stops gathering when the bag is full', () => {
    const sim = createSim(SEED);
    for (let s = 0; s < sim.inventory.size; s++) {
      sim.inventory.ids[s] = Item.Stone;
      sim.inventory.counts[s] = 50;
    }
    const i = firstNode(sim, NodeKind.Deadwood);
    faceNode(sim, i);
    hold(sim, 40);
    expect(sim.nodes.hp[i]).toBe(NODE_DEFS[NodeKind.Deadwood].hp);
    expect(events(sim, Ev.BagFull)).toBe(1);
  });

  it('blocks the player from walking through a node', () => {
    const sim = createSim(SEED);
    const i = firstNode(sim, NodeKind.Stone);
    faceNode(sim, i);
    const intent = idle();
    intent.moveY = 1;
    for (let f = 0; f < 120; f++) stepPlayer(sim, intent, 1 / 60);
    const d = Math.hypot(sim.player.x - sim.nodes.x[i], sim.player.z - sim.nodes.z[i]);
    expect(d).toBeGreaterThanOrEqual(NODE_DEFS[NodeKind.Stone].radius * sim.nodes.scale[i] + 0.29);
  });
});

describe('crafting', () => {
  it('offers hand recipes everywhere and all recipes at a workbench', () => {
    expect(recipesFor('hand').every((r) => r.station === 'hand')).toBe(true);
    expect(recipesFor('workbench').length).toBe(RECIPES.length);
    expect(recipesFor('workbench').length).toBeGreaterThan(recipesFor('hand').length);
  });

  it('consumes inputs and adds the output', () => {
    const c = createContainer(6);
    add(c, Item.Wood, 5);
    add(c, Item.Stone, 2);
    const axe = RECIPES.find((r) => r.output[0] === Item.Axe)!;
    expect(hasInputs(c, axe)).toBe(true);
    expect(craft(c, axe)).toBe(true);
    expect(count(c, Item.Axe)).toBe(1);
    expect(count(c, Item.Wood)).toBe(2);
    expect(count(c, Item.Stone)).toBe(0);
  });

  it('fails without changing anything when inputs are short', () => {
    const c = createContainer(6);
    add(c, Item.Wood, 2);
    add(c, Item.Stone, 2);
    const axe = RECIPES.find((r) => r.output[0] === Item.Axe)!;
    expect(craft(c, axe)).toBe(false);
    expect(count(c, Item.Wood)).toBe(2);
    expect(count(c, Item.Stone)).toBe(2);
  });

  it('puts everything back when the output does not fit', () => {
    const c = createContainer(2);
    add(c, Item.Wood, 50);
    add(c, Item.Stone, 50);
    const axe = RECIPES.find((r) => r.output[0] === Item.Axe)!;
    expect(craft(c, axe)).toBe(false);
    expect(count(c, Item.Wood)).toBe(50);
    expect(count(c, Item.Stone)).toBe(50);
    expect(count(c, Item.Axe)).toBe(0);
  });
});

describe('building', () => {
  function withKit(item: number, n = 1): Sim {
    const sim = createSim(SEED);
    sim.inventory.ids[5] = item;
    sim.inventory.counts[5] = n;
    sim.selected = 5;
    return sim;
  }

  it('places the selected kit on the targeted cell and uses it up', () => {
    const sim = withKit(Item.KitCrate);
    const { cx, cz } = aimAtGround(sim);
    expect(sim.target.buildable).toBe(true);
    sim.actPressed = true;
    tickSim(sim);
    const id = entityAt(sim.entities, cx, cz);
    expect(id).toBeGreaterThan(0);
    expect(sim.entities.kind[id]).toBe(EntityKind.Crate);
    expect(sim.entities.containers[id]!.size).toBe(ENTITY_DEFS[EntityKind.Crate].storage);
    expect(sim.inventory.counts[5]).toBe(0);
    expect(events(sim, Ev.Placed)).toBe(1);
  });

  it('does not place on an occupied cell, under the player or on a node', () => {
    const sim = withKit(Item.KitWall, 5);
    const { cx, cz } = aimAtGround(sim);
    sim.actPressed = true;
    tickSim(sim);
    expect(canBuildAt(sim, cx, cz)).toBe(false);
    expect(canBuildAt(sim, Math.floor(sim.player.x), Math.floor(sim.player.z))).toBe(false);
    const n = firstNode(sim, NodeKind.Stone);
    expect(canBuildAt(sim, Math.floor(sim.nodes.x[n]), Math.floor(sim.nodes.z[n]))).toBe(false);
    expect(canBuildAt(sim, 0, 0)).toBe(false);
    // Aiming at the placed wall now targets the entity, so a second press places nothing.
    updateTarget(sim);
    expect(sim.target.type).toBe(TargetType.Entity);
    sim.actPressed = true;
    tickSim(sim);
    expect(sim.inventory.counts[5]).toBe(4);
  });

  it('keeps the player out of a placed entity', () => {
    const sim = withKit(Item.KitWall);
    const { cx, cz } = aimAtGround(sim);
    sim.actPressed = true;
    tickSim(sim);
    const intent = idle();
    intent.moveY = 1;
    for (let f = 0; f < 180; f++) stepPlayer(sim, intent, 1 / 60);
    const p = sim.player;
    const inside = p.x > cx - 0.29 && p.x < cx + 1.29 && p.z > cz - 0.29 && p.z < cz + 1.29;
    expect(inside).toBe(false);
  });

  it('packs up an entity even while another kit is selected', () => {
    const sim = withKit(Item.KitWall, 2);
    const { cx, cz } = aimAtGround(sim);
    sim.actPressed = true;
    tickSim(sim);
    updateTarget(sim);
    hold(sim, 18);
    expect(entityAt(sim.entities, cx, cz)).toBe(0);
    expect(count(sim.inventory, Item.KitWall)).toBe(2);
  });

  it('dismantles an entity back into its kit, returning stored items first', () => {
    const sim = withKit(Item.KitCrate);
    const { cx, cz } = aimAtGround(sim);
    sim.actPressed = true;
    tickSim(sim);
    const id = entityAt(sim.entities, cx, cz);
    add(sim.entities.containers[id]!, Item.Coal, 7);
    updateTarget(sim);
    expect(sim.target.type).toBe(TargetType.Entity);
    hold(sim, 18);
    expect(entityAt(sim.entities, cx, cz)).toBe(0);
    expect(count(sim.inventory, Item.KitCrate)).toBe(1);
    expect(count(sim.inventory, Item.Coal)).toBe(7);
    expect(events(sim, Ev.Removed)).toBe(1);
  });
});

describe('vitals and day cycle', () => {
  it('uses a consumable from the hotbar', () => {
    const sim = createSim(SEED);
    sim.vitals.health = 300;
    sim.selected = 0; // rations
    sim.actPressed = true;
    tickSim(sim);
    expect(sim.vitals.health).toBe(650);
    expect(sim.inventory.counts[0]).toBe(1);
  });

  it('runs day then night and drains warmth faster in the dark', () => {
    const sim = createSim(SEED);
    expect(isNight(sim)).toBe(false);
    for (let i = 0; i < 1200; i++) tickSim(sim);
    const dayLoss = VITAL_MAX - sim.vitals.warmth;
    expect(daylight(sim)).toBe(1);

    sim.tick = DAY_TICKS + 700;
    sim.vitals.warmth = VITAL_MAX;
    expect(isNight(sim)).toBe(true);
    expect(daylight(sim)).toBe(0);
    for (let i = 0; i < 1200; i++) tickSim(sim);
    const nightLoss = VITAL_MAX - sim.vitals.warmth;
    expect(dayLoss).toBe(100);
    expect(nightLoss).toBe(400);
    sim.tick = CYCLE_TICKS;
    expect(isNight(sim)).toBe(false);
  });

  it('warms the player near a campfire', () => {
    const sim = createSim(SEED);
    sim.inventory.ids[5] = Item.KitCampfire;
    sim.inventory.counts[5] = 1;
    sim.selected = 5;
    aimAtGround(sim);
    sim.vitals.warmth = 200;
    expect(nearHeat(sim)).toBe(false);
    sim.actPressed = true;
    tickSim(sim);
    expect(nearHeat(sim)).toBe(true);
    for (let i = 0; i < 100; i++) tickSim(sim);
    expect(sim.vitals.warmth).toBeGreaterThan(600);
  });

  it('freezing drains health, kills, and respawn restores play', () => {
    const sim = createSim(SEED);
    sim.vitals.warmth = 0;
    sim.vitals.health = 5;
    sim.player.x += 20;
    for (let i = 0; i < 20; i++) tickSim(sim);
    expect(sim.dead).toBe(true);
    expect(events(sim, Ev.Died)).toBe(1);
    const tick = sim.tick;
    tickSim(sim);
    expect(sim.tick).toBe(tick + 1);
    respawn(sim);
    expect(sim.dead).toBe(false);
    expect(sim.vitals.health).toBeGreaterThan(0);
    expect(sim.player.x).toBe(128);
  });

  it('sprinting spends charge and stops when it runs out', () => {
    const sim = createSim(SEED);
    const intent = idle();
    intent.moveY = 1;
    intent.sprint = true;
    stepPlayer(sim, intent, 1 / 60);
    expect(sim.sprinting).toBe(true);
    for (let i = 0; i < 100; i++) tickSim(sim);
    expect(sim.vitals.charge).toBe(VITAL_MAX - 50);
    sim.vitals.charge = 0;
    intent.sprint = true;
    stepPlayer(sim, intent, 1 / 60);
    expect(sim.sprinting).toBe(false);
  });
});

describe('save and load', () => {
  it('round-trips a played game through JSON', () => {
    const sim = createSim(SEED);
    const n = firstNode(sim, NodeKind.Deadwood);
    faceNode(sim, n);
    hold(sim, 60);
    sim.player.x = 128;
    sim.player.z = 128;
    sim.inventory.ids[5] = Item.KitCrate;
    sim.inventory.counts[5] = 1;
    sim.selected = 5;
    const { cx, cz } = aimAtGround(sim);
    sim.actPressed = true;
    tickSim(sim);
    add(sim.entities.containers[entityAt(sim.entities, cx, cz)]!, Item.Stone, 9);
    sim.vitals.warmth = 432;

    const copy = deserialize(JSON.parse(JSON.stringify(serialize(sim))))!;
    expect(copy).not.toBeNull();
    expect(copy.tick).toBe(sim.tick);
    expect(copy.player.x).toBeCloseTo(sim.player.x, 2);
    expect(copy.vitals.warmth).toBe(432);
    expect(Array.from(copy.inventory.counts)).toEqual(Array.from(sim.inventory.counts));
    expect(copy.nodes.hp[n]).toBe(sim.nodes.hp[n]);
    const id = entityAt(copy.entities, cx, cz);
    expect(copy.entities.kind[id]).toBe(EntityKind.Crate);
    expect(count(copy.entities.containers[id]!, Item.Stone)).toBe(9);
    expect(copy.entities.y[id]).toBeCloseTo(sim.entities.y[entityAt(sim.entities, cx, cz)], 4);
  });

  it('rejects missing, foreign and wrong-version data', () => {
    expect(deserialize(null)).toBeNull();
    expect(deserialize({ hello: 1 })).toBeNull();
    const data = serialize(createSim(SEED));
    expect(deserialize({ ...data, v: 99 })).toBeNull();
    expect(deserialize({ ...data, nodeHp: [1, 2, 3] })).toBeNull();
  });
});
