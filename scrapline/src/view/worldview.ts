import * as THREE from 'three';
import { MAX_STRAYS } from '../core/enemies';
import { BELT_SLOTS, DIR_X, DIR_Z, ENTITY_DEFS, EntityKind, MAX_ENTITIES } from '../core/entities';
import { ITEMS, Item } from '../core/items';
import { NodeType, wireDstNode, wireSrcNode } from '../core/logic/graph';
import { BELT_PERIOD } from '../core/machines';
import { NODE_DEFS, NodeKind } from '../core/nodes';
import { Ev, Sim, TargetType, selectedItem } from '../core/sim';
import { heightAt } from '../core/world';
import {
  beltItemGeometry, entityGeometry, flameGeometry, glowGeometry, indicatorGeometry, nodeGeometry, strayGeometry,
} from './props';

const NODE_KINDS = [NodeKind.Deadwood, NodeKind.Stone, NodeKind.Ore, NodeKind.Coal];
/** Instance capacity per entity kind. */
const ENTITY_CAPACITY: Record<number, number> = {
  [EntityKind.Workbench]: 32,
  [EntityKind.Crate]: 128,
  [EntityKind.Wall]: 1024,
  [EntityKind.Campfire]: 32,
  [EntityKind.Smelter]: 64,
  [EntityKind.Drill]: 64,
  [EntityKind.Belt]: 1024,
  [EntityKind.Sensor]: 128,
  [EntityKind.Turret]: 64,
  [EntityKind.Lamp]: 128,
  [EntityKind.Switch]: 128,
};
const ENTITY_KINDS = Object.keys(ENTITY_CAPACITY).map(Number);

const FLAME_CAPACITY = 96;
const GLOW_CAPACITY = 160;
const INDICATOR_CAPACITY = 512;
const BELT_ITEM_CAPACITY = 2048;
const TRACER_CAPACITY = 16;
const WIRE_CAPACITY = 512;
const WOBBLE_TIME = 0.2;
const TRACER_TIME = 0.09;
/** Belt items farther than this from the player are not drawn. */
const BELT_ITEM_RANGE = 40;

const GHOST_OK = 0x5fd0c5;
const GHOST_BAD = 0xef6f5e;

const AMBER = new THREE.Color(0xffc266);
const TEAL = new THREE.Color(0x6fe6da);
const CORAL = new THREE.Color(0xff6b57);

const ITEM_COLORS: Record<number, THREE.Color> = {
  [Item.Wood]: new THREE.Color(0x8a6a4c),
  [Item.Stone]: new THREE.Color(0x8d979c),
  [Item.IronOre]: new THREE.Color(0xa56a4c),
  [Item.Coal]: new THREE.Color(0x1d2226),
  [Item.IronIngot]: new THREE.Color(0xc3ced4),
  [Item.Gear]: new THREE.Color(0xaab6bd),
  [Item.Circuit]: new THREE.Color(0x2f7d6d),
};
const ITEM_COLOR_OTHER = new THREE.Color(0xbfae90);

const m4 = new THREE.Matrix4();
const pos = new THREE.Vector3();
const quat = new THREE.Quaternion();
const scl = new THREE.Vector3();
const UP = new THREE.Vector3(0, 1, 0);
const euler = new THREE.Euler();
const IDENTITY = new THREE.Quaternion();

/**
 * Draws everything that lives in the world besides terrain: resource nodes,
 * entities, belt cargo, strays, lights and the build ghost. Each kind is one
 * instanced draw call. Static instance buffers change only on events; the
 * small dynamic ones (cargo, lights, strays) are rewritten every frame.
 */
export class WorldView {
  readonly group = new THREE.Group();
  private nodeMeshes = new Map<number, THREE.InstancedMesh>();
  /** Instance index of each node within its kind's mesh. */
  private nodeSlot: Uint16Array;
  private entityMeshes = new Map<number, THREE.InstancedMesh>();
  private entityGeos = new Map<number, THREE.BufferGeometry>();
  private entityVersion = -1;
  /** Entity ids that can show a light or flame, and belt ids, cached per entity version. */
  private litIds = new Uint16Array(MAX_ENTITIES);
  private litCount = 0;
  private beltIds = new Uint16Array(MAX_ENTITIES);
  private beltCount = 0;

