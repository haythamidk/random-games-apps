import { ENTITY_DEFS } from '../core/entities';
import { ITEMS, itemDef } from '../core/items';
import { NODE_DEFS } from '../core/nodes';
import { Ev, HOTBAR_SIZE, Sim, TICK_HZ, TargetType, VITAL_MAX, isNight, selectedItem, ticksToPhaseChange } from '../core/sim';
import { TouchInput } from '../input/touch';
import { targetName } from '../view/worldview';
import { SlotView } from './slots';

const VITAL_GLYPHS = {
  health: '<path d="M12 20s-7-4.4-7-9.6A3.9 3.9 0 0112 8a3.9 3.9 0 017 2.4C19 15.6 12 20 12 20z"/>',
  charge: '<path d="M13.5 3L6 13.5h5L10 21l8-11h-5.2z"/>',
  warmth: '<path d="M12 3c1 3.5 5 5.5 5 10a5 5 0 01-10 0c0-2.2 1.2-3.6 2.3-4.8.3 1.6 1 2.4 1.8 2.8C11 8.5 11 5.5 12 3z"/>',
} as const;

type VitalKey = keyof typeof VITAL_GLYPHS;
const VITAL_KEYS: VitalKey[] = ['health', 'charge', 'warmth'];

export type ContextAction = '' | 'rotate' | 'craft' | 'open' | 'toggle' | 'circuit';

export interface HudCallbacks {
  openBag(): void;
  openMenu(): void;
  context(action: ContextAction, entityId: number): void;
  respawn(): void;
}

const CONTEXT_LABEL: Record<ContextAction, string> = {
  '': '', rotate: 'Rotate', craft: 'Use', open: 'Open', toggle: 'Flip', circuit: 'Circuit',
};

const LINK_BAD = ['That cannot be wired', 'That only receives signals. Start from a sensor or switch', 'That only sends signals. Pick something to drive', 'The circuit is full'];

/** Always-on overlay: vitals, clock, reticle target, hotbar and the thumb buttons. */
export class Hud {
  private fills = {} as Record<VitalKey, HTMLElement>;
  private shown = { health: -1, charge: -1, warmth: -1 };
  private clock: HTMLElement;
  private shownClock = '';
  private slots: SlotView[] = [];
  private shownSelected = -1;
  private toast: HTMLElement;
  private toastTimer = 0;
  private feed: HTMLElement;
  private feedItem = 0;
  private feedCount = 0;
  private feedTimer = 0;
  private target: HTMLElement;
  private targetLabel: HTMLElement;
  private targetBar: HTMLElement;
  private shownTarget = '';
  private shownProgress = -1;
  private ctx: HTMLButtonElement;
  private ctxAction: ContextAction = '';
  private death: HTMLElement;
  private shownDead = false;
  private dawn: HTMLElement;
  private dawnBody: HTMLElement;
  private hurt: HTMLElement;

