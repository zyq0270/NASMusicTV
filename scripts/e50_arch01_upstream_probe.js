#!/usr/bin/env node
'use strict';
/*
 * scripts/e50_arch01_upstream_probe.js
 * ---------------------------------------------------------------------------
 * E50「千里江山」**对账探针**：把上游 shan-shui-inf（LingDong Huang, MIT）的
 * `Arch.arch01`（= `hut` 草屋 + `box` 桥身 + `rail`×2 栏杆 + `Man.man` 渔人）
 * **原样**跑一遍，输出：
 *
 *   1. 每次 `poly()` 发射的轮廓（点列 / 填充 / 描边 / 线宽）——Float64 原始值；
 *   2. PRNG 抽取次数（`Math.random()` 的实际调用数）；
 *   3. 逐条轮廓的 **FNV-1a 64** 摘要（与 Kotlin 侧 `ShanShuiPortGateTest` 同口径）。
 *
 * ⛔ **探针对上游只有两处改动，且都不碰几何**：
 *    ① perlin 表的**初始化**：上游 `Noise.noise` 首次调用时用 `Math.random()` 填 4096 个
 *       表项（值任意）；探针改成读 `__perlinLcg[]`（与 port 的
 *       `ShanShuiNoise.noiseSeed(20250101.0)` **完全相同的 LCG**）⇒ 两侧噪声表逐位相同。
 *    ② 在 `this.arch01` 那一行**同行**追加 `this.__hut = hut;`（行数不变 ⇒ 上游行号不变）
 *       ⇒ 只为能**单独**测量 `hut`（它在 `Arch` 的 IIFE 内部，外部不可见）。
 *    几何算法本身**逐字**来自上游（脚本块按原行号抽取，不重排）。
 *
 * ⚠️ 上游 `poly()` 输出时用 `.toFixed(1)`（1 位小数）；探针捕获的是**进 poly 之前**
 *    的 Float64 点列（加完 xof/yof 偏移之后）⇒ 与 port 的 Float32 轮廓同口径。
 *
 * ## 产物
 * - **黄金基线**（默认 `app/src/test/resources/qianli/shan_shui_arch01_golden.json`）：
 *   门禁测试读它做逐位比对（⛔ 期望值不写在测试源码里）。
 * - **明细 dump**（默认 `output/e50_arch01_upstream.json`）：逐条轮廓的点列，便于人工核对。
 *
 * 用法：
 *   node scripts/e50_arch01_upstream_probe.js [--upstream=<file>] [--seed=157]
 *          [--xoff=0] [--yoff=0] [--archseed=3] [--hei=90] [--wid=180] [--per=0.5]
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

const XOFF = Number(CLI.xoff !== undefined ? CLI.xoff : 0);
const YOFF = Number(CLI.yoff !== undefined ? CLI.yoff : 0);
const ARCH_SEED = Number(CLI.archseed !== undefined ? CLI.archseed : 3);
const HEI = Number(CLI.hei !== undefined ? CLI.hei : 90);
const WID = Number(CLI.wid !== undefined ? CLI.wid : 180);
const PER = Number(CLI.per !== undefined ? CLI.per : 0.5);

/** 单独的 `hut` 夹具（与 Kotlin 门禁的 hut 用例同参）。 */
const HUT_XOFF = 0;
const HUT_YOFF = 0;
const HUT_HEI = 40;
const HUT_WID = 60;
const HUT_TEX = 10;

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
      'shan_shui_arch01_golden.json',
    ),
);
const DUMP_JSON = path.resolve(
  CLI.dump || path.join(REPO_ROOT, 'output', 'e50_arch01_upstream.json'),
);
const CHECK_ONLY = CLI.check === true || CLI.check === 'true';
const EMIT_POINTS = CLI.points === true || CLI.points === 'true';

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
    while (j < lines.length && !/^\s*<\/script>\s*$/.exec(lines[j])) j++;
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
// 2. 唯一的上游改动：perlin 表的初始化
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
// 2b. 第二处（同样是最小改动）：把 `hut` 暴露出来，便于**单独**测量它。
//     `hut` 是 `Arch` IIFE 内部的 `var` ⇒ 外部不可见 ⇒ 在 `this.arch01` 那一行
//     **同行**追加 `this.__hut = hut;`（行数不变 ⇒ 上游行号全部保持）。
// --------------------------------------------------------------------------
let exposed = 0;
for (let i = 0; i < PADDED.length; i++) {
  if (PADDED[i] === '    this.arch01 = function(xoff, yoff, seed, args) {') {
    PADDED[i] = '    this.__hut = hut; this.arch01 = function(xoff, yoff, seed, args) {';
    exposed++;
  }
}
if (exposed !== 1) {
  console.error(
    'FATAL: `this.arch01` 的上游原文没能唯一匹配（命中 ' + exposed + ' 处）——' +
      '上游文件可能已变动，探针必须停手。',
  );
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

// --------------------------------------------------------------------------
// 4. 插桩：捕获每一次 poly() 发射 + 统计 Math.random 抽取次数
// --------------------------------------------------------------------------
vm.runInContext(
  [
    'var __rec = [];',
    'var __inMan = false;',
    'var __origPoly = poly;',
    // ⚠️ 上游的每一条**可见轮廓**都恰好是一次 poly() 调用
    //   （stroke() 内部也只发一次 poly）⇒ 记录**全部** poly 调用即得发射序列。
    'poly = function (plist, args) {',
    '  var xof = (args && args.xof !== undefined) ? args.xof : 0;',
    '  var yof = (args && args.yof !== undefined) ? args.yof : 0;',
    '  var fil = (args && args.fil !== undefined) ? args.fil : "rgba(0,0,0,0)";',
    '  var str = (args && args.str !== undefined) ? args.str : fil;',
    '  var wid = (args && args.wid !== undefined) ? args.wid : 0;',
    '  __rec.push({',
    '    fil: fil, str: str, wid: wid, inMan: __inMan,',
    '    pts: plist.map(function (p) { return [p[0] + xof, p[1] + yof]; }),',
    '  });',
    '  return __origPoly(plist, args);',
    '};',
    'var __origMan = Man.man;',
    'Man.man = function () {',
    '  var was = __inMan;',
    '  __inMan = true;',
    '  try { return __origMan.apply(Man, arguments); } finally { __inMan = was; }',
    '};',
    'var __draws = 0;',
    'var __origRandom = Math.random;',
    'Math.random = function () { __draws++; return __origRandom(); };',
  ].join('\n'),
  ctx,
  { filename: 'instrument.js' },
);

// --------------------------------------------------------------------------
// 5. seed + 调用上游 arch01（再单独测一次 hut）
// --------------------------------------------------------------------------
vm.runInContext(
  [
    'function __reset() { __rec.length = 0; __draws = 0; Math.seed(' + SEED + '); }',
    'function __measure(expr) {',
    '  __reset();',
    '  eval(expr);',
    '  return { shapes: __rec.slice(), draws: __draws };',
    '}',
  ].join('\n'),
  ctx,
  { filename: 'instrument.js' },
);

const archRes = vm.runInContext(
  `__measure("Arch.arch01(${XOFF}, ${YOFF}, ${ARCH_SEED}, { hei: ${HEI}, wid: ${WID}, per: ${PER} })")`,
  ctx,
);
const hutRes = vm.runInContext(
  `__measure("Arch.__hut(${HUT_XOFF}, ${HUT_YOFF}, { hei: ${HUT_HEI}, wid: ${HUT_WID}, tex: ${HUT_TEX} })")`,
  ctx,
);

const rec = archRes.shapes;
const draws = archRes.draws;

// --------------------------------------------------------------------------
// 6. 摘要（与 Kotlin 侧同口径）
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
 * ⇒ Float32 量化后偶尔差 1 个 ULP（实测 951 点里 115 点）。
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
 *   ⇒ 「port 画出来的点落在上游的同一个 0.1 网格上」。
 * - `scale = 100` ⇒ 0.01 网格（更紧的指纹）。
 *
 * ⚠️ 两侧各用自己的精度（node = Float64、port = Float32）计算 `Math.round(v*scale)`
 *   —— 实测两个 scale 在本夹具下**都零失配**。
 */
function roundedDigestOf(shapes, scale) {
  let h = FNV_OFFSET;
  for (const s of shapes) {
    h = fnvStep(h, s.pts.length);
    for (const p of s.pts) {
      h = fnvStep(h, Math.round(p[0] * scale));
      h = fnvStep(h, Math.round(p[1] * scale));
    }
  }
  return h;
}

const manShapes = rec.filter((s) => s.inMan);
const shapes = rec.filter((s) => !s.inMan);
const digest = digestOf(shapes);
const structureDigest = structureDigestOf(shapes);
const roundedDigest10 = roundedDigestOf(shapes, 10);
const roundedDigest100 = roundedDigestOf(shapes, 100);

const hutShapes = hutRes.shapes.filter((s) => !s.inMan);

const hex = (b) => '0x' + b.toString(16).padStart(16, '0');

/** 一个测量项的摘要集合。 */
function summarize(res, shapes) {
  return {
    draws: res.draws,
    contourCount: shapes.length,
    manContourCount: res.shapes.filter((s) => s.inMan).length,
    digest: hex(digestOf(shapes)),
    structureDigest: hex(structureDigestOf(shapes)),
    roundedDigest10: hex(roundedDigestOf(shapes, 10)),
    roundedDigest100: hex(roundedDigestOf(shapes, 100)),
    pointCount: shapes.reduce((a, s) => a + s.pts.length, 0),
  };
}

const report = {
  meta: {
    generator: 'scripts/e50_arch01_upstream_probe.js',
    upstream: UPSTREAM,
    upstreamBytes: fs.statSync(UPSTREAM).size,
    patch:
      '① perlin 表初始化：Math.random() → __perlinLcg[]（LCG 种子 ' +
      NOISE_SEED + '）；② this.arch01 行内追加 this.__hut = hut;（行号不变）',
    seed: SEED,
    noiseSeed: NOISE_SEED,
  },
  arch01: Object.assign(
    {
      call: { xoff: XOFF, yoff: YOFF, archSeed: ARCH_SEED, hei: HEI, wid: WID, per: PER },
    },
    summarize(archRes, shapes),
  ),
  hut: Object.assign(
    {
      call: { xoff: HUT_XOFF, yoff: HUT_YOFF, hei: HUT_HEI, wid: HUT_WID, tex: HUT_TEX },
    },
    summarize(hutRes, hutShapes),
  ),
};

// 明细 dump（含逐条轮廓的点列）
fs.mkdirSync(path.dirname(DUMP_JSON), { recursive: true });
fs.writeFileSync(
  DUMP_JSON,
  JSON.stringify(
    {
      meta: report.meta,
      arch01: report.arch01,
      hut: report.hut,
      shapes: shapes.map((s, i) => ({
        i: i,
        fil: s.fil,
        str: s.str,
        wid: s.wid,
        n: s.pts.length,
        pts: EMIT_POINTS ? s.pts.map((p) => [Math.fround(p[0]), Math.fround(p[1])]) : undefined,
      })),
    },
    null,
    1,
  ),
  'utf8',
);

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

console.log('=== upstream Arch.arch01 probe ===');
console.log('upstream      :', UPSTREAM);
console.log('seed          :', SEED, ' noiseSeed:', NOISE_SEED);
console.log('--- arch01', JSON.stringify(report.arch01.call));
console.log('  PRNG draws  :', report.arch01.draws);
console.log('  contours    :', report.arch01.contourCount, '(Man.man 的', report.arch01.manContourCount, '条已排除)');
console.log('  points      :', report.arch01.pointCount);
console.log('  structure   :', report.arch01.structureDigest);
console.log('  rounded0.1  :', report.arch01.roundedDigest10);
console.log('  rounded0.01 :', report.arch01.roundedDigest100);
console.log('  float32     :', report.arch01.digest, '(仅供参考：V8/JVM 的超越函数差 1 ULP)');
console.log('--- hut', JSON.stringify(report.hut.call));
console.log('  PRNG draws  :', report.hut.draws);
console.log('  contours    :', report.hut.contourCount);
console.log('  points      :', report.hut.pointCount);
console.log('  structure   :', report.hut.structureDigest);
console.log('  rounded0.1  :', report.hut.roundedDigest10);
console.log('  rounded0.01 :', report.hut.roundedDigest100);
console.log('golden written:', OUT_JSON);
console.log('dump written  :', DUMP_JSON);
