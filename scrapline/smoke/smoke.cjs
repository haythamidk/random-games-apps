/* eslint-disable */
// Smoke test: serves the production build, opens it in a phone-sized touch
// browser, drives it with synthetic touches and checks the game responds.
// Usage: npm run build && npm run smoke [-- --shots]
const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const DIST = path.join(__dirname, '..', 'dist');
const SHOTS = path.join(__dirname, 'shots');
const VIEWPORT = { width: 844, height: 390 };
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json' };

function serve() {
  const server = http.createServer((req, res) => {
    const url = decodeURIComponent(req.url.split('?')[0]);
    const file = path.join(DIST, url === '/' ? 'index.html' : url);
    if (!file.startsWith(DIST) || !fs.existsSync(file)) {
      res.writeHead(404);
      res.end();
      return;
    }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)));
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`);
}

(async () => {
  if (!fs.existsSync(path.join(DIST, 'index.html'))) {
    console.error('dist/ not found. Run "npm run build" first.');
    process.exit(2);
  }
  fs.mkdirSync(SHOTS, { recursive: true });
  const server = await serve();
  const base = `http://127.0.0.1:${server.address().port}/`;
  const browser = await chromium.launch({ args: ['--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const context = await browser.newContext({ viewport: VIEWPORT, hasTouch: true, isMobile: true, deviceScaleFactor: 2 });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(m.text());
  });
  const cdp = await context.newCDPSession(page);
  const touch = (type, points) =>
    cdp.send('Input.dispatchTouchEvent', {
      type,
      touchPoints: points.map((p) => ({ x: p.x, y: p.y, id: p.id, radiusX: 8, radiusY: 8, force: 1 })),
    });
  const state = () =>
    page.evaluate(() => {
      const s = window.__scrapline.sim;
      return {
        x: s.player.x, y: s.player.y, z: s.player.z, yaw: s.player.yaw, pitch: s.player.pitch,
        tick: s.tick, selected: s.selected,
        ids: Array.from(s.inventory.ids), counts: Array.from(s.inventory.counts),
      };
    });
  const shot = (name) => page.screenshot({ path: path.join(SHOTS, name) });

  const game = (fn, arg) => page.evaluate(fn, arg);
  const center = async (sel) => {
    const b = await page.locator(sel).boundingBox();
    return { x: b.x + b.width / 2, y: b.y + b.height / 2 };
  };
  /** Presses and holds the action button for `ms`. */
  const holdAct = async (ms) => {
    const c = await center('.btn-act');
    await touch('touchStart', [{ ...c, id: 7 }]);
    await sleep(ms);
    await touch('touchEnd', []);
    await sleep(120);
  };
  const countOf = (id) =>
    game((id) => {
      const inv = window.__scrapline.sim.inventory;
      let n = 0;
      for (let i = 0; i < inv.size; i++) if (inv.ids[i] === id) n += inv.counts[i];
      return n;
    }, id);
  const slotOf = (id) =>
    game((id) => {
      const inv = window.__scrapline.sim.inventory;
      for (let i = 0; i < inv.size; i++) if (inv.ids[i] === id && inv.counts[i] > 0) return i;
      return -1;
    }, id);
  /** Puts an item on the hotbar through the bag UI if needed, then selects it. */
  const equip = async (id, hotSlot) => {
    const at = await slotOf(id);
    if (at !== hotSlot) {
      await page.locator('.btn-bag').tap();
      await sleep(250);
      await page.locator('.pane-bag .slot').nth(at).tap();
      await page.locator('.pane-bag .slot').nth(hotSlot).tap();
      await page.locator('.sheet-backdrop.open .sheet-close').tap();
      await sleep(250);
    }
    await page.locator('.hotbar .slot').nth(hotSlot).tap();
    await sleep(80);
  };
  /** Stands at a spot near the landing pad looking down at the ground ahead. */
  const standAt = (x, z, yaw) =>
    game(([x, z, yaw]) => {
      const p = window.__scrapline.sim.player;
      p.x = x; p.z = z; p.yaw = yaw; p.pitch = -0.6; p.vx = 0; p.vz = 0;
    }, [x, z, yaw]);
  const entities = () =>
    game(() => {
      const es = window.__scrapline.sim.entities;
      const out = [];
      for (let id = 1; id < es.high; id++) if (es.kind[id]) out.push({ id, kind: es.kind[id], cx: es.cx[id], cz: es.cz[id], rot: es.rot[id] });
      return out;
    });
  const ITEM = { Wood: 1, Stone: 2, Axe: 8, KitWorkbench: 12, KitCrate: 13, KitWall: 14, KitCampfire: 15 };

  await page.goto(base + '?fresh&debug=1');
  await page.waitForFunction(() => window.__scrapline && window.__scrapline.ready, null, { timeout: 15000 });
  await sleep(1200);
  await shot('01-start.png');

  // ================= Day 1 =================
  const s0 = await state();
  await sleep(600);
  const s1 = await state();
  check('fixed tick advances', s1.tick > s0.tick, `${s0.tick} -> ${s1.tick}`);
  const overlay = await page.locator('.debug').innerText();
  check('debug overlay shows stats', /fps/.test(overlay) && /draws \d+/.test(overlay), overlay.replace(/\n/g, ' | '));

  const perf0 = await game(() => ({ ...window.__scrapline.perf }));
  check('world is drawn', perf0.triangles > 1000, `${perf0.triangles} tris`);
  check('draw calls within budget (<= 120)', perf0.calls > 0 && perf0.calls <= 120, `${perf0.calls} calls`);
  check('triangles within budget (<= 120k)', perf0.triangles <= 120000, `${perf0.triangles} tris`);

  // both thumbs at once: left stick forward, right drag to turn
  const stick = { x: 150, y: 290, id: 0 };
  const look = { x: 560, y: 120, id: 1 };
  await touch('touchStart', [stick]);
  await touch('touchStart', [stick, look]);
  for (let i = 1; i <= 10; i++) {
    stick.y = 290 - i * 4; // 40 px up: a walk, below the sprint threshold
    look.x = 560 + i * 9;
    await touch('touchMove', [stick, look]);
    await sleep(16);
  }
  await sleep(1500);
  const s2 = await state();
  await touch('touchEnd', [look]);
  await touch('touchEnd', []);
  const moved = Math.hypot(s2.x - s1.x, s2.z - s1.z);
  check('left stick moves the player', moved > 1.5, `${moved.toFixed(2)} m`);
  check('right drag turns the camera', Math.abs(s2.yaw - s1.yaw) > 0.2, `${(s2.yaw - s1.yaw).toFixed(2)} rad`);
  await sleep(300);
  const s3 = await state();
  await sleep(300);
  const s4 = await state();
  check('player stops when the stick is released', Math.hypot(s4.x - s3.x, s4.z - s3.z) < 0.05);

  const yBefore = (await state()).y;
  const jb = await center('.btn-jump');
  await touch('touchStart', [{ ...jb, id: 3 }]);
  await sleep(220);
  const yMid = (await state()).y;
  await touch('touchEnd', []);
  check('jump button lifts the player', yMid - yBefore > 0.3, `${(yMid - yBefore).toFixed(2)} m`);
  await sleep(900);

  // ================= Day 2 =================
  // --- 2.1 gather: stand in front of the first deadwood and hold the action button
  await page.locator('.hotbar .slot').nth(3).tap(); // empty slot: bare hands
  check('tapping a hotbar slot selects it', (await state()).selected === 3);
  await game(() => {
    const s = window.__scrapline.sim;
    const n = s.nodes;
    let i = 0;
    while (n.kind[i] !== 1) i++;
    const p = s.player;
    p.x = n.x[i]; p.z = n.z[i] + 1.6; p.yaw = 0; p.pitch = -0.35;
  });
  await sleep(300);
  const label = await page.locator('.target-label').innerText();
  check('reticle names the targeted node', label === 'Deadwood', label);
  await shot('02-target.png');
  await holdAct(1700);
  const wood = await countOf(ITEM.Wood);
  check('holding the action button gathers by hand', wood >= 2, `${wood} wood`);
  const feed = await page.locator('.feed').innerText();
  check('pickup feed tallies gathered items', /^\+\d+ Deadwood$/.test(feed), feed);

  // --- 2.2 craft by hand
  await game(() => {
    const g = window.__scrapline.give;
    g(1, 60);
    g(2, 30);
  });
  await page.locator('.btn-bag').tap();
  await sleep(300);
  check('bag opens with hand recipes', (await game(() => window.__scrapline.sheetOpen())) && (await page.locator('.recipe').count()) === 4);
  await page.locator('.recipe[data-recipe="0"]').tap(); // axe
  await page.locator('.recipe[data-recipe="3"]').tap(); // workbench kit
  await page.locator('.recipe[data-recipe="2"]').tap(); // campfire kit
  check('recipes craft into the bag',
    (await countOf(ITEM.Axe)) === 1 && (await countOf(ITEM.KitWorkbench)) === 1 && (await countOf(ITEM.KitCampfire)) === 1);
  await shot('03-crafting.png');

  // tap-move and hold-for-half inside the bag
  const woodSlot = await slotOf(ITEM.Wood);
  const before = await state();
  await page.locator('.pane-bag .slot').nth(woodSlot).tap();
  await page.locator('.pane-bag .slot').nth(20).tap();
  const after = await state();
  check('tap, tap moves a whole stack', after.counts[woodSlot] === 0 && after.counts[20] === before.counts[woodSlot],
    `${before.counts[woodSlot]} -> slot 20: ${after.counts[20]}`);
  const sb = await center('.pane-bag .slot:nth-child(21)');
  await touch('touchStart', [{ ...sb, id: 5 }]);
  await sleep(550);
  await touch('touchEnd', []);
  await sleep(100);
  await page.locator('.pane-bag .slot').nth(22).tap();
  const half = await state();
  check('hold picks up half a stack', half.counts[20] + half.counts[22] === after.counts[20] && Math.abs(half.counts[20] - half.counts[22]) <= 1,
    `${half.counts[20]} / ${half.counts[22]}`);
  await page.locator('.sheet-backdrop.open .sheet-close').tap();
  await sleep(250);
  check('bag closes', !(await game(() => window.__scrapline.sheetOpen())));

  // gather again with the axe: faster
  await equip(ITEM.Axe, 4);
  const w0 = await countOf(ITEM.Wood);
  await holdAct(1000);
  const w1 = await countOf(ITEM.Wood);
  check('the axe gathers faster than bare hands', w1 - w0 >= 3, `+${w1 - w0} wood in 1 s`);

  // --- 2.3 build: place the workbench
  await standAt(128.5, 128.5, 0);
  await equip(ITEM.KitWorkbench, 5);
  await sleep(250);
  await shot('04-ghost.png');
  check('context button offers Rotate while holding a kit', (await page.locator('.btn-ctx').innerText()) === 'Rotate');
  await page.locator('.btn-ctx').tap();
  await sleep(100);
  check('rotate turns the next placement', (await game(() => window.__scrapline.sim.buildRot)) === 1);
  await page.locator('.btn-act').tap();
  await sleep(250);
  let placed = await entities();
  check('a quick tap on the action button places the kit', placed.length === 1 && placed[0].kind === 1 && placed[0].rot === 1,
    JSON.stringify(placed));
  check('placing uses up the kit', (await countOf(ITEM.KitWorkbench)) === 0);

  // --- 2.4 stations: use the workbench, make a crate and walls
  await sleep(200);
  check('context button offers Use on the workbench', (await page.locator('.btn-ctx').innerText()) === 'Use');
  await page.locator('.btn-ctx').tap();
  await sleep(300);
  const title = await page.locator('.sheet-backdrop.open .sheet-title').innerText();
  check('workbench opens with bench recipes', title === 'Workbench' && (await page.locator('.recipe').count()) === 6, title);
  await page.locator('.recipe[data-recipe="4"]').tap(); // crate kit
  await page.locator('.recipe[data-recipe="5"]').tap(); // wall kit x2
  check('bench recipes craft', (await countOf(ITEM.KitCrate)) === 1 && (await countOf(ITEM.KitWall)) === 2);
  await shot('05-workbench.png');
  await page.locator('.sheet-backdrop.open .sheet-close').tap();
  await sleep(250);

  // place the crate to the right of the bench and store wood in it
  await standAt(128.5, 128.5, -0.9);
  await equip(ITEM.KitCrate, 5);
  await page.locator('.btn-act').tap();
  await sleep(250);
  placed = await entities();
  const crate = placed.find((e) => e.kind === 2);
  check('crate is placed on a second cell', placed.length === 2 && !!crate, JSON.stringify(placed));
  await sleep(200);
  check('context button offers Open on the crate', (await page.locator('.btn-ctx').innerText()) === 'Open');
  await page.locator('.btn-ctx').tap();
  await sleep(300);
  check('crate shows 16 storage slots', (await page.locator('.store-grid .slot').count()) === 16);
  const stoneSlot = await slotOf(ITEM.Stone);
  const stoneHad = (await state()).counts[stoneSlot];
  await page.locator('.pane-bag .slot').nth(stoneSlot).tap();
  await page.locator('.store-grid .slot').nth(0).tap();
  const stored = await game((id) => {
    const c = window.__scrapline.sim.entities.containers[id];
    return { id: c.ids[0], n: c.counts[0] };
  }, crate.id);
  check('items move from the bag into the crate', stored.id === ITEM.Stone && stored.n === stoneHad, `${stored.n} stone stored`);
  await shot('06-crate.png');
  await page.locator('.sheet-backdrop.open .sheet-close').tap();
  await sleep(250);

  // dismantle the crate: kit and contents come back (works whatever is in hand, even a kit)
  await page.locator('.hotbar .slot').nth(3).tap();
  await holdAct(1300);
  placed = await entities();
  check('holding the action button packs an entity up', placed.length === 1 && (await countOf(ITEM.KitCrate)) === 1 && (await countOf(ITEM.Stone)) >= stoneHad,
    `${placed.length} entities left`);

  // --- 2.5 vitals: campfire restores warmth, night falls
  await standAt(128.5, 128.5, 0.9);
  await equip(ITEM.KitCampfire, 5);
  await game(() => (window.__scrapline.sim.vitals.warmth = 150));
  await page.locator('.btn-act').tap();
  await sleep(1500);
  const warmth = await game(() => window.__scrapline.sim.vitals.warmth);
  check('a campfire restores warmth', warmth > 250, `${warmth}`);
  await game(() => (window.__scrapline.sim.tick = 16800 + 1200));
  await sleep(700);
  const clock = await page.locator('.clock').innerText();
  check('clock counts down to dawn at night', /^Dawn in \d+:\d\d$/.test(clock), clock);
  await shot('07-night.png');
  await game(() => (window.__scrapline.sim.tick = 2000));

  // --- 2.6 save and reload
  const saved = await game(() => {
    const ok = window.__scrapline.save();
    const s = window.__scrapline.sim;
    return { ok, tick: s.tick, counts: Array.from(s.inventory.counts), hp: Array.from(s.nodes.hp) };
  });
  await page.goto(base + '?debug=1');
  await page.waitForFunction(() => window.__scrapline && window.__scrapline.ready, null, { timeout: 15000 });
  await sleep(600);
  const loaded = await game(() => {
    const s = window.__scrapline.sim;
    return { tick: s.tick, counts: Array.from(s.inventory.counts), hp: Array.from(s.nodes.hp) };
  });
  const loadedEntities = await entities();
  check('game saves', saved.ok);
  check('reload restores bag, entities and depleted nodes',
    JSON.stringify(loaded.counts) === JSON.stringify(saved.counts) && JSON.stringify(loaded.hp) === JSON.stringify(saved.hp) &&
      loadedEntities.length === 2 && loaded.tick >= saved.tick,
    `${loadedEntities.length} entities, tick ${saved.tick} -> ${loaded.tick}`);
  await shot('08-reloaded.png');

  // menu: sensitivity and the two-step reset
  await page.locator('.btn-menu').tap();
  await sleep(250);
  await page.locator('.danger').tap();
  check('reset asks for a second tap', /again/i.test(await page.locator('.danger').innerText()));
  await page.locator('.sheet-backdrop.open .sheet-close').tap();
  await sleep(250);

  // --- frame budget signal under CPU throttle (software GL here, so informational)
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 });
  await sleep(2500);
  const perf = await page.evaluate(() => ({ ...window.__scrapline.perf }));
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: 1 });
  console.log(
    `INFO  4x CPU throttle, software GL: ${perf.fps.toFixed(0)} fps, ${perf.frameMs.toFixed(1)} ms, ` +
      `${perf.calls} draws, ${perf.triangles} tris, tick ${perf.tickMs.toFixed(3)} ms, heap ${perf.heapMb.toFixed(0)} MB`,
  );
  check('sim tick within budget (<= 2 ms)', perf.tickMs <= 2, `${perf.tickMs.toFixed(3)} ms`);

  check('no console errors', errors.length === 0, errors.slice(0, 3).join(' ; '));

  await browser.close();
  server.close();
  const failed = results.filter((r) => !r.ok).length;
  console.log(`\n${results.length - failed}/${results.length} checks passed`);
  process.exit(failed ? 1 : 0);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
