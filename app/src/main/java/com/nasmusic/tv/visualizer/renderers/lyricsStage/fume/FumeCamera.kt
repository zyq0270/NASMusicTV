// 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/fumeCamera.ts — Project Folia version 0.5.27-a16525c
package com.nasmusic.tv.visualizer.renderers.lyricsStage.fume

import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricLine
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricRenderHints
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * fume 相机该看向哪里：块内的焦点、重定向时长、全景机位与全景飞行桥。
 *
 * 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/fumeCamera.ts — Project Folia version 0.5.27-a16525c
 *
 * ## 浏览器 API → Android 等价物
 *  - Folia 的 `resolveSteppedBlockFocusPoint` / `resolveSmoothBlockFocusPoint` /
 *    `resolveBlockEntryFocusPoint` 返回 `{ x, y }` 字面量 ⇒ Android 改成**写入调用方持有的
 *    可变对象**（[FumeTypes.FumePoint]），绘制期零分配。
 *  - Folia 的 `resolveOverviewFlightBridge` 返回对象或 `null` ⇒ Android 改成
 *    **写入可变对象 + 返回 Boolean**。
 *  - Folia 的 `resolveArticleOverviewCamera` 返回新的 `CameraViewTarget` ⇒ Android 改成
 *    **写入调用方持有的可变对象**（[FumeTypes.CameraViewTarget]），绘制期零分配。
 *  - Folia 的 `getLineRenderHints` / `getLineTransitionTiming`（`src/utils/lyrics/renderHints.ts`）
 *    ⇒ 本仓已有的 `LyricRenderHints.getLineRenderHints` / `LyricRenderHints.getLineTransitionTiming`。
 */
object FumeCamera {

    /** 移植 fumeCamera.ts:11 */
    const val CAMERA_SCALE_MIN = 0.22f

    /** 移植 fumeCamera.ts:12 */
    const val CAMERA_SCALE_MAX = 2.24f

    /** 移植 fumeCamera.ts:13 */
    const val OVERVIEW_CAMERA_SOURCE = -2

    /** 移植 fumeCamera.ts:15 */
    private const val FUME_CAMERA_TELEPORT_TRIGGER_SCREENS = 2.75f

    /**
     * 移植 fumeCamera.ts:17-45 `resolveSteppedBlockFocusPoint`
     *
     * @param out 焦点点的持有者（复用对象）。
     */
    fun resolveSteppedBlockFocusPoint(
        block: FumeTypes.FumeBlock,
        printedCount: Int,
        out: FumeTypes.FumePoint,
    ): FumeTypes.FumePoint {
        if (block.renderLines.isEmpty()) {
            out.x = block.x + block.width * 0.5f
            out.y = block.y + block.height * 0.5f
            return out
        }

        val effectiveOffset = FumeMath.clamp(printedCount.toFloat(), 0f, block.graphemes.size.toFloat()).toInt()
        var targetLine = block.renderLines[block.renderLines.size - 1]
        for (renderLine in block.renderLines) {
            if (effectiveOffset <= renderLine.end) {
                targetLine = renderLine
                break
            }
        }
        val localOffset = FumeMath.clamp(
            effectiveOffset.toFloat(),
            targetLine.start.toFloat(),
            targetLine.end.toFloat(),
        ).toInt()
        val progressWidth = FumeTextMeasure.widthBetweenOffsets(
            block.prepared,
            block.segmentMetas,
            targetLine.start,
            localOffset,
        )
        val minX = block.x + targetLine.left
        val maxX = minX + targetLine.width

        out.x = FumeMath.clamp(minX + progressWidth, minX, maxX)
        out.y = block.y + targetLine.top + block.lineHeight * 0.5f
        return out
    }

