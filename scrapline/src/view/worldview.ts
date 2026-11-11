import * as THREE from 'three';
import { ENTITY_DEFS, EntityKind } from '../core/entities';
import { ITEMS } from '../core/items';
import { NODE_DEFS, NodeKind } from '../core/nodes';
import { Ev, Sim, TargetType, selectedItem } from '../core/sim';
import { heightAt } from '../core/world';
import { entityGeometry, flameGeometry, glowGeometry, nodeGeometry } from './props';

const NODE_KINDS = [NodeKind.Deadwood, NodeKind.Stone, NodeKind.Ore, NodeKind.Coal];
const ENTITY_KINDS = [EntityKind.Workbench, EntityKind.Crate, EntityKind.Wall, EntityKind.Campfire];
const ENTITY_CAPACITY: Record<number, number> = {
  [EntityKind.Workbench]: 64,
  [EntityKind.Crate]: 256,
  [EntityKind.Wall]: 1024,
  [EntityKind.Campfire]: 64,
};
const FLAME_CAPACITY = 64;
const WOBBLE_TIME = 0.2;

const GHOST_OK = 0x5fd0c5;
const GHOST_BAD = 0xef6f5e;

const m4 = new THREE.Matrix4();
const pos = new THREE.Vector3();
const quat = new THREE.Quaternion();
const scl = new THREE.Vector3();
const UP = new THREE.Vector3(0, 1, 0);
const euler = new THREE.Euler();

/**
 * Draws resource nodes, placed entities and the build ghost. Everything of one
 * kind is a single instanced draw call; instance buffers change only on events.
 */
export class WorldView {
  readonly group = new THREE.Group();
  private nodeMeshes = new Map<number, THREE.InstancedMesh>();
  /** Instance index of each node within its kind's mesh. */
  private nodeSlot: Uint16Array;
  private entityMeshes = new Map<number, THREE.InstancedMesh>();
  private entityGeos = new Map<number, THREE.BufferGeometry>();
  private flames: THREE.InstancedMesh;
  private glows: THREE.InstancedMesh;
  private flameX = new Float32Array(FLAME_CAPACITY);
  private flameY = new Float32Array(FLAME_CAPACITY);
  private flameZ = new Float32Array(FLAME_CAPACITY);
  private ghost: THREE.Mesh;
  private ghostMat: THREE.MeshBasicMaterial;
  private ghostKind = 0;
  private wobbleNode = -1;
  private wobbleLeft = 0;

  constructor(sim: Sim, material: THREE.Material) {
    const nodes = sim.nodes;
    this.nodeSlot = new Uint16Array(nodes.count);
    const perKind = new Map<number, number>();
    for (let i = 0; i < nodes.count; i++) {
      const k = nodes.kind[i];
      const n = perKind.get(k) ?? 0;
      this.nodeSlot[i] = n;
      perKind.set(k, n + 1);
    }
    for (const kind of NODE_KINDS) {
      const mesh = new THREE.InstancedMesh(nodeGeometry(kind), material, perKind.get(kind) ?? 0);
      // Totals are small enough to draw without per-instance culling.
      mesh.frustumCulled = false;
      this.nodeMeshes.set(kind, mesh);
      this.group.add(mesh);
    }
    for (let i = 0; i < nodes.count; i++) this.writeNode(sim, i, 1, 0);
    for (const mesh of this.nodeMeshes.values()) mesh.instanceMatrix.needsUpdate = true;

    for (const kind of ENTITY_KINDS) {
      const geo = entityGeometry(kind);
      const mesh = new THREE.InstancedMesh(geo, material, ENTITY_CAPACITY[kind]);
      mesh.frustumCulled = false;
      mesh.count = 0;
      this.entityGeos.set(kind, geo);
      this.entityMeshes.set(kind, mesh);
      this.group.add(mesh);
    }

    this.flames = new THREE.InstancedMesh(
      flameGeometry(),
      new THREE.MeshBasicMaterial({ color: 0xf8b84e }),
      FLAME_CAPACITY,
    );
    this.flames.frustumCulled = false;
    this.flames.count = 0;
    this.group.add(this.flames);

    this.glows = new THREE.InstancedMesh(
      glowGeometry(4.5),
      new THREE.MeshBasicMaterial({
        vertexColors: true,
        blending: THREE.AdditiveBlending,
        transparent: true,
        depthWrite: false,
        fog: false,
      }),
      FLAME_CAPACITY,
    );
    this.glows.frustumCulled = false;
    this.glows.count = 0;
    this.group.add(this.glows);

    this.ghostMat = new THREE.MeshBasicMaterial({
      color: GHOST_OK,
      transparent: true,
      opacity: 0.5,
      depthWrite: false,
    });
    this.ghost = new THREE.Mesh(this.entityGeos.get(EntityKind.Crate)!, this.ghostMat);
    this.ghost.visible = false;
    this.group.add(this.ghost);

    for (const kind of ENTITY_KINDS) this.rebuildEntities(sim, kind);
  }

