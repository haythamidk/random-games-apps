// Packs the production build into one self-contained HTML fragment
// (styles and script inlined, no external requests) for hosts that serve a
// single page, such as a shareable preview. Run after `vite build`.
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const dist = join(dirname(fileURLToPath(import.meta.url)), '..', 'dist');
const assets = readdirSync(join(dist, 'assets'));
const css = readFileSync(join(dist, 'assets', assets.find((f) => f.endsWith('.css'))), 'utf8');
const js = readFileSync(join(dist, 'assets', assets.find((f) => f.endsWith('.js'))), 'utf8')
  // A literal closing tag inside the bundle would end the inline script early.
  .replace(/<\/script/gi, '<\\/script');

const page = `<title>Scrapline</title>
<meta name="claude:viewport-fit-sides" content="cover">
<style>
${css}
body{padding-left:max(16px,env(safe-area-inset-left,0px));padding-right:max(16px,env(safe-area-inset-right,0px))}
</style>
<canvas id="scene"></canvas>
<div id="input-layer"></div>
<div id="ui"></div>
<div id="rotate-hint">Plays best in landscape</div>
<script type="module">
${js}
</script>
`;
const out = join(dist, 'scrapline.html');
writeFileSync(out, page);
console.log(`${out}  ${(page.length / 1024).toFixed(0)} KB`);
