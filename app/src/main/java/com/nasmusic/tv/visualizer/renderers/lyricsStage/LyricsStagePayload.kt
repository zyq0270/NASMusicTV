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
 *
 *  - 词表来源：有 `wordTimestamps` 时以**解析器的词表**为准（一条时间戳 ⇒ 一个词，
 *    词文本与词开始时间都取自条目本身）；没有时才把整行切成词并按行级时间均分。
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
     * 生成一行歌词的词表（文本 + 起止时间）。
     *
     * ⚠️ **有逐词时间戳时以解析器的词表为准，不再重新切分整行**（2026-11-07 修复）：
     * 本仓的 `WordTimestamp` **自带词文本**，解析器给的是**词**级时间戳
     * （例如「曾经」@0ms、「真的」@400ms、「以为」@900ms），不是字级。
     *
     * ⛔ 旧实现是「把整行切成单字，再按位置与时间戳一一配对」——
     *    词数（字数）多于时间戳条数时，后面的词只能拿均分兜底，
     *    词文本也被切成了单字 ⇒ 时间全错、文本全错。
     *    下游「倾斜律动」（`TiltLayout.findSegmentWordRange` → `segWords.first().startTime`）
     *    用 `words` 反查每段的起止时间 ⇒ 起点恒为 0 ⇒ `computeVisibleIndex` 永不推进
     *    ⇒ 每段 alpha 恒为 0 ⇒ **全屏黑**。
     *
     *  - 有逐词时间戳：一条时间戳 ⇒ 一个词；文本取条目自带的 `word`，
     *    开始时间取条目自带的 `startMs`，结束时间取下一个词的开始时间（最后一个词取行结束时间）
     *  - 无逐词时间戳：按行级时间在切出的词之间均分（没有时间信息时的兜底路径）
     */
    private fun buildWords(
        text: String,
        wordTimestamps: List<WordTimestamp>,
        lineStart: Double,
        lineEnd: Double,
    ): List<LyricWord> {
        // 有逐词时间戳 ⇒ 解析器的词表是权威的（词文本与词时间都自带）
        if (wordTimestamps.isNotEmpty()) {
            return buildWordsFromTimestamps(wordTimestamps, lineStart, lineEnd)
        }

        // 无逐词时间戳 ⇒ 行级兜底：把行切成词，在行内均分
        if (text.isBlank()) return emptyList()

        // 词切分：连续的非空白字符算一个词（中日韩逐字、拉丁按词）
        val tokens = TOKEN_REGEX.findAll(text).map { it.value }.toList()
        if (tokens.isEmpty()) return emptyList()

        val lineDuration = maxOf(lineEnd - lineStart, 0.05)
        val unit = lineDuration / tokens.size
        return tokens.mapIndexed { index, token ->
            LyricWord(
                text = token,
                startTime = lineStart + unit * index,
                endTime = lineStart + unit * (index + 1),
            )
        }
    }

    /**
     * 由解析器的逐词时间戳构建词表。
     *
     *  - 时间戳先按 `startMs` 排序（解析器不保证顺序）
     *  - 词文本取条目自带的 `word`（去首尾空白）；空白条目跳过
     *  - 词的结束时间取**下一个词**的开始时间；最后一个词取行结束时间
     *  - 防御：丢弃时间戳不单调递增的条目（排序后正常数据不会命中）
     *  - 所有时间都夹到 `[lineStart, lineEnd]`，且保证 `endTime >= startTime`
     */
    private fun buildWordsFromTimestamps(
        wordTimestamps: List<WordTimestamp>,
        lineStart: Double,
        lineEnd: Double,
    ): List<LyricWord> {
        // 行窗口可能被倒序的时间轴弄反 ⇒ 先规整成 lo <= hi，避免 coerceIn 抛异常
        val lo = minOf(lineStart, lineEnd)
        val hi = maxOf(lineStart, lineEnd)

        val sorted = wordTimestamps.sortedBy { it.startMs }

        // 先挑出「有文本 + 时间戳单调递增」的条目
        val entries = ArrayList<WordTimestamp>(sorted.size)
        var lastStartMs = Long.MIN_VALUE
        for (entry in sorted) {
            if (entry.word.isBlank()) continue
            // 防御：时间戳不是单调递增 ⇒ 丢弃（排序后正常数据不会命中）
            if (entry.startMs < lastStartMs) continue
            lastStartMs = entry.startMs
            entries.add(entry)
        }
        if (entries.isEmpty()) return emptyList()

        val words = ArrayList<LyricWord>(entries.size)
        for (i in entries.indices) {
            val start = (entries[i].startMs / 1000.0).coerceIn(lo, hi)
            val nextStart = if (i + 1 < entries.size) {
                (entries[i + 1].startMs / 1000.0).coerceIn(start, hi)
            } else {
                hi
            }
            words.add(
                LyricWord(
                    text = entries[i].word.trim(),
                    startTime = start,
                    endTime = nextStart,
                )
            )
        }
        return words
    }

    /** 词切分正则：中日韩逐字、拉丁按连续字母数字 */
    private val TOKEN_REGEX = Regex("[\\u4e00-\\u9fff\\u3040-\\u30ff\\uac00-\\ud7af]|[A-Za-z0-9']+")

    private const val DEFAULT_LINE_DURATION_MS = 3000L
}
