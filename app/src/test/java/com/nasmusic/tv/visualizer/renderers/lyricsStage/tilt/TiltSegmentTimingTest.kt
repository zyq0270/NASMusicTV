package com.nasmusic.tv.visualizer.renderers.lyricsStage.tilt

import com.nasmusic.tv.data.model.LyricsLine
import com.nasmusic.tv.data.model.WordTimestamp
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricsStageBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E53「歌词舞台」—— 「倾斜律动」(TILT_PULSE) **黑屏**的根因门禁。
 *
 * ## 症状
 * 真机上「倾斜律动」子模式整屏黑、一个字都不出（其它子模式正常）。
 *
 * ## 根因
 * `LyricsStageBridge` 曾把整行**按字**切碎，再把解析器的**词级**时间戳**按位置**配给这些字
 * ⇒ 词的时间全乱了。
 * 而 TILT 的段起始时间来自 `TiltLayout.findSegmentWordRange(...)` 命中的**第一个词**的
 * `startTime` ⇒ 段的时间也是乱的 ⇒ `computeVisibleIndex(...)` 里的
 * `timeSec >= segmentStarts[i] - LEAD` 永远不成立 ⇒ `visibleIndex = -1`
 * ⇒ 每段的 alpha 恒为 0 ⇒ **黑屏**。
 *
 * ## 这条门禁钉住什么
 * 用**真实的词级时间戳**走一遍「行 → 词 → 段 → 段起始时间」，
 * 断言段的起始时间**落在该行的时间区间内且单调不减** ⇒ 段一定能被看见。
 */
class TiltSegmentTimingTest {

    /** 一句真实的中文歌词（词级时间戳，来自本仓的 `WordTimestamp`） */
    private val line = LyricsLine(
        time = 10_000L,
        text = "曾经真的以为人生就这样了",
        wordTimestamps = listOf(
            WordTimestamp("曾经", 10_000L),
            WordTimestamp("真的", 10_400L),
            WordTimestamp("以为", 10_900L),
            WordTimestamp("人生", 11_400L),
            WordTimestamp("就这样了", 11_900L),
        ),
    )

    @Test
    fun `the words keep the parser's own granularity and timing`() {
        val lyricLine = LyricsStageBridge.buildLines(listOf(line)).single()

        // ⛔ 必须是**解析器的词**，不是被按字切碎的
        assertEquals(
            listOf("曾经", "真的", "以为", "人生", "就这样了"),
            lyricLine.words.map { it.text },
        )
        assertEquals(10.0, lyricLine.words[0].startTime, 1e-9)
        assertEquals(10.4, lyricLine.words[1].startTime, 1e-9)
        // 词的结束时间 = 下一个词的开始时间
        assertEquals(10.4, lyricLine.words[0].endTime, 1e-9)
        assertEquals(10.9, lyricLine.words[1].endTime, 1e-9)
    }

    @Test
    fun `every segment of the line lands inside the line's time span`() {
        val lyricLine = LyricsStageBridge.buildLines(listOf(line)).single()
        val lineStart = lyricLine.startTime
        val lineEnd = lyricLine.endTime

        // 用真实的时间戳把整行按词切成若干段（模拟 TILT 的 SentenceLayout 分段）
        val segments = lyricLine.words.map { it.text }

        var previousStart = Double.NEGATIVE_INFINITY
        for (segmentText in segments) {
            val offset = lyricLine.fullText.indexOf(segmentText)
            val (startWordIndex, endWordIndex) = TiltLayout.findSegmentWordRange(
                charOffset = offset,
                segmentText = segmentText,
                fullText = lyricLine.fullText,
                words = lyricLine.words,
            )

            // 段必须命中至少一个词（否则段起始时间会退化成整行起点，段就分不出来了）
            assertTrue(
                "段「$segmentText」没有命中任何词",
                startWordIndex < endWordIndex,
            )

            val segStart = lyricLine.words[startWordIndex].startTime
            val segEnd = lyricLine.words[endWordIndex - 1].endTime

            // 段的时间必须落在行内
            assertTrue(
                "段「$segmentText」起始时间 $segStart 不在 [$lineStart, $lineEnd]",
                segStart >= lineStart - 1e-9 && segStart <= lineEnd + 1e-9,
            )
            assertTrue(
                "段「$segmentText」结束时间 $segEnd 不在 [$lineStart, $lineEnd]",
                segEnd >= lineStart - 1e-9 && segEnd <= lineEnd + 1e-9,
            )
            // 段起始时间单调不减 ⇒ `computeVisibleIndex` 的推进不会倒退
            assertTrue(
                "段「$segmentText」起始时间 $segStart 比上一段 $previousStart 小",
                segStart >= previousStart - 1e-9,
            )
            previousStart = segStart
        }
    }

    @Test
    fun `the segment timing advances while the line is playing`() {
        val lyricLine = LyricsStageBridge.buildLines(listOf(line)).single()
        val segmentStarts = lyricLine.words.map { it.startTime }

        // 模拟 `computeVisibleIndex`：当前时间越过段的起始时间 ⇒ 该段可见
        fun visibleIndexAt(timeSec: Double): Int {
            var target = -1
            for (i in segmentStarts.indices) {
                if (timeSec >= segmentStarts[i] - 0.25) target = i
            }
            return target
        }

        // 行开始之前 ⇒ 没有任何段可见（这是对的）
        assertEquals(-1, visibleIndexAt(lyricLine.startTime - 1.0))
        // 行开始之后 ⇒ 至少第一段可见（**这正是黑屏时失败的那一步**）
        assertTrue(
            "行开始后 0.1s 仍没有任何段可见 ⇒ 会黑屏",
            visibleIndexAt(lyricLine.startTime + 0.1) >= 0,
        )
        // 时间推进 ⇒ 可见段数单调增加
        assertTrue(
            visibleIndexAt(lyricLine.startTime + 1.0) > visibleIndexAt(lyricLine.startTime + 0.1),
        )
    }
}
