export interface MenuCallbacks {
  setSensitivity(value: number): void;
  newWorld(): void;
}

/** Small settings sheet: look sensitivity and starting over. */
export class Menu {
  private backdrop: HTMLElement;
  private reset: HTMLButtonElement;
  private armed = false;

  constructor(root: HTMLElement, sensitivity: number, cb: MenuCallbacks) {
    this.backdrop = document.createElement('div');
    this.backdrop.className = 'sheet-backdrop';
    this.backdrop.addEventListener('pointerdown', (e) => {
      if (e.target === this.backdrop) this.close();
    });

    const sheet = document.createElement('div');
    sheet.className = 'sheet sheet-narrow';
    sheet.setAttribute('role', 'dialog');
    sheet.setAttribute('aria-label', 'Menu');

    const head = document.createElement('div');
    head.className = 'sheet-head';
    const title = document.createElement('span');
    title.className = 'sheet-title';
    title.textContent = 'Menu';
    const spacer = document.createElement('span');
    spacer.className = 'sheet-status';
    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'sheet-close';
    close.setAttribute('aria-label', 'Close');
    close.innerHTML =
      '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/></svg>';
    close.addEventListener('click', () => this.close());
    head.append(title, spacer, close);

    const row = document.createElement('label');
    row.className = 'menu-row';
    row.htmlFor = 'look-sensitivity';
    const rowText = document.createElement('span');
    rowText.textContent = 'Look sensitivity';
    const range = document.createElement('input');
    range.type = 'range';
    range.id = 'look-sensitivity';
    range.min = '0.4';
    range.max = '2';
    range.step = '0.1';
    range.value = String(sensitivity);
    range.addEventListener('input', () => cb.setSensitivity(Number(range.value)));
    row.append(rowText, range);

    this.reset = document.createElement('button');
    this.reset.type = 'button';
    this.reset.className = 'danger';
    this.reset.addEventListener('click', () => {
      if (!this.armed) {
        this.armed = true;
        this.reset.textContent = 'Tap again to erase this world';
        this.reset.classList.add('armed');
        return;
      }
      cb.newWorld();
    });

    const note = document.createElement('p');
    note.className = 'menu-note';
    note.textContent = 'The game saves by itself every half minute and whenever you leave the app.';

    sheet.append(head, row, note, this.reset);
    this.backdrop.appendChild(sheet);
    root.appendChild(this.backdrop);
    this.disarm();
  }

  get isOpen(): boolean {
    return this.backdrop.classList.contains('open');
  }

  open(): void {
    this.disarm();
    this.backdrop.classList.add('open');
  }

  close(): void {
    this.backdrop.classList.remove('open');
  }

  private disarm(): void {
    this.armed = false;
    this.reset.textContent = 'Start a new world';
    this.reset.classList.remove('armed');
  }
}
