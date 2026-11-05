#!/usr/bin/env node
'use strict';
/*
 * scripts/e50_tree45678_upstream_probe.js
 * ---------------------------------------------------------------------------
 * E50「千里江山」**对账探针**：把上游 shan-shui-inf（LingDong Huang, MIT）的
 * `Tree.tree04` / `tree05` / `tree06` / `tree07` / `tree08`（连同它们共用的
 * 递归骨架 `branch`、辅助 `twig` / `barkify`）**原样**跑一遍，输出：
 *
 *   1. 每次 `poly()` 发射的轮廓（点列 / 填充 / 描边 / 线宽）——Float64 原始值；
 *   2. PRNG 抽取次数（`Math.random()` 的实际调用数）；
 *   3. 逐条轮廓的 **FNV-1a 64** 摘要（与 Kotlin 侧 `ShanShuiTree45678GateTest` 同口径）。
 *
 * ⛔ **探针对上游只有两处改动，且都不碰几何**：
 *    ① perlin 表的**初始化**：上游 `Noise.noise` 首次调用时用 `Math.random()` 填 4096 个
 *       表项（值任意）；探针改成读 `__perlinLcg[]`（与 port 的
 *       `ShanShuiNoise.noiseSeed(20250101.0)` **完全相同的 LCG**）⇒ 两侧噪声表逐位相同。
 *    ② 把 `this.treeNN = function(...)` 改成 `this.treeNN = this.__treeNN = function(...)`
 *       （**同行**追加 ⇒ 行数不变 ⇒ 上游行号全部保持）⇒ 只为能**单独**测量每一棵树
 *       （`treeNN` 本来就在 `Tree` 的 IIFE 上，理论上已可从外部访问；这一处只是把
 *        「暴露」这件事**显式化**，并在暴露失败时立刻停手）。
 *    几何算法本身**逐字**来自上游（脚本块按原行号抽取，不重排）。
 *
 * ⚠️ 上游 `poly()` 输出时用 `.toFixed(1)`（1 位小数）；探针捕获的是**进 poly 之前**
 *    的 Float64 点列（加完 xof/yof 偏移之后）⇒ 与 port 的 Float32 轮廓同口径。
 *
 * ## 轮廓顺序 = 上游 SVG 的**拼接序**（不是 poly 的调用序）
 *
 * ⚠️ tree04/05/06/08 的上游实现把轮廓分装在 `txcanv`（树皮）与 `twcanv`（细枝）里，
 *    最后 `canv = poly(主干) + stroke(主干) + txcanv + twcanv` ⇒ **SVG 的元素顺序**
 *    与 `poly()` 的**调用顺序**不同（主干在前、树皮/细枝在后）。
 *    ⇒ 探针在 `poly()` 的返回值里塞一个**唯一标记**（`<序号>`），
 *      再从 treeNN 返回的 SVG 串里按标记出现顺序还原**拼接序**。
 *    （port 侧的轮廓列表也按这个顺序 ⇒ 与上游画面层次一致。）
 *
 * ## 产物
 * - **黄金基线**（默认 `app/src/test/resources/qianli/shan_shui_tree45678_golden.json`）：
 *   门禁测试读它做逐位比对（⛔ 期望值不写在测试源码里）。
 * - **明细 dump**（默认 `output/e50_tree45678_upstream.json`）：逐条轮廓的点列，便于人工核对。
 *
 * 用法：
 *   node scripts/e50_tree45678_upstream_probe.js [--upstream=<file>] [--seed=157]
 *          [--noiseseed=20250101] [--x=0] [--y=0]
 *          [--out=<file>] [--dump=<file>] [--check] [--points]
 *
 *   `--check` = 重新生成并与现有黄金基线逐字比对（漂移 ⇒ 退出码 1）。
 */

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const REPO_ROOT = path.resolve(__dirname, '..');

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
/** PRNG 种子（**数值**，与 port 的 `ShanShuiPrng.seed(Int)` 同口径）。 */
const SEED = Number(CLI.seed !== undefined ? CLI.seed : 157);
/** perlin 表的 LCG 种子（与 port 的 `ShanShuiNoise.noiseSeed(20250101.0)` 同值）。 */
const NOISE_SEED = Number(CLI.noiseseed !== undefined ? CLI.noiseseed : 20250101);

/** 五棵树的调用坐标（上游 `treeNN(x, y, args)`）。 */
const X = Number(CLI.x !== undefined ? CLI.x : 0);
const Y = Number(CLI.y !== undefined ? CLI.y : 0);