  constructor(root: HTMLElement, private sim: Sim, input: TouchInput, cb: HudCallbacks) {
    const vitals = el('div', 'vitals');
    for (const key of VITAL_KEYS) {
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
    this.clock = el('div', 'clock');
    vitals.appendChild(this.clock);
    root.appendChild(vitals);

    root.appendChild(el('div', 'reticle'));

    this.target = el('div', 'target');
    this.targetLabel = el('div', 'target-label');
    const track = el('div', 'target-track');
    this.targetBar = el('div', 'target-bar');
    track.appendChild(this.targetBar);
    this.target.append(this.targetLabel, track);
    root.appendChild(this.target);

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
    this.feed = el('div', 'feed');
    root.appendChild(this.feed);

    const menuBtn = button('btn btn-menu', 'Menu', MENU_GLYPH);
    menuBtn.addEventListener('click', () => cb.openMenu());
    root.appendChild(menuBtn);

    const bagBtn = button('btn btn-bag', 'Bag and crafting', BAG_GLYPH);
    bagBtn.addEventListener('click', () => cb.openBag());
    root.appendChild(bagBtn);

    const act = button('btn btn-act', 'Act', ACT_GLYPH);
    input.bindHoldButton(act, (held) => {
      input.act = held;
      if (held) input.actTapped = true;
    });
    root.appendChild(act);

    const jump = button('btn btn-jump', 'Jump', JUMP_GLYPH);
    input.bindHoldButton(jump, (held) => input.setJumpHeld(held));
    root.appendChild(jump);

    this.ctx = document.createElement('button');
    this.ctx.type = 'button';
    this.ctx.className = 'btn-ctx';
    this.ctx.hidden = true;
    this.ctx.addEventListener('click', () => {
      const t = this.sim.target;
      cb.context(this.ctxAction, t.type === TargetType.Entity ? t.id : 0);
    });
    root.appendChild(this.ctx);

    this.death = el('div', 'death');
    this.death.hidden = true;
    const card = el('div', 'death-card');
    const h = el('div', 'death-title');
    h.textContent = 'The cold took you';
    const body = el('div', 'death-body');
    body.textContent = 'Your suit rebooted at the landing pad. Your bag and your base are untouched.';
    const again = document.createElement('button');
    again.type = 'button';
    again.className = 'primary';
    again.textContent = 'Get up';
    again.addEventListener('click', () => cb.respawn());
    card.append(h, body, again);
    this.death.appendChild(card);
    root.appendChild(this.death);

    this.dawn = el('div', 'death');
    this.dawn.hidden = true;
    const dawnCard = el('div', 'death-card');
    const dawnTitle = el('div', 'death-title');
    dawnTitle.textContent = 'You held the night';
    this.dawnBody = el('div', 'death-body');
    const onward = document.createElement('button');
    onward.type = 'button';
    onward.className = 'primary';
    onward.textContent = 'Keep building';
    onward.addEventListener('click', () => (this.dawn.hidden = true));
    dawnCard.append(dawnTitle, this.dawnBody, onward);
    this.dawn.appendChild(dawnCard);
    root.appendChild(this.dawn);

    this.hurt = el('div', 'hurt');
    root.appendChild(this.hurt);
  }

  /** True while a card that needs an answer is covering the screen. */
  get blocking(): boolean {
    return !this.dawn.hidden;
  }

  select(i: number): void {
    this.sim.selected = i;
    const inv = this.sim.inventory;
    if (inv.counts[i] > 0) {
      const def = itemDef(inv.ids[i]);
      const hint = def.kind === 'kit' ? ' · aim at the ground and press the action button'
        : def.kind === 'consumable' ? ' · press the action button to use'
        : def.kind === 'linker' ? ' · press on a sensor or switch, then on what it should drive' : '';
      this.showToast(def.name + hint);
    }
  }

  showToast(text: string): void {
    this.toast.textContent = text;
    this.toast.classList.add('on');
    window.clearTimeout(this.toastTimer);
    this.toastTimer = window.setTimeout(() => this.toast.classList.remove('on'), 1800);
  }

  private entityName(id: number): string {
    return ENTITY_DEFS[this.sim.entities.kind[id]].name || 'it';
  }

  /** Brief red edge when the player is hit. */
  private flashHurt(): void {
    this.hurt.classList.remove('on');
    // Reading layout restarts the CSS animation when hits come close together.
    void this.hurt.offsetWidth;
    this.hurt.classList.add('on');
  }

  /** Running "+3 Deadwood" tally that merges repeated pickups of one item. */
  private pickup(item: number, n: number): void {
    if (item !== this.feedItem) {
      this.feedItem = item;
      this.feedCount = 0;
    }
    this.feedCount += n;
    this.feed.textContent = `+${this.feedCount} ${itemDef(item).name}`;
    this.feed.classList.add('on');
    window.clearTimeout(this.feedTimer);
    this.feedTimer = window.setTimeout(() => {
      this.feed.classList.remove('on');
      this.feedItem = 0;
    }, 1600);
  }

  /** Reflects sim state; writes to the DOM only when a shown value changed. */
  sync(sim: Sim): void {
    const ev = sim.events;
    for (let i = 0; i < ev.length; i += 3) {
      const code = ev[i];
      if (code === Ev.Gather) this.pickup(ev[i + 1], ev[i + 2]);
      else if (code === Ev.BagFull) this.showToast('Bag is full');
      else if (code === Ev.NeedTool) this.showToast(`${NODE_DEFS[ev[i + 1]].name} needs a pick`);
      else if (code === Ev.Used) this.showToast(`Used ${itemDef(ev[i + 1]).name}`);
      else if (code === Ev.Removed) this.showToast(`Packed up ${ENTITY_DEFS[ev[i + 2]].name}`);
      else if (code === Ev.LinkStart) this.showToast(`From ${this.entityName(ev[i + 1])}. Now press on what it should drive`);
      else if (code === Ev.Linked) this.showToast(`${this.entityName(ev[i + 1])} now drives ${this.entityName(ev[i + 2])}`);
      else if (code === Ev.Unlinked) this.showToast(`Unwired ${this.entityName(ev[i + 1])} from ${this.entityName(ev[i + 2])}`);
      else if (code === Ev.LinkBad) this.showToast(LINK_BAD[ev[i + 1]] ?? LINK_BAD[0]);
      else if (code === Ev.NeedDeposit) this.showToast('Drills only work on stained ground: an ore or coal deposit');
      else if (code === Ev.Dusk) this.showToast('One minute to nightfall. Strays wake in the dark');
      else if (code === Ev.StrayDown && ev[i + 2] === 1) this.showToast('Turret took down a stray');
      else if (code === Ev.Bite) this.flashHurt();
      else if (code === Ev.Dawn && ev[i + 2] === 1) {
        const n = ev[i + 1];
        this.dawnBody.textContent = n === 1
          ? 'First night survived. The strays shut down at sunrise. The next night brings more of them.'
          : `${n} nights survived. Each one brings more strays than the last.`;
        this.dawn.hidden = false;
      }
    }

    for (const key of VITAL_KEYS) {
      const pct = Math.round((sim.vitals[key] / VITAL_MAX) * 100);
      if (pct !== this.shown[key]) {
        this.fills[key].style.width = `${pct}%`;
        this.fills[key].parentElement!.classList.toggle('low', pct <= 25);
        this.shown[key] = pct;
      }
    }

    const secs = Math.ceil(ticksToPhaseChange(sim) / TICK_HZ);
    const clock = `${isNight(sim) ? 'Dawn' : 'Night'} in ${Math.floor(secs / 60)}:${String(secs % 60).padStart(2, '0')}`;
    if (clock !== this.shownClock) {
      this.clock.textContent = clock;
      this.clock.classList.toggle('night', isNight(sim));
      this.shownClock = clock;
    }

    for (const slot of this.slots) slot.sync(sim.inventory);
    if (sim.selected !== this.shownSelected) {
      this.slots.forEach((s, i) => s.el.classList.toggle('selected', i === sim.selected));
      this.shownSelected = sim.selected;
    }

    const name = targetName(sim);
    if (name !== this.shownTarget) {
      this.targetLabel.textContent = name;
      this.target.classList.toggle('on', name !== '');
      this.shownTarget = name;
    }
    const w = sim.work;
    const progress = w.need > 0 ? Math.round((w.progress / w.need) * 20) : 0;
    if (progress !== this.shownProgress) {
      this.targetBar.style.width = `${progress * 5}%`;
      this.target.classList.toggle('working', progress > 0);
      this.shownProgress = progress;
    }

    let action: ContextAction = '';
    const held = ITEMS[selectedItem(sim)];
    if (held.kind === 'linker') action = 'circuit';
    else if (held.kind === 'kit') action = 'rotate';
    else if (sim.target.type === TargetType.Entity) action = ENTITY_DEFS[sim.entities.kind[sim.target.id]].use;
    if (action !== this.ctxAction) {
      this.ctxAction = action;
      this.ctx.hidden = action === '';
      this.ctx.textContent = CONTEXT_LABEL[action];
    }

    if (sim.dead !== this.shownDead) {
      this.death.hidden = !sim.dead;
      this.shownDead = sim.dead;
    }
  }
}

const BAG_GLYPH =
  '<path d="M6 9h12l1.2 10.5H4.8z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/><path d="M9 9V7a3 3 0 016 0v2" fill="none" stroke="currentColor" stroke-width="1.8"/>';
const MENU_GLYPH = '<path d="M5 7h14M5 12h14M5 17h14" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>';
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
