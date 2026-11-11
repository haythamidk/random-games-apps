import * as THREE from 'three';
import { EYE_HEIGHT } from '../core/player';
import { Sim, daylight } from '../core/sim';
import { Terrain, buildTerrain } from './terrain';
import { WorldView } from './worldview';

const SKY_HORIZON = new THREE.Color(0xc4d6d2);
const SKY_TOP = new THREE.Color(0x4f8296);
const NIGHT_HORIZON = new THREE.Color(0x121d26);
/** Multiplied into the sky dome's vertex colours at night. */
const NIGHT_SKY_TINT = new THREE.Color(0x1c2a3a);
const WHITE = new THREE.Color(0xffffff);
const HEMI_DAY = 1.15;
const HEMI_NIGHT = 0.32;
const SUN_DAY = 2.5;
const FOG_NEAR = 70;
const FOG_FAR = 200;
/** Chunks whose bounding sphere is entirely beyond the fog are not drawn. */
const CHUNK_CULL = FOG_FAR + 4;

const FOV_LANDSCAPE = 62;
const FOV_PORTRAIT = 88;

const MAX_PIXEL_RATIO = 1.5;
const MIN_SCALE = 0.7;
/** Frame-time thresholds (ms) for stepping the resolution scale down / back up. */
const SLOW_MS = 20;
const FAST_MS = 14;

export interface RenderStats {
  calls: number;
  triangles: number;
  scale: number;
}

/** Owns the three.js scene. Reads sim state; never writes it. */
export class View {
  readonly renderer: THREE.WebGLRenderer;
  readonly scene = new THREE.Scene();
  readonly camera: THREE.PerspectiveCamera;
  private terrain: Terrain;
  private world: WorldView;
  private sky: THREE.Mesh;
  private skyMat: THREE.MeshBasicMaterial;
  private fog: THREE.Fog;
  private hemi: THREE.HemisphereLight;
  private sun: THREE.DirectionalLight;
  private shownLight = -1;
  private basePixelRatio: number;
  private scale = 1;
  private frameAvg = 16;
  private sinceScaleChange = 0;

  constructor(canvas: HTMLCanvasElement, sim: Sim) {
    this.renderer = new THREE.WebGLRenderer({
      canvas,
      antialias: false,
      powerPreference: 'high-performance',
      stencil: false,
    });
    this.basePixelRatio = Math.min(window.devicePixelRatio || 1, MAX_PIXEL_RATIO);
    this.renderer.setPixelRatio(this.basePixelRatio);

    this.camera = new THREE.PerspectiveCamera(FOV_LANDSCAPE, 1, 0.1, 600);
    this.camera.rotation.order = 'YXZ';

    this.fog = new THREE.Fog(SKY_HORIZON.getHex(), FOG_NEAR, FOG_FAR);
    this.scene.fog = this.fog;
    this.scene.background = new THREE.Color(SKY_HORIZON);

    this.hemi = new THREE.HemisphereLight(0xdfeeee, 0x3f4f50, HEMI_DAY);
    this.scene.add(this.hemi);
    this.sun = new THREE.DirectionalLight(0xfff0d6, SUN_DAY);
    this.sun.position.set(-0.55, 0.75, 0.35);
    this.scene.add(this.sun);

    this.terrain = buildTerrain(sim.world);
    this.scene.add(this.terrain.group);

    this.world = new WorldView(sim, this.terrain.material);
    this.scene.add(this.world.group);

    this.sky = buildSky();
    this.skyMat = this.sky.material as THREE.MeshBasicMaterial;
    this.scene.add(this.sky);

    canvas.addEventListener('webglcontextlost', (e) => e.preventDefault());
    this.resize();
    window.addEventListener('resize', () => this.resize());
  }

  resize(): void {
    const w = window.innerWidth;
    const h = window.innerHeight;
    this.renderer.setSize(w, h, false);
    this.camera.aspect = w / Math.max(1, h);
    // Portrait needs a taller field of view to keep a usable horizontal one.
    this.camera.fov = this.camera.aspect < 1 ? FOV_PORTRAIT : FOV_LANDSCAPE;
    this.camera.updateProjectionMatrix();
  }

