package com.nasmusic.tv.backend.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.EncodingUtils
import com.nasmusic.tv.util.NetAddressGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Meting-API 网络音乐服务实现
 *
 * Meting-API 是聚合多个音乐平台（网易云/QQ/酷狗等）的搜索接口。
 * 端点示例：{BASE}?server=netease&type=search&id=keyword
 *
 * 响应字段说明（实际 API 返回字段名）：
 * - title：歌曲名
 * - author：艺术家
 * - pic：封面端点 URL（302 重定向到真实图片），Coil 可自动跟随，可直接用作 coverUrl
 * - url：播放端点 URL（302 重定向到真实 mp3），需通过 resolvePlayUrl() 解析
 * - lrc：歌词端点 URL，请求后返回 LRC 文本
 *
 * 注意：响应中没有独立的 id 字段，需从 url 字段中提取 id 查询参数。
 *
 * v2.2.0 适配：
 * - OkHttpClient 使用守护线程池，防止阻止进程退出
 * - 日志使用 AppLog
 * - JSON 解析使用 Gson
 */
class MetingApiService(
    /**
     * Meting-API 基础端点，可在设置页面配置。
     * 默认使用公共服务 https://meting.mikus.ink/api
     */
    private val baseUrlProvider: () -> String,
    /**
     * 运行时获取当前音乐平台来源（server 参数）。
     * 默认返回 "netease"，由用户在网络音乐 Tab 中切换。
     */
    private val serverProvider: () -> String = { DEFAULT_SERVER },
    /**
     * F2-6：音质档位提供者（Meting br 参数）。
     * 返回 0 = AUTO（不传 br，由带宽估计决策或端点默认）。
     */
    private val qualityTierProvider: () -> Int = { 0 },
    /**
     * 网易免鉴权接口（Task 4 起由 NasMusicApp 注入）。
     * null = 未启用网易优先，行为与改造前逐字符一致（R1 的"新功能全走新增代码路径"）。
     */
    private val netease: com.nasmusic.tv.backend.network.netease.NeteaseOpenApi? = null,
    /**
     * 可播判据（§3.8 第 1 步，Q4 裁决）。null = 不探测，维持既有"拿到非空 Location 即成功"。
     * 生产由 NasMusicApp 注入 [HttpPlayabilityProbe]；单测注入假探测（⛔ 不打网络）。
     */
    private val probe: PlayabilityProbe? = null,
    /**
     * 洛雪音源增强层（方案 §5.4）。null = 未启用 / 未安装脚本，行为与改造前**逐字符一致**。
     * 生产由 `NetworkMusicManager` 按开关注入；单测注入假 host（⛔ 不打网络）。
     */
    private val enhancer: com.nasmusic.tv.backend.network.lx.PlayUrlEnhancer? = null,
    /**
     * 端点 fallback 用的预设端点列表（[buildEndpointFallbackOrder] 的数据源）。
     *
     * ⚠️ **默认 [PRESET_ENDPOINTS] 与改造前逐字符等价 ⇒ 线上行为零变化**：
     * 生产唯一的构造点 `NasMusicApp.onCreate` 用默认值，取到的仍是同一个 companion 常量
     * （同一 List 实例，连引用都相同），`buildEndpointFallbackOrder` 的去重/排序逻辑一字未动。
     *
     * ⚠️ 存在的原因只有一个：**单测的确定性**。原实现让单测在首选端点失败后
     * 必然去打 3 个**真实公网端点**（meting.mikus.ink / meting.api.redcha.cn / api.qijieya.cn），
     * 于是测试结果依赖第三方可用性与网络状况 → CI flaky 的真正来源。
     * 单测注入 `emptyList()` 或自己的 MockWebServer 即可**彻底不打公网**。
     * ⛔ 不要把默认值改成空列表，也不要从这里读用户设置——预设端点的**展示**在设置页另有出处。
     */
    private val presetEndpoints: List<Pair<Int, String>> = PRESET_ENDPOINTS
) : NetworkMusicService {

    override val sourceId = NetworkSongId.METING_SOURCE

    // 安全修复（C-1）：移除 trust-all，恢复系统默认证书校验。
    // 原实现因老盒子缺 Let's Encrypt 根证书而放宽校验；现恢复严格校验，
    // 老设备遇 https LE 端点握手失败时改用 http 端点即可（应用已允许明文流量）。

    // L2 修复（2026-10-06，代码审查报告 §5）：改用 BackendRegistry 的共享线程池与
    // 连接池——旧实现自建 cached 守护线程池（无并发上限、且 MetingApiService 无
    // close() 永不回收），背弃「跨适配器共享 OkHttp 防电视 WiFi 栈过载」的设计意图。
    // sharedDispatcher（16/8 并发上限）+ sharedConnectionPool 全 app 复用。
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(com.nasmusic.tv.backend.BackendRegistry.sharedDispatcher)
            .connectionPool(com.nasmusic.tv.backend.BackendRegistry.sharedConnectionPool)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 不跟随重定向，用于获取 302 Location */
    private val noRedirectClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(com.nasmusic.tv.backend.BackendRegistry.sharedDispatcher)
            .connectionPool(com.nasmusic.tv.backend.BackendRegistry.sharedConnectionPool)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    private val gson = Gson()

    /**
     * 当前基础端点（每次请求动态读取，支持运行时切换）
     * 清理可能混入的非法字符（反引号、引号、首尾空格等）
     */
    private val baseUrl: String
        get() = baseUrlProvider()
            .trim()
            .trim('`', '\'', '"')
            .trim()
            .trimEnd('/')

    /** M19：`server` 参数单点编码（值来自用户配置，含 & / = 会破坏 query） */
    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    /**
     * M22：安全取字符串字段。
     *
     * ⛔ `obj.get(k)?.asString` 对 **JsonNull** 会抛 UnsupportedOperationException
     * （JsonNull 是 JsonElement 但不是 JsonPrimitive），异常被外层 try 吞掉后
     * 整首单曲/整段响应作废。与 `JamendoModels.kt:17` 的既有范式保持一致。
     */
    private fun jsonStr(obj: JsonObject, key: String): String? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    companion object {
        private const val TAG = "MetingApiService"
        // 诊断日志 TAG（logcat 过滤关键字：MetingDiag）
        private const val DIAG = "MetingDiag"
        private const val DEFAULT_SERVER = "netease"

        /** 非-302 的 3xx（301/303/307/308）**跟跳上限**：超过即按失败处理，⛔ 不跟着死循环 */
        private const val MAX_REDIRECT_HOPS = 2

        /**
         * 单曲取链**总预算**（毫秒）。
         *
         * 端点链最多 4 个端点 × 降级链最多 4 档 = 16 次串行请求，单次 OkHttp 超时 15s；
         * 没有总预算时，一首死链歌曲可以把播放解析拖到分钟级（再叠加跨源重搜更久）。
         * 预算耗尽即结束整条链，返回失败让上层尽快走同源 / 跨源替换。
         */
        private const val RESOLVE_BUDGET_MS = 12_000L

        /** 剩余预算低于此值不再发起新请求（连连接建立都不够）。 */
        private const val MIN_REQUEST_BUDGET_MS = 600L
        // 默认公共 Meting-API 端点
        const val DEFAULT_BASE_URL = "https://meting.api.redcha.cn/api"

        /**
         * 预设公共 Meting-API 端点列表（供设置页选择）
         *
         * 仅包含已验证支持 type=search 的端点。不同端点返回字段名可能不同：
         * - Mikus / Redcha：返回 title / author
         * - Qijieya：返回 name / artist
         * parseSongs() 已兼容两种字段格式。
         *
         * 这些端点由社区维护，可能随时间失效。用户也可自建端点或输入其他公共端点。
         */
        val PRESET_ENDPOINTS: List<Pair<Int, String>> = listOf(
            R.string.meting_endpoint_mikus to "https://meting.mikus.ink/api",
            R.string.meting_endpoint_redcha to "https://meting.api.redcha.cn/api",
            R.string.meting_endpoint_qijieya to "https://api.qijieya.cn/meting"
        )

        /**
         * 实测**只支持 netease** 的端点（方案 §2.4：对 `kg` / `tx` 直接回
         * `{"status":400,"message":"server 参数不合法"}`）。
         *
         * ⚠️ 这是「已知不支持」的**正列表**，⛔ 不要改成「不在支持列表里就提示」：
         * 自建 Meting 端点默认支持全平台，按白名单反推会把用户自己的服务说成残废。
         */
        private val NETEASE_ONLY_ENDPOINTS: Set<String> = setOf(
            "https://meting.mikus.ink/api",
            "https://meting.api.redcha.cn/api",
        )

        /** 端点是否是「已知只支持 netease」的那几个公共预设（用于洛雪子块底部灰字，§7.3） */
        fun isNeteaseOnlyEndpoint(baseUrl: String?): Boolean {
            val normalized = baseUrl?.trim()?.trimEnd('/') ?: return false
            return NETEASE_ONLY_ENDPOINTS.any { it.trimEnd('/').equals(normalized, ignoreCase = true) }
        }
    }

    /**
     * 搜索歌曲
     *
     * 端点自动 fallback 策略：
     * 1. 优先使用用户在设置中选中的端点（baseUrl）
     * 2. 如果当前端点搜索失败（异常或无结果），自动尝试其他预设端点
     * 3. 任一端点返回非空结果即返回，用户无感切换
     *
     * 注意：Meting-API 搜索响应中的 url / pic / lrc 字段是 API 端点，不是直联 URL。
     * - url → 需要再请求获取 302 Location（real mp3 URL），由 resolvePlayUrl() 处理
     * - pic → Coil 自动处理 302，可以直接用端点 URL 作为 coverUrl
     * - lrc → 实际就是歌词文本端点，由 resolveLyrics() 请求获取
     */
    override suspend fun search(keyword: String, limit: Int): List<Song> = withContext(Dispatchers.IO) {
        AppLog.d(DIAG, "=== search start === keyword='$keyword'")
        if (keyword.isBlank()) {
            AppLog.d(DIAG, "search: keyword blank, return empty")
            return@withContext emptyList()
        }

        searchViaEndpoints(keyword)
    }

    /**
     * Q2「网易优先」：**只**供统一搜索入口（`SearchAggregator`）调用。
     *
     * ⛔ 不得写进 [search]——那会把天气电台 / 歌单补全 / 手机遥控 / 换一批等全部消费方
     *   一起换源（Q5 裁定「其他页面功能不受影响」）。
     */
    override suspend fun searchNativeFirst(keyword: String, limit: Int): List<Song> = withContext(Dispatchers.IO) {
        AppLog.d(DIAG, "=== search start === keyword='$keyword'")
        if (keyword.isBlank()) {
            AppLog.d(DIAG, "search: keyword blank, return empty")
            return@withContext emptyList()
        }

        // Q2「网易优先」：客户端已注入且当前平台是 netease 时，先打免鉴权原生接口（§10.3 子闸门 2.5s）。
        // ⚠️ netease == null（既有单测、以及任何未注入的构造点）时行为与改造前**逐字符一致**——
        //   R1/R10 的"新功能全走新增代码路径"在这一行落地。
        if (netease != null && serverProvider() == DEFAULT_SERVER) {
            val nativeSongs = try {
                netease.searchSongs(keyword)
            } catch (e: Exception) {
                // 网易随时下线（R1）是预期内常态：这里只降级，绝不上抛、绝不返回空集
                AppLog.d(DIAG, "search: netease-native threw ${e.javaClass.simpleName}: ${e.message}")
                emptyList()
            }
            if (nativeSongs.isNotEmpty()) {
                AppLog.d(DIAG, "search: netease-native hit=${nativeSongs.size}, endpoint chain skipped")
                AppLog.d(DIAG, "=== search end ===")
                return@withContext nativeSongs
            }
            AppLog.d(DIAG, "search: netease-native empty/offline, falling back to endpoint chain")
        }

        searchViaEndpoints(keyword)
    }

    /**
     * Meting 端点串行链（C2：从 [search] **原样抽出**，回退顺序、解析、日志一字未改）。
     *
     * 抽出来不是为了复用代码，而是为了**复用"pic 可用"这一性质**：
     * 网易 `type=1` 响应里**没有** `album.picUrl`（§2 C.1 实测 0/100），
     * 而 Meting 端点的 `pic` 是 302 封面端点、Coil 可直接用。
     * 所以 [searchCoverUrl] 必须走这里，⛔ 不能跟着"网易优先"一起改走 [search]——
     * 那会让既有无关表面（NAS 歌曲的在线歌词封面轮播、下载封面兜底链）在原生命中时恒拿不到封面。
     */
    private suspend fun searchViaEndpoints(keyword: String): List<Song> = withContext(Dispatchers.IO) {
        // 构造端点尝试顺序：当前选中的端点优先，然后是其他预设端点
        val currentBase = baseUrl
        val endpoints = buildEndpointFallbackOrder(currentBase)
        AppLog.d(DIAG, "search: endpoint fallback order=${endpoints.map { it.take(40) }}")

        for ((index, endpoint) in endpoints.withIndex()) {
            AppLog.d(DIAG, "search: trying endpoint[$index]='${endpoint.take(60)}'")
            val songs = searchWithEndpoint(keyword, endpoint)
            if (songs.isNotEmpty()) {
                AppLog.d(DIAG, "search: endpoint[$index] returned ${songs.size} songs, success")
                AppLog.d(DIAG, "=== search end ===")
                return@withContext songs
            }
            AppLog.d(DIAG, "search: endpoint[$index] returned empty, trying next")
        }

        AppLog.wThrottled(DIAG, "search: all endpoints failed or empty")
        AppLog.d(DIAG, "=== search end ===")
        emptyList()
    }

    /**
     * 构造端点 fallback 顺序
     *
     * 优先级：
     * 1. 当前用户选中的端点（baseUrl）
     * 2. 其他预设端点（按 [presetEndpoints] 顺序，排除当前端点）
     *
     * 如果当前端点是自定义的（不在预设列表中），也会先尝试它，然后 fallback 到预设端点。
     *
     * ⚠️ 预设端点来自构造参数 [presetEndpoints]（默认 [PRESET_ENDPOINTS]，线上等价），
     * 目的是让单测能注入 MockWebServer 或空列表、**不打真实公网**——见该参数 KDoc。
     */
    private fun buildEndpointFallbackOrder(currentBase: String): List<String> {
        val normalized = currentBase.trim().trimEnd('/')
        val result = mutableListOf(normalized)
        // 添加其他预设端点（去重）
        presetEndpoints.forEach { (_, url) ->
            val presetNormalized = url.trim().trimEnd('/')
            if (presetNormalized != normalized) {
                result.add(presetNormalized)
            }
        }
        return result
    }

    /**
     * 使用指定端点搜索歌曲（单次请求，不 fallback）
     *
     * 供 search() 在多端点 fallback 流程中调用。
     */
    private fun searchWithEndpoint(keyword: String, endpoint: String): List<Song> {
        val server = serverProvider()
        return try {
            val url = "$endpoint?server=${enc(server)}&type=search&id=${URLEncoder.encode(keyword, "UTF-8")}"
            AppLog.d(DIAG, "searchWithEndpoint: url='$url'")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            client.newCall(request).execute().use { response ->
                // I4-02：搜索响应可能是整页 HTML 错误页/超大正文，body?.string() 无上限读入会 OOM。
                // 参照 LxScriptRepo.download 的 okio.Buffer + MAX+1 限量读取（8 MB 足够搜索结果），
                // 超限按本次端点失败返回（上层会 fallback 其它端点）。限长后的字符串复用给
                // parseSongs，其下游 MetingJsonShape.preview 也只处理这一份限长正文。
                val maxBodyBytes = 8L * 1024 * 1024
                val source = response.body?.source()
                if (source == null) {
                    AppLog.d(DIAG, "searchWithEndpoint: failed code=${response.code} bodyEmpty=true")
                    return@use emptyList()
                }
                val limited = okio.Buffer()
                val read = source.read(limited, maxBodyBytes + 1)
                if (read > maxBodyBytes) {
                    AppLog.w(DIAG, "searchWithEndpoint: body 超过 ${maxBodyBytes / 1024 / 1024} MB 上限（read=$read），按失败返回")
                    return@use emptyList()
                }
                val body = limited.readUtf8()
                AppLog.d(DIAG, "searchWithEndpoint: response code=${response.code} bodyLen=${body.length}")
                if (!response.isSuccessful || body.isBlank()) {
                    AppLog.d(DIAG, "searchWithEndpoint: failed code=${response.code} bodyEmpty=${body.isBlank()}")
                    return@use emptyList()
                }
                parseSongs(body)
            }
        } catch (e: Exception) {
            AppLog.d(DIAG, "searchWithEndpoint error: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    /**
     * 解析播放链接
     *
     * 端点自动 fallback 策略：
     * 1. 优先使用用户在设置中选中的端点（baseUrl）
     * 2. 如果当前端点失败（异常或空结果），自动尝试其他预设端点
     * 3. 任一端点返回非空播放 URL 即返回
     *
     * 单次请求的响应形态（302 / 200 / 非-302 3xx 跟一跳）见 [fetchPlayUrl]。
     */
    /**
     * 解析播放链接（默认档位）。
     *
     * 委托 [resolvePlayUrlDetailed]，忽略降级信号；档位由 `qualityTierProvider` 决定。
     */
    override suspend fun resolvePlayUrl(song: Song): String? =
        resolvePlayUrlDetailed(song, qualityTierProvider()).url

    /** 指定档位解析，忽略降级信号 */
    override suspend fun resolvePlayUrl(song: Song, quality: Int): String? =
        resolvePlayUrlDetailed(song, quality).url

    /** 单次端点请求的结果：候选直链（+ 它是否来自重定向），或**给人看的失败原因** */
    private sealed class PlayUrlFetch {
        data class Found(val url: String, val fromRedirect: Boolean) : PlayUrlFetch()

        /** [reason] 只含状态码/异常类型这类短信息，⛔ 不放响应体（会被拼进 e 级日志） */
        data class Miss(val reason: String) : PlayUrlFetch()
    }

    /**
     * 请求一次 Meting 的 `type=url` 端点，取回候选播放直链。
     *
     * 三种响应形态（[noRedirectClient] 不自动跟随重定向）：
     * - **302** → `Location` **即**可播直链，⛔ 不再跟一跳（Meting 约定的主形态；
     *   [resolvePlayUrlDetailed] 的 probe 判据正是挂在这一跳的**结果**上，改了跟法就等于改了判据作用对象）
     * - **200** → JSON 的 `url` 字段
     * - **其余 3xx（301/303/307/308）且带 `Location`** → 按请求基址解析出绝对地址后**跟一跳**
     *   （相对 `Location` 也支持），落点再按上面两种形态判定，最多 [MAX_REDIRECT_HOPS] 跳。
     *   2026-10-13 电视端「无法播放」的根因之一：`api.qijieya.cn/meting` 先回 **301** 到自己的规范化地址，
     *   旧实现只认 302/200，301 落到 `else -> null` 被当成空结果丢掉 —— 而那一跳之后拿到的是
     *   **该电视（API 22，缺 Let's Encrypt 根）唯一能校验证书**的端点给出的可用直链。
     */
    private fun fetchPlayUrl(requestUrl: String, deadlineMs: Long): PlayUrlFetch {
        var url = requestUrl
        var hops = 0
        while (true) {
            var found: String? = null
            var fromRedirect = false
            var next: HttpUrl? = null
            var code = 0
            val remaining = deadlineMs - System.currentTimeMillis()
            if (remaining <= 0L) return PlayUrlFetch.Miss("budget exhausted")
            val call = noRedirectClient.newCall(Request.Builder().url(url).build())
            // 每一跳都不得超过总预算剩余（含 301 跟跳），否则 2 跳就能把预算翻倍。
            call.timeout().timeout(remaining.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                code = response.code
                val location = response.header("Location")
                when {
                    code == 302 -> {
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
                    }
                    code == 200 -> found = response.body?.string()?.let { extractUrlFromJson(it) }
                    code in 300..399 && !location.isNullOrBlank() -> {
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
                    }
                    else -> Unit
                }
            }
            // 复制进未被 lambda 捕获的局部 val：found 在 use{} 里被赋值，Kotlin 对被闭包捕获的
            // var **不做智能转换**，直接用会编译不过
            val hit = found
            if (!hit.isNullOrBlank()) return PlayUrlFetch.Found(hit, fromRedirect)
            val target = next
                ?: return PlayUrlFetch.Miss(if (code == 200) "200 without url" else "http $code")
            if (hops >= MAX_REDIRECT_HOPS) {
                return PlayUrlFetch.Miss("http $code after $hops hops")
            }
            url = target.toString()
            hops++
        }
    }

    /**
     * S05-07 / V4-N01 / V4-N02：播放直链统一地址守卫（集中一处）。
     *
     * 三条放行路径 —— 200-JSON 的 `url`、洛雪增强返回的 [ResolveResult]、networkId 缺失时的
     * `song.streamUrl` —— 在交给播放器前都必须经过这里。
     * ⚠️ 用 [NetAddressGuard.rejectReasonForPlayback]：它放行 RFC1918 私网 / nas.local
     * （用户自己的 NAS 是正当媒体源），只拦回环 / 链路本地 / 云元数据 / 组播 / 非 http(s)。
     * 用严格版 rejectReason 会误杀 NAS 直链，破坏既有行为。命中即降级为解析失败，绝不放行。
     */
    private fun guardPlayUrl(result: ResolveResult, requestedQuality: Int): ResolveResult {
        val url = result.url ?: return result
        val reason = NetAddressGuard.rejectReasonForPlayback(url) ?: return result
        AppLog.w(TAG, "resolvePlayUrl: 拒绝放行播放直链 ($reason)")
        return ResolveResult.failure(requestedQuality)
    }

    /**
     * 解析播放链接（带降级信号）。
     *
     * 端点自动 fallback 策略：
     * 1. 优先使用用户在设置中选中的端点（baseUrl）
     * 2. 如果当前端点失败（异常或空结果），自动尝试其他预设端点
     * 3. 任一端点返回非空播放 URL 即返回
     *
     * 档位降级链由 [QualityTiers.fallbackChainOf] 提供（AUTO → 不传 br；999 → 320 → 192 → 128）。
     * 返回的 [ResolveResult.actualQuality] 为**实际命中**的档位，不等于请求档位即表示发生降级。
     *
     * 单次请求的响应形态（302 / 200 / 非-302 3xx 跟一跳）见 [fetchPlayUrl]。
     * 注入 `probe` 时，来自重定向链的直链会跟一跳探测可播性（§3.8 第 1 步）：仅 DEAD 跳过该档位×端点组合，UNKNOWN 与 PLAYABLE 一律放行。
     *
     * ⚠️ 逐「端点×档位」的失败原因走 **d 级**（仅 Debug 落盘）；全链失败汇总进 **e 级**，
     * 设备侧（release）只需这一行就能判断「这首歌彻底不可播」。
     */
    override suspend fun resolvePlayUrlDetailed(song: Song, quality: Int): ResolveResult =
        withContext(Dispatchers.IO) {
            // 洛雪增强（新增路径）：命中即返回；null / 异常继续走下面原 302 链路。
            // ⚠️ CancellationException 必须原样抛出：吞掉取消会让播放抢占失效。
            if (enhancer != null) {
                val enhanced = try {
                    enhancer.tryResolve(song, quality)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    AppLog.w(TAG, "enhance: 增强层异常，回退原链路 —— ${t.javaClass.simpleName}")
                    null
                }
                if (enhanced != null) return@withContext guardPlayUrl(enhanced, quality)
            }
            val netId = song.networkId
                ?: return@withContext guardPlayUrl(ResolveResult(song.streamUrl, quality), quality)
            // 平台快照优先：搜索结果/收藏回放的 Song 携带收藏时的 networkPlatform，
            // 按原平台取链；历史数据缺字段时回退当前全局平台（与旧行为逐字符一致）。
            // ⚠️ 无 Song 上下文的路径（searchWithEndpoint / getPlaylist / findArtist）仍用 serverProvider()。
            val server = song.networkPlatform ?: serverProvider()
            val endpoints = buildEndpointFallbackOrder(baseUrl)
            // 档位与降级链（999 → 320 → 192 → 128；AUTO 直接不传 br 走端点默认）
            val brChain = QualityTiers.fallbackChainOf(quality)
            var probedDead = false
            val misses = mutableListOf<String>()
            // 总预算：跨端点 × 跨档位的串行链必须有时限，否则死链会拖到分钟级。
            val deadline = System.currentTimeMillis() + RESOLVE_BUDGET_MS
            fun remainingMs(): Long = deadline - System.currentTimeMillis()
            budgetLoop@ for (endpoint in endpoints) {
                for (br in brChain) {
                    val remaining = remainingMs()
                    if (remaining < MIN_REQUEST_BUDGET_MS) {
                        misses += "budget exhausted before $endpoint br=$br"
                        AppLog.w(TAG, "resolvePlayUrl: 总预算耗尽（${RESOLVE_BUDGET_MS}ms），提前结束端点链 netId=$netId")
                        break@budgetLoop
                    }
                    try {
                        val brParam = if (br != null) "&br=$br" else ""
                        val url = "$endpoint?server=${enc(server)}&type=url&id=${URLEncoder.encode(netId, "UTF-8")}$brParam"
                        val fetched = fetchPlayUrl(url, deadline)
                        when (fetched) {
                            is PlayUrlFetch.Found -> {
                                // br == null（AUTO 档）时 actualQuality 记为请求档位本身，不产生降级提示
                                val actual = br ?: quality
                                // §3.8 第 1 步（Q4）：判据**只加在跟链那一侧**（重定向 → 直链）。
                                // 200-JSON 分支不产生重定向链，维持既有"非空即成功"（§6 验收 16 的回归口径，
                                // 百度网盘 / jamendo 走的是接口默认实现，本方法根本不参与）。
                                // ⛔ 只有 DEAD 才 continue；UNKNOWN 与 PLAYABLE 一律放行。
                                if (fetched.fromRedirect && probe != null) {
                                    // 探活自身有 3s 超时（HttpPlayabilityProbe），失败/超时返回 UNKNOWN；
                                    // 按既有保守判据，只有 DEAD 才判失败并继续降级链。
                                    if (probe.probe(fetched.url) == Playability.DEAD) {
                                        probedDead = true
                                        misses += "$endpoint br=$br → probe DEAD"
                                        AppLog.d(TAG, "resolvePlayUrl: probe DEAD via '$endpoint' br=$br for netId=$netId")
                                        continue   // 只跳内层 for(br)：该端点×该档失败，继续降级链
                                    }
                                }
                                AppLog.d(TAG, "resolvePlayUrl: resolved via '$endpoint' br=$br (actual=$actual) for netId=$netId")
                                return@withContext guardPlayUrl(ResolveResult(fetched.url, actual), quality)
                            }
                            is PlayUrlFetch.Miss -> {
                                misses += "$endpoint br=$br → ${fetched.reason}"
                                AppLog.d(TAG, "resolvePlayUrl: empty from '$endpoint' br=$br for netId=$netId")
                            }
                        }
                    } catch (e: Exception) {
                        // 异常类型名是**唯一**能在 release 里区分"TLS 不信任 / DNS / 连接被拒 / 超时"的信息
                        misses += "$endpoint br=$br → ${e.javaClass.simpleName}: ${e.message?.take(80)}"
                        AppLog.d(TAG, "resolvePlayUrl: endpoint '$endpoint' br=$br failed: ${e.message}")
                    }
                }
            }
            AppLog.e(
                TAG,
                "resolvePlayUrl: all endpoints failed for netId=$netId (requested=$quality) " +
                    "probedDead=$probedDead misses=${misses.joinToString("; ")}"
            )
            ResolveResult.failure(quality, probedDead)
        }

    /**
     * 获取歌词
     *
     * Meting-API 的 lrc 端点直接返回 LRC 文本。
     * 与 search/resolvePlayUrl/getPlaylist 一致，采用多端点 fallback：
     * 当前端点失败（异常或空结果）时依次尝试其他预设端点。
     */
    override suspend fun resolveLyrics(song: Song): String? = withContext(Dispatchers.IO) {
        val netId = song.networkId ?: return@withContext null
        // 与 resolvePlayUrlDetailed 同口径：按歌曲自带平台快照取词，老数据回退当前全局平台。
        val server = song.networkPlatform ?: serverProvider()
        val endpoints = buildEndpointFallbackOrder(baseUrl)
        for (endpoint in endpoints) {
            try {
                val url = "$endpoint?server=${enc(server)}&type=lrc&id=${URLEncoder.encode(netId, "UTF-8")}"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string()
                    if (!text.isNullOrBlank()) {
                        AppLog.d(TAG, "resolveLyrics: resolved via '$endpoint' for netId=$netId, len=${text.length}")
                        return@withContext text
                    }
                }
                AppLog.d(TAG, "resolveLyrics: empty from '$endpoint' for netId=$netId")
            } catch (e: Exception) {
                AppLog.d(TAG, "resolveLyrics: endpoint '$endpoint' failed: ${e.message}")
            }
        }
        AppLog.w(TAG, "resolveLyrics: all endpoints failed for netId=$netId")
        null
    }

    /**
     * 封面 URL：搜索结果中的 pic 字段已是端点 URL，Coil 可自动跟随 302，
     * 无需额外解析，返回 null 让调用方使用 song.coverUrl。
     */
    override suspend fun resolveCoverUrl(song: Song): String? = null

    /**
     * 按标题+艺术家搜索第一首匹配歌曲的封面 URL。
     * 用于 NAS 歌曲切换到网络歌词时，联动获取网络封面图加入轮播。
     *
     * ⚠️ C2：**必须走端点链**（[searchViaEndpoints]），⛔ 不能改走 [search]。
     * 网易 `type=1` 响应没有 `album.picUrl`（§2 C.1 实测 0/100），
     * 走 [search] 后一旦原生命中就只会返回 null，把既有表面（歌词封面轮播 `MainViewModel`、
     * 下载封面兜底链 `CoverFileWriter`）的封面全部丢掉；`NetworkMusicManager.searchCoverUrl`
     * 的轮询也救不了——其余服务无条件返回 null。
     */
    override suspend fun searchCoverUrl(title: String, artist: String): String? {
        // F8（终审 M-B4）：空关键词短路。searchViaEndpoints 会打满整条端点链
        // （当前端点 + 3 个预设 = 最多 4 跳），空标题 + 空歌手的 NAS 曲不该白烧
        // 4 个 `id=` 为空的公网请求。⚠️ 参数名是 `id=`（见 searchWithEndpoint），不是 `name=`。
        // ⚠️ 条件是**两个都空**才短路：title 空但 artist 非空时 keyword 仍非空，
        // 那是合法的「只有歌手名」补封面查询，⛔ 不得挡掉。
        if (title.isBlank() && artist.isBlank()) return null
        val keyword = if (artist.isNotBlank()) "$title $artist" else title
        val items = searchViaEndpoints(keyword)
        return items.firstOrNull()?.coverUrl
    }

    /**
     * 获取歌单中的所有歌曲。
     *
     * 端点自动 fallback 策略：
     * 1. 优先使用用户在设置中选中的端点（baseUrl）
     * 2. 如果当前端点失败（异常或空结果），自动尝试其他预设端点
     * 3. 任一端点返回非空歌曲列表即返回
     *
     * Meting-API 的 type=playlist 端点直接返回歌曲 JSON 数组，
     * 格式与 type=search 完全一致，可直接复用 parseSongs() 解析。
     *
     * @param playlistId 网易云歌单 ID
     * @return 歌单中的歌曲列表；空列表表示无结果或获取失败
     */
    override suspend fun getPlaylist(playlistId: String): List<Song> = withContext(Dispatchers.IO) {
        val server = serverProvider()
        val endpoints = buildEndpointFallbackOrder(baseUrl)
        for (endpoint in endpoints) {
            try {
                val url = "$endpoint?server=${enc(server)}&type=playlist&id=${URLEncoder.encode(playlistId, "UTF-8")}"
                AppLog.d(DIAG, "getPlaylist: trying endpoint='$endpoint' url='$url'")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                var songs: List<Song>? = null
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    AppLog.d(DIAG, "getPlaylist: code=${response.code} bodyLen=${body?.length ?: 0}")
                    if (response.isSuccessful && !body.isNullOrBlank()) {
                        songs = parseSongs(body)
                    }
                }
                if (!songs.isNullOrEmpty()) {
                    AppLog.d(DIAG, "getPlaylist: loaded ${songs.size} songs via '$endpoint'")
                    return@withContext songs
                }
                // 「本端点返回空」是合法结果（歌单就是空的/不存在，或换了端点才有）——
                // 上面的 i 级日志已带 code/bodyLen 详情，此处按 W 打会在 release 刷屏
                // （W 恒落盘 LogStore，2026-10-20 真机：redcha 端点持续回 []），降 d。
                // 真的全军覆没（所有端点都空）才由循环后的 W 留痕。
                AppLog.d(DIAG, "getPlaylist: empty from '$endpoint'")
            } catch (e: Exception) {
                AppLog.d(DIAG, "getPlaylist: endpoint '$endpoint' failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        AppLog.w(DIAG, "getPlaylist: all endpoints failed for playlistId=$playlistId")
        emptyList()
    }

    override suspend fun findArtist(name: String): NetworkArtistInfo? {
        // 本期只覆盖 netease 平台（§5 P3 已按 Q8 撤出其他平台）；DEFAULT_SERVER 是类内既有常量（:102）
        if (serverProvider() != DEFAULT_SERVER) return null
        return netease?.findArtist(name)
    }

    override suspend fun getArtistSongs(artistId: String, offset: Int, limit: Int): ArtistSongsPage? {
        if (serverProvider() != DEFAULT_SERVER) return null
        val id = artistId.toLongOrNull() ?: run {
            AppLog.e(DIAG, "getArtistSongs: artistId 不是数字 '$artistId'")
            return null
        }
        return netease?.artistSongs(id, offset, limit)
    }

    /**
     * 解析 Meting-API 搜索响应
     *
     * **元素**字段（不同端点字段名不同，下面逐项都做了兼容）：
     * - title / name：歌曲名
     * - author / artist：艺术家
     * - album / al：专辑名
     * - pic：封面端点 URL
     * - url：播放端点 URL（含 id 参数，需提取）
     * - lrc：歌词端点 URL
     *
     * **整体形态**不一定是数组 —— v2.39.2 真机日志抓到有端点回对象
     * （`Expected BEGIN_ARRAY but was BEGIN_OBJECT`）。形态收敛交给
     * [MetingJsonShape]，本函数只负责「拿到数组之后的逐条映射」。
     */
    private fun parseSongs(body: String): List<Song> {
        // 先收敛形态：根数组直接用；对象则按常见包装键下钻（错误信封 / {"data":[…]}）
        val extraction = MetingJsonShape.extract(gson, body)
        val items = extraction.items
        if (items.isEmpty()) {
            // ⚠️ 两种空必须分开记，正对应 [MetingJsonShape] 的设计注释：
            // - `array@…` 形态 = 端点**合法地回答了空列表**（如 redcha 持续回 `[]`）——
            //   这是正常空结果，不是异常。按 W 打会在 release 刷屏且 W 恒落盘
            //   （挤掉崩溃现场，2026-10-20 真机日志实测被刷爆）⇒ 降 d。
            // - 其它形态（`invalid:` / `scalar@` / `object@` / `too-deep@` / `empty`）=
            //   端点回了认不出的东西，必须 W 留痕 + 正文摘要。
            val shape = extraction.shape
            val message = "parseSongs: no songs (shape=$shape, body=${extraction.bodyPreview})"
            if (shape.startsWith("array@")) AppLog.d(DIAG, message) else AppLog.w(DIAG, message)
            return emptyList()
        }
        return try {
            // 首项 key 集合打印一次（便于核对字段名），不再逐条打 INFO 日志
            items.firstOrNull()?.keySet()?.let { keys ->
                AppLog.d(DIAG, "parseSongs: size=${items.size}, shape=${extraction.shape}, keys=$keys")
            }
            var skippedNoTitle = 0
            var skippedNoId = 0
            val result = items.mapIndexedNotNull { idx, item ->
                try {
                    // 兼容不同端点的字段名：
                    // - Mikus / Redcha 返回 title / author
                    // - Qijieya 返回 name / artist
                    // M22：⛔ 不能用 `get(k)?.asString` —— 字段存在但值是 **JsonNull**
                    // 时 `asString` 会抛 UnsupportedOperationException，被外层 try 吞掉
                    // 后整首歌被丢弃（甚至整段天气响应作废）。统一走 jsonStr()。
                    val title = jsonStr(item, "title")
                        ?: jsonStr(item, "name")
                    val author = jsonStr(item, "author")
                        ?: jsonStr(item, "artist")
                    val pic = jsonStr(item, "pic")
                    val urlField = jsonStr(item, "url")
                    val albumField = jsonStr(item, "album")
                        ?: jsonStr(item, "al")
                    // 编码修复：部分端点可能返回 GBK 被当作 Latin-1 解码的乱码
                    val fixedTitle = EncodingUtils.fixEncoding(title)
                    val fixedAuthor = EncodingUtils.fixEncoding(author)
                    val fixedAlbum = EncodingUtils.fixEncoding(albumField)
                    if (fixedTitle == null) {
                        skippedNoTitle++
                        return@mapIndexedNotNull null
                    }
                    val netId = extractIdFromUrl(urlField)
                    if (netId == null) {
                        skippedNoId++
                        return@mapIndexedNotNull null
                    }
                    Song(
                        id = NetworkSongId.forNetworkSong(sourceId, netId),
                        title = fixedTitle,
                        artist = fixedAuthor.orEmpty(),
                        album = fixedAlbum.orEmpty(),
                        coverUrl = pic,
                        isNetworkSong = true,
                        networkSource = sourceId,
                        networkId = netId,
                        // 平台快照：之后用户切平台再播这首，洛雪仍按原平台取链（方案 §4.1）
                        networkPlatform = serverProvider()
                    )
                } catch (e: Exception) {
                    AppLog.d(DIAG, "parseSongs[$idx] error: ${e.message}")
                    null
                }
            }
            // 汇总日志：一次请求只打一条 INFO，避免逐条日志刷屏
            AppLog.d(DIAG, "parseSongs: result=${result.size}/${items.size}" +
                (if (skippedNoTitle > 0) " skippedNoTitle=$skippedNoTitle" else "") +
                (if (skippedNoId > 0) " skippedNoId=$skippedNoId" else ""))
            result
        } catch (e: Exception) {
            AppLog.e(DIAG, "parseSongs error: ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 从 Meting-API 端点 URL 中提取 id 查询参数
     *
     * 示例输入：https://meting.mikus.ink/api?server=netease&type=url&id=2652820720
     * 输出：2652820720
     *
     * 使用 android.net.Uri（Android 内置，对含空格/中文的 URL 更稳健），
     * 失败时回退正则作为防御兜底。
     */
    private fun extractIdFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        // 优先用 android.net.Uri.parse，比 java.net.URI 更宽容（不抛异常）
        try {
            val id = android.net.Uri.parse(url).getQueryParameter("id")
            if (!id.isNullOrBlank()) return id
        } catch (e: Exception) {
            AppLog.d(DIAG, "extractIdFromUrl: Uri.parse failed: ${e.message}")
        }
        // 兜底：正则提取（Uri.parse 失败或无 query parameter 时）
        return try {
            Regex("[?&]id=([^&]+)").find(url)?.groupValues?.getOrNull(1)
        } catch (e: Exception) {
            AppLog.d(DIAG, "extractIdFromUrl: regex fallback failed: ${e.message}")
            null
        }
    }

    /**
     * 从 JSON 响应中提取 URL（部分接口可能返回 {"url": "..."} 格式）
     */
    private fun extractUrlFromJson(body: String): String? {
        return try {
            val json = gson.fromJson(body, JsonObject::class.java)
            json?.get("url")?.asString
        } catch (e: Exception) {
            null
        }
    }
}
