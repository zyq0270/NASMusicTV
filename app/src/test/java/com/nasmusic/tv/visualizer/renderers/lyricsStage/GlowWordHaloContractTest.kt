package com.nasmusic.tv.visualizer.renderers.lyricsStage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E53「歌词舞台」—— **辉光位图缓存**（`GlowWordHalo`）的契约门禁。
 *
 * ## 为什么要有这条门禁
 * `GlowWordHalo` 是「每帧 N 次文字模糊」→「每帧 N 次贴图」的关键。
 * 它有两个容易被改坏的性质：
 *
 * 1. ⛔ **命中路径必须零分配**。
 *    缓存键一旦是 `String`，每次查找都要拼一次字符串 ⇒ 逐帧每个可见字形分配一次
 *    （60 fps × 20 字形 = 每秒 1200 次）⇒ 在电视上就是 GC 抖动。
 *    ⇒ 必须是「数组 + 线性比对」这类**不拼键**的查找。
 *
 * 2. ⛔ **绝不 `recycle()` 被丢掉的位图**。
 *    渲染器可能把 `Entry` 存进自己的数组并**跨帧持有**（离场动画还在画上一行）。
 *    若缓存把逐出的位图 `recycle()`，那些陈旧引用再被 `drawBitmap` 画一次就会抛
 *    「trying to use a recycled bitmap」⇒ 切歌/换行时随机崩溃。
 *
 * ## 这条门禁钉住什么
 * 直接读源码文本断言上面两条 —— 这类「性能/健壮性契约」用源码断言比用行为断言更稳
 * （行为断言需要真的渲染，而 `Paint`/`Bitmap` 在单测里是 shadow 实现，测不准）。
 */
class GlowWordHaloContractTest {

    private fun readSource(): String {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        val dir = candidates.first { it.isDirectory }
        val f = File(dir, "java/com/nasmusic/tv/visualizer/renderers/lyricsStage/GlowWordHalo.kt")
        assertTrue("找不到 GlowWordHalo.kt（工作目录 ${File("").absolutePath}）", f.isFile)
        return f.readText().replace("\r\n", "\n")
    }

    @Test
    fun `the halo cache must never recycle a bitmap`() {
        val src = readSource()
        // ⛔ 只认**真正的调用**（`xxx.recycle()`），不认 KDoc 里提到的 `recycle()`。
        val calls = Regex("""\.\s*recycle\s*\(""").findAll(src).count()
        assertEquals(
            "GlowWordHalo 不得调用 recycle() —— 渲染器可能跨帧持有 Entry，" +
                "回收后再 drawBitmap 会抛「trying to use a recycled bitmap」",
            0,
            calls,
        )
    }

    @Test
    fun `the halo cache must not build a string key on lookup`() {
        val src = readSource()
        // 缓存键一旦是 String，每次查找都要拼一次 ⇒ 逐帧每字形一次分配
        assertTrue(
            "GlowWordHalo 不得用 String 做缓存键（逐帧拼键 = 每帧每字形一次分配）",
            !Regex("""val\s+\w*key\w*\s*[:=]""").containsMatchIn(src),
        )
        assertTrue(
            "GlowWordHalo 不得用 `cache[key]` 这种字符串键索引",
            !Regex("""cache\[\s*\w+\s*]""").containsMatchIn(src),
        )
    }

    @Test
    fun `the halo cache must look up before it builds a bitmap`() {
        val src = readSource()
        val lookup = src.indexOf("for (i in entries.indices)")
        val build = src.indexOf("Bitmap.createBitmap")
        assertTrue("GlowWordHalo 必须在建位图之前先查缓存", lookup >= 0 && build >= 0 && lookup < build)
    }

    @Test
    fun `the halo cache must be reusable by every renderer`() {
        val src = readSource()
        assertTrue("GlowWordHalo 必须提供 obtain(...)", src.contains("fun obtain("))
        assertTrue("GlowWordHalo 必须提供 clear()", src.contains("fun clear("))
    }
}
