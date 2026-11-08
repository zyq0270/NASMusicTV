# E41「世界」原生 OpenGL ES 重写方案

> **版本**: v1.0（2026-11-08）
> **状态**: 开发方案评审
> **关联**: `docs/technical-overview.md` §10.215 / §10.216 / §10.197、`docs/archive/e41-tv-blackscreen-fix-plan.md`、`docs/archive/e52-highway-opengl-plan.md`
> **决策依据**: 所有者 2026-11-08 指示「用 OpenGL ES 方案」

---

## 〇、决策记录（2026-11-08 所有者裁决）

方案评审提出的三个待裁决点，**所有者 2026-11-08 裁决「按建议处理」** ⇒ 全部转为已决：

| # | 决策点 | 裁决 | 落地要点 |
|---|---|---|---|
| **D1** | **E41 档位下放**（ULTRA → ADV / 三档全可选） | ✅ **下放** | ⛔ **但不是现在改**：当前 WebView 版确实读 `ctx.quality.maxParticles` 且 TV 端黑屏 ⇒ 提前下放会把黑屏效果暴露给 MEDIUM/LOW 用户（**功能级回归**）⇒ 枚举改动**绑定在 GL 版上线那一刻**（Phase 7）。详见 §6.2 |
| **D2** | `app/src/globe-upstream/` 的 three.js / three-globe **ES6 原版** | ✅ **保留**（不删） | 三条理由：① **MIT 上游件**（three.js / three-globe），保留**无版权风险**；② 是 ES5 产物的**逐字节 diff 基线**（AGENTS.md 明文「永不删除、永不修改、永不移回 assets」）⇒ 该规则**继续有效**，不需要改 AGENTS.md；③ WebView 版若因任何原因回滚/对照，缺了再生源就无法复现。⇒ **GL 版落地后 `globe-upstream/` 五个文件全部留存**，只是不再有运行时消费者 |
| **D3** | **GL 基础层收敛**（避免写第三套） | ✅ **收敛到 `visualizer/gl/`** | 拆成两层：**通用部分上 main**（公开，两分支一致）＋ **上游派生部分留私有分支**。详见 §4.1 |

**D1 的一句话结论**：GL 版上线 ⇒ E41 从「只有最高画质档提供」变为「三档全可选」⇒ **老电视（创维 9R54_G8S）也能看到这个效果** —— 这是整个 GL 重写最大的用户可感知收益。

---

## 一、为什么重写（动机与既有事实）

### 1.1 阻断点不在渲染能力，在宿主

E41 当前走 **WebView + three-globe（WebGL）**，而 `docs/technical-overview.md:13460`（§10.215）记着一件已被真机确诊的事：

> 曾把地球从 three-globe 整体重写为原生 three.js，并真机验证画面正确可用；随后**全部回退**——因为真正的阻断点根本不在 three-globe，而在**宿主**：这台电视的系统 WebView 被 Compose `AndroidView` 承载时**只画出第一帧，之后永不更新**。

已排除的三条路（`docs/archive/e41-tv-blackscreen-fix-plan.md`）：`preserveDrawingBuffer` / `LAYER_TYPE_SOFTWARE` / `onResume`。

⇒ **只要还用 WebView，电视端就有这个天花板**。WebGL 本身没问题（原生 three.js 版本画面正确），问题在 WebView 的合成路径。

### 1.2 连带代价：E41 被门控到最高档，老电视根本看不到

| 事实 | 位置 |
|---|---|
| `WORLD(..., Tier.ULTRA, "41", needsParticleBudget = true)` | `data/model/AppSettings.kt:294` |
| `Tier.ULTRA -> allowFramebuffer`，而 `allowFramebuffer` **只有 HIGH 为 true** | `AppSettings.kt:637`, `:649` |
| ⇒ MEDIUM / LOW 档**一律不提供** E41 | 同上 |

⇒ 创维 9R54_G8S（Android 5.1.1）这类老电视上，E41 **看不到**。这是用户可感知的缺失，而 GL 路线能解掉。

### 1.3 当初选型的判断已被后来证伪

`renderers/WorldGlobeRenderer.kt:34-47` 记着 2026-09 的选型理由：*WorldWindKotlin 要求 minSdk 24 被否；手写 OpenGL ES 球体细分/光照/大圆插值成本过高 ⇒ 选 three-globe*。

**「成本过高」这条在 2026-10/11 被 E52 证伪**：E52「旷野公路」已在本仓库落地一整套原生 OpenGL ES 管线（`GLSurfaceView` + `GLES20` + 自写 ESSL + VBO + 自写投影矩阵 + 自适应画质 + 主线程↔GL 线程数据桥），且在**同一台创维机**上真机跑通（PowerVR Rogue G6110 / OpenGL ES 3.1）。

---

## 二、路线裁决

| 方案 | 裁决 | 理由 |
|---|---|---|
| **A 原生 OpenGL ES** | ✅ **本方案** | 绕开宿主限制；E52 已趟平全部基础设施；地球负载比 E52 轻一个数量级 |
| B 继续 WebView + three-globe | ❌ | TV 端黑屏是宿主问题，换库不解决 |
| C Canvas 2D 伪 3D | ❌ | ① 物理上限：Canvas `fill()` 无片元着色器/纹理采样（`docs/archive/e52-highway-opengl-plan.md:29-43`）② **已被裁决封死**：`docs/archive/visualizer-texture-upgrade-plan.md:4476` D6「⛔ 不得改回 Compose Canvas、不得把旧 `WorldRenderer` 重新接回工厂」 |
| D 离线预烘焙帧序列 | ❌ | E52 方案 A 已被所有者否决（2026-10-29）；且失去音频驱动 |
| E RuntimeShader / AGSL | ❌ | 需 API 33；且是全库禁物（`StarrySkyTest.kt:1719`，E42 局部红线） |

---

## 三、可复用资产盘点

### 3.1 贴图（`app/src/main/assets/globe/`，全部 2048×1024 或 1024×512，**2 的幂 ⇒ 可 mipmap**）

