// 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/fumeBlockFrame.ts — Project Folia version 0.5.27-a16525c
package com.nasmusic.tv.visualizer.renderers.lyricsStage.fume

import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricRenderHints

/**
 * 单个文章块的逐帧判定：是否在屏上、此刻的时序与样式、以及该画哪几层缓存快照。
 *
 * 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/fumeBlockFrame.ts — Project Folia version 0.5.27-a16525c
 *
 * ## 浏览器 API → Android 等价物
 *  - Folia 的 `theme` 只用到 `theme.primaryColor` ⇒ Android 传 ARGB Int。
 *  - Folia 的 `getSnapshot(spec)` 回调每次都新建一个 spec 对象（分配）⇒
 *    Android 改成 [FumeSnapshotProvider] 接口 + **预计算的缓存键**
 *    （键在版面构建时算好，见 `FumeTypes.FumeBlock` 的三个 `snapshotKey*`），
 *    绘制期零字符串拼接、零分配。
 *  - Folia 的 `resolveFumeStaticLayers` 返回数组（每帧分配）⇒
 *    Android 改成往复用的 [FumeStaticLayers] 里写（至多两层）。
 */
object FumeBlockFrame {

    /** 移植 fumeBlockFrame.ts:17-29 `resolveFumeGlowBases` 的返回结构（可变，逐帧复用） */
    class FumeGlowBases {
        var activeGlowBoost: Float = 0f
        var passedGlowBase: Float = 0f
    }

    /** 移植 fumeBlockFrame.ts:18-29 `resolveFumeGlowBases` */
    fun resolveFumeGlowBases(
        animationIntensity: String,
        glowIntensity: Float,
        out: FumeGlowBases,
    ): FumeGlowBases {
        val activeBase = when (animationIntensity) {
            "chaotic" -> 1.15f
            "calm" -> 0.72f
            else -> 0.92f
        }
        val passedBase = when (animationIntensity) {
            "chaotic" -> 0.95f
            "calm" -> 0.35f
            else -> 0.62f
        }
        out.activeGlowBoost = activeBase * glowIntensity
        out.passedGlowBase = passedBase * glowIntensity
        return out
    }

    /** 移植 fumeBlockFrame.ts:32-39 `isFumeBlockOnScreen` */
    fun isFumeBlockOnScreen(
        block: FumeTypes.FumeBlock,
        camera: FumeTypes.CameraTarget,
        viewport: FumeTypes.ViewportSize,
        overscan: Float = 180f,
    ): Boolean {
        val screenScale = camera.scale
        val screenLeft = viewport.width * 0.5f + (block.x - camera.x) * screenScale
        val screenTop = viewport.height * 0.5f + (block.y - camera.y) * screenScale
        val screenRight = screenLeft + block.width * screenScale
        val screenBottom = screenTop + block.height * screenScale
        return !(screenRight < -overscan || screenLeft > viewport.width + overscan ||
            screenBottom < -overscan || screenTop > viewport.height + overscan)
    }

    /**
     * 移植 fumeBlockFrame.ts:41-56 `FumeBlockTiming`
     *
     * Android：可变对象（逐帧复用，避免每帧分配）。
     */
    class FumeBlockTiming {
        var waitingOpacity: Float = 0f
        var activeOpacity: Float = 0f
        /** `standard` 或 `dimmed` */
        var effectiveTextHoldStyle: String = "standard"
        var passedStyle: FumeTextStyle.PassedTextStyle = FumeTextStyle.PassedTextStyle(0f, 0f, 0f, 0f)
        var transitionPassedStyle: FumeTextStyle.PassedTextStyle = FumeTextStyle.PassedTextStyle(0f, 0f, 0f, 0f)
        var baselineOffset: Float = 0f
        var lineEndTime: Double = 0.0
        var linePassCutoffTime: Double = 0.0
        var hasRevealCompleted: Boolean = false
        var hasPassCutoffReached: Boolean = false
        var lineDuration: Double = 0.0
        var colorTrailDuration: Float = 0f
        /** `waiting` / `passed` / null（null = 正在打印或处于色尾） */
        var staticState: String? = null
    }

