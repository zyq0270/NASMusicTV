// 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/fumeTypes.ts — Project Folia version 0.5.27-a16525c
package com.nasmusic.tv.visualizer.renderers.lyricsStage.fume

import android.graphics.Bitmap
import com.nasmusic.tv.visualizer.renderers.lyricsStage.GraphemeTiming
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricLine
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricWord

/**
 * fume 版面的数据结构：块、渲染行、渲染段、相机。
 *
 * 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/fumeTypes.ts — Project Folia version 0.5.27-a16525c
 *
 * ## 与 Folia 原文件的差异（均为 Android 侧的等价替换）
 *  - `PreparedTextWithSegments` / `layoutWithLines(...)` 是 `@chenglou/pretext` 的类型；
 *    Android 等价物见 `FumeTextMeasure.kt` 的 `FumePreparedText` / `FumeTextLayout`
 *    （`Paint.measureText` 逐前缀累计 + 自写断行）。
 *  - `StaticBlockSnapshot { canvas, padding }` → `StaticSnapshot(bitmap, padding)`（Canvas → Bitmap）。
 *  - `CameraTarget` / `CameraRetargetState` 在 Folia 里是可变字段的结构体（Folia 直接赋值整个对象）；
 *    Kotlin 端改成**就地修改**的可变类（逐帧零分配，且避免每帧 new 一个状态对象）。
 *  - Folia 的 `Line` → 本仓的 `LyricLine`；`Word` → `LyricWord`。
 */
object FumeTypes {

    const val VARIANT_BODY = "body"
    const val VARIANT_HERO = "hero"

    /** 移植 fumeTypes.ts:8-11 `ViewportSize`（Android：可变对象 ⇒ 逐帧零分配） */
    class ViewportSize(
        var width: Float = 0f,
        var height: Float = 0f,
    )

    /** 移植 fumeTypes.ts:13-17 `SegmentMeta` */
    data class SegmentMeta(
        val graphemeStart: Int,
        val graphemeEnd: Int,
        val graphemeCount: Int,
    )

    /** 移植 fumeTypes.ts:19-27 `WordRange` */
    class WordRange(
        val wordIndex: Int,
        val word: LyricWord,
        /** 该词在整行 grapheme 序列里的起点 */
        val start: Int,
        /** 该词在整行 grapheme 序列里的终点（不含） */
        val end: Int,
        /** 着色范围的起点 */
        val colorStart: Int,
        /** 着色范围的终点（不含） */
        val colorEnd: Int,
        /** 逐 grapheme 的时序（由 GraphemeTimingBuilder 生成） */
        val graphemeTimings: List<GraphemeTiming>,
    )

    /** 移植 fumeTypes.ts:42-52 `RenderSegmentSlice` */
    class RenderSegmentSlice(
        val text: String,
        /** 整行 grapheme 序列里的起点 */
        val start: Int,
        /** 整行 grapheme 序列里的终点（不含） */
        val end: Int,
        /** 相对本渲染行的起点 */
        val localStart: Int,
        /** 相对本渲染行的终点（不含） */
        val localEnd: Int,
        /** 本段在本渲染行里的 x 偏移 */
        val x: Float,
        /** 本段的宽度 */
        val width: Float,
        /** 本段是否覆盖了整段（未被行边界切过） */
        val isFullSegment: Boolean,
        /** 段内逐 grapheme 的前缀宽度（offsets[0] = 0，长度 = grapheme 数 + 1） */
        val measuredGlyphOffsets: FloatArray,
    )

    /** 移植 fumeTypes.ts:29-40 `RenderLineSlice` */
    class RenderLineSlice(
        val id: String,
        val text: String,
        /** 本行覆盖的 grapheme 区间（整行坐标系） */
        val start: Int,
        val end: Int,
        val graphemes: List<String>,
        /** 本行内逐 grapheme 的前缀宽度 */
        val glyphOffsets: FloatArray,
        /**
         * 逐 grapheme 是否含 CJK 字（Folia `isCJK(grapheme)`，fumeCanvasText.ts:349）。
         *
         * ⚠️ **版面期算好**：本仓的 `FumeMath.isCJK` 每次调用都要 `Regex(...)` 重新编译模式，
         *    而 Folia 在**每个 grapheme 每帧**都判一次 ⇒ 逐帧调用等于「每帧编译 N 次正则」。
         */
        val graphemeIsCjk: BooleanArray,
        val segments: List<RenderSegmentSlice>,
        /** 本行在块内的 x 偏移 */
        val left: Float,
        /** 本行在块内的 y 偏移 */
        val top: Float,
        /** 本行的宽度 */
        val width: Float,
    )

