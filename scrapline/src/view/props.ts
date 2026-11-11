import * as THREE from 'three';
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js';
import { EntityKind } from '../core/entities';
import { NodeKind } from '../core/nodes';

// Shared palette for everything placed in the world.
const SLATE = 0x36424d;
const STEEL = 0x5d6b73;
const FROST = 0xd6dbd0;
const TEAL = 0x4f9c93;
const AMBER = 0xf2a93b;
const PALE_WOOD = 0xbfae90;
const BARK = 0x6b5d52;
const ROCK = 0x87929a;
const ROCK_DARK = 0x76828b;

const tmpColor = new THREE.Color();

/**
 * Prepares a primitive for the shared flat-shaded vertex-colour material:
 * un-indexed (so every face gets its own normal), one colour, no UVs.
 */
function part(geo: THREE.BufferGeometry, color: number): THREE.BufferGeometry {
  const g = geo.index ? geo.toNonIndexed() : geo;
  g.deleteAttribute('uv');
  g.computeVertexNormals();
  const n = g.getAttribute('position').count;
  const col = new Float32Array(n * 3);
  tmpColor.set(color);
  for (let i = 0; i < n; i++) {
    col[i * 3] = tmpColor.r;
    col[i * 3 + 1] = tmpColor.g;
    col[i * 3 + 2] = tmpColor.b;
  }
  g.setAttribute('color', new THREE.BufferAttribute(col, 3));
  return g;
}

function box(w: number, h: number, d: number, x: number, y: number, z: number, color: number): THREE.BufferGeometry {
  return part(new THREE.BoxGeometry(w, h, d).translate(x, y, z), color);
}

function join(parts: THREE.BufferGeometry[]): THREE.BufferGeometry {
  const merged = mergeGeometries(parts, false);
  if (!merged) throw new Error('Could not merge prop geometry');
  for (const p of parts) p.dispose();
  return merged;
}

/** Low-poly geometry per resource node kind, built at unit scale with its base at y = 0. */
export function nodeGeometry(kind: number): THREE.BufferGeometry {
  switch (kind) {
    case NodeKind.Deadwood:
      return join([
        part(new THREE.CylinderGeometry(0.16, 0.3, 2.6, 5).translate(0, 1.3, 0), BARK),
        part(new THREE.ConeGeometry(0.16, 0.9, 5).translate(0, 3.05, 0), FROST),
        part(new THREE.CylinderGeometry(0.05, 0.09, 1.1, 4).translate(0, 0.55, 0).rotateZ(-0.95).translate(0.1, 1.7, 0), 0x7d6f63),
        part(new THREE.CylinderGeometry(0.04, 0.08, 0.9, 4).translate(0, 0.45, 0).rotateZ(0.85).rotateY(1.1).translate(-0.08, 2.15, 0), 0x7d6f63),
      ]);
    case NodeKind.Stone:
      return join([
        part(new THREE.IcosahedronGeometry(0.85, 0).scale(1, 0.62, 0.9).translate(0, 0.42, 0), ROCK),
        part(new THREE.IcosahedronGeometry(0.42, 0).scale(1, 0.7, 1).translate(0.7, 0.2, 0.35), ROCK_DARK),
      ]);
    case NodeKind.Ore:
      return join([
        part(new THREE.DodecahedronGeometry(0.85, 0).scale(1, 0.72, 0.95).translate(0, 0.5, 0), 0x4a5560),
        part(new THREE.OctahedronGeometry(0.26, 0).translate(0.45, 0.85, 0.3), 0xc97b4f),
        part(new THREE.OctahedronGeometry(0.22, 0).translate(-0.5, 0.7, -0.2), 0xc97b4f),
        part(new THREE.OctahedronGeometry(0.2, 0).translate(0.05, 1.02, -0.35), 0xd9925f),
      ]);
    default:
      return join([
        part(new THREE.DodecahedronGeometry(0.8, 0).scale(1.1, 0.6, 0.9).translate(0, 0.42, 0), 0x1f2428),
        part(new THREE.TetrahedronGeometry(0.4, 0).translate(0.55, 0.3, 0.45), 0x2c3338),
        part(new THREE.TetrahedronGeometry(0.34, 0).translate(-0.6, 0.26, 0.3), 0x2c3338),
      ]);
  }
}

/** Low-poly geometry per entity kind, centred on its build cell with its base at y = 0. */
export function entityGeometry(kind: number): THREE.BufferGeometry {
  switch (kind) {
    case EntityKind.Workbench:
      return join([
        box(0.95, 0.1, 0.72, 0, 0.9, 0, PALE_WOOD),
        box(0.08, 0.85, 0.08, -0.4, 0.425, -0.28, SLATE),
        box(0.08, 0.85, 0.08, 0.4, 0.425, -0.28, SLATE),
        box(0.08, 0.85, 0.08, -0.4, 0.425, 0.28, SLATE),
        box(0.08, 0.85, 0.08, 0.4, 0.425, 0.28, SLATE),
        box(0.84, 0.05, 0.6, 0, 0.3, 0, STEEL),
        box(0.2, 0.16, 0.2, 0.28, 1.03, 0.14, AMBER),
      ]);
    case EntityKind.Crate:
      return join([
        box(0.84, 0.62, 0.84, 0, 0.31, 0, 0x3f525c),
        box(0.9, 0.14, 0.9, 0, 0.69, 0, TEAL),
        box(0.22, 0.1, 0.04, 0, 0.5, 0.43, FROST),
      ]);
    case EntityKind.Wall:
      return join([
        box(1.0, 0.25, 0.46, 0, 0.125, 0, SLATE),
        box(1.0, 2.05, 0.36, 0, 1.275, 0, STEEL),
        box(1.0, 0.12, 0.46, 0, 2.36, 0, FROST),
      ]);
    default: {
      const parts: THREE.BufferGeometry[] = [
        box(0.72, 0.1, 0.12, 0, 0.12, 0, 0x5a4a3e).rotateY(0.6),
        box(0.72, 0.1, 0.12, 0, 0.2, 0, 0x4d4036).rotateY(-0.7),
      ];
      for (let i = 0; i < 6; i++) {
        const a = (i / 6) * Math.PI * 2;
        parts.push(
          part(
            new THREE.IcosahedronGeometry(0.15, 0).translate(Math.cos(a) * 0.4, 0.08, Math.sin(a) * 0.4),
            i % 2 ? ROCK : ROCK_DARK,
          ),
        );
      }
      return join(parts);
    }
  }
}

/** Flame for fires: drawn unlit so it glows at night. Base at y = 0. */
export function flameGeometry(): THREE.BufferGeometry {
  return new THREE.ConeGeometry(0.2, 0.6, 5).translate(0, 0.5, 0);
}

/**
 * Soft pool of firelight on the ground: a disc whose vertex colours fade from
 * warm at the centre to black at the rim, drawn with additive blending.
 */
export function glowGeometry(radius: number): THREE.BufferGeometry {
  const g = new THREE.CircleGeometry(radius, 20).rotateX(-Math.PI / 2);
  const n = g.getAttribute('position').count;
  const col = new Float32Array(n * 3);
  // Vertex 0 is the centre; the rest lie on the rim and stay black.
  col[0] = 0.42;
  col[1] = 0.24;
  col[2] = 0.08;
  g.setAttribute('color', new THREE.BufferAttribute(col, 3));
  return g;
}
