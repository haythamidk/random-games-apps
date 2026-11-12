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
  check('workbench opens with bench recipes', title === 'Workbench' && (await page.locator('.recipe').count()) === 16, title);
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

  // ================= Day 3 =================
  const I = { Wood: 1, Stone: 2, Ore: 3, Coal: 4, Ingot: 5, KitCrate: 13, KitSmelter: 16, KitDrill: 17, KitBelt: 18, KitSensor: 19, KitTurret: 20, KitLamp: 21, KitSwitch: 22, Linker: 23 };
  /** Empties the bag and fills it so that item k of the list sits in slot k. */
  const setBag = (items) =>
    game((items) => {
      const inv = window.__scrapline.sim.inventory;
      inv.ids.fill(0);
      inv.counts.fill(0);
      items.forEach(([id, n], k) => {
        inv.ids[k] = id;
        inv.counts[k] = n;
      });
    }, items);
  const target = () =>
    game(() => {
      const t = window.__scrapline.sim.target;
      return { type: t.type, id: t.id, cx: t.cx, cz: t.cz };
    });
  /** Stands south of a build cell and tilts the view until the reticle is on it. */
  const aimCell = async (cx, cz, wantEntity = false) => {
    await game(([cx, cz]) => {
      const p = window.__scrapline.sim.player;
      p.x = cx + 0.5; p.z = cz + 3.2; p.yaw = 0; p.vx = 0; p.vz = 0;
    }, [cx, cz]);
    let lo = -1.25;
    let hi = -0.15;
    for (let k = 0; k < 14; k++) {
      const pitch = (lo + hi) / 2;
      await game((pitch) => (window.__scrapline.sim.player.pitch = pitch), pitch);
      await sleep(90);
      const t = await target();
      const hit = t.type === 2 || t.type === 3;
      if (hit && t.cx === cx && t.cz === cz && (t.type === 2) === wantEntity) return true;
      if (!hit || t.cz < cz) hi = pitch; // looking too far: tilt down
      else lo = pitch;
    }
    return false;
  };
  const entityAtCell = (cx, cz) => game(([cx, cz]) => window.__scrapline.sim.entities.grid[cz * 256 + cx], [cx, cz]);
  const placeAt = async (hotSlot, cx, cz, rot = 0) => {
    await page.locator('.hotbar .slot').nth(hotSlot).tap();
    await game((rot) => (window.__scrapline.sim.buildRot = rot), rot);
    const aimed = await aimCell(cx, cz);
    await page.locator('.btn-act').tap();
    await sleep(220);
    const id = await entityAtCell(cx, cz);
    return aimed ? id : 0;
  };
  const activeOf = (id) => game((id) => window.__scrapline.sim.entities.active[id], id);
  const toastText = () => page.locator('.toast').innerText();

  // --- 3.1 smelter, hand fed through the machine panel
  await game(() => (window.__scrapline.sim.vitals.warmth = 1000));
  await setBag([[I.KitSmelter, 1], [I.Ore, 6], [I.Coal, 3]]);
  const smelter = await placeAt(0, 124, 133);
  check('smelter is placed', smelter > 0);
  await aimCell(124, 133, true);
  check('context button offers Open on the smelter', (await page.locator('.btn-ctx').innerText()) === 'Open');
  await page.locator('.btn-ctx').tap();
  await sleep(300);
  const slotNames = await page.locator('.machine-slot-name').allInnerTexts();
  check('machine panel labels its slots', slotNames.join(',') === 'Ore,Coal,Out', slotNames.join(','));
  check('machine panel says what it needs', (await page.locator('.machine-status').innerText()) === 'Needs iron ore');
  await page.locator('.pane-bag .slot').nth(1).tap();
  await page.locator('.machine-grid .slot').nth(0).tap();
  await page.locator('.pane-bag .slot').nth(2).tap();
  await page.locator('.machine-grid .slot').nth(1).tap();
  await sleep(600);
  const smeltStatus = await page.locator('.machine-status').innerText();
  check('smelter starts once it has ore and coal', /^Smelting/.test(smeltStatus), smeltStatus);
  await shot('09-smelter.png');
  await sleep(5200);
  const ingots = await game((id) => window.__scrapline.sim.entities.containers[id].counts[2], smelter);
  check('smelter turns ore into ingots', ingots >= 1, `${ingots} ingot(s)`);
  await page.locator('.sheet-backdrop.open .sheet-close').tap();
  await sleep(250);

  // --- 3.2 drill -> belts -> crate on the ore deposit
  const dep = await game(() => {
    const d = window.__scrapline.sim.nodes.deposits.find((x) => x.item === 3);
    return { cx: Math.floor(d.x), cz: Math.floor(d.z) };
  });
  await setBag([[I.KitDrill, 2], [I.KitBelt, 3], [I.KitCrate, 1]]);
  await page.locator('.hotbar .slot').nth(0).tap();
  await aimCell(124, 135);
  await page.locator('.btn-act').tap();
  await sleep(250);
  check('a drill cannot go on plain ground, and says why',
    (await entityAtCell(124, 135)) === 0 && /deposit/.test(await toastText()), await toastText());
  // The line runs toward -X (rot 1), away from the ore boulders that ring the deposit.
  const drill = await placeAt(0, dep.cx, dep.cz, 1);
  check('drill is placed on the deposit', drill > 0);
  let belts = 0;
  for (let k = 1; k <= 3; k++) if (await placeAt(1, dep.cx - k, dep.cz, 1)) belts++;
  const lineCrate = await placeAt(2, dep.cx - 4, dep.cz);
  check('belts and crate are placed in a line', belts === 3 && lineCrate > 0, `${belts} belts`);
  await sleep(6500);
  await shot('10-belts.png');
  const delivered = await game((id) => {
    const c = window.__scrapline.sim.entities.containers[id];
    let n = 0;
    for (let i = 0; i < c.size; i++) if (c.ids[i] === 3) n += c.counts[i];
    return n;
  }, lineCrate);
  check('drill, belts and crate run unattended', delivered >= 1, `${delivered} ore delivered`);

  // --- 3.4 Linker: switch -> lamp in the world
  await setBag([[I.KitSwitch, 1], [I.KitLamp, 1], [I.Linker, 1], [I.KitSensor, 1], [I.KitTurret, 1]]);
  const sw = await placeAt(0, 126, 133);
  const lamp = await placeAt(1, 128, 133);
  check('switch and lamp are placed', sw > 0 && lamp > 0);
  await page.locator('.hotbar .slot').nth(2).tap();
  await aimCell(126, 133, true);
  await page.locator('.btn-act').tap();
  await sleep(200);
  check('first Linker press picks the source', /^From Switch/.test(await toastText()), await toastText());
  await aimCell(128, 133, true);
  await page.locator('.btn-act').tap();
  await sleep(200);
  check('second Linker press wires source to target',
    (await game(() => window.__scrapline.sim.graph.wireCount)) === 1 && /Switch now drives Lamp/.test(await toastText()), await toastText());
  await page.locator('.hotbar .slot').nth(5).tap(); // empty hand
  await aimCell(126, 133, true);
  check('context button offers Flip on the switch', (await page.locator('.btn-ctx').innerText()) === 'Flip');
  await page.locator('.btn-ctx').tap();
  await sleep(400);
  check('flipping the switch lights the lamp', (await activeOf(lamp)) === 1);
  await game(() => (window.__scrapline.sim.tick = 16800 + 600));
  await sleep(500);
  await shot('11-lamp.png');
  await game(() => (window.__scrapline.sim.tick = 3000));

  // --- 3.5 circuit board: put a NOT between them, by touch
  await page.locator('.hotbar .slot').nth(2).tap();
  check('context button offers Circuit with the Linker in hand', (await page.locator('.btn-ctx').innerText()) === 'Circuit');
  await page.locator('.btn-ctx').tap();
  await sleep(300);
  check('circuit board opens', await game(() => window.__scrapline.boardOpen()));
  const nodeOf = (id) => game((id) => window.__scrapline.sim.entities.node[id], id);
  const port = (node, p, output) => game(([node, p, output]) => window.__scrapline.port(node, p, output), [node, p, output]);
  const tapAt = async (pt) => {
    await page.touchscreen.tap(pt.x, pt.y);
    await sleep(120);
  };
  const swNode = await nodeOf(sw);
  const lampNode = await nodeOf(lamp);
  // tap the lamp's input to select the wire into it, then remove that wire
  await tapAt(await port(lampNode, 0, false));
  await page.locator('.chip[data-action="remove-wire"]').tap();
  await sleep(120);
  check('a wire can be selected and removed', (await game(() => window.__scrapline.sim.graph.wireCount)) === 0);
  await page.locator('.chip[data-add="4"]').tap(); // + NOT
  await sleep(150);
  const notNode = await game(() => {
    const g = window.__scrapline.sim.graph;
    for (let n = g.high - 1; n > 0; n--) if (g.type[n] === 4) return n;
    return 0;
  });
  check('palette adds a logic node', notNode > 0);
  // put the new node to the right of the lamp, clear of the other ports and the toolbars
  await game(([n, a]) => {
    const g = window.__scrapline.sim.graph;
    g.bx[n] = g.bx[a] + 150; g.by[n] = g.by[a];
  }, [notNode, lampNode]);
  await sleep(150);
  // tap-tap wiring: switch.out -> NOT.in
  await tapAt(await port(swNode, 0, true));
  const hintMid = await page.locator('.board-hint').innerText();
  await tapAt(await port(notNode, 0, false));
  check('tap an output then an input makes a wire', (await game(() => window.__scrapline.sim.graph.wireCount)) === 1 && /tap the input/.test(hintMid), hintMid);
  // drag wiring: NOT.out -> lamp.in. The wire's end floats 48 px above the finger.
  const fromPt = await port(notNode, 0, true);
  const toPt = await port(lampNode, 0, false);
  await touch('touchStart', [{ x: fromPt.x, y: fromPt.y, id: 9 }]);
  for (let k = 1; k <= 8; k++) {
    await touch('touchMove', [{ x: fromPt.x + ((toPt.x - fromPt.x) * k) / 8, y: fromPt.y + ((toPt.y + 48 - fromPt.y) * k) / 8, id: 9 }]);
    await sleep(20);
  }
  await shot('12-board-drag.png');
  await touch('touchEnd', []);
  await sleep(200);
  check('dragging from an output to an input makes a wire', (await game(() => window.__scrapline.sim.graph.wireCount)) === 2);
  await sleep(500);
  check('the lamp now does the opposite of the switch', (await activeOf(sw)) === 1 && (await activeOf(lamp)) === 0);
  // a Delay node: select it and change its setting
  await page.locator('.chip[data-add="5"]').tap();
  await sleep(150);
  const before3 = await page.locator('.board-bar').innerText();
  await page.locator('.chip[data-action="more"]').tap();
  await sleep(120);
  const after3 = await page.locator('.board-bar').innerText();
  check('a node setting can be stepped', /Wait 1\.0 s/.test(before3) && /Wait 1\.2 s/.test(after3), `${before3.replace(/\s+/g, ' ')} -> ${after3.replace(/\s+/g, ' ')}`);
  await page.locator('.chip[data-action="remove-node"]').tap();
  await sleep(120);
  check('a logic node can be removed', (await game(() => {
    const g = window.__scrapline.sim.graph;
    let n = 0;
    for (let i = 1; i < g.high; i++) if (g.type[i] === 5) n++;
    return n;
  })) === 0);
  await shot('13-board.png');
  await page.locator('.board-close').tap();
  await sleep(250);
  check('circuit board closes', !(await game(() => window.__scrapline.boardOpen())));
  await page.locator('.hotbar .slot').nth(5).tap();
  await aimCell(126, 133, true);
  await page.locator('.btn-ctx').tap(); // flip off -> NOT -> lamp on
  await sleep(600);
  check('flipping the switch off lights the lamp through the NOT', (await activeOf(sw)) === 0 && (await activeOf(lamp)) === 1);

  // --- 3.6 the night: sensor -> turret defends without the player doing anything
  const sensor = await placeAt(3, 130, 133);
  const turret = await placeAt(4, 132, 133);
  check('sensor and turret are placed', sensor > 0 && turret > 0);
  await page.locator('.hotbar .slot').nth(2).tap();
  await aimCell(130, 133, true);
  await page.locator('.btn-act').tap();
  await sleep(200);
  await aimCell(132, 133, true);
  await page.locator('.btn-act').tap();
  await sleep(200);
  check('sensor is wired to the turret', /Sensor now drives Turret/.test(await toastText()), await toastText());
  await page.locator('.hotbar .slot').nth(5).tap();
  await game(() => {
    const s = window.__scrapline.sim;
    s.tick = 16800 + 95; // night; the first stray spawns within a few ticks
    s.vitals.health = 1000;
    s.vitals.warmth = 1000;
    s.player.x = 131.5; s.player.z = 135.5; s.player.pitch = -0.1;
  });
  let downed = false;
  let sawStray = false;
  for (let k = 0; k < 90 && !downed; k++) {
    await sleep(500);
    const st = await game(() => {
      const s = window.__scrapline.sim;
      s.vitals.warmth = 1000;
      return { count: s.strays.count, spawned: s.strays.spawned };
    });
    if (st.count > 0 && !sawStray) {
      sawStray = true;
      await shot('14-night-stray.png');
    }
    downed = /Turret took down a stray/.test(await toastText());
  }
  const healthAfter = await game(() => window.__scrapline.sim.vitals.health);
  check('strays come at night', sawStray);
  check('the wired turret destroys a stray on its own', downed);
  check('the player was never touched', healthAfter === 1000, `${healthAfter}`);
  await shot('15-turret.png');

  // dawn: strays shut down and the night is counted
  await game(() => (window.__scrapline.sim.tick = 21600 - 30));
  await sleep(2500);
  const dawnShown = await page.locator('.death-title', { hasText: 'You held the night' }).isVisible();
  check('surviving to dawn shows the night-held card', dawnShown && (await game(() => window.__scrapline.sim.nights)) === 1);
  await shot('16-dawn.png');
  await page.locator('.primary', { hasText: 'Keep building' }).tap();
  await sleep(200);
  check('strays are gone at dawn', (await game(() => window.__scrapline.sim.strays.count)) === 0);

  // the whole base survives a save and reload
  const saved3 = await game(() => {
    const ok = window.__scrapline.save();
    const s = window.__scrapline.sim;
    let n = 0;
    for (let id = 1; id < s.entities.high; id++) if (s.entities.kind[id]) n++;
    return { ok, entities: n, wires: s.graph.wireCount };
  });
  await page.goto(base + '?debug=1');
  await page.waitForFunction(() => window.__scrapline && window.__scrapline.ready, null, { timeout: 15000 });
  await sleep(700);
  const loaded3 = await game(() => {
    const s = window.__scrapline.sim;
    let n = 0;
    for (let id = 1; id < s.entities.high; id++) if (s.entities.kind[id]) n++;
    return { entities: n, wires: s.graph.wireCount, nights: s.nights };
  });
  check('factory and circuits survive a reload',
    saved3.ok && loaded3.entities === saved3.entities && loaded3.wires === saved3.wires && loaded3.wires === 3 && loaded3.nights === 1,
    JSON.stringify(loaded3));

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
