# Tendrils 移植开发方案

> 版本:V1.2(2026-11-09 归档;Phase 0 实测在 V1.1)
> 状态:📁 **已归档(2026-11-09)**:所有者选择**方案 A,放弃 E55 藤蔓**
> (原生 GLES GPGPU 路线在创维 9R54_G8S 上被实测证伪;CPU 物理路线未采用;
> 原文一字未删,仅作历史参考与决策依据)
> **⚠️ 历史记录**:原「原生 GLES GPGPU」路线已被证伪
> (浮点 FBO 渲染目标全灭,证据见附录 A)
> 主题编号建议:**E55**(ordinalLabel = `"55"`,现最大已用 `"54"`)
> ⭐ **2026-11-10 追记（编号已定）**:**E55 现归「街机合集」**(`VisualizerTheme.ARCADE_GAMES`,
> 2026-11-10 所有者按代码现实定案)⇒ 本文的「藤蔓」**不是 E55**,上文编号建议作废。
> 主题键建议:`TENDRIL`;显示名建议:**藤蔓 / Tendrils**
> 日期:2026-11-08(架构选型修订;Phase 0 实测)
> 参考源码:本地已下载 `D:\hxzhang\MyGithubSoftware\NasAudio\tendrils-master`(MIT 许可,upstream `keeffEoghan/tendrils`)

---

## 一、效果定位

Tendrils 上游是一次「涌现物理」实验,源自一个互动音乐视频。它不是传统的
频谱(柱状/波形/环形/点阵),而是 **GPGPU 粒子流体**:数万粒子在噪声游荡力与
自身留下的速度流场反馈中形成「藤蔓 / 触须 / 辫状」的有机形态。音频不是装饰,
而是驱动粒子出生、受力分布、流场参数与形态切换的直接信号源。

在 NASMusicTV 的语境里,它属于一类以粒子/有机形态反应音频的主题(类似
StarrySky / Molecule / SnowCedar),视觉上是流体化的频谱诠释。

定位差异(区别于现有主题):
- 现有 WebGL 主题(E41 地球、E54 生态地球)是几何地球/世界视角;Tendrils 是
  **平面屏幕自适应的粒子流场**,更贴近「音乐即画面」的沉浸态。
- 现有粒子主题(Particle / Molecule / Snow)是 CPU/Canvas 模拟;Tendrils 是
  **GPGPU 全 GPU 步进**,规模与流畅度上限更高。

## 二、上游源码机制分析

基于本地源码(`tendrils-master/src/`)逐层梳理。这部分与架构选型无关,是移植
后必须复现的数学与数据流。

### 2.1 核心数据结构:粒子状态纹理

`src/particles.js` + `src/index.js`。粒子不存 CPU 数组,而是存进一张
`Float FBO texture`,每个纹素 = 一个粒子,RGBA 四通道编码 `(x, y, vx, vy)`。
默认分辨率 `rootNum = 2^9 = 512`,即 **262,144 个粒子**(`index.js:31`)。
多缓冲 ping-pong:上一帧状态纹理与本帧状态纹理交替绑定,逻辑 Pass 读旧写新。

### 2.2 每帧三段 Pass(`src/index.js` `Tendrils.step()` / `draw()`)

1. **Logic Pass**(`src/logic.frag`):读入 `(pos, vel)`,累加三种力并积分:
   - **游荡力 wanderForce**:3D Simplex noise(`glsl-noise/simplex/3d`),
     噪声场随粒子位置与时间推进(`src/logic.frag:62-68`)。
   - **流场力 flowForce**:读上一帧粒子画出的速度流场纹理
     `flowAtScreenPos(...)`(`src/flow/*.glsl`),形成「前人脚印被后人跟随」的自激反馈。
   - **阻尼 + 目标吸引力**:`damping`、`target`(让粒子向(optionally 指定的点/图)收拢)。
   - 速度按 `speedLimit` 归一化钳制。
   - 用 Verlet/Euler 之一积分(`src/physics/{verlet,euler}/index.glsl`)。

