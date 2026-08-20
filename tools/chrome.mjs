/*
 * Draws the UI chrome onto a rasterised frame: the top bar, the proposal panel, the legend
 * and the status line.
 *
 * The canvas is only half the map. What a change *is* - "add CLASS RetentionPolicy in
 * notifier, because ..." - lives in the proposal panel, and what a colour or a shape means
 * lives in the legend. A picture of the canvas alone shows coloured rings with no way to
 * know what they say, which is exactly the complaint this file answers.
 *
 * It reads the panels' real contents out of the DOM the app populated - the same
 * updateProposalPanel() and buildLegend() that run in a browser - so the text is never
 * invented here. Only the geometry is duplicated, mirroring the fixed positions in
 * index.html; if you move a panel in the CSS, move it here too.
 */

import { statSync } from 'node:fs';
import { execFileSync } from 'node:child_process';

/** Panel geometry, mirroring the fixed positions in web/index.html. */
const GEO = {
  pad: 12,
  topHeight: 34,
  panelRadius: 10,
  prop: { top: 62, width: 316 },
  legend: { width: 300 },
};

/** Collects the text of an element and everything under it. */
function deepText(node) {
  if (!node) return '';
  let out = node._text || '';
  for (const child of node.children || []) out += deepText(child);
  return out;
}

/** Resolves a `var(--x)` reference, or passes a literal colour through. */
function colorOf(value, palette) {
  const m = /var\((--[a-z-]+)\)/.exec(value || '');
  const key = m ? m[1] : null;
  const rgb = key ? palette[key] : null;
  if (rgb) return hex(rgb);
  if (value && value.startsWith('#')) return value;
  return hex(palette['--ink']);
}

function hex(rgb) {
  return '#' + rgb.slice(0, 3)
    .map((c) => Math.round(Math.max(0, Math.min(1, c)) * 255).toString(16).padStart(2, '0'))
    .join('');
}

/** Greedy wrap at an estimated advance width; sans-serif runs about 0.52em per character. */
function wrap(text, size, maxPx) {
  const perChar = size * 0.52;
  const limit = Math.max(8, Math.floor(maxPx / perChar));
  if (text.length <= limit) return [text];
  const lines = [];
  let line = '';
  for (const word of text.split(/\s+/)) {
    if (!line.length) {
      line = word;
    } else if ((line + ' ' + word).length <= limit) {
      line += ' ' + word;
    } else {
      lines.push(line);
      line = word;
    }
    while (line.length > limit) {          // a single unbreakable token
      lines.push(line.slice(0, limit - 1) + '-');
      line = line.slice(limit - 1);
    }
  }
  if (line.length) lines.push(line);
  return lines.slice(0, 3);
}

/**
 * Builds the draw list for one frame.
 *
 * @returns {Array} primitives: panel | text | glyph | rule
 */
