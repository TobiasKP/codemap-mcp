/*
 * A CPU rasteriser for the map's instance buffers, and the one place that mirrors the
 * order render() draws them in.
 *
 * This exists so a picture of the map can be produced with no browser and no GPU: it runs
 * the real web/app.js, lets it build its instance buffers, and rasterises *those exact
 * buffers* through the same worldToScreen() the app uses for hit testing. That covers
 * everything on the CPU side of the GPU boundary - positions, radii, shapes, colours,
 * per-instance alphas - which is where map rendering actually goes wrong. It does not
 * execute the shaders; those are checked with tools/shader-check.sh.
 *
 * Shared by preview.mjs (one still) and demo-gif.mjs (an animation). Keeping the pass
 * order here rather than in each of them is what stops the two drifting from render().
 */

import fs from 'node:fs';
import zlib from 'node:zlib';

export function createRasteriser({ app, width, height, palette }) {
  const buffer = new Float64Array(width * height * 3);

  function fill(rgb) {
    for (let i = 0; i < width * height; i++) {
      buffer[i * 3] = rgb[0];
      buffer[i * 3 + 1] = rgb[1];
      buffer[i * 3 + 2] = rgb[2];
    }
  }

  function blend(x, y, rgb, alpha) {
    if (alpha <= 0 || x < 0 || y < 0 || x >= width || y >= height) return;
    const i = (y * width + x) * 3;
    const a = Math.min(1, alpha);
    buffer[i] += (rgb[0] - buffer[i]) * a;
    buffer[i + 1] += (rgb[1] - buffer[i + 1]) * a;
    buffer[i + 2] += (rgb[2] - buffer[i + 2]) * a;
  }

  /** Signed distance for the same four glyph shapes the fragment shader draws. */
  function shapeDistance(px, py, shape) {
    if (shape < 1.5) return Math.hypot(px, py) - 1;
    if (shape < 2.5) return Math.max(Math.abs(px) - 0.84, Math.abs(py) - 0.84);
    return Math.abs(px) + Math.abs(py) - 1.05;
  }

  function drawDisc(cx, cy, rPx, shape, rgb, alpha, hollow, dash) {
    if (alpha <= 0.003 || rPx <= 0) return;
    const lo = Math.max(0, Math.floor(cx - rPx - 1));
    const hi = Math.min(width - 1, Math.ceil(cx + rPx + 1));
    const top = Math.max(0, Math.floor(cy - rPx - 1));
    const bot = Math.min(height - 1, Math.ceil(cy + rPx + 1));
    const aa = 1.4 / Math.max(rPx, 1.5);
    // mirrors the fragment shader: ring width clamped in pixels, then made relative
    const ringWidth = Math.min(Math.max(2.4, 1.2), rPx * 0.45) / Math.max(rPx, 1);
    for (let y = top; y <= bot; y++) {
      for (let x = lo; x <= hi; x++) {
        const lx = (x + 0.5 - cx) / rPx;
        const ly = (y + 0.5 - cy) / rPx;
        let d = shapeDistance(lx, ly, shape);
        if (hollow && shape > 0.5 && shape < 1.5) d = Math.abs(d + ringWidth) - ringWidth;
        const cover = 1 - smoothstep(-aa, aa, d);
        if (cover <= 0.002) continue;
        // mirrors the fragment shader's angular dash for entities outside the view
        if (dash) {
          const angle = Math.atan2(ly, lx) / (Math.PI * 2) + 0.5;
          if ((angle * dash) % 1 > 0.55) continue;
        }
        blend(x, y, rgb, alpha * cover);
      }
    }
  }

  function drawSegment(ax, ay, bx, by, widthPx, rgb, alpha) {
    if (alpha <= 0.003) return;
    const len = Math.hypot(bx - ax, by - ay);
    if (len < 1.5) return;                       // the shader fades these out too
    const fade = smoothstep(1.5, 6, len);
    const half = Math.max(0.5, widthPx * 0.5);
    const lo = Math.max(0, Math.floor(Math.min(ax, bx) - half - 1));
    const hi = Math.min(width - 1, Math.ceil(Math.max(ax, bx) + half + 1));
    const top = Math.max(0, Math.floor(Math.min(ay, by) - half - 1));
    const bot = Math.min(height - 1, Math.ceil(Math.max(ay, by) + half + 1));
    for (let y = top; y <= bot; y++) {
      for (let x = lo; x <= hi; x++) {
        const d = distanceToSegment(x + 0.5, y + 0.5, ax, ay, bx, by);
        const cover = 1 - smoothstep(half - 0.6, half + 0.6, d);
        if (cover > 0.002) blend(x, y, rgb, alpha * fade * cover);
      }
    }
  }

  /** Mirrors drawDiscs(): same minPx / padPx rules, and the per-instance alpha. */
  function playDiscs(batch, o) {
    const data = batch.data;
    const scale = app.state.view.scale;
    for (let i = 0; i < batch.count; i++) {
      const base = i * 8;
      const [sx, sy] = app.worldToScreen(data[base], data[base + 1]);
      const rPx = Math.max(data[base + 2] * scale, o.minPx || 0) + (o.padPx || 0);
      const rgb = o.override || [data[base + 4], data[base + 5], data[base + 6]];
      // iColor.a is a per-instance multiplier and survives an override in the shader,
      // which is how a proposal dims everything it does not touch
      const alpha = (o.overrideAlpha != null ? o.overrideAlpha
        : (o.alpha != null ? o.alpha : 1)) * data[base + 7];
      drawDisc(sx, sy, rPx, data[base + 3], rgb, alpha, o.hollow !== false, o.dash || 0);
    }
  }

  /** Mirrors drawEdges(): same width-from-weight curve. */
  function playEdges(batch, o) {
    const data = batch.data;
    for (let i = 0; i < batch.count; i++) {
      const base = i * 5;
      const [ax, ay] = app.worldToScreen(data[base], data[base + 1]);
      const [bx, by] = app.worldToScreen(data[base + 2], data[base + 3]);
      const w = (o.width || 1.4) * (0.5 + 0.18 * Math.log2(1 + data[base + 4]));
      drawSegment(ax, ay, bx, by, w, o.color,
        o.alpha * (o.color[3] == null ? 1 : o.color[3]));
    }
  }

  const edgeAlpha = (count) => Math.max(1, Math.min(8, 40 / Math.sqrt(count + 1)));

  /**
   * Replays every pass in the order render() draws them. If you add a pass to render(),
   * add it here - this function is the contract between the two.
   */
  function renderScene() {
    const level = app.state.level;
    const dim = app.state.selected ? 0.34 : 1;
    const isArea = level === 1 || level === 2;
    const overlay = app.overlayActive();
    fill(parse(palette['--canvas']));

    playDiscs(app.batches.outline, { alpha: 0.55 * dim, minPx: 3 });

    if (isArea) {
      playDiscs(app.batches.nodes, {
        override: parse(palette[level === 1 ? '--district-line' : '--block-line']),
        overrideAlpha: dim, minPx: 4, padPx: 2.2, hollow: false,
      });
      playDiscs(app.batches.nodes, {
        override: parse(palette[level === 1 ? '--district' : '--block']),
        overrideAlpha: dim, minPx: 4, hollow: false,
      });
    }

    const edgeWidth = level === 1 ? 2.2 : (level === 2 ? 1.8 : 1.4);
    const edgeFade = dim * (overlay ? 0.22 : 1);
    if (app.state.showAllEdges) {
      playEdges(app.batches.edgesHidden, {
        color: parse(palette['--edge']),
        alpha: edgeAlpha(app.batches.edgesHidden.count + app.batches.edges.count)
          * edgeFade * 0.7,
        width: edgeWidth,
      });
    }
    playEdges(app.batches.edges, {
      color: parse(palette['--edge']),
      alpha: edgeAlpha(app.batches.edges.count) * edgeFade,
      width: edgeWidth,
    });

    if (!isArea) {
      playDiscs(app.batches.nodes, {
        override: parse(palette['--canvas']), overrideAlpha: dim, minPx: 4, padPx: 2,
        hollow: false,
      });
      playDiscs(app.batches.nodes, { alpha: dim, minPx: 4 });
    }

    if (app.batches.externals.count) {
      playEdges(app.batches.externalEdges, {
        color: parse(palette['--edge']),
        alpha: edgeAlpha(app.batches.externalEdges.count) * 0.8 * dim, width: 1.3,
      });
      playDiscs(app.batches.externals, {
        override: parse(palette['--canvas']), overrideAlpha: dim, minPx: 7, padPx: 2.5,
        hollow: false,
      });
      playDiscs(app.batches.externals, { alpha: dim, minPx: 7, dash: 13 });
    }

    if (app.batches.fold.count) {
      playDiscs(app.batches.fold, {
        override: parse(palette['--canvas']), overrideAlpha: dim, minPx: 9, padPx: 3,
        hollow: false,
      });
      playDiscs(app.batches.fold, { alpha: 0.9 * dim, minPx: 9 });
    }

    // the proposal overlay. The taper on a proposed edge is a vertex-shader effect and is
    // not reproduced here, so these read as plain thick lines.
    if (overlay) {
      playEdges(app.batches.proposedEdges, {
        color: parse(palette['--prop-add']), alpha: 0.95, width: 5.5,
      });
      playDiscs(app.batches.proposedNodes, {
        override: parse(palette['--canvas']), minPx: 7, padPx: 2.6, hollow: false,
      });
      playDiscs(app.batches.proposedNodes, { minPx: 7, hollow: false });
      playDiscs(app.batches.statusAdd, { minPx: 6, padPx: 3.4 });
      playDiscs(app.batches.statusAdd, { minPx: 6, padPx: 8.4 });
      playDiscs(app.batches.statusModify, { minPx: 6, padPx: 3.4 });
      playDiscs(app.batches.statusDelete, { minPx: 6, padPx: 3.4, dash: 11 });
      playDiscs(app.batches.statusMark, { minPx: 6, padPx: 3.4, alpha: 0.55 });
    }

    if (app.state.selected) {
      playEdges(app.batches.highlightIn,
        { color: parse(palette['--dir-in']), alpha: 0.85, width: 1.8 });
      playEdges(app.batches.highlightOut,
        { color: parse(palette['--dir-out']), alpha: 0.85, width: 1.8 });
      playDiscs(app.batches.highlight, { minPx: 7 });
    }
  }

  function writePng(file) {
    const raw = Buffer.alloc((width * 3 + 1) * height);
    let p = 0;
    for (let y = 0; y < height; y++) {
      raw[p++] = 0;                                   // filter: none
      for (let x = 0; x < width; x++) {
        const i = (y * width + x) * 3;
        raw[p++] = clamp255(buffer[i] * 255);
        raw[p++] = clamp255(buffer[i + 1] * 255);
        raw[p++] = clamp255(buffer[i + 2] * 255);
      }
    }
    const chunks = [Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])];
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(width, 0);
    ihdr.writeUInt32BE(height, 4);
    ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;  // 8-bit truecolour
    chunks.push(chunk('IHDR', ihdr));
    chunks.push(chunk('IDAT', zlib.deflateSync(raw, { level: 6 })));
    chunks.push(chunk('IEND', Buffer.alloc(0)));
    fs.writeFileSync(file, Buffer.concat(chunks));
  }

  function paintedPercent() {
    const bg = parse(palette['--canvas']);
    let n = 0;
    for (let i = 0; i < width * height; i++) {
      const j = i * 3;
      if (Math.abs(buffer[j] - bg[0]) > 0.004 || Math.abs(buffer[j + 1] - bg[1]) > 0.004
          || Math.abs(buffer[j + 2] - bg[2]) > 0.004) n++;
    }
    return (n / (width * height)) * 100;
  }

  return { renderScene, writePng, paintedPercent, fill, playDiscs, playEdges };
}