    /**
     * 移植 fumeCamera.ts:47-120 `resolveSmoothBlockFocusPoint`
     *
     * @param out 焦点点的持有者（复用对象）。
     */
    fun resolveSmoothBlockFocusPoint(
        block: FumeTypes.FumeBlock,
        printedProgress: Float,
        out: FumeTypes.FumePoint,
    ): FumeTypes.FumePoint {
        if (block.renderLines.isEmpty()) {
            out.x = block.x + block.width * 0.5f
            out.y = block.y + block.height * 0.5f
            return out
        }

        val effectiveOffset = FumeMath.clamp(
            printedProgress,
            0f,
            block.graphemes.size.toFloat(),
        )

        val targetLineIndex = findRenderLineIndex(block, effectiveOffset)
        resolvePointOnRenderLine(block, targetLineIndex, effectiveOffset, out)
        val pointX = out.x
        val pointY = out.y

        val currentLine = block.renderLines[targetLineIndex]
        val crossLineBlendWindow = 0.7f

        if (targetLineIndex > 0 && effectiveOffset < currentLine.start + crossLineBlendWindow) {
            val previousLine = block.renderLines[targetLineIndex - 1]
            val blend = FumeMath.easeInOutCubic(
                FumeMath.clamp(
                    1f - ((effectiveOffset - previousLine.end) / crossLineBlendWindow),
                    0f,
                    1f,
                ),
            )
            resolvePointOnRenderLine(block, targetLineIndex - 1, previousLine.end.toFloat(), out)
            out.x = FumeMath.mix(pointX, out.x, blend)
            out.y = FumeMath.mix(pointY, out.y, blend)
        } else if (targetLineIndex < block.renderLines.size - 1 &&
            effectiveOffset > currentLine.end - crossLineBlendWindow
        ) {
            val nextLine = block.renderLines[targetLineIndex + 1]
            val blend = FumeMath.easeInOutCubic(
                FumeMath.clamp(
                    (effectiveOffset - (currentLine.end - crossLineBlendWindow)) / crossLineBlendWindow,
                    0f,
                    1f,
                ),
            )
            resolvePointOnRenderLine(block, targetLineIndex + 1, nextLine.start.toFloat(), out)
            out.x = FumeMath.mix(pointX, out.x, blend)
            out.y = FumeMath.mix(pointY, out.y, blend)
        }

        return out
    }

    /** 移植 fumeCamera.ts:59-62 `findRenderLineIndex`（Folia 是内联闭包） */
    private fun findRenderLineIndex(block: FumeTypes.FumeBlock, offset: Float): Int {
        for (index in block.renderLines.indices) {
            if (offset <= block.renderLines[index].end) return index
        }
        return block.renderLines.size - 1
    }

    /** 移植 fumeCamera.ts:64-86 `resolvePointOnRenderLine`（Folia 是内联闭包） */
    private fun resolvePointOnRenderLine(
        block: FumeTypes.FumeBlock,
        lineIndex: Int,
        offset: Float,
        out: FumeTypes.FumePoint,
    ) {
        val targetLine = block.renderLines[lineIndex.coerceAtMost(block.renderLines.size - 1)]
        val clampedOffset = FumeMath.clamp(
            offset,
            targetLine.start.toFloat(),
            targetLine.end.toFloat(),
        )
        val baseOffset = floor(clampedOffset).toInt()
        val fractionalOffset = clampedOffset - baseOffset
        val baseWidth = FumeTextMeasure.widthBetweenOffsets(
            block.prepared,
            block.segmentMetas,
            targetLine.start,
            baseOffset,
        )
        val localGlyphIndex = baseOffset - targetLine.start
        val glyphAdvance = if (localGlyphIndex >= 0 && localGlyphIndex < targetLine.graphemes.size) {
            FumeTextMeasure.resolveGlyphAdvance(targetLine, localGlyphIndex)
        } else {
            0f
        }
        val minX = block.x + targetLine.left
        val maxX = minX + targetLine.width

        out.x = FumeMath.clamp(minX + baseWidth + glyphAdvance * fractionalOffset, minX, maxX)
        out.y = block.y + targetLine.top + block.lineHeight * 0.5f
    }

    /**
     * 移植 fumeCamera.ts:125-140 `resolveBlockEntryFocusPoint`
     *
     * Folia 原文带 `@ai-ignore` 标记（要求逐字保留），此处按其行为逐行移植。
     */
    fun resolveBlockEntryFocusPoint(block: FumeTypes.FumeBlock, out: FumeTypes.FumePoint): FumeTypes.FumePoint {
        val firstRenderLine = block.renderLines.firstOrNull()
        if (firstRenderLine == null) {
            out.x = block.x + block.width * 0.5f
            out.y = block.y + block.height * 0.5f
            return out
        }

        out.x = block.x + firstRenderLine.left
        out.y = block.y + firstRenderLine.top + block.lineHeight * 0.5f
        return out
    }

