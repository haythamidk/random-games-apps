const { chromium } = require('playwright');
const fs = require('fs'); const path = require('path');
(async () => {
  const dir = process.argv[2];
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const page = await browser.newPage();
  const names = [...new Set(fs.readdirSync(dir).map(f => f.replace(/\.(vert|frag)$/, '')))];
  const src = {};
  for (const n of names) src[n] = { vs: fs.readFileSync(path.join(dir, n + '.vert'), 'utf8'), fs: fs.readFileSync(path.join(dir, n + '.frag'), 'utf8') };
  const res = await page.evaluate((src) => {
    const c = document.createElement('canvas'); const gl = c.getContext('webgl2');
    if (!gl) return { fatal: 'no webgl2' };
    const out = {};
    for (const n in src) {
      const mk = (t, s) => { const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh); return [sh, gl.getShaderParameter(sh, gl.COMPILE_STATUS), gl.getShaderInfoLog(sh)]; };
      const [v, vok, vlog] = mk(gl.VERTEX_SHADER, src[n].vs); const [f, fok, flog] = mk(gl.FRAGMENT_SHADER, src[n].fs);
      let link = '';
      if (vok && fok) { const p = gl.createProgram(); gl.attachShader(p, v); gl.attachShader(p, f); gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) link = gl.getProgramInfoLog(p); }
      out[n] = { vok, vlog, fok, flog, link };
    }
    return out;
  }, src);
  let bad = 0;
  for (const n in res) { const r = res[n]; if (r.vok && r.fok && !r.link) console.log('ok   ' + n); else { bad++; console.log('FAIL ' + n + '\n' + (r.vlog||'') + (r.flog||'') + (r.link||'')); } }
  if (res.fatal) console.log(res.fatal);
  await browser.close(); process.exit(bad ? 1 : 0);
})();
