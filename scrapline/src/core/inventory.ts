import { ItemId, STACK_MAX } from './items';

/**
 * A fixed-size set of item slots. Player bag, crates and machine buffers all use
 * this one structure so every system moves items through the same functions.
 */
export interface Container {
  size: number;
  ids: Uint16Array;
  counts: Uint16Array;
}

export function createContainer(size: number): Container {
  return { size, ids: new Uint16Array(size), counts: new Uint16Array(size) };
}

/** Total number of `id` held across all slots. */
export function count(c: Container, id: ItemId): number {
  let n = 0;
  for (let i = 0; i < c.size; i++) if (c.ids[i] === id) n += c.counts[i];
  return n;
}

/** How many more of `id` would fit. */
export function space(c: Container, id: ItemId): number {
  const max = STACK_MAX[id];
  let n = 0;
  for (let i = 0; i < c.size; i++) {
    if (c.counts[i] === 0) n += max;
    else if (c.ids[i] === id) n += max - c.counts[i];
  }
  return n;
}

/**
 * Adds up to `n` of `id`: tops up existing stacks first, then fills empty slots
 * in order. Returns the amount that did not fit.
 */
export function add(c: Container, id: ItemId, n: number): number {
  if (id === 0 || n <= 0) return 0;
  const max = STACK_MAX[id];
  let left = n;
  for (let i = 0; i < c.size && left > 0; i++) {
    if (c.ids[i] === id && c.counts[i] > 0 && c.counts[i] < max) {
      const take = Math.min(left, max - c.counts[i]);
      c.counts[i] += take;
      left -= take;
    }
  }
  for (let i = 0; i < c.size && left > 0; i++) {
    if (c.counts[i] === 0) {
      const take = Math.min(left, max);
      c.ids[i] = id;
      c.counts[i] = take;
      left -= take;
    }
  }
  return left;
}

/** Removes up to `n` of `id`, last slots first. Returns the amount actually removed. */
export function remove(c: Container, id: ItemId, n: number): number {
  let left = n;
  for (let i = c.size - 1; i >= 0 && left > 0; i--) {
    if (c.ids[i] === id && c.counts[i] > 0) {
      const take = Math.min(left, c.counts[i]);
      c.counts[i] -= take;
      left -= take;
      if (c.counts[i] === 0) c.ids[i] = 0;
    }
  }
  return n - left;
}

/** Removes up to `n` from one slot. Returns the amount removed. */
export function removeFromSlot(c: Container, slot: number, n: number): number {
  const take = Math.min(n, c.counts[slot]);
  c.counts[slot] -= take;
  if (c.counts[slot] === 0) c.ids[slot] = 0;
  return take;
}

/**
 * Moves up to `n` items from slot `i` of `from` to slot `j` of `to`.
 * - Empty target: moves up to a full stack.
 * - Same item: merges up to the stack limit.
 * - Different item: swaps, but only when the whole source stack was requested.
 * Returns the number of items that left the source slot.
 */
export function moveSlot(from: Container, i: number, to: Container, j: number, n: number): number {
  if (from === to && i === j) return 0;
  const id = from.ids[i];
  const have = from.counts[i];
  if (have === 0 || n <= 0) return 0;
  const want = Math.min(n, have);
  const dstCount = to.counts[j];

  if (dstCount === 0 || to.ids[j] === id) {
    const room = STACK_MAX[id] - dstCount;
    const moved = Math.min(want, room);
    if (moved <= 0) return 0;
    to.ids[j] = id;
    to.counts[j] = dstCount + moved;
    from.counts[i] = have - moved;
    if (from.counts[i] === 0) from.ids[i] = 0;
    return moved;
  }

  if (want === have) {
    const dstId = to.ids[j];
    to.ids[j] = id;
    to.counts[j] = have;
    from.ids[i] = dstId;
    from.counts[i] = dstCount;
    return have;
  }
  return 0;
}

/** First slot index holding `id`, or -1. */
export function findSlot(c: Container, id: ItemId): number {
  for (let i = 0; i < c.size; i++) if (c.ids[i] === id && c.counts[i] > 0) return i;
  return -1;
}
