package com.nasmusic.tv.visualizer.renderers.lyricsStage

import com.nasmusic.tv.data.model.LyricsLine
import com.nasmusic.tv.data.model.WordTimestamp

/**
 * 歌词舞台每帧拿到的歌词数据。
 *
 * ⛔ **刻意不放进 `RenderContext`**：`RenderContext.update()` 有 16 个位置参数，
 * 且本仓库的覆盖层（E51/E52 私有分支）会遮蔽同名文件 —— 改它会让本地构建与 CI 行为分叉。
 * 改为由 `VisualizerStage` 直接推给 `LyricsStageRenderer`（见 `LyricsStageRenderer.applyLyrics`）。
 *
 * ⚠️ **可变类而非 data class**：舞台每帧只改三个字段、不新建对象 ⇒ 逐帧零分配。
 */
class LyricsStagePayload(
    /** 整曲歌词行（Folia 的 `lines`），FULL_VIEW 子模式要把它排成整版版面 */
    var lines: List<LyricLine> = emptyList(),
    /** 当前行索引（-1 = 无） */
    var currentIndex: Int = -1,
    /** 播放进度（秒） */
    var timeSec: Double = 0.0,
) {
    val current: LyricLine? get() = lines.getOrNull(currentIndex)
    val next: LyricLine? get() = lines.getOrNull(currentIndex + 1)
    val hasLyrics: Boolean get() = lines.isNotEmpty()

    companion object {
        val Empty = LyricsStagePayload()
    }
}

/**
 * NASMusicTV 的歌词模型 → 歌词舞台内部模型的转换。
 *
 * 移植自 Folia（AGPL-3.0）的歌词管线约定：
 *  - Folia 的 `Line.words[]` 带 startTime / endTime；
 *  - 本仓的 `LyricsLine` 只有 `time`（行首 ms）与 `wordTimestamps`（**绝对 ms**，无结束时间）。
 *  ⇒ 词的结束时间取「下一个词的开始时间」，最后一个词取「行结束时间」；
 *     这与 Folia `buildLineRenderHints` 里 `getLastWordEndTime` 的兜底一致。
 */
object LyricsStageBridge {

    /**
     * 把整曲 `LyricsLine` 列表转成歌词舞台的 `LyricLine` 列表。
     *
     * @param lyrics NASMusicTV 歌词行
     * @return 与 `lyrics` 一一对应的 `LyricLine` 列表（行结束时间用下一行的开始时间）
     */
    fun buildLines(lyrics: List<LyricsLine>): List<LyricLine> {
        if (lyrics.isEmpty()) return emptyList()
        return lyrics.mapIndexed { index, line ->
            buildLine(line, lyrics.getOrNull(index + 1)?.time)
        }
    }

    /** 单行转换。`nextLineTimeMs` 为 null 时用「行首 + 3 秒」兜底。 */
    fun buildLine(line: LyricsLine, nextLineTimeMs: Long?): LyricLine {
        val startTime = line.time / 1000.0
        val endTimeMs = nextLineTimeMs ?: (line.time + DEFAULT_LINE_DURATION_MS)
        val endTime = endTimeMs / 1000.0

        val words = buildWords(line.text, line.wordTimestamps, startTime, endTime)

        val lyricLine = LyricLine(
            fullText = line.text,
            startTime = startTime,
            endTime = endTime,
            words = words,
            isChorus = false,
        )

        // renderHints 由歌词管线决定；这里按 Folia 的 buildLineRenderHints 推导
        return lyricLine.copy(renderHints = LyricRenderHints.buildLineRenderHints(lyricLine))
    }

    /**
     * 把行文本切成词，并按 `wordTimestamps` 给每个词定时。
     *
     *  - 有逐词时间戳：按顺序一一对应（词数与时间戳数不一致时按位置取，越界则均分兜底）
     *  - 无逐词时间戳：按行级时间在词之间均分
     */
    private fun buildWords(
        text: String,
        wordTimestamps: List<WordTimestamp>,
        lineStart: Double,
        lineEnd: Double,
    ): List<LyricWord> {
        if (text.isBlank()) return emptyList()

        // 词切分：连续的非空白字符算一个词（中日韩逐字、拉丁按词）
        val tokens = TOKEN_REGEX.findAll(text).map { it.value }.toList()
        if (tokens.isEmpty()) return emptyList()

        val lineDuration = maxOf(lineEnd - lineStart, 0.05)

        if (wordTimestamps.isEmpty()) {
            // 行级均分
            val unit = lineDuration / tokens.size
            return tokens.mapIndexed { index, token ->
                LyricWord(
                    text = token,
                    startTime = lineStart + unit * index,
                    endTime = lineStart + unit * (index + 1),
                )
            }
        }

        // 逐词时间戳：与 token 按位置配对
        val starts = wordTimestamps.map { it.startMs / 1000.0 }
        return tokens.mapIndexed { index, token ->
            val start = starts.getOrNull(index)
                ?: (lineStart + lineDuration * index / tokens.size)
            val nextStart = starts.getOrNull(index + 1)
                ?: lineEnd
            LyricWord(
                text = token,
                startTime = start.coerceIn(lineStart, lineEnd),
                endTime = nextStart.coerceIn(start, lineEnd),
            )
        }
    }

    /** 词切分正则：中日韩逐字、拉丁按连续字母数字 */
    private val TOKEN_REGEX = Regex("[\\u4e00-\\u9fff\\u3040-\\u30ff\\uac00-\\ud7af]|[A-Za-z0-9']+")

    private const val DEFAULT_LINE_DURATION_MS = 3000L
}
