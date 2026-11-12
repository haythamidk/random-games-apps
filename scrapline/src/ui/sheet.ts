import { ENTITY_DEFS } from '../core/entities';
import { Container, count, moveSlot } from '../core/inventory';
import { itemDef } from '../core/items';
import { machineStatus } from '../core/machines';
import { Recipe, Station, craft, hasInputs, recipesFor } from '../core/recipes';
import { HOTBAR_SIZE, INVENTORY_SIZE, Sim } from '../core/sim';
import { iconSvg } from './icons';
import { SlotView } from './slots';

const LONG_PRESS_MS = 380;
const HINT = 'Tap a stack, then tap where it goes. Hold for half.';

export type SheetMode = { kind: 'craft'; station: Station } | { kind: 'storage'; entityId: number };

interface RecipeRow {
  recipe: Recipe;
  el: HTMLButtonElement;
  needs: { id: number; need: number; el: HTMLElement; shown: number }[];
  shownOk: boolean | null;
}

/**
 * The one bottom sheet for everything item-related. The right pane is always
 * the player's bag; the left pane is either a recipe list or another container.
 * Items move by tapping (pick up, then put down), never by dragging.
 */
export class InventorySheet {
  private backdrop: HTMLElement;
  private title: HTMLElement;
  private status: HTMLElement;
  private left: HTMLElement;
  private bagSlots: SlotView[] = [];
  private storeSlots: SlotView[] = [];
  private store: Container | null = null;
  private storeEntity = 0;
  private machineLine: HTMLElement | null = null;
  private shownMachine = '';
  private rows: RecipeRow[] = [];
  private heldFrom: Container | null = null;
  private heldSlot = -1;
  private heldCount = 0;
  private heldEl: HTMLElement | null = null;
  private pressTimer = 0;
  private longPressed = false;

  constructor(root: HTMLElement, private sim: Sim) {
    this.backdrop = div('sheet-backdrop');
    this.backdrop.addEventListener('pointerdown', (e) => {
      if (e.target === this.backdrop) this.close();
    });

    const sheet = div('sheet');
    sheet.setAttribute('role', 'dialog');

    const head = div('sheet-head');
    this.title = document.createElement('span');
    this.title.className = 'sheet-title';
    this.status = document.createElement('span');
    this.status.className = 'sheet-status';
    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'sheet-close';
    close.setAttribute('aria-label', 'Close');
    close.innerHTML =
      '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/></svg>';
    close.addEventListener('click', () => this.close());
    head.append(this.title, this.status, close);

    const body = div('sheet-body');
    this.left = div('pane pane-left');
    const right = div('pane pane-bag');
    const grid = div('slot-grid');
    for (let i = 0; i < INVENTORY_SIZE; i++) {
      const slot = new SlotView(i, i < HOTBAR_SIZE ? 'bag-hot' : '');
      this.bindSlot(slot, () => this.sim.inventory);
      grid.appendChild(slot.el);
      this.bagSlots.push(slot);
    }
    right.appendChild(grid);
    body.append(this.left, right);

    sheet.append(head, body);
    this.backdrop.appendChild(sheet);
    root.appendChild(this.backdrop);
  }

  get isOpen(): boolean {
    return this.backdrop.classList.contains('open');
  }

  open(mode: SheetMode): void {
    this.drop();
    this.left.textContent = '';
    this.rows = [];
    this.storeSlots = [];
    this.store = null;
    this.storeEntity = 0;
    this.machineLine = null;
    this.shownMachine = '';

    if (mode.kind === 'craft') {
      this.title.textContent = mode.station === 'workbench' ? 'Workbench' : 'Bag';
      this.buildRecipes(mode.station);
    } else {
      const es = this.sim.entities;
      this.store = es.containers[mode.entityId];
      this.storeEntity = mode.entityId;
      this.title.textContent = ENTITY_DEFS[es.kind[mode.entityId]].name;
      this.buildStorage();
    }
    this.backdrop.classList.add('open');
    this.sync();
  }

  close(): void {
    this.drop();
    this.backdrop.classList.remove('open');
  }

  /** Refreshes whatever is on screen from sim state. Cheap when nothing changed. */
  sync(): void {
    if (!this.isOpen) return;
    const inv = this.sim.inventory;
    for (const slot of this.bagSlots) slot.sync(inv);
    if (this.store) {
      // The machine or crate may have been packed up by something else while open.
      if (this.sim.entities.containers[this.storeEntity] !== this.store) {
        this.close();
        return;
      }
      for (const slot of this.storeSlots) slot.sync(this.store);
      if (this.machineLine) {
        const text = machineStatus(this.sim, this.storeEntity);
        if (text !== this.shownMachine) {
          this.machineLine.textContent = text;
          this.shownMachine = text;
        }
      }
    }
    for (const row of this.rows) this.syncRow(row);
    if (this.heldFrom && this.heldFrom.counts[this.heldSlot] === 0) this.drop();
  }

  // ---------------------------------------------------------------- crafting

