/** Item ids are u16; 0 always means "empty slot". */
export type ItemId = number;

export type ItemKind = 'resource' | 'part' | 'tool' | 'kit' | 'consumable' | 'linker';

export interface ItemDef {
  id: ItemId;
  key: string;
  name: string;
  /** Maximum count per slot; 1 for tools. */
  stack: number;
  kind: ItemKind;
  /** Tool properties: tier scales gather speed, gathers is a bitmask of node classes. */
  tool?: { tier: number; gathers: number };
  /** For kits: the entity kind this item becomes when placed (see core/entities). */
  places?: number;
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
  KitWorkbench: 12,
  KitCrate: 13,
  KitWall: 14,
  KitCampfire: 15,
  KitSmelter: 16,
  KitDrill: 17,
  KitBelt: 18,
  KitSensor: 19,
  KitTurret: 20,
  KitLamp: 21,
  KitSwitch: 22,
  Linker: 23,
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
  // `places` values are EntityKind ids; kept as literals to avoid a circular import.
  { id: Item.KitWorkbench, key: 'kit_workbench', name: 'Workbench Kit', stack: 5, kind: 'kit', places: 1 },
  { id: Item.KitCrate, key: 'kit_crate', name: 'Crate Kit', stack: 10, kind: 'kit', places: 2 },
  { id: Item.KitWall, key: 'kit_wall', name: 'Wall Kit', stack: 30, kind: 'kit', places: 3 },
  { id: Item.KitCampfire, key: 'kit_campfire', name: 'Campfire Kit', stack: 5, kind: 'kit', places: 4 },
  { id: Item.KitSmelter, key: 'kit_smelter', name: 'Smelter Kit', stack: 5, kind: 'kit', places: 5 },
  { id: Item.KitDrill, key: 'kit_drill', name: 'Drill Kit', stack: 5, kind: 'kit', places: 6 },
  { id: Item.KitBelt, key: 'kit_belt', name: 'Belt Kit', stack: 50, kind: 'kit', places: 7 },
  { id: Item.KitSensor, key: 'kit_sensor', name: 'Sensor Kit', stack: 10, kind: 'kit', places: 8 },
  { id: Item.KitTurret, key: 'kit_turret', name: 'Turret Kit', stack: 5, kind: 'kit', places: 9 },
  { id: Item.KitLamp, key: 'kit_lamp', name: 'Lamp Kit', stack: 10, kind: 'kit', places: 10 },
  { id: Item.KitSwitch, key: 'kit_switch', name: 'Switch Kit', stack: 10, kind: 'kit', places: 11 },
  { id: Item.Linker, key: 'linker', name: 'Linker', stack: 1, kind: 'linker' },
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
