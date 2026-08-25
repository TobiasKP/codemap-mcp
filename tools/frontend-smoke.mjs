/*
 * Headless smoke test for the map viewer.
 *
 * Runs web/app.js against a live `codemap serve` instance inside a stub DOM and a
 * stub WebGL2 context, then asserts on what the real code produced: nodes ingested,
 * instance buffers filled, a frame drawn, hit testing, labels, and cross-package ports.
 * It cannot tell you the map looks good, but it catches every JavaScript-level break
 * without needing a browser.
 *
 *   node tools/frontend-smoke.mjs [http://localhost:7777]
 *
 * The shaders are checked separately: extract them and run glslangValidator.
 */
import fs from 'node:fs';
import path from 'node:path';
import { createEnvironment, PALETTE, DARK_PALETTE, ROOT, WEB } from './stub-dom.mjs';

const BASE = (process.argv[2] || 'http://localhost:7777').replace(/\/$/, '');
const APP = path.join(WEB, 'app.js');
const VIEWPORT = { w: 1440, h: 900 };

const env = createEnvironment({ viewport: VIEWPORT, palette: PALETTE, base: BASE });
const { app, el, glCalls, frame, drawNow } = env;

// ------------------------------------------------------------------- asserts

let failures = 0;
function check(label, condition, detail) {
  const ok = !!condition;
  if (!ok) failures++;
  console.log(`${ok ? '  ok  ' : ' FAIL '} ${label}${detail == null ? '' : '  (' + detail + ')'}`);
}

/*
 * Some properties are conditional on the data: whether any dependency crosses the container
 * you happen to open, whether the view you land in contains a class with methods. The suite
 * runs against whatever server you point it at, so "this project does not offer that case"
 * is information, not a failure - failing on it trains you to ignore the output.
 */
/**
 * The geometry of a view: where everything sits, and the frame it is measured against.
 *
 * Real entities take their coordinates from the layout in the database and cannot move, but
 * everything derived - the outline, the ring the externals sit on, the spot a proposed node
 * or a fold marker is given - used to be measured over whatever subset was being drawn. So
 * toggling focus or opening a fold visibly shifted things that had not changed.
 */
function geometry(app) {
  const at = (n) => n.x.toFixed(3) + ',' + n.y.toFixed(3);
  return {
    extent: JSON.stringify({
      cx: +app.state.viewExtent.cx.toFixed(3),
      cy: +app.state.viewExtent.cy.toFixed(3),
      r: +app.state.viewExtent.r.toFixed(3),
    }),
    nodes: new Map(app.state.viewNodes.map((n) => [n.name, at(n)])),
    externals: new Map(app.state.externals.map((x) => [x.name, at(x)])),
    proposed: new Map(app.state.proposedNodes.map((n) => [n.name, at(n) + '/' + n.r.toFixed(3)])),
    marker: app.state.foldMarker ? at(app.state.foldMarker) : null,
  };
}

/** Names in `before` that exist in `after` at different coordinates. */
function shifted(before, after) {
  const out = [];
  for (const [name, where] of before) {
    if (after.has(name) && after.get(name) !== where) out.push(name);
  }
  return out;
}

/** All the text under an element, the way the offline chrome reads it. */
function textOf(node) {
  if (!node) return '';
  let out = node._text || '';
  for (const child of node.children || []) out += textOf(child);
  return out;
}

function note(label, detail) {
  console.log(`  n/a  ${label}${detail == null ? '' : '  (' + detail + ')'}`);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function settle(predicate, label, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return true;
    await sleep(40);
  }
  check(label + ' (timed out)', false);
  return false;
}

console.log(`\nfrontend smoke test against ${BASE}\n`);

