# 频谱效果开发文档 —— 星际四季频谱

> **版本**: v1.0（2026-11-08）
> **状态**: 开发方案评审
> **关联**: `docs/globe-present.html`（视觉参考源）、`docs/technical-overview.md` §10

---

## 一、设计目标

### 1.1 定位

创建一个**独立的音乐频谱可视化效果**，在 NowPlaying 界面中作为播放背景呈现。视觉语言源自 `docs/globe-present.html`（生态地球）的美学体系——星空、大气光晕、四季色调、流星——但**全部用 Android 端 2D Canvas 实现**，不涉及 three-globe / WebView / 3D 地球的技术路线。

### 1.2 与「世界」效果的边界

| 「世界」效果（E41） | 本频谱效果 |
|---|---|
| WebView + three-globe 3D 球体 | **纯 2D Canvas**（`android.graphics.Canvas`） |
| 球面投影 + 球面纹理 | 平面频谱条 / 波形 / 粒子 |
| WebView JS ↔ Kotlin 桥接 | Kotlin 原生渲染 |
| 地球自转动画 | 频谱数据驱动动画 |
| 150 棵树 / 19 只动物 | 频段粒子 / 频谱几何 |

**核心区别**：本效果是 **2D 平面的频谱分析器**，不是 3D 场景。视觉灵感来自 globe-present.html 的色彩与氛围，但完全脱离球面假设。

### 1.3 质量要求

- 逐行移植视觉要素（星空、大气、流星、季节色、昼夜），不降低效果与美观
- 帧率预算：**≥ 30 FPS**（低端设备），**≥ 45 FPS**（主流设备）
- 零外部依赖：不引入新的第三方库（除非已有依赖满足需求）
- 与现有 VisualizerRenderer 框架集成（`VisualizerRendererFactory.create(theme, context)`）

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
| L1 | **星空** | y: 0~30% | LCG 种子随机星点，三档亮度，微弱闪烁 |
| L2 | **大气光晕** | y: 80~100% | 底部径向渐变暖色雾（随季节变色） |
| L3 | **频谱条** | y: 30~80% | 主频谱，频段驱动高度，季节色着色 |
| L4 | **频谱粒子** | y: 20~90% | 高频触发的发光粒子（原 globe 的花/雪花/落叶 → 频谱粒子） |
| L5 | **波形线** | y: 70~95% | 实时波形，半透明叠加 |
| L6 | **流星** | 任意位置 | 间歇性流星划过（原 globe 的 V3 流星） |
| L7 | **昼夜光标** | 垂直方向 | 频谱中央有一条昼夜分界线（日间暖光 / 夜间冷光） |

### 2.3 色彩系统

#### 四季调色板（移植自 `SEASON_PARAMS`）

每季一组 RGB 三元组，作用于背景渐变、频谱条色、大气雾色。季节按 **歌曲时长比例** 或 **播放列表索引** 映射，无需用户手动切换。

| 季节 | 背景 (top→bottom) | 频谱主色 | 频谱次色 | 大气雾色 |
|---|---|---|---|---|
| **春** (0) | `#1a2f4a → #0d1b2e → #040810` | `#7acc6e` (嫩绿) | `#b4e0a0` | `#2a5a4a` |
| **夏** (1) | `#1e3a5a → #0f2035 → #050815` | `#4f9fd4` (海洋蓝) | `#8fd0f0` | `#1a3a6a` |
| **秋** (2) | `#2a1f10 → #1a140a → #0a0604` | `#e8a030` (橙金) | `#f0c060` | `#5a3a1a` |
| **冬** (3) | `#0a1a35 → #051028 → #020410` | `#6b8ec4` (冷蓝) | `#a0c0e0` | `#1a2a5a` |

#### 季节过渡

移植 globe-present.html 的 **`seasonW` S 曲线** 机制：
- 每个季节周期 = **4 首歌**（一首歌≈3~5min ≈ 12~20min 一个季节）
- 季末 30% 连续过渡到下一季
- `seasonW = smooth01(smooth01(fract(sf)))`（双重 smooth01 → 有意平顶 S 曲线）
- 背景 / 频谱 / 大气三层用**同一** transition 因子，保证同步

#### 频谱条的双色插值

每根频谱条在 **主色 → 次色** 之间按频段能量线性插值：
- 低频段 (20~200Hz) → 偏向主色
- 中频段 (200~2000Hz) → 主色/次色混合
- 高频段 (2000~20000Hz) → 偏向次色

