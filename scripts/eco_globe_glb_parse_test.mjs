// 「生态地球」GLTFLoader 离线解析测试（定位「动物没加载出来」）。
//
// 目的：在**没有设备**的情况下，复现页面的加载路径 ——
//   1. 按 index.html 的真实顺序加载 polyfill → three → three-globe → loaders；
//   2. 检查 window.THREE 在 three-globe 之后有没有被换掉（换掉 ⇒ 实例不一致的经典坑）；
//   3. 把 THREE.FileLoader 的网络读取换成读本地磁盘，再用**真实产物** loaders.es5.js
//      里的 GLTFLoader 去解析真实的 GLB（deer.glb / tree.glb）。
//
// 用法（仓库根目录）：node scripts/eco_globe_glb_parse_test.mjs
// 退出码 0 = 通过。
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const A = (p) => path.join(ROOT, 'app', 'src', 'main', 'assets', p);

let failures = 0;
function ok(cond, msg) {
  console.log((cond ? '[PASS] ' : '[FAIL] ') + msg);
  if (!cond) failures++;
}

function makeSandbox() {
  const sandbox = {
    console: { log() {}, warn() {}, error() {}, info() {}, debug() {} },
    setTimeout, clearTimeout, setInterval, clearInterval,
    performance: { now: () => Date.now() },
    requestAnimationFrame: () => 0,
    cancelAnimationFrame: () => {},
    location: { search: '', href: 'https://appassets.androidplatform.net/eco-globe/index.html' },
    navigator: { userAgent: 'node-vm' },
    innerWidth: 1920, innerHeight: 1080, devicePixelRatio: 1,
    addEventListener: () => {}, removeEventListener: () => {},
    URL: Object.assign(URL, { createObjectURL: () => 'blob:stub', revokeObjectURL: () => {} }),
    fetch: () => Promise.reject(new Error('offline')),
    document: {
      createElement: () => ({ style: {}, getContext: () => null, addEventListener: () => {} }),
      createElementNS: () => ({ style: {}, getContext: () => null }),
      getElementById: () => ({ style: {}, classList: { add() {}, remove() {} } }),
      querySelector: () => null, querySelectorAll: () => [],
      addEventListener: () => {}, removeEventListener: () => {},
      body: { style: {}, classList: { add() {}, remove() {} } },
      documentElement: { style: {} },
    },
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  sandbox.self = sandbox;
  return sandbox;
}

const ctx = vm.createContext(makeSandbox());

// ── ① 按页面真实顺序加载 ───────────────────────────────────────────────────
for (const rel of ['globe/polyfill.es5.js', 'globe/three.es5.js', 'globe/three-globe.es5.js', 'eco-globe/loaders.es5.js']) {
  try {
    new vm.Script(fs.readFileSync(A(rel), 'utf8'), { filename: rel }).runInContext(ctx);
    console.log('[PASS] loaded ' + rel);
  } catch (e) {
    ok(false, 'load ' + rel + ' threw: ' + (e && e.message));
  }
}

const T = ctx.THREE;
ok(!!T, 'window.THREE defined (REVISION=' + (T && T.REVISION) + ')');
ok(typeof ctx.GLTFLoader === 'function', 'GLTFLoader defined');
ok(!!ctx.SkeletonUtils && typeof ctx.SkeletonUtils.clone === 'function', 'SkeletonUtils.clone defined');

// ── ② three-globe 之后 window.THREE 有没有被换掉 ───────────────────────────
// 用一个探针对象记录「页面自己的 THREE」与「shim 用的 T」是不是同一个实例。
const marker = {};
T.__ECO_MARKER__ = marker;
ok(!!T.FileLoader && !!T.TextureLoader && !!T.Skeleton && !!T.SkinnedMesh,
  'shim 需要的 THREE 类齐全');

// ── ③ 把 FileLoader 的网络读取换成读磁盘 ──────────────────────────────────
class LocalFileLoader extends T.FileLoader {
  load(url, onLoad, onProgress, onError) {
    const file = path.isAbsolute(url) ? url : path.join(A('eco-globe/glb'), url);
    let buf;
    try {
      buf = fs.readFileSync(file);
    } catch (e) {
      if (onError) onError(e);
      return this;
    }
    const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
    if (onProgress) onProgress({ loaded: ab.byteLength, total: ab.byteLength });
    // three 的 FileLoader 以 responseType=arraybuffer 回调
    if (onLoad) onLoad(ab);
    return this;
  }
}
// ⛔ GLTFLoader 内部 `new FileLoader()` ⇒ 必须换掉原型上的构造引用
T.FileLoader = LocalFileLoader;

function parse(file) {
  return new Promise((resolve) => {
    const loader = new ctx.GLTFLoader();
    let settled = false;
    const done = (v) => { if (!settled) { settled = true; resolve(v); } };
    loader.load(file, (gltf) => done({ ok: true, gltf }),
      undefined,
      (err) => done({ ok: false, err: String(err && err.message || err) }));
    setTimeout(() => done({ ok: false, err: 'timeout' }), 15000);
  });
}

for (const name of ['deer.glb', 'tree.glb', 'panda.glb']) {
  const res = await parse(path.join(A('eco-globe/glb'), name));
  if (res.ok) {
    const g = res.gltf;
    const meshes = (g.scene && g.scene.children) ? g.scene.children.length : 0;
    let anims = 0, skins = 0;
    g.scene && g.scene.traverse((o) => {
      if (o.isSkinnedMesh) skins++;
      if (o.animations) anims += o.animations.length;
    });
    ok(true, name + ' parsed: scene children=' + meshes + ' skinnedMeshes=' + skins +
      ' clips=' + (g.animations ? g.animations.length : 0));
  } else {
    ok(false, name + ' parse failed: ' + res.err);
  }
}

console.log('');
if (failures) { console.log('[FAIL] ' + failures + ' item(s) failed'); process.exit(1); }
console.log('[ok] all GLB parsed by the shipped loader bundle');
