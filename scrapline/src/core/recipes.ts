import { Container, add, count, remove } from './inventory';
import { Item, ItemId } from './items';

export type Station = 'hand' | 'workbench';

export interface Recipe {
  id: number;
  station: Station;
  /** Up to three [item, amount] pairs. */
  inputs: readonly (readonly [ItemId, number])[];
  output: readonly [ItemId, number];
}

const list: Omit<Recipe, 'id'>[] = [
  { station: 'hand', inputs: [[Item.Wood, 3], [Item.Stone, 2]], output: [Item.Axe, 1] },
  { station: 'hand', inputs: [[Item.Wood, 2], [Item.Stone, 3]], output: [Item.Pick, 1] },
  { station: 'hand', inputs: [[Item.Wood, 4], [Item.Stone, 4]], output: [Item.KitCampfire, 1] },
  { station: 'hand', inputs: [[Item.Wood, 8], [Item.Stone, 4]], output: [Item.KitWorkbench, 1] },
  { station: 'workbench', inputs: [[Item.Wood, 6]], output: [Item.KitCrate, 1] },
  { station: 'workbench', inputs: [[Item.Stone, 4], [Item.Wood, 1]], output: [Item.KitWall, 2] },
];

export const RECIPES: readonly Recipe[] = list.map((r, id) => ({ ...r, id }));

/** Recipes available at a station. A workbench also offers everything craftable by hand. */
export function recipesFor(station: Station): readonly Recipe[] {
  return station === 'workbench' ? RECIPES : RECIPES.filter((r) => r.station === 'hand');
}

export function hasInputs(c: Container, r: Recipe): boolean {
  for (const [id, n] of r.inputs) if (count(c, id) < n) return false;
  return true;
}

/**
 * Crafts once: consumes the inputs and adds the output. If the output does not
 * fit even after the inputs are gone, everything is put back and it fails.
 */
export function craft(c: Container, r: Recipe): boolean {
  if (!hasInputs(c, r)) return false;
  for (const [id, n] of r.inputs) remove(c, id, n);
  const [outId, outN] = r.output;
  const left = add(c, outId, outN);
  if (left > 0) {
    remove(c, outId, outN - left);
    for (const [id, n] of r.inputs) add(c, id, n);
    return false;
  }
  return true;
}
