// E50「千里江山」移植层 —— 上游 shan-shui-inf 实跑基准生成器。
//
// 目的：
//   1) 把上游 `shan-shui-inf-main/index.html` 的原始 JS **喂进 node**（只读，不修改），
//      实跑上游的 `Mount.rock` 与 `mountplanner`，产出本仓移植层门禁要用的**黄金基线**。
//   2) 黄金基线落盘到 `app/src/test/resources/qianli/shan_shui_upstream_golden.json`，
//      Kotlin 门禁（ShanShuiPortGateTest）只**读**该文件 ⇒ 测试里不出现任何手抄的上游数字。
//
// 用法（从仓库根跑）：
//   node scripts/qianli_upstream_ref.js            # 重新生成黄金基线
//   node scripts/qianli_upstream_ref.js --check     # 只校验（漂移则退出码 1）
//
// 上游原件路径：
//   默认 `D:/hxzhang/MyGithubSoftware/NasAudio/shan-shui-inf-main/index.html`
//   可用环境变量 `SHANSHUI_UPSTREAM` 覆盖。
//
// ⛔ 本文件**只读**上游 index.html；⛔ 不修改上游任何字节。
//
// ── 与移植层的对齐约定（全部在本文件里显式写死，避免"隐式约定"）──
//   · PRNG：上游 `Math.random` 被全局覆写成 `Prng.next` ⇒ 本文件用上游**原版** `Prng`，
//     种子 = 157（与 Kotlin 侧 `ShanShuiPrng.global.seed(157)` 逐字一致）。
//   · perlin 表：上游 `Noise` 默认用 `Math.random()` 填表（不可复现）⇒ 本文件改用上游
//     **自带的** `Noise.noiseSeed(20250101)`（上游 `:177-202` 的 LCG 建表路径），
//     与 Kotlin 侧 `ShanShuiNoise.noiseSeed(20250101.0)` 逐字一致。
//     ⚠️ `noiseSeed` 在上游是死代码，但它**确实在上游源码里**且两端同路径 ⇒ 比对有效。
//   · `mountplanner` 的 **boat 分支**（上游 `:3806-3811`）：本仓移植层**没有**船
//     （已接受偏差 O7，见 `ShanShuiPlanner.kt` 文件头）⇒ 端口不抽那几次随机。
//     本文件**照跑上游原版**（含 boat），并单独统计出 boat 分支消耗的抽取次数
//     （= 「含 boat」与「去掉 boat」两次同种子运行的抽取数之差），
//     由 Kotlin 侧在两次 `plan()` 之间**补抽同样次数**⇒ 两端随机流重新对齐。
const fs = require("fs");
const path = require("path");

const UPSTREAM = process.env.SHANSHUI_UPSTREAM ||
  "D:/hxzhang/MyGithubSoftware/NasAudio/shan-shui-inf-main/index.html";
const REPO = path.resolve(__dirname, "..");
const OUT_DIR = path.join(REPO, "app/src/test/resources/qianli");
const OUT_FILE = path.join(OUT_DIR, "shan_shui_upstream_golden.json");

const PRNG_SEED = 157;
const NOISE_SEED = 20250101;

// ── 上游沙箱 ──────────────────────────────────────────────────────────
// DOM / window 的最小桩（上游只在**加载时**碰它们；本文件只调用纯几何函数）。
// ⚠️ 沙箱内用**局部** `console` 静默上游的 console.log（不改全局 console）。
const SANDBOX_STUB = `
const document = {
  getElementById: () => ({ setAttribute(){}, style:{}, innerHTML:"", value:0 }),
  createElement: () => ({ style:{}, click(){} }),
  body: { appendChild(){}, removeChild(){}, scrollTo(){} },
  getElementsByTagName: () => [{ style:{} }],
  addEventListener(){},
};
const window = {
  addEventListener(){}, scrollX:0, scrollY:0, location:{ href:"x" },
  btoa: (s) => Buffer.from(s, "binary").toString("base64"),
};
const console = { log: function(){}, warn: function(){}, error: function(){} };
// ⚠️ 上游会写 Math.random = ...（全局覆写）⇒ 必须给沙箱一个**局部** Math，
//    否则第二个沙箱会抢走第一个沙箱的随机流（两次运行互串）。
const Math = Object.create(globalThis.Math);
`;

