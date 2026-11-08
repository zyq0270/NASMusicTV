// 「生态地球」页面的 **vm 加载测试**（方案 §7.1）。
//
// 用法（仓库根目录）：
//   node scripts/eco_globe_vm_load_test.mjs
// 退出码 0 = 通过。
//
// 为什么必须真跑一遍：`downlevel_libs.mjs` 的 acorn 校验只证明**语法**，
// 历史上「语法通过但运行时崩」（§10.215 的 Proxy 坑）就是这么溜过去的。
//
// ⚠️ 本仓的最终产物**不是 ES5**：esbuild 0.24.0 无法 `--target=es5`
//    （`Transforming class syntax / const / let to "es5" is not supported yet`，892 条）
//    ⇒ 按方案 §九 R1 的退路改 **es2015**。因此本条**不再模拟 Chrome 39 的缺失 API**
//    （那会让测试因「本来就放弃的目标」而恒红），改为验证：
//      ① 5 个脚本按页面的真实顺序加载，**不抛同步异常**；
//      ② 契约对象齐全（THREE / ThreeGlobe / GLTFLoader / SkeletonUtils / EcoGlobe）；
//      ③ `setAudio` / `fps` 可调用；`initApp` / `boot` 是函数；
//      ④ 负向自证：缺 three 时 loaders 必须**明确报错**，而不是静默产出 undefined。
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const A = (p) => path.join(ROOT, 'app', 'src', 'main', 'assets', p);

const CHAIN = [
  'globe/polyfill.es5.js',
  'globe/three.es5.js',
  'globe/three-globe.es5.js',
  'eco-globe/loaders.es5.js',
  'eco-globe/globe.js'
];

let failures = 0;
function ok(cond, msg) {
  if (cond) {
    console.log('[PASS] ' + msg);
  } else {
    failures++;
    console.log('[FAIL] ' + msg);
  }
}

// ── 最小 DOM / 浏览器桩：够 three.js 与页面脚本**加载**，不驱动渲染 ─────────────
function make2d() {
  const noop = () => {};
  const grad = { addColorStop: noop };
  const base = {
    setTransform: noop,
    resetTransform: noop,
    clearRect: noop,
    fillRect: noop,
    strokeRect: noop,
    drawImage: noop,
    beginPath: noop,
    closePath: noop,
    moveTo: noop,
    lineTo: noop,
    arc: noop,
    ellipse: noop,
    fill: noop,
    stroke: noop,
    save: noop,
    restore: noop,
    translate: noop,
    rotate: noop,
    scale: noop,
    clip: noop,
    putImageData: noop,
    createLinearGradient: () => grad,
    createRadialGradient: () => grad,
    createPattern: () => null,
    getImageData: (x, y, w, h) => ({
      data: new Uint8ClampedArray(Math.max(4, (w | 0) * (h | 0) * 4)),
      width: w | 0,
      height: h | 0
    })
  };
  // 未列出的 2D API 一律当空操作（页面还在持续演进，别把桩写成白名单）
  return new Proxy(base, {
    get: (t, k) => (k in t ? t[k] : noop),
    set: (t, k, v) => { t[k] = v; return true; }
  });
}

function makeElement(tag) {
  const el = {
    tagName: String(tag).toUpperCase(),
    style: {},
    dataset: {},
    children: [],
    childNodes: [],
    width: 0,
    height: 0,
    textContent: '',
    innerHTML: '',
    className: '',
    id: '',
    offsetWidth: 0,
    offsetHeight: 0,
    clientWidth: 0,
    clientHeight: 0,
    classList: {
      add: () => {},
      remove: () => {},
      toggle: () => {},
      contains: () => false
    },
    appendChild: (c) => { el.children.push(c); return c; },
    removeChild: () => {},
    insertBefore: (c) => c,
    setAttribute: () => {},
    getAttribute: () => null,
    removeAttribute: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 0, height: 0, right: 0, bottom: 0 }),
    // 关键：**没有** WebGL ⇒ three 的 WebGLRenderer 会抛错，被页面 boot() 的 catch 接住
    getContext: (type) => (type === '2d' ? make2d() : null),
    toDataURL: () => 'data:image/png;base64,'
  };
  return el;
}