### 2.4 频谱几何规格

#### 频谱条

```
数量: 64 根（默认） / 可配 32~128
  ↓ 对应 FFT bin 分组: 64 bins ÷ 64 bars = 1 bin/bar
  ↓ 或合并高频箱到对数刻度: 低频 1:1, 高频 2~4 bin/bar
形状: 等宽矩形（宽 = (screenW - padding×2) / barCount）
圆角: 顶部 2px 圆角（使用 Canvas drawRoundRect）
最大高度: screenH × 0.50（留出顶部空间）
最小高度: 2px（保持底边连成一线）
```

#### 波形线

```
采样点数: 128
采样率: 跟随 Media3 ExoPlayer 的 AudioTrack 数据
渲染: 二阶贝塞尔曲线平滑，半透明（alpha=0.45）
```

### 2.5 频谱粒子系统（移植原 globe 的花/雪花/落叶）

原 globe-present.html 中有三种季节性粒子：花朵（夏）、雪花（冬）、落叶（秋）。频谱版本改为**频段驱动的类型化粒子**：

| 粒子类型 | 触发条件 | 外观 | 原 globe 对应 |
|---|---|---|---|
| **星尘** | 常驻（低频底噪） | 极小亮点，缓慢飘降 | 森林上方光点 / motes |
| **能量火花** | 高频峰值 (>15000Hz) | 上升的发光点，到顶后消散 | 夏季花朵 |
| **雪晶** | 冬季季 | 旋转的六角雪花，缓慢飘落 | 冬季雪花 |
| **落叶** | 秋季季 | 旋转的扁椭圆，左右摆动飘落 | 秋季落叶 |

**粒子生命周期**：每个粒子从底部生成（或随机 y），有 maxLife（1.5~4s），在生命周期内做非线性位移 + 旋转。

**数量控制**（对应原 `flowerCap: 900`）：
- 星尘常驻: 30 个
- 能量火花: 频段峰值时生成，最多同屏 50
- 季节粒子 (雪/落叶): 季节强度 × 120 个

**频段对齐**：粒子的生成频率与能量关联——
```
spawnRate = baseRate × (1 + bandEnergy[highBand] × 3.0)
```

---

## 三、技术架构

### 3.1 数据流

```
ExoPlayer AudioData
  ↓
SpectrumAnalyzer (FFT 分析)
  ↓ 频段能量 [bins[]]
SpectrumRepository (帧缓冲 + 平滑)
  ↓ 平滑后的频段能量
AudioFrame (带时间戳的帧数据)
  ↓
┌──────────────────────────────────────┐
│  StarFieldRenderer   ← 常驻地渲染      │
│  SpectrumBarRenderer ← 频段数据驱动     │
│  WaveformRenderer    ← 波形数据驱动     │
│  ParticleSystem      ← 频段+季节 驱动   │
│  MeteorScheduler     ← 间歇触发         │
│  AtmosphereRenderer  ← 季节色驱动       │
└──────────────────────────────────────┘
  ↓
Canvas 合成与渲染管线
```

### 3.2 核心类设计

```kotlin
// 频谱整体渲染器
class StellarSpectrumRenderer(
    private val width: Int,
    private val height: Int
) : SpectrumRenderer {
    // 模块内部渲染管线
    private val starfield = StarfieldController()
    private val bars = SpectrumBarController(barCount = 64)
    private val waveform = WaveformController(samplePoints = 128)
    private val particles = ParticleController()
    private val meteors = MeteorScheduler()
    private val atmosphere = AtmosphereController()
    private val seasons = SeasonController()
    private val palette = StellarPalette()

    // 每帧驱动
    override fun onAudioFrame(frame: AudioFrame) {
        bars.setBands(frame.bands)
        waveform.setSamples(frame.samples)
        particles.onFrame(frame, seasons.currentIntensity())
    }

    // 每帧渲染
    override fun draw(canvas: Canvas, deltaTime: Long) {
        seasons.advance(deltaTime)
        val bg = palette.background(seasons.tint)
        canvas.drawColor(bg)

        starfield.draw(canvas)
        atmosphere.draw(canvas, seasons.tint)
        bars.draw(canvas, palette.barColors(seasons.tint))
        waveform.draw(canvas, palette.waveformTint(seasons.tint))
        particles.draw(canvas)
        meteors.update()
        meteors.draw(canvas)
    }
}
```

