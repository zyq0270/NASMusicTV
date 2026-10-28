package com.nasmusic.tv.backend.network.lx

/**
 * 洛雪自定义源协议的数据模型（方案 §5.4.1.6）。
 *
 * ⚠️ 本包内 `source` **专指洛雪平台**（`wy` / `kg` / `tx` / `kw` / `mg`），
 * 脚本正文一律叫 `scriptText`。两义混用会在 `getMusicUrl(source, …)` 的形参上撞车。
 */

/** `host.load()` 的结果：脚本自报的 `inited` 内容 */
internal sealed interface LxScriptInfo {
    /** `inited` 已收到：[sources] 是脚本声明的平台 → 声明档位 */
    data class Loaded(
        val sources: Map<String, LxDeclaredSource>,
        val version: String?,
        val name: String?,
    ) : LxScriptInfo

    data class Failed(val reason: LxFailReason, val detail: String?) : LxScriptInfo
}

/** 脚本为某个平台声明的元数据 */
internal data class LxDeclaredSource(
    val name: String?,
    val actions: List<String> = emptyList(),
    /** 声明档位（⛔ 是「声明」不是「能力」，方案 §6.1） */
    val qualitys: List<String> = emptyList(),
)

/** 喂给脚本 `musicInfo` 的曲目字段（方案 §2.3 实测字段集） */
internal data class LxMusicInfo(
    val songmid: String? = null,
    val hash: String? = null,
    val copyrightId: String? = null,
    val name: String? = null,
    val singer: String? = null,
    val albumName: String? = null,
    val albumId: String? = null,
    val interval: String? = null,
) {
    /** 脚本侧按 `musicInfo.xxx` 直取，故只需非空字段 */
    fun toJson(): String {
        val map = linkedMapOf<String, String>()
        songmid?.let { map["songmid"] = it }
        hash?.let { map["hash"] = it }
        copyrightId?.let { map["copyrightId"] = it }
        name?.let { map["name"] = it }
        singer?.let { map["singer"] = it }
        albumName?.let { map["albumName"] = it }
        albumId?.let { map["albumId"] = it }
        interval?.let { map["interval"] = it }
        return map.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"$k\":\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
    }
}

// ─────────────────────────── 验证（L1 / L2 / L3） ───────────────────────────

internal sealed interface LxL1 {
    data class Loaded(val sources: List<String>, val version: String?) : LxL1
    data class Failed(val reason: LxFailReason, val detail: String?) : LxL1
    data object NotInstalled : LxL1
}

internal sealed interface LxL2 {
    data class Url(val url: String) : LxL2
    data object Empty : LxL2
    data class Error(val reason: LxFailReason, val detail: String?) : LxL2
}

internal sealed interface LxL3 {
    data class Ok(val code: Int, val contentType: String?, val magic: String?) : LxL3
    data class Doubtful(val code: Int, val contentType: String?) : LxL3
    data class Dead(val code: Int, val contentType: String?, val reason: LxFailReason) : LxL3
    data class Error(val reason: LxFailReason, val detail: String?) : LxL3
}

internal enum class LxFailReason {
    L1_TIMEOUT, L1_SCRIPT_ERROR, L1_NO_SOURCES, L1_NOT_INSTALLED,
    EMPTY_URL, BAD_URL, SCRIPT_ERROR, TIMEOUT,
    HTTP_403, HTTP_404, HTTP_429, HTTP_5XX,
    NOT_AUDIO, NO_ANCHOR, BUDGET, CANCELLED,

    /** `SWEEP` 只探 kg ⇒ 其余平台是这个原因，⛔ 不是「无锚点」也不是「坏了」 */
    SKIPPED_BY_DEPTH,
}

internal enum class LxPlatformVerdict { PLAYABLE, DOUBTFUL, DEAD, UNTESTED }

internal enum class LxProbeDepth {
    /** 「全部验证」专用：只探 kg × 最高档（≈21 s/源） */
    SWEEP,

    /** 单源「验证」默认：全部已声明平台 × 各自最高档 */
    QUICK,

    /** 全部平台 × 最高档 + 次高档（命中即停） */
    FULL,

    /** 全部平台 × 全部声明档位 */
    MATRIX,
}

internal data class LxAttempt(
    val quality: String,
    val l2: LxL2,
    /** null = 没走到 L3 */
    val l3: LxL3?,
)

internal data class LxPlatformProbe(
    val source: String,
    val declaredQualities: List<String>,
    val attempts: List<LxAttempt>,
    val verdict: LxPlatformVerdict,
    /** 实测拿到的最好档位（≠ 声明） */
    val bestQuality: String?,
    val actualContainer: LxContainer?,
    /** 声明无损、实测 MP3 ⇒ 虚标 */
    val qualityMismatch: Boolean,
    val reason: LxFailReason?,
)

internal enum class LxContainer { FLAC, MP3, M4A, OGG }

internal data class LxProbeReport(
    val scriptId: String,
    val scriptName: String,
    val version: String?,
    val l1: LxL1,
    val platforms: List<LxPlatformProbe>,
    val depth: LxProbeDepth,
    val startedAt: Long,
    val elapsedMs: Long,
    val truncated: Boolean,
)

internal sealed interface LxProbeEvent {
    data class L1Done(val l1: LxL1) : LxProbeEvent
    data class PlatformStart(val source: String, val index: Int, val total: Int) : LxProbeEvent
    data class AttemptDone(val source: String, val attempt: LxAttempt) : LxProbeEvent
    data class PlatformDone(val probe: LxPlatformProbe) : LxProbeEvent
    data class Finished(val report: LxProbeReport) : LxProbeEvent
    data object Cancelled : LxProbeEvent
}

/**
 * 脚本头部注释里的元信息（`@name` / `@version` / …）。
 *
 * 洛雪本体把它作为 `lx.currentScriptInfo` 注入沙箱，**部分脚本会读它做校验**，
 * 所以必须在执行正文前就解析好。`@name` 缺失即认为不是合法音源（安装时拒绝，方案 §5.3）。
 */
internal data class LxScriptHeader(
    val name: String? = null,
    val version: String? = null,
    val author: String? = null,
    val description: String? = null,
    val homepage: String? = null,
) {
    fun toJson(): String = org.json.JSONObject().apply {
        put("name", name.orEmpty())
        put("version", version.orEmpty())
        put("author", author.orEmpty())
        put("description", description.orEmpty())
        put("homepage", homepage.orEmpty())
    }.toString()

    companion object {
        private val FIELDS = setOf("name", "version", "author", "description", "homepage")
        private val ENTRY = Regex("""@([A-Za-z]+)[ \t]+([^\r\n]*)""")
        private const val HEAD_LIMIT = 4096

        fun parse(scriptText: String): LxScriptHeader {
            val head = scriptText.take(HEAD_LIMIT)
            val start = head.indexOf("/*")
            val end = if (start >= 0) head.indexOf("*/", start + 2) else -1
            val block = if (start >= 0 && end > start) head.substring(start, end) else head
            val found = HashMap<String, String>(4)
            for (match in ENTRY.findAll(block)) {
                val key = match.groupValues[1].lowercase()
                if (key !in FIELDS || found.containsKey(key)) continue
                val value = match.groupValues[2].removeSuffix("*/").trim()
                if (value.isNotEmpty()) found[key] = value
            }
            return LxScriptHeader(
                name = found["name"],
                version = found["version"],
                author = found["author"],
                description = found["description"],
                homepage = found["homepage"],
            )
        }
    }
}
