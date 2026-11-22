package com.nasmusic.tv.util

import java.text.Normalizer

/**
 * 歌唱家拆分工具
 *
 * 将后端返回的原始 artist 字段（如 "张三 & 李四"）拆分为独立歌唱家列表。
 * 分隔符优先级按长度降序匹配，确保长分隔符优先于短分隔符。
 *
 * 分隔符列表（优先级顺序）：
 * 1. " feat. " — 常见合作标记（需前导空白）
 * 2. " ft. " — feat 的缩写
 * 3. " with " — 英文合作标记
 * 4. "&"、"、"、"/"、"×"、"，"、"＆"、"," 及全角变体（"／"、"；"、"｜"、"＋"）— 各式中英文并列分隔符
 * 5. " + " — 半角加号（需两侧空白，避免误伤含加号的单人艺名）
 * 6. " vs "、" vs. " — 对唱标记
 *
 * 同名判定统一走 [normalizeKey]：NFKC 归一化（全角转半角）+ 首尾 trim +
 * 内部连续空白折叠 + 小写。避免 "古天乐" 与 "古天乐 " / "古天乐／萱萱"
 * 这类肉眼相同、字符串不同的名字被当成两个艺术家。
 */
object ArtistSplitter {

    private val whitespace = Regex("\\s+")

    /**
     * 有界后缀标记：只认白名单里的演出/版本词，且必须以括号或短横线明确界定。
     * ⛔ 绝不做无边界 `contains` / `substringBefore` —— 那会让「周」命中「周杰伦」。
     */
    private val bracketedTag = Regex("""\s*[（(【\[]\s*([^）)】\]]{1,24})\s*[）)】\]]\s*$""")
    private val dashedTag = Regex("""\s+[-–—]\s*([^\s]{1,24})\s*$""")
    private val knownTags = setOf(
        "live", "现场", "演唱会", "concert", "acoustic", "不插电",
        "伴奏", "inst", "instrumental", "纯音乐", "karaoke", "ktv",
        "remix", "remastered", "重制版", "cover", "翻唱",
        "explicit", "clean", "radio edit", "studio"
    )

    private val delimiters = listOf(
        Regex("\\s+feat\\.?", RegexOption.IGNORE_CASE),
        Regex("\\s+ft\\.", RegexOption.IGNORE_CASE),
        Regex("\\s+with\\s+", RegexOption.IGNORE_CASE),
        // 中英文并列分隔符（含全角变体：／ ； ｜ ＋）
        Regex("\\s*[&/、×，＆,／;；|｜＋]\\s*"),
        // 半角加号只在两侧有空白时才当分隔符，避免误拆单人艺名
        Regex("\\s+\\+\\s+"),
        Regex("\\s+vs\\.?\\s+", RegexOption.IGNORE_CASE),
    )

    /**
     * 艺术家名归一化键：用于「是否同一艺术家」的判定。
     *
     * - NFKC：全角字符转半角（"ＧＶＴ" → "GUT"），全角空格 U+3000 → 半角空格
     * - 移除所有不可见字符（零宽空格 U+200B / BOM U+FEFF / LTR-RTL 标记 / FORMAT 类）
     * - 折叠内部连续空白为一个半角空格
     * - trim：去掉首尾所有 Unicode 空白（Character.isWhitespace，比 String.trim 更全）
     * - lowercase：大小写不敏感
     */
    fun normalizeKey(name: String): String {
        if (name.isBlank()) return ""
        // NFKC 归一化 + 移除所有不可见/格式字符（零宽空格、BOM、LTR/RTL 标记等 NFKC 不处理的字符）
        val cleaned = Normalizer.normalize(name, Normalizer.Form.NFKC).filter { ch ->
            val type = Character.getType(ch).toByte()
            // 保留：字母、数字、标点、符号、空格（普通空白，已被 whitespace 正则覆盖）
            // 移除：FORMAT (Cf)、CONTROL (Cc)、SURROGATE (Cs)、未分配
            type != Character.FORMAT && type != Character.CONTROL && type != Character.SURROGATE &&
                type != Character.UNASSIGNED && ch.code != 0xFEFF && ch.code != 0x200B &&
                ch.code != 0x200C && ch.code != 0x200D && ch.code != 0x200E && ch.code != 0x200F &&
                ch.code != 0x2060 && ch.code != 0x2061 && ch.code != 0x2062 && ch.code != 0x2063 &&
                ch.code != 0x2064
        }
        return cleaned.replace(whitespace, " ")
            .trim()
            .lowercase()
    }

