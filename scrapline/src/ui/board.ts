import { ENTITY_DEFS, EntityKind } from '../core/entities';
import {
  LOGIC_DEFS, LOGIC_HZ, NodeType, PORTS, addNode, addWire, findWire, removeNode, removeWireAt, wireDstNode,
  wireDstPort, wireSrcNode, wireSrcPort,
} from '../core/logic/graph';
import { SENSOR_RANGE, Sim, ensureNode } from '../core/sim';

const NODE_W = 124;
const HEAD_H = 26;
const ROW_H = 26;
/** Ports are drawn small but can be hit from this far away, in screen pixels at any zoom. */
const PORT_DRAW = 8;
const PORT_HIT = 30;
const WIRE_HIT = 16;
const DRAG_SLOP = 8;
/** A dragged wire's free end floats this far above the finger so the finger never hides the target. */
const DRAG_LIFT = 48;
const ZOOM_MIN = 0.55;
const ZOOM_MAX = 1.7;
const GRID = 8;

const C = {
  bg: '#0f1a1f',
  grid: 'rgba(232,228,216,0.06)',
  node: '#1c2d35',
  device: '#22383f',
  line: 'rgba(232,228,216,0.28)',
  text: '#e8e4d8',
  muted: '#9fb0b3',
  amber: '#f2a93b',
  teal: '#5fd0c5',
  coral: '#ef6f5e',
  off: '#51626a',
};

const PALETTE = [NodeType.And, NodeType.Or, NodeType.Not, NodeType.Delay, NodeType.Timer];

interface Port {
  node: number;
  port: number;
  output: boolean;
}

/**
 * Full-screen circuit editor. Built for thumbs:
 * tap an output, then tap an input to wire them (dragging between them works too);
 * tap a wire or a node to act on it; one finger on empty space pans, two pinch.
 * It edits the live graph, so signals can be watched flowing while wiring.
 */
export class Board {
  private root: HTMLElement;
  private canvas: HTMLCanvasElement;
  private ctx: CanvasRenderingContext2D;
  private hint: HTMLElement;
  private bar: HTMLElement;
  private panX = 0;
  private panY = 0;
  private zoom = 1;
  private w = 0;
  private h = 0;
  private dpr = 1;

  /** Output port chosen as the start of a new wire. */
  private from: Port | null = null;
  private selNode = 0;
  private selWire = -1;
  private shownSel = '';

  private pointers = new Map<number, { x: number; y: number }>();
  private mode: 'idle' | 'pan' | 'node' | 'wire' | 'pinch' = 'idle';
  private downX = 0;
  private downY = 0;
  private moved = false;
  private dragNode = 0;
  private dragDX = 0;
  private dragDY = 0;
  private wireX = 0;
  private wireY = 0;
  private pinchDist = 0;
  private pinchZoom = 1;

  constructor(parent: HTMLElement, private sim: Sim) {
    this.root = el('div', 'board');
    this.root.hidden = true;
    this.canvas = document.createElement('canvas');
    this.canvas.className = 'board-canvas';
    this.ctx = this.canvas.getContext('2d')!;

    const top = el('div', 'board-top');
    const title = el('div', 'board-title');
    title.textContent = 'Circuit';
    this.hint = el('div', 'board-hint');
    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'board-close';
    close.textContent = 'Done';
    close.addEventListener('click', () => this.close());
    top.append(title, this.hint, close);

    this.bar = el('div', 'board-bar');

    const palette = el('div', 'board-palette');
    for (const type of PALETTE) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'chip';
      b.dataset.add = String(type);
      b.textContent = `+ ${LOGIC_DEFS[type].name}`;
      b.addEventListener('click', () => this.addLogic(type));
      palette.appendChild(b);
    }

    this.root.append(this.canvas, top, this.bar, palette);
    parent.appendChild(this.root);

