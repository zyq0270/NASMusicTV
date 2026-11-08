# 「生态地球」WebView 主题实施方案

> **版本**: v1.1（2026-11-08 落地定稿）
> **状态**: **已落地**（Phase 0~4 完成；§7.2 手机真机项待所有者执行）
> **关联**:
> - `docs/globe-present.html` —— **视觉与参数的唯一来源**（本主题要移植的对象）
> - `docs/e41-globe-opengl-plan.md` —— E41 地球的**原生 OpenGL ES 重写**方案（本主题的替代路线）
> - `docs/technical-overview.md` §10.215 / §10.216 —— E41 的宿主问题与资产纪律

---

## 一、目标与定位

把 `docs/globe-present.html`（「生态地球」演示页：150 棵树 / 19 只动物 / 山脉 / 龙卷风 / 城市灯火 / 三层云 / 极光 / 流星 / 四季）**作为 App 里的一个新主题**搬进来。

**技术路线**：**复用 E41「世界」现有的 WebView + three-globe 技术方案**（`assets/globe/` 里那套现成的 ES5 UMD 产物 + `WorldGlobeRenderer` 的宿主代码）。

**为什么不走原生 GL**：`docs/e41-globe-opengl-plan.md` 那条路（31~45 工作日）的唯一目的是**解决电视端黑屏**；本主题若只追求「画面与 HTML 完全一致」，WebView 路线保真度更高、工作量约 **1/6**。

**已知代价（明写在前面）**：
- ⛔ **电视端仍黑屏**（宿主问题，§10.215）⇒ 本主题在创维那类电视上与 E41 同样不可用；
- ⛔ **APK +约 6.6 MB**（16 个 GLB 5.58 MB + 贴图 0.87 MB + loader 打包产物 ~0.15 MB）。

---

## 二、范围

### 2.1 做

- [ ] 新增 `VisualizerTheme` 一项（如 `ECO_GLOBE` / 「生态地球」），与 E41「世界」**并列**（两个独立场景，不合并）
- [ ] 新增 assets 目录 `app/src/main/assets/eco-globe/`（页面 + 业务 JS + GLB + 贴图）
- [ ] 新增 `EcoGlobeRenderer`（View 型，**复用** `WorldGlobeRenderer` 的宿主逻辑）
- [ ] 扩展 `WorldGlobeRenderer` 的虚拟资产域路由，使 `/eco-globe/*` 与 `/globe/*` 共存
- [ ] loader 打包脚本（esbuild）+ 门禁扩展

### 2.2 不做（并说明理由）

| 不做项 | 理由 |
|---|---|
| 合并 E41「世界」与「生态地球」为同一场景 | 两套**完全不同的场景逻辑**（前者 Robinson 陆地+真卫星图+大圆航线；后者程序化生态地球+动物树木+山脉+龙卷风）⇒ 合并成本远高于并列 |
| 为电视做 ES5 兼容工作 | 电视端反正黑屏 ⇒ **零收益**（详见 §4.3） |
| 改动 `downlevel_libs.mjs` | 它是 **E41 现役资产的生产链** ⇒ 碰它一旦搞坏，**现在在手机上工作正常的 E41 也会挂** ⇒ 本方案**完全不碰**（见 §4.4） |
| 修 TV 黑屏 | 那是 `e41-globe-opengl-plan.md` 的活 |
| 移植 `globe-present.html` 的交互 | 演示页本身「明确不加交互」（无鼠标/无点击） |

---

## 三、总体方案

```
┌─ 主线程 ────────────────────────────────────────────────┐
│ EcoGlobeRenderer : VisualizerRenderer                   │
│   isViewBased = true                                     │
│   createView() → WebView（配置照抄 WorldGlobeRenderer）      │
│   onFrame()    → 100ms tick 读 spectrumRepository.frame      │
│                   → evaluateJavascript("WorldGlobe.setAudio")│
│                                                          │
│   WebViewClient.shouldInterceptRequest:                  │
│     appassets.androidplatform.net/globe/*     → assets/globe/*      │
│     appassets.androidplatform.net/eco-globe/* → assets/eco-globe/*  │
│     appassets.androidplatform.net/favicon.ico → 空 body             │
│     其他 host → return null（真联网）                                 │
└──────────────────────────────────────────────────────────┘
        │  https://appassets.androidplatform.net/eco-globe/index.html
        ▼
┌─ WebView 内 ────────────────────────────────────────────┐
│ <script src="/globe/polyfill.es5.js"></script>   ← 复用 E41 的（共享，不复制）│
│ <script src="/globe/three.es5.js"></script>     ← 复用 E41 的（共享，不复制）│
│ <script src="/globe/three-globe.es5.js"></script> ← 复用 E41 的（共享，不复制）│
│ <script src="/eco-globe/loaders.es5.js"></script>  ← 新增（esbuild 打包）       │
│ <script src="/eco-globe/globe.js"></script>        ← 新增（演示页业务代码改造）   │
└──────────────────────────────────────────────────────────┘
```

⭐ **关键**：three / three-globe / polyfill 三个产物**直接复用 E41 的**，用**绝对路径**引用
⇒ **零重复、零新增 APK 体积**、**不碰 E41 的生产链**。

