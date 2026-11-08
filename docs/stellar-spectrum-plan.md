# 频谱效果开发文档 —— 星际四季频谱

> **版本**: v1.2（2026-11-08，第二轮代码审阅后修正：接入 `RendererFx` 模板方法 + `fx.dt` 时钟 + 第四条门禁 + 渐变烘焙 + 档位门控）
> **状态**: 开发方案评审
> **关联**: `docs/globe-present.html`（视觉参考源）、`docs/technical-overview.md` §10

---

## 一、设计目标

### 1.1 定位

创建一个**独立的音乐频谱可视化效果**，在 NowPlaying 界面中作为播放背景呈现。视觉语言源自 `docs/globe-present.html`（生态地球演示页）的美学体系——星空、大气光晕、四季色调、流星——但**全部用 Compose `DrawScope` 2D 绘制**，不涉及 three-globe / WebView / 3D 地球的技术路线。

### 1.2 与既有渲染路径的边界

> ⚠️ 归因澄清：`docs/globe-present.html` 是「生态地球」**演示页**（星空 / 150 棵树 / 19 只动物 / 龙卷风 / 山脉）。
> 它**不是** E41「世界」（`VisualizerTheme.WORLD`）——后者是航空地球：Robinson 陆地 + 城市灯火 + 动态航线弧
> （`data/model/AppSettings.kt:261-294`）。本方案只借鉴演示页的**氛围语言**，与两者均无代码重叠。

| E41「世界」（`WORLD`, 3D 路线） | 本频谱效果 |
|---|---|
| WebView + three-globe 3D 球体（`WorldGlobeRenderer`） | **纯 Compose `DrawScope` 2D** |
| 球面投影 + 球面纹理 | 平面频谱条 / 波形 / 粒子 |
| WebView JS ↔ Kotlin 桥接 | Kotlin 原生渲染 |
| 地球自转动画 | 频谱数据驱动动画 |
| 航空航线 / 城市灯火 | 频段粒子 / 频谱几何 |

**核心区别**：本效果是 **2D 平面的频谱分析器**，不是 3D 场景。视觉灵感来自演示页的色彩与氛围，但完全脱离球面假设。

### 1.3 质量要求

- 逐行移植视觉要素（星空、大气、流星、季节色、昼夜），不降低效果与美观
- 帧率预算：**≥ 30 FPS**（低端设备），**≥ 45 FPS**（主流设备）
- **零新增依赖**：不引入第三方库（FFT 已由既有 `SpectrumAnalyzer` 提供）
- **必须继承 `RendererFx` 基类**（模板方法），⛔ 不直接实现 `VisualizerRenderer`
  （`renderers/RendererFx.kt:34`；其 KDoc 写明：29 个渲染器曾各写各的，"`dt` 钳制 6 种写法"）
- **性能红线**：`draw` / `drawContent` 内禁止任何对象分配（`Paint` / `Path` / `Brush` / `Color` / 数组一律提到成员变量，见 `VisualizerRenderer.kt:14`）
- **分辨率适配**：一切像素尺寸按 `ctx.minDim`（`RenderContext.kt:92`）比例化，⛔ 不用绝对 px

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

### 2.2 视觉层面（从底到顶）

| 层 | 名称 | 位置 | 内容 |
|---|---|---|---|
| L0 | **背景渐变** | y: 0~100% | 径向渐变：深空蓝 → 午夜蓝 → 近黑（随季节微调） |
| L1 | **星空** | y: 0~30% | 随机星点，三档亮度，微弱闪烁 |
| L2 | **大气光晕** | y: 80~100% | 底部径向渐变暖色雾（随季节变色） |
| L3 | **频谱条** | y: 30~80% | 主频谱，频段驱动高度，季节色着色 |
| L4 | **频谱粒子** | y: 20~90% | 频段触发的发光粒子（演示页的花/雪花/落叶 → 频谱粒子） |
| L5 | **波形线** | y: 70~95% | 实时波形，半透明叠加 |
| L6 | **流星** | 任意位置 | 间歇性流星划过（演示页的 V3 流星） |
| L7 | **昼夜光标** | 垂直方向 | 频谱中央有一条昼夜分界线（日间暖光 / 夜间冷光） |

### 2.3 色彩系统

#### 四季调色板（改编自演示页 `SEASON_PARAMS`）

每季一组 RGB 三元组，作用于背景渐变、频谱条色、大气雾色。季节按**播放时长**推进（驱动规则见 §5.1），无需用户手动切换。

| 季节 | 背景 (top→bottom) | 频谱主色 | 频谱次色 | 大气雾色 |
|---|---|---|---|---|
| **春** (0) | `#1a2f4a → #0d1b2e → #040810` | `#7acc6e` (嫩绿) | `#b4e0a0` | `#2a5a4a` |
| **夏** (1) | `#1e3a5a → #0f2035 → #050815` | `#4f9fd4` (海洋蓝) | `#8fd0f0` | `#1a3a6a` |
| **秋** (2) | `#2a1f10 → #1a140a → #0a0604` | `#e8a030` (橙金) | `#f0c060` | `#5a3a1a` |
| **冬** (3) | `#0a1a35 → #051028 → #020410` | `#6b8ec4` (冷蓝) | `#a0c0e0` | `#1a2a5a` |

#### 季节过渡

移植演示页的 **`seasonW` S 曲线** 机制：
- 每个季节周期 = **15 分钟**（≈ 4 首歌）
- 季末 30% 连续过渡到下一季
- `seasonW = smooth01(smooth01(fract(sf)))`（双重 smooth01 → 有意平顶 S 曲线）
- 背景 / 频谱 / 大气三层用**同一** transition 因子，保证同步

#### 频谱条的双色插值（频段边界对齐 `SpectrumContract`）