| 文件 | 尺寸 | 用途 | 状态 |
|---|---|---|---|
| `earth_lit.jpg` | 2048×1024 / 340 KB | 昼面（NASA Blue Marble，亮度压到 0.62） | ✅ 复用 |
| `earth_night.jpg` | 2048×1024 / 188 KB | 夜面城市灯光（与昼面**同源同投影 ⇒ 像素级对齐**） | ✅ 复用 |
| `earth_glow.jpg` | 2048×1024 / 138 KB | 大陆辉光（离线已高斯模糊 ⇒ ⛔ 不可在 shader 里再偏移 UV 低通） | ✅ 复用 |
| `moon.jpg` | 1024×512 / 325 KB | 月面反照率 | ✅ 复用 —— ⚠️ **E44「明月」共用**（`MoonlitRenderer.kt:1448` `MOON_ASSET = "globe/moon.jpg"`）⇒ **清空 `assets/globe/` 会打断 E44** |

### 3.2 数据层（`app/src/main/java/com/nasmusic/tv/visualizer/renderers/`）

| 类 | 生产调用点 | 裁决 |
|---|---|---|
| `WorldCities`（37 座：Tier 9/11/11/6） | ✅ `WorldGlobeRenderer.kt:513-557` | ✅ **保留**（`ALL` / `tierOf` / `paxOf` / `weightOf` / `pickWeighted` / `distanceKm` / `nearestHubs` / `activeFlightRange`） |
| `WorldNetwork`（航线算法模型，3 级 + 4 拍点档） | ✅ `WorldGlobeRenderer.kt:391-498` | ✅ **保留**（`classify` / `pickRoute` / `maxParallelLanes` / `maxActiveFlights` / `MAX_ROUTE_KM=15000`） |
| `BeatClassifier` | ✅ `WorldGlobeRenderer.kt:153` | ✅ **保留**（`update(bassRaw, nowMs, hasBeat)`） |
| `WorldProjection`（Robinson 19 行 5° 标准表） | ❌ **零生产调用点** | ⛔ 2D 遗留 ⇒ GL 版不需要（3D 版用真球面） |
| `WorldTerminator`（真 UTC 晨昏线几何） | ❌ **零生产调用点** | ⛔ 2D 遗留 ⇒ GL 版由**片元着色器的 `uSunDirView`** 取代（更准、更便宜） |
| `WorldMapData` / `WorldLandmass`（Natural Earth 110m 轮廓，127 环 / 4169 点） | ❌ **零生产调用点** | ⛔ 2D 遗留 ⇒ GL 版不需要 |

⚠️ 删这 3 个类前必须再跑一次全仓 grep（含 `app/src/test`）——它们有单测覆盖，删类要同时删测试。

### 3.3 数值资产（WebView 侧全部实测值，逐一移植）

| 项 | WebView 实测值 | 位置 |
|---|---|---|
| 相机 | `PerspectiveCamera(50, 1, 50, 1000)`，`position.z = 260`，aspect 每帧更新 | `globe.js:41-45` |
| 地球半径 | **100**（three-globe 内部 `de = 100`） | `three-globe.es5.js` |
| 地球网格 | `SphereGeometry(100, 90, 45)`（**实测** `globeCurvatureResolution=4 ⇒ n=90`） | `three-globe.es5.js` |
| ⚠️ 注释错误 | `globe.js:550-552` 写的「180×90」**是错的**（那是 tile engine 的球） | — |
| yaw | `EARTH_YAW_OFFSET = -π/2` 是 **three-globe 的球体朝向补偿，不是地理事实** ⇒ **原生实现 yaw = 0** | §10.215 已定案 |
| 地球材质 | `MeshPhongMaterial{color:0xffffff, emissive:0x000000, shininess:8, specular:0x14243c}` | `globe.js:288-295` |
| 大气层 | 球壳沿法线外推 `size = 100×0.16 = 16` ⇒ **外径 116**；`BackSide`；`depthWrite:false`；片元 `pow(0.1 + dot(normal, viewDir), 3.5)`；`hollowRadius=100` 区域 `discard` | `globe.js:526-528` + three-globe `Atmosphere` |
| 夜面壳 | `SphereGeometry(100.6, 144, 90)` | `globe.js:321` |
| 夜面片元 | `night = smoothstep(0.10, -0.10, ndl)`；`col = vec3(0.045,0.070,0.125)`；`col += lamp * vec3(1.00,0.84,0.55) * 2.6`；`col += glow * uContinentGlow`；`alpha = min(night * uNightLevel, 0.85)` | `globe.js:339-385` |
| 自转 | `rotation.y += 0.00022 × (1 + energy×0.8)` ⇒ ≈0.76°/s ⇒ 470 s 一圈 | `globe.js:749` |
| 太阳 | `SUN_DIR=(0.60,0.52,0.34).normalize()`，`SUN_DIST=700`；`DirectionalLight(0xfff4e0, 3.4)`；`AmbientLight(0x6f86b0, 0.18)`（**必须压低**，否则抹掉昼夜分界）；sprite `scale 150×(1+0.10×energy)` | `globe.js:75-119` |
| 月球 | `RADIUS=16`、`ORBIT=150`、`INCLINATION=5.145°`、`PERIOD=110000 ms`；`SphereGeometry(16,96,64)`；`map = emissiveMap = moon.jpg`（⛔ 缺 emissiveMap 暗面会变均匀灰球） | `globe.js:396-511` |
| 星空 | 两层：`(1500, 2.6, 0.80, seed 20260929)` + `(380, 4.8, 1.00, seed 77712345)`；`STAR_RADIUS=600`；固定种子 LCG `s=(s*1103515245+12345)&0x7fffffff` | `globe.js:121-222` |
| 城市光点半径 | `TIER_RADIUS = {1:0.20, 2:0.15, 3:0.11, 4:0.08}`（**单位是地球度数** ⇒ 实际世界半径 = `pointRadius × 1.74533`）；`pointAltitude 0.02` ⇒ 高 2.0 世界单位 | `globe.js:569` |
| 城市光点色 | `{1:#fff1b8, 2:#ffc14d, 3:#ff7a5c, 4:#5ec8b5}` | `globe.js:563-568` |
| 城市呼吸 | `r = base × (1 + 0.18 sin(t×0.0005×2π + φ))`，`φ = (i × 2.39996323) mod 2π`（黄金角）；拍点 `×(1 + 0.5×beatEnv)`，`beatEnv ×= 0.94`/帧；刷新限流 30 Hz | `globe.js:684-702` |
| 航线弧高 | `max(0.05, span/180 × 0.22) × (1 + lane × 0.34)`（`arcAltitude` = **球半径倍数** ⇒ 半径 = `100×(1+alt)`） | `globe.js:598-629` |
| 航线配色 | TRUNK `rgba(150,205,255,0.5/0.95)` stroke 1.1 dash 1600 ms；REGIONAL `rgba(90,150,230,0.5/0.8)` stroke 0.75 dash 2600 ms；FEEDER `rgba(60,110,180,0.45/0.7)` stroke 0.5 dash 3800 ms | `globe.js:589-596` |
| 航线几何 | `arcStroke` 非 null ⇒ **`TubeGeometry`**（`radius = stroke/2`）；`arcCurveResolution=64`，`arcCircularResolution=6` | three-globe 内部 |
| dash 片元 | `if (vRelDistance < dashOffset) discard; if (mod(vRelDistance - dashOffset, dashSize + gapSize) > dashSize) discard;` | three-globe 内部 |
| 音频驱动 | `energy`→自转速度 / 夜面亮度(`0.88+0.22×energy`) / 大陆辉光(`0.95+0.45×energy`) / 太阳尺寸；`beat`→光点半径；`bass/mid/treble` **JS 侧只存不用**（Kotlin 侧用于选航线） | `globe.js:721-812` |
| 数据下发 | **100 ms** 一次（`TICK_MS`），经 `Handler(Looper.getMainLooper())`；3 个入口 `evaluateJavascript`：`WorldGlobe.initCities/updateRoutes/setAudio` | `WorldGlobeRenderer.kt:354-588` |
| 像素比 | `renderer.setPixelRatio(1)` —— 锁 1.0（1.5× ⇒ 2.25× 像素量，真机 WebView 渲染进程曾占 133% CPU） | `globe.js:55` |

