import { GATHER_ROCK, GATHER_WOOD, Item, ItemId } from './items';
import { Rng } from './rng';
import { WORLD_SIZE, World, heightAt, slopeAt } from './world';

/** Kinds of hand-gatherable resource node. 0 is unused so a zero means "none". */
export const NodeKind = { Deadwood: 1, Stone: 2, Ore: 3, Coal: 4 } as const;

export interface NodeDef {
  name: string;
  item: ItemId;
  /** Units the node yields before it is used up. */
  hp: number;
  /** Collision and targeting radius at scale 1, metres. */
  radius: number;
  /** Height of the targetable volume at scale 1, metres. */
  height: number;
  /** Tool class that works this node quickly. */
  gatherClass: number;
  /** True if bare hands cannot gather it at all. */
  needsTool: boolean;
}

export const NODE_DEFS: readonly NodeDef[] = [
  { name: '', item: 0, hp: 0, radius: 0, height: 0, gatherClass: 0, needsTool: false },
  { name: 'Deadwood', item: Item.Wood, hp: 8, radius: 0.38, height: 3.4, gatherClass: GATHER_WOOD, needsTool: false },
  { name: 'Stone Outcrop', item: Item.Stone, hp: 7, radius: 0.85, height: 1.1, gatherClass: GATHER_ROCK, needsTool: false },
  { name: 'Ore Vein', item: Item.IronOre, hp: 7, radius: 0.9, height: 1.2, gatherClass: GATHER_ROCK, needsTool: true },
  { name: 'Coal Seam', item: Item.Coal, hp: 7, radius: 0.9, height: 1.0, gatherClass: GATHER_ROCK, needsTool: true },
];

/** Side length of a spatial bucket, metres. */
export const BUCKET = 8;
const BUCKETS_PER_SIDE = WORLD_SIZE / BUCKET;

/** All resource nodes, struct-of-arrays. A node with hp 0 is depleted. */
export interface Nodes {
  count: number;
  kind: Uint8Array;
  x: Float32Array;
  y: Float32Array;
  z: Float32Array;
  scale: Float32Array;
  rot: Float32Array;
  hp: Uint8Array;
  /** Node ids per spatial bucket, row-major. Built once; depleted nodes stay listed. */
  buckets: Uint16Array[];
  /** Ground patches a drill can extract from without ever running dry. */
  deposits: Deposit[];
}

export interface Deposit {
  x: number;
  z: number;
  radius: number;
  item: ItemId;
}

export function bucketIndex(x: number, z: number): number {
  let bx = Math.floor(x / BUCKET);
  let bz = Math.floor(z / BUCKET);
  if (bx < 0) bx = 0;
  else if (bx >= BUCKETS_PER_SIDE) bx = BUCKETS_PER_SIDE - 1;
  if (bz < 0) bz = 0;
  else if (bz >= BUCKETS_PER_SIDE) bz = BUCKETS_PER_SIDE - 1;
  return bz * BUCKETS_PER_SIDE + bx;
}

interface Scatter {
  kind: number;
  /** Number of clusters and nodes per cluster. */
  clusters: number;
  perCluster: number;
  spread: number;
  /** Keep clusters at least this far from the spawn point. */
  minFromSpawn: number;
}

const SCATTER: Scatter[] = [
  { kind: NodeKind.Deadwood, clusters: 46, perCluster: 4, spread: 7, minFromSpawn: 9 },
  { kind: NodeKind.Stone, clusters: 30, perCluster: 3, spread: 5, minFromSpawn: 10 },
  { kind: NodeKind.Ore, clusters: 9, perCluster: 4, spread: 4, minFromSpawn: 30 },
  { kind: NodeKind.Coal, clusters: 8, perCluster: 4, spread: 4, minFromSpawn: 26 },
];