---

## 四、资产与产物

### 4.1 新目录 `app/src/main/assets/eco-globe/`

| 文件 | 来源 | 大小 | 说明 |
|---|---|---|---|
| `index.html` | 改自 `docs/globe-present.html` 的 `<style>` + `<body>` + 加载 UI | ~4 KB | 脚本引用改成绝对路径 |
| `globe.js` | 改自 `docs/globe-present.html` 的 `<script type="module">` 全体 | ~120 KB | 见 §4.5 的 6 处改造 |
| `loaders.es5.js` | **esbuild 新产物** | ~150 KB | 暴露 `window.GLTFLoader` / `window.SkeletonUtils` |
| `glb/*.glb` | **从 `docs/assets/glb/` 复制**（16 个） | **5.58 MB** | 动物 15 + 树 1 |
| `tex/*.png` | 从 `docs/assets/tex/` 复制（6 个） | ~0.87 MB | `earth` / `bump` / `rough` / `cloud-a` / `cloud-b` / `terrain` |

> ⛔ **`docs/assets/` 不在 app 源集 ⇒ 现在不打包** ⇒ 必须复制进 `app/src/main/assets/eco-globe/`。
> ⛔ 复制而非软链：assets 目录是打包单元，软链在 `mergeDebugAssets` 下不可靠。

### 4.2 `tex/terrain.png` 是**必需**项（不是可选优化）

`elevationFast()` 在 `ELEV.data` 为空时**回退到程序化噪声** `elevationP()`（`globe-present.html:556`）
⇒ 地形、陆海分布、草地/森林候选池**全部会变** ⇒ 与演示页不一致。
⇒ `terrain.png`（185 KB）**必须**带；其余 5 张（`earth/bump/rough/cloud-a/cloud-b`）可由程序化回退生成，
但带上能保证**首屏更快**且**贴图与演示页逐字节一致** ⇒ 建议全带（+0.69 MB）。

### 4.3 为什么不需要「为电视降级」

| 事项 | 结论 |
|---|---|
| 电视端可用性 | ⛔ **反正黑屏**（§10.215 宿主问题）⇒ 兼容 Chrome 39 **零收益** |
| three / three-globe | ⛔ **不用重新降级** —— E41 的 ES5 **UMD** 产物**已在 `assets/globe/`**（`three.es5.js` 987 KB / `three-globe.es5.js` 2007 KB），UMD 用普通 `<script src>` 加载，任何浏览器都行 ⇒ **直接复用** |
| 离线 | ✅ 仍需把库放进 assets（不依赖 unpkg CDN）—— 但**复用 E41 的即可**，不新增 |
| 唯一的新产物 | `GLTFLoader` + `SkeletonUtils`（r160 只有 ESM 版）⇒ **打包**，不是降级（§4.4） |

### 4.4 `loaders.es5.js` 的打包（**不碰 `downlevel_libs.mjs`**）

`three.min.js` / `three-globe.min.js` 之所以能被现有脚本用 `module: None` 直接转译，是因为它们是 **UMD**；
而 r160 的 `examples/jsm/loaders/GLTFLoader.js` 与 `utils/SkeletonUtils.js` 是 **ESM**
（`import { ... } from 'three'`），`module: None` 会**保留 import 语句** ⇒ Chrome 39 语法错误。

⇒ 改用**打包**（bundle）而非降级：

```bash
# ⛔ 依赖装在 app/src/eco-upstream/（构建输入目录，⛔ 不在 assets 下）
cd app/src/eco-upstream
npm install --no-save esbuild@0.24.0 three@0.160.0
node build_loaders.mjs
# 产物 → app/src/main/assets/eco-globe/loaders.es5.js
rm -rf node_modules package.json package-lock.json
```

`build_loaders.mjs` 核心（**别名 three ⇒ 一个只读全局的 shim**，避免把 987KB 的 three 再打进包里）：

```js
// three-shim.mjs —— 只读 window.THREE，不打包 three 本体
const T = globalThis.THREE;
export default T;
export const { AnimationClip, AnimationMixer, Box3, BufferAttribute, BufferGeometry,
  Color, FileLoader, ImageBitmapLoader, LinearFilter, LinearMipmapLinearFilter, Loader,
  Matrix3, Matrix4, Mesh, MeshStandardMaterial, Object3D, PointLight, PropertyBinding,
  Quaternion, RGBAFormat, SRGBColorSpace, TextureLoader, Vector2, Vector3, Vector4 } = T;
```

> ⚠️ **具名导出的清单不要手写** —— 按 `GLTFLoader.js` / `SkeletonUtils.js` 里**实际的 `import { … } from 'three'`** 生成
> （漏一个 ⇒ 该名字在 shim 里是 `undefined` ⇒ **不报错、但运行时静默出错**）。
> 建议在 `build_loaders.mjs` 里**解析源文件的 import 语句自动生成 shim**，避免手抄漏项。

```
esbuild --bundle --format=iife --target=es5 \
  --alias:three=./three-shim.mjs \
  --outfile=app/src/main/assets/eco-globe/loaders.es5.js \
  node_modules/three/examples/jsm/loaders/GLTFLoader.js \
  node_modules/three/examples/jsm/utils/SkeletonUtils.js
```