> ⚠️ 频段边界必须与 `SpectrumContract`（`visualizer/SpectrumContract.kt:29-34`）+ `SpectrumAnalyzer` 的 64 柱感知映射一致：
> bass = bins 0–39（20–250Hz）、mid = 40–55（250Hz–3kHz）、treble = 56–63（3k–20kHz）。

每根频谱条在 **主色 → 次色** 之间按柱索引线性插值：
- 低频段（bins 0–39，20–250Hz）→ 偏向主色
- 中频段（bins 40–55，250Hz–3kHz）→ 主色/次色混合
- 高频段（bins 56–63，3kHz–20kHz）→ 偏向次色

### 2.4 频谱几何规格

#### 频谱条

```
数量: 64 根 —— ⛔ 不可配置，恒取 frame.spectrum.size（运行时 = SpectrumContract.BAR_COUNT = 64）
  ↓ SpectrumContract.kt:5-7 明文：「渲染侧不得自行指定柱数，一律取 AudioFrame.spectrum.size」
  ↓ ⇒ 方案内不提供 barCount 参数，避免「请求 96 柱 / 实际 64 柱」错位（历史 BUG ①）
形状: 等宽矩形（宽 = (size.width - padding×2) / bars）
圆角: 顶部 2dp（= 2/1080 × ctx.minDim，⛔ 不是绝对 2px）
最大高度: size.height × 0.48
最小高度: 4dp（按 minDim 比例）
```

#### 波形线

```
采样点数: 128 —— 恒取 frame.waveform.size（SpectrumContract.WAVE_POINTS = 128）
数据: AudioFrame.waveform（-1..1 时域波形，已由 SpectrumAnalyzer 填好）
渲染: 二阶贝塞尔曲线平滑，半透明（alpha≈0.45）—— 用预分配的 Path
```

### 2.5 频谱粒子系统（改编演示页的花/雪花/落叶）

演示页有三种季节性粒子：花朵（夏）、雪花（冬）、落叶（秋）。频谱版本改为**频段驱动的类型化粒子**：

| 粒子类型 | 触发条件 | 外观 | 演示页对应 |
|---|---|---|---|
| **星尘** | 常驻（低频底噪） | 极小亮点，缓慢飘降 | 森林上方光点 / motes |
| **能量火花** | 高频能量（`treble`）越过阈值 | 上升的发光点，到顶后消散 | 夏季花朵 |
| **雪晶** | 冬季季 | 旋转的六角雪花，缓慢飘落 | 冬季雪花 |
| **落叶** | 秋季季 | 旋转的扁椭圆，左右摆动飘落 | 秋季落叶 |

**粒子生命周期**：每个粒子有 maxLife（1.5~4s），生命周期内做非线性位移 + 旋转。

**数量控制**（对象池上限，按画质档分表，见 §4.6）：
- 星尘常驻: 30 / 15 / 0（FULL / LITE / OFF）
- 能量火花: 最多同屏 50 / 25 / 0
- 季节粒子 (雪/落叶): 季节强度 × 120 / 60 / 0

**频段对齐**（⛔ 用**线性律动通道**判定强弱，不用已 gamma 压缩的显示通道）：
```
spawnRate = baseRate × (1 + frame.treble × 3.0)     // treble 为线性能量
```

---

## 三、技术架构

### 3.1 数据流（对齐仓库真实链路，⛔ 不新建分析层）

```
ExoPlayer AudioSink
  ↓
PcmTapProcessor / PcmSpectrumTap（PCM 透传采样，后台 FFT，无需 RECORD_AUDIO）
  ↓
SpectrumAnalyzer（player/SpectrumAnalyzer.kt：64 柱感知加权映射 + gamma + AGC）
  ↓ 零分配回调
SpectrumRepository（AudioFrame 唯一写入方，双缓冲）
  ↓ frame（只读单例，渲染层每帧取一次引用）
AudioFrame（spectrum[64] / waveform[128] / bass·mid·treble·energy / pulse·beat·bpm / timeMs）
  ↓
StellarSpectrumRenderer : RendererFx
  ├─ StarfieldController   ← 常驻
  ├─ SpectrumBarController ← 读 frame.spectrum
  ├─ WaveformController    ← 读 frame.waveform
  ├─ ParticleController    ← 读 treble / pulse + 季节
  ├─ MeteorScheduler       ← fx.dt 驱动
  └─ AtmosphereController  ← 季节色驱动（烘焙位图）
  ↓
Compose DrawScope 绘制管线（基类 draw 模板：时钟 → drawContent → postFx 后处理）
```

**关键**：渲染器**只读** `SpectrumRepository.frame`，绝不自己算 FFT、绝不新增录音/权限。

### 3.2 核心类设计（继承 `RendererFx`，⛔ 不直接实现 `VisualizerRenderer`）

> `RendererFx`（`renderers/RendererFx.kt:34`）是仓库的**渲染器模板方法基类**：
> `draw` / `onEnter` / `onExit` 均为 `final`，子类只实现 `drawContent(frame, ctx, fx)`；
> 基类负责 ① `FrameClock` 时钟（`fx.dt` / `fx.nowMs`）② `postFx` 后处理 ③ 资源释放（`OverlayFx.release()` / `ProceduralTexture.release()`）
> ④ `protected val rng = VisualizerRandom()`。
> ⛔ **不得覆写 `draw` / `onEnter` / `onExit`** —— `RendererBaseContractTest` 源码扫描会判违规（见 §10.2）。

