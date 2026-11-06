package com.nasmusic.tv.visualizer.renderers.lyricsStage

import kotlin.math.abs

/**
 * 逐字形辉光层（**共享**）—— 移植自 Folia（AGPL-3.0）:
 * `src/components/visualizer/wordGlow.ts`
 * Folia 版本: 0.5.27-a16525c
 *
 * ## Folia 的原意
 * Folia 的 `GlowWord.tsx:86-119` 在正文层（Body Layer）**下面**铺一层辉光层：
 * 透明字形 + text-shadow / drop-shadow。辉光从无到满再淡回；
 * 每一档揭示模式有各自的半径与关键帧。
 *
 * ⚠️ **为什么是共享的**：Folia 里这层被 `classic` 与 `partita` **共用**
 * （两者都渲染 `<GlowWord>`）⇒ 本仓也必须共用，否则分栏引导会缺一层光晕
 * （与上游画面不一致）。
 *
 * ## 为什么不用 text-shadow
 * Folia 的注释（wordGlow.ts:10-27）说明了原因：Chromium 的字形缓存把阴影的
 * 模糊半径算进 strike key ⇒ 每帧改半径会不断铸造 strike ⇒ 泄漏 fd。
 * 因此 Linux 上改用 `drop-shadow()` 合成器滤镜（不经过字形缓存，半径可自由动画）。
 *
 * Android 侧：`Paint.setShadowLayer` 是硬件加速支持的 ⇒ 用它近似，
 * 外圈 + 内圈两遍（内圈按 `DROP_SHADOW_INNER_ALPHA` 降透明度，因为两层阴影会互相叠加）。
 */
object GlowWordGlow {

    /** 移植 wordGlow.ts:29 `DROP_SHADOW_RADIUS_SCALE` */
    const val DROP_SHADOW_RADIUS_SCALE = 0.4f

    /** 移植 wordGlow.ts:31 `DROP_SHADOW_INNER_ALPHA`（内圈要弱 —— 外圈也会给它投影） */
    const val DROP_SHADOW_INNER_ALPHA = 0.7f

    /** 移植 wordGlow.ts:61-63 `glowRadii` —— text-shadow 半径按揭示模式 */
    fun radiiFor(wordRevealMode: String): Pair<Float, Float> = when (wordRevealMode) {
        "instant" -> 14f to 24f
        "fast" -> 18f to 32f
        else -> 20f to 40f
    }

    /** 移植 wordGlow.ts:157 —— passed 档的淡出时长 */
    fun fadeDuration(wordRevealMode: String): Double = when (wordRevealMode) {
        "instant" -> 0.12
        "fast" -> 0.22
        else -> 0.9
    }

    // ── 关键帧（移植 wordGlow.ts:74-87 / :100-155）──────────────

    private val TIMES_INSTANT = floatArrayOf(0f, 0.35f, 1f)
    private val TIMES_FAST = floatArrayOf(0f, 0.4f, 1f)
    private val TIMES_SWEEP = floatArrayOf(0f, 0.3f, 1f)
    private val TIMES_SUSTAINED = floatArrayOf(0f, 0.9f, 1f)

    /** 升-降（瞬时）峰值 */
    private val PEAKS_RAMP = floatArrayOf(0f, 1f, 0f)

    /** 持续（单词级）峰值 */
    private val PEAKS_SUSTAINED = floatArrayOf(0f, 1f, 1f)

    /**
     * 移植 wordGlow.ts:94-167 —— 某个字形此刻的辉光峰值（0..1）。
     *
     * @param status 词状态（waiting 无辉光）
     * @param wordRevealMode "normal" | "fast" | "instant"
     * @param displayDuration 词的显示时长（`GlowWordCore.displayDuration`）
     * @param graphemeCount 该词的字形数（>1 ⇒ 走「逐字扫过」曲线）
     * @param graphemeStart 该字形的开始时间（秒）
     * @param graphemeEnd 该字形的结束时间（秒）
     * @param wordStartTime 词的开始时间（秒）
     * @param activeStart 进入 active 的时刻（秒）
     * @param passedAt 进入 passed 的时刻（秒）
     * @param nowSec 当前播放时间（秒）
     */
    fun peakForGrapheme(
        status: GlowWordStatus,
        wordRevealMode: String,
        displayDuration: Double,
        graphemeCount: Int,
        graphemeStart: Double,
        graphemeEnd: Double,
        wordStartTime: Double,
        activeStart: Double,
        passedAt: Double,
        nowSec: Double,
    ): Float {
        if (status == GlowWordStatus.WAITING) return 0f
        if (status == GlowWordStatus.ACTIVE) {
            return ramp(
                wordRevealMode = wordRevealMode,
                displayDuration = displayDuration,
                graphemeCount = graphemeCount,
                graphemeStart = graphemeStart,
                graphemeEnd = graphemeEnd,
                wordStartTime = wordStartTime,
                elapsed = nowSec - activeStart,
            )
        }
        // passed：离场瞬间的峰值 × (1 − easeOut(t / fade))
        val fade = fadeDuration(wordRevealMode)
        if (fade <= 0.0) return 0f
        val elapsed = nowSec - passedAt
        if (elapsed < 0.0) return 0f
        val t = (elapsed / fade).toFloat()
        if (t >= 1f) return 0f
        val atPass = ramp(
            wordRevealMode = wordRevealMode,
            displayDuration = displayDuration,
            graphemeCount = graphemeCount,
            graphemeStart = graphemeStart,
            graphemeEnd = graphemeEnd,
            wordStartTime = wordStartTime,
            elapsed = passedAt - activeStart,
        )
        if (atPass <= 0f) return 0f
        return atPass * (1f - easeOut(t))
    }