/** 取上游前 6 个 <script> 块（Prng / Noise / poly / stroke / Mount / MEM / mountplanner）。 */
function upstreamLib() {
  const html = fs.readFileSync(UPSTREAM, "utf8");
  const scripts = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
  return scripts.slice(0, 6).join("\n");
}

function loadUpstream() {
  const lib = upstreamLib();
  // 捕获 poly()/stroke() 的**入参点列**（上游只返回 SVG 字符串，点列拿不到）。
  // ⚠️ poly/stroke 是函数声明 ⇒ 在同一作用域里重新赋值即可被 Mount.rock 看到。
  const patch = `
var __cap = [];
var __origPoly = poly;
var __origStroke = stroke;
poly = function(plist, args) {
  __cap.push({ k: "poly", p: plist.map(function(q){ return [q[0], q[1]]; }) });
  return __origPoly(plist, args);
};
stroke = function(ptlist, args) {
  __cap.push({ k: "stroke", p: ptlist.map(function(q){ return [q[0], q[1]]; }) });
  return __origStroke(ptlist, args);
};
`;
  const api = new Function(
    SANDBOX_STUB + "\n" + lib + "\n" + patch +
    "\nreturn { Mount, MEM, Noise, Prng, mountplanner, chunkloader," +
    " cap: __cap, resetCap: function(){ __cap.length = 0; } };",
  )();
  return api;
}

// ── PRNG 抽取计数（包住上游的 Prng.next ⇒ 每次 Math.random 都过这里）──
function withDrawCounter(api) {
  const orig = api.Prng.next;
  const counter = { n: 0 };
  api.Prng.next = function () {
    counter.n++;
    return orig();
  };
  return counter;
}

// ── 夹具 ─────────────────────────────────────────────────────────────
const ROCK_FIXTURES = [
  { name: "default", hei: 80, wid: 100, seed: 7, tex: 40, sha: 10 },
  { name: "smallRock", hei: 20, wid: 20, seed: 3, tex: 40, sha: 2 },
  { name: "bigRock", hei: 40, wid: 50, seed: 11, tex: 40, sha: 5 },
  { name: "wideRock", hei: 60, wid: 35, seed: 0, tex: 40, sha: 10 },
];

const PLAN_SEGMENTS = [
  { xmin: 0, xmax: 500 },
  { xmin: 500, xmax: 1000 },
];

// 规划夹具 B：**同样不重叠**，但落在**会产出 mount** 的 x 区间
//（上游 perlin 表下 x∈[2000,2500) 有 17 座山 ⇒ planmtx 真的被标记过）。
const PLAN_MOUNT_SEGMENTS = [
  { xmin: 2000, xmax: 2500 },
  { xmin: 2500, xmax: 3000 },
];

// 规划夹具 C：**重叠**区间连调两次 ⇒ 只有「跨调用累积的全局 planmtx」才可能对上上游
//（上游 MEM.planmtx 是模块级全局；本仓移植层此前是每次调用新建的局部数组）。
const PLAN_OVERLAP = [
  { xmin: 2000, xmax: 2500 },
  { xmin: 2000, xmax: 2500 },
];

// ── rock ─────────────────────────────────────────────────────────────
function runRock(api) {
  const out = [];
  for (const f of ROCK_FIXTURES) {
    api.resetCap();
    api.Mount.rock(0, 0, f.seed, {
      hei: f.hei, wid: f.wid, tex: f.tex, sha: f.sha,
    });
    const cap = api.cap;
    const white = cap.find((c) => c.k === "poly");
    const outline = cap.find((c) => c.k === "stroke");
    if (!white || !outline) throw new Error("rock 捕获失败: " + f.name);
    out.push({
      name: f.name,
      hei: f.hei,
      wid: f.wid,
      seed: f.seed,
      tex: f.tex,
      sha: f.sha,
      // 上游 WHITE BG：poly(ptlist[0].concat([[0,0]])) ⇒ 51 点（50 + 追加的 [0,0]）
      whiteBg: white.p,
      // 上游 OUTLINE：stroke(ptlist[0].map(+xoff/+yoff)) ⇒ 50 点（xoff=yoff=0 ⇒ 未偏移）
      outline: outline.p,
    });
  }
  return out;
}