### 3.4 可删除的资产（GL 版落地后）

| 文件 | 字节 | 理由 |
|---|---|---|
| `three.es5.js` | 1,011,523 | GL 版不需要 three.js |
| `three-globe.es5.js` | 2,056,088 | 同上 |
| `polyfill.es5.js` | 12,787 | 同上（Chrome 39 垫片） |
| `globe.js` | 47,577 | 业务逻辑被 Kotlin + GLSL 取代 |
| `index.html` | 3,377 | 装配页 |
| **合计** | **≈ 3.09 MB** | ⇒ APK 显著缩小 |

✅ **D2 已裁决：保留**。`app/src/globe-upstream/` 的五个文件（`three.min.js` / `three-globe.min.js` / `earth.jpg` / `cities.json` / `downlevel_libs.mjs`）**全部留存**：
① **MIT 上游件**，保留无版权风险；② 是 ES5 产物的逐字节 diff 基线（AGENTS.md 明文「永不删除、永不修改」）⇒ **该规则继续有效，不需要改 AGENTS.md**；③ WebView 版若回滚或需对照，缺了再生源就无法复现。
⇒ GL 版落地后它们**不再有运行时消费者**，但**不删除**。

⚠️ `GlobeAssetsHygieneTest`（5 例）会扫描 `assets/globe/` ⇒ 删文件后必须同步该测试。
⚠️ `app/src/globe-upstream/` 里**不得装 npm 依赖**（⛔ 装在 `assets/` 下会被 `mergeDebugAssets` 打进 APK，2026-09-06 实测一次多 23 MB）—— 依赖必须装在 `globe-upstream/` 本目录。

---

## 四、架构设计

### 4.1 包结构（新增，落在 main 源集）

```
app/src/main/java/com/nasmusic/tv/visualizer/
├── gl/                          ★ 新增：共享 GL 基础层（频谱效果共用）
│   ├── GlHost.kt                GLSurfaceView 子类：EGL 配置 / 生命周期 / 尺寸 / 释放
│   ├── GlBridge.kt              主线程 → GL 线程数据桥（双缓冲 + volatile 索引 + 版本号）
│   ├── GlCaps.kt                能力探测（ES 版本 / maxTextureSize / instancing / 深度位）
│   ├── GlProjection.kt          透视 / lookAt / 乘法（column-major，零分配变体）
│   └── GlShader.kt              program 编译链接 / uniform & attribute location 缓存
└── globe/                       ★ 新增：E41 地球
    ├── GlobeGLRenderer.kt       View 型渲染器（isViewBased = true）
    ├── GlobeGLSurfaceView.kt    GL 承载
    ├── GlobeGlScene.kt          场景 / 几何 / 绘制顺序
    ├── GlobeGlsl.kt             ESSL 常量
    ├── GlobeGeometry.kt         球体网格 / 弧线管 / 光点 / 星空的网格生成
    └── GlobeCityPoints.kt       光点属性打包
```

> ✅ **D3 已裁决：收敛到 `visualizer/gl/`，不写第三套。**
> E52 的 `HighwayGLSurfaceView` / `HighwayBridge` / `HighwayProjection` / `HighwayGlCaps` 已在覆盖层实现同类能力 ⇒ **拆成两层**：
>
> | 层 | 内容 | 归属 | 理由 |
> |---|---|---|---|
> | **通用层** | `GlHost`（GLSurfaceView 承载）/ `GlBridge`（数据桥）/ `GlCaps`（能力探测）/ `GlProjection`（透视·lookAt·乘法）/ `GlShader`（编译链接） | **main 源集**（公开，两分支一致） | 我们自己的通用基础设施，**不含上游代码** ⇒ 按 AGENTS.md 判据属公开 |
> | **上游派生层** | `HighwayGlsl.kt`（逐字搬运的 GLSL）、`HighwayProjection` 里的 `UPSTREAM_PE_FACTOR` / `UPSTREAM_FAR_SIZE` / `upstreamHorizonDistance` / `upstreamCamera` 等**上游常量** | **留私有分支** | E52 上游（Slow Roads）衍生件 ⇒ 按判据属私有 |
>
> ⇒ **落地动作**：
> ① main 新增 `visualizer/gl/` 五个文件；
> ② 私有分支的 E52 文件改为**委托**共享层（`HighwayGLSurfaceView` 继承/包装 `GlHost`，`HighwayBridge` 包装 `GlBridge`…）；
> ③ ⛔ 覆盖层会掩蔽仓库同名文件 ⇒ 改覆盖层副本后跑 `python scripts/sync_private_overlay.py` 同步，`--check` 必须零漂移；
> ④ `visualizer/gl/` 属**公开代码** ⇒ 必须**同时存在于 `main` 与 `feat/e51-private`**（`git checkout main -- visualizer/gl/` 覆盖过去，⛔ 不要 cherry-pick 手工合并）。
> ⑤ ⚠️ 共享层**不得**包含任何上游常量或上游 GLSL —— 一旦混进去就变成"公开的上游衍生件" ⇒ 泄漏。

