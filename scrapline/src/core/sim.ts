import { ENTITY_DEFS, Entities, createEntities, entityAt, inBounds, placeEntity, removeEntity } from './entities';
import { Container, add, createContainer, removeFromSlot, space } from './inventory';
import { ITEMS, Item } from './items';
import { BUCKET, NODE_DEFS, Nodes, bucketIndex, generateNodes } from './nodes';
import { EYE_HEIGHT, MoveIntent, Player, createPlayer, updatePlayer } from './player';
import { WORLD_SIZE, World, createWorld, heightAt } from './world';

export const TICK_HZ = 20;
export const TICK_DT = 1 / TICK_HZ;

export const HOTBAR_SIZE = 6;
export const INVENTORY_SIZE = 24;
export const VITAL_MAX = 1000;

/** Day and night lengths in ticks: 14 minutes of light, 4 minutes of dark. */
export const DAY_TICKS = 14 * 60 * TICK_HZ;
export const NIGHT_TICKS = 4 * 60 * TICK_HZ;
export const CYCLE_TICKS = DAY_TICKS + NIGHT_TICKS;
/** Length of the dusk and dawn fades. */
const TWILIGHT_TICKS = 30 * TICK_HZ;

/** How far the player can reach to gather, use and build, metres. */
export const REACH = 4.5;
const RAY_STEP = 0.15;
const PLAYER_RADIUS = 0.3;

const SWING_TICKS_HAND = 14;
const SWING_TICKS_TOOL = 5;
const SWING_CHARGE_COST = 3;
const DISMANTLE_TICKS = 18;

/** Survival gauges, each an integer 0..VITAL_MAX. */
export interface Vitals {
  health: number;
  charge: number;
  warmth: number;
}

export const TargetType = { None: 0, Node: 1, Entity: 2, Ground: 3 } as const;

/** What the reticle is on. Reused every frame; never reallocated. */
export interface Target {
  type: number;
  /** Node index or entity id. */
  id: number;
  /** Build cell under the reticle (valid for Ground and Entity). */
  cx: number;
  cz: number;
  /** For Ground: whether an entity may be placed on this cell. */
  buildable: boolean;
}

/** Progress of the held action (gathering a node or dismantling an entity). */
export interface Work {
  type: number;
  id: number;
  progress: number;
  need: number;
  /** Set once a "can't do that" event was sent for this press, to avoid spamming. */
  warned: boolean;
}

/** Event codes pushed to `Sim.events` as [code, a, b] triples for the view and UI. */
export const Ev = {
  /** a = item id, b = amount */
  Gather: 1,
  /** a = node index */
  Swing: 2,
  /** a = node index */
  NodeGone: 3,
  /** a = entity id, b = kind */
  Placed: 4,
  /** a = entity id, b = kind */
  Removed: 5,
  BagFull: 6,
  /** a = item id */
  Used: 7,
  /** a = node kind */
  NeedTool: 8,
  Died: 9,
} as const;

/** Whole game state. Everything the view and UI show is read from here. */
export interface Sim {
  seed: number;
  /** Fixed ticks elapsed since the world was created. Tick 0 is dawn. */
  tick: number;
  world: World;
  nodes: Nodes;
  entities: Entities;
  player: Player;
  /** Slots 0..HOTBAR_SIZE-1 are the hotbar, the rest is the bag. */
  inventory: Container;
  /** Selected hotbar slot. */
  selected: number;
  vitals: Vitals;
  target: Target;
  work: Work;
  /** Quarter turns applied to the next placed entity. */
  buildRot: number;
  /** Input state, written by the main loop. */
  actHeld: boolean;
  actPressed: boolean;
  sprinting: boolean;
  dead: boolean;
  /** Flat [code, a, b] triples; the main loop clears it after the view and UI have read it. */
  events: number[];
}

export function createSim(seed: number): Sim {
  const world = createWorld(seed);
  const inventory = createContainer(INVENTORY_SIZE);
  // Starting kit salvaged from the drop pod.
  add(inventory, Item.Ration, 2);
  add(inventory, Item.Cell, 1);
  return {
    seed,
    tick: 0,
    world,
    nodes: generateNodes(world, seed),
    entities: createEntities(),
    player: createPlayer(world),
    inventory,
    selected: 0,
    vitals: { health: VITAL_MAX, charge: VITAL_MAX, warmth: VITAL_MAX },
    target: { type: TargetType.None, id: 0, cx: 0, cz: 0, buildable: false },
    work: { type: 0, id: 0, progress: 0, need: 0, warned: false },
    buildRot: 0,
    actHeld: false,
    actPressed: false,
    sprinting: false,
    dead: false,
    events: [],
  };
}

