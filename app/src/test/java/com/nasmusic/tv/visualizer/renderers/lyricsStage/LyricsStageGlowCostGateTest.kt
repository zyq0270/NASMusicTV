package com.nasmusic.tv.visualizer.renderers.lyricsStage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E53「歌词舞台」—— **逐字形辉光的开销门禁**。
 *
 * ## 为什么要有这条门禁
 * Folia 的辉光层用合成器滤镜（`drop-shadow()`）—— GPU 上免费。
 * Android 最接近的是 `Paint.setShadowLayer`，而它是**逐次绘制的模糊**：
 * 每个字形每次都要重新模糊一次。
 *
 * Folia 自己在 `wordGlow.ts:10-27` 记录过 text-shadow 的代价：Chromium 的字形缓存
 * 不断铸造 strike，Mac 上实测光栅线程每秒耗掉 **160–220 ms**。
 * 一行 30 个字形 × 外圈/内圈两遍 ⇒ **每帧 60 次文字模糊** ⇒ 电视上必卡。
 *
 * ⇒ 现在的做法：辉光**位图缓存**（`GlowWordHalo`）—— 模糊每个 (字形, 字号, 颜色) 只算一次，
 * 逐帧只做一次 `drawBitmap`。
 *
 * ⛔ 这条门禁就是防止「谁哪天又把 `setShadowLayer` 塞回逐帧路径」。
 */
class LyricsStageGlowCostGateTest {

    private fun readMain(rel: String): String {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        val dir = candidates.first { it.isDirectory }
        val f = File(dir, rel)
        assertTrue("找不到源码：$rel（工作目录 ${File("").absolutePath}）", f.isFile)
        return f.readText().replace("\r\n", "\n")
    }

    private fun lyricsStage(rel: String): String =
        readMain("java/com/nasmusic/tv/visualizer/renderers/lyricsStage/$rel")

    /** 逐帧辉光路径的两个文件 */
    private val perFrameGlowFiles = listOf("GlowLineSubRenderer.kt", "PartitaSubRenderer.kt")

    /**
     * 逐字形辉光函数里**不得**有 `setShadowLayer`。
     *
     * ⚠️ 只查**辉光函数内部**，不查整个文件：文件里允许出现少量 `setShadowLayer`
     *   （例如引导线的辉光 —— Android 的 `setShadowLayer` 对**线条**要到 API 28 才硬件支持，
     *   更早的设备会整块退回软件层）。但「每个字形每帧两次文字模糊」是绝对不能有的。
     */
    @Test
    fun `the per frame glow path must not call setShadowLayer`() {
        val glowFunctions = listOf("drawGlowPass", "drawGlowLayer")
        for (file in perFrameGlowFiles) {
            val src = lyricsStage(file)
            for (fn in glowFunctions) {
                val body = functionBody(src, fn) ?: continue
                val hits = Regex("""setShadowLayer\s*\(""").findAll(body).count()
                assertEquals(
                    "$file 的 $fn() 里不得有 setShadowLayer（逐字形文字模糊 = 每帧 N 次模糊 ⇒ 电视上卡顿；" +
                        "辉光已改用 GlowWordHalo 的位图缓存）",
                    0,
                    hits,
                )
            }
        }
    }

    /** 取某个函数体的文本（按大括号配对）；找不到返回 null。 */
    private fun functionBody(src: String, name: String): String? {
        val idx = src.indexOf("fun $name(")
        if (idx < 0) return null
        var depth = 0
        var started = false
        var i = idx
        while (i < src.length) {
            when (src[i]) {
                '{' -> { depth++; started = true }
                '}' -> {
                    depth--
                    if (started && depth == 0) return src.substring(idx, i + 1)
                }
            }
            i++
        }
        return null
    }

    @Test
    fun `the per frame glow path must use the shared halo bitmap cache`() {
        for (file in perFrameGlowFiles) {
            val src = lyricsStage(file)
            assertTrue(
                "$file 必须用 GlowWordHalo.obtain(...) 画辉光（位图缓存）",
                src.contains("GlowWordHalo.obtain("),
            )
            assertTrue(
                "$file 必须用 drawBitmap 贴辉光位图",
                Regex("""drawBitmap\s*\(""").containsMatchIn(src),
            )
        }
    }

    @Test
    fun `the halo cache must be released on exit`() {
        for (file in perFrameGlowFiles) {
            val src = lyricsStage(file)
            assertTrue(
                "$file 必须在 onExit 里调 GlowWordHalo.clear()（否则位图泄漏）",
                Regex("""onExit[\s\S]{0,600}?GlowWordHalo\.clear\s*\(""").containsMatchIn(src),
            )
        }
    }

    /**
     * ⛔ `BlurMaskFilter` 在硬件画布上**整个被丢弃** ⇒ CSS `filter: blur()` 没有等价实现；
     * 而且在软件路径上会把整块绘制拖进软件层 ⇒ 一次大停顿。
     */
    @Test
    fun `no renderer may use BlurMaskFilter`() {
        val files = listOf(
            "GlowLineSubRenderer.kt",
            "PartitaSubRenderer.kt",
            "TiltPulseSubRenderer.kt",
            "FullViewSubRenderer.kt",
        )
        for (file in files) {
            val src = lyricsStage(file)
            assertTrue(
                "$file 不得使用 BlurMaskFilter（硬件画布丢弃它；软件路径会拖慢整块绘制）",
                !src.contains("BlurMaskFilter"),
            )
        }
    }

    /** 配色必须走语义映射（不能直接拿 `palette.accent` 当基础文字色） */
    @Test
    fun `the renderers must take their colours from the semantic mapping`() {
        val files = listOf(
            "GlowLineSubRenderer.kt",
            "PartitaSubRenderer.kt",
            "TiltPulseSubRenderer.kt",
            "FullViewSubRenderer.kt",
        )
        for (file in files) {
            val src = lyricsStage(file)
            assertTrue(
                "$file 必须用 LyricsStageColors（按语义取色）",
                src.contains("LyricsStageColors."),
            )
            assertTrue(
                "$file 不得再直接拿 ctx.palette.accent 当基础文字色" +
                    "（它在本仓恒为鲜艳蓝 ⇒ 满屏歌词变成霓虹蓝）",
                !Regex("""palette\.accent\.toArgb\(\)""").containsMatchIn(src),
            )
        }
    }
}