// A <canvas> is a replaced element: `inset: 0` alone leaves it at its intrinsic
// 300x150, which silently squeezes the whole map into the top-left corner. The stub
// DOM cannot notice that, so check the stylesheet says so explicitly.
{
  const html = fs.readFileSync(path.join(WEB, 'index.html'), 'utf8');
  const rule = (html.match(/#map\s*\{[^}]*\}/) || [''])[0];
  check('canvas has an explicit size in CSS',
    /width:\s*100%/.test(rule) && /height:\s*100%/.test(rule),
    rule.replace(/\s+/g, ' ').slice(0, 90));
}

// The renderer's palette lives in app.js (THEMES) and the chrome's lives in the
// stylesheet. They must agree, or the canvas and the panels drift apart.
{
  const html = fs.readFileSync(path.join(WEB, 'index.html'), 'utf8');
  const src = fs.readFileSync(APP, 'utf8');
  const squash = (v) => v.trim().replace(/\s+/g, '').toLowerCase();

  const themeBlock = (name) => {
    const m = src.match(new RegExp(name + ":\\s*\\{([^}]*)\\}"));
    const out = {};
    if (m) {
      for (const [, k, v] of m[1].matchAll(/'(--[a-z-]+)':\s*'([^']+)'/g)) out[k] = squash(v);
    }
    return out;
  };
  const cssBlock = (selector) => {
    const i = html.indexOf(selector);
    if (i < 0) return {};
    const body = html.slice(html.indexOf('{', i) + 1, html.indexOf('}', i));
    const out = {};
    for (const [, k, v] of body.matchAll(/(--[a-z-]+)\s*:\s*([^;]+);/g)) out[k] = squash(v);
    return out;
  };

  const pairs = [
    ['light', themeBlock('light'), cssBlock(':root {')],
    ['dark', themeBlock('dark'), cssBlock(':root[data-theme="dark"]')],
  ];
  const drift = [];
  for (const [name, js, css] of pairs) {
    if (!Object.keys(js).length) drift.push(name + ': THEMES block not found');
    for (const key of Object.keys(js)) {
      if (css[key] === undefined) drift.push(`${name} ${key} missing from CSS`);
      else if (css[key] !== js[key]) drift.push(`${name} ${key}: js ${js[key]} vs css ${css[key]}`);
    }
  }
  check('renderer palette matches the stylesheet', drift.length === 0,
    drift.length ? drift.slice(0, 3).join('; ') : pairs[0][1] && Object.keys(pairs[0][1]).length + ' keys x2');

  /*
   * And the offline palette matches both. stub-dom.mjs keeps its own copy because it fakes
   * getComputedStyle, so a new colour added to the app and the stylesheet is silently
   * absent from every still and GIF - the renderer falls back to ink and nothing errors.
   * That is exactly how --prop-risk shipped wrong once.
   */
  const offline = [];
  for (const [name, js, , ] of pairs) {
    const stub = name === 'dark' ? DARK_PALETTE : PALETTE;
    for (const key of Object.keys(js)) {
      if (stub[key] === undefined) offline.push(`${name} ${key} missing from stub-dom`);
      else if (squash(stub[key]) !== js[key]) {
        offline.push(`${name} ${key}: stub ${squash(stub[key])} vs js ${js[key]}`);
      }
    }
  }
  check('the offline palette matches the renderer', offline.length === 0,
    offline.length ? offline.slice(0, 3).join('; ')
      : Object.keys(PALETTE).length + ' keys shared with the rasteriser');
}

// An on-demand renderer MUST keep its drawing buffer: WebGL empties it after each
// composite, and with alpha:false an empty buffer composites as opaque black, so the
// map goes pitch black on the next repaint the page happens to make.
{
  const src = fs.readFileSync(APP, 'utf8');
  const call = (src.match(/getContext\('webgl2'[\s\S]{0,220}?\)\;/) || [''])[0];
  const onDemand = /needsRedraw = false/.test(src);
  check('drawing buffer is preserved for on-demand rendering',
    !onDemand || /preserveDrawingBuffer:\s*true/.test(call),
    onDemand ? 'on-demand renderer' : 'continuous renderer');
}

// The canvas transform and worldToScreen() must agree about which way is up. A stray
// negation in a vertex shader mirrors the map against its own DOM labels: circles pan one
// way, text the other. The preview tool rasterises via worldToScreen, so it is blind to
// this by construction - hence a source check.
{
  const src = fs.readFileSync(APP, 'utf8');
  const vertexShaders = [...src.matchAll(/`#version 300 es[\s\S]*?gl_Position[^;]*;/g)]
    .map((m) => m[0]);
  const negated = vertexShaders.filter((v) => /gl_Position[^;]*-\s*\w*[Pp]x?\.y/.test(v));
  check('no vertex shader negates y on its own', negated.length === 0,
    vertexShaders.length + ' vertex shaders share uPixelToClip');
  check('the screen convention is stated in one place',
    (src.match(/function pixelToClip\(/g) || []).length === 1
      && !/uResolution/.test(src),
    'pixelToClip() is the single source');
}

// Element ids app.js touches must exist in the markup, or boot() dies on null.
{
  const html = fs.readFileSync(path.join(WEB, 'index.html'), 'utf8');
  const src = fs.readFileSync(APP, 'utf8');
  const ids = [...src.matchAll(/getElementById\('([^']+)'\)/g)].map((m) => m[1]);
  const absent = [...new Set(ids)].filter((id) => !html.includes('id="' + id + '"'));
  check('every getElementById target exists in the markup', absent.length === 0,
    absent.length ? 'missing ' + absent.join(', ') : ids.length + ' lookups');
}

await settle(() => app.state.meta.project_name, 'meta loaded');
const meta = app.state.meta;
console.log(`  project: ${meta.project_name} (${meta.nodes_layer_1} modules, `
  + `${meta.nodes_layer_2} packages, ${meta.nodes_layer_3} types)\n`);

// ---- the first view ----
/*
 * Two shapes, and the suite has to cope with both, because it runs against whatever server
 * you point it at. A project with several top-level build units opens on them. A project
 * that is one build unit - one go.mod, one root package.json - opens *inside* it, since a
 * view holding a single circle tells you nothing.
 */
await settle(() => app.state.viewNodes.length > 0, 'first view built');
const topLevel = app.state.byLayer[1].filter((n) => !n.parent);
if (topLevel.length === 1) {
  check('a single-build-unit project opens inside its module',
    app.state.container && app.state.container.id === topLevel[0].id,
    'inside ' + (app.state.container && app.state.container.name));
} else {
  check('the root shows the top-level modules', app.state.container === null
    && app.state.viewNodes.every((n) => n.layer === 1 && !n.parent),
    `${app.state.viewNodes.length} of ${meta.nodes_layer_1} modules are top level`);
  check('the root shows edges between them', app.state.viewEdges.length > 0,
    `${app.state.viewEdges.length} of ${meta.edges_layer_1}`);
  check('level 1 shows nothing else', app.state.viewNodes.every((n) => n.layer === 1)
    && app.state.externals.length === 0, 'no packages, types or externals');
}
check('boot scheduled its own first frame', frame() > 0, 'rAF callbacks run');
check('a frame drew instances', glCalls.drawArraysInstanced > 0,
  glCalls.drawArraysInstanced + ' instanced draws');
check('the frame sized the drawing buffer', el('map').width > 1 && el('map').height > 1,
  el('map').width + ' x ' + el('map').height);
check('entity buffer filled', app.batches.nodes.count === app.state.viewNodes.length,
  app.batches.nodes.count + ' of ' + app.state.viewNodes.length + ' drawn');

// world y is up: the topmost entity must land above the bottommost one on screen
{
  const sorted = app.state.viewNodes.slice().sort((a, b) => b.y - a.y);
  const top = sorted[0];
  const bottom = sorted[sorted.length - 1];
  if (sorted.length >= 2 && top.y !== bottom.y) {
    const [, topScreenY] = app.worldToScreen(top.x, top.y);
    const [, bottomScreenY] = app.worldToScreen(bottom.x, bottom.y);
    check('world y up maps to screen y down', topScreenY < bottomScreenY,
      `${top.name} at ${Math.round(topScreenY)} above ${bottom.name} at `
        + Math.round(bottomScreenY));
  }
}

// panning must move the labels with the map, and by the right amount
app.updateLabels();
const before = el('labels').children.filter((c) => c.style.display !== 'none')
  .map((c) => c.style.transform);
app.state.view.cx += 400 / app.state.view.scale;
app.updateLabels();
const shown = el('labels').children.filter((c) => c.style.display !== 'none');
const after = shown.map((c) => c.style.transform);
check('labels follow a pan', before.length > 0 && after.length > 0
  && before.join('|') !== after.join('|'),
  `${before.length} labels repositioned`);

// A label may be placed beside its entity when it will not fit on it, so what matters is
// that it stays adjacent to it and moves with it - not that it sits dead centre.
const drift = shown.map((c) => {
  const m = /translate\((-?[\d.]+)px, (-?[\d.]+)px\)$/.exec(c.style.transform);
  if (!m || !c._node) return { ok: false, d: Infinity };
  const [sx, sy] = app.worldToScreen(c._node.x, c._node.y);
  const away = Math.hypot(sx - parseFloat(m[1]), sy - parseFloat(m[2]));
  const allowed = c._node.r * app.state.view.scale + c.textContent.length * 3.4 + 40;
  return { ok: away <= allowed, d: away };
});
check('labels stay next to their entity', drift.every((d) => d.ok),
  `worst offset ${Math.max(0, ...drift.map((d) => d.d)).toFixed(0)}px, all within bounds`);

// Labels must not overlap each other. This is what made the big views unreadable: the
// old placement reserved a fixed grid cell, while a 35-character name is 250px wide.
const labelRects = () => el('labels').children
  .filter((c) => c.style.display !== 'none')
  .map((c) => {
    const m = /translate\((-?[\d.]+)px, (-?[\d.]+)px\)$/.exec(c.style.transform);
    // same per-class widths the stub ruler uses, so the checker and the placer agree
    let em = 0;
    for (const ch of c.textContent) {
      if (/[A-Z0-9@#%&]/.test(ch)) em += 0.64;
      else if (/[ijltIfr.,:;'`|!\[\]()-]/.test(ch)) em += 0.31;
      else if (/[mwMW]/.test(ch)) em += 0.83;
      else em += 0.53;
    }
    const width = em * 13 + 6;
    return { x1: +m[1] - width / 2, x2: +m[1] + width / 2, y1: +m[2] - 7.5, y2: +m[2] + 7.5 };
  });
