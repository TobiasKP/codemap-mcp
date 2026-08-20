/*
 * Renders the README animation: a codebase map, an agent's proposal landing on it, and the
 * drill-down from "which module" to "which class".
 *
 * It drives the real app.js against a real server through the real HTTP API - the proposal
 * is posted exactly the way the MCP server posts it - and rasterises the actual instance
 * buffers frame by frame. So the animation cannot show something the tool does not do.
 *
 *   node tools/demo-gif.mjs --url http://localhost:7777 --out docs/demo.gif
 *
 * Needs ffmpeg on PATH for the PNG -> GIF step.
 */
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { createEnvironment, PALETTE, DARK_PALETTE } from './stub-dom.mjs';
import { createRasteriser, smoothstep } from './raster.mjs';
import { decorate, FONT } from './chrome.mjs';

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf('--' + name);
  return i >= 0 && args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : fallback;
};
const BASE = opt('url', 'http://localhost:7777').replace(/\/$/, '');
const OUT = opt('out', 'docs/demo.gif');
const WIDTH = +opt('width', 1100);
const HEIGHT = +opt('height', 690);
const FPS = +opt('fps', 20);
const DARK = args.includes('--dark');
const FRAMES = path.join(process.env.TMPDIR || '/tmp', 'codemap-gif-frames');

const env = createEnvironment({
  viewport: { w: WIDTH, h: HEIGHT },
  palette: DARK ? DARK_PALETTE : PALETTE,
  theme: DARK ? 'dark' : 'light',
  base: BASE,
});
const { app, el, frame } = env;
const raster = createRasteriser({
  app, width: WIDTH, height: HEIGHT, palette: app.palette(),
});

const sleep = (ms) => new Promise((r) => globalThis.setTimeout(r, ms));
async function until(predicate, ms = 30000) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    if (predicate()) return true;
    await sleep(40);
  }
  return false;
}
const post = (p, body) => fetch(BASE + p, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
}).then((r) => r.json());

let shot = 0;
fs.rmSync(FRAMES, { recursive: true, force: true });
fs.mkdirSync(FRAMES, { recursive: true });

/**
 * One frame: rasterise the GL buffers, then draw the labels and the UI chrome over them.
 *
 * The chrome is not decoration. The canvas shows *where* a change lands; the proposal panel
 * on the left is where it says what the change is and why, and the legend is where a ring
 * texture stops being a mystery. A recording of the canvas alone was the first version of
 * this, and it showed coloured circles nobody could interpret.
 */
function capture() {
  frame();
  app.updateLabels();
  raster.renderScene();
  const file = path.join(FRAMES, String(shot).padStart(4, '0') + '.png');
  raster.writePng(file);
  decorate(file, { app, el, width: WIDTH, height: HEIGHT });
  shot++;
}

/** Holds the current state for a beat, so a viewer can read it. */
function hold(seconds) {
  for (let i = 0; i < Math.round(seconds * FPS); i++) capture();
}

/**
 * Eases the camera from wherever it is to whatever fitView() would choose for the current
 * view. Opening a level is a fresh framing rather than a continuous move, so this is the
 * animation standing in for the cut - it reads as travel instead of a jump.
 */
function glideToFit(seconds) {
  const from = { ...app.state.view };
  app.fitView();
  const to = { ...app.state.view };
  const steps = Math.max(1, Math.round(seconds * FPS));
  for (let i = 1; i <= steps; i++) {
    const t = smoothstep(0, 1, i / steps);
    app.state.view = {
      cx: from.cx + (to.cx - from.cx) * t,
      cy: from.cy + (to.cy - from.cy) * t,
      scale: from.scale * Math.pow(to.scale / from.scale, t),   // geometric: feels linear
    };
    capture();
  }
  app.state.view = to;
}

// ------------------------------------------------------------------ the script

if (!await until(() => app.state.meta.project_name)) {
  console.error(`no data from ${BASE} - is the server running?`);
  process.exit(1);
}
await fetch(BASE + '/api/proposal', { method: 'DELETE' });
await app.pollProposal();
frame();

// 1. the map as scanned
const outermost = app.state.container;
hold(1.4);

/*
 * 2. An agent draws a change on it. The targets are real nodes from the graph, and the
 *    package is chosen to be *legible* rather than important: the busiest package in a
 *    large project holds a hundred classes and several hundred edges, which is a hairball,
 *    and the whole argument of this animation is that the lowest level tells you something.
 *    A dozen classes with a dozen edges between them is a view you can actually read.
 */