### 4.2 渲染器（View 型）

```kotlin
class GlobeGLRenderer : VisualizerRenderer {
    override val theme: VisualizerTheme = VisualizerTheme.WORLD
    override val isViewBased: Boolean get() = true
    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {}   // ⛔ 空实现，不被调用
    override fun createView(context: Context): View = GlobeGLSurfaceView(context.applicationContext, bridge)
    override fun onViewAttached() { bridge.start(); tick() }
    override fun onViewDetached() { bridge.stop(); view?.releaseGl() }
    override fun renderFps(): Float = lastFps        // ⛔ 必须覆写（见 §4.5）
    override fun onFrame(frame: AudioFrame, ctx: RenderContext) { ... }     // 推数据给 bridge
}
```

**⛔ 必须覆写 `renderFps()`**：`VisualizerRenderer.kt:46-56` 明文 —— 舞台的 `FpsBadge` 量的是 Compose 的 Choreographer 回调率，而 View 型渲染器有**自己独立的渲染循环**，两者完全解耦 ⇒ 不覆写就会出现「显示 59 fps 但画面很卡」（E52 真机实测徽章 52~59 而 `frame stats avg=154 ms` ⇒ 真实 6.5 fps）。

**⛔ 必须覆写 `onFrame(frame, ctx)`**：`VisualizerRenderer.kt:80-90` —— `RenderContext.update(...)` 只在 Canvas 的 draw 块里被调用 ⇒ View 旁路下 `ctx.songId` **永远是旧值**。

> ⚠️ **但 main 源集当前没有这两条通道**：`VisualizerRenderer.onFrame` 与 `renderFps()` 在 `app/src/main` 内**零生产调用点**（只有私有覆盖层的 `VisualizerStage.kt:294-314, 351` 调它们）。
> ⇒ **两条路**：
> ① **照 E41 现状**：自己起 `Handler` tick（100 ms），**直接读** `spectrumRepository.frame`（绕过舞台）—— 与现有 `WorldGlobeRenderer.kt:380-384` 同款，**零改动 main 源集**；
> ② **补 main 的舞台通道**：把 `onFrame` / `renderFps()` 接到 `VisualizerStage`（需改 main 源集，⛔ 会与覆盖层的 `VisualizerStage.kt` 冲突）。
> **建议 ①**（与现状一致，改动面最小）；`renderFps()` 仍要覆写，但要自己找一个能被调用的地方 ⇒ 见 §4.5 的兜底方案。

### 4.3 数据桥（照 E52 的 `HighwayBridge` 形态）

```kotlin
class GlobeBridge {
    class Snap {
        @JvmField var songId: String? = null
        @JvmField var energy = 0f; var bass = 0f; var mid = 0f; var treble = 0f
        @JvmField var pulse = 0f; var beat = false
        @JvmField var spectrum = FloatArray(SpectrumContract.BAR_COUNT)
        @JvmField var seq = 0L; var timeMs = 0L
    }
    fun publishAudio(frame: AudioFrame)          // 主线程写
    fun publishSongId(id: String?)                 // 主线程写（每次都写，只置 volatile）
    fun consume(dst: Snap): Boolean               // GL 线程读，单读者
    fun close()
}
```

**并发契约**（照抄 `HighwayBridge.kt:6-40` 的论证）：
1. ⛔ **纯数据通道**：不持有任何 GL / EGL 句柄。
2. ⛔ **必须拷贝**：`AudioFrame` 契约明写「渲染层不得持有引用跨帧使用」，且 `SpectrumRepository` 写端会翻转 `writeIndex` ⇒ 直接透传引用有**撕裂读**风险。
3. ⛔ **不得跨帧持有引用**。
4. 实现是「**双缓冲 + 一个 volatile 索引 + 每槽版本号**」：写端写进「非当前发布槽」→ 版本号置奇（写中）→ 填数据 → 版本号置偶（稳定）→ 发布槽号 → 发布序号；读端读序号 → 读槽号 → 校验版本号是偶数 → 拷贝 → 再校验版本号没变 → 变了就丢弃本帧。
5. 发布顺序：**先槽号、后序号**（读者按「先读序号、再读槽号」看 ⇒ 不会读到半个状态）。

### 4.4 EGL 与线程

```kotlin
setEGLContextClientVersion(pickClientVersion(context))   // 3，不支持退 2
setEGLConfigChooser(8, 8, 8, 0, 24, 0)                   // ⛔ 不要 alpha / 不要 sRGB / 要 24 位深度
setRenderer(glRenderer)
renderMode = RENDERMODE_CONTINUOUSLY
isFocusable = false; isFocusableInTouchMode = false       // ⛔ 让出焦点给 Compose
override fun onTouchEvent(event: MotionEvent): Boolean = false   // ⛔ 必须 false（见 §7）
```

- **`setEGLConfigChooser` 三条硬约束**（E52 `HighwayGLSurfaceView.kt:31-41` 原文）：⛔ 不要 alpha（第 4 参 = 0，否则与下层混合出脏色）；⛔ 不要 sRGB；✅ 要 24 位深度。
  ⇒ **地球也需要深度测试**（球壳/夜面壳/弧线有前后关系）⇒ 保留 24 位深度。
- **`pickClientVersion`**：`reqGlEsVersion >= 0x30000 ? 3 : 2`，异常兜底 2。
- ⚠️ **创维机 `clientVersion = 2` 也返回 3.1** ⇒ 拿不到真 ES2 上下文 ⇒ 着色器仍写 **ESSL 1.00**（ES3 上下文也接受），但**顶点数据必须走 VBO**（ES3 核心删除了客户端顶点数组）。

### 4.5 帧率上报

E52 的 EMA 实现（`HighwayGLSurfaceView.kt:697-706`）：
```kotlin
emaFrameMs = if (emaFrameMs <= 0f) ms else emaFrameMs * 0.85f + ms * 0.15f
lastFps = 1000f / emaFrameMs
```
⛔ 不能用「120 帧窗口」算 fps：真机 ~5 fps 时 24 秒才刷新一次 ⇒ 前 24 秒 `renderFps()` 恒 0。

