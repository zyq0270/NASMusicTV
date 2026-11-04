# 歌词舞台（Lyrics Stage）开发方案 — 可开发版

> 基于 [Folia](https://github.com/chthollyphile/folia-major)（AGPL-3.0）歌词可视化系统的逐行移植，
> 结合 NASMusicTV 现有 visualizer 框架。
>
> **核心原则：逐行移植 Folia 源码的算法与参数，不自行设计视觉。**
> 每个子模式直接对应 Folia 的一个主题，移植其渲染逻辑、动画参数、时序模型。
> 仅将 Web API 映射为 Android 等价物，算法和数值不变。

## 致谢

本方案的全部视觉设计与算法来自开源项目 **Folia**（chthollyphile/folia-major，AGPL-3.0）。
Folia 实现了 17 种全屏歌词动画主题，本方案从中选取 4 个主题进行逐行移植：

| 子模式 | Folia 原主题 | 渲染技术（Folia） | 源文件 |
|---|---|---|---|
| 逐字行光 | `classic` | DOM+CSS + framer-motion | `classic/Visualizer.tsx` + `GlowWord.tsx` + `glowWordVariants.ts` + `glowWordTiming.ts` |
| 全屏纵览 | `fume` | Canvas2D 全屏重绘 | `fume/useFumeCanvasStage.ts` + `fumeCanvasText.ts` + `fumeCameraStep.ts` + `fumeBlockFrame.ts` + `fumeCamera.ts` + `fumeMath.ts` + `fumeReveal.ts` + `fumeTextStyle.ts` + `fumeTextMeasure.ts` |
| 倾斜律动 | `tilt` | DOM+CSS + framer-motion + pretext | `tilt/VisualizerTilt.tsx` + `utils/lyrics/graphemeTiming.ts` |
| 分栏引导 | `partita` | DOM+CSS + framer-motion | `partita/PartitaChunk.tsx` + `partita/partitaLayout.ts` + 共享 `GlowWord.tsx` |

子模式 0（歌词点阵）为 NASMusicTV 现有 E23 效果，不来自 Folia。

代码实现中，每个移植文件头部标注对应的 Folia 源文件路径与版本号。

---

## 0. 歌词模式总览

歌词舞台（LYRICS_STAGE）包含以下 5 个子模式，用户可通过上下键 / 手势上下滑动手动切换：

| # | 子模式 | 中文名 | 来源 | Folia 对应主题 | 画质档位 | 状态 |
|---|---|---|---|---|---|---|
| 0 | `DOT_MATRIX` | 歌词点阵 | NASMusicTV E23 原创 | — | ADV | 现有，吸收 |
| 1 | `GLOW_LINE` | 逐字行光 | Folia 逐行移植 | `classic` | ADV | 移植中 |
| 2 | `FULL_VIEW` | 全屏纵览 | Folia 逐行移植 | `fume` | ULTRA | 移植中 |
| 3 | `TILT_PULSE` | 倾斜律动 | Folia 逐行移植 | `tilt` | ADV | 移植中 |
| 4 | `PARTITA` | 分栏引导 | Folia 逐行移植 | `partita` | ADV | 移植中 |

> ⛔ **仅手动切换**：不自动定时切换、不随歌曲切换、不随段落/副歌切换。
> 子模式选择持久化到 `VisualizerPrefs`，切歌后保持。

---

## 1. 数据模型（Kotlin）

以下 Kotlin 数据类镜像 Folia 的 TypeScript 类型，供渲染器内部使用。
它们**不是** RenderContext 的字段——RenderContext 只传基本类型（String、Long、Float），
渲染器在 `onEnter` 时自行解析构造。

### 1.1 词与行

```kotlin
// visualizer/renderers/lyricsStage/LyricWord.kt
// 镜像 Folia: src/types.ts Word 类型
data class LyricWord(
    val text: String,
    val startTime: Double,  // 秒
    val endTime: Double,    // 秒
    val syllables: List<Syllable>? = null,
)

data class Syllable(
    val text: String,
    val startTime: Double,
    val endTime: Double,
)

// 镜像 Folia: src/types.ts Line 类型（含 renderHints）
data class LyricLine(
    val fullText: String,
    val startTime: Double,
    val endTime: Double,
    val words: List<LyricWord>,
    val renderHints: LineRenderHints? = null,
)

// 镜像 Folia: src/utils/lyrics/renderHints.ts LineRenderHints
data class LineRenderHints(
    val rawDuration: Double,
    val timingClass: String,       // "normal" | "short" | "micro"
    val renderEndTime: Double,
    val lineTransitionMode: String, // "normal" | "fast" | "none"
    val wordRevealMode: String,     // "normal" | "fast" | "instant"
)
```

> ⚠️ `Word` 还需携带 `wordColors`（来自 `theme.wordColors`）供 `resolveWordColor()` 逐词配色使用。
> Folia 的 `resolveWordColor(word.text, theme.wordColors, theme.accentColor)` 对每个词根据文本哈希得到不同高亮色，
> 这是"逐字行光"的核心视觉特征，不能简化为统一的 `accentColor`。

### 1.2 Grapheme 时序

```kotlin
// visualizer/renderers/lyricsStage/GraphemeTiming.kt
// 镜像 Folia: src/utils/lyrics/graphemeTiming.ts GraphemeTiming
data class GraphemeTiming(
    val char: String,
    val startTime: Double,
    val endTime: Double,
    val wordIndex: Int? = null,
)
```

### 1.3 fume 专用数据结构

```kotlin
// visualizer/renderers/lyricsStage/fume/FumeTypes.kt
// 镜像 Folia: src/components/visualizer/fume/fumeTypes.ts

data class ViewportSize(val width: Float, val height: Float)

data class WordRange(
    val wordIndex: Int,
    val word: LyricWord,
    val start: Int,        // grapheme 索引
    val end: Int,
    val colorStart: Int,
    val colorEnd: Int,
    val graphemeTimings: List<GraphemeTiming>,
)

data class CameraTarget(
    var x: Float,
    var y: Float,
    var velocityX: Float,
    var velocityY: Float,
    var focusX: Float,
    var focusY: Float,
    var scale: Float,
    var velocityScale: Float,
    var focusScale: Float,
)

data class CameraRetargetState(
    var sourceLineIndex: Int,
    var startedAt: Double,
    var duration: Double,
    var fromX: Float,
    var fromY: Float,
    var fromScale: Float,
    var bridgeMode: String,       // "none" | "direct" | "overview"
    var bridgeWaypointX: Float,
    var bridgeWaypointY: Float,
    var bridgeWaypointScale: Float,
    var bridgeWaypointPhase: Double,
)

data class FumeCameraState(
    var camera: CameraTarget,
    var retarget: CameraRetargetState,
    var initialized: Boolean,
)

data class FumeBlockTiming(
    val waitingOpacity: Float,
    val activeOpacity: Float,
    val effectiveTextHoldStyle: String,   // "standard" | "dimmed"
    val passedStyle: PassedTextStyle,
    val transitionPassedStyle: PassedTextStyle,
    val baselineOffset: Float,
    val lineEndTime: Double,
    val linePassCutoffTime: Double,
    val hasRevealCompleted: Boolean,
    val hasPassCutoffReached: Boolean,
    val lineDuration: Double,
    val colorTrailDuration: Double,
    val staticState: String?,  // "waiting" | "passed" | null
)

data class PassedTextStyle(
    val opacity: Float,
    val glowMultiplier: Float,
    val shadowAlphaBase: Float,
    val shadowAlphaTrail: Float,
)
```

### 1.4 子模式枚举

```kotlin
// visualizer/renderers/lyricsStage/LyricsStageMode.kt
enum class LyricsStageMode(val displayName: String) {
    DOT_MATRIX("歌词点阵"),
    GLOW_LINE("逐字行光"),
    FULL_VIEW("全屏纵览"),
    TILT_PULSE("倾斜律动"),
    PARTITA("分栏引导"),
    ;

    companion object {
        val Default: LyricsStageMode = DOT_MATRIX
        fun fromOrdinal(ord: Int): LyricsStageMode =
            entries.getOrElse(ord) { Default }
    }
}
```

---

## 2. RenderContext 扩展

```kotlin
// visualizer/RenderContext.kt — 新增字段

/** 全曲歌词行列表（FULL_VIEW 子模式用） */
var lyricLines: List<LyricLine> = emptyList()

/** 每行起始时间 ms（FULL_VIEW 用，与 lyricLines 对应） */
var lyricLineStartTimesMs: List<Long> = emptyList()

/** 当前行逐词时间戳（GLOW_LINE / PARTITA 用，替代现有的 lyricWordTimestamps） */
var lyricWords: List<LyricWord> = emptyList()
```

> ⚠️ 现有 `currentLyricLine` / `nextLyricLine` / `lyricLineProgress` / `lyricLineIndex` /
> `lyricWordTimestamps` / `lyricHasWordTimestamps` / `lyricMaxLineChars` / `longestLyricLine`
> 字段保留不动——现有 E23 渲染器依赖它们。
> 新增字段仅供歌词舞台子模式使用。

### 数据填充来源

`VisualizerStage` 的帧循环中，从 `lyrics: List<LyricsLine>?` 参数构造 `LyricLine` 列表：

```kotlin
// VisualizerStage.kt — 帧循环内，构造 RenderContext 时
ctx.lyricLines = lyrics?.map { it.toLyricLine() } ?: emptyList()
ctx.lyricLineStartTimesMs = lyrics?.map { it.startTimeMs } ?: emptyList()
ctx.lyricWords = ctx.currentLyricLine?.let { /* 从当前行解析 */ } ?: emptyList()
```

`LyricsLine` → `LyricLine` 的转换实现 `toLyricLine()`：
将 `LyricsLine`（NASMusicTV 现有模型）的字段映射到 `LyricLine`。
逐词时间戳的解析逻辑：
- 若 `ctx.lyricHasWordTimestamps == true` 且 `lyricWordTimestamps` 非空，按词均分到 `LyricWord`
- 否则用行级时间均分：每个词的 `startTime = line.startTime + (wordIndex / wordCount) * lineDuration`
- 词颜色通过 `resolveWordColor(word.text, theme.wordColors, theme.accentColor)` 预计算

> ⚠️ `parseToLyricLine(line, words)` 是 `toLyricLine()` 的别名——各子渲染器中用 `parseToLyricLine` 指代同一转换，
> 实际实现统一为 `LyricsLine.toLyricLine()`。定义位置：`data/model/LyricLine.kt`（新增扩展函数）。

> ⚠️ `VisualQuality` 枚举定义在 `AppSettings.kt`（第 560 行起），**不在**独立的 `VisualQuality.kt` 文件中。

---

## 3. 架构总览

```
VisualizerTheme.LYRICS_STAGE (E53, 新增枚举项)
  └─ LyricsStageRenderer (实现 VisualizerRenderer)
       ├─ mode: LyricsStageMode (内部子模式枚举)
       ├─ subRenderer: LyricsSubRenderer (接口)
       │    ├─ DotMatrixSubRenderer      (子模式 0：点阵，复用现有 E23 逻辑)
       │    ├─ GlowLineSubRenderer       (子模式 1：逐字行光，移植 classic)
       │    ├─ FullViewSubRenderer       (子模式 2：全屏纵览，移植 fume)
       │    ├─ TiltPulseSubRenderer      (子模式 3：倾斜律动，移植 tilt)
       │    └─ PartitaSubRenderer        (子模式 4：分栏引导，移植 partita)
       └─ crossfadeAlpha: Float (切换过渡用)
```

### 子渲染器接口

```kotlin
// visualizer/renderers/lyricsStage/LyricsSubRenderer.kt
interface LyricsSubRenderer {
    /** 进入子模式：预分配缓冲、预计算时序 */
    fun onEnter(ctx: RenderContext)

    /** 每帧绘制（零分配约束：Paint/Path/Color 提到成员变量） */
    fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext)

    /** 退出子模式：释放资源 */
    fun onExit()
}
```

---

## 4. 子模式 0：DOT_MATRIX（现有 E23 吸收）

### 4.1 来源

NASMusicTV 原创效果（`LyricsDotMatrixRenderer`），非 Folia 移植。

### 4.2 实现

**不新增文件**。`LyricsStageRenderer` 的 `DOT_MATRIX` 子模式直接复用现有
`LyricsDotMatrixRenderer` 的渲染逻辑。

实现方式：将 `LyricsDotMatrixRenderer` 的核心算法提取为 `object LyricsDotMatrixCore`，
原渲染器和新子渲染器共享：

```kotlin
// visualizer/renderers/LyricsDotMatrixCore.kt — 从现有渲染器提取
object LyricsDotMatrixCore {
    // sampleLine / addLineToPaths / 状态机 / 粒子物理
    // 提取后 LyricsDotMatrixRenderer 和 DotMatrixSubRenderer 都调用这里
}
```

### 4.3 改动

- `LyricsDotMatrixRenderer.kt`：将核心算法提取到 `LyricsDotMatrixCore`，原渲染器改为调用 core
- `DotMatrixSubRenderer.kt`：薄封装，委托给 `LyricsDotMatrixCore`

---

## 5. 子模式 1：GLOW_LINE（移植自 Folia classic）

### 5.1 移植文件清单

| Folia 源文件 | 移植内容 | Kotlin 目标文件 |
|---|---|---|
| `GlowWord.tsx:44-61` | 词状态机（waiting/active/passed） | `GlowWordCore.kt` |
| `glowWordTiming.ts:16-56` | wordLookahead / activeEndTime / displayDuration | `GlowWordCore.kt` |
| `glowWordVariants.ts:12-51` | 容器动画（opacity/scale/x/y/rotate） | `GlowLineSubRenderer.kt` |
| `glowWordVariants.ts:55-83` | 正文动画（color/blur） | `GlowLineSubRenderer.kt` |
| `classic/Visualizer.tsx:151-308` | 逐行确定性随机布局 | `GlowLineSubRenderer.kt` |
| `classic/Visualizer.tsx:314-341` | 呼吸浮动 | `GlowLineSubRenderer.kt` |
| `classic/Visualizer.tsx:145` | 字号 clamp | `GlowLineSubRenderer.kt` |
| `glowWordTiming.ts:58-91` | 行容器入场/离场 | `GlowLineSubRenderer.kt` |

### 5.2 GlowWordCore.kt — 词状态机与时序

```kotlin
// visualizer/renderers/lyricsStage/GlowWordCore.kt
// 移植自 Folia: src/components/visualizer/GlowWord.tsx + glowWordTiming.ts
// Folia 版本: 0.5.27-a16525c

enum class GlowWordStatus { WAITING, ACTIVE, PASSED }

data class GlowWordProfile(
    val wordLookahead: Double,        // 0.03 (instant) / 0.08 (fast) / 0.15 (normal)
    val lineRenderEndTime: Double,
    val lineTransitionMode: String,   // "normal" | "fast" | "none"
    val wordRevealMode: String,       // "normal" | "fast" | "instant"
)

object GlowWordCore {

    /** 移植自 GlowWord.tsx:53-61 — 词状态判定 */
    fun statusOf(word: LyricWord, time: Double, profile: GlowWordProfile): GlowWordStatus {
        val activeEnd = activeEndTime(word, profile)
        return when {
            time < word.startTime - profile.wordLookahead -> GlowWordStatus.WAITING
            time <= activeEnd -> GlowWordStatus.ACTIVE
            else -> GlowWordStatus.PASSED
        }
    }

    /** 移植自 glowWordTiming.ts:22-56 — activeEndTime 计算 */
    fun activeEndTime(word: LyricWord, profile: GlowWordProfile): Double {
        return when (profile.wordRevealMode) {
            "instant" -> profile.lineRenderEndTime
            "fast" -> minOf(
                profile.lineRenderEndTime,
                maxOf(word.endTime, word.startTime + 0.12)
            )
            else -> word.endTime
        }
    }

    /** 移植自 glowWordTiming.ts:58-72 — wordDisplayDuration */
    fun displayDuration(word: LyricWord, profile: GlowWordProfile): Double {
        val activeEnd = activeEndTime(word, profile)
        val minDuration = when (profile.wordRevealMode) {
            "instant" -> 0.08
            "fast" -> 0.12
            else -> 0.1
        }
        return maxOf(activeEnd - word.startTime, minDuration)
    }

    /** 移植自 glowWordTiming.ts:16-56 — 从 Line 构造 Profile */
    fun profileFor(line: LyricLine): GlowWordProfile? {
        if (line.renderHints == null) return null
        val hints = line.renderHints
        val wordRevealMode = hints.wordRevealMode
        val wordLookahead = when (wordRevealMode) {
            "instant" -> 0.03
            "fast" -> 0.08
            else -> 0.15
        }
        return GlowWordProfile(
            wordLookahead = wordLookahead,
            lineRenderEndTime = hints.renderEndTime,
            lineTransitionMode = hints.lineTransitionMode,
            wordRevealMode = wordRevealMode,
        )
    }
}
```

### 5.3 GlowLineSubRenderer.kt — 渲染器

```kotlin
// visualizer/renderers/lyricsStage/GlowLineSubRenderer.kt
// 移植自 Folia: src/components/visualizer/classic/Visualizer.tsx
//               + glowWordVariants.ts + glowWordTiming.ts
// Folia 版本: 0.5.27-a16525c

class GlowLineSubRenderer : LyricsSubRenderer {

    // ── 成员变量（零分配约束：每帧 draw 不创建新对象）──
    private val paintBody = Paint().apply { isAntiAlias = true }
    private val paintGlow = Paint().apply { isAntiAlias = true }
    private val paintShadow = Paint().apply { isAntiAlias = true }

    // 词布局缓存（onEnter 时预计算，draw 时只读）
    private var wordLayouts: List<WordLayout> = emptyList()
    private var lineFontSize: Float = 0f
    private var profile: GlowWordProfile? = null

    // 呼吸浮动参数
    private var breathDistance: Float = 0f
    private var breathDuration: Double = 0.0

    // 行容器入场/离场状态
    private var lineOpacity: Float = 1f
    private var lineScale: Float = 1f
    private var lineBlur: Float = 0f

    data class WordLayout(
        val word: LyricWord,
        val x: Float,
        val y: Float,
        val rotate: Float,
        val scale: Float,
        val baseColor: Int,    // ARGB — theme.primaryColor
        val activeColor: Int,  // ARGB — resolveWordColor 逐词配色
        val passedRotate: Float,
        val marginRightPx: Float,
    )

    data class LineConfig(
        val justifyContent: String,  // "start" | "center" | "end" | "around" | "between"
        val alignItems: String,      // "start" | "center" | "end"
        val perspective: Float,
        val rowGap: Float,
        val reachTop: Float,
        val reachBottom: Float,
    )

    // 呼吸浮动关键帧（移植 classic/Visualizer.tsx:331-334）
    // Folia 用 framer-motion 的 keyframe 数组 + repeat:Infinity + easeInOut
    // 不是 sin 波。Kotlin 端用预计算的 5 帧插值近似。
    private val breathYKeyframes = floatArrayOf(0f, -1f, 0f, 0.45f, 0f)
    private val breathScaleKeyframes = floatArrayOf(1f, 1.01f, 1f, 0.995f, 1f)
    private var breathElapsed: Double = 0.0
    private var breathMultiplier: Float = 1f

    override fun onEnter(ctx: RenderContext) {
        // ── 移植 classic/Visualizer.tsx:145 — 字号 ──
        // clamp(2.25rem, 6vw, 4.5rem) * lineFontScale
        // Android: rem = 16px → clamp(36px, 6%屏宽, 72px)
        val screenW = ctx.canvasSize.width
        lineFontSize = maxOf(36f, minOf(72f, screenW * 0.06f))

        // ── 移植 classic/Visualizer.tsx:151-308 — 逐行确定性随机布局 ──
        val line = ctx.currentLyricLine?.let { parseToLyricLine(it, ctx.lyricWords) }
        if (line != null) {
            profile = GlowWordCore.profileFor(line)
            val layout = buildWordLayouts(line, ctx, profile!!)
            wordLayouts = layout.wordLayouts
            currentLineConfig = layout.lineConfig
        }

        // ── 移植 classic/Visualizer.tsx:314-341 — 呼吸浮动参数 ──
        // intensity: calm → distance=10, duration=8.5; normal → 14, 7; chaotic → 18, 5.8
        val intensity = when {
            ctx.quality == VisualQuality.LOW -> "calm"
            else -> "normal"
        }
        breathDistance = when (intensity) {
            "calm" -> 10f
            "chaotic" -> 18f
            else -> 14f
        }
        breathDuration = when (intensity) {
            "calm" -> 8.5
            "chaotic" -> 5.8
            else -> 7.0
        }
    }

    /** 移植 classic/Visualizer.tsx:151-308 — 确定性随机布局 + 行配置 */
    private fun buildWordLayouts(
        line: LyricLine,
        ctx: RenderContext,
        profile: GlowWordProfile,
    ): Pair<List<WordLayout>, LineConfig> {
        val seed = line.startTime

        // 移植 classic/Visualizer.tsx:204-207 — random(offset)
        fun random(i: Int, offset: Int): Double {
            val x = Math.sin(seed + i + offset) * 10000.0
            return x - Math.floor(x)
        }

        // 移植 classic/Visualizer.tsx:155-167 — intensity 分档
        val intensity = "normal"
        val isChaotic = intensity == "chaotic"
        val isCalm = intensity == "calm"

        // 移植 classic/Visualizer.tsx:162-175 — 行容器配置
        val isInterlude = line.fullText == "......"
        val justifyOptions = if (isCalm) listOf("center")
            else listOf("start", "center", "end", "around", "between")
        val alignOptions = if (isCalm) listOf("center")
            else listOf("start", "center", "end")
        val lineConfig = LineConfig(
            justifyContent = if (isInterlude) "center" else justifyOptions[(seed % justifyOptions.size).toInt()],
            alignItems = alignOptions[((seed * 2) % alignOptions.size).toInt()],
            perspective = if (isChaotic) 500 + (seed % 500) else 1000f,
            rowGap = 0f,
            reachTop = 0f,
            reachBottom = 0f,
        )

        // 移植 classic/Visualizer.tsx:191-192 — baseSpread / baseRotate
        val baseSpread = if (isChaotic) 60f else if (isCalm) 0f else 20f
        val baseRotate = if (isChaotic) 30f else if (isCalm) 0f else 5f

        // 移植 classic/Visualizer.tsx:181-188 — 字号 px 计算
        val fontScale = 1f
        val minPx = 2.25 * fontScale * 16
        val valPx = (6 * fontScale * ctx.canvasSize.width) / 100
        val maxPx = 4.5 * fontScale * 16
        val pxFontSize = maxOf(minPx, minOf(valPx, maxPx))

        // 逐词测量宽度（移植 measureWordWidth，用 Paint.measureText 近似）
        val wordWidths = line.words.map { word ->
            paintBody.textSize = pxFontSize
            paintBody.measureText(word.text)
        }

        // 移植 classic/Visualizer.tsx:211-217 — 每词 placement
        val wordLayouts = line.words.mapIndexed { i, word ->
            // interlude 特殊处理（移植 :230-241）
            if (isInterlude) {
                val r = random(i, 2)
                return@mapIndexed WordLayout(
                    word = word,
                    x = 0f,
                    y = ((r - 0.5) * 15).toFloat(),
                    rotate = 0f,
                    scale = 1.5f,
                    baseColor = ctx.palette.primaryColor.toArgb(),
                    activeColor = resolveWordColor(word.text, ctx.palette.accentColor, ctx.palette.primaryColor).toArgb(),
                    passedRotate = 0f,
                    marginRightPx = 3 * 16f,  // 3rem
                )
            }

            // 移植 :211-216 — scale / rotate / offset
            val scale = if (isChaotic) 0.8 + random(i, 4) * 0.6 else 1.1 + random(i, 4) * 0.2
            val rotate = ((random(i, 3) - 0.5) * baseRotate * 2).toFloat()
            val passedRotate = ((random(i, 8) - 0.5) * 45).toFloat()
            val xOffset = ((random(i, 1) - 0.5) * baseSpread * 2).toFloat()
            val yOffset = ((random(i, 2) - 0.5) * baseSpread * 2).toFloat()

            // 移植 :248-288 — marginRight（avoidOverlap）
            val marginRightPx = if (!isChaotic) {
                0.8f * 16f  // 0.8rem → px
            } else {
                random(i, 5) * 1.5f * 16f  // random(5) * 1.5rem
            }

            // 移植 :384 — resolveWordColor 逐词配色
            val activeColor = resolveWordColor(word.text, ctx.palette.accentColor, ctx.palette.primaryColor)

            WordLayout(
                word = word,
                x = xOffset,
                y = yOffset,
                rotate = rotate,
                scale = scale.toFloat(),
                baseColor = ctx.palette.primaryColor.toArgb(),
                activeColor = activeColor.toArgb(),
                passedRotate = passedRotate,
                marginRightPx = marginRightPx,
            )
        }

        return wordLayouts to lineConfig
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val timeSec = ctx.nowMs / 1000.0
        val profile = this@GlowLineSubRenderer.profile ?: return

        // ── 呼吸浮动（移植 classic/Visualizer.tsx:331-341 — keyframe 序列）──
        // Folia 用 framer-motion keyframe: y: [0, -d, 0, d*0.45, 0], scale: [1, 1.01, 1, 0.995, 1]
        // 5 帧循环，easeInOut，repeat: Infinity
        breathElapsed += 1.0 / 60.0
        val breathT = (breathElapsed % breathDuration) / breathDuration
        val frameIndex = (breathT * 5).toInt() % 5
        val frameNext = (frameIndex + 1) % 5
        val frameFrac = (breathT * 5) - frameIndex.toFloat()
        // easeInOut 插值
        val eased = if (frameFrac < 0.5f) 2 * frameFrac * frameFrac else 1 - Math.pow(-2 * frameFrac + 2, 2).toFloat() / 2
        val breathY = (breathYKeyframes[frameIndex] + (breathYKeyframes[frameNext] - breathYKeyframes[frameIndex]) * eased) * breathDistance
        val breathScale = 1f + (breathScaleKeyframes[frameIndex] + (breathScaleKeyframes[frameNext] - breathScaleKeyframes[frameIndex]) * eased - 1f) * breathMultiplier

        // 行容器入场/离场（移植 glowWordTiming.ts:58-91 getGlowWordLineContainerMotion）
        // 根据 lineTransitionMode 选择初始/动画/退出状态
        val containerOpacity = computeLineContainerOpacity(timeSec, line)
        val containerScale = computeLineContainerScale(timeSec, line)
        val containerBlur = computeLineContainerBlur(timeSec, line)

        val centerX = ctx.canvasSize.width / 2f
        val centerY = ctx.canvasSize.height * 0.5f  // h-[70vh] 容器中心

        for (layout in wordLayouts) {
            val status = GlowWordCore.statusOf(layout.word, timeSec, profile)

            // ── 容器动画：opacity / scale / rotate / x / y（移植 glowWordVariants.ts:12-51）──
            val (opacity, scale, rotate, dx, dy) = interpolateContainerAnimation(
                layout, status, timeSec, profile
            )

            // ── 正文动画：color / blur（移植 glowWordVariants.ts:55-83）──
            val (color, blur) = interpolateBodyAnimation(layout, status, timeSec, profile)

            // ── 合唱涟漪（移植 GlowWord.tsx:130-143）──
            // 若 isChorus 且 status==ACTIVE，画扩散圆环
            if (layout.word.isChorus && status == GlowWordStatus.ACTIVE) {
                drawChorusRipple(layout.x + centerX, layout.y + centerY, opacity)
            }

            // ── 绘制 ──
            val finalX = centerX + layout.x + dx
            val finalY = centerY + layout.y + dy + breathY * containerScale
            val finalScale = scale * containerScale * breathScale

            paintBody.color = Color(color).copy(alpha = opacity * containerOpacity)
            paintBody.textSize = lineFontSize * finalScale
            paintBody.setShadowLayer(blur, 0f, 0f, Color(layout.activeColor))

            save()
            translate(finalX, finalY)
            rotateZ(rotate * Math.PI / 180.0)
            drawText(layout.word.text, 0f, 0f, paintBody)
            restore()
        }
    }

    /** 移植 glowWordVariants.ts:12-51 — 容器动画插值 */
    private fun interpolateContainerAnimation(
        layout: WordLayout,
        status: GlowWordStatus,
        time: Double,
        profile: GlowWordProfile,
    ): FloatArray {
        // waiting: opacity=0, scale=0.5, x=config.x+sin(config.y)*100, y=config.y+cos(config.x)*50, rotate=config.rotate+20
        // active: opacity=1, scale=config.scale*1.4, x=config.x, y=config.y, rotate=config.rotate
        // passed: opacity=0.82, scale=config.scale, rotate=config.rotate+config.passedRotate

        return when (status) {
            GlowWordStatus.WAITING -> floatArrayOf(
                0f, 0.5f,
                layout.x + Math.sin(layout.y.toDouble()) * 100f,
                layout.y + Math.cos(layout.x.toDouble()) * 50f,
                layout.rotate + 20f,
            )
            GlowWordStatus.ACTIVE -> floatArrayOf(
                1f, layout.scale * 1.4f,
                layout.x, layout.y,
                layout.rotate,
            )
            GlowWordStatus.PASSED -> floatArrayOf(
                0.82f, layout.scale,
                layout.x, layout.y,
                layout.rotate + layout.passedRotate,
            )
        }
    }

    /** 移植 glowWordVariants.ts:55-83 — 正文动画插值 */
    private fun interpolateBodyAnimation(
        layout: WordLayout,
        status: GlowWordStatus,
        time: Double,
        profile: GlowWordProfile,
    ): Pair<Int, Float> {
        // waiting: baseColor, blur(10px)
        // active: activeColor, blur(0)
        // passed: baseColor, blur(0)

        return when (status) {
            GlowWordStatus.WAITING -> layout.baseColor to 10f
            GlowWordStatus.ACTIVE -> layout.activeColor to 0f
            GlowWordStatus.PASSED -> layout.baseColor to 0f
        }
    }

    /** 移植 glowWordTiming.ts:58-91 — 行容器入场/离场 */
    private fun computeLineContainerOpacity(time: Double, line: LyricLine): Float {
        val mode = line.renderHints?.lineTransitionMode ?: "normal"
        val enterStart = line.startTime
        val enterEnd = enterStart + 0.3  // 近似
        return when {
            time < enterStart -> 0f
            time < enterEnd -> (time - enterStart) / 0.3f  // 线性入场
            else -> 1f
        }
    }

    private fun computeLineContainerScale(time: Double, line: LyricLine): Float {
        val mode = line.renderHints?.lineTransitionMode ?: "normal"
        val enterStart = line.startTime
        return when (mode) {
            "none" -> 1f
            "fast" -> if (time < enterStart) 0.96f else 1f
            else -> if (time < enterStart) 0.9f else 1f
        }
    }

    private fun computeLineContainerBlur(time: Double, line: LyricLine): Float {
        val mode = line.renderHints?.lineTransitionMode ?: "normal"
        val enterStart = line.startTime
        return when (mode) {
            "none" -> 0f
            "fast" -> if (time < enterStart) 4f else 0f
            else -> if (time < enterStart) 10f else 0f
        }
    }

    /** 移植 GlowWord.tsx:130-143 — 合唱涟漪 */
    private fun drawChorusRipple(cx: Float, cy: Float, opacity: Float) {
        // 扩散圆环：scale 1.5→3.5, opacity 0.8→0, 0.5s easeOut
        val elapsed = (System.nanoTime() / 1_000_000.0) % 500  // 500ms 循环
        val t = elapsed / 500.0
        val scale = 1.5 + t * 2.0
        val rippleAlpha = (0.8 * (1 - t)).toFloat()
        val radius = lineFontSize * 0.75f * scale
        paintGlow.color = Color.White.copy(alpha = rippleAlpha)
        paintGlow.style = Paint.Style.STROKE
        paintGlow.strokeWidth = 1f
        drawCircle(cx, cy, radius, paintGlow)
    }

    override fun onExit() {
        wordLayouts = emptyList()
        profile = null
        breathElapsed = 0.0
    }
}
```

### 5.4 关键算法细节

#### spring 物理近似

Folia 用 `framer-motion spring(stiffness=200, damping=20)`。
Android 没有等价的内置 spring 实现，用临界阻尼公式近似：

```kotlin
/** 移植 glowWordVariants.ts:25-33 — spring(stiffness=200, damping=20) 的 Kotlin 近似 */
fun springValue(
    t: Double,          // 时间
    stiffness: Double = 200.0,
    damping: Double = 20.0,
    initial: Float = 0f,
    target: Float = 1f,
): Float {
    val omega = Math.sqrt(stiffness)              // 14.14
    val zeta = damping / (2 * omega)              // 0.707 (欠阻尼)
    val omegaD = omega * Math.sqrt(1 - zeta * zeta) // 10.0
    val decay = Math.exp(-zeta * omega * t)
    val oscillation = Math.cos(omegaD * t)
    val progress = 1 - decay * oscillation
    return initial + (target - initial) * progress.toFloat()
}
```

#### blur 效果映射

CSS `filter: blur(Npx)` 是对整个元素做高斯模糊。
Android 的 `Paint.setShadowLayer(blur, 0, 0, color)` 是阴影模糊，视觉不完全等价。
差异：CSS blur 模糊整个元素（包括内部），Android shadowLayer 只在元素下方画阴影。

**应对方案**：用多层 `drawText` 叠加近似：
- 先画一层带 `shadowLayer` 的文本（辉光层）
- 再画一层纯色文本（正文层）

---

## 6. 子模式 2：FULL_VIEW（移植自 Folia fume）

### 6.1 移植文件清单

| Folia 源文件 | 移植内容 | Kotlin 目标文件 |
|---|---|---|
| `fumeMath.ts:1-59` | clamp/mix/quadraticBezier/easeOutCubic/easeInOutCubic/isCJK/hashString/seeded | `FumeMath.kt` |
| `fumeReveal.ts:1-217` | buildWordRanges/resolvePrintedGraphemeCount/resolveLinePassCutoffTime | `FumeReveal.kt` |
| `fumeTextStyle.ts:1-88` | resolvePassedTextStyle/resolvePassedDimAmount/resolveFumePassedFadeDuration/getActiveColor | `FumeTextStyle.kt` |
| `fumeBlockFrame.ts:1-175` | resolveFumeGlowBases/isFumeBlockOnScreen/resolveFumeBlockTiming/resolveFumeStaticLayers | `FumeBlockFrame.kt` |
| `fumeCamera.ts:1-341` | CAMERA_SCALE_MIN/MAX/resolveSteppedBlockFocusPoint/resolveSmoothBlockFocusPoint/resolveCameraScaleForBlock/resolveCameraRetargetDuration/resolveOverviewRetargetDuration/resolveOverviewFlightBridge/resolveArticleOverviewCamera/resolveFocusBlock | `FumeCamera.kt` |
| `fumeCameraStep.ts:1-412` | createFumeCameraState/syncFumeCameraToArticle/stepFumeCamera/resolveFumeBackgroundView | `FumeCameraStep.kt` |
| `fumeCanvasText.ts:66-109` | createStaticBlockSnapshot | `FumeCanvasText.kt` |
| `fumeLiveRaster.ts:17-119` | FumeLiveRaster（活块光栅化） | `FumeLiveRaster.kt`（新建） |
| `fumeTextMeasure.ts:1-460` | buildSegmentMetas/widthBetweenOffsets/buildGlyphOffsets/buildRenderSegments/buildFontSpec/chooseSingleLineFontPx | `FumeTextMeasure.kt` |
| `useFumeCanvasStage.ts:1-308` | 每帧重绘流程 | `FullViewSubRenderer.kt` |

### 6.2 FumeMath.kt — 数学工具

```kotlin
// visualizer/renderers/lyricsStage/fume/FumeMath.kt
// 移植自 Folia: src/components/visualizer/fume/fumeMath.ts
// Folia 版本: 0.5.27-a16525c

object FumeMath {
    fun clamp(value: Float, min: Float, max: Float): Float =
        maxOf(min, minOf(max, value))

    fun mix(from: Float, to: Float, amount: Float): Float =
        from + (to - from) * amount

    fun quadraticBezier(from: Float, control: Float, to: Float, amount: Float): Float {
        val normalized = clamp(amount, 0f, 1f)
        val inverse = 1 - normalized
        return inverse * inverse * from + 2 * inverse * normalized * control + normalized * normalized * to
    }

    fun easeOutCubic(value: Float): Float {
        val v = clamp(value, 0f, 1f)
        return 1f - Math.pow(1 - v, 3.0).toFloat()
    }

    fun easeInCubic(value: Float): Float {
        val v = clamp(value, 0f, 1f)
        return Math.pow(v, 3.0).toFloat()
    }

    fun easeInOutCubic(value: Float): Float {
        val normalized = clamp(value, 0f, 1f)
        return if (normalized < 0.5f) {
            4 * normalized * normalized * normalized
        } else {
            1f - Math.pow(-2 * normalized + 2, 3.0) / 2f
        }
    }

    /** 移植 fumeMath.ts:31-43 — 延迟辉光包络 */
    fun resolveDelayedGlowEnvelope(progress: Float, peakProgress: Float = 0.8f): Float {
        val normalized = clamp(progress, 0f, 1f)
        val clampedPeak = clamp(peakProgress, 0.05f, 0.95f)
        return if (normalized <= clampedPeak) {
            easeOutCubic(normalized / clampedPeak)
        } else {
            1f - easeInCubic((normalized - clampedPeak) / (1f - clampedPeak))
        }
    }

    /** 移植 fumeMath.ts:45 — CJK 检测 */
    fun isCJK(text: String): Boolean =
        text.any { it in '\u4e00'..'\u9fff' || it in '\u3040'..'\u30ff' || it in '\uac00'..'\ud7af' }

    /** 移植 fumeMath.ts:47-54 — hashString (FNV-1a) */
    fun hashString(input: String): Int {
        var hash = 2166136261
        for (ch in input) {
            hash ^= ch.code
            hash = Math.imul(hash, 16777619)
        }
        return hash ushr 0
    }

    /** 移植 fumeMath.ts:56-59 — seeded random */
    fun seeded(seed: String): Double {
        val hash = hashString(seed)
        return (hash % 10000) / 10000.0
    }

    /** grapheme 拆分（替代 Intl.Segmenter） */
    fun splitGraphemes(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        // Android API 22 无 Intl.Segmenter
        // 用 BreakIterator 或手动 CJK 检测
        return text.toList().map { it.toString() }
    }
}
```

### 6.3 FumeReveal.kt — 逐字打印时序

```kotlin
// visualizer/renderers/lyricsStage/fume/FumeReveal.kt
// 移植自 Folia: src/components/visualizer/fume/fumeReveal.ts

object FumeReveal {

    /** 移植 fumeReveal.ts:10-48 — 从词列表构建 WordRange */
    fun buildWordRanges(
        line: LyricLine,
        graphemes: List<String>,
    ): List<WordRange> {
        if (line.words.isEmpty() || graphemes.isEmpty()) return emptyList()
        val rangedWords = line.words.filter { FumeMath.splitGraphemes(it.text).isNotEmpty() }
        if (rangedWords.isEmpty()) return emptyList()

        val ranges = mutableListOf<WordRange>()
        var cursor = 0
        for (word in rangedWords) {
            val wordGraphemes = FumeMath.splitGraphemes(word.text)
            val start = FumeMath.clamp(cursor, 0, graphemes.size)
            var end = FumeMath.clamp(start + wordGraphemes.size, start, graphemes.size)
            // 附带空格给当前词
            while (end < graphemes.size && graphemes[end].isWhitespace()) end++
            ranges.add(WordRange(
                wordIndex = ranges.size,
                word = word,
                start = start,
                end = end,
                colorStart = start,
                colorEnd = end,
                graphemeTimings = GraphemeTimingBuilder.buildFor(word),
            ))
            cursor = end
        }
        return ranges
    }

    /** 移植 fumeReveal.ts:62-100 — 范围内已打印 grapheme 数
     *  有 syllable 时序时按 graphemeTiming 判定
     *  无时序时按 word reveal progress 线性插值
     */
    fun resolvePrintedGlyphsInRange(range: WordRange, time: Double): Int {
        val length = maxOf(range.end - range.start, 0)
        if (length == 0) return 0
        if (time < range.word.startTime) return 0

        val timedGlyphCount = if (range.word.syllables?.isNotEmpty() == true) {
            minOf(range.graphemeTimings.size, length)
        } else 0

        if (timedGlyphCount > 0) {
            if (time >= range.word.endTime) return length
            var printed = 0
            for (i in 0 until timedGlyphCount) {
                if (time >= range.graphemeTimings[i].startTime) {
                    printed = i + 1
                }
            }
            return FumeMath.clamp(printed, 0, length)
        }

        // 无逐字时序：按词 reveal progress
        val progress = resolveWordRevealProgress(range, time)
        if (progress >= 1f) return length
        return FumeMath.clamp(
            Math.floor(progress * length + 0.2).toInt(),
            if (progress > 0f) 1 else 0, length
        )
    }

    /** 移植 fumeReveal.ts:56-60 — 词 reveal progress */
    private fun resolveWordRevealProgress(range: WordRange, time: Double): Float {
        val wordDuration = maxOf(range.word.endTime - range.word.startTime, 0.05)
        return FumeMath.clamp((time - range.word.startTime) / wordDuration, 0f, 1f)
    }

    /** 移植 fumeReveal.ts:138-174 — 已打印 grapheme 数量（行级） */
    fun resolvePrintedGraphemeCount(
        line: LyricLine,
        wordRanges: List<WordRange>,
        graphemeCount: Int,
        time: Double,
    ): Int {
        if (graphemeCount == 0) return 0
        if (time < line.startTime) return 0
        if (time >= line.endTime) return graphemeCount
        if (wordRanges.isEmpty()) {
            val duration = maxOf(line.endTime - line.startTime, 0.12)
            val progress = FumeMath.clamp((time - line.startTime) / duration, 0f, 1f)
            return FumeMath.clamp(
                Math.floor(progress * graphemeCount + (if (progress > 0) 1 else 0)).toInt(),
                0, graphemeCount
            )
        }
        var printed = 0
        for (range in wordRanges) {
            val partial = resolvePrintedGlyphsInRange(range, time)
            printed = range.start + partial
            if (partial < range.end - range.start) return FumeMath.clamp(printed, 0, graphemeCount)
        }
        return FumeMath.clamp(printed, 0, graphemeCount)
    }

    /** 移植 fumeReveal.ts:107-117 — 行过场截止时间 */
    fun resolveLinePassCutoffTime(
        line: LyricLine,
        nextLineStartTime: Double?,
    ): Double {
        val renderEndTime = line.renderHints?.renderEndTime ?: line.endTime
        if (nextLineStartTime == null) return renderEndTime
        return minOf(renderEndTime, nextLineStartTime)
    }
}
```

### 6.4 FumeBlockFrame.kt — Block 状态计算

```kotlin
// visualizer/renderers/lyricsStage/fume/FumeBlockFrame.kt
// 移植自 Folia: src/components/visualizer/fume/fumeBlockFrame.ts

object FumeBlockFrame {

    /** 移植 fumeBlockFrame.ts:18-29 — 辉光基础值 */
    fun resolveFumeGlowBases(intensity: String, glowIntensity: Float): Pair<Float, Float> {
        val activeGlowBoost = when (intensity) {
            "chaotic" -> 1.15f
            "calm" -> 0.72f
            else -> 0.92f
        } * glowIntensity
        val passedGlowBase = when (intensity) {
            "chaotic" -> 0.95f
            "calm" -> 0.35f
            else -> 0.62f
        } * glowIntensity
        return activeGlowBoost to passedGlowBase
    }

    /** 移植 fumeBlockFrame.ts:32-39 — 是否在屏幕内 */
    fun isOnScreen(
        blockX: Float, blockY: Float, blockW: Float, blockH: Float,
        cameraX: Float, cameraY: Float, cameraScale: Float,
        viewportW: Float, viewportH: Float,
        overscan: Float = 180f,
    ): Boolean {
        val screenLeft = viewportW * 0.5f + (blockX - cameraX) * cameraScale
        val screenTop = viewportH * 0.5f + (blockY - cameraY) * cameraScale
        val screenRight = screenLeft + blockW * cameraScale
        val screenBottom = screenTop + blockH * cameraScale
        return !(screenRight < -overscan || screenLeft > viewportW + overscan ||
                 screenBottom < -overscan || screenTop > viewportH + overscan)
    }

    /** 移植 fumeBlockFrame.ts:58-101 — Block 时序状态 */
    fun resolveBlockTiming(
        block: FumeBlockData,
        time: Double,
        nextLineStartTime: Double?,
        textHoldRatio: Float,
    ): FumeBlockTiming {
        val waitingOpacity = if (block.variant == "hero") 0.06f else 0.035f
        val activeOpacity = if (block.variant == "hero") 0.985f else 0.92f
        val effectiveTextHoldStyle = if (textHoldRatio >= 1f) "standard" else "dimmed"
        val passedStyle = resolvePassedTextStyle(block.variant, effectiveTextHoldStyle)
        val transitionPassedStyle = resolvePassedTextStyle(block.variant, "standard")
        val baselineOffset = block.lineHeight * (if (FumeMath.isCJK(block.fullText)) 0.52f else 0.5f)
        val lineEndTime = block.renderHints?.renderEndTime ?: block.endTime
        val linePassCutoffTime = FumeReveal.resolveLinePassCutoffTime(block.lineData, nextLineStartTime)
        val revealCompleteTime = block.endTime
        val hasRevealCompleted = time >= revealCompleteTime
        val hasPassCutoffReached = time >= linePassCutoffTime
        val lineDuration = maxOf(lineEndTime - block.startTime, 0.18)
        val colorTrailDuration = FumeMath.clamp(
            lineDuration * (if (block.variant == "hero") 0.42f else 0.52f),
            0.45f, 1.45f
        )
        val staticState: String? = when {
            time < block.startTime -> "waiting"
            time >= lineEndTime + colorTrailDuration -> "passed"
            else -> null
        }
        return FumeBlockTiming(
            waitingOpacity = waitingOpacity,
            activeOpacity = activeOpacity,
            effectiveTextHoldStyle = effectiveTextHoldStyle,
            passedStyle = passedStyle,
            transitionPassedStyle = transitionPassedStyle,
            baselineOffset = baselineOffset,
            lineEndTime = lineEndTime,
            linePassCutoffTime = linePassCutoffTime,
            hasRevealCompleted = hasRevealCompleted,
            hasPassCutoffReached = hasPassCutoffReached,
            lineDuration = lineDuration,
            colorTrailDuration = colorTrailDuration,
            staticState = staticState,
        )
    }
}
```

### 6.5 FumeCamera.kt — 相机焦点

```kotlin
// visualizer/renderers/lyricsStage/fume/FumeCamera.kt
// 移植自 Folia: src/components/visualizer/fume/fumeCamera.ts

object FumeCamera {
    const val CAMERA_SCALE_MIN = 0.22f
    const val CAMERA_SCALE_MAX = 2.24f
    const val OVERVIEW_CAMERA_SOURCE = -2

    /** 移植 fumeCamera.ts:142-149 — 相机缩放 */
    fun resolveCameraScaleForBlock(lineHeight: Float, viewportW: Float, viewportH: Float): Float {
        val minSide = maxOf(minOf(viewportW, viewportH), 1f)
        val targetLineHeight = FumeMath.clamp(minSide * 0.115f, 64f, 124f)
        return FumeMath.clamp(targetLineHeight / maxOf(lineHeight, 1f), 0.88f, 2.2f)
    }

    /** 移植 fumeCamera.ts:151-180 — 相机重定向时长 */
    fun resolveCameraRetargetDuration(hints: LineRenderHints): Float {
        if (hints.lineTransitionMode == "none") {
            return FumeMath.clamp(maxOf(hints.rawDuration, 0.08) * 0.34f, 0.04f, 0.075f)
        }
        if (hints.lineTransitionMode == "fast") {
            val enterDur = FumeMath.clamp(hints.rawDuration * 0.45f, 0.045f, 0.06f)
            val exitDur = FumeMath.clamp(hints.rawDuration * 0.22f, 0.03f, 0.04f)
            return FumeMath.clamp(enterDur * 0.5f + exitDur * 0.12f, 0.055f, 0.095f)
        }
        val enterDur = Math.min(0.42f, Math.max(0.22f, Math.max(hints.rawDuration, 0.12f) * 0.34f))
        val linePassHold = if (hints.wordRevealMode == "instant") 0f else 0.06f
        return FumeMath.clamp(enterDur * 0.44f + linePassHold * 0.22f, 0.075f, 0.13f)
    }

    /** 移植 fumeCamera.ts:182-186 — 全景重定向时长 */
    fun resolveOverviewRetargetDuration(viewportW: Float, viewportH: Float): Float {
        val minSide = maxOf(minOf(viewportW, viewportH), 1f)
        return FumeMath.clamp(minSide / 1500f, 0.38f, 0.58f)
    }
}
```

### 6.6 FumeCameraStep.kt — 每帧相机步进

这是 fume 最复杂的文件（412 行）。核心逻辑：

```kotlin
// visualizer/renderers/lyricsStage/fume/FumeCameraStep.kt
// 移植自 Folia: src/components/visualizer/fume/fumeCameraStep.ts

object FumeCameraStep {

    /** 移植 fumeCameraStep.ts:19-45 — 创建初始相机状态 */
    fun createState(): FumeCameraState {
        return FumeCameraState(
            camera = CameraTarget(0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f),
            retarget = CameraRetargetState(
                sourceLineIndex = -1, startedAt = 0.0, duration = 0.18,
                fromX = 0f, fromY = 0f, fromScale = 1f,
                bridgeMode = "none", bridgeWaypointX = 0f, bridgeWaypointY = 0f,
                bridgeWaypointScale = 1f, bridgeWaypointPhase = 0.36,
            ),
            initialized = false,
        )
    }

    /** 移植 fumeCameraStep.ts:48-72 — 同步到文章 */
    fun syncToArticle(state: FumeCameraState, articleW: Float, articleH: Float) {
        if (!state.initialized) {
            state.camera.x = articleW * 0.5f
            state.camera.y = articleH * 0.5f
            state.camera.focusX = articleW * 0.5f
            state.camera.focusY = articleH * 0.5f
            state.camera.scale = 1.18f
            state.camera.focusScale = 1.18f
            state.initialized = true
        } else {
            state.camera.x = FumeMath.clamp(state.camera.x, 0f, articleW)
            state.camera.y = FumeMath.clamp(state.camera.y, 0f, articleH)
            state.camera.scale = FumeMath.clamp(state.camera.scale, FumeCamera.CAMERA_SCALE_MIN, FumeCamera.CAMERA_SCALE_MAX)
            state.camera.focusScale = FumeMath.clamp(state.camera.focusScale, FumeCamera.CAMERA_SCALE_MIN, FumeCamera.CAMERA_SCALE_MAX)
        }
    }

    /** 移植 fumeCameraStep.ts:98-375 — 每帧步进（最复杂函数） */
    fun step(
        state: FumeCameraState,
        time: Double,
        nowMs: Long,
        dt: Double,
        lineIndex: Int,
        viewportW: Float,
        viewportH: Float,
        cameraSpeed: Float,
        cameraTrackingMode: String,  // "stepped" | "smooth"
        staticMode: Boolean,
        intensity: String,
    ): FumeCameraStepResult {
        // 1. 确定 focus block（移植 fumeCamera.ts:308-341 resolveFocusBlock）
        // 2. 计算 target camera position（focus point + scale）
        // 3. 重定向桥接（bridge mode: none/direct/overview）
        // 4. 空闲浮动（float phase）
        // 5. spring 追赶（非桥接时）
        // 6. 更新 state.camera

        // ... 完整实现需逐行移植 fumeCameraStep.ts:98-375
        // 关键参数：
        // - springStrength: mix(208, clamp(15.8/duration², 260, 780), retargetBoost)
        // - damping: mix(24, clamp(sqrt(springStrength)*1.36, 24, 40), retargetBoost)
        // - bridgeCatchUp: 1 - exp(-dt * mix(10.5, 17.5, 1-retargetPhase))
        // - maxVelocity: mix(1320, clamp(distance/(duration*0.28), 2600, 8800), retargetBoost)

        return FumeCameraStepResult(shouldShowOverview = false, overviewTextRestoreProgress = 0f)
    }
}
```

### 6.7 FumeCanvasText.kt — 文字绘制

```kotlin
// visualizer/renderers/lyricsStage/fume/FumeCanvasText.kt
// 移植自 Folia: src/components/visualizer/fume/fumeCanvasText.ts

object FumeCanvasText {

    data class StaticSnapshot(
        val bitmap: Bitmap,
        val padding: Float,
    )

    /** 移植 fumeCanvasText.ts:66-109 — 创建静态快照
     *  ⚠️ 返回 {bitmap, padding}，不是仅 Bitmap
     *  padding 用于后续绘制时定位
     */
    fun createStaticBlockSnapshot(
        block: FumeBlockData,
        fillColor: Int,
        shadowBlur: Float,
        shadowColor: Int,
        rasterScale: Float = 1f,
    ): StaticSnapshot? {
        val padding = maxOf(
            block.fontPx * 0.32f,
            shadowBlur + block.fontPx * 0.08f,
            4f,
        )
        val width = ((block.width + padding * 2) * rasterScale).toInt().coerceAtLeast(1)
        val height = ((block.height + padding * 2) * rasterScale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            textSize = block.fontPx
            setShadowLayer(shadowBlur, 0f, 0f, shadowColor)
        }
        val baselineOffset = block.lineHeight * (if (FumeMath.isCJK(block.fullText)) 0.52f else 0.5f)
        canvas.save()
        canvas.scale(rasterScale, rasterScale)
        for (renderLine in block.renderLines) {
            canvas.drawText(
                renderLine.text,
                renderLine.left + padding,
                renderLine.top + baselineOffset + padding,
                paint,
            )
        }
        canvas.restore()
        paint.setShadowLayer(0f, 0f, 0f, shadowColor)
        return StaticSnapshot(bitmap, padding)
    }

    /** 移植 fumeCanvasText.ts:124-410 — 绘制活动 block（逐字打印+着色+辉光+印章）
     *  这是 fume 最复杂的绘制函数（400+ 行）
     *  核心逻辑：
     *  1. 已打印计数 = FumeReveal.resolvePrintedGraphemeCount(line, wordRanges, graphemes, time)
     *  2. 行辉光：lineGlowEnvelope = FumeMath.resolveDelayedGlowEnvelope(lineProgress, 0.8)
     *     lineGlowAlpha = (hero?0.16:0.12) + envelope * (hero?0.26:0.2) * glowIntensity
     *     lineGlowBlur = (hero?12:8) + envelope * (fontPx * (hero?0.7:0.52)) * glowIntensity
     *  3. 逐字着色三阶段：
     *     - 未到词开始: alpha=waitingOpacity, fill=primaryColor
     *     - 词进行中: alpha=mix(waiting, active, easedProgress), fill=mix(primary, active, 0.22+progress*0.78)
     *     - 词颜色尾迹: alpha=mix(active, passed, trailProgress), fill=mix(active, primary, 0.18+trail*0.82)
     *  4. 打印印章：在词激活窗口内画矩形光块
     */
}
```

### 6.8 FumeLiveRaster.kt — 活动块光栅化

```kotlin
// visualizer/renderers/lyricsStage/fume/FumeLiveRaster.kt
// 移植自 Folia: src/components/visualizer/fume/fumeLiveRaster.ts

object FumeLiveRaster {

    const val LIVE_IDLE_MS = 5_000L
    const val LIVE_CANVAS_GRANULE = 256
    const val LIVE_RASTER_LEVELS_PER_OCTAVE = 48

    /** 移植 fumeLiveRaster.ts:24-26 — 确定光栅缩放级别
     *  2 ** (ceil(log2(scale) * 48) / 48) — 每级别缩放 ≤ 2^(1/48) ≈ 1.5%
     */
    fun resolveLiveRasterScale(scale: Float): Float {
        val log2 = Math.log(scale.coerceAtLeast(0.001f)) / Math.log(2.0)
        return (2.0.pow(Math.ceil(log2 * LIVE_RASTER_LEVELS_PER_OCTAVE - 1e-9) / LIVE_RASTER_LEVELS_PER_OCTAVE)).toFloat()
    }

    data class LiveRasterResult(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val x: Float,
        val y: Float,
        val worldWidth: Float,
        val worldHeight: Float,
    )

    /** 移植 fumeLiveRaster.ts:58-90 — 绘制活动块到离屏 Bitmap
     *  1. 计算 raster scale
     *  2. 计算 glowReach padding
     *  3. 裁剪到 visible 矩形
     *  4. 按 256px granule 增长 canvas
     *  5. 在 scale 变换下绘制 drawFumeLiveBlock
     */
    fun draw(
        block: FumeBlockData,
        timing: FumeBlockTiming,
        deviceScale: Float,
        visible: RectF,
        now: Long,
    ): LiveRasterResult? {
        val scale = resolveLiveRasterScale(deviceScale)
        val glowReach = (12f + block.fontPx * 0.7f) * maxOf(1f, 1.5f)
        val padLeft = block.x - glowReach
        val padTop = block.y - block.lineHeight * 0.5f - glowReach
        val padRight = block.x + block.width + glowReach
        val padBottom = block.y + block.lineHeight * 0.5f + glowReach

        val clipLeft = maxOf(visible.left, padLeft)
        val clipTop = maxOf(visible.top, padTop)
        val clipRight = minOf(visible.right, padRight)
        val clipBottom = minOf(visible.bottom, padBottom)

        var width = ((clipRight - clipLeft) * scale).toInt().coerceAtLeast(1)
        var height = ((clipBottom - clipTop) * scale).toInt().coerceAtLeast(1)
        // 对齐到 granule
        width = (width + LIVE_CANVAS_GRANULE - 1) / LIVE_CANVAS_GRANULE * LIVE_CANVAS_GRANULE
        height = (height + LIVE_CANVAS_GRANULE - 1) / LIVE_CANVAS_GRANULE * LIVE_CANVAS_GRANULE

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-clipLeft, -clipTop)
        // 调用 drawFumeLiveBlock（此处省略完整参数）
        // FumeCanvasText.drawFumeLiveBlock(block, timing, ...)
        canvas.restore()

        return LiveRasterResult(
            bitmap = bitmap,
            width = width,
            height = height,
            x = clipLeft,
            y = clipTop,
            worldWidth = width / scale,
            worldHeight = height / scale,
        )
    }

    /** 移植 fumeLiveRaster.ts:107-114 — 清扫空闲光栅 */
    fun sweep(now: Long, entries: MutableMap<String, Pair<Bitmap, Long>>) {
        entries.entries.removeAll { (_, entry) ->
            now - entry.second > LIVE_IDLE_MS
        }
    }

    /** 移植 fumeLiveRaster.ts:116-118 — 清除全部 */
    fun clear(entries: MutableMap<String, Pair<Bitmap, Long>>) {
        sweep(Long.MAX_VALUE, entries)
    }
}
```

### 6.8 FullViewSubRenderer.kt — 主渲染器

```kotlin
// visualizer/renderers/lyricsStage/FullViewSubRenderer.kt
// 移植自 Folia: src/components/visualizer/fume/useFumeCanvasStage.ts
// Folia 版本: 0.5.27-a16525c

class FullViewSubRenderer : LyricsSubRenderer {

    // ── 成员变量 ──
    private val cameraState = FumeCameraStep.createState()
    private val snapshotCache = mutableMapOf<String, Pair<Bitmap, Long>>()  // key → (bitmap, lastUsed)
    private var articleLayout: FumeArticleLayout? = null
    private var hasPrintedContent: Boolean = false  // 移植 useFumeCanvasStage.ts:78 — 一次性翻转
    private val liveRasterCache = mutableMapOf<String, Pair<Bitmap, Long>>()

    // 移植 useFumeCanvasStage.ts:29 — 快照空闲回收阈值
    private const val SNAPSHOT_IDLE_MS = 15_000L

    // Paint 对象（零分配约束）
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintShadow = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onEnter(ctx: RenderContext) {
        // ── 构建文章版面（移植 useFumeCanvasStage.ts:101-289 的初始化部分）──
        // 1. 从 ctx.lyricLines 构建 block 列表
        // 2. 计算文章尺寸（多列布局）
        // 3. 预计算每个 block 的 word ranges / grapheme timings
        // 4. 初始化相机（syncFumeCameraToArticle）

        val lines = ctx.lyricLines
        if (lines.isEmpty()) return

        // 简化：每行一个 block，单列布局
        // 完整实现需移植 fumeTextMeasure.ts 的文字测量逻辑
        val viewportH = ctx.canvasSize.height
        val columns = 2  // 简化
        val gap = 48f

        // 构建 articleLayout（FumeArticleLayout）
        // ...

        FumeCameraStep.syncToArticle(cameraState, articleW, articleH)
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val timeSec = ctx.nowMs / 1000.0
        val dt = 1.0 / 60.0  // 假设 60fps
        val article = articleLayout ?: return

        // ── 移植 useFumeCanvasStage.ts:78 — hasPrintedContent 一次性翻转 ──
        if (!hasPrintedContent && timeSec >= article.firstRenderableStartTime) {
            hasPrintedContent = true
        }

        // ── 移植 useFumeCanvasStage.ts:101-289 — 每帧重绘 ──
        // 1. 步进相机
        val result = FumeCameraStep.step(
            cameraState, timeSec, ctx.nowMs, dt,
            ctx.lyricLineIndex, ctx.canvasSize.width, ctx.canvasSize.height,
            cameraSpeed = 1f, cameraTrackingMode = "smooth",
            staticMode = false, intensity = "normal",
        )

        // 2. 相机变换
        save()
        val viewportCx = ctx.canvasSize.width / 2f
        val viewportCy = ctx.canvasSize.height / 2f
        translate(viewportCx, viewportCy)
        scale(cameraState.camera.scale)
        translate(-cameraState.camera.x, -cameraState.camera.y)

        // 3. 遍历屏幕内的 block
        val glowBases = FumeBlockFrame.resolveFumeGlowBases("normal", 1f)
        val visibleRect = RectF(
            -cameraState.camera.x * cameraState.camera.scale + viewportCx,
            -cameraState.camera.y * cameraState.camera.scale + viewportCy,
            (-cameraState.camera.x + ctx.canvasSize.width) * cameraState.camera.scale + viewportCx,
            (-cameraState.camera.y + ctx.canvasSize.height) * cameraState.camera.scale + viewportCy,
        )

        for (block in article.blocks) {
            val nextStart = if (block.lineIndex + 1 < article.blocks.size) {
                article.blocks[block.lineIndex + 1].startTime
            } else null

            val timing = FumeBlockFrame.resolveBlockTiming(
                block, timeSec, nextStart, 1f
            )

            // 静态 block 用快照
            if (timing.staticState != null) {
                val snapshot = getSnapshot(block, timing, glowBases)
                if (snapshot != null) {
                    val alpha = if (timing.staticState == "passed") timing.passedStyle.opacity else timing.waitingOpacity
                    drawBitmap(
                        snapshot.bitmap,
                        block.x - snapshot.padding,
                        block.y - snapshot.padding,
                        paintText.apply { this.alpha = (alpha * 255).toInt() },
                    )
                }
            } else {
                // 活动 block 用 FumeLiveRaster
                val raster = FumeLiveRaster.draw(block, timing, 1f, visibleRect, ctx.nowMs)
                if (raster != null) {
                    drawBitmap(raster.bitmap, raster.x, raster.y, paintText)
                } else {
                    // 回退：直接绘制
                    FumeCanvasText.drawFumeLiveBlock(block, timing, timeSec, glowBases, /* ... */)
                }
            }
        }
        restore()

        // 清扫空闲快照/光栅
        FumeLiveRaster.sweep(ctx.nowMs, liveRasterCache)
        snapshotCache.entries.removeAll { (_, entry) ->
            ctx.nowMs - entry.second > SNAPSHOT_IDLE_MS
        }
    }

    private fun getSnapshot(
        block: FumeBlockData,
        timing: FumeBlockTiming,
        glowBases: Pair<Float, Float>,
    ): FumeCanvasText.StaticSnapshot? {
        val key = "${block.lineIndex}-${block.variant}"
        snapshotCache[key]?.let { return FumeCanvasText.StaticSnapshot(it.first, 0f) }

        val fillColor = if (timing.staticState == "passed") {
            timing.passedStyle.fillColor
        } else {
            ctx_palette_primary  // 简化
        }
        val shadowBlur = if (timing.staticState == "passed") {
            timing.passedStyle.shadowBlur
        } else 0f

        val snapshot = FumeCanvasText.createStaticBlockSnapshot(
            block, fillColor, shadowBlur, /* shadowColor */ 0,
        )
        if (snapshot != null) {
            snapshotCache[key] = Pair(snapshot.bitmap, System.nanoTime() / 1_000_000)
        }
        return snapshot
    }

    override fun onExit() {
        snapshotCache.values.forEach { it.recycle() }
        snapshotCache.clear()
        articleLayout = null
    }
}
```

### 6.9 fume 移植要点

#### 文字测量

Folia 用 `@chenglou/pretext` 的 `measureNaturalWidth` / `layoutWithLines` / `prepareWithSegments`。
Android 等价：`Paint.measureText(text)` + `StaticLayout`。

关键差异：
- pretext 按 grapheme 拆分并逐字测量宽度
- Android `Paint.measureText` 测量整串宽度
- 需要逐字测量时，用 `Paint.measureText(text.substring(0, i))` 累加

#### 文章版面

Folia 的 fume 将整曲歌词排版成多列"文章版面"，相机在其中移动。
Android 实现：
- 版面 Bitmap 尺寸可能很大（长歌 50+ 行 × 2 列）
- **不预渲染整张版面**，而是每帧只渲染屏幕内的 block
- 静态 block 用快照 Bitmap 缓存

#### 内存管理

- 快照 Bitmap 在 `onExit` 时 `recycle()`
- 用 `BitmapPool` 或简单的 `Map<String, Bitmap>` 缓存
- 长歌场景下快照可能很多，需要限制缓存数量

---

## 7. 子模式 3：TILT_PULSE（移植自 Folia tilt）

### 7.1 移植文件清单

| Folia 源文件 | 移植内容 | Kotlin 目标文件 |
|---|---|---|
| `tilt/VisualizerTilt.tsx:20-28` | determineLineCount | `TiltLayout.kt` |
| `tilt/VisualizerTilt.tsx:103-178` | buildCharTimings | `TiltLayout.kt` |
| `tilt/VisualizerTilt.tsx:180-198` | getCharPulseIntensity | `TiltLayout.kt` |
| `tilt/VisualizerTilt.tsx:200-204` | measureAtSize | `TiltLayout.kt` |
| `tilt/VisualizerTilt.tsx:206-209` | seededRandom | `TiltLayout.kt` |
| `tilt/VisualizerTilt.tsx:211-331` | buildTiltLayout（含 overflow/resplit） | `TiltLayout.kt` |
| `tilt/VisualizerTilt.tsx:233-242` | tilt style 选择 | `TiltLayout.kt` |
| `tilt/VisualizerTilt.tsx:431-537` | draw（normal vs tilt 行） | `TiltPulseSubRenderer.kt` |
| `utils/lyrics/graphemeTiming.ts` | buildWordGraphemeTimings | `GraphemeTimingBuilder.kt` |
| `utils/lyrics/sentenceLayout.ts:38-545` | splitIntoSentences | `SentenceLayout.kt`（新建） |

### 7.2 TiltLayout.kt — 布局与时序

```kotlin
// visualizer/renderers/lyricsStage/tilt/TiltLayout.kt
// 移植自 Folia: src/components/visualizer/tilt/VisualizerTilt.tsx
// Folia 版本: 0.5.27-a16525c

object TiltLayout {

    const val CHAR_REF_LENGTH = 20
    const val LOG_OFFSET = 4
    private val LINE_THRESHOLDS = floatArrayOf(0.45f, 1.05f, 1.7f)

    /** 移植 VisualizerTilt.tsx:49-52 — resplit / scale floor 常量 */
    const val RESPLIT_THRESHOLD = 1.6f
    const val SCALE_FLOOR_NORMAL = 0.55f
    const val SCALE_FLOOR_TILT = 0.5f

    /** 移植 VisualizerTilt.tsx:20-28 — 确定行数
     *  ⚠️ normalized = log(charCount + LOG_OFFSET) / log(CHAR_REF_LENGTH + LOG_OFFSET)
     *     = log(charCount + 4) / log(24)，不是 log(24)
     */
    fun determineLineCount(charCount: Int, seed: Double, splitProbability: Double): Int {
        val normalized = Math.log(charCount + LOG_OFFSET.toDouble()) / Math.log((CHAR_REF_LENGTH + LOG_OFFSET).toDouble())
        val jitter = seededRandom(seed, 1) * 0.6 + 0.7
        val score = normalized * jitter * splitProbability
        return when {
            score < LINE_THRESHOLDS[0] -> 1
            score < LINE_THRESHOLDS[1] -> 2
            score < LINE_THRESHOLDS[2] -> 3
            else -> 4
        }
    }

    /** 移植 VisualizerTilt.tsx:180-198 — 脉冲强度 */
    fun getCharPulseIntensity(currentTime: Double, timing: GraphemeTiming): Float {
        val rawDuration = maxOf(timing.endTime - timing.startTime, 0.05)
        val duration = rawDuration.coerceIn(0.2, 0.9)
        val elapsed = currentTime - timing.startTime
        if (elapsed < 0) return 0f
        if (elapsed <= duration) {
            return Math.sin((elapsed / duration) * Math.PI).toFloat()
        }
        val afterElapsed = elapsed - duration
        val afterglowRamp = duration * 1.2
        if (afterElapsed >= afterglowRamp) return 0.25f
        return 0.25f * (afterElapsed / afterglowRamp).toFloat()
    }

    /** 移植 VisualizerTilt.tsx:200-204 — 文字测量
     *  Folia 用 @chenglou/pretext 的 prepareWithSegments + layoutWithLines
     *  Android 等价：Paint.measureText 逐段累加（见 FumeTextMeasure.kt 的 buildSegmentMetas）
     */
    fun measureAtSize(text: String, pxSize: Float, paint: Paint, fontSpec: String): Float {
        paint.textSize = pxSize
        return paint.measureText(text)
    }

    /** 移植 VisualizerTilt.tsx:206-209 — seededRandom
     *  ⚠️ 公式是 Math.sin(seed * 1000 + offset) * 10000，不是 sin(seed + offset)
     */
    private fun seededRandom(seed: Double, offset: Int): Double {
        val x = Math.sin(seed * 1000 + offset) * 10000.0
        return x - Math.floor(x)
    }
}
```

### 7.3 SentenceLayout.kt — 句子级拆分

```kotlin
// visualizer/renderers/lyricsStage/tilt/SentenceLayout.kt
// 移植自 Folia: src/utils/lyrics/sentenceLayout.ts
// Folia 版本: 0.5.27-a16525c

object SentenceLayout {

    data class Segment(
        val text: String,
        val isTilt: Boolean = false,
        val isShortLastLine: Boolean = false,
    )

    /** 移植 sentenceLayout.ts:38-545 — 渐进式拆分
     *  拆分优先级：标点 → 括号/引号 → 西文词 → CJK 空格 → 特殊字符
     *  → secondarySplit（词粒度）→ mergeSentences（合并过短）
     */
    fun splitIntoSentences(text: String, targetCount: Int, timeSeed: Double): List<Segment> {
        if (targetCount <= 1 || text.isEmpty()) return listOf(Segment(text))

        // 移植 splitByPunctuation（sentenceLayout.ts:104）
        var segments = splitByPunctuation(text)

        if (segments.size < targetCount) {
            segments = splitByBracketsQuotes(segments)
        }
        if (segments.size < targetCount) {
            segments = splitByWesternWords(segments)
        }
        if (segments.size < targetCount) {
            segments = splitCJKBySpace(segments)
        }
        if (segments.size < targetCount) {
            segments = splitBySpecialChars(segments)
        }
        if (segments.size < targetCount) {
            segments = secondarySplit(segments, targetCount)
        }
        if (segments.size > targetCount) {
            segments = mergeSentences(segments, targetCount)
        }

        return segments.map { Segment(it) }
    }

    private fun splitByPunctuation(text: String): List<String> =
        text.split(Regex("[，。；！？、…·\\.\,\;\\!\\?]+")).filter { it.isNotEmpty() }

    private fun splitByBracketsQuotes(segments: List<String>): List<String> {
        // 最外层括号对：「」『』《》【】｛｝［］[]()（）""'
        val result = mutableListOf<String>()
        for (seg in segments) {
            val parts = seg.split(Regex("[「」『』《》【】｛｝［］\\[\\]\\(\\)（）\\"\\']+"))
            result.addAll(parts.filter { it.isNotEmpty() })
        }
        return if (result.isEmpty()) segments else result
    }

    private fun splitByWesternWords(segments: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (seg in segments) {
            val parts = seg.split(Regex("\\s+"))
            result.addAll(parts.filter { it.isNotEmpty() })
        }
        return if (result.isEmpty()) segments else result
    }

    private fun splitCJKBySpace(segments: List<String>): List<String> {
        // CJK 文本按空格拆分
        val result = mutableListOf<String>()
        for (seg in segments) {
            val parts = seg.split(Regex("\\s+"))
            if (parts.size > 1) result.addAll(parts.filter { it.isNotEmpty() })
            else result.add(seg)
        }
        return result
    }

    private fun splitBySpecialChars(segments: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (seg in segments) {
            val parts = seg.split(Regex("[：:\\/／\\\\|｜~～]+"))
            result.addAll(parts.filter { it.isNotEmpty() })
        }
        return if (result.isEmpty()) segments else result
    }

    private fun secondarySplit(segments: List<String>, targetCount: Int): List<String> {
        // 词粒度拆分：在最接近中点的词边界处拆分
        val result = mutableListOf<String>()
        for (seg in segments) {
            if (result.size >= targetCount) { result.add(seg); continue }
            val words = seg.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.size <= 1) { result.add(seg); continue }
            val mid = words.size / 2
            // 拆成两半
            result.add(words.take(mid).joinToString(" "))
            result.add(words.drop(mid).joinToString(" "))
        }
        return result
    }

    private fun mergeSentences(segments: List<String>, targetCount: Int): List<String> {
        // 贪心合并最短相邻对
        val result = segments.toMutableList()
        while (result.size > targetCount) {
            var minLen = Int.MAX_VALUE
            var minIdx = 0
            for (i in 0 until result.size - 1) {
                val len = result[i].length + result[i + 1].length
                if (len < minLen) { minLen = len; minIdx = i }
            }
            result[minIdx] = result[minIdx] + result[minIdx + 1]
            result.removeAt(minIdx + 1)
        }
        return result
    }
}
```

### 7.4 GraphemeTimingBuilder.kt — Grapheme 时序构建

```kotlin
// visualizer/renderers/lyricsStage/GraphemeTimingBuilder.kt
// 移植自 Folia: src/utils/lyrics/graphemeTiming.ts

object GraphemeTimingBuilder {

    /** 移植 graphemeTiming.ts:51-59 — 词→grapheme 时序 */
    fun buildFor(word: LyricWord): List<GraphemeTiming> {
        if (word.syllables?.isNotEmpty() == true) {
            return word.syllables.flatMap { syl ->
                buildEven(syl.text, syl.startTime, syl.endTime)
            }
        }
        return buildEven(word.text, word.startTime, word.endTime)
    }

    /** 移植 graphemeTiming.ts:29-49 — 均分时序 */
    private fun buildEven(text: String, startTime: Double, endTime: Double): List<GraphemeTiming> {
        val graphemes = text.toList().map { it.toString() }
        if (graphemes.isEmpty()) return emptyList()
        val duration = maxOf(endTime - startTime, 0.0)
        val unit = duration / graphemes.size
        return graphemes.mapIndexed { i, ch ->
            GraphemeTiming(
                char = ch,
                startTime = startTime + unit * i,
                endTime = if (i == graphemes.lastIndex) endTime else startTime + unit * (i + 1),
            )
        }
    }
}
```

### 7.4 TiltPulseSubRenderer.kt — 渲染器

```kotlin
// visualizer/renderers/lyricsStage/TiltPulseSubRenderer.kt
// 移植自 Folia: src/components/visualizer/tilt/VisualizerTilt.tsx

class TiltPulseSubRenderer : LyricsSubRenderer {

    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintGlow = Paint(Paint.ANTI_ALIAS_FLAG)

    // 字符时序缓存（onEnter 时预计算）
    private var charTimings: List<GraphemeTiming> = emptyList()
    private var lineCount: Int = 1
    private var fontSize: Float = 0f

    // ── 行拆分结果（onEnter 时预计算）──
    private var segments: List<Segment> = emptyList()
    private var segmentTimings: List<List<GraphemeTiming>> = emptyList()
    private var tiltIndices: Set<Int> = emptySet()
    private var scaleMultiplier: Float = 1f

    override fun onEnter(ctx: RenderContext) {
        val line = ctx.currentLyricLine?.let { parseToLyricLine(it, ctx.lyricWords) }
        if (line == null) return

        // ── 移植 VisualizerTilt.tsx:211-250 — 字体规格 ──
        val viewportW = ctx.canvasSize.width
        val measureMaxPx = maxOf(viewportW * 0.06875f, 5.625f * 16f * 1f)
        val normalBasePx = measureMaxPx
        val tiltBasePx = normalBasePx * 1.4
        fontSize = normalBasePx

        // ── 移植 VisualizerTilt.tsx:20-28 — 确定行数 ──
        val charCount = line.fullText.length
        lineCount = TiltLayout.determineLineCount(charCount, line.startTime, 1.0)

        // ── 移植 VisualizerTilt.tsx:229 — SentenceLayout 拆分 ──
        // 先按 SentenceLayout 拆成 sentence 级 segments
        val sentenceSegs = SentenceLayout.splitIntoSentences(line.fullText, lineCount, line.startTime)

        // ── 移植 VisualizerTilt.tsx:233-242 — tilt style 选择 ──
        // 对每个 segment 掷骰子决定是否 tilt
        val lineSeed = line.startTime
        val tiltStyleProbability = 0.3f  // tuning.tiltStyleProbability
        tiltIndices = sentenceSegs.indices.filter { i ->
            val roll = TiltLayout.seededRandom(lineSeed, 100 + i)
            roll < tiltStyleProbability
        }.toSet()

        // 如果没有任何 segment 被选为 tilt，强制选第一个
        if (tiltIndices.isEmpty() && sentenceSegs.isNotEmpty()) {
            tiltIndices = setOf(0)
        }

        // 标记 segments
        segments = sentenceSegs.mapIndexed { i, seg ->
            Segment(seg.text, isTilt = i in tiltIndices)
        }

        // ── 移植 VisualizerTilt.tsx:258-331 — overflow / resplit ──
        // 测量每个 segment 的宽度，检查是否溢出
        val availableWidth = viewportW * 0.85f  // 可用宽度（留边距）
        val tiltFontSpec = "italic 300 ${tiltBasePx}px sans-serif"
        val normalFontSpec = "400 ${normalBasePx}px sans-serif"

        // 测量各 segment 宽度
        val segWidths = segments.mapIndexed { i, seg ->
            val isTilt = i in tiltIndices
            val fontPx = if (isTilt) tiltBasePx else normalBasePx
            TiltLayout.measureAtSize(seg.text, fontPx, paintText, if (isTilt) tiltFontSpec else normalFontSpec)
        }

        val tiltWidth = segWidths.filterIndexed { i, _ -> i in tiltIndices }.sum()
        val normalWidth = segWidths.filterIndexed { i, _ -> i !in tiltIndices }.sum()
        val maxOverflow = maxOf(tiltWidth, normalWidth) / availableWidth

        if (maxOverflow > 1f) {
            if (maxOverflow >= TiltLayout.RESPLIT_THRESHOLD) {
                // ── 移植 :283-321 — resplit ──
                val extraSplitsNeeded = minOf(4, maxOf(segments.size + 1,
                    Math.ceil((tiltWidth + normalWidth) / (availableWidth * 1.45f)).toInt()))
                if (extraSplitsNeeded > segments.size) {
                    val resplitSegs = SentenceLayout.splitIntoSentences(line.fullText, extraSplitsNeeded, lineSeed)
                    // resplit 后重新掷 tilt（移植 :299-304）
                    val resplitTiltRoll = TiltLayout.seededRandom(lineSeed, 300)
                    if (resplitTiltRoll < tiltStyleProbability && resplitSegs.isNotEmpty()) {
                        val picked = (TiltLayout.seededRandom(lineSeed, 301) * resplitSegs.size).toInt()
                        // 标记 picked 为 tilt
                    }
                    segments = resplitSegs.map { Segment(it.text, isTilt = false) }
                    tiltIndices = emptySet()
                    // 重新测量
                    val newWidths = segments.mapIndexed { i, seg ->
                        TiltLayout.measureAtSize(seg.text, normalBasePx, paintText, normalFontSpec)
                    }
                    val widest = newWidths.maxOrNull() ?: 1f
                    scaleMultiplier = maxOf(TiltLayout.SCALE_FLOOR_NORMAL, availableWidth / widest)
                } else {
                    // scale down
                    scaleMultiplier = maxOf(TiltLayout.SCALE_FLOOR_TILT, availableWidth / tiltWidth)
                }
            } else {
                // scale down only
                scaleMultiplier = if (tiltWidth > normalWidth) {
                    maxOf(TiltLayout.SCALE_FLOOR_TILT, availableWidth / tiltWidth)
                } else {
                    maxOf(TiltLayout.SCALE_FLOOR_NORMAL, availableWidth / normalWidth)
                }
            }
        }

        // ── 移植 VisualizerTilt.tsx:103-178 — 构建字符时序 ──
        charTimings = buildCharTimings(line)
    }

    private fun buildCharTimings(line: LyricLine): List<GraphemeTiming> {
        // 移植 VisualizerTilt.tsx:103-178
        // 1. 用 grapheme 拆分 fullText
        // 2. 有逐字时间戳（word.syllables）时按 syllable 分配
        // 3. 无逐字时间戳时按词均分：charDuration = wordDuration / wordNonSpaceCount
        // 4. 兜底：avgCharDuration = totalDuration / nonSpaceGraphemes
        return line.words.flatMap { word ->
            GraphemeTimingBuilder.buildFor(word)
        }
    }

    // ── 字符布局缓存（onEnter 时按 segment 预计算）──
    private data class CharLayout(
        val char: String,
        val x: Float,
        val y: Float,
        val scale: Float,
        val isTilt: Boolean,
        val isSpace: Boolean,
    )

    private var charLayouts: List<CharLayout> = emptyList()
    private var normalFontSize: Float = 0f
    private var tiltFontSize: Float = 0f

    private fun buildCharLayouts(ctx: RenderContext) {
        // 移植 VisualizerTilt.tsx:431-537 — 逐字符布局
        // normal line: fontSize=normalFontSize, lineHeight=1.35, letterSpacing=0.08em, color=normal
        // tilt line: fontSize=tiltFontSize, fontStyle=italic, lineHeight=1.25, letterSpacing=0.15em, color=tilt
        // yOffset = tiltFontPx / 6（仅 tilt 行）
        // yStagger: isEven ? -1 : 1（上下交替）
        // pulse scale: normal=1+intensity*0.15, tilt=1+intensity*0.18
        // space minWidth: normal=0.25em, tilt=0.35em

        normalFontSize = fontSize * scaleMultiplier
        tiltFontSize = normalFontSize * 1.4f

        val centerX = ctx.canvasSize.width / 2f
        val centerY = ctx.canvasSize.height / 2f

        val layouts = mutableListOf<CharLayout>()
        for ((segIdx, seg) in segments.withIndex()) {
            val isTilt = segIdx in tiltIndices
            val fontPx = if (isTilt) tiltFontSize else normalFontSize
            val yOffset = if (isTilt) tiltFontSize / 6f else 0f
            val letterSpacing = if (isTilt) 0.15f else 0.08f  // em
            val lineHeight = if (isTilt) 1.25f else 1.35f

            val chars = seg.text.toList().map { it.toString() }
            var x = 0f
            var yStagger = 1f  // 上下交替

            for (ch in chars) {
                val isSpace = ch.isWhitespace()
                val charWidth = if (isSpace) {
                    fontPx * (if (isTilt) 0.35f else 0.25f)  // minWidth in em
                } else {
                    paintText.textSize = fontPx
                    paintText.measureText(ch) + fontPx * letterSpacing
                }

                val y = if (isTilt && !isSpace) {
                    yStagger * yOffset
                } else {
                    0f
                }

                layouts.add(CharLayout(
                    char = if (isSpace) "\u00A0" else ch,
                    x = x,
                    y = y,
                    scale = 1f,  // pulse scale 在 draw 时计算
                    isTilt = isTilt,
                    isSpace = isSpace,
                ))

                x += charWidth
                yStagger *= -1f
            }

            // segment 换行（每个 segment 占一行）
        }

        // 整体居中
        val totalWidth = layouts.maxOfOrNull { it.x } ?: 0f
        val startX = centerX - totalWidth / 2f
        for (layout in layouts) {
            layout.x += startX
        }

        charLayouts = layouts
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val timeSec = ctx.nowMs / 1000.0
        if (charLayouts.isEmpty()) {
            buildCharLayouts(ctx)
        }
        if (charLayouts.isEmpty()) return

        // ── 移植 VisualizerTilt.tsx:431-537 — 逐字符绘制 ──
        // normal 行: color=normalColor, pulse scale=1+intensity*0.15
        // tilt 行: color=tiltColor, italic, yOffset=tiltFontPx/6 上下交替, pulse scale=1+intensity*0.18

        val bassBoost = frame.bass  // 0..1
        val centerY = ctx.canvasSize.height / 2f
        val normalColor = Color.White.copy(alpha = 0.85f)
        val tiltColor = ctx.palette.accentColor

        var segIdx = 0
        var yCursor = centerY - (segments.size * fontSize * 1.3f) / 2f

        for ((segIdx, seg) in segments.withIndex()) {
            val isTilt = segIdx in tiltIndices
            val fontPx = if (isTilt) tiltFontSize else normalFontSize
            val lineHeight = if (isTilt) 1.25f else 1.35f
            val yOffset = if (isTilt) tiltFontSize / 6f else 0f

            // 该 segment 的字符布局起始索引
            val startIdx = if (segIdx == 0) 0 else {
                // 计算前序 segment 的字符数
                segments.take(segIdx).sumOf { it.text.length }
            }
            val segCharCount = seg.text.length

            var yStagger = 1f
            var x = 0f
            val segChars = charLayouts.filterIndexed { i, _ ->
                i >= startIdx && i < startIdx + segCharCount
            }

            for (layout in segChars) {
                val timingIdx = startIdx + segChars.indexOf(layout)
                val timing = charTimings.getOrNull(timingIdx)
                val pulse = timing?.let { TiltLayout.getCharPulseIntensity(timeSec, it) } ?: 0f

                // pulse scale: normal=1+pulse*bassBoost*0.15, tilt=1+pulse*bassBoost*0.18
                val pulseScale = if (layout.isTilt) {
                    1f + pulse * bassBoost * 0.18f
                } else {
                    1f + pulse * bassBoost * 0.15f
                }

                // y offset（仅 tilt 行）
                val charY = if (layout.isTilt && !layout.isSpace) {
                    yStagger * yOffset * pulse
                } else {
                    0f
                }

                paintText.color = if (layout.isTilt) tiltColor else normalColor
                paintText.textSize = fontPx * pulseScale
                paintText.isFakeBoldText = false
                // italic: Android Paint 无直接 italic，用 textSkewX 近似
                paintText.textSkewX = if (layout.isTilt) -0.2f else 0f
                paintText.setShadowLayer(
                    if (layout.isTilt) 8f else 4f, 0f, 0f,
                    if (layout.isTilt) tiltColor.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.2f)
                )

                save()
                translate(layout.x + centerX - charLayouts.maxOfOrNull { it.x } ?: 0f, yCursor + charY)
                drawText(layout.char, 0f, 0f, paintText)
                restore()

                x += fontPx * (if (layout.isSpace) 0.3f else 0.6f)
                yStagger *= -1f
            }

            yCursor += fontPx * lineHeight
        }
    }

    override fun onExit() {
        charTimings = emptyList()
        lineCount = 1
        fontSize = 0f
    }
}
```

---

## 8. 子模式 4：PARTITA（移植自 Folia partita）

### 8.1 移植文件清单

| Folia 源文件 | 移植内容 | Kotlin 目标文件 |
|---|---|---|
| `partita/PartitaChunk.tsx:35-48` | Chunk 状态机 | `PartitaSubRenderer.kt` |
| `partita/PartitaChunk.tsx:76-93` | Chunk 动画 | `PartitaSubRenderer.kt` |
| `partita/PartitaChunk.tsx:95-182` | 引导线（L 形） | `PartitaSubRenderer.kt` |
| `partita/partitaLayout.ts:97-125` | buildPartitaWordConfigs | `PartitaLayout.kt` |
| `partita/partitaLayout.ts:139-248` | buildSequentialColumns | `PartitaLayout.kt` |
| `GlowWord.tsx` | 词级动画（共享，复用 GlowWordCore） | `GlowWordCore.kt` |

### 8.2 PartitaLayout.kt — 分栏布局

```kotlin
// visualizer/renderers/lyricsStage/partita/PartitaLayout.kt
// 移植自 Folia: src/components/visualizer/partita/partitaLayout.ts
// Folia 版本: 0.5.27-a16525c

object PartitaLayout {

    data class WordLayoutConfig(
        val id: String,
        val x: Float,
        val y: Float,
        val rotate: Float,
        val scale: Float,
        val marginBottom: String,   // ⚠️ Folia 是 string（CSS rem 值如 "0.4rem"）
        val alignSelf: String,      // ⚠️ Folia 是 string（"auto" | "center"）
        val passedRotate: Float,
    )

    data class ChunkData(
        val words: List<LyricWord>,
        val config: WordLayoutConfig,
        val rowIndex: Int,
        val isStaggeredLeft: Boolean,
    )

    data class PartitaLayoutResult(
        val columns: List<List<ChunkData>>,
        val totalGraphemes: Int,
    )

    /** 移植 partitaLayout.ts:97-125 — 词级随机偏移 */
    fun buildWordConfigs(
        words: List<LyricWord>,
        intensity: String,
    ): List<WordLayoutConfig> {
        val baseSpread = when (intensity) {
            "chaotic" -> 15f
            "calm" -> 0f
            else -> 6f
        }
        val baseRotate = when (intensity) {
            "chaotic" -> 8f
            "calm" -> 0f
            else -> 3f
        }
        val wordSeed = words.firstOrNull()?.startTime ?: 0.0

        return words.mapIndexed { idx, _ ->
            val s = wordSeed + idx * 7.13
            fun random(offset: Int): Double {
                val x = Math.sin(s + offset) * 10000.0
                return x - Math.floor(x)
            }
            WordLayoutConfig(
                id = "w${idx}",
                x = (random(1) - 0.5).toFloat() * baseSpread * 2,
                y = (random(2) - 0.5).toFloat() * baseSpread * 2,
                rotate = (random(3) - 0.5).toFloat() * baseRotate * 2,
                scale = 1f,
                marginBottom = "0",       // ⚠️ string 类型
                alignSelf = "auto",       // ⚠️ string 类型
                passedRotate = (random(8) - 0.5).toFloat() * 20f,
            )
        }
    }

    /** 移植 partitaLayout.ts:139-248 — 分栏布局 */
    fun buildSequentialColumns(
        line: LyricLine,
        windowHeight: Float,
        intensity: String,
    ): PartitaLayoutResult {
        val totalGraphemes = line.fullText.replace(Regex("\\s+"), "").length
        val isChaotic = intensity == "chaotic"
        val isCalm = intensity == "calm"

        // 目标行数
        val availableHeight = windowHeight * 0.65f
        val targetRowCount = maxOf(1, (availableHeight / 100f).toInt())
        val actualRowCount = minOf(line.words.size, targetRowCount)

        // 确定性随机
        var seed = line.startTime
        fun random(): Double {
            val x = Math.sin(seed++) * 10000.0
            return x - Math.floor(x)
        }

        // 分块（长度不均匀）
        val chunks = mutableListOf<List<LyricWord>>()
        var remaining = line.words.size
        var remainingChunks = actualRowCount
        var unitIndex = 0

        for (c in 0 until actualRowCount) {
            val isLast = c == actualRowCount - 1
            val avg = remaining / remainingChunks.toDouble()
            val chunkLength = if (isLast) {
                remaining
            } else {
                val max = Math.ceil(avg * 1.5).toInt()
                val randVal = random()
                Math.max(1, Math.min(max, (avg + (randVal - 0.5) * avg).roundToInt()))
            }.coerceIn(1, remaining - (remainingChunks - 1))

            chunks.add(line.words.subList(unitIndex, unitIndex + chunkLength))
            unitIndex += chunkLength
            remaining -= chunkLength
            remainingChunks--
        }

        // 构建 ChunkData
        val column = chunks.mapIndexed { rowIndex, chunkWords ->
            val rowBias = rowIndex - (actualRowCount - 1) / 2f
            val isStaggeredLeft = rowIndex % 2 == 0
            val staggerMagnitude = 0 + random() * 100f  // staggerMin=0, staggerMax=100
            val staggerX = if (isStaggeredLeft) -staggerMagnitude else staggerMagnitude
            val staggerScale = if (isCalm) 1f else 0.8f + random().toFloat() * 0.9f

            ChunkData(
                words = chunkWords,
                config = WordLayoutConfig(
                    id = "row${rowIndex}-${chunkWords.first().startTime}",
                    x = staggerX,
                    y = if (isCalm) 0f else rowBias * 2.5f,
                    rotate = if (isChaotic) (if (isStaggeredLeft) -3f else 3f) else 0f,
                    scale = staggerScale,
                    marginBottom = if (isChaotic) "0.4rem" else "0.6rem",  // ⚠️ string
                    alignSelf = "center",                                    // ⚠️ string
                    passedRotate = (if (rowIndex % 2 == 0) 1 else -1) * (if (isChaotic) 6f else 3f),
                ),
                rowIndex = rowIndex,
                isStaggeredLeft = isStaggeredLeft,
            )
        }

        return PartitaLayoutResult(columns = listOf(column), totalGraphemes = totalGraphemes)
    }
}
```

### 8.3 PartitaSubRenderer.kt — 渲染器

```kotlin
// visualizer/renderers/lyricsStage/PartitaSubRenderer.kt
// 移植自 Folia: src/components/visualizer/partita/PartitaChunk.tsx
//              + partitaLayout.ts
// Folia 版本: 0.5.27-a16525c

class PartitaSubRenderer : LyricsSubRenderer {

    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintGuide = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintGuideGlow = Paint(Paint.ANTI_ALIAS_FLAG)

    private var chunks: List<PartitaLayout.ChunkData> = emptyList()
    private var profile: GlowWordProfile? = null
    private var lineFontSize: Float = 0f

    override fun onEnter(ctx: RenderContext) {
        val line = ctx.currentLyricLine?.let { parseToLyricLine(it, ctx.lyricWords) }
        if (line == null) return

        profile = GlowWordCore.profileFor(line)
        lineFontSize = maxOf(36f, minOf(72f, ctx.canvasSize.width * 0.06f))

        // ── 移植 partitaLayout.ts:139-248 — 分栏布局 ──
        val intensity = when (ctx.quality) {
            VisualQuality.LOW -> "calm"
            VisualQuality.HIGH -> "chaotic"
            else -> "normal"
        }
        val result = PartitaLayout.buildSequentialColumns(line, ctx.canvasSize.height, intensity)
        chunks = result.columns.firstOrNull() ?: emptyList()
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val timeSec = ctx.nowMs / 1000.0
        val profile = this@PartitaSubRenderer.profile ?: return

        val centerX = ctx.canvasSize.width / 2f
        var yOffset = ctx.canvasSize.height / 2f

        for (chunk in chunks) {
            // ── 移植 PartitaChunk.tsx:35-48 — Chunk 状态机 ──
            val chunkStart = chunk.words.first().startTime
            val chunkEnd = GlowWordCore.activeEndTime(chunk.words.last(), profile)
            val status = when {
                timeSec < chunkStart - profile.wordLookahead -> ChunkStatus.WAITING
                timeSec <= chunkEnd -> ChunkStatus.ACTIVE
                else -> ChunkStatus.PASSED
            }

            // ── 移植 PartitaChunk.tsx:76-93 — Chunk 动画 ──
            val chunkOpacity = when (status) {
                ChunkStatus.WAITING -> 0f
                ChunkStatus.ACTIVE -> 1f
                ChunkStatus.PASSED -> 0.6f
            }

            // ── 绘制引导线（移植 PartitaChunk.tsx:95-182）──
            if (chunk.isStaggeredLeft) {
                drawGuideLine(centerX + chunk.config.x, yOffset, chunkOpacity, ctx.palette.accentColor)
            } else {
                drawGuideLine(centerX + chunk.config.x, yOffset, chunkOpacity, ctx.palette.accentColor)
            }

            // ── 绘制词（复用 GlowWordCore 词级动画）──
            for (word in chunk.words) {
                val wordStatus = GlowWordCore.statusOf(word, timeSec, profile)
                val wordLayout = PartitaLayout.buildWordConfigs(chunk.words, "normal")
                // 绘制词...
            }

            yOffset += lineFontSize * 1.5f
        }
    }

    /** 移植 PartitaChunk.tsx:95-182 — L 形引导线 */
    private fun drawGuideLine(x: Float, y: Float, opacity: Float, color: Color) {
        // 竖线：w=1px, h=32px, bottom=-16px
        // 横线：h=1px, width=100%+36px, bottom=-8px
        paintGuide.color = color.copy(alpha = opacity * 0.22f)
        paintGuideGlow.color = color.copy(alpha = opacity * 0.35f)
        paintGuideGlow.setShadowLayer(10f, 0f, 0f, color.copy(alpha = opacity * 0.45f))

        // 竖线
        drawLine(x - 8f, y, x - 8f, y + 32f, paintGuide)
        // 横线
        drawLine(x - 16f, y + 16f, x + 100f, y + 16f, paintGuide)
    }

    override fun onExit() {
        chunks = emptyList()
        profile = null
    }
}
```

---

## 9. 切换机制

### 9.1 按键切换

在 `VisualizerStage.kt:347` 的 `onPreviewKeyEvent` 中：

```kotlin
.onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown) {
        when (e.key) {
            Key.DirectionLeft -> { onPrevTheme(); true }
            Key.DirectionRight -> { onNextTheme(); true }
            Key.DirectionUp -> {
                if (theme == VisualizerTheme.LYRICS_STAGE) {
                    onLyricsStageNextMode(); true
                } else {
                    // 现有 LOFI_CITY / HIGHWAY 逻辑
                    false
                }
            }
            Key.DirectionDown -> {
                if (theme == VisualizerTheme.LYRICS_STAGE) {
                    onLyricsStagePrevMode(); true
                } else {
                    false
                }
            }
            else -> false
        }
    } else false
}
```

### 9.2 手势切换

在 `VisualizerStage.kt` 的 `pointerInput` 中：

```kotlin
.pointerInput(Unit) {
    detectVerticalDragGestures(
        onDragStart = { },
        onDragEnd = {
            if (theme != VisualizerTheme.LYRICS_STAGE) return@detectVerticalDragGestures
            if (dragDistanceY < -threshold) onLyricsStageNextMode()
            else if (dragDistanceY > threshold) onLyricsStagePrevMode()
        }
    )
}
```

- 阈值：屏高的 15%
- 切换时 300ms 淡入淡出（alpha 插值）

### 9.3 切换约束

- ⛔ **仅手动切换**：不自动定时切换
- ⛔ 不随歌曲切换自动重置子模式
- ⛔ 不随段落/副歌切换自动变化
- 子模式选择持久化到 `VisualizerPrefs`

---

## 10. 集成序列

### 10.1 VisualizerTheme 枚举新增

```kotlin
// data/model/AppSettings.kt:181 — VisualizerTheme 枚举末尾追加
LYRICS_STAGE(R.string.visualizer_theme_lyrics_stage, "歌词舞台", Tier.ULTRA, "53"),
```

> 归 ULTRA 的原因：FULL_VIEW 子模式需要整曲版面 + 相机变换，与 WORLD 同理。
> LOW/MEDIUM 画质下 FULL_VIEW 不可用（由子渲染器自行判断）。

### 10.2 VisualizerRendererFactory 新增

```kotlin
// visualizer/VisualizerRendererFactory.kt — when 分支新增
LYRICS_STAGE -> LyricsStageRenderer(),
```

### 10.3 LyricsStageRenderer 实现

```kotlin
// visualizer/renderers/LyricsStageRenderer.kt
class LyricsStageRenderer : VisualizerRenderer {
    override val theme = VisualizerTheme.LYRICS_STAGE

    private var mode: LyricsStageMode = LyricsStageMode.Default
    private var subRenderer: LyricsSubRenderer? = null
    private var crossfadeAlpha: Float = 1f
    private var isTransitioning: Boolean = false

    override fun onEnter(ctx: RenderContext) {
        // 读取持久化的子模式
        mode = LyricsStageMode.fromOrdinal(loadModeOrdinal())
        createSubRenderer(mode, ctx)
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val current = subRenderer ?: return
        if (isTransitioning) {
            // 300ms 淡入淡出
            val elapsed = transitionElapsedMs
            val t = (elapsed / 300f).coerceIn(0f, 1f)
            crossfadeAlpha = if (t < 0.5f) t * 2f else 1f - (t - 0.5f) * 2f
            if (t >= 1f) {
                isTransitioning = false
                crossfadeAlpha = 1f
            }
            withAlpha(crossfadeAlpha) {
                current.draw(frame, ctx)
            }
        } else {
            current.draw(frame, ctx)
        }
    }

    override fun onExit() {
        subRenderer?.onExit()
        subRenderer = null
    }

    /** 上下键/手势调用 */
    fun nextMode() {
        mode = (mode.ordinal + 1) % LyricsStageMode.entries.size
        switchTo(mode)
    }

    fun prevMode() {
        mode = (mode.ordinal - 1 + LyricsStageMode.entries.size) % LyricsStageMode.entries.size
        switchTo(mode)
    }

    private fun switchTo(newMode: LyricsStageMode) {
        // 保存子模式
        saveModeOrdinal(newMode.ordinal)
        // 创建新子渲染器
        createSubRenderer(newMode, /* ctx */)
        // 开始 300ms 过渡
        isTransitioning = true
        transitionElapsedMs = 0L
    }

    private fun createSubRenderer(mode: LyricsStageMode, ctx: RenderContext) {
        subRenderer?.onExit()
        subRenderer = when (mode) {
            LyricsStageMode.DOT_MATRIX -> DotMatrixSubRenderer()
            LyricsStageMode.GLOW_LINE -> GlowLineSubRenderer()
            LyricsStageMode.FULL_VIEW -> {
                // FULL_VIEW 需要 HIGH 画质
                if (ctx.quality == VisualQuality.HIGH) FullViewSubRenderer()
                else GlowLineSubRenderer()  // 降级到 GLOW_LINE
            }
            LyricsStageMode.TILT_PULSE -> TiltPulseSubRenderer()
            LyricsStageMode.PARTITA -> PartitaSubRenderer()
        }
        subRenderer?.onEnter(ctx)
    }

    // 持久化
    private fun loadModeOrdinal(): Int = /* 从 prefs 读取 */ 0
    private fun saveModeOrdinal(ord: Int) { /* 写入 prefs */ }

    private var transitionElapsedMs: Long = 0L
}
```

### 10.4 VisualizerPrefs 新增

```kotlin
// data/prefs/VisualizerPrefs.kt — 新增
private val keyLyricsStageMode = intPreferencesKey("settings_lyrics_stage_mode")

val lyricsStageModeOrdinal: Int
    get() = prefs.getInt(keyLyricsStageMode, 0)

suspend fun setLyricsStageModeOrdinal(ord: Int) =
    prefs.setInt(keyLyricsStageMode, ord)
```

### 10.5 VisualizerViewModel 新增

```kotlin
// ui/viewmodel/VisualizerViewModel.kt — 新增
private val _lyricsStageMode = MutableStateFlow(LyricsStageMode.Default)
val lyricsStageMode: StateFlow<LyricsStageMode> = _lyricsStageMode.asStateFlow()

fun setLyricsStageMode(mode: LyricsStageMode) {
    _lyricsStageMode.value = mode
    viewModelScope.launch {
        prefs.visualizerPrefs.setLyricsStageModeOrdinal(mode.ordinal)
    }
}
```

### 10.6 字符串资源

```xml
<!-- res/values/strings.xml -->
<string name="visualizer_theme_lyrics_stage">歌词舞台</string>

<!-- res/values-en/strings.xml -->
<string name="visualizer_theme_lyrics_stage">Lyrics Stage</string>
```

### 10.7 VisualizerStage 按键/手势接入

在 `VisualizerStage.kt` 中，`onPreviewKeyEvent` 和 `pointerInput` 的
`LYRICS_STAGE` 分支调用 `VisualizerViewModel.setLyricsStageMode()`。

---

## 11. 文件改动清单

### 11.1 新增文件

| 文件 | 职责 | 移植来源 |
|---|---|---|
| `visualizer/renderers/LyricsStageRenderer.kt` | 主渲染器（子模式管理+分发） | 新架构 |
| `visualizer/renderers/lyricsStage/LyricsStageMode.kt` | 子模式枚举 | 新 |
| `visualizer/renderers/lyricsStage/LyricsSubRenderer.kt` | 子渲染器接口 | 新 |
| `visualizer/renderers/lyricsStage/GlowWordCore.kt` | 词状态机+时序（shared） | Folia `GlowWord.tsx` + `glowWordTiming.ts` |
| `visualizer/renderers/lyricsStage/GlowLineSubRenderer.kt` | 子模式 1：逐字行光 | Folia `classic/Visualizer.tsx` |
| `visualizer/renderers/lyricsStage/DotMatrixSubRenderer.kt` | 子模式 0：点阵 | NASMusicTV E23 |
| `visualizer/renderers/lyricsStage/GraphemeTimingBuilder.kt` | Grapheme 时序构建 | Folia `graphemeTiming.ts` |
| `visualizer/renderers/lyricsStage/fume/FumeMath.kt` | 数学工具 | Folia `fumeMath.ts` |
| `visualizer/renderers/lyricsStage/fume/FumeReveal.kt` | 逐字打印时序 | Folia `fumeReveal.ts` |
| `visualizer/renderers/lyricsStage/fume/FumeTextStyle.kt` | 文字样式 | Folia `fumeTextStyle.ts` |
| `visualizer/renderers/lyricsStage/fume/FumeBlockFrame.kt` | Block 状态计算 | Folia `fumeBlockFrame.ts` |
| `visualizer/renderers/lyricsStage/fume/FumeCamera.kt` | 相机焦点 | Folia `fumeCamera.ts` |
| `visualizer/renderers/lyricsStage/fume/FumeCameraStep.kt` | 每帧相机步进 | Folia `fumeCameraStep.ts` |
| `visualizer/renderers/lyricsStage/fume/FumeCanvasText.kt` | 文字绘制 | Folia `fumeCanvasText.ts` |
| `visualizer/renderers/lyricsStage/fume/FumeTextMeasure.kt` | 文字测量 | Folia `fumeTextMeasure.ts` |
| `visualizer/renderers/lyricsStage/FullViewSubRenderer.kt` | 子模式 2：全屏纵览 | Folia `useFumeCanvasStage.ts` |
| `visualizer/renderers/lyricsStage/fume/FumeTypes.kt` | fume 数据类型 | Folia `fumeTypes.ts` |
| `visualizer/renderers/lyricsStage/tilt/TiltLayout.kt` | 倾斜布局+时序 | Folia `VisualizerTilt.tsx` |
| `visualizer/renderers/lyricsStage/TiltPulseSubRenderer.kt` | 子模式 3：倾斜律动 | Folia `VisualizerTilt.tsx` |
| `visualizer/renderers/lyricsStage/partita/PartitaLayout.kt` | 分栏布局 | Folia `partitaLayout.ts` |
| `visualizer/renderers/lyricsStage/PartitaSubRenderer.kt` | 子模式 4：分栏引导 | Folia `PartitaChunk.tsx` |

### 11.2 修改文件

| 文件 | 改动 |
|---|---|
| `data/model/AppSettings.kt` | `VisualizerTheme` 新增 `LYRICS_STAGE` 枚举项 |
| `visualizer/VisualizerRendererFactory.kt` | `when` 分支新增 `LYRICS_STAGE` |
| `visualizer/RenderContext.kt` | 新增 `lyricLines` / `lyricLineStartTimesMs` / `lyricWords` |
| `ui/components/VisualizerStage.kt` | 上下键 + 手势处理新增 `LYRICS_STAGE` 分支 |
| `data/prefs/VisualizerPrefs.kt` | 新增 `lyricsStageModeOrdinal` 读写 |
| `ui/viewmodel/VisualizerViewModel.kt` | 新增 `lyricsStageMode` 状态管理 |
| `res/values/strings.xml` | 新增 `visualizer_theme_lyrics_stage` |
| `res/values-en/strings.xml` | 新增英文翻译 |

### 11.3 提取共享

- `visualizer/renderers/LyricsDotMatrixCore.kt` — 从现有 `LyricsDotMatrixRenderer` 提取核心算法

---

## 12. 测试策略

### 12.1 纯函数门禁测试

以下函数是纯计算，可直接单元测试：

| 测试类 | 测试函数 | 测试内容 |
|---|---|---|
| `GlowWordCoreTest` | `statusOf_*` | 词状态机：waiting/active/passed 边界 |
| `GlowWordCoreTest` | `activeEndTime_*` | activeEndTime 计算：instant/fast/normal |
| `GlowWordCoreTest` | `displayDuration_*` | wordDisplayDuration 最小值约束 |
| `GlowWordCoreTest` | `profileFor_*` | LineRenderHints → GlowWordProfile 转换 |
| `FumeMathTest` | `easeOutCubic_*` | 缓动函数边界值 |
| `FumeMathTest` | `easeInOutCubic_*` | 缓动函数中点对称 |
| `FumeMathTest` | `quadraticBezier_*` | 贝塞尔曲线端点 |
| `FumeMathTest` | `resolveDelayedGlowEnvelope_*` | 辉光包络峰值 |
| `FumeMathTest` | `isCJK_*` | CJK 字符检测 |
| `FumeMathTest` | `hashString_*` | FNV-1a 哈希一致性 |
| `FumeRevealTest` | `resolvePrintedGraphemeCount_*` | 逐字打印数量边界 |
| `FumeRevealTest` | `resolveLinePassCutoffTime_*` | 行过场截止时间 |
| `FumeBlockFrameTest` | `resolveFumeGlowBases_*` | 辉光基础值 |
| `FumeBlockFrameTest` | `isOnScreen_*` | 屏幕可见性判断 |
| `FumeBlockFrameTest` | `resolveBlockTiming_*` | Block 时序状态 |
| `FumeCameraTest` | `resolveCameraScaleForBlock_*` | 相机缩放 |
| `FumeCameraTest` | `resolveCameraRetargetDuration_*` | 重定向时长 |
| `FumeCameraTest` | `resolveOverviewRetargetDuration_*` | 全景重定向时长 |
| `TiltLayoutTest` | `determineLineCount_*` | 行数确定（对数+jitter+阈值） |
| `TiltLayoutTest` | `getCharPulseIntensity_*` | 脉冲强度（sin 波 + 余晖） |
| `GraphemeTimingBuilderTest` | `buildFor_*` | 词→grapheme 时序 |
| `GraphemeTimingBuilderTest` | `buildEven_*` | 均分时序 |
| `PartitaLayoutTest` | `buildWordConfigs_*` | 词级随机偏移 |
| `PartitaLayoutTest` | `buildSequentialColumns_*` | 分栏布局 |

### 12.2 集成测试

| 测试 | 内容 |
|---|---|
| `LyricsStageModeTest` | 子模式枚举循环：DOT_MATRIX → GLOW_LINE → FULL_VIEW → TILT_PULSE → PARTITA → DOT_MATRIX |
| `LyricsStageRendererTest` | 渲染器创建/销毁生命周期 |
| `LyricsStageRendererTest` | 子模式切换 300ms 过渡 |
| `VisualizerThemeStringsGateTest` | 更新双语显示名（新增 LYRICS_STAGE） |
| `FxCoverageScanTest` | 更新渲染器类数（25 → 46：新增 21 个类） |

### 12.3 真机回归

- 创维 5.1.1（低端基准）
- 模拟器（API 22）
- 真机（API 34）

测试项：
- 每个子模式的画面效果
- 上下键切换子模式
- 手势上下滑动切换子模式
- 左右键切换顶级主题
- 切歌后子模式保持
- 画质降档时 FULL_VIEW 降级

---

## 13. 画质适配

| 子模式 | Tier | LOW | MEDIUM | HIGH |
|---|---|---|---|---|
| DOT_MATRIX | ADV (`needsParticleBudget=true`) | ✅ 粒子 900 | ✅ 粒子 3600 | ✅ |
| GLOW_LINE | ADV | ✅ 单层辉光 | ✅ 双层辉光 | ✅ 双层+外发光 |
| FULL_VIEW | ULTRA | ❌ 降级到 GLOW_LINE | ❌ 降级到 GLOW_LINE | ✅ 完整版面 |
| TILT_PULSE | ADV | ✅ 每行 1 段 | ✅ 每行 2 段 | ✅ 每行 4 段 |
| PARTITA | ADV | ✅ 简化引导线 | ✅ 标准引导线 | ✅ 引导线+辉光 |

> FULL_VIEW 归 ULTRA 的原因：整曲版面 Bitmap + 相机变换，与现有 WORLD 同理。
> LOW/MEDIUM 画质下自动降级到 GLOW_LINE。

---

## 14. 风险与约束

| 风险 | 说明 | 应对 |
|---|---|---|
| fume 复杂度 | `drawFumeLiveBlock` 400+ 行，逐字着色逻辑复杂 | 按函数逐行移植，先移植数学工具和状态计算，再移植绘制 |
| spring 物理 | framer-motion 的 spring 参数与 Android 不完全等价 | 实现临界阻尼公式：`exp(-zeta*omega*t)*cos(omega_d*t)` |
| blur 效果 | CSS `filter: blur(Npx)` 与 Android `Paint.setShadowLayer` 视觉不完全等价 | 用多层 shadowLayer 叠加近似 |
| fume 静态快照 | Canvas2D 的 `drawImage` 在 Android 用 `drawBitmap`，需要处理 Bitmap 回收 | 复用 Bitmap 池，`Bitmap.recycle()` 在 `onExit` 时调用 |
| RenderContext 扩展 | fume 需要全曲歌词数据，当前只有 current/next | 新增字段，由 VisualizerStage 的歌词源每帧更新 |
| Intl.Segmenter | Android API 22 无 `Intl.Segmenter` | 用 `BreakIterator` 或手动 CJK 检测替代 grapheme 拆分 |
| pretext 文字测量 | `@chenglou/pretext` 在 Android 无等价物 | 用 `Paint.measureText` 逐字测量 |
| 现有 E23 兼容 | 用户可能已习惯 E23 作为独立主题 | `LYRICS_DOT_MATRIX` 主题保留不动 |

---

## 15. 不做的事

- ⛔ 不做自动切换（定时/随歌/随段落）
- ⛔ 不移植 WebGL/three.js/Pixi 主题（设备兼容性风险）
- ⛔ 不自行设计视觉参数（所有数值来自 Folia 源码）
- ⛔ 不做逐字时间戳的实时解析（依赖现有 `lyricWordTimestamps`）
- ⛔ 不做 AI 配色（复用现有 `CoverPalette`）
- ⛔ 不做歌词下载/匹配（复用现有 `LyricsManager`）
- ⛔ 不做模组系统（Folia 的 Folium 模组系统是桌面端特性）
