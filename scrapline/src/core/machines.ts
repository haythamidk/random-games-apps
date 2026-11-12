import { BELT_SLOTS, ENTITY_DEFS, Entities, EntityKind, MAX_ENTITIES, entityInFront } from './entities';
import { removeFromSlot } from './inventory';
import { Item, STACK_MAX } from './items';
import type { Sim } from './sim';
import { WORLD_SIZE } from './world';

/** Ticks to smelt one ore, and how long one coal burns (four ingots' worth). */
export const SMELT_TICKS = 100;
export const FUEL_TICKS = 400;
/** Ticks for a drill to extract one item. */
export const DRILL_TICKS = 40;
/** Belts advance one slot every this many ticks (a tile is BELT_SLOTS slots long). */
export const BELT_PERIOD = 3;

export const SMELTER_ORE = 0;
export const SMELTER_COAL = 1;
export const SMELTER_OUT = 2;

/** Cached belt processing order (downstream belts first), rebuilt only when entities change. */
export interface BeltOrder {
  ids: Uint16Array;
  count: number;
  /** `Entities.version` the order was built for. */
  version: number;
  visited: Uint8Array;
  path: Uint16Array;
}

export function createBeltOrder(): BeltOrder {
  return {
    ids: new Uint16Array(MAX_ENTITIES),
    count: 0,
    version: -1,
    visited: new Uint8Array(MAX_ENTITIES),
    path: new Uint16Array(MAX_ENTITIES),
  };
}

/**
 * Orders belts so each one is stepped after the belt it feeds. A full line then
 * advances in a single pass, and an item can never move two slots in one step.
 */
function rebuildOrder(es: Entities, order: BeltOrder): void {
  order.visited.fill(0);
  order.count = 0;
  for (let id = 1; id < es.high; id++) {
    if (es.kind[id] !== EntityKind.Belt || order.visited[id]) continue;
    let n = 0;
    let cur = id;
    while (cur !== 0 && es.kind[cur] === EntityKind.Belt && !order.visited[cur]) {
      order.visited[cur] = 1;
      order.path[n++] = cur;
      cur = entityInFront(es, cur);
    }
    while (n > 0) order.ids[order.count++] = order.path[--n];
  }
  order.version = es.version;
}

/** Whether a belt would be pushing straight back into the thing that feeds it. */
function facesBack(es: Entities, belt: number, feeder: number): boolean {
  return entityInFront(es, belt) === feeder;
}

/** Offers one item to whatever an entity faces. Returns true if it was taken. */
function pushForward(es: Entities, from: number, item: number): boolean {
  const to = entityInFront(es, from);
  if (to === 0) return false;
  const kind = es.kind[to];
  if (kind === EntityKind.Belt) {
    if (facesBack(es, to, from) || es.belt[to * BELT_SLOTS] !== 0) return false;
    es.belt[to * BELT_SLOTS] = item;
    es.beltMoved[to * BELT_SLOTS] = 1;
    return true;
  }
  return acceptItem(es, to, item);
}

/** Puts one item into a machine or crate if it takes that item and has room. */
export function acceptItem(es: Entities, id: number, item: number): boolean {
  const c = es.containers[id];
  if (!c) return false;
  const kind = es.kind[id];
  if (kind === EntityKind.Smelter) {
    const slot = item === Item.IronOre ? SMELTER_ORE : item === Item.Coal ? SMELTER_COAL : -1;
    if (slot < 0) return false;
    if (c.counts[slot] === 0) {
      c.ids[slot] = item;
      c.counts[slot] = 1;
      return true;
    }
    if (c.ids[slot] !== item || c.counts[slot] >= STACK_MAX[item]) return false;
    c.counts[slot]++;
    return true;
  }
  if (kind === EntityKind.Crate) {
    const max = STACK_MAX[item];
    for (let i = 0; i < c.size; i++) {
      if (c.ids[i] === item && c.counts[i] > 0 && c.counts[i] < max) {
        c.counts[i]++;
        return true;
      }
    }
    for (let i = 0; i < c.size; i++) {
      if (c.counts[i] === 0) {
        c.ids[i] = item;
        c.counts[i] = 1;
        return true;
      }
    }
  }
  return false;
}

