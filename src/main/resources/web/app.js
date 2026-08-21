'use strict';
/*
 * codemap map viewer.
 *
 * The map shows exactly one level at a time, the way a folder view does:
 *
 *   level 1  the modules of the project, and the dependencies between them
 *   level 2  the packages inside one module, and the dependencies between them
 *   level 3  the types inside one package, and the dependencies between them
 *   level 4  the functions inside one type, and the calls between them
 *
 * Double-clicking an entity opens the level below it. Anything a view depends on that
 * lives outside it is drawn as a dashed entity on the rim, positioned in the direction
 * that thing actually lies; double-clicking one takes you there. Nothing from another
 * level is ever drawn, so a view only ever contains things you can act on.
 *
 * Layout comes from the server in one shared world coordinate system, so opening a level
 * is a camera move rather than a new diagram, and positions stay stable between visits.
 */

// ---------------------------------------------------------------- kind mapping

// Identity is carried by colour AND shape: only three categorical slots are in play,
// which is what keeps the palette readable for colour-vision deficiency.
const KINDS = {
  CLASS:      { color: '--kind-class',     shape: 0, group: 'Class' },
  INTERFACE:  { color: '--kind-interface', shape: 1, group: 'Interface / trait' },
  TRAIT:      { color: '--kind-interface', shape: 1, group: 'Interface / trait' },
  PROTOCOL:   { color: '--kind-interface', shape: 1, group: 'Interface / trait' },
  ENUM:       { color: '--kind-value',     shape: 2, group: 'Enum / record / struct' },
  RECORD:     { color: '--kind-value',     shape: 2, group: 'Enum / record / struct' },
  STRUCT:     { color: '--kind-value',     shape: 2, group: 'Enum / record / struct' },
  ANNOTATION: { color: '--kind-value',     shape: 2, group: 'Enum / record / struct' },
  FILE:       { color: '--kind-file',      shape: 3, group: 'File, no type declared' },
  // layer 4
  METHOD:      { color: '--kind-class', shape: 0, group: 'Function / method' },
  CONSTRUCTOR: { color: '--kind-value', shape: 2, group: 'Constructor' },
  FUNCTION:    { color: '--kind-class', shape: 0, group: 'Function / method' },
};
const KIND_FALLBACK = KINDS.CLASS;

const LAYER = { MODULE: 1, PACKAGE: 2, TYPE: 3, MEMBER: 4 };
const SHAPE = { CIRCLE: 0, RING: 1, SQUARE: 2, DIAMOND: 3 };

// ------------------------------------------------------------------- app state

const state = {
  meta: {},
  nodes: new Map(),                 // id -> node, every layer
  byLayer: { 1: [], 2: [], 3: [], 4: [] },
  edges: { 1: [], 2: [], 3: [], 4: [] },
  edgeKeys: new Set(),
  loadedContainers: new Set(),
  pendingContainers: new Set(),
  /** parent id -> child nodes, the only structure a view needs. */
  childrenOf: new Map(),

  /** which level is on screen, and whose children it shows. */
  level: LAYER.MODULE,
  container: null,
  /** the nodes and edges this view draws; nothing else is ever drawn. */
  viewNodes: [],
  viewEdges: [],
  /** dashed stand-ins for things this view depends on that live outside it. */
  externals: [],
  /** name prefix every entity in this view shares, dropped from labels. */
  labelPrefix: '',
  /** the circle the current view's contents occupy; coordinates are local to a view. */
  viewExtent: { cx: 0, cy: 0, r: 1 },

  view: { cx: 0, cy: 0, scale: 1 },
  anim: null,
  selected: null,
  hover: null,
  needsRedraw: false,
  buffersDirty: true,

  /*
   * The proposal overlay. `proposal` is exactly what the server sent: the change list, a
   * status per touched node with its ancestors rolled in, the nodes it wants to create and
   * the edges it wants to draw. `overlayOn` is a local switch - it lets you look at the
   * code as it is without asking the agent to withdraw its plan.
   */
  proposal: null,
  proposalRevision: -1,
  overlayOn: true,
  /** the additions this view draws, and the proposed edges between things in it. */
  proposedNodes: [],
  proposedEdges: [],

  /** the tail this view is not drawing, and the way back to it. */
  hiddenEdges: [],
  showAllEdges: false,
  foldMarker: null,
  expandedFolds: new Set(),
  /** what the view would hold untruncated, for the status line. */
  childCount: 0,
  edgeCount: 0,
};

/** True when there is something to paint and the user has not switched it off. */
function overlayActive() {
  return !!(state.overlayOn && state.proposal && state.proposal.changes.length);
}

/**
 * The rolled-up status of a node under the current proposal, or null.
 *
 * A dashed external stands for a node somewhere else, so it answers for that node: a
 * proposal that touches something outside the view lights the way out to it.
 */
function statusOf(node) {
  if (!overlayActive() || !node) return null;
  const key = node.isExternal ? node.targetId : node.id;
  return state.proposal.nodes[String(key)] || null;
}

// ------------------------------------------------------------------- webgl setup

const canvas = document.getElementById('map');
/*
 * preserveDrawingBuffer is not optional here.
 *
 * This is an on-demand renderer: it draws one frame per requestRedraw and then stops.
 * By default WebGL clears the drawing buffer as soon as it has been presented, so any
 * later composite the page triggers - a label moving, a panel repainting, a hover -
 * shows an emptied buffer, and with alpha:false an empty buffer composites as OPAQUE
 * BLACK. The map then looks like it never drew, with no error anywhere.
 */
const gl = canvas.getContext('webgl2', {
  antialias: true,
  alpha: false,
  preserveDrawingBuffer: true,
});
if (!gl) {
  document.getElementById('loading').textContent =
    'This browser has no WebGL2. The map needs it to draw.';
  throw new Error('webgl2 unavailable');
}

function compile(type, src) {
  const sh = gl.createShader(type);
  gl.shaderSource(sh, src);
  gl.compileShader(sh);
  if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
    throw new Error('shader: ' + gl.getShaderInfoLog(sh) + '\n' + src);
  }
  return sh;
}

function program(vsSrc, fsSrc, attribs, uniforms) {
  const p = gl.createProgram();
  gl.attachShader(p, compile(gl.VERTEX_SHADER, vsSrc));
  gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fsSrc));
  gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
    throw new Error('link: ' + gl.getProgramInfoLog(p));
  }
  const out = { p, a: {}, u: {} };
  attribs.forEach((n) => { out.a[n] = gl.getAttribLocation(p, n); });
  uniforms.forEach((n) => { out.u[n] = gl.getUniformLocation(p, n); });
  return out;
}

// Every glyph, container circle and dashed external comes from one instanced quad.
// Positions are computed in device pixels so nothing shrinks below a clickable size.
const discProgram = program(`#version 300 es
  in vec2 aCorner;
  in vec2 iPos;
  in float iRadius;
  in float iShape;
  in vec4 iColor;
  uniform vec2 uPixelToClip;
  uniform vec2 uCenter;
  uniform float uScale;
  uniform float uMinPx;
  uniform float uPadPx;
  uniform float uAlpha;
  uniform vec4 uOverride;
  out vec2 vLocal;
  out vec4 vColor;
  out float vShape;
  out float vRadiusPx;
  void main() {
    // Screen space is device pixels from the viewport centre with y UP, exactly what
    // worldToScreen() in JS assumes. uPixelToClip is derived from that same convention
    // so the canvas and the DOM labels can never disagree about which way is up - a
    // stray negation here silently mirrors the map against its own labels.
    vec2 centerPx = (iPos - uCenter) * uScale;
    float rPx = max(iRadius * uScale, uMinPx) + uPadPx;
    vec2 px = centerPx + aCorner * rPx;
    gl_Position = vec4(px * uPixelToClip, 0.0, 1.0);
    vLocal = aCorner;
    vShape = iShape;
    vRadiusPx = rPx;
    // iColor.a is a per-instance multiplier, and it survives an override: that is how a
    // proposal pushes everything it does not touch into the background while the areas
    // themselves are still painted with the level's own rim and fill colours.
    vec3 rgb = uOverride.a > 0.0 ? uOverride.rgb : iColor.rgb;
    float base = uOverride.a > 0.0 ? uOverride.a * iColor.a : iColor.a;
    vColor = vec4(rgb, base * uAlpha);
  }`, `#version 300 es
  precision highp float;
  in vec2 vLocal;
  in vec4 vColor;
  in float vShape;
  in float vRadiusPx;
  uniform float uHollow;
  uniform float uDash;
  out vec4 outColor;

  float shapeDistance(vec2 p, float shape) {
    if (shape < 1.5) return length(p) - 1.0;                       // circle and ring
    if (shape < 2.5) { vec2 d = abs(p) - vec2(0.84); return max(d.x, d.y); }  // square
    return (abs(p.x) + abs(p.y)) - 1.05;                           // diamond
  }

  void main() {
    float d = shapeDistance(vLocal, vShape);
    float aa = 1.4 / max(vRadiusPx, 1.5);
    // A ring is the same outline turned into a band hugging its edge. The band is
    // clamped in PIXELS, not in local units: a relative floor would make the ring
    // around a whole package tens of pixels thick.
    if (uHollow > 0.5 && vShape > 0.5 && vShape < 1.5) {
      float widthPx = clamp(2.4, 1.2, vRadiusPx * 0.45);
      float w = widthPx / max(vRadiusPx, 1.0);
      d = abs(d + w) - w;
    }
    float cover = 1.0 - smoothstep(-aa, aa, d);
    if (cover <= 0.002 || vColor.a <= 0.002) discard;
    // dashes mark an entity that is not really here: it lives in another view
    if (uDash > 0.5) {
      float angle = atan(vLocal.y, vLocal.x) / 6.28318531 + 0.5;
      if (fract(angle * uDash) > 0.55) discard;
    }
    outColor = vec4(vColor.rgb, vColor.a * cover);
  }`,
  ['aCorner', 'iPos', 'iRadius', 'iShape', 'iColor'],
  ['uPixelToClip', 'uCenter', 'uScale', 'uMinPx', 'uPadPx', 'uAlpha',
    'uOverride', 'uHollow', 'uDash']);

// Edges are quads rather than GL lines so their width is controllable and consistent.
const edgeProgram = program(`#version 300 es
  in vec2 aCorner;
  in vec2 iA;
  in vec2 iB;
  in float iWeight;
  uniform vec2 uPixelToClip;
  uniform vec2 uCenter;
  uniform float uScale;
  uniform float uWidthPx;
  uniform float uAlpha;
  uniform float uTaper;
  uniform vec4 uColor;
  out vec4 vColor;
  out float vAcross;
  out float vAlong;
  void main() {
    // same convention as the disc program: device pixels from the centre, y up
    vec2 aPx = (iA - uCenter) * uScale;
    vec2 bPx = (iB - uCenter) * uScale;
    vec2 along = bPx - aPx;
    float len = length(along);
    vec2 dir = len > 0.0001 ? along / len : vec2(1.0, 0.0);
    vec2 normal = vec2(-dir.y, dir.x);
    float halfWidth = uWidthPx * (0.5 + 0.18 * log2(1.0 + iWeight)) * 0.5;
    // Tapering narrows the quad towards its destination, which is how a proposed edge
    // shows direction. An arrowhead would need a per-instance rotation; a taper is the
    // same information for one multiply, and it stays readable at any zoom.
    halfWidth *= mix(1.0, 1.0 - uTaper, aCorner.x);
    vec2 px = aPx + along * aCorner.x + normal * aCorner.y * halfWidth;
    gl_Position = vec4(px * uPixelToClip, 0.0, 1.0);
    // a segment shorter than a couple of pixels is clutter, not information
    vColor = vec4(uColor.rgb, uColor.a * uAlpha * smoothstep(1.5, 6.0, len));
    vAcross = aCorner.y;
    vAlong = aCorner.x * len;
  }`, `#version 300 es
  precision highp float;
  in vec4 vColor;
  in float vAcross;
  in float vAlong;
  uniform float uDashPx;
  out vec4 outColor;
  void main() {
    if (vColor.a <= 0.003) discard;
    if (uDashPx > 0.0 && fract(vAlong / uDashPx) > 0.55) discard;
    float soft = 1.0 - smoothstep(0.55, 1.0, abs(vAcross));
    outColor = vec4(vColor.rgb, vColor.a * soft);
  }`,
  ['aCorner', 'iA', 'iB', 'iWeight'],
  ['uPixelToClip', 'uCenter', 'uScale', 'uWidthPx', 'uAlpha', 'uColor', 'uDashPx',
    'uTaper']);

const quadDisc = gl.createBuffer();
gl.bindBuffer(gl.ARRAY_BUFFER, quadDisc);
gl.bufferData(gl.ARRAY_BUFFER,
  new Float32Array([-1, -1, 1, -1, -1, 1, 1, -1, 1, 1, -1, 1]), gl.STATIC_DRAW);