    /**
     * 移植 fumeTypes.ts:54-74 `FumeBlock`
     *
     * 快照缓存键（`waiting` / `passed` / `passed:standard`）在版面构建时一次算好，
     * 逐帧不再拼字符串 —— Folia 在 `useFumeCanvasStage` 里每帧拼一次，这里改成预计算。
     */
    class FumeBlock(
        val id: String,
        /** 该块对应的歌词行下标 */
        val sourceLineIndex: Int,
        val line: LyricLine,
        /** `body` 或 `hero` */
        val variant: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val innerWidth: Float,
        val fontPx: Float,
        val lineHeight: Float,
        /** 该块用的字重（640 body / 780 hero） */
        val fontWeight: Int,
        val prepared: FumeTextMeasure.FumePreparedText,
        val layout: FumeTextMeasure.FumeTextLayout,
        val graphemes: List<String>,
        /**
         * 整行是否含 CJK 字（Folia `isCJK(block.line.fullText)`，fumeBlockFrame.ts:69 /
         * fumeCanvasText.ts:89）。
         *
         * ⚠️ **必须在版面构建时算好并缓存**：Folia 的 `isCJK` 是 JS 正则字面量（V8 按站点缓存），
         * 而本仓的 `FumeMath.isCJK` 每次调用都要 `Regex(...)` **重新编译一个模式** ⇒
         * 放进逐帧路径就是「每块每帧编译一次正则」。
         */
        val isCjkLine: Boolean,
        val segmentMetas: List<SegmentMeta>,
        val wordRanges: List<WordRange>,
        /** 每个 grapheme 属于哪个词区间（-1 = 不属于任何词） */
        val wordRangeIndexByOffset: IntArray,
        /** 每个 grapheme 属于哪个着色区间（-1 = 不属于任何词） */
        val colorRangeIndexByOffset: IntArray,
        /** 每个词区间的激活色（Folia 的 `getActiveColor`，在版面构建时算好） */
        val activeColors: IntArray,
        val renderLines: List<RenderLineSlice>,
        /** 等待态快照的缓存键 */
        val snapshotKeyWaiting: String,
        /** 唱过态快照的缓存键（按 effectiveTextHoldStyle） */
        val snapshotKeyPassed: String,
        /** 唱过态「标准样式」快照的缓存键（暗色淡出时的交叉淡入用） */
        val snapshotKeyPassedStandard: String,
    )

    /** 移植 fumeTypes.ts:76-81 `FumePaperBounds` */
    data class FumePaperBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    /** 移植 fumeTypes.ts:83-95 `FumeArticleLayout` */
    class FumeArticleLayout(
        val width: Float,
        val height: Float,
        val viewportHeight: Float,
        val columns: Int,
        val gap: Float,
        val paperBounds: FumePaperBounds,
        val blocks: List<FumeBlock>,
        /** 歌词行下标 → 块 */
        val blockBySourceLineIndex: Map<Int, FumeBlock>,
        /** 按歌词时间排序的块 */
        val chronologicalBlocks: List<FumeBlock>,
        val firstRenderableStartTime: Double,
        val lastChronologicalRenderEndTime: Double,
    )

    /** 移植 fumeTypes.ts:97-104 `FumeArticleLayoutMetrics` */
    data class FumeArticleLayoutMetrics(
        val width: Float,
        val height: Float,
        val viewportHeight: Float,
        val columns: Int,
        val gap: Float,
        val paperBounds: FumePaperBounds,
    )

    /**
     * 移植 fumeTypes.ts:106-109 `StaticBlockSnapshot`
     *
     * Android：`HTMLCanvasElement` → `android.graphics.Bitmap`。
     */
    data class StaticSnapshot(
        val bitmap: Bitmap,
        /** 快照四周的留白（世界单位） */
        val padding: Float,
    )

