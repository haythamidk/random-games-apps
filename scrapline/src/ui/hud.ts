import { itemDef } from '../core/items';
import { HOTBAR_SIZE, Sim, VITAL_MAX } from '../core/sim';
import { TouchInput } from '../input/touch';
import { SlotView } from './slots';

const VITAL_GLYPHS = {
  health: '<path d="M12 20s-7-4.4-7-9.6A3.9 3.9 0 0112 8a3.9 3.9 0 017 2.4C19 15.6 12 20 12 20z"/>',
  charge: '<path d="M13.5 3L6 13.5h5L10 21l8-11h-5.2z"/>',
  warmth: '<path d="M12 3c1 3.5 5 5.5 5 10a5 5 0 01-10 0c0-2.2 1.2-3.6 2.3-4.8.3 1.6 1 2.4 1.8 2.8C11 8.5 11 5.5 12 3z"/>',
} as const;

type VitalKey = keyof typeof VITAL_GLYPHS;

export interface HudCallbacks {
  openBag(): void;
}

/** Always-on overlay: vitals, hotbar and the thumb buttons. */
export class Hud {
  private fills = {} as Record<VitalKey, HTMLElement>;
  private shown = { health: -1, charge: -1, warmth: -1 };
  private slots: SlotView[] = [];
  private shownSelected = -1;
  private toast: HTMLElement;
  private toastTimer = 0;

  constructor(root: HTMLElement, private sim: Sim, input: TouchInput, cb: HudCallbacks) {
    const vitals = el('div', 'vitals');
    for (const key of ['health', 'charge', 'warmth'] as VitalKey[]) {
      const row = el('div', `vital vital-${key}`);
      row.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true">${VITAL_GLYPHS[key]}</svg>`;
      const gauge = el('div', 'gauge');
      const fill = el('div', 'gauge-fill');
      gauge.appendChild(fill);
      row.appendChild(gauge);
      row.setAttribute('aria-label', key);
      vitals.appendChild(row);
      this.fills[key] = fill;
    }
    root.appendChild(vitals);

    const reticle = el('div', 'reticle');
    root.appendChild(reticle);

    const bar = el('div', 'hotbar');
    for (let i = 0; i < HOTBAR_SIZE; i++) {
      const slot = new SlotView(i, 'hot');
      slot.el.addEventListener('click', () => this.select(i));
      bar.appendChild(slot.el);
      this.slots.push(slot);
    }
    // Swiping up from the hotbar opens the bag.
    let swipeY = 0;
    bar.addEventListener('pointerdown', (e) => (swipeY = e.clientY));
    bar.addEventListener('pointerup', (e) => {
      if (swipeY - e.clientY > 28) cb.openBag();
    });
    root.appendChild(bar);

    this.toast = el('div', 'toast');
    root.appendChild(this.toast);

    const bagBtn = button('btn btn-bag', 'Bag', BAG_GLYPH);
    bagBtn.addEventListener('click', () => cb.openBag());
    root.appendChild(bagBtn);

    const act = button('btn btn-act', 'Act', ACT_GLYPH);
    input.bindHoldButton(act, (held) => (input.act = held));
    root.appendChild(act);

    const jump = button('btn btn-jump', 'Jump', JUMP_GLYPH);
    input.bindHoldButton(jump, (held) => input.setJumpHeld(held));
    root.appendChild(jump);
  }

  select(i: number): void {
    this.sim.selected = i;
    const inv = this.sim.inventory;
    if (inv.counts[i] > 0) this.showToast(itemDef(inv.ids[i]).name);
  }

  showToast(text: string): void {
    this.toast.textContent = text;
    this.toast.classList.add('on');
    window.clearTimeout(this.toastTimer);
    this.toastTimer = window.setTimeout(() => this.toast.classList.remove('on'), 1400);
  }

  /** Reflects sim state; writes to the DOM only when a shown value changed. */
  sync(sim: Sim): void {
    for (const key of ['health', 'charge', 'warmth'] as VitalKey[]) {
      const pct = Math.round((sim.vitals[key] / VITAL_MAX) * 100);
      if (pct !== this.shown[key]) {
        this.fills[key].style.width = `${pct}%`;
        this.fills[key].parentElement!.classList.toggle('low', pct <= 25);
        this.shown[key] = pct;
      }
    }
    for (const slot of this.slots) slot.sync(sim.inventory);
    if (sim.selected !== this.shownSelected) {
      this.slots.forEach((s, i) => s.el.classList.toggle('selected', i === sim.selected));
      this.shownSelected = sim.selected;
    }
  }
}

const BAG_GLYPH =
  '<path d="M6 9h12l1.2 10.5H4.8z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/><path d="M9 9V7a3 3 0 016 0v2" fill="none" stroke="currentColor" stroke-width="1.8"/>';
const ACT_GLYPH =
  '<circle cx="12" cy="12" r="3.2" fill="currentColor"/><circle cx="12" cy="12" r="8" fill="none" stroke="currentColor" stroke-width="1.8"/>';
const JUMP_GLYPH =
  '<path d="M6 14l6-7 6 7M6 19l6-7 6 7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>';

function el(tag: string, className: string): HTMLElement {
  const e = document.createElement(tag);
  e.className = className;
  return e;
}

function button(className: string, label: string, glyph: string): HTMLButtonElement {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = className;
  b.setAttribute('aria-label', label);
  b.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true">${glyph}</svg>`;
  return b;
}
