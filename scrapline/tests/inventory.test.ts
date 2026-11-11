import { describe, expect, it } from 'vitest';
import { add, count, createContainer, findSlot, moveSlot, remove, removeFromSlot, space } from '../src/core/inventory';
import { Item } from '../src/core/items';

describe('add', () => {
  it('fills empty slots in order', () => {
    const c = createContainer(4);
    expect(add(c, Item.Wood, 10)).toBe(0);
    expect(c.ids[0]).toBe(Item.Wood);
    expect(c.counts[0]).toBe(10);
    expect(c.counts[1]).toBe(0);
  });

  it('tops up an existing stack before using a new slot', () => {
    const c = createContainer(4);
    add(c, Item.Stone, 5);
    add(c, Item.Wood, 45);
    add(c, Item.Wood, 10);
    expect(c.counts[1]).toBe(50);
    expect(c.ids[2]).toBe(Item.Wood);
    expect(c.counts[2]).toBe(5);
  });

  it('splits across stacks and reports what did not fit', () => {
    const c = createContainer(2);
    expect(add(c, Item.Wood, 130)).toBe(30);
    expect(c.counts[0]).toBe(50);
    expect(c.counts[1]).toBe(50);
    expect(count(c, Item.Wood)).toBe(100);
  });

  it('keeps tools one per slot', () => {
    const c = createContainer(3);
    expect(add(c, Item.Axe, 2)).toBe(0);
    expect(c.counts[0]).toBe(1);
    expect(c.counts[1]).toBe(1);
  });

  it('ignores the empty item and non-positive amounts', () => {
    const c = createContainer(2);
    expect(add(c, Item.None, 5)).toBe(0);
    expect(add(c, Item.Wood, 0)).toBe(0);
    expect(c.counts[0]).toBe(0);
  });
});

describe('remove', () => {
  it('removes across stacks and clears emptied slots', () => {
    const c = createContainer(3);
    add(c, Item.Wood, 70);
    expect(remove(c, Item.Wood, 30)).toBe(30);
    expect(c.counts[0]).toBe(40);
    expect(c.counts[1]).toBe(0);
    expect(c.ids[1]).toBe(Item.None);
  });

  it('removes only what exists', () => {
    const c = createContainer(2);
    add(c, Item.Coal, 3);
    expect(remove(c, Item.Coal, 10)).toBe(3);
    expect(count(c, Item.Coal)).toBe(0);
    expect(findSlot(c, Item.Coal)).toBe(-1);
  });

  it('removes from a single slot', () => {
    const c = createContainer(2);
    add(c, Item.Ration, 2);
    expect(removeFromSlot(c, 0, 1)).toBe(1);
    expect(c.counts[0]).toBe(1);
    expect(removeFromSlot(c, 0, 5)).toBe(1);
    expect(c.ids[0]).toBe(Item.None);
  });
});

describe('space', () => {
  it('counts room in matching stacks and empty slots', () => {
    const c = createContainer(3);
    add(c, Item.Wood, 20);
    add(c, Item.Stone, 1);
    expect(space(c, Item.Wood)).toBe(30 + 50);
    expect(space(c, Item.Axe)).toBe(1);
  });
});

describe('moveSlot', () => {
  it('moves a whole stack into an empty slot', () => {
    const c = createContainer(4);
    add(c, Item.Wood, 12);
    expect(moveSlot(c, 0, c, 3, 12)).toBe(12);
    expect(c.counts[0]).toBe(0);
    expect(c.ids[0]).toBe(Item.None);
    expect(c.ids[3]).toBe(Item.Wood);
    expect(c.counts[3]).toBe(12);
  });

  it('moves part of a stack', () => {
    const c = createContainer(4);
    add(c, Item.Wood, 12);
    expect(moveSlot(c, 0, c, 2, 6)).toBe(6);
    expect(c.counts[0]).toBe(6);
    expect(c.counts[2]).toBe(6);
  });

  it('merges up to the stack limit and leaves the rest', () => {
    const c = createContainer(4);
    c.ids[0] = Item.Wood;
    c.counts[0] = 40;
    c.ids[1] = Item.Wood;
    c.counts[1] = 30;
    expect(moveSlot(c, 0, c, 1, 40)).toBe(20);
    expect(c.counts[0]).toBe(20);
    expect(c.counts[1]).toBe(50);
  });

  it('swaps different items when the whole stack is moved', () => {
    const c = createContainer(4);
    add(c, Item.Wood, 5);
    add(c, Item.Stone, 7);
    expect(moveSlot(c, 0, c, 1, 5)).toBe(5);
    expect(c.ids[0]).toBe(Item.Stone);
    expect(c.counts[0]).toBe(7);
    expect(c.ids[1]).toBe(Item.Wood);
    expect(c.counts[1]).toBe(5);
  });

  it('refuses a partial move onto a different item', () => {
    const c = createContainer(4);
    add(c, Item.Wood, 5);
    add(c, Item.Stone, 7);
    expect(moveSlot(c, 0, c, 1, 2)).toBe(0);
    expect(c.counts[0]).toBe(5);
    expect(c.counts[1]).toBe(7);
  });

  it('does nothing for an empty source or the same slot', () => {
    const c = createContainer(4);
    add(c, Item.Wood, 5);
    expect(moveSlot(c, 2, c, 3, 1)).toBe(0);
    expect(moveSlot(c, 0, c, 0, 5)).toBe(0);
    expect(c.counts[0]).toBe(5);
  });

  it('moves between two containers and conserves the total', () => {
    const a = createContainer(2);
    const b = createContainer(2);
    add(a, Item.Coal, 30);
    add(b, Item.Coal, 45);
    expect(moveSlot(a, 0, b, 0, 30)).toBe(5);
    expect(count(a, Item.Coal) + count(b, Item.Coal)).toBe(75);
  });
});