    /** 移植 fumeCamera.ts:142-149 `resolveCameraScaleForBlock` */
    fun resolveCameraScaleForBlock(
        block: FumeTypes.FumeBlock,
        viewport: FumeTypes.ViewportSize,
    ): Float {
        val minViewportSide = max(min(viewport.width, viewport.height), 1f)
        val targetLineHeight = FumeMath.clamp(minViewportSide * 0.115f, 64f, 124f)
        return FumeMath.clamp(targetLineHeight / max(block.lineHeight, 1f), 0.88f, 2.2f)
    }

    /**
     * 移植 fumeCamera.ts:151-180 `resolveCameraRetargetDuration`
     *
     * `getLineRenderHints` / `getLineTransitionTiming` ⇒ 本仓已有的
     * `LyricRenderHints.getLineRenderHints` / `LyricRenderHints.getLineTransitionTiming`。
     */
    fun resolveCameraRetargetDuration(line: LyricLine): Float {
        val hints = LyricRenderHints.getLineRenderHints(line) ?: return 0.09f

        val transitionTiming = LyricRenderHints.getLineTransitionTiming(
            hints.rawDuration,
            hints.lineTransitionMode,
            hints.wordRevealMode,
        )

        if (hints.lineTransitionMode == "none") {
            return FumeMath.clamp(max(hints.rawDuration.toFloat(), 0.08f) * 0.34f, 0.04f, 0.075f)
        }

        if (hints.lineTransitionMode == "fast") {
            return FumeMath.clamp(
                transitionTiming.enterDuration.toFloat() * 0.5f +
                    transitionTiming.exitDuration.toFloat() * 0.12f,
                0.055f,
                0.095f,
            )
        }

        return FumeMath.clamp(
            transitionTiming.enterDuration.toFloat() * 0.44f +
                transitionTiming.linePassHold.toFloat() * 0.22f,
            0.075f,
            0.13f,
        )
    }

    /** 移植 fumeCamera.ts:182-186 `resolveOverviewRetargetDuration` */
    fun resolveOverviewRetargetDuration(viewport: FumeTypes.ViewportSize): Float =
        FumeMath.clamp(min(viewport.width, viewport.height) / 1500f, 0.38f, 0.58f)

    /**
     * 移植 fumeCamera.ts:188-261 `resolveOverviewFlightBridge`
     *
     * @param out 桥接点的持有者（复用对象）；返回 false = 没有桥接（`null`）。
     */
    fun resolveOverviewFlightBridge(
        fromX: Float,
        fromY: Float,
        fromScale: Float,
        targetX: Float,
        targetY: Float,
        targetScale: Float,
        overviewCamera: FumeTypes.CameraViewTarget?,
        viewport: FumeTypes.ViewportSize,
        out: FumeTypes.FumeFlightBridge,
    ): Boolean {
        if (overviewCamera == null) return false

        val safeScale = max(max(fromScale, targetScale), max(overviewCamera.scale, 0.001f))
        val minViewportSide = max(min(viewport.width, viewport.height), 1f)
        val deltaX = fromX - targetX
        val deltaY = fromY - targetY
        val worldDistance = hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat()
        val screenDistance = worldDistance * safeScale

        if (worldDistance <= 0f ||
            screenDistance < minViewportSide * FUME_CAMERA_TELEPORT_TRIGGER_SCREENS
        ) {
            return false
        }

        val loftStrength = FumeMath.clamp(
            (screenDistance / minViewportSide - FUME_CAMERA_TELEPORT_TRIGGER_SCREENS) / 3.4f,
            0f,
            1f,
        )
        val midpointX = FumeMath.mix(fromX, targetX, 0.5f)
        val midpointY = FumeMath.mix(fromY, targetY, 0.5f)
        val waypointCenterBias = FumeMath.mix(0.18f, 0.42f, loftStrength)
        val waypointX = FumeMath.mix(midpointX, overviewCamera.x, waypointCenterBias)
        val waypointY = FumeMath.mix(midpointY, overviewCamera.y, waypointCenterBias)
        val endpointScale = max(max(fromScale, targetScale), 0.001f)
        val loftedScale = endpointScale * FumeMath.mix(0.62f, 0.4f, loftStrength)
        val overviewLimitedScale = overviewCamera.scale * FumeMath.mix(1.85f, 1.55f, loftStrength)
        val waypointScale = FumeMath.clamp(
            max(loftedScale, overviewLimitedScale),
            CAMERA_SCALE_MIN,
            max(endpointScale * 0.92f, CAMERA_SCALE_MIN),
        )
        val overviewDistanceFromStart = hypot(
            (waypointX - fromX).toDouble(),
            (waypointY - fromY).toDouble(),
        ).toFloat() * max(max(fromScale, waypointScale), 0.001f)
        val overviewDistanceToTarget = hypot(
            (targetX - waypointX).toDouble(),
            (targetY - waypointY).toDouble(),
        ).toFloat() * max(max(targetScale, waypointScale), 0.001f)
        val totalLegDistance = overviewDistanceFromStart + overviewDistanceToTarget
        val waypointPhase = if (totalLegDistance <= 0f) {
            0.36
        } else {
            FumeMath.clamp(overviewDistanceFromStart / totalLegDistance, 0.26f, 0.44f).toDouble()
        }
        val duration = FumeMath.clamp(
            0.26f + (screenDistance / (minViewportSide * 5.5f)) * 0.28f,
            0.3f,
            0.68f,
        )

        out.waypointX = waypointX
        out.waypointY = waypointY
        out.waypointScale = waypointScale
        out.waypointPhase = waypointPhase
        out.duration = duration.toDouble()
        return true
    }