const OUT_JSON = path.resolve(
  CLI.out ||
    process.env.SHANSHUI_OUT ||
    path.join(
      REPO_ROOT,
      'app',
      'src',
      'test',
      'resources',
      'qianli',
      'shan_shui_tree45678_golden.json',
    ),
);
const DUMP_JSON = path.resolve(
  CLI.dump || path.join(REPO_ROOT, 'output', 'e50_tree45678_upstream.json'),
);
const CHECK_ONLY = CLI.check === true || CLI.check === 'true';
const EMIT_POINTS = CLI.points === true || CLI.points === 'true';

/** 五棵树（上游的 `this.treeNN`）。 */
const TREES = ['tree04', 'tree05', 'tree06', 'tree07', 'tree08'];

// --------------------------------------------------------------------------
// 1. 抽取上游 <script> 块（保持原始行号）
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
      openLine: i + 1,
      bodyFirst: i + 2,
      bodyLast: j,
      body: lines.slice(i + 1, j),
    });
    i = j;
  }
  return { lines, blocks };
}

const { lines: SRC_LINES, blocks: BLOCKS } = splitScripts(
  fs.readFileSync(UPSTREAM, 'utf8'),
);

// 0 = PRNG, 2 = PerlinNoise, 3 = PolyTools, 4 = Util, 5 = 主块
// （块 1 会用 wall-clock 时间重新 seed PRNG ⇒ 必须跳过）
const WANTED = new Set([0, 2, 3, 4, 5]);
const PADDED = new Array(SRC_LINES.length).fill('');
for (const b of BLOCKS) {
  if (!WANTED.has(b.index)) continue;
  for (let k = 0; k < b.body.length; k++) {
    PADDED[b.bodyFirst - 1 + k] = b.body[k];
  }
}

// --------------------------------------------------------------------------
// 2. 唯一的上游改动①：perlin 表的初始化
// --------------------------------------------------------------------------
const NOISE_INIT_ORIGINAL = [
  '        perlin = new Array(PERLIN_SIZE + 1);',
  '        for (var i = 0; i < PERLIN_SIZE + 1; i++) {',
  '          perlin[i] = Math.random();',
  '        }',
];
const NOISE_INIT_PATCHED = [
  '        perlin = new Array(PERLIN_SIZE + 1);',
  '        for (var i = 0; i < PERLIN_SIZE + 1; i++) {',
  '          perlin[i] = __perlinLcg[i];',
  '        }',
];

function perlinLcg(seed) {
  // 与 port 的 ShanShuiNoise.noiseSeed 完全同款 LCG
  const m = 4294967296;
  const a = 1664525;
  const c = 1013904223;
  const arr = new Float64Array(4096);
  let z = seed >>> 0;
  for (let i = 0; i < 4096; i++) {
    z = (a * z + c) % m;
    arr[i] = z / m;
  }
  return arr;
}

let patched = 0;
for (let i = 0; i < PADDED.length; i++) {
  if (
    PADDED[i] === NOISE_INIT_ORIGINAL[0] &&
    PADDED[i + 1] === NOISE_INIT_ORIGINAL[1] &&
    PADDED[i + 2] === NOISE_INIT_ORIGINAL[2] &&
    PADDED[i + 3] === NOISE_INIT_ORIGINAL[3]
  ) {
    PADDED[i + 2] = NOISE_INIT_PATCHED[2];
    patched++;
  }
}
if (patched !== 1) {
  console.error(
    'FATAL: perlin 表初始化的上游原文没能唯一匹配（命中 ' + patched + ' 处）——' +
      '上游文件可能已变动，探针必须停手。',
  );
  process.exit(2);
}

// --------------------------------------------------------------------------
// 2b. 唯一的上游改动②：把 `treeNN` **显式**暴露（同行追加 ⇒ 行号不变）。
// --------------------------------------------------------------------------
let exposed = 0;
for (const t of TREES) {
  const orig = '    this.' + t + ' = function(x, y, args) {';
  let hits = 0;
  for (let i = 0; i < PADDED.length; i++) {
    if (PADDED[i] === orig) {
      PADDED[i] = '    this.' + t + ' = this.__' + t + ' = function(x, y, args) {';
      hits++;
    }
  }
  if (hits !== 1) {
    console.error(
      'FATAL: `this.' + t + '` 的上游原文没能唯一匹配（命中 ' + hits + ' 处）——' +
        '上游文件可能已变动，探针必须停手。',
    );
    process.exit(2);
  }
  exposed += hits;
}
if (exposed !== TREES.length) {
  console.error('FATAL: 暴露的树数量不对（' + exposed + '）');
  process.exit(2);
}