const quadEdge = gl.createBuffer();
gl.bindBuffer(gl.ARRAY_BUFFER, quadEdge);
gl.bufferData(gl.ARRAY_BUFFER,
  new Float32Array([0, -1, 1, -1, 0, 1, 1, -1, 1, 1, 0, 1]), gl.STATIC_DRAW);

/** One instanced draw target: a growable interleaved buffer plus its instance count. */
function makeBatch(stride) {
  return { buffer: gl.createBuffer(), stride, data: new Float32Array(0), count: 0 };
}
function uploadBatch(batch, array, count) {
  batch.data = array;
  batch.count = count;
  gl.bindBuffer(gl.ARRAY_BUFFER, batch.buffer);
  gl.bufferData(gl.ARRAY_BUFFER, array, gl.DYNAMIC_DRAW);
}

const batches = {
  /** entities of the current level. */
  nodes: makeBatch(8),
  edges: makeBatch(5),
  /** dashed stand-ins on the rim, and the edges reaching them. */
  externals: makeBatch(8),
  externalEdges: makeBatch(5),
  /** the boundary of the container you are inside. */
  outline: makeBatch(8),
  /** the selection and its immediate neighbours. */
  highlight: makeBatch(8),
  highlightOut: makeBatch(5),
  highlightIn: makeBatch(5),
  /**
   * The proposal overlay: one ring batch per status, so each can be drawn with its own
   * texture, plus the nodes a proposal wants to create and the edges it wants to draw.
   */
  statusAdd: makeBatch(8),
  statusModify: makeBatch(8),
  statusDelete: makeBatch(8),
  statusMark: makeBatch(8),
  proposedNodes: makeBatch(8),
  proposedEdges: makeBatch(5),
  /** the thinned-away edges, and the marker standing in for a folded tail. */
  edgesHidden: makeBatch(5),
  edgesHovered: makeBatch(5),
  fold: makeBatch(8),
};

// ------------------------------------------------------------------ palette

/*
 * The renderer owns its own colours.
 *
 * These used to be read back out of the stylesheet with getComputedStyle, which is a
 * silent single point of failure: if a lookup returns an empty string the parser yields
 * black, every pass paints black on black, and the map looks like it never drew at all.
 * The CSS custom properties in index.html still style the chrome (panels, text, legend);
 * these values are the canvas half and must be kept in step with them - the smoke test
 * asserts that they agree. A few chrome colours (--panel, --ink-2) live here too, not
 * because the renderer needs them but because tools/chrome.mjs draws the panels offline for
 * the README stills, and having them here puts them under that same agreement check.
 */
const THEMES = {
  light: {
    '--canvas': '#f7f6f3', '--district': '#ecebe4', '--district-line': '#c6c3b4',
    '--block': '#dedbd0', '--block-line': '#aeab9b', '--edge': 'rgba(11,11,11,0.07)',
    '--kind-class': '#2a78d6', '--kind-interface': '#eb6834', '--kind-value': '#1baf7a',
    '--kind-file': '#898781', '--dir-out': '#2a78d6', '--dir-in': '#eb6834',
    '--ink': '#0b0b0b', '--ink-2': '#52514e', '--ink-muted': '#898781',
    '--panel': '#fcfcfb',
    '--prop-add': '#0e7546', '--prop-change': '#dfa300', '--prop-del': '#c4291c',
  },
  dark: {
    '--canvas': '#0d0d0d', '--district': '#1c1c1a', '--district-line': '#3d3d37',
    '--block': '#2b2b28', '--block-line': '#52524a', '--edge': 'rgba(255,255,255,0.09)',
    '--kind-class': '#3987e5', '--kind-interface': '#d95926', '--kind-value': '#199e70',
    '--kind-file': '#898781', '--dir-out': '#3987e5', '--dir-in': '#d95926',
    '--ink': '#ffffff', '--ink-2': '#c3c2b7', '--ink-muted': '#898781',
    '--panel': '#1a1a19',
    '--prop-add': '#33aa74', '--prop-change': '#b8860b', '--prop-del': '#c73b2e',
  },
};

/** What a proposal status looks like: a colour, a ring texture, and a word. */
const STATUS = {
  add:    { color: '--prop-add',    label: 'Add',    dash: 0,  double: true },
  modify: { color: '--prop-change', label: 'Change', dash: 0,  double: false },
  delete: { color: '--prop-del',    label: 'Delete', dash: 11, double: false },
  // grey, not ink: a black ring is what a selection looks like, and a note is not one
  mark:   { color: '--ink-muted',    label: 'Note',   dash: 0,  double: false },
};

let palette = {};
let themeName = 'light';

function prefersDark() {
  return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
}

/** The theme actually in effect: an explicit toggle wins over the OS preference. */
function currentTheme() {
  const stamped = document.documentElement.getAttribute('data-theme');
  if (stamped === 'dark' || stamped === 'light') return stamped;
  return prefersDark() ? 'dark' : 'light';
}

function readPalette() {
  themeName = currentTheme();
  const theme = THEMES[themeName] || THEMES.light;
  palette = {};
  for (const key of Object.keys(theme)) palette[key] = parseColor(theme[key]);
}

function parseColor(text) {
  if (!text) return [0, 0, 0, 1];
  if (text.startsWith('#')) {
    let hex = text.slice(1);
    if (hex.length === 3) hex = hex.split('').map((c) => c + c).join('');
    const n = parseInt(hex.slice(0, 6), 16);
    const a = hex.length >= 8 ? parseInt(hex.slice(6, 8), 16) / 255 : 1;
    return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255, a];
  }
  const m = text.match(/-?[\d.]+/g);
  if (!m) return [0, 0, 0, 1];
  return [(+m[0]) / 255, (+m[1]) / 255, (+m[2]) / 255, m.length > 3 ? +m[3] : 1];
}

function kindOf(node) {
  return KINDS[node.kind] || KIND_FALLBACK;
}

/** Colour and shape for an entity at the level it is being drawn on. */
function glyphOf(node) {
  if (node.kind === 'GROUP') return { color: palette['--ink'], shape: SHAPE.CIRCLE };
  if (node.layer === LAYER.TYPE || node.layer === LAYER.MEMBER) {
    const kind = kindOf(node);
    return { color: palette[kind.color], shape: kind.shape };
  }
  return { color: palette['--ink'], shape: SHAPE.CIRCLE };
}

// ---------------------------------------------------------------- data loading

async function getJson(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(url + ' -> ' + res.status);
  return res.json();
}

/**
 * Files a node under its parent, once.
 *
 * A stub is not filed, because it is a placeholder for an edge endpoint we have not really
 * loaded. The subtlety is what happens when the real thing arrives afterwards: it used to
 * be un-stubbed in place and never filed, so any node that happened to be referenced from
 * another view before its own view was opened went missing from its parent's child list for
 * the rest of the session. That silently under-reported a container's contents - 325 of a
 * package's 338 types, with no error anywhere.
 */
function register(node) {
  if (node.filed) return;
  node.filed = true;
  let siblings = state.childrenOf.get(node.parent);
  if (!siblings) { siblings = []; state.childrenOf.set(node.parent, siblings); }
  siblings.push(node);
}

function ingestNodes(list) {
  for (const n of list) {
    const existing = state.nodes.get(n.id);
    if (existing) {
      if (existing.stub && !n.stub) {
        Object.assign(existing, n, { stub: false });
        register(existing);
      }
      continue;
    }
    state.nodes.set(n.id, n);
    state.byLayer[n.layer].push(n);
    if (!n.stub) register(n);
  }
}

function ingestEdges(layer, list) {
  for (const e of list) {
    const key = layer + ':' + e.s + ':' + e.d;
    if (state.edgeKeys.has(key)) continue;
    state.edgeKeys.add(key);
    state.edges[layer].push(e);
  }
}

async function boot() {
  const [meta, l1, l2] = await Promise.all([
    getJson('/api/meta'),
    getJson('/api/graph?layer=1'),
    getJson('/api/graph?layer=2'),
  ]);
  state.meta = meta.meta || {};
  ingestNodes(l1.nodes); ingestEdges(LAYER.MODULE, l1.edges);
  ingestNodes(l2.nodes); ingestEdges(LAYER.PACKAGE, l2.edges);

  document.title = 'codemap · ' + (state.meta.project_name || 'map');
  buildLegend();
  document.getElementById('loading').style.display = 'none';
  /*
   * Most repositories are a single build unit - one go.mod, one root package.json, one
   * pom - and a view containing one circle is not a view. Open straight into it. The
   * breadcrumb and Backspace still go back up to the project level, which is worth having
   * for the projects that really do have several top-level modules.
   */
  const roots = state.byLayer[1].filter((n) => !n.parent);
  await openView(roots.length === 1 ? roots[0] : null);

  // an agent may already have drawn something before this page was opened
  await pollProposal();
  setInterval(pollProposal, PROPOSAL_POLL_MS);
}

/**
 * Fetches a container's children, once. Uniform for every level: the classes of a
 * package, the functions of a class. This used to be keyed on the module, which stopped
 * working the moment packages could hang off a name-path group instead of the module.
 */
async function loadChildren(container) {
  const id = container.id;
  if (state.loadedContainers.has(id)) return;
  if (state.pendingContainers.has(id)) {
    while (state.pendingContainers.has(id)) await new Promise((r) => setTimeout(r, 30));
    return;
  }
  const childLayer = container.layer === LAYER.TYPE ? LAYER.MEMBER : LAYER.TYPE;
  state.pendingContainers.add(id);
  try {
    const data = await getJson('/api/graph?layer=' + childLayer + '&parent=' + id);
    for (const stub of data.stubs || []) stub.stub = true;
    ingestNodes(data.stubs || []);
    ingestNodes(data.nodes);
    ingestEdges(childLayer, data.edges);
    state.loadedContainers.add(id);
  } finally {
    state.pendingContainers.delete(id);
  }
}

// ------------------------------------------------------------ proposal overlay

const PROPOSAL_POLL_MS = 1200;

/**
 * Watches for a proposal arriving, changing or being withdrawn.
 *
 * Polling rather than a socket: the payload is a few kilobytes, `?since=` makes an
 * unchanged answer four fields long, and an agent edits the graph in bursts of a few calls
 * a second at most. A socket would be a second protocol to get right for no gain here.
 */
async function pollProposal() {
  let data;
  try {
    data = await getJson('/api/proposal?since=' + state.proposalRevision);
  } catch (err) {
    return;                                    // server restarting; try again next tick
  }
  if (data.unchanged) return;
  state.proposalRevision = data.revision;
  state.proposal = (data.changes || []).length ? data : null;
  buildView();                                 // additions are placed per view
  updateProposalPanel();
  buildLegend();
  updateStats();
  if (state.selected) rebuildHighlight();
  requestRedraw();
}

/**
 * Where to draw the nodes a proposal wants to create.
 *
 * Only additions whose parent is the container you are looking *into* can be drawn: the
 * map shows one level at a time, so a new class inside a package that is currently one
 * circle has nothing to be drawn next to. That case is not lost - the rollup has already
 * turned the package green, and opening it draws the class.
 *
 * A new thing has no position, because the layout was computed from code that does not
 * contain it yet. It goes in the emptiest spot inside its container: inside, because it
 * belongs there and the rim already means "elsewhere"; emptiest, because dropping it on top
 * of an existing class would read as a change to that class.
 */
function placeAdditions(siblings, parentId) {
  if (!overlayActive()) return [];
  const additions = state.proposal.additions.filter((a) => a.parentId === parentId);
  if (!additions.length) return [];
  const extent = state.viewExtent;
  const radii = siblings.map((n) => n.r).sort((a, b) => a - b);
  const r = radii.length ? radii[Math.floor(radii.length / 2)]
    : Math.max(extent.r * 0.12, 4);
  const out = [];
  for (const addition of additions) {
    const spot = freeSpot(siblings, out, extent, r);
    out.push({
      id: 'add:' + addition.ref,
      ref: addition.ref,
      name: addition.name,
      qname: '',
      kind: addition.kind,
      layer: state.level,
      isProposed: true,
      note: addition.note,
      in: 0,
      out: 0,
      x: spot.x,
      y: spot.y,
      r,
    });
  }
  return out;
}

/**
 * The clearest point on a few rings inside the container. Deterministic: the same proposal
 * on the same graph always puts the new node in the same place, so it does not wander
 * between polls or between visits.
 */
function freeSpot(siblings, placed, extent, r) {
  const STEPS = 48;
  let best = { x: extent.cx, y: extent.cy, clearance: -Infinity };
  for (const factor of [0.74, 0.92, 0.55, 0.35]) {
    const radius = extent.r * factor;
    for (let i = 0; i < STEPS; i++) {
      const angle = Math.PI / 2 - (i / STEPS) * Math.PI * 2;
      const x = extent.cx + Math.cos(angle) * radius;
      const y = extent.cy + Math.sin(angle) * radius;
      let clearance = Infinity;
      for (const node of siblings) {
        clearance = Math.min(clearance, Math.hypot(node.x - x, node.y - y) - node.r);
      }
      for (const other of placed) {
        clearance = Math.min(clearance, Math.hypot(other.x - x, other.y - y) - other.r);
      }
      if (clearance > best.clearance) best = { x, y, clearance };
      if (best.clearance > r * 1.8) return best;      // comfortable; stop looking
    }
  }
  return best;
}

