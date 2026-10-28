package com.nasmusic.tv.backend.network.lx

import com.nasmusic.tv.data.model.Song

/**
 * Meting 平台 ↔ 洛雪 `source` 映射（方案 §4.3，**唯一真相源**）。
 *
 * ⚠️ 这张表是 §2.3 实测 + §2.4 对齐的产物：**改它必须有新的实测依据**。
 * 尤其是主键字段——`wy`/`tx`/`kw` 认 `songmid`，`kg` 认 `hash`，`mg` 认 `copyrightId`，
 * 填错字段不会报错，只会拿到一个「长得合法但内容不对」的直链（§2.3 的 `undefined` 事故）。
 */
internal object LxPlatformMap {

    /** Meting server → (洛雪 source, `musicInfo` 主键字段名) */
    private val MAP: Map<String, Pair<String, String>> = linkedMapOf(
        "netease" to ("wy" to "songmid"),
        "tencent" to ("tx" to "songmid"),
        "kugou" to ("kg" to "hash"),
        "kuwo" to ("kw" to "songmid"),
        "migu" to ("mg" to "copyrightId"),
    )

    /** 洛雪五平台（顺序仅用于 UI 展示与遍历） */
    val lxSources: List<String> = MAP.values.map { it.first }

    fun toLxSource(platform: String?): String? = platform?.let { MAP[it]?.first }

    fun toPlatform(source: String): String? = MAP.entries.firstOrNull { it.value.first == source }?.key

    /** 该 source 的主键字段名（锚点表 / 诊断文案用） */
    fun keyFieldOf(source: String): String? = MAP.values.firstOrNull { it.first == source }?.second

    /**
     * 由 [Song] 构造喂给脚本的 `musicInfo`。
     *
     * @param platform 平台（调用方负责 `song.networkPlatform ?: 全局平台` 的回退）
     * @return null = 平台不认识，或该平台的主键缺失（没有 id 就不该调脚本）
     */
    fun musicInfoOf(song: Song, platform: String?): LxMusicInfo? {
        val keyField = keyFieldOf(platform?.let { MAP[it]?.first } ?: return null) ?: return null
        val id = song.networkId?.takeIf { it.isNotBlank() } ?: return null
        val base = LxMusicInfo(
            name = song.title,
            singer = song.artist.ifBlank { null },
            albumName = song.album.ifBlank { null },
            interval = intervalOf(song.durationMs),
        )
        return when (keyField) {
            "songmid" -> base.copy(songmid = id)
            "hash" -> base.copy(hash = id)
            "copyrightId" -> base.copy(copyrightId = id)
            else -> null
        }
    }

    /** 洛雪 `interval` 是 `mm:ss`（`musicInfo.interval`，部分源拿它算时长） */
    fun intervalOf(durationMs: Long): String? {
        if (durationMs <= 0L) return null
        val totalSeconds = durationMs / 1000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
