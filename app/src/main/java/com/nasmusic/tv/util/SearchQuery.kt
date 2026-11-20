package com.nasmusic.tv.util

/**
 * 搜索关键词的形态判定与分词（**唯一真相源**）
 *
 * 为什么必须集中：拼音分支的入口判据此前散在三处（`SearchAggregator` 自己写了一遍、
 * `SearchViewModel` 求值百度本地索引时没判、`BaiduFileIndexCache` 只认 pinyinMatch 布尔），
 * 口径一漂移就会出现「本地按拼音筛、服务端却没搜」这类**静默丢结果**——
 * 用户看到 0 条，日志里也没有任何异常。
 *
 * 约定：
 * - 「拼音/英文输入」= 不含中文、含字母、其余字符只有字母/数字/空白。
 *   ⚠️ 纯英文单词（`adele`）也落进这个分支，所以消费方**不能用它当「一定不是英文」的判据**，
 *   只能当成「服务端大概率不认这串输入」的提示（例如客户端补拼音匹配）。
 * - [tokens] 是**多词查询**的唯一分词口径（空白按 L09 口径折叠：含全角空格 U+3000、
 *   不换行空格 U+00A0）。
 */
object SearchQuery {

    /** 与 `SearchAggregator.WHITESPACE_RUN` 同口径（L09）：连续空白折叠 + 全角/不换行空格 */
    private val WHITESPACE_RUN = Regex("[\\s\\u00A0\\u3000]+")

    /**
     * 是否是「拼音 / 英文」形态的输入。
     *
     * @return 不含中文、至少含一个字母、且所有字符都是字母/数字/空白
     */
    fun isPinyinLike(raw: String): Boolean {
        val q = raw.trim()
        if (q.isEmpty()) return false
        if (q.any { it.code in 0x4E00..0x9FFF }) return false
        return q.any { it.isLetter() } && q.all { it.isLetterOrDigit() || it.isWhitespace() }
    }

    /** 按空白切词（小写 + 去空串）。空查询返回空列表。 */
    fun tokens(raw: String): List<String> =
        raw.trim().lowercase().split(WHITESPACE_RUN).filter { it.isNotEmpty() }
}
