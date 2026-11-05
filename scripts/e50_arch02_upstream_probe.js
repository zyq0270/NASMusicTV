#!/usr/bin/env node
'use strict';
/*
 * scripts/e50_arch02_upstream_probe.js
 * ---------------------------------------------------------------------------
 * E50「千里江山」**对账探针**（arch02 / arch03 / arch04）：把上游 shan-shui-inf
 * （LingDong Huang, MIT）的
 *
 *   · `Arch.arch02`（多层楼阁，:3061-3119）
 *   · `Arch.arch03`（塔，      :3120-3163）
 *   · `Arch.arch04`（拱桥，    :3164-3207）
 *
 * 以及它们的零件（`box` / `deco` / `rail` / `roof` / `pagroof`）
 * **原样**跑一遍，输出：
 *
 *   1. 每次 `poly()` 发射的轮廓（点列 / 填充 / 描边 / 线宽）——Float64 原始值；
 *   2. PRNG 抽取次数（`Math.random()` 的实际调用数）；
 *   3. 逐条轮廓的 **FNV-1a 64** 摘要（与 Kotlin 侧 `ShanShuiArch23GateTest` 同口径）。
 *
 * ⛔ **探针对上游只有两处改动，且都不碰几何**：
 *    ① perlin 表的**初始化**：上游 `Noise.noise` 首次调用时用 `Math.random()` 填 4096 个
 *       表项（值任意）；探针改成读 `__perlinLcg[]`（与 port 的
 *       `ShanShuiNoise.noiseSeed(20250101.0)` **完全相同的 LCG**）⇒ 两侧噪声表逐位相同。
 *    ② 在 `this.arch01` 那一行**同行**追加 `this.__box = box; this.__deco = deco;
 *       this.__rail = rail; this.__roof = roof; this.__pagroof = pagroof;`
 *       （行数不变 ⇒ 上游行号不变）⇒ 只为能**单独**测量这几个零件
 *       （它们是 `Arch` 的 IIFE 内部的 `var` ⇒ 外部不可见）。
 *       （`Arch.arch02/03/04` 本身就是 `this.archNN` ⇒ 外部**本来就能访问** ⇒ 无需暴露。）
 *    几何算法本身**逐字**来自上游（脚本块按原行号抽取，不重排）。
 *
 * ⚠️ 上游 `poly()` 输出时用 `.toFixed(1)`（1 位小数）；探针捕获的是**进 poly 之前**
 *    的 Float64 点列（加完 xof/yof 偏移之后）⇒ 与 port 的 Float32 轮廓同口径。
 *
 * ⚠️ **`<text>` 题字**（上游 `roof` 的 `:2929-2954`，内容「Pizza Hut」）：它**不是**
 *    `poly()` 发射 ⇒ 不在 `__rec` 里。但它确实出现在上游返回的 SVG 字符串中，
 *    且**位置在它所在那一层的最后**（在 `roof()` 的末尾）⇒ 探针按 **SVG 元素的实际顺序**
 *    扫描返回串：`<polyline>` 依次消耗 `__rec` 里的 poly 记录，`<text>` 现场合成一条
 *    等价记录（锚点 = `translate(x,y)`、填充 = `style` 的 `fill`、线宽 = `font-size`）。
 *    ⇒ 两侧的轮廓序列**逐条同序**（Kotlin 侧用 `SHANSHUI_TAG_ARCH_TEXT` 标记那条题字）。
 *
 * ## 产物
 * - **黄金基线**（默认 `app/src/test/resources/qianli/shan_shui_arch02_golden.json`）：
 *   门禁测试读它做逐位比对（⛔ 期望值不写在测试源码里）。
 * - **明细 dump**（默认 `output/e50_arch02_upstream.json`）：逐条轮廓的点列，便于人工核对。
 *
 * 用法：
 *   node scripts/e50_arch02_upstream_probe.js [--upstream=<file>] [--seed=157]
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
      'shan_shui_arch02_golden.json',
    ),
);
const DUMP_JSON = path.resolve(
  CLI.dump || path.join(REPO_ROOT, 'output', 'e50_arch02_upstream.json'),
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
// 2. 唯一的上游改动 ①：perlin 表的初始化
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
// 2b. 第二处（同样是最小改动）：把 `box` / `deco` / `rail` / `roof` / `pagroof`
//     暴露出来，便于**单独**测量这几个零件（它们是 `Arch` IIFE 内部的 `var`）。
//     在 `this.arch01` 那一行**同行**追加（行数不变 ⇒ 上游行号全部保持）。
// --------------------------------------------------------------------------
const EXPOSE_LINE = '    this.arch01 = function(xoff, yoff, seed, args) {';
const EXPOSE_PATCH =
  '    this.__box = box; this.__deco = deco; this.__rail = rail; ' +
  'this.__roof = roof; this.__pagroof = pagroof; ' +
  'this.arch01 = function(xoff, yoff, seed, args) {';
let exposed = 0;
for (let i = 0; i < PADDED.length; i++) {
  if (PADDED[i] === EXPOSE_LINE) {
    PADDED[i] = EXPOSE_PATCH;
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
    'var __origPoly = poly;',
    'poly = function (plist, args) {',
    '  var xof = (args && args.xof !== undefined) ? args.xof : 0;',
    '  var yof = (args && args.yof !== undefined) ? args.yof : 0;',
    '  var fil = (args && args.fil !== undefined) ? args.fil : "rgba(0,0,0,0)";',
    '  var str = (args && args.str !== undefined) ? args.str : fil;',
    '  var wid = (args && args.wid !== undefined) ? args.wid : 0;',
    '  __rec.push({',
    '    fil: fil, str: str, wid: wid,',
    '    pts: plist.map(function (p) { return [p[0] + xof, p[1] + yof]; }),',
    '  });',
    '  return __origPoly(plist, args);',
    '};',
    'var __draws = 0;',
    'var __origRandom = Math.random;',
    'Math.random = function () { __draws++; return __origRandom(); };',
  ].join('\n'),
  ctx,
  { filename: 'instrument.js' },
);

// --------------------------------------------------------------------------
// 5. 夹具 —— 与 Kotlin 门禁逐字一致
// --------------------------------------------------------------------------
/**
 * 每个夹具 = { key, call（写进黄金基线的入参，供 Kotlin 侧照抄）, expr（vm 内求值） }。
 *
 * ⚠️ `expr` 里的对象字面量**按 JS 的左到右求值序**书写 —— 但这些夹具的入参里
 *    **没有任何随机数**（`Math.random` 全部在函数体内部）⇒ 求值序不影响随机流。
 */