```kotlin
package com.nasmusic.tv.visualizer.renderers

class StellarSpectrumRenderer : RendererFx() {

    override val theme: VisualizerTheme = VisualizerTheme.STELLAR_SPECTRUM

    /**
     * 后处理：暗角。
     * ⛔ vignetteEdge 必须给**固定身份色**（深空蓝）——默认会用封面 accent 色，
     *    换歌时整幅画面被封面染掉（PostFx.kt:167-171 记的数字雨实测）。
     * ⚠️ 字面量 0.42f 是 FxCoverageScanTest 认定「本效果走了基类后处理」的唯一依据，
     *    ⛔ 不要写成变量或 0f。
     */
    override val postFx = PostFx(vignette = 0.42f, vignetteEdge = Color(0xFF0A2E4A))

    // 模块内部渲染管线（全部构造期分配）
    private val starfield = StarfieldController()
    private val bars = SpectrumBarController()      // 柱数 = frame.spectrum.size
    private val waveform = WaveformController()
    private val particles = ParticleController()
    private val meteors = MeteorScheduler()
    private val atmosphere = AtmosphereController()
    private val seasons = SeasonController()
    private val palette = StellarPalette()

    // ⛔ 子类只实现这两个（onEnter / onExit 在基类是 final）
    override fun onEnterContent(ctx: RenderContext) {
        // 烘焙大气位图、生成星点、按 (w,h) 重建尺寸相关资源
    }

    override fun onExitContent() {
        // 释放本渲染器自有的 ImageBitmap（基类已代放 OverlayFx / ProceduralTexture）
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        // fx.dt：已含首帧 0 / 负差钳 0 / 上界 0.1s ⛔ 不要自己算差
        seasons.advance(fx.dt)
        particles.onFrame(frame, fx, seasons.currentIntensity())

        drawRect(palette.background(seasons.tint), size = size)   // L0 背景
        starfield.draw(this, seasons.tint, fx.nowMs)             // L1 星空
        atmosphere.draw(this, seasons.tint)                       // L2 大气（烘焙位图）
        bars.draw(this, frame.spectrum, palette)                 // L3 频谱条（64 柱）
        particles.draw(this)                                     // L4 粒子
        waveform.draw(this, frame.waveform, palette)             // L5 波形
        meteors.update(fx.dt)
        meteors.draw(this)                                       // L6 流星
    }
    // ⚠️ 后处理（暗角）由基类在 drawContent 之后自动施加（fx.level != OFF 时）⇒ 无需手写
}
```

**继承基类带来的四个免写项**（自写就会踩 §10.2 门禁）：

| 免写项 | 基类提供 | 自写会怎样 |
|---|---|---|
| `dt` 时钟 | `fx.dt`（`FrameClock`，`RendererFx.kt:195-213`） | 重复实现已被负向自证保护的问题；首帧哨兵写法易错 |
| 后处理 | `postFx` + `applyPostFx` | G7 判「未覆盖后处理」⇒ 门禁挂 |
| 资源释放 | `onExit` 里 `OverlayFx.release()` / `ProceduralTexture.release()` | 纹理泄漏（native 堆，API 22–25 尤甚） |
| 随机源 | `protected val rng` | 契约门禁 ③ 判违规 |

### 3.3 音频帧契约（对齐 `AudioFrame`，⛔ 不自定义帧结构）

> `AudioFrame`（`visualizer/AudioFrame.kt:16-58`）是分析层唯一输出契约，由 `SpectrumRepository` 独占写入，渲染层只读、不得跨帧持有。

```kotlin
class AudioFrame(barCount: Int, wavePoints: Int) {
    // ── 显示通道（已 gamma ^0.75 压缩）──────────────
    val spectrum: FloatArray    // 64，0..1，直接用于柱长
    val waveform: FloatArray    // 128，-1..1 时域波形
    // ── 律动通道（线性，未压缩）────────────────────
    var bass/mid/treble: Float  // 20–250 / 250–3k / 3k–20k 线性
    var energy/sectionEnergy: Float
    var bassRaw: Float          // 未峰值跟随的原始低频（需强弱差异时用）
    // ── 节拍 ──
    var beat: Boolean; var pulse: Float; var bpm: Float
    // ── 元信息 ──
    var timeMs: Long; var seq: Long
}
```

**两个通道的用途（铁律，`AudioFrame.kt:11-14`）**：

| 用途 | 用哪个通道 | 原因 |
|---|---|---|
| 柱高 / 波形 / 视觉亮度 | `spectrum` / `waveform`（显示通道） | 小信号可见 |
| 强弱判定 / 粒子触发 / 律动 | `bass` / `mid` / `treble` / `pulse`（线性律动通道） | gamma 会把轻/重差异压平（历史 BUG ⑨-b） |

**FFT 参数不由本方案决定**：既有 `SpectrumAnalyzer` 使用 44100Hz、64 柱感知加权映射、`DISPLAY_GAMMA=0.75`、`AGC_DECAY=0.995`。**⛔ 方案内不出现 FFT size / window / overlap 等参数**。

### 3.4 平滑处理（避免重复加工）

> ⚠️ 输入 `frame.spectrum` **已是** gamma 压缩 + AGC 归一化的显示通道；再叠加大窗口平滑会造成
> 「二次压缩 ⇒ 动态被抹平、反应变钝」。因此：

- **默认不平滑**：直接使用 `frame.spectrum`（分析层已做输出稳定）。
- 若个别柱仍有轻微抖动，**只允许**加一层**极轻的 EMA**（`smooth = mix(smooth, raw, 0.35)`），
  且必须在类 KDoc 注明「输入已压缩，此处仅做时间去抖，不做幅度压缩」。
- **动态强弱**（粒子触发阈值、火花生成）一律走 §3.3 的线性律动通道，⛔ 不要在显示通道上判定强弱。

### 3.5 渲染管线（与 `VisualizerRenderer` 集成）

#### 3.5.1 工厂注册（`VisualizerRendererFactory.createEnabled` 的 `when` 分支）

> 工厂真实签名：`fun create(theme: VisualizerTheme, context: Context): VisualizerRenderer`
> （`VisualizerRendererFactory.kt:57`）。本渲染器零外部资产 ⇒ **无参构造**（不接收 Context）。

```kotlin
private fun createEnabled(theme: VisualizerTheme, context: Context): VisualizerRenderer = when (theme) {
    // ... 现有分支 ...
    VisualizerTheme.STELLAR_SPECTRUM -> StellarSpectrumRenderer()
}
```

