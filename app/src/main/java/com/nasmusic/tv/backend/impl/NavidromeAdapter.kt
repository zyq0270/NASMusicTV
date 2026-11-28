package com.nasmusic.tv.backend.impl

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.backend.BackendCapabilities
import com.nasmusic.tv.backend.BackendError
import com.nasmusic.tv.backend.BackendResult
import com.nasmusic.tv.backend.backendErrorFromHttpStatus
import com.nasmusic.tv.backend.backendErrorFromThrowable
import com.nasmusic.tv.backend.getOrNull
import com.nasmusic.tv.backend.subsonicBackendError
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Genre
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongTechnicalInfo
import com.nasmusic.tv.data.model.VersionInfo
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.ArtistSplitter
import com.nasmusic.tv.util.EncodingUtils
import com.nasmusic.tv.util.RetryConfig
import com.nasmusic.tv.util.UrlSanitizer
import com.nasmusic.tv.util.withRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Navidrome 后端适配器
 * 使用 Subsonic API（Navidrome 完全兼容）
 */
class NavidromeAdapter : BackendAdapter {

    override val backendType: String = "navidrome"
    override val capabilities = BackendCapabilities(
        playlistRead = true,
        playlistCreate = true,
        playlistDelete = true,
        playlistAdd = true,
        playlistRemove = true,
    )
    override var apiVersion: String = "Unknown"

@Volatile
    private var baseUrl: String = ""
@Volatile
    private var username: String = ""
@Volatile
    private var password: String = ""
@Volatile
    private var apiToken: String = ""
    override var serverName: String = "Navidrome"
        private set

    // B11 修复：固定 salt + token，避免 buildCoverUrl 每次生成新 salt
    // 导致封面 URL 不稳定、Coil 缓存 key 失效、同一封面反复下载。
@Volatile
    private var salt: String = ""

    private val gson = Gson()

