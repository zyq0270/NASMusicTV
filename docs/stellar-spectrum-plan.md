# 频谱效果开发文档 —— 星际四季频谱（OpenGL ES 版）

> **版本**: v2.0（2026-11-08，**渲染栈改为原生 OpenGL ES**，与 E41 地球 GL 重写共用 `visualizer/gl/` 共享层）
> **状态**: 开发方案评审
> **关联**:
> - `docs/globe-present.html`（视觉参考源）
> - `docs/e41-globe-opengl-plan.md`（**同批方案**：E41 地球原生 GL 重写，含共享层设计）
> - `docs/technical-overview.md` §10

---

## 〇、决策记录（2026-11-08 所有者裁决）

与 E41 GL 方案同批评审的三个待裁决点，**所有者 2026-11-08 裁决「按建议处理」** ⇒ 全部转为已决：

| # | 决策点 | 裁决 | 本方案的影响 |
|---|---|---|---|
| **D1** | E41 档位下放 | ✅ 下放 | 与本方案**无直接耦合**（E41 自己的枚举改动）。但**同一条规则适用于本效果**：本效果不读 `ctx.quality.maxParticles` ⇒ `needsParticleBudget = false` ⇒ 三档全可选（§5.2 / §10.2）—— 这条在 v1.2 就已定，v2.0 沿用 |
| **D2** | `globe-upstream/` 的 three.js / three-globe ES6 原版 | ✅ **保留** | 本方案**不碰** `assets/globe/` ⇒ 无影响（E41 侧处置见 `docs/e41-globe-opengl-plan.md` §3.4） |
| **D3** | GL 基础层收敛 | ✅ **收敛到 `visualizer/gl/`** | ⇒ 本方案的 `GlHost` / `GlBridge` / `GlCaps` / `GlProjection` / `GlShader` **不另起炉灶**，直接落在共享层；E52 的同类件改为委托共享层。拆分规则见 `docs/e41-globe-opengl-plan.md` §4.1 |

⚠️ **⛔ 共享层纪律**：通用层**不得**包含任何上游常量或上游 GLSL —— 一旦混进去就变成「公开的上游衍生件」⇒ 泄漏（origin 是 public 仓库）。

---

## 一、设计目标

### 1.1 定位

创建一个**独立的音乐频谱可视化效果**，在 NowPlaying 界面中作为播放背景呈现。视觉语言源自 `docs/globe-present.html`（生态地球演示页）的美学体系——星空、大气光晕、四季色调、流星——用**原生 OpenGL ES（`GLSurfaceView` + `GLES20`）**渲染。

**v2.0 的路线变更**：v1.x 定的是 Compose `DrawScope` 2D；现改为原生 GL，理由见 §1.4。

### 1.2 与既有渲染路径的边界

> ⚠️ 归因澄清：`docs/globe-present.html` 是「生态地球」**演示页**（星空 / 150 棵树 / 19 只动物 / 龙卷风 / 山脉）。
> 它**不是** E41「世界」（`VisualizerTheme.WORLD`）——后者是航空地球：Robinson 陆地 + 城市灯火 + 动态航线弧
> （`data/model/AppSettings.kt:261-294`）。本方案只借鉴演示页的**氛围语言**。

| E41「世界」（当前 WebView 路线） | 本频谱效果 |
|---|---|
| WebView + three-globe 3D 球体 | **原生 OpenGL ES**（无 WebView） |
| 球体 + 卫星贴图 + 航线管 | 平面频谱条 / 波形 / 粒子 |
| 独立 JS 运行时（Chrome 39 ES5 降级） | 自写 ESSL + Kotlin 场景 |
| 地球自转 | 频谱数据驱动 |

**与 E41 GL 重写的关系**：两者**共享** `visualizer/gl/` 基础层（§3.2），但**各自独立**：不同 renderer、不同 `GLSurfaceView` 实例、不同 program 集、不同几何。

### 1.3 质量要求

- 逐行移植视觉要素（星空、大气、流星、季节色、昼夜），不降低效果与美观
- 帧率预算：**≥ 45 FPS**（低端设备），**≥ 60 FPS**（主流设备）
- **零新增依赖**：不引入第三方库（FFT 已由既有 `SpectrumAnalyzer` 提供）
- **渲染器形态**：View 型旁路（`isViewBased = true`），⛔ **不继承 `RendererFx`**（那是 Canvas 路径的模板方法基类）
- **零分配红线**：`onDrawFrame` 内禁止任何对象分配（矩阵 / 数组 / 顶点缓冲全部预分配复用）
- **分辨率适配**：一切像素尺寸按画幅短边比例化，⛔ 不用绝对 px

### 1.4 为什么从 Canvas 2D 改成 OpenGL ES

| 维度 | Canvas 2D（v1.x） | OpenGL ES（v2.0） |
|---|---|---|
| 载体 | Compose `DrawScope` | `GLSurfaceView` + `GLES20` |
| 渐变（大气） | ⛔ 逐帧重建 `Brush` 会分配；连续插值下无法避免 | ✅ 片元着色器算，零分配 |
| 星点闪烁 | ⛔ 逐点 alpha ⇒ 只能逐颗 `drawCircle` | ✅ per-point alpha attribute ⇒ **一次 `GL_POINTS` 画完** |
| 频谱条圆角 | `drawRoundRect`（每条一次） | ✅ 片元着色器算 SDF 圆角 ⇒ **64 条合并成一个 VBO 一次 draw** |
| 波形 | 逐段 `drawLine` | ✅ `GL_LINE_STRIP` 一次 |
| 粒子 | 逐颗绘制 | ✅ `GL_POINTS` + 动态 VBO |
| 抗锯齿 | 依赖 Canvas | ✅ MSAA（`antialias: true` 的 EGL config） |
| TV 端 | ✅ 无宿主问题 | ⚠️ 需自建 EGL/GLSurfaceView 生命周期（E52 已趟平） |
| 帧率 | 与 Compose Choreographer 同步 | ⚠️ 独立渲染循环 ⇒ ⛔ 必须覆写 `renderFps()` |

**结论**：视觉规格完全保留（v1.x 的视觉设计逐条照搬），**渲染实现整体换栈**。

