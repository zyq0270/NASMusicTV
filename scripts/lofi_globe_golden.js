// E51「像素城市」P2 地球层逐位基线再生脚本（docs/e51-lofi-city-plan.md §6.2 / §6.3 / §9 P2）。
//
//   在**仓库根**执行：  node scripts/lofi_globe_golden.js
//
// 在 `vm` 沙箱里跑 `app/src/lofi-upstream/modules/` 里的**上游原文**
// （`js__ui__globe-land.js.js` + `js__ui__globe-draw.js.js`，一个字符都不改），产出两个文件：
//
//   ① app/src/main/java/com/nasmusic/tv/visualizer/pixel/PixelGlobeLand.kt
//      —— `LAND5` 原样搬入（§6.3：改判后直接搬，自产 Natural Earth 栅格那套方案已作废）
//         + `decodeLand()` 直译。
//   ② app/src/main/java/com/nasmusic/tv/visualizer/pixel/LofiGlobeColors.kt
//      —— RAMPS / DUSK / LIGHTS8 与九枚单色常量（main 源集用的色表，与基线同源同一次再生）。
//   ③ app/src/test/java/com/nasmusic/tv/visualizer/pixel/LofiGlobeBaseline.kt
//      —— 地球数学（hash2 / smooth2 / vec / toView / sunAt / ease / wrap / clampTilt /
//         px2 / rgba3 / BAYER2）、陆地栅格校验数、夜灯生成抽样、以及**两帧整幅位图**逐位基线。
//
// ⛔ 基线数字只许由本脚本再生，不许手抄（AGENTS.md：抄进注释或测试就是 D24 的重演）。
//    色带十六进制串同样从上游原文里**正则抽出**，再与沙箱算出的 RAMPS/DUSK/LIGHTS8 逐个对撞，
//    所以"抄错一个色值"会在再生阶段就红，而不是等到单测。
//
// ## 唯一的表示差异（不知道这条，整幅对账必全红）
// 上游把颜色写进 `new Uint32Array(imageData.data.buffer)` —— 那是 **RGBA 字节缓冲的小端视图**，
// 所以它的 `px2/rgba3` 故意做了 R/B 交换，得到 `0xAABBGGRR`。
// 本项目 `PixelCanvas` 用**非预乘 ARGB**（`0xAARRGGBB`，见 PixelColor.kt 头注）。
// ⇒ 本脚本落盘前把上游 uint32 换回 ARGB（`toArgb`），Kotlin 侧不做任何交换。
// 也就是说：对账的是"最终显示出来的那个颜色"，不是"上游字面量的位模式"。
//
// ## 自检（防"假基线"）
//   1) LAND_W/LAND_H 必须 720/360，`LAND5` 必须是非空字符串；
//   2) `decodeLand()` 每行游程之和必须恰好铺满 720 格；四类直方图之和必须等于 720×360；
//   3) 从原文抽出的十六进制串经 `px2` 必须逐个等于沙箱里的 RAMPS/DUSK/LIGHTS8（防正则抽错）；
//   4) 夜灯数必须 > 0；两帧整幅位图里至少有一帧出现夜灯色（否则 lights 通路是死的）；
//   5) 任何 NaN/undefined 进基线、或整幅位图全 0 ⇒ 直接抛，不静默产出空对账表。
//
// 精度口径：Double 一律以 IEEE-754 **位模式（Long）** 落盘，Kotlin 侧 `Double.fromBits` 还原。

'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const REPO = path.resolve(__dirname, '..');
const MODS = path.join(REPO, 'app', 'src', 'lofi-upstream', 'modules');
const PKG_MAIN = path.join(REPO, 'app', 'src', 'main', 'java', 'com', 'nasmusic', 'tv', 'visualizer', 'pixel');
const PKG_TEST = path.join(REPO, 'app', 'src', 'test', 'java', 'com', 'nasmusic', 'tv', 'visualizer', 'pixel');
const OUT_MAIN = path.join(PKG_MAIN, 'PixelGlobeLand.kt');
const OUT_COLORS = path.join(PKG_MAIN, 'LofiGlobeColors.kt');
const OUT_TEST = path.join(PKG_TEST, 'LofiGlobeBaseline.kt');
const REGISTRY_KT = path.join(PKG_MAIN, 'CityRegistry.kt');

function die(msg) {
  process.stderr.write(msg + '\n');
  process.exit(1);
}

// ───────────────────────── 沙箱装载上游原文 ─────────────────────────

const LAND_FILE = 'js__ui__globe-land.js.js';
const DRAW_FILE = 'js__ui__globe-draw.js.js';
const UI_FILE = 'js__ui__globe.js.js';        // ease / wrap / clampTilt / TILT_MAX 在这份里
for (const f of [LAND_FILE, DRAW_FILE, UI_FILE]) {
  if (!fs.existsSync(path.join(MODS, f))) {
    die(`缺少上游模块切片 ${f}（应在 app/src/lofi-upstream/modules/，本机 gitignored 资料）`);
  }
}

// esbuild 切片保留的 `__esm/__export/__toESM` 包装 ⇒ 须提供等价 shim 才能原地运行。
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

const ctx = vm.createContext({
  Math, Object, Array, Number, String, JSON, RegExp, Error, isNaN, isFinite,
  Date, Infinity, Uint8Array, Uint8ClampedArray, Uint32Array, Int32Array, Float32Array,
});
const drawSrc = fs.readFileSync(path.join(MODS, DRAW_FILE), 'utf8');
function runIn(file, src) {
  vm.runInContext(PRELUDE + (src || fs.readFileSync(path.join(MODS, file), 'utf8')), ctx, { filename: file });
}
runIn(LAND_FILE);
runIn(DRAW_FILE, drawSrc);   // globe-draw 顶层引用 LAND5/LAND_W/LAND_H ⇒ 必须先装 globe-land
runIn(UI_FILE);              // globe.js 顶层只用 RAD（由 globe-draw 提供），函数体里的 document 不在再生时执行

