import { describe, expect, it } from 'vitest';
import { MoveIntent, createPlayer, updatePlayer } from '../src/core/player';
import { CELL, GRID_N, WORLD_SIZE, createWorld, heightAt } from '../src/core/world';

const idle = (): MoveIntent => ({ moveX: 0, moveY: 0, sprint: false, jump: false, lookYaw: 0, lookPitch: 0 });

describe('world', () => {
  it('is deterministic for a seed', () => {
    const a = createWorld(42);
    const b = createWorld(42);
    const c = createWorld(43);
    expect(a.heights).toEqual(b.heights);
    expect(a.heights).not.toEqual(c.heights);
  });

  it('interpolates exactly at lattice points and stays in range inside a cell', () => {
    const w = createWorld(7);
    const ix = 20;
    const iz = 31;
    const i = iz * GRID_N + ix;
    expect(heightAt(w, ix * CELL, iz * CELL)).toBeCloseTo(w.heights[i], 5);
    const corners = [w.heights[i], w.heights[i + 1], w.heights[i + GRID_N], w.heights[i + GRID_N + 1]];
    const h = heightAt(w, ix * CELL + 0.7, iz * CELL + 1.6);
    expect(h).toBeGreaterThanOrEqual(Math.min(...corners) - 1e-4);
    expect(h).toBeLessThanOrEqual(Math.max(...corners) + 1e-4);
  });

  it('has a rim that is much higher than the basin', () => {
    const w = createWorld(20261111);
    const centre = heightAt(w, WORLD_SIZE / 2, WORLD_SIZE / 2);
    expect(heightAt(w, 2, WORLD_SIZE / 2) - centre).toBeGreaterThan(20);
    expect(heightAt(w, WORLD_SIZE / 2, WORLD_SIZE - 2) - centre).toBeGreaterThan(20);
  });
});

describe('player', () => {
  it('walks forward along -Z at yaw 0 and stays on the ground', () => {
    const w = createWorld(20261111);
    const p = createPlayer(w);
    const z0 = p.z;
    const intent = idle();
    intent.moveY = 1;
    for (let i = 0; i < 60; i++) updatePlayer(p, w, intent, 1 / 60);
    expect(z0 - p.z).toBeGreaterThan(3);
    expect(Math.abs(p.x - WORLD_SIZE / 2)).toBeLessThan(0.01);
    expect(p.y).toBeCloseTo(heightAt(w, p.x, p.z), 3);
    expect(p.onGround).toBe(true);
  });

  it('strafes to the right of the facing direction', () => {
    const w = createWorld(20261111);
    const p = createPlayer(w);
    const x0 = p.x;
    const intent = idle();
    intent.moveX = 1;
    for (let i = 0; i < 30; i++) updatePlayer(p, w, intent, 1 / 60);
    expect(p.x - x0).toBeGreaterThan(1);
  });

  it('jumps and lands again', () => {
    const w = createWorld(20261111);
    const p = createPlayer(w);
    const intent = idle();
    intent.jump = true;
    updatePlayer(p, w, intent, 1 / 60);
    intent.jump = false;
    expect(p.onGround).toBe(false);
    let peak = 0;
    for (let i = 0; i < 120; i++) {
      updatePlayer(p, w, intent, 1 / 60);
      peak = Math.max(peak, p.y - heightAt(w, p.x, p.z));
    }
    expect(peak).toBeGreaterThan(0.8);
    expect(p.onGround).toBe(true);
  });

  it('cannot leave the map or climb the rim', () => {
    const w = createWorld(20261111);
    const p = createPlayer(w);
    const centre = p.y;
    const intent = idle();
    intent.moveY = 1;
    intent.sprint = true;
    for (let i = 0; i < 60 * 60; i++) updatePlayer(p, w, intent, 1 / 60);
    expect(p.z).toBeGreaterThanOrEqual(2);
    expect(p.y - centre).toBeLessThan(30);
  });

  it('clamps pitch', () => {
    const w = createWorld(1);
    const p = createPlayer(w);
    const intent = idle();
    intent.lookPitch = 5;
    updatePlayer(p, w, intent, 1 / 60);
    expect(p.pitch).toBeLessThan(Math.PI / 2);
  });
});
