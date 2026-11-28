package com.nasmusic.tv.backend

import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Genre
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongTechnicalInfo
import com.nasmusic.tv.data.model.VersionInfo

/**
 * 后端适配器接口
 * 统一封装不同NAS音乐后端的API差异
 */
interface BackendAdapter {

    /** 当前后端对歌单读写的真实能力；UI 与同步编排据此禁用/提示。 */
    val capabilities: BackendCapabilities
        get() = BackendCapabilities()

    /**
     * 后端类型标识
     */
    val backendType: String

    /**
     * 服务端显示名称（登录后由后端返回的实际名称）
     */
    val serverName: String

    /**
     * 后端 API 协议版本号（initialize 时从服务端获取，供设置→关于页展示）
     *
     * 接口默认 "Unknown"，各适配器用 `override var` 覆盖，在 initialize() 内赋值。
     * @deprecated 请使用 [getApiVersion] 获取更详细的版本信息
     */
    @Deprecated("Use getApiVersion() instead", ReplaceWith("getApiVersion().displayVersion"))
    val apiVersion: String
        get() = "Unknown"

    /**
     * 获取当前 API 版本号详细信息（用于设置→关于页展示）
     *
     * 返回 [VersionInfo] 密封接口，包含四种状态：
     * - [VersionInfo.Static]   硬编码常量版本
     * - [VersionInfo.Runtime]  运行时从服务端获取
     * - [VersionInfo.NoVersion] 无版本号服务（仅展示服务名）
     * - [VersionInfo.Disconnected] 未连接
     */
    suspend fun getApiVersion(): VersionInfo

    /**
     * 播放流时需要注入的 HTTP 请求头（如 Authorization）
     *
     * 默认空 Map。飞牛音乐等需要请求头认证的后端覆盖此属性；
     * 由 `BackendAuthHeaders`（provider 实时读取）经 `BaiduHttpDataSourceFactory`
     * 的拦截器注入 ExoPlayer 播放与 Coil 封面两条链路。
     */
    val streamHeaders: Map<String, String>
        get() = emptyMap()

    /**
     * 最近一次失败的简短原因（面向用户，可直接展示）。
     *
     * 存在的理由：`initialize()` 只返回 `Boolean`，无法区分「地址非法 / 凭据错 /
     * 服务端要求访问码 / 网络不可达」——用户只会看到一句泛化的「连接失败」，
     * 排查无从下手。需要给出诊断的适配器（如 `FeiniuAdapter`）覆写此属性并在失败
     * 分支写入**不含任何凭据**的短文案；其余后端沿用默认空串。
     *
     * ⚠️ **禁止写入访问码 / 密码 / token**——这些会经 `BackendRegistry` 直接显示到 UI。
     */
    val lastErrorDetail: String
        get() = ""

    /**
     * 设置访问码 / 安全码（在 [initialize] 之前由 `BackendRegistry` 调用）。
     *
     * 默认空实现：只有需要访问码保护的后端（飞牛 fnOS）覆写。传入空串 = 未启用。
     */
    fun setAccessCode(accessCode: String) {}

    /**
     * 初始化连接
     */
    suspend fun initialize(baseUrl: String, apiToken: String, username: String = "", password: String = ""): Boolean

    /**
     * 测试连接是否可用
     */
    suspend fun testConnection(): Boolean

    /**
     * 断开后端会话连接，释放服务端 session 资源。
     * Jellyfin：POST /Sessions/Logout 使 token 失效。
     * Navidrome：无状态认证，不需要显式登出。
     * 默认空实现，子类按需覆盖。
     */
    suspend fun logout() {}

    /**
     * 释放底层网络资源（OkHttp 连接池）。
     * logout() 负责使服务端 session 失效，
     * close() 负责释放客户端连接，防止连接泄漏。
     */
    fun close() {}

    /**
     * 获取所有专辑。
     *
     * ⛔ 失败必须抛异常，⛔ 不许吞成 `emptyList()`：调用方 `MainViewModel.loadLibrary`
     * 靠异常构造 `UiState.Error`（含重试闭包）。返回空列表只表示「服务端确实没有专辑」。
     * 分页过程中任一页失败也必须抛，不能把半截列表当成完整结果返回。
     */
    suspend fun getAlbums(): List<Album>

    /**
     * 获取专辑中的歌曲
     */
    suspend fun getAlbumSongs(albumId: String): List<Song>