> `--target=es5` 顺带规避 Chrome 39 的 `Proxy` 缺失问题（§10.215 那个 `three-globe.es5.js:10976` 的坑）。
> ⚠️ **产物必须验运行时**：acorn 的 `ecmaVersion: 5` 只验语法 ⇒ 必须配 **vm 加载测试**（§7.1）。

### 4.5 `globe-present.html` → `index.html` / `globe.js` 的改造清单（6 处）

| # | 位置 | 现状 | 改成 |
|---|---|---|---|
| 1 | `index.html` 的 importmap（`:319-326`） | `{"three": "https://unpkg.com/three@0.160.0/build/three.module.js", "three/addons/": ...}` | **整块删除** ⇒ 改用普通 `<script src>`（绝对路径引 E41 的共享产物） |
| 2 | `index.html` 的脚本段 | `<script type="module">` | 拆成 5 个普通 `<script src>`（顺序不可换：`polyfill → three → three-globe → loaders → globe.js`） |
| 3 | `globe.js` 的 `import * as THREE from "three"`（`:329`） | ESM import | 改用全局 `THREE`（E41 的 `globe.js` 同款：`window.THREE`） |
| 4 | `globe.js` 的 `import { GLTFLoader }`（`:330`） | ESM import | 改用 `window.GLTFLoader`（`loaders.es5.js` 暴露） |
| 5 | `globe.js` 的 `import { clone as cloneSkeleton }`（`:333`） | ESM import | 改用 `window.SkeletonUtils.clone` |
| 6 | `globe.js` 的模型路径（`:1933-1958`，16 处 `assets/glb/x.glb`） | 相对路径（依赖页面同目录） | 改成 `/eco-globe/glb/x.glb`（虚拟资产域绝对路径） |

**保留不动**：
- CDN 回退逻辑（`:2135`「本地副本优先，缺失则回退 CDN」）—— 可保留作兜底，但**运行时零网络**是硬要求 ⇒
  ⇒ **建议删掉回退**（否则缺资源时会真联网，违反离线纪律）；改为「加载失败 ⇒ 报错并停」。
- 业务代码主体（已是 ES5 语法 `var`/`function`）⇒ **不动**。

---

## 五、宿主接线（Kotlin）

### 5.1 复用清单（`WorldGlobeRenderer` 的可复用点）

| 能力 | 现状（`WorldGlobeRenderer.kt`） | 复用方式 |
|---|---|---|
| 虚拟资产域 | `ASSET_HOST = "appassets.androidplatform.net"`、`ASSET_BASE = ".../globe"` | **共用 host**，新增 `/eco-globe` 前缀 |
| 资源拦截 | `shouldInterceptRequest` 按 host 精确匹配 + MIME 猜测 + favicon 空 body | 抽成**共用函数**，加一个前缀参数 |
| console 转发 | `WebChromeClient.onConsoleMessage` → `AppLog`（含 three.js 已知噪音降级） | 抽成共用 |
| 禁焦三件套 | `isFocusable=false` / `isFocusableInTouchMode=false` / `descendantFocusability=FOCUS_BLOCK_DESCENDENTS` | 照抄（否则遥控方向键被 WebView 吃掉） |
| 异步销毁 | `onViewDetached` 里 `wv.post { removeView; destroy() }` | 照抄（同步 destroy 会崩） |
| 100ms tick | `Handler(Looper.getMainLooper())` + `evaluateJavascript("WorldGlobe.setAudio(...)")` | 照抄（改 JS 函数名） |
| 音频 JSON | `{"energy":f,"bass":f,"mid":f,"treble":f,"beat":f}` | 照抄 |

### 5.2 新增 `EcoGlobeRenderer`

```kotlin
class EcoGlobeRenderer : VisualizerRenderer {
    override val theme: VisualizerTheme = VisualizerTheme.ECO_GLOBE
    override val isViewBased: Boolean get() = true
    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {}   // View 旁路，空实现
    override fun createView(context: Context): View = /* WebView，配置见 §5.1 */
    override fun onViewAttached() { /* 起 100ms tick */ }
    override fun onViewDetached() { /* 停 tick + 异步 destroy */ }
    override fun renderFps(): Float = lastFps     // ⛔ 必须覆写（见下）
    override fun onFrame(frame: AudioFrame, ctx: RenderContext) { /* 推数据 */ }
}
```

⚠️ **两个必须覆写的方法**（`VisualizerRenderer.kt:46-56` / `:80-90`）：
- `renderFps()`：View 型有独立渲染循环，舞台徽章量的是 Compose Choreographer 回调率 ⇒ 不覆写会「显示 59 fps 但画面很卡」。
- `onFrame(frame, ctx)`：View 旁路下 `RenderContext.update(...)` 不在 draw 块里被调用 ⇒ `ctx.songId` 永远是旧值。
  ⚠️ **但 main 源集当前没有这两个方法的调用点**（只有私有覆盖层的 `VisualizerStage.kt:294-314, 351` 调）⇒
  ⇒ **照 E41 现状**：自己起 `Handler` tick 直接读 `spectrumRepository.frame`（`WorldGlobeRenderer.kt:380-384` 同款）。