/**
 * The proposed connections that can be drawn in this view, with each end rolled up to
 * whatever stands for it here. A connection between two methods in different modules is
 * therefore an arrow between two modules at the top and an arrow between two methods once
 * you are inside - the same fact, drawn at whatever level you are looking at.
 */
function routeConnections(inside) {
  if (!overlayActive()) return [];
  const byRef = new Map(state.proposedNodes.map((node) => [node.ref, node]));
  const out = [];
  for (const conn of state.proposal.connections) {
    const a = endpointInView(conn.fromId, conn.fromRef, inside, byRef, 0);
    const b = endpointInView(conn.toId, conn.toRef, inside, byRef, 0);
    if (!a || !b || a === b) continue;
    out.push({ a, b, kind: conn.kind, note: conn.note });
  }
  return out;
}

function endpointInView(id, ref, inside, byRef, depth) {
  if (depth > 8) return null;
  if (ref) {
    const drawn = byRef.get(ref);
    if (drawn) return drawn;
    // the new node is not drawn here; stand in for whatever will contain it
    const addition = state.proposal.additions.find((a) => a.ref === ref);
    if (!addition) return null;
    if (addition.parentRef) {
      return endpointInView(0, addition.parentRef, inside, byRef, depth + 1);
    }
    return addition.parentId
      ? endpointInView(addition.parentId, '', inside, byRef, depth + 1) : null;
  }
  if (inside.has(id)) return state.nodes.get(id);
  for (const ancestor of state.proposal.chains[String(id)] || []) {
    if (inside.has(ancestor)) return state.nodes.get(ancestor);
  }
  return null;
}

/**
 * How visible an untouched entity is while a proposal is up. This is the "everything else
 * recedes" half of the design: without it a green ring on one of 300 packages is a needle
 * in a haystack, and with it the eye lands on the change before it reads anything.
 */
function proposalAlpha(node) {
  if (!overlayActive()) return 1;
  return statusOf(node) ? 1 : 0.16;
}

// -------------------------------------------------------- thinning and folding

/*
 * Two ways a view stops being readable, and they need different answers.
 *
 * Too many EDGES is a density problem, and dependency weight is brutally skewed: measured
 * on netty's module graph, the heaviest 10% of edges carry 71.7% of the total weight. So
 * drawing the tail costs most of the ink and buys almost none of the information. At rest
 * we draw the edges that carry the view and keep the rest one keypress away.
 *
 * Too many ENTITIES is a different problem - no alpha or threshold saves a view of 338
 * circles, because each one still needs a position and a label. That one is answered by
 * folding: show what carries the view, and say out loud how much was folded away.
 *
 * Both are truncation, so both are stated in the status line. A silent cap reads as "this
 * is everything" and is worse than the clutter it removes.
 */
const FOLD_LIMIT = 40;
const EDGE_FLOOR = 60;
/** the share of total edge weight the drawn edges must account for. */
const EDGE_SHARE = 0.85;

/** How much an entity carries its view: how big it is, and how much depends on it. */
function importanceOf(node) {
  return (node.r || 0) * 2 + (node.in || 0) * 0.6 + (node.out || 0) * 0.2;
}

/** The entities worth drawing, plus how many were folded away behind the marker. */
function foldTail(children, container) {
  if (!container || children.length <= FOLD_LIMIT
      || state.expandedFolds.has(container.id)) {
    return { shown: children, folded: 0 };
  }
  // ranked the same way labels are, so the things drawn are the things named
  const ranked = children.slice().sort((a, b) => importanceOf(b) - importanceOf(a));
  return { shown: ranked.slice(0, FOLD_LIMIT), folded: children.length - FOLD_LIMIT };
}

/**
 * Splits a view's edges into the ones drawn at rest and the tail.
 *
 * Every entity keeps its single heaviest link whatever the threshold says. Without that
 * guarantee thinning can leave a node looking unconnected when it is not, which is a lie
 * rather than a simplification - and the whole point of dropping guessed edges elsewhere in
 * this project is that the map does not lie about connections.
 */
function thinEdges(edges) {
  if (edges.length <= EDGE_FLOOR) return { drawn: edges, hidden: [] };
  const sorted = edges.slice().sort((a, b) => b.w - a.w);
  const total = sorted.reduce((sum, e) => sum + e.w, 0);

  const keep = new Set();
  const heaviest = new Map();
  for (const e of sorted) {                    // sorted desc, so first seen is heaviest
    if (!heaviest.has(e.s)) heaviest.set(e.s, e);
    if (!heaviest.has(e.d)) heaviest.set(e.d, e);
  }
  for (const e of heaviest.values()) keep.add(e);

  let acc = 0;
  for (const e of keep) acc += e.w;
  for (const e of sorted) {
    if (acc >= total * EDGE_SHARE) break;
    if (keep.has(e)) continue;
    keep.add(e);
    acc += e.w;
  }
  return {
    drawn: sorted.filter((e) => keep.has(e)),
    hidden: sorted.filter((e) => !keep.has(e)),
  };
}

/** The marker standing in for the folded tail: a door, not a participant. */
function makeFoldMarker(container, shown, folded) {
  if (!folded) return null;
  const extent = state.viewExtent;
  const radii = shown.map((n) => n.r).sort((a, b) => a - b);
  const r = radii.length ? radii[Math.floor(radii.length * 0.75)]
    : Math.max(extent.r * 0.1, 4);
  const spot = freeSpot(shown, [], extent, r);
  return {
    id: 'fold:' + container.id,
    isFold: true,
    folded,
    name: '+' + folded + ' more',
    qname: '',
    kind: 'FOLD',
    layer: state.level,
    in: 0,
    out: 0,
    x: spot.x,
    y: spot.y,
    r,
  };
}

/** The hidden edges touching one entity, so hovering it reveals what was thinned away. */
function hiddenEdgesFor(node) {
  if (!node || !state.hiddenEdges.length) return [];
  return state.hiddenEdges.filter((e) => e.s === node.id || e.d === node.id);
}

// ------------------------------------------------------------------ the view

function moduleOf(node) {
  let current = node;
  for (let i = 0; i < 4 && current; i++) {
    if (current.layer === LAYER.MODULE) return current;
    current = state.nodes.get(current.parent);
  }
  return null;
}

/**
 * Opens a container: the view shows whatever hangs off it, whatever layer that happens to
 * be. Containment is a tree - a module holds name-path groups, a group holds groups or
 * packages, a package holds sub-packages and its own classes, a class holds its functions -
 * so there is no fixed number of levels to enumerate, only "what is inside this".
 */
async function openView(container) {
  await ensureLoaded(container);
  state.container = container || null;
  state.level = container ? childLayerOf(container) : LAYER.MODULE;
  state.selected = null;
  clearHighlight();
  buildView();
  updateCrumbs();
  updateStats();
  document.getElementById('side').classList.remove('open');
  fitView();
  requestRedraw();
}

/** Fetches whatever the container's children need before the view is built. */
async function ensureLoaded(container) {
  if (!container) return;
  // a module's children are containers, which ship with the first request; anything
  // deeper holds classes or functions, which are fetched when you open into it
  if (container.layer === LAYER.MODULE) return;
  await loadChildren(container);
}

/** The layer most of a container's children sit on; only used for styling and labels. */
function childLayerOf(container) {
  const children = state.childrenOf.get(container.id) || [];
  if (!children.length) return Math.min(LAYER.MEMBER, container.layer + 1);
  const counts = new Map();
  for (const c of children) counts.set(c.layer, (counts.get(c.layer) || 0) + 1);
  let best = container.layer + 1, bestCount = -1;
  for (const [layer, count] of counts) {
    if (count > bestCount) { bestCount = count; best = layer; }
  }
  return best;
}

/** Collects the nodes, edges and dashed externals for the current view. */
function buildView() {
  const container = state.container;
  // modules nest - an npm workspace holds packages that are themselves modules - so the
  // root shows the top-level ones and the rest open from their parent like anything else
  const children = container
    ? (state.childrenOf.get(container.id) || []).slice()
    : state.byLayer[1].filter((n) => !n.parent);
  const fold = foldTail(children, container);
  const nodes = fold.shown;
  const inside = new Set(nodes.map((n) => n.id));
  const parentId = container ? container.id : 0;

  // an edge carries the id of the view it belongs to, so this is a filter, not a search
  const edges = [];
  for (const layer of [1, 2, 3, 4]) {
    for (const e of state.edges[layer]) {
      if (e.p === parentId && inside.has(e.s) && inside.has(e.d)) edges.push(e);
    }
  }
  const split = thinEdges(edges);

  state.viewNodes = nodes;
  state.viewEdges = split.drawn;
  state.hiddenEdges = split.hidden;
  state.childCount = children.length;
  state.edgeCount = edges.length;
  state.viewExtent = measureExtent(nodes);
  state.foldMarker = container ? makeFoldMarker(container, nodes, fold.folded) : null;
  state.externals = container ? computeExternals(container, inside) : [];
  state.proposedNodes = placeAdditions(nodes, parentId);
  state.proposedEdges = routeConnections(inside);
  state.labelPrefix = dominantPrefix(nodes);
  state.buffersDirty = true;
}

/** The circle this view's contents occupy, used for framing, the rim and the outline. */
function measureExtent(nodes) {
  if (!nodes.length) return { cx: 0, cy: 0, r: 1 };
  let sx = 0, sy = 0;
  for (const n of nodes) { sx += n.x; sy += n.y; }
  const cx = sx / nodes.length;
  const cy = sy / nodes.length;
  let r = 1;
  for (const n of nodes) r = Math.max(r, Math.hypot(n.x - cx, n.y - cy) + n.r);
  return { cx, cy, r };
}

/**
 * The longest name prefix most of a view shares, on a separator boundary.
 *
 * Every package in a Java module tends to start with the same fifteen characters, and
 * repeating them on all 365 labels is what turns a view into a wall of text. A threshold
 * rather than a strict common prefix, because one stray entry (a resources folder among
 * the packages) would otherwise collapse it to nothing.
 */
function dominantPrefix(nodes) {
  if (nodes.length < 6) return '';
  const counts = new Map();
  for (const node of nodes) {
    const name = node.name || '';
    for (let i = 0; i < name.length; i++) {
      const ch = name[i];
      // '-' counts as a separator too: build-unit names are hyphenated far more often than
      // dotted (netty-transport-native-epoll, @scope/pkg-core), and without it a view of
      // module names repeats the project's own name on every label
      if (ch !== '.' && ch !== '/' && ch !== '-') continue;
      const candidate = name.slice(0, i + 1);
      if (candidate.length < 4) continue;
      counts.set(candidate, (counts.get(candidate) || 0) + 1);
    }
  }
  const needed = nodes.length * 0.6;
  let best = '';
  for (const [candidate, count] of counts) {
    if (count >= needed && candidate.length > best.length) best = candidate;
  }
  // keep at least one segment of real name, never shorten to nothing
  return nodes.every((n) => (n.name || '').length > best.length) ? best : '';
}

/** Drops the shared prefix, then the middle if it is still too long. */
function labelFor(node) {
  let text = node.name || '';
  if (node.isExternal) text = text.replace(/^\u21e2 /, '');
  const prefix = state.labelPrefix;
  if (prefix && text.startsWith(prefix)) text = text.slice(prefix.length);
  text = shortenLeft(text, 34);
  if (node.isFold) return node.name;
  if (node.isProposed) return '+ ' + text;
  return node.isExternal ? '\u21e2 ' + text : text;
}

/** True when node sits anywhere inside container. */
function isInside(node, container) {
  let current = node;
  for (let i = 0; i < 32 && current; i++) {
    if (current.id === container.id) return true;
    current = state.nodes.get(current.parent);
  }
  return false;
}

/**
 * Everything this view depends on that is not in it, gathered onto the rim: one dashed
 * entity per foreign container, placed in the direction that container actually lies, so
 * following it is a move on the same map rather than a jump to somewhere unrelated.
 */
