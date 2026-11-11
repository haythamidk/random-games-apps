import { ENTITY_DEFS, placeEntity } from './entities';
import { Sim, createSim } from './sim';
import { heightAt } from './world';

export const SAVE_VERSION = 1;

/** Plain-JSON snapshot of a game. Terrain and node positions are rebuilt from the seed. */
export interface SaveData {
  v: number;
  seed: number;
  tick: number;
  player: [x: number, y: number, z: number, yaw: number, pitch: number];
  vitals: [health: number, charge: number, warmth: number];
  selected: number;
  buildRot: number;
  inv: [ids: number[], counts: number[]];
  /** Remaining hp per resource node, in generation order. */
  nodeHp: number[];
  /** [kind, cx, cz, rot, storageIds?, storageCounts?] per placed entity. */
  entities: (number | number[])[][];
}

export function serialize(sim: Sim): SaveData {
  const es = sim.entities;
  const entities: (number | number[])[][] = [];
  for (let id = 1; id < es.high; id++) {
    if (es.kind[id] === 0) continue;
    const row: (number | number[])[] = [es.kind[id], es.cx[id], es.cz[id], es.rot[id]];
    const store = es.containers[id];
    if (store) row.push(Array.from(store.ids), Array.from(store.counts));
    entities.push(row);
  }
  const p = sim.player;
  const r = (n: number) => Math.round(n * 1000) / 1000;
  return {
    v: SAVE_VERSION,
    seed: sim.seed,
    tick: sim.tick,
    player: [r(p.x), r(p.y), r(p.z), r(p.yaw), r(p.pitch)],
    vitals: [sim.vitals.health, sim.vitals.charge, sim.vitals.warmth],
    selected: sim.selected,
    buildRot: sim.buildRot,
    inv: [Array.from(sim.inventory.ids), Array.from(sim.inventory.counts)],
    nodeHp: Array.from(sim.nodes.hp),
    entities,
  };
}

/** Rebuilds a game from a snapshot. Returns null if the data is missing, foreign or from another version. */
export function deserialize(data: unknown): Sim | null {
  if (!data || typeof data !== 'object') return null;
  const d = data as Partial<SaveData>;
  if (d.v !== SAVE_VERSION || typeof d.seed !== 'number') return null;
  if (!d.player || !d.vitals || !d.inv || !d.nodeHp || !d.entities) return null;

  const sim = createSim(d.seed);
  if (d.nodeHp.length !== sim.nodes.count) return null;
  if (d.inv[0].length !== sim.inventory.size || d.inv[1].length !== sim.inventory.size) return null;

  sim.tick = d.tick ?? 0;
  const p = sim.player;
  [p.x, p.y, p.z, p.yaw, p.pitch] = d.player;
  [sim.vitals.health, sim.vitals.charge, sim.vitals.warmth] = d.vitals;
  sim.selected = d.selected ?? 0;
  sim.buildRot = d.buildRot ?? 0;
  sim.inventory.ids.set(d.inv[0]);
  sim.inventory.counts.set(d.inv[1]);
  sim.nodes.hp.set(d.nodeHp);

  for (const row of d.entities) {
    const [kind, cx, cz, rot] = row as number[];
    if (!ENTITY_DEFS[kind] || kind === 0) continue;
    const id = placeEntity(sim.entities, kind, cx, cz, rot, 0);
    if (id === 0) continue;
    sim.entities.y[id] = heightOf(sim, cx, cz);
    const store = sim.entities.containers[id];
    if (store && Array.isArray(row[4]) && Array.isArray(row[5]) && row[4].length === store.size) {
      store.ids.set(row[4]);
      store.counts.set(row[5]);
    }
  }
  return sim;
}

function heightOf(sim: Sim, cx: number, cz: number): number {
  return heightAt(sim.world, cx + 0.5, cz + 0.5);
}