> ⚠️ **Canvas 2D 路线的物理上限**（`docs/archive/e52-highway-opengl-plan.md:29-43`）：
> *"Canvas 2D 的 `fill()` 语义是「一条路径 → 一个纯色」：没有片元着色器、没有纹理采样、没有法线贴图、没有逐像素光照"*
> ⇒ 渐变/闪烁/圆角这三样在 Canvas 下都要用"多描边 + 叠加层"硬凑（帧数与内存双涨），在 GL 里都是免费的。

---

## 二、视觉设计

### 2.1 整体画面构图

```
┌───────────────────────────────────┐
│  ★  *   ✦    ★         *    ✦   │ ← Starfield (y: 0~25%)
│     ★      ✦    *                 │
│  ★     ★  ✦      ★    ✦    *    │
│                                  │
│  ▓▓▓ ▓▓▓ ▓▓▓ ▓▓▓ ▓▓▓ ▓▓▓ ▓▓▓ ▓▓▓ │ ← 频谱条 (y: 30%~75%)
│  ▒▒▒ ▒▒▒ ▒▒▒ ▒▒▒ ▒▒▒ ▒▒▒ ▒▒▒ ▒▒▒ │   (底部低→顶部高)
│  ░░░ ░░░ ░░░ ░░░ ░░░ ░░░ ░░░ ░░░ │
│                                  │
│       ════════════════════       │
│         波形 / 粒子层              │ ← Waveform / Particles (y: 75%~90%)
│       ════════════════════       │
│                                  │
│    ♠ ───────流星────────→  ♠     │ ← Meteor (间歇出现)
│       大气光晕渐变 ↓                │
│   ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░│ ← Atmosphere glow (y: 88%~100%)
└───────────────────────────────────┘
```

### 2.2 视觉层面 → GL 实现（从底到顶）

| 层 | 名称 | 位置 | 内容 | **GL 实现** |
|---|---|---|---|---|
| L0 | **背景渐变** | 全屏 | 深空蓝 → 午夜蓝 → 近黑（随季节微调） | 全屏 quad + 片元径向渐变（`uTintBg`） |
| L1 | **星空** | y: 0~30% | 随机星点，三档亮度，逐颗闪烁 | `GL_POINTS` × 1880（两层），per-point `aColor`（含 alpha）⇒ **1 次 draw** |
| L2 | **大气光晕** | y: 80~100% | 底部径向渐变暖色雾（随季节变色） | 与 L0 **同一 program**（uniform 切换分支），片元算底部径向 |
| L3 | **频谱条** | y: 30~80% | 64 根，季节色着色 | **一个动态 VBO**（64×6 顶点）+ 片元 SDF 圆角 ⇒ **1 次 draw** |
| L4 | **频谱粒子** | y: 20~90% | 频段驱动的发光点 | `GL_POINTS` + 动态 VBO（200 槽对象池）⇒ **1 次 draw** |
| L5 | **波形线** | y: 70~95% | 实时波形，半透明 | `GL_LINE_STRIP` × 128 点，per-vertex color ⇒ **1 次 draw** |
| L6 | **流星** | 任意 | 间歇性流星划过 | 2 个 quad（头 + 渐变尾），与 L3 同 program ⇒ **≤2 次 draw** |
| L7 | **昼夜光标** | 垂直 | 日间暖光 / 夜间冷光 | uniform 混合系数，作用于 L0/L2/L3 的 tint |

**每帧 draw call 预算：≤ 8**（背景+大气 1 / 星空 1 / 频谱条 1 / 粒子 1 / 波形 1 / 流星 ≤2 / 收尾 1）

### 2.3 色彩系统

#### 四季调色板（改编自演示页 `SEASON_PARAMS`）

每季一组 RGB 三元组，作用于背景渐变、频谱条色、大气雾色。季节按**播放时长**推进（驱动规则见 §5.1）。

| 季节 | 背景 (top→bottom) | 频谱主色 | 频谱次色 | 大气雾色 |
|---|---|---|---|---|
| **春** (0) | `#1a2f4a → #0d1b2e → #040810` | `#7acc6e` (嫩绿) | `#b4e0a0` | `#2a5a4a` |
| **夏** (1) | `#1e3a5a → #0f2035 → #050815` | `#4f9fd4` (海洋蓝) | `#8fd0f0` | `#1a3a6a` |
| **秋** (2) | `#2a1f10 → #1a140a → #0a0604` | `#e8a030` (橙金) | `#f0c060` | `#5a3a1a` |
| **冬** (3) | `#0a1f35 → #051028 → #020410` | `#6b8ec4` (冷蓝) | `#a0c0e0` | `#1a2a5a` |

#### 季节过渡

移植演示页的 **`seasonW` S 曲线**：
- 每个季节周期 = **15 分钟**（≈ 4 首歌）
- 季末 30% 连续过渡到下一季
- `seasonW = smooth01(smooth01(fract(sf)))`（双重 smooth01 ⇒ 有意平顶 S 曲线）
- 背景 / 频谱 / 大气三层用**同一** transition 因子 ⇒ 同步

#### 频谱条的双色插值（频段边界对齐 `SpectrumContract`）

> ⚠️ 频段边界必须与 `SpectrumContract`（`visualizer/SpectrumContract.kt:29-34`）+ `SpectrumAnalyzer` 的 64 柱感知映射一致：
> bass = bins 0–39（20–250Hz）、mid = 40–55（250Hz–3kHz）、treble = 56–63（3k–20kHz）。

每根频谱条在 **主色 → 次色** 之间按柱索引线性插值（**在 CPU 侧算好，per-vertex 颜色上传**）：
- 低频段（bins 0–39）→ 偏向主色
- 中频段（bins 40–55）→ 主色/次色混合
- 高频段（bins 56–63）→ 偏向次色

### 2.4 频谱几何规格

#### 频谱条

```
数量: 64 根 —— ⛔ 不可配置，恒取 frame.spectrum.size（SpectrumContract.BAR_COUNT = 64）
  ↓ SpectrumContract.kt:5-7 明文：「渲染侧不得自行指定柱数，一律取 AudioFrame.spectrum.size」
形状: 等宽矩形（宽 = (viewportW - 2×margin) / bars）
圆角: 顶部圆角（片元 SDF，⛔ 不用 drawRoundRect 的 CPU 几何开销）
最大高度: viewportH × 0.48
最小高度: 4dp（按短边比例）
间隙: 2dp（按短边比例）
```

