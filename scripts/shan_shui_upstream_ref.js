#!/usr/bin/env node
'use strict';
/*
 * scripts/shan_shui_upstream_ref.js
 * ---------------------------------------------------------------------------
 * Render the UPSTREAM shan-shui-inf (Lingdong Huang, MIT) generator into
 * structured JSON so that the E50 「千里江山」 port can be diffed line by line
 * against the original JavaScript.
 *
 * Rules honoured here:
 *   - The upstream implementation is NEVER hand-copied.  Every function we need
 *     (Noise / PolyTools / poly / stroke / texture / Tree / Mount / Arch /
 *     water / mountplanner / chunkloader) is EXTRACTED TEXTUALLY from
 *     `shan-shui-inf-main/index.html` and evaluated in a node `vm` context.
 *   - The extracted source is re-assembled with blank padding so that the
 *     upstream file's line numbers are preserved exactly.  That lets us recover
 *     the *call site* of every emitted SVG shape straight from the JS stack
 *     trace -> every shape in the JSON carries an upstream provenance label.
 *   - `Math.random` is the upstream `Prng` (see block `id="PRNG"`), seeded with
 *     a FIXED seed so the output is reproducible.  The seed is printed and
 *     stored in the JSON.
 *   - The DOM is stubbed minimally (only `document.addEventListener` is touched
 *     at load time by the upstream block we evaluate).
 *
 * Output: output/shan_shui_upstream_ref.json
 *
 * Usage:
 *   node scripts/shan_shui_upstream_ref.js [--seed=<s>] [--out=<file>]
 */

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const REPO_ROOT = path.resolve(__dirname, '..');

// --------------------------------------------------------------------------
// CLI
// --------------------------------------------------------------------------
function parseArgs(argv) {
  const out = {};
  for (const a of argv) {
    const m = /^--([^=]+)(?:=(.*))?$/.exec(a);
    if (m) out[m[1]] = m[2] === undefined ? true : m[2];
  }
  return out;
}
const CLI = parseArgs(process.argv.slice(2));

const UPSTREAM = path.resolve(
  CLI.upstream ||
    process.env.SHANSHUI_UPSTREAM ||
    'D:/hxzhang/MyGithubSoftware/NasAudio/shan-shui-inf-main/index.html',
);
const SEED = CLI.seed || process.env.SHANSHUI_SEED || 'e50ref20261105';
const OUT_JSON = path.resolve(
  CLI.out ||
    process.env.SHANSHUI_OUT ||
    path.join(REPO_ROOT, 'output', 'shan_shui_upstream_ref.json'),
);

// Upstream constants (index.html :3995-3998, :3816-3827)
const ZOOM = 1.142;
const WINDX = 3000;
const WINDY = 800;
const CWID = 512;

// --------------------------------------------------------------------------
// 1. Extract the <script> blocks, keeping their original line numbers
// --------------------------------------------------------------------------
function splitScripts(html) {
  const lines = html.split(/\r?\n/);
  const blocks = [];
  for (let i = 0; i < lines.length; i++) {
    const m = /^\s*<script([^>]*)>\s*$/.exec(lines[i]);
    if (!m) continue;
    let j = i + 1;
    while (j < lines.length && !/^\s*<\/script>\s*$/.test(lines[j])) j++;
    blocks.push({
      index: blocks.length,
      attr: (m[1] || '').trim(),
      openLine: i + 1, // 1-based line of the <script ...> tag
      bodyFirst: i + 2, // 1-based line of the first body line
      bodyLast: j, // 1-based line of the last body line
      body: lines.slice(i + 1, j),
    });
    i = j;
  }
  return { lines, blocks };
}

const { lines: SRC_LINES, blocks: BLOCKS } = splitScripts(
  fs.readFileSync(UPSTREAM, 'utf8'),
);

