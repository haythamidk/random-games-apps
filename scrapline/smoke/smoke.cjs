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

  await page.goto(base + '?debug=1');
  await page.waitForFunction(() => window.__scrapline && window.__scrapline.ready, null, { timeout: 15000 });
  await sleep(1200);
  await shot('01-start.png');

  // --- 1.1 loop is ticking, overlay has numbers
  const s0 = await state();
  await sleep(600);
  const s1 = await state();
  check('fixed tick advances', s1.tick > s0.tick, `${s0.tick} -> ${s1.tick}`);
  const overlay = await page.locator('.debug').innerText();
  check('debug overlay shows stats', /fps/.test(overlay) && /draws \d+/.test(overlay), overlay.replace(/\n/g, ' | '));

  // --- 1.2 terrain draws within budget
  const perf0 = await page.evaluate(() => ({ ...window.__scrapline.perf }));
  check('terrain is drawn', perf0.triangles > 1000, `${perf0.triangles} tris`);
  check('draw calls within budget (<= 120)', perf0.calls > 0 && perf0.calls <= 120, `${perf0.calls} calls`);

  // --- 1.3 both thumbs at once: left stick forward, right drag to turn
  const stick = { x: 150, y: 290, id: 0 };
  const look = { x: 600, y: 160, id: 1 };
  await touch('touchStart', [stick]);
  await touch('touchStart', [stick, look]);
  for (let i = 1; i <= 10; i++) {
    stick.y = 290 - i * 4; // 40 px up: a walk, below the sprint threshold
    look.x = 600 + i * 9;
    await touch('touchMove', [stick, look]);
    await sleep(16);
  }
  await sleep(1500);
  await shot('02-moving.png');
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

  // jump button
  const yBefore = (await state()).y;
  const jb = await page.locator('.btn-jump').boundingBox();
  await touch('touchStart', [{ x: jb.x + jb.width / 2, y: jb.y + jb.height / 2, id: 3 }]);
  await sleep(220);
  const yMid = (await state()).y;
  await touch('touchEnd', []);
  check('jump button lifts the player', yMid - yBefore > 0.3, `${(yMid - yBefore).toFixed(2)} m`);
  await sleep(900);

  // --- 1.5 HUD + bag
  await page.evaluate(() => {
    const g = window.__scrapline.give;
    g(1, 64); // wood: one full stack + 14
    g(2, 15); // stone
    g(8, 1); // axe
  });
  await sleep(200);
  const hotbarText = await page.locator('.hotbar').innerText();
  check('seeded items appear in the hotbar', /50/.test(hotbarText) && /14/.test(hotbarText), hotbarText.replace(/\s+/g, ' '));

  await page.locator('.hotbar .slot').nth(2).tap();
  check('tapping a hotbar slot selects it', (await state()).selected === 2);

  await page.locator('.btn-bag').tap();
  await sleep(300);
  check('bag opens', await page.evaluate(() => window.__scrapline.bagOpen()));
  await shot('03-bag.png');

  // tap-move: slot 2 (wood x50) -> slot 12 (empty)
  const before = await state();
  await page.locator('.slot-grid .slot').nth(2).tap();
  await page.locator('.slot-grid .slot').nth(12).tap();
  const after = await state();
  check(
    'tap, tap moves a whole stack',
    after.counts[2] === 0 && after.ids[12] === before.ids[2] && after.counts[12] === before.counts[2],
    `slot2 ${before.counts[2]} -> ${after.counts[2]}, slot12 ${after.counts[12]}`,
  );

  // hold for half: slot 12 (x50) -> slot 20
  const sb = await page.locator('.slot-grid .slot').nth(12).boundingBox();
  await touch('touchStart', [{ x: sb.x + sb.width / 2, y: sb.y + sb.height / 2, id: 5 }]);
  await sleep(550);
  await touch('touchEnd', []);
  await sleep(100);
  await page.locator('.slot-grid .slot').nth(20).tap();
  const half = await state();
  check('hold picks up half a stack', half.counts[12] === 25 && half.counts[20] === 25, `${half.counts[12]} / ${half.counts[20]}`);
  await shot('04-bag-moved.png');

  await page.locator('.sheet-close').tap();
  await sleep(300);
  check('bag closes', !(await page.evaluate(() => window.__scrapline.bagOpen())));

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