#### 波形线

```
采样点数: 128 —— 恒取 frame.waveform.size（SpectrumContract.WAVE_POINTS = 128）
渲染: GL_LINE_STRIP，per-vertex color（季节次色，alpha≈0.45）
位置: y = viewportH × 0.82
振幅: ±(viewportH × 0.04)
```

### 2.5 频谱粒子系统（改编演示页的花/雪花/落叶）

演示页有三种季节性粒子：花朵（夏）、雪花（冬）、落叶（秋）。频谱版本改为**频段驱动的类型化粒子**：

| 粒子类型 | 触发条件 | 外观 | 演示页对应 |
|---|---|---|---|
| **星尘** | 常驻（低频底噪） | 极小亮点，缓慢飘降 | 森林上方光点 |
| **能量火花** | 高频能量（`treble`）越过阈值 | 上升的发光点，到顶消散 | 夏季花朵 |
| **雪晶** | 冬季季 | 旋转的六角雪花，缓慢飘落 | 冬季雪花 |
| **落叶** | 秋季季 | 旋转的扁椭圆，左右摆动飘落 | 秋季落叶 |

**粒子生命周期**：`maxLife` 1.5~4s，生命周期内非线性位移 + 旋转。

**数量分档**（按画质档，见 §5.2）：

| 档 | 星尘 | 火花上限 | 季节粒子 |
|---|---|---|---|
| HIGH | 30 | 50 | 强度 × 120 |
| MEDIUM | 15 | 25 | 强度 × 60 |
| LOW | 0 | 0 | 0 |

**频段对齐**（⛔ 用**线性律动通道**判定强弱，不用已 gamma 压缩的显示通道）：
```
spawnRate = baseRate × (1 + frame.treble × 3.0)
```

**GL 侧实现**：全部 4 类粒子**共用一个 `GL_POINTS` draw**，用 per-point 属性区分：
- `aPos`（vec3）+ `aSize`（float）+ `aColor`（vec4，含 alpha）+ `aShape`（float：0=圆点 / 1=雪花 / 2=落叶）
- 片元着色器按 `aShape` 分支画形状（雪花六角 / 落叶椭圆）—— ⛔ 分支在**片元里**做（GPU 免费），⛔ 不要在 CPU 侧生成不同几何

---

## 三、技术架构

### 3.1 数据流

```
ExoPlayer AudioSink
  ↓
PcmTapProcessor / PcmSpectrumTap（PCM 透传，后台 FFT，无需 RECORD_AUDIO）
  ↓
SpectrumAnalyzer（player/SpectrumAnalyzer.kt：64 柱感知加权映射 + gamma + AGC）
  ↓ 零分配回调
SpectrumRepository（AudioFrame 唯一写入方，双缓冲）
  ↓ frame（只读单例）
┌──────────────────────────────────────────────┐
│ 主线程                                        │
│  StellarSpectrumRenderer.onFrame(frame, ctx) │
│      ↓ publish（标量 + 数组，逐个拷进缓冲）      │
│  GlBridge（双缓冲 + volatile 索引 + 版本号）    │
└──────────────────────────────────────────────┘
      ↓ consume（单读者）
┌──────────────────────────────────────────────┐
│ GL 线程                                       │
│  StellarGlScene.onDrawFrame()                 │
│   ├─ 场景状态更新（季节 / 昼夜 / 粒子 / 流星）    │
│   ├─ 顶点数据打包（预分配 FloatArray）           │
│   ├─ VBO 上传（glBufferSubData / glBufferData） │
│   └─ 绘制：背景+大气 / 星空 / 频谱条 / 粒子 /    │
│            波形 / 流星                          │
└──────────────────────────────────────────────┘
```

**关键**：渲染器**只读** `SpectrumRepository.frame`，绝不自己算 FFT、绝不新增录音/权限。

### 3.2 共享 GL 基础层（`visualizer/gl/`）

> 与 E41 地球 GL 重写**共用**（见 `docs/e41-globe-opengl-plan.md` §4.1）。设计要点：

| 件 | 职责 | 关键约束 |
|---|---|---|
| `GlHost` | `GLSurfaceView` 子类：EGL 配置 / 焦点 / 触摸 / 尺寸 / 释放 | ⛔ `onTouchEvent` 必须返回 **false**（否则吃掉 Compose 手势）；⛔ `isFocusable=false`（否则吃遥控方向键） |
| `GlBridge` | 主线程 → GL 线程数据桥 | ⛔ 纯数据、不持 GL 句柄；⛔ 必须拷贝（`AudioFrame` 不得跨帧持有）；双缓冲 + volatile 索引 + **每槽版本号**（奇=写中/偶=稳定） |
| `GlCaps` | 能力探测 | ⛔ 任何一项拿不到就给默认值，**绝不抛异常**把 GL 线程搞挂 |
| `GlProjection` | 透视 / lookAt / 乘法 | column-major（下标 `m[col*4+row]`），零分配变体 `xxxInto` |
| `GlShader` | program 编译链接 / uniform & attribute location 缓存 | 编译失败打 logcat 并返回 0（⛔ 不抛） |

✅ **D3 已裁决（2026-11-08 所有者）：收敛到共享层，不写第三套。** 拆分与落地动作详见
`docs/e41-globe-opengl-plan.md` §4.1，摘要：
- **通用层**（`GlHost` / `GlBridge` / `GlCaps` / `GlProjection` / `GlShader`）⇒ **main 源集**，公开、两个分支一致；
- **上游派生层**（E52 的逐字搬运 GLSL、上游常量）⇒ 留私有分支；
- 改覆盖层副本后跑 `python scripts/sync_private_overlay.py` 同步，`--check` 必须零漂移；
- ⛔ 共享层**不得**含任何上游常量或上游 GLSL（混进去就变成公开的上游衍生件 ⇒ 泄漏）。

### 3.3 频谱侧专用件

