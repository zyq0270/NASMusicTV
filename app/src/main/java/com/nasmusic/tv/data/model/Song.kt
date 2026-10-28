package com.nasmusic.tv.data.model

/**
 * 歌曲
 *
 * 统一承载 NAS 本地歌曲、网络搜索歌曲与本地存储歌曲：
 * - NAS 歌曲：isNetworkSong=false，id 为后端原始 ID
 * - 网络歌曲：isNetworkSong=true，id 格式为 "ntwk_${source}_${sourceId}"，
 *   networkSource 标识来源（"meting"/"baidu"/"jamendo"），networkId 为源平台原始 ID。
 *   streamUrl 在播放时由 NetworkMusicManager.resolvePlayUrl() 实时解析赋值，不持久化。
 * - 本地歌曲：isLocalSong=true，id 格式为 "local_${mediaStoreId}"，
 *   path 为文件绝对路径，streamUrl 为 content:// 或 file:// URI（ExoPlayer 直接可播），
 *   storageType 标识存储类型（"INTERNAL"/"EXTERNAL"/"USB"）。
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String = "",
    val artistId: String? = null,
    val album: String = "",
    val albumId: String? = null,
    val coverUrl: String? = null,
    val streamUrl: String? = null,
    val durationMs: Long = 0L,
    val trackNumber: Int = 0,
    val discNumber: Int = 1,
    val year: Int? = null,
    val genre: String? = null,
    val bitrate: Int = 0,
    /**
     * v2.35.0 多码率：网络歌曲解析后**实际命中**的音质档位
     * （0/128/192/320/999；0 = 未解析或 AUTO）。
     *
     * 仅用于 UI 显示"♫ 320 / FLAC"徽标，**不参与缓存键与下载键**，
     * 也不落盘（`Song` 不序列化该字段）。与 [bitrate]（真实码率）是不同概念。
     */
    val resolvedQuality: Int = 0,
    // 网络歌曲扩展字段（默认值保持向后兼容）
    val isNetworkSong: Boolean = false,
    val networkSource: String? = null,
    val networkId: String? = null,
    /**
     * 网络歌曲所属平台（`netease` / `tencent` / `kugou` / `kuwo` / `migu`）。
     *
     * 洛雪音源按它路由（`networkId` 的**含义**随平台而变，跨平台会张冠李戴）。
     * null = 历史数据或未标注 ⇒ 回退当前全局平台设置，与改造前行为一致。
     * ⛔ Room 实体不加此字段：下载后已有本地文件，不需要再取链。
     */
    val networkPlatform: String? = null,
    // 文件绝对路径（百度网盘 / 本地音乐共用）
    val path: String? = null,
    // 本地音乐扩展字段
    val isLocalSong: Boolean = false,
    val storageType: String? = null    // "INTERNAL" / "EXTERNAL" / "USB" / "UNKNOWN"
)