    /** 移植 wordGlow.ts:100-154 —— active 档的逐字形辉光曲线 */
    private fun ramp(
        wordRevealMode: String,
        displayDuration: Double,
        graphemeCount: Int,
        graphemeStart: Double,
        graphemeEnd: Double,
        wordStartTime: Double,
        elapsed: Double,
    ): Float {
        val duration: Double
        val times: FloatArray
        val peaks: FloatArray
        val delay: Double

        when {
            wordRevealMode == "instant" -> {
                // 移植 wordGlow.ts:101-110 —— duration = min(duration, 0.12)，times [0, 0.35, 1]
                duration = minOf(displayDuration, 0.12)
                times = TIMES_INSTANT
                peaks = PEAKS_RAMP
                delay = 0.0
            }
            wordRevealMode == "fast" -> {
                // 移植 wordGlow.ts:112-121 —— duration = clamp(duration, 0.12, 0.2)，times [0, 0.4, 1]
                duration = minOf(maxOf(displayDuration, 0.12), 0.2)
                times = TIMES_FAST
                peaks = PEAKS_RAMP
                delay = 0.0
            }
            graphemeCount > 1 -> {
                // 移植 wordGlow.ts:123-144 —— 逐字扫过
                //   charDuration = max(charEnd − charStart, 0.001)
                //   duration = charDuration × 6（把淡出拉长到几个字母）
                //   times [0, 0.3, 1]，delay = max(0, charStart − wordStart)
                val charDuration = maxOf(graphemeEnd - graphemeStart, 0.001)
                duration = charDuration * 6.0
                times = TIMES_SWEEP
                peaks = PEAKS_RAMP
                delay = maxOf(0.0, graphemeStart - wordStartTime)
            }
            else -> {
                // 移植 wordGlow.ts:146-154 —— 单字形 / CJK：持续辉光
                //   duration = displayDuration，times [0, 0.9, 1]，peaks [0, 1, 1]
                duration = displayDuration
                times = TIMES_SUSTAINED
                peaks = PEAKS_SUSTAINED
                delay = 0.0
            }
        }

        val t = (elapsed - delay) / duration
        if (t <= 0.0) return 0f
        if (t >= 1.0) return peaks[2]
        return keyframeRamp(
            t.toFloat(),
            times,
            peaks,
            if (wordRevealMode == "instant") EasingId.EASE_OUT else EasingId.EASE_IN_OUT,
        )
    }

    /** 三关键帧插值（移植 framer-motion 的 `times` + `ease` 语义） */
    fun keyframeRamp(
        t: Float,
        times: FloatArray,
        peaks: FloatArray,
        easing: Int,
    ): Float {
        val t0 = times[0]
        val t1 = times[1]
        val t2 = times[2]
        val seg = if (t < t1) 0 else 1
        val local = if (seg == 0) {
            if (t1 <= t0) 0f else (t - t0) / (t1 - t0)
        } else {
            if (t2 <= t1) 0f else (t - t1) / (t2 - t1)
        }
        val e = ease(easing, local.coerceIn(0f, 1f))
        return peaks[seg] + (peaks[seg + 1] - peaks[seg]) * e
    }

    // ── 缓动 ───────────────────────────────────────────────────

    /** framer-motion 的默认 tween 缓动标识 */
    object EasingId {
        /** ease-out = cubic-bezier(0, 0, 0.58, 1) */
        const val EASE_OUT = 1

        /** ease-in-out = cubic-bezier(0.42, 0, 0.58, 1) */
        const val EASE_IN_OUT = 2
    }

    private val easeOutBezier = UnitBezier(0f, 0f, 0.58f, 1f)
    private val easeInOutBezier = UnitBezier(0.42f, 0f, 0.58f, 1f)

    /** 按标识取缓动值（0..1） */
    fun ease(easing: Int, t: Float): Float = when (easing) {
        EasingId.EASE_IN_OUT -> easeInOutBezier.ease(t)
        else -> easeOutBezier.ease(t)
    }

    /** ease-out = cubic-bezier(0, 0, 0.58, 1) */
    fun easeOut(t: Float): Float = easeOutBezier.ease(t)

    /** ease-in-out = cubic-bezier(0.42, 0, 0.58, 1) */
    fun easeInOut(t: Float): Float = easeInOutBezier.ease(t)

    /**
     * CSS `cubic-bezier(x1, y1, x2, y2)` 的求值。
     *
     * 用 24 步二分求 t（与浏览器同法）⇒ 精度约 2⁻²⁴。
     */
    class UnitBezier(
        private val x1: Float,
        private val y1: Float,
        private val x2: Float,
        private val y2: Float,
    ) {
        fun ease(t: Float): Float {
            if (t <= 0f) return 0f
            if (t >= 1f) return 1f
            var lo = 0f
            var hi = 1f
            var mid = t
            repeat(24) {
                val x = sampleX(mid)
                if (x < t) lo = mid else hi = mid
                mid = (lo + hi) * 0.5f
            }
            return sampleY(mid)
        }

        private fun sampleX(t: Float): Float {
            val u = 1f - t
            return 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t
        }

        private fun sampleY(t: Float): Float {
            val u = 1f - t
            return 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t
        }
    }

    /** 供外部复用的「峰值 → 透明度」换算（0..255） */
    fun alphaOf(peak: Float, lineOpacity: Float): Int =
        (peak * lineOpacity * 255f).toInt().coerceIn(0, 255)

    /** 供外部判断「这个峰值是否值得画」 */
    fun isVisible(peak: Float): Boolean = peak > 0.003f && !abs(peak).isNaN()
}
