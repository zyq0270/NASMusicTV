package com.nasmusic.tv.backend.network

/**
 * 网络歌曲主键的**唯一**构造点（艺人全量方案 红线 1）。
 *
 * `MetingApiService.parseSongs` 与 `NeteaseOpenApi` 都必须经这里生成 `Song.id`。
 * 两条链路给同一首歌生成不同 id，等于同一首歌在收藏 / 下载 / 断点续传索引里变成两个实体
 * （这些主键全部建在这个字符串上）。
 */
object NetworkSongId {
    /**
     * 红线 2 的**唯一合法值**（F5，终审 B1）：`Song.networkSource` 只能取这个字符串，
     * 同时是 `NetworkMusicManager.services` 的注册键与 `MetingApiService.sourceId` 的值。
     *
     * main 源集原先有 8 个声明点各自硬编码 `"meting"`，常量漂移时只有播放会坏
     * （`NetworkMusicManager` 精确路由不 fallback）——全部收口到这里。
     * ⛔ 代码位不得再写裸字面量；注释/KDoc 里的 `"meting"` 说明文字不动。
     * v2.39.8 起 `NetworkSource` 枚举已删除（方案 §7.5），UI 侧直接用本常量作 key。
     */
    const val METING_SOURCE: String = "meting"

    /** 形如 `ntwk_meting_2652820720`；⛔ sourceId 只能是注册源 key，不能是平台名（红线 2） */
    fun forNetworkSong(sourceId: String, netId: String): String = "ntwk_${sourceId}_$netId"
}
