import { add } from './core/inventory';
import { MoveIntent, updatePlayer } from './core/player';
import { TICK_DT, createSim, tickSim } from './core/sim';
import { TouchInput } from './input/touch';
import { Bag } from './ui/bag';
import { DebugOverlay } from './ui/debug';
import { Hud } from './ui/hud';
import { View } from './view/renderer';

const MAX_FRAME_DT = 0.1;
const MAX_TICKS_PER_FRAME = 5;

const params = new URLSearchParams(location.search);
const seed = Number(params.get('seed')) || 20261111;

const canvas = document.getElementById('scene') as HTMLCanvasElement;
const layer = document.getElementById('input-layer') as HTMLElement;
const uiRoot = document.getElementById('ui') as HTMLElement;

const sim = createSim(seed);
const view = new View(canvas, sim);
const input = new TouchInput(layer);
const bag = new Bag(uiRoot, sim);
const hud = new Hud(uiRoot, sim, input, { openBag: () => bag.open() });
const debug = new DebugOverlay(uiRoot);
input.onThreeFingerTap = () => debug.toggle();
if (params.has('debug')) debug.toggle();

const intent: MoveIntent = { moveX: 0, moveY: 0, sprint: false, jump: false, lookYaw: 0, lookPitch: 0 };
let last = performance.now();
let acc = 0;

function frame(now: number): void {
  const frameMs = now - last;
  const dt = Math.min(MAX_FRAME_DT, frameMs / 1000);
  last = now;

  input.consume(intent);
  updatePlayer(sim.player, sim.world, intent, dt);

  acc += dt;
  let ticks = 0;
  const t0 = performance.now();
  while (acc >= TICK_DT && ticks < MAX_TICKS_PER_FRAME) {
    tickSim(sim);
    acc -= TICK_DT;
    ticks++;
  }
  // If we fell too far behind, drop the backlog rather than spiral.
  if (ticks === MAX_TICKS_PER_FRAME) acc = 0;
  const tickMs = ticks ? (performance.now() - t0) / ticks : 0;

  view.sync(sim);
  view.render(frameMs);
  hud.sync(sim);
  bag.sync();
  debug.update(now, frameMs, tickMs, view.stats());

  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);

// Hook for automated smoke tests and manual debugging from the console.
declare global {
  interface Window {
    __scrapline: {
      ready: boolean;
      sim: typeof sim;
      perf: typeof debug.sample;
      give(id: number, n: number): number;
      bagOpen(): boolean;
    };
  }
}
window.__scrapline = {
  ready: true,
  sim,
  perf: debug.sample,
  give: (id, n) => add(sim.inventory, id, n),
  bagOpen: () => bag.isOpen,
};