function computeExternals(container, inside) {
  const byTarget = new Map();
  const record = (targetNode, innerNode, weight, outgoing) => {
    if (!targetNode || targetNode.id === container.id) return;
    let ext = byTarget.get(targetNode.id);
    if (!ext) {
      ext = {
        id: 'ext:' + targetNode.id,
        targetId: targetNode.id,
        name: targetNode.name,
        qname: targetNode.qname,
        kind: 'EXTERNAL',
        layer: targetNode.layer,
        isExternal: true,
        out: 0,
        in: 0,
        links: [],
      };
      byTarget.set(targetNode.id, ext);
    }
    if (outgoing) ext.out += weight;
    else ext.in += weight;
    if (innerNode) ext.links.push({ inner: innerNode, weight, outgoing });
  };

  const childOf = (node) => {
    let current = node;
    for (let i = 0; i < 32 && current; i++) {
      if (inside.has(current.id)) return current;
      current = state.nodes.get(current.parent);
    }
    return null;
  };

  // Detailed pass: facts that cross the container, attributed to the child they leave
  // from. Only possible where the underlying facts are loaded, i.e. inside a package or
  // a class.
  for (const layer of [LAYER.TYPE, LAYER.MEMBER]) {
    for (const e of state.edges[layer]) {
      const src = state.nodes.get(e.s);
      const dst = state.nodes.get(e.d);
      if (!src || !dst) continue;
      const srcIn = isInside(src, container);
      const dstIn = isInside(dst, container);
      if (srcIn === dstIn) continue;
      if (srcIn) record(nearestNavigable(dst, container), childOf(src), e.w, true);
      else record(nearestNavigable(src, container), childOf(dst), e.w, false);
    }
  }

  // Coarse pass: the rolled-up edges that touch this container or one of its ancestors.
  // These are always available, so a group high up the tree still shows what it depends
  // on even though none of its classes have been fetched.
  const chain = new Set();
  let current = container;
  for (let i = 0; i < 32 && current; i++) {
    chain.add(current.id);
    current = state.nodes.get(current.parent);
  }
  for (const layer of [LAYER.MODULE, LAYER.PACKAGE, LAYER.TYPE, LAYER.MEMBER]) {
    for (const e of state.edges[layer]) {
      const srcIn = chain.has(e.s);
      const dstIn = chain.has(e.d);
      if (srcIn === dstIn) continue;
      const target = state.nodes.get(srcIn ? e.d : e.s);
      if (!target || byTarget.has(target.id)) continue;   // detail pass already has it
      record(target, null, e.w, srcIn);
    }
  }

  const extent = state.viewExtent;
  const radius = Math.max(extent.r * 0.06, 3);
  const list = [...byTarget.values()]
    .sort((a, b) => (b.out + b.in) - (a.out + a.in))
    .slice(0, 24);
  const needed = list.length * (radius * 2 + radius * 0.9);
  const ring = Math.max(extent.r * 1.16, needed / (Math.PI * 2));
  // aim each one at where its own contents sit, so the direction still means something
  let index = 0;
  for (const ext of list) {
    const inner = ext.links.find((l) => l.inner);
    const from = inner ? inner.inner : null;
    ext.angle = from
      ? Math.atan2(from.y - extent.cy, from.x - extent.cx)
      : (index / Math.max(1, list.length)) * Math.PI * 2;
    ext.r = radius;
    index++;
  }
  spreadAroundRing(list, ring, radius);
  for (const ext of list) {
    ext.x = extent.cx + Math.cos(ext.angle) * ring;
    ext.y = extent.cy + Math.sin(ext.angle) * ring;
  }
  return list;
}

/**
 * The outside thing to show for a foreign node: its highest ancestor that is still a
 * sibling of something on our own ancestor chain. Naming the whole far-away module would
 * be useless, and naming the exact far-away method would be unreachable.
 */
function nearestNavigable(node, container) {
  const ours = [];
  let current = container;
  for (let i = 0; i < 32 && current; i++) {
    ours.push(current.id);
    current = state.nodes.get(current.parent);
  }
  let candidate = node;
  let walker = node;
  for (let i = 0; i < 32 && walker; i++) {
    const parent = state.nodes.get(walker.parent);
    if (!parent) return candidate;
    if (ours.includes(parent.id)) return walker;   // sibling of one of our ancestors
    candidate = parent;
    walker = parent;
  }
  return candidate;
}

/**
 * Nudges entities apart along the ring until they stop overlapping. Several foreign
 * containers often lie in almost the same direction, and two dashed circles on top of
 * each other are one unreadable blob rather than two things you can aim at.
 */
function spreadAroundRing(list, ring, radius) {
  if (list.length < 2) return;
  const TWO_PI = Math.PI * 2;
  const minGap = Math.min(TWO_PI / list.length, 2.4 * Math.atan2(radius, ring));
  list.sort((a, b) => a.angle - b.angle);
  for (let pass = 0; pass < 60; pass++) {
    let moved = 0;
    for (let i = 0; i < list.length; i++) {
      const a = list[i];
      const b = list[(i + 1) % list.length];
      let gap = b.angle - a.angle;
      while (gap < 0) gap += TWO_PI;
      if (gap >= minGap) continue;
      const push = (minGap - gap) / 2;
      a.angle -= push;
      b.angle += push;
      moved++;
    }
    if (!moved) break;
  }
}

function fitView() {
  const e = state.viewExtent;
  // room for the dashed ring of outside entities without pushing the contents too far away
  const margin = state.externals.length ? 1.34 : 1.06;
  fitToBounds(e.cx - e.r * margin, e.cy - e.r * margin,
    e.cx + e.r * margin, e.cy + e.r * margin);
}

function fitToNodes(nodes, margin) {
  if (!nodes.length) return;
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const n of nodes) {
    minX = Math.min(minX, n.x - n.r); maxX = Math.max(maxX, n.x + n.r);
    minY = Math.min(minY, n.y - n.r); maxY = Math.max(maxY, n.y + n.r);
  }
  const padX = (maxX - minX) * (margin - 1) * 0.5;
  const padY = (maxY - minY) * (margin - 1) * 0.5;
  fitToBounds(minX - padX, minY - padY, maxX + padX, maxY + padY);
}

function fitToBounds(minX, minY, maxX, maxY) {
  const w = Math.max(1, maxX - minX);
  const h = Math.max(1, maxY - minY);
  const viewW = Math.max(1, canvas.clientWidth);
  const viewH = Math.max(1, canvas.clientHeight);
  state.anim = null;
  state.view = {
    cx: (minX + maxX) / 2,
    cy: (minY + maxY) / 2,
    scale: 0.92 * Math.min(viewW / w, viewH / h),
  };
}

// ------------------------------------------------------------------- camera

function sizeCanvas() {
  const ratio = Math.min(window.devicePixelRatio || 1, 2);
  const w = Math.round(Math.max(1, canvas.clientWidth) * ratio);
  const h = Math.round(Math.max(1, canvas.clientHeight) * ratio);
  if (canvas.width !== w || canvas.height !== h) {
    canvas.width = w; canvas.height = h;
  }
  return ratio;
}

function flyTo(cx, cy, scale, ms) {
  state.anim = {
    from: { ...state.view },
    to: { cx, cy, scale },
    start: performance.now(),
    ms: ms == null ? 420 : ms,
  };
  requestRedraw();
}

function flyToNode(node, margin) {
  const m = margin || 3.0;
  const scale = Math.min(canvas.clientWidth, canvas.clientHeight)
    / (Math.max(node.r, 1) * 2 * m);
  flyTo(node.x, node.y, scale);
}

function stepAnimation(now) {
  if (!state.anim) return false;
  const a = state.anim;
  const t = Math.min(1, (now - a.start) / a.ms);
  const e = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
  state.view.cx = a.from.cx + (a.to.cx - a.from.cx) * e;
  state.view.cy = a.from.cy + (a.to.cy - a.from.cy) * e;
  // scale interpolates geometrically so zooming feels linear
  state.view.scale = a.from.scale * Math.pow(a.to.scale / a.from.scale, e);
  if (t >= 1) state.anim = null;
  return true;
}

function screenToWorld(sx, sy) {
  return [
    (sx - canvas.clientWidth / 2) / state.view.scale + state.view.cx,
    -(sy - canvas.clientHeight / 2) / state.view.scale + state.view.cy,
  ];
}

function worldToScreen(x, y) {
  return [
    (x - state.view.cx) * state.view.scale + canvas.clientWidth / 2,
    -(y - state.view.cy) * state.view.scale + canvas.clientHeight / 2,
  ];
}

function zoomBy(factor, sx, sy) {
  const px = sx == null ? canvas.clientWidth / 2 : sx;
  const py = sy == null ? canvas.clientHeight / 2 : sy;
  const [wx, wy] = screenToWorld(px, py);
  state.view.scale = Math.max(1e-4, Math.min(6000, state.view.scale * factor));
  const [ax, ay] = screenToWorld(px, py);
  state.view.cx += wx - ax;
  state.view.cy += wy - ay;
  state.anim = null;
  requestRedraw();
}

// ------------------------------------------------------------------ buffers

/** Packs an edge list into an instance buffer, skipping any endpoint not loaded. */
function uploadEdges(batch, edges) {
  const arr = new Float32Array(edges.length * 5);
  let i = 0;
  for (const e of edges) {
    const a = state.nodes.get(e.s);
    const b = state.nodes.get(e.d);
    if (!a || !b) continue;
    arr[i++] = a.x; arr[i++] = a.y; arr[i++] = b.x; arr[i++] = b.y; arr[i++] = e.w;
  }
  uploadBatch(batch, arr.subarray(0, i), i / 5);
}

function rebuildBuffers() {
  const nodes = state.viewNodes;
  const arr = new Float32Array(nodes.length * 8);
  let i = 0;
  for (const n of nodes) {
    const glyph = glyphOf(n);
    const color = glyph.color || palette['--ink'];
    arr[i++] = n.x; arr[i++] = n.y; arr[i++] = n.r; arr[i++] = glyph.shape;
    arr[i++] = color[0]; arr[i++] = color[1]; arr[i++] = color[2];
    arr[i++] = proposalAlpha(n);
  }
  uploadBatch(batches.nodes, arr, nodes.length);
  rebuildProposalBuffers();

  uploadEdges(batches.edges, state.viewEdges);
  uploadEdges(batches.edgesHidden, state.hiddenEdges);

  if (state.foldMarker) {
    const f = state.foldMarker;
    const color = palette['--ink-muted'];
    uploadBatch(batches.fold, new Float32Array(
      [f.x, f.y, f.r, SHAPE.RING, color[0], color[1], color[2], 1]), 1);
  } else {
    batches.fold.count = 0;
  }

  const exts = state.externals;
  const xarr = new Float32Array(exts.length * 8);
  let k = 0;
  const extColor = palette['--dir-out'];
  for (const ext of exts) {
    xarr[k++] = ext.x; xarr[k++] = ext.y; xarr[k++] = ext.r; xarr[k++] = SHAPE.RING;
    xarr[k++] = extColor[0]; xarr[k++] = extColor[1]; xarr[k++] = extColor[2];
    xarr[k++] = proposalAlpha(ext);
  }
  uploadBatch(batches.externals, xarr, exts.length);

  const links = [];
  for (const ext of exts) {
    for (const link of ext.links) {
      if (link.inner) links.push(link.inner.x, link.inner.y, ext.x, ext.y, link.weight);
    }
  }
  uploadBatch(batches.externalEdges, new Float32Array(links), links.length / 5);

  if (state.container) {
    const e = state.viewExtent;
    const color = palette['--district-line'];
    uploadBatch(batches.outline, new Float32Array(
      [e.cx, e.cy, e.r * 1.04, SHAPE.RING, color[0], color[1], color[2], 1]), 1);
  } else {
    batches.outline.count = 0;
  }

  state.buffersDirty = false;
}

/**
 * The overlay's own geometry: a status ring per touched entity, the nodes a proposal wants
 * to create, and the edges it wants to draw. One ring batch per status so each can carry
 * its own texture - a proposal has to be legible in greyscale and to a red-green colour
 * blind reader, and hue alone cannot do that for green / amber / red.
 */
function rebuildProposalBuffers() {
  const rings = { add: [], modify: [], delete: [], mark: [] };
  if (overlayActive()) {
    const push = (node, status) => {
      const spec = STATUS[status] || STATUS.mark;
      const color = palette[spec.color] || palette['--ink'];
      const list = rings[status] || rings.mark;
      list.push(node.x, node.y, Math.max(node.r, 0.5), SHAPE.RING,
        color[0], color[1], color[2], 1);
    };
    for (const node of state.viewNodes) {
      const mark = statusOf(node);
      if (mark) push(node, mark.s);
    }
    for (const node of state.proposedNodes) push(node, 'add');
    for (const ext of state.externals) {
      const mark = statusOf(ext);
      if (mark) push(ext, mark.s);
    }
  }
  uploadBatch(batches.statusAdd, new Float32Array(rings.add), rings.add.length / 8);
  uploadBatch(batches.statusModify, new Float32Array(rings.modify), rings.modify.length / 8);
  uploadBatch(batches.statusDelete, new Float32Array(rings.delete), rings.delete.length / 8);
  uploadBatch(batches.statusMark, new Float32Array(rings.mark), rings.mark.length / 8);

  const added = [];
  const addColor = palette['--prop-add'];
  for (const node of state.proposedNodes) {
    added.push(node.x, node.y, node.r, SHAPE.DIAMOND,
      addColor[0], addColor[1], addColor[2], 1);
  }
  uploadBatch(batches.proposedNodes, new Float32Array(added), added.length / 8);

  const edges = [];
  for (const conn of state.proposedEdges) {
    edges.push(conn.a.x, conn.a.y, conn.b.x, conn.b.y, 3);
  }
  uploadBatch(batches.proposedEdges, new Float32Array(edges), edges.length / 5);
}

