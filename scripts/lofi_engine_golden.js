// E51「像素城市」引擎层逐位基线再生脚本（docs/e51-lofi-city-plan.md §9 P1）。
//
//   在**仓库根**执行：  node scripts/lofi_engine_golden.js
//
// 它在 `vm` 沙箱里跑 `app/src/lofi-upstream/modules/` 里的**上游原文**
// （`js__engine__core.js.js` + `js__engine__display.js.js`，未改动一个字符），
// 把 hash / rng / cycle / step / scroll / env / smooth / wave / bayer / textWidth /
// hex / mix / layout 的输出打印成 Kotlin 常量文件
// `app/src/test/java/com/nasmusic/tv/visualizer/pixel/LofiEngineBaseline.kt`。
//
// ⛔ 基线数字**只许由本脚本再生，不许手抄**（`AGENTS.md`：抄进注释或测试就是 D24 的重演）。
// ⛔ 上游原件是 gitignored 的本机资料 ⇒ 生成物进仓库、原件不进。
//    改了 `visualizer/pixel/` 的数学 ⇒ 重跑本脚本并一起提交再生结果。
//
// 两条自检（防止"假基线"）：
//   1) 沙箱里 `W/H/LOOP` 必须是 480/270/240 —— 上游若改了常量，本脚本立刻报错而不是静默产出；
//   2) `init_core` 没进上下文全局 ⇒ 直接抛（读不到常量时所有函数都会静默返回 NaN，
//      NaN 写进基线就是"看着有数、其实全废"）。
//
// 精度口径：所有 Double 一律以 **IEEE-754 位模式（Long）** 落盘，Kotlin 侧 `Double.fromBits` 还原
// ⇒ 跨 Node/JVM 的十进制打印精度问题不存在。

'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const REPO = path.resolve(__dirname, '..');
const MODS = path.join(REPO, 'app', 'src', 'lofi-upstream', 'modules');
const OUT = path.join(
  REPO, 'app', 'src', 'test', 'java', 'com', 'nasmusic', 'tv', 'visualizer', 'pixel', 'LofiEngineBaseline.kt'
);

function die(msg) {
  process.stderr.write(msg + '\n');
  process.exit(1);
}

// ───────────────────────── 沙箱装载上游原文 ─────────────────────────

const CORE_FILE = 'js__engine__core.js.js';
const DISPLAY_FILE = 'js__engine__display.js.js';
for (const f of [CORE_FILE, DISPLAY_FILE]) {
  if (!fs.existsSync(path.join(MODS, f))) {
    die(`缺少上游模块切片 ${f}（应在 app/src/lofi-upstream/modules/，本机 gitignored 资料）`);
  }
}

// esbuild 的 `__esm` 包装：`var init_x = __esm({ "路径"() { …body… } })`。
// 上游切片保留了这层包装，须提供等价 helper 才能原地运行。
const PRELUDE = `
var __esm = function (deps) {
  var done = {};
  return function () {
    for (var k in deps) { if (!done[k]) { done[k] = true; deps[k](); } }
  };
};
var __export = function (target, defs) {
  for (var k in defs) Object.defineProperty(target, k, { get: defs[k], enumerable: true });
  return target;
};
var __toESM = function (m) { return m; };
`;

const ctx = vm.createContext({ Math, Object, Array, Number, String, JSON, isNaN, isFinite });
function runIn(file) {
  const src = fs.readFileSync(path.join(MODS, file), 'utf8');
  vm.runInContext(PRELUDE + src, ctx, { filename: file });
}
runIn(CORE_FILE);
runIn(DISPLAY_FILE);   // display.js 顶层自己会调 init_core()

const G = (name) => {
  if (!(name in ctx)) die(`沙箱里没有 ${name} —— 上游切片结构变了，基线不可信`);
  return ctx[name];
};
const W = G('W'), H = G('H'), LOOP = G('LOOP');
if (W !== 480 || H !== 270 || LOOP !== 240) {
  die(`上游常量与方案不符：W=${W} H=${H} LOOP=${LOOP}（期望 480/270/240）`);
}

// ───────────────────────── 采样定义 ─────────────────────────

// ⚠️ 必须按**有符号** Long 打印（十进制），不能打无符号十六进制：
//    负 double 的位模式高位置 1，`readBigUInt64LE` 会写出 `0xffff…L` 这种超出 Long 范围的字面量，
//    Kotlin 直接 `Value out of range` 编译失败。
const LONG_MIN = -0x8000000000000000n;
const bits = (v) => {
  if (typeof v !== 'number' || Number.isNaN(v)) die(`基线里出现了 NaN —— 判据函数没跑通：${v}`);
  const b = Buffer.alloc(8);
  b.writeDoubleLE(v, 0);
  const n = b.readBigInt64LE(0);
  return n === LONG_MIN ? 'Long.MIN_VALUE' : n.toString(10);
};

