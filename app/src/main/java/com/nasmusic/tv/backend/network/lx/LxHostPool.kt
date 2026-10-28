package com.nasmusic.tv.backend.network.lx

import com.nasmusic.tv.util.AppLog
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * 运行中的脚本宿主池：**一个脚本只有一个 runtime**（方案 §5.4.1.5 / §9.3）。
 *
 * 为什么必须共用：验证与播放取链都走同一份 `__handlers` 状态；而且单 runtime 常驻内存
 * 就已经接近 15 MB，再开一份在 1 GB 电视上直接 OOM。
 *
 * 生命周期：[sync] 由启停列表驱动（启用的才建 host），脚本正文被刷新（mtime 变化）时
 * 关掉旧的另建新的 —— 否则用户「刷新」后仍在跑旧代码。
 */
internal class LxHostPool(
    private val repo: LxScriptRepo,
    private val httpClient: OkHttpClient,
    private val bootScriptProvider: () -> String,
) {

    private class Cached(val installedAt: Long, val host: LxHost)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** 增强层的取链顺序（= 用户排序，来自 `settings_lx_sources` 的数组顺序） */
    @Volatile
    var currentSlots: List<LxScriptSlot> = emptyList()
        private set

    /**
     * 对齐启停列表并加载新 host。
     *
     * ⚠️ 必须在**后台线程**调用：`load()` 会建 runtime 并执行脚本正文（首次 `loadLibrary` 也要读磁盘）。
     */
    suspend fun sync(entries: List<LxSourceEntry>) {
        val wanted = entries.filter { it.enabled && it.installed }
        val wantedIds = wanted.map { it.id }.toSet()

        cache.keys.filter { it !in wantedIds }.forEach { id ->
            cache.remove(id)?.let { runCatching { it.host.close() } }
        }

        val slots = ArrayList<LxScriptSlot>(wanted.size)
        for (entry in wanted) {
            val host = hostFor(entry.id) ?: continue
            if (host.info !is LxScriptInfo.Loaded) {
                val info = runCatching { host.load() }.getOrElse {
                    AppLog.w(TAG, "[${entry.id}] 加载失败：${it.javaClass.simpleName}")
                    LxScriptInfo.Failed(LxFailReason.L1_SCRIPT_ERROR, it.message)
                }
                if (info !is LxScriptInfo.Loaded) {
                    // L1 失败不改用户的开关（下次刷新还能救回来），只是本轮不参与取链
                    AppLog.w(TAG, "[${entry.id}] 未加载成功，本轮不参与取链")
                    continue
                }
            }
            slots += LxScriptSlot(entry.id, host)
        }
        currentSlots = slots
    }

    /** 取 host（没有就建，正文缺失返回 null）。⛔ 创建本身很轻，`load()` 才是重活。 */
    fun hostFor(id: String): LxHost? {
        val file = repo.scriptFileOf(id)
        if (!file.isFile) return null
        val installedAt = file.lastModified()
        cache[id]?.let { cached ->
            if (cached.installedAt == installedAt) return cached.host
            cache.remove(id)
            runCatching { cached.host.close() }
        }
        val text = repo.scriptTextOf(id) ?: return null
        val host = LxScriptHost(
            scriptId = id,
            scriptText = text,
            bootScript = bootScriptProvider(),
            httpClient = httpClient,
        )
        cache[id] = Cached(installedAt, host)
        return host
    }

    fun closeAll() {
        val all = ArrayList(cache.values)
        cache.clear()
        currentSlots = emptyList()
        all.forEach { runCatching { it.host.close() } }
    }

    private companion object {
        const val TAG = "LxHostPool"
    }
}