export function chromeOps({ app, el, width, height, palette }) {
  const ops = [];
  const ink = hex(palette['--ink']);
  const ink2 = hex(palette['--ink-2']);
  const muted = hex(palette['--ink-muted']);

  const panel = (x, y, w, h) => ops.push({ type: 'panel', x, y, w, h });
  const text = (x, y, size, color, value, opts = {}) =>
    ops.push({ type: 'text', x, y, size, color, text: value, ...opts });

  // ------------------------------------------------------------------ top bar
  {
    const y = GEO.pad;
    panel(GEO.pad, y, width - GEO.pad * 2, GEO.topHeight);
    let x = GEO.pad + 11;
    text(x, y + 10, 13, ink, 'codemap', { weight: 'Bold' });
    x += 68;
    // breadcrumb: project / module / package, as the app built it
    // drop whole leading segments when it is too long, rather than cutting a name in half
    const crumbs = (el('crumbs').children || []).map((c) => deepText(c)).filter(Boolean);
    let trail = crumbs.join(' ');
    while (trail.length > 74 && crumbs.length > 3) {
      crumbs.splice(0, 2);                       // a label and the separator after it
      trail = '… ' + crumbs.join(' ');
    }
    if (trail) text(x, y + 10, 12, ink2, trail);
    // #stats is set through innerHTML, so its text still carries the <b> markup
    const stats = deepText(el('stats')).replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
    if (stats) text(GEO.pad + 11, y + 10, 12, muted, stats, { align: 'right' });
  }

  // ---------------------------------------------------------- proposal panel
  const proposal = app.state.proposal;
  if (app.overlayActive() && proposal) {
    const x = GEO.pad;
    const w = GEO.prop.width;
    const inner = w - 26;
    const lines = [];

    lines.push({ size: 13, color: ink, text: deepText(el('p-title')), weight: 'Bold' });
    lines.push({ size: 11, color: muted, text: deepText(el('p-sub')) });
    lines.push({ rule: true });

    // the status key: swatch, texture and the word, one entry per status in play
    const key = (el('p-key').children || []);
    if (key.length) lines.push({ keyRow: key.map((item) => keyEntry(item, palette)) });

    for (const row of el('p-list').children || []) {
      const [mark, what] = row.children;
      const symbol = deepText(mark);
      const style = (mark.className || '').split(' ').find((c) => c.startsWith('op-'));
      const color = colorOf(`var(${statusVar(style)})`, palette);
      const body = what.children || [];
      const headline = deepText(body[0]).replace(/\s+/g, ' ').trim();
      const note = body[1] ? deepText(body[1]).replace(/\s+/g, ' ').trim() : '';
      lines.push({ size: 12, color: ink, text: headline, bullet: symbol, bulletColor: color });
      for (const part of note ? wrap(note, 11, inner - 16) : []) {
        lines.push({ size: 11, color: muted, text: part, indent: 16 });
      }
    }

    // measure, then draw the box behind the text
    let h = 11;
    for (const line of lines) h += line.rule ? 11 : (line.keyRow ? 20 : line.size + 6);
    h += 11;
    panel(x, GEO.prop.top, w, h);

    let y = GEO.prop.top + 11;
    for (const line of lines) {
      if (line.rule) {
        ops.push({ type: 'rule', x: x + 13, y: y + 4, w: inner });
        y += 11;
        continue;
      }
      if (line.keyRow) {
        let kx = x + 13;
        for (const entry of line.keyRow) {
          ops.push({ type: 'glyph', x: kx + 6, y: y + 7, ...entry.glyph });
          text(kx + 14, y, 11, ink2, entry.label);
          kx += 19 + entry.label.length * 6.1;
        }
        y += 20;
        continue;
      }
      const tx = x + 13 + (line.indent || 0);
      if (line.bullet) {
        text(x + 13, y, 12, line.bulletColor, line.bullet, { weight: 'Bold' });
        text(tx + 11, y, line.size, line.color, line.text);
      } else {
        text(tx, y, line.size, line.color, line.text, { weight: line.weight });
      }
      y += line.size + 6;
    }
  }

  // ----------------------------------------------------------------- legend
  {
    const kinds = (el('legend-kinds').children || [])
      .map((item) => ({ label: deepText(item), glyph: kindGlyph(item, palette) }));
    const edges = (el('legend-edges').children || [])
      .map((item) => ({ label: deepText(item), color: lineColor(item, palette) }));
    const hint = deepText(el('legend-hint'));

    const rows = [];
    rows.push({ heading: 'NODE' });
    for (const k of kinds) rows.push(k);
    rows.push({ heading: 'EDGE' });
    for (const e of edges) rows.push(e);
    if (hint) {
      rows.push({ rule: true });
      for (const part of wrap(hint, 11, GEO.legend.width - 26)) {
        rows.push({ label: part, size: 11, color: muted, plain: true });
      }
    }

    let h = 10;
    for (const row of rows) h += row.heading ? 17 : (row.rule ? 9 : 17);
    h += 10;
    const y0 = height - GEO.pad - h;
    panel(GEO.pad, y0, GEO.legend.width, h);

    let y = y0 + 10;
    for (const row of rows) {
      if (row.heading) {
        text(GEO.pad + 13, y + 2, 10, muted, row.heading, { weight: 'Bold' });
        y += 17;
      } else if (row.rule) {
        ops.push({ type: 'rule', x: GEO.pad + 13, y: y + 3, w: GEO.legend.width - 26 });
        y += 9;
      } else if (row.plain) {
        text(GEO.pad + 13, y, row.size, row.color, row.label);
        y += 17;
      } else {
        if (row.glyph) {
          ops.push({ type: 'glyph', x: GEO.pad + 19, y: y + 7, ...row.glyph });
        } else if (row.color) {
          ops.push({ type: 'rule', x: GEO.pad + 13, y: y + 7, w: 14, color: row.color, thick: 2 });
        }
        text(GEO.pad + 30, y, 11, ink2, row.label);
        y += 17;
      }
    }
  }

  return ops;
}

