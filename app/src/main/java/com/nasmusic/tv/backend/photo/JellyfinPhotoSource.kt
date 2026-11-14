package com.nasmusic.tv.backend.photo

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Jellyfin 照片库来源（**手机 + 电视**）
 *
 * ## 接口
 *
 * - 全局列表：`/Items?IncludeItemTypes=Photo&Recursive=true&fields=Width,Height,Path&UserId=…`
 * - **相册级列表**：`/Items?IncludeItemTypes=Photo&ParentId=<相册 id>&Recursive=true&…`（§10.393）
 * - 媒体库根：`/Items?Recursive=false&UserId=…`
 * - 取图：`/Items/{id}/Images/Primary?maxWidth=1920&quality=90`
 *
 * ⚠️ `maxWidth=1920` 是刻意的 —— 音频封面用的是 512，照片放大到全屏会糊。
 *
 * ## ⛔ 为什么必须有「相册级回退」（2026-11-12 电视实测 + 服务器直查）
 *
 * 应用原来只用**全局递归查询**取照片。实测（Jellyfin 10.11.5）：
 * 全局递归集合 51592 条里按类型求和后 **Photo = 0、PhotoAlbum = 0**
 * ⇒ `homevideos` 库的照片挂在 `PhotoAlbum` 下，而**全局递归查询不下钻到相册**
 * ⇒ 全局查询恒为 0 张 ⇒ 照片墙的 NAS 来源永远空。
 *
 * ⇒ 现在：**先试全局查询（`photos` / `mixed` 库走这条），拿不到照片再逐相册查**。
 * 详细取证与策略见 [JellyfinPhotoLibrary]。
 *
 * ## 认证
 *
 * Jellyfin 用 `api_key` **查询参数**认证。`baseUrl` / `apiToken` / `userId` 都是
 * `JellyfinAdapter` 的私有状态，外部拿不到 ⇒ 通过
 * [BackendAdapter.buildAuthenticatedUrl] + [BackendAdapter.currentUserId] 两个
 * **新增的通用成员**取得（不是为了照片专门开的后门，任何需要任意路径的功能都能用）。
 *
 * Navidrome 等未覆盖 `buildAuthenticatedUrl` 的后端返回 `null`
 * ⇒ 本来源直接判 `UNAVAILABLE`（照片墙不是 Jellyfin 专属能力，但确实只有它支持）。
 *
 * ## 宽高 / 时间
 *
 * - 宽高：`fields=Width,Height` **免费**返回 ⇒ 填真实值（§14.2.1 宽高契约）
 * - 拍摄时间：`fields=PremiereDate` ⇒ [PhotoRef.capturedAt]（`DateCreated` 是入库时间，兜底用）
 * - 扩展名：`fields=Path` 返回实际文件路径 ⇒ 从文件名判白名单（HEIC / RAW 过滤）。
 *   ⛔ Jellyfin 的 `Name` **不带扩展名**，不能拿它过滤 —— v2.37.4 之前正是这里
 *   把全部照片滤成 0 张（见 §10.188）
 * - 时间：`DateCreated` 是 ISO 8601 ⇒ 必须自己转成**秒**
 *   ⛔ 不能用 `java.time`（需要 API 26，本项目 `minSdk = 22`）
 *   ⇒ 用 `SimpleDateFormat` 手工归一化（见 [isoToEpochSeconds]）
 *   ⚠️ 实测 Jellyfin 的**照片条目 `DateCreated` 常为空** ⇒ 归一化后是 0，
 *   不影响展示（池是打乱的）；JELLYFIN 项在 [PhotoDedup] 里整体豁免，也不会误杀。
 * - `size`：需要 `MediaSources` 才有，而 **JELLYFIN 项不参与跨来源去重**（§6.8）
 *   ⇒ 不为此多请求字段，填 `0`
 */
