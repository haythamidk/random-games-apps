import { Container, add, createContainer } from './inventory';
import { Item } from './items';
import { Player, createPlayer } from './player';
import { World, createWorld } from './world';

export const TICK_HZ = 20;
export const TICK_DT = 1 / TICK_HZ;

export const HOTBAR_SIZE = 6;
export const INVENTORY_SIZE = 24;
export const VITAL_MAX = 1000;

/** Survival gauges, each an integer 0..VITAL_MAX. */
export interface Vitals {
  health: number;
  charge: number;
  warmth: number;
}

/** Whole game state. Everything the view and UI show is read from here. */
export interface Sim {
  seed: number;
  /** Fixed ticks elapsed since the world was created. */
  tick: number;
  world: World;
  player: Player;
  /** Slots 0..HOTBAR_SIZE-1 are the hotbar, the rest is the bag. */
  inventory: Container;
  /** Selected hotbar slot. */
  selected: number;
  vitals: Vitals;
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
    player: createPlayer(world),
    inventory,
    selected: 0,
    vitals: { health: VITAL_MAX, charge: VITAL_MAX, warmth: VITAL_MAX },
  };
}

/** Advances the fixed-rate simulation by one tick. Must not allocate. */
export function tickSim(sim: Sim): void {
  sim.tick++;
}
