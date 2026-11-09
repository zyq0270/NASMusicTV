# -*- coding: utf-8 -*-
"""Batch A：M16/M17/M18/M19/M22/M15/M20/M21/M06/C02/M02。"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S = ""
CRLF = False


def load(rel):
    global S, CRLF
    p = os.path.join(ROOT, rel)
    S = io.open(p, "r", encoding="utf-8", newline="").read()
    CRLF = "\r\n" in S
    return p


def rep(a, b, tag, expect=1):
    global S
    a2 = a.replace("\n", "\r\n") if CRLF else a
    b2 = b.replace("\n", "\r\n") if CRLF else b
    n = S.count(a2)
    if n != expect:
        print("FAIL [%s] count=%d (expect %d)" % (tag, n, expect))
        sys.exit(1)
    S = S.replace(a2, b2)


def save(p):
    io.open(p, "w", encoding="utf-8", newline="").write(S)


# ══════════════════════════════════════════════════════════════
# M16：BilibiliMvService 直链白名单
# ══════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/backend/network/mv/BilibiliMvService.kt")
rep(
    """    /** 从 playurl 响应提取直链：优先 durl[].url，其次 dash.video[].baseUrl */
    internal fun extractPlayUrl(body: String): String? {""",
    """    /**
     * M16：该直链是否可信 —— 只接受 http/https 且 host 命中 B 站自有 CDN 后缀。
     *
     * ⛔ 直链最终交给 ExoPlayer 直接联网，而 `playurl` 的响应来自第三方代理端点：
     * 没有白名单时，一个被劫持/投毒的响应可以把播放器指向任意地址
     * （包括 `file://`、内网地址、纯 tracker）。CDN 域名集合按 2026-11 实测的
     * `upos-sz-mirror*.bilivideo.com` / `*.hdslb.com` / `upos-*.akamaized.net` 取，
     * 用**后缀**匹配以覆盖 regional/镜像子域。
     */
    private fun isTrustedMediaUrl(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        if (u.scheme != "https" && u.scheme != "http") return false
        val host = u.host.lowercase()
        return TRUSTED_MEDIA_HOST_SUFFIXES.any { host == it || host.endsWith(it) }
    }

    /** 从 playurl 响应提取直链：优先 durl[].url，其次 dash.video[].baseUrl（M16 白名单过滤） */
    internal fun extractPlayUrl(body: String): String? {""",
    "m16-doc",
)
rep(
    """                for (item in durl) {
                    val url = item?.asJsonObject?.get("url")?.asString
                    if (!url.isNullOrBlank()) return url
                }""",
    """                for (item in durl) {
                    val url = item?.asJsonObject?.get("url")?.asString
                    if (url.isNullOrBlank()) continue
                    if (isTrustedMediaUrl(url)) return url
                    AppLog.w(TAG, "extractPlayUrl: reject untrusted durl host=${url.toHttpUrlOrNull()?.host}")
                }""",
    "m16-durl",
)
rep(
    """                for (item in dashVideo) {
                    val url = item?.asJsonObject?.get("baseUrl")?.asString
                    if (!url.isNullOrBlank()) return url
                }""",
    """                for (item in dashVideo) {
                    val url = item?.asJsonObject?.get("baseUrl")?.asString
                    if (url.isNullOrBlank()) continue
                    if (isTrustedMediaUrl(url)) return url
                    AppLog.w(TAG, "extractPlayUrl: reject untrusted dash host=${url.toHttpUrlOrNull()?.host}")
                }""",
    "m16-dash",
)
save(p)
print("OK BilibiliMvService (M16)  —— 仍需补 companion 常量/import")

# ══════════════════════════════════════════════════════════════
# M17：JamendoService 日志不含 client_id
# ══════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/backend/network/JamendoService.kt")
rep(
    """                AppLog.w(TAG, "GET ${url.take(100)} -> ${resp.code}")""",
    """                // M17：⛔ 不记完整 URL —— Jamendo 的 client_id 就在 query 里，
                // 而 w 级日志会落盘。只记 path 与状态码，足够定位。
                AppLog.w(TAG, "GET ${req.url.encodedPath} -> ${resp.code}")""",
    "m17",
)
save(p)
print("OK JamendoService (M17)")

# ══════════════════════════════════════════════════════════════
# M18：LyricsNetworkProvider 酷狗 URL 用 HttpUrl.Builder 构造
# ══════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/lyrics/LyricsNetworkProvider.kt")
rep(
    """                val lrcUrl = "${kugouLrcUrl.trimEnd('/')}/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
                val lrcRequest = Request.Builder()
                    .url(lrcUrl)""",
    """                // M18：⛔ 不能手拼 query —— id/accesskey 来自**不可信的第三方响应**，
                // 含 & / # / 空格就会注入或截断查询串，而异常又被下游吞掉 ⇒ 歌词静默失败。
                // 用 HttpUrl.Builder 让每个参数各走一次编码。
                val lrcUrl = kugouLrcUrl.trimEnd('/').toHttpUrlOrNull()?.newBuilder()
                    ?.addPathSegment("download")
                    ?.addQueryParameter("ver", "1")
                    ?.addQueryParameter("client", "pc")
                    ?.addQueryParameter("id", id)
                    ?.addQueryParameter("accesskey", accessKey)
                    ?.addQueryParameter("fmt", "lrc")
                    ?.addQueryParameter("charset", "utf8")
                    ?.build() ?: return null
                val lrcRequest = Request.Builder()
                    .url(lrcUrl)""",
    "m18",
)
save(p)
print("OK LyricsNetworkProvider (M18)")

# ══════════════════════════════════════════════════════════════
# M19 + M22：MetingApiService
# ══════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt")
rep(
    """                    code == 302 -> {
                        found = location
                        fromRedirect = !location.isNullOrBlank()
                    }""",
    """                    code == 302 -> {
                        // M19：Location 就是最终交给 ExoPlayer 的直链，⛔ 不能无条件相信
                        // （端点被投毒即可把播放器指向内网/回环/非 http 协议）。
                        val loc = location
                        val reject = loc?.let { NetAddressGuard.rejectReasonForPlayback(it) }
                        if (loc != null && reject == null) {
                            found = loc
                            fromRedirect = true
                        } else if (loc != null) {
                            AppLog.w(DIAG, "fetchPlayUrl: reject 302 location ($reject)")
                        }
                    }""",
    "m19-302",
)
rep(
    """                    code in 300..399 && !location.isNullOrBlank() ->
                        // 相对 Location 按**实际请求的 URL**解析（端点链里已带 query）
                        next = response.request.url.resolve(location)""",
    """                    code in 300..399 && !location.isNullOrBlank() -> {
                        // 相对 Location 按**实际请求的 URL**解析（端点链里已带 query）
                        val candidate = response.request.url.resolve(location)
                        // M19：跟跳目标同样要过 scheme/host 校验（拒 file:/内网/回环）
                        if (candidate == null) {
                            next = null
                        } else {
                            val reject = NetAddressGuard.rejectReason(candidate.toString())
                            if (reject == null) {
                                next = candidate
                            } else {
                                AppLog.w(DIAG, "fetchPlayUrl: reject $code hop ($reject)")
                                next = null
                            }
                        }
                    }""",
    "m19-hop",
)
rep("""            val url = "$endpoint?server=$server&type=search&id=${URLEncoder.encode(keyword, "UTF-8")}\"""",
    """            val url = "$endpoint?server=${enc(server)}&type=search&id=${URLEncoder.encode(keyword, "UTF-8")}\"""",
    "m19-srv1")
rep("""                        val url = "$endpoint?server=$server&type=url&id=${URLEncoder.encode(netId, "UTF-8")}$brParam\"""",
    """                        val url = "$endpoint?server=${enc(server)}&type=url&id=${URLEncoder.encode(netId, "UTF-8")}$brParam\"""",
    "m19-srv2")
rep("""                val url = "$endpoint?server=$server&type=lrc&id=${URLEncoder.encode(netId, "UTF-8")}\"""",
    """                val url = "$endpoint?server=${enc(server)}&type=lrc&id=${URLEncoder.encode(netId, "UTF-8")}\"""",
    "m19-srv3")
rep("""                val url = "$endpoint?server=$server&type=playlist&id=${URLEncoder.encode(playlistId, "UTF-8")}\"""",
    """                val url = "$endpoint?server=${enc(server)}&type=playlist&id=${URLEncoder.encode(playlistId, "UTF-8")}\"""",
    "m19-srv4")
# M22：JsonNull 安全取串
rep(
    """                    val title = item.get("title")?.asString
                        ?: item.get("name")?.asString
                    val author = item.get("author")?.asString
                        ?: item.get("artist")?.asString
                    val pic = item.get("pic")?.asString
                    val urlField = item.get("url")?.asString
                    val albumField = item.get("album")?.asString
                        ?: item.get("al")?.asString""",
    """                    // M22：⛔ 不能用 `get(k)?.asString` —— 字段存在但值是 **JsonNull**
                    // 时 `asString` 会抛 UnsupportedOperationException，被外层 try 吞掉
                    // 后整首歌被丢弃（甚至整段天气响应作废）。统一走 jsonStr()。
                    val title = jsonStr(item, "title")
                        ?: jsonStr(item, "name")
                    val author = jsonStr(item, "author")
                        ?: jsonStr(item, "artist")
                    val pic = jsonStr(item, "pic")
                    val urlField = jsonStr(item, "url")
                    val albumField = jsonStr(item, "album")
                        ?: jsonStr(item, "al")""",
    "m22",
)
save(p)
print("OK MetingApiService (M19/M22)")

# ══════════════════════════════════════════════════════════════
# M15：BaiduLyricsProvider 侧车 LRC 有界读取
# ══════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/backend/network/baidu/BaiduLyricsProvider.kt")
rep(
    """                resp.body?.string()?.takeIf { it.isNotBlank() }""",
    """                // M15：⛔ 不能直接 `body.string()` —— dlink 指向的是网盘上的任意文件，
                // 体积由服务端决定（同文件 downloadRange 已按 206 + 分段保护，这里原先没有）。
                // 先看 Content-Length 快速拒绝，再按上限**有界**读取（chunked 无长度也拦得住）。
                val body = resp.body ?: return null
                val declared = body.contentLength()
                if (declared > MAX_SIDECAR_LRC_BYTES) {
                    AppLog.w(TAG, "downloadText too large: $declared bytes")
                    return null
                }
                val buf = okio.Buffer()
                val read = body.source().read(buf, MAX_SIDECAR_LRC_BYTES + 1)
                if (read > MAX_SIDECAR_LRC_BYTES) {
                    AppLog.w(TAG, "downloadText exceeded cap (>$MAX_SIDECAR_LRC_BYTES bytes)")
                    return null
                }
                buf.readUtf8().takeIf { it.isNotBlank() }""",
    "m15",
)
save(p)
print("OK BaiduLyricsProvider (M15)")

# ══════════════════════════════════════════════════════════════
# M20：LrcParser 逐字时间戳补零
# ══════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/lyrics/LrcParser.kt")
rep(
    """                line.wordTimestamps.joinToString("") { wt ->
                    val wMin = wt.startMs / 60000
                    val wSec = (wt.startMs % 60000) / 1000
                    val wMs = wt.startMs % 1000
                    "<${wMin.toInt()}:${wSec.toInt()}.${(wMs / 10).toInt()}>${wt.word}"
                }""",
    """                line.wordTimestamps.joinToString("") { wt ->
                    val wMin = wt.startMs / 60000
                    val wSec = (wt.startMs % 60000) / 1000
                    val wMs = wt.startMs % 1000
                    // M20：⛔ 逐字时间戳也必须补零 —— `WORD_TIMESTAMP_REGEX` 要求
                    // 分/秒/百分秒都是两位，不补零写出的 `<0:5.3>` 连自己都回读不了，
                    // 卡拉 OK 逐字时间轴会在「写盘 → 重新加载」后静默丢失。
                    val wmm = wMin.toString().padStart(2, '0')
                    val wss = wSec.toString().padStart(2, '0')
                    val wxx = (wMs / 10).toString().padStart(2, '0')
                    "<$wmm:$wss.$wxx>${wt.word}"
                }""",
    "m20",
)
save(p)
print("OK LrcParser (M20)")

# ══════════════════════════════════════════════════════════════
# M21：NetworkMusicManager 注销时同清 deadProbeCache
# ══════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicManager.kt")
rep(
    """        playUrlCache.entries.removeAll { it.key.startsWith("${sourceId}:") }
        AppLog.d(TAG, "unregisterService: $sourceId (total=${services.size})")""",
    """        playUrlCache.entries.removeAll { it.key.startsWith("${sourceId}:") }
        // M21：deadProbeCache 的 key 同口径（playUrlKey），必须一起清 ——
        // 否则重新注册同一个源之后，残留的 DEAD 判定（TTL 5 分钟）会把本可播的歌
        // 静默判死，取消再打开开关也不恢复。
        val deadCleared = deadProbeCache.keys.count { it.startsWith("${sourceId}:") }
        deadProbeCache.keys.removeAll { it.startsWith("${sourceId}:") }
        AppLog.d(TAG, "unregisterService: $sourceId (total=${services.size}, deadCleared=$deadCleared)")""",
    "m21",
)
save(p)
print("OK NetworkMusicManager (M21)")

# ══════════════════════════════════════════════════════════════
# M06：SubsonicRestClient 非 2xx 记录状态码
# ══════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/backend/impl/SubsonicRestClient.kt")
rep(
    """    /** 歌曲总数缓存（-1 表示未统计） */""",
    """    /**
     * 最近一次 REST 请求的 HTTP 状态码（M06；0 = 还没有失败记录）。
     *
     * ⛔ [executeRequest] 对非 2xx 一律返回 null，调用方把它当「空结果」处理
     * ⇒ 密码错（401）、无权限（403）、服务端 500 与「曲库为空」在 UI 上完全无法区分，
     * 用户只看到「暂无歌曲」。这里把状态码留下来（已脱敏：只有数字），
     * 由适配器在 null 分支读取并拼进 lastErrorDetail。
     */
    @Volatile internal var lastHttpStatus: Int = 0

    /** 歌曲总数缓存（-1 表示未统计） */""",
    "m06-field",
)
rep(
    """                    if (response.isSuccessful) {
                        gson.fromJson(utf8Body, JsonObject::class.java)
                    } else {
                        null
                    }""",
    """                    if (response.isSuccessful) {
                        lastHttpStatus = response.code
                        gson.fromJson(utf8Body, JsonObject::class.java)
                    } else {
                        // M06：⛔ 不能静默吞成 null —— 401/403/5xx 与空曲库无法区分。
                        // 只记状态码与脱敏后的 URL（不含 u/t/s 参数）。
                        lastHttpStatus = response.code
                        AppLog.w(tag, "executeRequest HTTP ${response.code} for ${UrlSanitizer.sanitize(url)}")
                        null
                    }""",
    "m06-log",
)
save(p)
print("OK SubsonicRestClient (M06)")

# ══════════════════════════════════════════════════════════════
# C02：LocalFilePicker.readText 有界读取（size 未知时）
# ══════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/util/LocalFilePicker.kt")
rep(
    """    fun readText(context: Context, uri: Uri, maxBytes: Long): String {
        val size = sizeOf(context, uri)
        if (size > maxBytes) throw TooLargeException(size)
        return context.contentResolver.openInputStream(uri)?.use {
            it.reader(Charsets.UTF_8).use { r -> r.readText() }
        } ?: throw IOException("openInputStream returned null")
    }""",
    """    fun readText(context: Context, uri: Uri, maxBytes: Long): String {
        val size = sizeOf(context, uri)
        if (size > maxBytes) throw TooLargeException(size)
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("openInputStream returned null")
        // C02：⛔ `sizeOf == -1`（SAF provider 不报 SIZE）时不能退化成无上限的 readText()。
        // 改成边读边计数：一旦累计超过 maxBytes 立刻抛 TooLargeException，
        // 既保证上限生效，也不会先把整份文件读进内存再判断。
        stream.use { input ->
            val buf = ByteArray(READ_CHUNK_BYTES)
            val out = java.io.ByteArrayOutputStream(
                size.coerceIn(0L, READ_CHUNK_BYTES.toLong()).toInt()
            )
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw TooLargeException(total)
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        }
    }""",
    "c02",
)
save(p)
print("OK LocalFilePicker (C02)  —— 仍需 READ_CHUNK_BYTES 常量")

# ══════════════════════════════════════════════════════════════
# M02：CacheInventory 达到上限即终止遍历
# ══════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/data/cache/CacheInventory.kt")
rep(
    """        var count = 0
        var truncated = false
        val sizes = ArrayList<Long>()
        target.walkTopDown().filter { it.isFile }.forEach { f ->
            if (count >= MAX_FILES) truncated = true
            else { count++; sizes += runCatching { f.length() }.getOrDefault(0L) }
        }
        sumSizes(sizes, truncated)""",
    """        var truncated = false
        val sizes = ArrayList<Long>()
        // M02：⛔ 不能用 forEach + 计数标记 —— 那只置 truncated，`walkTopDown()` 照样把
        // **整棵目录树**走完，而「遍历目录项」本身就是这里的主要开销
        // （下载曲库数万文件时设置页仍会卡）。take(MAX_FILES + 1) 多取一个用于判定截断，
        // 取满即终止序列。
        for (f in target.walkTopDown().filter { it.isFile }.take(MAX_FILES + 1)) {
            if (sizes.size >= MAX_FILES) {
                truncated = true
                break
            }
            sizes += runCatching { f.length() }.getOrDefault(0L)
        }
        sumSizes(sizes, truncated)""",
    "m02",
)
save(p)
print("OK CacheInventory (M02)")

print("ALL DONE")
