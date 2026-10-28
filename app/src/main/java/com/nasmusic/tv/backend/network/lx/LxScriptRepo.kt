package com.nasmusic.tv.backend.network.lx

import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
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
        prefs.lx.sourcesJsonFlow.map { LxSourceEntry.parseList(it) }

    suspend fun snapshot(): List<LxSourceEntry> = LxSourceEntry.parseList(prefs.lx.sourcesJsonFlow.first())

    fun scriptFileOf(id: String): File = File(scriptsDir, "$id.js")

    /** 正文；null = 未安装（只登记了 URL）或文件被清掉 */
    fun scriptTextOf(id: String): String? = runCatching {
        val file = scriptFileOf(id)
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    }.getOrNull()

    /** 下载并安装 / 更新。失败时旧正文与旧版本号**原样保留**。 */
    suspend fun installFromUrl(url: String): Result<LxSourceEntry> = runCatching {
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
            )
            val next = current.filterNot { it.id == id } + entry
            prefs.lx.setSourcesJson(LxSourceEntry.toListJson(next))
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
            val limited = okio.Buffer()
            val body = response.body ?: throw IOException("空响应体")
            body.source().read(limited, MAX_SCRIPT_BYTES)
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

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(com.nasmusic.tv.backend.BackendRegistry.sharedDispatcher)
            .connectionPool(com.nasmusic.tv.backend.BackendRegistry.sharedConnectionPool)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