function emit(sim: Sim, code: number, a = 0, b = 0): void {
  sim.events.push(code, a, b);
}

/** Item id in the selected hotbar slot, or 0. */
export function selectedItem(sim: Sim): number {
  return sim.inventory.counts[sim.selected] > 0 ? sim.inventory.ids[sim.selected] : 0;
}

// ---------------------------------------------------------------- day / night

/** 1 in full daylight, 0 at night, fading through dusk and dawn. */
export function daylight(sim: Sim): number {
  const t = sim.tick % CYCLE_TICKS;
  if (t < DAY_TICKS) {
    const toDusk = DAY_TICKS - t;
    if (toDusk < TWILIGHT_TICKS) return toDusk / TWILIGHT_TICKS;
    if (t < TWILIGHT_TICKS) return 0.35 + 0.65 * (t / TWILIGHT_TICKS);
    return 1;
  }
  const toDawn = CYCLE_TICKS - t;
  return toDawn < TWILIGHT_TICKS ? 0.35 * (1 - toDawn / TWILIGHT_TICKS) : 0;
}

export function isNight(sim: Sim): boolean {
  return sim.tick % CYCLE_TICKS >= DAY_TICKS;
}

/** Ticks until the next change between day and night. */
export function ticksToPhaseChange(sim: Sim): number {
  const t = sim.tick % CYCLE_TICKS;
  return t < DAY_TICKS ? DAY_TICKS - t : CYCLE_TICKS - t;
}

// ---------------------------------------------------------------- per frame

/**
 * Moves the player for one rendered frame, keeps them out of solid things and
 * refreshes what the reticle is on.
 */
export function stepPlayer(sim: Sim, intent: MoveIntent, dt: number): void {
  if (sim.dead) {
    intent.moveX = 0;
    intent.moveY = 0;
    intent.jump = false;
  }
  if (sim.vitals.charge <= 0) intent.sprint = false;
  sim.sprinting = intent.sprint && (intent.moveX !== 0 || intent.moveY !== 0);
  updatePlayer(sim.player, sim.world, intent, dt);
  collide(sim);
  updateTarget(sim);
}

/** Pushes the player out of resource nodes and occupied build cells. */
function collide(sim: Sim): void {
  const p = sim.player;
  const nodes = sim.nodes;

  for (let oz = -1; oz <= 1; oz += 2) {
    for (let ox = -1; ox <= 1; ox += 2) {
      // Sample the four buckets around the player; duplicates are harmless.
      const list = nodes.buckets[bucketIndex(p.x + ox * 1.5, p.z + oz * 1.5)];
      for (let k = 0; k < list.length; k++) {
        const i = list[k];
        if (nodes.hp[i] === 0) continue;
        const def = NODE_DEFS[nodes.kind[i]];
        if (p.y > nodes.y[i] + def.height * nodes.scale[i]) continue;
        const r = def.radius * nodes.scale[i] + PLAYER_RADIUS;
        const dx = p.x - nodes.x[i];
        const dz = p.z - nodes.z[i];
        const d2 = dx * dx + dz * dz;
        if (d2 < r * r && d2 > 1e-8) {
          const d = Math.sqrt(d2);
          p.x = nodes.x[i] + (dx / d) * r;
          p.z = nodes.z[i] + (dz / d) * r;
        }
      }
    }
  }

  const es = sim.entities;
  const pcx = Math.floor(p.x);
  const pcz = Math.floor(p.z);
  for (let cz = pcz - 1; cz <= pcz + 1; cz++) {
    for (let cx = pcx - 1; cx <= pcx + 1; cx++) {
      const id = entityAt(es, cx, cz);
      if (id === 0) continue;
      const def = ENTITY_DEFS[es.kind[id]];
      if (def.heat > 0) continue; // fires are walk-through
      if (p.y > es.y[id] + def.height) continue;
      // Closest point on the cell's square to the player.
      const qx = p.x < cx ? cx : p.x > cx + 1 ? cx + 1 : p.x;
      const qz = p.z < cz ? cz : p.z > cz + 1 ? cz + 1 : p.z;
      const dx = p.x - qx;
      const dz = p.z - qz;
      const d2 = dx * dx + dz * dz;
      if (d2 >= PLAYER_RADIUS * PLAYER_RADIUS) continue;
      if (d2 > 1e-8) {
        const d = Math.sqrt(d2);
        p.x = qx + (dx / d) * PLAYER_RADIUS;
        p.z = qz + (dz / d) * PLAYER_RADIUS;
      } else {
        // Centre is inside the cell: leave through the nearest side.
        const l = p.x - cx;
        const r = cx + 1 - p.x;
        const t = p.z - cz;
        const b = cz + 1 - p.z;
        const m = Math.min(l, r, t, b);
        if (m === l) p.x = cx - PLAYER_RADIUS;
        else if (m === r) p.x = cx + 1 + PLAYER_RADIUS;
        else if (m === t) p.z = cz - PLAYER_RADIUS;
        else p.z = cz + 1 + PLAYER_RADIUS;
      }
    }
  }
}