    /**
     * 移植 fumeCamera.ts:263-306 `resolveArticleOverviewCamera`
     *
     * @param out 全景机位的持有者（复用对象）——Folia 每次都返回一个新对象，
     *            这里改成就地写入，绘制期零分配。
     */
    fun resolveArticleOverviewCamera(
        article: FumeTypes.FumeArticleLayout,
        viewport: FumeTypes.ViewportSize,
        out: FumeTypes.CameraViewTarget,
    ) {
        if (article.blocks.isEmpty()) {
            val fitScale = min(
                viewport.width / max(article.width, 1f),
                viewport.height / max(article.height, 1f),
            )

            out.x = article.width * 0.5f
            out.y = article.height * 0.5f
            out.scale = FumeMath.clamp(fitScale * 0.92f, CAMERA_SCALE_MIN, 0.72f)
            return
        }

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for (block in article.blocks) {
            minX = min(minX, block.x)
            minY = min(minY, block.y)
            maxX = max(maxX, block.x + block.width)
            maxY = max(maxY, block.y + block.height)
        }

        val paddingX = FumeMath.clamp(viewport.width * 0.2f, 120f, 280f)
        val paddingY = FumeMath.clamp(viewport.height * 0.2f, 96f, 220f)
        val framedWidth = max(maxX - minX + paddingX * 2, 1f)
        val framedHeight = max(maxY - minY + paddingY * 2, 1f)
        val fitScale = min(
            viewport.width / framedWidth,
            viewport.height / framedHeight,
        )

        out.x = (minX + maxX) * 0.5f
        out.y = (minY + maxY) * 0.5f
        out.scale = FumeMath.clamp(fitScale, CAMERA_SCALE_MIN, 0.72f)
    }

    /**
     * 移植 fumeCamera.ts:308-341 `resolveFocusBlock`
     *
     * 相机该关注的块：优先当前歌词行；否则最后一个已渲染的行；否则最后一个已有打印内容的块。
     */
    fun resolveFocusBlock(
        article: FumeTypes.FumeArticleLayout,
        currentLineIndex: Int,
        currentTimeValue: Double,
    ): FumeTypes.FumeBlock? {
        if (currentLineIndex >= 0) {
            val active = article.blockBySourceLineIndex[currentLineIndex]
            if (active != null) return active
        }

        val chronologicalLastBlock = article.chronologicalBlocks.lastOrNull()

        if (chronologicalLastBlock != null &&
            currentTimeValue >= article.lastChronologicalRenderEndTime
        ) {
            return chronologicalLastBlock
        }

        // ⛔ Folia 是 `for (let index = len - 1; index >= 0; index -= 1)` ⇒ 这里用 while，
        //    避免 Kotlin 的 `indices.reversed()` 在逐帧路径上产生迭代器对象。
        var index = article.chronologicalBlocks.size - 1
        while (index >= 0) {
            val block = article.chronologicalBlocks[index]
            val printedCount = FumeReveal.resolvePrintedGraphemeCount(
                block.line,
                block.wordRanges,
                block.graphemes.size,
                currentTimeValue,
            )

            if (printedCount > 0) return block
            index -= 1
        }

        return article.chronologicalBlocks.firstOrNull()
    }
}
