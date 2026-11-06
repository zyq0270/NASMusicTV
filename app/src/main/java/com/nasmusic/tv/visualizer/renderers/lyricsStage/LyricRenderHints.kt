package com.nasmusic.tv.visualizer.renderers.lyricsStage

/**
 * 移植自 Folia（AGPL-3.0）: src/utils/lyrics/renderHints.ts
 * Folia 版本: 0.5.27-a16525c
 *
 * renderHints 来自歌词管线，不是视觉器自己算的；这里只是把它们重新打包成逐帧好用的形状。
 */
object LyricRenderHints {

    const val MICRO_LINE_DURATION_THRESHOLD = 0.10
    const val SHORT_LINE_DURATION_THRESHOLD = 0.18
    const val MICRO_LINE_RENDER_FLOOR = 0.067

    /** 移植 renderHints.ts:37-41 `LineTransitionTiming` */
    data class LineTransitionTiming(
        val enterDuration: Double,
        val exitDuration: Double,
        val linePassHold: Double,
    )

    /** 移植 renderHints.ts:48-74 `getLineTransitionTiming` */
    fun getLineTransitionTiming(
        rawDuration: Double,
        lineTransitionMode: String,
        wordRevealMode: String,
    ): LineTransitionTiming {
        if (lineTransitionMode == "none") {
            return LineTransitionTiming(0.0, 0.0, 0.0)
        }

        if (lineTransitionMode == "fast") {
            return LineTransitionTiming(
                enterDuration = clamp(rawDuration * 0.45, 0.045, 0.06),
                exitDuration = clamp(rawDuration * 0.22, 0.03, 0.04),
                linePassHold = if (wordRevealMode == "instant") 0.0 else 0.03,
            )
        }

        return LineTransitionTiming(
            enterDuration = Math.min(0.42, Math.max(0.22, Math.max(rawDuration, 0.12) * 0.34)),
            exitDuration = Math.min(0.32, Math.max(0.18, Math.max(rawDuration, 0.12) * 0.18)),
            linePassHold = if (wordRevealMode == "instant") 0.0 else 0.06,
        )
    }

    /** 移植 renderHints.ts:76-86 `getTimingClass` */
    fun getTimingClass(rawDuration: Double): String = when {
        rawDuration < MICRO_LINE_DURATION_THRESHOLD -> "micro"
        rawDuration < SHORT_LINE_DURATION_THRESHOLD -> "short"
        else -> "normal"
    }

    /** 移植 renderHints.ts:88-98 `getLineTransitionMode` */
    fun getLineTransitionMode(timingClass: String): String = when (timingClass) {
        "micro" -> "none"
        "short" -> "fast"
        else -> "normal"
    }

    /** 移植 renderHints.ts:100-110 `getWordRevealMode` */
    fun getWordRevealMode(timingClass: String): String = when (timingClass) {
        "micro" -> "instant"
        "short" -> "fast"
        else -> "normal"
    }

    /** 移植 renderHints.ts:114-135 `buildLineRenderEndTime` */
    fun buildLineRenderEndTime(
        startTime: Double,
        endTime: Double,
        lastWordEndTime: Double,
        rawDuration: Double,
        lineTransitionMode: String,
        wordRevealMode: String,
    ): Double {
        // renderEndTime 是视觉器可以在揭示结束后继续把这行留在屏幕上的最晚时刻。
        // 它不是一条独立的时间轴：后一行可能更早开始，从而把这段额外窗口截短。
        if (lineTransitionMode == "none") {
            return Math.max(endTime, startTime + MICRO_LINE_RENDER_FLOOR)
        }

        val transitionTiming = getLineTransitionTiming(rawDuration, lineTransitionMode, wordRevealMode)
        val linePassStart = Math.max(lastWordEndTime, startTime) + transitionTiming.linePassHold
        val exitStart = if (lineTransitionMode == "fast") {
            // ⚠️ Folia 的 JS `Math.max` 是**变参**的（可传 3 个），
            //    Kotlin 的 `Math.max` 只收 2 个 ⇒ 这里必须用 `maxOf`（变参版）。
            maxOf(
                startTime + transitionTiming.enterDuration + 0.01,
                linePassStart,
                endTime - transitionTiming.exitDuration,
            )
        } else {
            Math.max(linePassStart, endTime - transitionTiming.exitDuration)
        }

        return Math.max(endTime, exitStart + transitionTiming.exitDuration)
    }

    /** 移植 renderHints.ts:137-155 `buildLineRenderHints` */
    fun buildLineRenderHints(line: LyricLine): LineRenderHints {
        val rawDuration = Math.max(line.endTime - line.startTime, 0.0)
        val timingClass = getTimingClass(rawDuration)
        val lineTransitionMode = getLineTransitionMode(timingClass)
        val wordRevealMode = getWordRevealMode(timingClass)
        val lastWordEndTime = line.words.lastOrNull()?.endTime ?: line.endTime

        return LineRenderHints(
            rawDuration = rawDuration,
            timingClass = timingClass,
            renderEndTime = buildLineRenderEndTime(
                line.startTime,
                line.endTime,
                lastWordEndTime,
                rawDuration,
                lineTransitionMode,
                wordRevealMode,
            ),
            lineTransitionMode = lineTransitionMode,
            wordRevealMode = wordRevealMode,
        )
    }

    /** 移植 renderHints.ts:157-163 `getLineRenderHints` */
    fun getLineRenderHints(line: LyricLine?): LineRenderHints? {
        if (line == null) return null
        return line.renderHints ?: buildLineRenderHints(line)
    }

    /** 移植 renderHints.ts:165-171 `getLineRenderEndTime` */
    fun getLineRenderEndTime(line: LyricLine?): Double {
        if (line == null) return Double.NEGATIVE_INFINITY
        return getLineRenderHints(line)?.renderEndTime ?: line.endTime
    }

    private fun clamp(value: Double, min: Double, max: Double): Double =
        Math.min(Math.max(value, min), max)
}