#### 3.5.2 主题枚举（对齐 `AppSettings.kt:184-192` 的五参数声明）

> ⛔ **不是** `enum class VisualizerTheme(val id: String)`。真实声明为：
> `displayNameRes(@StringRes) / displayName / tier / ordinalLabel / needsParticleBudget`。

```kotlin
enum class VisualizerTheme(
    @StringRes val displayNameRes: Int,
    val displayName: String,
    val tier: Tier,
    val ordinalLabel: String,
    val needsParticleBudget: Boolean = false
) {
    // ... 现有项 ...
    STELLAR_SPECTRUM(
        R.string.visualizer_theme_stellar_spectrum,
        "星际四季频谱",
        Tier.ADV,
        "54",                           // 下一个未占用两位序号 —— 落地时以仓库实际枚举为准
        needsParticleBudget = false,    // ⛔ 不读 maxParticles（见下）
    ),
}
```

**`needsParticleBudget = false` 的理由（⛔ 不要改成 `true`）**：
`VisualQuality.supports()` 对 ADV 的门槛是 `!needsParticleBudget || maxParticles > 0`（`AppSettings.kt:648`），
而 `LOW` 档 `maxParticles = 0`（`AppSettings.kt:637`）⇒ 标 `true` 会让本效果**在 LOW 档不可选**
（"能渲染却不可选，切走即永久回不来"那类缺陷）。
本方案的粒子数量按 `fx.level` 三档表调（§4.6），**不读** `ctx.quality.maxParticles` ⇒ 标 `false` 才是**可核对的事实**，
与 E42/E43/E47/E48/E49/E50 的既有做法一致。

**落地时必须同步的枚举配套项**：
- **字符串资源**：`R.string.visualizer_theme_stellar_spectrum` 必须**同时**存在于
  `app/src/main/res/values/strings.xml` 与 `values-en/strings.xml`（缺一份 = 运行时崩溃，见 §10.2 门禁 G1）。
- **序号 `ordinalLabel`**：取仓库当前未占用的两位序号（E51=51 / E52=52 仅在私有分支、E50=50、E53=53 已占用），
  ⛔ **不要凭空写死**，落地时读 `VisualizerTheme.entries` 确认。
- **`fromKey` 兼容**：枚举名即持久化 key，无需额外映射。

#### 3.5.3 主题数断言（不写死数字）

> ⛔ 不写「27 → 28」这类硬编码：E23 已从独立主题退役、E51/E52 分支门控、E53 已加入，
> 主题总数会随分支变化。落地时**同步既有门禁**即可（见 §10.2），不要在本方案里钉死总数。

---

## 四、各组件详细规格

### 4.1 星空（Starfield）

**移植依据**：演示页原装 `stars` 独立随机生成。

```
星点数量: 200 颗（按 fx.level 三档：200 / 100 / 0）
星点大小: 0.5~2.0 dp（按 minDim 比例化，⛔ 不用绝对 px）
亮度档位: 三档
  tier-3: 1.5~2.0dp, alpha=0.9（亮星，约 15%）
  tier-2: 0.8~1.2dp, alpha=0.6（中等，约 35%）
  tier-1: 0.5~0.7dp, alpha=0.3（暗星，约 50%）
闪烁: tier-3 以 3~5 秒周期正弦闪烁，alpha 在 0.6~1.0 之间变化
颜色: 纯白 #FFFFFF 或微暖 #FFF8EE（约 10% 暖星）
生成时机: onEnterContent 一次性生成坐标（此后每帧只读）
```

**绘制（⛔ 一次 `drawPoints` 画不完 —— 它共用一个 `strokeWidth` 与一个 `color`）**：

| 档位 | 画法 | 说明 |
|---|---|---|
| tier-1 / tier-2 | 各一次 `drawPoints(points, PointMode.Points, strokeWidth = 档位尺寸, cap = StrokeCap.Round, color = 档位色)` | 同档同尺寸 ⇒ 可合批；两档 = 2 次调用 |
| tier-3（约 30 颗） | **逐颗 `drawCircle`** | 需要**逐星 alpha 闪烁** ⇒ 合批会丢闪烁 |

⇒ 每帧 3 次 draw 调用（tier-1 / tier-2 合批 + tier-3 逐颗），远优于 200 次 `drawCircle`。
⚠️ `drawPoints` 的 `points` 是 `List<Offset>` ⇒ **预分配** `Offset` 列表，禁止每帧构造。

**随机源**：用基类 `protected val rng`（`VisualizerRandom`，零分配）。
⛔ **不要自建常量种子**：仓库明文「不能给所有渲染器同一个常量种子 —— 那样每个渲染器的首帧图案会完全一致
（交叉淡入时会看到两层图案重合），且每次进入同一效果都重复同一套图案」（`VisualizerRandom.kt:17-19`）。
若单测需要可复现，**另起名**注入种子（如 `private val starRng = VisualizerRandom(20261108u)`），
⛔ **不得命名为 `rng`**（`RendererBaseContractTest` ③ 判违规）。

### 4.2 频谱条（Spectrum Bars）

**规格**：

```
Band count: 64 —— ⛔ 恒取 frame.spectrum.size（不可配置，见 §2.4）
Bar width:  动态计算 = (size.width - 2×margin) / bars
Bar gap:    2dp（按 minDim 比例化）
Max height: size.height × 0.48
Min height: 4dp（按 minDim 比例化）
Corner:     顶部圆角 2dp（按 minDim 比例化）

频段划分（对齐 SpectrumContract）:
  bass   bins 0–39   (20–250Hz)
  mid    bins 40–55  (250Hz–3kHz)
  treble bins 56–63  (3kHz–20kHz)

视觉优化:
  · 底部连成一线（有 minimum height）
  · 顶部圆角
  · 消除相邻帧跳变（见 §3.4：分析层已输出稳定，默认不再叠加）

颜色: 见 §2.3 双色插值
背景: 半透明深色板（0.05 alpha 黑矩形覆盖在频谱区）
```