**⚠️ main 源集的落点问题**：`renderFps()` 在 main 内无调用点 ⇒ 需要二选一：
- ① 在 `VisualizerStage.kt` 补一行读取（改 main 源集 ⇒ 与覆盖层的 `VisualizerStage.kt` 冲突 ⇒ 需同步覆盖层副本）；
- ② 退而求其次：GL 线程把 fps 写进一个 `object GlFpsHolder`（照 `HighwayGlHolder`），UI 侧若有地方要显示再读。

**建议 ①**，但按覆盖层规则同步（`scripts/sync_private_overlay.py`）。

### 4.6 生命周期（⛔ 一个已知缺口）

`HighwayGlHolder.kt:8-23` 的 KDoc 原文：

> `GLSurfaceView` **不会自动跟随 Activity 生命周期** ⇒ 切后台时 GL 线程仍在跑、EGL 上下文仍占着显存 ⇒ 电视上表现为「退出效果后内存不降」。
> ⛔ 但 `MainActivity` **不能反向依赖 Compose 内部状态** ⇒ 用这个 `object` 做一层中转。

⚠️ **实测缺口**：这条链（`MainActivity.onPause/onResume` → `GlHolder`）在 **main 源集与私有分支 `feat/e51-private` 上都未接线**（两边 grep `GlHolder` 均零命中）。
⇒ **本方案必须自己补这条链**，否则 E41 切后台后 GL 线程不退、显存不释放。

---

## 五、渲染设计

### 5.1 绘制顺序

```
背景（纯黑） → 星空 → 地球昼面 → 大气层 → 夜面壳 → 城市光点 → 航线弧 → 月亮 → 太阳
```

⛔ **顺序硬约束**（E52 `HighwayGlScene.kt:1537-1542` 原文）：*任何一处抛异常 ⇒ 后面所有绘制都被跳过* ⇒ 绘制顺序末尾必须有兜底。

### 5.2 球体网格

- 分段：**从 90×45 提到 180×90**（32,400 三角）。
  依据：E52 真机实测 442,000 三角 @1080p = **39.40 fps** ⇒ 几何不是瓶颈 ⇒ 地球的 3 万三角毫无压力。
  ⚠️ 提细后**必须同步**夜面壳（原 144×90 → 180×90，否则夜面壳比昼面粗 ⇒ 晨昏线锯齿）。
- 顶点格式：`pos3 + normal3 + uv2`（8 float / 顶点，stride 32 B）。
- 索引：`glDrawElements` 的索引是**无符号 16 位** ⇒ 最大索引 < 65536。180×90 ⇒ 顶点数 91×181 = 16,471 ⇒ ✅ 安全。

### 5.3 地球片元着色器（合并昼面 + 夜面 + 晨昏 ⇒ 1 个 program）

把 WebView 侧的「昼面 MeshPhongMaterial + 独立夜面壳 ShaderMaterial + 大气」**合并成一个 program**，用 uniform 分支：

```glsl
// vertex
attribute vec3 aPos; attribute vec3 aNormal; attribute vec2 aUv;
uniform mat4 uViewProj; uniform mat3 uNormalMat;
varying vec3 vNrm; varying vec2 vUv;
void main() {
  vNrm = normalize(uNormalMat * aNormal);
  vUv = aUv;
  gl_Position = uViewProj * vec4(aPos, 1.0);
}
// fragment
uniform sampler2D uLitMap; uniform sampler2D uNightMap; uniform sampler2D uGlowMap;
uniform vec3 uSunDirView; uniform float uNightLevel; uniform float uContinentGlow;
varying vec3 vNrm; varying vec2 vUv;
void main() {
  float ndl = dot(normalize(vNrm), normalize(uSunDirView));
  float day = smoothstep(-0.10, 0.10, ndl);          // ⛔ 与夜面壳的 smoothstep(0.10,-0.10,ndl) 互为补集
  vec3 lit = texture2D(uLitMap, vUv).rgb;
  vec3 col = lit * (0.12 + 0.88 * max(ndl, 0.0));   // 简化 Lambert（对齐 WebView 的 Phong 观感）
  vec3 lamp = texture2D(uNightMap, vUv).rgb;
  vec3 nightCol = vec3(0.045, 0.070, 0.125)
                + lamp * vec3(1.00, 0.84, 0.55) * 2.6
                + texture2D(uGlowMap, vUv).rgb * uContinentGlow;
  gl_FragColor = vec4(mix(nightCol, col, day), 1.0);
}
```

**合并的收益**：program 数从 3 降到 1 ⇒ 直接缓解 §7 的「创维 shader 编译慢」风险。

⚠️ **⛔ 不可在 shader 里做 4 抽头偏移 UV 低通**：曾用 `b=0.12` ≈ 43° 经度模糊 ⇒ 灯光糊到海里。辉光必须**在贴图生成阶段**就模糊好（`earth_glow.jpg` 已离线做好）。

### 5.4 大气层

照 three-globe 的 `Atmosphere` 实现：
- 几何：地球网格 clone，**沿法线外推 16** ⇒ 外径 116；`side = BackSide`；`depthWrite = false`；`transparent = true`。
- 片元：`float intensity = pow(0.1 + dot(vVertexNormal, viewCameraToVertex), 3.5);`
- `hollowRadius = 100`（相机到球心距离 < 100 ⇒ discard）与 `vVertexAngularDistanceToHollowRadius < 0` ⇒ discard。

⇒ **可以合并进地球 program**（用一个 uniform 控制「是球体还是大气壳」+ 顶点着色器里按 uniform 外推半径）⇒ 不增 program 数。

### 5.5 城市光点（GL_POINTS）

- 顶点属性：`pos3 + size1 + color3`（7 float / 点）。
- ⛔ **逐点尺寸必须在顶点着色器算**：`gl_PointSize = min(uPointScale * aSize / (-mvPosition.z), 64.0)`。
  ⛔ **必须 clamp 到 64 px**（E52 教训：`:3733-3740` 原文 —— 上游公式没有 clamp，`-mvPosition.z` 约等于 0 ⇒ `gl_PointSize` 爆成几十万像素 ⇒ *一个黑方块糊满屏幕*，真机实测到的「巨大黑带」）。
  ⛔ 相机后方 / 平面上的点**直接丢弃**（`if (-mvPosition.z <= 0.001) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }`）。