const PADDED_SRC = PADDED.join('\n');

// --------------------------------------------------------------------------
// 3. 在 vm 沙箱里跑上游
// --------------------------------------------------------------------------
const sandbox = {
  console: { log: () => {}, warn: () => {}, error: () => {}, info: () => {} },
  window: {
    btoa: (s) => Buffer.from(String(s), 'latin1').toString('base64'),
    location: { href: 'file:///shan-shui-inf/index.html' },
    addEventListener: () => {},
  },
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
  __perlinLcg: perlinLcg(NOISE_SEED),
};
const ctx = vm.createContext(sandbox);
ctx.self = ctx;
vm.runInContext(PADDED_SRC, ctx, { filename: UPSTREAM });

// 暴露自检：五棵树必须都在
for (const t of TREES) {
  const ok = vm.runInContext('typeof Tree.__' + t + ' === "function"', ctx);
  if (!ok) {
    console.error('FATAL: Tree.__' + t + ' 没能暴露 —— 探针必须停手。');
    process.exit(2);
  }
}

// --------------------------------------------------------------------------
// 4. 插桩：捕获每一次 poly() 发射 + 统计 Math.random 抽取次数
// --------------------------------------------------------------------------
const MARK = '';
vm.runInContext(
  [
    'var __rec = [];',
    'var __origPoly = poly;',
    // ⚠️ 上游的每一条**可见轮廓**都恰好是一次 poly() 调用
    //   （stroke()/blob() 内部也只发一次 poly）⇒ 记录**全部** poly 调用即得发射序列。
    'poly = function (plist, args) {',
    '  var xof = (args && args.xof !== undefined) ? args.xof : 0;',
    '  var yof = (args && args.yof !== undefined) ? args.yof : 0;',
    '  var fil = (args && args.fil !== undefined) ? args.fil : "rgba(0,0,0,0)";',
    '  var str = (args && args.str !== undefined) ? args.str : fil;',
    '  var wid = (args && args.wid !== undefined) ? args.wid : 0;',
    '  var idx = __rec.length;',
    '  __rec.push({',
    '    fil: fil, str: str, wid: wid,',
    '    pts: plist.map(function (p) { return [p[0] + xof, p[1] + yof]; }),',
    '  });',
    // 返回值里塞唯一标记 ⇒ 事后可从 SVG 串还原**拼接序**（见文件头说明）
    '  return "' + MARK + '" + idx + "' + MARK + '" + __origPoly(plist, args);',
    '};',
    'var __draws = 0;',
    'var __origRandom = Math.random;',
    'Math.random = function () { __draws++; return __origRandom(); };',
  ].join('\n'),
  ctx,
  { filename: 'instrument.js' },
);

// --------------------------------------------------------------------------
// 5. seed + 逐棵调用上游 treeNN
// --------------------------------------------------------------------------
vm.runInContext(
  [
    'function __reset() { __rec.length = 0; __draws = 0; Math.seed(' + SEED + '); }',
    'function __measure(expr) {',
    '  __reset();',
    '  var canv = eval(expr);',
    '  return { canv: canv, shapes: __rec.slice(), draws: __draws };',
    '}',
  ].join('\n'),
  ctx,
  { filename: 'instrument.js' },
);

const results = {};
for (const t of TREES) {
  results[t] = vm.runInContext(
    `__measure("Tree.__${t}(${X}, ${Y}, {})")`,
    ctx,
  );
}

// --------------------------------------------------------------------------
// 6. 摘要（与 Kotlin 侧同口径 —— 照抄 e50_arch01_upstream_probe.js）
// --------------------------------------------------------------------------
const FNV_OFFSET = 0xcbf29ce484222325n;
const FNV_PRIME = 0x100000001b3n;
const MASK64 = 0xffffffffffffffffn;

function fnvStep(h, v) {
  // ⚠️ 与 Kotlin 侧 `fnvStep` 同口径：Int 先**零扩展**成 32 位（`v >>> 0`）
  //    ⇒ 负数不会被符号扩展成 64 位（否则高 32 位会分叉）。
  return ((h ^ (BigInt(v >>> 0) & MASK64)) * FNV_PRIME) & MASK64;
}

const _f32buf = new ArrayBuffer(4);
const _f32view = new DataView(_f32buf);
function f32bits(x) {
  _f32view.setFloat32(0, Math.fround(x));
  return _f32view.getInt32(0);
}

