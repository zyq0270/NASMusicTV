package com.nasmusic.tv.visualizer.renderers.lyricsStage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E53「歌词舞台」—— **逐帧开销门禁**。
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
 *
 * ## 2026-11-07 新增的两条（电视卡顿实测后）
 * ① **辉光缓存的生命周期**：子模式切换**不得**清缓存（清了就让切换后的首行重新模糊
 *    每个字形 ⇒ 硬卡顿）⇒ 回收挪到**主题**退出时。
 * ② **缩放量化**：`Canvas.scale` 改的是**设备空间**的字号 ⇒ 缩放一变字形缓存就 miss
 *    ⇒ 动画缩放必须量化到 1/1024，否则每帧重新光栅化整行文字。
 * ③ **切换淡入**：`saveLayer(null, …)` 的图层是**整块画布**（电视上 8.3 MB）
 *    ⇒ 切换那几百毫秒每帧 30 MB 的额外 GPU 流量 ⇒ 切换必须关掉淡入。
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

    /** 主渲染器（在 `renderers/` 而不是 `renderers/lyricsStage/`） */
    private fun rendererSrc(): String =
        readMain("java/com/nasmusic/tv/visualizer/renderers/LyricsStageRenderer.kt")

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

    // ── 辉光缓存的生命周期 ────────────────────────────────────────────────
    //
    // 判据拆成两半（**两半都要成立**）：
    //   ① **主题**退出时清缓存（否则位图泄漏到进程结束）；
    //   ② **子渲染器**（= 子模式切换时走的 `onExit`）**不得**清缓存。
    //
    // ② 是 2026-11-07 加的：切换子模式 ⇒ 缓存变冷 ⇒ 切换后的首行要把每个字形
    // 重新模糊一遍（10–15 字形 × 2 次真实高斯模糊，**主线程**）⇒ 一次硬卡顿。
    // 缓存键是 (字形, 字号, 颜色, 内圈半径, 外圈半径) ⇒ 别的子模式的条目只是**闲置**，
    // 缓存本身还有 `GlowWordHalo.MAX_ENTRIES` 上限 ⇒ 不需要（也不该）在切换时清。

    /** 子渲染器里出现「清辉光缓存」即违规 */
    private fun clearsHaloCache(src: String): Boolean = src.contains("GlowWordHalo.clear(")

    /** 主题渲染器的 `onExit()` 里必须清辉光缓存 */
    private fun themeExitClearsHaloCache(src: String): Boolean =
        functionBody(src, "onExit")?.contains("GlowWordHalo.clear(") == true

    @Test
    fun `the theme exit releases the halo cache`() {
        val src = rendererSrc()
        assertTrue(
            "LyricsStageRenderer 必须有 onExit()",
            functionBody(src, "onExit") != null,
        )
        assertTrue(
            "LyricsStageRenderer.onExit() 必须调 GlowWordHalo.clear()（否则位图泄漏到进程结束）",
            themeExitClearsHaloCache(src),
        )
    }

    @Test
    fun `a sub mode switch must not cool the halo cache`() {
        for (file in perFrameGlowFiles) {
            val src = lyricsStage(file)
            assertFalse(
                "$file 不得调 GlowWordHalo.clear()：子模式切换会走子渲染器的 onExit ⇒ " +
                    "清掉共享缓存 ⇒ 切换后的首行要把每个字形重新模糊一遍" +
                    "（10–15 字形 × 2 次真实高斯模糊，主线程）⇒ 硬卡顿。" +
                    "缓存键含 (字形, 字号, 颜色, 内外半径) ⇒ 别的子模式的条目只是闲置；" +
                    "回收由 LyricsStageRenderer.onExit() 负责。",
                clearsHaloCache(src),
            )
        }
    }

    /**
     * 负向自证：坏值喂给**同一份**判据 ⇒ 必须挂（否则上面两条是空转）。
     */
    @Test
    fun `negative self proof the halo cache assertions bite`() {
        val badSub = """
            override fun onExit() {
                activeState = null
                GlowWordHalo.clear()
            }
        """.trimIndent()
        val goodSub = """
            override fun onExit() {
                activeState = null
            }
        """.trimIndent()
        val badTheme = """
            override fun onExit() {
                subRenderer?.onExit()
            }
        """.trimIndent()
        val goodTheme = """
            override fun onExit() {
                subRenderer?.onExit()
                GlowWordHalo.clear()
            }
        """.trimIndent()

        assertTrue("坏样本（子渲染器清缓存）必须被判违规", clearsHaloCache(badSub))
        assertFalse("好样本（子渲染器不清缓存）必须放行", clearsHaloCache(goodSub))
        assertFalse("坏样本（主题退出不清缓存）必须被判违规", themeExitClearsHaloCache(badTheme))
        assertTrue("好样本（主题退出清缓存）必须放行", themeExitClearsHaloCache(goodTheme))
    }

    // ── 缩放量化（字形缓存不能每帧 miss）────────────────────────────────

    /**
     * 动画缩放**必须量化**：不量化的话设备空间的字号每帧都变 ⇒ 字形缓存每帧 miss
     * ⇒ 整行文字每帧在 RenderThread 上重新光栅化 ⇒ 弱电视上就是卡。
     */
    @Test
    fun `the animated canvas scale must be quantized`() {
        for (file in perFrameGlowFiles) {
            val src = lyricsStage(file)
            assertTrue(
                "$file 必须定义缩放量化步长 SCALE_QUANT_STEP = 1024f" +
                    "（幅度区间只有 1.5% ⇒ 1/64 会把动画塌成一个取值 ⇒ 不能用）",
                Regex("""SCALE_QUANT_STEP\s*=\s*1024f""").containsMatchIn(src),
            )
            assertTrue(
                "$file 必须提供 quantizeScale(...)（缩放量化）",
                src.contains("fun quantizeScale("),
            )
            assertTrue(
                "$file 必须真的用上 quantizeScale(...)",
                Regex("""quantizeScale\s*\(""").containsMatchIn(src),
            )
            // ⛔ 动画缩放**不得**原样传给 `scale(...)`
            assertFalse(
                "$file 不得把动画缩放原样传给 scale(...)（每帧都变 ⇒ 字形缓存每帧 miss）",
                Regex("""scale\(\s*(breathScale|floatScale|chunk\.scale|wordState\.scale)\b""")
                    .containsMatchIn(src),
            )
        }
    }

    // ── 切换淡入（整块画布的图层太贵）──────────────────────────────────

    /**
     * 子模式切换的淡入**必须关掉**：`saveLayer(null, paint)` 的图层是**整块画布**
     * （1080×2340 ⇒ 10 MB；1920×1080 ⇒ 8.3 MB）⇒ 切换那 300 ms 每帧约 30 MB 的
     * 额外 GPU 流量 ⇒ 弱电视上 ≈ 10 ms/帧 ⇒ 切换本身掉到 10–20 fps。
     *
     * ⛔ 淡入的**实现**必须保留（把 `TRANSITION_MS` 改回 `300L` 即可重新启用）。
     */
    @Test
    fun `the sub mode switch must not allocate a full canvas layer`() {
        val src = rendererSrc()
        assertTrue(
            "LyricsStageRenderer.TRANSITION_MS 必须是 0L（淡入会分配整块画布的图层）",
            Regex("""TRANSITION_MS\s*=\s*0L""").containsMatchIn(src),
        )
        assertTrue(
            "淡入的实现必须保留（改回 TRANSITION_MS 即可重新启用）",
            src.contains("saveLayer("),
        )
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
