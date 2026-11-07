// 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/useFumeCanvasStage.ts — Project Folia version 0.5.27-a16525c
package com.nasmusic.tv.visualizer.renderers.lyricsStage

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.renderers.lyricsStage.fume.FumeBlockFrame
import com.nasmusic.tv.visualizer.renderers.lyricsStage.fume.FumeCamera
import com.nasmusic.tv.visualizer.renderers.lyricsStage.fume.FumeCameraStep
import com.nasmusic.tv.visualizer.renderers.lyricsStage.fume.FumeCanvasText
import com.nasmusic.tv.visualizer.renderers.lyricsStage.fume.FumeLiveRaster
import com.nasmusic.tv.visualizer.renderers.lyricsStage.fume.FumeMath
import com.nasmusic.tv.visualizer.renderers.lyricsStage.fume.FumeReveal
import com.nasmusic.tv.visualizer.renderers.lyricsStage.fume.FumeTextMeasure
import com.nasmusic.tv.visualizer.renderers.lyricsStage.fume.FumeTextStyle
import com.nasmusic.tv.visualizer.renderers.lyricsStage.fume.FumeTypes
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 子模式 2：全屏纵览 —— Folia `fume` 视觉器的逐行移植。
 *
 * 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/useFumeCanvasStage.ts — Project Folia version 0.5.27-a16525c
 *
 * ## 移植范围（Folia 行号）
 *  - `useFumeCanvasStage.ts:29`   `SNAPSHOT_IDLE_MS = 15_000`
 *  - `useFumeCanvasStage.ts:78`   `hasPrintedContent` 的一次性翻转
 *  - `useFumeCanvasStage.ts:101-152` 每帧重绘循环（dt / 时间 / 翻转 / 相机步进）
 *  - `useFumeCanvasStage.ts:204-282` 相机变换 + 逐块绘制（静态快照 / 活块）
 *  - `useFumeCanvasStage.ts:283-284` 活块光栅清扫 + 快照空闲回收
 *  - `useFumeCanvasStage.ts:299-308` `releaseSnapshots`
 *  - `VisualizerFume.tsx:233-241` 全景机位与全景开始时刻
 *  - `VisualizerFume.tsx:241-250` 打印开始时的 0.985 → 1 缩放
 *  - `fumeArticleLayout.ts` 的**版面构建**部分（列宽 / 边距 / 块变体 / 字号 / 摆放）——
 *    见下方「版面」的说明。
 *
 * ## 浏览器 API → Android 等价物
 *  - **世界单位 = 画布设备像素**（1 设备 px = 1 世界单位）⇒ 视口 = 画布尺寸，
 *    **不除以 `density`**。这与 Folia 的 CSS px 一一对应（DPR = 1 时两者是同一个东西）。
 *    ⛔ **2026-11-07 修正（本次缺陷的根因）**：先前取 **dp** 当世界单位
 *    （视口 = 画布 / `density`），等价于模拟「DPR = `density`」的浏览器
 *    ⇒ 视口被缩小 `density` 倍；而 Folia 的**绝对常量全都以世界单位计**
 *    （纸宽 920..2400、目标行高 64..124、相机缩放 0.88..2.2、字号上限 30）
 *    ⇒ 它们相对屏幕被放大 `density` 倍，后果有三条：
 *    ① 字号 ≈ 屏幕高的 11%（Folia ≈ 6%）⇒ 「太大、邻行淹没画面」；
 *    ② 相机缩放的**上限 2.2 永远够不到**（只到 2.0）⇒ 可见窗口只有 1.05 个列宽，
 *       相机横扫时**活动行被左右切掉**；
 *    ③ 15 字行占屏宽 94%、20 字行占 105% ⇒ 直接跑出画面。
 *    改回设备像素后与 Folia（DPR = 1）逐值相同：可见窗口 = 1.48 个列宽
 *    ⇒ 活动行永远完整可见（这正是「活动行应当完整、邻行只露一部分」的来源）。
 *  - `window.devicePixelRatio` ⇒ **只**用于静态快照的光栅密度（`clamp(density, 1, 2)`），
 *    不参与版面与相机（Folia 的 DPR 也只乘在画布变换上，不进版面公式）。
 *  - `document.createElement('canvas')` + `ctx.drawImage` ⇒ `android.graphics.Bitmap` + `Canvas.drawBitmap`。
 *  - `requestAnimationFrame` ⇒ Compose 的逐帧 `draw` 回调（本仓每帧调一次）。
 *  - `performance.now()` ⇒ `ctx.nowMs`（帧时间，单调）。
 *  - `ctx.setTransform(dpr, …)` ⇒ Android 画布本身就是设备像素 ⇒ 相当于 DPR = 1
 *    ⇒ **不乘 `density`**。
 *
 * ## 版面（Folia 的 `fumeArticleLayout.ts`）
 *  Folia 的版面构建包含一次「列数 × 密度」的二分搜索（8 次迭代 × 最多 4 种列数），
 *  用来把整张「报纸」的高度调到目标值附近；那是一次**构图搜索**，不改任何视觉常量。
 *  本移植把它固定为「列数 = Folia 的 `maxColumns` 规则、密度 = 1.0」，
 *  其余全部照抄：纸宽、边距、列间距、块变体（hero/body）、字号选择、行高、块间距与摆放规则。
 *  ⚠️ 整曲歌词的版面在**首次需要时**构建一次（换歌时重建），构建期间会测量每一行的文字。
 *
 * ## 与 Folia 的偏差清单（有意保留，逐条记原因）
 *  ① **版面搜索固定**（`columns = maxColumns`、`densityScale = 1.0`）：
 *     Folia 的搜索只影响「整张报纸的高度」（构图），不影响任何视觉常量；
 *     但 `densityScale` 会整体放大字号（候选字号 = 中点 × `densityScale`），
 *     固定成 1.0 ⇒ 字号比 Folia 搜索出的结果**偏小**，方向是安全的（不会过大）。
 *     代价：报纸高度不再逼近 `viewportHeight * 2.45`（只影响纵向留白多少，不影响横向裁切）。
 *  ② **背景层未移植**（Folia 的 `FumeBackground` 几何背景）：本子模式只画整块底色。
 *     与本次「字号 / 相机」缺陷无关，另案。
 *  ③ **世界单位 = 设备像素**（见上节）：Folia 的 CSS px 在 DPR = 1 时与设备像素同值，
 *     本仓画布本身就是设备像素 ⇒ 取 DPR = 1 是唯一自洽的映射。
 *     ⚠️ 副作用（已知并接受）：4K 画布（3840×2160）上 Folia 的纸宽上限 2400 会生效，
 *     字号仍是 ~66 px（屏幕高的 3%）——这与 Folia 在同尺寸浏览器窗口里的表现一致。
 *
 * ## 零分配
 *  `draw` 逐帧不新建 Paint / Bitmap / 数组 / 字符串；相机状态、时序对象、图层缓冲、
 *  活块参数全部是复用的成员对象。
 */