| 件 | 职责 |
|---|---|
| `StellarGlBridge` | `GlBridge` 的频谱特化：额外带 **`waveform[128]`**（⚠️ E52 的 `HighwayBridge` **没有** waveform 字段） |
| `StellarGlScene` | 场景状态 + 顶点打包 + 绘制顺序 |
| `StellarGlsl` | ESSL 常量（背景/大气、精灵点、条形/四边形） |
| `StellarSeason` | 季节相位（S 曲线 + 调色板插值） |
| `StellarParticles` | 粒子对象池（200 槽，零分配） |
| `StellarMeteors` | 流星调度（时间驱动，≤2 颗） |

### 3.4 渲染器（View 型）

```kotlin
class StellarSpectrumRenderer : VisualizerRenderer {

    override val theme: VisualizerTheme = VisualizerTheme.STELLAR_SPECTRUM

    // ⛔ View 型 ⇒ draw 根本不被调用
    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {}

    override val isViewBased: Boolean get() = true

    private val bridge = StellarGlBridge()

    override fun createView(context: Context): View =
        StellarGLSurfaceView(context.applicationContext, bridge, quality)

    override fun onViewAttached() { bridge.start() }
    override fun onViewDetached() { bridge.stop() }

    // ⛔ 必须覆写：View 型有独立渲染循环，舞台徽章量的是 Compose Choreographer 回调率
    //    ⇒ 不覆写就会出现「显示 59 fps 但画面很卡」（E52 真机：徽章 52~59 而实际 6.5 fps）
    override fun renderFps(): Float = bridge.lastFps

    // ⛔ 必须覆写：View 旁路下 RenderContext.update(...) 不在 draw 块里被调用
    //    ⇒ ctx.songId 永远是旧值（VisualizerRenderer.kt:80-90）
    override fun onFrame(frame: AudioFrame, ctx: RenderContext) {
        bridge.publishAudio(frame)
        bridge.publishSongId(ctx.songId)
    }
}
```

**⚠️ main 源集当前没有 `onFrame` / `renderFps()` 的生产调用点**（只有私有覆盖层的 `VisualizerStage.kt:294-314, 351` 调它们）。
⇒ 两条路：
- **① 照 E41 现状**：自建 `Handler` tick（100 ms），**直接读** `spectrumRepository.frame`（绕过舞台）—— 与 `WorldGlobeRenderer.kt:380-384` 同款，**main 源集零改动**；
- **② 补 main 的舞台通道**：在 `VisualizerStage.kt` 里加 `onFrame` 推送 + `renderFps()` 读取（改 main 源集 ⇒ 与覆盖层同名文件冲突 ⇒ 需同步覆盖层副本）。

**建议 ②**（顺带把 E52 的缺口一起补上），但要按覆盖层规则操作。

### 3.5 EGL 与线程配置

```kotlin
setEGLContextClientVersion(pickClientVersion(context))   // 3，不支持退 2
setEGLConfigChooser(8, 8, 8, 0, 0, 0)                   // ⛔ 不要 alpha / ⛔ 不要 sRGB / 2D 不需要深度
setRenderer(glRenderer)
renderMode = RENDERMODE_CONTINUOUSLY
isFocusable = false; isFocusableInTouchMode = false
override fun onTouchEvent(event: MotionEvent): Boolean = false
```

- **EGL 三条硬约束**（E52 `HighwayGLSurfaceView.kt:31-41` 原文）：⛔ 不要 alpha（第 4 参 = 0，否则与下层混合出脏色）；⛔ 不要 sRGB（会让画面变亮变灰）；深度按需。
  ⇒ 本效果是纯 2D 叠层 ⇒ **深度位设 0**（省显存与带宽）；若未来要混 3D，共享层参数化即可。
- ⚠️ **创维机 `clientVersion = 2` 也返回 3.1** ⇒ 拿不到真 ES2 上下文 ⇒ 着色器仍写 **ESSL 1.00**（ES3 上下文也接受），但**顶点数据必须走 VBO**（ES3 核心删除了客户端顶点数组）。

### 3.6 帧率与 dt

- **dt 来源**：GL 线程**自己**按 `System.nanoTime()` 差分算（⛔ 不要用 `frame.timeMs` —— 那是音频帧时钟，30 Hz 量化 ⇒ 动画会抖）。
  ⇒ 这是 GL 路线相对 Canvas 路线的一个改进：Canvas 路线必须用基类 `fx.dt`（`FrameClock`，`RendererFx.kt:195-213`）；GL 路线自算即可。
- **上报**（照 E52 `HighwayGLSurfaceView.kt:697-706`）：
  ```kotlin
  emaFrameMs = if (emaFrameMs <= 0f) ms else emaFrameMs * 0.85f + ms * 0.15f
  lastFps = 1000f / emaFrameMs
  ```
  ⛔ **不能用 120 帧窗口**：真机 ~5 fps 时 24 秒才刷新一次 ⇒ 前 24 秒 `renderFps()` 恒 0。
- **相位推进**：⛔ **绝不用 `nowMs × 系数`**（`photo/PhotoTransitionClock.kt:17`：1.7e12 量级乘系数会把每帧抖动放大千万倍 ⇒ 画面闪现跳档）⇒ 一律 `phase += dt` 累加。

### 3.7 音频帧契约（对齐 `AudioFrame`，⛔ 不自定义帧结构）

```kotlin
class AudioFrame(barCount: Int, wavePoints: Int) {
    val spectrum: FloatArray    // 64，显示通道（已 gamma ^0.75）
    val waveform: FloatArray    // 128，-1..1 时域波形
    var bass/mid/treble/energy/sectionEnergy/bassRaw: Float   // 线性律动通道
    var beat: Boolean; var pulse: Float; var bpm: Float
    var timeMs: Long; var seq: Long
}
```

| 用途 | 用哪个通道 | 原因 |
|---|---|---|
| 柱高 / 波形 / 视觉亮度 | `spectrum` / `waveform`（显示通道） | 小信号可见 |
| 强弱判定 / 粒子触发 / 律动 | `bass` / `mid` / `treble` / `pulse`（线性律动通道） | gamma 会把轻/重差异压平（历史 BUG ⑨-b） |

**FFT 参数不由本方案决定**（既有 `SpectrumAnalyzer`：44100Hz、64 柱感知加权、`DISPLAY_GAMMA=0.75`、`AGC_DECAY=0.995`）⇒ **⛔ 方案内不出现 FFT size / window / overlap**。

### 3.8 平滑处理