### 5.3 枚举与门禁

```kotlin
// data/model/AppSettings.kt
ECO_GLOBE(R.string.visualizer_theme_eco_globe, "生态地球", Tier.ULTRA, "54", needsParticleBudget = false),
```
- ⚠️ **`ordinalLabel` 取号要现场确认**：E50=50、E51=51 / E52=52（仅私有分支）、E53=53 已占用 ⇒ "54" 是当前推算值，
  ⛔ **落地前读 `VisualizerTheme.entries` 核对**，不要凭空写死。
- **两份 strings**：`values/strings.xml` + `values-en/strings.xml` 都要加 `visualizer_theme_eco_globe`（G1 门禁，缺一 ⇒ 运行时崩溃）。
- **`Tier.ULTRA`**：与 E41 同档（`Tier.ULTRA -> allowFramebuffer`，而 `allowFramebuffer` **只有 HIGH 画质档为 true**
  ⇒ **只有 HIGH 档提供本主题**，MEDIUM / LOW 不提供）。
  ⚠️ 注意：**电视若把画质设成 HIGH，本主题仍会被选中 ⇒ 仍然黑屏**（宿主问题）⇒
  ⇒ 若希望电视上完全不可见，需另加设备门控；本方案**不做**（与 E41 现状保持一致：E41 在 HIGH 档的电视上同样黑屏）。
- **`needsParticleBudget = false`**：本主题**不读** `ctx.quality.maxParticles`（数量在 JS 侧写死）⇒ 按 `ParticleBudgetGateTest` 的判据必须标 `false`。
- **`FxCoverageScanTest`**：新渲染器是 View 型 ⇒ **必须登记进 `exempt`**（照 `WorldGlobeRenderer` 的条目）。
- **`VisualizerRendererFactory.createEnabled`**：加 `VisualizerTheme.ECO_GLOBE -> EcoGlobeRenderer()` 分支。

---

## 六、构建流水线

```
app/src/eco-upstream/                    ← 构建输入（不进 APK）
  build_loaders.mjs                     ← esbuild 打包脚本
  three-shim.mjs                        ← 只读 window.THREE 的 shim
  node_modules/                         ← ⛔ 用完即删（装在 assets 下会被打进 APK）

app/src/main/assets/eco-globe/           ← 运行时（进 APK）
  index.html  globe.js  loaders.es5.js  glb/*.glb  tex/*.png
```

⚠️ **`npm install` 必须装在 `app/src/eco-upstream/`**，⛔ 不能装 `assets/` 下
（2026-09-06 实测：`typescript` + `acorn` 的 `node_modules` 共 **23.3 MB** 被 `mergeDebugAssets` 打进 APK）。

---

## 七、验证

### 7.1 vm 加载测试（**必须**）

`downlevel_libs.mjs` 的 `assertEs5()`（acorn `ecmaVersion: 5`）**只证明语法，不证明运行时**
（§10.215 那个 `Proxy` 坑就是从语法校验里溜过去的）⇒ `loaders.es5.js` 必须配 vm 加载测试：

- 在 Node `vm` 沙箱里删掉 Chrome 39 缺失的 API（`Proxy` / `Object.assign` / `Array.from` …）
- 按 `polyfill → three → three-globe → loaders → globe.js` 顺序加载
- 断言 `THREE` / `ThreeGlobe` / `GLTFLoader` / `SkeletonUtils` 均已定义
- 断言 `globe.js` 里的 `initApp()` 能跑通（起一个 WebGL stub 即可）

### 7.2 手机真机（需所有者执行）

- [ ] 主题出现在效果列表，显示名正确（中/英）
- [ ] 进出场正常（无黑屏、无崩溃）
- [ ] 150 棵树 / 19 只动物都出现、骨骼步态正常（`SkeletonUtils.clone` 生效）
- [ ] 四季循环正常（60 s/轮，HTML 原值 `CONFIG.seasonPeriod`）
- [ ] 龙卷风间歇出现、城市灯火只在陆地、三层云反向视差
- [ ] 音频驱动生效（自转速度 / 粒子 / 流星）
- [ ] **运行时零网络**（抓包或日志确认无 unpkg / poly.pizza 请求）
- [ ] FPS 徽章读数与实际相符（`renderFps()` 覆写生效）

### 7.3 电视（**预期黑屏**，需确认"黑屏而非崩溃"）

- [ ] 电视上选到该主题 ⇒ 画面黑（或明确提示），**不崩溃、不 ANR**
- [ ] 切走该主题后内存回落（WebView 异步销毁生效）

### 7.4 门禁

| 门禁 | 要求 |
|---|---|
| `GlobeAssetsHygieneTest` | ⚠️ 当前只扫 `assets/globe/` ⇒ **需扩展**到 `assets/eco-globe/`：① `node_modules` 不得在 assets ② `package.json`/`package-lock.json` 不得在 assets ③ 构建输入不得在 assets |
| `FxCoverageScanTest` | 新渲染器登记 `exempt` |
| `ParticleBudgetGateTest` | 枚举 `needsParticleBudget = false` 与源码一致 |
| `VisualizerThemeStringsGateTest`（G1） | 两份 strings 都有 key |
| `RendererBaseContractTest`（G12） | ⚠️ **不适用**（只扫 `RendererFx` 子类；本主题是 View 型） |