class FullViewSubRenderer : LyricsSubRenderer {

    // ══════════════════════════════════════════════════════════════════
    // Folia 的场景常量（DEFAULT_FUME_TUNING + FumeStageScene）
    // ══════════════════════════════════════════════════════════════════

    /** 移植 types.ts:520 `DEFAULT_FUME_TUNING.textHoldRatio` */
    private val textHoldRatio = 1f

    /** 移植 types.ts:522 `DEFAULT_FUME_TUNING.cameraSpeed` */
    private val cameraSpeed = 1f

    /** 移植 types.ts:521 `DEFAULT_FUME_TUNING.cameraTrackingMode` */
    private val cameraTrackingMode = "smooth"

    /** 移植 types.ts:523 `DEFAULT_FUME_TUNING.glowIntensity` */
    private val glowIntensity = 1f

    /** 移植 types.ts:516 `DEFAULT_FUME_TUNING.hidePrintSymbols`（取反 ⇒ 显示打印印章） */
    private val showPrintStamp = true

    /** 移植 types.ts:524 `DEFAULT_FUME_TUNING.heroScale` */
    private val heroScale = 1f

    /** 移植 fumeArticleLayout.ts:289 `lyricsFontScale` */
    private val lyricsFontScale = 1f

    /** 移植 fumeArticleLayout.ts:289 `densityScale`（本移植固定为 1.0，见文件头说明） */
    private val densityScale = 1f

    /** 移植 useFumeCanvasStage.ts:29 `SNAPSHOT_IDLE_MS` */
    private val snapshotIdleMs = 15_000L

    /** 移植 VisualizerFume.tsx:245 打印开始时的缩放（0.985 → 1） */
    private val printedContentScale = 0.985f

    /** 移植 VisualizerFume.tsx:246 缩放过渡时长（ms） */
    private val printedContentTransitionMs = 450f

    /** 移植 useFumeCanvasStage.ts:222 `margin = 32 / screenScale` */
    private val liveRasterMargin = 32f

    /** 移植 VisualizerFume.tsx:239 全景开始时刻 = 末行开始 + 渲染时长的一半 */
    private val overviewStartRatio = 0.5

    /** 移植 fumeArticleLayout.ts:499 纸宽下限 */
    private val paperWidthMin = 920f

    /** 移植 fumeArticleLayout.ts:499 纸宽上限 */
    private val paperWidthMax = 2400f

    /** 移植 fumeArticleLayout.ts:244 水平边距系数 */
    private val horizontalMarginRatio = 0.86f

    /** 移植 fumeArticleLayout.ts:244 水平边距下限 */
    private val horizontalMarginMin = 280f

    /** 移植 fumeArticleLayout.ts:245 垂直边距系数 */
    private val verticalMarginRatio = 0.82f

    /** 移植 fumeArticleLayout.ts:245 垂直边距下限 */
    private val verticalMarginMin = 220f

    /** 移植 fumeArticleLayout.ts:521 列间距系数（4 列） */
    private val gapRatio4 = 0.0065f

    /** 移植 fumeArticleLayout.ts:521 列间距系数（3 列） */
    private val gapRatio3 = 0.0085f

    /** 移植 fumeArticleLayout.ts:521 列间距系数（其它列数） */
    private val gapRatioOther = 0.0115f

    /** 移植 fumeArticleLayout.ts:521 列间距下限 */
    private val gapMin = 6f

    /** 移植 fumeArticleLayout.ts:521 列间距上限 */
    private val gapMax = 14f

    /** 移植 fumeArticleLayout.ts:299 行高系数（hero） */
    private val lineHeightRatioHero = 1.02f

    /** 移植 fumeArticleLayout.ts:299 行高系数（body） */
    private val lineHeightRatioBody = 1.06f

    /** 移植 fumeArticleLayout.ts:301 块间距系数（hero） */
    private val blockGapRatioHero = 0.2f

    /** 移植 fumeArticleLayout.ts:301 块间距系数（body） */
    private val blockGapRatioBody = 0.08f

    /** 移植 fumeArticleLayout.ts:302 块间距下限（hero） */
    private val blockGapMinHero = 6f

    /** 移植 fumeArticleLayout.ts:302 块间距下限（body） */
    private val blockGapMinBody = 2f

    /** 移植 fumeArticleLayout.ts:288 块内边距（左右） */
    private val paddingX = 0f

    /** 移植 fumeArticleLayout.ts:288 块内边距（上下） */
    private val paddingY = 0f

    /** 移植 fumeArticleLayout.ts:408 块宽下限 */
    private val innerWidthMin = 120f

    /** 移植 fumeArticleLayout.ts:501 版面种子（主题名在本仓没有 ⇒ 固定值） */
    private val layoutSeedKey = "fume"

    /** 移植 fumeArticleLayout.ts:65-75 hero 变体的最长字数 */
    private val heroMaxGraphemes = 22

    /** 移植 fumeArticleLayout.ts:69-72 body→hero 的字数区间 */
    private val heroMinGraphemes = 4
    private val heroMaxGraphemesBody = 28

    /** 移植 fumeArticleLayout.ts:72 「靠近中段」的判定上限 */
    private val heroCenteredLimit = 0.72f

