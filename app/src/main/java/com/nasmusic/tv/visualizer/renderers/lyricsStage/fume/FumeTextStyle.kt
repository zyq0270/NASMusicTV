// 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/fumeTextStyle.ts — Project Folia version 0.5.27-a16525c
package com.nasmusic.tv.visualizer.renderers.lyricsStage.fume

import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricLine
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricRenderHints
import com.nasmusic.tv.visualizer.renderers.lyricsStage.WordColorEntry
import com.nasmusic.tv.visualizer.renderers.lyricsStage.WordColoring
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * fume 的「与渲染器无关」的文字样式：唱过后的样式、它的淡出时长、以及当前词的激活色。
 *
 * 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/fumeTextStyle.ts — Project Folia version 0.5.27-a16525c
 *
 * ## 浏览器 API → Android 等价物
 *  - Folia 的颜色是 CSS `rgba(r,g,b,a)` 字符串 ⇒ Android 用 **ARGB Int**。
 *    `colorWithAlpha` / `mixColors` 移植自 `src/components/visualizer/colorMix.ts`
 *    （放在本文件里：它们是「与渲染器无关的样式」的一部分）。
 *  - `theme.wordColors` 在 NASMusicTV 的 `RenderContext` 里**不存在** ⇒
 *    `resolveWordColor` 永远走回落分支（`theme.accentColor`）。
 *    为保留 Folia 的逐词配色能力，Android 端改成**预先算好的查表**
 *    （见 [buildWordColorMap]），绘制期只做一次 map 查找，零分配。
 */
object FumeTextStyle {

    /** 移植 fumeTextStyle.ts:14-31 `resolvePassedTextStyle` 的返回结构 */
    data class PassedTextStyle(
        val opacity: Float,
        val glowMultiplier: Float,
        val shadowAlphaBase: Float,
        val shadowAlphaTrail: Float,
    )

    /**
     * `resolvePassedTextStyle` 只有 4 种组合（body/hero × standard/dimmed）⇒ 预先建好共享实例，
     * 逐帧调用不产生任何分配。
     */
    private val BODY_STANDARD = PassedTextStyle(0.58f, 1f, 0.1f, 0.16f)
    private val BODY_DIMMED = PassedTextStyle(0.075f, 0f, 0f, 0f)
    private val HERO_STANDARD = PassedTextStyle(0.74f, 1f, 0.1f, 0.16f)
    private val HERO_DIMMED = PassedTextStyle(0.11f, 0f, 0f, 0f)

    /** 移植 fumeTextStyle.ts:14-31 `resolvePassedTextStyle` */
    fun resolvePassedTextStyle(variant: String, textHoldStyle: String): PassedTextStyle {
        val isHero = variant == FumeTypes.VARIANT_HERO
        return if (textHoldStyle == "dimmed") {
            if (isHero) HERO_DIMMED else BODY_DIMMED
        } else {
            if (isHero) HERO_STANDARD else BODY_STANDARD
        }
    }

    /** 移植 fumeTextStyle.ts:33-44 `resolvePassedDimAmount` */
    fun resolvePassedDimAmount(
        currentTimeValue: Double,
        passedAt: Double,
        fadeDuration: Double,
    ): Float {
        if (!currentTimeValue.isFinite() || !passedAt.isFinite() || !fadeDuration.isFinite() || fadeDuration <= 0.0) {
            return 1f
        }

        val passedAge = max(currentTimeValue - passedAt, 0.0)
        return FumeMath.easeInCubic(FumeMath.clamp((passedAge / fadeDuration).toFloat(), 0f, 1f))
    }

    /**
     * 移植 fumeTextStyle.ts:46-82 `resolveFumePassedFadeDuration`
     *
     * ⚠️ Folia 用「每行 start:end 拼成的字符串」做缓存键；Android 端改成
     * **不缓存**（本函数只在版面构建时调用一次，不在绘制期），
     * 避免逐帧拼字符串。
     */
    fun resolveFumePassedFadeDuration(lines: List<LyricLine>, textHoldRatio: Float): Float {
        if (textHoldRatio >= 1f) return Float.POSITIVE_INFINITY

        // 移植 fumeTextStyle.ts:51-61 —— 只保留时间有限的行，并按开始时间排序
        val timed = ArrayList<Pair<Double, Double>>(lines.size)
        for (line in lines) {
            val startTime = line.startTime
            val endTime = LyricRenderHints.getLineRenderEndTime(line)
            if (startTime.isFinite() && endTime.isFinite() && endTime >= startTime) {
                timed.add(Pair(startTime, endTime))
            }
        }
        timed.sortBy { it.first }

        if (timed.size <= 1) {
            return FumeMath.clamp(8f * textHoldRatio, 2.4f, 130f)
        }

        val first = timed.first()
        val last = timed.last()
        val totalDuration = max(last.second - first.first, 0.0)
        return FumeMath.clamp((totalDuration * textHoldRatio).toFloat(), 2.4f, 130f)
    }