    /** 移植 fumeTypes.ts:129-139 `CameraTarget`（Folia 直接改字段 ⇒ Kotlin 用可变类） */
    class CameraTarget(
        var x: Float = 0f,
        var y: Float = 0f,
        var velocityX: Float = 0f,
        var velocityY: Float = 0f,
        var focusX: Float = 0f,
        var focusY: Float = 0f,
        var scale: Float = 1f,
        var velocityScale: Float = 0f,
        var focusScale: Float = 1f,
    )

    /** 移植 fumeTypes.ts:141-153 `CameraRetargetState` */
    class CameraRetargetState(
        var sourceLineIndex: Int = -1,
        var startedAt: Double = 0.0,
        var duration: Double = 0.18,
        var fromX: Float = 0f,
        var fromY: Float = 0f,
        var fromScale: Float = 1f,
        /** `none` / `direct` / `overview` */
        var bridgeMode: String = "none",
        var bridgeWaypointX: Float = 0f,
        var bridgeWaypointY: Float = 0f,
        var bridgeWaypointScale: Float = 1f,
        var bridgeWaypointPhase: Double = 0.36,
    )

    /** 移植 fumeTypes.ts:155-159 `CameraViewTarget`（Android：可变对象 ⇒ 逐帧零分配） */
    class CameraViewTarget(
        var x: Float = 0f,
        var y: Float = 0f,
        var scale: Float = 1f,
    )

    /**
     * 相机焦点点（Folia 返回 `{ x, y }` 字面量）。
     *
     * Android：可变对象，逐帧复用 ⇒ 绘制期零分配。
     */
    class FumePoint {
        var x: Float = 0f
        var y: Float = 0f
    }

    /**
     * 全景飞行的中间点（Folia 返回 `{ waypointX, … }` 字面量或 `null`）。
     *
     * Android：可变对象 + `Boolean` 返回值，逐帧复用 ⇒ 绘制期零分配。
     */
    class FumeFlightBridge {
        var waypointX: Float = 0f
        var waypointY: Float = 0f
        var waypointScale: Float = 1f
        var waypointPhase: Double = 0.36
        var duration: Double = 0.0
    }

    /**
     * Android 侧的后台场景尺寸（Folia 的 `FumeBackground` 只用到宽高）。
     *
     * 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/FumeBackground.ts — Project Folia version 0.5.27-a16525c
     */
    class FumeBackgroundScene {
        var width: Float = 0f
        var height: Float = 0f
    }

    /**
     * 移植 fumeCanvasText.ts:111-119 `FumeLiveBlockParams`（Android：主题色用 ARGB Int）。
     *
     * Folia 的 `theme` 只有两个颜色被用到：`primaryColor`（正文）与 `accentColor`（行辉光）。
     * Android 等价：`primaryColor` = `ctx.palette.accent`，`accentColor` = `ctx.palette.secondary`。
     */
    class FumeLiveBlockParams {
        var time: Double = 0.0
        var primaryColor: Int = 0xFFFFFFFF.toInt()
        var accentColor: Int = 0xFFFFFFFF.toInt()
        var glowIntensity: Float = 1f
        var activeGlowBoost: Float = 1f
        var passedGlowBase: Float = 1f
        var showPrintStamp: Boolean = true
    }

    /** 移植 fumeCanvasText.ts:37-54 `FumeLiveRasterFrame` */
    class FumeLiveRasterFrame {
        /** 舞台上每个世界单位对应多少设备像素 */
        var deviceScale: Float = 1f
        var visibleLeft: Float = 0f
        var visibleTop: Float = 0f
        var visibleRight: Float = 0f
        var visibleBottom: Float = 0f
    }

    /** 移植 fumeCanvasText.ts:43-52 `FumeLiveRasterImage`（Android：Canvas → Bitmap） */
    class FumeLiveRasterImage {
        var bitmap: Bitmap? = null
        var width: Int = 0
        var height: Int = 0
        var x: Float = 0f
        var y: Float = 0f
        var worldWidth: Float = 0f
        var worldHeight: Float = 0f
    }
}