// ── mountplanner ─────────────────────────────────────────────────────
function planEntries(reg) {
  // 端口没有 boat 分支 ⇒ boat 条目单独放一份，比对时只用非 boat 的部分。
  return reg.map((r) => ({ tag: r.tag, x: r.x, y: r.y, h: r.h }));
}

/**
 * 跑一组规划分段。
 * @param resetPlanmtxPerCall true ⇒ 每次调用前把 `MEM.planmtx` 清空
 *        （= 本仓移植层**修改前**的「每次调用新建局部数组」口径 ⇒ 负向自证用）。
 */
function runPlanOnce(api, counter, segments, resetPlanmtxPerCall) {
  api.Prng.seed(PRNG_SEED);
  api.Noise.noiseSeed(NOISE_SEED);
  counter.n = 0;
  // 上游页面加载时 `MEM.planmtx` 是空的 ⇒ 每次「跑一遍」都从干净状态开始。
  api.MEM.planmtx = [];
  const perCall = [];
  for (const s of segments) {
    if (resetPlanmtxPerCall) api.MEM.planmtx = [];
    const before = counter.n;
    const reg = api.mountplanner(s.xmin, s.xmax);
    perCall.push({
      xmin: s.xmin,
      xmax: s.xmax,
      entries: planEntries(reg),
      draws: counter.n - before,
    });
  }
  return perCall;
}

// boat 分支消耗的抽取次数 = 「含 boat 的运行」−「去掉 boat 的运行」（同种子、同分段）。
// ⚠️ 必须是**差值**：去掉 boat 之后的抽取数 = 计划部分本身的抽取数（= 端口 plan() 的抽取数）。
const BOAT_LOOP = `    for (var i = xmin; i < xmax; i += xstep) {
      if (Math.random() < 0.2) {
        var r = { tag: "boat", x: i, y: 300 + Math.random() * 390 };
        chadd(r, 400);
      }
    }`;

function boatDrawsOf(api, counter, segments) {
  const lib = upstreamLib();
  if (!lib.includes(BOAT_LOOP)) {
    throw new Error("未找到上游 boat 循环原文，无法统计其随机抽取数");
  }
  const libNoBoat = lib.replace(BOAT_LOOP, "/* boat 分支已移除（仅用于统计其随机抽取数） */");
  const api2 = new Function(
    SANDBOX_STUB + "\n" + libNoBoat +
    "\nreturn { Mount, MEM, Noise, Prng, mountplanner };",
  )();
  const counter2 = withDrawCounter(api2);
  const withBoat = runPlanOnce(api, counter, segments, false);
  const withoutBoat = runPlanOnce(api2, counter2, segments, false);
  return withBoat.map((c, i) => c.draws - withoutBoat[i].draws);
}

// ── 主流程 ───────────────────────────────────────────────────────────
function planFixture(api, counter, segments) {
  // 全局口径（= 上游 MEM.planmtx 的真实语义：模块级全局，跨调用累积）
  const global = runPlanOnce(api, counter, segments, false);
  // 局部口径（= 本仓移植层修改前的「每次调用新建局部数组」）⇒ 负向自证用
  const local = runPlanOnce(api, counter, segments, true);
  const boat = boatDrawsOf(api, counter, segments);
  return global.map((c, i) => ({
    xmin: c.xmin,
    xmax: c.xmax,
    draws: c.draws,
    boatDraws: boat[i],
    entries: c.entries.filter((e) => e.tag !== "boat"),
    boatEntries: c.entries.filter((e) => e.tag === "boat"),
    localEntries: local[i].entries.filter((e) => e.tag !== "boat"),
  }));
}

