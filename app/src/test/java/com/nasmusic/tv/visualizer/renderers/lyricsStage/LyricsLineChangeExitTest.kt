package com.nasmusic.tv.visualizer.renderers.lyricsStage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E53「歌词舞台」—— **换行不得掐断离场动画**的门禁测试（C49）。
 *
 * 背景：`LyricsSubRenderer.onLineChanged` 的默认实现 = 再走一遍 `onEnter`。
 * 这对「整体入场」型子模式（点阵 / 全屏）是对的，但对**逐行自管版面**的三个子模式
 * （GLOW_LINE / TILT_PULSE / PARTITA）是错的：
 *
 *  - 三者都在 `draw()` 里自己检测换行（`needRebuild` / `syncLine`），
 *    把旧版面转进 `prevLayout` / `exitingState` 后按各自的时长播离场；
 *  - 而 `onEnter` 会把这些字段连同逐帧时间步一起复位
 *    ⇒ 离场动画**永远不执行**，换行变成硬切。
 *
 * 单测咬不住（`DrawScope` 扩展 + 帧时钟），所以做源码扫描：
 * 三个子渲染器必须**显式覆盖** `onLineChanged`，且实现体**不得**转调 `onEnter`。
 */
class LyricsLineChangeExitTest {

    private fun readMain(rel: String): String {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        val dir = candidates.first { it.isDirectory }
        val f = File(dir, rel)
        assertTrue("找不到源码：$rel（工作目录 ${File("").absolutePath}）", f.isFile)
        return f.readText().replace("\r\n", "\n")
    }

    private fun sub(rel: String): String =
        readMain("java/com/nasmusic/tv/visualizer/renderers/lyricsStage/$rel")

    /** 逐行自管版面 ⇒ 必须自己处理换行、不得被 onEnter 复位。 */
    private val selfManaged = listOf(
        "GlowLineSubRenderer.kt",
        "TiltPulseSubRenderer.kt",
        "PartitaSubRenderer.kt",
    )

    /** 整体入场型 ⇒ 允许（也期望）用默认的「换行即重进”。 */
    private val wholeEnter = listOf(
        "DotMatrixSubRenderer.kt",
        "FullViewSubRenderer.kt",
    )

    @Test
    fun `the self managed sub renderers override onLineChanged`() {
        for (name in selfManaged) {
            val src = sub(name)
            assertTrue("$name 必须覆盖 onLineChanged（否则离场动画被 onEnter 掐断）",
                src.contains("override fun onLineChanged("))
        }
    }

    @Test
    fun `the self managed onLineChanged must not delegate to onEnter`() {
        for (name in selfManaged) {
            val src = sub(name)
            val body = bodyOf(src, "onLineChanged")
            assertTrue("$name：没找到 onLineChanged 的实现体", body != null)
            assertFalse(
                "$name：onLineChanged 里不得调用 onEnter（会复位版面/离场态 ⇒ 换行硬切）",
                body!!.contains("onEnter"),
            )
            // 也不许顺手复位离场态字段（等价于把动画掐断）
            for (field in listOf("curLayout", "prevLayout", "activeState", "exitingState")) {
                assertFalse(
                    "$name：onLineChanged 里不得写 $field（那正是 onEnter 复位的东西）",
                    Regex("""\b$field\s*=""").containsMatchIn(body),
                )
            }
        }
    }

    @Test
    fun `the base default still re-enters so whole enter sub renderers keep working`() {
        val src = sub("LyricsSubRenderer.kt")
        val body = bodyOf(src, "onLineChanged")
        assertTrue("基类必须保留默认实现", body != null)
        assertTrue(
            "基类默认实现应为 onEnter(ctx, lyrics)（点阵/全屏依赖它）",
            body!!.contains("onEnter(ctx, lyrics)"),
        )
        // 已显式覆盖的两个仍需保留覆盖（它们的入场粒度是整首/整块）
        for (name in wholeEnter) {
            assertTrue("$name 应显式覆盖 onLineChanged", sub(name).contains("override fun onLineChanged("))
        }
    }

    /**
     * 取出「`onLineChanged` 从签名到配对右花括号」的实现体文本。
     *
     * ⚠️ 不用正则跨行匹配（容易吃掉后面的声明）—— 手动做花括号配平，
     *    并跳过字符串与字符字面量里的花括号。
     */
    private fun bodyOf(src: String, fnName: String): String? {
        val key = "override fun $fnName("
        val sig = src.indexOf(key).let { if (it >= 0) it else src.indexOf("fun $fnName(") }
        if (sig < 0) return null
        val open = src.indexOf('{', sig)
        if (open < 0) return null
        var depth = 0
        var i = open
        var inStr = false
        var inChar = false
        var inLineComment = false
        var inBlockComment = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when {
                inLineComment -> if (c == '\n') inLineComment = false
                inBlockComment -> if (c == '*' && next == '/') { inBlockComment = false; i++ }
                inStr -> when {
                    c == '\\' -> i++
                    c == '"' -> inStr = false
                }
                inChar -> when {
                    c == '\\' -> i++
                    c == '\'' -> inChar = false
                }
                c == '/' && next == '/' -> { inLineComment = true; i++ }
                c == '/' && next == '*' -> { inBlockComment = true; i++ }
                c == '"' -> inStr = true
                c == '\'' -> inChar = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open + 1, i)
                }
            }
            i++
        }
        return null
    }
}
