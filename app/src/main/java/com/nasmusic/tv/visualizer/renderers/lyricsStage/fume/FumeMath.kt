package com.nasmusic.tv.visualizer.renderers.lyricsStage.fume

import com.nasmusic.tv.visualizer.renderers.lyricsStage.GraphemeSplitter

/**
 * 移植自 Folia（AGPL-3.0）: src/components/visualizer/fume/fumeMath.ts
 * Folia 版本: 0.5.27-a16525c
 *
 * fume 各处共用的小工具：数值、缓动、grapheme 与播种。
 */
object FumeMath {

    /** 移植 fumeMath.ts:8-14 `splitGraphemes` */
    fun splitGraphemes(text: String): List<String> = GraphemeSplitter.split(text)

    /** 移植 fumeMath.ts:16 */
    fun clamp(value: Float, min: Float, max: Float): Float = Math.max(min, Math.min(max, value))

    /** 移植 fumeMath.ts:17 */
    fun mix(from: Float, to: Float, amount: Float): Float = from + (to - from) * amount

    /** 移植 fumeMath.ts:18-22 */
    fun quadraticBezier(from: Float, control: Float, to: Float, amount: Float): Float {
        val normalized = clamp(amount, 0f, 1f)
        val inverse = 1f - normalized
        return inverse * inverse * from +
            2f * inverse * normalized * control +
            normalized * normalized * to
    }

    /** 移植 fumeMath.ts:23 */
    fun easeOutCubic(value: Float): Float {
        val v = clamp(value, 0f, 1f)
        // ⚠️ Kotlin **没有** `Double - Float` 运算 ⇒ 先把 Float 转成 Double。
        return 1f - Math.pow((1.0 - v.toDouble()), 3.0).toFloat()
    }

    /** 移植 fumeMath.ts:24 */
    fun easeInCubic(value: Float): Float {
        val v = clamp(value, 0f, 1f)
        return Math.pow(v.toDouble(), 3.0).toFloat()
    }

    /** 移植 fumeMath.ts:25-30 */
    fun easeInOutCubic(value: Float): Float {
        val normalized = clamp(value, 0f, 1f)
        return if (normalized < 0.5f) {
            4f * normalized * normalized * normalized
        } else {
            // ⚠️ Kotlin **没有** `Double * Float` 运算 ⇒ 先转 Double 再算。
            1f - (Math.pow((-2.0 * normalized.toDouble() + 2.0), 3.0) / 2.0).toFloat()
        }
    }

    /** 移植 fumeMath.ts:31-43 `resolveDelayedGlowEnvelope` */
    fun resolveDelayedGlowEnvelope(progress: Float, peakProgress: Float = 0.8f): Float {
        val normalized = clamp(progress, 0f, 1f)
        val clampedPeak = clamp(peakProgress, 0.05f, 0.95f)

        if (normalized <= clampedPeak) {
            return easeOutCubic(normalized / clampedPeak)
        }

        return 1f - easeInCubic((normalized - clampedPeak) / (1f - clampedPeak))
    }

    /** 移植 fumeMath.ts:45 */
    fun isCJK(text: String): Boolean =
        Regex("[\\u4e00-\\u9fff\\u3040-\\u30ff\\uac00-\\ud7af]").containsMatchIn(text)

    /** 移植 fumeMath.ts:47-54 `hashString`（FNV-1a）
     *
     *  ⚠️ Folia 的 `hash >>> 0` 返回**无符号** 32 位值（0..4294967295）；
     *    Kotlin 的 `Int` 是**有符号**的 ⇒ 同样的位模式在这里是负数。
     *    需要无符号语义时用 [hashStringUnsigned]。
     */
    fun hashString(input: String): Int {
        var hash = 0x811C9DC5.toInt()
        for (ch in input) {
            hash = hash xor ch.code
            hash = imul(hash, 16777619)
        }
        return hash ushr 0
    }

    /** Folia `hash >>> 0` 的无符号语义（0..4294967295） */
    fun hashStringUnsigned(input: String): Long =
        hashString(input).toLong() and 0xFFFFFFFFL

    /** 移植 fumeMath.ts:56-59 `seeded`
     *
     *  ⚠️ **必须用无符号哈希值**：Kotlin 的 `%` 在负数上给出**负余数**
     *    ⇒ 直接用 `hashString` 会让结果落到 [-1, 0) ⇒ 与 Folia 不符。
     */
    fun seeded(seed: String): Double {
        val hash = hashStringUnsigned(seed)
        return (hash % 10000) / 10000.0
    }

    /**
     * 32 位无符号乘法（等价 `Math.imul`）。
     *
     * ⛔ **`java.lang.Math.imul` 是 API 24+（Java 8 才引入）** ⇒ 本仓 minSdk 22 上
     * 直接调用会在运行期抛 `NoSuchMethodError` ⇒ 这里手写等价实现。
     */
    private fun imul(a: Int, b: Int): Int {
        val aL = a.toLong() and 0xFFFFFFFFL
        val bL = b.toLong() and 0xFFFFFFFFL
        val product = aL * bL
        return ((product ushr 32).toInt()) xor product.toInt()
    }
}
