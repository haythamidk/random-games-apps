const { chromium } = require('playwright'); const http = require('http'); const fs = require('fs'); const path = require('path');
(async () => {
  const root = process.argv[2]; const scenes = process.argv[3].split(','); const outDir = process.argv[4];
  const srv = http.createServer((req, res) => { const f = path.join(root, decodeURIComponent(req.url.split('?')[0])); fs.readFile(f, (e, d) => { if (e) { res.writeHead(404); res.end(); } else { res.writeHead(200, { 'Content-Type': f.endsWith('.html') ? 'text/html' : 'application/octet-stream' }); res.end(d); } }); }).listen(0);
  const port = srv.address().port;
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  for (const s of scenes) {
    const page = await browser.newPage({ viewport: { width: 1560, height: 720 } });
    page.on('console', m => { if (m.type() === 'error') console.log('console:', m.text()); });
    await page.goto(`http://127.0.0.1:${port}/render.html?scene=${s}`);
    await page.waitForFunction(() => window.DONE, null, { timeout: 180000 });
    console.log(s, await page.evaluate(() => window.DONE));
    await page.screenshot({ path: path.join(outDir, s + '.png') });
    await page.close();
  }
  await browser.close(); srv.close();
})();
