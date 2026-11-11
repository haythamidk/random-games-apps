import { hash2 } from './rng';

/** World is a square of WORLD_SIZE metres on X and Z, origin at one corner. */
export const WORLD_SIZE = 256;
/** Terrain lattice spacing in metres. Collision and rendering share the same triangles. */
export const CELL = 2;
/** Lattice points per side. */
export const GRID_N = WORLD_SIZE / CELL + 1;

export interface World {
  seed: number;
  /** Lattice heights, row-major: index = iz * GRID_N + ix. */
  heights: Float32Array;
}

function smooth(t: number): number {
  return t * t * (3 - 2 * t);
}

function valueNoise(x: number, z: number, seed: number): number {
  const xi = Math.floor(x);
  const zi = Math.floor(z);
  const fx = smooth(x - xi);
  const fz = smooth(z - zi);
  const a = hash2(xi, zi, seed);
  const b = hash2(xi + 1, zi, seed);
  const c = hash2(xi, zi + 1, seed);
  const d = hash2(xi + 1, zi + 1, seed);
  return a + (b - a) * fx + (c - a) * fz + (a - b - c + d) * fx * fz;
}

function fbm(x: number, z: number, seed: number): number {
  let sum = 0;
  let amp = 0.5;
  let freq = 1;
  for (let o = 0; o < 4; o++) {
    sum += amp * valueNoise(x * freq, z * freq, seed + o * 101);
    amp *= 0.5;
    freq *= 2;
  }
  return sum; // roughly 0..0.94
}

/**
 * Builds the map: a gently rolling basin in the middle, ringed by a steep rim
 * that acts as the natural world boundary.
 */
export function createWorld(seed: number): World {
  const heights = new Float32Array(GRID_N * GRID_N);
  const half = WORLD_SIZE / 2;
  for (let iz = 0; iz < GRID_N; iz++) {
    for (let ix = 0; ix < GRID_N; ix++) {
      const x = ix * CELL;
      const z = iz * CELL;
      const rolling = (fbm(x / 48, z / 48, seed) - 0.45) * 9;
      const detail = (valueNoise(x / 9, z / 9, seed + 977) - 0.5) * 0.7;
      // Distance from centre as a 0..1 "squircle" so the rim follows the square map.
      const dx = Math.abs(x - half) / half;
      const dz = Math.abs(z - half) / half;
      const edge = Math.pow(Math.pow(dx, 4) + Math.pow(dz, 4), 0.25);
      const rimT = Math.min(1, Math.max(0, (edge - 0.72) / 0.28));
      const rim = smooth(rimT) * 34;
      // Flatten a landing pad around the spawn point so the first minute is easy to read.
      const dc = Math.hypot(x - half, z - half);
      const pad = 1 - Math.min(1, Math.max(0, (dc - 6) / 14));
      const h = (rolling + detail) * (1 - 0.85 * smooth(pad)) + rim;
      heights[iz * GRID_N + ix] = h;
    }
  }
  return { seed, heights };
}

/**
 * Terrain height at a point, interpolated across the same two triangles per cell
 * that the renderer draws, so the player's feet never sink or float.
 */
export function heightAt(w: World, x: number, z: number): number {
  const max = WORLD_SIZE - 1e-4;
  const cx = x < 0 ? 0 : x > max ? max : x;
  const cz = z < 0 ? 0 : z > max ? max : z;
  const gx = cx / CELL;
  const gz = cz / CELL;
  const ix = Math.floor(gx);
  const iz = Math.floor(gz);
  const fx = gx - ix;
  const fz = gz - iz;
  const i = iz * GRID_N + ix;
  const h00 = w.heights[i];
  const h10 = w.heights[i + 1];
  const h01 = w.heights[i + GRID_N];
  const h11 = w.heights[i + GRID_N + 1];
  if (fx + fz <= 1) return h00 + (h10 - h00) * fx + (h01 - h00) * fz;
  return h11 + (h01 - h11) * (1 - fx) + (h10 - h11) * (1 - fz);
}

/** Steepness at a point as rise over run, sampled over half a metre. */
export function slopeAt(w: World, x: number, z: number): number {
  const e = 0.5;
  const dx = heightAt(w, x + e, z) - heightAt(w, x - e, z);
  const dz = heightAt(w, x, z + e) - heightAt(w, x, z - e);
  return Math.hypot(dx, dz) / (2 * e);
}