    /** 移植 fumeArticleLayout.ts:72 随机阈值 */
    private val heroRandomThreshold = 0.965

    /** 移植 fumeArticleLayout.ts:72 每 6 行出现一个 hero */
    private val heroEveryNth = 6

    // ══════════════════════════════════════════════════════════════════
    // 复用的逐帧对象（零分配）
    // ══════════════════════════════════════════════════════════════════

    private val cameraState = FumeCameraStep.createFumeCameraState()
    private val cameraInput = FumeCameraStep.FumeCameraStepInput()
    private val cameraResult = FumeCameraStep.FumeCameraStepResult()
    private val glowBases = FumeBlockFrame.FumeGlowBases()
    private val blockTiming = FumeBlockFrame.FumeBlockTiming()
    private val staticLayerInput = FumeBlockFrame.FumeStaticLayerInput()
    private val staticLayers = FumeBlockFrame.FumeStaticLayers()
    private val liveParams = FumeTypes.FumeLiveBlockParams()
    private val liveFrame = FumeTypes.FumeLiveRasterFrame()
    private val liveImage = FumeTypes.FumeLiveRasterImage()
    private val textDrawer = FumeCanvasText.FumeTextDrawer()
    private val liveRaster = FumeLiveRaster()

    /** 快照画笔（只设 alpha，逐帧复用） */
    private val snapshotPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** 页面底色画笔 */
    private val backgroundPaint = Paint()

    // ══════════════════════════════════════════════════════════════════
    // 版面与快照缓存
    // ══════════════════════════════════════════════════════════════════

    /** 一张缓存的静态快照 */
    private class SnapshotEntry(
        val snapshot: FumeTypes.StaticSnapshot,
        var lastUsed: Long,
    )

    private val snapshotCache = HashMap<String, SnapshotEntry>()

    /** 快照提供者（Folia 的 `getSnapshot` 回调） */
    private inner class SnapshotProvider : FumeBlockFrame.FumeSnapshotProvider {
        /** 当前正在取快照的块（Folia 的闭包捕获） */
        var block: FumeTypes.FumeBlock? = null

        /** 当前时刻（毫秒） */
        var now: Long = 0L

        /** 快照的光栅密度 */
        var rasterScale: Float = 1f

        override fun getSnapshot(
            key: String,
            fill: Int,
            shadowBlur: Float,
            shadowColor: Int,
        ): FumeTypes.StaticSnapshot? {
            val cached = snapshotCache[key]
            if (cached != null) {
                cached.lastUsed = now
                return cached.snapshot
            }
            val target = block ?: return null
            val created = FumeCanvasText.createStaticBlockSnapshot(
                target,
                fill,
                shadowBlur,
                shadowColor,
                rasterScale,
            ) ?: return null
            snapshotCache[key] = SnapshotEntry(created, now)
            return created
        }
    }

    private val snapshotProvider = SnapshotProvider()

    /** 当前版面 */
    private var article: FumeTypes.FumeArticleLayout? = null

    /** 构建当前版面所用的歌词行（换歌时换掉 ⇒ 重建） */
    private var builtLines: List<LyricLine>? = null

    /** 构建当前版面所用的原始歌词行（`sourceLineIndex` 的下标来源） */
    private var builtSourceLines: List<LyricLine> = emptyList()

    /** 构建当前版面时的快照光栅密度（密度变了 ⇒ 缓存键变了 ⇒ 重建） */
    private var builtSnapshotScale = 0f

    /** 行测量缓存（`variant:text` ⇒ 测量结果），重建版面时复用 */
    private val lineMeasures = HashMap<String, FumeTextMeasure.FumeLineMeasures>()

    /** 移植 useFumeCanvasStage.ts:78 `hasPrintedContent` */
    private var hasPrintedContent = false

    /** 翻转的时刻（用于 0.985 → 1 的过渡） */
    private var printedContentAtMs = 0L

    /** 上一帧的时刻 */
    private var lastFrameAtMs = -1L

    /** 复用的视口尺寸（逐帧复用） */
    private val viewport = FumeTypes.ViewportSize(0f, 0f)

    /** 复用的版面视口尺寸（版面构建期复用；与 [viewport] 同单位 = 画布设备像素） */
    private val layoutViewport = FumeTypes.ViewportSize(0f, 0f)

    /** 复用的全景机位（逐帧复用） */
    private val overviewCamera = FumeTypes.CameraViewTarget(0f, 0f, 1f)

    /** 唱过文字的淡出时长（版面不变时不变 ⇒ 只在版面重建时算一次） */
    private var passedFadeDuration = 0f

    /** 全景镜头开始的时刻（版面不变时不变） */
    private var overviewStartTime = Double.POSITIVE_INFINITY

    // ══════════════════════════════════════════════════════════════════
    // LyricsSubRenderer
    // ══════════════════════════════════════════════════════════════════

    override fun onEnter(ctx: RenderContext, lyrics: LyricsStagePayload) {
        releaseSnapshots()
        liveRaster.clear()
        cameraState.initialized = false
        hasPrintedContent = false
        lastFrameAtMs = -1L
        article = null
        builtLines = null
        builtSnapshotScale = 0f
        lineMeasures.clear()
    }

    /**
     * 本子模式的版面是**整曲级**的 ⇒ 换行不重建版面（只更新当前行相关的信息）。
     *
     * 移植 LyricsSubRenderer.kt 的约定（见其 KDoc）。
     */
    override fun onLineChanged(ctx: RenderContext, lyrics: LyricsStagePayload) {
        // 版面与当前行无关 ⇒ 无需重建。
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext, lyrics: LyricsStagePayload) {
        renderStage(frame, ctx, lyrics, density)
    }

    override fun onExit() {
        releaseSnapshots()
        liveRaster.clear()
        article = null
        builtLines = null
        lineMeasures.clear()
    }

    // ══════════════════════════════════════════════════════════════════
    // 逐帧渲染
    // ══════════════════════════════════════════════════════════════════

