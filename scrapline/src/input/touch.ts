import { MoveIntent } from '../core/player';

const STICK_RADIUS = 56;
const SPRINT_THRESHOLD = 0.85;
/** Radians of camera turn per CSS pixel of drag at sensitivity 1. */
const LOOK_RATE = 0.0052;
/** Fraction of the screen width, from the left, that spawns the move stick. */
const STICK_ZONE = 0.45;

/**
 * Turns raw pointer events into intents. Every control claims the pointer id
 * that touched it, so both thumbs (and a held action button) work together.
 */
export class TouchInput {
  sensitivity = 1;
  /** True while the action button is held. */
  act = false;
  /**
   * Latched when the action button goes down, cleared by the main loop. A quick
   * tap can start and end between two frames; this keeps it from being lost.
   */
  actTapped = false;
  /** Called on a three-finger tap (debug overlay toggle). */
  onThreeFingerTap: (() => void) | null = null;

  private stickId = -1;
  private stickX = 0;
  private stickY = 0;
  private moveX = 0;
  private moveY = 0;
  private lookId = -1;
  private lastX = 0;
  private lastY = 0;
  private lookDX = 0;
  private lookDY = 0;
  private jumpQueued = false;
  private jumpHeld = false;
  private active = new Set<number>();
  private keys = new Set<string>();
  private base: HTMLElement;
  private knob: HTMLElement;

  constructor(private layer: HTMLElement) {
    this.base = document.createElement('div');
    this.base.className = 'stick-base';
    this.knob = document.createElement('div');
    this.knob.className = 'stick-knob';
    this.base.appendChild(this.knob);
    layer.appendChild(this.base);

    layer.addEventListener('pointerdown', (e) => this.down(e));
    layer.addEventListener('pointermove', (e) => this.move(e));
    layer.addEventListener('pointerup', (e) => this.up(e));
    layer.addEventListener('pointercancel', (e) => this.up(e));
    layer.addEventListener('contextmenu', (e) => e.preventDefault());

    // Keyboard fallback for desktop testing.
    window.addEventListener('keydown', (e) => {
      this.keys.add(e.code);
      if (e.code === 'Space') this.jumpQueued = true;
    });
    window.addEventListener('keyup', (e) => this.keys.delete(e.code));
    window.addEventListener('blur', () => this.reset());
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) this.reset();
    });
  }

  /**
   * Makes a button that also steers the camera while held, so the right thumb
   * can keep aiming without lifting off the button.
   */
  bindHoldButton(el: HTMLElement, onChange: (held: boolean) => void): void {
    let id = -1;
    let lx = 0;
    let ly = 0;
    el.addEventListener('pointerdown', (e) => {
      if (id !== -1) return;
      id = e.pointerId;
      lx = e.clientX;
      ly = e.clientY;
      el.setPointerCapture(id);
      el.classList.add('held');
      onChange(true);
      e.preventDefault();
      e.stopPropagation();
    });
    el.addEventListener('pointermove', (e) => {
      if (e.pointerId !== id) return;
      this.lookDX += e.clientX - lx;
      this.lookDY += e.clientY - ly;
      lx = e.clientX;
      ly = e.clientY;
    });
    const end = (e: PointerEvent) => {
      if (e.pointerId !== id) return;
      id = -1;
      el.classList.remove('held');
      onChange(false);
    };
    el.addEventListener('pointerup', end);
    el.addEventListener('pointercancel', end);
  }

  setJumpHeld(held: boolean): void {
    this.jumpHeld = held;
    if (held) this.jumpQueued = true;
  }

  /** Writes this frame's intent and clears accumulated look deltas and edges. */
  consume(out: MoveIntent): void {
    let mx = this.moveX;
    let my = this.moveY;
    let sprint = Math.hypot(mx, my) > SPRINT_THRESHOLD;
    if (this.stickId === -1) {
      mx = (this.keys.has('KeyD') ? 1 : 0) - (this.keys.has('KeyA') ? 1 : 0);
      my = (this.keys.has('KeyW') ? 1 : 0) - (this.keys.has('KeyS') ? 1 : 0);
      sprint = this.keys.has('ShiftLeft');
    }
    out.moveX = mx;
    out.moveY = my;
    out.sprint = sprint;
    out.jump = this.jumpQueued || this.jumpHeld;
    out.lookYaw = -this.lookDX * LOOK_RATE * this.sensitivity;
    out.lookPitch = -this.lookDY * LOOK_RATE * this.sensitivity;
    this.lookDX = 0;
    this.lookDY = 0;
    this.jumpQueued = false;
  }

  private down(e: PointerEvent): void {
    e.preventDefault();
    this.layer.setPointerCapture(e.pointerId);
    this.active.add(e.pointerId);
    if (this.active.size === 3) this.onThreeFingerTap?.();

    const isMouse = e.pointerType === 'mouse';
    if (!isMouse && this.stickId === -1 && e.clientX < window.innerWidth * STICK_ZONE) {
      this.stickId = e.pointerId;
      this.stickX = e.clientX;
      this.stickY = e.clientY;
      this.base.style.transform = `translate(${e.clientX}px, ${e.clientY}px)`;
      this.knob.style.transform = 'translate(-50%, -50%)';
      this.base.classList.add('on');
    } else if (this.lookId === -1) {
      this.lookId = e.pointerId;
      this.lastX = e.clientX;
      this.lastY = e.clientY;
    }
  }

  private move(e: PointerEvent): void {
    if (e.pointerId === this.stickId) {
      let dx = e.clientX - this.stickX;
      let dy = e.clientY - this.stickY;
      const d = Math.hypot(dx, dy);
      if (d > STICK_RADIUS) {
        dx = (dx / d) * STICK_RADIUS;
        dy = (dy / d) * STICK_RADIUS;
      }
      this.moveX = dx / STICK_RADIUS;
      this.moveY = -dy / STICK_RADIUS;
      this.knob.style.transform = `translate(calc(-50% + ${dx}px), calc(-50% + ${dy}px))`;
    } else if (e.pointerId === this.lookId) {
      this.lookDX += e.clientX - this.lastX;
      this.lookDY += e.clientY - this.lastY;
      this.lastX = e.clientX;
      this.lastY = e.clientY;
    }
  }

  private up(e: PointerEvent): void {
    this.active.delete(e.pointerId);
    if (e.pointerId === this.stickId) {
      this.stickId = -1;
      this.moveX = 0;
      this.moveY = 0;
      this.base.classList.remove('on');
    } else if (e.pointerId === this.lookId) {
      this.lookId = -1;
    }
  }

  private reset(): void {
    this.active.clear();
    this.keys.clear();
    this.stickId = -1;
    this.lookId = -1;
    this.moveX = 0;
    this.moveY = 0;
    this.lookDX = 0;
    this.lookDY = 0;
    this.jumpHeld = false;
    this.jumpQueued = false;
    this.base.classList.remove('on');
  }
}
