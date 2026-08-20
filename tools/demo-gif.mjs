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

/*
 * Labels live in the DOM, not in the GL buffers, so the rasteriser cannot draw them and a
 * nameless map badly undersells the tool - half of what a code map is for is reading the
 * names. So each frame records where the app placed its labels and ImageMagick draws the
 * real text on top afterwards. The positions are the app's own, not re-derived here.
 */
const FONT = ['/usr/share/fonts/Adwaita/AdwaitaSans-Regular.ttf',
  '/usr/share/fonts/noto/NotoSans-Regular.ttf',
  '/usr/share/fonts/gsfonts/NimbusSans-Regular.otf']
  .find((f) => fs.existsSync(f));
const labelFrames = [];

const LABEL_STYLE = {
  district: { size: 13, role: '--ink-2', upper: true },
  block: { size: 12, role: '--ink-2', upper: false },
  type: { size: 12, role: '--ink', upper: false },
  port: { size: 12, role: '--ink', upper: false },
  prop: { size: 13, role: '--prop-add', upper: false },
};

/** One rasterised frame, plus the label placement the app decided on for it. */
function capture() {
  frame();
  app.updateLabels();
  raster.renderScene();
  raster.writePng(path.join(FRAMES, String(shot).padStart(4, '0') + '.png'));

  const labels = [];
  for (const node of el('labels').children) {
    if (node.style.display === 'none') continue;
    const m = /translate\((-?[\d.]+)px, (-?[\d.]+)px\)$/.exec(node.style.transform || '');
    if (!m) continue;
    const cls = ['prop', 'port', 'district', 'block', 'type']
      .find((c) => (node.className || '').includes(c)) || 'type';
    const style = LABEL_STYLE[cls];
    // the font has no U+21E2, and a tofu box in the middle of the map looks like a bug
    let text = (node.textContent || '').replace(/⇢/g, '->');
    if (style.upper) text = text.toUpperCase();
    labels.push({ x: +m[1], y: +m[2], text, ...style });
  }
  labelFrames.push(labels);
  shot++;
}

/** Draws each frame's labels with ImageMagick, centred where the app put them. */
function annotate() {
  if (!FONT) {
    console.log('  no usable font found; leaving frames unlabelled');
    return;
  }
  const palette = app.palette();
  const hex = (rgb) => '#' + rgb.slice(0, 3)
    .map((c) => Math.round(c * 255).toString(16).padStart(2, '0')).join('');
  for (let i = 0; i < shot; i++) {
    const file = path.join(FRAMES, String(i).padStart(4, '0') + '.png');
    const args = [file, '-font', FONT, '-gravity', 'center'];
    for (const label of labelFrames[i]) {
      if (!label.text) continue;
      args.push('-pointsize', String(label.size),
        '-fill', hex(palette[label.role] || palette['--ink']),
        '-annotate', `+${Math.round(label.x - WIDTH / 2)}+${Math.round(label.y - HEIGHT / 2)}`,
        label.text);
    }
    args.push(file);
    execFileSync('magick', args);
  }
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

// 2. an agent draws a change on it. Pick real targets from the graph: the package with the
//    most traffic, a class inside it, and one of that class's neighbours.
const busiest = app.state.viewNodes.slice()
  .sort((a, b) => (b.out + b.in) - (a.out + a.in) || b.children - a.children)[0];
await app.openView(busiest);
let host = app.state.container;
for (let step = 0; step < 6 && !app.state.viewNodes.some((n) => n.layer === 3); step++) {
  const next = app.state.viewNodes.slice()
    .sort((a, b) => (b.out + b.in) - (a.out + a.in) || b.children - a.children)[0];
  if (!next || next.layer > 2) break;
  await app.openView(next);
  host = app.state.container;
}
// Skip test scaffolding when picking what the plan touches: "delete mockQuerier" is a
// nonsense plan, and a demo that shows a nonsense plan argues against the tool.
const classes = app.state.viewNodes.filter((n) => n.layer === 3
    && !/test|mock|fake|stub|_test/i.test(n.name))
  .sort((a, b) => (b.in + b.out) - (a.in + a.out));
const target = classes[0];
const partner = classes[1] || classes[0];

await post('/api/proposal/start', { title: 'Extract the retention policy' });
await post('/api/proposal/change', {
  op: 'add', parent: String(host.id), name: 'RetentionPolicy', kind: 'CLASS',
  note: 'one place to decide what gets kept',
});
await post('/api/proposal/change', {
  op: 'modify', target: String(target.id), note: 'ask the policy instead of deciding inline',
});
await post('/api/proposal/change', {
  op: 'connect', from: String(target.id), to: 'n1', edge_kind: 'CALL',
});
if (partner !== target) {
  await post('/api/proposal/change', {
    op: 'delete', target: String(partner.id), note: 'folded into the new policy',
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

console.log(`${shot} frames at ${WIDTH}x${HEIGHT}`);
annotate();

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
