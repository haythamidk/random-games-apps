import { MAX_STRAYS, Strays, createStrays, damageStray, tickStrays } from './enemies';
import {
  BELT_SLOTS, ENTITY_DEFS, Entities, EntityKind, createEntities, entityAt, inBounds, placeEntity, removeEntity,
} from './entities';
import { Container, add, createContainer, removeFromSlot, space } from './inventory';
import { ITEMS, Item } from './items';
import {
  Graph, LogicHost, NodeType, addNode, addWire, createGraph, findWire, inputValue, inputWired, removeNode,
  removeWireAt, stepGraph,
} from './logic/graph';
import { BeltOrder, createBeltOrder, tickMachines } from './machines';
import { BUCKET, NODE_DEFS, Nodes, bucketIndex, generateNodes } from './nodes';
import { EYE_HEIGHT, MoveIntent, Player, createPlayer, updatePlayer } from './player';
import { Rng } from './rng';
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
const HIT_TICKS = 8;
const HIT_DAMAGE_HAND = 14;
const HIT_DAMAGE_TOOL = 34;
/** Default detection radius of a proximity sensor, metres. */
export const SENSOR_RANGE = 8;
/** The night warning fires this many ticks before dusk. */
const DUSK_WARNING_TICKS = 60 * TICK_HZ;

/** Survival gauges, each an integer 0..VITAL_MAX. */
export interface Vitals {
  health: number;
  charge: number;
  warmth: number;
}

export const TargetType = { None: 0, Node: 1, Entity: 2, Ground: 3, Stray: 4 } as const;

/** What the reticle is on. Reused every frame; never reallocated. */
export interface Target {
  type: number;
  /** Node index, entity id or stray index. */
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
  /** a = source entity id */
  LinkStart: 10,
  /** a = source entity id, b = target entity id */
  Linked: 11,
  Unlinked: 12,
  /** a = reason: 1 nothing to send, 2 nothing to receive, 3 circuit is full */
  LinkBad: 13,
  /** a = turret entity id, b = stray index */
  Shot: 14,
  /** a = stray index, b = 1 if a turret did it */
  StrayDown: 15,
  /** A stray hit the player. */
  Bite: 16,
  /** One minute until night. */
  Dusk: 17,
  /** a = nights survived so far */
  Dawn: 18,
  /** a = stray index */
  StrayHit: 19,
  /** A drill was aimed at ground with no deposit. */
  NeedDeposit: 20,
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

  rng: Rng;
  /** Item id a drill extracts on each build cell, 0 where there is no deposit. */
  deposits: Uint8Array;
  beltOrder: BeltOrder;
  graph: Graph;
  host: LogicHost;
  strays: Strays;
  /** Entity chosen as the source of the wire being made with the Linker, or 0. */
  linkFrom: number;
  /** Nights lived through without dying. */
  nights: number;
  /** Set if the player died during the current night. */
  diedTonight: boolean;
  /** Callbacks handed to the threat tick; created once so ticking never allocates. */
  onShot: (turret: number, stray: number) => void;
  onDown: (stray: number, byTurret: boolean) => void;
  onBite: () => void;
}

export function createSim(seed: number): Sim {
  const world = createWorld(seed);
  const inventory = createContainer(INVENTORY_SIZE);
  // Starting kit salvaged from the drop pod.
  add(inventory, Item.Ration, 2);
  add(inventory, Item.Cell, 1);
  const nodes = generateNodes(world, seed);
  const deposits = new Uint8Array(WORLD_SIZE * WORLD_SIZE);
  for (const d of nodes.deposits) {
    const r = Math.ceil(d.radius);
    for (let cz = Math.floor(d.z) - r; cz <= Math.floor(d.z) + r; cz++) {
      for (let cx = Math.floor(d.x) - r; cx <= Math.floor(d.x) + r; cx++) {
        if (!inBounds(cx, cz)) continue;
        if (Math.hypot(cx + 0.5 - d.x, cz + 0.5 - d.z) <= d.radius) deposits[cz * WORLD_SIZE + cx] = d.item;
      }
    }
  }
  const sim: Sim = {
    seed,
    tick: 0,
    world,
    nodes,
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
    rng: new Rng(seed ^ 0xa11ce),
    deposits,
    beltOrder: createBeltOrder(),
    graph: createGraph(),
    host: { sense: () => {}, drive: () => {} },
    strays: createStrays(),
    linkFrom: 0,
    nights: 0,
    diedTonight: false,
    onShot: (turret, stray) => emit(sim, Ev.Shot, turret, stray),
    onDown: (stray, byTurret) => {
      emit(sim, Ev.StrayDown, stray, byTurret ? 1 : 0);
      // Wrecks near the player sometimes leave a usable circuit behind.
      const st = sim.strays;
      const near = Math.hypot(st.x[stray] - sim.player.x, st.z[stray] - sim.player.z) < 15;
      if (near && sim.rng.next() < 0.5 && add(sim.inventory, Item.Circuit, 1) === 0) emit(sim, Ev.Gather, Item.Circuit, 1);
    },
    onBite: () => emit(sim, Ev.Bite),
  };
  sim.host = createHost(sim);
  return sim;
}