- `uPointScale = viewportHeight / 2`。
- 呼吸与拍点：照 §3.3 的公式，**30 Hz 限流**（不要每帧重算 37 个点的三角函数）。

### 5.6 航线弧

WebView 侧用的是 `TubeGeometry`（管）。GL 版两条路：

| 方案 | 三角数 | 说明 |
|---|---|---|
| **A 管（照搬）** | 64 段 × 6 截面 × 2 = **768 / 条**；26 条 = **20,000** | 与 WebView 观感一致（`arcStroke` 有粗细） |
| B ribbon（面向相机的带） | 64 × 2 = **128 / 条**；26 条 = **3,300** | 粗细靠顶点色 alpha 表达，dash 仍可做 |

**建议 A**（几何预算充足，且能保住「主干粗、支线细」的分层观感）。

- 弧线生成：沿大圆 **slerp** 采样 64 点（`fe(lat,lng,alt)` 的球面公式）⇒ 半径 = `100×(1+alt)`，`alt = max(0.05, span/180×0.22)×(1+lane×0.34)`。
- 管截面：6 边形（照 `arcCircularResolution = 6`），半径 = `stroke/2`。
- dash：顶点属性带 `aRelDistance`（弧长归一化 0..1），片元：
  ```glsl
  if (vRelDistance < uDashOffset) discard;
  if (mod(vRelDistance - uDashOffset, uDashSize + uDashGap) > uDashSize) discard;
  ```
  推进：`uDashOffset += dt × 1000 / dashAnimateTime`（**按 dt 推进，不要按帧**）。

### 5.7 星空

- 两层 `GL_POINTS`：`1500`（星尘，size 2.6, alpha 0.80）+ `380`（亮星，size 4.8, alpha 1.00）。
- `STAR_RADIUS = 600`（< far 1000；远大于大气层 116 ⇒ 自转时视差正确）。
- 固定种子 LCG `s = (s×1103515245 + 12345) & 0x7fffffff`（照搬 `globe.js`）。
- 顶点属性：`pos3 + color3`（含亮度与色温）。
- 自转：`-0.00004 rad/帧` ⇒ **改为按 dt 推进**（`rotation -= 0.00004 × dt × 60`），否则帧率依赖。

### 5.8 月亮与太阳

- **月球**：球体网格（半径 16，分段 96×64）+ `moon.jpg` 同时作 `map` 与 `emissiveMap`（⛔ 缺 emissiveMap 暗面变均匀灰球）。
  - 公转：`moonPivot.rotation.x = 5.145°`，`moonMesh.position = (cos(ang)×150, 0, sin(ang)×150)`，`ang = (t/110000)×2π`。
  - ⛔ **月亮绝不挂在地球下**（地球每帧自转 ⇒ 月亮跟着转、失去公转）。
  - 本影衰减：`applyMoonEclipse()` —— `k = smoothstep(clamp((t-0.15)/0.85))`，`target = 1 - 0.94k`，最暗保留 **6%**（给 0 会消失在黑背景里）。
- **太阳**：⛔ **不放在真实 3D 位置**（会跑到相机背后）⇒ 用**屏幕空间偏移**：`position = SUN_SCREEN_OFFSET.applyQuaternion(camera.quaternion) + camera.position`，`SUN_SCREEN_OFFSET = (300, 165, -520)`；一张 128×128 径向渐变贴图（照 `makeGlowTexture`）。

### 5.9 ESSL 约束（⛔ 硬约束）

- **ESSL 1.00**：⛔ 浮点后缀 `1.0f` 是语法错误（`'f' : suffix for floats requires language version 300`）；用 `texture2D`（不是 `texture`）；用 `gl_FragColor`（不是自定义 out）。
- **不用 instancing**：⛔ 创维机没有 `GL_EXT_instanced_arrays`（E52 实测）⇒ instancing 只能靠 ES3 核心 `GLES30.glVertexAttribDivisor`。
  ⇒ 地球的弧线（26 条固定）+ 光点（37 固定）+ 星空（1880 固定）数量都是**静态小量** ⇒ **每帧重建一个动态 VBO** 即可（1880 点 × 7 float × 4 B ≈ 52 KB/帧，传输量可忽略）⇒ **完全避开 instancing 与 divisor 全局状态坑**。
- **VBO 必须用**：ES3 上下文里 `glVertexAttribPointer` 不绑 buffer 是 `GL_INVALID_OPERATION`。
- **贴图**：原样上传（`GL_RGBA` / `GL_UNSIGNED_BYTE`），⛔ 不开 sRGB framebuffer；⛔ **V 轴必须翻转**（three.js `Texture.flipY = true`）；NPOT 先缩到 2 的幂（本方案 2048×1024 / 1024×512 已是 2 的幂 ✅）。

---

## 六、音频驱动与画质分档

### 6.1 音频驱动（照 WebView 侧语义）

| 音频量 | 作用 |
|---|---|
| `energy` | 自转速度 ×(1+0.8·energy)；夜面亮度 `0.88+0.22·energy`；大陆辉光 `0.95+0.45·energy`；太阳尺寸 ×(1+0.10·energy) |
| `beat` | 光点半径 ×(1+0.5·beatEnv)，`beatEnv ×= 0.94`/帧 |
| `bass/mid/treble` | 驱动航线强度（Kotlin 侧 `WorldNetwork.pickRoute` + `BeatClassifier`） |
| `spectrum[64]` | ⛔ WebView 版**没用**（只传 5 个标量）⇒ GL 版可**保留能力但不必用**；若要用（如大陆辉光随高频起伏）走 `frame.spectrum` |

### 6.2 画质分档与**档位下放**（本方案最大的用户可感知收益）

当前 E41 = `Tier.ULTRA` ⇒ 只有 HIGH 档提供。GL 版可以下放：

| 档 | `maxParticles` | 建议 | 理由 |
|---|---|---|---|
| HIGH | 350 | 弧线 26 条 / 光点 37 / 球体 180×90 | 与 WebView 版同观感 |
| MEDIUM | 150 | 弧线 16 条 / 光点 37 / 球体 144×72 | `WorldNetwork.maxActiveFlights(150, 100f)` = **26**（算法自带的降级） |
| LOW | 0 | 弧线 8 条 / 光点 37 / 球体 90×45 | 仍然可看 ⇒ **老电视也能用** |