const G = (name) => {
  if (!(name in ctx)) die(`沙箱里没有 ${name} —— 上游切片结构变了，基线不可信`);
  return ctx[name];
};

const LAND_W = G('LAND_W');
const LAND_H = G('LAND_H');
if (LAND_W !== 720 || LAND_H !== 360) die(`上游陆地栅格尺寸与方案不符：${LAND_W}×${LAND_H}（期望 720×360）`);
const LAND5 = G('LAND5');
if (typeof LAND5 !== 'string' || LAND5.length === 0) die('LAND5 不是非空字符串');

const hash2 = req('hash2'), smooth2 = req('smooth2'), vec = req('vec'), toView = req('toView');
const sunAt = req('sunAt'), decodeLand = req('decodeLand'), px2 = req('px2'), rgba3 = req('rgba3');
const ease = req('ease'), wrap = req('wrap'), clampTilt = req('clampTilt'), createGlobeArt = req('createGlobeArt');
function req(n) {
  const f = G(n);
  if (typeof f !== 'function') die(`${n} 在上游切片里不是函数`);
  return f;
}
const RAD = G('RAD');
const TILT_MAX = G('TILT_MAX');
const BAYER2 = G('BAYER2');
const RAMPS = G('RAMPS'), DUSK = G('DUSK'), LIGHTS8 = G('LIGHTS8');
if (!Array.isArray(RAMPS) || RAMPS.length !== 5 || RAMPS.some((r) => r.length !== 7)) {
  die(`RAMPS 形状与方案不符（期望 5×7）：${Array.isArray(RAMPS) ? RAMPS.map((r) => r && r.length).join('/') : typeof RAMPS}`);
}

// ───────────────────────── 颜色：上游 uint32（小端 RGBA）→ ARGB ─────────────────────────

function toArgb(u32) {
  const a = (u32 >>> 24) & 0xFF, b = (u32 >>> 16) & 0xFF, g = (u32 >>> 8) & 0xFF, r = u32 & 0xFF;
  return ((a << 24) | (r << 16) | (g << 8) | b) | 0;
}

// 色带字符串**从上游原文正则抽出**（不手抄），再与沙箱算好的 RAMPS/DUSK/LIGHTS8 逐个对撞。
// ⚠️ `tail` 必须逐条给：RAMPS 的收尾是 `.map((r) => r.map(px2))`，若统一按 `.map(px2)` 找右括号，
//    非贪婪匹配会一路吃到下一条声明（DUSK）的 `]` 才停 ⇒ 抽出 40 个十六进制串。
//    个数对撞只能证明"抽多了"，真正抓住它的是下面与沙箱 RAMPS/DUSK/LIGHTS8 的**逐值**对撞。
function extractArray(prefix, tail) {
  const re = new RegExp('var ' + prefix + ' = (\\[[\\s\\S]*?\\])\\.' + tail);
  const m = re.exec(drawSrc);
  if (!m) die(`上游原文里抽不到 var ${prefix} = [...].${tail} —— 原文结构变了`);
  const hexes = m[1].match(/"#[0-9a-fA-F]{6}"/g);
  if (!hexes) die(`var ${prefix} 里抽不到十六进制串`);
  return hexes.map((s) => s.slice(1, -1));
}
const RAMPS_HEX = extractArray('RAMPS', 'map\\(\\(r\\)');
const DUSK_HEX = extractArray('DUSK', 'map\\(px2\\)');
const LIGHTS_HEX = extractArray('LIGHTS8', 'map\\(px2\\)');
if (RAMPS_HEX.length !== 35 || DUSK_HEX.length !== 5 || LIGHTS_HEX.length !== 3) {
  die(`抽出的色带个数不符：RAMPS=${RAMPS_HEX.length}（应 35）DUSK=${DUSK_HEX.length}（应 5）LIGHTS8=${LIGHTS_HEX.length}（应 3）`);
}
for (let r = 0; r < 5; r++) for (let c = 0; c < 7; c++) {
  const h = RAMPS_HEX[r * 7 + c];
  if (toArgb(px2(h) >>> 0) !== toArgb(RAMPS[r][c] >>> 0)) die(`RAMPS[${r}][${c}] 抽出的 ${h} 与沙箱值不一致`);
}
DUSK_HEX.forEach((h, i) => { if (toArgb(px2(h) >>> 0) !== toArgb(DUSK[i] >>> 0)) die(`DUSK[${i}] 抽出的 ${h} 与沙箱值不一致`); });
LIGHTS_HEX.forEach((h, i) => { if (toArgb(px2(h) >>> 0) !== toArgb(LIGHTS8[i] >>> 0)) die(`LIGHTS8[${i}] 抽出的 ${h} 与沙箱值不一致`); });

// 上游这组单色常量有两种写法：`var X = "#rrggbb"`（后面直接喂 rgba3）和 `var X = px2("#rrggbb")`
// （已经是 uint32）。抽的是**引号里那串十六进制**，两种写法统一走 px2 归一。
function extractSingle(name) {
  const re = new RegExp('var ' + name + ' = (?:px2\\()?"(#[0-9a-fA-F]{6})"');
  const m = re.exec(drawSrc);
  if (!m) die(`上游原文里抽不到 var ${name} = "#……"（或 px2("#……")）`);
  return m[1];
}
const SINGLE_NAMES = ['AIR_DAY', 'AIR_NIGHT', 'STAR2', 'CREAM7', 'CREAM_HI', 'WHITE3', 'LIVE', 'LIVE_HI', 'DIM2'];
const SINGLE_HEX = SINGLE_NAMES.map(extractSingle);
// 后六枚在上游是 `px2("#…")`（沙箱里已是 uint32）⇒ 拿沙箱值对撞抽出的串，防"下标整体错位"。
SINGLE_NAMES.forEach((n, i) => {
  const v = ctx[n];
  if (typeof v !== 'number') return;
  if (toArgb(v >>> 0) !== toArgb(px2(SINGLE_HEX[i]) >>> 0)) die(`单色常量 ${n} 抽出的 ${SINGLE_HEX[i]} 与沙箱值不一致`);
});
// OUTLINE2 也在单色名单里，但基线要单独引用它 ⇒ 仍单独抽一次（同一正则口径）
const OUTLINE_HEX_M = /var OUTLINE2 = px2\("(#[0-9a-fA-F]{6})"\)/.exec(drawSrc);
if (!OUTLINE_HEX_M) die('上游原文里抽不到 OUTLINE2');
const DEFAULT_ACCENT_M = /accent: a = "(#[0-9a-fA-F]{6})"/.exec(drawSrc);
if (!DEFAULT_ACCENT_M) die('上游原文里抽不到 draw() 的默认 accent');
const DEFAULT_ACCENT = DEFAULT_ACCENT_M[1];

