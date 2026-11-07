package com.nasmusic.tv.visualizer.renderers.lyricsStage

import com.nasmusic.tv.data.model.LyricsLine
import com.nasmusic.tv.data.model.WordTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E53「歌词舞台」—— 歌词模型转换（`LyricsLine` → `LyricLine`）的门禁测试。
 */
class LyricsStageBridgeTest {

    @Test
    fun `an empty lyric list converts to an empty line list`() {
        assertTrue(LyricsStageBridge.buildLines(emptyList()).isEmpty())
    }

    @Test
    fun `the line start time comes from the millisecond timestamp`() {
        val lines = LyricsStageBridge.buildLines(
            listOf(LyricsLine(time = 5_000L, text = "你好"))
        )
        assertEquals(5.0, lines.single().startTime, 1e-9)
    }

    @Test
    fun `the line end time comes from the next line`() {
        val lines = LyricsStageBridge.buildLines(
            listOf(
                LyricsLine(time = 5_000L, text = "你好"),
                LyricsLine(time = 8_000L, text = "世界"),
            )
        )
        assertEquals(5.0, lines[0].startTime, 1e-9)
        assertEquals(8.0, lines[0].endTime, 1e-9)
        assertEquals(8.0, lines[1].startTime, 1e-9)
        // 最后一行没有下一行 ⇒ 用 +3 秒兜底
        assertEquals(11.0, lines[1].endTime, 1e-9)
    }

    @Test
    fun `the words are split on the cjk and latin boundaries`() {
        val lines = LyricsStageBridge.buildLines(
            listOf(LyricsLine(time = 0L, text = "你好world"))
        )
        val words = lines.single().words
        assertEquals(listOf("你", "好", "world"), words.map { it.text })
    }

    @Test
    fun `without word timestamps the words share the line span evenly`() {
        val lines = LyricsStageBridge.buildLines(
            listOf(LyricsLine(time = 0L, text = "一二三四", wordTimestamps = emptyList()))
        )
        val words = lines.single().words
        assertEquals(4, words.size)
        assertEquals(0.0, words[0].startTime, 1e-9)
        // 行是 0ms→3000ms（末行无下一行 ⇒ +3 秒兜底）⇒ 4 个词均分 3 秒
        assertEquals(3.0, words[3].endTime, 1e-9)
        // 均分 ⇒ 相邻词首尾相接
        for (i in 0 until words.size - 1) {
            assertEquals(words[i].endTime, words[i + 1].startTime, 1e-9)
        }
    }

    @Test
    fun `the word timestamps define the words and their times`() {
        val lines = LyricsStageBridge.buildLines(
            listOf(
                LyricsLine(
                    time = 0L,
                    text = "曾经真的以为人生就这样了",
                    wordTimestamps = listOf(
                        WordTimestamp("曾经", 0L),
                        WordTimestamp("真的", 400L),
                        WordTimestamp("以为", 900L),
                    ),
                ),
                // 下一行只用来给第一行一个确定的结束时间
                LyricsLine(time = 2_000L, text = "下一行"),
            )
        )
        val line = lines[0]
        // 词表来自解析器：一个时间戳 ⇒ 一个词（不是按字数切）
        assertEquals(listOf("曾经", "真的", "以为"), line.words.map { it.text })
        // 开始时间来自解析器自己的时间戳
        assertEquals(0.0, line.words[0].startTime, 1e-9)
        assertEquals(0.4, line.words[1].startTime, 1e-9)
        assertEquals(0.9, line.words[2].startTime, 1e-9)
        // 词的结束时间取下一个词的开始时间
        assertEquals(0.4, line.words[0].endTime, 1e-9)
        assertEquals(0.9, line.words[1].endTime, 1e-9)
        // 最后一个词取行结束时间（= 下一行的开始时间）
        assertEquals(2.0, line.words[2].endTime, 1e-9)
    }

    @Test
    fun `fewer word timestamps than characters do not expand into characters`() {
        val lines = LyricsStageBridge.buildLines(
            listOf(
                LyricsLine(
                    time = 0L,
                    text = "曾经真的以为人生就这样了",
                    wordTimestamps = listOf(
                        WordTimestamp("曾经", 0L),
                        WordTimestamp("真的", 400L),
                    ),
                )
            )
        )
        val words = lines.single().words
        // 只有 2 条时间戳 ⇒ 只有 2 个词（不按字数展开）
        assertEquals(2, words.size)
        assertEquals(listOf("曾经", "真的"), words.map { it.text })
        assertEquals(0.0, words[0].startTime, 1e-9)
        assertEquals(0.4, words[1].startTime, 1e-9)
        // 最后一个词取行结束时间（末行无下一行 ⇒ +3 秒兜底）
        assertEquals(3.0, words[1].endTime, 1e-9)
    }