function makeSandbox() {
  const listeners = {};
  const sandbox = {
    console: {
      log: (...a) => console.log('  [page]', ...a),
      warn: (...a) => console.log('  [page:warn]', ...a),
      error: (...a) => console.log('  [page:error]', ...a),
      info: () => {},
      debug: () => {}
    },
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    performance: { now: () => Date.now() },
    requestAnimationFrame: () => 0,
    cancelAnimationFrame: () => {},
    location: {
      search: '',
      href: 'https://appassets.androidplatform.net/eco-globe/index.html',
      origin: 'https://appassets.androidplatform.net'
    },
    navigator: { userAgent: 'node-vm' },
    innerWidth: 1920,
    innerHeight: 1080,
    devicePixelRatio: 1,
    addEventListener: (n, f) => { (listeners[n] ||= []).push(f); },
    removeEventListener: () => {},
    URL: Object.assign(URL, {
      createObjectURL: () => 'blob:stub',
      revokeObjectURL: () => {}
    }),
    fetch: () => Promise.reject(new Error('零网络：vm 里不发起任何请求')),
    Image: function () {
      this.onload = null;
      this.onerror = null;
      Object.defineProperty(this, 'src', {
        set: () => { /* 不触发 onload：资源缺失走失败分支 */ }
      });
    },
    document: {
      createElement: (t) => makeElement(t),
      createElementNS: (ns, t) => makeElement(t),
      getElementById: (id) => makeElement(id),
      querySelector: () => makeElement('div'),
      querySelectorAll: () => [],
      addEventListener: () => {},
      removeEventListener: () => {},
      body: makeElement('body'),
      documentElement: makeElement('html'),
      hidden: false,
      visibilityState: 'visible'
    },
    // 调用方（页面）用它判断是否在浏览器里
    self: null
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  sandbox.self = sandbox;
  return sandbox;
}

// ── ① 正向：按页面真实顺序加载 ────────────────────────────────────────────────
const ctx = vm.createContext(makeSandbox());

for (const rel of CHAIN) {
  const file = A(rel);
  if (!fs.existsSync(file)) {
    console.log('[FAIL] 缺少资产 ' + rel);
    failures++;
    continue;
  }
  const code = fs.readFileSync(file, 'utf8');
  try {
    const script = new vm.Script(code, { filename: rel });
    script.runInContext(ctx);
    console.log('[PASS] 加载 ' + rel + '（' + code.length + ' B）');
  } catch (e) {
    failures++;
    console.log('[FAIL] 加载 ' + rel + ' 抛异常: ' + (e && e.message));
  }
}

const g = ctx;
ok(typeof g.THREE === 'object' && typeof g.THREE.REVISION === 'string',
  'THREE 已定义（REVISION=' + (g.THREE && g.THREE.REVISION) + '）');
ok(typeof g.ThreeGlobe === 'function', 'ThreeGlobe 已定义（共享库，页面本身不用它）');
ok(typeof g.GLTFLoader === 'function', 'GLTFLoader 已定义');
ok(g.SkeletonUtils && typeof g.SkeletonUtils.clone === 'function', 'SkeletonUtils.clone 已定义');
ok(g.EcoGlobe && typeof g.EcoGlobe.setAudio === 'function', 'EcoGlobe.setAudio 已定义');
ok(g.EcoGlobe && typeof g.EcoGlobe.fps === 'function', 'EcoGlobe.fps 已定义');
ok(g.EcoGlobe && typeof g.EcoGlobe.initApp === 'function', 'EcoGlobe.initApp 已定义');
ok(g.EcoGlobe && typeof g.EcoGlobe.boot === 'function', 'EcoGlobe.boot 已定义');

// setAudio：正常数据 + 坏数据都不能抛（Kotlin 每 100ms 调一次）
try {
  g.EcoGlobe.setAudio('{"energy":0.8,"bass":0.5,"mid":0.3,"treble":0.2,"beat":1}');
  g.EcoGlobe.setAudio('{not json');
  g.EcoGlobe.setAudio(null);
  ok(true, 'setAudio 接受合法/非法输入均不抛');
} catch (e) {
  ok(false, 'setAudio 抛异常: ' + e.message);
}

try {
  const fps = g.EcoGlobe.fps();
  ok(typeof fps === 'number' && isFinite(fps), 'fps() 返回有限数值（' + fps + '）');
} catch (e) {
  ok(false, 'fps() 抛异常: ' + e.message);
}

// ── ② 负向自证：没有 three 时 loaders 必须**明确报错** ────────────────────────
try {
  const bare = vm.createContext(makeSandbox());
  const code = fs.readFileSync(A('eco-globe/loaders.es5.js'), 'utf8');
  new vm.Script(code, { filename: 'loaders.es5.js (bare)' }).runInContext(bare);
  ok(false, '缺 three 时 loaders 应报错，实际静默通过');
} catch (e) {
  ok(/window\.THREE|THREE/.test(String(e && e.message)),
    '缺 three 时 loaders 明确报错（' + (e && e.message) + '）');
}

console.log('');
if (failures) {
  console.log('✗ ' + failures + ' 项失败');
  process.exit(1);
}
console.log('全部通过');