    private val client: OkHttpClient by lazy {
        // R-6：注入共享连接池/Dispatcher（BackendRegistry 持有，切后端不再累积线程池）
        OkHttpClient.Builder()
            .apply {
                // 日志拦截器仅在 debug 构建启用，避免 release 中 URL 写入 logcat
                if (com.nasmusic.tv.BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectionPool(com.nasmusic.tv.backend.BackendRegistry.sharedConnectionPool)
            .dispatcher(com.nasmusic.tv.backend.BackendRegistry.sharedDispatcher)
            .build()
    }

    /** R-10：Subsonic 公共层（认证/URL 构造/请求执行），初始化时同步认证态 */
    private val restClient = SubsonicRestClient("NavidromeAdapter", client)

    override suspend fun initialize(
        baseUrl: String,
        apiToken: String,
        username: String,
        password: String
    ): Boolean = withContext(Dispatchers.IO) {
        this@NavidromeAdapter.baseUrl = baseUrl.removeSuffix("/")
        this@NavidromeAdapter.username = username
        this@NavidromeAdapter.password = password
        this@NavidromeAdapter.apiToken = apiToken
        // B11：初始化时固定 salt，与 Subsonic 一致，保证封面/流 URL 稳定可缓存
        // （token = md5(password + salt) 在 buildRestUrl 内现场计算，无需预存）
        this@NavidromeAdapter.salt = System.currentTimeMillis().toString()
        // R-10：公共层同步认证态
        restClient.baseUrl = this@NavidromeAdapter.baseUrl
        restClient.username = username
        restClient.password = password
        restClient.salt = this@NavidromeAdapter.salt

        testConnection()
    }

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("ping")
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use false
                if (!response.isSuccessful) return@use false
                val json = gson.fromJson(body, JsonObject::class.java)
                val subsonic = json.getAsJsonObject("subsonic-response") ?: return@use false
                val status = subsonic.get("status")?.asString ?: return@use false
                val version = subsonic.get("version")?.asString ?: ""
                if (version.isNotBlank()) {
                    serverName = "Navidrome $version"
                    apiVersion = "Subsonic API $version"
                }
                status == "ok"
            }
        } catch (e: Exception) {
            AppLog.w("NavidromeAdapter", "testConnection failed", e)
            false
        }
    }

    override suspend fun getApiVersion(): VersionInfo = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("ping")
            val request = Request.Builder().url(url).build()
            val result = client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use VersionInfo.Disconnected("Navidrome")
                if (!response.isSuccessful) return@use VersionInfo.Disconnected("Navidrome")
                val json = gson.fromJson(body, JsonObject::class.java)
                val subsonic = json.getAsJsonObject("subsonic-response") ?: return@use VersionInfo.Disconnected("Navidrome")
                val status = subsonic.get("status")?.asString ?: return@use VersionInfo.Disconnected("Navidrome")
                val version = subsonic.get("version")?.asString ?: ""
                if (status == "ok" && version.isNotBlank()) {
                    VersionInfo.Runtime("Navidrome", version, "rest/ping.view", System.currentTimeMillis())
                } else {
                    VersionInfo.Disconnected("Navidrome")
                }
            }
            result
        } catch (e: Exception) {
            AppLog.w("NavidromeAdapter", "getApiVersion failed", e)
            VersionInfo.Disconnected("Navidrome")
        }
    }

    override suspend fun getAlbums(): List<Album> = withContext(Dispatchers.IO) {
        try {
            // B10 修复：原硬编码 size=500 无分页，超过 500 张专辑的用户会丢专辑。
            // 改为按页循环拉取（每页 500），直到返回不足一页或达到安全上限。
            val pageSize = 500
            val allAlbums = mutableListOf<Album>()
            var offset = 0
            var maxPages = 100 // 安全上限：最多 5 万张专辑，防止异常循环

            while (maxPages-- > 0) {
                val url = buildRestUrl("getAlbumList2") +
                        "&type=alphabeticalByName&size=$pageSize&offset=$offset"

                // 无响应 / 缺 albumList2 是请求失败，不是「已经翻完」：
                // 否则半截专辑列表会被当成完整曲库，上层也拿不到错误态。
                val json = executeRequest(url)
                    ?: throw IllegalStateException("Navidrome getAlbums 第 ${offset / pageSize + 1} 页无响应")
                val subsonic = json.getAsJsonObject("subsonic-response")
                // G2-02：`albumList2` 整体缺失才是协议错误；它存在但没有 `album` 键是
                // **合法的空页**（空曲库，或总数恰为 pageSize 整数倍时的末页）。
                // 旧实现把这种情况当失败抛异常 ⇒ 空库/整页倍数曲库的专辑页恒 Error。
                val albumList = subsonic?.getAsJsonObject("albumList2")
                    ?: throw IllegalStateException("Navidrome getAlbums 缺少 albumList2")
                val albums = albumList.getAsJsonArray("album") ?: break

                if (albums.size() == 0) break

                for (i in 0 until albums.size()) {
                    val obj = albums[i].asJsonObject
                    val id = obj.get("id")?.asString ?: continue
                    val name = EncodingUtils.fixEncoding(obj.get("name")?.asString) ?: "Unknown Album"
                    val artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: ""
                    val year = obj.get("year")?.asInt
                    val songCount = obj.get("songCount")?.asInt ?: 0
                    val durationSec = obj.get("duration")?.asLong ?: 0L

                    allAlbums.add(
                        Album(
                            id = id,
                            name = name,
                            artist = artist,
                            coverUrl = buildCoverUrl(id),
                            year = year,
                            songCount = songCount,
                            durationMs = durationSec * 1000
                        )
                    )
                }

                // 本页不足一页 → 已到末页，停止
                if (albums.size() < pageSize) break
                offset += pageSize
            }
            allAlbums
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getAlbums failed", e)
            throw e
        }
    }

    override suspend fun getAlbumSongs(albumId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getAlbum") + "&id=$albumId"

            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val album = subsonic?.getAsJsonObject("album")
            val songs = album?.getAsJsonArray("song")
                ?: return@withContext emptyList<Song>()

            val albumName = EncodingUtils.fixEncoding(album.get("name")?.asString) ?: ""
            val albumArtist = EncodingUtils.fixEncoding(album.get("artist")?.asString) ?: ""

            songs.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown"
                val artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: albumArtist
                val track = obj.get("track")?.asInt ?: 0
                val disc = obj.get("discNumber")?.asInt ?: 1
                val year = obj.get("year")?.asInt
                val durationSec = obj.get("duration")?.asLong ?: 0L
                val bitrate = obj.get("bitRate")?.asInt ?: 0
                val coverId = obj.get("coverArt")?.asString ?: ""

                Song(
                    id = id,
                    title = title,
                    artist = artist,
                    album = albumName,
                    albumId = albumId,
                    coverUrl = if (coverId.isNotBlank()) buildCoverUrl(coverId) else null,
                    streamUrl = getStreamUrl(id),
                    durationMs = durationSec * 1000,
                    trackNumber = track,
                    discNumber = disc,
                    year = year,
                    bitrate = bitrate
                )
            }
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getAlbumSongs failed", e)
            emptyList()
        }
    }

    override suspend fun getArtists(): List<Artist> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getArtists")

            // 无响应 = 请求失败，不是「没有艺术家」；交给上层产生 UiState.Error
            val json = executeRequest(url)
                ?: throw IllegalStateException("Navidrome getArtists 无响应")
            val subsonic = json.getAsJsonObject("subsonic-response")
            val artistsWrap = subsonic?.getAsJsonObject("artists")
            val indices = artistsWrap?.getAsJsonArray("index")
                ?: return@withContext emptyList<Artist>()

            val result = mutableListOf<Artist>()
            indices.forEach { indexElem ->
                val artists = indexElem.asJsonObject.getAsJsonArray("artist") ?: return@forEach
                artists.forEach { artistElem ->
                    val obj = artistElem.asJsonObject
                    val id = obj.get("id")?.asString ?: return@forEach
                    val name = EncodingUtils.fixEncoding(obj.get("name")?.asString) ?: "Unknown"
                    val albumCount = obj.get("albumCount")?.asInt ?: 0
                    result.add(
                        Artist(
                            id = id,
                            name = name,
                            coverUrl = buildCoverUrl(id),
                            albumCount = albumCount
                        )
                    )
                }
            }
            result
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getArtists failed", e)
            throw e
        }
    }

    override suspend fun getArtistSongs(artistId: String, artistName: String?): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getArtist") + "&id=$artistId"
            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val artist = subsonic?.getAsJsonObject("artist")
            val albums = artist?.getAsJsonArray("album")
                ?: return@withContext emptyList<Song>()

            // 并发请求所有专辑的歌曲，supervisorScope 隔离单个请求失败
            val allSongs = supervisorScope {
                albums.map { albumElem ->
                    async {
                        val albumId = albumElem.asJsonObject.get("id")?.asString ?: return@async emptyList<Song>()
                        getAlbumSongs(albumId)
                    }
                }.awaitAll().flatten()
            }.toMutableList()

            // ⛔ getArtist 只返回「album artist = 该歌手」的专辑。合辑里 track artist
            // 是该歌手但 album artist 不是的歌不会出现在这些专辑中 ⇒ 详情页漏歌。
            // 用 search3 按名字补漏，再按 ArtistSplitter 精确过滤 artist 字段（search3 的
            // query 会匹配标题/专辑名，不过滤会混入无关歌曲）。按 id 去重，补漏失败不影响主结果。
            if (!artistName.isNullOrBlank()) {
                val queryName = artistName // 先取本地 val：lambda 内对可空参数的智能转换不可靠
                try {
                    val seenIds = allSongs.map { it.id }.toSet()
                    val supplementary = searchSongs(queryName)
                        .filter { ArtistSplitter.containsArtist(it.artist, queryName) }
                        .filterNot { it.id in seenIds }
                    allSongs.addAll(supplementary)
                    AppLog.d("NavidromeAdapter", "getArtistSongs($artistId): albums=${albums.size()} songs=${allSongs.size} supplementary=${supplementary.size}")
                } catch (e: Exception) {
                    AppLog.w("NavidromeAdapter", "getArtistSongs search3 补漏失败: ${e.message?.take(50)}")
                }
            }
            allSongs
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getArtistSongs failed", e)
            emptyList()
        }
    }

    override suspend fun getSongs(limit: Int, offset: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getSongs") + "&type=alphabeticalByName&size=$limit&offset=$offset"

            val json = executeRequest(url) ?: return@withContext fallbackGetSongs(limit, offset)
            val subsonic = json.getAsJsonObject("subsonic-response") ?: return@withContext fallbackGetSongs(limit, offset)

            // 尝试标准格式: subsonic-response > songs > song[]
            var songs = subsonic.getAsJsonObject("songs")?.getAsJsonArray("song")
            // 尝试替代格式: subsonic-response > song[]（直接数组）
            if (songs == null) {
                songs = subsonic.getAsJsonArray("song")
            }
            // B8 修复：只有当 songs 字段完全缺失（端点异常/格式不兼容）时才走 fallback。
            // 原实现把「songs 存在但为空数组」也当作异常触发 fallback，导致翻到末页时
            // （正常返回空数组）每次都遍历全部专辑做 N+1 请求，大曲库下卡死。
            if (songs == null) {
                return@withContext fallbackGetSongs(limit, offset)
            }
            // songs 存在但为空 → 正常末页，直接返回空列表，不再 fallback
            if (songs.size() == 0) {
                return@withContext emptyList<Song>()
            }

            songs.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown"
                val artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: ""
                val album = EncodingUtils.fixEncoding(obj.get("album")?.asString) ?: ""
                val albumId = obj.get("albumId")?.asString ?: ""
                val track = obj.get("track")?.asInt ?: 0
                val disc = obj.get("discNumber")?.asInt ?: 1
                val year = obj.get("year")?.asInt
                val durationSec = obj.get("duration")?.asLong ?: 0L
                val bitrate = obj.get("bitRate")?.asInt ?: 0
                val coverId = obj.get("coverArt")?.asString ?: ""

                Song(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    albumId = albumId,
                    coverUrl = if (coverId.isNotBlank()) buildCoverUrl(coverId) else null,
                    streamUrl = getStreamUrl(id),
                    durationMs = durationSec * 1000,
                    trackNumber = track,
                    discNumber = disc,
                    year = year,
                    bitrate = bitrate
                )
            }
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getSongs failed", e)
            fallbackGetSongs(limit, offset)
        }
    }

    /**
     * 当 getSongs 端点不可用或返回空时的兜底方案：
     * 遍历所有专辑获取歌曲，再按分页裁剪。
     */
    private suspend fun fallbackGetSongs(limit: Int, offset: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            AppLog.w("NavidromeAdapter", "fallbackGetSongs: falling back to album iteration")
            // size=5000：覆盖绝大多数个人曲库规模，同时留在 Subsonic 服务端典型上限内
            val albumUrl = buildRestUrl("getAlbumList2") + "&type=alphabeticalByName&size=5000&offset=0"
            val json = executeRequest(albumUrl) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val albumList = subsonic?.getAsJsonObject("albumList2")?.getAsJsonArray("album")
                ?: return@withContext emptyList<Song>()

            val albumCount = albumList.size()
            AppLog.w("NavidromeAdapter", "fallbackGetSongs: iterating $albumCount albums (may be slow for large libraries)")

            val allSongs = supervisorScope {
                albumList.map { albumElem ->
                    async {
                        val albumId = albumElem.asJsonObject.get("id")?.asString
                            ?: return@async emptyList<Song>()
                        getAlbumSongs(albumId)
                    }
                }.awaitAll().flatten()
            }

            // 按分页裁剪
            allSongs.drop(offset).take(limit)
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "fallbackGetSongs failed", e)
            emptyList()
        }
    }

    override suspend fun searchSongs(query: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = buildRestUrl("search2") +
                    "&query=$encodedQuery&songCount=200&artistCount=0&albumCount=0"

            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val searchRes = subsonic?.getAsJsonObject("searchResult2")
            val songs = searchRes?.getAsJsonArray("song")
                ?: return@withContext emptyList<Song>()

            songs.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown"
                val artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: ""
                val album = EncodingUtils.fixEncoding(obj.get("album")?.asString) ?: ""
                val albumId = obj.get("albumId")?.asString ?: ""
                val track = obj.get("track")?.asInt ?: 0
                val durationSec = obj.get("duration")?.asLong ?: 0L
                val coverId = obj.get("coverArt")?.asString ?: ""

                Song(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    albumId = albumId,
                    coverUrl = if (coverId.isNotBlank()) buildCoverUrl(coverId) else null,
                    streamUrl = getStreamUrl(id),
                    durationMs = durationSec * 1000,
                    trackNumber = track
                )
            }
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "searchSongs failed", e)
            emptyList()
        }
    }

    override suspend fun getRecentSongs(): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getAlbumList2") + "&type=newest&size=20"
            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val albumList = subsonic?.getAsJsonObject("albumList2")
            val albums = albumList?.getAsJsonArray("album")
                ?: return@withContext emptyList<Song>()

            // 并发请求所有专辑的歌曲，每个专辑最多取 5 首
            val deferredSongs = albums.take(20).map { albumElem ->
                async {
                    val albumId = albumElem.asJsonObject.get("id")?.asString ?: return@async emptyList<Song>()
                    getAlbumSongs(albumId).take(5)
                }
            }
            val recentSongs = deferredSongs.awaitAll().flatten()
            recentSongs.take(100)
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getRecentSongs failed", e)
            emptyList()
        }
    }

    override fun getStreamUrl(songId: String): String =
        buildRestUrl("stream") + "&id=$songId"

    override fun getCoverUrl(songId: String): String =
        buildCoverUrl(songId).also {
            AppLog.d("NavidromeAdapter", "getCoverUrl: id=$songId")
        }

    override fun getCoverUrlCandidates(song: Song): List<String> {
        val urls = mutableListOf<String>()
        // 1. 歌曲/专辑封面（coverArt 字段已填充到 coverUrl）
        song.coverUrl?.let { urls.add(it) }
        // albumId 在 Subsonic 中也是合法的 coverArt id，作为 fallback
        song.albumId?.takeIf { it.isNotBlank() }?.let {
            val albumCoverUrl = buildCoverUrl(it)
            if (albumCoverUrl !in urls) urls.add(albumCoverUrl)
        }
        // 2. 艺术家封面
        song.artistId?.takeIf { it.isNotBlank() }?.let {
            urls.add(buildCoverUrl(it))
        }
        return urls.distinct().filter { it.isNotBlank() }
    }

    override suspend fun getSongTechnicalInfo(songId: String): SongTechnicalInfo? = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getSong") + "&id=$songId"
            val json = executeRequest(url) ?: return@withContext null
            val subsonic = json.getAsJsonObject("subsonic-response") ?: return@withContext null
            val songObj = subsonic.getAsJsonObject("song") ?: return@withContext null

            val codec = songObj.get("contentType")?.asString?.substringAfter("/")?.uppercase()
                ?: songObj.get("suffix")?.asString?.uppercase() ?: ""
            val bitrate = songObj.get("bitRate")?.asInt ?: 0
            val durationSec = songObj.get("duration")?.asLong ?: 0L
            val size = songObj.get("size")?.asLong ?: 0L

            // Subsonic API 不直接返回采样率和声道数，尝试从其他字段推断或留空
            // Navidrome 支持通过 getSong 获取更完整的元数据
            SongTechnicalInfo(
                codec = codec,
                bitrate = bitrate,
                sampleRate = 0,
                channels = 0,
                fileSize = size,
                durationMs = durationSec * 1000,
                format = codec
            )
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getSongTechnicalInfo failed", e)
            null
        }
    }

    override suspend fun getLyrics(songId: String): String? = withContext(Dispatchers.IO) {
        try {
            // 1. 通过 getSong 获取歌曲元数据（artist + title）
            val songUrl = buildRestUrl("getSong") + "&id=$songId"
            val songJson = executeRequest(songUrl) ?: return@withContext null
            val subsonic = songJson.getAsJsonObject("subsonic-response")
            val song = subsonic?.getAsJsonObject("song") ?: return@withContext null
            val artist = EncodingUtils.fixEncoding(song.get("artist")?.asString) ?: return@withContext null
            val title = EncodingUtils.fixEncoding(song.get("title")?.asString) ?: return@withContext null

            // 2. 调用 Subsonic getLyrics 端点（按歌手+标题搜索）
            val encodedArtist = java.net.URLEncoder.encode(artist, "UTF-8")
            val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
            val lyricsUrl = buildRestUrl("getLyrics") + "&artist=$encodedArtist&title=$encodedTitle"
            val lyricsJson = executeRequest(lyricsUrl) ?: return@withContext null
            val lyricsSubsonic = lyricsJson.getAsJsonObject("subsonic-response")
            val lyrics = lyricsSubsonic?.getAsJsonObject("lyrics")
            val value = lyrics?.get("value")?.asString
            if (value != null) EncodingUtils.fixEncoding(value) else null
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getLyrics failed", e)
            null
        }
    }

    /**
     * P1-3 修复（2026-09-22 审查）：原走接口默认 emptyList()，「年代」维度恒空。
     * 与 SubsonicAdapter.getYears 同款：分页拉歌提取年份（上限 20 页 × 500 = 1 万首，
     * 超出部分不保证完整，取舍与 Subsonic 一致）。
     */
    override suspend fun getYears(): List<Int> = withContext(Dispatchers.IO) {
        try {
            val allYears = mutableSetOf<Int>()
            val pageSize = 500
            var offset = 0
            var maxPages = 20
            while (maxPages-- > 0) {
                val batch = getSongs(pageSize, offset)
                if (batch.isEmpty()) break
                batch.filter { it.year != null }.forEach { allYears.add(it.year!!) }
                offset += pageSize
            }
            allYears.sorted()
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getYears failed", e)
            emptyList()
        }
    }

    // ========== F-1 扩展接口 ==========

    // --- 播放列表 ---
    override suspend fun getPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getPlaylists")
            val json = executeRequest(url) ?: return@withContext emptyList<Playlist>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val playlistsWrap = subsonic?.getAsJsonObject("playlists")
            val playlists = playlistsWrap?.getAsJsonArray("playlist")
                ?: return@withContext emptyList<Playlist>()
            playlists.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                Playlist(
                    id = id,
                    name = EncodingUtils.fixEncoding(obj.get("name")?.asString) ?: "Unknown",
                    songCount = obj.get("songCount")?.asInt ?: 0,
                    durationMs = (obj.get("duration")?.asLong ?: 0L) * 1000,
                    owner = EncodingUtils.fixEncoding(obj.get("owner")?.asString) ?: ""
                )
            }
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getPlaylists failed", e)
            emptyList()
        }
    }

    override suspend fun getPlaylistSongs(playlistId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getPlaylist") + "&id=$playlistId"
            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val playlist = subsonic?.getAsJsonObject("playlist")
                ?: return@withContext emptyList<Song>()
            val entries = playlist.getAsJsonArray("entry")
                ?: return@withContext emptyList<Song>()

            val playlistName = EncodingUtils.fixEncoding(playlist.get("name")?.asString) ?: ""

            entries.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown"
                val artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: ""
                val albumName = EncodingUtils.fixEncoding(obj.get("album")?.asString) ?: ""
                val track = obj.get("track")?.asInt ?: 0
                val disc = obj.get("discNumber")?.asInt ?: 1
                val year = obj.get("year")?.asInt
                val durationSec = obj.get("duration")?.asLong ?: 0L
                val bitrate = obj.get("bitRate")?.asInt ?: 0
                val coverId = obj.get("coverArt")?.asString ?: ""

                Song(
                    id = id,
                    title = title,
                    artist = artist,
                    album = albumName,
                    // P1-4 修复（2026-09-22 审查）：原为 playlistId——歌单条目自身的 albumId
                    // 被丢弃，导致从歌单跳专辑 / 按专辑找封面全部错位（SubsonicAdapter 同构
                    // 实现是正确的）。恢复解析条目真实 albumId。
                    albumId = obj.get("albumId")?.asString ?: "",
                    coverUrl = if (coverId.isNotBlank()) buildCoverUrl(coverId) else null,
                    streamUrl = getStreamUrl(id),
                    durationMs = durationSec * 1000,
                    trackNumber = track,
                    discNumber = disc,
                    year = year,
                    bitrate = bitrate
                )
            }
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getPlaylistSongs failed", e)
            emptyList()
        }
    }

    override suspend fun createPlaylist(name: String): Playlist? = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("createPlaylist") + "&name=${java.net.URLEncoder.encode(name, "UTF-8")}"
            val json = executeRequest(url) ?: return@withContext null
            val subsonic = json.getAsJsonObject("subsonic-response")
            val playlist = subsonic?.getAsJsonObject("playlist")
            val id = playlist?.get("id")?.asString ?: return@withContext null
            Playlist(id = id, name = name)
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "createPlaylist failed", e)
            null
        }
    }

    override suspend fun deletePlaylist(playlistId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("deletePlaylist") + "&id=$playlistId"
            val json = executeRequest(url) ?: return@withContext false
            val subsonic = json.getAsJsonObject("subsonic-response")
            subsonic?.get("status")?.asString == "ok"
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "deletePlaylist failed", e)
            false
        }
    }

    override suspend fun addToPlaylist(playlistId: String, songId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("updatePlaylist") + "&playlistId=$playlistId&songIdToAdd=$songId"
            val json = executeRequest(url) ?: return@withContext false
            val subsonic = json.getAsJsonObject("subsonic-response")
            subsonic?.get("status")?.asString == "ok"
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "addToPlaylist failed", e)
            false
        }
    }

    override suspend fun removeFromPlaylist(playlistId: String, songId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("updatePlaylist") + "&playlistId=$playlistId&songIdToRemove=$songId"
            val json = executeRequest(url) ?: return@withContext false
            val subsonic = json.getAsJsonObject("subsonic-response")
            subsonic?.get("status")?.asString == "ok"
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "removeFromPlaylist failed", e)
            false
        }
    }

    // --- 歌单同步结构化 API（保留 HTTP/协议错误，旧 Boolean/List 方法不变） ---
    override suspend fun getPlaylistsResult(): BackendResult<List<Playlist>> = withContext(Dispatchers.IO) {
        val result = requestPlaylistJson(buildRestUrl("getPlaylists"))
        if (result is BackendResult.Failure) return@withContext result
        val json = (result as BackendResult.Success).value
        val playlists = json?.getAsJsonObject("subsonic-response")
            ?.getAsJsonObject("playlists")?.let { it.getAsJsonArray("playlist") ?: emptyList() }
            ?: return@withContext BackendResult.Failure(BackendError.PROTOCOL)
        BackendResult.Success(playlists.mapNotNull { item ->
            val obj = item.asJsonObject
            val id = obj.get("id")?.asString ?: return@mapNotNull null
            Playlist(
                id = id,
                name = EncodingUtils.fixEncoding(obj.get("name")?.asString) ?: "Unknown",
                songCount = obj.get("songCount")?.asInt ?: 0,
                durationMs = (obj.get("duration")?.asLong ?: 0L) * 1000,
                owner = EncodingUtils.fixEncoding(obj.get("owner")?.asString) ?: "",
            )
        })
    }

    override suspend fun getPlaylistSongsResult(playlistId: String): BackendResult<List<Song>> =
        withContext(Dispatchers.IO) {
            val encodedId = java.net.URLEncoder.encode(playlistId, "UTF-8")
            val result = requestPlaylistJson(buildRestUrl("getPlaylist") + "&id=$encodedId")
            if (result is BackendResult.Failure) return@withContext result
            val json = (result as BackendResult.Success).value
            val playlist = json?.getAsJsonObject("subsonic-response")?.getAsJsonObject("playlist")
                ?: return@withContext BackendResult.Failure(BackendError.PROTOCOL)
            val entries = playlist.getAsJsonArray("entry") ?: emptyList()
            BackendResult.Success(entries.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val coverId = obj.get("coverArt")?.asString.orEmpty()
                Song(
                    id = id,
                    title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown",
                    artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: "",
                    album = EncodingUtils.fixEncoding(obj.get("album")?.asString) ?: "",
                    albumId = obj.get("albumId")?.asString ?: "",
                    coverUrl = if (coverId.isNotBlank()) buildCoverUrl(coverId) else null,
                    streamUrl = getStreamUrl(id),
                    durationMs = (obj.get("duration")?.asLong ?: 0L) * 1000,
                    trackNumber = obj.get("track")?.asInt ?: 0,
                    discNumber = obj.get("discNumber")?.asInt ?: 1,
                    year = obj.get("year")?.asInt,
                    bitrate = obj.get("bitRate")?.asInt ?: 0,
                )
            })
        }

    override suspend fun createPlaylistResult(name: String): BackendResult<Playlist> = withContext(Dispatchers.IO) {
        val url = buildRestUrl("createPlaylist") + "&name=${java.net.URLEncoder.encode(name, "UTF-8")}"
        when (val result = requestPlaylistJson(url)) {
            is BackendResult.Failure -> result
            is BackendResult.Success -> {
                val id = result.value?.getAsJsonObject("subsonic-response")
                    ?.getAsJsonObject("playlist")?.get("id")?.asString
                if (id == null) BackendResult.Failure(BackendError.PROTOCOL)
                else BackendResult.Success(Playlist(id = id, name = name))
            }
        }
    }

    override suspend fun addToPlaylistResult(playlistId: String, songId: String): BackendResult<Unit> =
        withContext(Dispatchers.IO) {
            val url = buildRestUrl("updatePlaylist") +
                "&playlistId=${java.net.URLEncoder.encode(playlistId, "UTF-8")}" +
                "&songIdToAdd=${java.net.URLEncoder.encode(songId, "UTF-8")}"
            when (val result = requestPlaylistJson(url)) {
                is BackendResult.Success -> BackendResult.Success(Unit)
                is BackendResult.Failure -> result
            }
        }

    private suspend fun requestPlaylistJson(url: String): BackendResult<JsonObject?> = try {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                return@use BackendResult.Failure(backendErrorFromHttpStatus(response.code))
            }
            val body = response.body?.string()
            if (body.isNullOrBlank()) return@use BackendResult.Failure(BackendError.PROTOCOL)
            val json = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
                ?: return@use BackendResult.Failure(BackendError.PROTOCOL)
            json.subsonicBackendError()?.let { return@use BackendResult.Failure(it) }
            BackendResult.Success(json)
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        BackendResult.Failure(backendErrorFromThrowable(e))
    }

    // --- 收藏 ---
    // 本地缓存收藏状态（H1 修复后仅用于展示层同步，不再决定 star/unstar 方向）
    private val _favoriteIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    override suspend fun toggleFavorite(songId: String, isCurrentlyFavorite: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            // H1 修复（2026-10-06，代码审查报告 §3）：以调用方传入的 isCurrentlyFavorite
            // 决定 star/unstar（对齐 SubsonicAdapter:458-467 的修复版本）。
            // 旧实现完全忽略入参、依赖 _favoriteIds 会话缓存判断——当 getStarred2 拉取
            // 失败或返回空时（调用方传入值同样来自第二次 getStarred2），两边同为空、
            // 全部走 star，用户无法取消收藏。_favoriteIds 仅用于展示层同步。
            val method = if (isCurrentlyFavorite) "unstar" else "star"
            val url = buildRestUrl(method) + "&id=$songId"
            val json = executeRequest(url)
            val responseSubsonic = json?.getAsJsonObject("subsonic-response")
            val ok = responseSubsonic?.get("status")?.asString == "ok"
            if (ok) {
                if (isCurrentlyFavorite) _favoriteIds.remove(songId) else _favoriteIds.add(songId)
                // H1 修复：成功后与服务器全量对账，消除本地缓存与服务器漂移
                runCatching { syncFavoritesFromServer() }
            }
            ok
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "toggleFavorite failed", e)
            false
        }
    }

    private suspend fun syncFavoritesFromServer() {
        // H1 修复后的展示层同步：重新拉取全量收藏到 _favoriteIds（仅用于 UI 展示状态，
        // 不再参与 star/unstar 方向判定）。失败时保留旧缓存并记日志。
        val url = buildRestUrl("getStarred2")
        val json = executeRequest(url) ?: run {
            AppLog.w("NavidromeAdapter", "syncFavoritesFromServer: getStarred2 request failed")
            return
        }
        val subsonic = json.getAsJsonObject("subsonic-response")
        val starredWrap = subsonic?.getAsJsonObject("starred2")
        val songs = starredWrap?.getAsJsonArray("song") ?: run {
            AppLog.w("NavidromeAdapter", "syncFavoritesFromServer: starred2/song missing")
            return
        }
        _favoriteIds.clear()
        for (i in 0 until songs.size()) {
            songs[i].asJsonObject.get("id")?.asString?.let { _favoriteIds.add(it) }
        }
    }

    override suspend fun getFavorites(): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getStarred2")
            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val starredWrap = subsonic?.getAsJsonObject("starred2")
            val songs = starredWrap?.getAsJsonArray("song")
                ?: return@withContext emptyList<Song>()
            songs.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                Song(
                    id = id,
                    title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown",
                    artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: "",
                    album = EncodingUtils.fixEncoding(obj.get("album")?.asString) ?: "",
                    albumId = obj.get("albumId")?.asString ?: "",
                    coverUrl = buildCoverUrl(obj.get("coverArt")?.asString ?: id),
                    streamUrl = getStreamUrl(id),
                    durationMs = (obj.get("duration")?.asLong ?: 0L) * 1000,
                    trackNumber = obj.get("track")?.asInt ?: 0
                )
            }
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getFavorites failed", e)
            emptyList()
        }
    }

    // --- 评分 ---
    override suspend fun setRating(songId: String, rating: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("setRating") + "&id=$songId&rating=$rating"
            val json = executeRequest(url) ?: return@withContext false
            val subsonic = json.getAsJsonObject("subsonic-response")
            subsonic?.get("status")?.asString == "ok"
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "setRating failed", e)
            false
        }
    }

    // --- 流派 ---
    override suspend fun getGenres(): List<Genre> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getGenres")
            val json = executeRequest(url) ?: return@withContext emptyList<Genre>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val genresWrap = subsonic?.getAsJsonObject("genres")
            val genres = genresWrap?.getAsJsonArray("genre")
                ?: return@withContext emptyList<Genre>()
            genres.mapNotNull { item ->
                val obj = item.asJsonObject
                val name = obj.get("value")?.asString ?: return@mapNotNull null
                Genre(
                    id = name,
                    name = name,
                    songCount = obj.get("songCount")?.asInt ?: 0
                )
            }
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getGenres failed", e)
            emptyList()
        }
    }

    override suspend fun getSongsByGenre(genre: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val encodedGenre = java.net.URLEncoder.encode(genre, "UTF-8")
            val url = buildRestUrl("getSongsByGenre") + "&genre=$encodedGenre&size=500"
            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val songsByGenre = subsonic?.getAsJsonObject("songsByGenre")
            val songs = songsByGenre?.getAsJsonArray("song")
                ?: return@withContext emptyList<Song>()
            songs.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                Song(
                    id = id,
                    title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown",
                    artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: "",
                    album = EncodingUtils.fixEncoding(obj.get("album")?.asString) ?: "",
                    albumId = obj.get("albumId")?.asString ?: "",
                    coverUrl = buildCoverUrl(obj.get("coverArt")?.asString ?: id),
                    streamUrl = getStreamUrl(id),
                    durationMs = (obj.get("duration")?.asLong ?: 0L) * 1000,
                    trackNumber = obj.get("track")?.asInt ?: 0,
                    year = obj.get("year")?.asInt
                )
            }
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getSongsByGenre failed", e)
            emptyList()
        }
    }

    override suspend fun getSongsByYearRange(fromYear: Int, toYear: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            // Subsonic doesn't have a direct year-range endpoint; paginate through songs
            val pageSize = 500
            val allSongs = mutableListOf<Song>()
            var offset = 0
            var maxPages = 200
            while (maxPages-- > 0) {
                val batch = getSongs(pageSize, offset)
                if (batch.isEmpty()) break
                allSongs.addAll(batch.filter { it.year != null && it.year in fromYear..toYear })
                offset += pageSize
            }
            allSongs
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getSongsByYearRange failed", e)
            emptyList()
        }
    }

    // --- Scrobble ---
    override suspend fun scrobblePlay(songId: String, timestamp: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("scrobble") + "&id=$songId&time=$timestamp"
            val json = executeRequest(url) ?: return@withContext false
            val subsonic = json.getAsJsonObject("subsonic-response")
            subsonic?.get("status")?.asString == "ok"
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "scrobblePlay failed", e)
            false
        }
    }

    // --- 随机歌曲 ---
    override suspend fun getRandomSongs(limit: Int): List<Song> = withContext(Dispatchers.IO) {
        try {
            val url = buildRestUrl("getRandomSongs") + "&size=$limit"
            val json = executeRequest(url) ?: return@withContext emptyList<Song>()
            val subsonic = json.getAsJsonObject("subsonic-response")
            val randomWrap = subsonic?.getAsJsonObject("randomSongs")
            val songs = randomWrap?.getAsJsonArray("song")
                ?: return@withContext emptyList<Song>()
            songs.mapNotNull { item ->
                val obj = item.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                Song(
                    id = id,
                    title = EncodingUtils.fixEncoding(obj.get("title")?.asString) ?: "Unknown",
                    artist = EncodingUtils.fixEncoding(obj.get("artist")?.asString) ?: "",
                    album = EncodingUtils.fixEncoding(obj.get("album")?.asString) ?: "",
                    albumId = obj.get("albumId")?.asString ?: "",
                    coverUrl = buildCoverUrl(obj.get("coverArt")?.asString ?: id),
                    streamUrl = getStreamUrl(id),
                    durationMs = (obj.get("duration")?.asLong ?: 0L) * 1000,
                    trackNumber = obj.get("track")?.asInt ?: 0,
                    year = obj.get("year")?.asInt
                )
            }
        } catch (e: Exception) {
            AppLog.e("NavidromeAdapter", "getRandomSongs failed", e)
            emptyList()
        }
    }

    /**
     * 释放 OkHttp 连接资源。
     * Navidrome 使用无状态认证，无服务端 session 需要清理。
     * 此处关闭客户端连接池，防止连接泄漏。
     */
    override fun close() {
        // R-6：连接池/线程池已共享（BackendRegistry 持有），此处禁止 shutdown/evictAll
        try {
            baseUrl = ""
            username = ""
            password = ""
            apiToken = ""
            salt = ""
            // L11：认证态是**两处**存的 —— initialize 时同步给 restClient 一份
            // （见 R-10 注释），close 只清了本类字段 ⇒ restClient 里那串明文 password / salt
            // 会跨「断开连接」一直驻留到下一次 initialize，任何延迟引用 restClient 的请求
            // （分页拉取、封面批量取）仍会带着旧口令打旧服务器。
            restClient.baseUrl = ""
            restClient.username = ""
            restClient.password = ""
            restClient.salt = ""
            AppLog.d("NavidromeAdapter", "close: auth state cleared (shared OkHttp pool retained)")
        } catch (e: Exception) {
            AppLog.w("NavidromeAdapter", "close failed", e)
        }
    }

    // --- 内部辅助方法 ---

    private fun buildRestUrl(method: String): String = restClient.buildRestUrl(method)

    // 修复：此前未覆盖该方法，沿用接口默认实现恒返回 0（UI“共 M 首”永远显示 0）。
    override suspend fun getSongsTotalCount(): Int = restClient.songsTotalCount()

    private fun buildCoverUrl(coverArtId: String): String = restClient.buildCoverUrl(coverArtId)

    private suspend fun executeRequest(url: String): JsonObject? = restClient.executeRequest(url)

    companion object {
        /**
         * Subsonic API 协议版本号。
         * - 这是 API 协议版本（不是客户端版本），用于服务端兼容性判断
         * - Subsonic API 当前稳定版本为 1.16.1，Navidrome 完全兼容
         * - 升级前提：服务端明确要求更高版本（如新增 API 字段时）
         */
        private const val API_VERSION = "1.16.1"

        /** 客户端标识（Subsonic API c 参数，服务端用于区分客户端类型） */
        private const val CLIENT_NAME = "NASMusicTV"
    }
}