// ------------------------------------------------------------------ drawing

let dpr = 1;

function bindDiscAttribs(batch) {
  const a = discProgram.a;
  gl.bindBuffer(gl.ARRAY_BUFFER, quadDisc);
  gl.enableVertexAttribArray(a.aCorner);
  gl.vertexAttribPointer(a.aCorner, 2, gl.FLOAT, false, 0, 0);
  gl.vertexAttribDivisor(a.aCorner, 0);

  gl.bindBuffer(gl.ARRAY_BUFFER, batch.buffer);
  const stride = 8 * 4;
  gl.enableVertexAttribArray(a.iPos);
  gl.vertexAttribPointer(a.iPos, 2, gl.FLOAT, false, stride, 0);
  gl.vertexAttribDivisor(a.iPos, 1);
  gl.enableVertexAttribArray(a.iRadius);
  gl.vertexAttribPointer(a.iRadius, 1, gl.FLOAT, false, stride, 8);
  gl.vertexAttribDivisor(a.iRadius, 1);
  gl.enableVertexAttribArray(a.iShape);
  gl.vertexAttribPointer(a.iShape, 1, gl.FLOAT, false, stride, 12);
  gl.vertexAttribDivisor(a.iShape, 1);
  gl.enableVertexAttribArray(a.iColor);
  gl.vertexAttribPointer(a.iColor, 4, gl.FLOAT, false, stride, 16);
  gl.vertexAttribDivisor(a.iColor, 1);
}

function bindEdgeAttribs(batch) {
  const a = edgeProgram.a;
  gl.bindBuffer(gl.ARRAY_BUFFER, quadEdge);
  gl.enableVertexAttribArray(a.aCorner);
  gl.vertexAttribPointer(a.aCorner, 2, gl.FLOAT, false, 0, 0);
  gl.vertexAttribDivisor(a.aCorner, 0);

  gl.bindBuffer(gl.ARRAY_BUFFER, batch.buffer);
  const stride = 5 * 4;
  gl.enableVertexAttribArray(a.iA);
  gl.vertexAttribPointer(a.iA, 2, gl.FLOAT, false, stride, 0);
  gl.vertexAttribDivisor(a.iA, 1);
  gl.enableVertexAttribArray(a.iB);
  gl.vertexAttribPointer(a.iB, 2, gl.FLOAT, false, stride, 8);
  gl.vertexAttribDivisor(a.iB, 1);
  gl.enableVertexAttribArray(a.iWeight);
  gl.vertexAttribPointer(a.iWeight, 1, gl.FLOAT, false, stride, 16);
  gl.vertexAttribDivisor(a.iWeight, 1);
}

/**
 * Device pixels (from the viewport centre, y up) to clip space. This is the only place
 * the screen convention is written down; both shaders and worldToScreen() follow it.
 */
function pixelToClip() {
  return [2 / Math.max(1, canvas.width), 2 / Math.max(1, canvas.height)];
}

function drawDiscs(batch, opts) {
  if (!batch.count) return;
  const u = discProgram.u;
  gl.useProgram(discProgram.p);
  bindDiscAttribs(batch);
  const [kx, ky] = pixelToClip();
  gl.uniform2f(u.uPixelToClip, kx, ky);
  gl.uniform2f(u.uCenter, state.view.cx, state.view.cy);
  gl.uniform1f(u.uScale, state.view.scale * dpr);
  gl.uniform1f(u.uMinPx, (opts.minPx || 0) * dpr);
  gl.uniform1f(u.uPadPx, (opts.padPx || 0) * dpr);
  gl.uniform1f(u.uAlpha, opts.alpha == null ? 1 : opts.alpha);
  const o = opts.override;
  gl.uniform4f(u.uOverride, o ? o[0] : 0, o ? o[1] : 0, o ? o[2] : 0,
    o ? (opts.overrideAlpha == null ? o[3] : opts.overrideAlpha) : 0);
  gl.uniform1f(u.uHollow, opts.hollow === false ? 0 : 1);
  gl.uniform1f(u.uDash, opts.dash || 0);
  gl.drawArraysInstanced(gl.TRIANGLES, 0, 6, batch.count);
}

function drawEdges(batch, opts) {
  if (!batch.count || opts.alpha <= 0.003) return;
  const u = edgeProgram.u;
  gl.useProgram(edgeProgram.p);
  bindEdgeAttribs(batch);
  const [kx, ky] = pixelToClip();
  gl.uniform2f(u.uPixelToClip, kx, ky);
  gl.uniform2f(u.uCenter, state.view.cx, state.view.cy);
  gl.uniform1f(u.uScale, state.view.scale * dpr);
  gl.uniform1f(u.uWidthPx, (opts.width || 1.4) * dpr);
  gl.uniform1f(u.uAlpha, opts.alpha);
  gl.uniform1f(u.uDashPx, (opts.dashPx || 0) * dpr);
  gl.uniform1f(u.uTaper, opts.taper || 0);
  const c = opts.color;
  gl.uniform4f(u.uColor, c[0], c[1], c[2], c[3] == null ? 1 : c[3]);
  gl.drawArraysInstanced(gl.TRIANGLES, 0, 6, batch.count);
}

/**
 * Faint edges get lost in a sparse view; strong ones accumulate into felt in a dense one.
 *
 * The boost used to reach 8, which against an edge colour already carrying alpha 0.07 put a
 * single line at 0.56 - so five overlapping lines were effectively opaque and a view with
 * only 57 edges read as a grey mesh. The ceiling is now low enough that overlap reads as
 * density rather than as ink.
 */
function edgeAlpha(count) {
  return Math.max(0.55, Math.min(3, 14 / Math.sqrt(count + 1)));
}

function render(now) {
  // cleared before drawing, not after: a frame that throws must not leave the flag set,
  // or every later requestRedraw() is silently dropped and the map freezes
  state.needsRedraw = false;
  try {
    renderFrame(now);
  } catch (err) {
    reportFailure('Rendering failed: ' + (err && err.message ? err.message : err));
    console.error(err);
  }
}

function reportFailure(message) {
  const box = document.getElementById('loading');
  box.style.display = 'block';
  box.textContent = message + ' — see the browser console, or run tools/preview.mjs.';
}

function renderFrame(now) {
  dpr = sizeCanvas();
  const animating = stepAnimation(now || performance.now());
  if (state.buffersDirty) rebuildBuffers();

  const bg = palette['--canvas'];
  gl.viewport(0, 0, canvas.width, canvas.height);
  gl.clearColor(bg[0], bg[1], bg[2], 1);
  gl.clear(gl.COLOR_BUFFER_BIT);
  gl.enable(gl.BLEND);
  gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);

  const dim = state.selected ? 0.34 : 1;
  const level = state.level;

  // the boundary of whatever you are inside, as context you can see but not act on
  drawDiscs(batches.outline, { alpha: 0.55 * dim, minPx: 3 });

  const isArea = level === LAYER.MODULE || level === LAYER.PACKAGE;
  if (isArea) {
    // containers read as areas: a rim disc behind an opaque fill
    const rim = palette[level === LAYER.MODULE ? '--district-line' : '--block-line'];
    const fill = palette[level === LAYER.MODULE ? '--district' : '--block'];
    drawDiscs(batches.nodes, {
      override: rim, overrideAlpha: dim, minPx: 4, padPx: 2.2, hollow: false,
    });
    drawDiscs(batches.nodes, { override: fill, overrideAlpha: dim, minPx: 4, hollow: false });
  }

  // existing dependencies fade back under a proposal: what matters then is what the
  // proposal touches, and full-strength edges everywhere drown the overlay out
  const edgeWidth = level === LAYER.MODULE ? 2.2 : (level === LAYER.PACKAGE ? 1.8 : 1.4);
  const edgeFade = dim * (overlayActive() ? 0.22 : 1);
  // the thinned-away tail, on request or around whatever the cursor is on
  if (state.showAllEdges) {
    drawEdges(batches.edgesHidden, {
      color: palette['--edge'],
      alpha: edgeAlpha(batches.edgesHidden.count + batches.edges.count) * edgeFade * 0.7,
      width: edgeWidth,
    });
  } else {
    drawEdges(batches.edgesHovered, {
      color: palette['--edge'], alpha: 1.6 * edgeFade, width: edgeWidth, dashPx: 7,
    });
  }
  drawEdges(batches.edges, {
    color: palette['--edge'],
    alpha: edgeAlpha(batches.edges.count) * edgeFade,
    width: edgeWidth,
  });

  if (!isArea) {
    // types are marks, not areas: a surface ring keeps overlapping glyphs separable
    drawDiscs(batches.nodes, {
      override: palette['--canvas'], overrideAlpha: dim, minPx: 4, padPx: 2, hollow: false,
    });
    drawDiscs(batches.nodes, { alpha: dim, minPx: 4 });
  }

  drawExternals(dim);
  // the folded tail: an outline you can open, drawn like a container because that is what
  // it is - the rest of this view's contents
  if (batches.fold.count) {
    drawDiscs(batches.fold, {
      override: palette['--canvas'], overrideAlpha: dim, minPx: 9, padPx: 3, hollow: false,
    });
    drawDiscs(batches.fold, { alpha: 0.9 * dim, minPx: 9 });
  }
  drawProposal();
  drawHighlight();

  updateLabels();
  if (animating) requestRedraw();
}

/**
 * The overlay, on top of everything else. Drawn last and at full strength: it is the one
 * thing on screen that is not a fact about the code, and it has to win the eye.
 */
function drawProposal() {
  if (!overlayActive()) return;

  // a proposed dependency, tapering towards the thing it would use
  drawEdges(batches.proposedEdges, {
    color: palette['--prop-add'], alpha: 0.95, width: 5.5, taper: 0.72,
  });

  // entities that do not exist yet: a diamond, on a surface ring so one landing near an
  // existing glyph stays separable from it
  drawDiscs(batches.proposedNodes, {
    override: palette['--canvas'], minPx: 7, padPx: 2.6, hollow: false,
  });
  drawDiscs(batches.proposedNodes, { minPx: 7, hollow: false });

  /*
   * Status rings, each with its own texture: doubled for an addition, single for a change,
   * dashed for a deletion, faint for a note. The texture is not decoration - green and red
   * are indistinguishable to a deuteranopic reader and in print, so the ring pattern is
   * what actually carries the status; the colour is the fast path for everyone else.
   */
  drawDiscs(batches.statusAdd, { minPx: 6, padPx: 3.4 });
  drawDiscs(batches.statusAdd, { minPx: 6, padPx: 8.4 });
  drawDiscs(batches.statusModify, { minPx: 6, padPx: 3.4 });
  drawDiscs(batches.statusDelete, { minPx: 6, padPx: 3.4, dash: 11 });
  drawDiscs(batches.statusMark, { minPx: 6, padPx: 3.4, alpha: 0.55 });
}

function drawExternals(dim) {
  if (!batches.externals.count) return;
  // recessive and dashed: the dashed ring already says "elsewhere", and colouring every
  // link as well drowns out the calls actually inside the view
  drawEdges(batches.externalEdges, {
    color: palette['--edge'], alpha: edgeAlpha(batches.externalEdges.count) * 0.8 * dim,
    width: 1.3, dashPx: 8,
  });
  drawDiscs(batches.externals, {
    override: palette['--canvas'], overrideAlpha: dim, minPx: 7, padPx: 2.5, hollow: false,
  });
  drawDiscs(batches.externals, { alpha: dim, minPx: 7, dash: 13 });
}

function drawHighlight() {
  if (!state.selected) return;
  drawEdges(batches.highlightIn, { color: palette['--dir-in'], alpha: 0.85, width: 1.8 });
  drawEdges(batches.highlightOut, { color: palette['--dir-out'], alpha: 0.85, width: 1.8 });
  drawDiscs(batches.highlight, { minPx: 7 });
}

function requestRedraw() {
  if (state.needsRedraw) return;
  state.needsRedraw = true;
  requestAnimationFrame(render);
}

// ------------------------------------------------------------------- labels

const labelHost = document.getElementById('labels');
const labelPool = [];

/** Measures label text once per string, so collision testing uses real widths. */
const textRuler = (() => {
  const ctx = document.createElement('canvas').getContext('2d');
  const cache = new Map();
  return (text, font) => {
    const key = font + '\u001f' + text;
    let width = cache.get(key);
    if (width === undefined) {
      ctx.font = font;
      width = ctx.measureText(text).width;
      if (cache.size > 4000) cache.clear();
      cache.set(key, width);
    }
    return width;
  };
})();

const LABEL_BUDGET = 60;

/** Trims text from the left until it fits, keeping the distinctive tail. */
function fitToWidth(text, maxPx, font) {
  if (textRuler(text, font) <= maxPx) return text;
  for (let keep = text.length - 1; keep >= 4; keep--) {
    const candidate = '\u2026' + text.slice(text.length - keep);
    if (textRuler(candidate, font) <= maxPx) return candidate;
  }
  return '';
}