    /**
     * 获取所有歌手
     *
     * ⛔ 失败必须抛异常，⛔ 不许吞成 `emptyList()`：调用方 `MainViewModel.loadArtists`
     * 靠异常构造 `UiState.Error`（含重试闭包）。返回空列表只表示「服务端确实没有艺术家」。
     */
    suspend fun getArtists(): List<Artist>

    /**
     * 获取歌手的歌曲。
     *
     * 参数契约（v2.39.23 统一）：`artistId` 与 `artistName` 由调用方**同时**传入。
     * - 按 ID 查询的后端（Navidrome / Subsonic / 飞牛 / 刀鱼）：用 `artistId`，忽略名字；
     * - 按名字查询的后端（Jellyfin）：用 `artistName`，忽略 ID。
     *
     * ⚠️ 调用方会为「同一人的多个原始 ID」逐个调用本方法。按名字查询的后端必须把
     * [queriesArtistSongsByName] 覆盖为 true，调用方据此只发一次请求；否则同一条
     * 按名查询会被重复发出 N 次，结果还要再靠 dedupeKey 去掉。
     *
     * 返回结果**不要求**后端自行按 artist 过滤（调用方有统一的 ArtistSplitter 过滤）；
     * 但 `song.artist` 必须被正确填充，否则调用方按名过滤会整批丢弃。
     */
    suspend fun getArtistSongs(artistId: String, artistName: String? = null): List<Song>

    /**
     * 该后端是否按 artistName 查询歌曲（忽略 artistId）。默认 false。
     * 仅 Jellyfin 为 true；调用方据此避免多 ID 重复请求。
     */
    val queriesArtistSongsByName: Boolean get() = false

    /**
     * 获取歌曲（支持分页）
     * @param limit 每批返回的最大歌曲数量
     * @param offset 起始位置（用于分页）
     */
    suspend fun getSongs(limit: Int = 500, offset: Int = 0): List<Song>

    /**
     * 获取歌曲总数（利用 Jellyfin 的 TotalRecordCount）
     * 用于分页显示 "已加载 N / 共 M 首"
     */
    suspend fun getSongsTotalCount(): Int = 0

    /**
     * 按 ID 批量查询歌曲
     * 用于最近播放、收藏等场景，避免依赖全量歌曲列表
     * @param ids 歌曲 ID 列表
     */
    suspend fun getSongsByIds(ids: List<String>): List<Song> = emptyList()

    /**
     * 获取所有年份列表（从服务端提取，避免依赖全量歌曲）
     */
    suspend fun getYears(): List<Int> = emptyList()

    /**
     * 搜索歌曲
     */
    suspend fun searchSongs(query: String): List<Song>

    /**
     * 获取最近添加的歌曲
     */
    suspend fun getRecentSongs(): List<Song>

    /**
     * 获取歌曲流地址
     */
    fun getStreamUrl(songId: String): String

    /**
     * 获取歌曲封面地址
     */
    fun getCoverUrl(songId: String): String

    /**
     * 获取歌曲的候选封面 URL 列表，按优先级排序（歌曲→专辑→艺术家）。
     * UI 层依次尝试/轮播，第一个加载成功的即为可用封面。
     * 返回空列表表示无可用封面。
     */
    fun getCoverUrlCandidates(song: Song): List<String> = emptyList()

    /**
     * 构造「**带认证的任意后端路径**」URL —— 照片墙通道用（§6.4）。
     *
     * ⚠️ 为什么需要新方法：现有接口只暴露了 [getStreamUrl] / [getCoverUrl] 这类
     * **为音频写死**的 URL 构造，而照片要查
     * `/Items?IncludeItemTypes=Photo` 与 `/Items/{id}/Images/Primary?maxWidth=1920`，
     * 两者都拿不到 —— 尤其后者需要 `maxWidth=1920`（封面那个是 512，放大会糊）。
     *
     * 默认返回 `null` = 该后端**不支持**任意路径访问
     * ⇒ 照片来源按不可用处理（Navidrome 等即走此分支）。
     *
     * @param path  以 `/` 开头的后端路径，如 `/Items`
     * @param query 已编码好的查询串（**不含** `?`），可空
     */
    fun buildAuthenticatedUrl(path: String, query: String = ""): String? = null

    /**
     * 当前登录用户 id —— 部分后端的 `/Items` 查询需要 `UserId` 参数。
     *
     * 未知 / 不适用时返回空串（默认实现）。
     */
    val currentUserId: String
        get() = ""

    /**
     * 获取歌词（如果后端支持）
     */
    suspend fun getLyrics(songId: String): String?