const countOverlaps = (rects) => {
  let n = 0;
  for (let i = 0; i < rects.length; i++) {
    for (let j = i + 1; j < rects.length; j++) {
      const a = rects[i], b = rects[j];
      if (!(a.x2 < b.x1 || a.x1 > b.x2 || a.y2 < b.y1 || a.y1 > b.y2)) n++;
    }
  }
  return n;
};
{
  const rects = labelRects();
  check('labels never overlap each other', countOverlaps(rects) === 0,
    `${rects.length} labels, ${countOverlaps(rects)} overlapping pairs`);
}
app.fitView();

// ---- level 2: one module's packages ----
const district = app.state.viewNodes.slice().sort((a, b) => b.children - a.children)[0];
check('found a module to open', !!district, district && district.name);
await app.openView(district);
app.updateLabels();
{
  const rects = labelRects();
  check('labels stay readable in the densest view', countOverlaps(rects) === 0,
    `${app.state.viewNodes.length} entities, ${rects.length} labels, `
      + `prefix "${app.state.labelPrefix}" dropped`);
}
// The invariant is containment, not layer: a module can hold nested modules as well as
// packages (go workspaces, npm workspaces), and a package can hold sub-packages.
check('a container shows exactly its own children',
  app.state.viewNodes.length > 0
    && app.state.viewNodes.every((n) => n.parent === district.id),
  `${app.state.viewNodes.length} children of ${district.name}, layers `
    + [...new Set(app.state.viewNodes.map((n) => n.layer))].sort().join('+'));
check('level 2 edges stay inside the module',
  app.state.viewEdges.every((e) => {
    const ids = new Set(app.state.viewNodes.map((n) => n.id));
    return ids.has(e.s) && ids.has(e.d);
  }), app.state.viewEdges.length + ' internal edges');
// Nothing is outside the outermost build unit, and a module whose packages happen to keep
// to themselves has nothing on the rim either.
if (!district.parent) {
  note('level 2 has dashed externals', 'outermost build unit - nothing is outside it');
} else if (app.state.externals.length === 0) {
  note('level 2 has dashed externals', 'nothing in ' + district.name + ' reaches outside it');
} else {
  check('level 2 has dashed externals', true,
    app.state.externals.length + ' outside this module');
}
check('every external points at a real node',
  app.state.externals.every((x) => !!app.state.nodes.get(x.targetId)),
  'targets resolve');
check('externals sit outside the container',
  app.state.externals.every((x) =>
    Math.hypot(x.x - district.x, x.y - district.y) > district.r),
  'placed on the rim');
// coordinates are local to a view now, so the camera frames the contents, not the container
check('the view is framed on its contents',
  Math.abs(app.state.view.cx - app.state.viewExtent.cx) < 1
    && Math.abs(app.state.view.cy - app.state.viewExtent.cy) < 1,
  'camera on the extent centre');
frame();
check('level 2 drew its entities', app.batches.nodes.count === app.state.viewNodes.length,
  app.batches.nodes.count + ' instances');