// Blocks we need:
//   0  <script id="PRNG">      -> Prng + Math.random override
//   2  <script id="PerlinNoise">-> Noise
//   3  <script id="PolyTools">  -> PolyTools
//   4  <script id="Util">       -> util helpers + poly
//   5  <script>                 -> stroke/blob/texture/Tree/Mount/Arch/water/
//                                  mountplanner/MEM/chunkloader
// Block 1 (<script> with parseArgs/SEED) is deliberately SKIPPED: it seeds the
// PRNG with a wall-clock timestamp. We seed it ourselves with a fixed seed.
const WANTED = new Set([0, 2, 3, 4, 5]);

// Re-assemble a source file with identical line numbering: wanted blocks keep
// their body lines, everything else becomes blank.
const PADDED = new Array(SRC_LINES.length).fill('');
for (const b of BLOCKS) {
  if (!WANTED.has(b.index)) continue;
  for (let k = 0; k < b.body.length; k++) {
    PADDED[b.bodyFirst - 1 + k] = b.body[k];
  }
}
const PADDED_SRC = PADDED.join('\n');

// --------------------------------------------------------------------------
// 2. Provenance helpers (pure JS, computed from the upstream source text)
// --------------------------------------------------------------------------
const DEF_RE = [
  /^\s*this\.([A-Za-z_$][\w$]*)\s*=\s*function\b/, // this.foo = function
  /^\s*(?:var|let|const)\s+([A-Za-z_$][\w$]*)\s*=\s*function\b/, // var foo = function
  /^\s*function\s+([A-Za-z_$][\w$]*)\s*\(/, // function foo(
];
// --- brace depth tracking so we can tell nested helper functions apart -----
const DEPTH = [];
{
  let d = 0;
  for (let i = 0; i < SRC_LINES.length; i++) {
    DEPTH.push(d);
    for (const ch of SRC_LINES[i]) {
      if (ch === '{') d++;
      else if (ch === '}') d--;
    }
  }
}
const DEFS = [];
let curObj = null;
for (let i = 0; i < SRC_LINES.length; i++) {
  const ln = SRC_LINES[i];
  const objM = /^\s*var\s+([A-Za-z_$][\w$]*)\s*=\s*new function\s*\(/.exec(ln);
  if (objM) {
    curObj = objM[1];
    continue;
  }
  for (let k = 0; k < DEF_RE.length; k++) {
    const m = DEF_RE[k].exec(ln);
    if (m) {
      const name = k === 0 && curObj ? `${curObj}.${m[1]}` : m[1];
      DEFS.push({ line: i + 1, name, depth: DEPTH[i] });
      break;
    }
  }
}
// end line of every def (first line where the depth is back at def.depth)
for (const def of DEFS) {
  def.end = SRC_LINES.length;
  for (let i = def.line; i < SRC_LINES.length; i++) {
    if (DEPTH[i] <= def.depth) {
      def.end = i + 1;
      break;
    }
  }
}
// the chain of defs covering a given line, outermost first
function defChain(line) {
  const covering = DEFS.filter((d) => d.line <= line && line < d.end);
  covering.sort((a, b) => a.depth - b.depth || b.line - a.line);
  return covering.map((d) => d.name);
}
function enclosingDef(line) {
  const chain = defChain(line);
  return chain.length ? chain[chain.length - 1] : '?';
}
function nearestComment(line) {
  const chain = defChain(line);
  const defLine = chain.length
    ? DEFS.filter(
        (d) => d.line <= line && line < d.end,
      ).reduce((a, b) => (a.depth >= b.depth ? a : b)).line
    : 1;
  for (let l = line - 1; l >= Math.max(defLine, line - 80); l--) {
    const m = /^\s*\/\/\s*([A-Za-z][A-Za-z0-9 _\-()]*)/.exec(SRC_LINES[l - 1]);
    if (m) {
      const t = m[1].trim();
      if (/^console/.test(t)) continue;
      return t;
    }
  }
  return null;
}
function labelFor(site) {
  if (!site || site < 0) return 'unknown';
  const chain = defChain(site);
  const fn = chain.length ? chain.join('>') : '?';
  const cm = nearestComment(site);
  return cm ? `${fn}:${site}:${cm}` : `${fn}:${site}`;
}

// --------------------------------------------------------------------------
// 3. Run the upstream code in a vm context
// --------------------------------------------------------------------------
const sandbox = {
  console: {
    log: () => {},
    warn: () => {},
    error: () => {},
    info: () => {},
  },
  // upstream Prng.hash() calls window.btoa(JSON.stringify(x))
  window: {
    btoa: (s) => Buffer.from(String(s), 'latin1').toString('base64'),
    location: { href: 'file:///shan-shui-inf/index.html' },
    addEventListener: () => {},
  },
  // only document.addEventListener() is hit at load time (index.html :3986-3987)
  document: {
    addEventListener: () => {},
    getElementById: () => ({
      setAttribute: () => {},
      appendChild: () => {},
      style: {},
      innerHTML: '',
    }),
  },
  setTimeout: () => 0,
  setInterval: () => 0,
};
const ctx = vm.createContext(sandbox);
ctx.self = ctx;

vm.runInContext(PADDED_SRC, ctx, { filename: UPSTREAM });

// --------------------------------------------------------------------------
// 4. Instrument poly/stroke so every emitted SVG shape is captured together
//    with its upstream call site (recovered from the JS stack trace).
// --------------------------------------------------------------------------
const INSTRUMENT = `
Error.stackTraceLimit = 400;
var __recShapes = [];
var __recChunks = [];
var __recCur = null;
var __origPoly = poly;
var __origStroke = stroke;
var __depth = 0;

function __site() {
  var st = (new Error()).stack || "";
  var re = /index\\.html:(\\d+):/g;
  var m = re.exec(st);
  if (m) return parseInt(m[1], 10);
  return -1;
}

poly = function (plist, args) {
  var s = __origPoly(plist, args);
  if (__depth === 0) {
    var rec = { kind: "poly", site: __site(), svg: s };
    if (__recCur) { __recCur.shapes.push(rec); } else { __recShapes.push(rec); }
  }
  return s;
};

stroke = function (ptlist, args) {
  __depth++;
  var s;
  try { s = __origStroke(ptlist, args); } finally { __depth--; }
  var rec = { kind: "stroke", site: __site(), svg: s };
  if (__recCur) { __recCur.shapes.push(rec); } else { __recShapes.push(rec); }
  return s;
};

function __wrapChunkFn(target, key, kind, tag, yAdjust) {
  var orig = target[key];
  target[key] = function () {
    var a = Array.prototype.slice.call(arguments);
    var rec = {
      src: kind,
      tag: tag,
      x: a[0],
      y: (a[1] === undefined ? 0 : a[1]) + (yAdjust || 0),
      shapes: []
    };
    var ret;
    __recCur = rec;
    try { ret = orig.apply(target, a); } finally { __recCur = null; }
    __recChunks.push(rec);
    return ret; // upstream expects the SVG string back
  };
}
`;
vm.runInContext(INSTRUMENT, ctx, { filename: 'instrument.js' });

// Wrap the chunk producers so we know which shapes belong to which chunk.
// NOTE: Mount.rock is NOT wrapped - upstream calls it from inside
// Mount.mountain (index.html :2062) and Mount.flatDec (:2240/2265/2290/2307),
// so its shapes belong to the enclosing mountain / flatmount chunk.
vm.runInContext(
  [
    '__wrapChunkFn(Mount, "mountain",  "Mount.mountain",  "mount",     0);',
    '__wrapChunkFn(Mount, "flatMount","Mount.flatMount", "flatmount", 0);',
    '__wrapChunkFn(Mount, "distMount","Mount.distMount", "distmount", 0);',
    '__wrapChunkFn(Arch,  "boat01",   "Arch.boat01",     "boat",      0);',
    '__wrapChunkFn(this,  "water",    "water",           "water",     0);',
  ].join('\n'),
  ctx,
  { filename: 'instrument.js' },
);

// --------------------------------------------------------------------------
// 5. Seed the PRNG (upstream's own Prng) with a FIXED seed and generate
// --------------------------------------------------------------------------
ctx.SEED = SEED;
vm.runInContext('Math.seed(SEED);', ctx, { filename: 'instrument.js' });
const PRNG_STATE = ctx.Prng ? ctx.Prng.s : null;

// upstream: chunkloader(MEM.cursx, MEM.cursx + MEM.windx)  (index.html :4020)
vm.runInContext(
  `chunkloader(${0}, ${0 + WINDX});`,
  ctx,
  { filename: 'instrument.js' },
);

const recChunks = vm.runInContext('__recChunks', ctx);
const memChunks = vm.runInContext('MEM.chunks', ctx);

// The water chunk is stored by upstream with y = plan.y - 10000
// (index.html :3897-3902) and with tag "mount".  Fix that up so the chunk list
// matches upstream's MEM.chunks exactly.
for (const r of recChunks) {
  if (r.src === 'water') {
    r.tag = 'mount';
    r.y = r.y - 10000;
  }
}

// --------------------------------------------------------------------------
// 6. Reproduce upstream's chunk ordering (MEM.chunks is kept sorted by y)
// --------------------------------------------------------------------------
function sortLikeUpstream(list) {
  const out = [];
  for (const nch of list) {
    if (out.length === 0) {
      out.push(nch);
      continue;
    }
    if (nch.y <= out[0].y) {
      out.unshift(nch);
      continue;
    }
    if (nch.y >= out[out.length - 1].y) {
      out.push(nch);
      continue;
    }
    let placed = false;
    for (let j = 0; j < out.length - 1; j++) {
      if (out[j].y <= nch.y && nch.y <= out[j + 1].y) {
        out.splice(j + 1, 0, nch);
        placed = true;
        break;
      }
    }
    if (!placed) out.push(nch);
  }
  return out;
}
const ordered = sortLikeUpstream(recChunks.slice());

// cross-check against upstream's own MEM.chunks
const key = (c) => `${c.tag}|${c.x}|${c.y}`;
const upstreamKeys = memChunks.map(key);
const mineKeys = ordered.map(key);
const orderMatches =
  upstreamKeys.length === mineKeys.length &&
  upstreamKeys.every((k, i) => k === mineKeys[i]);

// --------------------------------------------------------------------------
// 7. Parse the upstream SVG fragments into structured fields
// --------------------------------------------------------------------------
const SVG_RE =
  /^<polyline points='([^']*)' style='fill:([^;]*);stroke:([^;]*);stroke-width:([^']*)'\/>$/;

function parseSvg(svg) {
  const m = SVG_RE.exec(svg);
  if (!m) return null;
  const raw = m[1].trim();
  const points = raw.length
    ? raw.split(/\s+/).map((p) => {
        const xy = p.split(',');
        return [Number(xy[0]), Number(xy[1])];
      })
    : [];
  return {
    points,
    fill: m[2],
    stroke: m[3],
    strokeWidth: Number(m[4]),
  };
}

function colorInfo(c) {
  if (c === undefined || c === null) return null;
  if (c === 'none') return { css: c, kind: 'none' };
  if (c === 'white') return { css: c, kind: 'rgb', r: 255, g: 255, b: 255, a: 1 };
  if (c === 'black') return { css: c, kind: 'rgb', r: 0, g: 0, b: 0, a: 1 };
  let m = /^rgba?\(([^)]*)\)$/.exec(c);
  if (m) {
    const p = m[1].split(',').map((s) => parseFloat(s.trim()));
    return {
      css: c,
      kind: 'rgb',
      r: p[0],
      g: p[1],
      b: p[2],
      a: p.length > 3 ? p[3] : 1,
    };
  }
  return { css: c, kind: 'unknown' };
}

const KIND_OF = {
  'Mount.mountain': 'mountain',
  'Mount.flatMount': 'flatmount',
  'Mount.distMount': 'distmount',
  'Arch.boat01': 'boat',
  water: 'water',
};

const chunks = ordered.map((c) => {
  const shapes = [];
  let nanCount = 0;
  for (const s of c.shapes) {
    const parsed = parseSvg(s.svg);
    if (!parsed) continue;
    let pts = parsed.points;
    // upstream chunkloader replaces every literal "NaN" by -1000
    // (index.html :3847-3851) before the chunk is drawn.
    pts = pts.map((p) =>
      p.map((v) => {
        if (Number.isNaN(v)) {
          nanCount++;
          return -1000;
        }
        return v;
      }),
    );
    shapes.push({
      kind: s.kind,
      site: s.site,
      label: labelFor(s.site),
      fill: parsed.fill,
      stroke: parsed.stroke,
      strokeWidth: parsed.strokeWidth,
      points: pts,
    });
  }
  return {
    tag: c.tag,
    kind: KIND_OF[c.src] || c.src,
    src: c.src,
    x: c.x,
    y: c.y,
    nShapes: shapes.length,
    nanReplaced: nanCount,
    shapes,
  };
});

// --------------------------------------------------------------------------
// 8. Statistics that the reader needs (printed + stored in the JSON)
// --------------------------------------------------------------------------
function bbox(shapes) {
  let x0 = Infinity,
    y0 = Infinity,
    x1 = -Infinity,
    y1 = -Infinity;
  for (const s of shapes) {
    for (const p of s.points) {
      if (p[0] < x0) x0 = p[0];
      if (p[0] > x1) x1 = p[0];
      if (p[1] < y0) y0 = p[1];
      if (p[1] > y1) y1 = p[1];
    }
  }
  if (!isFinite(x0)) return null;
  return { x0, y0, x1, y1, w: x1 - x0, h: y1 - y0 };
}

const byKind = {};
for (const c of chunks) {
  if (!byKind[c.tag]) byKind[c.tag] = [];
  byKind[c.tag].push(c);
}
const tagStats = {};
for (const [tag, list] of Object.entries(byKind)) {
  const ys = list.map((c) => c.y);
  const bb = bbox(list.flatMap((c) => c.shapes));
  tagStats[tag] = {
    count: list.length,
    chunkYMin: Math.min(...ys),
    chunkYMax: Math.max(...ys),
    chunkXMin: Math.min(...list.map((c) => c.x)),
    chunkXMax: Math.max(...list.map((c) => c.x)),
    shapeBBox: bb,
    shapes: list.reduce((a, c) => a + c.nShapes, 0),
    byKind: list.reduce((acc, c) => {
      acc[c.kind] = (acc[c.kind] || 0) + 1;
      return acc;
    }, {}),
  };
}

// water cluster statistics (the 9 emitted bands per water chunk)
const waterStats = [];
for (const c of chunks.filter((c) => c.kind === 'water')) {
  const bands = c.shapes.filter((s) => s.kind === 'stroke');
  const lens = bands.map((b) => {
    const bbx = bbox([b]);
    return bbx ? bbx.w : 0;
  });
  const ys = bands.map((b) => bbox([b]).y0);
  const ys1 = bands.map((b) => bbox([b]).y1);
  waterStats.push({
    x: c.x,
    y: c.y,
    bands: bands.length,
    bandWidths: lens,
    bandYMin: Math.min(...ys),
    bandYMax: Math.max(...ys1),
    verticalSpan: Math.max(...ys1) - Math.min(...ys),
  });
}

// per upstream-call-site statistics (this is what makes the diff readable)
const labelStats = {};
for (const c of chunks) {
  for (const s of c.shapes) {
    const key = s.label;
    if (!labelStats[key]) {
      labelStats[key] = { label: key, site: s.site, n: 0, fills: {}, strokes: {} };
    }
    const st = labelStats[key];
    st.n++;
    st.fills[s.fill] = (st.fills[s.fill] || 0) + 1;
    st.strokes[s.stroke] = (st.strokes[s.stroke] || 0) + 1;
    const bb = bbox([s]);
    if (bb) {
      if (!st.bbox) st.bbox = { x0: bb.x0, y0: bb.y0, x1: bb.x1, y1: bb.y1 };
      else {
        st.bbox.x0 = Math.min(st.bbox.x0, bb.x0);
        st.bbox.y0 = Math.min(st.bbox.y0, bb.y0);
        st.bbox.x1 = Math.max(st.bbox.x1, bb.x1);
        st.bbox.y1 = Math.max(st.bbox.y1, bb.y1);
      }
      st.maxW = Math.max(st.maxW || 0, bb.w);
      st.maxH = Math.max(st.maxH || 0, bb.h);
    }
  }
}
// collapse the per-label colour histograms (they are huge and useless at full
// resolution) into "distinct colours" + a handful of examples
for (const st of Object.values(labelStats)) {
  const collapse = (m) => {
    const entries = Object.entries(m).sort((a, b) => b[1] - a[1]);
    return { distinct: entries.length, top: entries.slice(0, 4) };
  };
  st.fills = collapse(st.fills);
  st.strokes = collapse(st.strokes);
}

// --------------------------------------------------------------------------
// 8b. Targeted analysis for the four questions (A dist-mount gaps,
//     B water clusters, C mountain foot, D half discs)
// --------------------------------------------------------------------------
function shapeBBoxOf(chunk) {
  return bbox(chunk.shapes);
}

const distAnalysis = chunks
  .filter((c) => c.kind === 'distmount')
  .map((c) => {
    const b = shapeBBoxOf(c);
    return {
      chunkX: c.x,
      chunkY: c.y,
      bandX0: b ? b.x0 : null,
      bandX1: b ? b.x1 : null,
      bandWidth: b ? b.w : null,
      bandY0: b ? b.y0 : null,
      bandY1: b ? b.y1 : null,
      nSegmentPolys: c.shapes.filter((s) => s.site === 2413).length,
      nTriangles: c.shapes.filter((s) => s.site === 2427).length,
    };
  });
// gaps between consecutive dist-mount bands (sorted by bandX0)
const distSorted = distAnalysis.slice().sort((a, b) => a.bandX0 - b.bandX0);
const distGaps = [];
for (let i = 0; i < distSorted.length - 1; i++) {
  distGaps.push({
    from: distSorted[i].bandX0,
    to: distSorted[i].bandX1,
    nextFrom: distSorted[i + 1].bandX0,
    gap: distSorted[i + 1].bandX0 - distSorted[i].bandX1,
  });
}

const rockShapes = [];
for (const c of chunks) {
  for (const s of c.shapes) {
    if (s.site === 2485) {
      const b = bbox([s]);
      rockShapes.push({
        owner: c.kind,
        chunkX: c.x,
        chunkY: c.y,
        w: b.w,
        h: b.h,
        x0: b.x0,
        y0: b.y0,
        x1: b.x1,
        y1: b.y1,
        // a "half disc" would be flat along the bottom: check the vertical
        // spread of the bottom-most points vs. the top-most ones
        topY: Math.min(...s.points.map((p) => p[1])),
        botY: Math.max(...s.points.map((p) => p[1])),
        flatBottom: (() => {
          const bots = s.points.filter((p) => p[1] > Math.max(...s.points.map((q) => q[1])) - 1);
          const xs = bots.map((p) => p[0]);
          return {
            bottomPoints: bots.length,
            bottomXSpan: xs.length ? Math.max(...xs) - Math.min(...xs) : 0,
          };
        })(),
      });
    }
  }
}

const footShapes = [];
for (const c of chunks) {
  for (const s of c.shapes) {
    if (s.site === 1780 || s.site === 1788) {
      const b = bbox([s]);
      footShapes.push({
        site: s.site,
        kind: c.kind,
        chunkX: c.x,
        chunkY: c.y,
        fill: s.fill,
        stroke: s.stroke,
        strokeWidth: s.strokeWidth,
        w: b.w,
        h: b.h,
      });
    }
  }
}

const analysis = {
  A_distmount: {
    chunks: distAnalysis,
    gaps: distGaps,
    note: 'upstream mountplanner puts one dist-mount every 1000 world units; '
      + 'len is randChoice([500,1000,1500]) so bands shorter than 1000 leave a gap',
  },
  B_water: {
    chunks: waterStats,
    totalBands: waterStats.reduce((a, w) => a + w.bands, 0),
    note: 'upstream water() builds clu=10 clusters but the draw loop starts at j=1 '
      + '=> 9 bands per chunk; yk accumulates rnd*5 per cluster',
  },
  C_mountainFoot: {
    count: footShapes.length,
    whiteFill: footShapes.filter((f) => f.site === 1780).length,
    faintStroke: footShapes.filter((f) => f.site === 1788).length,
    sample: footShapes.slice(0, 12),
  },
  D_halfDisc: {
    count: rockShapes.length,
    shapes: rockShapes,
    note: 'Mount.rock WHITE BG (index.html :2485) - fill is pure "white"',
  },
};

const meta = {
  upstream: UPSTREAM,
  upstreamBytes: fs.statSync(UPSTREAM).size,
  seed: SEED,
  prng: 'upstream Prng (index.html script id="PRNG"), Math.random = Prng.next',
  prngStateAfterSeed: PRNG_STATE,
  zoom: ZOOM,
  viewBox: `0 0 ${(WINDX / ZOOM).toFixed(2)} ${(WINDY / ZOOM).toFixed(2)}`,
  worldVisible: {
    x0: 0,
    x1: WINDX / ZOOM,
    y0: 0,
    y1: WINDY / ZOOM,
  },
  screenWindow: { xmin: 0, xmax: WINDX, ymin: 0, ymax: WINDY },
  generatedAt: new Date().toISOString(),
  orderMatchesUpstreamMemChunks: orderMatches,
  memChunkCount: memChunks.length,
  recordedChunkCount: recChunks.length,
  totalShapes: chunks.reduce((a, c) => a + c.shapes.length, 0),
  totalPoints: chunks.reduce(
    (a, c) => a + c.shapes.reduce((b, s) => b + s.points.length, 0),
    0,
  ),
  tagStats,
  labelStats,
  waterStats,
  analysis,
};

fs.mkdirSync(path.dirname(OUT_JSON), { recursive: true });
fs.writeFileSync(
  OUT_JSON,
  JSON.stringify({ meta, chunks }, null, 1),
  'utf8',
);

// --------------------------------------------------------------------------
// 9. Console report
// --------------------------------------------------------------------------
console.log('=== shan-shui-inf upstream reference ===');
console.log('upstream      :', UPSTREAM);
console.log('seed          :', SEED, ' (Prng.s =', PRNG_STATE, ')');
console.log('viewBox       :', meta.viewBox);
console.log(
  'world visible : x[0,',
  meta.worldVisible.x1.toFixed(2),
  '] y[0,',
  meta.worldVisible.y1.toFixed(2),
  ']',
);
console.log(
  'chunks        :',
  meta.recordedChunkCount,
  '(upstream MEM.chunks =',
  meta.memChunkCount,
  ', order match =',
  orderMatches,
  ')',
);
console.log('shapes        :', meta.totalShapes, ' points:', meta.totalPoints);
console.log('--- per tag ---');
for (const [tag, st] of Object.entries(tagStats)) {
  console.log(
    `  ${tag.padEnd(10)} n=${String(st.count).padStart(3)}  kinds=${JSON.stringify(
      st.byKind,
    )}  chunkY[${st.chunkYMin.toFixed(1)}, ${st.chunkYMax.toFixed(
      1,
    )}]  chunkX[${st.chunkXMin.toFixed(1)}, ${st.chunkXMax.toFixed(
      1,
    )}]  shapeY[${st.shapeBBox.y0.toFixed(1)}, ${st.shapeBBox.y1.toFixed(
      1,
    )}]  shapeX[${st.shapeBBox.x0.toFixed(1)}, ${st.shapeBBox.x1.toFixed(1)}]`,
  );
}
console.log('--- per upstream call site ---');
const lstats = Object.values(labelStats).sort((a, b) => b.n - a.n);
for (const st of lstats) {
  console.log(
    `  ${st.label.padEnd(32)} n=${String(st.n).padStart(6)}  bbox=[${st.bbox.x0.toFixed(
      0,
    )},${st.bbox.y0.toFixed(0)}]-[${st.bbox.x1.toFixed(0)},${st.bbox.y1.toFixed(
      0,
    )}]  maxW=${st.maxW.toFixed(0)} maxH=${st.maxH.toFixed(0)}  fill#${st.fills.distinct} ${
      st.fills.top[0] ? st.fills.top[0][0] : ''
    }`,
  );
}
console.log('--- A: dist-mount bands (x, width, gaps) ---');
for (const d of analysis.A_distmount.chunks) {
  console.log(
    `  chunkX=${d.chunkX.toFixed(1).padStart(7)}  band=[${d.bandX0.toFixed(
      1,
    )}, ${d.bandX1.toFixed(1)}]  width=${d.bandWidth.toFixed(
      1,
    )}  y=[${d.bandY0.toFixed(1)}, ${d.bandY1.toFixed(1)}]  segPolys=${
      d.nSegmentPolys
    } tris=${d.nTriangles}`,
  );
}
for (const g of analysis.A_distmount.gaps) {
  console.log(
    `  gap: ${g.from.toFixed(1)} -> ${g.nextFrom.toFixed(1)}  (band ends ${g.to.toFixed(
      1,
    )})  width=${g.gap.toFixed(1)}`,
  );
}
console.log('--- B: water ---');
console.log(
  '  chunks =',
  analysis.B_water.chunks.length,
  ' total bands =',
  analysis.B_water.totalBands,
);
for (const w of analysis.B_water.chunks.slice(0, 8)) {
  console.log(
    `  x=${w.x.toFixed(1).padStart(8)}  y=${w.y}  bands=${w.bands}  bandW=[${Math.min(
      ...w.bandWidths,
    ).toFixed(0)}, ${Math.max(...w.bandWidths).toFixed(
      0,
    )}]  bandY=[${w.bandYMin.toFixed(1)}, ${w.bandYMax.toFixed(
      1,
    )}]  verticalSpan=${w.verticalSpan.toFixed(1)}`,
  );
}
console.log('--- C: mountain foot ---');
console.log(
  '  white-fill polys =',
  analysis.C_mountainFoot.whiteFill,
  ' faint-gray strokes =',
  analysis.C_mountainFoot.faintStroke,
);
console.log('--- D: half discs (Mount.rock WHITE BG) ---');
console.log('  count =', analysis.D_halfDisc.count);
for (const r of analysis.D_halfDisc.shapes.slice(0, 8)) {
  console.log(
    `  owner=${r.owner} x=${r.x0.toFixed(0)}..${r.x1.toFixed(
      0,
    )} w=${r.w.toFixed(0)} h=${r.h.toFixed(
      0,
    )} topY=${r.topY.toFixed(0)} botY=${r.botY.toFixed(
      0,
    )} bottomPts=${r.flatBottom.bottomPoints} bottomXSpan=${r.flatBottom.bottomXSpan.toFixed(
      0,
    )}`,
  );
}
console.log('JSON written  :', OUT_JSON);
