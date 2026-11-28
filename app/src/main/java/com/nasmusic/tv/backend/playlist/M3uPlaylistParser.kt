package com.nasmusic.tv.backend.playlist

/**
 * M3u / M3u8 解析器。
 *
 * 设计：docs/archive/playlist-import-feature-plan.md §4.1.1 / §4.1.4 / §4.1.8
 * - `#EXTINF:<sec>,<artist> - <title>`：按 ` - ` 拆第一个分隔（左侧 artist、右侧 title）；
 *   无 ` - ` 时整段当 title（artist 空）
 * - `#EXTINF` 之后的第一个非注释、非空行是该条目的 path hint → `directUrl`
 *   （按 §4.1.8 (1) URL 分类：HTTP / file:// / 绝对路径 / 相对路径）
 * - 无 `#EXTINF` 关联的裸 URL 行自成条目：title=URL 文件名（去扩展名）、artist 空
 * - 跳过 `#EXT-X-`（直播）、`#PLAYLIST:` 等头与空行
 * - 相对路径条目原样保留（type=RELATIVE_PATH），由上层 PlaylistImporter 计入 skipped
 */
class M3uPlaylistParser : PlaylistParser, PlaylistNameProvider {

    private companion object {
        const val PORTABILITY_PREFIX = "#NASMUSIC:PORTABILITY="
    }

    /** 最近一次 parse 提取的歌单名 hint（`#PLAYLIST:` 头，§4.2 取名兜底链）。 */
    override var innerName: String? = null
        private set

