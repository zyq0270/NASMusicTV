#!/usr/bin/env node
'use strict';
/*
 * scripts/e50_boat_man_upstream_probe.js
 * ---------------------------------------------------------------------------
 * E50「千里江山」**对账探针（扁舟 + 人物）**：把上游 shan-shui-inf
 * （LingDong Huang, MIT）的
 *   - `Arch.boat01`（`:3209-3248`，扁舟：船身 + 篷）
 *   - `Man.man`   （`:3522-3664`，人物：衣袖/身体/头 + 斗笠）
 *   - `Man.hat01` （`:3406-3449`，斗笠）
 *   - `Man.hat02` （`:3450-3521`，另一种帽子）
 *   - `Man.stick01`（`:3487-3513`，撑杆；`boat01` 内部用作 `ite`）
 * **原样**跑一遍，输出：
 *
 *   1. 每次 `poly()` 发射的轮廓（点列 / 填充 / 描边 / 线宽）——Float64 原始值；
 *   2. PRNG 抽取次数（`Math.random()` 的实际调用数）；
 *   3. 逐条轮廓的 **FNV-1a 64** 摘要（与 Kotlin 侧 `ShanShuiBoatManGateTest` 同口径）。
 *
 * ⛔ **探针对上游只有两处改动，且都不碰几何**：
 *    ① perlin 表的**初始化**：上游 `Noise.noise` 首次调用时用 `Math.random()` 填 4096 个
 *       表项（值任意）；探针改成读 `__perlinLcg[]`（与 port 的
 *       `ShanShuiNoise.noiseSeed(20250101.0)` **完全相同的 LCG**）⇒ 两侧噪声表逐位相同。
 *    ② 把 `boat01` / `man` / `hat01` / `hat02` / `stick01` **暴露**出来（在
 *       `this.boat01 = function...` 那一行**同行**改成
 *       `this.boat01 = this.__boat01 = function...`，行数不变 ⇒ 上游行号全部保持）。
 *       ⚠️ 与 `e50_arch01_upstream_probe.js` 暴露 `hut` 的写法**不同**：
 *          `hut` 是 IIFE 内部的 `var hut = function…`（有名）⇒ 可以写 `this.__hut = hut;`；
 *          而 `boat01`/`man`/`hat01`/`hat02`/`stick01` 都是**匿名**函数表达式直接赋值给
 *          `this.*` ⇒ 只能**链式赋值**（`this.x = this.__x = function…`）才能拿到引用。
 *          两者的行号效果完全一致（**不新增、不删除任何行**）。
 *    几何算法本身**逐字**来自上游（脚本块按原行号抽取，不重排）。
 *
 * ⚠️ 上游 `poly()` 输出时用 `.toFixed(1)`（1 位小数）；探针捕获的是**进 poly 之前**
 *    的点列（加完 xof/yof 偏移之后）⇒ 与 port 的 Float32 轮廓同口径。
 *
 * ## 摘要口径（与 `e50_arch01_upstream_probe.js` 同族）
 * - `digest`：**Float32 量化**的逐位摘要（FNV-1a 64）。
 * - `structureDigest`：填充 / 描边 / 线宽 / 点数（不含坐标）。
 * - `roundedDigest10` / `roundedDigest100`：**Float32 量化后**的 0.1 / 0.01 网格摘要。
 *   ⚠️ 探针在**捕获点列时**就 `Math.fround()` 量化 ⇒ 两侧（node / Kotlin 的
 *   `FloatArray`）算的是**同一批 Float32 值**的网格 ⇒ 必然逐位相等。
 *   （若 node 侧用原始 Float64，当某坐标恰好落在 `xx.005` 边界附近时，
 *   Float32 量化会把它推到另一侧 ⇒ **假失配**。实测 `manBoat` / `boat01` 各有 1 点。）
 * - `roundedDigest10Raw` / `roundedDigest100Raw`：原始 Float64 的网格摘要（**仅留档**）。
 *
 * ## 产物
 * - **黄金基线**（默认 `app/src/test/resources/qianli/shan_shui_boat_man_golden.json`）：
 *   门禁测试读它做逐位比对（⛔ 期望值不写在测试源码里）。
 * - **明细 dump**（默认 `output/e50_boat_man_upstream.json`）：逐条轮廓的点列，便于人工核对。
 *
 * 用法：
 *   node scripts/e50_boat_man_upstream_probe.js [--upstream=<file>] [--seed=157]
 *          [--noiseseed=20250101] [--out=<file>] [--dump=<file>] [--check] [--points]
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

// ── 夹具（与 Kotlin 门禁逐字一致） ────────────────────────────────

/** `Arch.boat01` 的夹具（上游 `:3209`）。 */
const BOAT = {
  xoff: 0,
  yoff: 0,
  seed: 3,
  len: 120,
  sca: 1,
  fli: false,
};