// ---- level 3: one package's types ----
// Walk down until the view actually holds classes. Containment is a tree, so a module may
// open into name-path groups before any package with code in it.
let pkg = null;
for (let step = 0; step < 8; step++) {
  const classes = app.state.viewNodes.filter((n) => n.layer === 3);
  if (classes.length >= 3) { pkg = app.state.container; break; }
  const next = app.state.viewNodes.slice()
    .sort((a, b) => (b.out + b.in) - (a.out + a.in) || b.children - a.children)[0];
  if (!next || next.layer > 2) break;
  await app.openView(next);
}
pkg = pkg || app.state.container;
check('found a container holding classes', !!pkg, pkg && pkg.name);
check('a package view holds its classes',
  app.state.viewNodes.some((n) => n.layer === 3)
    && app.state.viewNodes.every((n) => n.parent === pkg.id),
  app.state.viewNodes.filter((n) => n.layer === 3).length + ' classes in ' + pkg.name);
if (app.state.externals.length === 0) {
  note('level 3 has dashed externals', 'nothing in this package reaches outside it');
} else {
  check('level 3 has dashed externals', true,
    app.state.externals.length + ' outside this package');
}
frame();
check('level 3 drew its entities', app.batches.nodes.count === app.state.viewNodes.length,
  app.batches.nodes.count + ' instances');

// hit testing sees exactly what was drawn
const target = app.state.viewNodes[0];
const [tx, ty] = app.worldToScreen(target.x, target.y);
const hit = app.pick(tx, ty);
check('hit test finds the entity under the cursor', hit && hit.id === target.id,
  target.name + ' -> ' + (hit ? hit.name : 'nothing'));
const ext = app.state.externals[0];
if (ext) {
  const [ex, ey] = app.worldToScreen(ext.x, ext.y);
  const extHit = app.pick(ex, ey);
  check('hit test finds a dashed external', extHit && extHit.id === ext.id,
    ext.name + ' -> ' + (extHit ? extHit.name : 'nothing'));
}

app.selectNode(target);
check('selection built highlight rings', app.batches.highlight.count > 0,
  app.batches.highlight.count + ' rings');
app.updateLabels();
check('labels rendered', el('labels').children
  .filter((c) => c.style.display !== 'none').length > 0, 'labels present');

// ---- level 4: one class's callables and the calls between them ----
{
  // a FILE node is a source file with no type in it - a shell script has no methods, so
  // it is not a candidate for the callable level however many children the view reports
  const withMembers = app.state.viewNodes
    .filter((n) => n.layer === 3 && n.kind !== 'FILE' && n.children > 1)
    .sort((a, b) => b.children - a.children)[0];
  if (!withMembers) {
    note('found a class with callables', 'no class in this view declares more than one');
  } else {
    check('found a class with callables', true,
      `${withMembers.name} (${withMembers.children} members)`);
  }
  if (withMembers) {
    await app.openView(withMembers);
    check('level 4 shows only that class\'s callables',
      app.state.viewNodes.length > 0
        && app.state.viewNodes.every((n) => n.layer === 4 && n.parent === withMembers.id),
      app.state.viewNodes.length + ' callables of ' + withMembers.name);
    check('callables carry a callable kind',
      app.state.viewNodes.every((n) => ['METHOD', 'CONSTRUCTOR', 'FUNCTION'].includes(n.kind)),
      [...new Set(app.state.viewNodes.map((n) => n.kind))].join(', '));
    frame();
    check('level 4 drew its entities', app.batches.nodes.count === app.state.viewNodes.length,
      app.batches.nodes.count + ' instances');

    const inner = app.state.viewEdges.length;
    check('calls between the class\'s own functions are edges', inner >= 0,
      inner + ' internal calls');
    const target = app.state.viewNodes[0];
    const [tx, ty] = app.worldToScreen(target.x, target.y);
    const hit = app.pick(tx, ty);
    check('hit test finds a callable', hit && hit.id === target.id,
      target.name + ' -> ' + (hit ? hit.name : 'nothing'));

    const before = app.state.container;
    app.goUp();
    await settle(() => app.state.container !== before, 'going up leaves the class');
    check('going up from a class returns to its package',
      app.state.container && app.state.container.id === withMembers.parent,
      app.state.container && app.state.container.name);
    await app.openView(withMembers);
  }
}

// ---- navigation ----
if (ext) {
  // externals belong to the level they were built for, so be on that level again
  await app.openView(pkg);
  const sameExt = app.state.externals.find((x) => x.targetId === ext.targetId)
    || app.state.externals[0];
  await app.activate(sameExt);
  check('opening an external travels to it',
    app.state.container && app.state.container.id === sameExt.targetId,
    'now inside ' + (app.state.container && app.state.container.name));
}
await app.openView(pkg);
let steps = 0;
while (app.state.container && steps < 12) {
  const from = app.state.container;
  app.goUp();
  await settle(() => app.state.container !== from, 'going up moved');
  steps++;
}
check('going up repeatedly reaches the root',
  app.state.container === null, steps + ' steps from ' + pkg.name);