    /**
     * 匹配键：在 [normalizeKey] 基础上剥离**有界**的演出/版本后缀，供歌手名相等判定使用。
     *
     * - 只剥白名单里明确界定的后缀：`(Live)` / `（现场）` / `[inst.]` / ` - Remix`；
     * - ⛔ 不剥白名单外的括号内容（`赵传(精选)` 仍是独立名字）；
     * - ⛔ 剥完为空则保留原值（艺名本身叫 `Live` 时不能被剥成空串）；
     * - 幂等：可安全对预计算 key 再调用一次。
     *
     * 该函数只用于 [containsArtist] / [containsArtistWithKey] 的相等判定。
     * ⛔ 不要改 [normalizeKey] 本身：它是 albumKey / 去重 / Map 键的身份口径，
     *    动它会让「赵传」与「赵传(Live)」在曲库里合并成一张卡。
     */
    fun matchKey(name: String): String {
        val normalized = normalizeKey(name)
        if (normalized.isBlank()) return ""
        var stripped = normalized
        while (true) {
            // ⛔ 用 find 不用 match：Regex.match 要求整串匹配，而这里只匹配尾部后缀。
            val match = bracketedTag.find(stripped) ?: dashedTag.find(stripped)
            if (match == null) break
            val tag = match.groupValues[1].trim().trim('.')
            if (tag !in knownTags) break
            val candidate = stripped.substring(0, match.range.first).trim()
            if (candidate.isBlank()) break
            stripped = candidate
        }
        return stripped
    }

    /**
     * 专辑身份键：归一化「专辑名 + 艺术家」，供曲库合并与搜索聚合共用同一口径。
     *
     * - 同名不同歌手的专辑必须分成两张卡（如两张都叫《爱情》的专辑）；
     * - artist 为空时退化为纯专辑名键，供上游把「艺术家元数据缺失」的同源条目归并。
     *   ⚠️ `MusicMerger.mergeAlbums` 还会把无艺术家条目并入同名下曲目数最多的具名分组，
     *   以保留 NAS + 百度同名专辑（百度侧常无 artist）的既有合并行为。
     */
    fun albumKey(name: String, artist: String): String {
        val album = normalizeKey(name)
        if (album.isBlank()) return ""
        val artistKey = normalizeKey(artist)
        return if (artistKey.isBlank()) album else "$album::$artistKey"
    }

    /**
     * 将原始 artist 字符串拆分为独立歌唱家列表。
     * 迭代拆分：先用第一个分隔符拆分，再对每个部分用后续分隔符继续拆分。
     * 如果无法拆分，返回包含原始字符串的单元素列表。
     *
     * 返回的是用于展示的名字（原大小写、已 trim）；重名去重按 [normalizeKey] 判定，
     * 保留首次出现的写法。
     */
    fun split(artist: String): List<String> {
        if (artist.isBlank()) return emptyList()
        var results = listOf(artist.trim())
        for (delim in delimiters) {
            results = results.flatMap { part ->
                val sub = part.split(delim).map { it.trim() }.filter { it.isNotBlank() }
                if (sub.size > 1) sub else listOf(part)
            }
        }
        // 按归一化键去重，保留首次出现的展示名
        val seen = linkedSetOf<String>()
        val out = mutableListOf<String>()
        for (part in results) {
            val key = normalizeKey(part)
            if (key.isBlank()) continue
            if (seen.add(key)) out.add(part.trim())
        }
        return out
    }

    /**
     * 判断 artist 字段是否包含多个歌唱家
     */
    fun isMultiArtist(artist: String): Boolean = split(artist).size > 1

    /**
     * 判断某首歌的原始 artist 字段是否属于指定艺术家（归一化比较）。
     *
     * 例如 song.artist = "古天乐/萱萱"、artistName = "古天乐" → true；
     * artistName = "古天乐/萱萱"（未拆分的合唱名）→ false，调用方应先拆分再入库。
     */
    fun containsArtist(rawArtistField: String, artistName: String): Boolean {
        val key = matchKey(artistName)
        if (key.isBlank()) return false
        return split(rawArtistField).any { matchKey(it) == key }
    }

    /**
     * 与 [containsArtist] 相同，但接受预计算的归一化 key，
     * 避免在批量过滤时对同一个 artistName 重复做 NFKC 归一化。
     *
     * 用法：
     * ```
     * val key = ArtistSplitter.normalizeKey("赵传")
     * songs.filter { ArtistSplitter.containsArtistWithKey(it.artist, key) }
     * ```
     */
    fun containsArtistWithKey(rawArtistField: String, artistKey: String): Boolean {
        if (artistKey.isBlank()) return false
        if (rawArtistField.isBlank()) return false
        // 两边都过 matchKey：调用方可能传 normalizeKey 结果（仍带 (Live)），
        // matchKey 幂等，重复调用不会改变纯名字的 key。
        val key = matchKey(artistKey)
        if (key.isBlank()) return false
        return split(rawArtistField).any { matchKey(it) == key }
    }
}
