package com.nasmusic.tv.visualizer.renderers.lyricsStage

import java.text.BreakIterator
import java.util.Locale

/**
 * Grapheme（字素簇）拆分。
 *
 * Folia 用 `Intl.Segmenter`（granularity: 'grapheme'）。
 * Android min SDK 22 没有 Intl.Segmenter，API 24 起才有 `android.icu.text.BreakIterator`
 * （真 grapheme 簇）。这里：
 *  - API 24+：反射调用 android.icu.text.BreakIterator 的字符实例 ⇒ 真 grapheme 簇
 *  - 更低版本：java.text.BreakIterator 的字符实例 ⇒ 至少正确处理代理对（码点）
 *
 * 移植自 Folia（AGPL-3.0）: src/utils/lyrics/graphemeTiming.ts `splitLyricGraphemes`
 */
object GraphemeSplitter {

    /** 缓存 BreakIterator 实例（BreakIterator 不是线程安全的，但本项目只在渲染线程用） */
    private var cachedIcu: Any? = null
    private var icuResolved = false

    private val fallback: BreakIterator by lazy {
        BreakIterator.getCharacterInstance(Locale.getDefault())
    }

    /** 移植 graphemeTiming.ts:17-27 */
    fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val icu = resolveIcu()
        if (icu != null) {
            return splitWithIcu(icu, text)
        }

        val it = fallback
        it.setText(text)
        val result = mutableListOf<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            result.add(text.substring(start, end))
            start = end
            end = it.next()
        }
        return result
    }

    private fun resolveIcu(): Any? {
        if (icuResolved) return cachedIcu
        icuResolved = true
        cachedIcu = try {
            // API 24+ 有 android.icu.text.BreakIterator；反射调用以免 minSdk 22 触发 lint NewApi
            val clazz = Class.forName("android.icu.text.BreakIterator")
            val method = clazz.getMethod(
                "getCharacterInstance",
                Locale::class.java,
            )
            method.invoke(null, Locale.getDefault())
        } catch (_: Throwable) {
            null
        }
        return cachedIcu
    }

    private fun splitWithIcu(icu: Any, text: String): List<String> {
        return try {
            // ⚠️ `setText` 的形参类型是 **CharSequence**（不是 String）⇒ 查方法时
            //    必须用 CharSequence::class.java，否则 NoSuchMethodException ⇒ 静默退化成逐码元。
            val setText = icu.javaClass.getMethod("setText", CharSequence::class.java)
            val first = icu.javaClass.getMethod("first")
            val next = icu.javaClass.getMethod("next")

            setText.invoke(icu, text)
            val result = mutableListOf<String>()
            var start = first.invoke(icu) as Int
            var end = next.invoke(icu) as Int
            while (end != BreakIterator.DONE) {
                if (end > start) result.add(text.substring(start, end))
                start = end
                end = next.invoke(icu) as Int
            }
            // 防御：反射调用若与预期不符，退回逐字符
            if (result.isEmpty() && text.isNotEmpty()) {
                text.map { it.toString() }
            } else {
                result
            }
        } catch (_: Throwable) {
            text.map { it.toString() }
        }
    }
}