    /**
     * 移植 fumeTextStyle.ts:84-88 `getActiveColor`
     *
     * Folia：`resolveWordColor(wordText, theme.wordColors, theme.accentColor, { cjkMatchMode: 'bidirectional-contains' })`
     *
     * Android：`theme.accentColor` ⇒ `LyricsStageColors.accent(ctx)`（**鲜艳蓝**的高亮色）；
     * `theme.wordColors` 在本仓不存在 ⇒ 词色表由调用方预先算好（[buildWordColorMap]），
     * 绘制期只查表，未命中即回落到 accent 色。
     *
     * ⚠️ 基础文字色走 `LyricsStageColors.baseText(ctx)`（近白），**不是** `palette.accent`
     * （那在本仓恒为鲜艳蓝 ⇒ 满屏歌词会变成霓虹蓝）。
     */
    fun getActiveColor(wordText: String, accentArgb: Int, wordColorMap: Map<String, Int>): Int {
        if (wordColorMap.isEmpty()) return accentArgb
        return wordColorMap[wordText] ?: accentArgb
    }

    /**
     * 预先算出「词文本 → 激活色」的查表（Folia 的 `theme.wordColors` 在本仓的等价物）。
     *
     * 匹配规则复用本仓的 `WordColoring.resolveWordColor`（含 Folia 的
     * `cjkMatchMode = 'bidirectional-contains'`）。空词色表 ⇒ 空表（全部回落 accent）。
     */
    fun buildWordColorMap(
        wordTexts: Collection<String>,
        wordColors: List<WordColorEntry>,
        accentArgb: Int,
    ): Map<String, Int> {
        if (wordColors.isEmpty() || wordTexts.isEmpty()) return emptyMap()

        val fallbackHex = argbToHex(accentArgb)
        val map = HashMap<String, Int>()
        for (text in wordTexts) {
            val matched = WordColoring.resolveWordColor(
                wordText = text,
                wordColors = wordColors,
                fallbackColor = fallbackHex,
                keywordColoringEnabled = true,
                cjkMatchMode = "bidirectional-contains",
            )
            if (matched == fallbackHex) continue
            val parsed = parseColorOrNull(matched) ?: continue
            map[text] = parsed
        }
        return map
    }

    /**
     * 逐词区间的激活色（Folia 在绘制期每次都调 `getActiveColor`）。
     *
     * Android：绘制期不能分配 ⇒ 在版面构建时算好，绘制期只按下标取。
     *
     * @param wordTexts 每个词区间的词文本（与 [FumeTypes.WordRange] 同序）
     */
    fun buildActiveColors(
        wordTexts: List<String>,
        wordColorMap: Map<String, Int>,
        accentArgb: Int,
    ): IntArray {
        val colors = IntArray(wordTexts.size)
        for (index in wordTexts.indices) {
            colors[index] = getActiveColor(wordTexts[index], accentArgb, wordColorMap)
        }
        return colors
    }

    /**
     * 移植 `src/components/visualizer/colorMix.ts:75-81` `colorWithAlpha`
     *
     * Folia 返回 `rgba(r,g,b,alpha)`；Android 直接改 alpha 通道。
     */
    fun colorWithAlpha(color: Int, alpha: Float): Int {
        val normalizedAlpha = FumeMath.clamp(alpha, 0f, 1f)
        val a = (normalizedAlpha * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /**
     * 移植 `src/components/visualizer/colorMix.ts:99-108` `mixColors`
     *
     * Folia 线性混合 r/g/b 通道并把 alpha 设为给定值（**不**做预乘）。
     */
    fun mixColors(from: Int, to: Int, amount: Float, alpha: Float = 1f): Int {
        val normalizedAmount = FumeMath.clamp(amount, 0f, 1f)
        val r = mixChannel(from, to, normalizedAmount, 16)
        val g = mixChannel(from, to, normalizedAmount, 8)
        val b = mixChannel(from, to, normalizedAmount, 0)
        val a = (FumeMath.clamp(alpha, 0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun mixChannel(from: Int, to: Int, amount: Float, shift: Int): Int {
        val fromValue = (from shr shift) and 0xFF
        val toValue = (to shr shift) and 0xFF
        val mixed = fromValue + (toValue - fromValue) * amount
        return min(255f, max(0f, round(mixed))).toInt()
    }

    /** ARGB Int → `#rrggbb`（`WordColoring.resolveWordColor` 的回落色格式） */
    private fun argbToHex(argb: Int): String {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return String.format("#%02x%02x%02x", r, g, b)
    }
    /** `#rrggbb` / `#aarrggbb` → ARGB Int；解析不了返回 null */
    private fun parseColorOrNull(text: String): Int? = try {
        android.graphics.Color.parseColor(text)
    } catch (_: Throwable) {
        null
    }
}