const lines = [];
const emit = (s) => lines.push(s);

function emitDoubleTable(name, comment, values) {
  // ⚠️ `object` 的属性初始化按**声明顺序**执行 ⇒ 位模式数组必须先于解码访问器声明，
  //    否则 Kotlin 报 `Variable 'BITS_xxx' must be initialized`。
  emit(`    /** ${comment} —— 位模式表，解码见下方同名 DoubleArray */`);
  emit(`    private val BITS_${name}: LongArray = longArrayOf(`);
  const per = 4;
  for (let i = 0; i < values.length; i += per) {
    emit('        ' + values.slice(i, i + per).map(bits).join(', ') + ',');
  }
  emit('    )');
  emit('');
  emit(`    /** ${comment} */`);
  emit(`    val ${name}: DoubleArray = DoubleArray(BITS_${name}.size) { i -> Double.fromBits(BITS_${name}[i]) }`);
  emit('');
}

function emitIntTable(name, comment, values) {
  emit(`    /** ${comment} */`);
  emit(`    val ${name}: IntArray = intArrayOf(`);
  const per = 8;
  for (let i = 0; i < values.length; i += per) {
    emit('        ' + values.slice(i, i + per).map((v) => String(v | 0)).join(', ') + ',');
  }
  emit('    )');
  emit('');
}

// ── hash(a,b,c)：三参数扫一遍整数域的代表点 ──
const hashVals = [];
const HASH_CASES = [];
for (const a of [0, 1, 7, 43, 255, 479, 1024, -7, -1]) {
  for (const b of [0, 1, 19, 270]) {
    hashVals.push(G('hash')(a, b, 0));
    HASH_CASES.push([a, b, 0]);
  }
}
for (const c of [1, 61, 4096, -1024]) {         // 第三参非零（撒点里用得上）
  hashVals.push(G('hash')(13, 29, c));
  HASH_CASES.push([13, 29, c]);
}
emitIntTable('HASH_ARGS', `hash(a,b,c) 的入参三元组，与 BITS_HASH_V 同序（${HASH_CASES.length} 组）`,
  HASH_CASES.flat());
emitDoubleTable('HASH_V', 'hash(a,b,c) 的输出（上游原值，位模式还原）', hashVals);

// ── rng(seed)：每粒种子连续 8 次 ──
const RNG_SEEDS = [1, 2, 7, 12345, 4294967295, 2147483648, -7, 987654321];
// 上游 rng 先做 `seed >>> 0` ⇒ 同一粒种子在 Kotlin 侧是 32 位截断后的 Int。
// 基线把**截断后**的 Int 一并落盘，测试直接用它构造 PxRng（⛔ 不许在测试里另抄一份种子）。
emitIntTable('RNG_SEEDS', 'rng 的种子（已按 ToInt32 截断，与 RNG_V 同序：每粒 8 个输出）',
  RNG_SEEDS.map((s) => (s >>> 0) | 0));
const rngVals = [];
for (const s of RNG_SEEDS) {
  const r = G('rng')(s);
  for (let i = 0; i < 8; i++) rngVals.push(r());
}
emitDoubleTable('RNG_V', `rng(seed) 每粒种子连续 8 个输出摊平（种子 ${RNG_SEEDS.join(', ')}）`, rngVals);

// ── cycle / step / scroll / env / wave ──
const frac = G('frac'), clampF = G('clamp'), lerpF = G('lerp'), smoothF = G('smooth');
const wave = G('wave'), step = G('step'), env = G('env'), scroll = G('scroll'), bayer = G('bayer');
const cycle = G('cycle'), inEvent = G('inEvent'), textWidth = G('textWidth'), hex = G('hex');

const T_GRID = [];
for (let i = 0; i < 17; i++) T_GRID.push(-30 + i * 17.5);   // 含负数与 > LOOP 的样本

const cycU = [], cycC = [], stepVals = [], scrollVals = [], waveVals = [];
for (const t of T_GRID) {
  const c = cycle(t, 16, 0.25);
  cycU.push(c.u); cycC.push(c.cyc);
  stepVals.push(step(t, 16));
  scrollVals.push(scroll(t, 3, 120));
  waveVals.push(wave(t, 5, 0.1));
}
emitDoubleTable('CYCLE_U', `cycle(t, 16, 0.25).u（t 取 ${T_GRID.length} 个样本，含负数与超 LOOP）`, cycU);
emitIntTable('CYCLE_C', 'cycle(t, 16, 0.25).cyc，与 CYCLE_U 同序', cycC);
emitIntTable('STEP_V', 'step(t, 16)，与 CYCLE_U 同序', stepVals);
emitIntTable('SCROLL_V', 'scroll(t, 3, 120)，与 CYCLE_U 同序', scrollVals);
emitDoubleTable('WAVE_V', 'wave(t, 5, 0.1)，与 CYCLE_U 同序', waveVals);