    /**
     * 移植 fumeBlockFrame.ts:58-101 `resolveFumeBlockTiming`
     *
     * @param out 复用对象；本函数把结果写进去并返回它（零分配）。
     */
    fun resolveFumeBlockTiming(
        block: FumeTypes.FumeBlock,
        time: Double,
        nextLineStartTime: Double?,
        textHoldRatio: Float,
        out: FumeBlockTiming,
    ): FumeBlockTiming {
        val isHero = block.variant == FumeTypes.VARIANT_HERO
        out.waitingOpacity = if (isHero) 0.06f else 0.035f
        out.activeOpacity = if (isHero) 0.985f else 0.92f
        out.effectiveTextHoldStyle = if (textHoldRatio >= 1f) "standard" else "dimmed"
        out.passedStyle = FumeTextStyle.resolvePassedTextStyle(block.variant, out.effectiveTextHoldStyle)
        out.transitionPassedStyle = FumeTextStyle.resolvePassedTextStyle(block.variant, "standard")
        out.baselineOffset = block.lineHeight * (if (block.isCjkLine) 0.52f else 0.5f)
        out.lineEndTime = LyricRenderHints.getLineRenderEndTime(block.line)
        out.linePassCutoffTime = FumeReveal.resolveLinePassCutoffTime(block.line, nextLineStartTime)
        val revealCompleteTime = block.line.endTime
        out.hasRevealCompleted = time >= revealCompleteTime
        out.hasPassCutoffReached = time >= out.linePassCutoffTime
        out.lineDuration = Math.max(out.lineEndTime - block.line.startTime, 0.18)
        out.colorTrailDuration = FumeMath.clamp(
            (out.lineDuration * (if (isHero) 0.42f else 0.52f)).toFloat(),
            0.45f,
            1.45f,
        )
        out.staticState = when {
            time < block.line.startTime -> "waiting"
            time >= out.lineEndTime + out.colorTrailDuration -> "passed"
            else -> null
        }
        return out
    }

    /**
     * 快照提供者（Folia 的 `getSnapshot(spec)` 回调）。
     *
     * Android：接口而非 lambda ⇒ 绘制期不产生闭包分配；
     * spec 的四个字段拆成参数 ⇒ 绘制期不产生 spec 对象分配。
     */
    interface FumeSnapshotProvider {
        /**
         * 取某个 spec 对应的快照；没有就按 spec 新建。
         *
         * @param key 缓存键（**预先算好**，绘制期不拼字符串）
         * @param fill 文字填充色（ARGB）
         * @param shadowBlur 辉光半径（0 = 无辉光）
         * @param shadowColor 辉光颜色（ARGB）
         * @return null = 这个 spec 造不出来（此时该块改为实时绘制）。
         */
        fun getSnapshot(
            key: String,
            fill: Int,
            shadowBlur: Float,
            shadowColor: Int,
        ): FumeTypes.StaticSnapshot?
    }

    /** 移植 fumeBlockFrame.ts:111-119 `FumeStaticLayerInput` */
    class FumeStaticLayerInput {
        var time: Double = 0.0
        /** Folia 的 `theme.primaryColor` */
        var primaryColor: Int = 0xFFFFFFFF.toInt()
        var passedGlowBase: Float = 1f
        var passedFadeDuration: Float = 0f
        /** 全景镜头飞入时：0..1，唱过的文字恢复标准样式 */
        var overviewTextRestoreProgress: Float = 0f
        /** 快照的光栅密度（缓存键的一部分） */
        var snapshotScale: Float = 1f
    }

