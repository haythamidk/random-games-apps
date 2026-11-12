import * as THREE from 'three';
import { hash2 } from '../core/rng';
import { Item } from '../core/items';
import { Deposit } from '../core/nodes';
import { CELL, GRID_N, WORLD_SIZE, World } from '../core/world';

export const CHUNK_SIZE = 32;
const CHUNKS_PER_SIDE = WORLD_SIZE / CHUNK_SIZE;
const CELLS_PER_CHUNK = CHUNK_SIZE / CELL;

// Cold palette: frost flats, lichen lows, slate rock.
const FROST = new THREE.Color(0xd6dbd0);
const LICHEN = new THREE.Color(0x4f7c74);
const SLATE = new THREE.Color(0x36424d);
const RIDGE = new THREE.Color(0x8e9aa0);
// Deposits stain the ground so they can be found from a distance.
const ORE_STAIN = new THREE.Color(0x8f5b42);
const COAL_STAIN = new THREE.Color(0x24292d);

export interface Terrain {
  group: THREE.Group;
  chunks: THREE.Mesh[];
  material: THREE.Material;
}

/**
 * Builds the terrain as CHUNKS_PER_SIDE² flat-shaded meshes that share one
 * vertex-colour material. Each chunk is frustum culled on its own.
 */
export function buildTerrain(world: World, deposits: readonly Deposit[]): Terrain {
  const material = new THREE.MeshLambertMaterial({ vertexColors: true });
  const group = new THREE.Group();
  const chunks: THREE.Mesh[] = [];
  const vertsPerChunk = CELLS_PER_CHUNK * CELLS_PER_CHUNK * 6;
  const color = new THREE.Color();
  const a = new THREE.Vector3();
  const b = new THREE.Vector3();
  const c = new THREE.Vector3();
  const ab = new THREE.Vector3();
  const ac = new THREE.Vector3();
  const n = new THREE.Vector3();

  const h = (ix: number, iz: number) => world.heights[iz * GRID_N + ix];

  for (let cz = 0; cz < CHUNKS_PER_SIDE; cz++) {
    for (let cx = 0; cx < CHUNKS_PER_SIDE; cx++) {
      const pos = new Float32Array(vertsPerChunk * 3);
      const nor = new Float32Array(vertsPerChunk * 3);
      const col = new Float32Array(vertsPerChunk * 3);
      let v = 0;

      const tri = (
        ax: number, az: number, bx: number, bz: number, cx2: number, cz2: number, faceSeed: number,
      ) => {
        a.set(ax * CELL, h(ax, az), az * CELL);
        b.set(bx * CELL, h(bx, bz), bz * CELL);
        c.set(cx2 * CELL, h(cx2, cz2), cz2 * CELL);
        ab.subVectors(b, a);
        ac.subVectors(c, a);
        n.crossVectors(ab, ac).normalize();

        const avgH = (a.y + b.y + c.y) / 3;
        const steep = 1 - n.y; // 0 flat .. 1 vertical
        const jitter = hash2(ax * 2 + faceSeed, az, world.seed + 31) - 0.5;
        // Low ground grows lichen, flats carry frost, steep faces show rock.
        const lowT = THREE.MathUtils.clamp((1.5 - avgH) / 4, 0, 1);
        color.copy(FROST).lerp(LICHEN, lowT * 0.85);
        const rockT = THREE.MathUtils.smoothstep(steep, 0.12, 0.42);
        color.lerp(SLATE, rockT);
        const highT = THREE.MathUtils.clamp((avgH - 18) / 14, 0, 1);
        color.lerp(RIDGE, highT * 0.6);
        color.offsetHSL(0, 0, jitter * 0.07);
        const mx = (a.x + b.x + c.x) / 3;
        const mz = (a.z + b.z + c.z) / 3;
        for (const d of deposits) {
          const dist = Math.hypot(mx - d.x, mz - d.z);
          if (dist < d.radius + 0.8) {
            color.lerp(d.item === Item.IronOre ? ORE_STAIN : COAL_STAIN, dist < d.radius ? 0.85 : 0.4);
          }
        }

        for (const p of [a, b, c]) {
          pos[v * 3] = p.x;
          pos[v * 3 + 1] = p.y;
          pos[v * 3 + 2] = p.z;
          nor[v * 3] = n.x;
          nor[v * 3 + 1] = n.y;
          nor[v * 3 + 2] = n.z;
          col[v * 3] = color.r;
          col[v * 3 + 1] = color.g;
          col[v * 3 + 2] = color.b;
          v++;
        }
      };

      for (let j = 0; j < CELLS_PER_CHUNK; j++) {
        for (let i = 0; i < CELLS_PER_CHUNK; i++) {
          const ix = cx * CELLS_PER_CHUNK + i;
          const iz = cz * CELLS_PER_CHUNK + j;
          // Same diagonal as core/world.heightAt: split from (ix+1, iz) to (ix, iz+1).
          tri(ix, iz, ix, iz + 1, ix + 1, iz, 0);
          tri(ix + 1, iz + 1, ix + 1, iz, ix, iz + 1, 1);
        }
      }

      const geo = new THREE.BufferGeometry();
      geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
      geo.setAttribute('normal', new THREE.BufferAttribute(nor, 3));
      geo.setAttribute('color', new THREE.BufferAttribute(col, 3));
      geo.computeBoundingSphere();
      const mesh = new THREE.Mesh(geo, material);
      mesh.matrixAutoUpdate = false;
      group.add(mesh);
      chunks.push(mesh);
    }
  }
  return { group, chunks, material };
}