function statusVar(opClass) {
  return {
    'op-add': '--prop-add',
    'op-modify': '--prop-change',
    'op-delete': '--prop-del',
    'op-mark': '--ink-muted',
  }[opClass] || '--ink';
}

/** The proposal key's swatch: colour plus the ring texture that carries the status. */
function keyEntry(item, palette) {
  const label = deepText(item);
  const svg = (item.children || []).find((c) => c.tagName === 'SVG');
  const circles = svg ? (svg.children || []) : [];
  const first = circles[0];
  return {
    label,
    glyph: {
      shape: 'ring',
      color: colorOf(first && first.getAttribute('stroke'), palette),
      dashed: !!(first && first.getAttribute('stroke-dasharray')),
      double: circles.length > 1,
    },
  };
}

/** The legend's node glyph, read back off the SVG the app built. */
function kindGlyph(item, palette) {
  const svg = (item.children || []).find((c) => c.tagName === 'SVG');
  const shape = svg && svg.children && svg.children[0];
  if (!shape) return null;
  const stroke = shape.getAttribute('stroke');
  const dashed = !!shape.getAttribute('stroke-dasharray');
  const kind = shape.tagName === 'RECT' ? 'square'
    : (shape.tagName === 'PATH' ? 'diamond' : (stroke ? 'ring' : 'circle'));
  return {
    shape: kind,
    color: colorOf(stroke || shape.getAttribute('fill'), palette),
    dashed,
  };
}

function lineColor(item, palette) {
  const svg = (item.children || []).find((c) => c.tagName === 'SVG');
  const line = svg && svg.children && svg.children[0];
  return colorOf(line && line.getAttribute('stroke'), palette);
}

/**
 * Turns the draw list into ImageMagick arguments. Panels first so text lands on top of
 * them, exactly as the stylesheet's z-index puts chrome above the canvas and its labels.
 */
export function magickArgs(ops, { font, palette, width }) {
  const args = [];
  const panelFill = hex(palette['--panel']);
  const hairline = hex(palette['--ink-muted']);

  for (const op of ops.filter((o) => o.type === 'panel')) {
    args.push('-fill', panelFill, '-stroke', hairline, '-strokewidth', '1',
      '-draw', `roundrectangle ${op.x},${op.y} ${op.x + op.w},${op.y + op.h} `
        + `${GEO.panelRadius},${GEO.panelRadius}`);
  }
  args.push('-stroke', 'none');

  for (const op of ops) {
    if (op.type === 'rule') {
      args.push('-stroke', op.color || hairline, '-strokewidth', String(op.thick || 1),
        '-draw', `line ${op.x},${op.y} ${op.x + op.w},${op.y}`, '-stroke', 'none');
    } else if (op.type === 'glyph') {
      args.push(...glyphArgs(op));
    } else if (op.type === 'text' && op.text) {
      args.push('-font', op.weight === 'Bold' ? boldOf(font) : font,
        '-pointsize', String(op.size), '-fill', op.color,
        '-gravity', op.align === 'right' ? 'NorthEast' : 'NorthWest',
        '-annotate', `+${Math.round(op.x)}+${Math.round(op.y)}`, op.text);
    }
  }
  args.push('-gravity', 'NorthWest');
  return args;
}

/**
 * A legend or key glyph. Everything goes through a single MVG string per shape, because
 * `stroke-dasharray` is an MVG property rather than a CLI option - passing it as its own
 * `-draw` is silently useless, and the dashes are what distinguish a deletion from a
 * change without relying on hue.
 */