// -------------------------------------------------------------------- helpers

export function smoothstep(a, b, x) {
  const t = Math.max(0, Math.min(1, (x - a) / (b - a)));
  return t * t * (3 - 2 * t);
}

function distanceToSegment(px, py, ax, ay, bx, by) {
  const dx = bx - ax, dy = by - ay;
  const len2 = dx * dx + dy * dy;
  const t = len2 < 1e-9 ? 0
    : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
  return Math.hypot(px - (ax + dx * t), py - (ay + dy * t));
}

export function parse(value) {
  if (Array.isArray(value)) return value;
  if (!value) return [0, 0, 0, 1];
  if (value.startsWith('#')) {
    let hex = value.slice(1);
    if (hex.length === 3) hex = hex.split('').map((c) => c + c).join('');
    const n = parseInt(hex.slice(0, 6), 16);
    return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255, 1];
  }
  const m = value.match(/-?[\d.]+/g) || [0, 0, 0];
  return [+m[0] / 255, +m[1] / 255, +m[2] / 255, m.length > 3 ? +m[3] : 1];
}

function clamp255(v) {
  return Math.max(0, Math.min(255, Math.round(v)));
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body) >>> 0, 0);
  return Buffer.concat([len, body, crc]);
}

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return c ^ -1;
}
