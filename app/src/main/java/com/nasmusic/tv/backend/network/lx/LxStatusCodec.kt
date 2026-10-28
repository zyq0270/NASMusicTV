package com.nasmusic.tv.backend.network.lx

import org.json.JSONObject

/**
 * `lastStatus` 的紧凑编解码（方案 §8.1）。
 *
 * ⛔ **只存「下次进设置页先显示什么」**：完整 [LxProbeReport]（含每次尝试的 URL 与响应头）
 * 只在内存里供详情面板展开，进程重启即丢。这里刻意不含 URL —— 直链带签名，落盘既无意义也易泄漏。
 */
internal object LxStatusCodec {

    /** 超过这个时长就显示「结果已过期」（方案 §7.2 状态机最后一行） */
    const val STALE_AFTER_MS = 24L * 60 * 60 * 1000

    fun isStale(ts: Long, now: Long = System.currentTimeMillis()): Boolean = now - ts > STALE_AFTER_MS

    fun encode(report: LxProbeReport): String {
        val root = JSONObject()
        root.put("ts", report.startedAt)
        val l1 = report.l1
        root.put("l1", if (l1 is LxL1.Loaded) "ok" else "fail")
        if (l1 is LxL1.Failed) {
            root.put("l1r", l1.reason.name)
            l1.detail?.let { root.put("l1d", it.take(200)) }
        }
        report.version?.let { root.put("version", it) }
        if (report.scriptName.isNotBlank()) root.put("name", report.scriptName)
        root.put("truncated", report.truncated)
        val platforms = JSONObject()
        for (p in report.platforms) {
            val o = JSONObject()
            o.put(
                "v",
                when (p.verdict) {
                    LxPlatformVerdict.PLAYABLE -> "ok"
                    LxPlatformVerdict.DOUBTFUL -> "doubt"
                    LxPlatformVerdict.DEAD -> "dead"
                    LxPlatformVerdict.UNTESTED -> "untested"
                },
            )
            p.reason?.let { o.put("r", it.name) }
            p.bestQuality?.let { o.put("q", it) }
            p.actualContainer?.let { o.put("c", it.name) }
            if (p.qualityMismatch) o.put("mismatch", true)
            platforms.put(p.source, o)
        }
        root.put("platforms", platforms)
        return root.toString()
    }

    /** 解析失败一律返回 null（坏数据不该让设置页打不开） */
    fun decode(json: String?): LxStoredStatus? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(json)
            val platformsObj = root.optJSONObject("platforms")
            val platforms = HashMap<String, LxStoredPlatform>(5)
            if (platformsObj != null) {
                for (source in platformsObj.keys()) {
                    val o = platformsObj.optJSONObject(source) ?: continue
                    platforms[source] = LxStoredPlatform(
                        verdict = when (o.optString("v")) {
                            "ok" -> LxPlatformVerdict.PLAYABLE
                            "doubt" -> LxPlatformVerdict.DOUBTFUL
                            "dead" -> LxPlatformVerdict.DEAD
                            else -> LxPlatformVerdict.UNTESTED
                        },
                        reason = o.optString("r").takeIf { it.isNotBlank() }?.let { name ->
                            LxFailReason.entries.firstOrNull { it.name == name }
                        },
                        quality = o.optString("q").takeIf { it.isNotBlank() },
                        container = o.optString("c").takeIf { it.isNotBlank() }?.let { name ->
                            LxContainer.entries.firstOrNull { it.name == name }
                        },
                        mismatch = o.optBoolean("mismatch", false),
                    )
                }
            }
            LxStoredStatus(
                ts = root.optLong("ts"),
                l1Ok = root.optString("l1") == "ok",
                l1Reason = root.optString("l1r").takeIf { it.isNotBlank() }?.let { name ->
                    LxFailReason.entries.firstOrNull { it.name == name }
                },
                l1Detail = root.optString("l1d").takeIf { it.isNotBlank() },
                version = root.optString("version").takeIf { it.isNotBlank() },
                name = root.optString("name").takeIf { it.isNotBlank() },
                truncated = root.optBoolean("truncated", false),
                platforms = platforms,
            )
        }.getOrNull()
    }
}

/** 落盘形态（与 §8.1 的 JSON 一一对应） */
internal data class LxStoredStatus(
    val ts: Long,
    val l1Ok: Boolean,
    val l1Reason: LxFailReason?,
    val l1Detail: String?,
    val version: String?,
    val name: String?,
    val truncated: Boolean,
    val platforms: Map<String, LxStoredPlatform>,
)

internal data class LxStoredPlatform(
    val verdict: LxPlatformVerdict,
    val reason: LxFailReason?,
    val quality: String?,
    val container: LxContainer?,
    val mismatch: Boolean,
)