**柱宽计算**（仅用于几何换算，非决定柱数）：
```kotlin
val barW = size.width / bars   // bars = frame.spectrum.size
```

### 4.3 波形线（Waveform）

```
Sample points: 128 —— 恒取 frame.waveform.size
Smoothing: 二次贝塞尔曲线（预分配 Path，drawContent 内不 new Path）
Color: 半透明 (alpha≈0.45) 的白色/季节次色
Position: 频谱条下方，y = size.height × 0.82
Amplitude: ±(size.height × 0.04) （振幅小，作为辅层）
```

### 4.4 大气光晕（Atmosphere）

**移植依据**：演示页的 `#fx` canvas 大气光晕（径向渐变 Sprite）。

```
形状: 底部径向渐变
尺寸: 宽 = size.width，高 = size.height × 0.35
锚点: 底部中心 (cx=size.width/2, cy=size.height)
渐变: 中心 0 alpha → 80% 处最大 alpha → 边缘 0 alpha
颜色: 随季节变化（见 §2.3 大气雾色表）
动画: 极慢呼吸式（±10% alpha 周期 8s）
```

⚠️ **⛔ 不可用逐帧重建的 `Brush`**：季节是连续插值的 ⇒ 渐变颜色每帧都变 ⇒
逐帧 `Brush.radialGradient(...)` = 每帧分配（破 `VisualizerRenderer.kt:14` 性能红线）；
若只在 `onEnter` 建一次，则颜色无法随季节变。

**正确做法（仓库既有模式）**：**烘焙位图 + 量化季节**
1. 按四季各烘一张全屏大气渐变 `ImageBitmap`（4 张，ARGB）；
2. 用 `ProceduralTexture.ensureFullscreenOnly(id, w, h)`（`visualizer/fx/ProceduralTexture.kt:223`）托管，
   尺寸变化时按 `(w, h)` 重建（`SizeCache` 双键缓存，`RendererFx.kt:238`）；
3. 季节插值到**最近的 4 档之一**（或两张位图按 `alpha` 叠化）⇒ 每帧 1~2 次 blit，零分配；
4. 释放走 `ProceduralTexture.release()`（基类 `onExit` 已代劳）**或**在 `onExitContent` 里释放自烘位图。

> 参照：E46/E47/E48/E49/E50 的「grade 位图」同款做法（1 blit / 1.000 屏）。

### 4.5 流星（Meteors）

**移植依据**：演示页的 V3 流星。

```
数量: 0~2 颗同屏
随机触发:
  · 基础间隔: 4~9 秒随机间隔（由 fx.dt 累加计时）
  · 每次触发 1 颗
生命周期: 约 2.5 秒（淡入 0.3s → 持续 2s → 淡出 0.2s）
轨迹:
  · 从右上方（80% 宽，10% 高）向左下方（20% 宽，40% 高）
  · 长度约 size.width × 0.35
  · 速度 ≈ 屏幕宽度 / 2.5 秒
外观:
  · 头部: 明亮白/暖色，alpha 1.0，半径 1.5dp（按 minDim 比例化）
  · 尾部: 渐变尾巴，alpha 1.0 → 0，宽度 1.5dp → 0.3dp
季节影响（颜色）:
  · 春: 偏绿   夏: 偏白   秋: 偏橙   冬: 偏蓝
```

**随机源**：用基类 `protected val rng`（⛔ 不自建常量种子，理由同 §4.1）。

### 4.6 粒子系统（Particle System）

**架构**：对象池 + 每帧更新；绘制走 `DrawScope`。

```kotlin
class ParticleController {
    private val pool = Array(MAX_POOL) { Particle() }   // MAX_POOL=200，构造期分配
    private val active = IntArray(MAX_POOL)             // 活跃索引（避免集合分配）
    // ⛔ 随机源：注入基类 rng（构造时传入），不新建字段

    fun onFrame(frame: AudioFrame, fx: FxFrame, seasonIntensity: Float) { ... }
    fun draw(scope: DrawScope) { ... }                  // ⛔ 不是 draw(canvas: Canvas)
}
```

**粒子类型定义**：
```kotlin
enum class ParticleType {
    STARDUST,      // 星尘：常驻，低频
    SPARK,         // 能量火花：高频触发
    SNOWFLAKE,     // 雪花：冬季
    LEAF           // 落叶：秋季
}
```

**各类型运动学**（速度按 `minDim` 比例化）：

| 类型 | 速度 | 加速度 | 旋转 | 生命周期 |
|---|---|---|---|---|
| STARDUST | y: +15 dp/s（缓慢下落） | 无 | 无 | 8~12s |
| SPARK | y: -60~-100 dp/s（上升） | 重力衰减 20 dp/s² | 闪烁 | 0.8~2.0s |
| SNOWFLAKE | x: sin 摆动 ±1; y: +20~+40 dp/s | 微风 | 自转 1~2 rad/s | 4~8s |
| LEAF | x: sin ×2; y: +30~+50 dp/s | 无 | 3~5 rad/s | 5~9s |

**数量分档（按 `fx.level`，⛔ 不读 `ctx.quality.maxParticles`）**：

| `fx.level` | 星尘 | 火花上限 | 季节粒子 |
|---|---|---|---|
| `FULL` | 30 | 50 | 强度 × 120 |
| `LITE` | 15 | 25 | 强度 × 60 |
| `OFF` | 0 | 0 | 0 |

**频段触发逻辑**（⛔ 用线性律动通道）：
```
SPARK 生成阈值: frame.treble > 0.75（线性）
SPARK 生成频率: 基础 1 个/s + 阈值命中次数 × 3
冬季雪花密度: seasonIntensity × 0.3 个/帧
秋季落叶密度: seasonIntensity × 0.4 个/帧
```

> `fx.level` 由 `FxBudget.of(ctx.quality)` 推出（`RendererFx.kt:72`；`FxLevel.kt:25`：HIGH→FULL / MEDIUM→LITE / LOW→OFF）。