> ⚠️ `GlobeAssetsHygieneTest` 的**已知失效模式**（其 KDoc `:28-37`）：它只读文件系统、不引用 Gradle 输入 ⇒
> 只改 assets 不改源码时，Gradle 可能判测试 **UP-TO-DATE 根本不重跑** ⇒
> **人工验证必须带 `--rerun-tasks`**。

---

## 八、里程碑与工作量

| Phase | 内容 | 产出 | 工作量 |
|---|---|---|---|
| **0** | 资产准备：复制 16 个 GLB + 6 张贴图；建 `app/src/eco-upstream/`；写 `build_loaders.mjs` + `three-shim.mjs`；产出 `loaders.es5.js` | 可加载的 loader 包 | **1~1.5 天** |
| **1** | 页面改造：`globe-present.html` → `index.html` + `globe.js`（§4.5 的 6 处） | 可离线加载的页面 | **1~1.5 天** |
| **2** | Kotlin 宿主：`EcoGlobeRenderer` + 虚拟域多前缀路由 + 枚举 + strings + 门禁登记 | 主题可被选中 | **1.5~2 天** |
| **3** | 验证：vm 加载测试 + 手机真机 + 电视黑屏确认 | 验收证据 | **0.5~1 天** |
| **4** | 收尾：CHANGELOG + `docs/technical-overview.md` §10 + 本文档定稿 | 归档 | **0.5 天** |

**合计：约 4~6.5 天**（单人）

**APK 增量：约 +6.6 MB**（GLB 5.58 + 贴图 0.87 + loader ~0.15）

---

## 九、风险

| # | 风险 | 缓解 |
|---|---|---|
| R1 | **esbuild 产物在 Chrome 39 上跑不起来**（`GLTFLoader` 用到 `Object3D.copy` / `BufferAttribute` 等，ES5 target 可能引入 `__spreadArray` 之类的辅助） | `--target=es5` 出产物 + **vm 加载测试必须真跑**（§7.1）；失败则退 `--target=es2015`（放弃电视兼容，反正黑屏） |
| R2 | **`SkeletonUtils.clone` 在 ES5 化后骨骼引用断裂**（仓库历史上踩过：普通 `clone(true)` 不重建 `skeleton.bones` ⇒ 多实例串台，见 `globe-present.html:331-333` 的注释） | 必须在真机上验证**多实例步态不串台**（§7.2） |
| R3 | **5.58 MB GLB 进包后 APK 体积** | 已核对；必要时可只带**用到的** GLB（演示页 16 个全用） |
| R4 | **运行时零网络被破坏**（演示页的 CDN 回退逻辑） | **删掉回退**（§4.5），缺资源直接报错 |
| R5 | **电视上崩溃而非黑屏** | `Tier.ULTRA` + 电视上不可选；且 `WebView` 崩溃要能降级（见 §5.2 的兜底） |
| R6 | **E41 被牵连** | ⛔ **不碰 `downlevel_libs.mjs`、不碰 `assets/globe/` 现有文件**（只新增 `eco-globe/`）⇒ E41 零风险 |
| R7 | **`GlobeAssetsHygieneTest` 覆盖不到新目录** ⇒ 依赖残留被打进 APK | §7.4 要求**扩展该测试**到新目录 |

---

## 十、验收标准

**已核对（2026-11-08，证据见 §12.6 / §12.9）**

- [x] **门禁全绿**（含扩展后的 `GlobeAssetsHygieneTest`）：全量 287 类 / 2895 例 / 0 失败；
      `lintDebug` 0 error（新增 4 个 `UseKtx` warning 与 E41 `WorldGlobeRenderer` 完全同款）。
- [x] **APK 增量 ≤ 7 MB**：实测 APK 内 `assets/eco-globe/` 压缩后 **3,607,550 B**（未压缩 6,935,798 B）。
- [x] **运行时零网络**（静态侧）：CDN 回退与 16 个 `url` 字段已删、`static.poly.pizza` 计数 0；
      `scripts/check_eco_globe_refs.py` 核对全部引用可解析、磁盘无冗余资产。
- [x] **显示名中/英正确**：`visualizer_theme_eco_globe` 双语 key 由 `VisualizerThemeStringsGateTest` 守住。
- [x] `docs/technical-overview.md` §10 记录（§10.363）+ CHANGELOG 一行 + 本文档定稿（v1.1）。

**待真机（⏳ 需所有者执行，见 §7.2）**

- [ ] 主题出现在效果列表、进出场正常（无黑屏、无崩溃）
- [ ] **观感与 `docs/globe-present.html` 一致**（150 树 / 19 动物 / 山脉 / 龙卷风 / 城市灯火 / 三层云 / 极光 / 流星 / 四季）
- [ ] 骨骼步态正常、多实例不串台
- [ ] 音频驱动生效（自转速度随能量变化）
- [ ] 电视上「黑屏但不崩溃」（与 E41 同源的宿主问题，§10.215）

---

## 十一、与「原生 GL 重写」的关系