### 3.3 频谱分析器

复用项目现有的 `SpectrumAnalyzer`（若不存在则新建），输出**两组数据**：

```kotlin
data class AudioFrame(
    val timestamp: Long,              // 帧时间戳
    val bands: FloatArray,            // 频段能量 [64]（对数刻度）
    val waveform: ShortArray,         // 波形样本 [128]
    val rhythmTempo: Double,          // 节奏感知（可选）
)
```

**FFT 参数**：
- FFT size: 2048（采样率 44100 / FFT 2048 ≈ 21.5 Hz/bin）
- 重叠率: 50%（Hann window）
- 帧率: 与渲染帧率锁定（60fps），跟随 `requestAnimationFrame`

### 3.4 平滑处理

频谱数据必须做平滑，避免条带闪烁：

```kotlin
class BandSmoother(val bars: Int) {
    private val history = Array(bars) { FloatArray(SMOOTH_WINDOW) }  // 环形缓冲
    private val ptr = IntArray(bars)

    fun smooth(band: Int, value: Float): Float {
        history[band][ptr[band]] = value
        ptr[band] = (ptr[band] + 1) % SMOOTH_WINDOW
        var sum = 0f, n = 0f
        for (i in 0 until bars) {
            // 加权平均：越新的权重越大
            val w = 1.0f - ((i + 1) / SMOOTH_WINDOW) * 0.4  // 衰减系数
            // 实际上用环形缓冲：
        }
        return average()
    }
}
```

- **SMOOTH_WINDOW**: 5 帧（约 83ms @60fps）
- 加权：中心加权，越新的帧权重越高
- 每帧对 64 条频谱条各做一次 5 帧滑动窗口加权平均

### 3.5 渲染管线（与 VisualizerRenderer 集成）

#### 3.5.1 工厂注册

在 `VisualizerRendererFactory.create(theme, context)` 中添加新主题分支：

```kotlin
fun create(theme: VisualizerTheme, context: Context): VisualizerRenderer {
    return when (theme) {
        // ... 现有主题 ...
        THEME_ID_STELLAR_SPECTRUM -> StellarSpectrumRenderer(
            width = canvasWidth,
            height = canvasHeight
        )
    }
}
```

#### 3.5.2 主题 ID

```kotlin
enum class VisualizerTheme(val id: String) {
    // ... 现有主题 ...
    STELLAR_SPECTRUM("stellar_spectrum"),
}
```

#### 3.5.3 主题数更新

- 当前主题数: 27（E41~E50 + 其他）
- 新增后: **28**

---

## 四、各组件详细规格

### 4.1 星空（Starfield）

**移植依据**：globe-present.html 原装 `stars` 独立 LCG 随机生成。

```
星点数量: 200 颗
星点大小: 0.5~2.0px（按亮度分档）
亮度档位: 三档
  tier-3: 1.5~2.0px, alpha=0.9（亮星，约 15%）
  tier-2: 0.8~1.2px, alpha=0.6（中等，约 35%）
  tier-1: 0.5~0.7px, alpha=0.3（暗星，约 50%）
闪烁: tier-3 以 3~5 秒周期正弦闪烁,alpha 在 0.6~1.0 之间变化
颜色: 纯白 #FFFFFF 或微暖 #FFF8EE（约 10% 暖星）
种子: LCG(20261108)，固定种子 ⇒ 可复现
```

**绘制**：每个星点一条 `drawCircle`，半径即大小。purity：200 次 `drawCircle` 约 200 draw calls——可接受（Canvas 在低端设备上也可以承受）。

### 4.2 频谱条（Spectrum Bars）

**规格**：

```
Band count: 64（默认值）
Bar width:  动态计算 = (screenWidth - 2×margin) / 64
Bar gap:    2px
Max height: screenHeight × 0.48
Min height: 4px  

映射方式: 对数刻度
  bin→bar 映射: 
    bar[i] 对应 bin 区间 [exp(i * log2(bins/64))] 
    即低 16 bar 用 1 bin/bar, 高 16 bar 合并 4~8 bin/bar
  
视觉优化:
  · 底部连成一线（有 minimum height）
  · 顶部圆角 2px（roundRect）
  · 边缘频段向内收缩一列（更漂亮）
  · 消除相邻帧跳变（加平滑）

颜色: 见 §2.3 双色插值
背景: 半透明深色板（0.05 alpha 黑色矩形覆盖在频谱区）
```

