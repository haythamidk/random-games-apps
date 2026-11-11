/** Item ids are u16; 0 always means "empty slot". */
export type ItemId = number;

export type ItemKind = 'resource' | 'part' | 'tool' | 'kit' | 'consumable';

export interface ItemDef {
  id: ItemId;
  key: string;
  name: string;
  /** Maximum count per slot; 1 for tools. */
  stack: number;
  kind: ItemKind;
  /** Tool properties: tier scales gather speed, gathers is a bitmask of node classes. */
  tool?: { tier: number; gathers: number };
  /** Consumable effect in vitals units (0..1000 scale). */
  use?: { health?: number; charge?: number; warmth?: number };
}

/** Resource node classes a tool can work on (bitmask). */
export const GATHER_WOOD = 1;
export const GATHER_ROCK = 2;

export const Item = {
  None: 0,
  Wood: 1,
  Stone: 2,
  IronOre: 3,
  Coal: 4,
  IronIngot: 5,
  Gear: 6,
  Circuit: 7,
  Axe: 8,
  Pick: 9,
  Ration: 10,
  Cell: 11,
} as const;

const defs: ItemDef[] = [
  { id: Item.None, key: 'none', name: '', stack: 0, kind: 'resource' },
  { id: Item.Wood, key: 'wood', name: 'Deadwood', stack: 50, kind: 'resource' },
  { id: Item.Stone, key: 'stone', name: 'Stone', stack: 50, kind: 'resource' },
  { id: Item.IronOre, key: 'ore_iron', name: 'Iron Ore', stack: 50, kind: 'resource' },
  { id: Item.Coal, key: 'coal', name: 'Coal', stack: 50, kind: 'resource' },
  { id: Item.IronIngot, key: 'ingot_iron', name: 'Iron Ingot', stack: 50, kind: 'part' },
  { id: Item.Gear, key: 'gear', name: 'Gear', stack: 30, kind: 'part' },
  { id: Item.Circuit, key: 'circuit', name: 'Circuit', stack: 30, kind: 'part' },
  { id: Item.Axe, key: 'axe', name: 'Axe', stack: 1, kind: 'tool', tool: { tier: 1, gathers: GATHER_WOOD } },
  { id: Item.Pick, key: 'pick', name: 'Pick', stack: 1, kind: 'tool', tool: { tier: 1, gathers: GATHER_ROCK } },
  { id: Item.Ration, key: 'ration', name: 'Ration Pack', stack: 10, kind: 'consumable', use: { health: 350 } },
  { id: Item.Cell, key: 'cell', name: 'Charge Cell', stack: 10, kind: 'consumable', use: { charge: 500 } },
];

/** Item definitions indexed by id. */
export const ITEMS: readonly ItemDef[] = defs;

/** Max stack per item id, as a flat array for the allocation-free container functions. */
export const STACK_MAX: Uint16Array = Uint16Array.from(defs.map((d) => d.stack));

export function itemDef(id: ItemId): ItemDef {
  const d = defs[id];
  if (!d) throw new Error(`Unknown item id ${id}`);
  return d;
}