  private flames: THREE.InstancedMesh;
  private glows: THREE.InstancedMesh;
  private indicators: THREE.InstancedMesh;
  private beltItems: THREE.InstancedMesh;
  private strays: THREE.InstancedMesh;
  private tracers: THREE.LineSegments;
  private tracerPos: Float32Array;
  private tracerLeft = new Float32Array(TRACER_CAPACITY);
  private tracerNext = 0;
  private wires: THREE.LineSegments;
  private wirePos: Float32Array;
  private wireVersion = -1;

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
      this.entityGeos.set(kind, geo);
      this.entityMeshes.set(kind, this.instanced(geo, material, ENTITY_CAPACITY[kind]));
    }

    this.strays = this.instanced(strayGeometry(), material, MAX_STRAYS);
    this.beltItems = this.instanced(beltItemGeometry(), new THREE.MeshLambertMaterial(), BELT_ITEM_CAPACITY);
    this.flames = this.instanced(flameGeometry(), new THREE.MeshBasicMaterial({ color: 0xf8b84e }), FLAME_CAPACITY);
    this.indicators = this.instanced(indicatorGeometry(), new THREE.MeshBasicMaterial(), INDICATOR_CAPACITY);
    this.glows = this.instanced(
      glowGeometry(4.5),
      new THREE.MeshBasicMaterial({
        vertexColors: true,
        blending: THREE.AdditiveBlending,
        transparent: true,
        depthWrite: false,
        fog: false,
      }),
      GLOW_CAPACITY,
    );
    // Per-instance colour buffers must exist before the first draw.
    this.beltItems.setColorAt(0, ITEM_COLOR_OTHER);
    this.indicators.setColorAt(0, AMBER);

    this.tracerPos = new Float32Array(TRACER_CAPACITY * 6);
    this.tracers = this.lines(this.tracerPos, 0xffd27a);
    this.wirePos = new Float32Array(WIRE_CAPACITY * 6);
    this.wires = this.lines(this.wirePos, 0x6fe6da);
    this.wires.visible = false;

    this.ghostMat = new THREE.MeshBasicMaterial({ color: GHOST_OK, transparent: true, opacity: 0.5, depthWrite: false });
    this.ghost = new THREE.Mesh(this.entityGeos.get(EntityKind.Crate)!, this.ghostMat);
    this.ghost.visible = false;
    this.group.add(this.ghost);
  }

  private instanced(geo: THREE.BufferGeometry, material: THREE.Material, capacity: number): THREE.InstancedMesh {
    const mesh = new THREE.InstancedMesh(geo, material, capacity);
    mesh.frustumCulled = false;
    mesh.count = 0;
    this.group.add(mesh);
    return mesh;
  }

  private lines(buffer: Float32Array, color: number): THREE.LineSegments {
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(buffer, 3));
    geo.setDrawRange(0, 0);
    const lines = new THREE.LineSegments(geo, new THREE.LineBasicMaterial({ color, fog: false }));
    lines.frustumCulled = false;
    this.group.add(lines);
    return lines;
  }

  /**
   * Applies this frame's sim events, then animates what moves.
   * `alpha` is how far the sim is into the current tick (0..1), for smooth belt cargo.
   */
  sync(sim: Sim, dt: number, time: number, light: number, alpha: number): void {
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
      } else if (code === Ev.Shot) {
        this.addTracer(sim, ev[i + 1], ev[i + 2]);
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

    if (this.entityVersion !== sim.entities.version) this.rebuildEntities(sim);
    this.syncLights(sim, time);
    this.syncBeltItems(sim, alpha);
    this.syncStrays(sim, time);
    this.syncTracers(dt);
    this.syncWires(sim);
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

  /** Rewrites every entity instance buffer. Runs only when something was placed or removed. */
  private rebuildEntities(sim: Sim): void {
    const es = sim.entities;
    for (const mesh of this.entityMeshes.values()) mesh.count = 0;
    this.litCount = 0;
    this.beltCount = 0;
    scl.set(1, 1, 1);
    for (let id = 1; id < es.high; id++) {
      const kind = es.kind[id];
      const mesh = this.entityMeshes.get(kind);
      if (!mesh || mesh.count >= mesh.instanceMatrix.count) continue;
      pos.set(es.cx[id] + 0.5, es.y[id] - 0.04, es.cz[id] + 0.5);
      quat.setFromAxisAngle(UP, (es.rot[id] * Math.PI) / 2);
      mesh.setMatrixAt(mesh.count++, m4.compose(pos, quat, scl));
      if (kind === EntityKind.Belt) this.beltIds[this.beltCount++] = id;
      else if (kind !== EntityKind.Workbench && kind !== EntityKind.Crate && kind !== EntityKind.Wall && kind !== EntityKind.Drill) {
        this.litIds[this.litCount++] = id;
      }
    }
    for (const mesh of this.entityMeshes.values()) mesh.instanceMatrix.needsUpdate = true;
    this.entityVersion = es.version;
    this.wireVersion = -1;
  }

  /** Flames, status lights and pools of light for whatever is burning, lit or triggered. */
  private syncLights(sim: Sim, time: number): void {
    const es = sim.entities;
    let flames = 0;
    let glows = 0;
    let lights = 0;
    const flame = (x: number, y: number, z: number, i: number) => {
      if (flames >= FLAME_CAPACITY) return;
      const f = 0.85 + 0.2 * Math.sin(time * 11 + i * 2.1) + 0.1 * Math.sin(time * 23 + i);
      pos.set(x, y, z);
      quat.setFromAxisAngle(UP, time * 1.7 + i);
      scl.set(1, f, 1);
      this.flames.setMatrixAt(flames++, m4.compose(pos, quat, scl));
    };
    const glow = (x: number, y: number, z: number, size: number) => {
      if (glows >= GLOW_CAPACITY) return;
      pos.set(x, y + 0.12, z);
      scl.set(size, 1, size);
      this.glows.setMatrixAt(glows++, m4.compose(pos, IDENTITY, scl));
    };
    const light = (x: number, y: number, z: number, color: THREE.Color, size: number) => {
      if (lights >= INDICATOR_CAPACITY) return;
      pos.set(x, y, z);
      scl.set(size, size, size);
      this.indicators.setMatrixAt(lights, m4.compose(pos, IDENTITY, scl));
      this.indicators.setColorAt(lights++, color);
    };

    for (let k = 0; k < this.litCount; k++) {
      const id = this.litIds[k];
      const kind = es.kind[id];
      const x = es.cx[id] + 0.5;
      const y = es.y[id];
      const z = es.cz[id] + 0.5;
      const on = es.active[id] === 1;
      if (kind === EntityKind.Campfire) {
        flame(x, y, z, id);
        glow(x, y, z, 1);
      } else if (!on) {
        continue;
      } else if (kind === EntityKind.Smelter) {
        flame(x, y + 1.45, z, id);
        glow(x, y, z, 0.8);
      } else if (kind === EntityKind.Lamp) {
        light(x, y + 2.0, z, AMBER, 2);
        glow(x, y, z, 1.5);
      } else if (kind === EntityKind.Switch) {
        light(x, y + 1.25, z, TEAL, 1);
      } else if (kind === EntityKind.Sensor) {
        light(x, y + 1.62, z, CORAL, 0.9);
      } else if (kind === EntityKind.Turret) {
        light(x, y + 1.12, z, CORAL, 1);
      }
    }

    // Stray eyes, so they can be seen coming in the dark.
    const st = sim.strays;
    if (st.count > 0) {
      for (let i = 0; i < MAX_STRAYS; i++) {
        if (st.alive[i]) light(st.x[i], st.y[i] + 0.16 + 0.08 * Math.sin(time * 4 + i), st.z[i], CORAL, 1.3);
      }
    }

    this.flames.count = flames;
    this.glows.count = glows;
    this.indicators.count = lights;
    if (flames) this.flames.instanceMatrix.needsUpdate = true;
    if (glows) this.glows.instanceMatrix.needsUpdate = true;
    if (lights) {
      this.indicators.instanceMatrix.needsUpdate = true;
      this.indicators.instanceColor!.needsUpdate = true;
    }
  }

  /** Cargo on belts near the player, eased between slots so it glides instead of hopping. */
  private syncBeltItems(sim: Sim, alpha: number): void {
    const es = sim.entities;
    const p = sim.player;
    const mesh = this.beltItems;
    const phase = Math.min(1, ((sim.tick % BELT_PERIOD) + alpha) / BELT_PERIOD);
    let n = 0;
    scl.set(1, 1, 1);
    for (let k = 0; k < this.beltCount && n < BELT_ITEM_CAPACITY; k++) {
      const id = this.beltIds[k];
      const bx = es.cx[id] + 0.5;
      const bz = es.cz[id] + 0.5;
      if (Math.abs(bx - p.x) > BELT_ITEM_RANGE || Math.abs(bz - p.z) > BELT_ITEM_RANGE) continue;
      const dx = DIR_X[es.rot[id]];
      const dz = DIR_Z[es.rot[id]];
      for (let s = 0; s < BELT_SLOTS; s++) {
        const item = es.belt[id * BELT_SLOTS + s];
        if (item === 0) continue;
        let t = (s + 0.5) / BELT_SLOTS - 0.5;
        if (es.beltMoved[id * BELT_SLOTS + s]) t -= (1 - phase) / BELT_SLOTS;
        pos.set(bx + dx * t, es.y[id] + 0.44, bz + dz * t);
        mesh.setMatrixAt(n, m4.compose(pos, IDENTITY, scl));
        mesh.setColorAt(n++, ITEM_COLORS[item] ?? ITEM_COLOR_OTHER);
      }
    }
    mesh.count = n;
    if (n) {
      mesh.instanceMatrix.needsUpdate = true;
      mesh.instanceColor!.needsUpdate = true;
    }
  }

  private syncStrays(sim: Sim, time: number): void {
    const st = sim.strays;
    const p = sim.player;
    let n = 0;
    if (st.count > 0) {
      scl.set(1, 1, 1);
      for (let i = 0; i < MAX_STRAYS; i++) {
        if (!st.alive[i]) continue;
        pos.set(st.x[i], st.y[i] + 0.08 * Math.sin(time * 4 + i), st.z[i]);
        // Nose toward the player, with a slight roll as it weaves.
        euler.set(0, Math.atan2(st.x[i] - p.x, st.z[i] - p.z), 0.18 * Math.sin(time * 3 + i), 'YXZ');
        quat.setFromEuler(euler);
        this.strays.setMatrixAt(n++, m4.compose(pos, quat, scl));
      }
      this.strays.instanceMatrix.needsUpdate = true;
    }
    this.strays.count = n;
  }

  private addTracer(sim: Sim, turret: number, stray: number): void {
    const es = sim.entities;
    const st = sim.strays;
    const i = this.tracerNext;
    this.tracerNext = (i + 1) % TRACER_CAPACITY;
    const o = i * 6;
    this.tracerPos[o] = es.cx[turret] + 0.5;
    this.tracerPos[o + 1] = es.y[turret] + 0.8;
    this.tracerPos[o + 2] = es.cz[turret] + 0.5;
    this.tracerPos[o + 3] = st.x[stray];
    this.tracerPos[o + 4] = st.y[stray];
    this.tracerPos[o + 5] = st.z[stray];
    this.tracerLeft[i] = TRACER_TIME;
  }

  private syncTracers(dt: number): void {
    let live = 0;
    for (let i = 0; i < TRACER_CAPACITY; i++) {
      if (this.tracerLeft[i] <= 0) continue;
      this.tracerLeft[i] -= dt;
      if (this.tracerLeft[i] <= 0) this.tracerPos.fill(0, i * 6, i * 6 + 6);
      else live++;
    }
    const geo = this.tracers.geometry;
    geo.setDrawRange(0, live > 0 ? TRACER_CAPACITY * 2 : 0);
    geo.getAttribute('position').needsUpdate = true;
  }

  /** While the Linker is in hand, draws every direct device-to-device wire as a line in the world. */
  private syncWires(sim: Sim): void {
    const show = ITEMS[selectedItem(sim)].kind === 'linker';
    this.wires.visible = show;
    if (!show || this.wireVersion === sim.graph.version) return;
    const g = sim.graph;
    const es = sim.entities;
    let n = 0;
    for (let i = 0; i < g.wireCount && n < WIRE_CAPACITY; i++) {
      const a = wireSrcNode(g.wires[i]);
      const b = wireDstNode(g.wires[i]);
      if (g.type[a] !== NodeType.Device || g.type[b] !== NodeType.Device) continue;
      const ea = g.entity[a];
      const eb = g.entity[b];
      const o = n++ * 6;
      this.wirePos[o] = es.cx[ea] + 0.5;
      this.wirePos[o + 1] = es.y[ea] + ENTITY_DEFS[es.kind[ea]].height + 0.15;
      this.wirePos[o + 2] = es.cz[ea] + 0.5;
      this.wirePos[o + 3] = es.cx[eb] + 0.5;
      this.wirePos[o + 4] = es.y[eb] + ENTITY_DEFS[es.kind[eb]].height + 0.15;
      this.wirePos[o + 5] = es.cz[eb] + 0.5;
    }
    this.wires.geometry.setDrawRange(0, n * 2);
    this.wires.geometry.getAttribute('position').needsUpdate = true;
    this.wireVersion = g.version;
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
  if (t.type === TargetType.Stray) return 'Stray';
  return '';
}