| | 本方案（WebView） | `e41-globe-opengl-plan.md`（原生 GL） |
|---|---|---|
| 保真度 | **100%**（同一个 three.js 运行时） | 需逐元素重写，靠人工对账 |
| 电视可用 | ⛔ 黑屏 | ✅ |
| 工作量 | **4~6.5 天** | 31~45 天 |
| APK | +6.6 MB | ~0 |

⇒ **两条路线不冲突**：本方案**先落地**，产出「观感基准」；原生 GL 重写以它为**逐元素对账的参照**，
其唯一价值就是解决电视端可用性。

---

## 十二、落地记录（2026-11-08，实测）

### 12.1 实际产出与体积

| 项 | 路径 | 实测 |
|---|---|---|
| 页面 | `app/src/main/assets/eco-globe/index.html` | 25,144 B |
| 业务代码 | `app/src/main/assets/eco-globe/globe.js` | 168,124 B |
| loader 产物 | `app/src/main/assets/eco-globe/loaders.es5.js` | **114,432 B** |
| 模型 | `app/src/main/assets/eco-globe/glb/` | 16 个 / 5,578,096 B |
| 贴图 | `app/src/main/assets/eco-globe/tex/` | 6 张 / 1,050,002 B |
| 构建输入（不进 APK） | `app/src/eco-upstream/` | `build_loaders.mjs` / `entry.mjs` / `three-shim.mjs` / `check_shim_names.mjs` |
| 页面生成器 | `scripts/build_eco_globe_page.py` | 幂等；⛔ 不手改产物 |
| vm 加载测试 | `scripts/eco_globe_vm_load_test.mjs` | exit=0 |

**APK 增量 ≈ 6.6 MB**，与 §八 预估一致。

### 12.2 ⭐ 偏离 R1：`--target=es5` 不可用 ⇒ 改 **es2015**

§4.4 与 R1 原写 `--target=es5`（顺带规避 Chrome 39 的 `Proxy` 缺失）。实测 **esbuild 0.24.0 根本降不到 ES5**：

```
Transforming class syntax to the configured target environment ("es5") is not supported yet
Transforming default arguments to the configured target environment ("es5") is not supported yet
...
892 errors
```

⇒ 按 R1 预告的退路改 **`--target=es2015`**。**损失为零**：
电视端本来就是 WebView 黑屏（`technical-overview.md` §10.215），手机 WebView 均 ≥ Chrome 60。

⚠️ 由此带来的两点必须知道：
1. **文件名 `loaders.es5.js` 是沿用命名习惯**，产物实际是 ES2015 —— 不要因为文件名去怀疑内容。
2. vm 测试**不再模拟「Chrome 39 缺失 API」**（那会让测试因"本来就放弃的目标"而恒红），
   改为验证「按真实顺序加载不抛 + 契约对象齐全 + 负向自证」。

### 12.3 shim 的具名导出**自动生成**（⛔ 不手抄）

`build_loaders.mjs` 递归解析两个 loader 可达源文件里所有 `import { … } from 'three'`，
收集到 **69 个**具名导入，生成 `three-shim.mjs`；`check_shim_names.mjs` 对 three r160 逐个核验
⇒ `names 69 / missing []`。

> 手抄漏一个的后果：`export var X = T.X` 拿到 `undefined` ⇒ **不报错、但运行时静默出错**。
> 这是本方案里最容易埋的雷，因此把"生成"而不是"抄写"作为唯一路径。

`three` 本体不进包：esbuild 的 `onResolve` 插件把 `^three$` 别名到 shim（只读 `window.THREE`）。

### 12.4 页面生成器的五处硬断言（防静默产残页）

`scripts/build_eco_globe_page.py` 在改动源文本前后逐项断言，任一不满足即 `exit 1`：

1. GLB 路径 `'assets/glb/*.glb'` 必须正好 **16** 处（改后 `/eco-globe/glb/` 也是 16 处）；
2. CDN `url: 'https://static.poly.pizza/*.glb'` 字段必须正好 **16** 个（全部删除）；
3. `bakedDir: 'assets/tex/'` 必须存在（⇒ `/eco-globe/tex/`）；
4. `earth.rotation.y += dt * SPIN_RATE;` 与 `clouds.rotation.y += dt * CLOUD_RATE;` 必须存在（音频注入锚点）；
5. `renderer.render(scene, camera);` 必须存在（帧率采样锚点）；
   工具：改完必须 `static.poly.pizza` 计数 **0**。

> ⚠️ 这些都是为了「源文件结构一变就立刻失败」，而不是静默产出少几个模型的残页。

### 12.5 追加的两个宿主契约（原型页本没有）

| 契约 | 语义 |
|---|---|
| `EcoGlobe.setAudio({energy,bass,mid,treble,beat})` | 只映射到**自转 / 云层转速**：`SPIN_RATE * (1 + energy*0.85 + beat*0.35)`、`CLOUD_RATE * (1 + energy*0.6)`。⛔ 不引入随机性、不碰摆放/季节/龙卷风时序 ⇒ 观感可与演示页逐帧对账 |
| `EcoGlobe.fps()` | 页面内 **500ms 滑窗**统计真实 rAF 帧率，供 `EcoGlobeRenderer.renderFps()` 覆写 |

