package com.nasmusic.tv.visualizer.renderers.lyricsStage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E53「歌词舞台」—— 子模式枚举与桥接转换的门禁测试。
 */
class LyricsStageModeTest {

    @Test
    fun `the stage exposes exactly five lyric sub modes`() {
        assertEquals(5, LyricsStageMode.entries.size)
    }

    @Test
    fun `the sub mode order is dot matrix glow line full view tilt partita`() {
        assertEquals(
            listOf(
                LyricsStageMode.DOT_MATRIX,
                LyricsStageMode.GLOW_LINE,
                LyricsStageMode.FULL_VIEW,
                LyricsStageMode.TILT_PULSE,
                LyricsStageMode.PARTITA,
            ),
            LyricsStageMode.entries.toList(),
        )
    }

    @Test
    fun `next cycles through every sub mode and wraps back`() {
        var mode = LyricsStageMode.DOT_MATRIX
        val visited = mutableListOf(mode)
        repeat(4) {
            mode = LyricsStageMode.next(mode)
            visited.add(mode)
        }
        assertEquals(LyricsStageMode.entries.toList(), visited)
        assertEquals(LyricsStageMode.DOT_MATRIX, LyricsStageMode.next(mode))
    }

    @Test
    fun `prev is the inverse of next and wraps back`() {
        var mode = LyricsStageMode.DOT_MATRIX
        repeat(5) {
            assertEquals(mode, LyricsStageMode.prev(LyricsStageMode.next(mode)))
            mode = LyricsStageMode.next(mode)
        }
        assertEquals(LyricsStageMode.PARTITA, LyricsStageMode.prev(LyricsStageMode.DOT_MATRIX))
    }

    @Test
    fun `fromOrdinal falls back to the default for out of range values`() {
        assertEquals(LyricsStageMode.DOT_MATRIX, LyricsStageMode.fromOrdinal(0))
        assertEquals(LyricsStageMode.PARTITA, LyricsStageMode.fromOrdinal(4))
        assertEquals(LyricsStageMode.Default, LyricsStageMode.fromOrdinal(5))
        assertEquals(LyricsStageMode.Default, LyricsStageMode.fromOrdinal(-1))
    }

    @Test
    fun `every sub mode points at a string resource`() {
        assertTrue(LyricsStageMode.entries.none { it.displayNameRes == 0 })
    }

    @Test
    fun `the display name resources are unique`() {
        val ids = LyricsStageMode.entries.map { it.displayNameRes }
        assertEquals(ids.size, ids.distinct().size)
    }

    /**
     * ⛔ **双语字符串门禁**：缺 `values-en` 的 key **编译得过**、**运行期才崩**
     * （与 `VisualizerThemeStringsGateTest` 同一个坑）⇒ 这里逐个 key 核对两侧都存在。
     */
    @Test
    fun `every sub mode display name exists in both chinese and english`() {
        val zh = readRes("values/strings.xml")
        val en = readRes("values-en/strings.xml")
        for (mode in LyricsStageMode.entries) {
            val key = resName(mode.displayNameRes)
            assertTrue("values/strings.xml 缺 $key", zh.contains("name=\"$key\""))
            assertTrue("values-en/strings.xml 缺 $key", en.contains("name=\"$key\""))
        }
    }

    private fun resName(id: Int): String =
        com.nasmusic.tv.R.string::class.java.fields
            .first { it.getInt(null) == id }
            .name

    private fun readRes(rel: String): String {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        val dir = candidates.first { it.isDirectory }
        val f = File(dir, rel)
        assertTrue("找不到资源文件：$rel（工作目录 ${File("").absolutePath}）", f.isFile)
        return f.readText().replace("\r\n", "\n")
    }
}