⇒ 若 LOW 档也提供 ⇒ 枚举改 `Tier.ADV`（ADV 的门槛是 `!needsParticleBudget || maxParticles > 0`；**若不读 `maxParticles`** ⇒ 标 `false` ⇒ 三档全可选）。
⇒ **裁决点**：GL 版**自己按 `ctx.quality` 分档**（View 型渲染器拿不到 `fx.level`，但 `ctx.quality` 有）⇒ 数量按档位表 ⇒ **`needsParticleBudget = false`** ⇒ LOW 档也可选。

> ✅ **D1 已裁决：下放。** 这是**行为变更**（E41 从「ULTRA 独占」变成「三档全可选」），理由链已闭环：
> §10.197 当初把它提到 ULTRA，是因为 WebView 在电视端黑屏 ⇒ 限定到最高档，让「能显示它的设备」与「该用最高档的设备」尽量重合；
> **GL 版没有黑屏问题 ⇒ 这条理由自动失效 ⇒ 应当下放。**
>
> **⛔ 但改动时点绑定在 GL 版上线那一刻（Phase 7），不是现在。** 当前 WebView 版**确实读** `ctx.quality.maxParticles`
> （`WorldGlobeRenderer.kt:391` → `WorldNetwork.maxActiveFlights(quality.maxParticles, 100f)`）且 TV 端黑屏
> ⇒ 提前把枚举改成 `ADV` + `needsParticleBudget = false`，会把**黑屏效果暴露给 MEDIUM/LOW 用户** ⇒ **功能级回归**。
>
> **具体改动（Phase 7 与 GL 渲染器同一个提交）**：
> ```kotlin
> // data/model/AppSettings.kt
> - WORLD(R.string.visualizer_theme_world, "世界", Tier.ULTRA, "41", needsParticleBudget = true),
> + WORLD(R.string.visualizer_theme_world, "世界", Tier.ADV,   "41", needsParticleBudget = false),
> ```
> ⚠️ 连带：`Tier.ADV` 不再受 `allowFramebuffer` 门控（那是 ULTRA 的门槛，`AppSettings.kt:649`）⇒ 天然三档全可选。
> ⚠️ **GL 渲染器不得再读 `ctx.quality.maxParticles`** —— 它必须按 `ctx.quality` 枚举（HIGH/MEDIUM/LOW）自己定数量；
> ⚠️ 调用 `WorldNetwork.maxActiveFlights(...)` 时**不要**把 `ctx.quality.maxParticles` 传进去（LOW 档它是 0 ⇒ 会算成 10 条下限），
> ⇒ **改传本渲染器自己的按档常量**（HIGH 350 / MEDIUM 150 / LOW 80）。
> ⚠️ `ParticleBudgetGateTest` 会源码扫描渲染器是否读 `maxParticles` ⇒ 枚举标注与源码必须一致（都改成不读）。

---

## 七、风险清单（含既有真机教训）

| # | 风险 | 证据 | 缓解 |
|---|---|---|---|
| R1 | **shader 编译慢**：创维 PVRShaderCompiler 编译 PBR 变体 ~5 s | E52 `HighwayGLSurfaceView.kt:83-88` | ① **合并 program**（§5.3 把 3 个合 1）② **分帧预热**：首帧只 `glClear`，program 逐帧建（E52 的 `bootStage` 模式）③ 首帧黑屏时长实测后再定策略 |
| R2 | **点精灵尺寸爆炸**糊满屏 | E52 `glsl/HighwayGlsl.kt:3733-3740`（真机「巨大黑带」） | ⛔ `gl_PointSize` 必须 `min(…, 64.0)` + 相机后方点丢弃 |
| R3 | **上下文丢失 / surface 重建**：`holder.setFixedSize` 会重建 surface ⇒ 旧 GL 对象失效而 `GLSurfaceView` **不再回调 `onSurfaceCreated`** | E52 `HighwayGlScene.kt:1139-1151` | 自建 `rebuildGlResources()`；⛔ **顺序**：先把句柄置 0 再重建（反过来 ⇒ PowerVR 在 `glDrawElementsInstanced` 里 SIGSEGV，2026-10-31 真机实测） |
| R4 | **触摸被吃掉** | E52 `:370-388` | ⛔ `onTouchEvent` 必须返回 **false**；⛔ 不要在本 View 里实现竖滑（只收得到 `ACTION_DOWN`） |
| R5 | **焦点被吃掉**（遥控方向键） | `WorldGlobeRenderer.kt:182-189` / E52 `:162-163` | `isFocusable=false` / `isFocusableInTouchMode=false` / `descendantFocusability=FOCUS_BLOCK_DESCENDENTS` |
| R6 | **GL 线程不随 Activity 生命周期** | `HighwayGlHolder.kt:8-23` | ⚠️ **当前 main 与私有分支都未接线** ⇒ 本方案必须补 `MainActivity.onPause/onResume` → `GlHolder` |
| R7 | **16 位索引上限** | E52 `:933-940` | 地球 180×90 ⇒ 顶点数 16,471 < 65536 ✅；弧线单独 VBO ✅ |
| R8 | **帧率徽章说谎** | `VisualizerRenderer.kt:46-56` | ⛔ 必须覆写 `renderFps()`，用 EMA（⛔ 不用 120 帧窗口） |
| R9 | **`ctx.songId` 在 View 旁路下永远是旧值** | `VisualizerRenderer.kt:80-90` | ⛔ 必须覆写 `onFrame(frame, ctx)`（main 侧需补舞台通道） |
| R10 | **资产耦合**：`moon.jpg` 与 E44 共用 | `MoonlitRenderer.kt:1448` | 保留该文件；只删 three 相关文件 |
| R11 | **程序数门禁**：`FxCoverageScanTest` 要求 View 型必须登记 `exempt` | `FxCoverageScanTest.kt:82`, `:333-340` | 新渲染器登记进 `exempt`（写明「View 型」） |
| R12 | **删 2D 遗留类连带删测试** | `WorldProjection` / `WorldTerminator` / `WorldMapData` 零生产调用点 | 删类前跑全仓 grep（含 test）；同步删 `WorldMapDataTest` / `WorldLogicTest` 里的对应用例 |
| R13 | **WebView 侧资产清理后 `GlobeAssetsHygieneTest` 失败** | 该测试 5 例 | 同步更新该测试的期望清单 |

---

## 八、里程碑

