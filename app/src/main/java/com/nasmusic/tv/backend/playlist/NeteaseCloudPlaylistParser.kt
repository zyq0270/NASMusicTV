package com.nasmusic.tv.backend.playlist

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.nasmusic.tv.util.AppLog

/**
 * 网易云公开歌单分享 JSON 解析器。
 *
 * 设计：docs/archive/playlist-import-feature-plan.md §4.1.5 / §4.1.1
 * - 接受公开歌单页导出的「完整 JSON」（带 `tracks[]`，每项 `name/artists[]/album/duration`）
 * - `artists[].name` 用 `、` 拼成 artist 字段
 * - 过滤空 title / artist
 * - 分享链接 URL 不在本解析器处理（按 §4.1.8 URL 行规则走 m3u/txt 的 directUrl 捕获）
 */
class NeteaseCloudPlaylistParser : PlaylistParser, PlaylistNameProvider {

    /** 最近一次 parse 提取的歌单名 hint（`playlist.name`，§4.2 取名兜底链）。 */
    override var innerName: String? = null
        private set

    override fun canParse(headBytes: ByteArray, fileName: String): Boolean {
        if (headBytes.isEmpty() || !fileName.endsWith(".json", ignoreCase = true)) return false
        val head = PlaylistParsers.decode(headBytes)
        // 嗅探：顶部含 neteasePlaylistId，或 JSON 含 playlist/tracks 结构
        // V4-N03：字面量必须带引号比较，否则正文里出现裸词 neteasePlaylistId 就会误判
        // canParse=true，把不相关的 .json 交给本解析器（parse 返回空 ⇒ 整次导入被吞）。
        return head.contains("\"neteasePlaylistId\"") ||
            (head.contains("\"playlist\"") && head.contains("\"tracks\""))
    }

    override fun parse(text: String, fileName: String): List<RawSongEntry> {
        innerName = null
        // S05-05：PlaylistImporter.importBytes(:96) 对 parse 无 try/catch 兜底，任何畸形
        // 字段抛出的异常都会让整次歌单导入失败。在这里统一折算成「未识别格式」（空列表，
        // 与本解析器既有的「根非对象 / 无 tracks」语义一致）。
        // ⚠️ 兜底放在本文件而非 PlaylistImporter —— 后者不在本批次白名单内（硬规则 3）。
        return runCatching { parseInternal(text) }
            .onFailure {
                innerName = null
                AppLog.w(TAG, "parse: 未识别格式 / 解析异常 (${it.javaClass.simpleName}: ${it.message})")
            }
            .getOrElse { emptyList() }
    }

    private fun parseInternal(text: String): List<RawSongEntry> {
        val root: JsonObject = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: JsonParseException) {
            return emptyList()
        } catch (e: IllegalStateException) {
            // M12 口径：根是数组/标量时 asJsonObject 抛 IllegalStateException（不是 JsonParseException）
            return emptyList()
        }
        // §4.2 取名兜底：网易云歌单 JSON 内嵌 playlist.name
        (root.get("playlist") as? JsonObject)?.get("name")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { innerName = it }
        val entries = mutableListOf<RawSongEntry>()

        // tracks 可能在顶层，也可能在 playlist.tracks
        // M12：getAsJson* 在字段存在但类型不符时抛 IllegalStateException，全部换安全转换
        val tracks: JsonArray? = (root.get("tracks") as? JsonArray)
            ?: ((root.get("playlist") as? JsonObject)?.get("tracks") as? JsonArray)

        if (tracks != null) {
            var index = 0
            for (el in tracks) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val title = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                if (title.isEmpty()) continue

                val artists = obj.get("artists") as? JsonArray
                val artist = artists?.mapNotNull { a ->
                    (a as? JsonObject)?.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                }?.filter { it.isNotEmpty() }?.joinToString("、") ?: ""

                val album = (obj.get("album") as? JsonObject)
                    ?.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                    ?.takeIf { it.isNotEmpty() }

                // duration 网易云为毫秒（§4.1.5 说明按 ms 解析；归一为秒）
                val durationMs = obj.get("duration")?.takeIf { it.isJsonPrimitive }?.asLong
                val durationSec = durationMs?.coerceAtLeast(0)?.let { (it / 1000).toInt() }

                entries += RawSongEntry(
                    title = title,
                    artist = artist,
                    album = album,
                    durationSec = durationSec,
                    originalIndex = index++,
                )
            }
        }
        // 没有 tracks：不识别（按「未识别格式」处理）
        return entries
    }

    private companion object {
        const val TAG = "NeteaseCloudPlaylistParser"
    }
}