    /**
     * 移植 fumeBlockFrame.ts:127-175 `resolveFumeStaticLayers` 的返回结构。
     *
     * Android：复用的可变容器（至多两层：标准层 + 暗色层）。
     */
    class FumeStaticLayers {
        var count: Int = 0
        val snapshots = arrayOfNulls<FumeTypes.StaticSnapshot>(2)
        val alphas = FloatArray(2)

        fun reset() {
            count = 0
            snapshots[0] = null
            snapshots[1] = null
            alphas[0] = 0f
            alphas[1] = 0f
        }
    }

    /**
     * 移植 fumeBlockFrame.ts:127-175 `resolveFumeStaticLayers`
     *
     * 「静态块」要画哪几层缓存快照（自底向上）：正常一层；暗色唱过块淡出时两层交叉淡入。
     *
     * @param out 复用容器；返回时 `out.count` = 层数（0 = 没有快照 ⇒ 该块实时绘制）。
     */
    fun resolveFumeStaticLayers(
        block: FumeTypes.FumeBlock,
        timing: FumeBlockTiming,
        input: FumeStaticLayerInput,
        getSnapshot: FumeSnapshotProvider,
        out: FumeStaticLayers,
    ): FumeStaticLayers {
        out.reset()
        val staticState = timing.staticState ?: return out

        val isPassed = staticState == "passed"
        val passedStyle = timing.passedStyle
        val standardStyle = timing.transitionPassedStyle

        val mainKey = if (isPassed) block.snapshotKeyPassed else block.snapshotKeyWaiting
        val mainFill = if (isPassed) {
            FumeTextStyle.colorWithAlpha(input.primaryColor, passedStyle.opacity)
        } else {
            FumeTextStyle.colorWithAlpha(input.primaryColor, timing.waitingOpacity)
        }
        val mainShadowBlur = if (isPassed) {
            (2f + block.fontPx * 0.1f) * 0.65f * input.passedGlowBase * passedStyle.glowMultiplier
        } else {
            0f
        }
        val mainShadowColor = if (isPassed) {
            FumeTextStyle.colorWithAlpha(input.primaryColor, passedStyle.shadowAlphaBase)
        } else {
            0
        }

        val snapshot = getSnapshot.getSnapshot(
            key = mainKey,
            fill = mainFill,
            shadowBlur = mainShadowBlur,
            shadowColor = mainShadowColor,
        ) ?: return out

        if (isPassed && timing.effectiveTextHoldStyle == "dimmed") {
            // 移植 fumeBlockFrame.ts:151-171 —— 暗色唱过块：标准层 + 暗色层交叉淡入
            val passedAt = timing.lineEndTime + timing.colorTrailDuration
            val baseDimAmount = FumeTextStyle.resolvePassedDimAmount(
                input.time,
                passedAt,
                input.passedFadeDuration.toDouble(),
            )
            val dimAmount = baseDimAmount * (1f - input.overviewTextRestoreProgress)

            val standardSnapshot = getSnapshot.getSnapshot(
                key = block.snapshotKeyPassedStandard,
                fill = FumeTextStyle.colorWithAlpha(input.primaryColor, standardStyle.opacity),
                shadowBlur = (2f + block.fontPx * 0.1f) * 0.65f * input.passedGlowBase *
                    standardStyle.glowMultiplier,
                shadowColor = FumeTextStyle.colorWithAlpha(input.primaryColor, standardStyle.shadowAlphaBase),
            )

            if (standardSnapshot != null) {
                if (dimAmount <= 0f) {
                    out.count = 1
                    out.snapshots[0] = standardSnapshot
                    out.alphas[0] = 1f
                    return out
                }
                if (dimAmount < 1f) {
                    out.count = 2
                    out.snapshots[0] = standardSnapshot
                    out.alphas[0] = 1f - dimAmount
                    out.snapshots[1] = snapshot
                    out.alphas[1] = dimAmount
                    return out
                }
            }
        }

        out.count = 1
        out.snapshots[0] = snapshot
        out.alphas[0] = 1f
        return out
    }
}