| Phase | 内容 | 产出 | 预估 |
|---|---|---|---|
| **0** | **共享 GL 层** `visualizer/gl/`（GlHost / GlBridge / GlCaps / GlProjection / GlShader） | 可被 E41 与频谱共用 | 5~8 天 |
| **1** | 球体 + 昼面贴图 + 自转 + 相机 + 背景 | 静态转动的地球 | 3~4 天 |
| **2** | 夜面 + 晨昏 + 大陆辉光（合并进地球 program） | 昼夜分界 | 3~4 天 |
| **3** | 大气层（合并进地球 program） | 边缘辉光 | 2~3 天 |
| **4** | 城市光点（GL_POINTS + clamp） | 37 个光点 + 呼吸 | 3~4 天 |
| **5** | 航线弧（slerp + 管 + dash） | 主干/支线/毛细三级 | 5~7 天 |
| **6** | 星空 + 月亮 + 太阳 | 完整场景 | 4~5 天 |
| **7** | 音频驱动 + 画质分档 + **档位下放（D1：枚举 `Tier.ULTRA → Tier.ADV` + `needsParticleBudget → false`，与 GL 渲染器同一提交）** + `renderFps` + 生命周期接线 | 可交付 | 4~6 天 |
| **8** | 真机验证（需所有者）+ 资产清理（删 ~3.09 MB）+ 门禁同步 | 收尾 | 3~5 天 |

**合计**: 约 **32~46 个工作日**（单人）

> ⚠️ **设备操作边界**（AGENTS.md 所有者 2026-11-07 明示）：⛔ 代理**不得自主** `adb connect` / `install` / 启动 / 截屏 / 操作电视。
> ⇒ Phase 8 的真机验证必须由**所有者本人**执行（装包、运行、截图），代理只负责出包与判定标准。

---

## 九、测试与门禁

### 9.1 既有门禁

| 门禁 | 要求 |
|---|---|
| `FxCoverageScanTest`（G7） | View 型渲染器必须登记进 `exempt`（写明「View 型：`isViewBased = true`，`draw` 是空实现且根本不被调用」） |
| `ParticleBudgetGateTest` | GL 版若**不读** `ctx.quality.maxParticles` ⇒ 枚举必须 `needsParticleBudget = false`；若读 ⇒ `true`。**必须与源码一致** |
| `VisualizerThemeStringsGateTest`（G1） | 枚举显示名 key 在两份 `strings.xml` 都有（本次不改枚举名则无需动） |
| `RendererBaseContractTest`（G12） | ⛔ **不适用**（只扫 `RendererFx` 子类；GL 渲染器直接实现 `VisualizerRenderer`） |

### 9.2 新增单测

| 测试类 | 测什么 |
|---|---|
| `GlobeGeometryTest` | 球体网格顶点数/索引上限/UV 范围；弧线 slerp 端点误差；管截面朝向 |
| `GlobeGlslParityTest` | ESSL 常量与 WebView 侧原值**逐字对账**（照 E52 的 `HighwayGlslParityTest` 做法） |
| `GlobeBridgeTest` | 双缓冲 + 版本号的撕裂检测（负向：版本号奇数时 `consume` 必须返回 false） |
| `GlobeArcTest` | `arcAltitude` 公式、`lane` 分层、dash 相位推进按 dt |
| `GlobePointSizeTest` | `gl_PointSize` clamp 到 64（负向：`-mvPosition.z → 0` 时必须为 0） |

### 9.3 真机验证（需所有者执行）

- [ ] 创维 9R54_G8S（Android 5.1.1 / Chrome 39 / PowerVR Rogue G6110 / **GL ES 3.1**）—— 基准设备
- [ ] 首帧黑屏时长（分帧预热策略的实际表现）
- [ ] 帧率（`renderFps()` 上报值 vs `dumpsys gfxinfo`）
- [ ] 昼夜分界位置（对照 `globe.js` 的 `smoothstep(0.10,-0.10,ndl)`）
- [ ] 航线弧观感（管 vs WebView 的 TubeGeometry）
- [ ] 切后台 → 回来：GL 线程是否重建、显存是否释放（R6）
- [ ] 遥控方向键不被吃掉（R5）、滑动切主题可用（R4）

---

## 十、与现有文档的关系

| 文档 | 处置 |
|---|---|
| `docs/technical-overview.md` §10.215 / §10.216 | 保留（历史纪实）。落地时**追加**新条目，不改历史段 |
| `docs/archive/e41-tv-blackscreen-fix-plan.md` | 归档（历史）。新方案不依赖它的结论 |
| `docs/archive/visualizer-texture-upgrade-plan.md` D6 | ⚠️ 该裁决封的是「改回 Compose Canvas」与「推广 WebView/WebView」⇒ **不封原生 GL** ⇒ 本方案不与其冲突，但落地时应请所有者确认 |
| `docs/stellar-spectrum-plan.md` | 同为 GL 路线 ⇒ **共用 §4.1 的 `visualizer/gl/` 共享层** |

---

## 十一、参考资料

- `app/src/main/java/com/nasmusic/tv/visualizer/renderers/WorldGlobeRenderer.kt` —— 现有 WebView 渲染器（参数与资产来源）
- `app/src/main/assets/globe/globe.js` —— 现有业务逻辑（数值真源）
- `app/src/main/java/com/nasmusic/tv/visualizer/renderers/WorldCities.kt` / `WorldNetwork.kt` —— **保留**的数据层
- `app/src/main/java/com/nasmusic/tv/visualizer/renderers/WorldProjection.kt` / `WorldTerminator.kt` / `WorldMapData.kt` —— 2D 遗留（可删）
- `app/src/e51-overlay/ovl/app/src/main/java/com/nasmusic/tv/visualizer/highway/` —— **E52 原生 GL 基础设施（主要参考件）**
- `app/src/main/java/com/nasmusic/tv/visualizer/renderers/RendererFx.kt` / `VisualizerRenderer.kt` —— 渲染器接口与基类
- `app/src/main/java/com/nasmusic/tv/visualizer/photo/PhotoTransitionClock.kt` —— 相位推进的禁忌（`nowMs × 系数`）
- `docs/archive/e52-highway-opengl-plan.md` —— 原生 GL 路线裁决与真机实测数据
- `docs/technical-overview.md` §10.197 —— E41 当初的路线取舍（WorldWindKotlin / 手写 GL 的成本判断）
