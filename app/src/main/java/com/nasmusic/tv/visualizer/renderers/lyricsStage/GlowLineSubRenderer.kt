// 移植自 Folia (AGPL-3.0): src/components/visualizer/classic/Visualizer.tsx 等 — Project Folia version 0.5.27-a16525c
package com.nasmusic.tv.visualizer.renderers.lyricsStage

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

// ══════════════════════════════════════════════════════════════════
// 文件级常量与工具
//
// ⚠️ 放在文件级而不是类里：嵌套类 `Anim` / `ColorAnim` 需要访问它们，
//    而 Kotlin 的嵌套类**没有**外层实例 ⇒ 类成员不可直接引用。
// ══════════════════════════════════════════════════════════════════

/** 线性 */
private const val EASE_LINEAR = 0

/** framer-motion 默认 tween 缓动 `easeOut` = cubicBezier(0, 0, 0.58, 1) */
private const val EASE_OUT = 1

/** framer-motion `easeInOut` = cubicBezier(0.42, 0, 0.58, 1) */
private const val EASE_IN_OUT = 2

/** 移植 glowWordVariants.ts:25-33 `spring(stiffness 200, damping 20)`（mass = 1） */
private const val SPRING_STIFFNESS = 200f
private const val SPRING_DAMPING = 20f
private const val SPRING_MAX_STEP = 1.0 / 240.0
private const val SPRING_REST_DELTA = 0.01f
private const val SPRING_REST_SPEED = 0.5f

/**
 * CSS `cubic-bezier(x1, y1, x2, y2)` 的求值器（WebKit UnitBezier 的多项式形式）。
 * framer-motion 的 `easeOut` / `easeInOut` 就是两条贝塞尔曲线。
 */
private class UnitBezier(x1: Float, y1: Float, x2: Float, y2: Float) {
    private val cx = 3f * x1
    private val bx = 3f * (x2 - x1) - cx
    private val ax = 1f - cx - bx
    private val cy = 3f * y1
    private val by = 3f * (y2 - y1) - cy
    private val ay = 1f - cy - by

    private fun sampleX(t: Float): Float = ((ax * t + bx) * t + cx) * t
    private fun sampleY(t: Float): Float = ((ay * t + by) * t + cy) * t
    private fun sampleDerivativeX(t: Float): Float = (3f * ax * t + 2f * bx) * t + cx

    /** 解 x(t) = x，再取 y(t) */
    fun ease(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var t = x
        var iter = 0
        while (iter < 8) {
            val dx = sampleX(t) - x
            if (abs(dx) < 1e-6f) return sampleY(t)
            val d = sampleDerivativeX(t)
            if (abs(d) < 1e-6f) break
            t -= dx / d
            iter++
        }
        // 牛顿迭代失败 ⇒ 二分回退
        var lo = 0f
        var hi = 1f
        t = x
        iter = 0
        while (iter < 24) {
            val xt = sampleX(t)
            if (abs(xt - x) < 1e-6f) return sampleY(t)
            if (xt < x) lo = t else hi = t
            t = (lo + hi) * 0.5f
            iter++
        }
        return sampleY(t)
    }
}

private val easeOutBezier = UnitBezier(0f, 0f, 0.58f, 1f)
private val easeInOutBezier = UnitBezier(0.42f, 0f, 0.58f, 1f)

private fun ease(easing: Int, t: Float): Float = when (easing) {
    EASE_LINEAR -> t
    EASE_IN_OUT -> easeInOutBezier.ease(t)
    else -> easeOutBezier.ease(t)
}

/** 颜色补间（移植 glowWordVariants.ts:55-83 的 `color` 过渡，按 sRGB 分量插值）。 */
private fun lerpColor(a: Int, b: Int, t: Float): Int {
    val af = (a ushr 24) and 0xFF
    val bf = (b ushr 24) and 0xFF
    val ar = (a ushr 16) and 0xFF
    val br = (b ushr 16) and 0xFF
    val ag = (a ushr 8) and 0xFF
    val bg = (b ushr 8) and 0xFF
    val ab = a and 0xFF
    val bb = b and 0xFF
    val o = ((af + (bf - af) * t).toInt() shl 24) or
        ((ar + (br - ar) * t).toInt() shl 16) or
        ((ag + (bg - ag) * t).toInt() shl 8) or
        (ab + (bb - ab) * t).toInt()
    return o
}

/**
 * 逐字行光（歌词舞台子模式 1）—— Folia `classic` 视觉器的逐行移植。
 *
 * 移植自 Folia (AGPL-3.0): src/components/visualizer/classic/Visualizer.tsx 等 — Project Folia version 0.5.27-a16525c
 *
 * 移植范围（Folia 行号）：
 *  - `classic/Visualizer.tsx:145`      主字号 `clamp(2.25rem, 6vw, 4.5rem)`
 *  - `classic/Visualizer.tsx:151-308`  逐行确定性随机布局（wordConfigs + lineConfig）
 *  - `classic/Visualizer.tsx:314-341`  整行呼吸浮动（5 帧 keyframe，repeat: Infinity）
 *  - `glowWordVariants.ts:12-51`       逐词「容器」动画（waiting / active / passed）
 *  - `glowWordVariants.ts:55-83`       逐词「正文」动画（颜色 + 模糊）
 *  - `wordGlow.ts:94-167`              逐字形辉光层
 *  - `GlowWord.tsx:130-143`            副歌涟漪
 *  - `GlowWord.tsx:53-61` / `glowWordTiming.ts:58-91` → 复用本仓已有的 `GlowWordCore`
 *
 * ## 浏览器 API → Android 等价物（逐条记录）
 *  - CSS `clamp(a, b, c)` → `max(a, min(b, c))`
 *  - CSS `rem` → 16px 常量（`remPx`）
 *  - CSS `vw` → 画布宽度百分比
 *  - CSS `max-w-6xl` → 72rem = 1152px 常量（`maxLineWidthPx`）
 *  - CSS `h-[70vh]` → 画布高度的 70%（`containerHeightRatio`），容器垂直居中 ⇒ 屏幕中间 70%
 *  - CSS `p-8` → `ctx.safeAreaPx`（Folia 的 `p-8` 其实是 `2rem = 32px`；本仓 `RenderContext`
 *    没有 rem 基准的 padding 来源，按任务约定用 overscan 安全边距代替）
 *  - CSS `flex / flex-wrap / justify-content / align-items` → 本文件内的确定性流式排版
 *  - CSS `perspective` → 仅保留数值，不参与 2D 投影（见 `LineLayout.perspective` 的注释）
 *  - CSS `filter: blur(Npx)` → `Paint.setShadowLayer`（同色模糊副本）；
 *    ⛔ HW 加速下 `BlurMaskFilter`（MaskFilter）会让整条绘制被丢弃 ⇒ 不用它
 *  - CSS `text-shadow` / `drop-shadow` → `Paint.setShadowLayer`（`glowOuterPaint` / `glowInnerPaint`）
 *  - framer-motion `tween`（默认缓动 `easeOut`）→ `UnitBezier(0, 0, 0.58, 1)`
 *  - framer-motion `easeInOut` → `UnitBezier(0.42, 0, 0.58, 1)`
 *  - framer-motion `spring(stiffness 200, damping 20)` → 逐帧数值积分（`Anim.stepSpring`）
 *  - framer-motion `keyframes` + `repeat: Infinity` → 本文件内的循环关键帧插值器
 *  - 浏览器 `canvas.measureText` → `android.graphics.Paint.measureText`（测量与绘制共用同一支 Paint）
 *  - `Math.random()`（涟漪倍率）→ 行种子派生的确定性伪随机（保证重进画面不变）
 *
 * ## 与 Folia 的已知差异（均为 NASMusicTV 侧缺少输入所致）
 *  - `theme.animationIntensity` → `ctx.quality`：LOW ⇒ "calm"，MEDIUM/HIGH ⇒ "normal"。
 *    ⇒ "chaotic" 不可达：Folia 的 chaotic 分支（baseSpread 60 / baseRotate 30 / 随机
 *    marginRight / alignSelf / passed 不透明度 0.9）已按原样实现但不会被触发。
 *  - `lyricsFontScale` 与 `useOverlapFit` 的收缩系数不在 `RenderContext` 里 ⇒ `lineFontScale = 1`。
 *  - `classicTuning`（本仓无对应设置项）⇒ 取 Folia 的 `DEFAULT_CLASSIC_TUNING`：
 *    `enableWordRotation = true`、`breathingFloatMultiplier = 1`、`useLegacyLayout = false`、
 *    `wordSpacing = 0.7`、`avoidOverlap = false`。
 *  - `resolveLyricTextDirection`（RTL 检测）不在本仓 ⇒ 一律按 LTR 排版。
 *  - Folia 的 CJK 语义排版（`buildPostLyricLayoutUnits`）不在本仓 ⇒ `displayWords = line.words`。
 *  - `theme.wordColors` 不存在 ⇒ `resolveWordColor` 传「空」列表，回落色取 `ctx.palette.secondary`
 *    （Folia 的 `theme.accentColor`）；正文底色取 `ctx.palette.accent`（Folia 的 `theme.primaryColor`）。
 *
 * ## 零分配
 *  `draw()` 内不新建 Paint / Path / 数组 / 字符串；所有逐词动画状态在换行时一次性建好，
 *  逐帧只改字段值。换行本身会重建布局（Folia 的 `useMemo` 也是如此），每行一次。
 */