**频段映射（对数）**：
```kotlin
val barIndex = (1.0 + Math.log(bandFreq / minFreq) / Math.log(maxFreq / minFreq)) * (bars - 1)
```

### 4.3 波形线（Waveform）

```
Sample points: 128
Smoothing: 二次贝塞尔曲线
Color: 半透明 (alpha=0.45) 的白色/季节次色
Position: 频谱条下方，y = screenHeight × 0.82
Amplitude: ±(screenHeight × 0.04) （振幅小，作为辅层）
```

### 4.4 大气光晕（Atmosphere）

**移植依据**：globe-present.html 的 `#fx` canvas 大气光晕（径向渐变 Sprite）。

```
形状: 底部径向渐变
尺寸: 宽度 = screenWidth，高度 = screenHeight × 0.35
锚点: 底部中心 (cx=screenWidth/2, cy=screenHeight)
渐变: 中心 0 alpha → 80% 处最大 alpha → 边缘 0 alpha
颜色: 随季节变化（见 §2.3 大气雾色表）
动画: 极慢呼吸式（±10% alpha 周期 8s）
```

**渐变色像素占比（频谱区域）**：
- 上半段屏幕: 深色（星空）
- 下半段屏幕: 大气光晕自然晕染，在频谱条后方提供暖色底

### 4.5 流星（Meteors）

**移植依据**：globe-present.html 的 V3 流星。

```
数量: 0~2 颗同屏
随机触发:
  · 基础间隔: 4~9 秒随机间隔
  · 每次触发 1 颗
生命周期: 约 2.5 秒（淡入 0.3s → 持续 2s → 淡出 0.2s）
轨迹:
  · 从右上方（80% 宽，10% 高）向左下方（20% 宽，40% 高）
  · 长度约 screenWidth × 0.35
  · 速度 ≈ 屏幕宽度 / 2.5 秒
外观:
  · 头部: 明亮白/暖色，alpha 1.0，半径 1.5px
  · 尾部: 渐变尾巴，alpha 1.0 → 0，宽度 1.5px → 0.3px
季节影响:
  · 春: 偏绿色
  夏: 偏白色
  · 秋: 偏橙色
  · 冬: 偏蓝色
```

**LCG 种子**：与星空共用 20261108

### 4.6 粒子系统（Particle System）

**架构**：对象池 + 每帧更新。