function tickSmelter(es: Entities, id: number): void {
  const c = es.containers[id]!;
  const hasOre = c.counts[SMELTER_ORE] > 0 && c.ids[SMELTER_ORE] === Item.IronOre;
  const outRoom =
    c.counts[SMELTER_OUT] === 0 ||
    (c.ids[SMELTER_OUT] === Item.IronIngot && c.counts[SMELTER_OUT] < STACK_MAX[Item.IronIngot]);
  let running = false;
  if (es.enabled[id] && hasOre && outRoom) {
    if (es.fuel[id] === 0 && c.counts[SMELTER_COAL] > 0 && c.ids[SMELTER_COAL] === Item.Coal) {
      removeFromSlot(c, SMELTER_COAL, 1);
      es.fuel[id] = FUEL_TICKS;
    }
    if (es.fuel[id] > 0) {
      running = true;
      es.fuel[id]--;
      if (++es.progress[id] >= SMELT_TICKS) {
        es.progress[id] = 0;
        removeFromSlot(c, SMELTER_ORE, 1);
        c.ids[SMELTER_OUT] = Item.IronIngot;
        c.counts[SMELTER_OUT]++;
      }
    }
  }
  if (!hasOre) es.progress[id] = 0;
  es.active[id] = running ? 1 : 0;
  if (c.counts[SMELTER_OUT] > 0 && pushForward(es, id, c.ids[SMELTER_OUT])) removeFromSlot(c, SMELTER_OUT, 1);
}

function tickDrill(sim: Sim, id: number): void {
  const es = sim.entities;
  const c = es.containers[id]!;
  const item = sim.deposits[es.cz[id] * WORLD_SIZE + es.cx[id]];
  const room = c.counts[0] === 0 || (c.ids[0] === item && c.counts[0] < STACK_MAX[item]);
  let running = false;
  if (es.enabled[id] && item !== 0 && room) {
    running = true;
    if (++es.progress[id] >= DRILL_TICKS) {
      es.progress[id] = 0;
      c.ids[0] = item;
      c.counts[0]++;
    }
  }
  es.active[id] = running ? 1 : 0;
  if (c.counts[0] > 0 && pushForward(es, id, c.ids[0])) removeFromSlot(c, 0, 1);
}

function stepBelt(es: Entities, id: number): void {
  const base = id * BELT_SLOTS;
  const last = base + BELT_SLOTS - 1;
  es.beltMoved.fill(0, base, base + BELT_SLOTS);
  if (!es.enabled[id]) {
    es.active[id] = 0;
    return;
  }
  let moved = false;
  if (es.belt[last] !== 0 && pushForward(es, id, es.belt[last])) {
    es.belt[last] = 0;
    moved = true;
  }
  for (let s = last - 1; s >= base; s--) {
    if (es.belt[s] !== 0 && es.belt[s + 1] === 0) {
      es.belt[s + 1] = es.belt[s];
      es.belt[s] = 0;
      es.beltMoved[s + 1] = 1;
      moved = true;
    }
  }
  es.active[id] = moved ? 1 : 0;
}

/** Runs every machine for one tick and, every BELT_PERIOD ticks, advances the belts. */
export function tickMachines(sim: Sim): void {
  const es = sim.entities;
  for (let id = 1; id < es.high; id++) {
    const kind = es.kind[id];
    if (kind === EntityKind.Smelter) tickSmelter(es, id);
    else if (kind === EntityKind.Drill) tickDrill(sim, id);
    else if (kind === EntityKind.Lamp) es.active[id] = es.enabled[id];
  }
  if (sim.tick % BELT_PERIOD === 0) {
    const order = sim.beltOrder;
    if (order.version !== es.version) rebuildOrder(es, order);
    for (let i = 0; i < order.count; i++) stepBelt(es, order.ids[i]);
  }
}

/** Places an item on a belt's entry slot by hand. Returns true if there was room. */
export function loadBelt(es: Entities, id: number, item: number): boolean {
  if (es.kind[id] !== EntityKind.Belt || es.belt[id * BELT_SLOTS] !== 0) return false;
  es.belt[id * BELT_SLOTS] = item;
  return true;
}

/** Short status line for the machine panel. */
export function machineStatus(sim: Sim, id: number): string {
  const es = sim.entities;
  const kind = es.kind[id];
  const c = es.containers[id];
  if (!es.enabled[id] && ENTITY_DEFS[kind].ins.length > 0) return 'Switched off by its circuit';
  if (kind === EntityKind.Smelter && c) {
    if (es.active[id]) return `Smelting · ${Math.round((es.progress[id] / SMELT_TICKS) * 100)}%`;
    if (c.counts[SMELTER_ORE] === 0) return 'Needs iron ore';
    if (es.fuel[id] === 0 && c.counts[SMELTER_COAL] === 0) return 'Needs coal';
    return 'Output is full';
  }
  if (kind === EntityKind.Drill && c) {
    if (sim.deposits[es.cz[id] * WORLD_SIZE + es.cx[id]] === 0) return 'Not on a deposit';
    return es.active[id] ? `Drilling · ${Math.round((es.progress[id] / DRILL_TICKS) * 100)}%` : 'Output is full';
  }
  return '';
}
