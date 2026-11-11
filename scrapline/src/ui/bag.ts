import { moveSlot } from '../core/inventory';
import { itemDef } from '../core/items';
import { HOTBAR_SIZE, INVENTORY_SIZE, Sim } from '../core/sim';
import { SlotView } from './slots';

const LONG_PRESS_MS = 380;

/**
 * Bag sheet. Items move by tapping, never by dragging:
 * tap a stack to pick it up, tap another slot to drop it there.
 * Holding a stack picks up half of it instead.
 */
export class Bag {
  private backdrop: HTMLElement;
  private status: HTMLElement;
  private slots: SlotView[] = [];
  /** Slot currently picked up, or -1. */
  private heldSlot = -1;
  private heldCount = 0;
  private pressTimer = 0;
  private longPressed = false;

  constructor(root: HTMLElement, private sim: Sim) {
    this.backdrop = document.createElement('div');
    this.backdrop.className = 'sheet-backdrop';
    this.backdrop.addEventListener('pointerdown', (e) => {
      if (e.target === this.backdrop) this.close();
    });

    const sheet = document.createElement('div');
    sheet.className = 'sheet';
    sheet.setAttribute('role', 'dialog');
    sheet.setAttribute('aria-label', 'Bag');

    const head = document.createElement('div');
    head.className = 'sheet-head';
    const title = document.createElement('span');
    title.className = 'sheet-title';
    title.textContent = 'Bag';
    this.status = document.createElement('span');
    this.status.className = 'sheet-status';
    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'sheet-close';
    close.setAttribute('aria-label', 'Close bag');
    close.innerHTML =
      '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/></svg>';
    close.addEventListener('click', () => this.close());
    head.append(title, this.status, close);

    const grid = document.createElement('div');
    grid.className = 'slot-grid';
    for (let i = 0; i < INVENTORY_SIZE; i++) {
      const slot = new SlotView(i, i < HOTBAR_SIZE ? 'bag-hot' : '');
      this.bindSlot(slot);
      grid.appendChild(slot.el);
      this.slots.push(slot);
    }

    sheet.append(head, grid);
    this.backdrop.appendChild(sheet);
    root.appendChild(this.backdrop);
  }

  get isOpen(): boolean {
    return this.backdrop.classList.contains('open');
  }

  open(): void {
    this.drop();
    this.backdrop.classList.add('open');
    this.sync();
  }

  close(): void {
    this.drop();
    this.backdrop.classList.remove('open');
  }

  sync(): void {
    if (!this.isOpen) return;
    const inv = this.sim.inventory;
    for (const slot of this.slots) slot.sync(inv);
    // The held stack may have been consumed by the simulation while the bag was open.
    if (this.heldSlot !== -1 && inv.counts[this.heldSlot] === 0) this.drop();
  }

  private bindSlot(slot: SlotView): void {
    const i = slot.index;
    slot.el.addEventListener('pointerdown', () => {
      this.longPressed = false;
      window.clearTimeout(this.pressTimer);
      this.pressTimer = window.setTimeout(() => {
        this.longPressed = true;
        this.pick(i, true);
      }, LONG_PRESS_MS);
    });
    const cancel = () => window.clearTimeout(this.pressTimer);
    slot.el.addEventListener('pointerleave', cancel);
    slot.el.addEventListener('pointercancel', cancel);
    slot.el.addEventListener('click', () => {
      cancel();
      if (this.longPressed) {
        this.longPressed = false;
        return;
      }
      this.tap(i);
    });
  }

  private tap(i: number): void {
    const inv = this.sim.inventory;
    if (this.heldSlot === -1) {
      this.pick(i, false);
    } else if (this.heldSlot === i) {
      this.drop();
    } else {
      moveSlot(inv, this.heldSlot, inv, i, this.heldCount);
      this.drop();
      this.sync();
    }
  }

  private pick(i: number, half: boolean): void {
    const inv = this.sim.inventory;
    const have = inv.counts[i];
    if (have === 0) return;
    this.drop();
    this.heldSlot = i;
    this.heldCount = half ? Math.max(1, Math.ceil(have / 2)) : have;
    this.slots[i].el.classList.add('held');
    this.status.textContent = `Moving ${this.heldCount} × ${itemDef(inv.ids[i]).name}`;
  }

  private drop(): void {
    if (this.heldSlot !== -1) this.slots[this.heldSlot].el.classList.remove('held');
    this.heldSlot = -1;
    this.heldCount = 0;
    this.status.textContent = 'Tap a stack, then tap where it goes. Hold for half.';
  }
}