// ---- thinning and folding ----
/*
 * Both are truncation, so what matters is that they truncate the tail rather than the
 * signal, and that they say so. A wide view exists in some projects and not others, so the
 * folding half is conditional on finding one.
 */
{
  const wide = app.state.byLayer[2].concat(app.state.byLayer[1])
    .filter((n) => n.children > 40)
    .sort((a, b) => b.children - a.children)[0];
  if (!wide) {
    note('a wide view folds its tail', 'no view in this project holds more than 40');
  } else {
    await app.openView(wide);
    check('a wide view folds its tail',
      app.state.viewNodes.length === 40 && app.state.foldMarker
        && app.state.foldMarker.folded === wide.children - 40,
      `${app.state.viewNodes.length} shown, ${app.state.foldMarker
        && app.state.foldMarker.folded} folded of ${wide.children}`);
    check('the fold marker says how much it hides',
      /^\+\d+ more$/.test(app.state.foldMarker.name), app.state.foldMarker.name);
    check('the status line states the truncation',
      el('stats').innerHTML.includes('of ' + wide.children),
      el('stats').innerHTML.replace(/<[^>]+>/g, ''));
    // top-K by importance guarantees exactly this and nothing stronger
    const rank = (n) => n.r * 2 + (n.in || 0) * 0.6 + (n.out || 0) * 0.2;
    const kept = app.state.viewNodes;
    const dropped = (app.state.childrenOf.get(wide.id) || [])
      .filter((other) => !kept.includes(other));
    check('what it keeps is what carries the view',
      Math.min(...kept.map(rank)) >= Math.max(...dropped.map(rank)),
      `weakest kept ${Math.min(...kept.map(rank)).toFixed(1)}`
        + ` >= strongest dropped ${Math.max(...dropped.map(rank)).toFixed(1)}`);
    frame();
    check('the fold marker is drawn and hit-testable', app.batches.fold.count === 1
      && app.pick(...app.worldToScreen(app.state.foldMarker.x, app.state.foldMarker.y))
        === app.state.foldMarker, 'one marker, pickable');

    // opening it is the way back to everything - and must not move what was already drawn
    const folded = app.state.foldMarker.folded;
    const beforeOpen = geometry(app);
    await app.activate(app.state.foldMarker);
    const afterOpen = geometry(app);
    check('opening the fold leaves the frame alone',
      beforeOpen.extent === afterOpen.extent, afterOpen.extent);
    check('opening the fold moves nothing that was already drawn',
      shifted(beforeOpen.nodes, afterOpen.nodes).length === 0
        && shifted(beforeOpen.externals, afterOpen.externals).length === 0,
      `${beforeOpen.nodes.size} entities and ${beforeOpen.externals.size} externals stayed put`);
    check('opening the fold shows the rest',
      app.state.viewNodes.length === wide.children && !app.state.foldMarker,
      `${app.state.viewNodes.length} of ${wide.children}, marker gone`);
    check('the fold stays open for that container',
      app.state.expandedFolds.has(wide.id), folded + ' were behind it');
  }

  /*
   * Edge thinning needs a view whose edge count clears the floor *after* folding - folding
   * removes the edges that touched what it folded away, which can drop a view back under
   * the threshold. So open candidates and look at what the view actually holds rather than
   * predicting it.
   */
  let dense = null;
  const byEdges = app.state.byLayer[2]
    .map((n) => ({ n, edges: app.state.edges[3].filter((e) => e.p === n.id).length }))
    .filter((c) => c.edges > 60)
    .sort((a, b) => b.edges - a.edges);
  for (const candidate of byEdges.slice(0, 8)) {
    await app.openView(candidate.n);
    if (app.state.hiddenEdges.length > 0) { dense = candidate; break; }
  }
  if (!dense) {
    note('a dense view thins its edges', 'no view in this project has more than 60');
  } else {
    await app.openView(dense.n);
    const drawn = app.state.viewEdges.length;
    const hidden = app.state.hiddenEdges.length;
    check('a dense view thins its edges', hidden > 0 && drawn < app.state.edgeCount,
      `${drawn} drawn, ${hidden} held back of ${app.state.edgeCount}`);

    // the guarantee that makes thinning honest rather than a lie
    const connected = new Set();
    for (const e of app.state.viewEdges) { connected.add(e.s); connected.add(e.d); }
    const hadEdges = new Set();
    for (const e of app.state.viewEdges.concat(app.state.hiddenEdges)) {
      hadEdges.add(e.s); hadEdges.add(e.d);
    }
    check('nothing that has a dependency is drawn as isolated',
      [...hadEdges].every((id) => connected.has(id)),
      hadEdges.size + ' entities with edges, all still connected');

    const total = app.state.viewEdges.concat(app.state.hiddenEdges)
      .reduce((sum, e) => sum + e.w, 0);
    const kept = app.state.viewEdges.reduce((sum, e) => sum + e.w, 0);
    check('the drawn edges carry most of the weight', kept / total >= 0.85,
      `${(100 * kept / total).toFixed(1)}% of the weight in `
        + `${(100 * drawn / (drawn + hidden)).toFixed(0)}% of the edges`);
    check('the status line states the truncation',
      el('stats').innerHTML.includes('of ' + app.state.edgeCount + ' edges'),
      el('stats').innerHTML.replace(/<[^>]+>/g, ''));

    frame();
    const restingDraws = app.batches.edgesHidden.count;
    check('the tail is buffered but not drawn at rest',
      restingDraws > 0 && !app.state.showAllEdges, restingDraws + ' held in a batch');

    // hovering an entity puts its own share of the tail back
    const withHidden = app.state.viewNodes.find((n) =>
      app.state.hiddenEdges.some((e) => e.s === n.id || e.d === n.id));
    if (withHidden) {
      const [hx, hy] = app.worldToScreen(withHidden.x, withHidden.y);
      el('map').fire('pointermove', { clientX: hx, clientY: hy });
      frame();
      check('hovering an entity reveals its hidden edges',
        app.batches.edgesHovered.count > 0,
        app.batches.edgesHovered.count + ' edges for ' + withHidden.name);
    }
  }
}