/** Scatters resource nodes in clusters over walkable ground. Deterministic per seed. */
export function generateNodes(world: World, seed: number): Nodes {
  const rng = new Rng(seed ^ 0x5eed);
  const xs: number[] = [];
  const zs: number[] = [];
  const kinds: number[] = [];
  const half = WORLD_SIZE / 2;

  const tooClose = (x: number, z: number, min: number) => {
    for (let i = 0; i < xs.length; i++) {
      const dx = xs[i] - x;
      const dz = zs[i] - z;
      if (dx * dx + dz * dz < min * min) return true;
    }
    return false;
  };

  // A few guaranteed nodes near spawn so the first minute never starts with a hike.
  const starters: [number, number, number][] = [
    [NodeKind.Deadwood, -10, -6],
    [NodeKind.Deadwood, -12, -3],
    [NodeKind.Deadwood, 9, -9],
    [NodeKind.Stone, 8, 7],
    [NodeKind.Stone, 11, 5],
    [NodeKind.Stone, -7, 10],
  ];
  for (const [kind, dx, dz] of starters) {
    xs.push(half + dx);
    zs.push(half + dz);
    kinds.push(kind);
  }

  // Two deposits within a short walk of spawn, each ringed by a few hand-minable
  // nodes of the same resource, so the first factory has somewhere to go.
  const deposits: Deposit[] = [
    { x: half + 24.5, z: half - 16.5, radius: 3.2, item: Item.IronOre },
    { x: half - 22.5, z: half + 20.5, radius: 3.2, item: Item.Coal },
  ];
  for (const d of deposits) {
    const kind = d.item === Item.IronOre ? NodeKind.Ore : NodeKind.Coal;
    for (let k = 0; k < 4; k++) {
      const a = (k / 4) * Math.PI * 2 + 0.4;
      xs.push(d.x + Math.cos(a) * (d.radius + 2.2));
      zs.push(d.z + Math.sin(a) * (d.radius + 2.2));
      kinds.push(kind);
    }
  }

  for (const s of SCATTER) {
    for (let c = 0; c < s.clusters; c++) {
      let cx = 0;
      let cz = 0;
      let ok = false;
      for (let tries = 0; tries < 30 && !ok; tries++) {
        cx = rng.range(24, WORLD_SIZE - 24);
        cz = rng.range(24, WORLD_SIZE - 24);
        ok = Math.hypot(cx - half, cz - half) >= s.minFromSpawn && slopeAt(world, cx, cz) < 0.5;
      }
      if (!ok) continue;
      // Every other ore and coal cluster sits beside a deposit of its own.
      if ((s.kind === NodeKind.Ore || s.kind === NodeKind.Coal) && c % 2 === 0) {
        deposits.push({ x: Math.floor(cx) + 0.5, z: Math.floor(cz) + 0.5, radius: 2.6, item: s.kind === NodeKind.Ore ? Item.IronOre : Item.Coal });
      }
      for (let n = 0; n < s.perCluster; n++) {
        for (let tries = 0; tries < 12; tries++) {
          const a = rng.range(0, Math.PI * 2);
          const r = rng.range(0.5, s.spread);
          const x = cx + Math.cos(a) * r;
          const z = cz + Math.sin(a) * r;
          if (slopeAt(world, x, z) > 0.55) continue;
          if (Math.hypot(x - half, z - half) < 6) continue;
          if (tooClose(x, z, 2.4)) continue;
          if (deposits.some((d) => Math.hypot(d.x - x, d.z - z) < d.radius + 0.4)) continue;
          xs.push(x);
          zs.push(z);
          kinds.push(s.kind);
          break;
        }
      }
    }
  }

  const count = xs.length;
  const nodes: Nodes = {
    count,
    kind: new Uint8Array(count),
    x: new Float32Array(count),
    y: new Float32Array(count),
    z: new Float32Array(count),
    scale: new Float32Array(count),
    rot: new Float32Array(count),
    hp: new Uint8Array(count),
    buckets: [],
    deposits,
  };
  const lists: number[][] = [];
  for (let i = 0; i < BUCKETS_PER_SIDE * BUCKETS_PER_SIDE; i++) lists.push([]);
  for (let i = 0; i < count; i++) {
    nodes.kind[i] = kinds[i];
    nodes.x[i] = xs[i];
    nodes.z[i] = zs[i];
    nodes.y[i] = heightAt(world, xs[i], zs[i]);
    nodes.scale[i] = rng.range(0.85, 1.25);
    nodes.rot[i] = rng.range(0, Math.PI * 2);
    nodes.hp[i] = NODE_DEFS[kinds[i]].hp;
    lists[bucketIndex(xs[i], zs[i])].push(i);
  }
  nodes.buckets = lists.map((l) => Uint16Array.from(l));
  return nodes;
}