2. **Flow Pass**(`src/flow/`):把粒子以 `gl.LINES` 画进 `flow` FBO,
   带半透明流色(`flowColor`),叠一个衰减。**注意顺序**:必须在逻辑 Pass
   之后做,避免粒子参考到自己刚画的流。

3. **Screen Pass**(`src/render/index.vert`):以 `gl.LINES` 把粒子画到屏幕,
   颜色由三部分合成:`mappedColor`(colorMap 纹理选列)、`flowAlignedColor`
  (速度方向对 RGB 三轴投影调色;按速度越快越亮)、`baseColor`。
   带 `vignette` 边缘渐暗 + `preAlpha` 预乘 alpha。

### 2.3 渲染管线依赖(**不是** three.js)

Tendrils 用 raw WebGL1(`gl-shader`/`gl-fbo`/`gl-geometry`/`glslify`),
**不用 three.js**。移植时需要把这些一起带上或手拓成原生 shader——
但**不管是 WebView 还是原生 GLES,这些库都要被绕过/手拓,因为它们都是
浏览器环境的封装**。这是两种方案的共同需求,不是 WebView 路线独有的成本。

### 2.4 音频驱动方式(`src/demo.main.js`)

上游音频输入走 Web Audio `AnalyserNode`,但对移植有意义的是其**驱动模式**:
- 每个 spawn 触发器挂在不同频段特征上(low-end velocity / high-end accel /
  sudden peak / mid force),用递增阶数数据
  (order 0 = 频率,order 1 = 速度,order 2 = 加速度,order 3 = jerk)做阈值判断
  (`demo.main.js:652-770`)。
- spawn 形态有 `ball / geometry / pixels / flow-line`(`src/spawn/`)。
- spawn 间可参数化(`defaults()` 里的 `damping / forceWeight / flowWeight /
  noiseWeight / noiseScale / target / ...`),音频能调这些参数。

NASMusicTV 的 `AudioFrame`(64 bars + beat + bass/mid/treble/energy)
可以直接等价映射到这两条通道:**频段 → spawn 阈值**与**频段 → 全局 uniform 调参**。

## 三、架构选型:原生 GLES vs WebView+WebGL

> 初版方案走 WebView+WebGL,后经架构评审改为**原生 GLES 为主路线**。
> 本节记录对比与结论。

### 3.1 对比表

| 维度 | 原生 GLSurfaceView (GLES2/3) ⭐ | WebView+WebGL |
|---|---|---|
| GPGPU 模式可移植性 | ✅ **1:1**。FBO 浮点纹理 ping-pong + 3 段 shader 直接翻 GLSL ES 1.00 | ✅ 1:1,但需 glslify 打包 |
| 每帧音频喂入 | ✅ 直接 `glUniform1fv` 从零分配 `AudioFrame` — **零 JSON / 零 stringify / 零 IPC** | ⚠️ 50Hz × 64 float JSON stringify + evaluateJavascript 往返 |
| ES 版本风险 | ✅ **不存在**。GLSL ES 1.00 原生编译,API 22+ 全覆盖 | ❌ 核心风险。ES2015+ 源码在 5.1 创维 WebView 崩 |
| 资产打包风险 | ✅ **无**(无 glslify / 无 esbuild / 无 asset 两处规则) | ⚠️ 需处理 glslify 内联转换 + 两处资产分布 |
| 效果保真度 | ✅ 源码 1:1 移植,shader 数学(simplex noise + flow 自激)完全保留 | ✅ 同 |
| 内存 / 启动 | ✅ 无 WebView 初始化 + V8 开销 | ⚠️ WebView 冷启动 + V8 内存 |
| 需要新写的代码 | 中等:~400 行 GLSL 手拓 + GLSurfaceView 渲染器 | 较大:esbuild bundle + JS 桥 + asset 双份 |
| 硬件兼容性 | ⚠️ 需查 `OES_texture_float` 扩展(电视 GPU 通常有) | ⚠️ 同样依赖,WebGL 的 float 扩展策略更宽松一点 |

