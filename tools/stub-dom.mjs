/*
 * A stub DOM and WebGL2 context, just complete enough to run web/app.js outside a
 * browser. Shared by tools/frontend-smoke.mjs (assertions) and tools/preview.mjs
 * (rasterises the instance buffers to a PNG).
 *
 * The GL stub records draw calls rather than drawing: what the tools inspect is the
 * instance data app.js uploaded, which is where geometry, colour and level-of-detail
 * bugs actually live.
 */
import fs from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const WEB = path.join(ROOT, 'src/main/resources/web');

/** Every custom property the renderer reads, with the light-theme values. */
export const PALETTE = {
  '--canvas': '#f7f6f3', '--district': '#ecebe4', '--district-line': '#c6c3b4',
  '--block': '#dedbd0', '--block-line': '#aeab9b', '--edge': 'rgba(11, 11, 11, 0.07)',
  '--kind-class': '#2a78d6', '--kind-interface': '#eb6834', '--kind-value': '#1baf7a',
  '--kind-file': '#898781', '--dir-out': '#2a78d6', '--dir-in': '#eb6834', '--ink': '#0b0b0b',
  '--ink-muted': '#898781',
  '--prop-add': '#0e7546', '--prop-change': '#dfa300', '--prop-del': '#c4291c',
};

export const DARK_PALETTE = {
  '--canvas': '#0d0d0d', '--district': '#1c1c1a', '--district-line': '#3d3d37',
  '--block': '#2b2b28', '--block-line': '#52524a', '--edge': 'rgba(255, 255, 255, 0.09)',
  '--kind-class': '#3987e5', '--kind-interface': '#d95926', '--kind-value': '#199e70',
  '--kind-file': '#898781', '--dir-out': '#3987e5', '--dir-in': '#d95926', '--ink': '#ffffff',
  '--ink-muted': '#898781',
  '--prop-add': '#33aa74', '--prop-change': '#b8860b', '--prop-del': '#c73b2e',
};

class El {
  constructor(tag, viewport) {
    this.tagName = (tag || 'div').toUpperCase();
    this.children = [];
    this.style = {};
    this.dataset = {};
    this._text = '';
    this._attrs = {};
    this._listeners = {};
    this.className = '';
    this.classList = {
      _set: new Set(),
      add: (c) => this.classList._set.add(c),
      remove: (c) => this.classList._set.delete(c),
      contains: (c) => this.classList._set.has(c),
    };
    this.offsetWidth = 120;
    this.offsetHeight = 30;
    this.clientWidth = viewport.w;
    this.clientHeight = viewport.h;
    this.width = viewport.w;
    this.height = viewport.h;
    this.value = '';
  }
  get textContent() { return this._text; }
  set textContent(v) { this._text = String(v); this.children.length = 0; }
  get innerHTML() { return this._text; }
  set innerHTML(v) { this._text = String(v); this.children.length = 0; }
  append(...kids) { for (const k of kids) this.children.push(k); }
  appendChild(k) { this.children.push(k); return k; }
  addEventListener(type, fn) { (this._listeners[type] ||= []).push(fn); }
  removeEventListener() {}
  setAttribute(k, v) { this._attrs[k] = String(v); }
  getAttribute(k) { return this._attrs[k] ?? null; }
  setPointerCapture() {}
  getBoundingClientRect() {
    return { left: 0, top: 0, width: this.clientWidth, height: this.clientHeight };
  }
  closest(sel) { return sel === '.lbl' && this.className.includes('lbl') ? this : null; }
  querySelector() { return null; }
  /** Fires both an addEventListener handler and an `onclick`-style property. */
  fire(type, event) {
    const direct = this['on' + type];
    if (typeof direct === 'function') direct(event);
    for (const fn of this._listeners[type] || []) fn(event);
  }
}

/**
 * Builds the environment and evaluates app.js inside it.
 *
 * @param {{viewport?: {w:number,h:number}, palette?: object, base: string}} opts
 * @returns {{app: object, el: Function, glCalls: object, rafQueue: Array,
 *            frame: Function, labels: object}}
 */
