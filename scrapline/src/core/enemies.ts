import { EntityKind } from './entities';
import type { Sim } from './sim';
import { WORLD_SIZE, heightAt } from './world';

export const MAX_STRAYS = 24;
export const STRAY_HP = 100;
const STRAY_SPEED = 3.2;
const STRAY_HOVER = 1.25;
const STRAY_REACH = 1.7;
const STRAY_DAMAGE = 50;
const STRAY_ATTACK_TICKS = 20;
const SPAWN_EVERY = 100;
const SPAWN_RING = 42;

export const TURRET_RANGE = 12;
const TURRET_DAMAGE = 25;
const TURRET_PERIOD = 6;

/** Hostile drones. Fixed pool, struct-of-arrays; `alive[i] === 0` slots are free. */
export interface Strays {
  alive: Uint8Array;
  x: Float32Array;
  y: Float32Array;
  z: Float32Array;
  hp: Int16Array;
  cooldown: Uint8Array;
  /** How many are alive right now. */
  count: number;
  /** How many have been spawned during the current night. */
  spawned: number;
}

export function createStrays(): Strays {
  return {
    alive: new Uint8Array(MAX_STRAYS),
    x: new Float32Array(MAX_STRAYS),
    y: new Float32Array(MAX_STRAYS),
    z: new Float32Array(MAX_STRAYS),
    hp: new Int16Array(MAX_STRAYS),
    cooldown: new Uint8Array(MAX_STRAYS),
    count: 0,
    spawned: 0,
  };
}

/** How many strays may be alive at once, and in total, on a given night (0-based). */
export function nightBudget(night: number): { atOnce: number; total: number } {
  return { atOnce: Math.min(3 + night, 10), total: Math.min(8 + night * 4, 40) };
}

export function spawnStray(sim: Sim, x: number, z: number): number {
  const s = sim.strays;
  for (let i = 0; i < MAX_STRAYS; i++) {
    if (s.alive[i]) continue;
    s.alive[i] = 1;
    s.x[i] = Math.min(WORLD_SIZE - 4, Math.max(4, x));
    s.z[i] = Math.min(WORLD_SIZE - 4, Math.max(4, z));
    s.y[i] = heightAt(sim.world, s.x[i], s.z[i]) + STRAY_HOVER;
    s.hp[i] = STRAY_HP;
    s.cooldown[i] = STRAY_ATTACK_TICKS;
    s.count++;
    return i;
  }
  return -1;
}

/** Applies damage. Returns true if the stray was destroyed. */
export function damageStray(sim: Sim, i: number, amount: number): boolean {
  const s = sim.strays;
  if (!s.alive[i]) return false;
  s.hp[i] -= amount;
  if (s.hp[i] > 0) return false;
  s.alive[i] = 0;
  s.count--;
  return true;
}

/** Index of the nearest living stray within `range` of a point, or -1. */
export function nearestStray(sim: Sim, x: number, z: number, range: number): number {
  const s = sim.strays;
  let best = -1;
  let bestD = range * range;
  for (let i = 0; i < MAX_STRAYS; i++) {
    if (!s.alive[i]) continue;
    const dx = s.x[i] - x;
    const dz = s.z[i] - z;
    const d = dx * dx + dz * dz;
    if (d < bestD) {
      bestD = d;
      best = i;
    }
  }
  return best;
}

/**
 * One tick of the night threat: spawning, chasing, biting, and turrets shooting.
 * `onShot`, `onDown` and `onBite` report what happened so the sim can emit events.
 */
export function tickStrays(
  sim: Sim,
  night: boolean,
  nightIndex: number,
  onShot: (turret: number, stray: number) => void,
  onDown: (stray: number, byTurret: boolean) => void,
  onBite: () => void,
): void {
  const s = sim.strays;
  const p = sim.player;

  if (night) {
    const budget = nightBudget(nightIndex);
    if (sim.tick % SPAWN_EVERY === 0 && s.count < budget.atOnce && s.spawned < budget.total) {
      const a = sim.rng.range(0, Math.PI * 2);
      if (spawnStray(sim, p.x + Math.cos(a) * SPAWN_RING, p.z + Math.sin(a) * SPAWN_RING) >= 0) s.spawned++;
    }
  } else if (s.count > 0) {
    // Daylight shuts them down.
    for (let i = 0; i < MAX_STRAYS; i++) {
      if (!s.alive[i]) continue;
      s.alive[i] = 0;
      s.count--;
      onDown(i, false);
    }
    s.spawned = 0;
  } else {
    s.spawned = 0;
  }

  for (let i = 0; i < MAX_STRAYS; i++) {
    if (!s.alive[i]) continue;
    const dx = p.x - s.x[i];
    const dz = p.z - s.z[i];
    const d = Math.hypot(dx, dz);
    if (d > STRAY_REACH * 0.8) {
      const step = STRAY_SPEED / 20;
      s.x[i] += (dx / d) * step;
      s.z[i] += (dz / d) * step;
    }
    s.y[i] = heightAt(sim.world, s.x[i], s.z[i]) + STRAY_HOVER;
    if (s.cooldown[i] > 0) s.cooldown[i]--;
    if (d < STRAY_REACH && s.cooldown[i] === 0 && !sim.dead) {
      s.cooldown[i] = STRAY_ATTACK_TICKS;
      sim.vitals.health = Math.max(0, sim.vitals.health - STRAY_DAMAGE);
      onBite();
    }
  }

  // Turrets fire only while their `fire` input is high.
  const es = sim.entities;
  for (let id = 1; id < es.high; id++) {
    if (es.kind[id] !== EntityKind.Turret) continue;
    let firing = 0;
    if (es.enabled[id] && s.count > 0) {
      const t = nearestStray(sim, es.cx[id] + 0.5, es.cz[id] + 0.5, TURRET_RANGE);
      if (t >= 0) {
        firing = 1;
        if (++es.progress[id] >= TURRET_PERIOD) {
          es.progress[id] = 0;
          onShot(id, t);
          if (damageStray(sim, t, TURRET_DAMAGE)) onDown(t, true);
        }
      }
    }
    es.active[id] = firing;
  }
}