`fps()` 存在的原因：**View 型渲染器有自己独立的渲染循环**，舞台 `withFrameNanos` 的
Choreographer 口径对它是假读数（「显示 59 fps 但画面很卡」）⇒ 必须由页面自报。

### 12.6 验证结果

| 手段 | 结果 |
|---|---|
| `compileDebugKotlin` | **BUILD SUCCESSFUL in 3m 9s**（exit=0） |
| `node scripts/eco_globe_vm_load_test.mjs` | **exit=0**：5 脚本按序加载；`THREE r160` / `ThreeGlobe` / `GLTFLoader` / `SkeletonUtils.clone` / `EcoGlobe.*` 全就位；`setAudio` 对合法/非法/null 均不抛；含「缺 three 时 loaders 明确报错」负向自证 |
| 门禁单测（5 类） | **30 例全绿**：`FxCoverageScanTest` 8 / `GlobeAssetsHygieneTest` 8 / `ParticleBudgetGateTest` 5 / `VisualizerThemeStringsGateTest` 3 / `EffectFurnitureGateTest` 6 |
| **全量** `testDebugUnitTest` | **287 类 / 2895 例 / 0 失败 / 0 错误 / 0 跳过**（两轮返修后，2026-11-08） |
| `lintDebug` | 通过，无新增 error |

⚠️ vm 测试的 DOM 桩用 `Proxy` 兜底空操作 —— 白名单式桩会因页面新增 2D API 而**假红**。
vm 里没有 WebGL ⇒ `boot()` 走 `catch` 分支属**预期**，不算失败。

### 12.7 门禁登记的真实改动

- 枚举：`ECO_GLOBE(R.string.visualizer_theme_eco_globe, "生态地球", Tier.ULTRA, "54", needsParticleBudget = false)`
  - `ULTRA` 与 E41 同档 ⇒ **只在 HIGH 画质提供**；
  - `false`：数量全部写死在 JS 的 `CONFIG`，渲染器**不读** `ctx.quality.maxParticles`
    （`ParticleBudgetGateTest` 按源码反推真值，标错直接红）。
- `FxCoverageScanTest`：`exempt` + `structuralExempt` 加 `EcoGlobeRenderer`（View 型），**类数下限 26 → 27**。
- `GlobeAssetsHygieneTest`：新增 3 例 —— assets 禁依赖/构建输入、运行时资产齐全（16 GLB + 6 贴图，
  `terrain.png` 单列）、构建输入确实在 `eco-upstream/`。
- `docs/visualizer-effects-list.md`：新增 `| E54 | ECO_GLOBE | 生态地球 |`。

### 12.8 私有分支处置（`feat/e51-private`）

⚠️ **覆盖层 ↔ 隐藏分支的漂移门禁**（`PrivateOverlayDriftGateTest`）会逐字节比对
`app/src/e51-overlay/` 与本地分支 `feat/e51-private` ⇒ **在覆盖层工厂里加 `ECO_GLOBE` 分支后，
该门禁立刻变红**（漂移 1 处：`visualizer/VisualizerRendererFactory.kt`）。

⛔ **不能直接跑 `python scripts/sync_private_overlay.py`** —— 它的方向是「私有分支 → 覆盖层」，
会把覆盖层里新增的 `ECO_GLOBE` 分支**覆盖掉** ⇒ `when` 不穷尽 ⇒ 本地构建编译失败。

⇒ 正解：**把 E54 的改动同样落到私有分支**（用 `git worktree` 在 `feat/e51-private` 上做，
⛔ 全程**本地提交、不 push**）。落到私有分支的内容：

| 文件 | 处理 |
|---|---|
| `EcoGlobeRenderer.kt` | 新增（与 main 逐字节相同） |
| `assets/eco-globe/**` | 新增（与 main 相同 ⇒ git 对象去重，**不额外占空间**） |
| `app/src/eco-upstream/**` + 3 个脚本 | 新增（与 main 相同） |
| `AppSettings.kt` | 加 `ECO_GLOBE` 枚举（序号 `"54"`） |
| `values/strings.xml` / `values-en/strings.xml` | 各加 1 个 key |
| `VisualizerRendererFactory.kt` | 加 import + `ECO_GLOBE` 分支（与覆盖层那份一致） |
| 4 个测试文件 | `VisualizerThemeTest`（计数 27→28）、`FxCoverageScanTest`（exempt + 下限 27→28）、`GlobeAssetsHygieneTest`（E54 三例）、`UpstreamOriginalsHygieneTest`（白名单加 `assets/eco-globe/`） |

### 12.9 全量单测暴露的门禁返修（**跑了两轮才收敛**）

⚠️ **只跑「与本次相关的 5 个门禁类」是不够的** —— 第一轮 30 例全绿，但**全量** `testDebugUnitTest`
（287 类 / 2895 例）跑出 **3 类红**；修完再跑全量，又冒出**第二轮才暴露的 2 处漏改**。
⇒ **教训：新增枚举项后必须跑全量，不能只跑自己关心的门禁类**。

