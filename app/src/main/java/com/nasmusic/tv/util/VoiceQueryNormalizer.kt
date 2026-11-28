package com.nasmusic.tv.util

import java.text.Normalizer

/**
 * 语音点歌命令文本规范化。
 *
 * 只处理「命令语法」，不做搜索匹配：识别器输出先经这里变成稳定查询，再由
 * SharedSearchService 走统一的聚合 / 拼音 / 去重链路。
 */
object VoiceQueryNormalizer {

    /** 规范化后的查询最大长度，避免识别器偶发的长文本进入搜索与历史记录。 */
    const val MAX_QUERY_LENGTH = 80

    data class Result(
        val query: String,
        val matchedPrefix: String?,
        val raw: String,
    )

    /**
     * 中文命令前缀。故意只匹配一次：`播放播放稻香` 只去掉第一个 `播放`，
     * `请播放 我想听 晴天` 只去掉 `请播放`，避免把用户真正想搜的词也吞掉。
     * 裸「听」/「play」必须后跟空白才算命令，避免「听海」「player」被误截断；
     * 「听 稻香」「play 稻香」的命令语义保持不变。
     */
    private val COMMAND_PREFIX = Regex(
        "^(" +
            "请播放|播放一下|来一首|放一首|我想听|听一下|播放|听(?=\\s)|" +
            "please\\s+play|i\\s+want\\s+to\\s+listen\\s+to|let\\s+me\\s+hear|listen\\s+to|play(?=\\s)" +
            ")\\s*",
        RegexOption.IGNORE_CASE,
    )

    /** 语音识别常见的装饰性标点；统一折成空格，保留查询词之间的边界。 */
    private val DECORATIVE_PUNCTUATION = Regex(
        "[，。！？、；：,.!?;:…“”‘’\"'《》〈〉「」『』（）()\\[\\]【】{}<>]+",
    )

    private val WHITESPACE = Regex("\\s+")

    fun normalize(raw: String): String = normalizeDetailed(raw).query

    fun normalizeDetailed(raw: String): Result {
        val compatible = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val cleaned = DECORATIVE_PUNCTUATION.replace(compatible, " ")
            .replace(WHITESPACE, " ")
            .trim()

        val prefix = COMMAND_PREFIX.find(cleaned)?.value?.trim()
        val withoutPrefix = if (prefix != null) cleaned.removeRange(0, prefix.length).trim() else cleaned

        // 「艺术家的歌曲」是显式语法，不是简单删前缀：只有命中命令前缀时才拆，
        // 避免把普通搜索词 `爱的故事` 误改成 `爱 故事`。
        val query = if (prefix != null) splitArtistSong(withoutPrefix) else withoutPrefix

        return Result(
            query = query.take(MAX_QUERY_LENGTH).trim(),
            matchedPrefix = prefix,
            raw = raw,
        )
    }

    private fun splitArtistSong(value: String): String {
        val separator = value.indexOf('的')
        if (separator <= 0 || separator == value.lastIndex) return value
        val artist = value.substring(0, separator).trim()
        val song = value.substring(separator + 1).trim()
        if (artist.isBlank() || song.isBlank()) return value
        return "$artist $song"
    }
}