/**
 * Places labels for the current view only.
 *
 * Two things make this readable rather than a wall of text: entities are labelled in
 * order of importance until the screen is full, and placement uses the label's measured
 * rectangle instead of a coarse grid - a 35-character name is 250 pixels wide and has to
 * reserve all of it. Anything that cannot be placed is simply not labelled; hovering or
 * selecting still names it.
 */
function updateLabels() {
  const w = canvas.clientWidth, h = canvas.clientHeight;
  const scale = state.view.scale;
  const cls = state.level === LAYER.MODULE ? 'district'
    : (state.level === LAYER.PACKAGE ? 'block' : 'type');
  const isArea = state.level === LAYER.MODULE || state.level === LAYER.PACKAGE;

  const candidates = [];
  const overlay = overlayActive();
  for (const node of state.viewNodes) {
    /*
     * While a proposal is up, only what it touches gets named. This is the same "everything
     * else recedes" rule as the dimming, applied to text - and text is where it matters
     * most, because sixty labels are exactly what stops you seeing the two that changed.
     */
    const mark = overlay ? statusOf(node) : null;
    if (overlay && !mark) continue;
    const rPx = Math.max(node.r * scale, 4);
    if (rPx < 6 && !mark) continue;
    const [sx, sy] = worldToScreen(node.x, node.y);
    if (sx < -60 || sx > w + 60 || sy < -24 || sy > h + 24) continue;
    // importance: how big it is on screen, and how much depends on it
    candidates.push({
      node, sx, sy, rPx, cls,
      priority: rPx * 2 + (node.in || 0) * 0.6 + (node.out || 0) * 0.2
        + (mark ? (mark.own ? 4e5 : 2e5) : 0),
    });
  }
  for (const node of state.proposedNodes) {
    const [sx, sy] = worldToScreen(node.x, node.y);
    if (sx < -60 || sx > w + 60 || sy < -24 || sy > h + 24) continue;
    candidates.push({
      node, sx, sy, rPx: Math.max(node.r * scale, 7), cls: 'prop',
      priority: 2e6,                            // a thing that does not exist yet needs a name
    });
  }
  if (state.foldMarker) {
    // truncation has to be visible, so this label is never dropped for budget
    const [sx, sy] = worldToScreen(state.foldMarker.x, state.foldMarker.y);
    candidates.push({
      node: state.foldMarker, sx, sy,
      rPx: Math.max(state.foldMarker.r * scale, 9), cls: 'fold', priority: 3e6,
    });
  }
  for (const ext of state.externals) {
    // an untouched way out is context, and context is exactly what recedes under a proposal
    if (overlay && !statusOf(ext)) continue;
    const [sx, sy] = worldToScreen(ext.x, ext.y);
    if (sx < -60 || sx > w + 60 || sy < -24 || sy > h + 24) continue;
    candidates.push({
      node: ext, sx, sy, rPx: Math.max(ext.r * scale, 7), cls: 'port',
      priority: 1e6 + ext.out + ext.in,          // the way out is always worth naming
    });
  }
  candidates.sort((a, b) => b.priority - a.priority);

  // whatever is under the cursor or selected gets named regardless of budget
  const forced = new Set();
  if (state.hover) forced.add(state.hover);
  if (state.selected) forced.add(state.selected);

  const font = getComputedStyle(labelHost).font || '13px system-ui';
  const placed = [];
  const shown = [];
  const free = (rect) => !placed.some((p) => !(rect.x2 < p.x1 || rect.x1 > p.x2
    || rect.y2 < p.y1 || rect.y1 > p.y2));

  for (const c of candidates) {
    const must = forced.has(c.node);
    if (shown.length >= LABEL_BUDGET && !must) continue;
    let text = labelFor(c.node);
    // Uppercase here rather than in CSS. text-transform changes what is rendered but not
    // what measureText() sees, so the placer reserved the mixed-case width and then the
    // browser drew something ~20% wider - which is why module labels collided.
    if (c.cls === 'district') text = text.toUpperCase();
    if (!text) continue;
    /*
     * A big area holds its own label, truncated to fit: a 30-character module name is
     * wider than its circle, and left untruncated it overlaps the neighbours and neither
     * gets placed. A small area cannot hold anything, so it is labelled like a mark -
     * beside itself. Container sizes track their content, so a project's modules span
     * more than an order of magnitude and both cases occur in the same view.
     */
    const holdsItsLabel = isArea && !['port', 'prop', 'fold'].includes(c.cls)
      && c.rPx >= 34;
    if (holdsItsLabel) {
      text = fitToWidth(text, c.rPx * 1.8, font);
      if (!text) continue;
    }
    const tw = textRuler(text, font) + 6;
    const th = 15;

    // Several candidate positions, cheapest first. An area is usually big enough to hold
    // its own label, so try inside before reaching outside; a mark has nothing to hold a
    // label and has to go beside itself. More candidates means more labels survive
    // placement, which is the difference between naming four things and naming forty.
    const inset = Math.min(c.rPx * 0.62, 150);
    const offsets = holdsItsLabel
      ? [[0, -inset], [0, 0], [0, inset], [0, -(c.rPx + 11)], [0, c.rPx + 11],
         [c.rPx + tw / 2 + 8, 0], [-(c.rPx + tw / 2 + 8), 0]]
      : [[0, c.rPx + 11], [0, -(c.rPx + 11)],
         [c.rPx + tw / 2 + 8, 0], [-(c.rPx + tw / 2 + 8), 0],
         [0, c.rPx + 26], [0, -(c.rPx + 26)],
         [c.rPx + tw / 2 + 8, 16], [-(c.rPx + tw / 2 + 8), 16]];

    let chosen = null;
    for (const [dx, dy] of offsets) {
      const rect = {
        x1: c.sx + dx - tw / 2, x2: c.sx + dx + tw / 2,
        y1: c.sy + dy - th / 2, y2: c.sy + dy + th / 2,
      };
      if (free(rect) || must) { chosen = { dx, dy, rect }; break; }
    }
    if (!chosen) continue;
    placed.push(chosen.rect);
    shown.push({ ...c, text, dx: chosen.dx, dy: chosen.dy });
  }

  while (labelPool.length < shown.length) {
    const el = document.createElement('div');
    el.className = 'lbl';
    labelHost.appendChild(el);
    labelPool.push(el);
  }
  for (let i = 0; i < labelPool.length; i++) {
    const el = labelPool[i];
    const c = shown[i];
    if (!c) {
      el.style.display = 'none';
      el._node = null;
      continue;
    }
    el.style.display = '';
    el.className = 'lbl ' + c.cls;
    if (el.textContent !== c.text) el.textContent = c.text;
    el.style.fontSize = c.cls === 'district'
      ? Math.min(19, Math.max(11, c.rPx * 0.05)) + 'px' : '';
    el.style.transform =
      `translate(-50%, -50%) translate(${c.sx + c.dx}px, ${c.sy + c.dy}px)`;
    el._node = c.node;
  }

  const badge = document.getElementById('prefix');
  badge.textContent = state.labelPrefix ? state.labelPrefix + '\u2026' : '';
  badge.title = state.labelPrefix ? 'shared prefix, hidden from labels' : '';
}

function shortenLeft(text, max) {
  if (!text || text.length <= max) return text || '';
  return '…' + text.slice(text.length - max + 1);
}

labelHost.addEventListener('click', (ev) => {
  const el = ev.target.closest('.lbl');
  if (el && el._node) activate(el._node);
});

// ------------------------------------------------------------------ picking

/** Hit testing only ever considers what the current view actually drew. */
function pick(sx, sy) {
  const [wx, wy] = screenToWorld(sx, sy);
  const slack = 4 / state.view.scale;
  const floor = 7 / state.view.scale;

  if (state.foldMarker) {
    const f = state.foldMarker;
    if (Math.hypot(f.x - wx, f.y - wy) <= Math.max(f.r, floor) + slack) return f;
  }
  // proposed nodes first: they are drawn on top, so they should be clickable on top
  for (const node of state.proposedNodes) {
    if (Math.hypot(node.x - wx, node.y - wy) <= Math.max(node.r, floor) + slack) return node;
  }
  for (const ext of state.externals) {
    if (Math.hypot(ext.x - wx, ext.y - wy) <= Math.max(ext.r, floor) + slack) return ext;
  }
  let best = null, bestDist = Infinity;
  for (const n of state.viewNodes) {
    const r = Math.max(n.r, 4 / state.view.scale);
    const d = Math.hypot(n.x - wx, n.y - wy);
    if (d > r + slack) continue;
    if (d < bestDist) { bestDist = d; best = n; }
  }
  return best;
}

// -------------------------------------------------------------- interaction

let drag = null;

canvas.addEventListener('pointerdown', (ev) => {
  canvas.setPointerCapture(ev.pointerId);
  drag = { x: ev.clientX, y: ev.clientY, moved: 0, cx: state.view.cx, cy: state.view.cy };
  canvas.classList.add('dragging');
  state.anim = null;
});

canvas.addEventListener('pointermove', (ev) => {
  if (drag) {
    const dx = ev.clientX - drag.x;
    const dy = ev.clientY - drag.y;
    drag.moved = Math.max(drag.moved, Math.abs(dx) + Math.abs(dy));
    state.view.cx = drag.cx - dx / state.view.scale;
    state.view.cy = drag.cy + dy / state.view.scale;
    requestRedraw();
    return;
  }
  const rect = canvas.getBoundingClientRect();
  const hit = pick(ev.clientX - rect.left, ev.clientY - rect.top);
  if (hit !== state.hover) {
    state.hover = hit;
    showTooltip(hit, ev.clientX, ev.clientY);
    // thinning hides the long tail, so hovering an entity puts its own share of it back
    uploadEdges(batches.edgesHovered, hiddenEdgesFor(hit));
    requestRedraw();
  } else if (hit) {
    positionTooltip(ev.clientX, ev.clientY);
  }
});

canvas.addEventListener('pointerup', (ev) => {
  canvas.classList.remove('dragging');
  const wasDrag = drag && drag.moved > 4;
  drag = null;
  if (wasDrag) return;
  const rect = canvas.getBoundingClientRect();
  const hit = pick(ev.clientX - rect.left, ev.clientY - rect.top);
  if (!hit) clearSelection();
  else selectNode(hit);
});

canvas.addEventListener('dblclick', (ev) => {
  ev.preventDefault();
  const rect = canvas.getBoundingClientRect();
  const hit = pick(ev.clientX - rect.left, ev.clientY - rect.top);
  if (hit) activate(hit);
});

/** What a double-click means: open the level below, or travel to a dashed external. */
async function activate(node) {
  if (node.isFold) {                                     // open the folded tail in place
    state.expandedFolds.add(state.container.id);
    buildView();
    updateStats();
    fitView();
    requestRedraw();
    return;
  }
  if (node.isProposed) { flyToNode(node, 5); return; }   // nothing to open: it is a plan
  if (node.isExternal) {
    const target = state.nodes.get(node.targetId);
    if (target) await openView(target);
    return;
  }
  // anything with something inside it can be opened; a callable is the end of the road
  if (node.layer === LAYER.MEMBER) { flyToNode(node, 4); return; }
  await openView(node);
}

canvas.addEventListener('wheel', (ev) => {
  ev.preventDefault();
  const rect = canvas.getBoundingClientRect();
  const factor = Math.pow(1.0016, -ev.deltaY * (ev.deltaMode === 1 ? 16 : 1));
  zoomBy(factor, ev.clientX - rect.left, ev.clientY - rect.top);
}, { passive: false });

window.addEventListener('keydown', (ev) => {
  if (ev.target.tagName === 'INPUT') {
    if (ev.key === 'Escape') { ev.target.blur(); closeResults(); }
    return;
  }
  if (ev.key === 'Escape') clearSelection();
  else if (ev.key === 'Backspace') { ev.preventDefault(); goUp(); }
  else if (ev.key === 'f') { fitView(); requestRedraw(); }
  else if (ev.key === 'e') {
    state.showAllEdges = !state.showAllEdges;
    updateStats();
    buildLegend();
    requestRedraw();
  }
  else if (ev.key === '+' || ev.key === '=') zoomBy(1.3);
  else if (ev.key === '-') zoomBy(1 / 1.3);
  else if (ev.key === '/') { ev.preventDefault(); document.getElementById('search').focus(); }
});

function goUp() {
  if (!state.container) return;
  openView(state.nodes.get(state.container.parent) || null);
}

window.addEventListener('resize', () => requestRedraw());
// The window-resize event misses layout changes that only affect the element, and
// changing canvas.width/height wipes the buffer, so watch the element itself.
if (typeof ResizeObserver !== 'undefined') {
  new ResizeObserver(() => requestRedraw()).observe(canvas);
}
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) requestRedraw();
});
window.addEventListener('pageshow', () => requestRedraw());

document.getElementById('zoom-in').onclick = () => zoomBy(1.4);
document.getElementById('zoom-out').onclick = () => zoomBy(1 / 1.4);
document.getElementById('reset').onclick = () => {
  clearSelection();
  fitView();
  requestRedraw();
};
document.getElementById('closeside').onclick = () => clearSelection();