// 扁平色表（下标即基线里的 HEX_ARGS）：5 条 ramp（35）+ DUSK(5) + LIGHTS(3) + 单色(9) + OUTLINE + 默认 accent
const HEX_STRINGS = [].concat(RAMPS_HEX, DUSK_HEX, LIGHTS_HEX, SINGLE_HEX, [OUTLINE_HEX_M[1], DEFAULT_ACCENT]);
const HEX_ARGB = HEX_STRINGS.map((h) => toArgb(px2(h) >>> 0));

// ───────────────────────── 城市表（夜灯生成的"附近有城"判据要用） ─────────────────────────

if (!fs.existsSync(REGISTRY_KT)) die('缺少 CityRegistry.kt —— 夜灯基线必须与仓库城市表同源');
const regSrc = fs.readFileSync(REGISTRY_KT, 'utf8');
const cities = [];
const blockRe = /LofiCity\(([\s\S]*?)\n        \),/g;
let mm;
while ((mm = blockRe.exec(regSrc))) {
  const body = mm[1];
  const id = /id = "([^"]*)"/.exec(body);
  const lat = /lat = ([-0-9.eE+]+)/.exec(body);
  const lon = /lon = ([-0-9.eE+]+)/.exec(body);
  if (!id || !lat || !lon) die(`CityRegistry.kt 里有一段 LofiCity(...) 缺 id/lat/lon：\n${body.slice(0, 200)}`);
  const c = { id: id[1], lat: parseFloat(lat[1]), lon: parseFloat(lon[1]) };
  if (!Number.isFinite(c.lat) || !Number.isFinite(c.lon)) die(`城市 ${c.id} 的经纬度不是有限数`);
  c.v = vec(c.lat * RAD, c.lon * RAD);
  cities.push(c);
}
if (cities.length < 40) die(`CityRegistry.kt 解析到的城市数异常：${cities.length}`);

// ───────────────────────── 陆地栅格自检 ─────────────────────────

const map = decodeLand();
if (map.length !== LAND_W * LAND_H) die(`decodeLand 输出长度 ${map.length} ≠ ${LAND_W * LAND_H}`);
const rowFilled = new Array(LAND_H).fill(0);
for (let j = 0; j < LAND_H; j++) for (let i = 0; i < LAND_W; i++) rowFilled[j]++;
const rowSums = [];
for (let j = 0; j < LAND_H; j++) {
  // "铺满"判据用游程长度之和：重跑一遍 decodeLand 的游程累加（与 fill 写入格数同源）
  let sum = 0;
  const row = String(LAND5).split('|')[j] || '';
  for (const r of row.matchAll(/([OGSI])([0-9a-z]+)/g)) sum += parseInt(r[2], 36);
  rowSums.push(sum);
  if (sum !== LAND_W) die(`第 ${j} 行游程之和 ${sum} ≠ ${LAND_W} ⇒ 游程表与行分隔符不匹配`);
}
const classCounts = [0, 0, 0, 0];
for (let k = 0; k < map.length; k++) {
  const t = map[k];
  if (t > 3) die(`解码结果出现未知类别 ${t}`);
  classCounts[t]++;
}
if (classCounts.reduce((a, b) => a + b, 0) !== LAND_W * LAND_H) die('类别直方图总数与栅格不符');
if (classCounts[0] + classCounts[1] === LAND_W * LAND_H) die('陆地/沙/冰全为零 —— 解码没真的填进去');

const LAND_SPOT_ARGS = [];
const LAND_SPOT_V = [];
for (let k = 0; k < 48; k++) {
  const i = (k * 149 + 7) % LAND_W;
  const j = (k * 97 + 11) % LAND_H;
  LAND_SPOT_ARGS.push(i, j);
  LAND_SPOT_V.push(map[j * LAND_W + i]);
}

// ───────────────────────── 夜灯（createGlobeArt 构造期那段循环） ─────────────────────────

function fakeCanvasCtx() {
  const c = {
    canvas: { width: 0, height: 0 },
    img: null,
    createImageData(w, h) { c.img = { data: new Uint8ClampedArray(w * h * 4), width: w, height: h }; return c.img; },
    putImageData(img) { c.img = img; },
  };
  return c;
}