```kotlin
class ParticleController {
    // 对象池
    private val pool = Array(MAX_POOL = 200) { Particle() }
    private val active = ArrayDeque<Particle>()  // 活跃列表
    private val rng = LCG(20261108)

    fun spawn(type: ParticleType, x: Float, y: Float) { ... }
    fun update(dt: Float, seasons: Float) { ... }
    fun draw(canvas: Canvas) { ... }
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

**各类型运动学**：

| 类型 | 速度 | 加速度 | 旋转 | 生命周期 |
|---|---|---|---|---|
| STARDUST | y: +15 px/s（缓慢下落） | 无 | 无 | 8~12s |
| SPARK | y: -60~-100 px/s（上升） | 重力衰减 20 px/s² | 闪烁 | 0.8~2.0s |
| SNOWFLAKE | x: sin 摆动 ±1; y: +20~+40 px/s | 微风 | 自转 1~2 rad/s | 4~8s |
| LEAF | x: sin ×2; y: +30~+50 px/s | 无 | 3~5 rad/s | 5~9s |

**频段触发逻辑**：
```
SPARK 生成阈值: bandEnergy[highBin] > 0.75
SPARK 生成频率: 基础 1 个/s + 频段能量超阈值频次 × 3
冬季雪花密度: season.w × 0.3 个/帧  （30fps × 0.3 = 9 个/s）
秋季落叶密度: season.w × 0.4 个/帧
```

---

## 五、动画系统

### 5.1 季节推进

| 参数 | 值 |
|---|---|
| 周期 | 4 首歌（≈12~20 分钟） |
| 季内占比 | 前 70% 保持当前季，末 30% 过渡 |
| 过渡曲线 | 双重 `smoothstep`（平顶 S 曲线） |
| 颜色插值 | 所有颜色参数同一因子 |

### 5.2 动画帧率控制

```kotlin
// 渲染帧率与音频帧率同步
// 60fps 渲染目标，低于 45fps 触发降级
```

- 目标帧率：**60 fps**
- 最低帧率：**30 fps**（低端设备）
- 自适应降级（移植原 globe 的 quality 控制）：
  - 若连续 30 帧 < 45 fps → 粒子数量减半
  - 若连续 60 帧 < 30 fps → 星星减半 + 关闭气象效果

### 5.3 昼夜交替

**概念**：一个 **昼夜相位** 在频谱中央摆动。
```
白天: 频谱条整体提亮 15%，背景顶部偏暖，大气雾色偏暖
夜晚: 频谱条保持不变，背景顶部加深，大气雾色偏冷，添加微光点
过渡: 约 8 秒的正弦过渡
```

实现方式：不需要实际的时间判断，而是歌曲播放进度驱动的伪昼夜。

---

## 六、性能预算

### 6.1 每帧成本估算（中端设备 60fps）

| 操作 | 预估 draw calls | 估算时间 |
|---|---|---|
| 背景填充 | 1 | ~0.5ms |
| 星空 200 星点 | 200 | ~2ms |
| 大气渐变 | 1 | ~0.5ms |
| 频谱条 64 根 | 64 | ~1.2ms |
| 波形线 | 1 | ~0.2ms |
| 粒子 ~30 个 | ~30 | ~1.0ms |
| 流星 0~2 | ~1 | ~0.1ms |
| **合计** | ~300 | **≈ 5~6ms** |

### 6.2 内存预估

| 对象 | 数量 | 内存 |
|---|---|---|
| 星点 offsets | 200 × 3 floats | 2.4 KB |
| 频谱条软件缓冲 | 64 × 5 floats | 1.3 KB |
| 粒子对象池 | 200 instances | ~40 KB |
| AudioFrame 缓冲 | 64 float + 128 short | 1.0 KB |
| **合计** | | **≈ 45 KB** |

### 6.3 优化策略

1. **星空合批**：若 draw calls 过多，用 `drawPoints` **一次绘制所有星点**（`Canvas.drawPoints` 一次调用绘 N 点）
2. **频谱条 top-level roundRect**：可用 `Path.addRoundRect` 批量构建路径
3. **粒子 alpha 合并**：同种粒子用同一 shader 实例（没有 shader ⇒ 低优先）
4. **备用降级方案**：
   - L1（最低）: 星空合并 + 频谱条用纯矩形 + 关闭粒子 + 关闭流星
   - L1+L0: 全简单模式，仅频谱条 + 背景

---

## 七、频谱接入点与数据来源

### 7.1 ExoPlayer 音频回调

```kotlin
// 在 PlayerManager 中新增频谱数据回调
player.addAudioFrameCallback listener:
  onAudioFrame(frameData: ByteArray) {
    // 解析 → FFT → bands + waveform
    // 通过回调传递给 SpectrumRepository
  }
```

### 7.2 频谱数据源优先级

1. **ExoPlayer 的 AudioFrameCallback**（首选，直接从解码后的 PCM 计算）
2. **Visualizer API**（兜底：Android 系统内置 `Visualizer` 类，但有 75ms 延迟）
3. **FFT 模拟数据**（无音频输入时的占位动画）

### 7.3 频谱数据平滑

```
输入: LIVE bands (64 นาที)
处理:
  1. 5-frame 滑动窗口加权平均(SMOOTH_WINDOW)
  2. 峰值抑制：若带宽超过 3×临时能量，标记为"峰值"，不参与亮度估计
  3. 最小保持：连续 10 帧大于 0.1 的频段，额外保底值