- **默认不平滑**：`frame.spectrum` 已 gamma 压缩 + AGC 归一化；再叠加大窗口平滑会造成二次压缩 ⇒ 动态被抹平。
- 若个别柱仍抖，**只允许**一层极轻 EMA（`smooth = mix(smooth, raw, 0.35)`），并在 KDoc 注明「输入已压缩，此处仅做时间去抖」。
- **强弱判定**一律走线性律动通道。

---

## 四、各组件 GL 规格

### 4.1 背景 + 大气（合并为 1 个 program）

```
几何: 全屏三角（(-1,-1) (3,-1) (-1,3)）—— 1 个三角形覆盖整屏，比 quad 少一次光栅化边界
顶点属性: aPos(vec2)
uniform: uResolution(vec2), uBgTop(vec3), uBgBottom(vec3), uAtmoColor(vec3), uAtmoStrength(float), uDayPhase(float)
片元:
  vec2 p = aPos;                       // NDC
  vec2 uv = p * 0.5 + 0.5;             // 0..1
  vec3 bg = mix(uBgTop, uBgBottom, uv.y);
  float d = length((uv - vec2(0.5, 0.0)) * vec2(uResolution.x/uResolution.y, 1.0));
  float glow = smoothstep(0.62, 0.0, d) * uAtmoStrength;
  vec3 col = bg + uAtmoColor * glow;
  gl_FragColor = vec4(col, 1.0);
```

⚠️ 昼夜（§2.2 L7）通过 `uDayPhase` 在 CPU 侧插值 `uBgTop/uBgBottom/uAtmoColor` ⇒ **着色器零分支**。

### 4.2 星空（1 个 program，1 次 draw）

```
点数: 1880（星尘 1500 + 亮星 380），球面均匀采样，固定种子 LCG
半径: 900（远大于视口 ⇒ 视差正确；< far）
属性: aPos(vec3) + aColor(vec4)（含亮度与 alpha）+ aSize(float)
闪烁: ⛔ 在 CPU 侧按 dt 更新 alpha ⇒ 每帧重写 VBO（1880×8 float ≈ 60 KB/帧，传输量可忽略）
绘制: glDrawArrays(GL_POINTS, 0, 1880)
```

⚠️ **点精灵尺寸**（E52 教训，`glsl/HighwayGlsl.kt:3733-3740`）：
```glsl
gl_PointSize = min(aSize * uPointScale / (-mvPosition.z), 64.0);
if (-mvPosition.z <= 0.001) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); gl_PointSize = 0.0; }
```
⛔ **必须 clamp 到 64 px**（不 clamp ⇒ 爆成几十万像素 ⇒ 一个黑方块糊满屏幕，E52 真机实测「巨大黑带」）；⛔ 相机后方的点必须丢弃。

### 4.3 频谱条（1 个 program，1 次 draw）

```
几何: 64 个 quad → 64×6 = 384 顶点，属性 aPos(vec2) + aUv(vec2) + aColor(vec4)
VBO: 动态，每帧 glBufferSubData（或 glBufferData）
片元: 顶部圆角用 SDF —— 传入 aLocal(vec2)（该 quad 内的归一化坐标）+ aHalfSize：
      float r = 2.0 * uRadius;                     // 圆角半径（px）
      vec2 q = abs(aLocal) - (uHalfSize - r);
      float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
      if (d > 0.0) discard;                        // 圆角外丢弃
颜色: aColor（CPU 侧按 §2.3 双色插值算好）
```

⛔ **不要用 64 次 `drawArrays`** ⇒ 一次 draw 全部 64 根。
⚠️ 圆角 SDF 的 `discard` 会关掉早期深度剔除 ⇒ 本效果无深度测试（§3.5 深度位 = 0）⇒ 无影响。

### 4.4 粒子（1 个 program，1 次 draw）

```
属性: aPos(vec3) + aSize(float) + aColor(vec4) + aShape(float)
片元: 按 aShape 分支 —— 0 = 圆点 / 1 = 六角雪花 / 2 = 扁椭圆落叶
VBO: 动态，200 槽对象池，每帧只上传活跃粒子（用 glBufferSubData 写活跃区间）
```

⚠️ 对象池**不做 swap-remove**（⛔ 会打乱活跃区间）⇒ 用「活跃计数 + 紧凑写入」：每帧把活跃粒子紧凑写入打包缓冲的上限区。

### 4.5 波形（1 个 program，1 次 draw）

```
GL_LINE_STRIP，128 顶点，aPos(vec2) + aColor(vec4)
线宽: ⛔ WebGL/GLES 的 lineWidth 恒为 1 ⇒ 需要粗线时改用四边形带（本方案线宽 1~2px ⇒ 可接受）
```

### 4.6 流星（≤2 次 draw，与频谱条共用 program）

```
形状: 头（实心点）+ 尾（沿轨迹的渐变四边形）
生命周期: 2.5s（淡入 0.3 / 持续 2.0 / 淡出 0.2）
触发: 4~9s 随机间隔（时间驱动，按 dt 累加计时）
轨迹: 从 (0.8W, 0.1H) 到 (0.2W, 0.4H)，长度 ≈ 0.35W
季节色: 春偏绿 / 夏偏白 / 秋偏橙 / 冬偏蓝
```

### 4.7 Program 清单（合并后 **2~3 个**）

| program | 用途 | attribute | uniform |
|---|---|---|---|
| `bg` | 背景 + 大气 + 昼夜 | `aPos` | `uResolution`, `uBgTop`, `uBgBottom`, `uAtmoColor`, `uAtmoStrength` |
| `sprite` | 星空 + 粒子 + 频谱条 + 流星（**用 `aMode` 区分**） | `aPos`, `aLocal`, `aColor`, `aSize`, `aMode` | `uViewProj`, `uPointScale` |
| `line` | 波形 | `aPos`, `aColor` | `uViewProj` |

> ⚠️ **program 数量是 TV 端首屏体验的关键**：创维的 PVRShaderCompiler 编译 PBR 变体要 **~5 秒**（E52 `HighwayGLSurfaceView.kt:83-88`）⇒ 必须
> ① **合并 program**（本方案 3 个 vs E52 的 12 个）② **分帧预热**：首帧只 `glClear` 并 swap 出清屏色，program 逐帧建（E52 的 `bootStage` 模式）③ ⛔ 绝不能等到第一次绘制时才编译。