class GlowLineSubRenderer : LyricsSubRenderer {

    // ══════════════════════════════════════════════════════════════════
    // Folia 常量（逐字照抄，不做「近似替换」）
    // ══════════════════════════════════════════════════════════════════

    /** 移植 classic/Visualizer.tsx:182 `const rem = 16`（CSS 1rem = 16px） */
    private val remPx = 16f

    /** 移植 classic/Visualizer.tsx:183-186 `getPixelFontSize` 的三个系数 */
    private val fontMinRem = 2.25f
    private val fontVwPercent = 6f
    private val fontMaxRem = 4.5f

    /** 移植 classic/Visualizer.tsx:370 `max-w-6xl` = 72rem */
    private val maxLineWidthPx = 72f * remPx

    /** 移植 classic/Visualizer.tsx:353 `h-[70vh]` */
    private val containerHeightRatio = 0.7f

    /** 移植 classic/Visualizer.tsx:191-192 `baseSpread` / `baseRotate`（normal 档） */
    private val baseSpreadNormal = 20f
    private val baseRotateNormal = 5f

    /** 移植 classic/Visualizer.tsx:191-192（calm 档） */
    private val baseSpreadCalm = 0f
    private val baseRotateCalm = 0f

    /** 移植 classic/Visualizer.tsx:191-192（chaotic 档；当前映射不可达，仍按原样实现） */
    private val baseSpreadChaotic = 60f
    private val baseRotateChaotic = 30f

    /** 移植 classic/Visualizer.tsx:204-206 `random(offset)` 的乘数 */
    private val randomScale = 10000.0

    /** 移植 classic/Visualizer.tsx:266 `resolvedClassicTuning.wordSpacing`（DEFAULT = 0.7） */
    private val wordSpacing = 0.7

    /** 移植 classic/Visualizer.tsx:267 `gap = 0.05 * pxFontSize` */
    private val gapRatio = 0.05f

    /** 移植 classic/Visualizer.tsx:273 `minMargin`（非 chaotic 档 0.12 * pxFontSize） */
    private val minMarginRatio = 0.12f

    /** 移植 classic/Visualizer.tsx:248 非 chaotic 档的 `marginRight = '0.8rem'` */
    private val plainMarginRem = 0.8f

    /** 移植 classic/Visualizer.tsx:237 间奏档的 `marginRight = '3rem'` */
    private val interludeMarginRem = 3f

    /** 移植 classic/Visualizer.tsx:321-325 呼吸浮动的三档配置（distance, duration） */
    private val breathCalmDistance = 10f
    private val breathCalmDuration = 8.5
    private val breathNormalDistance = 14f
    private val breathNormalDuration = 7.0
    private val breathChaoticDistance = 18f
    private val breathChaoticDuration = 5.8

    /** 移植 classic/Visualizer.tsx:59-61 `breathingFloatMultiplier`（DEFAULT = 1，clamp 到 [0,2]） */
    private val breathingFloatMultiplier = 1f

    /** 移植 glowWordVariants.ts:16-23 waiting 的 transition duration */
    private val waitingDuration = 0.4

    /** 移植 glowWordVariants.ts:30-35 active 的 opacity duration */
    private val activeOpacityDuration = 0.1

    /** 移植 glowWordVariants.ts:43-48 passed 的 duration / rotate duration */
    private val passedDuration = 0.5
    private val passedRotateDuration = 5.0

    /** 移植 glowWordVariants.ts:38 passed 的不透明度（chaotic 档 0.9） */
    private val passedOpacity = 0.82f
    private val passedOpacityChaotic = 0.9f

    /** 移植 glowWordVariants.ts:57 waiting 的正文模糊 */
    private val waitingBlurPx = 10f

    // ⚠️ 辉光的半径 / 时间轴 / 峰值曲线常量已搬进共享的 `GlowWordGlow`
    //    （classic 与 partita 共用，与 Folia 的 `GlowWord.tsx:86-119` 一致）⇒ 此处不再保留副本。

    /** 移植 wordGlow.ts:29 `DROP_SHADOW_RADIUS_SCALE` */
    private val dropShadowRadiusScale = 0.4f

    /** 移植 wordGlow.ts:31 `DROP_SHADOW_INNER_ALPHA` */
    private val dropShadowInnerAlpha = 0.7f

    /** 移植 GlowWord.tsx:137-140 副歌涟漪的动画参数 */
    private val rippleDuration = 0.5
    private val rippleStartScale = 0.2f
    private val rippleStartOpacity = 0.8f
    private val rippleBaseScale = 1.5f
    private val rippleScaleRange = 2f

    /** 移植 GlowWord.tsx:135 `h-[150%]`：圆直径 = 行高 × 1.5 */
    private val rippleHeightRatio = 1.5f

    /** 移植 glowWordVariants.ts:25-33 `spring(stiffness 200, damping 20)`（mass = 1，见文件级常量） */

    /**
     * 移植 DEFAULT_CLASSIC_TUNING.avoidOverlap = false（types.ts:458-464）。
     * ⛔ 改成 true 即可启用 Folia 的「按实际伸出量排版」路径（`getGlowWordPairGap` /
     * `getGlowWordOverflow` + `rowGap` / `reachTop` / `reachBottom`）—— 该路径已完整实现。
     */
    private val avoidOverlap = false

    /** 移植 classic/Visualizer.tsx:250 `!resolvedClassicTuning.useLegacyLayout`（DEFAULT = false ⇒ 恒真） */
    private val useLegacyLayout = false

    // ══════════════════════════════════════════════════════════════════
    // 缓动（framer-motion → 三次贝塞尔）见文件级 `ease` / `UnitBezier`
    // ══════════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════════
    // 动画标量
    // ══════════════════════════════════════════════════════════════════

    /** 单个可动画标量：补间（tween）或弹簧（spring）。 */
    private class Anim {
        var value = 0f
        var from = 0f
        var to = 0f
        var startSec = 0.0
        var durationSec = 0.0
        var velocity = 0f
        var spring = false
        var easing = EASE_OUT
        var running = false

        fun jumpTo(v: Float) {
            value = v
            from = v
            to = v
            velocity = 0f
            running = false
            spring = false
        }

        fun setTo(target: Float, nowSec: Double, duration: Double, easingId: Int, asSpring: Boolean) {
            from = value
            to = target
            startSec = nowSec
            durationSec = duration
            easing = easingId
            spring = asSpring
            running = true
            if (!asSpring) velocity = 0f
        }

        fun step(nowSec: Double, dtSec: Double) {
            if (!running) return
            if (spring) {
                stepSpring(dtSec)
                return
            }
            if (durationSec <= 0.0) {
                value = to
                running = false
                return
            }
            var t = ((nowSec - startSec) / durationSec).toFloat()
            if (t >= 1f) {
                value = to
                running = false
                return
            }
            if (t < 0f) t = 0f
            value = from + (to - from) * ease(easing, t)
        }

