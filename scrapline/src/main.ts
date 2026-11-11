import { add } from './core/inventory';
import { MoveIntent } from './core/player';
import { Sim, TICK_DT, createSim, respawn, stepPlayer, tickSim } from './core/sim';
import { TouchInput } from './input/touch';
import { DebugOverlay } from './ui/debug';
import { Hud } from './ui/hud';
import { Menu } from './ui/menu';
import { InventorySheet } from './ui/sheet';
import { clearGame, loadGame, loadPrefs, saveGame, savePrefs } from './ui/storage';
import { View } from './view/renderer';

const MAX_FRAME_DT = 0.1;
const MAX_TICKS_PER_FRAME = 5;
const AUTOSAVE_MS = 30000;
const DEFAULT_SEED = 20261111;

const params = new URLSearchParams(location.search);
const canvas = document.getElementById('scene') as HTMLCanvasElement;
const layer = document.getElementById('input-layer') as HTMLElement;
const uiRoot = document.getElementById('ui') as HTMLElement;

// `?fresh` ignores any saved game (used by automated tests); `?seed=` picks the map.
const fresh = params.has('fresh');
const sim: Sim = (fresh ? null : loadGame()) ?? createSim(Number(params.get('seed')) || DEFAULT_SEED);
const prefs = loadPrefs();

const view = new View(canvas, sim);
const input = new TouchInput(layer);
input.sensitivity = prefs.sensitivity;

const sheet = new InventorySheet(uiRoot, sim);
const menu = new Menu(uiRoot, prefs.sensitivity, {
  setSensitivity: (value) => {
    prefs.sensitivity = value;
    input.sensitivity = value;
    savePrefs(prefs);
  },
  newWorld: () => {
    saving = false;
    clearGame();
    location.reload();
  },
});
const hud = new Hud(uiRoot, sim, input, {
  openBag: () => sheet.open({ kind: 'craft', station: 'hand' }),
  openMenu: () => menu.open(),
  context: (action, entityId) => {
    if (action === 'rotate') sim.buildRot = (sim.buildRot + 1) & 3;
    else if (action === 'craft') sheet.open({ kind: 'craft', station: 'workbench' });
    else if (action === 'open' && entityId) sheet.open({ kind: 'storage', entityId });
  },
  respawn: () => respawn(sim),
});
const debug = new DebugOverlay(uiRoot);
input.onThreeFingerTap = () => debug.toggle();
if (params.has('debug')) debug.toggle();

let saving = !fresh;
const save = () => {
  if (saving) saveGame(sim);
};
window.setInterval(save, AUTOSAVE_MS);
document.addEventListener('visibilitychange', () => {
  if (document.hidden) save();
});
window.addEventListener('pagehide', save);

const intent: MoveIntent = { moveX: 0, moveY: 0, sprint: false, jump: false, lookYaw: 0, lookPitch: 0 };
let last = performance.now();
let acc = 0;

function frame(now: number): void {
  const frameMs = now - last;
  const dt = Math.min(MAX_FRAME_DT, frameMs / 1000);
  last = now;

  input.consume(intent);
  const modal = sheet.isOpen || menu.isOpen;
  sim.actHeld = input.act && !modal;
  if (input.actTapped && !modal) sim.actPressed = true;
  input.actTapped = false;
  stepPlayer(sim, intent, dt);

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

  view.sync(sim, dt, now / 1000);
  view.render(frameMs);
  hud.sync(sim);
  sheet.sync();
  sim.events.length = 0;
  debug.update(now, frameMs, tickMs, view.stats());

  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);

// Hook for automated smoke tests and manual debugging from the console.
declare global {
  interface Window {
    __scrapline: {
      ready: boolean;
      sim: Sim;
      perf: typeof debug.sample;
      give(id: number, n: number): number;
      sheetOpen(): boolean;
      save(): boolean;
    };
  }
}
window.__scrapline = {
  ready: true,
  sim,
  perf: debug.sample,
  give: (id, n) => add(sim.inventory, id, n),
  sheetOpen: () => sheet.isOpen,
  save: () => saveGame(sim),
};