const FIXTURES = [
  {
    key: 'arch02',
    kind: 'arch02',
    call: { xoff: 0, yoff: 0, seed: 3, hei: 10, wid: 50, rot: 0.3, per: 5, sto: 3, sty: 1, rai: false },
    expr:
      'Arch.arch02(0, 0, 3, {hei: 10, wid: 50, rot: 0.3, per: 5, sto: 3, sty: 1, rai: false})',
  },
  {
    // ⚠️ 这一组是为了让上游的「Pizza Hut」题字**真的出现**：
    //   上游 `:3103-3106` 的触发条件是 `sto == 1 && Math.random() < 1/3`。
    //   PRNG 种子 157 下，题字判定前已消耗的抽取数 = box 的 11 次（sty=3 ⇒ deco 出 6 条）
    //   + rail 的 7 次（rai=true）= 18 ⇒ 判定用的是第 19 次抽取 = 0.324982 < 1/3 ⇒ 触发。
    key: 'arch02Text',
    kind: 'arch02',
    call: { xoff: 0, yoff: 0, seed: 3, hei: 10, wid: 50, rot: 0.3, per: 5, sto: 1, sty: 3, rai: true },
    expr:
      'Arch.arch02(0, 0, 3, {hei: 10, wid: 50, rot: 0.3, per: 5, sto: 1, sty: 3, rai: true})',
  },
  {
    // ⚠️ 这一组是**端到端**验证上游的「Pizza Hut」题字（上游 `:3103-3106`）：
    //   触发条件 = `sto == 1 && Math.random() < 1/3`。
    //   题字判定之前已消耗的抽取数 = box 的 9 次（sty=1 ⇒ deco 出 4 条）+ rail 的 0 次
    //   （rai=false）= 9 ⇒ 判定用的是**第 10 次**抽取。
    //   PRNG 种子 **12** 时该值 = 0.078129 < 1/3 ⇒ 题字**真的会出现**。
    //   ⚠️ 这一条用**自己的 PRNG 种子**（不是全局的 157）⇒ 黄金基线里逐条记录 `prngSeed`。
    key: 'arch02Pizza',
    kind: 'arch02',
    prngSeed: 12,
    call: { xoff: 0, yoff: 0, seed: 3, hei: 10, wid: 50, rot: 0.3, per: 5, sto: 1, sty: 1, rai: false },
    expr:
      'Arch.arch02(0, 0, 3, {hei: 10, wid: 50, rot: 0.3, per: 5, sto: 1, sty: 1, rai: false})',
  },
  {
    key: 'arch02Sty2',
    kind: 'arch02',
    call: { xoff: 0, yoff: 0, seed: 3, hei: 10, wid: 50, rot: 0.3, per: 5, sto: 2, sty: 2, rai: false },
    expr:
      'Arch.arch02(0, 0, 3, {hei: 10, wid: 50, rot: 0.3, per: 5, sto: 2, sty: 2, rai: false})',
  },
  {
    key: 'arch02Sty3',
    kind: 'arch02',
    call: { xoff: 0, yoff: 0, seed: 3, hei: 10, wid: 50, rot: 0.7, per: 5, sto: 2, sty: 3, rai: false },
    expr:
      'Arch.arch02(0, 0, 3, {hei: 10, wid: 50, rot: 0.7, per: 5, sto: 2, sty: 3, rai: false})',
  },
  {
    key: 'arch03',
    kind: 'arch03',
    call: { xoff: 0, yoff: 0, seed: 3, hei: 10, wid: 50, rot: 0.7, per: 5, sto: 7 },
    expr: 'Arch.arch03(0, 0, 3, {hei: 10, wid: 50, rot: 0.7, per: 5, sto: 7})',
  },
  {
    key: 'arch04',
    kind: 'arch04',
    call: { xoff: 0, yoff: 0, seed: 3, hei: 15, wid: 30, rot: 0.7, per: 5, sto: 2 },
    expr: 'Arch.arch04(0, 0, 3, {hei: 15, wid: 30, rot: 0.7, per: 5, sto: 2})',
  },
  {
    key: 'box',
    kind: 'box',
    call: { xoff: 0, yoff: 0, hei: 20, wid: 120, rot: 0.3, per: 4, tra: false, bot: true, wei: 3, sty: 1 },
    expr:
      'Arch.__box(0, 0, {hei: 20, wid: 120, rot: 0.3, per: 4, tra: false, bot: true, wei: 3, ' +
      'dec: function(a){ return Arch.__deco(1, Object.assign({}, a, {hsp: [1,5], vsp: [1,2]})); }})',
  },
  {
    key: 'rail',
    kind: 'rail',
    call: { xoff: 0, yoff: 0, seed: 0.4, hei: 5, wid: 55, rot: 0.3, per: 5, seg: 4, wei: 0.5, tra: true, fro: true },
    expr:
      'Arch.__rail(0, 0, 0.4, {hei: 5, wid: 55, rot: 0.3, per: 5, seg: 4, wei: 0.5, tra: true, fro: true})',
  },
  {
    key: 'roof',
    kind: 'roof',
    call: { xoff: 0, yoff: 0, hei: 10, wid: 45, rot: 0.3, per: 5, cor: 5, wei: 1.5, pla: 1 },
    expr:
      'Arch.__roof(0, 0, {hei: 10, wid: 45, rot: 0.3, per: 5, wei: 1.5, pla: [1, "Pizza Hut"]})',
  },
  {
    key: 'pagroof',
    kind: 'pagroof',
    call: { xoff: 0, yoff: 0, hei: 15, wid: 45, rot: 0.7, per: 5, cor: 10, sid: 4, wei: 1.5 },
    expr: 'Arch.__pagroof(0, 0, {hei: 15, wid: 45, rot: 0.7, per: 5, wei: 1.5})',
  },
];