  /** Copies sim state into scene objects. `dt` and `time` are in seconds. */
  sync(sim: Sim, dt: number, time: number): void {
    const p = sim.player;
    const light = daylight(sim);
    this.world.sync(sim, dt, time, light);
    this.syncDaylight(light);

    this.camera.position.set(p.x, p.y + EYE_HEIGHT, p.z);
    this.camera.rotation.set(p.pitch, p.yaw, 0);
    this.sky.position.copy(this.camera.position);

    const chunks = this.terrain.chunks;
    for (let i = 0; i < chunks.length; i++) {
      const s = chunks[i].geometry.boundingSphere!;
      const dx = s.center.x - p.x;
      const dz = s.center.z - p.z;
      const reach = CHUNK_CULL + s.radius;
      chunks[i].visible = dx * dx + dz * dz < reach * reach;
    }
  }

  /** Fades sky, fog and lights between day and night. Skips work when nothing changed. */
  private syncDaylight(light: number): void {
    const q = Math.round(light * 200) / 200;
    if (q === this.shownLight) return;
    this.shownLight = q;
    this.fog.color.copy(NIGHT_HORIZON).lerp(SKY_HORIZON, q);
    (this.scene.background as THREE.Color).copy(this.fog.color);
    this.skyMat.color.copy(NIGHT_SKY_TINT).lerp(WHITE, q);
    this.hemi.intensity = HEMI_NIGHT + (HEMI_DAY - HEMI_NIGHT) * q;
    this.sun.intensity = SUN_DAY * q;
  }

  /** Renders one frame and adapts the resolution scale to the measured frame time. */
  render(frameMs: number): void {
    this.renderer.render(this.scene, this.camera);

    this.frameAvg += (frameMs - this.frameAvg) * 0.05;
    this.sinceScaleChange++;
    if (this.sinceScaleChange > 90) {
      if (this.frameAvg > SLOW_MS && this.scale > MIN_SCALE) this.setScale(this.scale - 0.1);
      else if (this.frameAvg < FAST_MS && this.scale < 1) this.setScale(this.scale + 0.1);
    }
  }

  private setScale(s: number): void {
    this.scale = Math.min(1, Math.max(MIN_SCALE, Math.round(s * 10) / 10));
    this.renderer.setPixelRatio(this.basePixelRatio * this.scale);
    this.renderer.setSize(window.innerWidth, window.innerHeight, false);
    this.sinceScaleChange = 0;
  }

  stats(): RenderStats {
    const info = this.renderer.info.render;
    return { calls: info.calls, triangles: info.triangles, scale: this.scale };
  }
}

/** Gradient sky dome: one draw call, no texture, unaffected by fog. */
function buildSky(): THREE.Mesh {
  const geo = new THREE.SphereGeometry(500, 16, 8);
  const pos = geo.getAttribute('position');
  const col = new Float32Array(pos.count * 3);
  const top = SKY_TOP;
  const horizon = SKY_HORIZON;
  const c = new THREE.Color();
  for (let i = 0; i < pos.count; i++) {
    const t = THREE.MathUtils.clamp(pos.getY(i) / 500, 0, 1);
    c.copy(horizon).lerp(top, Math.pow(t, 0.6));
    col[i * 3] = c.r;
    col[i * 3 + 1] = c.g;
    col[i * 3 + 2] = c.b;
  }
  geo.setAttribute('color', new THREE.BufferAttribute(col, 3));
  const mat = new THREE.MeshBasicMaterial({
    vertexColors: true,
    side: THREE.BackSide,
    fog: false,
    depthWrite: false,
  });
  const mesh = new THREE.Mesh(geo, mat);
  mesh.renderOrder = -1;
  mesh.frustumCulled = false;
  return mesh;
}