---

## 五、动画系统

### 5.1 季节推进（单一驱动规则）

> ⛔ **唯一驱动源 = `fx.dt` 累加**。季节相位由「本效果已播放时长」推进，
> **不读音轨进度**（切歌/拖动进度会让相位跳变），**不用 `nowMs × 系数`**。

| 参数 | 值 |
|---|---|
| 驱动 | `fx.dt` 累加 ⇒ `seasonElapsedSec += fx.dt` |
| 周期 | 15 分钟/季（≈ 4 首歌）⇒ `sf = (seasonElapsedSec % 900) / 900` |
| 季内占比 | 前 70% 保持当前季，末 30% 过渡 |
| 过渡曲线 | 双重 `smoothstep`（平顶 S 曲线） |
| 颜色插值 | 所有颜色参数同一因子 |
| 切歌 | ⛔ **不重置**季节相位（连续氛围不因换歌断裂）；若确需按歌重置，用 `ctx.songId` 变化时归零 `seasonElapsedSec`（`ctx.songId` 未被契约门禁禁止，`ctx.nowMs` 才是） |

### 5.2 动画帧率控制与画质分档

- 目标帧率：**60 fps**；最低帧率：**30 fps**（低端设备）
- **画质档**：一切数量按 `fx.level` 三档表调（`FxLevel.OFF` / `LITE` / `FULL`）——
  `RendererFx.draw` 每帧自动设 `fx.level = FxBudget.of(ctx.quality)`（`RendererFx.kt:72`）
- 粒子/星点/季节粒子的数量表见 §2.5 与 §4.6
- **运行时自适应**（可选）：若连续多帧 `fx.dt` 偏大（帧率不足）⇒ 在本渲染器内部再降一档数量
  （自维护一个 `degraded` 标志，不改 `fx.level`）

### 5.3 昼夜交替

**概念**：一个**昼夜相位**在频谱上缓慢摆动。

```
驱动: dayPhase += fx.dt / 8f（⛔ 不用 nowMs × 系数）
白天: 频谱条整体提亮 15%，背景顶部偏暖，大气雾色偏暖
夜晚: 频谱条保持不变，背景顶部加深，大气雾色偏冷，添加微光点
过渡: 约 8 秒的正弦过渡
```

> ⚠️ **相位累加禁忌**（`photo/PhotoTransitionClock.kt:17` 明文）：
> ⛔ 绝不用 `nowMs × 系数` 推进相位 —— `nowMs` 是 1.7e12 量级，乘上任何实时系数会把每帧抖动放大千万倍 → 画面闪现跳档。
> 一律 `phase += dt` 累加。

---

## 六、性能预算

### 6.1 每帧成本估算（中端设备 60fps）

| 操作 | 预估 draw 调用 | 估算时间 |
|---|---|---|
| 背景填充（drawRect） | 1 | ~0.5ms |
| 星空（2 次合批 + 30 颗逐颗） | ~33 | ~1.5ms |
| 大气（烘焙位图 blit） | 1~2 | ~0.5ms |
| 频谱条 64 根 | 64 | ~1.2ms |
| 波形线 | 1 | ~0.2ms |
| 粒子 ~30 个 | ~30 | ~1.0ms |
| 流星 0~2 | ~1 | ~0.1ms |
| 后处理暗角（基类施加） | 1 | ~0.3ms |
| **合计** | ~130 | **≈ 5~6ms** |

### 6.2 内存预估

| 对象 | 数量 | 内存 |
|---|---|---|
| 星点 offsets（预分配 `Offset` 列表） | 200 | ~6 KB |
| 频谱条缓冲 | 64 × 若干 floats | ~1.3 KB |
| 粒子对象池 | 200 instances | ~40 KB |
| 大气烘焙位图 | 4 × 全屏 ARGB | ⚠️ 见下 |
| 波形 Path | 1 | ~1 KB |

> ⚠️ **4 张全屏 ARGB 位图在 1080p ≈ 4 × 8.3MB = 33MB**（native 堆）⇒ 太大。
> **优化**：只烘**一张**大气渐变底（颜色用 `ColorFilter` / `tint` 随季节变），或把季节量化成 4 档后
> 只保留**当前档 + 下一档**两张并滚动替换，或用 `ProceduralTexture` 的分档复用机制。
> ⛔ 落地时必须实测 native 内存占用（`OverlayFx.release()` / `ProceduralTexture.release()` 是唯一的自愈路径）。

### 6.3 优化策略

1. **星空分档合批**：tier-1 / tier-2 各一次 `drawPoints`，tier-3 逐颗（见 §4.1）。
2. **频谱条路径**：64 条合并进一个预分配 `Path`（每帧 `reset()` 复用，不 `new`）。
3. **大气位图**：1 blit / 屏，⛔ 不逐帧建 `Brush`（见 §4.4）。
4. **零分配红线**：`drawContent` 内不 `new Paint/Path/Brush/Color/Offset`，全部提为成员（`VisualizerRenderer.kt:14`）。
5. **降级方案**（按 `fx.level`）：
   - `LITE`: 星空合批 + 频谱条纯矩形 + 粒子减半 + 无流星
   - `OFF`: 仅频谱条 + 背景

---

## 七、频谱接入点与数据来源

### 7.1 数据链路（已存在，⛔ 不改）

```
ExoPlayer AudioSink
  → PcmTapProcessor / PcmSpectrumTap（PCM 透传，后台 FFT）
  → SpectrumAnalyzer（64 柱感知加权 + gamma + AGC）
  → SpectrumRepository（双缓冲，唯一写入方）
  → AudioFrame（渲染层只读）
```

渲染器**只读** `SpectrumRepository.frame`（`val frame: AudioFrame`，`SpectrumRepository.kt:40`）。
现有 27 套渲染器全部这样接入，本效果同源。

