package com.nasmusic.tv.util

import com.github.promeg.pinyinhelper.Pinyin
import java.util.Collections
import java.util.LinkedHashMap

/**
 * 拼音匹配工具（**搜索匹配的唯一真相源**）
 *
 * 将中文文本转换为拼音（"周杰伦" → "zhoujielun" / "zjl"），[matches] 同时支持：
 * 1. 直接子串匹配（中文 / 英文 / 文件名）
 * 2. 拼音全拼匹配（"zhoujielun" → 周杰伦）
 * 3. 拼音首字母匹配（"zjl" → 周杰伦）
 *
 * ⛔ 历史上这里只有「子串 + 首字母」，另一套 `PinyinMatcher` 才有全拼，两套能力不等价，
 * 于是「聚合器采集阶段用这里先筛一遍」会把全拼命中提前裁掉（v2.39.26 修复的 P1 缺陷，§10.417）。
 * 现在全拼/首字母/子串三种能力**只在这一个类里实现**，其它调用方一律直接用它，
 * 不要再另写一套匹配器。
 *
 * 使用 TinyPinyin 库（不依赖 ICU），兼容 API 22+。
 */
object PinyinUtils {

    /**
     * 拼音计算结果缓存（有界 LRU）。
     *
     * 列表过滤（LibraryScreen）、搜索聚合、网盘索引搜索等场景会对同一批歌名/歌手反复调用
     * toPinyin / toPinyinInitials，而 TinyPinyin 逐字符转换有一定开销。
     * 输入均为短文本（歌名/歌手/专辑），条目数有界，内存占用可忽略。
     */
    private const val MAX_PINYIN_CACHE = 4096
    private val pinyinCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MAX_PINYIN_CACHE
        }
    )

    /**
     * 首字母缓存。
     *
     * ⚠️ 此前声明了却**从未被读写**（§10.89.3 声称「缓存覆盖全部调用方」只做了一半），
     * 而首字母恰恰是最热的一条路径（列表过滤 + 每次搜索的精细过滤都会走）。
     */
    private val initialsCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MAX_PINYIN_CACHE
        }
    )

    /**
     * 获取文本的拼音全拼。
     * 中文字符转换为完整拼音（"周杰伦" → "zhoujielun"），
     * 英文字母和数字原样保留（小写），拼音之间无分隔符。
     */
    fun toPinyin(text: String): String {
        if (text.isBlank()) return ""
        pinyinCache[text]?.let { return it }
        val result = buildString {
            for (c in text) {
                if (c.code in 0x4E00..0x9FFF) {
                    // CJK 统一表意文字：取完整拼音
                    val py = Pinyin.toPinyin(c)
                    if (py.isNotEmpty()) {
                        append(py.lowercase())
                    }
                } else if (c.isLetterOrDigit()) {
                    append(c.lowercaseChar())
                }
                // 其他字符（空格、标点等）跳过
            }
        }
        pinyinCache[text] = result
        return result
    }

    /**
     * 获取文本的拼音首字母。
     * 中文字符转换为拼音首字母（"你好" → "nh"），
     * 英文字母和数字原样保留（小写）。
     */
    fun toPinyinInitials(text: String): String {
        if (text.isBlank()) return ""
        initialsCache[text]?.let { return it }
        val result = buildString {
            for (c in text) {
                if (c.code in 0x4E00..0x9FFF) {
                    // CJK 统一表意文字：取拼音首字母
                    val py = Pinyin.toPinyin(c)
                    if (py.isNotEmpty()) {
                        append(py.first().lowercaseChar())
                    }
                } else if (c.isLetterOrDigit()) {
                    append(c.lowercaseChar())
                }
                // 其他字符（空格、标点等）跳过
            }
        }
        initialsCache[text] = result
        return result
    }

    /**
     * 判断**单段文本**是否匹配查询词（宽口径，三条路径任一命中即可）。
     * 匹配规则：
     * 1. 文本本身包含查询子串（不区分大小写）
     * 2. 文本的拼音全拼包含查询子串（"zhoujielun" → 周杰伦）
     * 3. 文本的拼音首字母包含查询子串（"zjl" → 周杰伦）
     *
     * ⚠️ 这里的 [query] 必须是**单个词**：多词查询（"周杰伦 晴天"）请用 [matchesQuery]，
     * 否则整串既不是子串也不是合法拼音串，必然判否。
     */
    fun matches(text: String, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        val t = text.trim().lowercase()

        // 1. 直接子串匹配（含中文匹配）
        if (t.contains(q)) return true

        // 2. 含中文/标点的查询不可能命中拼音串（拼音只由 a-z0-9 组成），先挡掉可省一次拼音转换
        if (!SearchQuery.isPinyinLike(q)) return false

        // 3. 拼音全拼匹配
        if (toPinyin(text).contains(q)) return true

        // 4. 拼音首字母匹配
        val initials = toPinyinInitials(text)
        return initials.isNotEmpty() && initials.contains(q)
    }

    /**
     * 判断单段文本是否匹配**整串查询**（支持多词）。
     *
     * 语义：查询按空白切分后，**每个词都要命中同一段文本**。
     * 跨字段的多词匹配（"周杰伦 晴天" = 歌手命中 + 歌名命中）由
     * `SearchAggregator.matchesBySearchType*` 在歌曲维度完成 —— 单段文本做不到。
     */
    fun matchesQuery(text: String, query: String): Boolean {
        val tokens = SearchQuery.tokens(query)
        if (tokens.isEmpty()) return true
        return tokens.all { matches(text, it) }
    }

    /**
     * 获取文本的分组首字母（用于 A-Z 分组索引）。
     * - 中文：取拼音首字母大写（"周杰伦" → 'Z'）
     * - 英文/字母：首字符大写（"Taylor Swift" → 'T'）
     * - 数字/符号/空：返回 '#'
     *
     * 用于专辑/艺术家列表的 A-Z 分组展示与侧边索引跳转。
     */
    fun getGroupLetter(text: String): Char {
        if (text.isBlank()) return '#'
        val firstChar = text.trim().first()
        return when {
            firstChar.code in 0x4E00..0x9FFF -> {
                // CJK 统一表意文字：取拼音首字母大写
                val py = Pinyin.toPinyin(firstChar)
                if (py.isNotEmpty()) py.first().uppercaseChar() else '#'
            }
            firstChar.isLetter() -> firstChar.uppercaseChar()
            else -> '#'
        }
    }

    /**
     * 索引条所有可能的字母列表（A-Z + #），用于侧边索引条渲染。
     * 实际显示时由调用方过滤掉无数据的字母。
     */
    fun getAllGroupLetters(): List<Char> = ('A'..'Z').toList() + '#'
}
