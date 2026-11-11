import { World, WORLD_SIZE, heightAt } from './world';

export const EYE_HEIGHT = 1.62;
const WALK_SPEED = 4.2;
const SPRINT_SPEED = 6.8;
const ACCEL = 28;
const GRAVITY = 22;
const JUMP_SPEED = 7.2;
/** Steepest slope (rise over run) the player can walk up. */
const MAX_CLIMB = 1.15;
const BORDER = 2;
const PITCH_LIMIT = 1.45;

export interface Player {
  x: number;
  /** Feet height. */
  y: number;
  z: number;
  vx: number;
  vy: number;
  vz: number;
  /** Radians; 0 looks toward -Z, positive turns left. */
  yaw: number;
  pitch: number;
  onGround: boolean;
}

/** What the input layer asks the player to do this frame. */
export interface MoveIntent {
  /** Strafe, -1 (left) to 1 (right). */
  moveX: number;
  /** Forward, -1 (back) to 1 (forward). */
  moveY: number;
  sprint: boolean;
  jump: boolean;
  /** Look deltas in radians, already scaled by sensitivity. */
  lookYaw: number;
  lookPitch: number;
}

export function createPlayer(w: World): Player {
  const x = WORLD_SIZE / 2;
  const z = WORLD_SIZE / 2;
  return { x, y: heightAt(w, x, z), z, vx: 0, vy: 0, vz: 0, yaw: 0, pitch: 0, onGround: true };
}

/**
 * Player kinematics. Runs once per rendered frame (not on the 20 Hz sim tick)
 * so that touch input has no added latency; `dt` is clamped by the caller.
 */
export function updatePlayer(p: Player, w: World, intent: MoveIntent, dt: number): void {
  p.yaw += intent.lookYaw;
  p.pitch += intent.lookPitch;
  if (p.pitch > PITCH_LIMIT) p.pitch = PITCH_LIMIT;
  else if (p.pitch < -PITCH_LIMIT) p.pitch = -PITCH_LIMIT;

  let mx = intent.moveX;
  let my = intent.moveY;
  const len = Math.hypot(mx, my);
  if (len > 1) {
    mx /= len;
    my /= len;
  }
  const speed = intent.sprint ? SPRINT_SPEED : WALK_SPEED;
  const sin = Math.sin(p.yaw);
  const cos = Math.cos(p.yaw);
  // Forward is (-sin, -cos); right is (cos, -sin).
  const targetVx = (-sin * my + cos * mx) * speed;
  const targetVz = (-cos * my - sin * mx) * speed;

  const maxDelta = ACCEL * dt * (p.onGround ? 1 : 0.35);
  const dvx = targetVx - p.vx;
  const dvz = targetVz - p.vz;
  const dv = Math.hypot(dvx, dvz);
  if (dv <= maxDelta || dv === 0) {
    p.vx = targetVx;
    p.vz = targetVz;
  } else {
    p.vx += (dvx / dv) * maxDelta;
    p.vz += (dvz / dv) * maxDelta;
  }

  if (intent.jump && p.onGround) {
    p.vy = JUMP_SPEED;
    p.onGround = false;
  }

  // Horizontal move, one axis at a time so the player slides along steep ground.
  const lo = BORDER;
  const hi = WORLD_SIZE - BORDER;
  const stepX = p.vx * dt;
  if (stepX !== 0) {
    let nx = p.x + stepX;
    if (nx < lo) nx = lo;
    else if (nx > hi) nx = hi;
    if (canStep(p, w, nx, p.z)) p.x = nx;
    else p.vx = 0;
  }
  const stepZ = p.vz * dt;
  if (stepZ !== 0) {
    let nz = p.z + stepZ;
    if (nz < lo) nz = lo;
    else if (nz > hi) nz = hi;
    if (canStep(p, w, p.x, nz)) p.z = nz;
    else p.vz = 0;
  }

  // Vertical.
  const ground = heightAt(w, p.x, p.z);
  if (p.onGround) {
    // Stick to the ground when walking downhill instead of micro-falling.
    if (ground >= p.y - 0.45) {
      p.y = ground;
      p.vy = 0;
    } else {
      p.onGround = false;
    }
  }
  if (!p.onGround) {
    p.vy -= GRAVITY * dt;
    p.y += p.vy * dt;
    if (p.y <= ground) {
      p.y = ground;
      p.vy = 0;
      p.onGround = true;
    }
  }
}

function canStep(p: Player, w: World, nx: number, nz: number): boolean {
  const run = Math.hypot(nx - p.x, nz - p.z);
  if (run < 1e-6) return true;
  const rise = heightAt(w, nx, nz) - p.y;
  // Airborne players may always move where they clear the ground.
  if (!p.onGround) return rise <= 0.3;
  return rise / run <= MAX_CLIMB;
}