class JellyfinPhotoSource(
    private val adapterProvider: () -> BackendAdapter?,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val client: OkHttpClient = defaultClient(),
) : PhotoSource {

    override val kind: PhotoSourceKind = PhotoSourceKind.JELLYFIN

    /**
     * 「上次扫描结果」的缓存与判定（**显示用**）。
     *
     * ⚠️ 它**不能**用来挡住扫描 ⇒ 每轮扫描前会先调用 [invalidateStatus] 清空
     * （否则「重新扫描」永远扫不出东西，见 [PhotoScanVerdict] 的说明）。
     */
    private val verdict = PhotoScanVerdict()

    override suspend fun status(): PhotoSourceStatus = withContext(Dispatchers.IO) {
        val adapter = adapterProvider() ?: return@withContext PhotoSourceStatus.NOT_CONNECTED
        // 后端不支持任意路径访问（如 Navidrome）⇒ 照片通道不可用
        if (adapter.buildAuthenticatedUrl(PATH_ITEMS) == null) {
            return@withContext PhotoSourceStatus.UNAVAILABLE
        }
        verdict.statusWhenConnected()
    }

    /**
     * 作废「上次扫到 0 张」的缓存判定 ⇒ 让下一轮扫描**真的**去问服务器。
     *
     * ⛔ 不做这一步的话：一旦某轮扫到 0 张（哪怕只是因为网络抖了一下），
     * [status] 会一直返回 [PhotoSourceStatus.NO_PHOTO_LIBRARY]
     * ⇒ 聚合器直接跳过 [listPhotos] ⇒ **「重新扫描」永远扫不出东西**。
     */
    override fun invalidateStatus() {
        verdict.invalidate()
    }

    override suspend fun listPhotos(): List<PhotoRef> = withContext(Dispatchers.IO) {
        val adapter = adapterProvider() ?: run {
            verdict.onScanFailed()
            return@withContext emptyList()
        }

        // ── 路径 1：全局递归查询（photos / mixed 库走这条，一次请求拿全）──
        val global = fetchAllItems(adapter, GLOBAL_QUERY, MAX_PHOTOS)
        val globalPhotos = global.items.mapNotNull { elementToRef(it) }
        if (globalPhotos.isNotEmpty()) {
            verdict.onScanSucceeded(globalPhotos.size)
            AppLog.w(TAG, "全局递归查询拿到 ${globalPhotos.size} 张照片")
            return@withContext globalPhotos
        }

        // ── 路径 2：相册级回退（homevideos 库走这条，§10.393）──
        if (!JellyfinPhotoLibrary.shouldFallbackToAlbums(globalPhotos.size)) {
            verdict.onScanFailed()
            return@withContext emptyList()
        }
        val byAlbum = queryByAlbum(adapter)
        // ⛔ **有照片就是有照片** —— 部分相册失败不影响已有结果（只有全军覆没才算失败）
        if (byAlbum.photos.isNotEmpty()) {
            verdict.onScanSucceeded(byAlbum.photos.size)
            AppLog.w(TAG, "相册级回退拿到 ${byAlbum.photos.size} 张照片")
            return@withContext byAlbum.photos
        }
        if (byAlbum.failed) {
            // ⛔ 失败 ≠ 0 张（见 [PhotoScanVerdict] 铁律 1）
            verdict.onScanFailed()
            return@withContext emptyList()
        }
        // 服务器确实一张都没有
        verdict.onScanSucceeded(0)
        AppLog.w(TAG, "相册级回退：服务器上确实没有照片")
        emptyList()
    }

    override suspend fun openStream(ref: PhotoRef): InputStream? = withContext(Dispatchers.IO) {
        val itemId = PhotoIds.payloadOf(ref.id) ?: return@withContext null
        val adapter = adapterProvider() ?: return@withContext null
        val url = adapter.buildAuthenticatedUrl(
            "$PATH_ITEMS/$itemId/Images/Primary",
            "maxWidth=$MAX_IMAGE_WIDTH&quality=90",
        ) ?: return@withContext null

        runCatching {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) {
                response.close()
                null
            } else {
                // ⚠️ 调用方关闭该流即释放 OkHttp 连接（byteStream 的 close 会关闭 body）
                response.body?.byteStream()
            }
        }.getOrNull()
    }

    // ────────────────────────── 相册级回退 ──────────────────────────

    /**
     * 相册级回退（§10.393）
     *
     * ```
     * /Items?Recursive=false                      → 顶层媒体库根（CollectionFolder）
     *   └─ /Items?ParentId=<根 id>&Recursive=false → 根下的相册（PhotoAlbum / Folder）
     *        └─ /Items?IncludeItemTypes=Photo&ParentId=<相册 id>&Recursive=true → 照片
     * ```
     *
     * ⛔ **自限**：第一步（列媒体库根）拿不到东西就**立刻收手** ⇒
     * 服务器不可达时不会打出几百个请求。
     */
    private suspend fun queryByAlbum(adapter: BackendAdapter): AlbumScan {
        val roots = fetchLibraryRoots(adapter)
        if (roots.isEmpty()) {
            AppLog.w(TAG, "相册级回退：没列到媒体库根（服务器不可达或没有媒体库）")
            return AlbumScan(emptyList(), failed = true)
        }

        val containers = ArrayList<String>(512)
        for (root in roots) {
            if (containers.size >= JellyfinPhotoLibrary.MAX_ALBUM_QUERIES) {
                AppLog.w(TAG, "相册级回退：相册数超过上限 ${JellyfinPhotoLibrary.MAX_ALBUM_QUERIES}，截断")
                break
            }
            containers += fetchAlbumContainers(adapter, root)
        }
        if (containers.isEmpty()) {
            // 有媒体库根但一个相册/文件夹都没有 ⇒ 服务器上确实没有照片（**不是失败**）
            AppLog.w(TAG, "相册级回退：媒体库根下没有相册/文件夹")
            return AlbumScan(emptyList(), failed = false)
        }
        AppLog.w(TAG, "相册级回退：共 ${containers.size} 个相册容器，开始逐个抓取")

        val out = ArrayList<PhotoRef>(1024)
        var anyFailed = false
        for (chunk in containers.chunked(JellyfinPhotoLibrary.ALBUM_CONCURRENCY)) {
            val batch = coroutineScope {
                chunk.map { id ->
                    async(Dispatchers.IO) { fetchAlbumPhotos(adapter, id) }
                }.awaitAll()
            }
            for (result in batch) {
                if (result.failed) {
                    anyFailed = true
                    continue
                }
                for (photo in result.photos) {
                    if (out.size >= MAX_PHOTOS) return AlbumScan(out, failed = false)
                    out.add(photo)
                }
            }
            if (out.size >= MAX_PHOTOS) break
        }
        // ⛔ 全军覆没才算失败；部分相册失败仍算成功（有照片就是有照片）
        return AlbumScan(out, failed = anyFailed && out.isEmpty())
    }

    /** 顶层媒体库根的 id 列表（`/Items?Recursive=false` ⇒ 只返回 CollectionFolder） */
    private fun fetchLibraryRoots(adapter: BackendAdapter): List<String> {
        val res = fetchAllItems(adapter, ROOT_QUERY, MAX_ROOT_ITEMS)
        if (res.failed) return emptyList()
        return res.items
            .filter { JellyfinPhotoLibrary.isLibraryRoot(it.stringOr("Type", null)) }
            .mapNotNull { it.stringOr("Id", null) }
    }

    /** 某个媒体库根下的相册 / 文件夹 id 列表 */
    private fun fetchAlbumContainers(adapter: BackendAdapter, rootId: String): List<String> {
        val res = fetchAllItems(
            adapter,
            "$ALBUM_CONTAINER_QUERY&ParentId=$rootId",
            JellyfinPhotoLibrary.MAX_ALBUM_QUERIES,
        )
        if (res.failed) return emptyList()
        return res.items
            .filter { JellyfinPhotoLibrary.isAlbumContainer(it.stringOr("Type", null)) }
            .mapNotNull { it.stringOr("Id", null) }
    }

    /** 某个相册 / 文件夹里的照片 */
    private fun fetchAlbumPhotos(adapter: BackendAdapter, containerId: String): AlbumScan {
        val query = "$ALBUM_PHOTO_QUERY&ParentId=$containerId"
        val res = fetchAllItems(adapter, query, MAX_PHOTOS)
        if (res.failed) return AlbumScan(emptyList(), failed = true)
        return AlbumScan(res.items.mapNotNull { elementToRef(it) }, failed = false)
    }

    // ────────────────────────── 内部实现 ──────────────────────────

    /** 一次 `/Items` 查询的原始结果（**未**映射成 [PhotoRef]） */
    private class ItemsPage(val items: List<JsonObject>, val total: Int)

    /** 一次拉取的结果：[failed] = 查询**失败**（不是「查到 0 条」） */
    private class ItemsFetch(val items: List<JsonObject>, val failed: Boolean)

    /** 一次「按相册」的扫描结果 */
    private class AlbumScan(val photos: List<PhotoRef>, val failed: Boolean)

    /**
     * 分页拉取 `/Items`。
     *
     * @param query **不含** `StartIndex` / `Limit` / `UserId` 的查询串
     * @param maxItems 最多收集多少条
     * @return [ItemsFetch]；`failed = true` 表示**查询失败**（网络 / HTTP / 解析），
     *   与「查到 0 条」严格区分（[PhotoScanVerdict] 铁律 1）
     */
    private fun fetchAllItems(
        adapter: BackendAdapter,
        query: String,
        maxItems: Int,
    ): ItemsFetch {
        val out = ArrayList<JsonObject>()
        var startIndex = 0
        var total = -1
        var pages = 0
        while (pages < MAX_PAGES && out.size < maxItems) {
            val page = fetchOnePage(adapter, query, startIndex, pageSize) ?: return ItemsFetch(out, failed = true)
            if (total < 0) total = page.total
            if (page.items.isEmpty()) break
            out.addAll(page.items)
            startIndex += pageSize
            pages++
            // 已经取完
            if (total >= 0 && startIndex >= total) break
        }
        return ItemsFetch(out, failed = false)
    }

    /** 单页查询；返回 `null` = 查询失败 */
    private fun fetchOnePage(
        adapter: BackendAdapter,
        query: String,
        startIndex: Int,
        limit: Int,
    ): ItemsPage? {
        val full = buildString {
            append(query)
            append("&StartIndex=$startIndex")
            append("&Limit=$limit")
            val uid = adapter.currentUserId
            if (uid.isNotEmpty()) append("&UserId=$uid")
        }
        val url = adapter.buildAuthenticatedUrl(PATH_ITEMS, full) ?: return null
        return runCatching {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    AppLog.w(TAG, "photo query HTTP ${resp.code}")
                    return@use null
                }
                val body = resp.body?.string() ?: return@use null
                val root = JsonParser.parseString(body).asJsonObject
                val total = root.intOr("TotalRecordCount", 0)
                val arr = root.getAsJsonArray("Items")
                val items = if (arr == null) {
                    emptyList()
                } else {
                    val list = ArrayList<JsonObject>(arr.size())
                    for (el in arr) list.add(el.asJsonObject)
                    list
                }
                ItemsPage(items, total)
            }
        }.getOrElse {
            AppLog.e(TAG, "photo query failed: ${it.message}", it)
            null
        }
    }

    private fun elementToRef(o: JsonObject): PhotoRef? {
        val id = o.stringOr("Id", null) ?: return null
        // ⛔ Jellyfin 的 Name **不含文件扩展名**（如 '0001'、'图'），扩展名在 Path 字段里
        // ⇒ 必须从 Path 取文件名判白名单（HEIC / RAW 电视解不了）。
        //    拿 Name 过滤会把所有照片滤掉（v2.37.4 修复，§10.188）。
        //    Path 缺失时不过滤 —— Jellyfin 已按 IncludeItemTypes=Photo 返回，
        //    解码层对无法解码的项会跳过（PhotoWallController "skip undecodable photo"）。
        val path = o.stringOr("Path", null)
        if (path != null && !isSupportedPhotoPath(path)) return null
        val name = o.stringOr("Name", null) ?: id
        val created = isoToEpochSeconds(o.stringOr("DateCreated", null))
        return PhotoRef(
            id = PhotoIds.of(PhotoSourceKind.JELLYFIN, id),
            displayName = name,
            width = o.intOr("Width", 0),
            height = o.intOr("Height", 0),
            size = 0L,
            lastModified = created,
            dateAdded = created,
            source = PhotoSourceKind.JELLYFIN,
            // 拍摄时间：Jellyfin 的 `PremiereDate` 就是「拍摄/上映日期」，比 `DateCreated`
            // （入库时间）更接近真实拍摄时刻 ⇒ 优先它；两者都没有就留 0，
            // 由 `PhotoBuffer` 在解码期用 EXIF 补（NAS 上的原图通常带 DateTimeOriginal）。
            capturedAt = isoToEpochSeconds(o.stringOr("PremiereDate", null))
                .takeIf { it > 0L }
                ?: created,
        )
    }

    private companion object {
        const val TAG = "JellyfinPhotoSource"
        const val PATH_ITEMS = "/Items"
        const val MAX_IMAGE_WIDTH = 1920
        const val DEFAULT_PAGE_SIZE = 500
        const val MAX_PAGES = 40
        const val MAX_PHOTOS = 20000

        /** 顶层媒体库根（`/Items?Recursive=false` ⇒ 只返回 CollectionFolder） */
        const val MAX_ROOT_ITEMS = 200

        /** 全局递归查询（`photos` / `mixed` 库走这条） */
        const val GLOBAL_QUERY =
            "IncludeItemTypes=Photo&Recursive=true&fields=Width,Height,Path,PremiereDate" +
                "&SortBy=SortName&SortOrder=Ascending"

        /** 顶层媒体库根查询 */
        const val ROOT_QUERY = "Recursive=false"

        /** 媒体库根下的相册 / 文件夹查询 */
        const val ALBUM_CONTAINER_QUERY = "Recursive=false"

        /** 相册内的照片查询（**必须带 `ParentId`**，否则摸不到 homevideos 库的照片） */
        const val ALBUM_PHOTO_QUERY =
            "IncludeItemTypes=Photo&Recursive=true&fields=Width,Height,Path,PremiereDate" +
                "&SortBy=SortName&SortOrder=Ascending"

        // 「未知总数」常量已移到 [PhotoScanVerdict]（判定器与来源共用同一份口径）

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

// ────────────────────────── JSON 取值（null 安全） ──────────────────────────

private fun JsonObject.stringOr(key: String, fallback: String?): String? {
    val el = get(key) ?: return fallback
    if (el.isJsonNull) return fallback
    return runCatching { el.asString }.getOrDefault(fallback)
}

private fun JsonObject.intOr(key: String, fallback: Int): Int {
    val el = get(key) ?: return fallback
    if (el.isJsonNull) return fallback
    return runCatching { el.asInt }.getOrDefault(fallback)
}

/**
 * ISO 8601 → **Unix 秒**
 *
 * ⛔ 刻意不用 `java.time`（`Instant.parse` / `OffsetDateTime` 都要 API 26，
 * 而本项目 `minSdk = 22`，电视是 Android 5.1.1）。用 `SimpleDateFormat` 手工归一化。
 *
 * Jellyfin 的 `DateCreated` 形如：
 * - `2026-09-23T10:11:12.3456789Z`
 * - `2026-09-23T10:11:12Z`
 * - `2026-09-23T10:11:12.3456789+08:00`（带偏移，罕见）
 *
 * 处理：截到秒（去掉小数与 `Z`），按 UTC 解析。
 * ⚠️ 带偏移的写法会丢掉偏移量 —— 对本用途（仅用于排序 / 指纹）足够，
 * 且 Jellyfin 默认返回 UTC `Z`。
 */
internal fun isoToEpochSeconds(iso: String?): Long {
    if (iso.isNullOrBlank()) return 0L
    return runCatching {
        val core = iso.substringBefore('.').substringBefore('Z').trim()
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        (fmt.parse(core)?.time ?: 0L) / 1000L
    }.getOrDefault(0L)
}