| # | 门禁 | 现象 | 修法 |
|---|---|---|---|
| 1 | `VisualizerThemeTest` | 硬编码条目数 `27` → `28` | 条目总数、`selectable`（`25 → 26`，两处）、照片墙开/关两份列表（`24/25 → 25/26`）、编号互异、显示名互异 —— **共 6 处** |
| 2 | `UpstreamOriginalsHygieneTest` | 新目录被判「上游原件混入」 | 白名单补 `assets/eco-globe/`，**并写明依据**（见下） |
| 3 | `PrivateOverlayDriftGateTest` | 覆盖层 ↔ 隐藏分支漂移 1 处 | 见 §12.8（在 `feat/e51-private` 上同样落下改动） |
| 4 | `StarrySkyTest`（E42 的门禁） | 「注册链 枚举 工厂 计数三处一致」里又一处硬编码 `27` | `27 → 28` |
| 5 | `VisualizerThemeTest` 编号互异 | 第 1 轮**只改了注释、漏了断言本身** ⇒ 第二轮仍红 | `assertEquals(27, labels.distinct().size)` → `28` |

⚠️ **第 4、5 条的共同教训**：**硬编码计数散落在多个测试文件里**（效果门禁在 `VisualizerThemeTest`、
E42 门禁在 `StarrySkyTest`），只按「改了哪个枚举就改哪个测试」的直觉去找，必然漏。
⇒ 正确做法：**先 `grep` 整个 `app/src/test/java` 找全部 `entries.size` / 套数断言**，再逐个对账
（本次据此复查，确认除这两处外，其余计数断言都是**自推导**的 `VisualizerTheme.entries.size` 两边相等，或与效果数无关）。

⚠️ 第 2 条的**依据**（AGENTS.md 的授权判据）：
`globe.js` 由本项目自己的 `scripts/build_eco_globe_page.py` 从 `docs/globe-present.html`
（本项目自有演示页）生成；`loaders.es5.js` 是把 **MIT 授权**的 three.js addons
（GLTFLoader / SkeletonUtils）用本项目自己的 `build_loaders.mjs` 打包而成（MIT 允许再发布）⇒
**不是「无授权的上游原件」**，补进白名单并写明理由。

### 12.10 遗留项

1. ⏳ **§7.2 手机真机项待所有者复测**：2026-11-08 真机第 1 轮报了两个问题（动物全挂 + 画面文案），
   **均已修复**（§12.11）⇒ 需要**用新包复测**：主题出现 / 进出场 / 150 树 19 动物与骨骼步态 /
   四季循环 / 龙卷风 / 音频驱动 / 运行时零网络。
2. 📌 **静态引用核对脚本**（`scripts/check_eco_globe_refs.py`）：vm 加载测试**不发起 fetch**
   ⇒ 抓不到「引用了但文件不存在」的缺件。该脚本覆盖两种引用形态
   （完整路径字面量 / `bakedDir` + 文件名拼接），并反向核对磁盘上没有白带的 GLB。
3. 📌 **GLB 自包含性核对**（`scripts/check_eco_globe_glb.py`）与
   **真实 loader 解析**（`scripts/eco_globe_glb_parse_test.mjs`）：前者查 GLB 有没有外部贴图引用，
   后者用真实产物在 vm 里解析真实 GLB ⇒ 两者合起来才能区分「资产问题」与「加载链路问题」。
4. 📌 **踩坑（给后来者）**：`EcoGlobeRenderer.kt` 的 KDoc 里写 `glb/*.glb` 与 `/globe/*` 会触发
   Kotlin 的**可嵌套块注释** ⇒ `Unclosed comment`，报错位置却指向文件末尾（极具误导性，本次踩了两次）。
   改完 Kotlin 源码先跑 `python scripts/kotlin_comment_balance.py`。

### 12.11 真机第 1 轮的两个问题（2026-11-08，已修）

**① 画面上的演示页文案**（「生态地球」「CARTOON GLOBE · 自动旋转演示」等）
⇒ 生成脚本里去掉 `<div class="title">`、加载遮罩的 `<h2>/<p>/<small id="loadPct">`、
以及 `#status` 状态条；**只留一条进度条与错误框**（错误框文案也改成 App 语境：
本主题运行时零网络 ⇒ 不能写「请检查网络」）。`<title>` 里的「· 自动旋转演示」一并改掉。

**② 动物一只都没加载出来**（树正常）
⇒ 根因：生成时删掉了 `import { clone as cloneSkeleton } from "three/addons/utils/SkeletonUtils.js"`，
却**没补回 `cloneSkeleton` 这个局部绑定** ⇒ 动物摆位调用它时抛
`ReferenceError: cloneSkeleton is not defined` ⇒ 被 `runPlan` 的 catch 吞成「加载失败，已跳过」
⇒ **页面不报错、树能出来、动物全没有**。

修法 + 两道防复发门禁（生成脚本的 `PROVIDERS` 登记 + 落地核对；vm 测试从源页面动态抽导入名核对）
详见 `docs/technical-overview.md` §10.364。两条回归判据已固化进打包门禁
`scripts/verify_eco_globe_in_apk.py`（APK 内 `globe.js` 必须含 `var cloneSkeleton`；
`index.html` 不得再有那几处文案）。