    // ========== F-1 扩展接口 ==========
    //
    // 以下方法均带有 Kotlin 接口默认实现（返回空值），
    // 新适配器可按需覆盖，不必强制实现全部接口。
    // 已有覆盖的适配器不受影响（override 优先于默认值）。

    // --- 播放列表 ---
    suspend fun getPlaylists(): List<Playlist> = emptyList()
    suspend fun getPlaylistSongs(playlistId: String): List<Song> = emptyList()
    suspend fun createPlaylist(name: String): Playlist? = null
    suspend fun deletePlaylist(playlistId: String): Boolean = false
    suspend fun addToPlaylist(playlistId: String, songId: String): Boolean = false
    suspend fun removeFromPlaylist(playlistId: String, songId: String): Boolean = false

    /**
     * 歌单同步专用结构化 API。默认桥接旧 Boolean/List 方法，适配器可覆写以保留
     * HTTP 401/403/404/5xx 等区别；旧调用方无需迁移。
     */
    suspend fun getPlaylistsResult(): BackendResult<List<Playlist>> =
        playlistCall { getPlaylists() }

    suspend fun getPlaylistSongsResult(playlistId: String): BackendResult<List<Song>> =
        playlistCall { getPlaylistSongs(playlistId) }

    suspend fun createPlaylistResult(name: String): BackendResult<Playlist> =
        playlistCall { createPlaylist(name) }

    suspend fun addToPlaylistResult(playlistId: String, songId: String): BackendResult<Unit> =
        playlistCall<Unit> {
            if (addToPlaylist(playlistId, songId)) Unit else null
        }

    // --- 收藏 ---
    /**
     * 切换收藏状态。
     * @param songId 歌曲 ID
     * @param isCurrentlyFavorite 调用方已知的当前收藏状态（从本地缓存获取，避免服务端二次查询）
     * @return 是否成功
     */
    suspend fun toggleFavorite(songId: String, isCurrentlyFavorite: Boolean = false): Boolean = false
    suspend fun getFavorites(): List<Song> = emptyList()

    // --- 评分 ---
    suspend fun setRating(songId: String, rating: Int): Boolean = false

    // --- 流派 ---
    suspend fun getGenres(): List<Genre> = emptyList()
    suspend fun getSongsByGenre(genre: String): List<Song> = emptyList()
    suspend fun getSongsByYearRange(fromYear: Int, toYear: Int): List<Song> = emptyList()

    // --- Scrobble ---
    suspend fun scrobblePlay(songId: String, timestamp: Long): Boolean = false

    // --- 随机歌曲 ---
    suspend fun getRandomSongs(limit: Int = 20): List<Song> = emptyList()

    // --- 歌曲技术信息 ---
    suspend fun getSongTechnicalInfo(songId: String): SongTechnicalInfo? = null
}

internal suspend fun <T> BackendAdapter.playlistCall(block: suspend () -> T?): BackendResult<T> =
    try {
        val value = block()
        if (value == null) BackendResult.Failure(BackendError.UNSUPPORTED)
        else BackendResult.Success(value)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        BackendResult.Failure(backendErrorFromThrowable(e))
    }

internal fun backendErrorFromHttpStatus(status: Int): BackendError = when (status) {
    401 -> BackendError.UNAUTHORIZED
    403 -> BackendError.FORBIDDEN
    404 -> BackendError.NOT_FOUND
    409 -> BackendError.CONFLICT
    in 500..599 -> BackendError.SERVER
    else -> BackendError.UNKNOWN
}

internal fun backendErrorFromThrowable(error: Throwable): BackendError = when (error) {
    is java.net.SocketTimeoutException -> BackendError.TIMEOUT
    is java.io.IOException -> BackendError.NETWORK
    else -> BackendError.UNKNOWN
}

internal fun com.google.gson.JsonObject?.subsonicBackendError(): BackendError? {
    this ?: return null
    val response = get("subsonic-response")
        ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val status = response.get("status")?.takeIf { it.isJsonPrimitive }?.asString
    if (status != "failed") return null
    val code = response.get("code")?.takeIf { it.isJsonPrimitive }?.let { element ->
        runCatching { element.asInt }.getOrNull()
    } ?: -1
    return when (code) {
        40 -> BackendError.UNAUTHORIZED
        50 -> BackendError.FORBIDDEN
        70 -> BackendError.NOT_FOUND
        in 0..99 -> BackendError.PROTOCOL
        else -> BackendError.SERVER
    }
}