function buildGolden() {
  const api = loadUpstream();
  const counter = withDrawCounter(api);

  // rock 的几何不依赖 PRNG（只依赖 perlin 表）⇒ 单独跑。
  api.Prng.seed(PRNG_SEED);
  api.Noise.noiseSeed(NOISE_SEED);
  const rock = runRock(api);

  return {
    generatedBy: "scripts/qianli_upstream_ref.js",
    upstream: UPSTREAM,
    prngSeed: PRNG_SEED,
    noiseSeed: NOISE_SEED,
    notes: [
      "rock 的 whiteBg = 上游 poly(ptlist[0].concat([[0,0]])) 的入参点列（Float64，未加偏移）。",
      "rock 的 outline = 上游 stroke(ptlist[0].map(+xoff/+yoff)) 的入参点列（Float64，xoff=yoff=0）。",
      "plan 的 entries = 上游 mountplanner 返回的 reg 中**非 boat** 的条目（端口无 boat 分支 ⇒ 已接受偏差 O7）。",
      "draws = 上游该次调用（含 boat 分支）的 Math.random 总次数；boatDraws = 其中 boat 分支消耗的次数；",
      "  ⇒ 「draws − boatDraws」= 上游**计划部分**的抽取次数（= 端口 plan() 应有的抽取次数）。",
      "boatDraws = 上游 boat 分支（:3806-3811）消耗的 Math.random 次数；端口无 boat ⇒ Kotlin 侧补抽同样次数以对齐随机流。",
      "localEntries = 把 MEM.planmtx 每次调用清空（= 移植层修改前的局部数组口径）的结果 ⇒ 负向自证用。",
      "overlap 夹具用**同一区间**连调两次 ⇒ 只有跨调用累积的全局 planmtx 才能与上游一致。",
    ],
    rock: rock,
    plan: {
      segments: PLAN_SEGMENTS,
      results: planFixture(api, counter, PLAN_SEGMENTS),
      mountSegments: PLAN_MOUNT_SEGMENTS,
      mountResults: planFixture(api, counter, PLAN_MOUNT_SEGMENTS),
      overlapSegments: PLAN_OVERLAP,
      overlapResults: planFixture(api, counter, PLAN_OVERLAP),
    },
  };
}

function main() {
  const check = process.argv.includes("--check");
  const golden = buildGolden();
  const text = JSON.stringify(golden, null, 2) + "\n";
  if (check) {
    if (!fs.existsSync(OUT_FILE)) {
      console.error("黄金基线不存在: " + OUT_FILE);
      process.exit(1);
    }
    const cur = fs.readFileSync(OUT_FILE, "utf8");
    if (cur !== text) {
      console.error("黄金基线与上游实跑结果不一致（漂移）: " + OUT_FILE);
      console.error("请重新运行: node scripts/qianli_upstream_ref.js");
      process.exit(1);
    }
    console.log("黄金基线与上游实跑结果一致 ✓ (" + OUT_FILE + ")");
    return;
  }
  fs.mkdirSync(OUT_DIR, { recursive: true });
  fs.writeFileSync(OUT_FILE, text);
  console.log("黄金基线已写入: " + OUT_FILE);
  console.log("  rock 夹具 = " + golden.rock.length);
  const show = (label, arr) => {
    for (const c of arr) {
      const same = JSON.stringify(c.entries) === JSON.stringify(c.localEntries);
      console.log(
        "  " + label + " [" + c.xmin + "," + c.xmax + ") 条目 = " + c.entries.length +
        " 抽取 = " + c.draws + " (boat " + c.boatDraws + ")" +
        " 局部口径条目 = " + c.localEntries.length +
        (same ? "  ⚠️ 与全局口径相同（该夹具无区分力）" : "  ✓ 与全局口径不同（有区分力）"),
      );
    }
  };
  show("plan", golden.plan.results);
  show("plan(mount)", golden.plan.mountResults);
  show("plan(overlap)", golden.plan.overlapResults);
}

main();