        /** 移植 framer-motion 的 `spring(stiffness 200, damping 20)`：逐帧数值积分（不是 lerp） */
        private fun stepSpring(dtSec: Double) {
            var remaining = dtSec
            var guard = 0
            while (remaining > 0.0 && guard < 64) {
                val h = min(remaining, SPRING_MAX_STEP)
                remaining -= h
                val accel = -SPRING_STIFFNESS * (value - to) - SPRING_DAMPING * velocity
                velocity += (accel * h).toFloat()
                value += (velocity * h).toFloat()
                guard++
            }
            if (abs(value - to) < SPRING_REST_DELTA && abs(velocity) < SPRING_REST_SPEED) {
                value = to
                velocity = 0f
                running = false
            }
        }
    }

    /** 颜色补间（移植 glowWordVariants.ts:55-83 的 `color` 过渡）。 */
    private class ColorAnim {
        var value = 0
        var from = 0
        var to = 0
        var startSec = 0.0
        var durationSec = 0.0
        var easing = EASE_OUT
        var running = false

        fun jumpTo(v: Int) {
            value = v
            from = v
            to = v
            running = false
        }

        fun setTo(target: Int, nowSec: Double, duration: Double, easingId: Int) {
            from = value
            to = target
            startSec = nowSec
            durationSec = duration
            easing = easingId
            running = true
        }

        fun step(nowSec: Double) {
            if (!running) return
            if (durationSec <= 0.0) {
                value = to
                running = false
                return
            }
            var t = ((nowSec - startSec) / durationSec).toFloat()
            if (t >= 1f) {
                value = to
                running = false
                return
            }
            if (t < 0f) t = 0f
            value = lerpColor(from, to, ease(easing, t))
        }
    }

    private fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    // ══════════════════════════════════════════════════════════════════
    // 逐词 / 逐行状态
    // ══════════════════════════════════════════════════════════════════

    /**
     * 移植 classic/Visualizer.tsx:32-41 `WordLayoutConfig` + glowWordVariants.ts 的逐词动画状态。
     */
    private class WordState {
        // ── 静态布局 ──
        var word: LyricWord? = null
        var text = ""
        var configX = 0f
        var configY = 0f
        var configRotate = 0f
        var configScale = 1f
        var passedRotate = 0f
        var marginRight = 0f

        /** 移植 classic/Visualizer.tsx:297 `alignSelf`（"auto" / "flex-start" / "flex-end" / "center"） */
        var alignSelf = "auto"

        var width = 0f
        var activeColor = 0
        var baseColor = 0

        // ── 行内位置（流式排版结果）──
        var rowX = 0f
        var rowTop = 0f

        // ── 字形（glow 层）──
        var graphemeCount = 0
        var graphemeBase = 0

        /** 副歌涟漪倍率（移植 GlowWord.tsx:45 `1.5 + Math.random() * 2`） */
        var rippleScale = 1.5f

        // ── 动画 ──
        var status = GlowWordStatus.WAITING
        var activeStart = 0.0
        var passedAt = 0.0

        val opacityAnim = Anim()
        val scaleAnim = Anim()
        val xAnim = Anim()
        val yAnim = Anim()
        val rotateAnim = Anim()
        val colorAnim = ColorAnim()
        val blurAnim = Anim()
    }

    /**
     * 一整行的布局 + 容器动画。
     *
     * 移植 classic/Visualizer.tsx:43-52 `LineLayoutConfig` + :151-308 的排版结果。
     */
    private class LineLayout {
        var key = 0.0
        var canvasWidth = 0f
        var canvasHeight = 0f
        var safeArea = 0f
        var quality = VisualQuality.Default
        var isChaotic = false

        var words = emptyArray<WordState>()
        var rowCount = 0

        /** 移植 classic/Visualizer.tsx:172-173 */
        var justify = "center"
        var align = "center"

        /**
         * 移植 classic/Visualizer.tsx:174 `perspective`。
         * ⚠️ CSS `perspective` 是 3D 提示；这些词只做 2D 变换（translate / rotate / scale），
         * ⇒ 对 2D 投影没有任何视觉影响。这里保留数值以忠实记录 Folia 的布局配置。
         */
        var perspective = 1000f

        var rowGap = 0f
        var reachTop = 0f
        var reachBottom = 0f

        var pxFontSize = 0f
        var rowHeight = 0f

        /** 文字基线相对行顶的偏移（CSS `line-height` 的居中 + ascent） */
        var baselineInRow = 0f

        /** 内容区（70vh 容器去掉 padding） */
        var contentLeft = 0f
        var contentTop = 0f
        var contentRight = 0f
        var contentBottom = 0f
        var contentCx = 0f
        var contentCy = 0f

        /** 行块（所有行）在内容区里的左上角 */
        var blockLeft = 0f
        var blockTop = 0f
        var lineWidth = 0f

        /** 70vh 容器（外层）的中心，呼吸浮动绕它缩放 */
        var boxCx = 0f
        var boxCy = 0f

        var profile: GlowWordProfile? = null
        var isChorus = false

        // ── 容器动画（移植 glowWordTiming.ts:58-91） ──
        val opacityAnim = Anim()
        val scaleAnim = Anim()
        val blurAnim = Anim()

        // ── 逐字形数据（扁平存放） ──
        var graphemeText = emptyArray<String>()
        var graphemeX = FloatArray(0)
        var graphemeStart = DoubleArray(0)
        var graphemeEnd = DoubleArray(0)
    }

    // ══════════════════════════════════════════════════════════════════
    // Paint（成员字段 ⇒ 每帧零分配）
    // ══════════════════════════════════════════════════════════════════

