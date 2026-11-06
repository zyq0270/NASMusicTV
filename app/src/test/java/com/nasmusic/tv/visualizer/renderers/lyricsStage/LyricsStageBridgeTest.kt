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
    fun `the word timestamps are paired with the words by position`() {
        val lines = LyricsStageBridge.buildLines(
            listOf(
                LyricsLine(
                    time = 0L,
                    text = "你 好",
                    wordTimestamps = listOf(
                        WordTimestamp("你", 0L),
                        WordTimestamp("好", 500L),
                    ),
                )
            )
        )
        val words = lines.single().words
        assertEquals(2, words.size)
        assertEquals(0.0, words[0].startTime, 1e-9)
        assertEquals(0.5, words[1].startTime, 1e-9)
        // 词的结束时间取下一个词的开始时间
        assertEquals(0.5, words[0].endTime, 1e-9)
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
        for (word in lines.single().words) {
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