/** Marches a ray from the eye and records the first node, entity or ground cell it meets. */
export function updateTarget(sim: Sim): void {
  const p = sim.player;
  const t = sim.target;
  const cp = Math.cos(p.pitch);
  const dx = -Math.sin(p.yaw) * cp;
  const dy = Math.sin(p.pitch);
  const dz = -Math.cos(p.yaw) * cp;
  const ox = p.x;
  const oy = p.y + EYE_HEIGHT;
  const oz = p.z;
  const nodes = sim.nodes;
  const es = sim.entities;

  t.type = TargetType.None;
  t.id = 0;
  t.buildable = false;

  let px = ox;
  let py = oy;
  let pz = oz;
  for (let d = RAY_STEP; d <= REACH; d += RAY_STEP) {
    px = ox + dx * d;
    py = oy + dy * d;
    pz = oz + dz * d;

    const cx = Math.floor(px);
    const cz = Math.floor(pz);
    const id = entityAt(es, cx, cz);
    if (id !== 0 && py <= es.y[id] + ENTITY_DEFS[es.kind[id]].height) {
      t.type = TargetType.Entity;
      t.id = id;
      t.cx = cx;
      t.cz = cz;
      return;
    }

    let lastBucket = -1;
    for (let c = 0; c < 4; c++) {
      const b = bucketIndex(px + (c & 1 ? 1 : -1), pz + (c & 2 ? 1 : -1));
      if (b === lastBucket) continue;
      lastBucket = b;
      const list = nodes.buckets[b];
      for (let k = 0; k < list.length; k++) {
        const i = list[k];
        if (nodes.hp[i] === 0) continue;
        const def = NODE_DEFS[nodes.kind[i]];
        const s = nodes.scale[i];
        if (py < nodes.y[i] - 0.2 || py > nodes.y[i] + def.height * s) continue;
        const r = def.radius * s + 0.12;
        const ndx = px - nodes.x[i];
        const ndz = pz - nodes.z[i];
        if (ndx * ndx + ndz * ndz < r * r) {
          t.type = TargetType.Node;
          t.id = i;
          return;
        }
      }
    }

    if (py <= heightAt(sim.world, px, pz)) break;
  }

  // Ground under the ray's end point (or where it went below the surface).
  t.type = TargetType.Ground;
  t.cx = Math.floor(px);
  t.cz = Math.floor(pz);
  t.buildable = canBuildAt(sim, t.cx, t.cz);
}