// 构造期那段循环上游不暴露 ⇒ 逐字复制 globe-draw.js:91-111 的原文（同 map、同 hash2、同 cities）。
const lights = (() => {
  const out = [];
  const near = Math.cos(2.2 * RAD);
  for (let j = 0; j < LAND_H; j++) for (let i = 0; i < LAND_W; i++) {
    const t = map[j * LAND_W + i], roll = hash2(i, j);
    if (t !== 1 && t !== 2 || roll >= 0.4) continue;
    const lat = (90 - (j + 0.5) * (180 / LAND_H)) * RAD, lon = ((i + 0.5) * (360 / LAND_W) - 180) * RAD;
    if (Math.abs(lat) > 62 * RAD) continue;
    const v = vec(lat, lon);
    if (!cities.some((c) => c.v[0] * v[0] + c.v[1] * v[1] + c.v[2] * v[2] > near)) {
      if (roll >= 0.05) continue;
      let coast = false;
      for (let dj = -2; dj <= 2 && !coast; dj++) for (let di = -2; di <= 2; di++) {
        const jj = Math.min(LAND_H - 1, Math.max(0, j + dj)), ii = (i + di + LAND_W) % LAND_W;
        if (map[jj * LAND_W + ii] === 0) { coast = true; break; }
      }
      if (roll >= (coast ? 0.05 : 6e-3) * (t === 2 ? 0.3 : 1)) continue;
    }
    out.push({ v, b: 0.45 + hash2(j, i) * 0.55, ph: hash2(i + 7, j + 3) * 6.283 });
  }
  return out;
})();
if (lights.length === 0) die('夜灯生成结果为 0 —— 上游构造期那段循环没跑通');

const LIGHTS_SAMPLE_COUNT = 12;
const LIGHTS_SAMPLE_IDX = [];
const LIGHTS_SAMPLE_V = [];
for (let k = 0; k < LIGHTS_SAMPLE_COUNT; k++) {
  const i = Math.floor((k * (lights.length - 1)) / (LIGHTS_SAMPLE_COUNT - 1));
  LIGHTS_SAMPLE_IDX.push(i);
  const l = lights[i];
  LIGHTS_SAMPLE_V.push(l.v[0], l.v[1], l.v[2], l.b, l.ph);
}

// ───────────────────────── 两帧整幅基线 ─────────────────────────

const KINDS = ['city', 'here', 'big', 'top', 'off'];   // = LofiGlobePinKind.fromIndex 的顺序

function pinsFixture(n, phase, kinds, zMin) {
  const pins = [];
  const count = 14;
  for (let k = 0; k < count; k++) {
    pins.push({
      x: (k * 5.31 + phase) % n,
      y: (k * 7.13 + phase * 2.7) % n,
      z: zMin + (1 - zMin) * (k / (count - 1)),
      kind: kinds[k % kinds.length],
    });
  }
  pins.push({ x: -3, y: 4, z: 0.9, kind: 'city' });           // 左上越界
  pins.push({ x: n + 1, y: n - 2, z: 0.5, kind: 'here' });     // 右下越界 + 呼吸环
  pins.push({ x: n / 2, y: n / 2, z: 0.02, kind: 'big' });      // 阈值下沿：必须不画
  pins.push({ x: n / 2, y: n / 2, z: 0.0201, kind: 'top' });    // 阈值上沿：必须画
  return pins;
}

const SUN_MS = [
  Date.UTC(2026, 0, 1, 0, 0, 0),
  Date.UTC(2026, 2, 20, 11, 30, 0),
  Date.UTC(2026, 5, 21, 18, 0, 0),
  Date.UTC(2026, 6, 4, 3, 7, 42),
  Date.UTC(2026, 11, 21, 6, 0, 0),
  0,
  -1,
  Date.UTC(2024, 1, 29, 23, 59, 59),
];

const FRAME_CFG = [
  {
    label: 'A', n: 64, lon0: 0.35, lat0: 0.30, R: 28.0, sunMs: SUN_MS[1],
    pins: pinsFixture(64, 1.7, KINDS, 0.05), still: true, accent: RAMPS_HEX[0], now: 0,
  },
  {
    label: 'B', n: 64, lon0: -1.25, lat0: -0.20, R: 30.0, sunMs: SUN_MS[4],
    pins: pinsFixture(64, 4.1, ['city', 'here', 'off'], 0.15), still: false, accent: DUSK_HEX[0], now: 800,
  },
];

function renderFrame(cfg) {
  const c = fakeCanvasCtx();
  const art = createGlobeArt(c, cities);
  art.resize(cfg.n);
  art.draw({ lon0: cfg.lon0, lat0: cfg.lat0, R: cfg.R, sun: sunAt(cfg.sunMs), pins: cfg.pins, still: cfg.still, accent: cfg.accent }, cfg.now);
  if (!c.img) die(`上游 draw（帧 ${cfg.label}）没调 putImageData —— 整幅基线拿不到`);
  const u = new Uint32Array(c.img.data.buffer);
  const argb = new Int32Array(u.length);
  let nonZero = 0;
  for (let k = 0; k < u.length; k++) {
    argb[k] = toArgb(u[k]);
    if (u[k] !== 0) nonZero++;
  }
  if (nonZero === 0) die(`帧 ${cfg.label} 整幅为 0 —— 等于对账一张空图`);
  let lightHits = 0;
  for (let k = 0; k < argb.length; k++) for (const col of LIGHTS8) if ((argb[k] >>> 0) === toArgb(col >>> 0) >>> 0) lightHits++;
  return { argb, nonZero, lightHits };
}

const frames = FRAME_CFG.map((cfg) => ({ cfg, ...renderFrame(cfg) }));
for (const r of frames) {
  process.stdout.write(`  帧 ${r.cfg.label}：非零像素 ${r.nonZero}/${r.cfg.n * r.cfg.n}，夜灯像素 ${r.lightHits}\n`);
}
if (frames.every((r) => r.lightHits === 0)) die('两帧里一个夜灯像素都没有 —— lights 通路没跑通，基线不可信');

// ───────────────────────── 数学夹具 ─────────────────────────

const HASH2_ARGS = [];
for (const x of [0, 1, 2, 7, 13, 121, 719, -1, -7, 720, 1000, 4096]) {
  for (const y of [0, 1, 3, 8, 63, 179, 359, -1, -2, 719, 720, 1000]) HASH2_ARGS.push(x, y);
}
const HASH2_V = [];
for (let k = 0; k < HASH2_ARGS.length; k += 2) HASH2_V.push(hash2(HASH2_ARGS[k], HASH2_ARGS[k + 1]));

