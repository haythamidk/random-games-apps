import { Container } from '../core/inventory';
import { iconSvg } from './icons';

/**
 * One on-screen item slot. Remembers what it last drew so `sync` only touches
 * the DOM when the slot's contents actually change.
 */
export class SlotView {
  readonly el: HTMLButtonElement;
  private icon: HTMLSpanElement;
  private qty: HTMLSpanElement;
  private shownId = -1;
  private shownCount = -1;

  constructor(readonly index: number, extraClass = '') {
    this.el = document.createElement('button');
    this.el.type = 'button';
    this.el.className = `slot ${extraClass}`.trim();
    this.el.dataset.slot = String(index);
    this.icon = document.createElement('span');
    this.icon.className = 'slot-icon';
    this.qty = document.createElement('span');
    this.qty.className = 'slot-qty';
    this.el.append(this.icon, this.qty);
  }

  sync(c: Container): void {
    const count = c.counts[this.index];
    const id = count > 0 ? c.ids[this.index] : 0;
    if (id !== this.shownId) {
      this.icon.innerHTML = id ? iconSvg(id) : '';
      this.shownId = id;
    }
    if (count !== this.shownCount) {
      this.qty.textContent = count > 1 ? String(count) : '';
      this.shownCount = count;
    }
  }
}