输出: SMOOTH_BANDS
```

---

## 八、兼容性与约束

### 8.1 架构约束

- **不引入 three-globe / WebView**（已有 E41 世界效果，本频谱为独立 2D 替代）
- **不引入新第三方依赖**（除非已有的 FFTW/KissFFT 类库可复用）
- **Kotlin 原生实现**，不混入 JS 或 Web assembly
- **与现有 VisualizerRenderer 兼容**（实现 `VisualizerRenderer` 接口）

### 8.2 设备兼容

| API Level | 能力 | 适配 |
|---|---|---|
| 22 (minSdk) | 基础 Canvas drawPoints/roundRect/阴影 | 可用 |
| 23 | 动态权限（无影响） | — |
| 24+ | 支持 `Canvas.drawPoints` 批量星点 | 优化路径 |

### 8.3 无障碍

- 频谱为纯视觉（非交互），无需 `contentDescription`
- 帧率自适应降级为自动行为
- 不使用 α 通道闪烁（防晕动症）：闪烁频率 < 3Hz

---

## 九、开发里程碑

### Phase 0: 骨架（1~2 天）
- [ ] 创建 `StellarSpectrumRenderer` 类骨架
- [ ] 实现 `VisualizerRenderer` 接口空壳
- [ ] 在工厂注册主题
- [ ] 简单验证：编译通过 + 不 crash

### Phase 1: 频谱核心（2~3 天）
- [ ] 频段能量 → 64 频谱条（对数映射 + 平滑）
- [ ] 基础颜色 + 圆角 + 间隙
- [ ] 波形线
- [ ] 帧率监控（自适应降级）
- [ ] 验证：播放音乐时频谱条有效响应

### Phase 2: 氛围层（2~3 天）
- [ ] 星空（200 颗 + LCG 固定种子 + 闪烁)
- [ ] 大气渐变
- [ ] 背景径向渐变
- [ ] 验证：静态帧美观

### Phase 3: 季节 + 粒子（3~5 天）
- [ ] SeasonController（S 曲线 + 双色插值）
- [ ] 四季调色板映射
- [ ] 粒子系统：星尘 + 火花
- [ ] 冬季雪花 / 秋季落叶
- [ ] 验证：季节切换视觉效果

### Phase 4: 流星 + 昼夜（1~2 天）
- [ ] 流星调度器
- [ ] 昼夜相位
- [ ] 验证：流星触发 + 昼夜交替

### Phase 5: 性能 + 收尾（2~3 天）
- [ ] draw calls 优化（drawPoints 合批 star）
- [ ] 帧率降级验证
- [ ] 单测（BandSmoother / SeasonController / LCG 种子）
- [ ] lint 通过
- [ ] 文档归档

**总计**: 约 11~17 个工作日

---

## 十、测试计划

### 10.1 单元测试

| 测试类 | 测什么 |
|---|---|
| `StellarPaletteTest` | 季节插值数学正确（双重 smoothstep 边界） |
| `BandSmootherTest` | 5 帧滑动窗口加权（衰减 0.4） |
| `StarfieldControllerTest` | LCG 种子可复现性（同种子同输出） |
| `ParticleControllerTest` | 对象池不泄漏、pool 正确回收 |
| `SpectrumBarControllerTest` | FFT bin → bar 映射对数刻度正确 |
| `SeasonControllerTest` | 季节折叠、过渡带、边界值 |

### 10.2 集成验证

- [ ] Release 包中能跑（APK ≤ 50MB 无增量）
- [ ] API 22 设备（创维 5.1.1）有帧率
- [ ] 频谱与播放同步（无延迟 > 100ms）
- [ ] 后台 → 前台切换时频谱恢复正常
- [ ] 省电模式降级

### 10.3 性能基线

- Waze 中端机 (API 24)：> 45fps
- 低端机 (API 22)：> 30fps
- draw call 总数 ≤ 320 的同时在 **60fps** 下运行

---

## 十一、验收标准

### 11.1 视觉验收 ✅

- [ ] 频谱条对音乐有效响应（不仅仅是常量动画）
- [ ] 星空 / 大气 / 流星 / 粒子构成完整的「星际四季」氛围
- [ ] 四季色调变化一眼可见（色相差异 ±20%）
- [ ] 整体美观度不输 globe-present.html（比例缩放后）

### 11.2 性能验收 ✅

- [ ] 60fps 低端机 / 中端机
- [ ] 无 ANR、无跳帧（> 3 连续丢帧）
- [ ] APK 增量 ≤ 50KB（无新增依赖）

### 11.3 架构验收 ✅

- [ ] 不使用 three-globe / WebView
- [ ] 复用现有 SpectrumAnalyzer（不许另起 FFT）
- [ ] 进入 VisualizerRenderer 框架
- [ ] 紧跟 §10 技术文档编写规范

---

## 十二、参考资料

- `docs/globe-present.html` —— 视觉参考源（星空 / 大气 / 季节 / 流星 / 昼夜）
- `docs/technical-overview.md` §10 —— 项目开发史
- `app/src/main/java/com/nasmusic/tv/visualizer/` —— 现有渲染器框架
- `app/src/main/java/com/nasmusic/tv/visualizer/fx/` —— 现有 FX 效果库
- `app/src/main/java/com/nasmusic/tv/visualizer/renderers/` —— Renderer 实现先例