const SMOOTH2_ARGS = [];
for (const [a, b] of [[-0.3, 0.3], [-0.1, 0.1], [0.25, 0], [-1, 1], [0, 1]]) {
  for (const x of [-2, -0.4, -0.001, 0, 0.001, 0.35, 1, 3]) SMOOTH2_ARGS.push(a, b, x);
}
const SMOOTH2_V = [];
for (let k = 0; k < SMOOTH2_ARGS.length; k += 3) SMOOTH2_V.push(smooth2(SMOOTH2_ARGS[k], SMOOTH2_ARGS[k + 1], SMOOTH2_ARGS[k + 2]));

const VEC_ARGS = [];
for (const lat of [-90, -62, -23.44, 0, 12, 48.857, 90]) {
  for (const lon of [-180, -122.4, -0.13, 0, 2.352, 139.7, 180]) VEC_ARGS.push(lat * RAD, lon * RAD);
}
const VEC_V = [];
for (let k = 0; k < VEC_ARGS.length; k += 2) for (const c of vec(VEC_ARGS[k], VEC_ARGS[k + 1])) VEC_V.push(c);

const TOVIEW_ARGS = [];
const TOVIEW_V = [];
{
  const out = {};
  for (const lat of [-60, -12, 0, 23.5, 70]) {
    for (const lon of [-170, -40, 0, 60, 150]) {
      const p = vec(lat * RAD, lon * RAD);
      for (const pair of [[0, 0], [0.35, 0.3], [-1.25, -0.2], [2.99, 1.22], [-3.1, -1.2]]) {
        const r = toView(p, pair[0], pair[1], out);
        TOVIEW_ARGS.push(p[0], p[1], p[2], pair[0], pair[1]);
        TOVIEW_V.push(r.u, r.v, r.z);
      }
    }
  }
}

const EASE_ARGS = [-1, -0.001, 0, 0.125, 0.5, 0.75, 0.999, 1, 1.5];
const EASE_V = EASE_ARGS.map((t) => ease(t));
const WRAP_ARGS = [-7, -3.2, -Math.PI, -1, 0, 1, Math.PI, 3.2, 7, 12];
const WRAP_V = WRAP_ARGS.map((a) => wrap(a));
const CLAMP_ARGS = [-2, -1.3, -TILT_MAX - 0.01, -TILT_MAX, -0.5, 0, 0.7, TILT_MAX, TILT_MAX + 0.01, 2];
const CLAMP_V = CLAMP_ARGS.map((a) => clampTilt(a));

const RGBA_ARGS = [];
const RGBA_V = [];
for (const h of [SINGLE_HEX[2] /* STAR2 */, SINGLE_HEX[0] /* AIR_DAY */, SINGLE_HEX[1] /* AIR_NIGHT */, SINGLE_HEX[4] /* WHITE3 */]) {
  for (const a of [0, 0.001, 0.1, 0.35, 0.5, 0.7, 0.999, 1]) {
    RGBA_ARGS.push(HEX_STRINGS.indexOf(h), a);
    RGBA_V.push(toArgb(rgba3(h, a) >>> 0));
  }
}

// ───────────────────────── ① PixelGlobeLand.kt ─────────────────────────

const today = new Date().toISOString().slice(0, 10);
const M = [];
const em = (s) => M.push(s);

em('// ★ GENERATED by scripts/lofi_globe_golden.js —— 禁止手改（改陆地栅格/解码请重跑脚本并一起提交再生结果）');
em(`//   再生时间：${today}`);
em('//   来源：上游原文 app/src/lofi-upstream/modules/js__ui__globe-land.js.js（LAND5 逐字节搬运）');
em('//         + js__ui__globe-draw.js.js 里的 decodeLand()（直译）。方案 §6.3：改判后直接搬，不再自产栅格。');
em('//   ⛔ 上游 JS 原件不进 git（.gitignore `app/src/lofi-upstream/`），只有本文件进 main 源集。');
em('package com.nasmusic.tv.visualizer.pixel');
em('');
em('/** 陆地栅格宽（等距圆柱投影的经度格数），上游 `LAND_W`。 */');
em(`const val LAND_W: Int = ${LAND_W}`);
em('');
em('/** 陆地栅格高（纬度格数），上游 `LAND_H`。 */');
em(`const val LAND_H: Int = ${LAND_H}`);
em('');
em('/** 类别：`"OGSI".indexOf(v)` ⇒ 0=海、1=陆、2=沙、3=冰。 */');
em('const val LAND_OCEAN = 0');
em('const val LAND_LAND = 1');
em('const val LAND_SAND = 2');
em('const val LAND_ICE = 3');
em('');
em('/** 与上游同源的类别字符表（下标即类别值）。 */');
em('val OGSI: String = "OGSI"');
em('');
em(`/** \`LAND5\`：720×360 栅格的 base-36 游程编码，\`|\` 分行；上游原样，长度 ${LAND5.length}。 */`);
em('const val LAND5: String =');
const CHUNK = 100;
for (let i = 0; i < LAND5.length; i += CHUNK) {
  const piece = LAND5.slice(i, i + CHUNK).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  em(`    "${piece}"${i + CHUNK < LAND5.length ? ' +' : ''}`);
}
em('');
em('/**');
em(' * 直译上游 \`decodeLand()\`（`js/ui/globe-draw.js:27-38`）：\`|\` 分行、\`/([OGSI])([0-9a-z]+)/g\` 取游程、');
em(' * \`parseInt(n,36)\` 展开成类别值。输出长度恒 \`LAND_W*LAND_H\`，值域 0..3。');
em(' *');
em(' * ⚠️ 与上游的两处口径差异（都由 \`LofiGlobeBaselineTest\` 的逐格对账兜住）：');
em(' *  1. 上游 \`Uint8Array\` → 这里 \`ByteArray\`（值 0..3，不涉符号）；');
em(' *  2. 上游 \`map.fill(v, i, i+len)\` 越界会静默截断 ⇒ 这里 \`coerceAtMost\` 同样截断。上游数据逐行恰好');
em(' *     铺满 720 格（再生脚本自检第 2 条），所以正常输入下两者等价。');
em(' */');
em('fun decodeLand5(text: String = LAND5): ByteArray {');
em('    val out = ByteArray(LAND_W * LAND_H)');
em('    val re = Regex("([OGSI])([0-9a-z]+)")');
em('    val rows = text.split("|")');
em('    val rowsN = minOf(rows.size, LAND_H)');
em('    for (j in 0 until rowsN) {');
em('        var i = j * LAND_W');
em('        for (mt in re.findAll(rows[j])) {');
em('            val v = OGSI.indexOf(mt.groupValues[1])');
em('            val len = mt.groupValues[2].toInt(36)');
em('            val end = minOf(i + len, out.size)');
em('            if (i < end) out.fill(v.toByte(), i, end)');
em('            i += len');
em('        }');
em('    }');
em('    return out');
em('}');
em('');
fs.writeFileSync(OUT_MAIN, M.join('\n') + '\n', 'utf8');