### 4.8 顶点数据与上传

| 缓冲 | 内容 | 大小 | 更新频率 |
|---|---|---|---|
| `bgVbo` | 全屏三角 | 6 float | 静态（一次） |
| `starVbo` | 1880 点 × 8 float | ~60 KB | 每帧（闪烁） |
| `barVbo` | 384 顶点 × 8 float | ~12 KB | 每帧 |
| `particleVbo` | 200 点 × 9 float | ~7 KB | 每帧 |
| `waveVbo` | 128 点 × 6 float | ~3 KB | 每帧 |
| `meteorVbo` | 2 quad | ~0.4 KB | 每帧 |

**合计每帧上传 ≈ 82 KB** ⇒ 60 fps 下 ≈ 4.9 MB/s，**可忽略**。

⚠️ ES2/ES3 都支持 `glBufferSubData`；⛔ **必须用 VBO**（ES3 上下文里 `glVertexAttribPointer` 不绑 buffer 是 `GL_INVALID_OPERATION`）。
⚠️ 若改用 instancing（**不建议**）：⛔ 创维机没有 `GL_EXT_instanced_arrays`（E52 实测）⇒ 只能靠 ES3 核心 `glVertexAttribDivisor`；且 `glVertexAttribDivisor` 是**按属性索引的全局状态**，少复位一次 ⇒ 后续非实例化绘制顶点全读第 0 个元素 ⇒ 三角形退化（E52 真机：除植被外全白）。⇒ **本方案坚持用动态 VBO，避开 instancing**。

---

## 五、动画系统

### 5.1 季节推进

| 参数 | 值 |
|---|---|
| 驱动 | GL 线程自算 `dt` 累加 ⇒ `seasonElapsedSec += dt` |
| 周期 | 15 分钟/季（≈ 4 首歌）⇒ `sf = (seasonElapsedSec % 900) / 900` |
| 季内占比 | 前 70% 保持当前季，末 30% 过渡 |
| 过渡曲线 | 双重 `smoothstep`（平顶 S 曲线） |
| 颜色插值 | 所有颜色参数同一因子 |
| 切歌 | ⛔ **不重置**季节相位（连续氛围不因换歌断裂）；若需按歌重置，用 `ctx.songId` 变化时归零（`ctx.songId` 未被契约门禁禁止） |

### 5.2 画质分档

- **View 型渲染器拿不到 `fx.level`**（那是 `RendererFx` 的概念）⇒ 按 `ctx.quality` 自己分档
  （`VisualQuality.HIGH/MEDIUM/LOW`，`AppSettings.kt:626-637`）。
- 数量表见 §2.5 / §4.2 / §4.3。
- **枚举标注**：若本效果**不读** `ctx.quality.maxParticles` ⇒ 枚举必须标 `needsParticleBudget = false`
  ⇒ `VisualQuality.supports()` 对 ADV 的门槛是 `!needsParticleBudget || maxParticles > 0`（`AppSettings.kt:648`），
  而 `LOW` 档 `maxParticles = 0`（`:637`）⇒ 标 `true` 会让本效果**在 LOW 档不可选**
  （"能渲染却不可选、切走即永久回不来"那类缺陷）⇒ **本方案读 `ctx.quality` 自己分档 ⇒ 标 `false`**，与 E42/E43/E47/E48/E49/E50 的既有做法一致。

### 5.3 昼夜交替

```
驱动: dayPhase += dt / 8f（⛔ 不用 nowMs × 系数）
白天: 频谱条整体提亮 15%，背景顶部偏暖，大气雾色偏暖
夜晚: 频谱条保持不变，背景顶部加深，大气雾色偏冷
过渡: 约 8 秒的正弦过渡
```

### 5.4 运行时自适应（可选）

若连续多帧 `dt` 偏大（帧率不足）⇒ 在场景内部再降一档数量（自维护 `degraded` 标志，⛔ 不改 `ctx.quality`）。

---

## 六、性能预算

### 6.1 每帧成本估算（1080p，中端设备）

| 操作 | draw calls | 估算时间 |
|---|---|---|
| 背景 + 大气（全屏三角） | 1 | ~0.6ms（填充 2 Mpx，1 次渐变） |
| 星空 1880 点 | 1 | ~0.5ms |
| 频谱条 64 根（1 draw + SDF 圆角） | 1 | ~0.8ms |
| 粒子 ≤50 点 | 1 | ~0.3ms |
| 波形 128 点 | 1 | ~0.1ms |
| 流星 ≤2 | 2 | ~0.1ms |
| **合计** | **≤ 7** | **≈ 2.4ms** |

⇒ **60 fps 有充足余量**，且 **renderScale 可保持 1.0**（全分辨率）——
与 E52 需降到 0.30~0.40 形成鲜明对比（E52 的瓶颈是「15 次采样/像素 @1080p = 87.21 ms」的填充率，本效果每像素只算一次渐变）。

### 6.2 内存预估

| 对象 | 内存 |
|---|---|
| 顶点缓冲（合计 ≈ 82 KB） | ~82 KB |
| 粒子对象池（200 × ~200 B） | ~40 KB |
| 场景状态 / 调色板 | ~2 KB |
| **合计** | **< 130 KB** |

### 6.3 优化策略

1. **program 合并**：3 个（见 §4.7）⇒ 规避创维 shader 编译慢。
2. **单 draw 合批**：频谱条 64 根 1 draw；星空 1880 点 1 draw；粒子 1 draw。
3. **零分配**：`onDrawFrame` 内不 new 矩阵 / 数组 / 顶点缓冲；打包缓冲全部预分配。
4. **分帧预热**：首帧只 `glClear`（swap 出清屏色），program 逐帧建。
5. **降级方案**（按 `ctx.quality`）：LOW 档关粒子与流星，星空减半。

---

## 七、数据来源与桥接

### 7.1 数据链路（已存在，⛔ 不改）

```
ExoPlayer AudioSink → PcmTapProcessor → SpectrumAnalyzer → SpectrumRepository → AudioFrame
```

渲染器**只读** `SpectrumRepository.frame`（`val frame: AudioFrame`，`SpectrumRepository.kt:40`）。

### 7.2 数据源优先级（⛔ 只有一条）