### 3.2 结论:原生 GLES 是主路线

**理由:**
1. **渲染器契约已经预见此路**:`VisualizerRenderer.kt:49-56` 注释明确写着
   `GLSurfaceView` / `RENDERMODE_CONTINUOUSLY` 有独立渲染循环,契约里的
   `renderFps()` 钩子和 `onFrame(frame, ctx)` 推送就是为这条路预留的
   (E52 实测教训已沉淀在 AGENTS.md)。**集成面不改舞台、不改接口,改动量
   与 WebView 路线相当,但链路更短更稳。**
2. **每帧音频喂入是 GPGPU 工作负载的生命线**,WebView 的 10~20Hz
   tick + JSON IPC 是整个链路里最脆弱的一环,原生路线把它整个去掉。
3. **ES 版本风险归零**:GLSL ES 1.00 在 API 22+ 全生命周期存在,
   不存在「源码编译不过 / 老内核崩」这类不可预测性。

**附带:** WebView 路线保留作为「兜底」——仅当原生 GLES 因硬件不支持
`OES_texture_float` 在真机被证伪时才启用。

## 四、可行性判断

| 维度 | 结论 | 依据 / 风险 |
|---|---|---|
| 技术可行性 | ✅ 成立 | GPGPU FBO ping-pong 是 GLSL ES 1.00 标准能力;契约已支持 GLSurfaceView 路线 |
| 音频输入可行性 | ✅ 成立 | `SpectrumRepository.frame` 零分配单例,每帧直接 `glUniform1fv` 喂 shader |
| 许可证 | ✅ MIT | 需在 build 输入目录保留署名 |
| 性能 | ⚠️ 需真机验证 | 262k 粒子是电视 GPU 实打实负载;Phase 0 做帧率曲线降档 |
| ES/资产打包风险 | ✅ **已消除** | 原生 GLES 路线下,上游 ES2015+ 源码与 glslify 内联全部绕开,手拓 GLSL ES 1.00 即 |
| 硬件兼容 | ⚠️ 需验证 | `OES_texture_float` 扩展在创维/小米电视 GPU 上是否可用 |

**总体判断(初版,已被推翻):可移植。** 主路线已锁定原生 GLES,主要遗留风险只剩
`OES_texture_float` 硬件兼容 + 默认粒子量性能。