export function createEnvironment(opts) {
  const viewport = opts.viewport || { w: 1440, h: 900 };
  const palette = opts.palette || PALETTE;
  const base = opts.base.replace(/\/$/, '');
  // app.js reads its own palette from THEMES at load time and picks the theme from
  // data-theme, so it has to be stamped before the script is evaluated.
  const theme = opts.theme || 'light';

  const byId = new Map();
  const el = (id) => {
    if (!byId.has(id)) {
      const e = new El('div', viewport);
      e.id = id;
      byId.set(id, e);
    }
    return byId.get(id);
  };

  const glCalls = { drawArraysInstanced: 0, bufferData: 0, useProgram: 0, draws: [] };
  let locCounter = 0;
  const glReal = {
    createShader: () => ({}),
    createProgram: () => ({}),
    createBuffer: () => ({}),
    shaderSource: () => {},
    compileShader: () => {},
    attachShader: () => {},
    linkProgram: () => {},
    getShaderParameter: () => true,
    getProgramParameter: () => true,
    getShaderInfoLog: () => '',
    getProgramInfoLog: () => '',
    getAttribLocation: () => locCounter++,
    getUniformLocation: () => ({}),
    bindBuffer: () => {},
    bufferData: () => { glCalls.bufferData++; },
    useProgram: () => { glCalls.useProgram++; },
    drawArraysInstanced: (mode, first, count, instances) => {
      glCalls.drawArraysInstanced++;
      if (!Number.isFinite(instances) || instances < 0) {
        throw new Error('bad instance count: ' + instances);
      }
    },
  };
  const glStub = new Proxy(glReal, {
    get(target, prop) {
      if (prop in target) return target[prop];
      if (typeof prop === 'string' && /^[A-Z][A-Z0-9_]*$/.test(prop)) return 1;
      return () => {};
    },
  });

  const canvasEl = el('map');
  canvasEl.getContext = () => glStub;

  // the label ruler measures text with a 2D context; approximate it by character count
  const ruler2d = { font: '', measureText: (t) => ({ width: t.length * 6.6 }) };

  const rafQueue = [];
  const root = new El('html', viewport);
  root.setAttribute('data-theme', theme);
  const context = {
    console,
    fetch: (url, init) => fetch(url.startsWith('http') ? url : base + url, init),
    performance: { now: () => Date.now() },
    requestAnimationFrame: (fn) => { rafQueue.push(fn); return rafQueue.length; },
    cancelAnimationFrame: () => {},
    setTimeout: (fn) => { globalThis.setTimeout(fn, 0); return 0; },
    clearTimeout: () => {},
    // the app polls for a proposal on an interval; the tests drive pollProposal() by hand,
    // so the timer is registered and never fired
    setInterval: () => 0,
    clearInterval: () => {},
    encodeURIComponent,
    URLSearchParams,
    location: { search: '' },
    Math, JSON, Date, Set, Map, Array, Object, String, Number, Boolean, Error,
    Float32Array, Promise, isFinite, parseInt, parseFloat,
    getComputedStyle: () => ({ getPropertyValue: (n) => palette[n] || '', font: '13px sans-serif' }),
    document: {
      documentElement: root,
      body: new El('body', viewport),
      head: new El('head', viewport),
      getElementById: el,
      createElement: (t) => {
        const node = new El(t, viewport);
        if (t === 'canvas') node.getContext = () => ruler2d;
        return node;
      },
      createElementNS: (ns, t) => new El(t, viewport),
      createDocumentFragment: () => new El('#fragment', viewport),
      createTextNode: (text) => {
        const node = new El('#text', viewport);
        node.textContent = text;
        return node;
      },
      addEventListener: () => {},
      title: '',
    },
    window: {
      innerWidth: viewport.w,
      innerHeight: viewport.h,
      devicePixelRatio: 1,
      addEventListener: () => {},
      matchMedia: () => ({ addEventListener: () => {}, matches: palette === DARK_PALETTE }),
    },
  };
  context.globalThis = context;
  context.window.document = context.document;

  const source = fs.readFileSync(path.join(WEB, 'app.js'), 'utf8');
  // the appended expression captures app.js's top-level lexical bindings for inspection
  const exposed = `
;globalThis.__app = { state, batches, render, pick, updateLabels, fitView, goUp, activate,
  openView, buildView, computeExternals, loadChildren, isInside, selectNode, clearSelection,
  worldToScreen, screenToWorld, zoomBy, flyToNode, kindOf, palette: () => palette,
  pollProposal, overlayActive, statusOf, proposalAlpha, updateProposalPanel, STATUS }`;
  vm.createContext(context);
  vm.runInContext(source + exposed, context, { filename: 'app.js' });
  const app = context.globalThis.__app;

  /**
   * Runs whatever the app scheduled through requestAnimationFrame and returns how many
   * callbacks there were. It deliberately does NOT fall back to calling render()
   * directly: "nothing was scheduled" is a real defect (the app failing to ask for a
   * frame), and a convenience fallback here hid exactly that bug for a long time.
   */
  const frame = () => {
    const pending = rafQueue.splice(0, rafQueue.length);
    for (const fn of pending) fn(Date.now());
    return pending.length;
  };

  /** Forces a draw when the test itself moved the camera rather than the app. */
  const drawNow = () => app.render(Date.now());

  return { app, el, glCalls, rafQueue, frame, drawNow, viewport, palette,
    labels: el('labels') };
}