// ───────────────────────── ② LofiGlobeColors.kt（main 源集的色表） ─────────────────────────

const C = [];
const ec = (s) => C.push(s);
/** ARGB → `0xAARRGGBB.toInt()` 字面量（Kotlin 里 >0x7FFFFFFF 的十六进制字面量必须 .toInt()）。 */
const argbLit = (v) => `0x${(v >>> 0).toString(16).toUpperCase().padStart(8, '0')}.toInt()`;

// 上游 RAMPS 每行后面挂一条尾注（`js/ui/globe-draw.js:64-73`），类别值 t 就是行下标：
// O/G/S/I → 0/1/2/3，海岸线再把 t 顶成 4 ⇒ 第 5 行是"近岸浅水"。
const RAMP_LABELS = ['海洋 ocean（t=0）', '陆地 land（t=1）', '沙地 sand（t=2）', '冰原 ice（t=3）', '近岸浅水 shallow water（t=4，海岸线覆盖值）'];

ec('// ★ GENERATED by scripts/lofi_globe_golden.js —— 禁止手改（改色带必须重跑脚本并一起提交再生结果）');
ec(`//   再生时间：${today}`);
ec('//   来源：上游原文 app/src/lofi-upstream/modules/js__ui__globe-draw.js.js 的 RAMPS / DUSK /');
ec('//         LIGHTS8 与九枚单色常量（§6.2 改判后**直接搬同一组色值**，不再另取色）。');
ec('//   ⚠️ 每个字面量都已按本项目的**非预乘 ARGB** 落盘：上游那层 `Uint32Array` 小端 RGBA 视图的');
ec('//      R/B 交换由再生脚本吸收（脚本头注「唯一的表示差异」），Kotlin 侧不许再做第二次交换。');
ec('//   ⛔ 上游 JS 原件不进 git（.gitignore `app/src/lofi-upstream/`），只有本文件进 main 源集。');
ec('package com.nasmusic.tv.visualizer.pixel');
ec('');
ec('/**');
ec(' * 地球层的色表（方案 §6.2）。5 条 7 级 ramp 用**行下标 = 陆地类别值**索引，');
ec(' * `DUSK` 用同一行下标取"晨昏线色"，`LIGHTS` 用亮度分档（0 暗 / 1 中 / 2 亮）。');
ec(' */');
ec('object LofiGlobeColors {');
ec('');
ec('    /** 上游 `RAMPS`：5 行 × 7 级。行 = 陆地类别（+ 4 表示近岸浅水），列 = 明度档位。 */');
ec('    val RAMPS: Array<IntArray> = arrayOf(');
for (let r = 0; r < 5; r++) {
  const row = [];
  for (let c2 = 0; c2 < 7; c2++) row.push(argbLit(HEX_ARGB[r * 7 + c2]));
  ec(`        intArrayOf(${row.join(', ')})${r < 4 ? ',' : ''} // ${RAMP_LABELS[r]}`);
}
ec('    )');
ec('');
ec('    /** 上游 `DUSK`：晨昏线附近按抖动概率改用的 5 色（下标同 [RAMPS] 的行）。 */');
ec(`    val DUSK: IntArray = intArrayOf(${DUSK_HEX.map((h, i) => argbLit(HEX_ARGB[35 + i])).join(', ')})`);
ec('');
ec('    /** 上游 `LIGHTS8`：夜灯的三档亮度（暗 / 中 / 亮）。 */');
ec(`    val LIGHTS: IntArray = intArrayOf(${LIGHTS_HEX.map((h, i) => argbLit(HEX_ARGB[40 + i])).join(', ')})`);
ec('');
ec('    /** 大气圈（昼侧 / 夜侧）与星空底色。 */');
SINGLE_NAMES.forEach((n, i) => {
  ec(`    val ${n}: Int = ${argbLit(HEX_ARGB[43 + i])}`);
});
ec('');
ec('    /** pin 描边色（上游 `OUTLINE2`）。 */');
ec(`    val OUTLINE: Int = ${argbLit(HEX_ARGB[52])}`);
ec('');
ec('    /** `draw()` 缺省 accent（上游形参默认值 `#ffc86e`）与它的十六进制原形。 */');
ec(`    const val DEFAULT_ACCENT_HEX: String = "${DEFAULT_ACCENT}"`);
ec(`    val DEFAULT_ACCENT: Int = ${argbLit(HEX_ARGB[53])}`);
ec('');
ec('}');
ec('');
fs.writeFileSync(OUT_COLORS, C.join('\n') + '\n', 'utf8');

// ───────────────────────── ③ LofiGlobeBaseline.kt ─────────────────────────