document.getElementById('theme').onclick = () => {
  document.documentElement.setAttribute('data-theme',
    currentTheme() === 'dark' ? 'light' : 'dark');
  readPalette();
  state.buffersDirty = true;
  if (state.selected) rebuildHighlight();
  buildLegend();
  requestRedraw();
};

// ------------------------------------------------------------- selection

function selectNode(node) {
  if (node && node.isFold) return;      // a door has nothing to inspect
  state.selected = node;
  rebuildHighlight();
  showDetails(node);
  requestRedraw();
}

function clearSelection() {
  state.selected = null;
  clearHighlight();
  document.getElementById('side').classList.remove('open');
  hideTooltip();
  requestRedraw();
}

function clearHighlight() {
  batches.highlight.count = 0;
  batches.highlightOut.count = 0;
  batches.highlightIn.count = 0;
}

/**
 * The selection and its neighbours as rings, never fills: the glyph is already on the
 * map, and a filled mark would cover a whole package at package level.
 */
function rebuildHighlight() {
  const node = state.selected;
  if (!node) return;
  const discs = [];
  const out = [];
  const inc = [];

  const addRing = (n, colorRole) => {
    const color = palette[colorRole] || palette['--ink'];
    discs.push(n.x, n.y, Math.max(n.r, 0.5), SHAPE.RING,
      color[0], color[1], color[2], 1);
  };
  addRing(node, '--ink');

  if (node.isExternal) {
    for (const link of node.links) {
      if (!link.inner) continue;
      if (link.outgoing) out.push(link.inner.x, link.inner.y, node.x, node.y, link.weight);
      else inc.push(node.x, node.y, link.inner.x, link.inner.y, link.weight);
      addRing(link.inner, link.outgoing ? '--dir-out' : '--dir-in');
    }
  } else {
    for (const e of state.viewEdges) {
      if (e.s === node.id) {
        const other = state.nodes.get(e.d);
        if (!other) continue;
        out.push(node.x, node.y, other.x, other.y, e.w);
        addRing(other, '--dir-out');
      } else if (e.d === node.id) {
        const other = state.nodes.get(e.s);
        if (!other) continue;
        inc.push(other.x, other.y, node.x, node.y, e.w);
        addRing(other, '--dir-in');
      }
    }
    // the dashed externals this node reaches are part of its story too
    for (const ext of state.externals) {
      for (const link of ext.links) {
        if (!link.inner || link.inner.id !== node.id) continue;
        if (link.outgoing) out.push(node.x, node.y, ext.x, ext.y, link.weight);
        else inc.push(ext.x, ext.y, node.x, node.y, link.weight);
        addRing(ext, link.outgoing ? '--dir-out' : '--dir-in');
      }
    }
    // and so are the connections a proposal wants to add to or from it
    for (const conn of state.proposedEdges) {
      if (conn.a === node) {
        out.push(node.x, node.y, conn.b.x, conn.b.y, 3);
        addRing(conn.b, '--dir-out');
      } else if (conn.b === node) {
        inc.push(conn.a.x, conn.a.y, node.x, node.y, 3);
        addRing(conn.a, '--dir-in');
      }
    }
  }
  uploadBatch(batches.highlight, new Float32Array(discs), discs.length / 8);
  uploadBatch(batches.highlightOut, new Float32Array(out), out.length / 5);
  uploadBatch(batches.highlightIn, new Float32Array(inc), inc.length / 5);
}

// ------------------------------------------------------------------ tooltip

const tip = document.getElementById('tip');

function showTooltip(node, x, y) {
  if (!node) { hideTooltip(); return; }
  tip.innerHTML = '';
  const name = document.createElement('div');
  name.textContent = node.name;
  const meta = document.createElement('div');
  meta.className = 'k';
  if (node.isFold) {
    meta.textContent = `${node.folded} more in this view, folded away`
      + ' · double-click to show them';
  } else if (node.isProposed) {
    meta.textContent = `proposed ${niceKind(node.kind)} · does not exist yet`
      + (node.note ? ' · ' + node.note : '');
  } else if (node.isExternal) {
    const where = ['', 'module', 'package', 'class', 'function'][node.layer] || 'entity';
    meta.textContent = `outside this view · ${where} · ${node.out} out, ${node.in} in`
      + ' · double-click to go there';
  } else {
    const deeper = node.layer === LAYER.MEMBER ? '' : ' · double-click to open';
    meta.textContent = `${niceKind(node.kind)}${node.lang ? ' · ' + node.lang : ''}`
      + ` · ${node.in} in, ${node.out} out${node.loc ? ' · ' + node.loc + ' lines' : ''}`
      + deeper;
  }
  tip.append(name, meta);
  const mark = statusOf(node);
  if (mark) {
    const prop = document.createElement('div');
    prop.className = 'k';
    prop.style.color = `var(${(STATUS[mark.s] || STATUS.mark).color})`;
    prop.textContent = describeMark(mark);
    tip.append(prop);
  }
  tip.style.display = 'block';
  positionTooltip(x, y);
}

function positionTooltip(x, y) {
  if (tip.style.display !== 'block') return;
  const pad = 14;
  const w = tip.offsetWidth, h = tip.offsetHeight;
  let left = x + pad, top = y + pad;
  if (left + w > window.innerWidth - 8) left = x - w - pad;
  if (top + h > window.innerHeight - 8) top = y - h - pad;
  tip.style.left = left + 'px';
  tip.style.top = top + 'px';
}

function hideTooltip() { tip.style.display = 'none'; }
canvas.addEventListener('pointerleave', hideTooltip);

function niceKind(kind) {
  if (!kind) return '';
  return kind.charAt(0) + kind.slice(1).toLowerCase();
}

// ------------------------------------------------------------------ sidebar

async function showDetails(node) {
  const side = document.getElementById('side');
  side.classList.add('open');
  document.getElementById('s-name').textContent = node.name;
  document.getElementById('s-qname').textContent = node.qname || '';

  const facts = document.getElementById('s-facts');
  facts.innerHTML = '';
  const rows = [];
  if (node.isProposed) {
    rows.push(['Status', 'Proposed — does not exist yet']);
    rows.push(['Kind', niceKind(node.kind)]);
    if (node.note) rows.push(['Why', node.note]);
  } else if (node.isExternal) {
    rows.push(['Where', 'outside this view']);
    rows.push(['Kind', ['', 'Module', 'Package', 'Class', 'Function'][node.layer] || '']);
    rows.push(['References out', String(node.out)]);
    rows.push(['References in', String(node.in)]);
  } else {
    rows.push(['Kind', niceKind(node.kind)]);
    rows.push(['Level', node.kind === 'GROUP' ? 'Package group'
      : ['', 'Module', 'Package / folder', 'Type', 'Function'][node.layer]]);
    if (node.lang) rows.push(['Language', node.lang]);
    if (node.path) rows.push(['Path', node.path]);
    if (node.loc) rows.push(['Lines', String(node.loc)]);
    if (node.children) rows.push(['Contains', String(node.children)]);
    rows.push(['Referenced by', String(node.in)]);
    rows.push(['References', String(node.out)]);
  }
  const mark = statusOf(node);
  if (mark) rows.push(['Proposal', describeMark(mark)]);
  for (const [k, v] of rows) {
    const dt = document.createElement('dt'); dt.textContent = k;
    const dd = document.createElement('dd'); dd.textContent = v;
    facts.append(dt, dd);
  }

  const links = document.getElementById('s-links');
  links.innerHTML = '';
  if (node.isProposed) {
    const h = document.createElement('h3');
    h.textContent = 'Part of the proposal, not of the code';
    links.append(h);
    return;
  }
  if (!node.isExternal) links.append(fileSection(node));
  if (node.isExternal) {
    const h = document.createElement('h3');
    h.textContent = 'Double-click it to go there';
    links.append(h);
    return;
  }
  links.textContent = 'Loading relationships…';
  let detail;
  try {
    detail = await getJson('/api/node?id=' + node.id);
  } catch (err) {
    links.textContent = 'Could not load relationships.';
    return;
  }
  if (state.selected !== node) return;
  links.innerHTML = '';
  links.append(
    linkSection('Depends on', detail.out, '--dir-out'),
    linkSection('Used by', detail.in, '--dir-in'),
  );
}

/**
 * The files that make up an entity. A C++ class is one node on the map but two files on
 * disk - a header that declares it and an implementation that defines it - and this is
 * where you see them individually, with the lines each one contributes.
 */
function fileSection(node) {
  const frag = document.createDocumentFragment();
  const files = node.files || [];
  if (files.length < 2 && !files.some((f) => f.role === 'implementation')) return frag;

  const h = document.createElement('h3');
  h.textContent = `Files (${files.length})`;
  frag.append(h);
  const ul = document.createElement('ul');
  for (const file of files) {
    const li = document.createElement('li');
    li.style.cursor = 'default';
    const n = document.createElement('span');
    n.className = 'n';
    n.textContent = file.path.slice(file.path.lastIndexOf('/') + 1);
    n.title = file.path;
    const w = document.createElement('span');
    w.className = 'w';
    w.textContent = `${file.role} · ${file.lines}`;
    li.append(n, w);
    ul.append(li);
  }
  frag.append(ul);
  return frag;
}

function linkSection(title, entries, colorRole) {
  const frag = document.createDocumentFragment();
  const h = document.createElement('h3');
  const sw = document.createElement('span');
  sw.className = 'swatch';
  sw.style.background = `var(${colorRole})`;
  h.append(sw, document.createTextNode(`${title} (${entries.length})`));
  frag.append(h);

  if (!entries.length) {
    const p = document.createElement('div');
    p.className = 'empty';
    p.textContent = 'Nothing';
    frag.append(p);
    return frag;
  }
  const ul = document.createElement('ul');
  for (const entry of entries.slice(0, 80)) {
    const li = document.createElement('li');
    const n = document.createElement('span');
    n.className = 'n';
    n.textContent = entry.node.name;
    n.title = entry.node.qname + '\n' + entry.breakdown;
    const w = document.createElement('span');
    w.className = 'w';
    w.textContent = niceKind(entry.kind) + ' ×' + entry.weight;
    li.append(n, w);
    li.onclick = () => reveal(entry.node);
    ul.append(li);
  }
  frag.append(ul);
  return frag;
}

/** Opens the view a node lives in, then selects it there. */
async function reveal(raw) {
  ingestNodes([raw]);
  const node = state.nodes.get(raw.id);
  await openView(node.layer === LAYER.MODULE ? null : state.nodes.get(node.parent) || null);
  selectNode(node);
}

// ---------------------------------------------------------- proposal panel

/** A node's part in the proposal, in words - never colour alone. */
function describeMark(mark) {
  const spec = STATUS[mark.s] || STATUS.mark;
  if (mark.own) {
    return 'Proposal: ' + spec.label.toLowerCase() + (mark.note ? ' — ' + mark.note : '');
  }
  const parts = [];
  if (mark.add) parts.push(mark.add + ' to add');
  if (mark.modify) parts.push(mark.modify + ' to change');
  if (mark.delete) parts.push(mark.delete + ' to delete');
  if (mark.mark) parts.push(mark.mark + ' noted');
  return 'Contains ' + (parts.join(', ') || 'a proposed change') + ' — open it to see';
}

const propPanel = document.getElementById('prop');

/**
 * The proposal as a list, beside the proposal as a picture.
 *
 * Both are needed. The map answers "where and how much"; the list answers "what exactly,
 * and why" - and it is what makes the status readable without relying on colour at all,
 * since every row spells its operation out in a word.
 */
function updateProposalPanel() {
  const proposal = state.proposal;
  if (!proposal || !proposal.changes.length) {
    propPanel.classList.remove('open');
    return;
  }
  propPanel.classList.add('open');
  document.getElementById('p-title').textContent = proposal.title || 'Proposed change';

  const counts = { add: 0, modify: 0, delete: 0, mark: 0 };
  for (const change of proposal.changes) counts[change.status] += 1;
  const lit = Object.keys(proposal.nodes).length;
  document.getElementById('p-sub').textContent =
    `${proposal.changes.length} change${proposal.changes.length === 1 ? '' : 's'} · `
    + `${lit} node${lit === 1 ? '' : 's'} lit · everything else dimmed`;

  const key = document.getElementById('p-key');
  key.innerHTML = '';
  for (const status of ['add', 'modify', 'delete', 'mark']) {
    if (!counts[status]) continue;
    const spec = STATUS[status];
    const item = document.createElement('span');
    item.append(statusGlyph(status), document.createTextNode(
      `${spec.label} ${counts[status]}`));
    key.append(item);
  }

  const list = document.getElementById('p-list');
  list.innerHTML = '';
  for (const change of proposal.changes) {
    list.append(changeRow(change));
  }
}