function parseCss(css) {
  if (css === 'none') return null;
  if (css === 'white') return { r: 255, g: 255, b: 255, a: 1 };
  if (css === 'black') return { r: 0, g: 0, b: 0, a: 1 };
  const m = /^rgba?\(([^)]*)\)$/.exec(css);
  if (!m) return { css: css, r: -1, g: -1, b: -1, a: 1 };
  const p = m[1].split(',').map((s) => parseFloat(s.trim()));
  return {
    r: p[0],
    g: p[1],
    b: p[2],
    a: p.length > 3 ? p[3] : 1,
  };
}

function colorSteps(css) {
  const c = parseCss(css);
  if (c === null) return [0xffffffff];
  return [0x00000000, c.r, c.g, c.b, f32bits(c.a)];
}

/** 逐条轮廓的摘要（Float32 量化的点列 + 颜色 + 线宽）。 */
function digestOf(shapes) {
  let h = FNV_OFFSET;
  for (const s of shapes) {
    for (const v of colorSteps(s.fil)) h = fnvStep(h, v);
    for (const v of colorSteps(s.str)) h = fnvStep(h, v);
    h = fnvStep(h, f32bits(s.wid));
    h = fnvStep(h, s.pts.length);
    for (const p of s.pts) {
      h = fnvStep(h, f32bits(p[0]));
      h = fnvStep(h, f32bits(p[1]));
    }
  }
  return h;
}

/**
 * **结构摘要**：逐条轮廓的 填充 / 描边 / 线宽 / 点数（**不含坐标**）⇒ 逐位可比。
 *
 * ⚠️ 坐标**不能**逐位比：V8 与 JVM 的 `Math.cos/sin/atan2` 最多差 1 ULP
 * ⇒ Float32 量化后偶尔差 1 个 ULP。
 * ⇒ 坐标改用「量化网格摘要」（见 [roundedDigestOf]）。
 */
function structureDigestOf(shapes) {
  let h = FNV_OFFSET;
  for (const s of shapes) {
    for (const v of colorSteps(s.fil)) h = fnvStep(h, v);
    for (const v of colorSteps(s.str)) h = fnvStep(h, v);
    h = fnvStep(h, f32bits(s.wid));
    h = fnvStep(h, s.pts.length);
  }
  return h;
}

/**
 * **量化网格摘要**：`Math.round(v * scale)` 后的整数坐标。
 *
 * - `scale = 10` ⇒ **0.1 网格 = 上游 `poly()` 的输出精度**（上游写进 SVG 的就是它）
 * - `scale = 100` ⇒ 0.01 网格（更紧的指纹）。
 *
 * ⚠️⚠️ **与 `e50_arch01_upstream_probe.js` 的唯一差别：先把坐标量化成 Float32**
 *    （`Math.fround`）再取整 ⇒ `Math.round(Math.fround(v) * scale)`。
 *
 *    **为什么必须这样**：port 的轮廓点**就是 Float32**（D14：上游 Float64 / port Float32）
 *    ⇒ Kotlin 侧算的是 `Math.round(<Float32> * scale)`。
 *    若探针侧用**原始 Float64** 取整，两侧在「恰好落在网格边界」的点上会因
 *    Float32 量化而**差 1 格**（实测 tree05 有一条轮廓的某个点：
 *    上游 Float64 = -198.049998 ⇒ ×10 = -1980.49998 ⇒ round ⇒ -1980；
 *    port 的 Float32 = -198.050003 ⇒ ×10 = -1980.50003 ⇒ round ⇒ -1981）。
 *    ⇒ 探针侧先 `Math.fround` ⇒ 两侧比的是**同一个精度**的值 ⇒ 逐位相等。
 *
 *    ⚠️ arch01 探针没这么做也没出事 —— 它的夹具只有 951 个点，**没踩到边界点**；
 *    tree04/05/06 有 1.8~2.2 万个点 ⇒ 必然踩到。
 *    （这与本仓 G-PORT-11 的既有约定一致：「把上游的 Float64 量化成 Float32 后逐位比较
 *     （等价于 `Math.fround`）」。）
 */
function roundedDigestOf(shapes, scale) {
  let h = FNV_OFFSET;
  for (const s of shapes) {
    h = fnvStep(h, s.pts.length);
    for (const p of s.pts) {
      h = fnvStep(h, Math.round(Math.fround(p[0]) * scale));
      h = fnvStep(h, Math.round(Math.fround(p[1]) * scale));
    }
  }
  return h;
}