const LEGIBLE = { min: 6, max: 16 };
const DECLARED = ['CLASS', 'STRUCT', 'INTERFACE', 'TRAIT', 'RECORD', 'ENUM'];
// Benchmarks, samples and generated code are real code but make a poor illustration: a
// plan that reorganises a JMH harness says nothing about what the tool is for.
const skip = /test|bench|jmh|example|sample|demo|vendor|fixture|third_party|node_modules|generated/i;

/*
 * A child count in the legible range is necessary but not sufficient: those children may be
 * sub-packages rather than types, which lands the animation on another package view instead
 * of the class level it is supposed to reach. So open each candidate and check what is
 * actually in it. Only real declared types count - a FILE node is a source file with no type
 * in it, and "delete kuma.go" reads as a tool that does not know what it is looking at.
 */
const candidates = app.state.byLayer[2]
  .filter((n) => n.children >= LEGIBLE.min && n.children <= LEGIBLE.max && !skip.test(n.qname))
  .sort((a, b) => (b.in + b.out) - (a.in + a.out));

let host = null;
let classes = [];
for (const candidate of candidates.slice(0, 15)) {
  await app.openView(candidate);
  const found = app.state.viewNodes
    .filter((n) => n.layer === 3 && DECLARED.includes(n.kind)
      // a readable panel row is part of the point being made, and
      // AbstractSharedExecutorMicrobenchmark.DelegateHarnessExecutor is not one
      && n.name.length <= 28
      && !/test|mock|fake|stub/i.test(n.name))
    .sort((a, b) => (b.in + b.out) - (a.in + a.out));
  if (found.length >= 4) {
    host = candidate;
    classes = found;
    break;
  }
}
if (!host) {
  console.error('no package in this project reaches a legible class-level view');
  process.exit(1);
}
await app.openView(host);

const target = classes[0];
const partner = classes[1] || classes[0];

await post('/api/proposal/start', { title: 'Extract a shared policy object' });
await post('/api/proposal/change', {
  op: 'add', parent: String(host.id), name: 'Policy', kind: 'CLASS',
  note: 'one place for rules these types each re-implement',
});
await post('/api/proposal/change', {
  op: 'modify', target: String(target.id), note: 'delegate the decision to the policy',
});
await post('/api/proposal/change', {
  op: 'connect', from: String(target.id), to: 'n1', edge_kind: 'CALL',
});
if (partner !== target) {
  await post('/api/proposal/change', {
    op: 'delete', target: String(partner.id), note: 'redundant once the policy exists',
  });
}

// back out to the top, so the animation shows what the map shows: from up here you can
// already see which part of the project the plan touches
await app.openView(outermost);
await app.pollProposal();
hold(1.9);

// 3. drill in, one level at a time, following the change
const chain = [];
for (let node = host; node && node !== outermost; node = app.state.nodes.get(node.parent)) {
  chain.unshift(node);
}
for (const node of chain) {
  await app.openView(node);
  glideToFit(0.55);
  hold(0.85);
}

// 4. land on the change itself, and stay there. Deliberately no selection: the selection
//    highlight rings every neighbour in blue and orange, which is a different story and
//    would drown out the one green diamond this whole animation exists to show.
hold(3.2);

console.log(`${shot} frames at ${WIDTH}x${HEIGHT}`
  + (FONT ? '' : '  (no font found - frames are unlabelled)'));

// ------------------------------------------------------------------- encode

fs.mkdirSync(path.dirname(OUT), { recursive: true });
// two passes: build a palette from the whole animation, then map to it. One shared palette
// avoids the colour flicker a per-frame palette produces on flat backgrounds.
const paletteFile = path.join(FRAMES, 'palette.png');
execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-framerate', String(FPS),
  '-i', path.join(FRAMES, '%04d.png'),
  '-vf', 'palettegen=max_colors=192:stats_mode=diff', paletteFile]);
execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-framerate', String(FPS),
  '-i', path.join(FRAMES, '%04d.png'), '-i', paletteFile,
  '-lavfi', 'paletteuse=dither=bayer:bayer_scale=3', OUT]);

const bytes = fs.statSync(OUT).size;
console.log(`${OUT}  ${(bytes / 1024 / 1024).toFixed(2)} MB  `
  + `${(shot / FPS).toFixed(1)}s at ${FPS}fps`);
if (bytes > 9 * 1024 * 1024) {
  console.log('  warning: over ~9 MB, GitHub will be slow to render it inline');
}