// --------------------------------------------------------------------------
// 6. 量测：跑一个夹具 ⇒ 返回 { svg, polyShapes, draws }
// --------------------------------------------------------------------------
vm.runInContext(
  [
    'function __reset(seed) { __rec.length = 0; __draws = 0; Math.seed(seed); }',
    'function __measure(expr, seed) {',
    '  __reset(seed);',
    '  var svg = eval(expr);',
    '  return { svg: svg, shapes: __rec.slice(), draws: __draws };',
    '}',
  ].join('\n'),
  ctx,
  { filename: 'instrument.js' },
);

// --------------------------------------------------------------------------
// 7. 把上游返回的 SVG 串拆成「发射序列」
//    · `<polyline .../>` ⇒ 依次消耗 poly 记录
//    · `<text ...>...</text>` ⇒ 现场合成一条等价记录（锚点 / 填充 / 字号）
// --------------------------------------------------------------------------
const EL_RE = /<polyline\b[^>]*\/>|<text\b[^>]*>[\s\S]*?<\/text>/g;

function parseTextElement(el) {
  const fs2 = /font-size='([^']*)'/.exec(el);
  const st = /style='fill:([^;']*)'/.exec(el);
  const tr = /transform='translate\(([^,]+),([^)]+)\) rotate\(([^)]+)\)'/.exec(el);
  if (!fs2 || !st || !tr) {
    throw new Error('FATAL: 无法解析上游的 <text> 元素：' + el);
  }
  return {
    fil: st[1],
    str: 'none',
    wid: parseFloat(fs2[1]),
    pts: [[parseFloat(tr[1]), parseFloat(tr[2])]],
    isText: true,
  };
}