/** Whether an entity may be placed on a build cell. */
export function canBuildAt(sim: Sim, cx: number, cz: number): boolean {
  if (!inBounds(cx, cz) || cx < 3 || cz < 3 || cx > WORLD_SIZE - 4 || cz > WORLD_SIZE - 4) return false;
  if (entityAt(sim.entities, cx, cz) !== 0) return false;

  const w = sim.world;
  const h0 = heightAt(w, cx, cz);
  const h1 = heightAt(w, cx + 1, cz);
  const h2 = heightAt(w, cx, cz + 1);
  const h3 = heightAt(w, cx + 1, cz + 1);
  if (Math.max(h0, h1, h2, h3) - Math.min(h0, h1, h2, h3) > 0.7) return false;

  const mx = cx + 0.5;
  const mz = cz + 0.5;
  const p = sim.player;
  if (Math.abs(p.x - mx) < 0.5 + PLAYER_RADIUS && Math.abs(p.z - mz) < 0.5 + PLAYER_RADIUS) return false;

  const nodes = sim.nodes;
  let lastBucket = -1;
  for (let c = 0; c < 4; c++) {
    const b = bucketIndex(mx + (c & 1 ? BUCKET / 4 : -BUCKET / 4), mz + (c & 2 ? BUCKET / 4 : -BUCKET / 4));
    if (b === lastBucket) continue;
    lastBucket = b;
    const list = nodes.buckets[b];
    for (let k = 0; k < list.length; k++) {
      const i = list[k];
      if (nodes.hp[i] === 0) continue;
      const r = NODE_DEFS[nodes.kind[i]].radius * nodes.scale[i] + 0.6;
      const dx = nodes.x[i] - mx;
      const dz = nodes.z[i] - mz;
      if (dx * dx + dz * dz < r * r) return false;
    }
  }
  return true;
}

// ---------------------------------------------------------------- fixed tick

/** Advances the fixed-rate simulation by one tick. Must not allocate. */
export function tickSim(sim: Sim): void {
  sim.tick++;
  if (sim.dead) {
    sim.actPressed = false;
    return;
  }
  tickActions(sim);
  tickVitals(sim);
}

function tickActions(sim: Sim): void {
  const inv = sim.inventory;
  const def = ITEMS[selectedItem(sim)];
  const pressed = sim.actPressed;
  sim.actPressed = false;

  // A kit places on the ground; aimed at an existing entity it falls through
  // to the hold-to-pack-up behaviour below, so a misplaced piece is easy to undo.
  if (def.kind === 'kit' && def.places && sim.target.type !== TargetType.Entity) {
    resetWork(sim);
    const { cx, cz } = sim.target;
    if (pressed && sim.target.type === TargetType.Ground && canBuildAt(sim, cx, cz)) {
      const y = heightAt(sim.world, cx + 0.5, cz + 0.5);
      const id = placeEntity(sim.entities, def.places, cx, cz, sim.buildRot, y);
      if (id !== 0) {
        removeFromSlot(inv, sim.selected, 1);
        emit(sim, Ev.Placed, id, def.places);
      }
    }
    return;
  }

  if (def.kind === 'consumable' && def.use) {
    resetWork(sim);
    if (pressed) {
      const v = sim.vitals;
      v.health = Math.min(VITAL_MAX, v.health + (def.use.health ?? 0));
      v.charge = Math.min(VITAL_MAX, v.charge + (def.use.charge ?? 0));
      v.warmth = Math.min(VITAL_MAX, v.warmth + (def.use.warmth ?? 0));
      removeFromSlot(inv, sim.selected, 1);
      emit(sim, Ev.Used, def.id);
    }
    return;
  }

  if (!sim.actHeld) {
    resetWork(sim);
    sim.work.warned = false;
    return;
  }

  const t = sim.target;
  const w = sim.work;
  if (t.type !== w.type || t.id !== w.id) {
    w.type = t.type;
    w.id = t.id;
    w.progress = 0;
    w.need = 0;
  }

  // The target is refreshed once per frame, but several ticks can run in one
  // frame, so never trust it to still exist.
  if (t.type === TargetType.Node && sim.nodes.hp[t.id] === 0) {
    w.progress = 0;
    w.need = 0;
  } else if (t.type === TargetType.Entity && sim.entities.kind[t.id] === 0) {
    w.progress = 0;
    w.need = 0;
  } else if (t.type === TargetType.Node) {
    const nodes = sim.nodes;
    const nd = NODE_DEFS[nodes.kind[t.id]];
    const fits = def.tool !== undefined && (def.tool.gathers & nd.gatherClass) !== 0;
    if (nd.needsTool && !fits) {
      warnOnce(sim, Ev.NeedTool, nodes.kind[t.id]);
      w.progress = 0;
      return;
    }
    if (space(inv, nd.item) === 0) {
      warnOnce(sim, Ev.BagFull);
      w.progress = 0;
      return;
    }
    const powered = fits && sim.vitals.charge > 0;
    w.need = powered ? SWING_TICKS_TOOL : SWING_TICKS_HAND;
    if (++w.progress >= w.need) {
      w.progress = 0;
      nodes.hp[t.id]--;
      add(inv, nd.item, 1);
      if (powered) sim.vitals.charge = Math.max(0, sim.vitals.charge - SWING_CHARGE_COST);
      emit(sim, Ev.Gather, nd.item, 1);
      emit(sim, Ev.Swing, t.id);
      if (nodes.hp[t.id] === 0) emit(sim, Ev.NodeGone, t.id);
    }
  } else if (t.type === TargetType.Entity) {
    w.need = DISMANTLE_TICKS;
    if (++w.progress >= w.need) {
      w.progress = 0;
      dismantle(sim, t.id);
    }
  } else {
    w.progress = 0;
    w.need = 0;
  }
}