const hex = (b) => '0x' + b.toString(16).padStart(16, '0');

/** 从 SVG 串里按标记出现顺序还原 poly 的**拼接序**。 */
function canvasOrder(canv) {
  const order = [];
  const re = new RegExp(MARK + '(\\d+)' + MARK, 'g');
  let m;
  while ((m = re.exec(canv)) !== null) order.push(Number(m[1]));
  return order;
}

const report = {
  meta: {
    generator: 'scripts/e50_tree45678_upstream_probe.js',
    upstream: UPSTREAM,
    upstreamBytes: fs.statSync(UPSTREAM).size,
    patch:
      '① perlin 表初始化：Math.random() → __perlinLcg[]（LCG 种子 ' +
      NOISE_SEED + '）；② this.treeNN 行内追加 this.__treeNN = …（行号不变）',
    order: 'canvas（上游 SVG 拼接序，由 poly 返回值里的唯一标记还原）',
    seed: SEED,
    noiseSeed: NOISE_SEED,
  },
};

const dump = {
  meta: report.meta,
  trees: {},
};

for (const t of TREES) {
  const res = results[t];
  const order = canvasOrder(res.canv);
  const shapes = order.map((i) => res.shapes[i]);
  const missing = res.shapes.length - shapes.length;
  const entry = {
    call: { x: X, y: Y, args: {} },
    draws: res.draws,
    contourCount: shapes.length,
    pointCount: shapes.reduce((a, s) => a + s.pts.length, 0),
    digest: hex(digestOf(shapes)),
    structureDigest: hex(structureDigestOf(shapes)),
    roundedDigest10: hex(roundedDigestOf(shapes, 10)),
    roundedDigest100: hex(roundedDigestOf(shapes, 100)),
  };
  if (missing !== 0) {
    console.error(
      'FATAL: ' + t + ' 有 ' + missing + ' 次 poly() 发射没有进入最终 SVG —— ' +
        '拼接序还原不完整，探针必须停手。',
    );
    process.exit(2);
  }
  report[t] = entry;
  dump.trees[t] = {
    summary: entry,
    shapes: shapes.map((s, i) => ({
      i: i,
      fil: s.fil,
      str: s.str,
      wid: s.wid,
      n: s.pts.length,
      pts: EMIT_POINTS ? s.pts.map((p) => [Math.fround(p[0]), Math.fround(p[1])]) : undefined,
    })),
  };
}

// 明细 dump（含逐条轮廓的点列）
fs.mkdirSync(path.dirname(DUMP_JSON), { recursive: true });
fs.writeFileSync(DUMP_JSON, JSON.stringify(dump, null, 1), 'utf8');

// 黄金基线
const goldenText = JSON.stringify(report, null, 1) + '\n';
if (CHECK_ONLY) {
  if (!fs.existsSync(OUT_JSON)) {
    console.error('FATAL: 黄金基线不存在：' + OUT_JSON);
    process.exit(1);
  }
  const cur = fs.readFileSync(OUT_JSON, 'utf8');
  if (cur !== goldenText) {
    console.error('FATAL: 黄金基线与上游实跑结果**漂移**了：' + OUT_JSON);
    console.error('  ⇒ 上游文件或探针口径变了 ⇒ 请复核后重新生成。');
    process.exit(1);
  }
  console.log('黄金基线与上游实跑结果一致：' + OUT_JSON);
} else {
  fs.mkdirSync(path.dirname(OUT_JSON), { recursive: true });
  fs.writeFileSync(OUT_JSON, goldenText, 'utf8');
}

console.log('=== upstream Tree.tree04..tree08 probe ===');
console.log('upstream      :', UPSTREAM);
console.log('seed          :', SEED, ' noiseSeed:', NOISE_SEED);
for (const t of TREES) {
  const e = report[t];
  console.log('--- ' + t, JSON.stringify(e.call));
  console.log('  PRNG draws  :', e.draws);
  console.log('  contours    :', e.contourCount);
  console.log('  points      :', e.pointCount);
  console.log('  structure   :', e.structureDigest);
  console.log('  rounded0.1  :', e.roundedDigest10);
  console.log('  rounded0.01 :', e.roundedDigest100);
  console.log('  float32     :', e.digest, '(仅供参考：V8/JVM 的超越函数差 1 ULP)');
}
console.log('golden written:', OUT_JSON);
console.log('dump written  :', DUMP_JSON);