/** Connects Device nodes in the logic graph to the entities they stand for. */
function createHost(sim: Sim): LogicHost {
  const es = sim.entities;
  const g = sim.graph;
  return {
    sense(entity, node, out, base) {
      if (es.kind[entity] !== EntityKind.Sensor) {
        out[base] = es.active[entity];
        return;
      }
      // Proximity sensor: nearest stray, or the player unless set to strays only.
      const x = es.cx[entity] + 0.5;
      const z = es.cz[entity] + 0.5;
      const range = g.param[node * 2] || SENSOR_RANGE;
      let best = range;
      const st = sim.strays;
      for (let i = 0; i < MAX_STRAYS; i++) {
        if (!st.alive[i]) continue;
        const d = Math.hypot(st.x[i] - x, st.z[i] - z);
        if (d < best) best = d;
      }
      if (g.param[node * 2 + 1] === 0 && !sim.dead) {
        const d = Math.hypot(sim.player.x - x, sim.player.z - z);
        if (d < best) best = d;
      }
      const seen = best < range;
      out[base] = seen ? 1 : 0;
      out[base + 1] = seen ? Math.round(best * 10) : 0;
      es.active[entity] = seen ? 1 : 0;
    },
    drive(entity, node) {
      const def = ENTITY_DEFS[es.kind[entity]];
      if (def.ins.length === 0) return;
      // Machines and belts run unless a wire says otherwise; turrets and lamps need a signal.
      es.enabled[entity] = inputWired(g, node, 0) ? (inputValue(g, node, 0) > 0 ? 1 : 0) : needsSignal(es.kind[entity]) ? 0 : 1;
    },
  };
}

function needsSignal(kind: number): boolean {
  return kind === EntityKind.Turret || kind === EntityKind.Lamp;
}

/** Gives an entity a node in the logic graph if it has none. Returns the node id, or 0 if the graph is full. */
export function ensureNode(sim: Sim, entity: number): number {
  const es = sim.entities;
  if (es.node[entity] !== 0) return es.node[entity];
  const g = sim.graph;
  const id = addNode(g, NodeType.Device, entity);
  if (id === 0) return 0;
  // Lay new devices out on the circuit board in rows of five.
  g.bx[id] = ((id - 1) % 5) * 150;
  g.by[id] = Math.floor((id - 1) / 5) * 110;
  if (es.kind[entity] === EntityKind.Sensor) g.param[id * 2] = SENSOR_RANGE;
  es.node[entity] = id;
  return id;
}