    this.canvas.addEventListener('pointerdown', (e) => this.down(e));
    this.canvas.addEventListener('pointermove', (e) => this.move(e));
    this.canvas.addEventListener('pointerup', (e) => this.up(e));
    this.canvas.addEventListener('pointercancel', (e) => this.up(e));
    this.canvas.addEventListener('wheel', (e) => {
      e.preventDefault();
      this.zoomAt(e.clientX, e.clientY, this.zoom * (e.deltaY < 0 ? 1.1 : 0.9));
    }, { passive: false });
    window.addEventListener('resize', () => this.resize());
  }

  get isOpen(): boolean {
    return !this.root.hidden;
  }

  /** Opens the board, centred on `entity` if given (it is added to the board if needed). */
  open(entity = 0): void {
    this.root.hidden = false;
    this.from = null;
    this.selNode = 0;
    this.selWire = -1;
    this.shownSel = '';
    this.mode = 'idle';
    this.pointers.clear();
    this.resize();
    const g = this.sim.graph;
    let focus = 0;
    if (entity && (ENTITY_DEFS[this.sim.entities.kind[entity]].ins.length || ENTITY_DEFS[this.sim.entities.kind[entity]].outs.length)) {
      focus = ensureNode(this.sim, entity);
    }
    if (focus) {
      this.panX = g.bx[focus] + NODE_W / 2;
      this.panY = g.by[focus] + 40;
      this.selNode = focus;
    } else {
      this.fit();
    }
  }

  close(): void {
    this.root.hidden = true;
  }

  /** Centres and zooms so every node is on screen. */
  private fit(): void {
    const g = this.sim.graph;
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (let n = 1; n < g.high; n++) {
      if (g.type[n] === NodeType.Free) continue;
      x0 = Math.min(x0, g.bx[n]);
      y0 = Math.min(y0, g.by[n]);
      x1 = Math.max(x1, g.bx[n] + NODE_W);
      y1 = Math.max(y1, g.by[n] + this.nodeH(n));
    }
    if (x0 === Infinity) {
      this.panX = 0;
      this.panY = 0;
      this.zoom = 1;
      return;
    }
    this.panX = (x0 + x1) / 2;
    this.panY = (y0 + y1) / 2;
    const fitZoom = Math.min((this.w - 80) / (x1 - x0 + 1), (this.h - 190) / (y1 - y0 + 1));
    this.zoom = Math.min(1.1, Math.max(ZOOM_MIN, fitZoom));
  }

  private resize(): void {
    if (this.root.hidden) return;
    this.w = this.root.clientWidth;
    this.h = this.root.clientHeight;
    this.dpr = Math.min(window.devicePixelRatio || 1, 2);
    this.canvas.width = Math.round(this.w * this.dpr);
    this.canvas.height = Math.round(this.h * this.dpr);
  }

  // ---------------------------------------------------------------- geometry

  private ins(n: number): readonly string[] {
    const g = this.sim.graph;
    return g.type[n] === NodeType.Device ? ENTITY_DEFS[this.sim.entities.kind[g.entity[n]]].ins : LOGIC_DEFS[g.type[n]].ins;
  }

  private outs(n: number): readonly string[] {
    const g = this.sim.graph;
    return g.type[n] === NodeType.Device ? ENTITY_DEFS[this.sim.entities.kind[g.entity[n]]].outs : LOGIC_DEFS[g.type[n]].outs;
  }

  private nodeName(n: number): string {
    const g = this.sim.graph;
    return g.type[n] === NodeType.Device ? ENTITY_DEFS[this.sim.entities.kind[g.entity[n]]].name : LOGIC_DEFS[g.type[n]].name;
  }

  private nodeH(n: number): number {
    return HEAD_H + ROW_H * Math.max(1, this.ins(n).length, this.outs(n).length) + 6;
  }

  private sx(wx: number): number {
    return (wx - this.panX) * this.zoom + this.w / 2;
  }

  private sy(wy: number): number {
    return (wy - this.panY) * this.zoom + this.h / 2;
  }

  /** Screen position of a port. */
  private portX(n: number, output: boolean): number {
    return this.sx(this.sim.graph.bx[n] + (output ? NODE_W : 0));
  }

  private portY(n: number, port: number): number {
    return this.sy(this.sim.graph.by[n] + HEAD_H + ROW_H * port + ROW_H / 2);
  }

  /** Nearest port within the hit radius. The radius is in screen pixels, so targets stay big when zoomed out. */
  private portAt(x: number, y: number, wantOutput: boolean | null): Port | null {
    const g = this.sim.graph;
    let best: Port | null = null;
    let bestD = PORT_HIT * PORT_HIT;
    for (let n = 1; n < g.high; n++) {
      if (g.type[n] === NodeType.Free) continue;
      for (let side = 0; side < 2; side++) {
        const output = side === 1;
        if (wantOutput !== null && wantOutput !== output) continue;
        const count = output ? this.outs(n).length : this.ins(n).length;
        for (let p = 0; p < count; p++) {
          const dx = x - this.portX(n, output);
          const dy = y - this.portY(n, p);
          const d = dx * dx + dy * dy;
          if (d < bestD) {
            bestD = d;
            best = { node: n, port: p, output };
          }
        }
      }
    }
    return best;
  }

  private nodeAt(x: number, y: number): number {
    const g = this.sim.graph;
    for (let n = g.high - 1; n >= 1; n--) {
      if (g.type[n] === NodeType.Free) continue;
      const x0 = this.sx(g.bx[n]);
      const y0 = this.sy(g.by[n]);
      if (x >= x0 && x <= x0 + NODE_W * this.zoom && y >= y0 && y <= y0 + this.nodeH(n) * this.zoom) return n;
    }
    return 0;
  }

  private wireAt(x: number, y: number): number {
    const g = this.sim.graph;
    let best = -1;
    let bestD = WIRE_HIT;
    for (let i = 0; i < g.wireCount; i++) {
      const w = g.wires[i];
      const d = this.distToWire(x, y, w);
      if (d < bestD) {
        bestD = d;
        best = i;
      }
    }
    return best;
  }

  /** Distance from a point to a wire, sampled along its curve. */
  private distToWire(x: number, y: number, w: number): number {
    const x0 = this.portX(wireSrcNode(w), true);
    const y0 = this.portY(wireSrcNode(w), wireSrcPort(w));
    const x3 = this.portX(wireDstNode(w), false);
    const y3 = this.portY(wireDstNode(w), wireDstPort(w));
    const k = Math.max(30, Math.abs(x3 - x0) * 0.5);
    let best = Infinity;
    for (let s = 0; s <= 16; s++) {
      const t = s / 16;
      const u = 1 - t;
      const bx = u * u * u * x0 + 3 * u * u * t * (x0 + k) + 3 * u * t * t * (x3 - k) + t * t * t * x3;
      const by = u * u * u * y0 + 3 * u * u * t * y0 + 3 * u * t * t * y3 + t * t * t * y3;
      best = Math.min(best, Math.hypot(x - bx, y - by));
    }
    return best;
  }

  // ---------------------------------------------------------------- input

  private local(e: PointerEvent): { x: number; y: number } {
    const r = this.canvas.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  }

  private down(e: PointerEvent): void {
    e.preventDefault();
    this.canvas.setPointerCapture(e.pointerId);
    const p = this.local(e);
    this.pointers.set(e.pointerId, p);

    if (this.pointers.size === 2) {
      const [a, b] = [...this.pointers.values()];
      this.mode = 'pinch';
      this.pinchDist = Math.hypot(a.x - b.x, a.y - b.y);
      this.pinchZoom = this.zoom;
      return;
    }
    if (this.pointers.size > 2) return;

    this.downX = p.x;
    this.downY = p.y;
    this.moved = false;
    const out = this.portAt(p.x, p.y, true);
    const node = out ? 0 : this.nodeAt(p.x, p.y);
    if (out) {
      this.mode = 'wire';
      this.from = out;
      this.wireX = p.x;
      this.wireY = p.y - DRAG_LIFT;
    } else if (node && !this.portAt(p.x, p.y, false)) {
      const g = this.sim.graph;
      this.mode = 'node';
      this.dragNode = node;
      this.dragDX = (p.x - this.sx(g.bx[node])) / this.zoom;
      this.dragDY = (p.y - this.sy(g.by[node])) / this.zoom;
    } else {
      this.mode = 'pan';
    }
  }

  private move(e: PointerEvent): void {
    const prev = this.pointers.get(e.pointerId);
    if (!prev) return;
    const p = this.local(e);
    this.pointers.set(e.pointerId, p);

    if (this.mode === 'pinch') {
      if (this.pointers.size < 2) return;
      const [a, b] = [...this.pointers.values()];
      const d = Math.hypot(a.x - b.x, a.y - b.y);
      this.zoomAt((a.x + b.x) / 2, (a.y + b.y) / 2, this.pinchZoom * (d / Math.max(1, this.pinchDist)));
      return;
    }
    if (!this.moved && Math.hypot(p.x - this.downX, p.y - this.downY) < DRAG_SLOP) return;
    this.moved = true;

    if (this.mode === 'pan') {
      this.panX -= (p.x - prev.x) / this.zoom;
      this.panY -= (p.y - prev.y) / this.zoom;
    } else if (this.mode === 'node') {
      const g = this.sim.graph;
      const wx = (p.x - this.w / 2) / this.zoom + this.panX - this.dragDX;
      const wy = (p.y - this.h / 2) / this.zoom + this.panY - this.dragDY;
      g.bx[this.dragNode] = Math.round(wx / GRID) * GRID;
      g.by[this.dragNode] = Math.round(wy / GRID) * GRID;
    } else if (this.mode === 'wire') {
      this.wireX = p.x;
      this.wireY = p.y - DRAG_LIFT;
    }
  }

  private up(e: PointerEvent): void {
    const p = this.pointers.get(e.pointerId);
    this.pointers.delete(e.pointerId);
    if (!p) return;
    const mode = this.mode;
    if (mode === 'pinch') {
      if (this.pointers.size === 0) this.mode = 'idle';
      return;
    }
    this.mode = 'idle';

    if (mode === 'wire' && this.moved) {
      // Dropped a dragged wire: connect if its lifted end is on an input.
      const target = this.portAt(this.wireX, this.wireY, false);
      if (target && this.from) this.connect(this.from, target);
      this.from = null;
      return;
    }
    if (this.moved) return;
    this.tap(p.x, p.y);
  }

  private tap(x: number, y: number): void {
    const port = this.portAt(x, y, null);
    if (port) {
      if (port.output) {
        // Tapping the chosen output again cancels.
        this.from = this.from && this.from.node === port.node && this.from.port === port.port ? null : port;
        this.selNode = 0;
        this.selWire = -1;
      } else if (this.from) {
        this.connect(this.from, port);
        this.from = null;
      } else {
        // An input on its own: select the first wire into it so it can be removed.
        this.selWire = this.wireInto(port);
        this.selNode = 0;
      }
      return;
    }
    this.from = null;
    const node = this.nodeAt(x, y);
    if (node) {
      this.selNode = node;
      this.selWire = -1;
      return;
    }
    this.selNode = 0;
    this.selWire = this.wireAt(x, y);
  }

  private wireInto(port: Port): number {
    const g = this.sim.graph;
    for (let i = 0; i < g.wireCount; i++) {
      if (wireDstNode(g.wires[i]) === port.node && wireDstPort(g.wires[i]) === port.port) return i;
    }
    return -1;
  }

  private connect(from: Port, to: Port): void {
    const g = this.sim.graph;
    if (from.node === to.node) {
      this.flash('A node cannot feed itself directly.');
      return;
    }
    const existing = findWire(g, from.node, from.port, to.node, to.port);
    if (existing >= 0) {
      this.selWire = existing;
      this.selNode = 0;
      return;
    }
    if (!addWire(g, from.node, from.port, to.node, to.port)) this.flash('The circuit is full.');
    this.selWire = -1;
  }

  private addLogic(type: number): void {
    const g = this.sim.graph;
    const id = addNode(g, type, 0, Math.round((this.panX - 48) / GRID) * GRID, Math.round((this.panY - 30) / GRID) * GRID);
    if (id === 0) {
      this.flash('The circuit is full.');
      return;
    }
    // Nudge if it would land exactly on another node.
    for (let n = 1; n < g.high; n++) {
      if (n !== id && g.type[n] !== NodeType.Free && g.bx[n] === g.bx[id] && g.by[n] === g.by[id]) {
        g.bx[id] += GRID * 3;
        g.by[id] += GRID * 3;
      }
    }
    this.selNode = id;
    this.selWire = -1;
    this.from = null;
  }

  private zoomAt(x: number, y: number, zoom: number): void {
    const z = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, zoom));
    // Keep the point under the fingers fixed while zooming.
    const wx = (x - this.w / 2) / this.zoom + this.panX;
    const wy = (y - this.h / 2) / this.zoom + this.panY;
    this.zoom = z;
    this.panX = wx - (x - this.w / 2) / z;
    this.panY = wy - (y - this.h / 2) / z;
  }

  private flashText = '';
  private flashUntil = 0;

  private flash(text: string): void {
    this.flashText = text;
    this.flashUntil = performance.now() + 2200;
  }

  // ---------------------------------------------------------------- selection bar

  private syncBar(): void {
    const g = this.sim.graph;
    if (this.selNode && g.type[this.selNode] === NodeType.Free) this.selNode = 0;
    if (this.selWire >= g.wireCount) this.selWire = -1;
    const n = this.selNode;
    const key = n ? `n${n}:${g.param[n * 2]}:${g.param[n * 2 + 1]}` : this.selWire >= 0 ? `w${this.selWire}` : '';
    if (key === this.shownSel) return;
    this.shownSel = key;
    this.bar.textContent = '';
    if (this.selWire >= 0) {
      this.bar.append(label('Wire'), chip('Remove wire', 'remove-wire', () => {
        removeWireAt(g, this.selWire);
        this.selWire = -1;
      }));
      return;
    }
    if (!n) return;
    this.bar.append(label(this.nodeName(n)));
    const type = g.type[n];
    const isSensor = type === NodeType.Device && this.sim.entities.kind[g.entity[n]] === EntityKind.Sensor;
    const param = isSensor
      ? { label: 'Range', min: 2, max: 20, step: 1, initial: SENSOR_RANGE, unit: 'm' }
      : LOGIC_DEFS[type].param;
    if (param) {
      const v = g.param[n * 2] || param.initial;
      const text = param.unit === 'm' ? `${param.label} ${v} m` : `${param.label} ${(v / LOGIC_HZ).toFixed(1)} s`;
      this.bar.append(
        chip('−', 'less', () => (g.param[n * 2] = Math.max(param.min, v - param.step))),
        label(text),
        chip('+', 'more', () => (g.param[n * 2] = Math.min(param.max, v + param.step))),
      );
    }
    if (isSensor) {
      const only = g.param[n * 2 + 1] === 1;
      this.bar.append(chip(only ? 'Sees strays only' : 'Sees strays and you', 'mode', () => (g.param[n * 2 + 1] = only ? 0 : 1)));
    }
    if (type > NodeType.Device) {
      this.bar.append(chip('Remove', 'remove-node', () => {
        removeNode(g, n);
        this.selNode = 0;
      }));
    }
  }

  // ---------------------------------------------------------------- drawing

  /** Redraws the board. Called every frame while open so live signals are visible. */
  draw(): void {
    if (this.root.hidden) return;
    this.syncBar();
    const g = this.sim.graph;
    const ctx = this.ctx;
    const z = this.zoom;
    ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    ctx.fillStyle = C.bg;
    ctx.fillRect(0, 0, this.w, this.h);

    // Dot grid, to give panning something to move against.
    const step = 40 * z;
    ctx.fillStyle = C.grid;
    const ox = ((this.w / 2 - this.panX * z) % step + step) % step;
    const oy = ((this.h / 2 - this.panY * z) % step + step) % step;
    for (let y = oy; y < this.h; y += step) for (let x = ox; x < this.w; x += step) ctx.fillRect(x - 1, y - 1, 2, 2);

    // Wires under nodes.
    ctx.lineCap = 'round';
    for (let i = 0; i < g.wireCount; i++) {
      const w = g.wires[i];
      const live = g.outCur[wireSrcNode(w) * PORTS + wireSrcPort(w)] > 0;
      const sel = i === this.selWire;
      this.curve(
        this.portX(wireSrcNode(w), true), this.portY(wireSrcNode(w), wireSrcPort(w)),
        this.portX(wireDstNode(w), false), this.portY(wireDstNode(w), wireDstPort(w)),
        sel ? C.coral : live ? C.amber : C.off, (sel ? 5 : live ? 4 : 3) * Math.max(0.8, z),
      );
    }
    if (this.mode === 'wire' && this.moved && this.from) {
      this.curve(this.portX(this.from.node, true), this.portY(this.from.node, this.from.port), this.wireX, this.wireY, C.teal, 3);
      ctx.fillStyle = C.teal;
      ctx.beginPath();
      ctx.arc(this.wireX, this.wireY, 7, 0, Math.PI * 2);
      ctx.fill();
    }

    const pulse = 0.5 + 0.5 * Math.sin(performance.now() / 160);
    let any = false;
    for (let n = 1; n < g.high; n++) {
      if (g.type[n] === NodeType.Free) continue;
      any = true;
      const x = this.sx(g.bx[n]);
      const y = this.sy(g.by[n]);
      const w = NODE_W * z;
      const h = this.nodeH(n) * z;
      const device = g.type[n] === NodeType.Device;
      ctx.fillStyle = device ? C.device : C.node;
      ctx.strokeStyle = n === this.selNode ? C.amber : C.line;
      ctx.lineWidth = n === this.selNode ? 2.5 : 1.5;
      roundRect(ctx, x, y, w, h, 9 * z);
      ctx.fill();
      ctx.stroke();

      ctx.fillStyle = device ? C.teal : C.text;
      ctx.font = `600 ${Math.round(13 * z)}px system-ui, sans-serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(this.nodeName(n), x + w / 2, y + (HEAD_H / 2 + 2) * z);

      ctx.font = `${Math.round(11 * z)}px system-ui, sans-serif`;
      const ins = this.ins(n);
      const outs = this.outs(n);
      for (let p = 0; p < ins.length; p++) {
        const px = this.portX(n, false);
        const py = this.portY(n, p);
        // Inputs pulse while a wire is being made, to show where it can go.
        const armed = this.from !== null && this.from.node !== n;
        this.port(px, py, false, armed ? pulse : 0, false);
        ctx.fillStyle = C.muted;
        ctx.textAlign = 'left';
        ctx.fillText(ins[p], px + 13 * z, py);
      }
      for (let p = 0; p < outs.length; p++) {
        const px = this.portX(n, true);
        const py = this.portY(n, p);
        const chosen = this.from !== null && this.from.node === n && this.from.port === p;
        this.port(px, py, g.outCur[n * PORTS + p] > 0, 0, chosen);
        ctx.fillStyle = C.muted;
        ctx.textAlign = 'right';
        ctx.fillText(outs[p], px - 13 * z, py);
      }
    }

    const now = performance.now();
    const text = now < this.flashUntil ? this.flashText
      : !any ? 'Nothing to wire yet. Place a sensor, switch, lamp or turret first.'
      : this.from ? 'Now tap the input it should drive.'
      : 'Tap an output, then an input. Tap a wire or node to change it.';
    if (this.hint.textContent !== text) this.hint.textContent = text;
  }

  private port(x: number, y: number, live: boolean, armed: number, chosen: boolean): void {
    const ctx = this.ctx;
    const r = PORT_DRAW * Math.max(0.8, this.zoom);
    if (armed > 0) {
      ctx.strokeStyle = `rgba(95,208,197,${0.25 + 0.5 * armed})`;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(x, y, r + 5 + armed * 5, 0, Math.PI * 2);
      ctx.stroke();
    }
    ctx.fillStyle = chosen ? C.teal : live ? C.amber : C.bg;
    ctx.strokeStyle = chosen ? C.teal : live ? C.amber : C.text;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(x, y, r, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
  }

  private curve(x0: number, y0: number, x3: number, y3: number, color: string, width: number): void {
    const ctx = this.ctx;
    const k = Math.max(30, Math.abs(x3 - x0) * 0.5);
    ctx.strokeStyle = color;
    ctx.lineWidth = width;
    ctx.beginPath();
    ctx.moveTo(x0, y0);
    ctx.bezierCurveTo(x0 + k, y0, x3 - k, y3, x3, y3);
    ctx.stroke();
  }

  // ---------------------------------------------------------------- test hooks

  /** Screen position of a port, for automated tests. */
  portPosition(node: number, port: number, output: boolean): { x: number; y: number } {
    const r = this.canvas.getBoundingClientRect();
    return { x: r.left + this.portX(node, output), y: r.top + this.portY(node, port) };
  }
}


function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number): void {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

function el(tag: string, className: string): HTMLElement {
  const e = document.createElement(tag);
  e.className = className;
  return e;
}

function label(text: string): HTMLElement {
  const e = el('span', 'bar-label');
  e.textContent = text;
  return e;
}

function chip(text: string, action: string, onTap: () => void): HTMLButtonElement {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'chip';
  b.dataset.action = action;
  b.textContent = text;
  b.addEventListener('click', onTap);
  return b;
}