function resetWork(sim: Sim): void {
  const w = sim.work;
  w.type = 0;
  w.id = 0;
  w.progress = 0;
  w.need = 0;
}

function warnOnce(sim: Sim, code: number, a = 0): void {
  if (sim.work.warned) return;
  sim.work.warned = true;
  emit(sim, code, a);
}

/** Returns an entity to the bag as its kit, emptying its storage into the bag first. */
function dismantle(sim: Sim, id: number): void {
  const es = sim.entities;
  const kind = es.kind[id];
  const def = ENTITY_DEFS[kind];
  const inv = sim.inventory;
  const store = es.containers[id];
  if (store) {
    for (let i = 0; i < store.size; i++) {
      if (store.counts[i] === 0) continue;
      const left = add(inv, store.ids[i], store.counts[i]);
      removeFromSlot(store, i, store.counts[i] - left);
      if (left > 0) {
        warnOnce(sim, Ev.BagFull);
        return;
      }
    }
  }
  if (space(inv, def.kit) === 0) {
    warnOnce(sim, Ev.BagFull);
    return;
  }
  add(inv, def.kit, 1);
  removeEntity(es, id);
  emit(sim, Ev.Removed, id, kind);
}

/** True when the player stands within the heat radius of a fire or running machine. */
export function nearHeat(sim: Sim): boolean {
  const es = sim.entities;
  const p = sim.player;
  for (let id = 1; id < es.high; id++) {
    const heat = ENTITY_DEFS[es.kind[id]].heat;
    if (heat === 0) continue;
    const dx = es.cx[id] + 0.5 - p.x;
    const dz = es.cz[id] + 0.5 - p.z;
    if (dx * dx + dz * dz < heat * heat) return true;
  }
  return false;
}

function tickVitals(sim: Sim): void {
  const v = sim.vitals;
  const night = isNight(sim);
  const tick = sim.tick;

  // Warmth: drains outdoors (four times faster at night), refills fast near heat.
  if (nearHeat(sim)) v.warmth = Math.min(VITAL_MAX, v.warmth + 5);
  else if (tick % (night ? 3 : 12) === 0) v.warmth = Math.max(0, v.warmth - 1);

  // Charge: sprinting spends it; daylight slowly trickles it back.
  if (sim.sprinting) {
    if (tick % 2 === 0) v.charge = Math.max(0, v.charge - 1);
  } else if (!night && tick % 8 === 0) {
    v.charge = Math.min(VITAL_MAX, v.charge + 1);
  }

  // Health: freezing hurts; staying warm slowly heals.
  if (v.warmth === 0) {
    if (tick % 2 === 0) v.health--;
  } else if (v.warmth > 400 && v.health < VITAL_MAX && tick % 10 === 0) {
    v.health++;
  }

  if (v.health <= 0) {
    v.health = 0;
    sim.dead = true;
    resetWork(sim);
    emit(sim, Ev.Died);
  }
}

/** Puts the player back at the landing pad after dying. Inventory and base are kept. */
export function respawn(sim: Sim): void {
  const p = sim.player;
  p.x = WORLD_SIZE / 2;
  p.z = WORLD_SIZE / 2;
  p.y = heightAt(sim.world, p.x, p.z);
  p.vx = p.vy = p.vz = 0;
  p.onGround = true;
  sim.vitals.health = 600;
  sim.vitals.charge = Math.max(sim.vitals.charge, 400);
  sim.vitals.warmth = 600;
  sim.dead = false;
}