    /** 正文笔 —— 测量与绘制共用同一支（保证量出来的宽度就是画出来的宽度） */
    private val textPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    /** 辉光外圈（移植 wordGlow.ts:57 `drop-shadow(0 0 outer * 0.4px color)`） */
    private val glowOuterPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    /** 辉光内圈（移植 wordGlow.ts:56 `drop-shadow(0 0 inner * 0.4px color @0.7)`） */
    private val glowInnerPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    /** 副歌涟漪（移植 GlowWord.tsx:135-136 `rounded-full border-1`） */
    private val ripplePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
    }

    // ══════════════════════════════════════════════════════════════════
    // 运行期状态
    // ══════════════════════════════════════════════════════════════════

    private var curLayout: LineLayout? = null
    private var prevLayout: LineLayout? = null

    private var lastFrameMs = -1L
    private var breathElapsed = 0.0
    private var frameDeltaSec = 0.0

    /** 呼吸浮动的关键帧（Folia 用 keyframe 数组 + repeat: Infinity + easeInOut） */
    private val breathYKeys = floatArrayOf(0f, -1f, 0f, 0.45f, 0f)
    private var breathScaleKeys = floatArrayOf(1f, 1.01f, 1f, 0.995f, 1f)

    private var breathDistance = 0f
    private var breathDuration = 7.0

    /** 当前帧的呼吸浮动位移与缩放（各绘制层共用） */
    private var breathY = 0f
    private var breathScale = 1f

    // ══════════════════════════════════════════════════════════════════
    // 生命周期
    // ══════════════════════════════════════════════════════════════════

    override fun onEnter(ctx: RenderContext, lyrics: LyricsStagePayload) {
        lastFrameMs = -1L
        breathElapsed = 0.0
        frameDeltaSec = 0.0
        curLayout = null
        prevLayout = null
        applyIntensity(ctx.quality)
    }

    override fun onExit() {
        curLayout = null
        prevLayout = null
    }

    /**
     * 移植 classic/Visualizer.tsx:155-159 —— `theme.animationIntensity` 的来源。
     * 本仓没有主题强度，只有画质档：LOW ⇒ "calm"，MEDIUM/HIGH ⇒ "normal"。
     */
    private fun applyIntensity(quality: VisualQuality) {
        val intensity = if (quality == VisualQuality.LOW) "calm" else "normal"
        when (intensity) {
            "calm" -> {
                breathDistance = breathCalmDistance
                breathDuration = breathCalmDuration
            }
            "chaotic" -> {
                breathDistance = breathChaoticDistance
                breathDuration = breathChaoticDuration
            }
            else -> {
                breathDistance = breathNormalDistance
                breathDuration = breathNormalDuration
            }
        }
        val m = breathingFloatMultiplier
        breathScaleKeys = floatArrayOf(1f, 1f + 0.01f * m, 1f, 1f - 0.005f * m, 1f)
    }

    // ══════════════════════════════════════════════════════════════════
    // 布局构建（移植 classic/Visualizer.tsx:151-308）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 移植 classic/Visualizer.tsx:204-207 / :221-225 的确定性伪随机：
     * `x = Math.sin(seed + i + offset) * 10000; return x - Math.floor(x)`
     */
    private fun randomAt(seed: Double, index: Int, offset: Int): Double {
        val x = sin(seed + index + offset) * randomScale
        return x - floor(x)
    }

    /** 移植 classic/Visualizer.tsx:181-188 `getPixelFontSize` */
    private fun computePxFontSize(viewportWidth: Float, fontScale: Float): Float {
        val minPx = fontMinRem * fontScale * remPx
        val valPx = (fontVwPercent * fontScale * viewportWidth) / 100f
        val maxPx = fontMaxRem * fontScale * remPx
        return max(minPx, min(valPx, maxPx))
    }

    private fun buildLayout(
        line: LyricLine,
        ctx: RenderContext,
    ): LineLayout {
        val layout = LineLayout()
        layout.key = line.startTime
        layout.canvasWidth = ctx.canvasSize.width
        layout.canvasHeight = ctx.canvasSize.height
        layout.safeArea = ctx.safeAreaPx
        layout.quality = ctx.quality
        layout.isChorus = line.isChorus

        val profile = GlowWordCore.resolveProfile(line)
        layout.profile = profile

        val seed = line.startTime
        val intensity = if (ctx.quality == VisualQuality.LOW) "calm" else "normal"
        val isChaotic = intensity == "chaotic"
        val isCalm = intensity == "calm"
        layout.isChaotic = isChaotic

        // ── 移植 classic/Visualizer.tsx:162-175 —— 行容器配置 ──
        val justifyOptions = if (isCalm) JUSTIFY_CALM else JUSTIFY_NORMAL
        val alignOptions = if (isCalm) ALIGN_CALM else ALIGN_NORMAL
        val isInterlude = line.fullText == "......"

        layout.justify = if (isInterlude) {
            "center"
        } else {
            justifyOptions[floor(seed % justifyOptions.size).toInt().coerceIn(0, justifyOptions.size - 1)]
        }
        layout.align = alignOptions[
            floor((seed * 2) % alignOptions.size).toInt().coerceIn(0, alignOptions.size - 1)
        ]
        layout.perspective = if (isChaotic) 500f + (seed % 500).toFloat() else 1000f

        // ── 移植 classic/Visualizer.tsx:181-188 —— 字号 ──
        // ⚠️ `lineFontScale = lyricsFontScale * overlapFit`；本仓 `RenderContext` 没有这两个输入
        //    ⇒ 取 1（见文件头「与 Folia 的已知差异」）。
        val lineFontScale = 1f
        val pxFontSize = computePxFontSize(ctx.canvasSize.width, lineFontScale)
        layout.pxFontSize = pxFontSize
        textPaint.textSize = pxFontSize

        // ── 移植 classic/Visualizer.tsx:189 —— 逐词测量宽度 ──
        // （Folia 用 canvas 2D `measureText`；Android 用同一支 Paint 的 `measureText`）
        val words = line.words
        val wordWidths = FloatArray(words.size)
        for (i in words.indices) {
            wordWidths[i] = textPaint.measureText(words[i].text)
        }

        // ── 移植 classic/Visualizer.tsx:191-192 —— baseSpread / baseRotate ──
        val baseSpread = when {
            isChaotic -> baseSpreadChaotic
            isCalm -> baseSpreadCalm
            else -> baseSpreadNormal
        }
        val baseRotate = when {
            isChaotic -> baseRotateChaotic
            isCalm -> baseRotateCalm
            else -> baseRotateNormal
        }

        // ── 移植 classic/Visualizer.tsx:203-217 `placementOf(i)` ──
        val placements = arrayOfNulls<GlowWordPlacement>(words.size)
        for (i in words.indices) {
            placements[i] = placementOf(seed, i, wordWidths[i], isChaotic, baseSpread, baseRotate)
        }

        // ── 移植 classic/Visualizer.tsx:218-300 —— 逐词配置 ──
        val baseColor = ctx.palette.accent.toArgb()
        val activeFallback = ctx.palette.secondary.toArgb()
        val fallbackHex = argbToHex(activeFallback)

        var rowReachTop = 0f
        var rowReachBottom = 0f

        val states = Array(words.size) { WordState() }
        for (i in words.indices) {
            val w = words[i]
            val st = states[i]
            st.word = w
            st.text = w.text
            st.width = wordWidths[i]
            st.baseColor = baseColor
            // 移植 classic/Visualizer.tsx:384 `resolveWordColor(word.text, theme.wordColors, theme.accentColor)`
            // ⚠️ 本仓没有主题逐词配色表 ⇒ 传**空**列表 ⇒ 恒取回落到 `ctx.palette.secondary`。
            val resolved = WordColoring.resolveWordColor(w.text, emptyList(), fallbackHex)
            st.activeColor = hexToArgb(resolved, activeFallback)

            // 移植 GlowWord.tsx:45 `rippleScale = 1.5 + Math.random() * 2`
            // ⚠️ `Math.random()` 不可复现 ⇒ 用行种子派生的确定性伪随机（重进画面不变）
            st.rippleScale = rippleBaseScale + randomAt(seed, i, 9).toFloat() * rippleScaleRange

            if (isInterlude) {
                // 移植 classic/Visualizer.tsx:230-241 —— 间奏分支
                st.configX = 0f
                st.configY = ((randomAt(seed, i, 2) - 0.5) * 15.0).toFloat()
                st.configRotate = 0f
                st.configScale = 1.5f
                st.marginRight = interludeMarginRem * remPx
                st.alignSelf = "center"
                st.passedRotate = 0f
                continue
            }

            val placement = placements[i]!!
            st.configX = placement.offsetX
            st.configY = placement.offsetY
            st.configRotate = placement.pose.rotate
            st.configScale = placement.pose.scale
            st.passedRotate = placement.pose.passedRotate

            // 移植 classic/Visualizer.tsx:248-288 —— marginRight 计算
            var marginRightPx = if (isChaotic) {
                (randomAt(seed, i, 5) * 1.5 * remPx).toFloat()
            } else {
                plainMarginRem * remPx
            }

            if (!useLegacyLayout) {
                val wI = wordWidths[i]
                val sI = placement.pose.scale * GLOW_WORD_ACTIVE_SCALE
                var wNext = 0f
                var sNext = 1f
                var xNext = 0f
                if (i + 1 < words.size) {
                    val next = placements[i + 1]!!
                    sNext = next.pose.scale * GLOW_WORD_ACTIVE_SCALE
                    xNext = next.offsetX
                    wNext = next.width
                }
                val spacingMultiplier = wordSpacing
                val gap = gapRatio * pxFontSize
                val halfOverflowI = wI * (sI - 1f) / 2f
                val halfOverflowNext = wNext * (sNext - 1f) / 2f
                // ⚠️ Folia 的 `rtl` 分支保留在源码里；本仓没有方向检测 ⇒ 恒 LTR
                val xOffsetDiff = placement.offsetX - xNext

                val calculatedMargin =
                    (halfOverflowI + halfOverflowNext + xOffsetDiff + gap) * spacingMultiplier
                val minMargin =
                    (if (isChaotic) 0.08f * pxFontSize else minMarginRatio * pxFontSize) *
                    spacingMultiplier
                var finalMargin = max(minMargin, calculatedMargin)

                if (avoidOverlap) {
                    if (i + 1 < words.size) {
                        val pairGap = GlowWordOverlap.getPairGap(
                            placement, placements[i + 1]!!, pxFontSize, "ltr",
                        )
                        finalMargin = max(finalMargin, (pairGap + gap).toDouble())
                    }
                    val overflow = GlowWordOverlap.getOverflow(
                        placement.width, pxFontSize, placement.pose,
                        placement.offsetX, placement.offsetY,
                    )
                    rowReachTop = max(rowReachTop, overflow.top)
                    rowReachBottom = max(rowReachBottom, overflow.bottom)
                }

                // 移植 classic/Visualizer.tsx:287 `${finalMargin.toFixed(1)}px`
                marginRightPx = (round(finalMargin * 10.0) / 10.0).toFloat()
            }

            st.marginRight = marginRightPx

            // 移植 classic/Visualizer.tsx:297 —— alignSelf
            st.alignSelf = if (isChaotic && randomAt(seed, i, 6) > 0.7) {
                if (randomAt(seed, i, 7) > 0.5) "flex-start" else "flex-end"
            } else {
                "auto"
            }
        }

        if (avoidOverlap) {
            // 移植 classic/Visualizer.tsx:302-306
            layout.rowGap = ceil(rowReachTop + rowReachBottom).toFloat()
            layout.reachTop = ceil(rowReachTop).toFloat()
            layout.reachBottom = ceil(rowReachBottom).toFloat()
        }

        // ── 逐字形数据（移植 GlowWord.tsx:48 `buildWordGraphemeTimings`） ──
        val graphemeTextList = ArrayList<String>(words.size * 2)
        val graphemeXList = ArrayList<Float>(words.size * 2)
        val graphemeStartList = ArrayList<Double>(words.size * 2)
        val graphemeEndList = ArrayList<Double>(words.size * 2)
        for (i in words.indices) {
            val st = states[i]
            val timings = GraphemeTimingBuilder.buildFor(words[i], i)
            st.graphemeBase = graphemeTextList.size
            st.graphemeCount = timings.size
            var acc = 0f
            for (t in timings) {
                graphemeTextList.add(t.char)
                graphemeXList.add(acc)
                graphemeStartList.add(t.startTime)
                graphemeEndList.add(t.endTime)
                acc += textPaint.measureText(t.char)
            }
        }
        layout.graphemeText = graphemeTextList.toTypedArray()
        layout.graphemeX = graphemeXList.toFloatArray()
        layout.graphemeStart = graphemeStartList.toDoubleArray()
        layout.graphemeEnd = graphemeEndList.toDoubleArray()

        layout.words = states

        // ── 字体度量（一次性） ──
        val fm = textPaint.fontMetrics
        val ascent = -fm.top
        val descent = fm.bottom
        val contentHeight = ascent + descent
        // 移植 GlowWord.tsx:81 `lineHeight: GLOW_WORD_LINE_HEIGHT`（1.22）
        layout.rowHeight = pxFontSize * GLOW_WORD_LINE_HEIGHT
        // CSS 行盒：额外行距上下各半
        layout.baselineInRow = (layout.rowHeight - contentHeight) / 2f + ascent

        layoutFlow(layout, ctx)

        // ── 容器入场动画（移植 glowWordTiming.ts:58-91，经 GlowWordCore.lineContainerMotion） ──
        val motion = GlowWordCore.lineContainerMotion(profile)
        layout.opacityAnim.jumpTo(motion.initialOpacity)
        layout.scaleAnim.jumpTo(motion.initialScale)
        layout.blurAnim.jumpTo(motion.initialBlurPx)

        // 初始状态：waiting（移植 glowWordVariants.ts:16-23 的 waiting 变体值）
        for (st in states) {
            initWordToWaiting(st)
        }

        return layout
    }

    /** 移植 classic/Visualizer.tsx:203-217 `placementOf(i)` */
    private fun placementOf(
        seed: Double,
        index: Int,
        width: Float,
        isChaotic: Boolean,
        baseSpread: Float,
        baseRotate: Float,
    ): GlowWordPlacement {
        val scale = if (isChaotic) {
            (0.8 + randomAt(seed, index, 4) * 0.6).toFloat()
        } else {
            (1.1 + randomAt(seed, index, 4) * 0.2).toFloat()
        }
        val rotate = ((randomAt(seed, index, 3) - 0.5) * baseRotate * 2.0).toFloat()
        val passedRotate = ((randomAt(seed, index, 8) - 0.5) * 45.0).toFloat()
        val offsetX = ((randomAt(seed, index, 1) - 0.5) * baseSpread * 2.0).toFloat()
        val offsetY = ((randomAt(seed, index, 2) - 0.5) * baseSpread * 2.0).toFloat()
        return GlowWordPlacement(
            width = width,
            pose = GlowWordPose(scale = scale, rotate = rotate, passedRotate = passedRotate),
            offsetX = offsetX,
            offsetY = offsetY,
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 流式排版（CSS flex-wrap + justify-content + align-items 的等价物）
    // ══════════════════════════════════════════════════════════════════

    private fun layoutFlow(layout: LineLayout, ctx: RenderContext) {
        val w = ctx.canvasSize.width
        val h = ctx.canvasSize.height
        val padding = ctx.safeAreaPx

        // 70vh 容器垂直居中 ⇒ 屏幕中间 70%（移植 classic/Visualizer.tsx:353 `h-[70vh]`）
        val boxTop = h * (1f - containerHeightRatio) / 2f
        val boxBottom = h - boxTop
        layout.boxCx = w / 2f
        layout.boxCy = (boxTop + boxBottom) / 2f

        layout.contentLeft = padding
        layout.contentRight = w - padding
        layout.contentTop = boxTop + padding
        layout.contentBottom = boxBottom - padding
        layout.contentCx = (layout.contentLeft + layout.contentRight) / 2f
        layout.contentCy = (layout.contentTop + layout.contentBottom) / 2f

        // 移植 classic/Visualizer.tsx:370 `w-full max-w-6xl`
        val contentWidth = layout.contentRight - layout.contentLeft
        val lineWidth = min(contentWidth, maxLineWidthPx)
        layout.lineWidth = lineWidth
        // 外层 `flex justify-center` ⇒ 行块水平居中
        layout.blockLeft = layout.contentLeft + (contentWidth - lineWidth) / 2f

        val words = layout.words
        if (words.isEmpty()) {
            layout.rowCount = 0
            layout.blockTop = layout.contentTop
            return
        }

        // 逐词流入行；行宽超过可用宽度则换行（CSS `flex-wrap: wrap`）
        val rowWidths = FloatArray(words.size)
        val rowStarts = IntArray(words.size)
        val rowOf = IntArray(words.size)
        var row = 0
        var x = 0f
        var rowWidth = 0f
        var rowStart = 0
        for (i in words.indices) {
            val st = words[i]
            val itemOuter = st.width + st.marginRight
            if (x > 0f && x + itemOuter > lineWidth) {
                rowWidths[row] = rowWidth
                rowStarts[row] = rowStart
                row++
                rowStart = i
                x = 0f
                rowWidth = 0f
            }
            st.rowX = x
            rowOf[i] = row
            rowWidth += itemOuter
            x += itemOuter
        }
        rowWidths[row] = rowWidth
        rowStarts[row] = rowStart
        layout.rowCount = row + 1

        // 行块高度（`rowGap` 只在 avoidOverlap 打开时有值）
        val blockHeight = layout.rowCount * layout.rowHeight +
            (layout.rowCount - 1) * layout.rowGap

        // 移植 classic/Visualizer.tsx:377-378 `paddingTop: reachTop` / `paddingBottom: reachBottom`
        val blockTopWithReach = layout.contentTop + layout.reachTop
        val blockBottomWithReach = layout.contentBottom - layout.reachBottom

        // 纵向摆放：按 `alignItems`（任务约定：把行块放到 70vh 内容区的 start / center / end）
        // ⚠️ CSS 里 `align-items` 是**行内**的交叉轴对齐（所有词等高 ⇒ 无视觉差异），
        //    真正把行块居中的是 `content-center`；这里按任务约定把它作用在行块上。
        layout.blockTop = when (layout.align) {
            "start" -> blockTopWithReach
            "end" -> blockBottomWithReach - blockHeight
            else -> blockTopWithReach + (blockBottomWithReach - blockTopWithReach - blockHeight) / 2f
        }

        // 每行的横向摆放（justify-content）
        for (r in 0 until layout.rowCount) {
            val start = rowStarts[r]
            var count = 0
            var k = start
            while (k < words.size && rowOf[k] == r) {
                count++
                k++
            }
            applyJustify(layout, start, count, rowWidths[r])
        }

        // 每行的顶部
        for (r in 0 until layout.rowCount) {
            val rowTop = layout.blockTop + r * (layout.rowHeight + layout.rowGap)
            val start = rowStarts[r]
            var k = start
            while (k < words.size && rowOf[k] == r) {
                words[k].rowTop = rowTop
                k++
            }
        }
    }

    /** 把一行内的词按 `justify-content` 横向摆放（CSS flexbox 的 justify-content 等价物） */
    private fun applyJustify(
        layout: LineLayout,
        rowStart: Int,
        count: Int,
        rowWidth: Float,
    ) {
        if (count <= 0) return
        val free = layout.lineWidth - rowWidth
        when (layout.justify) {
            "center" -> {
                val off = free / 2f
                for (k in 0 until count) layout.words[rowStart + k].rowX += off
            }
            "end" -> {
                for (k in 0 until count) layout.words[rowStart + k].rowX += free
            }
            "between" -> {
                if (count > 1) {
                    val step = free / (count - 1)
                    for (k in 0 until count) layout.words[rowStart + k].rowX += step * k
                }
            }
            "around" -> {
                val step = free / count
                val edge = step / 2f
                for (k in 0 until count) layout.words[rowStart + k].rowX += edge + step * k
            }
            else -> {
                // "start"：不偏移
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 逐词动画（移植 glowWordVariants.ts:12-83）
    // ══════════════════════════════════════════════════════════════════

    /** 移植 glowWordVariants.ts:16-23 waiting 变体的目标值（初次挂载时直接置位） */
    private fun initWordToWaiting(st: WordState) {
        st.status = GlowWordStatus.WAITING
        st.opacityAnim.jumpTo(0f)
        st.scaleAnim.jumpTo(0.5f)
        st.xAnim.jumpTo(st.configX + sin(st.configY.toDouble()).toFloat() * 100f)
        st.yAnim.jumpTo(st.configY + cos(st.configX.toDouble()).toFloat() * 50f)
        st.rotateAnim.jumpTo(st.configRotate + 20f)
        st.colorAnim.jumpTo(st.baseColor)
        st.blurAnim.jumpTo(waitingBlurPx)
    }

    private fun onStatusChange(
        st: WordState,
        status: GlowWordStatus,
        nowSec: Double,
        profile: GlowWordProfile,
        isChaotic: Boolean,
    ) {
        st.status = status
        when (status) {
            GlowWordStatus.WAITING -> {
                // 移植 glowWordVariants.ts:16-23
                st.opacityAnim.setTo(0f, nowSec, waitingDuration, EASE_OUT, false)
                st.scaleAnim.setTo(0.5f, nowSec, waitingDuration, EASE_OUT, false)
                st.xAnim.setTo(
                    st.configX + sin(st.configY.toDouble()).toFloat() * 100f,
                    nowSec, waitingDuration, EASE_OUT, false,
                )
                st.yAnim.setTo(
                    st.configY + cos(st.configX.toDouble()).toFloat() * 50f,
                    nowSec, waitingDuration, EASE_OUT, false,
                )
                st.rotateAnim.setTo(st.configRotate + 20f, nowSec, waitingDuration, EASE_OUT, false)
                // 移植 glowWordVariants.ts:56-60（正文：底色 + blur(10px)）
                st.colorAnim.setTo(st.baseColor, nowSec, waitingDuration, EASE_OUT)
                st.blurAnim.setTo(waitingBlurPx, nowSec, waitingDuration, EASE_OUT, false)
            }

            GlowWordStatus.ACTIVE -> {
                // 移植 glowWordVariants.ts:24-35
                val targetScale =
                    if (st.configScale.isNaN()) 1.5f else st.configScale * GLOW_WORD_ACTIVE_SCALE
                st.opacityAnim.setTo(1f, nowSec, activeOpacityDuration, EASE_OUT, false)
                st.scaleAnim.setTo(targetScale, nowSec, 0.0, EASE_OUT, true)
                st.xAnim.setTo(st.configX, nowSec, 0.0, EASE_OUT, true)
                st.yAnim.setTo(st.configY, nowSec, 0.0, EASE_OUT, true)
                st.rotateAnim.setTo(st.configRotate, nowSec, 0.0, EASE_OUT, true)
                // 移植 glowWordVariants.ts:61-71（正文：高亮色，color 过渡 = 词显示时长）
                val word = st.word
                val displayDuration = if (word != null) {
                    GlowWordCore.displayDuration(word, profile)
                } else {
                    0.2
                }
                st.colorAnim.setTo(st.activeColor, nowSec, displayDuration, EASE_LINEAR)
                st.blurAnim.setTo(
                    0f, nowSec, activeFilterDuration(profile.wordRevealMode), EASE_OUT, false,
                )
                st.activeStart = nowSec
            }

            GlowWordStatus.PASSED -> {
                // 移植 glowWordVariants.ts:37-50
                val targetOpacity = if (isChaotic) passedOpacityChaotic else passedOpacity
                val targetScale = if (st.configScale == 0f) 1f else st.configScale
                st.opacityAnim.setTo(targetOpacity, nowSec, passedDuration, EASE_OUT, false)
                st.scaleAnim.setTo(targetScale, nowSec, passedDuration, EASE_OUT, false)
                st.xAnim.setTo(st.configX, nowSec, passedDuration, EASE_OUT, false)
                st.yAnim.setTo(st.configY, nowSec, passedDuration, EASE_OUT, false)
                // rotate 单独 5s 线性（移植 glowWordVariants.ts:44-47）
                st.rotateAnim.setTo(
                    st.configRotate + st.passedRotate,
                    nowSec, passedRotateDuration, EASE_LINEAR, false,
                )
                // 移植 glowWordVariants.ts:72-82（正文：回到底色 + blur(0)）
                st.colorAnim.setTo(
                    st.baseColor, nowSec, passedColorDuration(profile.wordRevealMode), EASE_IN_OUT,
                )
                st.blurAnim.setTo(
                    0f, nowSec, passedFilterDuration(profile.wordRevealMode), EASE_OUT, false,
                )
                st.passedAt = nowSec
            }
        }
    }

    /** 移植 glowWordVariants.ts:66 —— active 档的 filter 过渡时长 */
    private fun activeFilterDuration(wordRevealMode: String): Double = when (wordRevealMode) {
        "instant" -> 0.08
        "fast" -> 0.12
        else -> 0.2
    }

    /** 移植 glowWordVariants.ts:76 —— passed 档的 color 过渡时长 */
    private fun passedColorDuration(wordRevealMode: String): Double = when (wordRevealMode) {
        "instant" -> 0.12
        "fast" -> 0.24
        else -> 0.8
    }

    /** 移植 glowWordVariants.ts:77 —— passed 档的 filter 过渡时长 */
    private fun passedFilterDuration(wordRevealMode: String): Double = when (wordRevealMode) {
        "instant" -> 0.12
        "fast" -> 0.2
        else -> 0.5
    }

    // ══════════════════════════════════════════════════════════════════
    // 逐字形辉光（移植 wordGlow.ts:94-167）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 一个字形在 [nowSec]（播放时间）时刻的辉光峰值（0..1）。
     *
     * 移植 wordGlow.ts:100-154：
     *  - instant 档：duration = min(displayDuration, 0.12)，times [0, 0.35, 1]，peaks [0, 1, 0]
     *  - fast 档：duration = clamp(displayDuration, 0.12, 0.2)，times [0, 0.4, 1]，peaks [0, 1, 0]
     *  - normal 档多字形：duration = charDuration × 6，times [0, 0.3, 1]，peaks [0, 1, 0]，
     *    delay = charStartTime − wordStartTime
     *  - normal 档单字形：duration = displayDuration，times [0, 0.9, 1]，peaks [0, 1, 1]
     * passed 档：把离场瞬间的峰值按 easeOut 淡出（移植 wordGlow.ts:156-167）
     */
    private fun glowPeakForGrapheme(
        layout: LineLayout,
        st: WordState,
        graphemeIndex: Int,
        nowSec: Double,
    ): Float {
        // ⚠️ 辉光数学在**共享**的 `GlowWordGlow` 里（`classic` 与 `partita` 共用，
        //    与 Folia 的 `GlowWord.tsx:86-119` 一致）⇒ 这里只做「本布局 → 共享参数」的搬运。
        val profile = layout.profile ?: return 0f
        val word = st.word ?: return 0f
        return GlowWordGlow.peakForGrapheme(
            status = st.status,
            wordRevealMode = profile.wordRevealMode,
            displayDuration = GlowWordCore.displayDuration(word, profile),
            graphemeCount = st.graphemeCount,
            graphemeStart = layout.graphemeStart[graphemeIndex],
            graphemeEnd = layout.graphemeEnd[graphemeIndex],
            wordStartTime = word.startTime,
            activeStart = st.activeStart,
            passedAt = st.passedAt,
            nowSec = nowSec,
        )
    }

    // ⚠️ 原先这里的 `glowRamp` / `keyframeRamp`（移植 wordGlow.ts:100-154）
    //    已搬进共享的 `GlowWordGlow`（classic 与 partita 共用，与 Folia 一致）⇒ 此处不再保留副本。

    // ══════════════════════════════════════════════════════════════════
    // 绘制
    // ══════════════════════════════════════════════════════════════════

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext, lyrics: LyricsStagePayload) {
        // ── 呼吸时钟（移植 classic/Visualizer.tsx:314-341） ──
        if (lastFrameMs < 0L) {
            lastFrameMs = frame.timeMs
        } else {
            val dt = (frame.timeMs - lastFrameMs) / 1000.0
            lastFrameMs = frame.timeMs
            if (dt > 0.0) breathElapsed += dt
            frameDeltaSec = if (dt > 0.0) dt else 0.0
        }
        advanceBreath()

        val nowSec = lyrics.timeSec
        val line = lyrics.current

        if (line != null) {
            val layout = curLayout
            val needRebuild = layout == null ||
                layout.key != line.startTime ||
                layout.canvasWidth != ctx.canvasSize.width ||
                layout.canvasHeight != ctx.canvasSize.height ||
                layout.safeArea != ctx.safeAreaPx ||
                layout.quality != ctx.quality
            if (needRebuild) {
                // 换行：旧行开始离场（移植 AnimatePresence 的 exit），新行入场
                if (layout != null) {
                    prevLayout = layout
                    startExit(layout, nowSec)
                }
                applyIntensity(ctx.quality)
                val fresh = buildLayout(line, ctx)
                curLayout = fresh
                startEnter(fresh, nowSec)
            }
        } else {
            curLayout = null
        }

        drawIntoCanvas { canvas ->
            renderFrame(canvas.nativeCanvas, nowSec)
        }
    }

    /** 移植 classic/Visualizer.tsx:330-340 —— 5 帧循环关键帧（easeInOut，repeat: Infinity） */
    private fun advanceBreath() {
        if (breathDuration <= 0.0) {
            breathY = 0f
            breathScale = 1f
            return
        }
        var phase = (breathElapsed % breathDuration) / breathDuration
        if (phase < 0.0) phase = 0.0
        val scaled = (phase * 5.0).toFloat()
        var index = scaled.toInt()
        if (index > 4) index = 4
        val next = if (index + 1 > 4) 0 else index + 1
        val local = (scaled - index).coerceIn(0f, 1f)
        val e = ease(EASE_IN_OUT, local)
        val yKeys = breathYKeys
        val y = (yKeys[index] + (yKeys[next] - yKeys[index]) * e) * breathDistance *
            breathingFloatMultiplier
        val sKeys = breathScaleKeys
        val s = sKeys[index] + (sKeys[next] - sKeys[index]) * e
        breathY = y
        breathScale = s
    }

    /** 移植 glowWordTiming.ts:58-91 —— 行的入场动画（initial → animate） */
    private fun startEnter(layout: LineLayout, nowSec: Double) {
        val motion = GlowWordCore.lineContainerMotion(layout.profile)
        // ⚠️ Folia 的 "normal" 档没有显式 transition ⇒ framer-motion 默认 0.3s 补间。
        //    移植的 `enterDurationSec` 对该档是 0.0 ⇒ 这里读作「无显式时长」并回落 0.3s。
        val duration = if (motion.enterDurationSec > 0.0) motion.enterDurationSec else 0.3
        layout.opacityAnim.setTo(1f, nowSec, duration, EASE_OUT, false)
        layout.scaleAnim.setTo(1f, nowSec, duration, EASE_OUT, false)
        layout.blurAnim.setTo(0f, nowSec, duration, EASE_OUT, false)
    }

    /** 移植 glowWordTiming.ts:58-91 —— 行的离场动画（animate → exit） */
    private fun startExit(layout: LineLayout, nowSec: Double) {
        val motion = GlowWordCore.lineContainerMotion(layout.profile)
        layout.opacityAnim.setTo(motion.exitOpacity, nowSec, motion.exitDurationSec, EASE_OUT, false)
        layout.scaleAnim.setTo(motion.exitScale, nowSec, motion.exitDurationSec, EASE_OUT, false)
        layout.blurAnim.setTo(motion.exitBlurPx, nowSec, motion.exitDurationSec, EASE_OUT, false)
    }

    /** 一帧的完整绘制 */
    private fun renderFrame(canvas: Canvas, nowSec: Double) {
        val dt = frameDeltaSec

        // 离场行（AnimatePresence：旧行先画 ⇒ 新行压在上面）
        val old = prevLayout
        if (old != null) {
            advanceLine(old, nowSec, dt)
            drawLine(canvas, old, nowSec)
            if (!old.opacityAnim.running && old.opacityAnim.value <= 0.004f) {
                prevLayout = null
            }
        }

        val layout = curLayout ?: return
        advanceLine(layout, nowSec, dt)
        drawLine(canvas, layout, nowSec)
    }

    /** 推进一行的容器动画 + 逐词动画 */
    private fun advanceLine(layout: LineLayout, nowSec: Double, dtSec: Double) {
        layout.opacityAnim.step(nowSec, dtSec)
        layout.scaleAnim.step(nowSec, dtSec)
        layout.blurAnim.step(nowSec, dtSec)

        val profile = layout.profile ?: return
        val isChaotic = layout.isChaotic
        val words = layout.words
        for (i in words.indices) {
            val st = words[i]
            val word = st.word ?: continue
            val status = GlowWordCore.statusOf(word, nowSec, profile)
            if (status != st.status) {
                onStatusChange(st, status, nowSec, profile, isChaotic)
            }
            st.opacityAnim.step(nowSec, dtSec)
            st.scaleAnim.step(nowSec, dtSec)
            st.xAnim.step(nowSec, dtSec)
            st.yAnim.step(nowSec, dtSec)
            st.rotateAnim.step(nowSec, dtSec)
            st.colorAnim.step(nowSec)
            st.blurAnim.step(nowSec, dtSec)
        }
    }

    /**
     * 画一行。
     *
     * Folia 的层序（DOM 顺序 + z-index）：
     *   1. 辉光层（逐字形，绝对定位，无 z-index）
     *   2. 副歌涟漪（`z-0`）
     *   3. 正文层（`relative z-10`）
     * ⇒ 辉光在最底、正文在最上。
     */
    private fun drawLine(canvas: Canvas, layout: LineLayout, nowSec: Double) {
        val lineOpacity = layout.opacityAnim.value
        if (lineOpacity <= 0.004f) return
        val lineScale = layout.scaleAnim.value
        val lineBlur = layout.blurAnim.value

        canvas.save()
        // 外层 70vh 容器：呼吸浮动（位移 + 绕容器中心的缩放）
        canvas.translate(0f, breathY)
        canvas.scale(breathScale, breathScale, layout.boxCx, layout.boxCy)
        // 行容器：入场 / 离场的缩放（绕内容区中心）
        canvas.scale(lineScale, lineScale, layout.contentCx, layout.contentCy)

        // ── 1. 辉光层 ──
        drawGlowPass(canvas, layout, nowSec, lineOpacity)

        // ── 2. 副歌涟漪 ──
        drawRipplePass(canvas, layout, nowSec, lineOpacity)

        // ── 3. 正文层 ──
        drawBodyPass(canvas, layout, lineOpacity, lineBlur)

        canvas.restore()
    }

    /** 移植 wordGlow.ts:94-167 —— 逐字形辉光层 */
    private fun drawGlowPass(canvas: Canvas, layout: LineLayout, nowSec: Double, lineOpacity: Float) {
        val radii = glowRadiiFor(layout)
        val words = layout.words
        for (i in words.indices) {
            val st = words[i]
            if (st.status == GlowWordStatus.WAITING) continue

            val scale = st.scaleAnim.value
            val rotate = st.rotateAnim.value
            val cx = layout.blockLeft + st.rowX + st.width * 0.5f + st.xAnim.value
            val cy = st.rowTop + layout.rowHeight * 0.5f + st.yAnim.value
            val baselineRel = layout.baselineInRow - layout.rowHeight * 0.5f

            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(rotate)
            canvas.scale(scale, scale)

            for (g in 0 until st.graphemeCount) {
                val gi = st.graphemeBase + g
                val peak = glowPeakForGrapheme(layout, st, gi, nowSec)
                if (peak <= 0.003f) continue
                val alpha = (peak * lineOpacity * 255f).toInt().coerceIn(0, 255)
                val gx = -st.width * 0.5f + layout.graphemeX[gi]
                // 外圈（移植 wordGlow.ts:57 `drop-shadow(0 0 outer * 0.4 * peak px color)`）
                glowOuterPaint.color = withAlpha(st.activeColor, alpha)
                glowOuterPaint.setShadowLayer(
                    radii.second * dropShadowRadiusScale * peak, 0f, 0f,
                    withAlpha(st.activeColor, alpha),
                )
                canvas.drawText(layout.graphemeText[gi], gx, baselineRel, glowOuterPaint)
                // 内圈（文字本体透明 ⇒ 只留光晕；移植 wordGlow.ts:56 的 @0.7 透明度）
                glowInnerPaint.color = withAlpha(st.activeColor, 0)
                glowInnerPaint.setShadowLayer(
                    radii.first * dropShadowRadiusScale * peak, 0f, 0f,
                    withAlpha(st.activeColor, (alpha * dropShadowInnerAlpha).toInt().coerceIn(0, 255)),
                )
                canvas.drawText(layout.graphemeText[gi], gx, baselineRel, glowInnerPaint)
            }

            canvas.restore()
        }
    }

    /** 移植 GlowWord.tsx:130-143 —— 副歌涟漪 */
    private fun drawRipplePass(canvas: Canvas, layout: LineLayout, nowSec: Double, lineOpacity: Float) {
        if (!layout.isChorus) return
        val words = layout.words
        for (i in words.indices) {
            val st = words[i]
            if (st.status != GlowWordStatus.ACTIVE) continue
            val elapsed = nowSec - st.activeStart
            if (elapsed < 0.0) continue
            val t = (elapsed / rippleDuration).toFloat()
            if (t >= 1f) continue
            val e = ease(EASE_OUT, t)
            val s = rippleStartScale + (st.rippleScale - rippleStartScale) * e
            val alpha = rippleStartOpacity * (1f - e) * lineOpacity
            if (alpha <= 0.004f) continue

            val scale = st.scaleAnim.value
            val rotate = st.rotateAnim.value
            val cx = layout.blockLeft + st.rowX + st.width * 0.5f + st.xAnim.value
            val cy = st.rowTop + layout.rowHeight * 0.5f + st.yAnim.value
            // 移植 GlowWord.tsx:135 `h-[150%] aspect-square` ⇒ 半径 = 行高 × 0.75
            val baseRadius = layout.rowHeight * rippleHeightRatio * 0.5f

            ripplePaint.style = Paint.Style.STROKE
            ripplePaint.strokeWidth = 1f * s
            ripplePaint.color = withAlpha(st.activeColor, (alpha * 255f).toInt().coerceIn(0, 255))

            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(rotate)
            canvas.scale(scale, scale)
            // ⚠️ Folia 的 `filter: blur(1px)`：HW 加速下 MaskFilter 会让整条绘制被丢弃
            //    ⇒ 用抗锯齿 1px 描边近似（见文件头的替换记录）。
            canvas.drawCircle(0f, 0f, baseRadius * s, ripplePaint)
            canvas.restore()
        }
    }

    /** 移植 glowWordVariants.ts:55-83 —— 正文层（颜色 + 模糊） */
    private fun drawBodyPass(
        canvas: Canvas,
        layout: LineLayout,
        lineOpacity: Float,
        lineBlur: Float,
    ) {
        val words = layout.words
        for (i in words.indices) {
            val st = words[i]
            val wordOpacity = st.opacityAnim.value * lineOpacity
            if (wordOpacity <= 0.004f) continue

            val color = st.colorAnim.value
            val alpha = (wordOpacity * 255f).toInt().coerceIn(0, 255)
            textPaint.color = withAlpha(color, alpha)
            // CSS `filter: blur(Npx)` ⇒ Android 用同色阴影层近似（HW 加速下 MaskFilter 不可用）
            val blur = st.blurAnim.value + lineBlur
            if (blur > 0.05f) {
                textPaint.setShadowLayer(blur, 0f, 0f, withAlpha(color, alpha))
            } else {
                textPaint.clearShadowLayer()
            }

            val scale = st.scaleAnim.value
            val rotate = st.rotateAnim.value
            val cx = layout.blockLeft + st.rowX + st.width * 0.5f + st.xAnim.value
            val cy = st.rowTop + layout.rowHeight * 0.5f + st.yAnim.value
            val baselineRel = layout.baselineInRow - layout.rowHeight * 0.5f

            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(rotate)
            canvas.scale(scale, scale)
            canvas.drawText(st.text, -st.width * 0.5f, baselineRel, textPaint)
            canvas.restore()
        }
    }

    /** 移植 wordGlow.ts:61-63 `glowRadii`（数学在共享的 `GlowWordGlow` 里） */
    private fun glowRadiiFor(layout: LineLayout): Pair<Float, Float> {
        val mode = layout.profile?.wordRevealMode ?: "normal"
        return GlowWordGlow.radiiFor(mode)
    }

    // ══════════════════════════════════════════════════════════════════
    // 颜色工具
    // ══════════════════════════════════════════════════════════════════

    private fun argbToHex(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val sb = StringBuilder(9)
        sb.append('#')
        sb.append(hex2(a))
        sb.append(hex2(r))
        sb.append(hex2(g))
        sb.append(hex2(b))
        return sb.toString()
    }

    private fun hex2(v: Int): String {
        val s = Integer.toHexString(v).uppercase()
        return if (s.length < 2) "0$s" else s
    }

    private fun hexToArgb(hex: String, fallback: Int): Int {
        if (hex.length != 9 || hex[0] != '#') return fallback
        return try {
            val a = hex.substring(1, 3).toInt(16)
            val r = hex.substring(3, 5).toInt(16)
            val g = hex.substring(5, 7).toInt(16)
            val b = hex.substring(7, 9).toInt(16)
            (a shl 24) or (r shl 16) or (g shl 8) or b
        } catch (_: NumberFormatException) {
            fallback
        }
    }

    private companion object {
        /** 移植 classic/Visualizer.tsx:162-164 —— 非 calm 档的 justify-content 候选 */
        private val JUSTIFY_NORMAL = arrayOf("start", "center", "end", "around", "between")

        /** 移植 classic/Visualizer.tsx:162-164 —— calm 档只有居中 */
        private val JUSTIFY_CALM = arrayOf("center")

        /** 移植 classic/Visualizer.tsx:165-167 —— 非 calm 档的 align-items 候选 */
        private val ALIGN_NORMAL = arrayOf("start", "center", "end")

        /** 移植 classic/Visualizer.tsx:165-167 —— calm 档只有居中 */
        private val ALIGN_CALM = arrayOf("center")
    }
}