    @Test
    fun `the unsorted word timestamps are sorted before pairing`() {
        val lines = LyricsStageBridge.buildLines(
            listOf(
                LyricsLine(
                    time = 0L,
                    text = "曾经真的以为",
                    wordTimestamps = listOf(
                        WordTimestamp("以为", 900L),
                        WordTimestamp("曾经", 0L),
                        WordTimestamp("真的", 400L),
                    ),
                )
            )
        )
        val words = lines.single().words
        // 按开始时间排序，且每个词都带着**自己**那条时间戳的文本
        assertEquals(listOf("曾经", "真的", "以为"), words.map { it.text })
        assertEquals(0.0, words[0].startTime, 1e-9)
        assertEquals(0.4, words[1].startTime, 1e-9)
        assertEquals(0.9, words[2].startTime, 1e-9)
        // 开始时间单调不减
        for (i in 0 until words.size - 1) {
            assertTrue(
                "start=${words[i + 1].startTime} < ${words[i].startTime}",
                words[i + 1].startTime >= words[i].startTime,
            )
        }
    }

    @Test
    fun `the blank word timestamp entries are skipped`() {
        val lines = LyricsStageBridge.buildLines(
            listOf(
                LyricsLine(
                    time = 0L,
                    text = "曾经真的",
                    wordTimestamps = listOf(
                        WordTimestamp("", 0L),
                        WordTimestamp("曾经", 100L),
                        WordTimestamp("   ", 200L),
                        WordTimestamp("真的", 300L),
                    ),
                )
            )
        )
        val words = lines.single().words
        assertEquals(listOf("曾经", "真的"), words.map { it.text })
        assertEquals(0.1, words[0].startTime, 1e-9)
        assertEquals(0.3, words[1].startTime, 1e-9)
    }

    @Test
    fun `the word times are clamped to the line span`() {
        val lines = LyricsStageBridge.buildLines(
            listOf(
                LyricsLine(
                    time = 1_000L,
                    text = "一二",
                    wordTimestamps = listOf(
                        // 早于行首 ⇒ 夹到行首
                        WordTimestamp("一", 0L),
                        // 晚于行尾 ⇒ 夹到行尾
                        WordTimestamp("二", 9_999L),
                    ),
                ),
                LyricsLine(time = 3_000L, text = "下一行"),
            )
        )
        val words = lines[0].words
        assertEquals(1.0, words[0].startTime, 1e-9)
        assertEquals(3.0, words[0].endTime, 1e-9)
        assertEquals(3.0, words[1].startTime, 1e-9)
        assertEquals(3.0, words[1].endTime, 1e-9)
    }

    @Test
    fun `a word end time never precedes its start time`() {
        val lines = LyricsStageBridge.buildLines(
            listOf(
                LyricsLine(
                    time = 0L,
                    text = "a b",
                    wordTimestamps = listOf(
                        WordTimestamp("a", 1_000L),
                        WordTimestamp("b", 500L),
                    ),
                )
            )
        )
        val words = lines.single().words
        // 乱序的条目先排序 ⇒ 两个词都保留，且按时间先后排列
        assertEquals(listOf("b", "a"), words.map { it.text })
        assertEquals(0.5, words[0].startTime, 1e-9)
        assertEquals(1.0, words[1].startTime, 1e-9)
        for (word in words) {
            assertTrue(
                "start=${word.startTime} end=${word.endTime}",
                word.endTime >= word.startTime,
            )
        }
    }

    @Test
    fun `the converted lines carry derived render hints`() {
        val lines = LyricsStageBridge.buildLines(
            listOf(
                // 第一行只持续 50ms ⇒ < 100ms ⇒ micro 档
                LyricsLine(time = 0L, text = "短"),
                LyricsLine(time = 50L, text = "长"),
            )
        )
        assertTrue("短行应是 micro 档", lines[0].renderHints!!.timingClass == "micro")
        assertTrue("长行应是 normal 档", lines[1].renderHints!!.timingClass == "normal")
    }

    @Test
    fun `a blank line has no words`() {
        val lines = LyricsStageBridge.buildLines(listOf(LyricsLine(time = 0L, text = "   ")))
        assertTrue(lines.single().words.isEmpty())
    }

    @Test
    fun `the payload exposes the current and the next line`() {
        val lines = LyricsStageBridge.buildLines(
            listOf(
                LyricsLine(time = 0L, text = "一"),
                LyricsLine(time = 3_000L, text = "二"),
            )
        )
        val payload = LyricsStagePayload(lines, 0, 1.0)
        assertEquals("一", payload.current!!.fullText)
        assertEquals("二", payload.next!!.fullText)
        assertTrue(payload.hasLyrics)
        // 索引越界时 current / next 为 null，但整曲仍非空
        val outOfRange = LyricsStagePayload(lines, 5, 1.0)
        assertTrue(outOfRange.hasLyrics)
        assertEquals(null, outOfRange.current)
        assertEquals(null, outOfRange.next)
    }
}