/** Flips a switch entity. */
export function toggleSwitch(sim: Sim, entity: number): void {
  if (sim.entities.kind[entity] === EntityKind.Switch) sim.entities.active[entity] ^= 1;
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
      if (!def.solid) continue;
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
  const st = sim.strays;
  for (let d = RAY_STEP; d <= REACH; d += RAY_STEP) {
    px = ox + dx * d;
    py = oy + dy * d;
    pz = oz + dz * d;

    if (st.count > 0) {
      for (let i = 0; i < MAX_STRAYS; i++) {
        if (!st.alive[i]) continue;
        const sx = px - st.x[i];
        const sy = py - st.y[i];
        const sz = pz - st.z[i];
        if (sx * sx + sy * sy + sz * sz < 0.75 * 0.75) {
          t.type = TargetType.Stray;
          t.id = i;
          return;
        }
      }
    }

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
  t.buildable = canBuildAt(sim, t.cx, t.cz, ITEMS[selectedItem(sim)].places ?? 0);
}

/** Whether an entity (of `kind`, if given) may be placed on a build cell. */
export function canBuildAt(sim: Sim, cx: number, cz: number, kind = 0): boolean {
  if (!inBounds(cx, cz) || cx < 3 || cz < 3 || cx > WORLD_SIZE - 4 || cz > WORLD_SIZE - 4) return false;
  if (entityAt(sim.entities, cx, cz) !== 0) return false;
  if (kind === EntityKind.Drill && sim.deposits[cz * WORLD_SIZE + cx] === 0) return false;

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
  const wasNight = isNight(sim) || sim.tick % CYCLE_TICKS === 0;
  tickActions(sim);
  tickMachines(sim);
  if (sim.tick % 2 === 0) stepGraph(sim.graph, sim.host);
  tickThreat(sim, wasNight);
  tickVitals(sim);
}

/** Runs the strays and turrets, and announces dusk and dawn. */
function tickThreat(sim: Sim, wasNight: boolean): void {
  const phase = sim.tick % CYCLE_TICKS;
  const night = phase >= DAY_TICKS;
  if (phase === DAY_TICKS - DUSK_WARNING_TICKS) emit(sim, Ev.Dusk);
  if (phase === DAY_TICKS) sim.diedTonight = false;
  if (phase === 0 && wasNight && sim.tick > 0) {
    if (!sim.diedTonight) sim.nights++;
    emit(sim, Ev.Dawn, sim.nights, sim.diedTonight ? 0 : 1);
  }
  tickStrays(sim, night, Math.floor(sim.tick / CYCLE_TICKS), sim.onShot, sim.onDown, sim.onBite);
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
    if (pressed && sim.target.type === TargetType.Ground) {
      if (canBuildAt(sim, cx, cz, def.places)) {
        const y = heightAt(sim.world, cx + 0.5, cz + 0.5);
        const id = placeEntity(sim.entities, def.places, cx, cz, sim.buildRot, y);
        if (id !== 0) {
          if (needsSignal(def.places)) sim.entities.enabled[id] = 0;
          if (ENTITY_DEFS[def.places].autoNode) ensureNode(sim, id);
          removeFromSlot(inv, sim.selected, 1);
          emit(sim, Ev.Placed, id, def.places);
        }
      } else if (def.places === EntityKind.Drill && canBuildAt(sim, cx, cz)) {
        emit(sim, Ev.NeedDeposit);
      }
    }
    return;
  }

  if (def.kind === 'linker') {
    resetWork(sim);
    if (pressed) tickLinker(sim);
    return;
  }
  sim.linkFrom = 0;

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
  } else if (t.type === TargetType.Stray) {
    if (!sim.strays.alive[t.id]) {
      w.progress = 0;
      w.need = 0;
    } else {
      w.need = HIT_TICKS;
      if (++w.progress >= w.need) {
        w.progress = 0;
        emit(sim, Ev.StrayHit, t.id);
        if (damageStray(sim, t.id, def.tool ? HIT_DAMAGE_TOOL : HIT_DAMAGE_HAND)) sim.onDown(t.id, false);
      }
    }
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
  if (kind === EntityKind.Belt) {
    for (let s = id * BELT_SLOTS; s < (id + 1) * BELT_SLOTS; s++) {
      if (es.belt[s] === 0) continue;
      if (add(inv, es.belt[s], 1) > 0) {
        warnOnce(sim, Ev.BagFull);
        return;
      }
      es.belt[s] = 0;
    }
  }
  if (space(inv, def.kit) === 0) {
    warnOnce(sim, Ev.BagFull);
    return;
  }
  add(inv, def.kit, 1);
  if (es.node[id] !== 0) removeNode(sim.graph, es.node[id]);
  if (sim.linkFrom === id) sim.linkFrom = 0;
  removeEntity(es, id);
  emit(sim, Ev.Removed, id, kind);
}

/**
 * One press of the Linker. The first press on something that can send a signal
 * picks it as the source; the second press on something that can receive one
 * wires them together, or unwires them if they already were.
 */
function tickLinker(sim: Sim): void {
  const t = sim.target;
  const es = sim.entities;
  if (t.type !== TargetType.Entity) {
    sim.linkFrom = 0;
    return;
  }
  const def = ENTITY_DEFS[es.kind[t.id]];
  if (sim.linkFrom === 0 || es.kind[sim.linkFrom] === 0) {
    if (def.outs.length === 0) {
      emit(sim, Ev.LinkBad, def.ins.length > 0 ? 1 : 0);
      return;
    }
    sim.linkFrom = t.id;
    emit(sim, Ev.LinkStart, t.id);
    return;
  }
  const from = sim.linkFrom;
  sim.linkFrom = 0;
  if (from === t.id) return;
  if (def.ins.length === 0) {
    emit(sim, Ev.LinkBad, 2);
    return;
  }
  const a = ensureNode(sim, from);
  const b = ensureNode(sim, t.id);
  if (a === 0 || b === 0) {
    emit(sim, Ev.LinkBad, 3);
    return;
  }
  const g = sim.graph;
  const existing = findWire(g, a, 0, b, 0);
  if (existing >= 0) {
    removeWireAt(g, existing);
    emit(sim, Ev.Unlinked, from, t.id);
  } else if (addWire(g, a, 0, b, 0)) {
    emit(sim, Ev.Linked, from, t.id);
  } else {
    emit(sim, Ev.LinkBad, 3);
  }
}

/** True when the player stands within the heat radius of a fire or running machine. */
export function nearHeat(sim: Sim): boolean {
  const es = sim.entities;
  const p = sim.player;
  for (let id = 1; id < es.high; id++) {
    const def = ENTITY_DEFS[es.kind[id]];
    const heat = def.heat;
    if (heat === 0 || (!def.alwaysHot && !es.active[id])) continue;
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
    sim.diedTonight = true;
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
