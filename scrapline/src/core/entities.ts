import { Container, createContainer } from './inventory';
import { Item, ItemId } from './items';
import { WORLD_SIZE } from './world';

/** Placeable things. Ids match `ItemDef.places`. 0 means "no entity". */
export const EntityKind = {
  None: 0,
  Workbench: 1,
  Crate: 2,
  Wall: 3,
  Campfire: 4,
  Smelter: 5,
  Drill: 6,
  Belt: 7,
  Sensor: 8,
  Turret: 9,
  Lamp: 10,
  Switch: 11,
} as const;

export type EntityUse = '' | 'craft' | 'open' | 'toggle';

export interface EntityDef {
  name: string;
  /** The kit item that places this entity and that dismantling returns. */
  kit: ItemId;
  /** Height of the targetable volume in metres. */
  height: number;
  /** Whether the player collides with it. */
  solid: boolean;
  /** Slots of storage, 0 for none. */
  storage: number;
  /** Labels for the storage slots, shown in the machine panel. */
  slotNames?: readonly string[];
  /** What the context button does when aiming at it. */
  use: EntityUse;
  /** Radius in metres within which it restores Warmth, 0 for none. */
  heat: number;
  /** True if it is warm whenever it exists; otherwise only while running. */
  alwaysHot?: boolean;
  /** Logic input and output port names. Entities with any ports can be wired. */
  ins: readonly string[];
  outs: readonly string[];
  /** True if it gets a logic node as soon as it is placed (so it shows on the circuit board). */
  autoNode?: boolean;
}

const NONE: readonly string[] = [];

export const ENTITY_DEFS: readonly EntityDef[] = [
  { name: '', kit: 0, height: 0, solid: false, storage: 0, use: '', heat: 0, ins: NONE, outs: NONE },
  { name: 'Workbench', kit: Item.KitWorkbench, height: 1.0, solid: true, storage: 0, use: 'craft', heat: 0, ins: NONE, outs: NONE },
  { name: 'Crate', kit: Item.KitCrate, height: 0.8, solid: true, storage: 16, use: 'open', heat: 0, ins: NONE, outs: NONE },
  { name: 'Wall', kit: Item.KitWall, height: 2.4, solid: true, storage: 0, use: '', heat: 0, ins: NONE, outs: NONE },
  { name: 'Campfire', kit: Item.KitCampfire, height: 0.5, solid: false, storage: 0, use: '', heat: 6, alwaysHot: true, ins: NONE, outs: NONE },
  {
    name: 'Smelter', kit: Item.KitSmelter, height: 1.5, solid: true, storage: 3, slotNames: ['Ore', 'Coal', 'Out'],
    use: 'open', heat: 5, ins: ['run'], outs: ['busy'],
  },
  {
    name: 'Drill', kit: Item.KitDrill, height: 1.3, solid: true, storage: 1, slotNames: ['Out'],
    use: 'open', heat: 0, ins: ['run'], outs: ['busy'],
  },
  { name: 'Belt', kit: Item.KitBelt, height: 0.45, solid: false, storage: 0, use: '', heat: 0, ins: ['run'], outs: ['moving'] },
  {
    name: 'Sensor', kit: Item.KitSensor, height: 1.5, solid: false, storage: 0, use: '', heat: 0,
    ins: NONE, outs: ['seen', 'range'], autoNode: true,
  },
  { name: 'Turret', kit: Item.KitTurret, height: 1.4, solid: true, storage: 0, use: '', heat: 0, ins: ['fire'], outs: ['firing'], autoNode: true },
  { name: 'Lamp', kit: Item.KitLamp, height: 2.3, solid: false, storage: 0, use: '', heat: 0, ins: ['on'], outs: NONE, autoNode: true },
  { name: 'Switch', kit: Item.KitSwitch, height: 1.1, solid: false, storage: 0, use: 'toggle', heat: 0, ins: NONE, outs: ['on'], autoNode: true },
];

export const MAX_ENTITIES = 2048;
/** Item slots along one belt tile. Slot 0 is the entry end, the last slot the exit end. */
export const BELT_SLOTS = 4;

/** Unit step on the build grid for each quarter turn: 0 = -Z, 1 = -X, 2 = +Z, 3 = +X. */
export const DIR_X: readonly number[] = [0, -1, 0, 1];
export const DIR_Z: readonly number[] = [-1, 0, 1, 0];

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
  /** Quarter turns, 0..3. The entity faces DIR_X/DIR_Z[rot]. */
  rot: Uint8Array;
  /** Ground height under the entity. */
  y: Float32Array;
  /** Storage for entities that have it, indexed by entity id. */
  containers: (Container | null)[];
  /** Entity id occupying each build cell (0 = empty), index = cz * WORLD_SIZE + cx. */
  grid: Uint16Array;

  // --- machine state
  /** Work done on the current item, ticks. */
  progress: Uint16Array;
  /** Burn time left, ticks. */
  fuel: Uint16Array;
  /** Driven by the logic graph: 1 = allowed to run / fire / light. */
  enabled: Uint8Array;
  /** 1 while the entity did work this tick (running, moving, firing, lit, switched on). */
  active: Uint8Array;
  /** Logic node id for this entity, 0 if it has none. */
  node: Uint16Array;
  /** Item id in each belt slot, index = id * BELT_SLOTS + slot. */
  belt: Uint16Array;
  /** 1 for belt slots whose item arrived in the last belt step (for smooth drawing). */
  beltMoved: Uint8Array;
  /** Bumped whenever an entity is placed or removed; lets caches know to rebuild. */
  version: number;
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
    progress: new Uint16Array(MAX_ENTITIES),
    fuel: new Uint16Array(MAX_ENTITIES),
    enabled: new Uint8Array(MAX_ENTITIES),
    active: new Uint8Array(MAX_ENTITIES),
    node: new Uint16Array(MAX_ENTITIES),
    belt: new Uint16Array(MAX_ENTITIES * BELT_SLOTS),
    beltMoved: new Uint8Array(MAX_ENTITIES * BELT_SLOTS),
    version: 0,
  };
}

export function inBounds(cx: number, cz: number): boolean {
  return cx >= 0 && cz >= 0 && cx < WORLD_SIZE && cz < WORLD_SIZE;
}

export function entityAt(es: Entities, cx: number, cz: number): number {
  return inBounds(cx, cz) ? es.grid[cz * WORLD_SIZE + cx] : 0;
}

/** The entity in the cell an entity faces, or 0. */
export function entityInFront(es: Entities, id: number): number {
  return entityAt(es, es.cx[id] + DIR_X[es.rot[id]], es.cz[id] + DIR_Z[es.rot[id]]);
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
  es.progress[id] = 0;
  es.fuel[id] = 0;
  es.enabled[id] = 1;
  es.active[id] = 0;
  es.node[id] = 0;
  es.belt.fill(0, id * BELT_SLOTS, (id + 1) * BELT_SLOTS);
  es.beltMoved.fill(0, id * BELT_SLOTS, (id + 1) * BELT_SLOTS);
  const storage = ENTITY_DEFS[kind].storage;
  es.containers[id] = storage > 0 ? createContainer(storage) : null;
  es.grid[cz * WORLD_SIZE + cx] = id;
  es.version++;
  return id;
}

export function removeEntity(es: Entities, id: number): void {
  if (id <= 0 || es.kind[id] === 0) return;
  es.grid[es.cz[id] * WORLD_SIZE + es.cx[id]] = 0;
  es.kind[id] = 0;
  es.containers[id] = null;
  es.node[id] = 0;
  es.active[id] = 0;
  es.version++;
}