function changeRow(change) {
  const li = document.createElement('li');
  const op = document.createElement('span');
  op.className = 'op op-' + change.status;
  op.textContent = { add: '+', modify: '~', delete: '−', mark: '·' }[change.status] || '·';
  op.title = (STATUS[change.status] || STATUS.mark).label;

  const what = document.createElement('span');
  what.className = 'what';
  const line = document.createElement('div');
  const verb = document.createElement('span');
  verb.className = 'op op-' + change.status;
  verb.textContent = change.op + ' ';
  line.append(verb);
  const subject = document.createElement('b');
  subject.textContent = refLabel(change.op === 'connect' ? change.from : change.target)
    || change.name;
  line.append(subject);
  if (change.op === 'connect') {
    line.append(document.createTextNode(' → ' + refLabel(change.to)));
  } else if (change.op === 'add') {
    line.append(document.createTextNode(' in ' + refLabel(change.parent)));
  } else if (change.op === 'move') {
    line.append(document.createTextNode(' → ' + refLabel(change.parent)));
  }
  what.append(line);
  if (change.note) {
    const note = document.createElement('div');
    note.className = 'note';
    note.textContent = change.note;
    what.append(note);
  }
  li.append(op, what);

  // clicking a row goes to the thing it is about, at whatever depth that is
  const focus = change.op === 'add' ? change.parent
    : (change.op === 'connect' ? change.from : change.target);
  if (focus && focus.id) {
    li.onclick = () => revealById(focus.id);
    li.title = focus.qname || focus.name;
  } else {
    li.style.cursor = 'default';
  }
  return li;
}

function refLabel(ref) {
  if (!ref) return '';
  if (ref.name) return ref.name;
  if (ref.id) return '#' + ref.id;
  return ref.ref || '';
}

/** A swatch that carries the status by texture as well as colour. */
function statusGlyph(status) {
  const spec = STATUS[status] || STATUS.mark;
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('width', '11');
  svg.setAttribute('height', '11');
  svg.setAttribute('viewBox', '0 0 11 11');
  const color = `var(${spec.color})`;
  const ring = (r, dashed) => {
    const c = document.createElementNS(ns, 'circle');
    c.setAttribute('cx', '5.5');
    c.setAttribute('cy', '5.5');
    c.setAttribute('r', String(r));
    c.setAttribute('fill', 'none');
    c.setAttribute('stroke', color);
    c.setAttribute('stroke-width', '1.6');
    if (dashed) c.setAttribute('stroke-dasharray', '2 1.6');
    return c;
  };
  svg.append(ring(3, status === 'delete'));
  if (spec.double) svg.append(ring(4.9, false));
  return svg;
}

/** Opens the view a node lives in and selects it, fetching it first if need be. */
async function revealById(id) {
  let node = state.nodes.get(id);
  if (!node) {
    let detail;
    try {
      detail = await getJson('/api/node?id=' + id);
    } catch (err) {
      return;
    }
    ingestNodes([...(detail.parents || []).slice().reverse(), detail.node]);
    node = state.nodes.get(id);
  }
  if (!node) return;
  await openView(node.parent ? state.nodes.get(node.parent) || null : null);
  selectNode(node);
}

document.getElementById('p-toggle').onclick = () => {
  state.overlayOn = !state.overlayOn;
  document.getElementById('p-toggle').textContent = state.overlayOn ? 'On' : 'Off';
  buildView();
  buildLegend();
  requestRedraw();
};

// --------------------------------------------------------------- breadcrumb

function updateCrumbs() {
  const host = document.getElementById('crumbs');
  host.innerHTML = '';
  const root = document.createElement('button');
  root.textContent = state.meta.project_name || 'project';
  root.onclick = () => openView(null);
  host.append(root);

  const chain = [];
  let current = state.container;
  while (current) {
    chain.unshift(current);
    current = state.nodes.get(current.parent);
  }
  for (const item of chain) {
    const sep = document.createElement('span');
    sep.className = 'sep';
    sep.textContent = '/';
    const btn = document.createElement('button');
    btn.textContent = item.name;
    btn.title = item.qname || item.name;
    btn.onclick = () => openView(item);
    host.append(sep, btn);
  }
}

// ------------------------------------------------------------------ search

const searchInput = document.getElementById('search');
const resultsBox = document.getElementById('results');
let searchTimer = null;

searchInput.addEventListener('input', () => {
  clearTimeout(searchTimer);
  const term = searchInput.value.trim();
  if (term.length < 2) { closeResults(); return; }
  searchTimer = setTimeout(() => runSearch(term), 180);
});

async function runSearch(term) {
  let data;
  try {
    data = await getJson('/api/search?q=' + encodeURIComponent(term));
  } catch (err) {
    closeResults();
    return;
  }
  resultsBox.innerHTML = '';
  if (!data.results.length) {
    const div = document.createElement('div');
    div.textContent = 'No match';
    resultsBox.append(div);
  }
  for (const node of data.results) {
    const div = document.createElement('div');
    const name = document.createElement('span');
    name.textContent = node.name;
    const q = document.createElement('span');
    q.className = 'q';
    q.textContent = ['', 'module', 'package', niceKind(node.kind).toLowerCase(),
      niceKind(node.kind).toLowerCase()][node.layer] + ' · ' + node.qname;
    div.append(name, q);
    div.onclick = () => {
      closeResults();
      searchInput.value = '';
      reveal(node);
    };
    resultsBox.append(div);
  }
  resultsBox.classList.add('open');
}

function closeResults() { resultsBox.classList.remove('open'); }
document.addEventListener('pointerdown', (ev) => {
  if (!ev.target.closest('.searchwrap')) closeResults();
});

// ------------------------------------------------------------- legend, stats

function buildLegend() {
  const kinds = document.getElementById('legend-kinds');
  kinds.innerHTML = '';
  const groups = new Map();
  for (const spec of Object.values(KINDS)) {
    if (!groups.has(spec.group)) groups.set(spec.group, spec);
  }
  for (const [label, spec] of groups) {
    kinds.append(legendItem(label, `var(${spec.color})`, spec.shape));
  }
  kinds.append(legendItem('Outside this view', 'var(--dir-out)', SHAPE.RING));

  const edges = document.getElementById('legend-edges');
  edges.innerHTML = '';
  edges.append(
    legendLine('Reference', 'var(--edge)'),
    legendLine('Depends on selection', 'var(--dir-out)'),
    legendLine('Uses selection', 'var(--dir-in)'),
  );
  if (overlayActive()) edges.append(legendLine('Proposed', 'var(--prop-add)'));

  const hints = [];
  if (overlayActive()) {
    hints.push('A proposal is on the map. Rings mark what it touches; the panel lists it.');
  } else {
    hints.push('Drag to pan · scroll to zoom · click to inspect · double-click to enter');
  }
  if (state.hiddenEdges.length) {
    hints.push(state.showAllEdges
      ? 'Showing every edge — press e for the ones that carry the view'
      : `Showing the edges that carry the view · press e for all ${state.edgeCount}`
        + ' · hover an entity for its own');
  }
  if (state.foldMarker) {
    hints.push(`${state.foldMarker.folded} more folded — double-click the outline to open`);
  }
  document.getElementById('legend-hint').textContent = hints.join(' · ');
}

function legendItem(label, color, shape) {
  const wrap = document.createElement('span');
  wrap.className = 'item';
  wrap.append(glyphSvg(color, shape));
  wrap.append(document.createTextNode(label));
  return wrap;
}

function glyphSvg(color, shape) {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('width', '11');
  svg.setAttribute('height', '11');
  svg.setAttribute('viewBox', '0 0 11 11');
  let el;
  if (shape === SHAPE.SQUARE) {
    el = document.createElementNS(ns, 'rect');
    el.setAttribute('x', '1.5'); el.setAttribute('y', '1.5');
    el.setAttribute('width', '8'); el.setAttribute('height', '8'); el.setAttribute('rx', '1.5');
  } else if (shape === SHAPE.DIAMOND) {
    el = document.createElementNS(ns, 'path');
    el.setAttribute('d', 'M5.5 1 L10 5.5 L5.5 10 L1 5.5 Z');
  } else {
    el = document.createElementNS(ns, 'circle');
    el.setAttribute('cx', '5.5'); el.setAttribute('cy', '5.5'); el.setAttribute('r', '4');
  }
  if (shape === SHAPE.RING) {
    el.setAttribute('fill', 'none');
    el.setAttribute('stroke', color);
    el.setAttribute('stroke-width', '2');
    el.setAttribute('stroke-dasharray', '2.4 2');
  } else {
    el.setAttribute('fill', color);
  }
  svg.append(el);
  return svg;
}

function legendLine(label, color) {
  const wrap = document.createElement('span');
  wrap.className = 'item';
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('width', '16'); svg.setAttribute('height', '11');
  svg.setAttribute('viewBox', '0 0 16 11');
  const line = document.createElementNS(ns, 'line');
  line.setAttribute('x1', '1'); line.setAttribute('y1', '5.5');
  line.setAttribute('x2', '15'); line.setAttribute('y2', '5.5');
  line.setAttribute('stroke', color); line.setAttribute('stroke-width', '2');
  svg.append(line);
  wrap.append(svg, document.createTextNode(label));
  return wrap;
}

function updateStats() {
  const el = document.getElementById('stats');
  const m = state.meta;
  const label = ['', 'modules', 'packages', 'types', 'functions'][state.level] || 'entities';
  const folded = state.foldMarker ? state.foldMarker.folded : 0;
  const hidden = state.hiddenEdges.length;
  const parts = [
    folded
      ? `<b>${state.viewNodes.length}</b> of ${state.childCount} ${label}`
      : `<b>${state.viewNodes.length}</b> ${label}`,
    hidden && !state.showAllEdges
      ? `<b>${state.viewEdges.length}</b> of ${state.edgeCount} edges`
      : `<b>${state.edgeCount || state.viewEdges.length}</b> edges`,
  ];
  if (state.externals.length) parts.push(`<b>${state.externals.length}</b> outside`);
  if (overlayActive()) {
    parts.push(`<b>${state.proposal.changes.length}</b> proposed`);
  }
  parts.push(`${m.nodes_layer_1 || 0}·${m.nodes_layer_2 || 0}·${m.nodes_layer_3 || 0} total`);
  el.innerHTML = parts.join(' · ');
}

// --------------------------------------------------------------- diagnostics

/**
 * ?diag=1 prints what the renderer actually sees. Reach for this first when the map is
 * blank: it distinguishes "no data", "bad palette", "empty buffers" and "wrong camera",
 * which all look identical on screen.
 */
function showDiagnostics() {
  const box = document.createElement('pre');
  box.className = 'panel';
  box.style.cssText = 'position:fixed;top:62px;left:12px;z-index:9;padding:10px 12px;'
    + 'margin:0;max-height:70vh;overflow:auto;font-size:11px;line-height:1.5;'
    + 'white-space:pre;color:var(--ink-2)';
  document.body.appendChild(box);

  const refresh = () => {
    const debug = gl.getExtension('WEBGL_debug_renderer_info');
    const lines = [
      'theme        ' + themeName,
      'canvas css   ' + canvas.clientWidth + ' x ' + canvas.clientHeight,
      'canvas store ' + canvas.width + ' x ' + canvas.height + '  dpr=' + dpr,
      'renderer     ' + (debug ? gl.getParameter(debug.UNMASKED_RENDERER_WEBGL) : 'unknown'),
      'gl error     ' + gl.getError(),
      'level        ' + state.level
        + (state.container ? '  inside ' + state.container.qname : '  (root)'),
      'view         cx=' + state.view.cx.toFixed(1) + ' cy=' + state.view.cy.toFixed(1)
        + ' scale=' + state.view.scale.toFixed(5),
      'view         nodes=' + state.viewNodes.length + ' edges=' + state.viewEdges.length
        + ' externals=' + state.externals.length,
      'batches      nodes=' + batches.nodes.count + ' edges=' + batches.edges.count
        + ' ext=' + batches.externals.count + ' extEdges=' + batches.externalEdges.count,
      'loaded       L1=' + state.byLayer[1].length + ' L2=' + state.byLayer[2].length
        + ' L3=' + state.byLayer[3].length,
      'proposal     rev=' + state.proposalRevision
        + ' changes=' + (state.proposal ? state.proposal.changes.length : 0)
        + ' lit=' + (state.proposal ? Object.keys(state.proposal.nodes).length : 0)
        + ' overlay=' + (state.overlayOn ? 'on' : 'off'),
      'proposal     drawn nodes=' + state.proposedNodes.length
        + ' edges=' + state.proposedEdges.length
        + ' rings=' + (batches.statusAdd.count + batches.statusModify.count
          + batches.statusDelete.count + batches.statusMark.count),
    ];
    box.textContent = lines.join('\n');
  };
  refresh();
  setInterval(refresh, 700);
}

// ---------------------------------------------------------------------- go

readPalette();
window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
  readPalette();
  state.buffersDirty = true;
  buildLegend();
  requestRedraw();
});

boot().then(() => {
  if (new URLSearchParams(location.search).has('diag')) showDiagnostics();
}).catch((err) => {
  reportFailure('Could not load the map: ' + (err && err.message ? err.message : err));
  console.error(err);
});