    override fun canParse(headBytes: ByteArray, fileName: String): Boolean {
        // 扩展名兜底：.m3u/.m3u8 直接走本解析器（§4.1.1「整文件只有 http
        // 单行导出、无 #EXTM3U 头的纯 URL 列表」也能识别）
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext == "m3u" || ext == "m3u8") return true
        if (headBytes.isEmpty()) return false
        val head = PlaylistParsers.decode(headBytes)
        // 头部嗅探优先于扩展名：从其他工具导出的 .txt 后缀 M3u 也能识别（§4.1.7 (4)）
        return head.contains("#EXTM3U") || head.contains("#EXTINF:")
    }

    override fun parse(text: String, fileName: String): List<RawSongEntry> {
        innerName = null
        val entries = mutableListOf<RawSongEntry>()
        var pendingExtinf: Pair<Int?, String?>? = null // (durationSec, displayLabel)
        var index = 0
        // 导出器把 #NASMUSIC:PORTABILITY= 写在 URI 上一行；只有该形态才可能是脱敏占位符。
        var previousWasPortabilityMarker = false

        text.lineSequence().forEach { rawLine ->
            if (rawLine.length > PlaylistParsers.MAX_LINE_LENGTH) return@forEach
            val line = rawLine.trim()
            if (line.isEmpty()) {
                previousWasPortabilityMarker = false
                return@forEach
            }
            val isPortabilityMarker = line.startsWith(PORTABILITY_PREFIX)
            val isRedactedUri = previousWasPortabilityMarker && !isPortabilityMarker && isRedactedCredentialUri(line)
            previousWasPortabilityMarker = isPortabilityMarker

            if (line.startsWith("#")) {
                if (line.startsWith("#EXTINF:")) {
                    // 上一条没有 URI（例如导出器标记的 UNRESOLVED）也要保留显示信息。
                    pendingExtinf?.let { (sec, label) ->
                        val (artist, title) = splitExtinfLabel(label)
                        entries += RawSongEntry(
                            title = title,
                            artist = artist,
                            durationSec = sec,
                            originalIndex = index++,
                        )
                    }
                    pendingExtinf = parseExtinf(line)
                } else if (line.startsWith("#PLAYLIST:")) {
                    // §4.2 取名兜底：`#PLAYLIST:<name>` 头（部分导出工具写入）
                    val name = line.removePrefix("#PLAYLIST:").trim()
                    if (name.isNotEmpty()) innerName = name
                }
                // 其他 # 行（#EXTM3U / #EXT-X-）忽略
                return@forEach
            }

            // 非注释、非空行 = path hint（首个非注释行通常紧跟 EXTINF）
            // 仅导出器确切写出的凭据脱敏 URI 不落地；fragment 占位符不影响请求，URL 仍可播。
            if (isRedactedUri) {
                if (pendingExtinf != null) {
                    val (sec, label) = pendingExtinf!!
                    pendingExtinf = null
                    val (artist, title) = splitExtinfLabel(label)
                    entries += RawSongEntry(
                        title = title.ifBlank { PlaylistParsers.displayNameFromUrl(line) },
                        artist = artist,
                        durationSec = sec,
                        originalIndex = index++,
                    )
                }
                return@forEach
            }
            val urlType = PlaylistParsers.classifyUrl(line)
            val isPathLike = urlType != DirectUrlType.NONE

            if (pendingExtinf != null) {
                val (sec, label) = pendingExtinf!!
                pendingExtinf = null
                val (artist, title) = splitExtinfLabel(label)
                val resolvedTitle = title.ifBlank { PlaylistParsers.displayNameFromUrl(line) }
                entries += RawSongEntry(
                    title = resolvedTitle,
                    artist = artist,
                    durationSec = sec,
                    originalIndex = index++,
                    directUrl = if (isPathLike) line else null,
                    directUrlType = if (isPathLike) urlType else DirectUrlType.NONE,
                )
            } else if (isPathLike) {
                // 裸 URL 行（无待归属 EXTINF）自成条目
                entries += RawSongEntry(
                    title = PlaylistParsers.displayNameFromUrl(line),
                    artist = "",
                    originalIndex = index++,
                    directUrl = line,
                    directUrlType = urlType,
                )
            }
            // 无 EXTINF 且非 URL 的裸文本行：非标准 m3u，忽略
        }
        pendingExtinf?.let { (sec, label) ->
            val (artist, title) = splitExtinfLabel(label)
            entries += RawSongEntry(
                title = title,
                artist = artist,
                durationSec = sec,
                originalIndex = index,
            )
        }
        return entries
    }

    /**
     * 只识别导出器确切形态：查询参数值整体为占位符（敏感凭据），或 userinfo 被替换为占位符。
     * fragment 的 `#__REDACTED__` 是 sanitizeUri 对任意 fragment 的无差别替换，不影响媒体
     * 请求，不能据此丢弃整条可播 URL；路径中自然出现的占位符子串同样不误伤。
     */
    private fun isRedactedCredentialUri(line: String): Boolean {
        val placeholder = M3uPlaylistSerializer.URL_PLACEHOLDER
        if (!line.contains(placeholder)) return false

        val queryIndex = line.indexOf('?')
        if (queryIndex >= 0) {
            val fragmentIndex = line.indexOf('#', queryIndex + 1)
            val queryEnd = if (fragmentIndex >= 0) fragmentIndex else line.length
            val hasRedactedQueryValue = line.substring(queryIndex + 1, queryEnd)
                .split('&')
                .any { pair -> pair.substringAfter('=', "").trim() == placeholder }
            if (hasRedactedQueryValue) return true
        }

        val scheme = line.indexOf("://")
        if (scheme < 0) return false
        val authorityEnd = line.indexOf('/', scheme + 3).let { if (it < 0) line.length else it }
        return line.substring(scheme + 3, authorityEnd).startsWith("$placeholder@")
    }

    /** 解析 `#EXTINF:<sec>,<剩余>`；label 可空（缺失逗号时）。 */
    private fun parseExtinf(line: String): Pair<Int?, String?> {
        val rest = line.removePrefix("#EXTINF:").trim()
        val comma = rest.indexOf(',')
        if (comma < 0) return (rest.toIntOrNull()?.coerceAtLeast(0)) to null
        val sec = rest.substring(0, comma).trim().toIntOrNull()?.coerceAtLeast(0)
        val label = rest.substring(comma + 1).trim()
        return sec to (label.ifBlank { null })
    }

    /**
     * 拆分 EXTINF 显示标签（§4.1.4 标准：`artist - title`）。
     * 只切第一个 ` - `；无分隔时整段当 title、artist 空。
     */
    private fun splitExtinfLabel(label: String?): Pair<String, String> {
        if (label.isNullOrBlank()) return "" to ""
        val trimmed = label.trim()
        val sep = trimmed.indexOf(" - ")
        return if (sep > 0) {
            trimmed.substring(0, sep).trim() to trimmed.substring(sep + 3).trim()
        } else {
            // 导出器对空 artist 仍写 " - <title>"；去掉仅剩的分隔符。
            "" to trimmed.removePrefix("- ").trim()
        }
    }
}