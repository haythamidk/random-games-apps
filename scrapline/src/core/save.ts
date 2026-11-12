import { BELT_SLOTS, ENTITY_DEFS, placeEntity } from './entities';
import { MAX_NODES, NodeType, PORTS } from './logic/graph';
import { Sim, createSim } from './sim';
import { heightAt } from './world';

export const SAVE_VERSION = 2;

/** Plain-JSON snapshot of a game. Terrain, nodes and deposits are rebuilt from the seed. */
export interface SaveData {
  v: number;
  seed: number;
  tick: number;
  nights: number;
  player: [x: number, y: number, z: number, yaw: number, pitch: number];
  vitals: [health: number, charge: number, warmth: number];
  selected: number;
  buildRot: number;
  inv: [ids: number[], counts: number[]];
  /** Remaining hp per resource node, in generation order. */
  nodeHp: number[];
  /**
   * Per placed entity: [kind, cx, cz, rot, machine, storageIds?, storageCounts?] where
   * machine = [progress, fuel, enabled, active, ...beltSlots].
   */
  entities: (number | number[])[][];
  /** Logic nodes: [id, type, entityRow (-1 for pure logic), param0, param1, state0, state1, bx, by, ...outputs]. */
  nodes: number[][];
  /** Packed wires, see core/logic/graph. */
  wires: number[];
}

export function serialize(sim: Sim): SaveData {
  const es = sim.entities;
  const entities: (number | number[])[][] = [];
  const rowOf = new Map<number, number>();
  for (let id = 1; id < es.high; id++) {
    if (es.kind[id] === 0) continue;
    rowOf.set(id, entities.length);
    const machine = [es.progress[id], es.fuel[id], es.enabled[id], es.active[id]];
    for (let s = 0; s < BELT_SLOTS; s++) machine.push(es.belt[id * BELT_SLOTS + s]);
    const row: (number | number[])[] = [es.kind[id], es.cx[id], es.cz[id], es.rot[id], machine];
    const store = es.containers[id];
    if (store) row.push(Array.from(store.ids), Array.from(store.counts));
    entities.push(row);
  }

  const g = sim.graph;
  const nodes: number[][] = [];
  for (let n = 1; n < g.high; n++) {
    if (g.type[n] === NodeType.Free) continue;
    const row = [
      n, g.type[n], g.type[n] === NodeType.Device ? rowOf.get(g.entity[n]) ?? -1 : -1,
      g.param[n * 2], g.param[n * 2 + 1], g.state[n * 2], g.state[n * 2 + 1], g.bx[n], g.by[n],
    ];
    for (let p = 0; p < PORTS; p++) row.push(g.outCur[n * PORTS + p]);
    nodes.push(row);
  }

  const p = sim.player;
  const r = (n: number) => Math.round(n * 1000) / 1000;
  return {
    v: SAVE_VERSION,
    seed: sim.seed,
    tick: sim.tick,
    nights: sim.nights,
    player: [r(p.x), r(p.y), r(p.z), r(p.yaw), r(p.pitch)],
    vitals: [sim.vitals.health, sim.vitals.charge, sim.vitals.warmth],
    selected: sim.selected,
    buildRot: sim.buildRot,
    inv: [Array.from(sim.inventory.ids), Array.from(sim.inventory.counts)],
    nodeHp: Array.from(sim.nodes.hp),
    entities,
    nodes,
    wires: Array.from(g.wires.subarray(0, g.wireCount)),
  };
}

/** Rebuilds a game from a snapshot. Returns null if the data is missing, foreign or from another version. */
export function deserialize(data: unknown): Sim | null {
  if (!data || typeof data !== 'object') return null;
  const d = data as Partial<SaveData>;
  if (d.v !== SAVE_VERSION || typeof d.seed !== 'number') return null;
  if (!d.player || !d.vitals || !d.inv || !d.nodeHp || !d.entities || !d.nodes || !d.wires) return null;

  const sim = createSim(d.seed);
  if (d.nodeHp.length !== sim.nodes.count) return null;
  if (d.inv[0].length !== sim.inventory.size || d.inv[1].length !== sim.inventory.size) return null;

  sim.tick = d.tick ?? 0;
  sim.nights = d.nights ?? 0;
  const p = sim.player;
  [p.x, p.y, p.z, p.yaw, p.pitch] = d.player;
  [sim.vitals.health, sim.vitals.charge, sim.vitals.warmth] = d.vitals;
  sim.selected = d.selected ?? 0;
  sim.buildRot = d.buildRot ?? 0;
  sim.inventory.ids.set(d.inv[0]);
  sim.inventory.counts.set(d.inv[1]);
  sim.nodes.hp.set(d.nodeHp);

  const es = sim.entities;
  const idOfRow: number[] = [];
  for (const row of d.entities) {
    const [kind, cx, cz, rot] = row as number[];
    if (!ENTITY_DEFS[kind] || kind === 0) {
      idOfRow.push(0);
      continue;
    }
    const id = placeEntity(es, kind, cx, cz, rot, heightAt(sim.world, cx + 0.5, cz + 0.5));
    idOfRow.push(id);
    if (id === 0) continue;
    const machine = row[4];
    if (Array.isArray(machine) && machine.length >= 4 + BELT_SLOTS) {
      es.progress[id] = machine[0];
      es.fuel[id] = machine[1];
      es.enabled[id] = machine[2];
      es.active[id] = machine[3];
      for (let s = 0; s < BELT_SLOTS; s++) es.belt[id * BELT_SLOTS + s] = machine[4 + s];
    }
    const store = es.containers[id];
    const ids = row[5];
    const counts = row[6];
    if (store && Array.isArray(ids) && Array.isArray(counts) && ids.length === store.size) {
      store.ids.set(ids);
      store.counts.set(counts);
    }
  }

  // Logic nodes go back at their original ids so the packed wires stay valid.
  const g = sim.graph;
  for (const row of d.nodes) {
    const [n, type, entityRow, p0, p1, s0, s1, bx, by] = row;
    if (!(n > 0 && n < MAX_NODES) || type <= NodeType.Free) continue;
    const entity = type === NodeType.Device ? idOfRow[entityRow] ?? 0 : 0;
    if (type === NodeType.Device && entity === 0) continue;
    g.type[n] = type;
    g.entity[n] = entity;
    g.param[n * 2] = p0;
    g.param[n * 2 + 1] = p1;
    g.state[n * 2] = s0;
    g.state[n * 2 + 1] = s1;
    g.bx[n] = bx;
    g.by[n] = by;
    for (let q = 0; q < PORTS; q++) {
      g.outCur[n * PORTS + q] = row[9 + q] ?? 0;
      g.outNext[n * PORTS + q] = row[9 + q] ?? 0;
    }
    if (entity) es.node[entity] = n;
    if (n >= g.high) g.high = n + 1;
    // Re-evaluate everything once after loading; time-driven nodes re-arm themselves.
    if (type > NodeType.Device) {
      g.queued[n] = 1;
      g.nextQueue[g.nextLen++] = n;
    }
  }
  for (const w of d.wires) {
    const sn = w & 0x3ff;
    const dn = (w >>> 12) & 0x3ff;
    if (g.type[sn] !== NodeType.Free && g.type[dn] !== NodeType.Free) g.wires[g.wireCount++] = w >>> 0;
  }
  g.csrStale = true;
  g.version++;
  return sim;
}