  private buildRecipes(station: Station): void {
    const label = div('pane-label');
    label.textContent = station === 'workbench' ? 'Build at the bench' : 'Craft by hand';
    const list = div('recipe-list');
    for (const recipe of recipesFor(station)) {
      const [outId, outN] = recipe.output;
      const el = document.createElement('button');
      el.type = 'button';
      el.className = 'recipe';
      el.dataset.recipe = String(recipe.id);

      const icon = document.createElement('span');
      icon.className = 'recipe-icon';
      icon.innerHTML = iconSvg(outId);
      const text = div('recipe-text');
      const name = div('recipe-name');
      name.textContent = itemDef(outId).name + (outN > 1 ? ` ×${outN}` : '');
      const costs = div('recipe-costs');
      const needs: RecipeRow['needs'] = [];
      for (const [id, need] of recipe.inputs) {
        const chip = document.createElement('span');
        chip.className = 'cost';
        chip.innerHTML = iconSvg(id);
        const num = document.createElement('span');
        chip.appendChild(num);
        costs.appendChild(chip);
        needs.push({ id, need, el: chip, shown: -1 });
      }
      text.append(name, costs);
      el.append(icon, text);
      const row: RecipeRow = { recipe, el, needs, shownOk: null };
      el.addEventListener('click', () => this.tryCraft(row));
      list.appendChild(el);
      this.rows.push(row);
    }
    this.left.append(label, list);
  }

  private syncRow(row: RecipeRow): void {
    const inv = this.sim.inventory;
    for (const n of row.needs) {
      const have = count(inv, n.id);
      if (have !== n.shown) {
        n.el.lastElementChild!.textContent = `${Math.min(have, 999)}/${n.need}`;
        n.el.classList.toggle('short', have < n.need);
        n.shown = have;
      }
    }
    const ok = hasInputs(inv, row.recipe);
    if (ok !== row.shownOk) {
      row.el.classList.toggle('ready', ok);
      row.el.setAttribute('aria-disabled', String(!ok));
      row.shownOk = ok;
    }
  }

  private tryCraft(row: RecipeRow): void {
    this.drop();
    const name = itemDef(row.recipe.output[0]).name;
    if (!hasInputs(this.sim.inventory, row.recipe)) {
      this.status.textContent = `Not enough materials for ${name}.`;
      return;
    }
    if (craft(this.sim.inventory, row.recipe)) {
      this.status.textContent = `Made ${name}.`;
    } else {
      this.status.textContent = 'Bag is full.';
    }
    this.sync();
  }

  // ---------------------------------------------------------------- storage

  private buildStorage(): void {
    if (!this.store) return;
    const names = ENTITY_DEFS[this.sim.entities.kind[this.storeEntity]].slotNames;
    const label = div('pane-label');
    label.textContent = names ? 'Inside the machine' : 'Stored here';
    const grid = div(names ? 'slot-grid machine-grid' : 'slot-grid store-grid');
    for (let i = 0; i < this.store.size; i++) {
      const slot = new SlotView(i);
      this.bindSlot(slot, () => this.store!);
      this.storeSlots.push(slot);
      if (names) {
        // Machines label each slot so it is clear what goes where.
        const cell = div('machine-slot');
        const name = div('machine-slot-name');
        name.textContent = names[i] ?? '';
        cell.append(slot.el, name);
        grid.appendChild(cell);
      } else {
        grid.appendChild(slot.el);
      }
    }
    this.left.append(label, grid);
    if (names) {
      this.machineLine = div('machine-status');
      const tip = div('machine-tip');
      tip.textContent = 'It pushes finished items onto a belt placed in front of it. Belts pointing into it feed it.';
      this.left.append(this.machineLine, tip);
    }
  }

  // ---------------------------------------------------------------- tap to move

  private bindSlot(slot: SlotView, container: () => Container): void {
    const i = slot.index;
    slot.el.addEventListener('pointerdown', () => {
      this.longPressed = false;
      window.clearTimeout(this.pressTimer);
      this.pressTimer = window.setTimeout(() => {
        this.longPressed = true;
        this.pick(container(), i, slot.el, true);
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
      this.tap(container(), i, slot.el);
    });
  }

  private tap(c: Container, i: number, el: HTMLElement): void {
    if (!this.heldFrom) {
      this.pick(c, i, el, false);
    } else if (this.heldFrom === c && this.heldSlot === i) {
      this.drop();
    } else {
      moveSlot(this.heldFrom, this.heldSlot, c, i, this.heldCount);
      this.drop();
      this.sync();
    }
  }

  private pick(c: Container, i: number, el: HTMLElement, half: boolean): void {
    const have = c.counts[i];
    if (have === 0) return;
    this.drop();
    this.heldFrom = c;
    this.heldSlot = i;
    this.heldCount = half ? Math.max(1, Math.ceil(have / 2)) : have;
    this.heldEl = el;
    el.classList.add('held');
    this.status.textContent = `Moving ${this.heldCount} × ${itemDef(c.ids[i]).name}`;
  }

  private drop(): void {
    this.heldEl?.classList.remove('held');
    this.heldEl = null;
    this.heldFrom = null;
    this.heldSlot = -1;
    this.heldCount = 0;
    this.status.textContent = HINT;
  }
}

function div(className: string): HTMLElement {
  const e = document.createElement('div');
  e.className = className;
  return e;
}