1. **`SpectrumRepository.frame`**（唯一）
2. ~~ExoPlayer AudioFrameCallback~~ —— ⛔ 不存在该通道，不要新增
3. ~~Visualizer API~~ —— ⛔ 已随 v2.37.6 移除（`SpectrumAnalyzer.kt:14`）；项目明文「不用 AudioRecord/Visualizer」（`AppSettings.kt:290-292`）
4. ~~自建 FFT~~ —— ⛔ 违反「复用现有分析层」

### 7.3 桥接内容

```kotlin
class StellarGlBridge {
    class Snap {
        @JvmField var songId: String? = null
        @JvmField var energy = 0f; var bass = 0f; var mid = 0f; var treble = 0f
        @JvmField var pulse = 0f; var beat = false
        @JvmField var spectrum = FloatArray(SpectrumContract.BAR_COUNT)   // 64
        @JvmField var waveform = FloatArray(SpectrumContract.WAVE_POINTS)  // 128 ⚠️ E52 的桥没有这个
        @JvmField var seq = 0L; var timeMs = 0L
    }
    fun publishAudio(frame: AudioFrame)
    fun publishSongId(id: String?)
    fun consume(dst: Snap): Boolean
    fun close()
}
```

**并发契约**（照 `HighwayBridge.kt:6-40`）：
① ⛔ 纯数据、不持 GL 句柄；② ⛔ 必须拷贝（`AudioFrame` 不得跨帧持有，`SpectrumRepository` 写端会翻转 `writeIndex` ⇒ 透传引用有**撕裂读**风险）；
③ ⛔ 不得跨帧持有引用；④ 双缓冲 + volatile 索引 + **每槽版本号**（奇=写中 / 偶=稳定）⇒ 读者能**发现**撕裂并丢弃该帧；
⑤ 发布顺序：**先槽号、后序号**（读者按「先读序号、再读槽号」看 ⇒ 不会读到半个状态）。

**零分配**：每帧只拷标量 + 64 + 128 个 float（`System.arraycopy`），≈ 832 B ⇒ 可忽略。

---

## 八、兼容性与约束

### 8.1 架构约束

- **不引入 WebView / three.js**（E41 的宿主问题是 TV 端黑屏，本效果不碰 WebView）
- **不引入新第三方依赖**
- **View 型渲染器**：直接实现 `VisualizerRenderer` + `isViewBased = true`，⛔ **不继承 `RendererFx`**（那是 Canvas 路径的模板方法基类，其 `draw` 是 `final`）
- ⛔ **不得覆写 `draw` 的实现逻辑**（View 旁路下它不被调用；写空实现即可）
- ⛔ **不得读 `ctx.nowMs`**（三个调用点语义不一致：墙钟 / 单调毫秒）⇒ 用自算 `dt`
- ⛔ **不得自建 `dt` 累加器于 Canvas 语义**（本方案在 GL 线程自算 `System.nanoTime()` 差分，是允许且更准的）
- **⛔ 不读 `ctx.quality.maxParticles`**（数量按 `ctx.quality` 自己分档 ⇒ 枚举 `needsParticleBudget = false`）

### 8.2 设备兼容

| API Level | 能力 | 适配 |
|---|---|---|
| 22 (minSdk) | `GLSurfaceView` / GLES2 / ESSL 1.00 | 可用（E52 已验证创维 5.1.1 可跑） |
| 24+ | 更稳的批量绘制 | 优化路径 |

### 8.3 无障碍

- 频谱为纯视觉（非交互），无需 `contentDescription`
- 闪烁频率 < 3Hz（防晕动症）—— 星空闪烁周期 3~5s ⇒ 远低于阈值
- 帧率自适应降级为自动行为

---

## 九、开发里程碑

### Phase 0: 共享 GL 层 + 门禁接入（5~8 天）
- [ ] `visualizer/gl/`：`GlHost` / `GlBridge` / `GlCaps` / `GlProjection` / `GlShader`
- [ ] 与私有覆盖层的 E52 同类件收敛（改覆盖层副本 + `sync_private_overlay.py` 同步 + `--check` 零漂移）
- [ ] 枚举新增 `STELLAR_SPECTRUM`（**五参数** + 两份 `strings.xml` key + `needsParticleBudget = false`）
- [ ] `VisualizerRendererFactory.createEnabled` 加 `when` 分支
- [ ] **登记 §10.2 的门禁**
- [ ] 补 main 源集的 `onFrame` / `renderFps()` 舞台通道（与 E41 一起做）

### Phase 1: 骨架 + 背景（2~3 天）
- [ ] `StellarSpectrumRenderer`（View 型）+ `StellarGLSurfaceView` + `StellarGlScene`
- [ ] `bg` program：全屏三角 + 背景渐变 + 大气
- [ ] `GlBridge` 频谱特化（含 waveform）
- [ ] 验证：编译通过 + 不 crash + 背景正确

### Phase 2: 频谱条 + 波形（3~4 天）
- [ ] `bar` 几何打包（64×6 顶点）+ SDF 圆角片元
- [ ] 波形 `GL_LINE_STRIP`
- [ ] 季节调色板 + 双色插值
- [ ] 验证：播放音乐时频谱条有效响应

### Phase 3: 星空 + 粒子（3~4 天）
- [ ] 星空（1880 点 + per-point alpha 闪烁 + `gl_PointSize` clamp）
- [ ] 粒子系统（4 类共用 program + per-point shape）+ 频段触发
- [ ] 验证：星空/粒子观感

### Phase 4: 流星 + 昼夜（1~2 天）
- [ ] 流星调度 + 形状
- [ ] 昼夜相位（`dt` 累加）
- [ ] 验证：流星触发 + 昼夜交替

### Phase 5: 性能 + 收尾（3~5 天）
- [ ] 分帧预热（首帧黑屏时长）
- [ ] 帧率上报（EMA）+ 画质分档验证
- [ ] 单测（见 §10.1）
- [ ] lint + 门禁全绿
- [ ] 真机验证（需所有者执行）
- [ ] 技术文档 §10 归档

**总计**: 约 **17~26 个工作日**（不含 Phase 0 的共享层，共享层与 E41 方案合并计算）

---

## 十、测试计划

### 10.1 单元测试