### 7.2 数据源优先级（⛔ 只有一条）

1. **`SpectrumRepository.frame`**（唯一）
2. ~~ExoPlayer AudioFrameCallback~~ —— ⛔ 不存在该通道，不要新增
3. ~~Visualizer API~~ —— ⛔ 已随 v2.37.6 移除（`SpectrumAnalyzer.kt:14`）；且项目明文「不用 AudioRecord/Visualizer」（`AppSettings.kt:290-292`）
4. ~~自建 FFT~~ —— ⛔ 违反「复用现有分析层」

### 7.3 通道选择规则（重申 §3.3 铁律）

```
柱高 / 波形     ← spectrum / waveform（显示通道，已 gamma）
强弱 / 粒子阈值 ← bass / mid / treble / pulse（线性律动通道）
```

---

## 八、兼容性与约束

### 8.1 架构约束

- **不引入 three-globe / WebView**（那是 E41 `WORLD` 的路线，本效果为独立 2D）
- **不引入新第三方依赖**（FFT 由既有 `SpectrumAnalyzer` 提供，无需 FFTW/KissFFT）
- **纯 Kotlin（Compose DrawScope）**，不混入 JS / WASM
- **必须继承 `RendererFx`**：只实现 `drawContent(frame, ctx, fx)` + `onEnterContent` / `onExitContent`
  - ⛔ 不得覆写 `draw` / `onEnter` / `onExit`（基类 `final`）
  - ⛔ 不得读 `ctx.nowMs`（用 `fx.nowMs`）
  - ⛔ 不得自建 `private val rng = VisualizerRandom()`（用基类 `protected val rng`）
  - ⛔ 不得自建 `dt` 累加器（用 `fx.dt`）
- **⛔ 不读 `ctx.quality.maxParticles`**（数量按 `fx.level` 分档 ⇒ 枚举标 `needsParticleBudget = false`）

### 8.2 设备兼容

| API Level | 能力 | 适配 |
|---|---|---|
| 22 (minSdk) | Compose DrawScope 全基础图元、`drawPoints`、`ImageBitmap` | 可用 |
| 24+ | 更稳的批量绘制性能 | 优化路径 |

> ⚠️ 本效果走 Compose `DrawScope`（与其余 20+ 套 Canvas 渲染器一致），不是 `android.graphics.Canvas`。

### 8.3 无障碍

- 频谱为纯视觉（非交互），无需 `contentDescription`
- 帧率自适应降级为自动行为
- 闪烁频率 < 3Hz（防晕动症）

---

## 九、开发里程碑

### Phase 0: 骨架 + 门禁接入（1~2 天）
- [ ] 创建 `StellarSpectrumRenderer : RendererFx()`，实现 `drawContent(frame, ctx, fx)`
- [ ] `onEnterContent` / `onExitContent` 分配与释放资源
- [ ] 枚举新增 `STELLAR_SPECTRUM`（**五参数** + 两份 `strings.xml` key + `needsParticleBudget = false`）
- [ ] `VisualizerRendererFactory.createEnabled` 加 `when` 分支
- [ ] **登记 §10.2 的四条源码扫描门禁**（`RendererBaseContractTest` / `FxCoverageScanTest` / `ParticleBudgetGateTest` / `VisualizerThemeStringsGateTest`）
- [ ] 验证：`./gradlew.bat testDebugUnitTest` 通过 + 不 crash

### Phase 1: 频谱核心（2~3 天）
- [ ] `frame.spectrum` → 64 频谱条（宽度均分 + 圆角 + 间隙，柱数取 `.size`）
- [ ] 基础颜色（双色插值）+ 波形线（`frame.waveform`）
- [ ] `fx.level` 分档接入
- [ ] 验证：播放音乐时频谱条有效响应

### Phase 2: 氛围层（2~3 天）
- [ ] 星空（`onEnterContent` 生成坐标 + 分档合批 + tier-3 闪烁）
- [ ] 大气渐变（**烘焙位图**，⛔ 不用逐帧 Brush）
- [ ] 背景径向渐变
- [ ] 验证：静态帧美观

### Phase 3: 季节 + 粒子（3~5 天）
- [ ] `SeasonController`（`fx.dt` 累加 + S 曲线 + 双色插值）
- [ ] 四季调色板映射
- [ ] 粒子系统：星尘 + 火花（用 `treble` 判定）
- [ ] 冬季雪花 / 秋季落叶
- [ ] 验证：季节切换视觉效果

### Phase 4: 流星 + 昼夜（1~2 天）
- [ ] 流星调度器（`fx.dt` 累加计时）
- [ ] 昼夜相位（`fx.dt` 累加，⛔ 不用 `nowMs × 系数`）
- [ ] 验证：流星触发 + 昼夜交替

### Phase 5: 性能 + 收尾（2~3 天）
- [ ] draw call 优化（`drawPoints` 分档合批；`Path.reset()` 复用）
- [ ] native 内存实测（大气位图数量与尺寸）
- [ ] 帧率降级验证（`fx.level` 三档）
- [ ] 单测（见 §10.1）
- [ ] lint + 四条门禁全绿
- [ ] 技术文档 §10 归档

**总计**: 约 11~17 个工作日

---

## 十、测试计划

### 10.1 单元测试

| 测试类 | 测什么 |
|---|---|
| `StellarPaletteTest` | 季节插值数学正确（双重 smoothstep 边界） |
| `StarfieldControllerTest` | 星点数量按 `fx.level` 分档、坐标生成在 `onEnterContent` 一次性完成 |
| `ParticleControllerTest` | 对象池不泄漏、pool 正确回收、数量分档正确 |
| `SeasonControllerTest` | 季节折叠、过渡带、边界值、`fx.dt` 累加语义 |
| `SpectrumBarControllerTest` | 柱数恒等于 `frame.spectrum.size`、柱宽均分正确 |