  /** Applies this frame's sim events, then animates the few things that move. */
  sync(sim: Sim, dt: number, time: number, light: number): void {
    // Firelight reads strongly at night and only faintly in daylight.
    (this.glows.material as THREE.MeshBasicMaterial).color.setScalar(1 - 0.85 * light);

    const ev = sim.events;
    for (let i = 0; i < ev.length; i += 3) {
      const code = ev[i];
      if (code === Ev.Swing) {
        this.endWobble(sim);
        this.wobbleNode = ev[i + 1];
        this.wobbleLeft = WOBBLE_TIME;
      } else if (code === Ev.NodeGone) {
        if (this.wobbleNode === ev[i + 1]) this.wobbleNode = -1;
        this.writeNode(sim, ev[i + 1], 1, 0);
        this.nodeMeshes.get(sim.nodes.kind[ev[i + 1]])!.instanceMatrix.needsUpdate = true;
      } else if (code === Ev.Placed || code === Ev.Removed) {
        this.rebuildEntities(sim, ev[i + 2]);
      }
    }

    if (this.wobbleNode >= 0) {
      this.wobbleLeft -= dt;
      if (this.wobbleLeft <= 0) {
        this.endWobble(sim);
      } else {
        const t = this.wobbleLeft / WOBBLE_TIME;
        this.writeNode(sim, this.wobbleNode, 1 - 0.07 * Math.sin(t * Math.PI), 0.07 * Math.sin(t * Math.PI * 2));
        this.nodeMeshes.get(sim.nodes.kind[this.wobbleNode])!.instanceMatrix.needsUpdate = true;
      }
    }

    // Flames flicker; there are only ever a handful.
    const flames = this.flames;
    for (let i = 0; i < flames.count; i++) {
      const f = 0.85 + 0.2 * Math.sin(time * 11 + i * 2.1) + 0.1 * Math.sin(time * 23 + i);
      pos.set(this.flameX[i], this.flameY[i], this.flameZ[i]);
      quat.setFromAxisAngle(UP, time * 1.7 + i);
      scl.set(1, f, 1);
      flames.setMatrixAt(i, m4.compose(pos, quat, scl));
    }
    if (flames.count > 0) flames.instanceMatrix.needsUpdate = true;

    this.syncGhost(sim);
  }

  private endWobble(sim: Sim): void {
    if (this.wobbleNode < 0) return;
    this.writeNode(sim, this.wobbleNode, 1, 0);
    this.nodeMeshes.get(sim.nodes.kind[this.wobbleNode])!.instanceMatrix.needsUpdate = true;
    this.wobbleNode = -1;
  }

  /** Writes one node's instance matrix. Depleted nodes collapse to zero scale. */
  private writeNode(sim: Sim, i: number, squash: number, tilt: number): void {
    const n = sim.nodes;
    const s = n.hp[i] === 0 ? 0 : n.scale[i];
    pos.set(n.x[i], n.y[i] - 0.05, n.z[i]);
    euler.set(tilt, n.rot[i], 0, 'YXZ');
    quat.setFromEuler(euler);
    scl.set(s, s * squash, s);
    this.nodeMeshes.get(n.kind[i])!.setMatrixAt(this.nodeSlot[i], m4.compose(pos, quat, scl));
  }

  /** Rewrites the instance buffer for one entity kind. Runs only when that kind is placed or removed. */
  private rebuildEntities(sim: Sim, kind: number): void {
    const es = sim.entities;
    const mesh = this.entityMeshes.get(kind);
    if (!mesh) return;
    const isFire = ENTITY_DEFS[kind].heat > 0;
    let n = 0;
    scl.set(1, 1, 1);
    for (let id = 1; id < es.high && n < mesh.instanceMatrix.count; id++) {
      if (es.kind[id] !== kind) continue;
      pos.set(es.cx[id] + 0.5, es.y[id] - 0.04, es.cz[id] + 0.5);
      quat.setFromAxisAngle(UP, (es.rot[id] * Math.PI) / 2);
      mesh.setMatrixAt(n, m4.compose(pos, quat, scl));
      if (isFire && n < FLAME_CAPACITY) {
        this.flameX[n] = pos.x;
        this.flameY[n] = pos.y;
        this.flameZ[n] = pos.z;
        pos.y += 0.12;
        quat.identity();
        this.glows.setMatrixAt(n, m4.compose(pos, quat, scl));
      }
      n++;
    }
    mesh.count = n;
    mesh.instanceMatrix.needsUpdate = true;
    if (isFire) {
      this.flames.count = Math.min(n, FLAME_CAPACITY);
      this.glows.count = this.flames.count;
      this.glows.instanceMatrix.needsUpdate = true;
    }
  }

  /** Shows a translucent preview of the selected kit on the targeted cell. */
  private syncGhost(sim: Sim): void {
    const def = ITEMS[selectedItem(sim)];
    const t = sim.target;
    if (def.kind !== 'kit' || !def.places || t.type !== TargetType.Ground || sim.dead) {
      this.ghost.visible = false;
      return;
    }
    if (this.ghostKind !== def.places) {
      this.ghost.geometry = this.entityGeos.get(def.places)!;
      this.ghostKind = def.places;
    }
    this.ghost.visible = true;
    this.ghost.position.set(t.cx + 0.5, heightAt(sim.world, t.cx + 0.5, t.cz + 0.5) - 0.02, t.cz + 0.5);
    this.ghost.rotation.y = (sim.buildRot * Math.PI) / 2;
    this.ghostMat.color.setHex(t.buildable ? GHOST_OK : GHOST_BAD);
  }
}

/** Name shown under the reticle for whatever is targeted, or '' for nothing of interest. */
export function targetName(sim: Sim): string {
  const t = sim.target;
  if (t.type === TargetType.Node) return NODE_DEFS[sim.nodes.kind[t.id]].name;
  if (t.type === TargetType.Entity) return ENTITY_DEFS[sim.entities.kind[t.id]].name;
  return '';
}