| 测试类 | 测什么 |
|---|---|
| `StellarPaletteTest` | 季节插值数学正确（双重 smoothstep 边界） |
| `StellarGlBridgeTest` | 双缓冲 + 版本号的撕裂检测（负向：版本号奇数时 `consume` 必须返回 false） |
| `StellarSeasonTest` | 季节折叠、过渡带、边界值、`dt` 累加语义 |
| `StellarParticlesTest` | 对象池不泄漏、活跃区间紧凑写入、数量分档正确 |
| `StellarBarPackingTest` | 64 根的顶点打包（数量、stride、颜色插值） |
| `StellarPointSizeTest` | `gl_PointSize` clamp 到 64（负向：`-mvPosition.z → 0` 时必须为 0） |

> ⚠️ 不单测 `dt`（GL 线程自算，无 Canvas 基类可测）。
> ⚠️ 门禁 `RendererBaseContractTest`（G12）**不适用**（只扫 `RendererFx` 子类；本效果是 View 型）。

### 10.2 既有门禁（⛔ 新增主题必须全部通过）

| 门禁 | 文件 | 本效果要做什么 |
|---|---|---|
| **G7 `FxCoverageScanTest`** | `app/src/test/.../visualizer/fx/FxCoverageScanTest.kt` | ⛔ **View 型必须登记进 `exempt`**（照 `WorldGlobeRenderer` 的条目：「View 型（`isViewBased = true`）：draw 是空实现且根本不被调用」） |
| **`ParticleBudgetGateTest`** | `app/src/test/.../data/model/ParticleBudgetGateTest.kt` | 扫描渲染器是否读 `ctx.quality.maxParticles`，与枚举标注对比。本方案**不读** ⇒ 枚举必须 `needsParticleBudget = false` |
| **G1 `VisualizerThemeStringsGateTest`** | `app/src/test/.../data/model/VisualizerThemeStringsGateTest.kt` | `R.string.visualizer_theme_stellar_spectrum` 必须**同时**存在于 `values/strings.xml` 与 `values-en/strings.xml` |
| **G12 `RendererBaseContractTest`** | `app/src/test/.../renderers/RendererBaseContractTest.kt` | ⚠️ **不适用**（只扫 `RendererFx` 子类）。但若日后把它迁到 `RendererFx` 基类 ⇒ ① 不得覆写 `draw`/`onEnter`/`onExit` ② 不得用 `ctx.nowMs` ③ 不得自建 `private val rng = VisualizerRandom()` |

### 10.3 集成验证

- [ ] Release 包中能跑（APK 无异常增量）
- [ ] API 22 设备（创维 5.1.1）有帧率
- [ ] 频谱与播放同步（无延迟 > 100ms）
- [ ] 后台 → 前台切换时恢复（GL 线程 / EGL 上下文重建）
- [ ] 暂停 → 恢复时动画不跳变
- [ ] 遥控方向键与滑动手势不被吃掉
- [ ] 切后台后显存释放（`GLSurfaceView` 不随 Activity ⇒ 需 `GlHolder` + `MainActivity` 接线，⚠️ **当前 main 与私有分支都未接线**）

### 10.4 性能基线

- 中端机 (API 24)：> 60fps
- 低端机 (API 22)：> 45fps
- draw call ≤ 8
- `renderScale = 1.0`（全分辨率）
- 每帧顶点上传 ≈ 82 KB

---

## 十一、验收标准

### 11.1 视觉验收 ✅

- [ ] 频谱条对音乐有效响应（不仅仅是常量动画）
- [ ] 星空 / 大气 / 流星 / 粒子构成完整的「星际四季」氛围
- [ ] 四季色调变化一眼可见（色相差异 ±20%）
- [ ] 整体美观度不输 `globe-present.html`（比例缩放后）
- [ ] 圆角 / 渐变 / 闪烁在 GPU 上完成，**画面质量不低于 Canvas 版**

### 11.2 性能验收 ✅

- [ ] 60fps 低端机 / 中端机
- [ ] 无 ANR、无跳变（`onDrawFrame` 内零分配）
- [ ] APK 无新增依赖
- [ ] 切后台再回来相位不跳

### 11.3 架构验收 ✅

- [ ] **不使用 WebView / three.js**
- [ ] 复用现有 `SpectrumAnalyzer`（⛔ 不另起 FFT、不用 Visualizer、不新增权限）
- [ ] View 型渲染器 + 覆写 `renderFps()` / `onFrame()`
- [ ] 通过 §10.2 门禁
- [ ] 与 E41 地球 GL 重写共用 `visualizer/gl/` 共享层
- [ ] 技术文档 §10 记录

---

## 十二、参考资料

- `docs/e41-globe-opengl-plan.md` —— **同批方案**（共享 `visualizer/gl/` 层、EGL/线程/生命周期的全部硬约束）
- `docs/globe-present.html` —— 视觉参考源（星空 / 大气 / 季节 / 流星 / 昼夜）
- `app/src/main/java/com/nasmusic/tv/visualizer/VisualizerRenderer.kt` —— 渲染器接口（`isViewBased` / `createView` / `renderFps` / `onFrame` 的必要性注释）
- `app/src/main/java/com/nasmusic/tv/visualizer/AudioFrame.kt` —— 音频帧契约（显示/律动双通道）
- `app/src/main/java/com/nasmusic/tv/visualizer/SpectrumContract.kt` —— 柱数 / 频段边界常量
- `app/src/main/java/com/nasmusic/tv/visualizer/SpectrumRepository.kt` —— 唯一写入方
- `app/src/main/java/com/nasmusic/tv/player/SpectrumAnalyzer.kt` —— 既有 FFT 分析器
- `app/src/main/java/com/nasmusic/tv/visualizer/VisualizerRendererFactory.kt` —— 工厂注册
- `app/src/main/java/com/nasmusic/tv/data/model/AppSettings.kt` —— `VisualizerTheme` 枚举 + `VisualQuality`
- `app/src/main/java/com/nasmusic/tv/visualizer/photo/PhotoTransitionClock.kt` —— 相位推进的禁忌（`nowMs × 系数`）
- `app/src/e51-overlay/ovl/app/src/main/java/com/nasmusic/tv/visualizer/highway/` —— **E52 原生 GL 基础设施（主要参考件）**
- `docs/technical-overview.md` §10 —— 项目开发史
