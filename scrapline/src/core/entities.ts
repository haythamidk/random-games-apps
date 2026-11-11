import { Container, createContainer } from './inventory';
import { Item, ItemId } from './items';
import { WORLD_SIZE } from './world';

/** Placeable things. Ids match `ItemDef.places`. 0 means "no entity". */
export const EntityKind = { None: 0, Workbench: 1, Crate: 2, Wall: 3, Campfire: 4 } as const;

export interface EntityDef {
  name: string;
  /** The kit item that places this entity and that dismantling returns. */
  kit: ItemId;
  /** Height of the solid volume in metres, for targeting. */
  height: number;
  /** Slots of storage, 0 for none. */
  storage: number;
  /** What the context button does when aiming at it. */
  use: '' | 'craft' | 'open';
  /** Radius in metres within which it restores Warmth, 0 for none. */
  heat: number;
}

export const ENTITY_DEFS: readonly EntityDef[] = [
  { name: '', kit: 0, height: 0, storage: 0, use: '', heat: 0 },
  { name: 'Workbench', kit: Item.KitWorkbench, height: 1.0, storage: 0, use: 'craft', heat: 0 },
  { name: 'Crate', kit: Item.KitCrate, height: 0.8, storage: 16, use: 'open', heat: 0 },
  { name: 'Wall', kit: Item.KitWall, height: 2.4, storage: 0, use: '', heat: 0 },
  { name: 'Campfire', kit: Item.KitCampfire, height: 0.5, storage: 0, use: '', heat: 6 },
];

export const MAX_ENTITIES = 2048;

/**
 * Placed entities, struct-of-arrays indexed by entity id (1..MAX_ENTITIES-1).
 * Every entity occupies exactly one 1 m build cell.
 */
export interface Entities {
  /** One past the highest id ever used. */
  high: number;
  kind: Uint8Array;
  cx: Uint16Array;
  cz: Uint16Array;
  /** Quarter turns, 0..3. */
  rot: Uint8Array;
  /** Ground height under the entity. */
  y: Float32Array;
  /** Storage for entities that have it, indexed by entity id. */
  containers: (Container | null)[];
  /** Entity id occupying each build cell (0 = empty), index = cz * WORLD_SIZE + cx. */
  grid: Uint16Array;
}

export function createEntities(): Entities {
  return {
    high: 1,
    kind: new Uint8Array(MAX_ENTITIES),
    cx: new Uint16Array(MAX_ENTITIES),
    cz: new Uint16Array(MAX_ENTITIES),
    rot: new Uint8Array(MAX_ENTITIES),
    y: new Float32Array(MAX_ENTITIES),
    containers: new Array<Container | null>(MAX_ENTITIES).fill(null),
    grid: new Uint16Array(WORLD_SIZE * WORLD_SIZE),
  };
}

export function inBounds(cx: number, cz: number): boolean {
  return cx >= 0 && cz >= 0 && cx < WORLD_SIZE && cz < WORLD_SIZE;
}

export function entityAt(es: Entities, cx: number, cz: number): number {
  return inBounds(cx, cz) ? es.grid[cz * WORLD_SIZE + cx] : 0;
}

/** Places an entity on an empty cell. Returns its id, or 0 if the cell is taken or the table is full. */
export function placeEntity(es: Entities, kind: number, cx: number, cz: number, rot: number, y: number): number {
  if (!inBounds(cx, cz) || es.grid[cz * WORLD_SIZE + cx] !== 0) return 0;
  let id = 0;
  for (let i = 1; i < es.high; i++) {
    if (es.kind[i] === 0) {
      id = i;
      break;
    }
  }
  if (id === 0) {
    if (es.high >= MAX_ENTITIES) return 0;
    id = es.high++;
  }
  es.kind[id] = kind;
  es.cx[id] = cx;
  es.cz[id] = cz;
  es.rot[id] = rot & 3;
  es.y[id] = y;
  const storage = ENTITY_DEFS[kind].storage;
  es.containers[id] = storage > 0 ? createContainer(storage) : null;
  es.grid[cz * WORLD_SIZE + cx] = id;
  return id;
}

export function removeEntity(es: Entities, id: number): void {
  if (id <= 0 || es.kind[id] === 0) return;
  es.grid[es.cz[id] * WORLD_SIZE + es.cx[id]] = 0;
  es.kind[id] = 0;
  es.containers[id] = null;
}