// ── smooth / env / clamp / lerp / frac：固定几组参数 ──
const SE_PAIRS = [[0, 1, -0.2], [0, 1, 0.5], [0, 1, 1.7], [2, 5, 2.0], [2, 5, 3.5], [2, 5, 5.0], [2, 5, 9]];
const smoothVals = SE_PAIRS.map(([a, b, v]) => smoothF(a, b, v));
const clampVals = SE_PAIRS.map(([a, b, v]) => clampF(v, a, b));
const lerpVals = SE_PAIRS.map(([a, b, v]) => lerpF(a, b, v));
const env2 = [];
for (const [t, a, b, f] of [[-1, 0, 10, 2], [0, 0, 10, 2], [2, 0, 10, 2], [5, 0, 10, 2], [9, 0, 10, 2],
  [10, 0, 10, 2], [11, 0, 10, 2], [107, 100, 130, 4], [124, 100, 130, 4]]) env2.push(env(t, a, b, f));
const fracVals = [-2.7, -0.2, 0, 0.4, 1.0, 7.25, 240.5].map((v) => frac(v));

const seDesc = SE_PAIRS.map((tr) => '(' + tr.join(',') + ')').join(' ');
emit('    /** smooth/clamp/lerp 共用的 (a, b, v) 三元组：' + seDesc + ' */');
emitDoubleTable('SMOOTH_V', 'smooth(a, b, v)，入参三元组见上一行注释', smoothVals);
emitDoubleTable('CLAMP_V', 'clamp(v, a, b)，与 SMOOTH_V 同序', clampVals);
emitDoubleTable('LERP_V', 'lerp(a, b, v)，与 SMOOTH_V 同序', lerpVals);
emitDoubleTable('ENV_V', 'env(t, a, b, f) 的 9 组输出（t=-1,0,2,5,9,10,11 配 a=0,b=10,f=2；t=107,124 配 a=100,b=130,f=4）', env2);
emitDoubleTable('FRAC_V', 'frac(v)，v 取 -2.7, -0.2, 0, 0.4, 1, 7.25, 240.5', fracVals);

// ── bayer(x,y)：4×4 一周期 + 越界样本 ──
const bayerVals = [];
const BAYER_CASES = [];
for (let y = 0; y < 5; y++) for (let x = 0; x < 5; x++) { bayerVals.push(bayer(x, y)); BAYER_CASES.push(x, y); }
bayerVals.push(bayer(-1, 3)); BAYER_CASES.push(-1, 3);
bayerVals.push(bayer(17, 22)); BAYER_CASES.push(17, 22);
emitIntTable('BAYER_XY', 'bayer 的 (x,y) 入参对（前 25 组是 0..4 × 0..4，末两组是负数/超界）', BAYER_CASES);
emitDoubleTable('BAYER_V', 'bayer(x, y) 输出，与 BAYER_XY 同序', bayerVals);

// ── textWidth(s, gap) ──
const TW_STRINGS = ['A', 'CAPE TOWN', 'TOKYO 2099', ' ', 'AB CD'];
const twVals = [];
for (const s of TW_STRINGS) for (const g of [1, 2]) twVals.push(textWidth(s, g));
emitIntTable('TEXT_WIDTH_V', `textWidth(s, gap)，s 依次 ${TW_STRINGS.map((s) => `"${s}"`).join(' / ')}，每个先 gap=1 再 gap=2`, twVals);

// ── hex() → 三元组，mix() 的通道取整 ──
const HEX_CASES = ['#000000', '#ffffff', '#070814', '#e8f55c', '#f4a261', '#39ff14', '#1c2046'];
const hexFlat = [];
for (const h of HEX_CASES) hexFlat.push(...hex(h));
emitIntTable('HEX_RGB', `hex(${HEX_CASES.map((h) => `"${h}"`).join(', ')}) 的 [r,g,b] 摊平`, hexFlat);

const MIX_PAIRS = [['#000000', '#ffffff'], ['#070814', '#e8f55c'], ['#f4a261', '#39ff14']];
const MIX_T = [0, 0.25, 0.5, 0.75, 1];
const mixVals = [];
for (const [a, b] of MIX_PAIRS) for (const t of MIX_T) {
  const A = hex(a), B = hex(b);
  for (let i = 0; i < 3; i++) mixVals.push(Math.round(lerpF(A[i], B[i], t)));
}
emitIntTable('MIX_RGB', 'mix(a,b,t) 的三通道取整结果（3 对色 × 5 个 t × 3 通道）', mixVals);

