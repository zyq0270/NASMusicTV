package com.nasmusic.tv.backend.network.lx

import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.NetAddressGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 洛雪脚本存取（方案 §5.3 / §8）。
 *
 * 分层理由：**正文进文件、元数据进 DataStore**。333 KB 的脚本文本塞进 DataStore 会让
 * 每次 `dataStore.data` 都整份解码，设置页任何一次读取都变慢。
 *
 * ⛔ 两条硬约束：
 * 1. 校验头部注释，`@name` 缺失即拒绝安装（不是合法音源，§5.3）；
 * 2. **更新失败保留旧版** —— 下载/校验失败绝不动已有文件与版本号，否则「刷新一下」把能用的源弄坏。
 */
internal class LxScriptRepo(
    private val prefs: AppPreferences,
    private val scriptsDir: File,
    private val client: OkHttpClient = defaultClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    val entries: Flow<List<LxSourceEntry>> =
        prefs.lx.sourcesJsonFlow.map { json ->
            LxSourceEntry.parseList(json).also { refreshPins(it) }
        }

    suspend fun snapshot(): List<LxSourceEntry> =
        LxSourceEntry.parseList(prefs.lx.sourcesJsonFlow.first()).also { refreshPins(it) }

    /**
     * `id → 安装时正文 SHA-256`。
     *
     * 只读快照，由 [entries] / [snapshot] / 写入口维护 —— [scriptTextOf] 是**非挂起**函数
     * （调用方在渲染/播放线程上），不能在里面 `first()` 读 DataStore，所以哈希放内存。
     * 冷启动时 `NasMusicApp` 会先收集 [entries] 再建 host，因此加载路径上 pins 必然已就绪。
     */
    @Volatile
    private var pins: Map<String, String> = emptyMap()

    private fun refreshPins(list: List<LxSourceEntry>) {
        val m = HashMap<String, String>(list.size)
        for (e in list) e.sha256?.takeIf { it.isNotBlank() }?.let { m[e.id] = it }
        pins = m
    }

    fun scriptFileOf(id: String): File = File(scriptsDir, "$id.js")

    /** 正文；null = 未安装（只登记了 URL）/ 文件被清掉 / **完整性校验不通过** */
    fun scriptTextOf(id: String): String? = runCatching {
        val file = scriptFileOf(id)
        if (!file.isFile) return@runCatching null
        val text = file.readText(Charsets.UTF_8)
        // C21：落盘后再校验一次 SHA-256 —— 脚本正文会被 QuickJS 直接执行，
        // 任何绕过本仓库的写入（备份恢复、外部工具、越权改文件）都必须在加载前发现。
        val expected = pins[id]
        if (!expected.isNullOrBlank() && !expected.equals(sha256Hex(text), ignoreCase = true)) {
            AppLog.w(TAG, "脚本 $id 完整性校验失败（磁盘正文与安装时哈希不一致），拒绝加载")
            return@runCatching null
        }
        text
    }.getOrNull()

    /** 下载并安装 / 更新。失败时旧正文与旧版本号**原样保留**。 */
    suspend fun installFromUrl(url: String): Result<LxSourceEntry> = runCatching {
        // C21：安装入口只接受 https，且目标不得指向本机/内网。
        // 旧实现无任何校验：指向 http://127.0.0.1 或内网地址也能装，装完还能被执行。
        val parsed = url.trim().toHttpUrlOrNull()
            ?: throw IOException("脚本 URL 不合法")
        if (parsed.scheme != "https") throw IOException("只允许 https 安装脚本（收到 ${parsed.scheme}）")
        NetAddressGuard.rejectReason(url)?.let { throw IOException(it) }
        val text = download(url)
        installText(url, text).getOrThrow()
    }

    /**
     * 已拿到正文时的安装路径（单测直打，不打网络）。
     *
     * @return 失败原因：正文不合法（没有 `@name`）/ 写盘失败
     */
    suspend fun installText(url: String, text: String, displayName: String? = null): Result<LxSourceEntry> =
        runCatching {
            val header = LxScriptHeader.parse(text)
            val name = header.name?.takeIf { it.isNotBlank() }
                ?: throw IOException("脚本缺少 @name 注释，不是合法的洛雪音源")
            val id = LxSourceEntry.idOf(url)
            writeAtomically(scriptFileOf(id), text)

            val current = snapshot()
            val existing = current.firstOrNull { it.id == id }
            val entry = LxSourceEntry(
                id = id,
                name = displayName?.takeIf { it.isNotBlank() } ?: name,
                url = url.trim(),
                // 更新不改变用户的启停选择
                enabled = existing?.enabled ?: true,
                version = header.version,
                installedAt = clock(),
                lastStatus = existing?.lastStatus,
                // C21：把正文哈希 pin 进元数据，加载前比对（见 scriptTextOf）
                sha256 = sha256Hex(text),
            )
            val next = current.filterNot { it.id == id } + entry
            prefs.lx.setSourcesJson(LxSourceEntry.toListJson(next))
            refreshPins(next)
            entry
        }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val current = snapshot()
        if (current.none { it.id == id }) return
        val next = current.map { if (it.id == id) it.copy(enabled = enabled) else it }
        prefs.lx.setSourcesJson(LxSourceEntry.toListJson(next))
    }

    /** 紧凑验证结果落盘（§8.1）；⛔ 只用于下次进设置页时先显示旧结果 */
    suspend fun saveLastStatus(id: String, statusJson: String) {
        val current = snapshot()
        if (current.none { it.id == id }) return
        val next = current.map { if (it.id == id) it.copy(lastStatus = statusJson) else it }
        prefs.lx.setSourcesJson(LxSourceEntry.toListJson(next))
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private fun download(url: String): String {
        val request = Request.Builder().url(url.trim()).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            // 上限 2 MB：真脚本最大 ~400 KB；超过就是下到了 HTML/错误页
            // C25：旧实现 read(limited, MAX) 会把超限正文**静默截断**成半截脚本，
            // 半截脚本仍可能含 @name 从而通过校验被执行 ⇒ 多读 1 字节判定是否超限。
            val limited = okio.Buffer()
            val body = response.body ?: throw IOException("空响应体")
            val read = body.source().read(limited, MAX_SCRIPT_BYTES + 1)
            if (read > MAX_SCRIPT_BYTES) {
                throw IOException("脚本正文超过 ${MAX_SCRIPT_BYTES / 1024 / 1024} MB 上限，已拒绝")
            }
            return limited.readUtf8()
        }
    }

    /** 先写临时文件再改名：半截文件会被当成合法脚本加载，比下载失败更糟 */
    private fun writeAtomically(target: File, text: String) {
        if (!scriptsDir.isDirectory && !scriptsDir.mkdirs()) throw IOException("无法创建目录 ${scriptsDir.path}")
        val temp = File(scriptsDir, target.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temp.delete()
            throw IOException("无法替换旧脚本 ${target.name}")
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("无法写入 ${target.name}")
        }
        AppLog.d(TAG, "脚本已落盘：${target.name}（${text.length} 字符）")
    }

    private companion object {
        const val TAG = "LxScriptRepo"
        const val MAX_SCRIPT_BYTES = 2L * 1024 * 1024

        /** 正文 SHA-256（小写 hex）。用于安装时 pin、加载前比对。 */
        fun sha256Hex(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (v < 0x10) sb.append('0')
                sb.append(v.toString(16))
            }
            return sb.toString()
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(com.nasmusic.tv.backend.BackendRegistry.sharedDispatcher)
            .connectionPool(com.nasmusic.tv.backend.BackendRegistry.sharedConnectionPool)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            // S05-01：C21 只守了安装入口的初始 URL，但 download 用的 client 会自动跟随 3xx ——
            // 公网脚本 URL 302 到内网/回环/云元数据即可绕过，响应体还会被当脚本正文写盘执行。
            // 复用 UrlReachabilityChecker.defaultClient 的 network interceptor 逐跳口径；
            // ⚠️ 与初始校验同口径用 rejectReason（严格，私网也拦），不放宽到 ForPlayback。
            .addNetworkInterceptor { chain ->
                val target = chain.request().url
                val reason = NetAddressGuard.rejectReason(target.toString())
                if (reason != null) throw IOException("blocked redirect hop: $reason")
                chain.proceed(chain.request())
            }
            .build()
    }
}