> ⚠️ 不单测平滑窗口：默认不平滑（§3.4），输入已由 `SpectrumAnalyzer` 稳定。
> ⚠️ 不单测 `dt`：时钟由基类 `FrameClock` 提供，`RendererBaseContractTest` 已覆盖其 5 条行为 + 负向自证。

### 10.2 既有门禁（⛔ 新增主题必须全部通过）

| 门禁 | 文件 | 新主题要做什么 |
|---|---|---|
| **G12 `RendererBaseContractTest`** | `app/src/test/.../renderers/RendererBaseContractTest.kt` | 扫 `renderers/`+`photo/` 全部 `RendererFx` 子类，判三条违规：① 覆写 `draw`/`onEnter`/`onExit` ② 用 `ctx.nowMs` ③ 自建 `private val rng = VisualizerRandom()`。本效果必须**零违规**（只实现 `drawContent`，随机走基类 `rng`） |
| **G7 `FxCoverageScanTest`** | `app/src/test/.../visualizer/fx/FxCoverageScanTest.kt` | 渲染器必须登记进 `covered`（继承 `RendererFx` ⇒ `override val postFx` 至少一项 > 0，**字面量**）或 `exempt`（写明理由）。本方案 §3.2 已给 `PostFx(vignette = 0.42f, …)` ⇒ 落 `covered` |
| **`ParticleBudgetGateTest`** | `app/src/test/.../data/model/ParticleBudgetGateTest.kt` | 扫描渲染器是否读 `ctx.quality.maxParticles`，与枚举标注逐一对比。本效果**不读** ⇒ 枚举必须标 `needsParticleBudget = false` |
| **G1 `VisualizerThemeStringsGateTest`** | `app/src/test/.../data/model/VisualizerThemeStringsGateTest.kt` | `R.string.visualizer_theme_stellar_spectrum` 必须**同时**存在于 `values/strings.xml` 与 `values-en/strings.xml` |

> 这四个门禁都会**源码扫描** `renderers/` + 枚举声明 ⇒ 只加类/枚举而不登记 = 门禁直接失败。
> ⚠️ `postFx` 的数值必须是**字面量**（`FxCoverageScanTest` 用正则抽数字），⛔ 不要抽成常量。

### 10.3 集成验证

- [ ] Release 包中能跑（APK 无异常增量）
- [ ] API 22 设备（创维 5.1.1）有帧率
- [ ] 频谱与播放同步（无延迟 > 100ms）
- [ ] 后台 → 前台切换时频谱恢复正常（`FrameClock.reset()` 由基类 `onEnter` 触发）
- [ ] 暂停 → 恢复时 `dt` 不跳变（`FrameClock` 上界钳制 0.1s）
- [ ] 换歌时暗角不随封面变色（`vignetteEdge` 固定色）
- [ ] 省电模式降级

### 10.4 性能基线

- 中端机 (API 24)：> 45fps
- 低端机 (API 22)：> 30fps
- draw call 总数（合批后）≤ 140 的同时在 **60fps** 下运行
- native 内存增量 ≤ 20MB（大气位图，实测为准）

---

## 十一、验收标准

### 11.1 视觉验收 ✅

- [ ] 频谱条对音乐有效响应（不仅仅是常量动画）
- [ ] 星空 / 大气 / 流星 / 粒子构成完整的「星际四季」氛围
- [ ] 四季色调变化一眼可见（色相差异 ±20%）
- [ ] 整体美观度不输 `globe-present.html`（比例缩放后）

### 11.2 性能验收 ✅

- [ ] 60fps 低端机 / 中端机
- [ ] 无 ANR、无跳变（`drawContent` 内零分配）
- [ ] APK 无新增依赖
- [ ] 切后台再回来相位不跳（`FrameClock` 钳制生效）

### 11.3 架构验收 ✅

- [ ] 不使用 three-globe / WebView
- [ ] 复用现有 `SpectrumAnalyzer`（⛔ 不另起 FFT、不用 Visualizer、不新增权限）
- [ ] **继承 `RendererFx`**，只实现 `drawContent`，零契约违规
- [ ] 通过 §10.2 四条门禁
- [ ] 技术文档 §10 记录

---

## 十二、参考资料

- `docs/globe-present.html` —— 视觉参考源（星空 / 大气 / 季节 / 流星 / 昼夜）
- `docs/technical-overview.md` §10 —— 项目开发史
- `app/src/main/java/com/nasmusic/tv/visualizer/renderers/RendererFx.kt` —— **渲染器基类**（`drawContent` / `postFx` / `FrameClock` / `fx`）
- `app/src/main/java/com/nasmusic/tv/visualizer/VisualizerRenderer.kt` —— 渲染器接口
- `app/src/main/java/com/nasmusic/tv/visualizer/AudioFrame.kt` —— 音频帧契约（显示/律动双通道）
- `app/src/main/java/com/nasmusic/tv/visualizer/SpectrumContract.kt` —— 柱数 / 频段边界常量
- `app/src/main/java/com/nasmusic/tv/visualizer/SpectrumRepository.kt` —— 唯一写入方
- `app/src/main/java/com/nasmusic/tv/player/SpectrumAnalyzer.kt` —— 既有 FFT 分析器
- `app/src/main/java/com/nasmusic/tv/visualizer/VisualizerRendererFactory.kt` —— 工厂注册
- `app/src/main/java/com/nasmusic/tv/visualizer/RenderContext.kt` —— `quality` / `canvasSize` / `minDim` / `songId`
- `app/src/main/java/com/nasmusic/tv/visualizer/VisualizerRandom.kt` —— 随机源（种子策略禁忌）
- `app/src/main/java/com/nasmusic/tv/visualizer/fx/ProceduralTexture.kt` —— 全屏烘焙纹理托管
- `app/src/main/java/com/nasmusic/tv/data/model/AppSettings.kt` —— `VisualizerTheme` 枚举 + `VisualQuality`
- `app/src/main/java/com/nasmusic/tv/visualizer/renderers/` —— 渲染器实现先例