function glyphArgs(op) {
  const { x, y, color, shape } = op;
  // the same geometry glyphSvg() uses in the app: radius 4 in an 11x11 box, ring stroke 2,
  // dashes 2.4 on 2 off. Anything smaller turns the dashed ring - which is what tells a
  // deletion from a change without relying on hue - into an unreadable smudge.
  const R = 4;
  const dash = op.dashed ? 'stroke-dasharray 2.4 2 ' : '';
  const out = [];
  if (shape === 'square') {
    out.push('-fill', color, '-stroke', 'none',
      '-draw', `roundrectangle ${x - R},${y - R} ${x + R},${y + R} 1.5,1.5`);
  } else if (shape === 'diamond') {
    out.push('-fill', color, '-stroke', 'none', '-draw',
      `polygon ${x},${y - R - 1} ${x + R + 1},${y} ${x},${y + R + 1} ${x - R - 1},${y}`);
  } else if (shape === 'ring') {
    out.push('-fill', 'none', '-stroke', color, '-strokewidth', '1.8',
      '-draw', `${dash}circle ${x},${y} ${x},${y - (op.double ? R - 1.6 : R)}`);
    if (op.double) out.push('-draw', `circle ${x},${y} ${x},${y - R - 1.4}`);
  } else {
    out.push('-fill', color, '-stroke', 'none',
      '-draw', `circle ${x},${y} ${x},${y - R}`);
  }
  out.push('-stroke', 'none', '-strokewidth', '1');
  return out;
}

/**
 * The bold face of a font family, when one is installed beside the regular.
 *
 * Families ship weights as separate files and not every install has all of them - naming a
 * file that is not there makes ImageMagick fail the whole command, so this checks before
 * returning it and falls back to the regular face.
 */
function boldOf(font) {
  const bold = font.replace(/-Regular\.(ttf|otf)$/, '-Bold.$1');
  return bold !== font && fsExists(bold) ? bold : font;
}

/** The first of these that exists; without one there is nothing to draw text with. */
export const FONT = [
  '/usr/share/fonts/Adwaita/AdwaitaSans-Regular.ttf',
  '/usr/share/fonts/noto/NotoSans-Regular.ttf',
  '/usr/share/fonts/gsfonts/NimbusSans-Regular.otf',
].find((f) => fsExists(f));

function fsExists(f) {
  try {
    return statSync(f).isFile();
  } catch {
    return false;
  }
}

/**
 * The map's labels, as the app placed them. They live in the DOM rather than in the GL
 * buffers, so the rasteriser cannot draw them - but a nameless map badly undersells the
 * tool, since reading the names is half of what a code map is for.
 */
function labelOps(el, { width, height, palette }) {
  const STYLE = {
    district: { size: 13, role: '--ink-2', upper: true },
    block: { size: 12, role: '--ink-2', upper: false },
    type: { size: 12, role: '--ink', upper: false },
    port: { size: 12, role: '--ink', upper: false },
    prop: { size: 13, role: '--prop-add', upper: false },
  };
  const out = [];
  for (const node of el('labels').children || []) {
    if (node.style.display === 'none') continue;
    const m = /translate\((-?[\d.]+)px, (-?[\d.]+)px\)$/.exec(node.style.transform || '');
    if (!m) continue;
    const cls = ['prop', 'port', 'district', 'block', 'type']
      .find((c) => (node.className || '').includes(c)) || 'type';
    const style = STYLE[cls];
    // the font has no U+21E2, and a tofu box in the middle of the map looks like a bug
    let value = (node.textContent || '').replace(/⇢/g, '->');
    if (style.upper) value = value.toUpperCase();
    if (!value) continue;
    out.push({
      x: +m[1] - width / 2, y: +m[2] - height / 2,
      size: style.size, color: hex(palette[style.role] || palette['--ink']), text: value,
    });
  }
  return out;
}

/**
 * Draws labels and then chrome onto an already-rasterised frame, in place.
 *
 * Order matters and matches the stylesheet: labels sit over the canvas, and the panels sit
 * over the labels, so a label drifting under the legend is hidden here exactly as it would
 * be in a browser.
 */
export function decorate(file, { app, el, width, height }) {
  if (!FONT) return false;
  const palette = app.palette();
  const args = [file];

  for (const label of labelOps(el, { width, height, palette })) {
    args.push('-font', FONT, '-pointsize', String(label.size), '-fill', label.color,
      '-gravity', 'center',
      '-annotate', `+${Math.round(label.x)}+${Math.round(label.y)}`, label.text);
  }

  const ops = chromeOps({ app, el, width, height, palette });
  args.push(...magickArgs(ops, { font: FONT, palette, width }));
  args.push(file);
  execFileSync('magick', args);
  return true;
}
