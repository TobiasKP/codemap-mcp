/*
 * Renders the map to a PNG without a browser or a GPU.
 *
 * A thin CLI over tools/raster.mjs, which does the rasterising and owns the draw order.
 * Useful for seeing what a scan looks like before opening a browser, and for spotting
 * layout regressions in review.
 *
 *   node tools/preview.mjs --url http://localhost:7777 --out map.png
 *   node tools/preview.mjs --url http://localhost:7777 --zoom module   # into the biggest module
 *   node tools/preview.mjs --url http://localhost:7777 --zoom class    # a class's functions
 *   node tools/preview.mjs --url http://localhost:7777 --zoom package --dark
 *   node tools/preview.mjs --url http://localhost:7777 --chrome --out shot.png   # + panels
 *   node tools/preview.mjs --url http://localhost:7777 --open io.netty.channel  # one view
 *
 * One blind spot worth knowing: it rasterises through worldToScreen(), so it cannot see a
 * vertex shader that disagrees with worldToScreen() about the screen transform. The smoke
 * test covers that with a source check.
 */
import { createEnvironment, PALETTE, DARK_PALETTE } from './stub-dom.mjs';
import { createRasteriser } from './raster.mjs';
import { decorate } from './chrome.mjs';

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf('--' + name);
  return i >= 0 && args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : fallback;
};
const flag = (name) => args.includes('--' + name);

const BASE = opt('url', 'http://localhost:7777');
const OUT = opt('out', 'map.png');
const ZOOM = opt('zoom', 'world');      // world | module | package | class
// a specific container by id or qualified name, for a reproducible before/after shot
const OPEN = opt('open', '');
const WIDTH = +opt('width', 1440);
const HEIGHT = +opt('height', 900);
const DARK = flag('dark');
// off by default: a bare canvas is what you want when checking layout, and the panels
// cover part of it. On for anything a human is meant to read.
const CHROME = flag('chrome');

const env = createEnvironment({
  viewport: { w: WIDTH, h: HEIGHT },
  palette: DARK ? DARK_PALETTE : PALETTE,
  theme: DARK ? 'dark' : 'light',
  base: BASE,
});
const { app, el, frame } = env;
// the app's own parsed palette, so the preview cannot drift from what it draws
const raster = createRasteriser({ app, width: WIDTH, height: HEIGHT, palette: app.palette() });

const sleep = (ms) => new Promise((r) => globalThis.setTimeout(r, ms));
async function until(predicate, ms = 25000) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    if (predicate()) return true;
    await sleep(40);
  }
  return false;
}

if (!await until(() => app.state.meta.project_name)) {
  console.error(`no data from ${BASE} - is the server running?`);
  process.exit(1);
}
frame();

if (OPEN) {
  const target = await fetch(BASE + '/api/resolve?ref=' + encodeURIComponent(OPEN))
    .then((r) => r.json());
  if (!target.id) {
    console.error(`cannot resolve --open ${OPEN}: ${target.error || 'not found'}`);
    process.exit(1);
  }
  const detail = await fetch(BASE + '/api/node?id=' + target.id).then((r) => r.json());
  app.ingestNodes([...(detail.parents || []).slice().reverse(), detail.node]);
  await app.openView(app.state.nodes.get(target.id));
} else if (ZOOM === 'module' || ZOOM === 'package' || ZOOM === 'class') {
  // boot may already have opened into the single top-level module, in which case the
  // "module" step is where we started
  if (!app.state.container) {
    const district = app.state.byLayer[1].slice()
      .sort((a, b) => b.children - a.children)[0];
    await app.openView(district);
  }
  if (ZOOM === 'package' || ZOOM === 'class') {
    const pkg = app.state.viewNodes.slice()
      .sort((a, b) => (b.out + b.in) - (a.out + a.in) || b.children - a.children)[0];
    await app.openView(pkg);
    if (ZOOM === 'class') {
      const cls = app.state.viewNodes.slice().sort((a, b) => b.children - a.children)[0];
      if (cls) await app.openView(cls);
    } else {
      const biggest = app.state.viewNodes.slice().sort((a, b) => b.in - a.in)[0];
      if (biggest) app.selectNode(biggest);
    }
  }
}
// an agent may have drawn a proposal on the map; the preview should show it
await app.pollProposal();
frame();

raster.renderScene();
raster.writePng(OUT);
if (CHROME) {
  app.updateLabels();
  decorate(OUT, { app, el, width: WIDTH, height: HEIGHT });
}

console.log(`${app.state.meta.project_name}  ${ZOOM}${DARK ? ' dark' : ''}`
  + `  level=${app.state.level}`
  + (app.state.container ? `  inside ${app.state.container.name}` : '')
  + `  scale=${app.state.view.scale.toFixed(4)}`);
console.log(`  entities ${app.batches.nodes.count}  edges ${app.batches.edges.count}`
  + `  outside ${app.batches.externals.count}`);
if (app.overlayActive()) {
  const rings = app.batches.statusAdd.count + app.batches.statusModify.count
    + app.batches.statusDelete.count + app.batches.statusMark.count;
  console.log(`  proposal "${app.state.proposal.title}"`
    + `  ${app.state.proposal.changes.length} changes  ${rings} lit here`
    + `  ${app.batches.proposedNodes.count} new  ${app.batches.proposedEdges.count} arrows`);
}
console.log(`  ${raster.paintedPercent().toFixed(1)}% of pixels differ from the background`
  + ` -> ${OUT}`);