    /**
     * 移植 useFumeCanvasStage.ts:101-289 的每帧循环。
     *
     * @param density 设备 px / dp（**只**用于静态快照的光栅密度；版面与相机的世界单位是设备像素）。
     */
    private fun DrawScope.renderStage(
        frame: AudioFrame,
        ctx: RenderContext,
        lyrics: LyricsStagePayload,
        density: Float,
    ) {
        val now = ctx.nowMs

        // 移植 useFumeCanvasStage.ts:103-105 —— 帧间隔
        var dt = if (lastFrameAtMs < 0L) {
            1.0 / 60.0
        } else {
            ((now - lastFrameAtMs) / 1000.0).coerceIn(1.0 / 240.0, 0.05)
        }
        lastFrameAtMs = now

        val time = lyrics.timeSec

        // 换歌 ⇒ 重建版面
        if (lyrics.lines !== builtLines) {
            rebuildArticle(lyrics.lines, density, ctx)
        }

        val current = article ?: return

        // 移植 useFumeCanvasStage.ts:149-153 —— 一次性翻转
        if (!hasPrintedContent && time >= current.firstRenderableStartTime) {
            hasPrintedContent = true
            printedContentAtMs = now
        }

        // ── 相机步进（移植 useFumeCanvasStage.ts:155-168）────────────────
        // ⚠️ 视口 = **画布设备像素**（世界单位），不除以 `density`（见文件头）。
        viewport.width = ctx.canvasSize.width
        viewport.height = ctx.canvasSize.height
        FumeCamera.resolveArticleOverviewCamera(current, viewport, overviewCamera)

        cameraInput.article = current
        cameraInput.time = time
        cameraInput.now = now.toDouble()
        cameraInput.dt = dt
        cameraInput.lineIndex = lyrics.currentIndex
        cameraInput.viewport = viewport
        cameraInput.overviewCamera = overviewCamera
        cameraInput.overviewStartTime = overviewStartTime
        cameraInput.cameraSpeed = cameraSpeed
        cameraInput.cameraTrackingMode = cameraTrackingMode
        cameraInput.staticMode = false
        cameraInput.animationIntensity = resolveAnimationIntensity(ctx.quality)

        FumeCameraStep.stepFumeCamera(cameraState, cameraInput, cameraResult)

        val camera = cameraState.camera
        val screenScale = camera.scale

        // ── 绘制（移植 useFumeCanvasStage.ts:204-282）────────────────────
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas

            // 页面底色（Folia 的 `theme.backgroundColor`）
            backgroundPaint.color = ctx.palette.background.toArgb()
            native.drawRect(0f, 0f, ctx.canvasSize.width, ctx.canvasSize.height, backgroundPaint)

            // 打印开始时的 0.985 → 1（VisualizerFume.tsx:241-250）
            val contentScale = resolvePrintedContentScale(now)

            native.save()
            native.translate(ctx.canvasSize.width * 0.5f, ctx.canvasSize.height * 0.5f)
            // ⚠️ 世界单位 = 设备像素 ⇒ 相机缩放直接乘（不再乘 `density`，见文件头）。
            native.scale(screenScale * contentScale, screenScale * contentScale)
            native.translate(-camera.x, -camera.y)

            drawBlocks(native, current, camera, viewport, time, now, ctx)

            native.restore()
        }