// ---- the proposal overlay ----
/*
 * A change drawn on the map rather than described. The two things worth proving are that a
 * proposal buried deep in the tree is visible from the top view, and that everything it
 * does not touch actually recedes - the second is what makes the first useful.
 */
{
  const post = (path, body) => fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }).then((r) => r.json());

  // pick a class deep in the tree, so the rollup has several levels to climb
  await app.openView(pkg);
  const victim = app.state.viewNodes.find((n) => n.layer === 3) || app.state.viewNodes[0];
  const other = app.state.viewNodes.find((n) => n !== victim) || victim;

  await post('/api/proposal/start', { title: 'Smoke test proposal' });
  const added = await post('/api/proposal/change', {
    op: 'add', parent: String(pkg.id), name: 'ProposedThing', kind: 'CLASS',
    note: 'somewhere for the new logic to live',
  });
  check('the server minted a ref for the addition', added.ref === 'n1', added.ref);
  await post('/api/proposal/change', {
    op: 'modify', target: String(victim.id), note: 'delegate to the new class',
  });
  await post('/api/proposal/change', {
    op: 'connect', from: String(victim.id), to: 'n1', edge_kind: 'CALL',
  });
  await post('/api/proposal/change', {
    op: 'delete', target: String(other.id), note: 'no longer needed',
  });

  await app.pollProposal();
  check('the viewer picked the proposal up', app.overlayActive(),
    app.state.proposal && app.state.proposal.changes.length + ' changes');
  check('the panel opened', el('prop').classList.contains('open'),
    el('p-title').textContent);
  check('the panel lists every change',
    el('p-list').children.length === app.state.proposal.changes.length,
    el('p-list').children.length + ' rows');
  check('the panel names each status in words',
    el('p-key').children.length >= 3, el('p-key').children.length + ' key entries');

  /*
   * The panel folds to its headline and back. It is a long panel pinned to the left rail,
   * so getting the space back has to be possible without withdrawing the proposal or
   * switching the overlay off - both of which change what the map says, where this does not.
   */
  {
    const rows = el('p-list').children.length;
    el('p-fold').fire('click', {});
    await sleep(40);
    check('the plan folds to its headline',
      el('prop').classList.contains('folded')
        && el('p-fold').getAttribute('aria-expanded') === 'false'
        && el('prop').classList.contains('open'),
      'folded, still open, ' + el('p-fold').textContent);
    check('folding withdraws nothing',
      app.overlayActive() && el('p-list').children.length === rows
        && app.state.proposedNodes.length > 0,
      `${rows} rows still listed, overlay still on`);
    const saved = await fetch(BASE + '/api/settings').then((r) => r.json());
    check('the fold is remembered',
      saved.settings['planning.plan_folded'] === 'yes',
      JSON.stringify(saved.settings));
    el('p-fold').fire('click', {});
    await sleep(40);
    check('and unfolds again', !el('prop').classList.contains('folded')
      && el('p-fold').getAttribute('aria-expanded') === 'true',
      el('p-fold').textContent);
  }

  check('the modified class is lit as its own change',
    (app.statusOf(victim) || {}).s === 'modify' && app.statusOf(victim).own === 1,
    JSON.stringify(app.statusOf(victim)));
  check('the deleted class is lit red', (app.statusOf(other) || {}).s === 'delete',
    (app.statusOf(other) || {}).s);
  frame();
  check('a status ring was built per touched entity',
    app.batches.statusModify.count >= 1 && app.batches.statusDelete.count >= 1,
    `add ${app.batches.statusAdd.count}, modify ${app.batches.statusModify.count}, `
      + `delete ${app.batches.statusDelete.count}`);
  check('the proposed class is placed and drawn',
    app.state.proposedNodes.length === 1 && app.batches.proposedNodes.count === 1,
    app.state.proposedNodes.length + ' placed');
  check('the proposed class sits clear of the existing ones',
    app.state.proposedNodes.every((added) => app.state.viewNodes.every((n) =>
      Math.hypot(n.x - added.x, n.y - added.y) > n.r)),
    'no overlap with real entities');
  check('the proposed connection was routed', app.batches.proposedEdges.count === 1,
    app.state.proposedEdges.length + ' edges in this view');

  /*
   * A proposed connection whose far end is not in this view. The map already answers a real
   * cross-boundary reference with a dashed entity on the rim; a proposed one is the same
   * claim and has to get the same answer, or a new class that exists to call three things
   * elsewhere reads as unconnected in its own view.
   */
  {
    const outsider = app.state.byLayer[3].find((n) => n.parent !== pkg.id && !n.stub)
      || app.state.byLayer[2].find((n) => n.id !== pkg.id && n.parent !== pkg.id);
    if (!outsider) {
      note('a proposed connection out of the view reaches the rim',
        'the fixture has nothing loaded outside this package');
    } else {
      await post('/api/proposal/change', {
        op: 'connect', from: 'n1', to: String(outsider.id), edge_kind: 'CALL',
        note: 'the new class needs something from elsewhere',
      });
      await app.pollProposal();
      frame();
      const leaving = app.state.proposedEdges.filter((c) => c.leaves);
      check('a proposed connection out of the view reaches the rim',
        leaving.length === 1 && leaving[0].b.isExternal,
        leaving.length ? `${leaving[0].a.name} -> ${leaving[0].b.name} on the rim`
          : 'no arrow left the view');
      check('the rim entity it lands on is the one you can travel to',
        leaving.length === 1 && !!app.state.externals.find((x) => x === leaving[0].b)
          && !!leaving[0].b.targetId,
        leaving.length ? 'target id ' + leaving[0].b.targetId : 'n/a');
      check('it is drawn dashed, in its own pass, not as a plain edge',
        app.batches.proposedOutEdges.count === leaving.length
          && app.batches.proposedEdges.count
            === app.state.proposedEdges.length - leaving.length,
        `${app.batches.proposedOutEdges.count} leaving, `
          + `${app.batches.proposedEdges.count} staying`);
      check('the legend explains the dashed arrow it just drew',
        el('legend-edges').children.some((c) =>
          c.children.some((s) => (s.children || [])
            .some((l) => l.getAttribute('stroke-dasharray')))),
        el('legend-edges').children.map((c) => c.children
          .filter((s) => s.tagName !== 'SVG').length).join('/') + ' rows');
      // the rim entity is a real node somewhere else, so its status has to come from there
      check('the rim entity carries the proposal status of what it stands for',
        leaving.length === 1 && !!app.statusOf(leaving[0].b),
        leaving.length ? JSON.stringify(app.statusOf(leaving[0].b)) : 'n/a');
    }
  }

  /*
   * The two things the graph says back. Everything else in the overlay is the agent's own
   * assertion drawn faithfully; these are the only parts that can disagree with it, so they
   * are the only parts that turn the map from a description into a review.
   */
  {
    const connects = app.state.proposal.changes.filter((c) => c.op === 'connect');
    check('every proposed connection is measured against precedent',
      connects.length > 0 && connects.every((c) => c.precedent),
      connects.map((c) => c.precedent && c.precedent.verdict).join(' | '));
    const local = connects.filter((c) => c.precedent.local);
    const crossing = connects.filter((c) => !c.precedent.local);
    check('an edge inside one container is called local, not unprecedented',
      local.length > 0 && local.every((c) => /^inside /.test(c.precedent.verdict)),
      local.length ? local[0].precedent.verdict : 'no local connection in this fixture');
    if (!crossing.length) {
      note('a boundary-crossing edge is counted both ways',
        'nothing in this proposal leaves its container');
    } else {
      check('a boundary-crossing edge is counted both ways',
        crossing.every((c) => c.precedent.from && c.precedent.to
          && c.precedent.forward >= 0 && c.precedent.backward >= 0),
        crossing.map((c) => `${c.precedent.from}→${c.precedent.to}`
          + ` ${c.precedent.forward}/${c.precedent.backward}`).join(', '));
    }

    const exposure = app.state.proposal.exposure || [];
    if (!exposure.length) {
      note('the plan is measured for what it does not mention',
        'nothing this plan changes has untouched users in this fixture');
      note('an exposed neighbour survives folding', 'no exposure to protect');
    } else {
      check('the plan is measured for what it does not mention',
        exposure.every((e) => e.total > e.addressed && e.samples.length > 0),
        exposure.map((e) => `${e.name}: ${e.total - e.addressed} of ${e.total}`).join(', '));
      check('the exposed set is rolled up so it can be seen from above',
        !!app.state.proposal.risk
          && exposure.every((e) => e.samples.every((n) => app.state.proposal.risk[n.id])),
        Object.keys(app.state.proposal.risk || {}).length + ' ids carry a risk count');
      /*
       * Exposure is text, never a mark on the canvas. It was a violet ring once and it lit
       * half the screen at every level - exposure is scattered by nature, so painting it
       * drowned out the change it was meant to qualify. The finding stayed; the ink went.
       */
      frame();
      const src = fs.readFileSync(APP, 'utf8');
      check('exposure is stated in the panel, not painted on the map',
        !/statusRisk/.test(src) && !/palette\['--prop-risk'\]/.test(src)
          && el('p-exposure').style.display === 'block',
        'no canvas pass reads --prop-risk; the panel section is open');
      const bullets = el('p-exposure').children
        .filter((c) => c.tagName === 'UL')
        .flatMap((c) => c.children);
      check('it is a short list of what to look at, not a report',
        bullets.length > 0 && bullets.length <= 7
          && bullets.every((b) => !/\n/.test(textOf(b))),
        bullets.map((b) => textOf(b)).join(' | ').slice(0, 90));
    }
  }

  // captured rather than hardcoded: the assertions below only care that switching a view
  // control did not withdraw anything, so they must not need editing when a case is added
  const changeCount = app.state.proposal.changes.length;

  app.clearSelection();
  app.updateLabels();
  const labelled = el('labels').children.filter((c) => c.style.display !== 'none');
  // the fold marker is always named, proposal or not: a truncation you cannot see is worse
  // than the clutter it removed
  const lit = app.state.viewNodes.filter((n) => app.statusOf(n)).length
    + app.state.externals.filter((x) => app.statusOf(x)).length
    + app.state.proposedNodes.length
    + (app.state.foldMarker ? 1 : 0);
  check('only what the proposal touches keeps a label', labelled.length === lit,
    `${labelled.length} labels, ${lit} lit, `
      + `${app.state.viewNodes.length + app.state.externals.length} entities in view`);
  check('the new class is one of them',
    labelled.some((c) => c.textContent.includes('ProposedThing')),
    labelled.map((c) => c.textContent).join(', ').slice(0, 70));

  /*
   * The point of the rollup: a class-level change stays visible from the outermost view.
   * "Outermost" has to mean the first level that has siblings to fade - on a
   * single-build-unit project the project view holds one circle and there is nothing to
   * compare it against.
   */
  await app.openView(null);
  if (app.state.viewNodes.length === 1) await app.openView(app.state.viewNodes[0]);
  const litTop = app.state.viewNodes.filter((n) => app.statusOf(n));
  check('the change is visible from the outermost view', litTop.length >= 1,
    litTop.map((n) => n.name + '=' + app.statusOf(n).s).join(', '));
  check('a container lit by its contents is not marked as changed itself',
    litTop.every((n) => app.statusOf(n).own === 0), 'rolled up, not claimed');
  const untouched = app.state.viewNodes.filter((n) => !app.statusOf(n));
  check('what the proposal does not touch recedes', untouched.length > 0
    && untouched.every((n) => app.proposalAlpha(n) < 0.3),
    untouched.length + ' dimmed to ' + app.proposalAlpha(untouched[0]));

  /*
   * Focus mode: the same proposal, but everything it does not touch is left out rather than
   * faded. The interesting invariants are that nothing survives without a status, that no
   * edge is left pointing at something that was removed, and that the view still says what
   * it is hiding.
   */
  {
    await app.openView(pkg);
    // measured against everything in the container, not the folded subset: focus is
    // answering "what is in play", so what it left out includes what folding had already
    // set aside
    const before = (app.state.childrenOf.get(pkg.id) || []).length;
    // observed, not derived: an earlier check may have left this container's fold open
    const shownBefore = app.state.viewNodes.length;
    const geoOff = geometry(app);
    el('set-planning-focus').checked = true;
    el('set-planning-focus').fire('change', { target: { checked: true } });
    await sleep(60);

    check('focus mode is on and stored as yes', app.state.settings['planning.focus'] === 'yes'
      && app.overlayActive(), 'planning.focus = ' + app.state.settings['planning.focus']);
    check('only what the proposal touches is drawn',
      app.state.viewNodes.length > 0 && app.state.viewNodes.every((n) => app.statusOf(n)),
      `${app.state.viewNodes.length} of ${before} entities kept`);
    check('the rest is left out, not faded',
      app.state.focusedOut === before - app.state.viewNodes.length
        && app.state.viewNodes.every((n) => app.proposalAlpha(n) === 1),
      app.state.focusedOut + ' removed, nothing drawn at reduced alpha');
    /*
     * The two planning views must agree about what exists. Focus filters to the touched set
     * before folding, so anything touched but low-ranked showed up focused and was missing
     * from the same view unfocused - a class present in one and absent in the other, which
     * reads as a bug in the graph rather than a display choice.
     */
    const focusedNames = new Set(app.state.viewNodes.map((n) => n.name));
    const unfocusedNames = new Set(geoOff.nodes.keys());
    const onlyFocused = [...focusedNames].filter((n) => !unfocusedNames.has(n));
    check('nothing the proposal touches is folded away when unfocused',
      onlyFocused.length === 0,
      onlyFocused.length ? 'missing unfocused: ' + onlyFocused.join(', ')
        : `all ${focusedNames.size} touched entities appear in both views`);

    // the whole point of a focused view is that it is the same view with less in it
    const geoOn = geometry(app);
    check('focusing leaves the frame alone', geoOff.extent === geoOn.extent, geoOn.extent);
    check('focusing moves nothing it kept',
      shifted(geoOff.nodes, geoOn.nodes).length === 0
        && shifted(geoOff.externals, geoOn.externals).length === 0
        && shifted(geoOff.proposed, geoOn.proposed).length === 0,
      `${geoOn.nodes.size} entities, ${geoOn.externals.size} externals and `
        + `${geoOn.proposed.size} proposed all at their original coordinates`);

    const drawnIds = new Set(app.state.viewNodes.map((n) => n.id));
    check('no edge points at something that was removed',
      app.state.viewEdges.every((e) => drawnIds.has(e.s) && drawnIds.has(e.d)),
      app.state.viewEdges.length + ' edges, both ends drawn');
    check('every connection between the kept entities is shown',
      app.state.hiddenEdges.length === 0, 'no thinning while focused');
    check('the rim only keeps what the proposal touches',
      app.state.externals.every((x) => app.statusOf(x))
        && app.state.externals.every((x) => x.links.every((l) => drawnIds.has(l.inner.id))),
      app.state.externals.length + ' outside, all touched');
    frame();
    check('what it wants to add is still drawn', app.state.proposedNodes.length > 0
      && app.batches.proposedNodes.count === app.state.proposedNodes.length,
      app.state.proposedNodes.length + ' proposed');
    check('the proposed connection is still drawn',
      app.batches.proposedEdges.count + app.batches.proposedOutEdges.count
          === app.state.proposedEdges.length
        && app.state.proposedEdges.length > 0,
      `${app.state.proposedEdges.length} arrows, `
        + `${app.batches.proposedOutEdges.count} of them leaving the view`);

    // the rollup still has to work, or focusing removes the way in
    await app.openView(null);
    if (app.state.viewNodes.length === 1) await app.openView(app.state.viewNodes[0]);
    check('the path down to the change survives focus',
      app.state.viewNodes.length > 0 && app.state.viewNodes.every((n) => app.statusOf(n)),
      app.state.viewNodes.map((n) => n.name).join(', ') + ' left at the top');
    await app.openView(pkg);
    // in a small view the proposal may touch everything, and then there is nothing to say
    if (app.state.focusedOut === 0) {
      note('the status line says what focus removed', 'focus removed nothing in this view');
    } else {
      check('the status line says what focus removed',
        el('stats').innerHTML.includes('hidden by focus'),
        el('stats').innerHTML.replace(/<[^>]+>/g, ''));
    }

    const stored = await fetch(BASE + '/api/settings').then((r) => r.json());
    check('the setting reached the database', stored.settings['planning.focus'] === 'yes',
      JSON.stringify(stored.settings));

    // and off again: the proposal is untouched, the view comes back
    el('set-planning-focus').checked = false;
    el('set-planning-focus').fire('change', { target: { checked: false } });
    await sleep(60);
    check('turning it off restores the view',
      app.state.viewNodes.length === shownBefore && app.state.focusedOut === 0
        && app.state.proposal.changes.length === changeCount,
      `${app.state.viewNodes.length} of ${shownBefore} entities back, proposal intact`);
  }

  // switching the overlay off is a local view control, not a change to the proposal
  el('p-toggle').fire('click', {});
  check('the overlay can be switched off without withdrawing the proposal',
    !app.overlayActive() && app.state.proposal.changes.length === changeCount,
    'overlay off, proposal intact');
  el('p-toggle').fire('click', {});
  check('and switched back on', app.overlayActive());

  await fetch(BASE + '/api/proposal', { method: 'DELETE' });
  await app.pollProposal();
  frame();
  check('clearing removes the overlay entirely',
    !app.overlayActive() && app.batches.statusModify.count === 0
      && app.batches.proposedNodes.count === 0 && app.batches.proposedEdges.count === 0,
    'nothing left to draw');
  check('the panel closed', !el('prop').classList.contains('open'));
  check('the map is back to full strength',
    app.state.viewNodes.every((n) => app.proposalAlpha(n) === 1), 'no dimming');
}

console.log(`\n${failures === 0 ? 'all checks passed' : failures + ' check(s) FAILED'}\n`);
process.exit(failures === 0 ? 0 : 1);