/** 按 SVG 里的**实际顺序**合成发射序列。 */
function emissionSequence(res) {
  const out = [];
  let polyIdx = 0;
  const els = res.svg.match(EL_RE) || [];
  for (const el of els) {
    if (el.startsWith('<polyline')) {
      const s = res.shapes[polyIdx];
      if (!s) throw new Error('FATAL: 上游 SVG 里的 <polyline> 比 poly() 调用还多。');
      out.push(s);
      polyIdx++;
    } else {
      out.push(parseTextElement(el));
    }
  }
  if (polyIdx !== res.shapes.length) {
    throw new Error(
      'FATAL: poly() 调用数（' + res.shapes.length + '）与 SVG 里的 <polyline> 数（' +
        polyIdx +
        '）不一致。',
    );
  }
  return out;
}

// --------------------------------------------------------------------------
// 8. 摘要（与 Kotlin 侧同口径 —— 照抄 e50_arch01_upstream_probe.js）
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
 * ⛔⛔ **必须先量化到 Float32**（`Math.fround`）再取整（2026-11-06 修正）：
 *   本仓端口的坐标存的是 **`FloatArray`（Float32）**（`ShanShuiPath.Point`，
 *   见 §10.281 D14「Float32 vs 上游 Float64，接受不修」）。
 *   ⇒ 若这里拿上游的 **Float64** 原始坐标去取整，而端口拿 **Float32** 去取整，
 *     两边会在「恰好落在 .5 边界」的点上差 1 ⇒ **哈希整体不同** ⇒ 门禁恒红，
 *     而**几何其实是对的**（实测：arch02/arch03 有这种点，arch04/box 没有）。
 *   ⇒ 两侧统一到 **Float32** 后比较 ⇒ **精确相等**，且判据**仍有牙**
 *     （算法不同 ⇒ 坐标不同 ⇒ 取整后仍不同）。
 *   ⇒ 明细 dump 里的 `pts` 本来就是 `Math.fround` 过的（见下方 dump 段）⇒ 两侧同源。
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