        // 移植 useFumeCanvasStage.ts:283-284 —— 清扫
        liveRaster.sweep(now)
        releaseIdleSnapshots(now)
    }

    /**
     * 移植 useFumeCanvasStage.ts:233-282 —— 逐块绘制。
     */
    private fun drawBlocks(
        native: Canvas,
        current: FumeTypes.FumeArticleLayout,
        camera: FumeTypes.CameraTarget,
        viewport: FumeTypes.ViewportSize,
        time: Double,
        now: Long,
        ctx: RenderContext,
    ) {
        // 移植 useFumeCanvasStage.ts:209 —— 辉光基准
        FumeBlockFrame.resolveFumeGlowBases(
            resolveAnimationIntensity(ctx.quality),
            glowIntensity,
            glowBases,
        )

        // 移植 useFumeCanvasStage.ts:210-212 —— 活块参数
        // ⚠️ 配色按**语义**对应（Folia 的 `theme.primaryColor` = 基础文字色、
        //    `theme.accentColor` = 高亮色），不是按名字对应 ⇒ 见 LyricsStageColors。
        liveParams.time = time
        liveParams.primaryColor = LyricsStageColors.baseText(ctx)
        liveParams.accentColor = LyricsStageColors.accent(ctx)
        liveParams.glowIntensity = glowIntensity
        liveParams.activeGlowBoost = glowBases.activeGlowBoost
        liveParams.passedGlowBase = glowBases.passedGlowBase
        liveParams.showPrintStamp = showPrintStamp

        // 移植 useFumeCanvasStage.ts:211-218 —— 静态图层的输入
        staticLayerInput.time = time
        staticLayerInput.primaryColor = LyricsStageColors.baseText(ctx)
        staticLayerInput.passedGlowBase = glowBases.passedGlowBase
        staticLayerInput.passedFadeDuration = passedFadeDuration
        staticLayerInput.overviewTextRestoreProgress = cameraResult.overviewTextRestoreProgress
        staticLayerInput.snapshotScale = builtSnapshotScale

        snapshotProvider.now = now
        snapshotProvider.rasterScale = builtSnapshotScale

        // 移植 useFumeCanvasStage.ts:222-231 —— 活块的可见区域
        val margin = liveRasterMargin / camera.scale
        // ⚠️ 世界单位 = 设备像素 ⇒ 每个世界单位对应 `camera.scale` 个设备纹素。
        liveFrame.deviceScale = camera.scale
        liveFrame.visibleLeft = camera.x - viewport.width * 0.5f / camera.scale - margin
        liveFrame.visibleTop = camera.y - viewport.height * 0.5f / camera.scale - margin
        liveFrame.visibleRight = camera.x + viewport.width * 0.5f / camera.scale + margin
        liveFrame.visibleBottom = camera.y + viewport.height * 0.5f / camera.scale + margin

        for (block in current.blocks) {
            // 移植 useFumeCanvasStage.ts:235-237 —— 只画在屏上的块
            if (!FumeBlockFrame.isFumeBlockOnScreen(block, camera, viewport)) continue

            val nextLineStartTime = builtSourceLines
                .getOrNull(block.sourceLineIndex + 1)
                ?.startTime

            // 移植 useFumeCanvasStage.ts:239-244 —— 该块的时序
            FumeBlockFrame.resolveFumeBlockTiming(
                block,
                time,
                nextLineStartTime,
                textHoldRatio,
                blockTiming,
            )

            // 移植 useFumeCanvasStage.ts:245-255 —— 静态快照层
            snapshotProvider.block = block
            FumeBlockFrame.resolveFumeStaticLayers(
                block,
                blockTiming,
                staticLayerInput,
                snapshotProvider,
                staticLayers,
            )
            if (staticLayers.count > 0) {
                for (layer in 0 until staticLayers.count) {
                    val snapshot = staticLayers.snapshots[layer] ?: continue
                    val alpha = staticLayers.alphas[layer]
                    drawSnapshot(native, block, snapshot, alpha)
                }
                continue
            }

            // 移植 useFumeCanvasStage.ts:272-279 —— 活块（经光栅层）
            if (!liveRaster.draw(
                    block,
                    blockTiming,
                    liveParams,
                    liveFrame,
                    textDrawer,
                    now,
                    liveImage,
                )
            ) {
                continue
            }
            drawLiveImage(native, liveImage)
        }
    }

    /**
     * 移植 useFumeCanvasStage.ts:258-266 —— 把缓存快照贴到世界里。
     */
    private fun drawSnapshot(
        native: Canvas,
        block: FumeTypes.FumeBlock,
        snapshot: FumeTypes.StaticSnapshot,
        alpha: Float,
    ) {
        val padding = snapshot.padding
        val worldWidth = block.width + padding * 2f
        val worldHeight = block.height + padding * 2f
        val bitmap = snapshot.bitmap
        if (bitmap.width <= 0 || bitmap.height <= 0) return

        snapshotPaint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)

        native.save()
        native.translate(block.x - padding, block.y - padding)
        native.scale(worldWidth / bitmap.width, worldHeight / bitmap.height)
        native.clipRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        native.drawBitmap(bitmap, 0f, 0f, snapshotPaint)
        native.restore()
    }

    /**
     * 移植 useFumeCanvasStage.ts:277-278 —— 把活块的光栅贴到世界里。
     */
    private fun drawLiveImage(native: Canvas, image: FumeTypes.FumeLiveRasterImage) {
        val bitmap = image.bitmap ?: return
        if (image.width <= 0 || image.height <= 0) return

        snapshotPaint.alpha = 255

        native.save()
        native.translate(image.x, image.y)
        native.scale(image.worldWidth / image.width, image.worldHeight / image.height)
        native.clipRect(0f, 0f, image.width.toFloat(), image.height.toFloat())
        native.drawBitmap(bitmap, 0f, 0f, snapshotPaint)
        native.restore()
    }

    // ══════════════════════════════════════════════════════════════════
    // 版面构建
    // ══════════════════════════════════════════════════════════════════

    /**
     * 构建整曲的版面（移植 `fumeArticleLayout.ts` 的构建部分）。
     *
     * @param lines 整曲歌词行。
     * @param density 设备 px / dp（**只**用于快照的光栅密度）。
     */
    private fun rebuildArticle(
        lines: List<LyricLine>,
        density: Float,
        ctx: RenderContext,
    ) {
        builtLines = lines
        builtSourceLines = lines
        builtSnapshotScale = FumeMath.clamp(density, 1f, 2f)
        releaseSnapshots()

        if (lines.isEmpty()) {
            article = null
            // 移植 useFumeCanvasStage.ts:97 —— 没有版面 ⇒ 相机复位
            FumeCameraStep.syncFumeCameraToArticle(cameraState, null)
            return
        }

        // ⚠️ 版面视口 = **画布设备像素**（世界单位），不除以 `density`（见文件头）。
        layoutViewport.width = ctx.canvasSize.width
        layoutViewport.height = ctx.canvasSize.height
        val viewport = layoutViewport

        // 移植 fumeArticleLayout.ts:499-503
        val paperWidth = FumeMath.clamp(
            max(viewport.width * 1.95f, viewport.width + 520f),
            paperWidthMin,
            paperWidthMax,
        )
        val viewportHeight = max(viewport.height, 240f)
        val columns = when {
            paperWidth >= 1120f -> 4
            paperWidth >= 760f -> 3
            paperWidth >= 500f -> 2
            else -> 1
        }
        // 移植 fumeArticleLayout.ts:521
        val gap = FumeMath.clamp(
            ceil(
                paperWidth * when {
                    columns >= 4 -> gapRatio4
                    columns == 3 -> gapRatio3
                    else -> gapRatioOther
                },
            ),
            gapMin,
            gapMax,
        )
        // 移植 fumeArticleLayout.ts:244-246
        val horizontalMargin = max(viewport.width * horizontalMarginRatio, horizontalMarginMin)
        val verticalMargin = max(viewport.height * verticalMarginRatio, verticalMarginMin)
        // 移植 fumeArticleLayout.ts:246
        val columnWidth = (paperWidth - gap * (columns - 1)) / columns

        // 移植 fumeArticleLayout.ts:250-257 —— 去掉空行，保留原下标
        val entries = ArrayList<LineEntry>(lines.size)
        for (index in lines.indices) {
            val line = lines[index]
            if (line.fullText.trim().isEmpty()) continue
            entries.add(LineEntry(line, index))
        }
        if (entries.isEmpty()) {
            article = null
            return
        }

        // 移植 fumeArticleLayout.ts:251-257 —— 按种子打乱摆放顺序（报纸感）
        entries.sortBy { FumeMath.seeded("$layoutSeedKey:${it.index}:${it.line.fullText}") }

        // 移植 fumeArticleLayout.ts:60-75 / 78-138 —— 块变体
        val forcedHeroIndex = chooseFallbackHeroBlockIndex(entries)

        // 移植 fumeArticleLayout.ts:308-368 —— 列高与摆放
        val columnHeights = FloatArray(columns) { verticalMargin }
        val blocks = ArrayList<FumeTypes.FumeBlock>(entries.size)

        for (entryIndex in entries.indices) {
            val entry = entries[entryIndex]
            val variant = if (entryIndex == forcedHeroIndex) {
                FumeTypes.VARIANT_HERO
            } else {
                chooseNaturalBlockVariant(entry.line, entryIndex, entries.size)
            }
            val isHero = variant == FumeTypes.VARIANT_HERO

            // 移植 fumeArticleLayout.ts:271-283 —— hero 的跨列宽度
            val heroSpanColumns = if (isHero) min(columns, if (columns <= 1) 1 else 2) else 1
            val heroSpanWidth = if (heroSpanColumns > 1) {
                columnWidth * heroSpanColumns + gap * (heroSpanColumns - 1)
            } else {
                paperWidth
            }
            val blockWidth = if (isHero) {
                if (heroSpanColumns == 1) {
                    paperWidth
                } else if (columns == 2) {
                    columnWidth * 1.5f + gap * 0.5f
                } else {
                    heroSpanWidth
                }
            } else {
                columnWidth
            }
            // 移植 fumeArticleLayout.ts:286
            val innerWidth = max(blockWidth - paddingX * 2f, innerWidthMin)

            // 移植 fumeArticleLayout.ts:289-295 —— 字号选择
            val measures = lineMeasuresFor(entry.line.fullText, variant)
            val choice = FumeTextMeasure.chooseSingleLineFontPx(
                measures,
                innerWidth,
                lyricsFontScale,
                densityScale,
                heroScale,
            )
            val fontPx = choice.fontPx
            // 移植 fumeArticleLayout.ts:299
            val lineHeight = (fontPx * (if (isHero) lineHeightRatioHero else lineHeightRatioBody)).roundToInt().toFloat()
            // 移植 fumeArticleLayout.ts:300-302
            val blockGap = if (isHero) {
                max((lineHeight * blockGapRatioHero).roundToInt().toFloat(), blockGapMinHero)
            } else {
                max((lineHeight * blockGapRatioBody).roundToInt().toFloat(), blockGapMinBody)
            }
            // 移植 fumeArticleLayout.ts:303
            val blockHeight = paddingY * 2f + choice.lineCount * lineHeight

            // 移植 fumeArticleLayout.ts:308-368 —— 摆放
            var x = 0f
            var y = 0f
            if (isHero && heroSpanColumns > 1) {
                var bestHeight = Float.MAX_VALUE
                var bestStart = 0
                for (startColumn in 0..(columns - heroSpanColumns)) {
                    var covered = 0f
                    for (columnIndex in startColumn until (startColumn + heroSpanColumns)) {
                        covered = max(covered, columnHeights[columnIndex])
                    }
                    if (covered < bestHeight) {
                        bestHeight = covered
                        bestStart = startColumn
                    }
                }
                y = bestHeight
                x = horizontalMargin +
                    bestStart * (columnWidth + gap) +
                    max((heroSpanWidth - blockWidth) * 0.5f, 0f)
                for (columnIndex in bestStart until (bestStart + heroSpanColumns)) {
                    columnHeights[columnIndex] = y + blockHeight + blockGap
                }
            } else {
                var targetColumn = 0
                var minHeight = columnHeights[0]
                for (columnIndex in 1 until columns) {
                    if (columnHeights[columnIndex] < minHeight) {
                        minHeight = columnHeights[columnIndex]
                        targetColumn = columnIndex
                    }
                }
                x = horizontalMargin + targetColumn * (columnWidth + gap)
                y = columnHeights[targetColumn]
                columnHeights[targetColumn] = y + blockHeight + blockGap
            }

            // 移植 fumeArticleLayout.ts:373-436 —— 渲染细节
            blocks.add(
                buildBlock(
                    entry = entry,
                    variant = variant,
                    x = x,
                    y = y,
                    width = blockWidth,
                    height = blockHeight,
                    innerWidth = innerWidth,
                    fontPx = fontPx,
                    lineHeight = lineHeight,
                    measures = measures,
                    // 移植 fumeTextStyle.ts:84-88 —— 逐词激活色的**回落**色
                    // （Folia：`resolveWordColor(word.text, theme.wordColors, theme.accentColor)`
                    //  ⇒ 回落色是 `theme.accentColor`，不是 `theme.secondaryColor`）。
                    accentArgb = LyricsStageColors.accent(ctx),
                ),
            )
        }

        // 移植 fumeArticleLayout.ts:443-480 —— 版面尺寸与索引
        var articleHeight = 0f
        for (height in columnHeights) {
            articleHeight = max(articleHeight, height)
        }
        articleHeight += verticalMargin

        val paperBounds = FumeTypes.FumePaperBounds(
            left = horizontalMargin,
            top = verticalMargin,
            right = horizontalMargin + paperWidth,
            bottom = max(articleHeight - verticalMargin, verticalMargin),
        )

        val chronological = ArrayList<FumeTypes.FumeBlock>(blocks)
        chronological.sortBy { it.sourceLineIndex }

        val bySourceIndex = HashMap<Int, FumeTypes.FumeBlock>(blocks.size)
        for (block in blocks) {
            bySourceIndex[block.sourceLineIndex] = block
        }

        article = FumeTypes.FumeArticleLayout(
            width = paperWidth + horizontalMargin * 2f,
            height = articleHeight,
            viewportHeight = viewportHeight,
            columns = columns,
            gap = gap,
            paperBounds = paperBounds,
            blocks = blocks,
            blockBySourceLineIndex = bySourceIndex,
            chronologicalBlocks = chronological,
            firstRenderableStartTime = chronological.firstOrNull()?.line?.startTime
                ?: Double.POSITIVE_INFINITY,
            lastChronologicalRenderEndTime = chronological.lastOrNull()?.line?.endTime
                ?: Double.NEGATIVE_INFINITY,
        )

        // 唱过文字的淡出时长与全景时刻只跟版面有关 ⇒ 在这里算一次
        passedFadeDuration = FumeTextStyle.resolveFumePassedFadeDuration(lines, textHoldRatio)
        overviewStartTime = resolveOverviewStartTime(lines)

        // 移植 useFumeCanvasStage.ts:97 —— 版面换了就把相机放到版面上
        // （新的版面 ⇒ 相机落到版面中心；已有的相机 ⇒ 夹在版面范围内）。
        // ⚠️ Folia 在 effect 体内调一次（版面变化时），不在每帧调。
        FumeCameraStep.syncFumeCameraToArticle(cameraState, article)
    }

    /** 版面构建用的一行 */
    private class LineEntry(val line: LyricLine, val index: Int)

    /**
     * 移植 fumeArticleLayout.ts:60-75 `chooseNaturalBlockVariant`
     */
    private fun chooseNaturalBlockVariant(line: LyricLine, index: Int, total: Int): String {
        val graphemeCount = FumeTextMeasure.countRenderableGraphemes(line.fullText)
        if (graphemeCount == 0) return FumeTypes.VARIANT_BODY

        if (line.isChorus && graphemeCount <= heroMaxGraphemes) return FumeTypes.VARIANT_HERO

        val shortEnough = graphemeCount >= heroMinGraphemes && graphemeCount <= heroMaxGraphemesBody
        val centered = abs(index - total / 2f) / max(total, 1)
        val random = FumeMath.seeded("${line.fullText}:$index")
        return if (shortEnough &&
            centered < heroCenteredLimit &&
            ((index + 1) % heroEveryNth == 0 || random > heroRandomThreshold)
        ) {
            FumeTypes.VARIANT_HERO
        } else {
            FumeTypes.VARIANT_BODY
        }
    }

    /**
     * 移植 fumeArticleLayout.ts:78-138 `chooseFallbackHeroBlockIndex`
     *
     * 没有自然出现的 hero 时，挑一个最合适的行当 hero。
     */
    private fun chooseFallbackHeroBlockIndex(entries: List<LineEntry>): Int {
        if (entries.isEmpty()) return -1

        for (entry in entries.indices) {
            if (chooseNaturalBlockVariant(entries[entry].line, entry, entries.size) == FumeTypes.VARIANT_HERO) {
                return -1
            }
        }

        var bestIndex = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (entry in entries.indices) {
            val line = entries[entry].line
            val graphemeCount = FumeTextMeasure.countRenderableGraphemes(line.fullText)
            if (graphemeCount == 0) continue

            val isComfortableHeroLength = graphemeCount >= heroMinGraphemes && graphemeCount <= heroMaxGraphemesBody
            val isAcceptableFallbackLength = graphemeCount <= 36
            if (!isComfortableHeroLength && !isAcceptableFallbackLength) continue

            val centered = abs(entry - entries.size / 2f) / max(entries.size, 1)
            val centerScore = 1f - centered
            val lengthScore = when {
                graphemeCount in 6..22 -> 1f
                graphemeCount <= heroMaxGraphemesBody -> 0.72f
                else -> 0.36f
            }
            val chorusScore = if (line.isChorus) 0.28f else 0f
            val stableJitter = (FumeMath.seeded("${line.fullText}:$entry:fallback-hero") * 0.04).toFloat()
            val score = centerScore * 0.62f + lengthScore * 0.34f + chorusScore + stableJitter
            if (score > bestScore) {
                bestScore = score
                bestIndex = entry
            }
        }

        if (bestIndex >= 0) return bestIndex

        var shortestIndex = -1
        var shortestCount = Int.MAX_VALUE
        for (entry in entries.indices) {
            val graphemeCount = FumeTextMeasure.countRenderableGraphemes(entries[entry].line.fullText)
            if (graphemeCount > 0 && graphemeCount < shortestCount) {
                shortestCount = graphemeCount
                shortestIndex = entry
            }
        }
        return shortestIndex
    }

    /**
     * 移植 fumeTextMeasure.ts:304-318 `lineMeasuresFor`（按 `variant:text` 缓存）。
     */
    private fun lineMeasuresFor(text: String, variant: String): FumeTextMeasure.FumeLineMeasures {
        val key = "$variant:$text"
        val cached = lineMeasures[key]
        if (cached != null) return cached
        val created = FumeTextMeasure.FumeLineMeasures(text, variant, null, null)
        lineMeasures[key] = created
        return created
    }

    /**
     * 移植 fumeArticleLayout.ts:373-436 —— 一个块的全部渲染细节。
     *
     * @param accentArgb 逐词激活色的**回落**色（= Folia 的 `theme.accentColor`，即高亮色）。
     */
    private fun buildBlock(
        entry: LineEntry,
        variant: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        innerWidth: Float,
        fontPx: Float,
        lineHeight: Float,
        measures: FumeTextMeasure.FumeLineMeasures,
        accentArgb: Int,
    ): FumeTypes.FumeBlock {
        val prepared = FumeTextMeasure.prepareLineAt(measures, fontPx)
        val metas = FumeTextMeasure.buildSegmentMetas(prepared)
        val graphemes = metas.graphemes
        val segmentMetas = metas.segmentMetas

        // 移植 fumeArticleLayout.ts:379 —— 断行
        val layout = FumeTextMeasure.layoutLines(prepared, segmentMetas, graphemes, innerWidth)

        // 移植 fumeArticleLayout.ts:381-382 —— 词区间
        val wordRanges = FumeReveal.buildWordRanges(entry.line, graphemes)
        val wordRangeIndexByOffset =
            FumeTextMeasure.buildWordRangeIndexByOffset(graphemes.size, wordRanges, "timing")
        val colorRangeIndexByOffset =
            FumeTextMeasure.buildWordRangeIndexByOffset(graphemes.size, wordRanges, "color")

        // 移植 fumeTextStyle.ts:84-88 —— 逐词的激活色
        val wordTexts = ArrayList<String>(wordRanges.size)
        for (range in wordRanges) {
            wordTexts.add(range.word.text)
        }
        val activeColors = FumeTextStyle.buildActiveColors(wordTexts, emptyMap(), accentArgb)

        // 移植 fumeBlockFrame.ts:69 / fumeCanvasText.ts:89 / fumeCanvasText.ts:349 —— CJK 判定
        // ⚠️ `FumeMath.isCJK` 每次调用都会 `Regex(...)` 重新编译一个模式 ⇒ 逐帧路径上绝不能直接调；
        //    这里在**版面构建时**算好整行版本（`isCjkLine`）与逐渲染行的逐 grapheme 版本。
        val isCjkLine = FumeMath.isCJK(entry.line.fullText)

        // 移植 fumeArticleLayout.ts:384-414 —— 渲染行
        val fontSpec = FumeTextMeasure.buildFontSpec(fontPx, variant, null, null)
        val renderLines = ArrayList<FumeTypes.RenderLineSlice>(layout.lines.size)
        for (lineIndex in layout.lines.indices) {
            val layoutLine = layout.lines[lineIndex]
            val lineGraphemes = FumeMath.splitGraphemes(layoutLine.text)
            // 移植 fumeCanvasText.ts:349 —— 逐 grapheme 的 CJK 判定（版面期算好，绘制期零成本）
            val lineGraphemeIsCjk = BooleanArray(lineGraphemes.size)
            for (graphemeIndex in lineGraphemes.indices) {
                lineGraphemeIsCjk[graphemeIndex] = FumeMath.isCJK(lineGraphemes[graphemeIndex])
            }
            renderLines.add(
                FumeTypes.RenderLineSlice(
                    id = "${entry.line.startTime}-$lineIndex",
                    text = layoutLine.text,
                    start = layoutLine.start,
                    end = layoutLine.end,
                    graphemes = lineGraphemes,
                    graphemeIsCjk = lineGraphemeIsCjk,
                    glyphOffsets = FumeTextMeasure.buildGlyphOffsets(
                        prepared,
                        segmentMetas,
                        layoutLine.start,
                        lineGraphemes.size,
                    ),
                    segments = FumeTextMeasure.buildRenderSegments(
                        prepared,
                        segmentMetas,
                        layoutLine.start,
                        layoutLine.end,
                        fontSpec,
                    ),
                    left = if (variant == FumeTypes.VARIANT_HERO) {
                        max((width - layoutLine.width) * 0.08f, 0f)
                    } else {
                        0f
                    },
                    top = paddingY + lineIndex * lineHeight,
                    width = layoutLine.width,
                ),
            )
        }

        // 移植 useFumeCanvasStage.ts:249-251 —— 快照的缓存键
        val id = "fume-${entry.line.startTime}-${entry.index}"
        val effectiveStyle = if (textHoldRatio >= 1f) "standard" else "dimmed"

        return FumeTypes.FumeBlock(
            id = id,
            sourceLineIndex = entry.index,
            line = entry.line,
            variant = variant,
            x = x,
            y = y,
            width = width,
            height = height,
            innerWidth = innerWidth,
            fontPx = fontPx,
            lineHeight = lineHeight,
            fontWeight = if (variant == FumeTypes.VARIANT_HERO) 780 else 640,
            prepared = prepared,
            layout = layout,
            graphemes = graphemes,
            isCjkLine = isCjkLine,
            segmentMetas = segmentMetas,
            wordRanges = wordRanges,
            wordRangeIndexByOffset = wordRangeIndexByOffset,
            colorRangeIndexByOffset = colorRangeIndexByOffset,
            activeColors = activeColors,
            renderLines = renderLines,
            snapshotKeyWaiting = "$id:waiting:base:$builtSnapshotScale",
            snapshotKeyPassed = "$id:passed:$effectiveStyle:$builtSnapshotScale",
            snapshotKeyPassedStandard = "$id:passed:standard:$builtSnapshotScale",
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════════

    /**
     * 移植 VisualizerFume.tsx:233-241 —— 全景镜头开始的时刻。
     */
    private fun resolveOverviewStartTime(lines: List<LyricLine>): Double {
        var lastRenderable: LyricLine? = null
        for (line in lines) {
            if (line.fullText.trim().isNotEmpty()) lastRenderable = line
        }
        val target = lastRenderable ?: return Double.POSITIVE_INFINITY
        val lineStartTime = target.startTime
        val lineRenderEndTime = LyricRenderHints.getLineRenderEndTime(target)
        return lineStartTime + max(lineRenderEndTime - lineStartTime, 0.0) * overviewStartRatio
    }
    /**
     * 移植 VisualizerFume.tsx:241-250 —— 打印开始时的 0.985 → 1 缩放。
     */
    private fun resolvePrintedContentScale(now: Long): Float {
        if (!hasPrintedContent) return printedContentScale
        val elapsed = (now - printedContentAtMs).toFloat()
        if (elapsed >= printedContentTransitionMs) return 1f
        val t = (elapsed / printedContentTransitionMs).coerceIn(0f, 1f)
        val eased = FumeMath.easeOutCubic(t)
        return printedContentScale + (1f - printedContentScale) * eased
    }

    /**
     * 主题的 `animationIntensity`（本仓没有主题 ⇒ 按画质档映射）。
     *
     * 与 `GlowLineSubRenderer` 的映射一致：LOW ⇒ "calm"，其余 ⇒ "normal"。
     */
    private fun resolveAnimationIntensity(quality: VisualQuality): String =
        if (quality == VisualQuality.LOW) "calm" else "normal"

    /**
     * 移植 useFumeCanvasStage.ts:299-308 `releaseSnapshots` —— 回收全部快照。
     */
    private fun releaseSnapshots() {
        for (entry in snapshotCache.values) {
            entry.snapshot.bitmap.recycle()
        }
        snapshotCache.clear()
    }

    /**
     * 移植 useFumeCanvasStage.ts:300-307 —— 回收空闲太久的快照。
     */
    private fun releaseIdleSnapshots(now: Long) {
        val iterator = snapshotCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastUsed <= snapshotIdleMs) continue
            entry.value.snapshot.bitmap.recycle()
            iterator.remove()
        }
    }
}