const K = [];
const e = (s) => K.push(s);
const LONG_MIN = -0x8000000000000000n;
function bitLiteral(v, what) {
  if (typeof v !== 'number' || Number.isNaN(v)) die(`基线里出现了 NaN（${what}）：判据函数没跑通，值=${v}`);
  const b = Buffer.alloc(8);
  b.writeDoubleLE(v, 0);
  const n = b.readBigInt64LE(0);
  return n === LONG_MIN ? 'Long.MIN_VALUE' : n.toString(10);
}

/** 位模式表 + 解码访问器（⚠️ object 的属性初始化按**声明顺序**执行 ⇒ BITS_ 必须先声明）。 */
function doubleTable(name, comment, values, perLine = 4) {
  e(`    /** ${comment} —— 位模式表，解码见下方同名 DoubleArray */`);
  e(`    private val BITS_${name}: LongArray = longArrayOf(`);
  for (let i = 0; i < values.length; i += perLine) {
    const chunk = values.slice(i, i + perLine).map((v) => bitLiteral(v, name));
    e(`        ${chunk.join(', ')}${i + perLine < values.length ? ',' : ''}`);
  }
  e('    )');
  e('');
  e(`    /** ${comment} */`);
  e(`    val ${name}: DoubleArray = DoubleArray(BITS_${name}.size) { i -> Double.fromBits(BITS_${name}[i]) }`);
  e('');
}
function intTable(name, comment, values, perLine = 8, sink = K, indent = '    ') {
  if (comment) sink.push(`    /** ${comment} */`);
  sink.push(`${indent}val ${name}: IntArray = intArrayOf(`);
  for (let i = 0; i < values.length; i += perLine) {
    const chunk = values.slice(i, i + perLine).map((v) => String(v | 0));
    sink.push(`        ${chunk.join(', ')}${i + perLine < values.length ? ',' : ''}`);
  }
  sink.push('    )');
  sink.push('');
}