// --------------------------------------------------------------------------
// 9. 逐个夹具量测
// --------------------------------------------------------------------------
const report = {
  meta: {
    generator: 'scripts/e50_arch02_upstream_probe.js',
    upstream: UPSTREAM,
    upstreamBytes: fs.statSync(UPSTREAM).size,
    patch:
      '① perlin 表初始化：Math.random() → __perlinLcg[]（LCG 种子 ' +
      NOISE_SEED +
      '）；② this.arch01 行内追加 this.__box/__deco/__rail/__roof/__pagroof 暴露（行号不变）',
    seed: SEED,
    noiseSeed: NOISE_SEED,
  },
};

const dumpShapes = {};

for (const f of FIXTURES) {
  const prngSeed = f.prngSeed !== undefined ? f.prngSeed : SEED;
  const res = vm.runInContext(
    '__measure(' + JSON.stringify(f.expr) + ', ' + prngSeed + ')',
    ctx,
  );
  const seq = emissionSequence(res);
  const entry = {
    call: Object.assign({ prngSeed: prngSeed }, f.call),
    kind: f.kind,
    draws: res.draws,
    contourCount: seq.length,
    textCount: seq.filter((s) => s.isText).length,
    pointCount: seq.reduce((a, s) => a + s.pts.length, 0),
    digest: hex(digestOf(seq)),
    structureDigest: hex(structureDigestOf(seq)),
    roundedDigest10: hex(roundedDigestOf(seq, 10)),
    roundedDigest100: hex(roundedDigestOf(seq, 100)),
  };
  report[f.key] = entry;
  dumpShapes[f.key] = seq.map((s, i) => ({
    i: i,
    fil: s.fil,
    str: s.str,
    wid: s.wid,
    n: s.pts.length,
    isText: !!s.isText,
    pts: EMIT_POINTS ? s.pts.map((p) => [Math.fround(p[0]), Math.fround(p[1])]) : undefined,
  }));
}

// --------------------------------------------------------------------------
// 10. 明细 dump
// --------------------------------------------------------------------------
fs.mkdirSync(path.dirname(DUMP_JSON), { recursive: true });
fs.writeFileSync(
  DUMP_JSON,
  JSON.stringify(
    {
      meta: report.meta,
      results: report,
      shapes: dumpShapes,
    },
    null,
    1,
  ),
  'utf8',
);

// --------------------------------------------------------------------------
// 11. 黄金基线
// --------------------------------------------------------------------------
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

console.log('=== upstream Arch.arch02/03/04 probe ===');
console.log('upstream      :', UPSTREAM);
console.log('seed          :', SEED, ' noiseSeed:', NOISE_SEED);
for (const f of FIXTURES) {
  const e = report[f.key];
  console.log('--- ' + f.key, JSON.stringify(e.call));
  console.log('  PRNG draws  :', e.draws);
  console.log('  contours    :', e.contourCount, '(其中 <text> 题字', e.textCount, '条)');
  console.log('  points      :', e.pointCount);
  console.log('  structure   :', e.structureDigest);
  console.log('  rounded0.1  :', e.roundedDigest10);
  console.log('  rounded0.01 :', e.roundedDigest100);
  console.log('  float32     :', e.digest, '(仅供参考：V8/JVM 的超越函数差 1 ULP)');
}
console.log('golden written:', OUT_JSON);
console.log('dump written  :', DUMP_JSON);