> ⚠️ **修正(2026-11-08 Phase 0 实测,见附录 A)**:该判断的实物前提——浮点 FBO
> 渲染目标可用——**在创维 9R54_G8S 上证伪**:`OES_texture_float` 报告 true 且浮点
> 纹理可创建,但**浮点渲染目标完全不可用**(float/half 全部 `GL_FRAMEBUFFER_UNSUPPORTED)。
> **原生 GLES GPGPU 路线(§三)在电视目标设备上不可行**,路线取舍待所有者决策。

## 五、分阶段开发方案

### Phase 0:独立原生 GLES 原型(不碰 NASMusicTV 工程树)

**目标:** 证明三个前置条件 —— `OES_texture_float` 可用、shader 数学正确、
帧率可接受。

- 在 `tendrils-master` 目录或 `output/` 出一份**独立 Android 工程**(最小
  `GLSurfaceView` + `EGLSurfaceView` 样例 + 3 个 shader),跑在小米手机 +
  创维电视(由所有者)。
- 测试点:
  1. 扩展查询:`GLES20.glGetExtension("OES_texture_float")` 是否非 null;
     `GLES20.glGetExtension("OES_texture_float_linear")` 是否非 null
     (后者决定纹理采样插值精度,audio 不敏感问题不大)。
  2. shader 编译通过 + 三段 Pass 输出不为全黑 / 全爆。
  3. `rootNum = 16 / 64 / 128 / 256 / 512` 帧率曲线 → 定位默认降档值。
     - 256×256 = 65,536 粒子 / 512×512 = 262,144 粒子
     - **门禁目标:** 256 下稳定 60fps,512 下 ≥ 30fps。
  4. `OES_texture_float` 不存在时的回退路径:切换到 `GLES3`(若设备
     API ≥ 26)/ RGBA8 降采样 / 转换到 HALF 浮点(`GL_HALF_FLOAT_OES`)。
- 产出:`output/tendrils_phase0_*/*` + 一份帧率/兼容记录。**不提交 NASMusicTV。**

### Phase 1:最小壳 GLSurfaceView 渲染器

**目标:** `TendrilRenderer` 渲染粒子流场,暂不接音频。

- 资产:
  - `app/src/main/res/raw/tendrils/*.glsl` — 手拓的三段 shader + simplex noise
    (作为 raw 资源,或 String.kt 常量)
  - **上游源码副本**放 `app/src/tendrils-upstream/`(对齐 `globe-upstream`/
    `eco-upstream` 模式:模块内、`main` 源集之外,Gradle 不打包),含 MIT
    license + 作者署名。
  - ⚖️ **资产两处规则**(跟 E41/E54 同款):`raw/` 存运行时,`upstream/`
    存 build 输入 + 上游原件;护栏 `TendrilAssetsHygieneTest`。
- `TendrilRenderer.kt`(`renderers/TendrilRenderer.kt`):
  - `isViewBased = true`,`createView(context)` 返回一个**自管
    `GLSurfaceView`**(自定义 subclass,内部调 `setEGLContextClientVersion(2)`
    + `setRenderer(this)` + `setRenderMode(CONTINUOUSLY)`。
  - `onViewAttached/Detached` 启停渲染循环。
  - `renderFps()` 必须覆写,上报**真实渲染循环帧率**(契约 `VisualizerRenderer.kt:49-56`
    要求 View 型渲染器这样做 —— **E52 教训;否则徽章显示 59fps 而真机 6.5fps**)。
- 门禁:`VisualizerThemeTest` 计数 28→29(§六).

### Phase 2:音频桥接(原生,无 IPC)

**目标:** 把 SpectrumRepository 的频谱数据喂进 shader。

- `TendrilRenderer` 在 `GLSurfaceView.Renderer.onDrawFrame(gl)` 里:
  - 直接读 `spectrumRepository.frame`(零分配单例,`SpectrumRepository.kt:40`
    T6 双缓冲)。
  - `frame.spectrum`(64 bars)→ `glUniform1fv(bandsProg, 64f, spectrumBuf)`,
    **不经过任何 JS / JSON / stringify**。
  - `frame.beat` / `frame.energy` / `frame.bass` 等标量 → `glUniform1f`。
  - 降档:若 Phase 0 证明 CPU 主线程扛不住 64×f32 上传,改 `USE_BANDS_UNIFORM`
    flag 走「每 tick 抽 12 段」——但**优先选前者**,因为 AudioFrame 本就零分配。
- **门禁:** `TendrilsAudioBridgeTest`(Robolectric):mock `SpectrumRepository.frame`,
  断言 `glUniform*` 调用参数;断言 shader uniform 数与 bands 数组大小匹配。

### Phase 3:主题注册 + UI 集成

- 注册:`AppSettings.kt` 枚举 + `VisualizerRendererFactory.when` + 两份
  strings + `docs/visualizer-effects-list.md` 增一行。
- UI:`VisualizerStage` 默认路径已支持 View-based 渲染器(含 `renderFps()`
  徽章直读 `cur.renderFps()`),无需改动。
- 性能档位:确认 `ctx.quality.maxParticles` 与 `needsParticleBudget` 同步(§六
  ParticleBudgetGateTest)。

### Phase 4:打磨、门禁、文档

- 调色:默认 colorMap/调色板给出 1~2 套预设(暖金/冷青),可随专辑封面取色
  (复用 `CoverPaletteProvider`,参考 `TripodFogRenderer` 的做法)。
- 节拍检测:复用 NASMusicTV 现有 `BeatDetector`(`frame.beat/pulse`),
  与上游自行阈值相比**保持全局节奏一致**。
- 门禁:新增至少 2 个 Unit Test + 1 个 Robolectric 测试(§六)。
- 文档:`§10.N` 落地纪实 + `docs/visualizer-effects-list.md` 更新 +
  CHANGELOG 一行。
- 真机取证:小米手机 screencap + logcat;电视端由所有者确认。

## 六、门禁 / 测试清单

1. **`VisualizerThemeTest.kt`**(`app/src/test/.../data/model/`):
   - `entries.size == 28` → **29**
   - `selectable(true).size == 26 + ...` → +1
   - `off.size == 25 + ...` → +1
   - `ordinalLabel` distinct → 29(新增 `"55"` 不与现有 28 冲突)
   - `displayName` distinct → 29
2. **`VisualizerThemeStringsGateTest.kt`:** `visualizer_theme_tendril` 键在
   `values/strings.xml` + `values-en/strings.xml` 都要存在。
3. **`FxCoverageScanTest.kt`:** `TendrilRenderer : VisualizerRenderer`
   归入 `exempt`(跟 `EcoGlobeRenderer` 同款 view-based)。
4. **`TendrilAssetsHygieneTest`**(新建):校验 `raw/` 与 `upstream/` 资产两处
   分布 + GLSL 源文件边界 + MIT license 文件存在。
5. **`TendrilsAudioBridgeTest`**(新建,Robolectric):AudioFrame → shader
   uniform 参数正确性;`pageReady` 阶段不触发 `glUniform*`。
6. **`TendrilsShaderGateTest`**(新建):用纯 Kotlin 模拟 GLSL 编译失败路径
  (marker agent 可通过反射注入一个非法 shader 触发 compile 失败,断言
   渲染器 degrade 到 fallback 而非崩)。
7. **ParticleBudgetGateTest:** 默认粒子量 + `ctx.quality.maxParticles`
   阈值 → `needsParticleBudget` 第 4 参必须 `= true`;否则门禁失败。

## 七、风险与对策

| ID | 风险 | 对策 |
|---|---|---|
| R1 | **实证命中(2026-11-08,见附录 A)**:创维 9R54_G8S 浮点渲染目标不可用——驱动报告 `EXT_color_buffer_float=true`,但 float FBO 隐式 + half 纹理创建全灭;`OES_texture_float` 可创建纹理 ≠ 可作为渲染目标 | **回退路径全部实测无效**:GLES3 上下文可用但浮点 FBO 仍不可用 / RGBA8 仅 8-bit 精度对物理累加不可靠 / HALF 创建失败。**路线决策待所有者(附录 A.5)** |
| R2 | 262k 粒子在电视 GPU 性能不足 | Phase 0 帧率曲线,默认 `rootNum` 砍到 256~320;支持 `preferLowLatency` 档降粒子 |
| R3 | GLSL ES 1.00 精度限制(老 Mali/Adreno fp32 模拟) | shader 里避免 `1/x` 类反复除法;用 reciprocal 近似;Phase 0 真机过光验证异常 |
| R4 | (已消除)ES 版本 / glslify 打包 / asset 两处规则 | 原生 GLES 路线下全部不存在 |
| R5 | GLSurfaceView 独立渲染循环与 Compose Choreographer 脱钩 | **协议已有 `renderFps()` 钩子强制覆写**(AGENTS.md E52 教训);`TendrilRenderer` 必须实现,否则徽章与实帧分裂 |
| R6 | 与隐藏分支覆盖层冲突 | Tendrils 文件命名避开 `VisualizerRendererFactory.kt`/`RenderContext.kt`/`VisualizerStage.kt` 等被掩蔽文件名 |

## 八、开放问题(待所有者决策)

| ID | 问题 | 建议 |
|---|---|---|
| Q1 | Tier 定位(BASIC / ADV / ULTRA)? | 默认 **ADV**:GPGPU 是新渲染范式,bundle 体积比纯 Canvas 主题大;若真机表现好可升 ULTRA |
| Q2 | 目标 API 抬升? | **原生 GLES 路线下**:`OES_texture_float` 在 API 22+ 普遍可用,无抬升需求(WebView 路线才有此问题) |
| Q3 | 默认粒子量与调色预设? | Phase 0 帧率曲线后由所有者定默认 `rootNum` 与色板 |
| Q4 | GLSL 放 res/raw 还是 Kotlin String 常量? | 建议 **res/raw/** 更贴近 `globe.js` 资产习惯;Kotlin String 注入更省一个 asset 拷贝 |
| Q5 | 复用 `BeatDetector` 还是上游自己的阈值? | 复用 NASMusicTV 现有 `BeatDetector`(已调参),保持全局节奏一致 |
| Q6 | **已裁决(2026-11-08 实测证伪)**:GLES3 路径在创维上不可行(ES3 上下文 + `#version 300 es` 编译链接正常,但浮点 FBO 渲染目标仍不可用,见附录 A) | 该问题已解决:GLES3 fallback 作为 GPGPU 路线的救援路径被排除;E55 路线整体待所有者对 A/B/C 方案决策(见附录 A.5) |

## 九、文件清单(原生 GLES 主线)

**新建(资产)**
- `app/src/main/res/raw/tendrils/logic.glsl`
- `app/src/main/res/raw/tendrils/render.glsl`(含 vert+frag)
- `app/src/main/res/raw/tendrils/flow.glsl`(流程 pass + 流场应用)
- `app/src/main/res/raw/tendrils/noise3d.glsl`(or inline in logic.glsl)

**新建(build 输入,模块内 Gradle 不打包)**
- `app/src/tendrils-upstream/` — upstream 源码副本 + MIT license + GLSL 原件
- `scripts/build_tendrils_glsl.py` — 将 upstream gl-slify 内联语法转换为 res/raw/*.glsl 的脚本(若 shader 手拓直接写 raw/ 则可省)

**新建(Kotlin)**
- `app/src/main/java/com/nasmusic/tv/visualizer/renderers/TendrilRenderer.kt`
  + 内部 `TendrilGLView : GLSurfaceView` + shader/texture/FBO helper

**新建(测试)**
- `app/src/test/java/com/nasmusic/tv/visualizer/renderers/TendrilAssetsHygieneTest.kt`
- `app/src/test/java/com/nasmusic/tv/visualizer/renderers/TendrilsAudioBridgeTest.kt`
- `app/src/test/java/com/nasmusmusic/tv/visualizer/renderers/TendrilsShaderGateTest.kt`

**修改**
- `app/src/main/java/com/nasmusic/tv/data/model/AppSettings.kt` — `VisualizerTheme` 加 `TENDRIL` 条目
- `app/src/main/java/com/nasmusic/tv/visualizer/VisualizerRendererFactory.kt` — `createEnabled().when` 加分支
- `app/src/main/res/values/strings.xml` — `visualizer_theme_tendril`
- `app/src/main/res/values-en/strings.xml` — `visualizer_theme_tendril`
- `app/src/test/java/com/nasmusic/tv/data/model/VisualizerThemeTest.kt` — 计数 28→29
- `app/src/test/java/com/nasmusic/tv/visualizer/fx/FxCoverageScanTest.kt` — `exempt` 加 `TendrilRenderer`
- `docs/visualizer-effects-list.md` — 增一行
- `CHANGELOG.md` / `docs/technical-overview.md` §10 — 落地后记

**私有 / 不提交**
- Phase 0 原型放 `D:\hxzhang\MyGithubSoftware\NasAudio\tendrils-master` 或 NASMusicTV 的 `output/`(不提交)
- 上游拷贝放 `app/src/tendrils-upstream/`(MIT 已授权,属公开项)

## 十、验收标准

1. ~~Phase 0:小米/创维电视原机驻留;`OES_texture_float` 探测通过,或 fallback 路径验证。~~ **Phase 0 已完成(2026-11-08)**:创维 9R54_G8S 实机;`OES_texture_float` 可创建纹理,但**浮点 FBO 渲染目标全部不可用**(float/half 全灭);GLES3 + `#version 300 es` 路径亦不可用。**GPGPU 路线被证伪**(矩阵与扩展位证据见附录 A)。
2. Phase 1+2:音乐播放时粒子随频谱流动、鼓点触发形态切换。
3. 门禁:`test` 单测全绿;`lint` 零新 error;`assembleRelease` 含 E55。
4. 文档:`docs/visualizer-effects-list.md` 与 `docs/technical-overview.md` §10 同步更新。
5. 真机:小米手机 screencap + logcat;电视端由所有者本人验收。

---

*本方案遵循 AGENTS.md 的 release 规则:patch 提交流程、changelog、§10 落地纪实。*

---

## 附录 A:Phase 0 实测证据(2026-11-08,创维 9R54_G8S)

> 本附录全部数据来自独立探针工程 `output/tendrils-probe/`(不提交 NASMusicTV 主树),
> 最终证据 logcat:`logs_temp/tendrils_probe_es3_r3.log`(tag `TendrilsProbe`)。
> 探针已 `force-stop` 清理,电视已恢复原状。

### A.1 设备与驱动

| 项 | 值 |
|---|---|
| 设备 | 创维 9R54_G8S(`192.168.0.114:5555`) |
| GPU | PowerVR Rogue G6110 |
| 驱动 | OpenGL ES 3.1 build 1.4@3632227 |
| 开发商 | Imagination Technologies |
| 年代判断 | 2016 年代驱动,无 compute shader 扩展 |

### A.2 扩展支持位(已知问题:存在误报)

| 扩展 | 位报告 | 实测推断 |
|---|---|---|
| `EXT_color_buffer_float` | **true** | **误报**——float 渲染目标实测不可用(见 A.3) |
| `EXT_color_buffer_half_float` | false | 与矩阵一致,half 渲染目标不可用 |
| `OES_texture_float` | true | 浮点**纹理**可创建,但不能作 FBO 渲染目标 |
| `OES_texture_half_float` | true | half **纹理**可创建(OES 路径),但 FBO 仍不可用 |
| `EXT_float_copy` | false | —(影响 PBO 拷贝路径) |

### A.3 FBO 渲染目标完整性矩阵(6 变体,两轮验证)

| 变体 | 内部格式 | 纹理类型 | 纹理创建 | FBO 状态 | 错误码 |
|---|---|---|---|---|---|
| Byte(RGBA8) | `GL_RGBA` | `GL_UNSIGNED_BYTE` | ok | **complete** ✅(控制组) | — |
| FloatA | `GL_RGBA` | `GL_FLOAT`(隐式) | ok | **incomplete** | `0x8cd6` = GL_FRAMEBUFFER_UNSUPPORTED |
| FloatB | `GL_RGBA32F` | `GL_FLOAT`(显式格式) | **失败** | — | `0x502` = GL_INVALID_ENUM |
| FloatC | `GL_RGB32F` | `GL_FLOAT`(显式格式) | **失败** | — | `0x501` = GL_INVALID_VALUE |
| HalfA | `GL_RGBA16F` | `GL_HALF_FLOAT` | **失败** | — | `0x502` = GL_INVALID_ENUM |
| HalfB | `GL_RGBA` | `GL_HALF_FLOAT`(隐式) | **失败** | — | `0x502` = GL_INVALID_ENUM |

**结论:** 本机唯一可用作 FBO 渲染目标的是 **RGBA8**(8-bit 定点)。
8-bit 对粒子物理积分(Verlet + 速度向量累积)精度不足——数十步之后量化噪声即污染速度场,
这正是上游改用 GPGPU 浮点纹理的动机。**GPGPU 全 GPU 步进路线在本机不可行。**

### A.4 渲染管线健康度(非 GPGPU 能力)

- ES 3.1 上下文 + `#version 300 es` shader:编译/链接正常,最终 probe 返回 program id=3 可用。
- Bench(全屏四边形 + 基础 shader,非 GPGPU):首 30 帧 **39.6 FPS**,稳态 42.7 FPS(帧 2520/2550 采样)。
- **渲染管线本身健康**;缺的恰好是 GPGPU 所需的唯一能力(浮点渲染目标)。

### A.5 路线选项(待所有者决策)

| 方案 | 描述 | 代价 |
|---|---|---|
| **A** | 放弃 E55 藤蔓 | 零开发成本;≈262k 粒子的有机流体形态在原生 GLES 路线失守 |
| **B** | **CPU 物理 + GL 点/线渲染**:粒子状态存 CPU 侧 Kotlin/DoubleArray(用 `Double` 或定点分块保存),每帧把 (x,y,vx,vy) 上传纹理经 GL 点精灵/线段渲染,保留"藤蔓/触须"视觉形态;物理步进减到能扛住的档位(如数万像素 × 每帧部分积分 + 跳过帧)。视觉形态近似但不再是 GPGPU 全 GPU 步进。 | 中高:CPU 物理需重写上游的 Verlet/wanderForce(simplex noise 计算是重头,可每颗粒子每 N 帧分批算或降采样);262k 粒子在电视 CPU(G6110 搭配的低端 SoC)上大概率扛不住满量,需显著减量(如 20k~50k)与画质取舍 |
| **C** | 换设备重测:在更新的设备(如小米手机或其它电视)上重跑 Phase 0 矩阵 | 低成本验证;但目标交付设备仍是这台创维,若其它设备支持也不改变"目标电视跑不了 GPGPU"的事实 |

### A.6 探针工程说明(复测指引)

- 位置:`output/tendrils-probe/`(独立 Gradle 工程,`com.probe.tendrils`)
- 构建(Windows,须 `--no-daemon`;**不要**加 `-Pkotlin.compiler.execution.strategy=in-process`,该参数会破坏 gradle 任务名解析):
  `./gradlew.bat assembleDebug --no-daemon`
- 启动:`adb shell am start -n com.probe.tendrils/.MainActivity`
- 日志:`adb logcat -s TendrilsProbe:V`(或 `logcat -d` 存盘后过滤 `TendrilsProbe`;
  注意系统广播会灌满 ring buffer,抓启动行前先 `logcat -c` 且等待 ≲45s)

## V1.1 (2026-11-08)

- **变更类型**:修改(Phase 0 实测结果写入)
- **变更内容**:新增附录 A(Phase 0 完整实测证据:渲染器/驱动、扩展位 + 误报、6 变体 FBO 矩阵、Bench 数据、A/B/C 路线选项、复测指引);§7 R1 行改为"实证命中"结论;§8 Q6 改为"已裁决(证伪)";§10 验收标准第 1 条改记 Phase 0 完成状态与路线证伪结论;文档头补 V1.1 版本说明。

## V1.2 (2026-11-09)

- **变更类型**:归档(文档状态变更)
- **变更内容**:所有者选择方案 A,放弃 E55 藤蔓。本方案经普通 `mv` 移入 `docs/archive/`(**全程 untracked、从未入库** ⇒ 无历史可保留;按第四轮/第七轮先例,首次提交即以归档路径入库)。文档头状态改为"已归档"。文外引用 3 处(`docs/archive/e55-cityscape-port-plan.md` ×1、`docs/archive/README.md` ×2)已同步改写为归档路径。
- **编号状态**:E55 在 2026-11-08(cityscape 放弃)已释放;本轮 E55 藤蔓亦放弃 ⇒ **E55 现无归属**。后续新主题若启用"55"编号,需所有者另行确认主题与效果。
- **可复用资产**:Phase 0 探针工程 `output/tendrils-probe/` 与证据 logcat `logs_temp/tendrils_probe_es3_r3.log`(均 gitignored,不入库);上游 MIT 源码 `D:\hxzhang\MyGithubSoftware\NasAudio\tendrils-master` 可用于同类 GPGPU 移植调研。