e('// ★ GENERATED by scripts/lofi_globe_golden.js —— 禁止手改（改地球数学请重跑脚本并一起提交再生结果）');
e(`//   再生时间：${today}`);
e('//   来源：上游原文 app/src/lofi-upstream/modules/js__ui__globe-draw.js.js 在 `vm` 沙箱里跑出来的输出。');
e('//   ⚠️ 数字只许由脚本再生（AGENTS.md 的 D24 教训）；颜色已换算成**本项目的非预乘 ARGB**，');
e('//      上游那层小端 RGBA 视图的 R/B 交换由脚本吸收（见脚本头注「唯一的表示差异」）。');
e('package com.nasmusic.tv.visualizer.pixel');
e('');
e('/** 地球层逐位基线（方案 §9 P2）。 */');
e('object LofiGlobeBaseline {');
e('');
intTable('HASH2_ARGS', 'hash2(x,y) 的入参对（每 2 个一组），与 HASH2_V 同序', HASH2_ARGS, 6);
doubleTable('HASH2_V', 'hash2(x,y) 的输出（上游原值，取值域 [0,1)）', HASH2_V, 6);
doubleTable('SMOOTH2_ARGS', 'smooth2(a,b,x) 的入参三元组（含 a>b 的反向区间）', SMOOTH2_ARGS, 4);
doubleTable('SMOOTH2_V', 'smooth2(a,b,x) 的输出', SMOOTH2_V, 6);
doubleTable('VEC_ARGS', 'vec(latRad, lonRad) 的入参对（度已按上游 RAD 折成弧度）', VEC_ARGS, 4);
doubleTable('VEC_V', 'vec() 的输出三元组摊平', VEC_V, 4);
doubleTable('TOVIEW_ARGS', 'toView(p0,p1,p2, lon0, lat0) 的入参五元组摊平', TOVIEW_ARGS, 4);
doubleTable('TOVIEW_V', 'toView() 的输出 (u,v,z) 三元组摊平', TOVIEW_V, 4);
e('    /** sunAt(ms) 的取样时刻（`Date.UTC` 口径，含 0 与 -1 两个边界）。 */');
e(`    val SUN_MS: LongArray = longArrayOf(${SUN_MS.map((v) => String(BigInt.asIntN(64, BigInt(v)))).join(', ')})`);
e('');
doubleTable('SUN_V', 'sunAt(ms) 的输出三元组 (x,y,z) 摊平，与 SUN_MS 同序', [].concat(...SUN_MS.map((ms) => sunAt(ms))), 4);
doubleTable('EASE_ARGS', 'ease(t) 的入参（含越界 -1 与 1.5）', EASE_ARGS, 6);
doubleTable('EASE_V', 'ease(t) 的输出', EASE_V, 6);
doubleTable('WRAP_ARGS', 'wrap(a) 的入参（跨过 ±π 的多圈）', WRAP_ARGS, 4);
doubleTable('WRAP_V', 'wrap(a) 的输出（恒在 [-π,π]）', WRAP_V, 4);
doubleTable('CLAMP_ARGS', 'clampTilt(a) 的入参（含 ±TILT_MAX 与越界）', CLAMP_ARGS, 4);
doubleTable('CLAMP_V', 'clampTilt(a) 的输出（TILT_MAX = 70°）', CLAMP_V, 4);
doubleTable('BAYER2_V', 'BAYER2：地球用的 4×4 有序抖动阈值 `(v+0.5)/16`', BAYER2, 4);
intTable('HEX_V', 'px2(hex) 换算成 ARGB 后的颜色；下标即 `LofiGlobe` 的色表展平顺序', HEX_ARGB, 6);
e('    /** 上游色表的十六进制串（与 HEX_V 同序，测试用来对撞 Kotlin 侧的字符串表）。 */');
e(`    val HEX_STRINGS: List<String> = listOf(${HEX_STRINGS.map((h) => `"${h}"`).join(', ')})`);
e('');
e('    /** `draw()` 的默认 accent（上游形参默认值），HEX_STRINGS 的最后一项。 */');
e(`    const val DEFAULT_ACCENT: String = "${DEFAULT_ACCENT}"`);
e('');
intTable('RGBA_HEX_IDX', 'rgba3 入参的色串下标（与 RGBA_A 同序）', RGBA_ARGS.filter((_, i) => i % 2 === 0), 6);
doubleTable('RGBA_A', 'rgba3 入参的 alpha', RGBA_ARGS.filter((_, i) => i % 2 === 1), 6);
intTable('RGBA_V', 'rgba3(hex, a) 换算成 ARGB 后的颜色（与 RGBA_A 同序）', RGBA_V, 6);
e('    /** 夜灯总数（`createGlobeArt` 构造期那段循环的输出长度）。 */');
e(`    const val LIGHTS_COUNT: Int = ${lights.length}`);
e('');
e('    /** 城市表条数（基线与仓库城市表同源的证据）。 */');
e(`    const val CITY_COUNT: Int = ${cities.length}`);
e('');
intTable('LIGHTS_SAMPLE_IDX', '夜灯数组的等距抽样下标', LIGHTS_SAMPLE_IDX, 6);
doubleTable('LIGHTS_SAMPLE_V', '抽样夜灯的 (v0,v1,v2,b,ph) 五元组摊平', LIGHTS_SAMPLE_V, 4);
intTable('LAND_ROW_SUMS', '每行游程长度之和（必须恒等于 LAND_W=720）', rowSums, 10);
intTable('LAND_CLASS_COUNTS', '四类（海/陆/沙/冰）像素总数，和为 720×360', classCounts, 4);
intTable('LAND_SPOT_ARGS', '抽查点 (i,j) 对', LAND_SPOT_ARGS, 8);
intTable('LAND_SPOT_V', '抽查点类别值 map[j*LAND_W+i]（与 LAND_SPOT_ARGS 同序）', LAND_SPOT_V, 12);
e('    /** 整幅位图基线的边长（`art.resize(N)` 的 N）。 */');
e(`    const val FRAME_N: Int = ${FRAME_CFG[0].n}`);
e('');
e('    /** pin 的 kind 顺序（= 上游 `{here,big,top,off,city}` 查表的本项目展平）。 */');
e(`    val PIN_KINDS: List<String> = listOf(${KINDS.map((k) => `"${k}"`).join(', ')})`);
e('');
const BIG = [];
for (const r of frames) {
  const c = r.cfg;
  const sunVec = sunAt(c.sunMs);
  const argsD = [c.lon0, c.lat0, c.R, sunVec[0], sunVec[1], sunVec[2]];
  doubleTable(`FRAME${c.label}_ARGS`, `帧 ${c.label} 入参：[0]=lon0 [1]=lat0 [2]=R [3..5]=sun（sun 只供交叉核对，绘制用 SUN_MS 现算）`, argsD, 4);
  e(`    /** 帧 ${c.label} 其余入参：still=${c.still} accent 下标=${HEX_STRINGS.indexOf(c.accent)} now=${c.now} */`);
  e(`    /** 帧 ${c.label} 的太阳时刻（epoch ms）：本项目把 sun 收进 \`LofiGlobeState.sunMs\`，由画布自己算。 */`);
  e(`    const val FRAME${c.label}_SUN_MS: Long = ${String(BigInt.asIntN(64, BigInt(c.sunMs)))}L`);
  e(`    const val FRAME${c.label}_STILL: Boolean = ${c.still}`);
  e(`    const val FRAME${c.label}_ACCENT_INDEX: Int = ${HEX_STRINGS.indexOf(c.accent)}`);
  e(`    const val FRAME${c.label}_NOW: Long = ${c.now}L`);
  e('');
  const xyz = [];
  const kinds = [];
  for (const p of c.pins) { xyz.push(p.x, p.y, p.z); kinds.push(KINDS.indexOf(p.kind)); }
  doubleTable(`FRAME${c.label}_PIN_XYZ`, `帧 ${c.label} 的 pin 坐标三元组摊平（与 PIN_KIND 同序）`, xyz, 4);
  intTable(`FRAME${c.label}_PIN_KIND`, `帧 ${c.label} 的 pin kind（下标进 PIN_KINDS）`, kinds, 8);
  // ⚠️ 整幅位图（n² 个 ARGB）**不放进主 object**：一张 64² 的 intArrayOf 就要 ~16 KB 字节码，
  //    两张加上其余表会直接撑爆 `<clinit>` 的 64 KB 方法上限（K2JVM「Method too large」）。
  //    ⇒ 每帧单独一个文件级 private object，各自一个 `<clinit>`。
  const big = [];
  big.push(`/** 帧 ${c.label} 的整幅位图（ARGB 非预乘，行优先，${c.n}×${c.n}）——独立 object，见上方注释。 */`);
  big.push(`private object LofiGlobeFrame${c.label} {`);
  big.push('');
  intTable('PX', '', Array.from(r.argb), 8, big);
  big.push('}');
  big.push('');
  BIG.push(big.join('\n'));
  e(`    /** 帧 ${c.label} 的整幅位图（ARGB 非预乘，行优先，${c.n}×${c.n}）。 */`);
  e(`    val FRAME${c.label}_PX: IntArray = LofiGlobeFrame${c.label}.PX`);
  e('');
}
e('}');
e('');
for (const b of BIG) K.push(b);
e('');
fs.writeFileSync(OUT_TEST, K.join('\n') + '\n', 'utf8');

process.stdout.write(
  `已再生：\n  ${path.relative(REPO, OUT_MAIN)}（LAND5 ${LAND5.length} 字符）\n` +
  `  ${path.relative(REPO, OUT_COLORS)}（色表 ${HEX_STRINGS.length} 项）\n` +
  `  ${path.relative(REPO, OUT_TEST)}（色表 ${HEX_STRINGS.length}、夜灯 ${lights.length}、城市 ${cities.length}、两帧 ${FRAME_CFG[0].n}²）\n`
);