/** `Man.man` 的夹具（上游 `:3522`，走**全部缺省**：hat01 / 无 ite / ang 缺省）。 */
const MAN = {
  xoff: 0,
  yoff: 0,
  sca: 0.5,
  fli: true,
};

/**
 * `Man.man` 的第二种夹具 —— **镜像 `boat01` 内部那次调用**（上游 `:3217-3223`）：
 * `ite: Man.stick01`、`hat: Man.hat02`、`sca: 0.5*sca`（`boat01` 的 `sca` = 1 ⇒ 0.5）、
 * `len` 第 5 项 = 10（不是 30）。
 */
const MAN_BOAT = {
  xoff: 0,
  yoff: 0,
  sca: 0.5,
  fli: true,
};

/** `Man.hat01` / `Man.hat02` / `Man.stick01` 的夹具（p0 = 帽檐根部，p1 = 帽顶）。 */
const HAT = {
  p0: [0, 0],
  p1: [0, -20],
  fli: false,
};

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
      'shan_shui_boat_man_golden.json',
    ),
);
const DUMP_JSON = path.resolve(
  CLI.dump || path.join(REPO_ROOT, 'output', 'e50_boat_man_upstream.json'),
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
// 2b. 第二处（同样是最小改动）：把 `boat01` / `man` / `hat01` / `hat02` /
//     `stick01` 暴露出来，便于**单独**测量它们。
//     这五个都是**匿名**函数表达式直接赋值给 `this.*` ⇒ 用**链式赋值**
//     （`this.x = this.__x = function…`）暴露，**行数不变** ⇒ 上游行号全部保持。
// --------------------------------------------------------------------------
const EXPOSURES = [
  // [上游行号, 原文, 改写后]
  [3209, '    this.boat01 = function(xoff, yoff, seed, args) {',
    '    this.boat01 = this.__boat01 = function(xoff, yoff, seed, args) {'],
  [3406, '    this.hat01 = function(p0, p1, args) {',
    '    this.hat01 = this.__hat01 = function(p0, p1, args) {'],
  [3450, '    this.hat02 = function(p0, p1, args) {',
    '    this.hat02 = this.__hat02 = function(p0, p1, args) {'],
  [3487, '    this.stick01 = function(p0, p1, args) {',
    '    this.stick01 = this.__stick01 = function(p0, p1, args) {'],
  [3522, '    this.man = function(xoff, yoff, args) {',
    '    this.man = this.__man = function(xoff, yoff, args) {'],
];

for (const [upLine, orig, rewritten] of EXPOSURES) {
  // 上游行号是 **1-based**；PADDED 是 **0-based**。
  const idx = upLine - 1;
  if (PADDED[idx] !== orig) {
    console.error(
      'FATAL: 上游 :' + upLine + ' 的原文没能唯一匹配 —— 探针必须停手。\n' +
        '  期望：' + JSON.stringify(orig) + '\n' +
        '  实际：' + JSON.stringify(PADDED[idx]),
    );
    process.exit(2);
  }
  PADDED[idx] = rewritten;
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
//    + 用 `__inMan` 标志把**人物**（`Man.man` 内部）的轮廓与其它分开。
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
    // ⚠️ **Float32 量化**（与 port 的存储精度一致）：
    //    port 的 `ShanShuiPath.Point` 是 `FloatArray`（Float32）⇒ 网格摘要必须在
    //    **两侧都用 Float32** 的前提下才有意义。
    //    （上游 `poly()` 自己写进 SVG 的是 `.toFixed(1)` —— 1 位小数，比 Float32 还粗。）
    '    pts: plist.map(function (p) { return [Math.fround(p[0] + xof), Math.fround(p[1] + yof)]; }),',
    // ⚠️ 原始 Float64（仅供留档 / 人工核对；**不参与**门禁摘要）。
    '    rawPts: plist.map(function (p) { return [p[0] + xof, p[1] + yof]; }),',
    '  });',
    '  return __origPoly(plist, args);',
    '};',
    // ⚠️ `Man.man` 的**包装**：把 `Man.man` 换成计数版 ⇒
    //    ① `Arch.boat01` 内部那次 `Man.man(...)`（上游 :3217）也会被包住
    //       ⇒ 扁舟里的人物轮廓带 `inMan = true`；
    //    ② 单独测量 `Man.man` 时也走包装 ⇒ 人物自己的轮廓同样带 `inMan = true`。
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
// 5. seed + 逐个测量上游图元（每次测量都重置 PRNG ⇒ 互相独立）
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

const FIXTURES = [
  {
    key: 'boat01',
    call: {
      xoff: BOAT.xoff,
      yoff: BOAT.yoff,
      seed: BOAT.seed,
      len: BOAT.len,
      sca: BOAT.sca,
      fli: BOAT.fli,
    },
    expr:
      'Arch.__boat01(' + BOAT.xoff + ', ' + BOAT.yoff + ', ' + BOAT.seed +
      ', { len: ' + BOAT.len + ', sca: ' + BOAT.sca + ', fli: ' + BOAT.fli + ' })',
  },
  {
    key: 'man',
    call: { xoff: MAN.xoff, yoff: MAN.yoff, sca: MAN.sca, fli: MAN.fli },
    expr:
      'Man.man(' + MAN.xoff + ', ' + MAN.yoff +
      ', { sca: ' + MAN.sca + ', fli: ' + MAN.fli + ' })',
  },
  {
    key: 'manBoat',
    call: { xoff: MAN_BOAT.xoff, yoff: MAN_BOAT.yoff, sca: MAN_BOAT.sca, fli: MAN_BOAT.fli },
    expr:
      'Man.man(' + MAN_BOAT.xoff + ', ' + MAN_BOAT.yoff +
      ', { ite: Man.stick01, hat: Man.hat02, sca: ' + MAN_BOAT.sca +
      ', fli: ' + MAN_BOAT.fli +
      ', len: [0, 30, 20, 30, 10, 30, 30, 30, 30] })',
  },
  {
    key: 'hat01',
    call: { p0: HAT.p0, p1: HAT.p1, fli: HAT.fli },
    expr:
      'Man.__hat01([' + HAT.p0[0] + ', ' + HAT.p0[1] + '], [' + HAT.p1[0] + ', ' + HAT.p1[1] +
      '], { fli: ' + HAT.fli + ' })',
  },
  {
    key: 'hat02',
    call: { p0: HAT.p0, p1: HAT.p1, fli: HAT.fli },
    expr:
      'Man.__hat02([' + HAT.p0[0] + ', ' + HAT.p0[1] + '], [' + HAT.p1[0] + ', ' + HAT.p1[1] +
      '], { fli: ' + HAT.fli + ' })',
  },
  {
    key: 'stick01',
    call: { p0: HAT.p0, p1: HAT.p1, fli: HAT.fli },
    expr:
      'Man.__stick01([' + HAT.p0[0] + ', ' + HAT.p0[1] + '], [' + HAT.p1[0] + ', ' + HAT.p1[1] +
      '], { fli: ' + HAT.fli + ' })',
  },
];

const results = {};
for (const f of FIXTURES) {
  results[f.key] = Object.assign({ call: f.call }, vm.runInContext(
    '__measure(' + JSON.stringify(f.expr) + ')',
    ctx,
  ));
}

// --------------------------------------------------------------------------
// 6. 摘要（与 Kotlin 侧同口径 —— 与 e50_arch01_upstream_probe.js 完全一致）
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
 * ⚠️ `s.pts` 已在捕获时**量化成 Float32**（= port 的 `FloatArray` 存储精度）⇒
 *    两侧（node / Kotlin）算的是**同一批 Float32 值**的网格 ⇒ 必然逐位相等。
 *    （若 node 侧用原始 Float64，则当某个坐标恰好落在 `xx.005` 边界附近时，
 *    Float32 量化会把它推到另一侧 ⇒ 出现**假失配**。实测 `manBoat` / `boat01`
 *    各有 1 个点会这样。）
 *
 * - `scale = 10` ⇒ **0.1 网格 = 上游 `poly()` 的输出精度**（上游写进 SVG 的就是它）
 *   ⇒ 「port 画出来的点落在上游的同一个 0.1 网格上」。
 * - `scale = 100` ⇒ 0.01 网格（更紧的指纹）。
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

/**
 * 同上，但用**原始 Float64** 坐标 —— 仅供留档（上游自己的精度），**不参与**门禁。
 */
function roundedDigestRawOf(shapes, scale) {
  let h = FNV_OFFSET;
  for (const s of shapes) {
    h = fnvStep(h, s.rawPts.length);
    for (const p of s.rawPts) {
      h = fnvStep(h, Math.round(p[0] * scale));
      h = fnvStep(h, Math.round(p[1] * scale));
    }
  }
  return h;
}

const hex = (b) => '0x' + b.toString(16).padStart(16, '0');

/** 一个测量项的摘要集合。 */
function summarize(res) {
  const shapes = res.shapes;
  return {
    draws: res.draws,
    contourCount: shapes.length,
    manContourCount: shapes.filter((s) => s.inMan).length,
    pointCount: shapes.reduce((a, s) => a + s.pts.length, 0),
    digest: hex(digestOf(shapes)),
    structureDigest: hex(structureDigestOf(shapes)),
    roundedDigest10: hex(roundedDigestOf(shapes, 10)),
    roundedDigest100: hex(roundedDigestOf(shapes, 100)),
    roundedDigest10Raw: hex(roundedDigestRawOf(shapes, 10)),
    roundedDigest100Raw: hex(roundedDigestRawOf(shapes, 100)),
  };
}

const report = {
  meta: {
    generator: 'scripts/e50_boat_man_upstream_probe.js',
    upstream: UPSTREAM,
    upstreamBytes: fs.statSync(UPSTREAM).size,
    patch:
      '① perlin 表初始化：Math.random() → __perlinLcg[]（LCG 种子 ' +
      NOISE_SEED + '）；② this.boat01 / this.man / this.hat01 / this.hat02 / ' +
      'this.stick01 行内链式追加 this.__xxx（同函数引用，行号不变）',
    seed: SEED,
    noiseSeed: NOISE_SEED,
  },
};

for (const f of FIXTURES) {
  report[f.key] = Object.assign({ call: f.call }, summarize(results[f.key]));
}

// 明细 dump（含逐条轮廓的点列）
const dump = { meta: report.meta };
for (const f of FIXTURES) {
  dump[f.key] = Object.assign({}, report[f.key], {
    shapes: results[f.key].shapes.map((s, i) => ({
      i: i,
      fil: s.fil,
      str: s.str,
      wid: s.wid,
      inMan: s.inMan,
      n: s.pts.length,
      pts: EMIT_POINTS ? s.pts.map((p) => [p[0], p[1]]) : undefined,
      rawPts: EMIT_POINTS ? s.rawPts.map((p) => [p[0], p[1]]) : undefined,
    })),
  });
}
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

console.log('=== upstream Arch.boat01 / Man.* probe ===');
console.log('upstream      :', UPSTREAM);
console.log('seed          :', SEED, ' noiseSeed:', NOISE_SEED);
for (const f of FIXTURES) {
  const r = report[f.key];
  console.log('--- ' + f.key, JSON.stringify(f.call));
  console.log('  PRNG draws  :', r.draws);
  console.log('  contours    :', r.contourCount, '(of which Man.man:', r.manContourCount + ')');
  console.log('  points      :', r.pointCount);
  console.log('  structure   :', r.structureDigest);
  console.log('  rounded0.1  :', r.roundedDigest10);
  console.log('  rounded0.01 :', r.roundedDigest100);
  console.log('  float32     :', r.digest, '(仅供参考：V8/JVM 的超越函数差 1 ULP)');
}
console.log('golden written:', OUT_JSON);
console.log('dump written  :', DUMP_JSON);
