package com.nasmusic.tv.visualizer.renderers.lyricsStage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E53「歌词舞台」—— **仅手动切换**的门禁测试。
 *
 * 用户裁决（2026-11-06）：子模式**只**能由用户手动切换；
 * ⛔ 不自动定时切换、不随歌曲切换、不随段落/副歌切换。
 *
 * 这条要求是**行为性**的，单靠枚举单测咬不住 ⇒ 这里做**源码扫描**：
 * 断言渲染器里不存在任何自动切换的机制，断言舞台的上下键/上下滑动确实走手动入口。
 */
class LyricsStageManualSwitchTest {

    private fun readMain(rel: String): String {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        val dir = candidates.first { it.isDirectory }
        val f = File(dir, rel)
        assertTrue("找不到源码：$rel（工作目录 ${File("").absolutePath}）", f.isFile)
        return f.readText().replace("\r\n", "\n")
    }

    private fun rendererSrc(): String =
        readMain("java/com/nasmusic/tv/visualizer/renderers/LyricsStageRenderer.kt")

    private fun stageSrc(): String =
        readMain("java/com/nasmusic/tv/ui/components/VisualizerStage.kt")

    // ── 渲染器：不得存在任何自动切换机制 ───────────────────────

    @Test
    fun `the renderer has no timer based auto switch`() {
        val src = rendererSrc()
        assertFalse(
            "歌词舞台不得有定时自动切换（⛔ delay）",
            Regex("""\bdelay\s*\(""").containsMatchIn(src),
        )
        assertFalse(
            "歌词舞台不得有协程作用域（⛔ CoroutineScope / launch）",
            Regex("""\b(CoroutineScope|GlobalScope|viewModelScope)\b""").containsMatchIn(src),
        )
    }

    @Test
    fun `the renderer has no clock driven mode change`() {
        val src = rendererSrc()
        // ⛔ 不得用挂钟 / 帧计数 / 播放位置去推算「该换子模式了」
        assertFalse(
            "歌词舞台不得按挂钟自动换子模式（⛔ System.currentTimeMillis）",
            src.contains("System.currentTimeMillis()"),
        )
        assertFalse(
            "歌词舞台不得按播放位置自动换子模式（⛔ payload.timeSec 参与模式判定）",
            Regex("""timeSec[^\n]*\b(mode|subRenderer)\b""").containsMatchIn(src),
        )
    }

    @Test
    fun `the renderer does not change the mode when the song or the line changes`() {
        val src = rendererSrc()
        // 换行 ⇒ 只应通知子渲染器重建（onLineChanged），⛔ 不得改 mode
        assertFalse(
            "换歌词行不得改变子模式（⛔ onLineChanged 里不得动 mode）",
            // ⚠️ `[^}]*` 本身就**能匹配换行**（否定字符类排除的只是 `}`）⇒ 不需要 DOT_MATCHES。
            Regex("""onLineChanged\([^)]*\)\s*\{[^}]*mode\s*=""").containsMatchIn(src),
        )
        // 切歌（songId 变化）也不得动 mode
        assertFalse(
            "切歌不得改变子模式（⛔ songId 不得参与模式判定）",
            Regex("""songId[^\n]*\b(mode|subRenderer)\b""").containsMatchIn(src),
        )
    }

    @Test
    fun `the only mode changing entry points are the manual ones`() {
        val src = rendererSrc()
        // 手动入口：setMode / nextMode / prevMode
        assertTrue("必须提供 setMode", src.contains("fun setMode("))
        assertTrue("必须提供 nextMode", src.contains("fun nextMode("))
        assertTrue("必须提供 prevMode", src.contains("fun prevMode("))
        // 切到新模式的唯一落点是 applyMode
        val applyModeCalls = Regex("""applyMode\(""").findAll(src).count()
        assertTrue("applyMode 调用点过少（切模式必须走它）：$applyModeCalls", applyModeCalls >= 2)
    }

    // ── 舞台：上下键与上下滑动必须走手动入口 ───────────────────

    @Test
    fun `the stage routes the up and down keys to the manual switch`() {
        val src = stageSrc()
        assertTrue(
            "上下键必须走 applyLyricsStageMode（⛔ 不得有别的模式切换入口）",
            Regex("""applyLyricsStageMode\(\s*[+-]1\s*\)""").containsMatchIn(src),
        )
        assertTrue(
            "上下键分支里必须调 applyLyricsStageMode",
            // ⚠️ `[^}]*` 能匹配换行 ⇒ 不需要 DOT_MATCHES。
            Regex("""Key\.DirectionUp\s*->[^}]*applyLyricsStageMode""").containsMatchIn(src) ||
                Regex("""Key\.DirectionUp[\s\S]{0,400}?applyLyricsStageMode""").containsMatchIn(src),
        )
        assertTrue(
            "下键分支里必须调 applyLyricsStageMode",
            Regex("""Key\.DirectionDown[\s\S]{0,400}?applyLyricsStageMode""").containsMatchIn(src),
        )
    }

    @Test
    fun `the stage routes the vertical swipe to the manual switch`() {
        val src = stageSrc()
        assertTrue(
            "必须有竖向滑动手势（手机上下滑切子模式）",
            src.contains("detectVerticalDragGestures("),
        )
        assertTrue(
            "竖滑必须调 applyLyricsStageMode",
            Regex("""detectVerticalDragGestures\([\s\S]{0,1200}?applyLyricsStageMode""").containsMatchIn(src),
        )
    }

    @Test
    fun `the manual switch is guarded by the lyrics stage theme`() {
        val src = stageSrc()
        assertTrue(
            "子模式切换必须被 LYRICS_STAGE 主题守卫（其它效果不许被牵连）",
            Regex("""applyLyricsStageMode[\s\S]{0,400}?VisualizerTheme\.LYRICS_STAGE""")
                .containsMatchIn(src) ||
            Regex("""VisualizerTheme\.LYRICS_STAGE[\s\S]{0,400}?applyLyricsStageMode""")
                .containsMatchIn(src),
        )
    }

    @Test
    fun `the chosen sub mode is persisted`() {
        val src = stageSrc()
        assertTrue(
            "子模式必须持久化（否则重启 App 就丢）",
            src.contains("setLyricsStageMode("),
        )
        assertTrue(
            "必须从持久化读回子模式",
            src.contains("lyricsStageMode.first()"),
        )
    }

    // ── 负向自证：坏值喂给**同一份**正向断言 ⇒ 必须挂 ─────────

    @Test
    fun `negative self proof the assertions bite`() {
        val good = "Key.DirectionUp -> { applyLyricsStageMode(+1); true }"
        val bad = "Key.DirectionUp -> { applyQianliColorVersion(+1); true }"

        val goodHits = Regex("""applyLyricsStageMode\(\s*[+-]1\s*\)""").containsMatchIn(good)
        val badHits = Regex("""applyLyricsStageMode\(\s*[+-]1\s*\)""").containsMatchIn(bad)
        assertTrue("正向断言必须咬住好值", goodHits)
        assertFalse("正向断言必须咬得住坏值", badHits)
    }
}
