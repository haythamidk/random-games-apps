import { RenderStats } from '../view/renderer';

export interface DebugSample {
  fps: number;
  frameMs: number;
  tickMs: number;
  calls: number;
  triangles: number;
  scale: number;
  heapMb: number;
}

/** Performance readout, toggled with a three-finger tap or `?debug=1`. */
export class DebugOverlay {
  readonly sample: DebugSample = { fps: 0, frameMs: 0, tickMs: 0, calls: 0, triangles: 0, scale: 1, heapMb: 0 };
  private el: HTMLElement;
  private frames = 0;
  private frameSum = 0;
  private tickMax = 0;
  private lastFlush = 0;
  private visible = false;

  constructor(root: HTMLElement) {
    this.el = document.createElement('pre');
    this.el.className = 'debug';
    root.appendChild(this.el);
  }

  toggle(): void {
    this.visible = !this.visible;
    this.el.classList.toggle('on', this.visible);
  }

  /** Called once per frame; refreshes the numbers twice a second. */
  update(now: number, frameMs: number, tickMs: number, stats: RenderStats): void {
    this.frames++;
    this.frameSum += frameMs;
    if (tickMs > this.tickMax) this.tickMax = tickMs;
    if (now - this.lastFlush < 500) return;

    const s = this.sample;
    s.frameMs = this.frameSum / this.frames;
    s.fps = 1000 / Math.max(0.001, s.frameMs);
    s.tickMs = this.tickMax;
    s.calls = stats.calls;
    s.triangles = stats.triangles;
    s.scale = stats.scale;
    const mem = (performance as unknown as { memory?: { usedJSHeapSize: number } }).memory;
    s.heapMb = mem ? mem.usedJSHeapSize / 1048576 : 0;
    this.frames = 0;
    this.frameSum = 0;
    this.tickMax = 0;
    this.lastFlush = now;

    if (this.visible) {
      this.el.textContent =
        `${s.fps.toFixed(0)} fps  ${s.frameMs.toFixed(1)} ms\n` +
        `draws ${s.calls}  tris ${s.triangles}\n` +
        `tick ${s.tickMs.toFixed(2)} ms  res ${s.scale.toFixed(1)}\n` +
        `heap ${s.heapMb.toFixed(0)} MB`;
    }
  }
}
