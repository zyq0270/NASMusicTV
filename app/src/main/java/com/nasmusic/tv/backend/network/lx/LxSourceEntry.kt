package com.nasmusic.tv.backend.network.lx

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条已安装脚本的元数据（方案 §8 `settings_lx_sources` 的一条）。
 *
 * 正文不入 DataStore：333 KB 只能放文件（[LxScriptRepo]）。这里只放「列表页要显示什么」。
 *
 * @param id 稳定标识（由 URL 派生，见 [idOf]）—— 文件名叫 `<id>.js`，改 URL 等于换源
 * @param installedAt 正文写入时间；`0` = **只登记了 URL、正文还没下载**
 * @param lastStatus 紧凑验证结果（§8.1，⛔ 不含 URL / 响应头）
 */
internal data class LxSourceEntry(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val version: String?,
    val installedAt: Long,
    val lastStatus: String? = null,
    /**
     * 安装时正文的 SHA-256（hex，小写）。
     *
     * 用于**落盘后完整性校验**：脚本正文是会被 QuickJS 直接执行的代码，一旦磁盘上的文件被
     * 其它途径改写（备份恢复、外部工具、越权写入），加载前必须能发现。
     *
     * `null` = 老版本安装的记录，未做 pin（下次刷新/重装后自动补上）。
     */
    val sha256: String? = null,
) {
    val installed: Boolean get() = installedAt > 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", url)
        put("enabled", enabled)
        put("version", version ?: "")
        put("installedAt", installedAt)
        if (lastStatus != null) put("lastStatus", JSONObject(lastStatus))
        if (!sha256.isNullOrBlank()) put("sha256", sha256)
    }

    companion object {
        fun fromJson(obj: JSONObject): LxSourceEntry = LxSourceEntry(
            id = obj.optString("id"),
            name = obj.optString("name"),
            url = obj.optString("url"),
            enabled = obj.optBoolean("enabled", true),
            version = obj.optString("version").takeIf { it.isNotBlank() },
            installedAt = obj.optLong("installedAt", 0L),
            lastStatus = obj.optJSONObject("lastStatus")?.toString(),
            sha256 = obj.optString("sha256").takeIf { it.isNotBlank() },
        )

        fun parseList(json: String?): List<LxSourceEntry> {
            if (json.isNullOrBlank()) return DEFAULT
            val stored = runCatching {
                val array = JSONArray(json)
                buildList {
                    for (i in 0 until array.length()) add(fromJson(array.getJSONObject(i)))
                }
            }.getOrDefault(DEFAULT)
            return withNewDefaults(stored)
        }

        /**
         * 把**后加进 [DEFAULT] 的内置源**补进用户已有列表（v2.39.8）。
         *
         * 为什么需要它：`parseList` 只在**从未写过** DataStore 时回退 [DEFAULT]。先装旧版的用户
         * 库里只有墨澜 + 星海，后来实测能播的 1010 / 星澜 / HYW 永远不会出现在设置页
         * —— 表现为「验证过的源没加进设置页」。
         *
         * ⚠️ 按 id 去重、**只增不删**：本仓库没有「删除脚本」入口，所以补回来不会与用户的删除意图冲突；
         * 用户的启停选择与验证结果都保留（只补缺失的 id）。
         */
        private fun withNewDefaults(stored: List<LxSourceEntry>): List<LxSourceEntry> {
            val known = stored.mapTo(HashSet()) { it.id }
            val missing = DEFAULT.filter { it.id !in known }
            return if (missing.isEmpty()) stored else stored + missing
        }

        fun toListJson(entries: List<LxSourceEntry>): String =
            JSONArray().apply { entries.forEach { put(it.toJson()) } }.toString()

        /**
         * 稳定 id：URL 归一化后取哈希。
         *
         * 用 URL 派生而不是自增序号：同一份脚本重复导入 ⇒ 同一条记录（不产生「墨澜 ×3」），
         * 换人换了下载地址 ⇒ 视为另一个源（文件名也不同，旧正文不会串味）。
         */
        fun idOf(url: String): String {
            val normalized = url.trim()
            var hash = 0
            for (ch in normalized) hash = hash * 31 + ch.code
            return "lx" + Integer.toHexString(hash)
        }

        /**
         * 出厂内置清单 = 方案 §2.2 里 **L3 实测能播的全部 5 个源**
         * （墨澜 / 星海 / 1010 / 星澜 / HYW）。
         *
         * ⛔ **不要把其余 7 个也塞进来**：它们已实测失效（长青、幻音「能加载、能返回 URL，
         * 但地址是死的」最典型，§2.2），列出来只会让用户在设置页看到一排永远红的源。
         * 想装的自定义源走「从 URL 导入」。
         *
         * ⚠️ **只登记 URL，不内置正文**（§9.4：脚本不随 APK 分发）。`installedAt = 0` ⇒
         * 正文要等用户点「刷新全部」（`MainViewModel.refreshLxScripts`）才拉；失败就停在
         * 「未下载」，不影响播放。点单个源的「验证」也会先补下载（`MainViewModel.runOneLxVerify`）。
         *
         * ⚠️ 内存：每个启用的脚本常驻一个 QuickJS runtime（§9.3 单 runtime < 15 MB）。
         * 5 个全开在老电视上偏高，子块底部有「建议只留 1~2 个」的提示。
         */
        val DEFAULT: List<LxSourceEntry> = listOf(
            entry(
                name = "墨澜 Molan",
                url = "https://raw.githubusercontent.com/shiliu6924/lx-music-source/main/墨澜2.3.4.js",
            ),
            entry(
                name = "星海 XingHai",
                url = "https://zrcdy.dpdns.org/lx/xinghai-music-sourcev2.3.15.js",
            ),
            entry(
                name = "1010 音源",
                url = "https://raw.githubusercontent.com/Lingeng27/lx-music-1010-source/main/1010.js",
            ),
            entry(
                name = "星澜 StellarWave",
                url = "https://raw.githubusercontent.com/shiliu6924/lx-music-source/main/星澜4.0.0.js",
            ),
            entry(
                name = "HYW 公益版",
                url = "https://raw.githubusercontent.com/shiliu6924/lx-music-source/main/HYW1.2.0.js",
            ),
        )

        private fun entry(name: String, url: String) = LxSourceEntry(
            id = idOf(url),
            name = name,
            url = url,
            enabled = true,
            version = null,
            installedAt = 0L,
        )
    }
}