// ── layout()：取景算术的逐位对账 ──
// 每一行 = 上游返回的 {sx,sy,sw,sh,dx,dy,dw,dh,bars,band,home}，坐标按**本项目的 Int 接缝**
// 先 Math.round 再落盘（Kotlin 侧只能吃 IntSize/IntOffset，见 PixelLayout.kt 头注）。
const BB = { x: 292, y: 96, w: 64, h: 40 };
const SCENES = [
  { name: '无地标无广告牌（全域）', scene: {}, frame: {} },
  { name: '仅地标', scene: { landmark: [150, 320], landmarkTop: 70 }, frame: {} },
  { name: '地标 + 广告牌入框', scene: { landmark: [150, 320], landmarkTop: 70, billboard: BB }, frame: {} },
  { name: '地标 + 广告牌但关广告牌取景', scene: { landmark: [150, 320], landmarkTop: 70, billboard: BB }, frame: { billboard: false } },
  { name: 'focusX 偏左 + 平移/安全区', scene: { landmark: [40, 120], landmarkTop: 30, focusX: 60 }, frame: {}, pan: 18, inset: { top: 24, bottom: 12 } },
];
const SIZES = [
  [1920, 1080], [1280, 720], [1600, 900], [3840, 2160], [1366, 768],  // 横屏
  [1080, 1920], [720, 1280], [828, 1792],                              // 竖屏
];
const layoutRows = [];
const layoutCases = [];
for (const sc of SCENES) {
  for (const [cw, ch] of SIZES) {
    const r = G('layout')(cw, ch, sc.scene, sc.pan || 0, sc.inset || { top: 0, bottom: 0 }, sc.frame);
    layoutRows.push(
      Math.round(r.sx), Math.round(r.sy), Math.round(r.sw), Math.round(r.sh),
      Math.round(r.dx), Math.round(r.dy), Math.round(r.dw), Math.round(r.dh),
      r.bars ? 1 : 0, r.band ? 1 : 0, Math.round(r.home || 0)
    );
    layoutCases.push(`${sc.name} @ ${cw}x${ch}${sc.pan ? ' pan=' + sc.pan : ''}${sc.inset ? ' inset=' + sc.inset.top + '/' + sc.inset.bottom : ''}`);
  }
}
emit(`    /**`);
emit(`     * layout() 的逐位基线，每行 11 个整数：`);
emit(`     * \`sx, sy, sw, sh, dx, dy, dw, dh, bars, band, home\`（坐标按 Int 接缝取整）。`);
emit(`     * 行序与 LAYOUT_CASES 一致。`);
emit(`     */`);
emit(`    val LAYOUT_ROWS: IntArray = intArrayOf(`);
for (let i = 0; i < layoutRows.length; i += 11) {
  emit('        ' + layoutRows.slice(i, i + 11).join(', ') + ',');
}
emit(`    )`);
emit('');
emit(`    /** LAYOUT_ROWS 的行说明（仅供报告，不参与断言）。 */`);
emit(`    val LAYOUT_CASES: List<String> = listOf(`);
for (let i = 0; i < layoutCases.length; i += 2) {
  emit(`        ${layoutCases.slice(i, i + 2).map((c) => JSON.stringify(c)).join(', ')},`);
}
emit(`    )`);
emit('');

// ── inEvent()：上游吃 [a,b] 数组对，本项目吃 EventWindow ──
const EV = [[10, 20], [100, 120]];
const evVals = [];
const evT = [0, 10, 15, 20, 99, 100, 110, 120, 121];
for (const t of evT) evVals.push(inEvent(t, EV) ? 1 : 0);
emitIntTable('IN_EVENT_V', `inEvent(t, [[10,20],[100,120]])，t 取 ${evT.join(', ')}（1 = 命中）`, evVals);

// ───────────────────────── 落盘 ─────────────────────────

const header = `// ★ GENERATED by scripts/lofi_engine_golden.js —— 禁止手改
//   再生：仓库根执行 \`node scripts/lofi_engine_golden.js\`
//   来源：app/src/lofi-upstream/modules/js__engine__core.js.js + js__engine__display.js.js（上游原文，未改动）
//   用途：docs/e51-lofi-city-plan.md §9 P1 的逐位对账（\`LofiEngineBaselineTest\`）。
//   ⚠️ 上游若改常量/数学，本文件必须由脚本重新再生；手抄数字 = D24 的重演。
//
// 精度口径：Double 一律以 IEEE-754 位模式（Long）存储，读取用 Double.fromBits。

package com.nasmusic.tv.visualizer.pixel

/** E51 引擎层逐位基线（本文件由脚本整体覆写，改动请改脚本）。 */
object LofiEngineBaseline {

`;
const body = header + lines.join('\n') + `    const val GENERATED_BY = "scripts/lofi_engine_golden.js"\n}\n`;
fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, body, 'utf8');
process.stdout.write(`已再生 ${path.relative(REPO, OUT)}（${lines.length} 行数据）\n`);
