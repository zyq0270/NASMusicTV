/* 生态地球 · 业务代码
   由 scripts/build_eco_globe_page.py 从 docs/globe-present.html 自动生成，⛔ 不要手改。
   改动请改源文件（docs/globe-present.html）后重跑脚本。 */
(function () {
'use strict';

/* ---------- 音频输入（Kotlin 宿主 100ms tick 调用 EcoGlobe.setAudio）----------
   原型页是纯自动旋转，没有音频；接入 App 后只把能量映射成转速，
   ⛔ 不引入随机性、不改任何摆放/季节逻辑，保证画面与演示页一致。 */
var AUDIO = { energy: 0, bass: 0, mid: 0, treble: 0, beat: 0 };
/* 实际渲染帧率（500ms 滑窗）—— 宿主 renderFps() 的唯一数据源。
   View 型渲染器有独立 rAF 循环，舞台的 Choreographer 口径对它是假读数。 */
var FPS = { v: 0, n: 0, t: 0 };
function clampAudio(v) { return (typeof v === "number" && isFinite(v)) ? Math.max(0, Math.min(1, v)) : 0; }
function setAudio(json) {
  try {
    var a = typeof json === "string" ? JSON.parse(json) : (json || {});
    AUDIO.energy = clampAudio(a.energy);
    AUDIO.bass = clampAudio(a.bass);
    AUDIO.mid = clampAudio(a.mid);
    AUDIO.treble = clampAudio(a.treble);
    AUDIO.beat = clampAudio(a.beat);
  } catch (e) { /* 坏数据不影响渲染 */ }
}
// ⛔ 已去掉 ESM import：THREE 来自 /globe/three.es5.js 的 UMD 全局，
//    GLTFLoader / SkeletonUtils 来自 /eco-globe/loaders.es5.js（本页 esbuild 产物）。
//    由 scripts/build_eco_globe_page.py 从 docs/globe-present.html 生成。

/* P10: 严格 CSP 的预览环境常把 blob: 排除在 connect-src 之外(WorkBuddy 预览面板),
   而 GLTFLoader 的 GLTFParser 构造时会按 typeof createImageBitmap 选择
   ImageBitmapLoader(fetch(blob:) 被拦 ⇒ 贴图全丢、模型变纯白剪影)。
   旧版直接 window.createImageBitmap = undefined 是「永久全局副作用」:
   整个页面生命周期都关掉了浏览器最快的纹理解码路径。
   现在改为:① 一次性探测 blob: 是否真被拦;② 仅在被拦时临时禁用;
   ③ 全部 GLB 加载完立刻恢复原值(另有 45s 兜底)。正常环境零改动。 */
var CSP = { probed: false, blocked: false, active: false,
            had: false, original: undefined, released: false };
function probeBlobCSP() {
  return new Promise(function (resolve) {
    var url = null, settled = false;
    function finish(blocked) {
      if (settled) return; settled = true;
      clearTimeout(timer);
      try { if (url) URL.revokeObjectURL(url); } catch (e) {}
      CSP.probed = true; CSP.blocked = blocked;
      resolve(blocked);
    }
    var timer = setTimeout(function () { finish(true); }, 900);
    try {
      url = URL.createObjectURL(new Blob([new Uint8Array([137, 80, 78, 71])],
                                         { type: 'image/png' }));
    } catch (e) { finish(true); return; }
    try {
      fetch(url).then(function () { finish(false); },
                      function () { finish(true); });
    } catch (e) { finish(true); }
  });
}
function ensureGLBTextureCompat() {
  return probeBlobCSP().then(function (blocked) {
    if (!blocked) return;
    CSP.had = ('createImageBitmap' in window);
    CSP.original = window.createImageBitmap;
    try { window.createImageBitmap = undefined; CSP.active = true; }
    catch (e) { CSP.active = false; }
  });
}
function releaseGLBTextureCompat() {
  if (CSP.released) return;
  CSP.released = true;
  if (!CSP.active) return;
  try {
    if (CSP.had) window.createImageBitmap = CSP.original;
    else delete window.createImageBitmap;
  } catch (e) {}
  CSP.active = false;
}
// 兜底:即便某个 GLB 永远挂起,也不把副作用留到页面结束
setTimeout(releaseGLBTextureCompat, 45000);

/* ---------- utilities ---------- */
const rand = (function (seed) {
  return function () { seed |= 0; seed = seed + 0x6D2B79F5 | 0;
    var t = Math.imul(seed ^ seed >>> 15, 1 | seed);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296;
  };
})(20261107);

function hash3(x, y, z) {
  var n = (Math.imul(Math.floor(x), 374761393) + Math.imul(Math.floor(y), 668265263) + Math.imul(Math.floor(z), 1442695041)) | 0;
  n = Math.imul(n ^ (n >>> 13), 1274126177); n = n ^ (n >>> 16);
  return ((n & 0xffffff) / 0xffffff) * 2 - 1;
}
function smooth(t) { return t * t * (3 - 2 * t); }
function vnoise3(x, y, z) {
  var x0 = Math.floor(x), y0 = Math.floor(y), z0 = Math.floor(z);
  var fx = x - x0, fy = y - y0, fz = z - z0;
  var u = smooth(fx), v = smooth(fy), w = smooth(fz);
  var c000 = hash3(x0, y0, z0), c100 = hash3(x0 + 1, y0, z0), c010 = hash3(x0, y0 + 1, z0), c110 = hash3(x0 + 1, y0 + 1, z0);
  var c001 = hash3(x0, y0, z0 + 1), c101 = hash3(x0 + 1, y0, z0 + 1), c011 = hash3(x0, y0 + 1, z0 + 1), c111 = hash3(x0 + 1, y0 + 1, z0 + 1);
  return c000 * (1 - u) * (1 - v) * (1 - w) + c100 * u * (1 - v) * (1 - w) +
    c010 * (1 - u) * v * (1 - w) + c110 * u * v * (1 - w) +
    c001 * (1 - u) * (1 - v) * w + c101 * u * (1 - v) * w +
    c011 * (1 - u) * v * w + c111 * u * v * w;
}
function fbm3(x, y, z, oct) {
  var sum = 0, amp = 1, f = 1, n = 0, i;
  for (i = 0; i < oct; i++) { sum += vnoise3(x * f, y * f, z * f) * amp; n += amp; amp *= 0.5; f *= 2; }
  return sum / n;
}
function clamp01(v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
function lerp(a, b, t) { return a + (b - a) * t; }
function spherePoint(u, v) {
  var th = u * Math.PI * 2, ph = v * Math.PI, sp = Math.sin(ph);
  return [Math.sin(th) * sp, Math.cos(ph), Math.cos(th) * sp];
}
function cross(a, b) {   // R3 叉积(返回新数组)
  return [a[1] * b[2] - a[2] * b[1],
          a[2] * b[0] - a[0] * b[2],
          a[0] * b[1] - a[1] * b[0]];
}

/* ---------- P8: 所有可调参数集中在这里 ----------
   页面内各处的 var 只是热路径短别名(值全部来自 CONFIG),调参只改这一处。
   window.__globeConfig 暴露给 ?debug=1 校对。 */
var CONFIG = {
  // 纹理 / 地形
  texW: 768, texH: 384, cloudW: 384, cloudH: 192,
  bakedTextures: true, bakedDir: '/eco-globe/tex/',
  // 地球 / 相机
  radius: 1.0, tilt: -0.41, spinRate: 0.055, cloudRate: 0.012,
  fov: 45, camDist: 3.2, camFit: 1.22,
  // 摆放 / 行走
  landMin: 0.47, walkSpeed: 0.010, stepRate: 0.45,
  footprint: 0.24, minGap: 0.30,
  forestCenters: 14, clusterR: 0.12, centerGap: 0.30,
  // 数量
  birds: 40, motes: 150, footprints: 240, treeHeight: 0.150,
  tornadoCount: 3, tornadoH: 0.19,
  // 加载 / 画质
  loadConcurrency: 4, maxPixelRatio: 2,
  lowFps: 26, highFps: 50, qualityWindow: 2.5,
  /* V6.3 夜面城市灯火开关(用户 2026-11-07 要求先关掉):
   false ⇒ 夜面不点灯,只保留夜面压暗与晨昏霞光。 */
  nightLights: false,
  // 新视觉
  seasonPeriod: 60, meteorMin: 4.0, meteorMax: 9.0,
  shadowCap: 200, footprintLife: 6.5, footprintEvery: 0.55,
  snowCap: 900, leafCap: 150, flowerCap: 900,
  mountainAmp: 0.185,        // V6.4 山峰最大相对半径抬升
};
window.__globeConfig = CONFIG;

/* ---------- V6.3 四季参数表 ----------
   索引 0=春 1=夏 2=秋 3=冬。每季一组参数,按季节进度线性插值。
   ⚠️ 此前四季的「出现强度」用 sin(π*(sf-季号)) 计算,而 sin 在季号
      边界恰好为 0 ⇒ 冬季一开始(season=3)强度就是 0,雪花/积雪永远
      看不见(2026-11-07 用户实测「加了?season=3,冬天没下雪」)。
      现在改成与 tint 同一套插值 ⇒ 钉档 season=N 就是纯净的第 N 季。
   - ground: 地球贴图 tint(乘法)
   - bark:   树干 tint(乘法,棕色系)
   - flowers/snowGround/snowFall/leafFall: 0..1 的出现强度
   树冠色不在这里 —— 见 LEAF_SEASON(它是「绝对色」不是乘法系数)。 */
/* 草地底色的实现形态:uGrassColor 只有枯黄单档(GRASS_YELLOW);
    「变绿」不插值颜色,而是枯黄强度 grassYellowK 在雪盖期内退回 0
    (强度 0 ⇒ shader 直接露出原贴图绿)。时序:冬前段雪已全覆盖、
    黄层在雪底退掉 ⇒ 雪消融时露出的就是原绿,用户只看到
    「连续变白 ⇒ 白色连续显出绿」。
    旧设计的 grassGreenK / GRASS_GREEN 仅停留在注释,未落地为代码,
    现按实际实现修正表述(2026-11-08 评审 P1)。 */
var GRASS_YELLOW = [0.72, 0.60, 0.26];
var SEASON_PARAMS = [
  { ground: [0.94, 1.14, 0.94], bark: [1.00, 1.02, 0.96],
    snowGround: 0.00, grassYellow: 0.00,
    flowers: 0.06, snowFall: 0.00, leafFall: 0.00 },
  { ground: [1.06, 1.05, 0.90], bark: [1.00, 1.00, 1.00],
    snowGround: 0.00, grassYellow: 0.00,
    flowers: 1.00, snowFall: 0.00, leafFall: 0.00 },
  { ground: [1.22, 0.88, 0.58], bark: [1.08, 0.94, 0.78],
    snowGround: 0.00, grassYellow: 1.00,
    flowers: 0.55, snowFall: 0.00, leafFall: 1.00 },
  { ground: [0.76, 0.90, 1.18], bark: [0.78, 0.86, 0.99],
    snowGround: 1.00, grassYellow: 0.00,
    flowers: 0.00, snowFall: 1.00, leafFall: 0.00 }
];

/* ---------- V6.3 树冠换色(保留贴图明暗,只换色相) ----------
   乘法染色(color *= tint)无法把绿色叶贴图变成冷蓝灰/正红 ——
   绿通道永远压不下去(2026-11-07 用户实测「冬天变深绿」)。
   改成 shader:取贴图亮度当明暗、季节色当色相,两者相乘。
   uLeafMix=0 ⇒ 完全保留原贴图色(夏季 = 现在的树颜色)。
   ⚠️ 必须给叶子材质单独 customProgramCacheKey:树皮与树叶的 defines
      完全相同 ⇒ 默认缓存键相同 ⇒ 树皮会复用树叶的着色器而被一起换色。 */
var LEAF_SEASON = {
  spring: 0x9fd97a,                                        // 淡绿
  summer: 0x4f7a2e,                                        // 深绿(盛夏,比贴图原绿更沉)
  winter: 0x6f7f96,                                        // 冷蓝灰(深)
  // 每个树形的秋色(黄 → 橙 → 红 → 深红 → 亮黄),同一片林子里就有黄树与红树
  autumn: [0xe8b53a, 0xdc8a2e, 0xc23b2a, 0x8f2318, 0xf0d05a]
};
function isLeafMaterial(mat) {
  /* ⚠️ 材质名是复数 "NormalTree_Leaves" —— "Leaves" 并不包含子串 "Leaf"
      (L-e-a-v ≠ L-e-a-f)⇒ 用 /leaf/ 会全部落空、树冠一个都识别不出来。
      这里同时接受 leaf / leaves / foliage / canopy。 */
  return !!(mat && mat.name && /leaf|leav|foliage|canopy/i.test(mat.name));
}
function patchLeafMaterial(mat, partIndex) {
  var u = { tint: { value: new THREE.Color(1, 1, 1) },
            mix:  { value: 0 },
            lum:  { value: 0.40 } };   // 叶贴图平均线性亮度,用于明暗归一
  mat.userData.seasonU = u;
  // 该树形的四季色:春夏冬用全局季色,秋季用树形专属色
  mat.userData.leafColor = [
    new THREE.Color(LEAF_SEASON.spring),
    new THREE.Color(LEAF_SEASON.summer),
    new THREE.Color(LEAF_SEASON.autumn[partIndex % LEAF_SEASON.autumn.length]),
    new THREE.Color(LEAF_SEASON.winter)
  ];
  // ⚠️ 四季都换色:春淡绿 / 夏深绿 / 秋黄橙红深红 / 冬冷蓝灰。
  //    之前夏季是 [1,0,1,1](不换色)⇒ 夏天看起来跟春天一样淡。
  mat.userData.leafMix = [1, 1, 1, 1];
  mat.onBeforeCompile = function (shader) {
    shader.uniforms.uLeafTint = u.tint;
    shader.uniforms.uLeafMix = u.mix;
    shader.uniforms.uLeafLum = u.lum;
    shader.fragmentShader = shader.fragmentShader
      .replace('#include <common>',
        '#include <common>\nuniform vec3 uLeafTint;\nuniform float uLeafMix;\nuniform float uLeafLum;')
      .replace('#include <map_fragment>',
        '#include <map_fragment>\n' +
        '  if ( uLeafMix > 0.0 ) {\n' +
        '    float lum = dot( diffuseColor.rgb, vec3(0.2126, 0.7152, 0.0722) );\n' +
        '    float k = clamp( lum / max(0.05, uLeafLum), 0.30, 2.6 );\n' +
        '    diffuseColor.rgb = mix( diffuseColor.rgb, uLeafTint * k, uLeafMix );\n' +
        '  }');
  };
  mat.customProgramCacheKey = function () { return 'treeLeafSeason'; };
  mat.color.setRGB(1, 1, 1);      // 颜色全部交给 uLeafTint,避免二次相乘
  mat.needsUpdate = true;
}

/* ---------- P3: elevation 网格查表 ----------
   旧实现每次调用都要跑 fbm3 的 5+4+3 个八度;walkers / tornado / 森林撒点
   每帧几百次,是纯 CPU 热点。地球纹理那次循环本来就把每个像素的 e 算好了,
   顺手存进 ELEV.data 即可零成本复用;elevation() 改成双线性插值查表。
   ELEV.cls 同时保存地表类别(0 海 / 1 沙 / 2 草候选 / 3 林候选 /
   4 岩 / 5 雪 / 6 高草非候选),供城市灯火 / 海面掩膜 / 预烘焙回读。 */
var ELEV = { w: 0, h: 0, data: null, cls: null };
function elevationFast(u, v) {
  if (!ELEV.data) return elevationP(spherePoint(u, v));   // 兜底:精确计算
  var w = ELEV.w, h = ELEV.h, d = ELEV.data;
  u = u - Math.floor(u);
  var fx = u * w - 0.5, fy = Math.min(h - 1, Math.max(0, v * h - 0.5));
  var x0 = Math.floor(fx), y0 = Math.floor(fy);
  var tx = fx - x0, ty = fy - y0;
  var xa = ((x0 % w) + w) % w, xb = (xa + 1) % w;
  var ya = Math.min(h - 1, Math.max(0, y0)), yb = Math.min(h - 1, ya + 1);
  var a = d[ya * w + xa], b = d[ya * w + xb],
      c = d[yb * w + xa], e = d[yb * w + xb];
  return (a + (b - a) * tx) * (1 - ty) + (c + (e - c) * tx) * ty;
}
function classAt(u, v) {
  if (!ELEV.cls) return 0;
  var w = ELEV.w, h = ELEV.h;
  u = u - Math.floor(u);
  var px = Math.min(w - 1, Math.max(0, Math.floor(u * w)));
  var py = Math.min(h - 1, Math.max(0, Math.floor(v * h)));
  return ELEV.cls[py * w + px];
}

/* SphereGeometry(r160) 的 UV → 球面方向映射,专供摆模型用:
     vertex = (-cos(2πu)·sin(πv), cos(πv), sin(2πu)·sin(πv)), uv = (u, 1-v)
   CanvasTexture 默认 flipY=true ⇒ canvas 的 (u, v) 恰好对应这里的 (u, v)。
   注意它和 spherePoint() 不是同一个映射(相差 90° 经度):程序化噪声用
   spherePoint 无所谓,但模型落点若用错,就会整体偏离纹理上的陆地。 */
function surfaceDir(u, v) {
  var th = u * Math.PI * 2, ph = v * Math.PI, sp = Math.sin(ph);
  return [-Math.cos(th) * sp, Math.cos(ph), Math.sin(th) * sp];
}

/* ---------- procedural cartoon earth texture ---------- */
var W = CONFIG.texW, H = CONFIG.texH;  // 值统一由 CONFIG 提供
function elevationP(p) {
  var e1 = fbm3(p[0] * 1.6 + 0.37, p[1] * 1.6 + 0.11, p[2] * 1.6 + 0.53, 5);
  var e2 = fbm3(p[0] * 4.3 + 1.2, p[1] * 4.3 + 2.4, p[2] * 4.3 + 3.7, 4);
  var e3 = fbm3(p[0] * 9.0 + 0.6, p[1] * 9.0 + 3.1, p[2] * 9.0 + 5.2, 3);
  return clamp01(0.46 + e1 * 0.34 + e2 * 0.12 + e3 * 0.08);
}
function elevation(u, v) { return elevationFast(u, v); }
function createEarthTexture() {
  var t0 = performance.now();
  var cv = document.createElement('canvas'); cv.width = W; cv.height = H;
  var ctx = cv.getContext('2d');
  var img = ctx.createImageData(W, H), data = img.data;
  // A2 地形凹凸 / A3 海陆粗糙度:与颜色图同一次循环生成,零额外噪声计算
  var bumpCv = document.createElement('canvas'); bumpCv.width = W; bumpCv.height = H;
  var bumpCtx = bumpCv.getContext('2d'), bumpImg = bumpCtx.createImageData(W, H), bd = bumpImg.data;
  var roughCv = document.createElement('canvas'); roughCv.width = W; roughCv.height = H;
  var roughCtx = roughCv.getContext('2d'), roughImg = roughCtx.createImageData(W, H), rd = roughImg.data;
  var grass = [], forest = [];
  var elevGrid = new Float32Array(W * H), clsGrid = new Uint8Array(W * H);
  for (var py = 0; py < H; py++) {
    var v = py / H;
    for (var px = 0; px < W; px++) {
      var u = px / W, i = (py * W + px) * 4;
      var sp = spherePoint(u, v), e = elevationP(sp);
      var det = fbm3(sp[0] * 20 + 7.7, sp[1] * 20 + 3.3, sp[2] * 20 + 9.9, 3);
      var sh = fbm3(sp[0] * 6 + 0.2, sp[1] * 6 + 4.4, sp[2] * 6 + 2.8, 3);
      var r, g, b, cls = 0;
      if (e < 0.46) {
        cls = 0;
        var dp = 1 - e / 0.46;
        var lo = fbm3(sp[0] * 30 + 1.9, sp[1] * 30 + 8.2, sp[2] * 30 + 12.4, 2);
        var lint = lo > 0.7 ? 0.1 : lo > 0.5 ? 0.05 : 0;
        var deep = clamp01(dp * 1.4);
        r = lerp(0.28, 0.1, deep) * 255; g = lerp(0.6, 0.32, deep) * 255; b = lerp(0.88, 0.55, deep) * 255;
        r += lint * 60; g += lint * 75; b += lint * 90;
      } else {
        var shade = 0.95 + sh * 0.12;
        if (e < 0.49) { cls = 1; r = 0.93 * 255 * shade; g = 0.84 * 255 * shade; b = 0.55 * 255 * shade; }
        else if (e < 0.72) {
          if (det > 0.5 + (e - 0.46) * 0.5) {
            cls = 3;
            r = 0.24 * shade * 255; g = 0.55 * shade * 255; b = 0.17 * shade * 255;
            forest.push({ u: +u.toFixed(5), v: +v.toFixed(5) });
          } else {
            cls = e < 0.66 ? 2 : 6;   // 只有 e<0.66 才进 grass 候选池
            var mot = 0.92 + fbm3(sp[0] * 16 + 3.5, sp[1] * 16 + 9.1, sp[2] * 16 + 6.3, 3) * 0.16;
            r = 0.46 * shade * mot * 255; g = 0.78 * shade * mot * 255; b = 0.24 * shade * mot * 255;
            if (e < 0.66) grass.push({ u: +u.toFixed(5), v: +v.toFixed(5) });
          }
        } else if (e < 0.82) {
          cls = 4;
          var tt = (e - 0.72) / 0.1;
          r = lerp(0.7, 0.58, tt) * shade * 255; g = lerp(0.66, 0.54, tt) * shade * 255; b = lerp(0.5, 0.44, tt) * shade * 255;
        } else {
          cls = 5;
          var se = 0.8 + (fbm3(sp[0] * 12 + 5.1, sp[1] * 12 + 0.8, sp[2] * 12 + 3.3, 3) + 1) * 0.025;
          var st = clamp01((e - se) / 0.05);
          r = lerp(0.72, 1.0, st) * 255; g = lerp(0.68, 1.0, st) * 255; b = lerp(0.64, 1.0, st) * 255;
        }
      }
      var polar = Math.min(v, 1 - v);
      if (polar < 0.08) {
        var pe = 0.07 + (fbm3(sp[0] * 8 + 2.2, sp[1] * 8 + 6.6, sp[2] * 8 + 1.1, 3) + 1) * 0.02;
        var pt = clamp01((pe - polar) / 0.03);
        r = lerp(r, 235, pt); g = lerp(g, 244, pt); b = lerp(b, 250, pt);
      }
      // A4 海岸浪花线:只贴着 e=0.46 的窄带,宽了会变成一整圈白沙滩
      if (e < 0.46) {
        var coast = clamp01((e - 0.447) / 0.013);
        r = lerp(r, 226, coast * 0.55);
        g = lerp(g, 244, coast * 0.55);
        b = lerp(b, 242, coast * 0.55);
      }
      data[i] = r; data[i + 1] = g; data[i + 2] = b; data[i + 3] = 255;
      elevGrid[py * W + px] = e; clsGrid[py * W + px] = cls;
      // 海陆权重:两张辅助图都必须平滑过渡,否则 bump 会在海岸拉出硬亮边
      var landT = clamp01((e - 0.44) / 0.06);
      // bumpMap:海面平坦,陆地按高度 + 细节噪声起伏
      var bv = 26 + landT * clamp01(0.30 + (e - 0.46) * 1.75 + det * 0.16) * 209;
      bd[i] = bd[i + 1] = bd[i + 2] = bv; bd[i + 3] = 255;
      // roughnessMap 走 G 通道:海面略光滑(柔和高光),陆地哑光
      var rv = lerp(112, 240, landT);
      rd[i] = rd[i + 1] = rd[i + 2] = rv; rd[i + 3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
  bumpCtx.putImageData(bumpImg, 0, 0);
  roughCtx.putImageData(roughImg, 0, 0);
  var tex = new THREE.CanvasTexture(cv);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.wrapS = THREE.RepeatWrapping;
  var bumpTex = new THREE.CanvasTexture(bumpCv);
  bumpTex.wrapS = THREE.RepeatWrapping;
  var roughTex = new THREE.CanvasTexture(roughCv);
  roughTex.wrapS = THREE.RepeatWrapping;
  ELEV.w = W; ELEV.h = H; ELEV.data = elevGrid; ELEV.cls = clsGrid;
  console.log('[globe-present] earth texture ' + W + 'x' + H + ' generated in ' +
              (performance.now() - t0).toFixed(0) + 'ms');
  return { texture: tex, bump: bumpTex, rough: roughTex, grass: grass, forest: forest,
           canvases: { earth: cv, bump: bumpCv, rough: roughCv },
           elevGrid: elevGrid, clsGrid: clsGrid, generated: true };
}

/* ---------- cloud texture ---------- */
function createCloudTexture(seedOffset) {
  var off = seedOffset || 0;
  var cw = 384, ch = 192, cv = document.createElement('canvas');
  cv.width = cw; cv.height = ch;
  var ctx = cv.getContext('2d'), img = ctx.createImageData(cw, ch), data = img.data;
  for (var py = 0; py < ch; py++) { for (var px = 0; px < cw; px++) {
    var u = px / cw, v = py / ch;
    var sp = spherePoint(u, v);
    var n = fbm3(sp[0] * 3 + 0.8 + off, sp[1] * 3 + 1.7 + off, sp[2] * 3 + 2.6 + off, 5) * 0.7 +
            fbm3(sp[0] * 9 + 4.2 + off, sp[1] * 9 + 2.3 + off, sp[2] * 9 + 5.9 + off, 3) * 0.3;
    var cl = clamp01((n + 0.22) / 0.5);
    var latBand = 1 - Math.abs(v - 0.5) * 1.5;
    var a = cl * cl * (0.7 + latBand * 0.3);
    var i = (py * cw + px) * 4;
    data[i] = 255; data[i + 1] = 255; data[i + 2] = 255; data[i + 3] = Math.round(a * 255);
  } }
  ctx.putImageData(img, 0, 0);
  var tex = new THREE.CanvasTexture(cv);
  tex.wrapS = THREE.RepeatWrapping;
  return tex;
}

/* ---------- P5: 程序化纹理预烘焙 ----------
   docs/assets/tex/ 下的 6 张静态图由 ?bake=1 一次性导出,首屏用
   TextureLoader 直接载入,省掉整段噪声循环(实测最耗时的一块)。
   terrain.png 是数据图:R = round(e*255),G = 地表类别(见 ELEV.cls),
   B = 0。回读后既恢复 ELEV 网格,又按类别精确重建 grass/forest 候选池
   ⇒ 落点与程序化生成时逐点一致,可复现。
   任一文件缺失 / 解码失败 ⇒ 整体回退到程序化生成,页面照常可用。 */
function buildProceduralResources() {
  var r = createEarthTexture();
  r.cloudA = createCloudTexture(0);
  r.cloudB = createCloudTexture(11.3);
  r.baked = false;
  console.log('[globe-present] procedural textures, land candidates: ' +
              r.grass.length + ' grass / ' + r.forest.length + ' forest');
  return r;
}
function bakeTextures() {
  var r = buildProceduralResources();
  // terrain.png:R=高程量化,G=类别,B=0,A=255
  var cv = document.createElement('canvas'); cv.width = W; cv.height = H;
  var ctx = cv.getContext('2d'), img = ctx.createImageData(W, H), d = img.data;
  for (var i = 0; i < W * H; i++) {
    d[i * 4]     = Math.round(clamp01(r.elevGrid[i]) * 255);
    d[i * 4 + 1] = r.clsGrid[i];
    d[i * 4 + 2] = 0;
    d[i * 4 + 3] = 255;
  }
  ctx.putImageData(img, 0, 0);
  var cvs = r.canvases;
  return {
    earth:   cvs.earth.toDataURL('image/png'),
    bump:    cvs.bump.toDataURL('image/png'),
    rough:   cvs.rough.toDataURL('image/png'),
    cloudA:  r.cloudA.image.toDataURL('image/png'),
    cloudB:  r.cloudB.image.toDataURL('image/png'),
    terrain: cv.toDataURL('image/png'),
    size:    [W, H]
  };
}
function loadTex(url, srgb) {
  return new THREE.TextureLoader().loadAsync(url).then(function (t) {
    if (srgb) t.colorSpace = THREE.SRGBColorSpace;
    t.wrapS = THREE.RepeatWrapping;
    return t;
  });
}
function loadTerrain(url) {
  return new Promise(function (resolve, reject) {
    var im = new Image();
    im.onload = function () {
      try {
        var cv = document.createElement('canvas');
        cv.width = im.width; cv.height = im.height;
        var ctx = cv.getContext('2d');
        ctx.drawImage(im, 0, 0);
        var d = ctx.getImageData(0, 0, cv.width, cv.height).data;
        var n = cv.width * cv.height;
        var eg = new Float32Array(n), cg = new Uint8Array(n);
        var grass = [], forest = [];
        for (var i = 0; i < n; i++) {
          eg[i] = d[i * 4] / 255;
          var cls = d[i * 4 + 1]; cg[i] = cls;
          if (cls === 2 || cls === 3) {
            var px = i % cv.width, py = Math.floor(i / cv.width);
            var p = { u: +(px / cv.width).toFixed(5), v: +(py / cv.height).toFixed(5) };
            if (cls === 2) grass.push(p); else forest.push(p);
          }
        }
        ELEV.w = cv.width; ELEV.h = cv.height; ELEV.data = eg; ELEV.cls = cg;
        resolve({ grass: grass, forest: forest, size: [cv.width, cv.height] });
      } catch (e) { reject(e); }
    };
    im.onerror = function () { reject(new Error('terrain.png load failed')); };
    im.src = url;
  });
}
async function loadBakedResources() {
  var D = CONFIG.bakedDir;
  var names = ['earth.png', 'bump.png', 'rough.png', 'cloud-a.png', 'cloud-b.png'];
  var tasks = [];
  for (var i = 0; i < names.length; i++)
    tasks.push(loadTex(D + names[i], i === 0 || i >= 3));
  var t = await Promise.all(tasks);
  var g = await loadTerrain(D + 'terrain.png');
  if (g.size[0] !== CONFIG.texW || g.size[1] !== CONFIG.texH)
    throw new Error('terrain size mismatch');
  return { texture: t[0], bump: t[1], rough: t[2],
           cloudA: t[3], cloudB: t[4],
           grass: g.grass, forest: g.forest, baked: true,
           elevGrid: ELEV.data, clsGrid: ELEV.cls };
}
async function getEarthResources() {
  if (!CONFIG.bakedTextures) return buildProceduralResources();
  try {
    var t0 = performance.now();
    var r = await loadBakedResources();
    console.log('[globe-present] baked textures loaded in ' +
                (performance.now() - t0).toFixed(0) + 'ms, land candidates: ' +
                r.grass.length + ' grass / ' + r.forest.length + ' forest');
    return r;
  } catch (e) {
    console.warn('[globe-present] 预烘焙纹理不可用,回退程序化生成:', e && e.message);
    return buildProceduralResources();
  }
}

/* ---------- P6: 加载遮罩状态 ---------- */
var LOAD = { frame: false, model: false, hidden: false };
function setLoadProgress(pct, text) {
  var bar = document.getElementById('loadBar'), lab = document.getElementById('loadPct');
  if (bar) bar.style.width = Math.max(0, Math.min(100, pct)) + '%';
  if (lab) lab.textContent = text || (Math.round(pct) + '%');
}
function maybeHideLoading() {
  if (LOAD.hidden || !LOAD.frame || !LOAD.model) return;
  LOAD.hidden = true;
  setLoadProgress(100, '就绪');
  var l = document.getElementById('loading');
  if (l) setTimeout(function () { l.classList.add('hide'); }, 120);
}
function forceHideLoading() {
  LOAD.hidden = true;
  var l = document.getElementById('loading');
  if (l) l.classList.add('hide');
}
function firstModelReady() { LOAD.model = true; maybeHideLoading(); }
setTimeout(forceHideLoading, 4000);   // 网络异常时不把用户挡在遮罩后面

/* ---------- sun / bird textures ---------- */
function sunSpriteTex() {
  var s = 256, cv = document.createElement('canvas'); cv.width = s; cv.height = s;
  var ctx = cv.getContext('2d');
  var g = ctx.createRadialGradient(s / 2, s / 2, 0, s / 2, s / 2, s / 2);
  g.addColorStop(0, 'rgba(255,252,235,1)'); g.addColorStop(0.15, 'rgba(255,246,205,0.9)');
  g.addColorStop(0.35, 'rgba(255,236,170,0.4)'); g.addColorStop(1, 'rgba(255,224,150,0)');
  ctx.fillStyle = g; ctx.fillRect(0, 0, s, s);
  var t = new THREE.CanvasTexture(cv); t.colorSpace = THREE.SRGBColorSpace; return t;
}
function birdTex(phase) {
  // C11 三帧翅膀姿态:0 平展 / 1 上扬 / 2 下压
  var lift = phase === 1 ? -0.17 : phase === 2 ? 0.13 : 0;
  var s = 64, cv = document.createElement('canvas'); cv.width = s; cv.height = s;
  var ctx = cv.getContext('2d');
  ctx.fillStyle = 'rgba(255,255,255,0.95)';
  ctx.beginPath();
  ctx.moveTo(s * 0.5, s * 0.44);
  ctx.quadraticCurveTo(s * 0.3, s * (0.62 + lift), s * 0.08, s * (0.56 + lift));
  ctx.quadraticCurveTo(s * 0.32, s * (0.68 + lift), s * 0.5, s * 0.48);
  ctx.quadraticCurveTo(s * 0.68, s * (0.68 + lift), s * 0.92, s * (0.56 + lift));
  ctx.quadraticCurveTo(s * 0.7, s * (0.62 + lift), s * 0.5, s * 0.44);
  ctx.closePath(); ctx.fill();
  var t = new THREE.CanvasTexture(cv); t.colorSpace = THREE.SRGBColorSpace; return t;
}

/* ---------- v5 视觉工具:接触阴影 / 柔光 / 星空 ---------- */
var _glowTex = null, _shadowTex = null;
var _snowTex = null, _leafTex = null, _flowerTex = null;
function glowTex() {
  if (_glowTex) return _glowTex;
  var s = 128, cv = document.createElement('canvas'); cv.width = cv.height = s;
  var ctx = cv.getContext('2d');
  var g = ctx.createRadialGradient(s / 2, s / 2, 0, s / 2, s / 2, s / 2);
  g.addColorStop(0, 'rgba(255,255,255,1)');
  g.addColorStop(0.25, 'rgba(255,255,255,0.70)');
  g.addColorStop(0.60, 'rgba(255,255,255,0.18)');
  g.addColorStop(1, 'rgba(255,255,255,0)');
  ctx.fillStyle = g; ctx.fillRect(0, 0, s, s);
  _glowTex = new THREE.CanvasTexture(cv);
  _glowTex.colorSpace = THREE.SRGBColorSpace;
  return _glowTex;
}
function contactShadowTex() {
  if (_shadowTex) return _shadowTex;
  var s = 128, cv = document.createElement('canvas'); cv.width = cv.height = s;
  var ctx = cv.getContext('2d');
  var g = ctx.createRadialGradient(s / 2, s / 2, 0, s / 2, s / 2, s / 2);
  g.addColorStop(0, 'rgba(10,26,18,0.62)');
  g.addColorStop(0.42, 'rgba(10,26,18,0.30)');
  g.addColorStop(0.78, 'rgba(10,26,18,0.08)');
  g.addColorStop(1, 'rgba(10,26,18,0)');
  ctx.fillStyle = g; ctx.fillRect(0, 0, s, s);
  _shadowTex = new THREE.CanvasTexture(cv);
  _shadowTex.colorSpace = THREE.SRGBColorSpace;
  return _shadowTex;
}
/* V6.2 冬季雪花:一个小圆点 + 微蓝晕,独立缓存 */
function snowTex() {
  if (_snowTex) return _snowTex;
  var s = 48, cv = document.createElement('canvas');
  cv.width = cv.height = s;
  var ctx = cv.getContext('2d');
  var c = s / 2;
  var g = ctx.createRadialGradient(c, c, 1, c, c, s * 0.47);
  g.addColorStop(0, 'rgba(255,255,255,1)');
  g.addColorStop(0.55, 'rgba(226,240,255,0.75)');
  g.addColorStop(1, 'rgba(190,220,255,0)');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, s, s);
  _snowTex = new THREE.CanvasTexture(cv);
  _snowTex.colorSpace = THREE.SRGBColorSpace;
  return _snowTex;
}
/* V6.2 秋季落叶:一小团暖橙,用 Independent Points 撒下 */
function leafTex() {
  if (_leafTex) return _leafTex;
  var s = 48, cv = document.createElement('canvas');
  cv.width = cv.height = s;
  var ctx = cv.getContext('2d');
  ctx.fillStyle = 'rgba(230,135,45,0.95)';
  ctx.beginPath();
  ctx.moveTo(s * 0.5, s * 0.10);
  ctx.quadraticCurveTo(s * 0.78, s * 0.30, s * 0.70, s * 0.80);
  ctx.quadraticCurveTo(s * 0.50, s * 0.95, s * 0.30, s * 0.80);
  ctx.quadraticCurveTo(s * 0.22, s * 0.30, s * 0.5, s * 0.10);
  ctx.closePath();
  ctx.fill();
  _leafTex = new THREE.CanvasTexture(cv);
  _leafTex.colorSpace = THREE.SRGBColorSpace;
  return _leafTex;
}
/* V6.3 夏季花朵:一颗实心小圆点(用户要求「用各种颜色的小点代替」)。
   颜色由 PointsMaterial 的 vertexColors 提供 ⇒ 一张白贴图即可着色。 */
function flowerTex() {
  if (_flowerTex) return _flowerTex;
  var s = 32, cv = document.createElement('canvas');
  cv.width = cv.height = s;
  var ctx = cv.getContext('2d');
  var c = s / 2;
  // 中心实心 + 边缘轻微羽化,避免像素锯齿
  var g = ctx.createRadialGradient(c, c, 0, c, c, c);
  g.addColorStop(0, 'rgba(255,255,255,1)');
  g.addColorStop(0.72, 'rgba(255,255,255,1)');
  g.addColorStop(1, 'rgba(255,255,255,0)');
  ctx.fillStyle = g;
  ctx.beginPath();
  ctx.arc(c, c, c, 0, Math.PI * 2);
  ctx.fill();
  _flowerTex = new THREE.CanvasTexture(cv);
  _flowerTex.colorSpace = THREE.SRGBColorSpace;
  return _flowerTex;
}
/* 星空用独立 LCG,绝不消耗全局 rand() ⇒ 动物/树落点与 v4 完全一致,可复现 */
function drawStarfield(cv) {
  var seed = 9137;
  function sr() { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed / 4294967296; }
  var dpr = Math.min(window.devicePixelRatio || 1, 2);
  var w = window.innerWidth, h = window.innerHeight;
  cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr);
  var ctx = cv.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  var band = ctx.createLinearGradient(0, 0, w, h);
  band.addColorStop(0, 'rgba(120,170,255,0)');
  band.addColorStop(0.45, 'rgba(150,190,255,0.09)');
  band.addColorStop(0.62, 'rgba(190,210,255,0.05)');
  band.addColorStop(1, 'rgba(120,170,255,0)');
  ctx.fillStyle = band; ctx.fillRect(0, 0, w, h);
  var n = Math.round(w * h / 5200);
  for (var i = 0; i < n; i++) {
    var x = sr() * w, y = sr() * h, r = 0.35 + sr() * 1.25, a = 0.22 + sr() * 0.70;
    ctx.fillStyle = 'rgba(255,255,255,' + a.toFixed(2) + ')';
    ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill();
    if (r > 1.30) {
      ctx.strokeStyle = 'rgba(205,228,255,' + (a * 0.32).toFixed(2) + ')';
      ctx.lineWidth = 0.6;
      ctx.beginPath();
      ctx.moveTo(x - r * 3, y); ctx.lineTo(x + r * 3, y);
      ctx.moveTo(x, y - r * 3); ctx.lineTo(x, y + r * 3);
      ctx.stroke();
    }
  }
}

/* ---------- v5.2 龙卷风纹理(纯程序化,不引入外部资源) ---------- */
function tornadoTex() {
  var w = 256, h = 256, cv = document.createElement('canvas');
  cv.width = w; cv.height = h;
  var ctx = cv.getContext('2d');
  var img = ctx.createImageData(w, h), d = img.data;
  for (var y = 0; y < h; y++) {
    for (var x = 0; x < w; x++) {
      var u = x / w, v = y / h;
      // 斜向螺旋条纹:相位沿高度递进 ⇒ UV 横向滚动时看起来在旋转
      var band = 0.5 + 0.5 * Math.sin((u * 4 - v * 6) * Math.PI * 2);
      var flake = 0.5 + 0.5 * Math.sin(u * Math.PI * 18 + v * 13) *
                         Math.sin(v * Math.PI * 10 - u * 9);
      // 更雾化:较低的峰值 alpha + 云絮噪声 + 更宽的上下淡出(不要硬边锥体);
      // 但保留灰蓝暗部条纹,否则叠在浅绿/浅蓝地表上会完全看不见。
      var a = 0.10 + band * 0.34 + flake * 0.22;
      a *= Math.min(1, Math.min(v, 1 - v) * 4.0);
      var shade = 0.25 + band * 0.75, i = (y * w + x) * 4;
      d[i]     = 92 + 140 * shade;       // 暗灰蓝 → 亮白(暗部再深一点)
      d[i + 1] = 118 + 122 * shade;
      d[i + 2] = 150 + 102 * shade;
      d[i + 3] = Math.min(255, a * 255);
    }
  }
  ctx.putImageData(img, 0, 0);
  var t = new THREE.CanvasTexture(cv);
  t.colorSpace = THREE.SRGBColorSpace;
  t.wrapS = THREE.RepeatWrapping;
  t.wrapT = THREE.ClampToEdgeWrapping;
  return t;
}
function dustTex() {
  var s = 128, cv = document.createElement('canvas'); cv.width = cv.height = s;
  var ctx = cv.getContext('2d');
  var g = ctx.createRadialGradient(s / 2, s / 2, s * 0.04, s / 2, s / 2, s * 0.5);
  g.addColorStop(0, 'rgba(190,210,228,0)');
  g.addColorStop(0.32, 'rgba(190,210,228,0.42)');
  g.addColorStop(0.68, 'rgba(176,198,220,0.16)');
  g.addColorStop(1, 'rgba(176,198,220,0)');
  ctx.fillStyle = g; ctx.fillRect(0, 0, s, s);
  var t = new THREE.CanvasTexture(cv);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

/* ---------- V1 / V3 / V4 / V6 视觉资源(全部程序化,零外部依赖) ---------- */
/* V1:从 ELEV.cls 的草 / 林 / 高草像素里撒暖色灯点,作为夜面城市灯火。
   用独立 LCG,不消耗全局 rand() ⇒ 落点可复现。 */
function makeCityTexture() {
  var cv = document.createElement('canvas'); cv.width = W; cv.height = H;
  var ctx = cv.getContext('2d');
  ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, H);
  var seed = 5521, cls = ELEV.cls;
  function sr() { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed / 4294967296; }
  if (cls) {
    for (var i = 0; i < 6500; i++) {
      var px = Math.floor(sr() * W), py = Math.floor(sr() * H);
      var c = cls[py * W + px];
      if (!(c === 2 || c === 3 || c === 6)) continue;
      if (Math.abs(py / H - 0.5) > 0.39) continue;
      var r = 1.0 + sr() * 2.2;
      ctx.fillStyle = 'rgba(255,' + (176 + Math.floor(sr() * 64)) + ',' +
                      (86 + Math.floor(sr() * 76)) + ',' + (0.55 + sr() * 0.45).toFixed(2) + ')';
      ctx.fillRect(px, py, r, r);
    }
  }
  var t = new THREE.CanvasTexture(cv);
  t.colorSpace = THREE.SRGBColorSpace; t.wrapS = THREE.RepeatWrapping;
  return t;
}
/* V4:海陆掩膜(白=海)。与地球纹理同 UV / flipY ⇒ 片元里 texture2D(mask,vUv)
   即可精确只让海面出高光。 */
function makeOceanMaskTexture() {
  var cv = document.createElement('canvas'); cv.width = W; cv.height = H;
  var ctx = cv.getContext('2d');
  var img = ctx.createImageData(W, H), d = img.data, cls = ELEV.cls;
  for (var i = 0; i < W * H; i++) {
    var o = cls ? (cls[i] === 0 ? 255 : 0) : 0;
    d[i * 4] = o; d[i * 4 + 1] = o; d[i * 4 + 2] = o; d[i * 4 + 3] = 255;
  }
  ctx.putImageData(img, 0, 0);
  var t = new THREE.CanvasTexture(cv); t.wrapS = THREE.RepeatWrapping;
  return t;
}
/* V6.3 陆地季节色层掩膜,与地球贴图同 UV ⇒ 色层严丝合缝盖在陆地上。
   数据源同 makeOceanMaskTexture:ELEV.cls,cls=0 是海,其余都是陆地。
   通道约定(自定义 shader 用,不是 alphaMap):
     R = 草地权重(0..1)  cls=2 草 / cls=6 高草 ⇒ 秋天只染草地
     G = 陆地权重(0..1)  任意陆地 ⇒ 冬天整块陆地都盖雪
     B = 高程(0..1)      高处冬天雪更厚、秋天岩石更多
   ⚠️ 早期版本用 alphaMap + alphaTest:0.5 ⇒ 阈值处「整块大陆突然出现/消失」,
      用户实测反馈「秋冬交界积雪突然出现、然后再逐渐变白;冬春交界突然消失」。
      根因:alphaTest 是硬阈值,而 opacity 又从 0.35 起步 ⇒ 两段式突变。
      现在改成纯渐变(alpha 由权重连续插值),杜绝突变。 */
function makeLandSeasonMaskTexture() {
  var cv = document.createElement('canvas'); cv.width = W; cv.height = H;
  var ctx = cv.getContext('2d');
  var img = ctx.createImageData(W, H), d = img.data, cls = ELEV.cls, eg = ELEV.data;
  for (var i = 0; i < W * H; i++) {
    var c = cls ? cls[i] : 0;
    var land = c === 0 ? 0 : 1;
    var grass = (c === 2 || c === 6) ? 1 : 0;
    var e = eg ? eg[i] : 0;
    var hNorm = clamp01((e - 0.46) / 0.34);       // 海平面 0.46 → 陆地最高
    d[i * 4]     = Math.round(grass * 255);        // R = 草地
    d[i * 4 + 1] = Math.round(land * 255);         // G = 陆地
    d[i * 4 + 2] = Math.round(hNorm * 255);        // B = 高程
    d[i * 4 + 3] = 255;
  }
  ctx.putImageData(img, 0, 0);
  var t = new THREE.CanvasTexture(cv);
  t.wrapS = THREE.RepeatWrapping;   // 数据纹理,不能做 sRGB 解码
  return t;
}
/* V3 月亮:柔光 + 环形山,普通混合(不像太阳那样过曝) */
function moonTex() {
  var s = 160, cv = document.createElement('canvas'); cv.width = cv.height = s;
  var ctx = cv.getContext('2d'), c = s / 2;
  var halo = ctx.createRadialGradient(c, c, s * 0.22, c, c, s * 0.5);
  halo.addColorStop(0, 'rgba(205,225,255,0.42)');
  halo.addColorStop(1, 'rgba(205,225,255,0)');
  ctx.fillStyle = halo; ctx.fillRect(0, 0, s, s);
  var disc = ctx.createRadialGradient(c - s * 0.12, c - s * 0.12, s * 0.02, c, c, s * 0.30);
  disc.addColorStop(0, '#fdfbf2'); disc.addColorStop(0.72, '#dce3ee');
  disc.addColorStop(1, '#a9b8cd');
  ctx.fillStyle = disc; ctx.beginPath(); ctx.arc(c, c, s * 0.29, 0, Math.PI * 2); ctx.fill();
  var seed = 3319;
  function sr() { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed / 4294967296; }
  for (var i = 0; i < 11; i++) {
    var a = sr() * Math.PI * 2, rr = sr() * s * 0.22;
    var x = c + Math.cos(a) * rr, y = c + Math.sin(a) * rr, rad = s * (0.012 + sr() * 0.032);
    ctx.fillStyle = 'rgba(120,138,162,0.20)';
    ctx.beginPath(); ctx.arc(x, y, rad, 0, Math.PI * 2); ctx.fill();
  }
  var t = new THREE.CanvasTexture(cv); t.colorSpace = THREE.SRGBColorSpace; return t;
}
/* V6 脚印贴图:一枚软蹄印 + 三个小趾 */
function footTex() {
  var s = 64, cv = document.createElement('canvas'); cv.width = cv.height = s;
  var ctx = cv.getContext('2d');
  var g = ctx.createRadialGradient(s / 2, s / 2, 1, s / 2, s / 2, s * 0.46);
  g.addColorStop(0, 'rgba(255,255,255,0.95)');
  g.addColorStop(0.55, 'rgba(255,255,255,0.50)');
  g.addColorStop(1, 'rgba(255,255,255,0)');
  ctx.fillStyle = g;
  ctx.beginPath(); ctx.ellipse(s / 2, s / 2, s * 0.40, s * 0.30, 0, 0, Math.PI * 2); ctx.fill();
  ctx.fillStyle = 'rgba(255,255,255,0.80)';
  for (var i = 0; i < 3; i++) {
    ctx.beginPath(); ctx.arc(s * 0.32 + i * s * 0.18, s * 0.29, s * 0.052, 0, Math.PI * 2); ctx.fill();
  }
  var t = new THREE.CanvasTexture(cv); t.colorSpace = THREE.SRGBColorSpace; return t;
}
/* V3 极光 + 流星:独立 2D 图层,低分辨率绘制,不占 WebGL draw call。
   流星用 Math.random(),刻意不消耗全局 rand() ⇒ 不扰动落点采样。 */
var fxCanvas = null, fxCtx = null, fxW = 0, fxH = 0, fxScale = 0.70;
var meteors = [], nextMeteor = 3.2;
function resizeFx(w, h) {
  fxCanvas = document.getElementById('fx');
  if (!fxCanvas) return;
  fxW = Math.max(1, Math.round(w * fxScale));
  fxH = Math.max(1, Math.round(h * fxScale));
  fxCanvas.width = fxW; fxCanvas.height = fxH;
  fxCtx = fxCanvas.getContext('2d');
}
function drawFx(now, dt) {
  if (!fxCtx) return;
  var c = fxCtx, w = fxW, h = fxH, t = now * 0.001;
  c.clearRect(0, 0, w, h);
  c.globalCompositeOperation = 'lighter';
  for (var r = 0; r < 3; r++) {
    var yb = h * (0.05 + r * 0.042);
    var grad = c.createLinearGradient(0, yb - h * 0.08, 0, yb + h * 0.14);
    grad.addColorStop(0, 'rgba(90,220,190,0)');
    grad.addColorStop(0.45, r === 1 ? 'rgba(120,170,255,0.070)' : 'rgba(90,235,190,0.082)');
    grad.addColorStop(1, 'rgba(160,120,255,0)');
    c.fillStyle = grad;
    c.beginPath(); c.moveTo(0, yb);
    for (var x = 0; x <= w; x += 14)
      c.lineTo(x, yb + Math.sin(x * 0.008 + t * 0.32 + r * 1.7) * h * 0.026
                     + Math.sin(x * 0.021 - t * 0.19 + r) * h * 0.010);
    c.lineTo(w, yb + h * 0.16); c.lineTo(0, yb + h * 0.16);
    c.closePath(); c.fill();
  }
  nextMeteor -= dt;
  if (nextMeteor <= 0) {
    nextMeteor = CONFIG.meteorMin + Math.random() * (CONFIG.meteorMax - CONFIG.meteorMin);
    meteors.push({ x: w * (0.15 + Math.random() * 0.70), y: -18,
                   vx: -(90 + Math.random() * 90) * fxScale,
                   vy: (150 + Math.random() * 110) * fxScale,
                   life: 0, max: 0.85 + Math.random() * 0.5 });
  }
  for (var m = meteors.length - 1; m >= 0; m--) {
    var q = meteors[m];
    q.life += dt; q.x += q.vx * dt; q.y += q.vy * dt;
    var k = 1 - q.life / q.max;
    if (k <= 0 || q.y > h * 1.1) { meteors.splice(m, 1); continue; }
    var tx = q.x - q.vx * 0.17, ty = q.y - q.vy * 0.17;
    var lg = c.createLinearGradient(q.x, q.y, tx, ty);
    lg.addColorStop(0, 'rgba(255,255,255,' + (0.82 * k).toFixed(3) + ')');
    lg.addColorStop(1, 'rgba(180,220,255,0)');
    c.strokeStyle = lg; c.lineWidth = 1.6 * fxScale; c.lineCap = 'round';
    c.beginPath(); c.moveTo(q.x, q.y); c.lineTo(tx, ty); c.stroke();
  }
  c.globalCompositeOperation = 'source-over';
}

/* ---------- scene ---------- */
async function initApp() {
  var skyCanvas = document.getElementById('sky');
  if (skyCanvas) drawStarfield(skyCanvas);
  var stage = document.getElementById('stage');
  var renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.15;
  renderer.setClearAlpha(0);
  stage.appendChild(renderer.domElement);

  var scene = new THREE.Scene();
  var camera = new THREE.PerspectiveCamera(CONFIG.fov, 1, 0.1, 100);
  camera.position.set(0, 0.55, CONFIG.camDist);
  camera.lookAt(0, 0, 0);

  /* V1 昼夜(v6.1e):旧太阳方向 (3,4,6) 几乎正对相机,正面圆盘全白昼,
     晨昏线跑到球背面 ⇒ 用户反馈「昼夜看不到」。改成右侧偏上的侧光,
     让晨昏线穿过正面圆盘;同时压低环境光/半球光、略提太阳光拉开明暗。
     SUN_DIR 同时供 nightLayer / nightShade / oceanLayer 使用,单一数据源。 */
  var SUN_DIR = new THREE.Vector3(1, 0.45, 0.55).normalize();
  var ambientLight = new THREE.AmbientLight(0xffffff, 0.42);
  var hemiLight = new THREE.HemisphereLight(0xd8f0ff, 0x8fae66, 0.45);
  var sunLight = new THREE.DirectionalLight(0xfff3d6, 2.4);
  sunLight.position.copy(SUN_DIR).multiplyScalar(10);
  scene.add(ambientLight); scene.add(hemiLight); scene.add(sunLight);

  // tilted axis + spin
  var tiltPivot = new THREE.Group();
  tiltPivot.rotation.z = CONFIG.tilt; // 23.5°
  scene.add(tiltPivot);

  var earthRes = await getEarthResources();
  // A2 地形凹凸 + A3 海洋高光:同一份 elevation 派生的两张灰度图
  var earthMat = new THREE.MeshStandardMaterial({
    map: earthRes.texture,
    bumpMap: earthRes.bump, bumpScale: 0.012,
    roughnessMap: earthRes.rough, roughness: 1.0, metalness: 0.0
  });
  var earth = new THREE.Mesh(new THREE.SphereGeometry(1, 64, 64), earthMat);
  tiltPivot.add(earth);

  /* V6.3 陆地季节色层:一个比地表略大的球壳,按季节给陆地染色。
     · 冬季:整块陆地盖白(积雪),海洋不着色
     · 秋季:草地变黄(只染 cls=2/6 的草地,森林/岩石保留原色)
     挂在 earth 下 ⇒ 随地球自转,色层始终盖在同一块陆地上。
     半径 1.0012 ⇒ 刚好浮在地表之上,不会被 bumpMap 的法线扰动穿帮。
     ⚠️ 用自定义 shader 而非 alphaMap + alphaTest:
        alphaTest 是硬阈值 ⇒ 阈值处「整块大陆突然出现/消失」
        (用户实测:秋冬交界积雪突然出现、冬春交界突然消失)。
        这里 alpha 由掩膜权重 × 季节强度【连续】插值 ⇒ 全程无突变。 */
  var landSeason = new THREE.Mesh(
    new THREE.SphereGeometry(1.0012, 64, 64),
    new THREE.ShaderMaterial({
      uniforms: {
        tMask: { value: makeLandSeasonMaskTexture() },
        tEarth: { value: earthRes.texture },   // 原地表贴图(取亮度做细节)
        uGrassColor: { value: new THREE.Color(0x8f8f4a) },  // 当前季节草地色(逐季插值)
        uSnowColor: { value: new THREE.Color(0xf4f8ff) },    // 冬天积雪色(微冷白)
        uGrassAmt: { value: 0 },     // 草地染色强度(秋天=1,其它季=0)
        uSnowAmt: { value: 0 },      // 积雪强度
        uSun: { value: SUN_DIR }
      },
      vertexShader: [
        'varying vec2 vUv; varying vec3 vWN;',
        'void main(){',
        '  vUv = uv;',
        '  vWN = normalize(mat3(modelMatrix) * normal);',
        '  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);',
        '}'
      ].join('\n'),
      fragmentShader: [
        'uniform sampler2D tMask; uniform sampler2D tEarth;',
        'uniform vec3 uGrassColor; uniform vec3 uSnowColor;',
        'uniform float uGrassAmt; uniform float uSnowAmt;',
        'uniform vec3 uSun;',
        'varying vec2 vUv; varying vec3 vWN;',
        'void main(){',
        '  vec3 m = texture2D(tMask, vUv).rgb;',
        '  float grass = m.r, land = m.g, hNorm = m.b;',
        '  // 原地表贴图的亮度(保留纹理细节,别涂成一片死色)',
        '  vec3 earth = texture2D(tEarth, vUv).rgb;',
        '  float elum = dot(earth, vec3(0.299, 0.587, 0.114));',
        '  // 积雪:整块陆地都盖,高处更厚;细节用地表亮度调制',
        '  // ⚠️ 雪的 alpha 必须【完全不透明】(land=1 时就是 1):',
        '  //    留一点透明(0.8)就会让底下的草地黄/绿透出来 ⇒ 交界处又看到色变。',
        '  //    高低起伏改成调制【颜色】而不是 alpha。',
        '  float snowA = land * uSnowAmt;',
        '  float snowDetail = (0.86 + 0.30 * elum) * (0.93 + 0.09 * hNorm);',
        '  // 草地染色强度(秋天把草地染黄;其它季=0 ⇒ 露出本来的绿)',
        '  float grassA = grass * uGrassAmt;',
        '  // 草地色保留原贴图的明暗(有纹理,不与沙滩糊成一片)',
        '  float grassDetail = 0.78 + 0.44 * elum;',
        '  if (grassA <= 0.002 && snowA <= 0.002) discard;',
        '  // ⚠️ 合成顺序至关重要(用户 2026-11-07 明确指出):',
        '  //   底层 = 草地色(随季节连续插值:秋天黄 → 冬天绿)',
        '  //   上层 = 雪(连续插值)',
        '  //   用 over 合成让白色【连续地盖住】底色 ⇒',
        '  //     秋冬交界:黄 → 连续变白(底下的黄→绿变化被白盖住,看不见)',
        '  //     冬春交界:白 → 连续显出绿色。',
        '  //   若用「二选一」(snowA > a 就换成雪),草地黄的 alpha 会先掉、',
        '  //   透出下面的绿 ⇒ 看起来「黄跳变成绿,再逐渐变白」(实测反馈)。',
        '  vec3 gCol = uGrassColor * grassDetail;',
        '  vec3 sCol = uSnowColor * snowDetail;',
        '  vec3 col;',
        '  float a;',
        '  if (snowA > 0.0) {',
        '    // 雪在上层:草地按被覆盖比例残留,雪按比例替换',
        '    float sc = clamp(snowA, 0.0, 1.0);',
        '    float gA = grassA * (1.0 - sc);',
        '    float w = gA + sc;',
        '    col = (gCol * gA + sCol * sc) / max(w, 1e-4);',
        '    a = w;',
        '  } else {',
        '    col = gCol;',
        '    a = grassA;',
        '  }',
        '  if (a <= 0.002) discard;',
        '  // 受光:与地表同一套光照口径(昼面亮、夜面暗),避免白得刺眼',
        '  float d = dot(normalize(vWN), normalize(uSun));',
        '  float day = 0.55 + 0.45 * smoothstep(-0.30, 0.45, d);',
        '  gl_FragColor = vec4(col * day, min(a, 1.0));',
        '}'
      ].join('\n'),
      transparent: true, depthWrite: false
    })
  );
  landSeason.renderOrder = 1;
  landSeason.visible = false;
  earth.add(landSeason);

  /* ---------- V6.4 山脉层(路线 B:叠加山体,不动球面假设) ----------
     地球本体保持【完美球面】⇒ 动物 / 树 / 接触阴影 / 脚印 / 龙卷风的贴地
     逻辑【零改动】⇒ 硬约束「全部陆地贴地、动画过程零缩放」不受影响。
     山脉是一层独立的分辨率壳体:
       · 顶点沿径向抬起(高度场用页面同源的 hash3/vnoise3/fbm3 现场算)
       · 自带岩 / 雪着色,随 earth 一起自转 ⇒ 山长在原地
       · 山脚【埋进地表以下】(几何上),露出地表的部分自然形成山脚线
     ⚠️ 不读 terrain.png ⇒ 无需重新烘焙那 6 张贴图。
     ⚠️ 山峰会盖住 landSeason 的积雪壳(半径 1.0012,在山体内部)
        ⇒ 冬季的雪山由山体材质自己响应季节(uniform uMtnSnow)。
     ⚠️ 夜面压暗壳(1.0025)也在山体内部 ⇒ 山体材质自带同一套昼夜压暗,
        否则山在夜面会比周围地面亮一截。
     ⚠️ 树木 / 动物要避开山体(否则会埋在岩里)⇒ mtnBlock() 供落点过滤。 */
  var MTN_W = 256, MTN_H = 128;
  var MTN_AMP = CONFIG.mountainAmp;
  var MTN_SINK = 0.004;      // 山脚埋入地表的深度(世界单位)
  var MTN_MIN = 0.02;         // 归一化山势阈值(低于此不成山)
  var mtnField = new Float32Array(MTN_W * MTN_H);
  (function buildMountainField() {
    for (var j = 0; j < MTN_H; j++) {
      var v = (j + 0.5) / MTN_H;
      for (var i = 0; i < MTN_W; i++) {
        var u = (i + 0.5) / MTN_W;
        var pp = spherePoint(u, v);
        // ① 山带:大尺度噪声决定山脉出现在哪(成带,不是均匀撒)
        var rg = fbm3(pp[0] * 2.1 + 40.5, pp[1] * 2.1 + 11.2, pp[2] * 2.1 + 77.3, 4);
        var mask = clamp01((rg * 0.5 + 0.5 - 0.625) / 0.135);
        mask = mask * mask * (3 - 2 * mask);
        if (mask <= 0.001) continue;
        // ② 山脊:多倍频 ridged noise(脊线锐利)
        var rid = 0, amp = 1, fq = 7.0, norm = 0, o;
        for (o = 0; o < 4; o++) {
          var nz = fbm3(pp[0] * fq + 3.3, pp[1] * fq + 8.8, pp[2] * fq + 1.4, 2);
          rid += (1 - Math.abs(nz)) * amp; norm += amp; amp *= 0.5; fq *= 2.1;
        }
        mtnField[j * MTN_W + i] = Math.pow(Math.max(0, rid / norm), 3.0) * mask;
      }
    }
  })();

  // 归一化山势(双线性)
  function mtnRawAt(u, v) {
    var x = (u % 1) * MTN_W - 0.5, y = v * MTN_H - 0.5;
    var x0 = Math.floor(x), y0 = Math.floor(y), tx = x - x0, ty = y - y0;
    function g(a, b) {
      var aa = ((a % MTN_W) + MTN_W) % MTN_W;
      var bb = Math.min(MTN_H - 1, Math.max(0, b));
      return mtnField[bb * MTN_W + aa];
    }
    return g(x0, y0) * (1 - tx) * (1 - ty) + g(x0 + 1, y0) * tx * (1 - ty) +
           g(x0, y0 + 1) * (1 - tx) * ty + g(x0 + 1, y0 + 1) * tx * ty;
  }
  // 山体高度(相对半径抬升,世界单位)
  function mtnHeightAt(u, v) {
    var m = mtnRawAt(u, v);
    if (m <= MTN_MIN) return 0;
    var e = elevation(u, v);
    if (e < 0.455) return 0;                      // 海面不长山
    var inland = clamp01((e - 0.47) / 0.05);       // 离岸衰减,山不直接顶到海岸
    if (inland <= 0) return 0;
    // 【最终决定】保留 (m-MTN_MIN)/(1-MTN_MIN) 归一化。理由:归一化后
    //    MTN_AMP 即峰高的绝对上界,与噪声场幅值(pow3 指数 / seed 漂移)
    //    解耦 —— 重调种子不会把山体整体抬高或压没。
    //    旧注释的"归一化把典型山势压到 0.005 半径 → 几乎贴地看不见"是
    //    MTN_AMP≈0.0625 时代的算式;当前 MTN_AMP=0.185 数值复算
    //    (logs_temp/glb_probe/mtn_peak_check.mjs,elevation 用未烘焙兜底近似):
    //    peak ≈ 0.139 半径、起伏带 0.02~0.08、63% 非零顶点 ≥1% 半径,
    //    山体清晰可见;且归一化与"直接用 m"在峰处仅差 0.7%、典型山坡差
    //    ≈15% ⇒ 视觉上可互换,保留边界稳定这一优势。
    return (m - MTN_MIN) / (1 - MTN_MIN) * MTN_AMP * inland;
  }
  // 该点是否在山体上(树木 / 动物落点要避开)
  function mtnBlock(u, v) { return mtnHeightAt(u, v) > MTN_SINK * 2.2; }

  // 几何:球壳顶点径向抬起;山脚埋入地表以下
  var mtnGeo = new THREE.SphereGeometry(1, MTN_W, MTN_H);
  var mtnPos = mtnGeo.attributes.position;
  var mtnAttr = new Float32Array(mtnPos.count);
  var mtnPeak = 0;
  for (var mi2 = 0; mi2 < mtnPos.count; mi2++) {
    var ix = mi2 % (MTN_W + 1), iy = (mi2 / (MTN_W + 1)) | 0;
    var mu = ix / MTN_W, mv = iy / MTN_H;
    var mh = mtnHeightAt(mu, mv);
    if (mh > mtnPeak) mtnPeak = mh;
    var md = surfaceDir(mu, mv);
    var mr = 1 + mh - MTN_SINK;
    mtnPos.setXYZ(mi2, md[0] * mr, md[1] * mr, md[2] * mr);
    mtnAttr[mi2] = mh;
  }
  mtnGeo.setAttribute('aMtn', new THREE.BufferAttribute(mtnAttr, 1));
  mtnGeo.computeVertexNormals();

  /* ---- 局部峰顶包络(±MTN_ENV_R 格内的最大高度)----
     ⚠️ 为什么需要:山脚的「草裙 / 岩石带 / 常年雪帽」若按【绝对高度】划分,
        矮山整座都会落在草裙区间里(看起来只有一片草地没有山),
        高山则大半截被雪盖住(看起来是一团白)。
     ⇒ 改按【相对本地峰顶的高度比例】划分:每座山都有自己的草裙与雪帽,
        与山有多高无关。 */
  var MTN_ENV_R = 10;
  var W2 = MTN_W + 1, H2 = MTN_H + 1;
  var mtnEnv = new Float32Array(mtnAttr.length), mtnEnv2 = new Float32Array(mtnAttr.length);
  (function () {
    var x, y, dx, dy, xx, yy, m, t;
    for (y = 0; y < H2; y++) {                 // 横向 max(可分离滤波)
      for (x = 0; x < W2; x++) {
        m = 0;
        for (dx = -MTN_ENV_R; dx <= MTN_ENV_R; dx++) {
          xx = x + dx; if (xx < 0) xx += W2; else if (xx >= W2) xx -= W2;
          t = mtnAttr[y * W2 + xx]; if (t > m) m = t;
        }
        mtnEnv[y * W2 + x] = m;
      }
    }
    for (x = 0; x < W2; x++) {                 // 纵向 max(用第二缓冲,避免原地污染)
      for (y = 0; y < H2; y++) {
        m = 0;
        for (dy = -MTN_ENV_R; dy <= MTN_ENV_R; dy++) {
          yy = y + dy; if (yy < 0) yy += H2; else if (yy >= H2) yy -= H2;
          t = mtnEnv[yy * W2 + x]; if (t > m) m = t;
        }
        mtnEnv2[y * W2 + x] = m;
      }
    }
  })();
  var mtnRel = new Float32Array(mtnAttr.length);
  for (var mi3 = 0; mi3 < mtnRel.length; mi3++) {
    var ev = mtnEnv2[mi3];
    mtnRel[mi3] = ev > 1e-5 ? Math.min(1, mtnAttr[mi3] / ev) : 0;
  }
  mtnGeo.setAttribute('aMtnRel', new THREE.BufferAttribute(mtnRel, 1));

  var mtnSnowU = { value: 0 };     // 冬季积雪强度
  var mtnWarmU = { value: 0 };     // 秋季岩石偏暖
  // 山脚草地跟随大陆四季:春夏=0(露出原贴图绿),秋天=grassYellowK
  var mtnGrassU = { value: new THREE.Vector3(GRASS_YELLOW[0], GRASS_YELLOW[1], GRASS_YELLOW[2]) };
  var mtnGrassAmtU = { value: 0 };
  var mtnMat = new THREE.MeshStandardMaterial({
    map: earthRes.texture, roughness: 0.94, metalness: 0.0
  });
  mtnMat.onBeforeCompile = function (sh) {
    sh.uniforms.uMtnSnow = mtnSnowU;
    sh.uniforms.uMtnWarm = mtnWarmU;
    sh.uniforms.uMtnGrass = mtnGrassU;
    sh.uniforms.uMtnGrassAmt = mtnGrassAmtU;
    // 山脚裁切高度:山体壳与地球面(64×64 多面体,面中心比半径 1 低约 0.0012)
    //   在山脚一圈几乎共面 ⇒ 互相穿插 ⇒ z-fighting(实测出现同心涟漪)。
    //   做法:低于此高度的片元直接丢弃 —— 那里本来就该由地球面露出,
    //   颜色本来就与山脚一致(foot≈1 ⇒ 用的是地球贴图原色)。
    //   裁切面取 1.0018 > landSeason 壳半径 1.0012 ⇒ 大陆的季节色层
    //   永远落在山体壳内部(被山体挡住),山体自带季节色,两层不打架。
    sh.uniforms.uMtnCut = { value: MTN_SINK + 0.0018 };
    // 复用陆地季节色层的草地掩膜(R=草地)⇒ 山脚与大陆四季完全同源
    sh.uniforms.tMask = { value: landSeason.material.uniforms.tMask.value };
    // 自带一份地表贴图:用来取【未染色】的亮度(与 landSeason 的 elum 同口径)
    sh.uniforms.uMtnTex = { value: earthRes.texture };
    sh.uniforms.uMtnSun = { value: SUN_DIR };
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>',
        '#include <common>\nattribute float aMtn;\nattribute float aMtnRel;\nvarying float vMtn;\nvarying float vMtnRel;\nvarying vec3 vMtnWN;\nvarying vec2 vMtnUv;')
      .replace('#include <begin_vertex>',
        '#include <begin_vertex>\n  vMtn = aMtn;\n  vMtnRel = aMtnRel;\n  vMtnUv = uv;\n  vMtnWN = normalize(mat3(modelMatrix) * normal);');
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>',
        '#include <common>\nvarying float vMtn;\nvarying float vMtnRel;\nvarying vec3 vMtnWN;\nvarying vec2 vMtnUv;\nuniform float uMtnSnow;\nuniform float uMtnWarm;\nuniform vec3 uMtnGrass;\nuniform float uMtnGrassAmt;\nuniform float uMtnCut;\nuniform sampler2D tMask;\nuniform sampler2D uMtnTex;\nuniform vec3 uMtnSun;')
      .replace('#include <map_fragment>', [
        '  // 山脚裁切:低于 uMtnCut 的片元直接丢弃。',
        '  //   那里山体壳与地球面(64×64 多面体)几乎共面 ⇒ 互相穿插 ⇒',
        '  //   z-fighting(实测山脚出现同心涟漪花纹)。',
        '  //   裁切面恒在半径 1.0018(> landSeason 壳 1.0012)⇒ 两层不打架。',
        '  if (vMtn < uMtnCut) discard;',
        '#include <map_fragment>',
        '  // ---- 山脚与大陆平滑衔接 ----',
        '  // vMtnRel = 该点高度 / 本座山峰顶高度(0=山脚,1=峰顶)',
        '  // 草裙与雪帽都按【相对高度】划分 ⇒ 每座山都有自己的植被裙边与雪帽,',
        '  //   不受这座山本身有多高影响(矮山不会整座都是草,高山不会半身雪)。',
        '  float foot = 1.0 - smoothstep(0.04, 0.34, vMtnRel);',
        '  // 未染色的地表采样:与 landSeason 层共用同一张贴图、同一口径,',
        '  //   ⇒ 山脚颜色与大陆逐像素对得上(染色只影响岩石,不影响衔接)。',
        '  vec3 texCol = texture2D(uMtnTex, vMtnUv).rgb;',
        '  float ml = dot(texCol, vec3(0.299, 0.587, 0.114));',
        '  float tl = dot(diffuseColor.rgb, vec3(0.299, 0.587, 0.114));',
        '  // 岩石固有色(中性偏暖灰)而不是直接拿脚下的地表色当岩石',
        '  //   ⇒ 山体落在沙滩/草地上时也不会变成「一座沙山」;',
        '  //   再乘季节染色(diffuse = 本帧的 tint),与大陆四季色调一致。',
        '  vec3 rockTone = vec3(0.44, 0.415, 0.385) * diffuse;',
        '  vec3 rock = mix(diffuseColor.rgb, rockTone * (0.62 + 0.66 * ml), (1.0 - foot) * 0.93);',
        '  // 山脚草地:与大陆共用同一张草地掩膜(tMask.R)+ 同一个季节强度',
        '  //   (uMtnGrassAmt ≡ 大陆的 grassYellowK)⇒ 春夏露出原贴图绿、',
        '  //   秋天随大陆一起泛黄 ⇒ 两者同源、平滑结合,不会各变各的。',
        '  float mtnGrass = texture2D(tMask, vMtnUv).r;',
        '  float gAmt = clamp(mtnGrass * uMtnGrassAmt * foot, 0.0, 1.0);',
        '  // 秋天岩石偏暖(草地部分不吃暖色,否则会与大陆的枯黄对不上)',
        '  rock = mix(rock, rock * vec3(1.12, 0.92, 0.74), uMtnWarm * (1.0 - gAmt));',
        '  vec3 gCol = uMtnGrass * (0.78 + 0.44 * ml) * 0.62;',
        '  rock = mix(rock, gCol, gAmt);',
        '  // 常年积雪:按【相对峰顶高度】给雪帽(只有顶部约 20% 才盖白)',
        '  //   ⇒ 雪是「帽」而不是「半身」,山体轮廓仍然读得出来。',
        '  float alp = smoothstep(0.78, 0.97, vMtnRel);',
        '  float sn = clamp(uMtnSnow + alp * 0.85, 0.0, 1.0);',
        '  // 雪的明暗调制与 landSeason 层同一口径(0.86+0.30*elum)⇒ 冬季山雪',
        '  //   与大陆积雪的纹理密度一致,不会一块纯白一块有细节。',
        '  vec3 snowCol = vec3(0.95, 0.97, 1.0) * (0.86 + 0.30 * ml);',
        '  diffuseColor.rgb = mix(rock, snowCol, sn);',
        '  // 夜面压暗(与 nightShade 同一口径,否则山在夜里比周围亮)',
        '  float mdn = dot(normalize(vMtnWN), normalize(uMtnSun));',
        '  float mnight = 1.0 - smoothstep(-0.20, 0.16, mdn);',
        '  diffuseColor.rgb = mix(diffuseColor.rgb, vec3(0.04, 0.08, 0.20), mnight * 0.62);'
      ].join('\n'));
  };
  mtnMat.customProgramCacheKey = function () { return 'mtnSeason'; };
  var mtnMesh = new THREE.Mesh(mtnGeo, mtnMat);
  mtnMesh.frustumCulled = false;
  earth.add(mtnMesh);

  /* V6.3 全黑纹理:给夜面灯火当「关掉」的替身,
   保留 nightLayer 的着色器与调用点,改回一行即可恢复灯火。 */
function makeBlackTexture() {
  var cv = document.createElement('canvas'); cv.width = cv.height = 4;
  var ctx = cv.getContext('2d');
  ctx.fillStyle = '#000'; ctx.fillRect(0, 0, 4, 4);
  var t = new THREE.CanvasTexture(cv);
  t.colorSpace = THREE.SRGBColorSpace; t.wrapS = THREE.RepeatWrapping;
  return t;
}
/* V1 昼夜分界 + 城市灯火:世界法线与太阳方向的点积决定昼/夜,
     只在夜面叠暖色灯点。挂在 earth 下 ⇒ 随地球自转,灯点固定在城市上。 */
  var nightLayer = new THREE.Mesh(
    new THREE.SphereGeometry(1.003, 64, 64),
    new THREE.ShaderMaterial({
      uniforms: { tLights: { value: makeCityTexture() },
                  uSun: { value: SUN_DIR }, uTime: { value: 0 } },
      vertexShader: [
        'varying vec2 vUv; varying vec3 vWN;',
        'void main(){',
        '  vUv = uv;',
        '  vWN = normalize(mat3(modelMatrix) * normal);',
        '  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);',
        '}'
      ].join('\n'),
      fragmentShader: [
        'uniform sampler2D tLights; uniform vec3 uSun; uniform float uTime;',
        'varying vec2 vUv; varying vec3 vWN;',
        'void main(){',
        '  float d = dot(normalize(vWN), normalize(uSun));',
        '  float night = 1.0 - smoothstep(-0.20, 0.16, d);',
        '  vec3 c = texture2D(tLights, vUv).rgb;',
        '  float tw = 0.72 + 0.28 * sin(uTime * 1.6 + vUv.x * 120.0 + vUv.y * 55.0);',
        '  gl_FragColor = vec4(c * night * tw * 2.6, 1.0);',
        '}'
      ].join('\n'),
      transparent: true, depthWrite: false, blending: THREE.AdditiveBlending
    })
  );
  nightLayer.renderOrder = 2;
  // V6.3 用户要求:夜面城市灯火先去掉(2026-11-07)。
  //   保留 nightShade(夜面压暗 + 晨昏霞光)—— 那不是「灯光」,是昼夜分界。
  //   只需把 tLights 换成全黑纹理即可,代码与数据都还在,改回一行即可恢复。
  if (!CONFIG.nightLights) {
    nightLayer.material.uniforms.tLights.value = makeBlackTexture();
  }
  earth.add(nightLayer);
  /* V1 夜面压暗层(v6.1e):仅靠灯光衰减夜面仍偏亮,加一层贴地深的半透明
     冷蓝把夜半球压暗,晨昏带透出一道暖橙霞光;城市灯火 additive 叠在其上,
     于是「昼亮 / 晨昏暖 / 夜暗且有灯点」一眼可辨。半径 1.0025 紧贴地表,
     不会像大气体那样留下玻璃罩硬边;depthWrite=false 不遮挡动物和树。 */
  var nightShade = new THREE.Mesh(
    new THREE.SphereGeometry(1.0025, 64, 64),
    new THREE.ShaderMaterial({
      uniforms: { uSun: { value: SUN_DIR } },
      vertexShader: [
        'varying vec3 vWN;',
        'void main(){',
        '  vWN = normalize(mat3(modelMatrix) * normal);',
        '  gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);',
        '}'
      ].join('\n'),
      fragmentShader: [
        'uniform vec3 uSun; varying vec3 vWN;',
        'void main(){',
        '  float d = dot(normalize(vWN), normalize(uSun));',
        '  float night = 1.0 - smoothstep(-0.20, 0.16, d);',
        '  float twilight = smoothstep(-0.30, -0.02, d) * (1.0 - smoothstep(0.02, 0.30, d));',
        '  vec3 col = mix(vec3(0.04,0.08,0.20), vec3(0.95,0.45,0.18), twilight * 0.55);',
        '  gl_FragColor = vec4(col, night * 0.62);',
        '}'
      ].join('\n'),
      transparent: true, depthWrite: false
    })
  );
  nightShade.renderOrder = 1;
  earth.add(nightShade);
  /* V4 海面流动高光:海陆掩膜 + 太阳半程镜面 + 两组滚动正弦波纹,
     只在昼面的海上出光,UV 随时间流动 ⇒ 不是静止的一层白。 */
  var oceanLayer = new THREE.Mesh(
    new THREE.SphereGeometry(1.004, 64, 64),
    new THREE.ShaderMaterial({
      uniforms: { tMask: { value: makeOceanMaskTexture() },
                  uSun: { value: SUN_DIR }, uTime: { value: 0 } },
      vertexShader: [
        'varying vec2 vUv; varying vec3 vWN; varying vec3 vWP;',
        'void main(){',
        '  vUv = uv;',
        '  vWN = normalize(mat3(modelMatrix) * normal);',
        '  vec4 wp = modelMatrix * vec4(position, 1.0);',
        '  vWP = wp.xyz;',
        '  gl_Position = projectionMatrix * viewMatrix * wp;',
        '}'
      ].join('\n'),
      fragmentShader: [
        'uniform sampler2D tMask; uniform vec3 uSun; uniform float uTime;',
        'varying vec2 vUv; varying vec3 vWN; varying vec3 vWP;',
        'void main(){',
        '  float ocean = texture2D(tMask, vUv).r;',
        '  if (ocean < 0.5) discard;',
        '  vec3 N = normalize(vWN); vec3 L = normalize(uSun);',
        '  vec3 V = normalize(cameraPosition - vWP);',
        '  vec3 H = normalize(L + V);',
        '  float day = smoothstep(-0.05, 0.35, dot(N, L));',
        '  float glint = pow(max(dot(N, H), 0.0), 46.0);',
        '  float w1 = 0.5 + 0.5 * sin(vUv.x * 210.0 + uTime * 1.35 + sin(vUv.y * 64.0 - uTime * 0.7) * 2.2);',
        '  float w2 = 0.5 + 0.5 * sin(vUv.y * 150.0 - uTime * 1.05);',
        '  float a = day * (glint * 0.75 + w1 * w2 * 0.11) * 0.34;',
        '  gl_FragColor = vec4(vec3(0.62, 0.84, 1.0) * a, 1.0);',
        '}'
      ].join('\n'),
      transparent: true, depthWrite: false, blending: THREE.AdditiveBlending
    })
  );
  oceanLayer.renderOrder = 2;
  earth.add(oceanLayer);
  /* V3 月亮:远离太阳方向,极慢漂移,普通混合避免过曝 */
  var moonSprite = new THREE.Sprite(new THREE.SpriteMaterial({
    map: moonTex(), transparent: true, depthWrite: false, opacity: 0.92
  }));
  moonSprite.renderOrder = 7;
  scene.add(moonSprite);
  /* V3 月亮按屏幕锚点定位(v6.1d):
     旧写法把月亮钉死在世界坐标 (-2.45,0.95,-1.30),只有 aspect>=1.35 才入画,
     窄预览面板 / 竖屏会整个跑出视野(用户反馈「月亮没看到」)。
     改成用相机基向量把固定 NDC 锚点反投影到世界:任何宽高比都完整入画,
     屏幕尺寸恒定(NDC 高度 0.26),窄屏锚点略往内上移以躲开地球与标题。
     每帧只改 position 做极慢漂移,不做任何缩放动画。 */
  var MOON_NDC_H = 0.26;
  var moonAnchor = { d: 5.45, hw: 1, hh: 1, nx: -0.84, ny: 0.60 };
  var _mFwd = new THREE.Vector3(), _mRight = new THREE.Vector3(), _mUp = new THREE.Vector3();
  function updateMoonAnchor() {
    var halfV = Math.tan(camera.fov * Math.PI / 360);
    var d = camera.position.length() + 2.2;
    moonAnchor.d = d;
    moonAnchor.hw = d * halfV * camera.aspect;
    moonAnchor.hh = d * halfV;
    moonAnchor.nx = camera.aspect >= 1.3 ? -0.84 : -0.72;
    moonAnchor.ny = camera.aspect >= 1.3 ? 0.60 : 0.68;
    moonSprite.scale.setScalar(MOON_NDC_H * moonAnchor.hh);
    _mFwd.set(0, 0, -1).applyQuaternion(camera.quaternion);
    _mRight.set(1, 0, 0).applyQuaternion(camera.quaternion);
    _mUp.set(0, 1, 0).applyQuaternion(camera.quaternion);
  }
  function positionMoon(mt) {
    var a = moonAnchor;
    var nx = a.nx + Math.cos(mt) * 0.045, ny = a.ny + Math.sin(mt) * 0.035;
    moonSprite.position.copy(camera.position)
      .addScaledVector(_mFwd, a.d)
      .addScaledVector(_mRight, nx * a.hw)
      .addScaledVector(_mUp, ny * a.hh);
    if (window.__globeDebug) window.__globeDebug.moon = {
      ndc: [+nx.toFixed(3), +ny.toFixed(3)],
      aspect: +camera.aspect.toFixed(3),
      onScreen: Math.abs(nx) < 1 && Math.abs(ny) < 1
    };
  }

  // B8 双层云反向慢速 ⇒ 视差;第三层把云纹当 alpha,在地表投一层很淡的暗斑
  var cloudTexA = earthRes.cloudA, cloudTexB = earthRes.cloudB;
  var cloudMat = new THREE.MeshLambertMaterial({ map: cloudTexA, transparent: true, opacity: 0.44, depthWrite: false });
  var clouds = new THREE.Mesh(new THREE.SphereGeometry(1.07, 44, 36), cloudMat);
  clouds.renderOrder = 3;
  tiltPivot.add(clouds);
  var cloudMat2 = new THREE.MeshLambertMaterial({ map: cloudTexB, transparent: true, opacity: 0.20, depthWrite: false });
  var clouds2 = new THREE.Mesh(new THREE.SphereGeometry(1.095, 40, 32), cloudMat2);
  clouds2.rotation.y = 0.9;
  clouds2.renderOrder = 4;
  tiltPivot.add(clouds2);
  var cloudShadow = new THREE.Mesh(
    new THREE.SphereGeometry(1.002, 48, 36),
    new THREE.MeshBasicMaterial({ map: cloudTexA, color: 0x18324c, transparent: true,
                                  opacity: 0.07, depthWrite: false })
  );
  cloudShadow.renderOrder = 1;
  tiltPivot.add(cloudShadow);

  // B7 云雾大气(v5.1):彻底不用几何球。
  //   ⚠️ 任何大气球都会在自身轮廓处留下硬边:BackSide 大半径(1.10/1.26)
  //      会整个圆盘泛蓝,FrontSide 贴边(1.018/1.055)则变成细亮环,
  //      两者看起来都像玻璃罩。改成面向相机的径向渐变光晕:中心透明、
  //      贴地球轮廓处最亮、向外平滑淡出到 0 ⇒ 完全没有几何边界。
  //   sprite 直径 3.0(半宽 1.5),地球半径 1 落在 canvas 半径 0.333*s 处。
  function atmoGlowTex() {
    var s = 512, cv = document.createElement('canvas'); cv.width = cv.height = s;
    var ctx = cv.getContext('2d'), c = s / 2, seed = 7717;
    function sr() { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed / 4294967296; }
    var g = ctx.createRadialGradient(c, c, s * 0.315, c, c, s * 0.5);
    g.addColorStop(0.00, 'rgba(96,170,255,0)');
    g.addColorStop(0.05, 'rgba(120,195,255,0.42)');
    g.addColorStop(0.20, 'rgba(140,205,255,0.26)');
    g.addColorStop(0.48, 'rgba(160,215,255,0.11)');
    g.addColorStop(0.78, 'rgba(175,220,255,0.035)');
    g.addColorStop(1.00, 'rgba(175,220,255,0)');
    ctx.fillStyle = g; ctx.fillRect(0, 0, s, s);
    // 沿轮廓叠柔和大斑 ⇒ 辉光不是完美圆环,有云絮般的不均匀感
    ctx.globalCompositeOperation = 'lighter';
    for (var i = 0; i < 34; i++) {
      var ang = sr() * Math.PI * 2, rr = s * (0.32 + sr() * 0.14);
      var x = c + Math.cos(ang) * rr, y = c + Math.sin(ang) * rr;
      var rad = s * (0.035 + sr() * 0.085);
      var gg = ctx.createRadialGradient(x, y, 0, x, y, rad);
      gg.addColorStop(0, 'rgba(205,232,255,0.13)');
      gg.addColorStop(1, 'rgba(205,232,255,0)');
      ctx.fillStyle = gg; ctx.fillRect(x - rad, y - rad, rad * 2, rad * 2);
    }
    // 遮罩强制内圈透明、外圈归零,杜绝任何硬边(独立 LCG,不动全局 rand)
    ctx.globalCompositeOperation = 'destination-in';
    var mask = ctx.createRadialGradient(c, c, s * 0.30, c, c, s * 0.5);
    mask.addColorStop(0.00, 'rgba(0,0,0,0)');
    mask.addColorStop(0.12, 'rgba(0,0,0,1)');
    mask.addColorStop(0.65, 'rgba(0,0,0,0.65)');
    mask.addColorStop(1.00, 'rgba(0,0,0,0)');
    ctx.fillStyle = mask; ctx.fillRect(0, 0, s, s);
    ctx.globalCompositeOperation = 'source-over';
    var tex = new THREE.CanvasTexture(cv);
    tex.colorSpace = THREE.SRGBColorSpace;
    return tex;
  }
  var atmoGlow = new THREE.Sprite(new THREE.SpriteMaterial({
    map: atmoGlowTex(), transparent: true, depthWrite: false,
    blending: THREE.AdditiveBlending, opacity: 0.85
  }));
  atmoGlow.scale.set(3.0, 3.0, 1);
  atmoGlow.renderOrder = 0;
  scene.add(atmoGlow);

  var sunSprite = new THREE.Sprite(new THREE.SpriteMaterial({
    map: sunSpriteTex(), transparent: true, depthWrite: false, blending: THREE.AdditiveBlending
  }));
  sunSprite.position.set(3.2, 4.6, 6); sunSprite.scale.setScalar(3);
  sunSprite.renderOrder = 8;
  scene.add(sunSprite);
  // B9 多层柔光 + 三枚淡彩光斑(简化 lens flare)
  var halo = new THREE.Sprite(new THREE.SpriteMaterial({
    map: sunSprite.material.map, transparent: true, depthWrite: false,
    blending: THREE.AdditiveBlending, opacity: 0.30
  }));
  halo.position.copy(sunSprite.position); halo.scale.setScalar(7.5);
  halo.renderOrder = 8;
  scene.add(halo);
  var flareSpec = [
    { k: 0.55, s: 0.30, c: 0x9fd8ff, o: 0.20 },
    { k: 0.30, s: 0.16, c: 0xffd9a0, o: 0.16 },
    { k: -0.18, s: 0.42, c: 0xbfe4ff, o: 0.12 }
  ];
  for (var fi = 0; fi < flareSpec.length; fi++) {
    var flare = flareSpec[fi];
    var flSprite = new THREE.Sprite(new THREE.SpriteMaterial({
      map: glowTex(), color: flare.c, transparent: true, depthWrite: false,
      blending: THREE.AdditiveBlending, opacity: flare.o
    }));
    flSprite.position.set(3.2 * flare.k, 4.6 * flare.k, 6 * flare.k);
    flSprite.scale.setScalar(flare.s);
    flSprite.renderOrder = 8;
    scene.add(flSprite);
  }

  // C11 鸟群重做:40 个独立 Sprite(原 Points 是静止方形点、不会扇翅、
  //    也没挂到 tiltPivot)。三张纹理按相位切换 ⇒ 翅膀上下扇动。
  var BC = CONFIG.birds, birdData = [], birdMats = [], birdSprites = [];
  for (var bf = 0; bf < 3; bf++) {
    birdMats.push(new THREE.SpriteMaterial({
      map: birdTex(bf), transparent: true, depthWrite: false, opacity: 0.78
    }));
  }
  for (var bi = 0; bi < BC; bi++) {
    birdData.push({
      r: 1.10 + rand() * 0.30, lat: (rand() - 0.5) * 1.0,
      speed: (0.16 + rand() * 0.3) * (rand() > 0.5 ? 1 : -1),
      ph: rand() * Math.PI * 2, bob: 0.02 + rand() * 0.03,
      bf: 0.9 + rand() * 1.5, size: 0.055 + rand() * 0.035
    });
    var bsp = new THREE.Sprite(birdMats[0]);
    bsp.scale.set(birdData[bi].size * 1.6, birdData[bi].size, 1);
    bsp.renderOrder = 7;
    tiltPivot.add(bsp);
    birdSprites.push(bsp);
  }

  /* ---------- spacing helper: 全球均匀散布 ---------- */
  function angularDist(a, b) {
    var lat1 = (0.5 - a.v) * Math.PI, lat2 = (0.5 - b.v) * Math.PI;
    var dLat = lat2 - lat1, dLon = (b.u - a.u) * Math.PI * 2;
    var h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * Math.asin(Math.min(1, Math.sqrt(h)));
  }
  // 所有物种共用一张占位表,避免鹿 / 兔 / 树各挑各的、堆在同一片大陆上
  var occupied = [];
  function pickSpots(list, count, min) {
    // 候选点来自纹理的逐像素扫描(北极→南极、u=0→1)。按原顺序贪心取点会把
    // 模型全挤在少数几条经度带里;先洗牌 ⇒ 等价于全球陆地上的均匀采样。
    var pool = [];
    for (var pi = 0; pi < list.length; pi++)
      if (!mtnBlock(list[pi].u, list[pi].v)) pool.push(list[pi]);
    for (var i = pool.length - 1; i > 0; i--) {
      var j = Math.floor(rand() * (i + 1));
      var t = pool[i]; pool[i] = pool[j]; pool[j] = t;
    }
    var out = [];
    function attempt(m) {
      for (var i = 0; i < pool.length && out.length < count; i++) {
        var p = pool[i], ok = true, j;
        for (j = 0; j < out.length; j++) if (angularDist(p, out[j]) < m) { ok = false; break; }
        for (j = 0; ok && j < occupied.length; j++) if (angularDist(p, occupied[j]) < m) { ok = false; break; }
        if (ok) out.push(p);
      }
    }
    attempt(min); if (out.length < count) attempt(min * 0.6); if (out.length < count) attempt(min * 0.35);
    occupied.push.apply(occupied, out);
    return out;
  }

  /* 150 棵树若继续用 pickSpots() 的「全局最小间距」,只会均匀平铺,
     看不出森林。这里改成两级:
       ① 从深绿 forest 像素里挑 FOREST_CENTERS 个中心,彼此拉开、
          并避开已分配的动物落点;
       ② 每个中心周围按 sqrt(rand) 圆盘撒树,簇内不设最小间距。
     中心挑在 forest 上、簇内允许落到草地,但一律 elevation >= LAND_MIN。
     ⚠️ rad 是球面角距,换算到 (u,v) 要除以 2π·cosLat / π;直接把 rad
        当参数空间偏移会把簇放大 3~6 倍,树就摊平了(2026-11-07 实测踩过)。 */
  function pickForestSpots(count) {
    var pool = earthRes.forest.slice();
    if (pool.length < 2000) pool = pool.concat(earthRes.grass);
    for (var i = pool.length - 1; i > 0; i--) {
      var j = Math.floor(rand() * (i + 1));
      var t = pool[i]; pool[i] = pool[j]; pool[j] = t;
    }

    var centers = [], i, k;
    for (i = 0; i < pool.length && centers.length < FOREST_CENTERS; i++) {
      var p = pool[i];
      if (p.v < 0.15 || p.v > 0.85) continue;
      var ok = true;
      for (k = 0; k < centers.length; k++)
        if (angularDist(p, centers[k]) < CENTER_GAP) { ok = false; break; }
      for (k = 0; ok && k < occupied.length; k++)
        if (angularDist(p, occupied[k]) < CENTER_GAP * 0.8) { ok = false; break; }
      if (ok) centers.push({ u: p.u, v: p.v });
    }
    if (!centers.length) centers.push({ u: 0.5, v: 0.5 });  // 极端兜底

    var per = Math.ceil(count / centers.length) + 2;  // 多撒几棵,供海岸裁剪
    var out = [];
    for (var ci = 0; ci < centers.length && out.length < count; ci++) {
      var c = centers[ci];
      var cosLat = Math.max(0.3, Math.cos((0.5 - c.v) * Math.PI));
      var made = 0, tries = 0;
      while (made < per && out.length < count && tries < per * 40) {
        tries++;
        var ang = rand() * Math.PI * 2;
        // rad 是球面角距。换算到参数空间:du = rad/(2π·cosLat),dv = rad/π
        var rad = Math.sqrt(rand()) * CLUSTER_R;
        var u = (c.u + rad * Math.cos(ang) / (2 * Math.PI * cosLat) + 1) % 1;
        var v = c.v - rad * Math.sin(ang) / Math.PI;
        if (v <= 0.12 || v >= 0.88) continue;
        if (elevation(u, v) < LAND_MIN) continue;
        if (mtnBlock(u, v)) continue;          // V6.4 不把树种在山体里
        out.push({ u: +u.toFixed(5), v: +v.toFixed(5) });
        made++;
      }
    }
    // 万一某几个中心贴着海岸没撒够,继续在已有中心周围补点(保持簇状),
    // 绝不全局随机撒 —— 那会把森林重新摊平。
    var guard = 0;
    while (out.length < count && guard < count * 30) {
      guard++;
      var c2 = centers[Math.floor(rand() * centers.length)];
      var cl2 = Math.max(0.3, Math.cos((0.5 - c2.v) * Math.PI));
      var a2 = rand() * Math.PI * 2, r2 = Math.sqrt(rand()) * CLUSTER_R;
      var u2 = (c2.u + r2 * Math.cos(a2) / (2 * Math.PI * cl2) + 1) % 1;
      var v2 = c2.v - r2 * Math.sin(a2) / Math.PI;
      if (v2 <= 0.12 || v2 >= 0.88) continue;
      if (elevation(u2, v2) < LAND_MIN) continue;
      out.push({ u: +u2.toFixed(5), v: +v2.toFixed(5) });
    }
    occupied.push.apply(occupied, out);
    return out;
  }

  /* ---------- model catalog (CC0 / CC-BY — see header comment) ----------
     deer / rabbit / tree : Quaternius Ultimate Animal & Vegetation Pack (CC0 1.0)
     giraffe : elkiotbear via Poly Pizza (CC-BY 3.0)
     panda   : Poly by Google via Poly Pizza (CC-BY 3.0)

     ⚠️ 尺寸策略:动物和树一律不按地球真实比例。真实比例下一只鹿只有地球
     半径的千万分之一,屏幕上连 1px 都不到。这里给每个模型固定的展示高度
     height(单位 = 地球半径 R=1),约合屏幕 40~60px,清晰可辨;再用
     FOOTPRINT 限制水平跨度,防止扁平模型被拉成裹住地球的一圈带子。
  --------------------------------------------------------------- */
  var MODELS = [
    // 原有 4 种动物:每种只留 2 个实例,给同源的 12 种带动画动物腾出落点。
    // 熊猫 / 长颈鹿继续用程序化步态(用户已确认效果),尺寸与其余动物一致。
    { name: 'deer',    local: '/eco-globe/glb/deer.glb', count: 2, height: 0.115, kind: 'grass',  walk: true, gap: 0.20 },
    { name: 'rabbit',  local: '/eco-globe/glb/rabbit.glb', count: 2, height: 0.095, kind: 'grass',  walk: true, gap: 0.20 },
    // ⚠️ 实测 orient_probe.html:长颈鹿头顶顶点 100% 落在局部 +X(其余物种
    //    都在 +Z),而 stepWalkers 的 makeBasis 约定局部 +Z 为前进方向 ⇒
    //    必须绕局部 Y 转 -90° 把 +X 摆到前进方向,否则整只横着走。
    { name: 'giraffe', local: '/eco-globe/glb/giraffe.glb', count: 2, height: 0.145, kind: 'grass',  walk: true, gait: true, facing: -Math.PI / 2, gap: 0.20 },
    { name: 'panda',   local: '/eco-globe/glb/panda.glb', count: 2, height: 0.110, kind: 'forest', walk: true, gait: true, gap: 0.20 },

    // Quaternius Ultimate Animated Animals (CC0),经 Poly Pizza 的
    // Animated-Animal-Pack-ILAPXeUYiS 分发;加上上面的 deer 正好 12 种,
    // 全部自带骨骼 Walk 剪辑 ⇒ 直接复用 AnimationMixer,无需程序化步态。
    // 统一 height=0.115、setScalar 均匀缩放 ⇒ 与现有动物同尺寸且不拉伸。
    { name: 'cow', local: '/eco-globe/glb/cow.glb', count: 1, height: 0.115, kind: 'grass', walk: true, gap: 0.20 },
    { name: 'donkey', local: '/eco-globe/glb/donkey.glb', count: 1, height: 0.115, kind: 'grass', walk: true, gap: 0.20 },
    { name: 'alpaca', local: '/eco-globe/glb/alpaca.glb', count: 1, height: 0.115, kind: 'grass', walk: true, gap: 0.20 },
    { name: 'bull', local: '/eco-globe/glb/bull.glb', count: 1, height: 0.115, kind: 'grass', walk: true, gap: 0.20 },
    { name: 'horse', local: '/eco-globe/glb/horse.glb', count: 1, height: 0.115, kind: 'grass', walk: true, gap: 0.20 },
    { name: 'white_horse', local: '/eco-globe/glb/white_horse.glb', count: 1, height: 0.115, kind: 'grass', walk: true, gap: 0.20 },
    { name: 'stag', local: '/eco-globe/glb/stag.glb', count: 1, height: 0.115, kind: 'grass', walk: true, gap: 0.20 },
    { name: 'fox', local: '/eco-globe/glb/fox.glb', count: 1, height: 0.115, kind: 'both', walk: true, gap: 0.20 },
    { name: 'shiba_inu', local: '/eco-globe/glb/shiba_inu.glb', count: 1, height: 0.115, kind: 'both', walk: true, gap: 0.20 },
    { name: 'husky', local: '/eco-globe/glb/husky.glb', count: 1, height: 0.115, kind: 'both', walk: true, gap: 0.20 },
    { name: 'wolf', local: '/eco-globe/glb/wolf.glb', count: 1, height: 0.115, kind: 'both', walk: true, gap: 0.20 },

    // 150 棵(原 30 的 5 倍)走 pickForestSpots():簇状聚集,不按全局最小间距平铺
    { name: 'tree',    local: '/eco-globe/glb/tree.glb', count: 150, height: CONFIG.treeHeight, kind: 'both', split: true, forest: true }
  ];
  // P7 分级:主角 4 种 + 树先进队列,其余 11 种随后;tier 只影响发起顺序,
  //   落点在加载前已预分配 ⇒ 分布与网络到达顺序无关。
  var TIER0 = { deer: 1, rabbit: 1, giraffe: 1, panda: 1, tree: 1 };
  for (var _mi = 0; _mi < MODELS.length; _mi++) {
    MODELS[_mi].tier = TIER0[MODELS[_mi].name] ? 0 : 1;
    if (MODELS[_mi].name === 'tree') MODELS[_mi].instanced = true;  // P1
  }
  // P8:以下是 CONFIG 的热路径短别名,数值不再散落在页面各处
  var R = CONFIG.radius, SURF = R;
  var LAND_MIN = CONFIG.landMin, WALK_SPEED = CONFIG.walkSpeed, STEP_RATE = CONFIG.stepRate,
      SPIN_RATE = CONFIG.spinRate, CLOUD_RATE = CONFIG.cloudRate,
      FOOTPRINT = CONFIG.footprint, MIN_GAP = CONFIG.minGap,
      FOREST_CENTERS = CONFIG.forestCenters, CLUSTER_R = CONFIG.clusterR,
      CENTER_GAP = CONFIG.centerGap;

  /* 平底模型站在球面上:中心相切、四角却离地 a²/2R。按缩放后半宽 a
     把模型下沉 sag*0.6 + 0.0015,既吃掉边缘空隙又不至于陷进地表。 */
  function groundRadius(size, s) {
    var a = Math.max(size.x, size.z) * s * 0.5;
    var sag = R - Math.sqrt(Math.max(0, R * R - a * a));
    return R - sag * 0.6 - 0.0015;
  }
  function smooth01(t) { t = clamp01(t); return t * t * (3 - 2 * t); }

  /* ---------- GLTF load: 保留骨骼与动画 ----------
     实测(2026-11-07):这些 GLB 的骨骼节点确实 bake 了 100×/140× 缩放,
     但只要「测量前先 gltf.scene.updateMatrixWorld(true)」,Box3 就能得到
     干净的真实尺寸(deer 4.3159,而不是旧代码测到的虚高 439.4),缩放严格
     线性,连播 216 帧 Walk 动画尺寸也完全不变。
     ⇒ 上一版把 SkinnedMesh 逐顶点烘焙成静态 Mesh 属过度修复,现已撤销:
       保留骨骼 + AnimationMixer,动物才有真正的腿部走动动画。
     仍保留两条防线:
       ① 所有归一化变换只作用在干净的 outer / inner 容器上,绝不改写
          GLB 节点自身的 position / scale / rotation;
       ② 摆放前复测一次包围盒做闭环校准。
  --------------------------------------------------------------- */
  function hasMeshDeep(node) {
    var found = false;
    node.traverse(function (o) { if (o.isMesh) found = true; });
    return found;
  }

  /* 给没有骨骼的模型造「腿」:两个 relative morph target。
     局部 +Y 是上、局部 +Z 是前进方向(placeOnSphere / makeBasis 的约定)。
     按顶点高度加权(只动下半身),按 x/z 所在象限分组,让对角腿同相、
     另一对反相 ⇒ 迈步时四条腿交替前后摆。
     注意:绝不能在这里调 geometry.computeBoundingBox(),r160 会把
     morph 的极值并进 bbox,导致上面的归一化缩放把模型算小。 */
  function addLegMorph(mesh) {
    var geo = mesh.geometry;
    if (!geo || !geo.attributes || !geo.attributes.position) return;
    if (geo.morphAttributes.position && geo.morphAttributes.position.length) return;
    var pos = geo.attributes.position, n = pos.count;
    var y0 = Infinity, y1 = -Infinity, x0 = Infinity, x1 = -Infinity,
        z0 = Infinity, z1 = -Infinity, i;
    for (i = 0; i < n; i++) {
      var vx = pos.getX(i), vy = pos.getY(i), vz = pos.getZ(i);
      if (vy < y0) y0 = vy; if (vy > y1) y1 = vy;
      if (vx < x0) x0 = vx; if (vx > x1) x1 = vx;
      if (vz < z0) z0 = vz; if (vz > z1) z1 = vz;
    }
    var h = Math.max(1e-6, y1 - y0), xm = (x0 + x1) * 0.5, zm = (z0 + z1) * 0.5;
    var amp = h * 0.085;
    var A = new Float32Array(n * 3), B = new Float32Array(n * 3);
    for (i = 0; i < n; i++) {
      var x = pos.getX(i), y = pos.getY(i), z = pos.getZ(i);
      var leg = 1 - smooth01((y - y0) / h / 0.42);
      var phase = (x >= xm ? 1 : -1) * (z >= zm ? 1 : -1);
      var dz = amp * leg * phase, dx = amp * 0.12 * leg;
      // 关键:morph target 必须存「绝对位置 = 原始顶点 + 位移」,不能只存 delta。
      // SkeletonUtils.clone 不保证复制 mesh.morphTargetsRelative;一旦该标志
      // 为 false,shader 按 (morphTarget - position) * influence 计算,
      // 近零的 delta 会把顶点拉向原点 ⇒ 模型周期性「长大缩小」。
      A[i * 3] = x + dx; A[i * 3 + 1] = y; A[i * 3 + 2] = z + dz;
      B[i * 3] = x + dx; B[i * 3 + 1] = y; B[i * 3 + 2] = z - dz;
    }
    var aAttr = new THREE.Float32BufferAttribute(A, 3); aAttr.name = 'legFwdA';
    var bAttr = new THREE.Float32BufferAttribute(B, 3); bAttr.name = 'legFwdB';
    geo.morphAttributes.position = [aAttr, bAttr];
    mesh.morphTargetsRelative = false;
    mesh.updateMorphTargets();
  }

  function collectMorphs(root) {
    var out = [];
    root.traverse(function (o) {
      if (!o.isMesh || !o.geometry || !o.geometry.morphAttributes) return;
      var mp = o.geometry.morphAttributes.position;
      if (!mp || mp.length < 2) return;
      o.morphTargetsRelative = false;  // morph target 是绝对位置
      o.updateMorphTargets();          // clone 后重建独立的 influence 数组
      out.push(o);
    });
    return out;
  }

  function makePart(node, procedural) {
    var outer = new THREE.Group();   // placeOnSphere 只操作 outer
    var gait  = new THREE.Group();   // 程序化步态 pivot:原点就在模型底面中心
    gait.name = 'gait';
    var inner = new THREE.Group();   // 底面 / 居中补偿
    outer.add(gait);
    gait.add(inner);
    inner.attach(node);              // attach 保留世界变换(含祖先 bake 缩放)
    outer.updateMatrixWorld(true);
    var box = new THREE.Box3().setFromObject(outer);
    var size = box.getSize(new THREE.Vector3());
    var ctr = box.getCenter(new THREE.Vector3());
    // 底面归到 y=0、X/Z 居中;补偿放在 inner,outer 的 position 留给球面摆放
    inner.position.set(-ctr.x, -box.min.y, -ctr.z);
    outer.updateMatrixWorld(true);
    outer.userData.size = { x: size.x, y: size.y, z: size.z };
    outer.traverse(function (o) {
      if (o.isMesh) {
        o.material = o.material.clone();
        o.material.roughness = 0.92;
        o.material.metalness = 0.0;
        if (o.material.map) o.material.map.colorSpace = THREE.SRGBColorSpace;
        // 树叶原始材质是 alphaMode=BLEND(半透明):10 棵时不显眼,30 棵叠在
        // 草地上就会发白像「幽灵树」。改成 alphaTest 硬边镂空 ⇒ 颜色饱满、
        // 不参与透明排序,树冠轮廓也更清楚。
        if (o.material.transparent && o.material.map) {
          o.material.alphaTest = 0.42;
          o.material.transparent = false;
          o.material.depthWrite = true;
          o.material.needsUpdate = true;
        }
        if (procedural) addLegMorph(o);
      }
    });
    return outer;
  }

  /* 无骨骼动物的程序化步态剪辑。作用在名为 'gait' 的容器上。
     ⚠️ 只有 quaternion(摇摆/点头) 与 position.x(沿前进方向微摆):
        不动画 scale ⇒ 模型尺寸恒定;不动画 position.y ⇒ 脚底始终贴地。
     幅度按 1/scale 换算成世界单位。pivot 在底面中心 ⇒ 摇摆时脚不飞。 */
  function makeGaitClip(invScale, tag) {
    var N = 24, dur = 1.0, times = [], q = [], p = [], i;
    var e = new THREE.Euler(), quat = new THREE.Quaternion();
    for (i = 0; i <= N; i++) {
      var t = i / N, ph = t * Math.PI * 2;
      times.push(t * dur);
      e.set(0.042 * Math.sin(2 * ph + 0.6), 0.034 * Math.sin(ph + 1.1),
            0.075 * Math.sin(ph), 'XYZ');
      quat.setFromEuler(e);
      q.push(quat.x, quat.y, quat.z, quat.w);
      p.push(0.0009 * invScale * Math.sin(ph), 0, 0);
    }
    return new THREE.AnimationClip('ProceduralGait_' + tag, dur, [
      new THREE.QuaternionKeyframeTrack('gait.quaternion', times, q),
      new THREE.VectorKeyframeTrack('gait.position', times, p)
    ]);
  }

  function fetchGLTF(src) {
    return new Promise(function (resolve, reject) {
      new GLTFLoader().load(src, resolve, undefined, reject);
    });
  }

  function pickWalkClip(clips) {
    if (!clips || !clips.length) return null;
    // deer: "Walk"; rabbit: "CharacterArmature|...|Walk";
    // 排除 Walk_Holding / Idle,优先精确以 Walk 结尾的 clip
    var exact = null, loose = null;
    for (var i = 0; i < clips.length; i++) {
      var n = clips[i].name || '';
      if (/(^|\|)Walk$/i.test(n)) { if (!exact) exact = clips[i]; }
      else if (/walk/i.test(n) && !/holding/i.test(n)) { if (!loose) loose = clips[i]; }
    }
    return exact || loose || null;
  }

  function loadGLB(spec) {
    // ⛔ 运行时零网络：没有 CDN 回退，缺文件直接失败（方案 §4.5 / §九 R4）。
    return fetchGLTF(spec.local).then(function (gltf) {
      // ⚠️ 测量前必须刷新整棵世界矩阵。否则 SkinnedMesh 的 bindMatrixInverse
      // 还是旧值,Box3 会量到虚高 100× 的尺寸(旧版"巨鹿"的真正原因)。
      gltf.scene.updateMatrixWorld(true);
      var nodes = [];
      if (spec.split) {
        var direct = gltf.scene.children.filter(hasMeshDeep);
        if (direct.length >= 2) nodes = direct.slice();
        else if (gltf.scene.children.length === 1 && !gltf.scene.children[0].isMesh) {
          var kids = gltf.scene.children[0].children.filter(hasMeshDeep);
          if (kids.length >= 2) nodes = kids.slice();
        }
      }
      if (!nodes.length) nodes = [gltf.scene];
      var parts = nodes.map(function (nd) { return makePart(nd, !!spec.gait); });
      var clip = pickWalkClip(gltf.animations);
      console.log('[globe-present] ' + spec.name + ': ' + parts.length +
                  ' part(s), walk=' + (clip ? clip.name : 'none') +
                  ' / ' + (gltf.animations ? gltf.animations.length : 0) + ' clips, size=' +
                  parts.map(function (p) {
                    var z = p.userData.size;
                    return z.x.toFixed(2) + 'x' + z.y.toFixed(2) + 'x' + z.z.toFixed(2);
                  }).join(' | '));
      return { parts: parts, clip: clip };
    });
  }

  /* ---------- 摆放: 统一展示尺寸 + 贴球面 + 闭环校准 ---------- */
  var _placeUp = new THREE.Vector3(0, 1, 0), _yawQ = new THREE.Quaternion();
  /* 纯计算版:InstancedMesh 没有 Object3D 可挂 Box3 校准,直接用同一套
     size/u/v/height 公式得到 scale / 法线 / 朝向 / 贴地半径。 */
  function computePlace(size, u, v, targetHeight, yaw) {
    var hDen = size.y > 1e-9 ? size.y : Math.max(size.x, size.z, 1e-9);
    var wDen = Math.max(size.x, size.z, 1e-9);
    var s = Math.min(targetHeight / hDen, FOOTPRINT / wDen);
    var d = surfaceDir(u, v);
    var n = new THREE.Vector3(d[0], d[1], d[2]).normalize();
    var q = new THREE.Quaternion().setFromUnitVectors(_placeUp, n);
    q.multiply(_yawQ.setFromAxisAngle(_placeUp, yaw));   // 局部 Y = 地表法线
    return { scale: s, height: s * hDen, groundR: groundRadius(size, s), n: n, q: q };
  }
  function placeOnSphere(root, u, v, targetHeight) {
    var size = root.userData.size;
    var pl = computePlace(size, u, v, targetHeight, rand() * Math.PI * 2);
    var s = pl.scale, desiredH = pl.height;
    root.scale.setScalar(s);
    root.position.set(0, 0, 0);
    root.quaternion.identity();
    root.updateMatrixWorld(true);
    // 此时无旋转 ⇒ AABB 高度就是模型高度;偏差 >1% 再修正一次,兜住皮肤网格等
    var box = new THREE.Box3().setFromObject(root);
    var h = box.getSize(new THREE.Vector3()).y;
    if (h > 1e-9 && isFinite(h) && Math.abs(h - desiredH) / desiredH > 0.01) {
      s *= desiredH / h;
      root.scale.setScalar(s);
      pl.groundR = groundRadius(size, s);
    }
    root.quaternion.copy(pl.q);
    root.userData.groundR = pl.groundR;
    root.position.copy(pl.n).multiplyScalar(pl.groundR);
    return { scale: s, height: desiredH, groundR: pl.groundR, n: pl.n, q: pl.q };
  }

  /* ---------- async load + scatter all models ----------
     ⚠️ 不能等所有 GLB 到齐再统一摆放:tree 的 GLB 有 3.4MB(内嵌纹理),
     慢的时候要几十秒,旧写法会让地球先光秃秃地转很久,看着像"没有动物"。
     改为「落点先按固定顺序预分配 → 每个模型加载完立刻单独摆放」:
     先到的小模型几秒内就出现,分布依旧可复现(不依赖网络到达顺序)。
  --------------------------------------------------------------- */
  var outGroup = new THREE.Group();
  tiltPivot.add(outGroup);
  /* P1 合批①:169 个接触阴影贴片改成一个 InstancedMesh。
     共享纹理 + 共享几何 + 一个 draw call;走动的 19 只动物每帧只更新
     自己那一个实例的矩阵,150 棵树是静态的,一次写入后不再动。 */
  var shadowTex = contactShadowTex();
  var shadowMat = new THREE.MeshBasicMaterial({
    map: shadowTex, transparent: true, depthWrite: false, opacity: 0.72
  });
  var shadowBatch = new THREE.InstancedMesh(
    new THREE.PlaneGeometry(1, 1), shadowMat, CONFIG.shadowCap);
  shadowBatch.count = 0;
  shadowBatch.renderOrder = 2;
  shadowBatch.frustumCulled = false;
  outGroup.add(shadowBatch);
  var shadowSlot = 0;
  var _shUp = new THREE.Vector3(0, 0, 1), _shadowDummy = new THREE.Object3D();
  function setShadow(slot, n, width) {
    _shadowDummy.position.copy(n).multiplyScalar(R + 0.0035);
    _shadowDummy.quaternion.setFromUnitVectors(_shUp, n);
    _shadowDummy.scale.set(width, width, 1);
    _shadowDummy.updateMatrix();
    shadowBatch.setMatrixAt(slot, _shadowDummy.matrix);
    shadowBatch.instanceMatrix.needsUpdate = true;
  }
  function allocShadow(n, width) {
    if (shadowSlot >= CONFIG.shadowCap) return -1;
    var s = shadowSlot++;
    shadowBatch.count = shadowSlot;
    setShadow(s, n, width);
    return s;
  }
  /* P1 合批②:150 棵树。tree GLB 拆出的每个 part(5 种树形)里的每个 mesh
     单独建一个 InstancedMesh,实例数 = 用该树形的树棵数。
     几何在 buildTreeBatches() 里把节点自身变换(含 inner 的底面归零补偿)
     一次性烘焙进顶点,实例矩阵只负责球面摆放。
     树冠微摆由 writeTreeMatrix() 每帧重写矩阵实现,绝不改 scale 数值。 */
  var treeSways = [];                       // 树的微摆记录(现在是实例槽位)
  var treeInstances = [], treeCursor = [], treeInstanceCount = 0;
  var treeTintMats = [];                   // V2 四季着色用(树干)
  var treeLeafMats = [];                   // V6.3 树冠(换色相)
  var _treeDummy = new THREE.Object3D();
  var _swayE = new THREE.Euler(), _swayQ = new THREE.Quaternion();
  function buildTreeBatches(parts) {
    var groups = [];
    for (var vi = 0; vi < parts.length; vi++) {
      var part = parts[vi];
      part.updateMatrixWorld(true);
      var meshes = [];
      part.traverse(function (o) {
        if (!o.isMesh || !o.geometry || !o.geometry.attributes.position) return;
        var g = o.geometry.clone();
        g.applyMatrix4(o.matrixWorld);
        var m = o.material.clone();
        // V6.3:叶子材质按「树形索引」分季色(5 种树形 → 5 种秋色)
        if (isLeafMaterial(m)) patchLeafMaterial(m, vi);
        meshes.push({ geo: g, mat: m });
      });
      groups.push(meshes);
      for (var q = 0; q < meshes.length; q++) {
        var mm = meshes[q].mat;
        if (isLeafMaterial(mm)) treeLeafMats.push(mm);
        else treeTintMats.push(mm);
      }
    }
    return groups;
  }
  function makeTreeInstances(groups, counts) {
    for (var vi = 0; vi < groups.length; vi++) {
      var n = counts[vi], arr = [];
      treeCursor[vi] = 0;
      if (!n) { treeInstances[vi] = arr; continue; }
      for (var mi = 0; mi < groups[vi].length; mi++) {
        var im = new THREE.InstancedMesh(groups[vi][mi].geo, groups[vi][mi].mat, n);
        im.count = n;
        im.renderOrder = 1;
        im.frustumCulled = false;
        outGroup.add(im);
        arr.push(im);
        treeInstanceCount++;
      }
      treeInstances[vi] = arr;
    }
  }
  function writeTreeMatrix(rec, extraQ) {
    var arr = treeInstances[rec.vi];
    if (!arr) return;
    _treeDummy.position.copy(rec.n).multiplyScalar(rec.groundR);
    _treeDummy.quaternion.copy(rec.base);
    if (extraQ) _treeDummy.quaternion.multiply(extraQ);
    _treeDummy.scale.setScalar(rec.scale);
    _treeDummy.updateMatrix();
    for (var i = 0; i < arr.length; i++) {
      arr[i].setMatrixAt(rec.slot, _treeDummy.matrix);
      arr[i].instanceMatrix.needsUpdate = true;
    }
  }
  /* V6 脚印拖尾:一个 Points 批 + 环形复用 CONFIG.footprints 个槽位,
     每只动物每 0.55s 落一枚(左右交替),6.5s 线性淡出。
     只写 buffer 属性,不新建对象 ⇒ 1 个 draw call、零 GC 抖动。 */
  var FCAP = CONFIG.footprints;
  var fPos = new Float32Array(FCAP * 3), fAlpha = new Float32Array(FCAP),
      fSize = new Float32Array(FCAP), fBorn = new Float32Array(FCAP);
  for (var fz = 0; fz < FCAP; fz++) {
    fAlpha[fz] = 0; fSize[fz] = 0.020; fBorn[fz] = -1e9;
  }
  var fGeo = new THREE.BufferGeometry();
  fGeo.setAttribute('position', new THREE.Float32BufferAttribute(fPos, 3));
  fGeo.setAttribute('aAlpha', new THREE.Float32BufferAttribute(fAlpha, 1));
  fGeo.setAttribute('aSize', new THREE.Float32BufferAttribute(fSize, 1));
  var footPoints = new THREE.Points(fGeo, new THREE.ShaderMaterial({
    uniforms: { tDot: { value: footTex() } },
    vertexShader: [
      'attribute float aAlpha; attribute float aSize; varying float vA;',
      'void main(){',
      '  vA = aAlpha;',
      '  vec4 mv = modelViewMatrix * vec4(position, 1.0);',
      '  gl_PointSize = aSize * (900.0 / max(0.001, -mv.z));',
      '  gl_Position = projectionMatrix * mv;',
      '}'
    ].join('\n'),
    fragmentShader: [
      'uniform sampler2D tDot; varying float vA;',
      'void main(){',
      '  if (vA <= 0.0) discard;',
      '  vec4 d = texture2D(tDot, gl_PointCoord);',
      '  if (d.a < 0.02) discard;',
      '  gl_FragColor = vec4(0.17, 0.12, 0.08, d.a * vA * 0.55);',
      '}'
    ].join('\n'),
    transparent: true, depthWrite: false
  }));
  footPoints.frustumCulled = false;
  footPoints.renderOrder = 3;
  outGroup.add(footPoints);
  var fCursor = 0, panicCount = 0;
  function pushFootprint(p, size) {
    window.__footCount = (window.__footCount || 0) + 1;
    var i = fCursor; fCursor = (fCursor + 1) % FCAP;
    fPos[i * 3] = p.x; fPos[i * 3 + 1] = p.y; fPos[i * 3 + 2] = p.z;
    fSize[i] = size || 0.020; fAlpha[i] = 1; fBorn[i] = performance.now();
    fGeo.attributes.position.needsUpdate = true;
    fGeo.attributes.aSize.needsUpdate = true;
    // 新脚印的 alpha 由上帧旧值变为 1;updateFootprints 只在检测到
    // alpha 差异时才置 needsUpdate ⇒ 旧流程下这一帧的新脚印 alpha
    // 延迟一帧生效(评审 P2,2026-11-08:主动置脏更稳健)。
    fGeo.attributes.aAlpha.needsUpdate = true;
  }
  function updateFootprints(now) {
    var dirty = false;
    for (var i = 0; i < FCAP; i++) {
      if (fBorn[i] < 0) continue;
      var a = 1 - (now - fBorn[i]) / (CONFIG.footprintLife * 1000);
      var v = a > 0 ? a : 0;
      if (v <= 0) fBorn[i] = -1e9;
      if (fAlpha[i] !== v) { fAlpha[i] = v; dirty = true; }
    }
    if (dirty) fGeo.attributes.aAlpha.needsUpdate = true;
  }

  var debugMode = location.search.indexOf('debug=1') >= 0;
  var debugRows = [];
  var walkers = [];        // 沿陆地游走的实例(树不在内)
  var animMixers = [];     // 每个带骨骼实例独立的 AnimationMixer
  // P4:观测钩子常驻(不再只有 ?debug=1 才有),任意时刻可读 fps / draw calls
  window.__globeDebug = { models: 0, done: 0, instances: debugRows,
                          stats: {}, quality: 0, baked: !!earthRes.baked,
                          csp: CSP, config: CONFIG };
  // V6.3 观测钩子:暴露树冠材质状态,便于核对「换色是否真的生效」
  window.__globeDebug.leafMats = treeLeafMats;
  window.__globeDebug.barkMats = treeTintMats;
  if (debugMode) {
    // 校对用:每 0.5s 记一次第一只动物的球面坐标与动画时钟,用来证明
    // 位置在变(走来走去)、mixer.time 在推进(骨骼动画在播)
    window.__globeDebug.walkTrace = [];
    var traceTimer = setInterval(function () {
      var w0 = walkers[0], row = { t: Math.round(performance.now()) };
      if (w0) {
        row.u = +w0.u.toFixed(4); row.v = +w0.v.toFixed(4);
        row.p = [w0.inst.position.x, w0.inst.position.y, w0.inst.position.z]
          .map(function (x) { return +x.toFixed(4); });
      }
      row.mixer = animMixers.length ? +animMixers[0].time.toFixed(3) : null;
      window.__globeDebug.walkTrace.push(row);
      if (window.__globeDebug.walkTrace.length >= 40) clearInterval(traceTimer);
    }, 500);

    // 校对用:证明无骨骼动物的程序化步态(gait clip + leg morph)确实在逐帧动,
    // 以及地球自转已经降速。只在 ?debug=1 时运行。
    window.__globeDebug.motionTrace = [];
    var motionTimer = setInterval(function () {
      var row = { t: Math.round(performance.now()) };
      row.spin = +earth.rotation.y.toFixed(4);
      for (var gi = 0; gi < walkers.length; gi++) {
        var gw = walkers[gi];
        if (!gw.morphs || !gw.morphs.length || !gw.gaitAction) continue;
        var inf = gw.morphs[0].morphTargetInfluences;
        row.morph = [+inf[0].toFixed(3), +inf[1].toFixed(3)];
        row.gaitTime = +gw.gaitAction.time.toFixed(3);
        var gp = gw.inst.children[0];
        if (gp) {
          row.gaitQuat = [gp.quaternion.x, gp.quaternion.y, gp.quaternion.z, gp.quaternion.w]
            .map(function (x) { return +x.toFixed(4); });
          row.gaitScale = +gp.scale.x.toFixed(6);
        }
        // 校对用:任何时刻都应恒定 ⇒ 模型大小不变
        row.instScale = +gw.inst.scale.x.toFixed(6);
        break;
      }
      window.__globeDebug.motionTrace.push(row);
      if (window.__globeDebug.motionTrace.length >= 40) clearInterval(motionTimer);
    }, 500);
  }

  // 落点在开始加载前一次性挑好(共享 occupied 占位表),与 GLB 到达顺序无关
  var plans = MODELS.map(function (spec) {
    var src = spec.kind === 'forest' ? earthRes.forest :
              spec.kind === 'both' ? earthRes.grass.concat(earthRes.forest) : earthRes.grass;
    var spots = spec.forest ? pickForestSpots(spec.count)
                           : pickSpots(src, spec.count, spec.gap || MIN_GAP);
    return { spec: spec, spots: spots };
  });

  /* C14 氛围光点:森林簇上方漂浮的落叶 / 萤火虫。
     用独立 LCG(不动全局 rand)并挂 outGroup ⇒ 随地球自转始终跟着森林。 */
  var treePlan = null;
  for (var tpi = 0; tpi < plans.length; tpi++)
    if (plans[tpi].spec.name === 'tree') treePlan = plans[tpi];
  var PN = CONFIG.motes, pPos = new Float32Array(PN * 3), pData = [], pseed = 4421;
  function pr() { pseed = (Math.imul(pseed, 1664525) + 1013904223) >>> 0; return pseed / 4294967296; }
  for (var pj = 0; pj < PN; pj++) {
    var base = (treePlan && treePlan.spots.length)
      ? treePlan.spots[Math.floor(pr() * treePlan.spots.length)] : { u: 0.5, v: 0.5 };
    var du = (pr() - 0.5) * 0.02, dv = (pr() - 0.5) * 0.02;
    var pd = surfaceDir((base.u + du + 1) % 1,
                        Math.min(0.9, Math.max(0.1, base.v + dv)));
    var prad = 1.02 + pr() * 0.075;
    pData.push({ x: pd[0] * prad, y: pd[1] * prad, z: pd[2] * prad,
                 ph: pr() * Math.PI * 2, amp: 0.004 + pr() * 0.010,
                 sp: 0.5 + pr() * 1.2 });
    pPos[pj * 3] = pData[pj].x; pPos[pj * 3 + 1] = pData[pj].y; pPos[pj * 3 + 2] = pData[pj].z;
  }
  var pGeo = new THREE.BufferGeometry();
  pGeo.setAttribute('position', new THREE.Float32BufferAttribute(pPos, 3));
  var pPoints = new THREE.Points(pGeo, new THREE.PointsMaterial({
    size: 0.018, map: glowTex(), color: 0xfff0b8, transparent: true,
    depthWrite: false, blending: THREE.AdditiveBlending, sizeAttenuation: true
  }));
  pPoints.renderOrder = 2;
  outGroup.add(pPoints);

  /* V6.2 四季视觉增位:冬季雪花 / 秋季落叶 / 夏季花朵。
     全部挂在 outGroup ⇒ 随地球自转,始终对应地表。
     独立 LCG 不消耗全局 rand() ⇒ 动物/树落点不变。
     只改 opacity / position,绝不改 scale(零缩放约束)。 */
  // ① 雪花(冬季可见,缓慢坠落,贴地后重置悬空)
  var SNOW = CONFIG.snowCap;
  var nPos = new Float32Array(SNOW * 3), nData = [];
  var nseed = 8137;
  function nr() { nseed = (Math.imul(nseed, 1664525) + 1013904223) >>> 0; return nseed / 4294967296; }
  for (var ni = 0; ni < SNOW; ni++) {
    var nu = nr(), nv = 0.15 + nr() * 0.70;
    var ndir = surfaceDir((nu + 1) % 1, nv);
    var brad = 1.03 + nr() * 0.18;
    nData.push({ u: nu, v: nv, radius: brad, phase: nr() * Math.PI * 2, speed: 0.35 + nr() * 0.70 });
    nPos[ni * 3]     = ndir[0] * brad;
    nPos[ni * 3 + 1] = ndir[1] * brad;
    nPos[ni * 3 + 2] = ndir[2] * brad;
  }
  var snowGeo = new THREE.BufferGeometry();
  snowGeo.setAttribute('position', new THREE.Float32BufferAttribute(nPos, 3));
  // ⚠️ Float32BufferAttribute 会【复制】数组 ⇒ 必须反查 GPU 真正在用的
  //    buffer,否则每帧写 nPos 对 GPU 无效 ⇒ 雪花根本不会飘。
  var snowPosBuf = snowGeo.attributes.position.array;
  var snowPts = new THREE.Points(snowGeo, new THREE.PointsMaterial({
    size: 0.014, map: snowTex(), color: 0xffffff, transparent: true,
    depthWrite: false, blending: THREE.AdditiveBlending, sizeAttenuation: true
  }));
  snowPts.frustumCulled = false;
  snowPts.renderOrder = 2;
  snowPts.visible = false;
  outGroup.add(snowPts);

  // ② 落叶(秋季可见,自高处飘落)
  var LEAF = CONFIG.leafCap;
  var lPos = new Float32Array(LEAF * 3), lData = [];
  var lseed = 8267;
  function lr() { lseed = (Math.imul(lseed, 1664525) + 1013904223) >>> 0; return lseed / 4294967296; }
  for (var li = 0; li < LEAF; li++) {
    var lu = lr(), lv = 0.15 + lr() * 0.70;
    var ldir = surfaceDir((lu + 1) % 1, lv);
    var lrad = 1.06 + lr() * 0.15;
    lData.push({ u: lu, v: lv, radius: lrad, phase: lr() * Math.PI * 2,
                 speed: 0.80 + lr() * 1.30 });
    lPos[li * 3]     = ldir[0] * lrad;
    lPos[li * 3 + 1] = ldir[1] * lrad;
    lPos[li * 3 + 2] = ldir[2] * lrad;
  }
  var leafGeo = new THREE.BufferGeometry();
  leafGeo.setAttribute('position', new THREE.Float32BufferAttribute(lPos, 3));
  var leafPosBuf = leafGeo.attributes.position.array;   // 同上:反查 GPU buffer
  var leafPts = new THREE.Points(leafGeo, new THREE.PointsMaterial({
    size: 0.030, map: leafTex(), color: 0xffa44a, transparent: true,
    depthWrite: false, blending: THREE.AdditiveBlending, sizeAttenuation: true
  }));
  leafPts.frustumCulled = false;
  leafPts.renderOrder = 2;
  leafPts.visible = false;
  outGroup.add(leafPts);

  /* ③ 花朵(夏季可见):各种颜色的小圆点,撒在真正的草地上。
     ⚠️ 落点必须取自 earthRes.grass 候选池(草地区域的像素),
        不能用随机经纬度 —— 否则花会开在海里、沙漠、树上。
     花点贴着地表(半径 1.004,比草面略高一点点,避免与地表 z-fighting),
        随地球自转 ⇒ 始终开在同一片草地上。
     ⚠️ 昼夜:V6.3 用户要求「夜晚下花朵变暗变淡,转到白天恢复鲜艳」。
        PointsMaterial 不吃场景灯光 ⇒ 必须自己按「法线·太阳方向」
        求昼夜系数,再据此压暗顶点色。
        法线要转到世界空间再点积(花在 outGroup 里随地球自转,
        而 SUN_DIR 是世界空间的)。 */
  var FLOW = CONFIG.flowerCap;
  var fPos = new Float32Array(FLOW * 3), fCol = new Float32Array(FLOW * 3), fData = [];
  var fBase = new Float32Array(FLOW * 3);   // 基准色(未按昼夜调制)
  /* 花色:只保留在绿色草地上对比强烈的颜色。
     ⚠️ 白/浅绿在草地上几乎看不见(截图里一片白点但分不出颜色),
        蓝色也偏暗 ⇒ 这里用饱和的红/粉/紫/橙/黄,少量白作点缀。
     ⚠️ 颜色是【线性】空间的乘算系数 ⇒ 要得到想要的显示色,
        线性值要比 sRGB 直觉值更饱和(否则 ACES 之后发白)。 */
  var FLOWER_COLS = [
    [1.00, 0.05, 0.12],   // 玫红
    [1.00, 0.62, 0.04],   // 橙
    [0.80, 0.10, 0.90],   // 紫
    [1.00, 0.18, 0.55],   // 粉
    [1.00, 0.80, 0.06],   // 金黄
    [0.85, 0.04, 0.02],   // 朱红
    [1.00, 1.00, 1.00],   // 白(点缀)
    [1.00, 0.36, 0.02]    // 橙黄
  ];
  var fseed = 1863;
  function fr() { fseed = (Math.imul(fseed, 1664525) + 1013904223) >>> 0; return fseed / 4294967296; }
  var grassPool = earthRes.grass && earthRes.grass.length ? earthRes.grass : null;
  var fPts2 = [];   // 已选花点(用于最小间距判定)
  var _fdDbuf = [];                              // 调试:d 分布缓冲
  for (var fi = 0; fi < FLOW; fi++) {
    // 有草地候选池 ⇒ 从池里随机取(真正的草地);否则退回经纬度 + 陆地校验
    var fu, fv, ok = false;
    if (grassPool) {
      var gp = grassPool[Math.floor(fr() * grassPool.length)];
      fu = gp.u; fv = gp.v; ok = true;
    } else {
      for (var tr2 = 0; tr2 < 12 && !ok; tr2++) {
        fu = fr(); fv = 0.15 + fr() * 0.70;
        if (elevation(fu, fv) >= LAND_MIN) ok = true;
      }
      if (!ok) { fu = 0.5; fv = 0.5; }
    }
    // 最小间距:纯随机会让花聚成一坨、其余草地光秃。
    // 角距阈值 0.030 rad ⇒ 屏幕上约 20px 间距,看起来是「一片花海」。
    for (var t2 = 0; t2 < 6 && fPts2.length; t2++) {
      var tooClose = false;
      for (var q2 = 0; q2 < fPts2.length; q2++) {
        var d2 = fPts2[q2];
        var du = fu - d2.u, dv = fv - d2.v;
        if (du * du + dv * dv < 0.030 * 0.030) { tooClose = true; break; }
      }
      if (!tooClose) break;
      // 太近 ⇒ 换一个草地像素重试
      var gp2 = grassPool ? grassPool[Math.floor(fr() * grassPool.length)] : null;
      if (gp2) { fu = gp2.u; fv = gp2.v; }
    }
    fPts2.push({ u: fu, v: fv });
    var fdir = surfaceDir((fu + 1) % 1, fv);
    var frad = 1.004;                        // 贴着草地表面
    fData.push({ u: fu, v: fv, baseH: frad, phase: fr() * Math.PI * 2, speed: 0.40 + fr() * 1.0,
                 nx: fdir[0], ny: fdir[1], nz: fdir[2] });   // 局部法线(昼夜判定用)
    fPos[fi * 3]     = fdir[0] * frad;
    fPos[fi * 3 + 1] = fdir[1] * frad;
    fPos[fi * 3 + 2] = fdir[2] * frad;
    // 配色按序号轮转(而非纯随机)⇒ 8 种颜色均匀分布,不会某色扎堆
    var col = FLOWER_COLS[fi % FLOWER_COLS.length];
    fBase[fi * 3]     = col[0];
    fBase[fi * 3 + 1] = col[1];
    fBase[fi * 3 + 2] = col[2];
    fCol[fi * 3]     = col[0];
    fCol[fi * 3 + 1] = col[1];
    fCol[fi * 3 + 2] = col[2];
  }
  var flGeo = new THREE.BufferGeometry();
  flGeo.setAttribute('position', new THREE.Float32BufferAttribute(fPos, 3));
  flGeo.setAttribute('color', new THREE.Float32BufferAttribute(fCol, 3));
  /* ⚠️⚠️ three.js 的 Float32BufferAttribute(array,size) 内部是
        `new Float32Array(array)` ⇒ 【复制】一份,不是引用!
        所以直接改 fCol / fPos 不会影响 GPU 缓冲区 —— 必须写
        geo.attributes.xxx.array(那是 GPU 真正在用的那块)。
        (原 C14 光点就是用 pGeo.attributes.position.array 反查的,
         见 frame() 里的 parr;2026-11-07 花朵昼夜不生效即此坑。)
        这里把两块 buffer 反查出来缓存,后续每帧直接写它们。 */
  var flPosBuf = flGeo.attributes.position.array;
  var flColBuf = flGeo.attributes.color.array;
  var flPts = new THREE.Points(flGeo, new THREE.PointsMaterial({
    size: 0.042, map: flowerTex(), vertexColors: true, transparent: true,
    depthWrite: false, blending: THREE.NormalBlending, sizeAttenuation: true,
    // ⚠️ 必须 toneMapped:false —— 场景开了 ACESFilmicToneMapping,
    //    小而亮的点经色调映射后饱和度被压掉,粉/橙/紫全变成接近白
    //    (2026-11-07 用户实测「大多是白色或相近颜色」)。
    toneMapped: false
  }));
  flPts.frustumCulled = false;
  flPts.renderOrder = 2;
  flPts.visible = false;
  outGroup.add(flPts);

  /* v5.2 龙卷风:3 个槽位,间歇性出现(用户要求「偶尔出现的」)。
     - 周期 38~60s,其中只有约 32% 时间可见:0.58~0.64 淡入、
       0.64~0.82 持续、0.82~0.90 淡出,其余隐藏。
     - 三个相位错开:0 号一打开就快出现、2 号正在消散,之后变成偶尔出现。
     - 每轮换一个陆地落点 ⇒ 不总在同一处。
     - 只改 opacity / visible / quaternion,绝不改 scale(延续零缩放约束)。
     - 独立 LCG,不消耗全局 rand ⇒ 动物 / 树落点完全不变。
     - 挂 outGroup ⇒ 随地球自转,始终立在陆地上。 */
  var TORNADO_COUNT = CONFIG.tornadoCount, TORNADO_H = CONFIG.tornadoH;
  // ⚠️ 不能复用 _upAxis:它声明在 stepWalkers 之前(更靠后),var 提升后此处
  //    是 undefined,setFromUnitVectors 读 .dot() 会让整个 initApp 崩掉。
  var _tornadoUp = new THREE.Vector3(0, 1, 0);
  var tSeed = 20261107;
  function tr() {
    tSeed = (Math.imul(tSeed, 1664525) + 1013904223) >>> 0;
    return tSeed / 4294967296;
  }
  var animalPts = [], treePts = [];
  for (var api = 0; api < plans.length; api++) {
    var isTree = plans[api].spec.name === 'tree';
    // 树有 150 棵且簇状聚集,只取 1/4 做粗粒度避让就够了,
    // 避免 150×1200 次全量球面距离把初始化拖慢。
    var stride = isTree ? 4 : 1, dst = isTree ? treePts : animalPts;
    for (var apj = 0; apj < plans[api].spots.length; apj += stride)
      dst.push(plans[api].spots[apj]);
  }
  /* 海陆混合落点(v6.1):
       wantSea=true  → 水龙卷,取 e<0.44 的开阔海面(离岸一段距离,免得看起来
                        仍贴着陆地);不检查动物 / 树(它们本来就在陆地上)。
       wantSea=false → 陆龙卷,取 e>=0.52 的陆地内部,继续避开动物与森林簇。
     开场那个槽位必须在正面半球,否则打开页面时正好在球背面看不见。 */
  function pickTornadoSpot(existing, frontOnly, wantSea) {
    var fallback = null;
    for (var tries = 0; tries < 1600; tries++) {
      var u = tr(), v = 0.16 + tr() * 0.68;
      var e = elevation(u, v);
      if (wantSea ? e >= 0.44 : e < 0.52) continue;
      // 开场可见的三个槽位限定在正面偏中部:z>0.58 保证朝着相机,
      // v∈[0.33,0.64] 避免落在球的上下缘(否则漏斗顺着法线伸出轮廓,
      // 看起来像「从地球底部漏出一根白柱」)。
      if (frontOnly && (surfaceDir(u, v)[2] < 0.58 || v < 0.33 || v > 0.64)) continue;
      var p = { u: u, v: v, sea: !!wantSea }, good = true;
      if (!wantSea) {
        for (var ai = 0; ai < animalPts.length; ai++)
          if (angularDist(p, animalPts[ai]) < 0.20) { good = false; break; }
        // 落在开阔陆地,别把灰白雾团盖在绿色树冠上(会看成「树开花」)
        for (var tpi2 = 0; good && tpi2 < treePts.length; tpi2++)
          if (angularDist(p, treePts[tpi2]) < 0.14) { good = false; break; }
        if (!good) continue;
      }
      for (var ei = 0; ei < existing.length; ei++)
        if (angularDist(p, existing[ei]) < 0.45) { good = false; break; }
      if (good) return p;
      if (!fallback) fallback = p;
    }
    return fallback || { u: 0.5, v: 0.42, sea: !!wantSea };
  }
  // 18 个落点海陆交替;槽位每次轮换 +1 ⇒ 每个龙卷风都会在两种环境间切换。
  var tSpots = [];
  for (var tsi = 0; tsi < TORNADO_COUNT * 6; tsi++) {
    // 0 / 1 / 12 是三个槽位的初始落点(0=海、1=陆、12=海),都要一打开就看得见
    var opening = (tsi === 0 || tsi === 1 || tsi === 12);
    tSpots.push(pickTornadoSpot(tSpots, opening, tsi % 2 === 0));
  }

  // 漏斗轮廓:底窄顶宽。内外两层用不同几何(不用 scale),避免任何缩放动画
  function funnelGeo(scaleR, scaleH) {
    var pts = [];
    for (var i = 0; i <= 12; i++) {
      var t = i / 12;
      pts.push(new THREE.Vector2((0.010 + 0.034 * Math.pow(t, 1.45)) * scaleR,
                                 t * TORNADO_H * scaleH));
    }
    return new THREE.LatheGeometry(pts, 28);
  }
  var funnelInner = funnelGeo(1.0, 1.0), funnelOuter = funnelGeo(1.35, 1.08);
  var funnelHaze  = funnelGeo(1.70, 1.14);         // ③ 更宽更淡的雾壳
  var capGeo = new THREE.IcosahedronGeometry(0.026, 0);
  var puffGeo = new THREE.IcosahedronGeometry(0.019, 0);   // ③ 沿高度的雾团
  var dustShared = dustTex(), tornadoTexShared = tornadoTex();
  function placeTornado(gr, n, p) {
    var dir = surfaceDir(p.u, p.v);
    n.set(dir[0], dir[1], dir[2]);
    gr.position.copy(n).multiplyScalar(R + 0.001);
    gr.quaternion.setFromUnitVectors(_tornadoUp, n);   // 局部 +Y 对齐球面法线
  }
  // v6.1:0 号(海)与 1 号(陆)一打开就处于完整可见的持续段,
  //   2 号(海)正在消散 ⇒ 海陆两种同时能看见,之后照旧进入间歇期。
  //   (此前 0.56 在低帧率 headless 下 26s 都还没淡入完,截图看不到。)
  var initPhases = [0.66, 0.66, 0.84];
  var initSpots = [0, 1, 12];   // 0/12 是海点,1 是陆点
  // 水龙卷偏冷蓝:只改 material.color,绝不改 scale(零缩放约束)
  function applyTornadoTint(tn) {
    var c = tn.sea ? 0xd2eaff : 0xffffff;
    tn.inner.material.color.setHex(c);
    tn.outer.material.color.setHex(c);
    tn.haze.material.color.setHex(c);
    tn.dust.material.color.setHex(tn.sea ? 0x9fd2ff : 0xffffff);
  }
  var tornadoes = [];
  for (var tk = 0; tk < TORNADO_COUNT; tk++) {
    var grp = new THREE.Group(), nrm = new THREE.Vector3();

    var texA = tornadoTexShared.clone(); texA.needsUpdate = true;
    var texB = tornadoTexShared.clone(); texB.needsUpdate = true;
    var texC = tornadoTexShared.clone(); texC.needsUpdate = true;
    texB.offset.x = 0.37; texC.offset.x = 0.71;
    // ② swirl 子 Group:整团漏斗 / 云帽 / 雾团一起绕自身轴公转,
    //    同时外层 group 沿球面移动 ⇒ 真正的「一边旋转一边运动」。
    var swirl = new THREE.Group();
    grp.add(swirl);
    var innerMat = new THREE.MeshBasicMaterial({
      map: texA, transparent: true, depthWrite: false,
      side: THREE.DoubleSide, opacity: 0
    });
    var outerMat = new THREE.MeshBasicMaterial({
      map: texB, transparent: true, depthWrite: false,
      side: THREE.DoubleSide, opacity: 0
    });
    var hazeMat = new THREE.MeshBasicMaterial({
      map: texC, transparent: true, depthWrite: false,
      side: THREE.DoubleSide, opacity: 0
    });
    var inner = new THREE.Mesh(funnelInner, innerMat); inner.renderOrder = 5;
    var outer = new THREE.Mesh(funnelOuter, outerMat); outer.renderOrder = 5;
    var haze  = new THREE.Mesh(funnelHaze, hazeMat);  haze.renderOrder = 5;
    swirl.add(inner); swirl.add(outer); swirl.add(haze);

    var fade = [{ m: innerMat, b: 0.86 }, { m: outerMat, b: 0.40 },
                { m: hazeMat, b: 0.22 }];
    // ③ 4 个顶部云帽 + 3 个沿高度分布的雾团,把硬边锥体打散成云絮
    for (var ci = 0; ci < 6; ci++) {
      var cm = new THREE.MeshLambertMaterial({
        color: 0xb9cddd, transparent: true, opacity: 0,
        flatShading: true, depthWrite: false
      });
      var isCap = ci < 3;
      var puff = new THREE.Mesh(isCap ? capGeo : puffGeo, cm);
      var ca = (ci / 6) * Math.PI * 2 + tr() * 0.7;
      var level = isCap ? 0.92 + tr() * 0.10 : 0.22 + tr() * 0.52;
      var cr = isCap ? 0.020 + tr() * 0.018 : 0.014 + tr() * 0.020;
      puff.position.set(Math.cos(ca) * cr, TORNADO_H * level, Math.sin(ca) * cr);
      puff.renderOrder = 5;
      swirl.add(puff);
      fade.push({ m: cm, b: isCap ? 0.72 : 0.36 });
    }
    // 贴地尘埃环:平面法线 +Z → +Y
    var dm = new THREE.MeshBasicMaterial({
      map: dustShared, transparent: true, opacity: 0,
      depthWrite: false, side: THREE.DoubleSide
    });
    var dust = new THREE.Mesh(new THREE.PlaneGeometry(0.15, 0.15), dm);
    dust.rotation.x = -Math.PI / 2; dust.position.y = 0.002;
    dust.renderOrder = 2;
    grp.add(dust); fade.push({ m: dm, b: 0.66 });

    var tn = {
      group: grp, swirl: swirl, nrm: nrm, base: new THREE.Quaternion(),
      spotIdx: initSpots[tk], phase: initPhases[tk], period: 38 + tr() * 22,
      sea: !!tSpots[initSpots[tk]].sea,
      u: tSpots[initSpots[tk]].u, v: tSpots[initSpots[tk]].v,
      heading: tr() * Math.PI * 2, speed: 0.010 + tr() * 0.010,
      scroll: 0.60 + tr() * 0.30, spin: 2.4 + tr() * 1.4,
      swayPhase: tr() * Math.PI * 2,
      fade: fade, texA: texA, texB: texB, texC: texC,
      inner: inner, outer: outer, haze: haze, dust: dust
    };
    applyTornadoTint(tn);
    placeTornado(grp, nrm, tSpots[tn.spotIdx]);
    tn.base.copy(grp.quaternion);
    grp.visible = false;
    outGroup.add(grp);
    tornadoes.push(tn);
  }

  var okCount = 0, doneCount = 0;
  var statusEl = document.getElementById('status');
  function setStatus(text) { if (statusEl) statusEl.textContent = text; }
  function hideStatus(delay) {
    setTimeout(function () { if (statusEl) statusEl.classList.add('hide'); }, delay || 0);
  }
  // 兜底:若某个 GLB 特别慢/挂起,不让状态条永远挂着
  var slowTimer = setTimeout(function () {
    if (doneCount < plans.length) {
      setStatus('部分模型较大仍在加载,其余已显示');
      hideStatus(2500);
    }
  }, 30000);

  /* ---------- 沿球面陆地游走 ----------
     在 (u,v) 参数空间步进,用 elevation() 判定下一格是不是陆地;撞到海岸
     就换方向,始终待在陆地上。高纬度经度步长要按 cos(lat) 放大,否则
     极地附近会被"拉长"。朝向由切向 + 法线构造正交基,随前进方向转身。
  --------------------------------------------------------------- */
  var _p0 = new THREE.Vector3(), _p1 = new THREE.Vector3(),
      _fwd = new THREE.Vector3(), _right = new THREE.Vector3(),
      _zAxis = new THREE.Vector3(0, 0, 1),
      _upAxis = new THREE.Vector3(0, 1, 0), _faceQ = new THREE.Quaternion(),
      _headDir = new THREE.Vector3(),
      _basis = new THREE.Matrix4(),
      _tmpWN = new THREE.Vector3(), _footPos = new THREE.Vector3(),
      _footRight = new THREE.Vector3();

  function stepWalkers(dt, now, panicPts) {
    panicCount = 0;
    for (var i = 0; i < walkers.length; i++) {
      var w = walkers[i];
      w.heading += (rand() - 0.5) * dt * 0.35;     // 缓慢随机游荡
      // V5 龙卷风联动:附近(角距 < ~0.35 rad)的动物受惊,掉头 + 加速 + 不休息
      var pan = 0, wN = _tmpWN.copy(w.inst.position).normalize();
      for (var pz = 0; pz < panicPts.length; pz++)
        if (wN.dot(panicPts[pz]) > 0.94) { pan = 1; break; }
      if (pan) {
        panicCount++;
        if (!w.panic) {
          w.heading += Math.PI * (0.6 + rand() * 0.7);
          w.pauseIn = 8 + rand() * 6;
        }
        w.idle = 0;
      }
      w.panic = pan;
      // C13 走走停停:随机进入 1.2~4s 原地张望,腿频同步降下来(不缩放)
      if (w.idle > 0) {
        w.idle -= dt;
        if (w.act) w.act.timeScale = w.baseRate * 0.12;
      } else {
        w.pauseIn -= dt;
        if (w.pauseIn <= 0 && !pan) { w.idle = 1.2 + rand() * 2.8; w.pauseIn = 4 + rand() * 8; }
        if (w.act) w.act.timeScale = w.baseRate * (pan ? 2.0 : 1);
      }
      var lat = (0.5 - w.v) * Math.PI;
      var cosLat = Math.max(0.3, Math.cos(lat));
      var step = w.idle > 0 ? 0 : w.speed * dt * (pan ? 2.4 : 1);

      function tryAt(h) {
        var u = (w.u + Math.cos(h) * step / cosLat + 1) % 1;
        var v = w.v - Math.sin(h) * step;
        return v > 0.12 && v < 0.88 && elevation(u, v) >= LAND_MIN
          && !mtnBlock(u, v)                     // V6.4 动物不走进山体
          ? { u: u, v: v } : null;
      }
      var next = tryAt(w.heading);
      if (!next) {
        // 撞到海岸/极区:原地找一个朝向陆地的新方向
        for (var a = 0; a < 8 && !next; a++) {
          w.heading += 0.6 + rand() * 1.2;
          next = tryAt(w.heading);
        }
        if (!next) { w.heading += Math.PI; continue; }  // 被海围住就原地转身
      }
      w.u = next.u; w.v = next.v;

      var d0 = surfaceDir(w.u, w.v);
      _p0.set(d0[0], d0[1], d0[2]).normalize();
      var eps = 0.003;
      var u2 = (w.u + Math.cos(w.heading) * eps / cosLat + 1) % 1;
      var v2 = w.v - Math.sin(w.heading) * eps;
      var d1 = surfaceDir(u2, v2);
      _p1.set(d1[0], d1[1], d1[2]);
      _fwd.subVectors(_p1, _p0);
      _fwd.addScaledVector(_p0, -_fwd.dot(_p0));   // 投影到切平面
      if (_fwd.lengthSq() < 1e-10) continue;
      _fwd.normalize();
      if (w.faceYaw) _fwd.applyAxisAngle(_p0, w.faceYaw);
      _right.crossVectors(_p0, _fwd).normalize();
      _basis.makeBasis(_right, _p0, _fwd);
      w.inst.quaternion.setFromRotationMatrix(_basis);
      // 物种自身的朝向前缀:右乘局部旋转,把模型真正的"头"摆到前进方向。
      // θ=-90° 时局部 +X → 局部 +Z(basis 的前进轴),长颈鹿专用。
      if (w.facing) {
        _faceQ.setFromAxisAngle(_upAxis, w.facing);
        w.inst.quaternion.multiply(_faceQ);
      }
      if (debugMode) {
        // 金标准校验:模型"头"轴(无 facing 用 +Z,长颈鹿用 +X)经完整旋转后
        // 应与前进切向 _fwd 同向 ⇒ dot 恒 ≈ +1;若为 0 就是横着走。
        _headDir.set(w.facing ? 1 : 0, 0, w.facing ? 0 : 1)
                .applyQuaternion(w.inst.quaternion);
        var ftr = window.__globeDebug.facingTrace ||
                  (window.__globeDebug.facingTrace = []);
        if (ftr.length < 40) ftr.push(+_headDir.dot(_fwd).toFixed(3));
      }

      // 贴地:不再叠加 bob 起伏(那会让脚离地);步态由 gait 旋转 + leg morph 提供。
      // 半径用该模型按 footprint 算出的 groundR,平底与球面切平。
      var _wN = _p0.clone().normalize();          // 先在缩放前取单位法线
      w.inst.position.copy(_p0.multiplyScalar(w.inst.userData.groundR || SURF));
      // A1 阴影贴片跟着走:合批后只改自己那一个实例矩阵
      if (w.shadowSlot >= 0) setShadow(w.shadowSlot, _wN, w.shadowW);
      // V6 脚印:移动时才落,左右交替,与地表法线 / 前进正交基对齐
      if (step > 0 && now - w.lastFoot > CONFIG.footprintEvery * 1000) {
        w.lastFoot = now;
        w.footSide = !w.footSide;
        _footPos.copy(_wN).multiplyScalar(R + 0.005);
        _footRight.set(1, 0, 0).applyQuaternion(w.inst.quaternion)
                  .multiplyScalar((w.footSide ? 1 : -1) * w.shadowW * 0.13);
        _footPos.add(_footRight);
        pushFootprint(_footPos, 0.018 + w.shadowW * 0.02);
      }

      // 程序化腿部迈步:与 gait clip 共用同一个 action.time,相位严格同步
      if (w.morphs && w.gaitAction) {
        var ph = (w.gaitAction.time % 1) * Math.PI * 2;
        var ca = Math.max(0, Math.sin(ph)), cb = Math.max(0, -Math.sin(ph));
        for (var qi = 0; qi < w.morphs.length; qi++) {
          var infl = w.morphs[qi].morphTargetInfluences;
          if (infl && infl.length >= 2) { infl[0] = ca; infl[1] = cb; }
        }
      }
    }
  }

  /* P1 树的合批摆放:与 scatter() 用同一套 computePlace(),落点 / 尺寸 /
     贴地半径完全一致,只是把「每棵一个 Group 层级」换成实例矩阵。 */
  function scatterTrees(res, plan) {
    var spec = plan.spec, spots = plan.spots, parts = res.parts;
    var groups = buildTreeBatches(parts);
    var counts = [], n = Math.min(spots.length, spec.count), k;
    for (k = 0; k < parts.length; k++) counts[k] = 0;
    for (k = 0; k < n; k++) counts[k % parts.length]++;
    makeTreeInstances(groups, counts);
    for (k = 0; k < n; k++) {
      var vi = k % parts.length, sp = spots[k];
      var pl = computePlace(parts[vi].userData.size, sp.u, sp.v,
                            spec.height, rand() * Math.PI * 2);
      var rec = { vi: vi, slot: treeCursor[vi]++, base: pl.q.clone(),
                  n: pl.n.clone(), scale: pl.scale, groundR: pl.groundR,
                  phase: rand() * Math.PI * 2, u: sp.u, v: sp.v, panic: 0 };
      treeSways.push(rec);
      writeTreeMatrix(rec, null);
      var tw = Math.min(0.17, Math.max(0.045,
        Math.max(parts[vi].userData.size.x, parts[vi].userData.size.z) * pl.scale * 1.40));
      allocShadow(pl.n, tw);
      if (debugMode) debugRows.push({
        name: spec.name, part: vi,
        u: +sp.u.toFixed(4), v: +sp.v.toFixed(4),
        elev: +elevation(sp.u, sp.v).toFixed(3),
        measured: parts[vi].userData.size, scale: +pl.scale.toFixed(6),
        height: +pl.height.toFixed(4),
        radius: +pl.groundR.toFixed(4), groundR: +pl.groundR.toFixed(4),
        gait: 'instanced'
      });
    }
    console.log('[globe-present] tree: ' + n + ' instances in ' +
                treeInstanceCount + ' InstancedMesh batches');
  }

  function scatter(res, plan) {
    var spec = plan.spec, spots = plan.spots, parts = res.parts, clip = res.clip;
    var gaitClip = !!(spec.gait && !clip);
    for (var k = 0; k < spots.length && k < spec.count; k++) {
      var partSrc = parts[k % parts.length];
      // 必须走 SkeletonUtils.clone,普通 clone(true) 会让所有实例共用一套
      // skeleton、动画串台(而且 clone 会丢 userData,下面补回)
      var inst = cloneSkeleton(partSrc);
      if (!inst.userData.size) inst.userData.size = partSrc.userData.size;
      var info = placeOnSphere(inst, spots[k].u, spots[k].v, spec.height);
      outGroup.add(inst);
      // clone 可能丢 name,显式钉住 gait pivot 供 AnimationClip 绑定
      if (spec.gait && inst.children[0]) inst.children[0].name = 'gait';

      // 走动状态(树不动);先建 walker,动画速率才能跟实际位移速度成正比
      var w = null;
      if (spec.walk) {
        w = {
          inst: inst,
          u: spots[k].u, v: spots[k].v,
          heading: rand() * Math.PI * 2,
          speed: WALK_SPEED * (0.7 + rand() * 0.6),
          phase: rand() * Math.PI * 2,
          faceYaw: (rand() - 0.5) * 0.6,
          facing: spec.facing || 0,
          pauseIn: 3 + rand() * 7, idle: 0, act: null,
          lastFoot: performance.now(), footSide: false, panic: 0
        };
        w.baseRate = STEP_RATE * (w.speed / WALK_SPEED);
        walkers.push(w);
      }

      // A1 接触阴影:独立于模型缩放(模型 s≈0.02,贴片必须按世界尺寸算)
      var shN = info.n.clone();
      var shW = Math.min(0.17, Math.max(0.045,
        Math.max(inst.userData.size.x, inst.userData.size.z) * info.scale * 1.40));
      var slot = allocShadow(shN, shW);
      if (w) { w.shadowSlot = slot; w.shadowN = shN; w.shadowW = shW; }

      // 每个实例独立 mixer:12 只带骨骼动物播 Walk,6 只无骨骼动物播程序化步态
      var useClip = clip;
      if (!useClip && gaitClip) useClip = makeGaitClip(1 / info.scale, spec.name);
      if (useClip) {
        var mixer = new THREE.AnimationMixer(inst);
        var act = mixer.clipAction(useClip);
        act.setLoop(THREE.LoopRepeat, Infinity);
        // 位移慢了腿也要慢,否则会原地打滑
        act.timeScale = STEP_RATE * (w ? w.speed / WALK_SPEED : 1);
        if (w) w.act = act;
        act.time = rand() * useClip.duration;
        act.play();
        animMixers.push(mixer);
        if (spec.gait) {
          w.morphs = collectMorphs(inst);
          w.gaitAction = act;
        }
      }

      if (debugMode) debugRows.push({
        name: spec.name, part: k % parts.length,
        u: +spots[k].u.toFixed(4), v: +spots[k].v.toFixed(4),
        elev: +elevation(spots[k].u, spots[k].v).toFixed(3),
        measured: inst.userData.size, scale: +info.scale.toFixed(6),
        height: +info.height.toFixed(4),
        radius: +inst.position.length().toFixed(4),
        groundR: +info.groundR.toFixed(4),
        gait: spec.gait ? 'procedural' : (clip ? 'skeleton' : 'static')
      });
    }
    console.log('[globe-present] ' + spec.name + ': placed ' +
                Math.min(spots.length, spec.count) + '/' + spec.count + ' on land' +
                (clip ? ' (walk clip: ' + clip.name + ')' :
                 spec.gait ? ' (procedural gait + leg morph)' : ' (static)'));
  }

  /* P7:分级 + 并发上限。16 个 GLB 一次性并发会抢带宽、拖慢主角出现;
     按 tier 排序后最多同时 4 个,完成一个补一个。落点已预分配 ⇒ 顺序安全。 */
  function runPlan(plan) {
    return loadGLB(plan.spec).then(function (res) {
      okCount++;
      if (plan.spec.instanced) scatterTrees(res, plan);
      else scatter(res, plan);
      if (okCount === 1) {                       // P6:首个模型真正上场
        firstModelReady();
        window.__globeDebug.firstModelMs = Math.round(performance.now());
      }
    }).catch(function (err) {
      // 单个模型失败只跳过它自己,不再连累其他物种
      console.warn('[globe-present] ' + plan.spec.name + ' 加载失败,已跳过:', err);
    }).then(function () {          // finally:成功 / 失败都推进进度
      doneCount++;
      window.__globeDebug.models = okCount;
      window.__globeDebug.done = doneCount;
      window.__globeDebug.walkers = walkers.length;
      window.__globeDebug.mixers = animMixers.length;
      window.__globeDebug.gait = walkers.filter(function (x) { return x.morphs; }).length;
      window.__globeDebug.trees = debugRows.filter(function (x) { return x.name === 'tree'; }).length;
      var pct = Math.round(doneCount / plans.length * 100);
      setLoadProgress(pct, doneCount + '/' + plans.length + ' 模型');
      if (doneCount < plans.length) {
        setStatus('正在放置动物与树木… ' + okCount + '/' + plans.length);
      } else {
        clearTimeout(slowTimer);
        releaseGLBTextureCompat();                 // P10:纹理解码完毕,恢复全局
        if (okCount === 0) {
          setStatus('模型加载失败,请检查网络');
          firstModelReady();                       // 别把用户挡在遮罩后面
          document.getElementById('errMsg').textContent =
            '动物 / 树木模型全部加载失败,请检查网络后刷新页面重试(应用内资产缺失?)。';
          document.getElementById('errBox').style.display = 'flex';
        } else {
          setStatus('已放置 ' + okCount + '/' + plans.length + ' 种模型');
          hideStatus(1500);
        }
      }
    });
  }
  var loadQueue = plans.slice().sort(function (a, b) {
    return (a.spec.tier || 0) - (b.spec.tier || 0);
  });
  var activeLoads = 0, queueIdx = 0;
  function pumpLoads() {
    while (activeLoads < CONFIG.loadConcurrency && queueIdx < loadQueue.length) {
      var plan = loadQueue[queueIdx++];
      activeLoads++;
      runPlan(plan).then(function () { activeLoads--; pumpLoads(); });
    }
  }
  pumpLoads();

  /* ---------- P2 自适应画质 ----------
     三档:0 全画质 / 1 降 DPR + 关云影与林间光点 / 2 再关第二层云、
     收鸟群、简化龙卷风雾壳。只减「氛围层」,绝不改动物 / 树的数量、
     尺寸、动画或落点 ⇒ 硬约束不受画质切换影响。
     连续 qualityWindow 秒低于 lowFps 才降档,高于 highFps 更久才升档,
     避免来回抖动。?quality=0|1|2 可钉住档位便于校对截图。 */
  var qPin = /quality=([012])/.exec(location.search);
  var seasonPin = /(?:^|[?&])season=([0123])(?:&|$)/.exec(location.search);
  var qualityLevel = 0, _qHold = 0;
  function applyQuality(lv) {
    lv = Math.max(0, Math.min(2, lv));
    qualityLevel = lv;
    renderer.setPixelRatio(lv === 0 ? Math.min(window.devicePixelRatio || 1, CONFIG.maxPixelRatio)
                                   : lv === 1 ? 1 : 0.75);
    renderer.setSize(window.innerWidth, window.innerHeight);
    clouds2.visible = lv < 2;
    cloudShadow.visible = lv < 1;
    pPoints.visible = lv < 1;
    atmoGlow.material.opacity = lv === 0 ? 0.85 : lv === 1 ? 0.62 : 0.42;
    var keep = lv === 0 ? BC : lv === 1 ? Math.floor(BC * 0.6) : Math.floor(BC * 0.3);
    for (var i = 0; i < birdSprites.length; i++) birdSprites[i].visible = i < keep;
    for (var j = 0; j < tornadoes.length; j++)
      if (tornadoes[j].haze) tornadoes[j].haze.visible = lv < 2;
    window.__globeDebug.quality = lv;
  }
  /* ---------- P4 观测钩子 ---------- */
  var _statFrames = 0, _statT = performance.now();
  function sampleStats(now) {
    _statFrames++;
    var span = now - _statT;
    if (span < 500) return;
    var fps = _statFrames * 1000 / Math.max(1, span);
    _statFrames = 0; _statT = now;
    var inf = renderer.info;
    window.__globeDebug.stats = {
      fps: +fps.toFixed(1), calls: inf.render.calls,
      tris: inf.render.triangles, points: inf.render.points,
      geometries: inf.memory.geometries, textures: inf.memory.textures,
      programs: inf.programs ? inf.programs.length : 0,
      quality: qualityLevel, baked: !!earthRes.baked,
      shadows: shadowSlot, treeBatches: treeInstanceCount,
      footprints: window.__footCount || 0, panic: panicCount,
      tornado: {
        sea: tornadoes.filter(function (x) { return x.sea; }).length,
        land: tornadoes.filter(function (x) { return !x.sea; }).length,
        visible: tornadoes.filter(function (x) { return x.group.visible; }).length
      },
      land: [earthRes.grass.length, earthRes.forest.length],
      csp: { blocked: CSP.blocked, active: CSP.active }
    };
    if (qPin) return;
    if (fps < CONFIG.lowFps && qualityLevel < 2) {
      _qHold += span / 1000;
      if (_qHold >= CONFIG.qualityWindow) { applyQuality(qualityLevel + 1); _qHold = 0; }
    } else if (fps > CONFIG.highFps && qualityLevel > 0) {
      _qHold += span / 1000;
      if (_qHold >= CONFIG.qualityWindow * 2) { applyQuality(qualityLevel - 1); _qHold = 0; }
    } else _qHold = 0;
  }
  if (qPin) applyQuality(Number(qPin[1]));

  /* ---------- animation loop ---------- */
  var prevT = performance.now();
  function frame(now) {
    var dt = Math.max(0, Math.min(0.05, (now - prevT) / 1000)); prevT = now;

    // V3 极光 / 流星 2D 图层
    drawFx(now, dt);
    // V5 联动源:当前可见龙卷风的球面法线(用上一帧位置,差一帧无影响)
    var panicPts = [];
    for (var pz0 = 0; pz0 < tornadoes.length; pz0++)
      if (tornadoes[pz0].group.visible)
        panicPts.push(tornadoes[pz0].group.position.clone().normalize());
    var mt = now * 0.000018;
    positionMoon(mt);
    // V6.3 四季:
    //   · 地球 tint / 树干 tint:按 SEASON_PARAMS 线性插值(与贴图同源)。
    //   · 树冠:shader 换色相(春淡绿 / 夏原色 / 秋黄橙红深红 / 冬冷蓝灰),
    //     每种树形一个秋色 ⇒ 同一片林子里就有黄树与红树。
    //   · 花朵(夏) / 积雪层(冬) / 落叶(秋) / 雪花(冬) 的出现强度同源插值。
    //   · 只改材质 color / opacity / position,不改几何 / 尺寸 / 落点。
    var sec = now * 0.001;
    var sf = seasonPin ? Number(seasonPin[1]) : ((sec / CONFIG.seasonPeriod) % 1) * 4;
    var si0 = Math.floor(sf) % 4, si1 = (si0 + 1) % 4, st = smooth01(sf - Math.floor(sf));
    var P0 = SEASON_PARAMS[si0], P1 = SEASON_PARAMS[si1];
    var SNOW_BLEND = 0.30;          // 过渡带占一季的比例(末尾 30%)
    // st 已是过渡进度,带内再 smooth01 ⇒ S 曲线(两头慢、中部快):
    // 前 70% 恒为本季值、季末才快速过渡 —— 有意的「本季稳定表现期」
    // 设计,不是时序误差(2026-11-08 评审 P2:补此注释)。
    var seasonW = (function () {
      var w = (st - (1 - SNOW_BLEND)) / SNOW_BLEND;
      if (w <= 0) return 0;
      if (w >= 1) return 1;
      return smooth01(w);
    })();


    var tintR = lerp(P0.ground[0], P1.ground[0], seasonW),
        tintG = lerp(P0.ground[1], P1.ground[1], seasonW),
        tintB = lerp(P0.ground[2], P1.ground[2], seasonW);
    var barkR = lerp(P0.bark[0], P1.bark[0], seasonW),
        barkG = lerp(P0.bark[1], P1.bark[1], seasonW),
        barkB = lerp(P0.bark[2], P1.bark[2], seasonW);
    // ⚠️ 全部用【连续】插值:si0 是当前季、si1 是下一季,st 是过渡进度。
    //    用户实测要求「秋冬/冬春交界不能突变」⇒ 任何一项都不能加硬阈值。
    var flowersK = lerp(P0.flowers, P1.flowers, seasonW);
    var snowGroundK = lerp(P0.snowGround, P1.snowGround, seasonW);
    var snowFallK = lerp(P0.snowFall, P1.snowFall, seasonW);
    var leafFallK = lerp(P0.leafFall, P1.leafFall, seasonW);
    var grassYellowK = lerp(P0.grassYellow, P1.grassYellow, seasonW);
    // ⚠️ 积雪的出现要【集中在秋冬交界】,而不是提前半个秋季就开始下雪。
    //    sf 在 [si0, si0+1) 内,st = sf - si0 从 0 递增到 1,
    //    所以 st→1 才是【本季末尾 = 正在向下一季过渡】。
    //    改用「末尾过渡带」:只有 st 接近 1 那一小段才渐变,
    //    带内用 smooth01 加速(仍是连续函数 ⇒ 不会突变)。
    function edgeBlend(from, to) {
      // st=0 → from(本季恒定值);st→1 → to(下一季的值)
      var w = (st - (1 - SNOW_BLEND)) / SNOW_BLEND;
      if (w <= 0) return from;
      if (w >= 1) return to;
      return lerp(from, to, smooth01(w));
    }
    // ⚠️ 统一的「季节推进权重」:本季前 70% 保持本季参数,
    //    季末 30% 连续过渡到下一季。
    //    之前地球 tint / 树色 / 草地色是跨【整季】lerp 的 ⇒ 每季参数只在
    //    季初那一瞬间成立,其余时间都在往下一季漂 ⇒ 季节感被稀释,
    //    也会让「雪融化」与「树变绿」的时序对不上。
    //    统一成「本季保持 + 季末过渡」后,四季各有稳定的表现期,
    //    且地面与树的颜色切换发生在同一时刻。
    // ⚠️ 草地「黄→绿」必须【等雪完全盖住之后】才推进(用户 2026-11-07 明确指出):
    //    之前草地色是跨整季 lerp(秋黄→冬绿)⇒ 深秋时草地已经在变绿,
    //    叠上渐渐泛白的雪 ⇒ 看到的是「先黄变绿,再逐渐变白」,顺序反了。
    //    实现注记:「变绿」不插值底色 —— 底色恒为枯黄单档,
    //      时序交给 grassYellowK 在冬季雪盖段撤销强度(见下冬分支):
    //      黄色保持 ⇒ 白色连续盖上来(黄→白);黄底色→原贴图绿的切换
    //      在雪盖下完成(看不见);冬春交界雪消融 ⇒ 白色连续显出绿。
    //      (原设计命名 grassGreenK 即「底色黄→绿」的进度;代码中无此变量。)
    // ⚠️ 草地黄化强度 grassYellowK:
    //   0 = 完全露出【原贴图】的绿色(与春夏完全一致)
    //   1 = 完全变成枯黄
    //   之前把「颜色」和「强度」分开插值,导致夏秋交界颜色瞬间跳变、
    //   冬春交界露出平涂深绿后又跳回贴图浅绿(用户 2026-11-07 实测反馈)。
    //   现在颜色只有【原贴图绿】与【枯黄】两档,由这一个强度连续插值,
    //   强度归零时露出的就是原贴图 ⇒ 与春季逐像素一致,不会有跳变。
    if (si0 === 2) {                // 秋:末尾才开始积雪;草地保持枯黄
      snowGroundK = edgeBlend(0, 1);
      snowFallK = edgeBlend(0, 1);
      grassYellowK = 1;
    } else if (si0 === 3) {         // 冬:末尾开始消融
      snowGroundK = edgeBlend(1, 0);
      snowFallK = edgeBlend(1, 0);
      // 冬季前 35%(雪已全覆盖)把黄化撤掉 ⇒ 白色遮盖下底色变绿(看不见),
      // 等雪融化时露出的已经是原贴图绿 ⇒ 冬春交界连续显绿,无跳变。
      grassYellowK = 1 - smooth01(clamp01(st / 0.35));
    } else if (si0 === 1) {         // 夏:末尾草地开始变黄(与秋季颜色连续)
      grassYellowK = edgeBlend(0, 1);
      snowGroundK = 0; snowFallK = 0;
    } else {                        // 春:无雪无黄
      snowGroundK = 0; snowFallK = 0; grassYellowK = 0;
    }
    /* V6.4 山体季节联动(必须【每帧无条件】更新 —— 放在 landSeason.visible
       的条件块里会在春夏(visible=false)残留上一季的值)。
       · mtnSnowU  : 冬季整座山变白(与大陆积雪同强度)
       · mtnWarmU  : 秋季岩石偏暖;随雪一起在秋末褪掉 ⇒ 与冬季无硬边
       · mtnGrassAmtU: 秋季山脚草地泛黄(与大陆 grassYellowK 同一个值) */
    mtnSnowU.value = snowGroundK;
    mtnWarmU.value = (si0 === 1) ? seasonW : (si0 === 2 ? (1 - snowGroundK) : 0);
    mtnGrassAmtU.value = grassYellowK;
    earthMat.color.setRGB(tintR, tintG, tintB);
    // V6.4 山体也是独立材质 ⇒ 必须跟大陆用【同一个季节 tint】,
    //   否则秋冬山体与大陆的色调对不上(大陆暖了、山体还是中性色)。
    mtnMat.color.setRGB(tintR, tintG, tintB);
    if (window.__globeDebug) {
      window.__globeDebug.mountains = {
        peak: +mtnPeak.toFixed(4), amp: MTN_AMP, grid: [MTN_W, MTN_H],
        snow: +mtnSnowU.value.toFixed(2), warm: +mtnWarmU.value.toFixed(2),
        grass: +mtnGrassAmtU.value.toFixed(2)
      };
    }
    atmoGlow.material.color.setRGB(lerp(1, tintR, 0.55),
                                   lerp(1, tintG, 0.55),
                                   lerp(1, tintB, 0.55));
    for (var sm = 0; sm < treeTintMats.length; sm++) {
      var tm = treeTintMats[sm];
      if (!tm.color) continue;
      tm.color.setRGB(barkR, barkG, barkB);
    }
    // V6.3 树冠换色:沿用原来的插值方式(在两季之间 lerp),
    //   只是把「乘法染色」换成「保留亮度的色相替换」——
    //   否则绿叶贴图乘任何系数都还是绿的,冬天变不成冷蓝灰。
    //   秋季每种树形一个专属色 ⇒ 同一片林子里有黄树也有红树。
    if (window.__globeDebug) {
      window.__globeDebug.leafMats = treeLeafMats;
      window.__globeDebug.barkMats = treeTintMats;
    }
    for (var lmi = 0; lmi < treeLeafMats.length; lmi++) {
      var lm = treeLeafMats[lmi], su = lm.userData.seasonU;
      if (!su || !lm.userData.leafColor) continue;
      var lc = lm.userData.leafColor, lmx = lm.userData.leafMix;
      var lr0 = lc[si0], lr1 = lc[si1];
      // ⚠️ 用 seasonW(本季保持 + 季末过渡),不是整季 lerp:
      //   整季 lerp 会让「夏→秋的绿转黄」分散到整个夏季,
      //   看上去一直是渐变、季节感很弱(用户反馈)。
      //   seasonW ⇒ 夏季稳定是深绿,秋末才开始转黄/红。
      su.tint.value.setRGB(
        lerp(lr0.r, lr1.r, seasonW), lerp(lr0.g, lr1.g, seasonW),
        lerp(lr0.b, lr1.b, seasonW));
      su.mix.value = lerp(lmx[si0], lmx[si1], seasonW);
    }
    sunLight.intensity = 2.4 * (0.88 + 0.12 * Math.cos(sf / 4 * Math.PI * 2));
    window.__globeDebug.season = {
      index: si0, sf: +sf.toFixed(2), pinned: !!seasonPin,
      tint: [+tintR.toFixed(3), +tintG.toFixed(3), +tintB.toFixed(3)],
      bark: [+barkR.toFixed(3), +barkG.toFixed(3), +barkB.toFixed(3)],
      flowersK: +flowersK.toFixed(2), snowGroundK: +snowGroundK.toFixed(2),
      snowFallK: +snowFallK.toFixed(2), leafFallK: +leafFallK.toFixed(2),
      grassYellowK: +grassYellowK.toFixed(2)
    };
    // ① 雪花:冬季可见,缓慢坠落;贴地后重置悬空
    if (snowPts) {
      snowPts.visible = snowFallK > 0.01;
      if (snowPts.visible) {
        snowPts.material.opacity = Math.min(1, snowFallK * 1.4);
        for (var si = 0; si < SNOW; si++) {
          var sd = nData[si];
          sd.radius -= dt * sd.speed * 0.12;
          if (sd.radius < 1.015) {
            sd.radius = 1.03 + nr() * 0.18;
            sd.u = nr();
            sd.v = 0.15 + nr() * 0.70;
          }
          var nsd = surfaceDir((sd.u + 1) % 1, sd.v);
          var nbob = Math.sin(now * 0.001 * 0.6 + sd.phase) * 0.006;
          snowPosBuf[si * 3]     = nsd[0] * (sd.radius + nbob);
          snowPosBuf[si * 3 + 1] = nsd[1] * (sd.radius + nbob);
          snowPosBuf[si * 3 + 2] = nsd[2] * (sd.radius + nbob);
        }
        snowGeo.attributes.position.needsUpdate = true;
      }
    }
    // ② 落叶:秋季可见,从高处飘落向地面;贴地后重悬空,循环往复
    if (leafPts) {
      leafPts.visible = leafFallK > 0.01;
      if (leafPts.visible) {
        leafPts.material.opacity = Math.min(1, leafFallK * 1.5);
        for (var li2 = 0; li2 < LEAF; li2++) {
          var ld = lData[li2];
          ld.radius -= dt * ld.speed * 0.20;
          if (ld.radius < 1.015) {
            ld.radius = 1.06 + lr() * 0.15;
            ld.u = lr();
            ld.v = 0.15 + lr() * 0.70;
          }
          var lsd = surfaceDir((ld.u + 1) % 1, ld.v);
          var lbob = Math.sin(now * 0.002 * ld.speed + ld.phase) * 0.020;
          // 使落叶有轻微横向漂移(在切面方向)
          // ⚠️ cross() 返回普通数组(不是 Vector3)⇒ 没有 .normalize(),
          //    必须手动除以模长,否则每帧抛 "tang.normalize is not a function"
          var tang = cross(surfaceDir((ld.u + 0.01 + 1) % 1, ld.v), lsd);
          var tlen = Math.sqrt(tang[0] * tang[0] + tang[1] * tang[1] + tang[2] * tang[2]);
          if (tlen > 1e-9) { tang[0] /= tlen; tang[1] /= tlen; tang[2] /= tlen; }
          else { tang[0] = 0; tang[1] = 0; tang[2] = 0; }
          var lx = tang[0] * lbob, ly = tang[1] * lbob, lz = tang[2] * lbob;
          leafPosBuf[li2 * 3]     = lsd[0] * ld.radius + lx;
          leafPosBuf[li2 * 3 + 1] = lsd[1] * ld.radius + ly;
          leafPosBuf[li2 * 3 + 2] = lsd[2] * ld.radius + lz;
        }
        leafGeo.attributes.position.needsUpdate = true;
      }
    }
    // ③ 花朵:夏季可见,悬于草地上方,轻轻上下浮动
    if (flPts) {
      flPts.visible = flowersK > 0.01;
      if (flPts.visible) {
        flPts.material.opacity = Math.min(1, flowersK * 1.2);
        // V6.3 昼夜:夜面花朵变暗变淡,转回白天恢复鲜艳。
        //   PointsMaterial 不吃场景灯光 ⇒ 自己按「世界法线·太阳方向」求昼夜系数。
        //   法线要先从 outGroup 局部空间转到世界空间(花随地球自转,
        //   而 SUN_DIR 是世界空间的)。
        //   判定口径与 nightLayer 一致:night = 1 - smoothstep(-0.20, 0.16, d)
        if (outGroup.matrixWorld) {
          // 法线变换:outGroup 只有「Z 轴倾斜(tiltPivot)+ Y 轴自转」两个旋转,
          // 直接解析算比取 matrixWorld 更稳(也不依赖渲染器的更新时序)。
          //   world = Rz(tilt) · Ry(earthRot) · local   (列向量约定)
          var _er = earth.rotation.y;
          var _ct = Math.cos(CONFIG.tilt), _st = Math.sin(CONFIG.tilt);
          var _ce = Math.cos(_er), _se = Math.sin(_er);
          // 展开后 local(lx,ly,lz) → world 的三个分量系数
          var _m00 = _ct * _ce,  _m01 = -_st,      _m02 = _ct * _se;
          var _m10 = _st * _ce,  _m11 = _ct,       _m12 = _st * _se;
          var _m20 = -_se,       _m21 = 0,         _m22 = _ce;
          var _dR = 0, _dG = 0, _dB = 0, _dN = 0, _nR = 0, _nG = 0, _nB = 0, _nN = 0;
          for (var fi2 = 0; fi2 < FLOW; fi2++) {
            var fd = fData[fi2];
            var wnx = _m00 * fd.nx + _m01 * fd.ny + _m02 * fd.nz;
            var wny = _m10 * fd.nx + _m11 * fd.ny + _m12 * fd.nz;
            var wnz = _m20 * fd.nx + _m21 * fd.ny + _m22 * fd.nz;
            var d = wnx * SUN_DIR.x + wny * SUN_DIR.y + wnz * SUN_DIR.z;
            // 夜面(朝向背离太阳)系数高;晨昏带平滑过渡
            var nightF = 1.0 - smooth01((d + 0.20) / 0.36);
            // ⚠️ 幅度必须够大才看得出来:之前只混 55% 到灰蓝,
            //    算下来夜面亮度还有 ~0.56 ⇒ 看着只是「稍微暗一点」。
            //    现在夜面:去饱和 80% + 亮度压到 28%(红)/32%(绿)/48%(蓝)
            //    ⇒ 明显变暗变淡、且偏冷蓝(与夜面压暗层的冷蓝一致)。
            var br = fBase[fi2 * 3], bg = fBase[fi2 * 3 + 1], bb = fBase[fi2 * 3 + 2];
            var lum = br * 0.299 + bg * 0.587 + bb * 0.114;
            var k = nightF;
            // ⚠️ 写 flColBuf(不是 fCol):后者是 Float32BufferAttribute 复制出来的
            //    独立数组,写它对 GPU 无效 ⇒ 花朵昼夜调制会完全不生效。
            flColBuf[fi2 * 3]     = lerp(br, lum, k * 0.80) * lerp(1.0, 0.28, k);
            flColBuf[fi2 * 3 + 1] = lerp(bg, lum, k * 0.80) * lerp(1.0, 0.32, k);
            flColBuf[fi2 * 3 + 2] = lerp(bb, lum * 1.15, k * 0.80) * lerp(1.0, 0.48, k);
            // 数值采样(昼/夜两组的平均色)⇒ 可在控制台核对是否真的变暗
            if (nightF < 0.35) { _dR += flColBuf[fi2 * 3]; _dG += flColBuf[fi2 * 3 + 1]; _dB += flColBuf[fi2 * 3 + 2]; _dN++; }
            else if (nightF > 0.65) { _nR += flColBuf[fi2 * 3]; _nG += flColBuf[fi2 * 3 + 1]; _nB += flColBuf[fi2 * 3 + 2]; _nN++; }
          }
          if (debugMode) {
            window.__globeDebug.flowerLight = {
              day: _dN ? [+(_dR / _dN).toFixed(3), +(_dG / _dN).toFixed(3), +(_dB / _dN).toFixed(3)] : null,
              night: _nN ? [+(_nR / _nN).toFixed(3), +(_nG / _nN).toFixed(3), +(_nB / _nN).toFixed(3)] : null,
              dayN: _dN, nightN: _nN
            };
          }
          // 调试:d 的分布 + 昼/夜平均色(核对昼夜调制是否真的生效)
          if (debugMode) {
            var _db = _fdDbuf; _fdDbuf = [];
            for (var fi4 = 0; fi4 < FLOW; fi4++) {
              var fd4 = fData[fi4];
              var dx4 = _m00 * fd4.nx + _m01 * fd4.ny + _m02 * fd4.nz;
              var dy4 = _m10 * fd4.nx + _m11 * fd4.ny + _m12 * fd4.nz;
              var dz4 = _m20 * fd4.nx + _m21 * fd4.ny + _m22 * fd4.nz;
              _db.push(dx4 * SUN_DIR.x + dy4 * SUN_DIR.y + dz4 * SUN_DIR.z);
            }
            _db.sort(function (a, b) { return a - b; });
            var _qn = _db.length;
            window.__globeDebug.flowerD = _qn ? {
              min: +_db[0].toFixed(3), max: +_db[_qn - 1].toFixed(3),
              p25: +_db[Math.floor(_qn * 0.25)].toFixed(3),
              p50: +_db[Math.floor(_qn * 0.5)].toFixed(3),
              p75: +_db[Math.floor(_qn * 0.75)].toFixed(3),
              len: _qn
            } : { len: 0 };
          }
        }
        for (var fi3 = 0; fi3 < FLOW; fi3++) {
          var fd3 = fData[fi3];
          // 沿球面法线方向轻微起伏(不能加 Y 分量:那会把花推离球面)
          var fb3 = Math.sin(now * 0.002 * fd3.speed + fd3.phase) * 0.004;
          var fdir3 = surfaceDir((fd3.u + 1) % 1, fd3.v);
          var fr3 = fd3.baseH + fb3;
          flPosBuf[fi3 * 3]     = fdir3[0] * fr3;
          flPosBuf[fi3 * 3 + 1] = fdir3[1] * fr3;
          flPosBuf[fi3 * 3 + 2] = fdir3[2] * fr3;
        }
        flGeo.attributes.position.needsUpdate = true;
        flGeo.attributes.color.needsUpdate = true;
      }
    }
    // ④ 陆地季节色层:冬天盖雪(整块陆地)、秋天草地变黄(只染草地)
    //    强度全部由季节进度连续插值 ⇒ 交界处平滑过渡,无突变。
    if (landSeason) {
      var snowA = snowGroundK, grassA = grassYellowK;
      landSeason.visible = (snowA > 0.002 || grassA > 0.002);
      if (landSeason.visible) {
        var lsu = landSeason.material.uniforms;
        lsu.uSnowAmt.value = snowA;
        lsu.uGrassAmt.value = grassA;
        // 草地底色(枯黄)颜色恒定,不逐季插值;「黄→绿」时序见上:
        // grassYellowK 在冬前雪盖期(雪已满盖)把强度降回 0 ⇒ 变色在
        // 白色遮盖下完成,shader 强度=0 时直接露出原贴图绿。
        lsu.uGrassColor.value.setRGB(GRASS_YELLOW[0], GRASS_YELLOW[1], GRASS_YELLOW[2]);
      }
    }
    nightLayer.material.uniforms.uTime.value = sec;
    oceanLayer.material.uniforms.uTime.value = sec;
    updateFootprints(now);

    // earth spin (8 deg/s) + clouds drift
    earth.rotation.y += dt * SPIN_RATE * (1 + AUDIO.energy * 0.85 + AUDIO.beat * 0.35);
    atmoGlow.material.rotation += dt * 0.006;   // 云雾光晕缓慢流动
    clouds.rotation.y += dt * CLOUD_RATE * (1 + AUDIO.energy * 0.6);
    clouds2.rotation.y -= dt * CLOUD_RATE * 0.55;   // B8 反向慢速 ⇒ 双层视差
    cloudShadow.rotation.y = clouds.rotation.y * 0.96;
    outGroup.rotation.y = earth.rotation.y;   // 模型跟着地球自转,始终踩在陆地上

    // C12 树随风微摆:合批后每帧重写实例矩阵的旋转部分(scale 恒定)
    var swayT = now * 0.001;
    for (var ti = 0; ti < treeSways.length; ti++) {
      var ts = treeSways[ti], a = swayT * 1.05 + ts.phase;
      var tpn = 0;
      for (var pz1 = 0; pz1 < panicPts.length; pz1++)
        if (ts.n.dot(panicPts[pz1]) > 0.94) { tpn = 1; break; }
      ts.panic = tpn;
      var amp = 0.016 * (1 + tpn * 3.2);   // V5 龙卷风联动
      _swayE.set(Math.sin(a) * amp, 0, Math.sin(a * 0.77 + 1.3) * amp * 1.3);
      _swayQ.setFromEuler(_swayE);
      writeTreeMatrix(ts, _swayQ);
    }

    // 骨骼腿部动画 + 沿陆地游走
    for (var mi = 0; mi < animMixers.length; mi++) animMixers[mi].update(dt);
    stepWalkers(dt, now, panicPts);

    // C11 鸟群绕地轴飞 + 三帧扇翅(Sprite 始终朝向相机)
    for (var bi = 0; bi < BC; bi++) {
      var b = birdData[bi];
      b.ph += dt * b.speed / b.r;
      var la = b.lat;
      birdSprites[bi].position.set(
        Math.cos(b.ph) * Math.cos(la) * b.r,
        Math.sin(la) * b.r + Math.sin(now * 0.002 + bi) * b.bob,
        Math.sin(b.ph) * Math.cos(la) * b.r
      );
      var frm = Math.floor((now * 0.001 * b.bf + bi * 0.37) * 3) % 3;
      birdSprites[bi].material = birdMats[frm];
    }

    // C14 森林上方的漂浮光点
    var parr = pGeo.attributes.position.array, pt = now * 0.001;
    for (var pj = 0; pj < PN; pj++) {
      var q = pData[pj], pv = Math.sin(pt * q.sp + q.ph);
      parr[pj * 3]     = q.x + pv * q.amp;
      parr[pj * 3 + 1] = q.y + Math.cos(pt * q.sp * 0.8 + q.ph) * q.amp * 1.6;
      parr[pj * 3 + 2] = q.z + pv * q.amp * 0.7;
    }
    pGeo.attributes.position.needsUpdate = true;
    pPoints.material.size = 0.018 * (panicPts.length ? 1.45 : 1);  // V5

    // v5.2 龙卷风:淡入 → 持续 → 淡出 → 隐藏,轮换落点;只改 opacity / visible
    for (var tq = 0; tq < tornadoes.length; tq++) {
      var tn = tornadoes[tq];
      tn.phase += dt / tn.period;
      if (tn.phase >= 1) {
        tn.phase -= 1;
        tn.spotIdx = (tn.spotIdx + 1) % tSpots.length;   // 下次出现在别处
        tn.u = tSpots[tn.spotIdx].u; tn.v = tSpots[tn.spotIdx].v;
        tn.sea = !!tSpots[tn.spotIdx].sea;               // 海 / 陆交替
        applyTornadoTint(tn);
        tn.heading = tr() * Math.PI * 2;
      }
      // ② 沿球面缓慢行进:与 walkers 同一套 (u,v) 参数空间步进。
      //    du 要除以 2π·cosLat、dv 除以 π,才是真正的球面角距。
      var tlat = (0.5 - tn.v) * Math.PI;
      var tcos = Math.max(0.3, Math.cos(tlat));
      var tdu = Math.cos(tn.heading) * tn.speed * dt / (2 * Math.PI * tcos);
      var tdv = -Math.sin(tn.heading) * tn.speed * dt / Math.PI;
      var tnu = (tn.u + tdu + 1) % 1, tnv = tn.v + tdv;
      tn.heading += (tr() - 0.5) * dt * 0.30;
      // v6.1:按「自身环境」判定,不再一律要求陆地 —— 水龙卷在海面走,
      //   陆龙卷在陆地走;靠近对方环境(海岸)或极区就转向。
      var eNext = elevation(tnu, tnv);
      var offGlobe = tnv < 0.12 || tnv > 0.88;
      var wrongTerrain = tn.sea ? eNext >= 0.455 : eNext < 0.50;
      if (offGlobe || wrongTerrain) {
        tn.heading += Math.PI * (0.5 + tr() * 0.5);
      } else {
        tn.u = tnu; tn.v = tnv;
      }
      placeTornado(tn.group, tn.nrm, { u: tn.u, v: tn.v });
      tn.base.copy(tn.group.quaternion);                 // 摆动仍叠加在新朝向之上
      var ph = tn.phase, k = 0;
      if (ph >= 0.58 && ph < 0.64) k = (ph - 0.58) / 0.06;        // 淡入
      else if (ph >= 0.64 && ph < 0.82) k = 1;                     // 持续
      else if (ph >= 0.82 && ph < 0.90) k = (0.90 - ph) / 0.08;    // 淡出
      k = Math.max(0, Math.min(1, k));
      tn.group.visible = k > 0.002;
      if (!tn.group.visible) continue;
      for (var fq = 0; fq < tn.fade.length; fq++)
        tn.fade[fq].m.opacity = tn.fade[fq].b * k;
      // 螺旋 UV 滚动 + 反向双层旋转 + 尘埃环旋转 + 极小幅摆动(不缩放)
      tn.texA.offset.x -= dt * tn.scroll;          // 螺旋云纹滚动
      tn.texB.offset.x += dt * tn.scroll * 0.55;
      tn.texC.offset.x -= dt * tn.scroll * 0.32;
      tn.swirl.rotation.y += dt * tn.spin * 0.8;   // 整团一起公转
      tn.inner.rotation.y += dt * tn.spin;
      tn.outer.rotation.y -= dt * tn.spin * 0.6;
      tn.haze.rotation.y += dt * tn.spin * 0.25;
      tn.dust.rotation.z += dt * 0.55;
      _swayE.set(Math.sin(now * 0.0008 + tn.swayPhase) * 0.022, 0,
                 Math.cos(now * 0.0011 + tn.swayPhase) * 0.018);
      _swayQ.setFromEuler(_swayE);
      tn.group.quaternion.copy(tn.base).multiply(_swayQ);
    }
    // 金标准:记录可见数量随时间的起伏,证明确实是「偶尔出现」
    if (debugMode) {
      var ttr = window.__globeDebug.tornadoTrace ||
                (window.__globeDebug.tornadoTrace = []);
      if (ttr.length < 60 && (!ttr.length || now - ttr[ttr.length - 1][0] > 400)) {
        var vc = 0, phs = [], tpos = [], kinds = [];
        for (var tq2 = 0; tq2 < tornadoes.length; tq2++) {
          var tt = tornadoes[tq2];
          if (tt.group.visible) vc++;
          phs.push(+tt.phase.toFixed(2));
          kinds.push(tt.sea ? 'sea' : 'land');
          tpos.push([+tt.u.toFixed(4), +tt.v.toFixed(4),
                     +tt.swirl.rotation.y.toFixed(2),
                     +elevation(tt.u, tt.v).toFixed(3)]);
        }
        ttr.push([Math.round(now), vc, phs, tpos, panicCount, kinds]);
      }
    }

    // 帧率采样（500ms 滑窗）：View 型渲染器有自己的 rAF 循环，
    // 舞台的 Choreographer 徽章对它是假读数 ⇒ 由本页自己上报。
    FPS.n++;
    if (now - FPS.t >= 500) {
      FPS.v = FPS.n * 1000 / Math.max(1, now - FPS.t);
      FPS.n = 0; FPS.t = now;
    }
    renderer.render(scene, camera);
    sampleStats(now);                                            // P4 + P2
    if (!LOAD.frame) { LOAD.frame = true; maybeHideLoading(); }  // P6
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);

  /* ---------- resize ---------- */
  function onResize() {
    var w = window.innerWidth, h = window.innerHeight;
    renderer.setSize(w, h);
    resizeFx(w, h);                       // V3
    if (skyCanvas) drawStarfield(skyCanvas);
    camera.aspect = w / h;
    // 窄视口(竖屏)水平视野不足,地球边缘连同站在边缘的模型会被裁掉。
    // 按 aspect 把相机拉远,保证半径约 1.22 的范围完整入画。
    var halfV = Math.tan(camera.fov * Math.PI / 360);
    var dist = Math.max(CONFIG.camDist,
                        CONFIG.camFit / (halfV * Math.min(1, camera.aspect)));
    camera.position.set(0, 0.55 * (dist / CONFIG.camDist), dist);
    camera.lookAt(0, 0, 0);
    camera.updateProjectionMatrix();
    updateMoonAnchor();
  }
  window.addEventListener('resize', onResize);
  onResize();

}

/* ---------- boot ----------
   ?bake=1 是 P5 的离线构建入口:不启动 WebGL,只跑一遍程序化纹理并
   把 6 张 PNG 的 dataURL 放到 window.__bakeResult,供构建脚本落盘。 */
async function boot() {
  try {
    // 必须用精确参数匹配:字符串 'nobake=1' 里也含 'bake=1',
    // 用 indexOf 会把「强制程序化回退」误判成「只导出纹理不启动场景」。
    var qs = location.search;
    if (/(?:^|[?&])bake=1(?:&|$)/.test(qs)) {
      window.__bakeResult = bakeTextures();
      return;
    }
    if (/(?:^|[?&])nobake=1(?:&|$)/.test(qs)) CONFIG.bakedTextures = false;
    await ensureGLBTextureCompat();
    await initApp();
  } catch (e) {
    console.error('[globe-present] init failed:', e);
    var box = document.getElementById('errBox');
    var msg = document.getElementById('errMsg');
    msg.textContent = '3D 引擎初始化失败：' + (e && e.message ? e.message : e);
    box.style.display = 'flex';
    forceHideLoading();
  }
}
boot();
/* ---------- 宿主入口 ----------
   Kotlin 侧只认这三个；其余保持原型页的模块内私有作用域不变。 */
window.EcoGlobe = {
  setAudio: setAudio,
  initApp: initApp,
  boot: boot,
  fps: function () { return FPS.v; }
};
})();
