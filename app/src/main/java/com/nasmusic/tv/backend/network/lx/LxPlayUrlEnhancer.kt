package com.nasmusic.tv.backend.network.lx

import com.nasmusic.tv.backend.network.Playability
import com.nasmusic.tv.backend.network.PlayabilityProbe
import com.nasmusic.tv.backend.network.ResolveResult
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.NetAddressGuard

/**
 * 洛雪增强层（方案 §5.4）。
 *
 * `MetingApiService.resolvePlayUrlDetailed()` 的第一跳：命中就返回，null 就继续走原 302 链路。
 * `null` = 未启用，行为与改造前逐字符一致。
 *
 * ⚠️ 本接口是 **public**：`MetingApiService` 的构造参数要跨包暴露它（增强层的实现类仍是 internal）。
 */
interface PlayUrlEnhancer {
    suspend fun tryResolve(song: Song, quality: Int): ResolveResult?
}

/** 一个可用脚本：`scriptId` 用于健康状态键，[host] 提供真实取链 */
internal class LxScriptSlot(val scriptId: String, val host: LxHost)

/**
 * 把「Meting 解析直链」升级成「先问洛雪脚本」。
 *
 * 红线（方案 §5.4）：
 * - 内部**全包 try/catch**，任何异常返回 null，绝不外抛（播放链路不能因为增强层崩）；
 * - **不做重试循环**——多档位下探是脚本声明的降级路径，不是重试；
 * - ⛔ **拿到非空 URL ≠ 可播**：必须复用既有 [PlayabilityProbe] 跟跳验活一次，
 *   且判据保持保守（只有 `DEAD` 判失败，`UNKNOWN` 放行）。验证按钮用的是另一套更严的判据，
 *   两者**不要合并**（合并任一方向都会出问题，见 §5.4.1.3）。
 */
internal class LxPlayUrlEnhancer(
    private val registry: LxSourceRegistry,
    /** 已启用脚本，**用户排序**（多源时先试用户排在前面的） */
    private val slots: () -> List<LxScriptSlot>,
    private val probe: PlayabilityProbe,
    /** `song.networkPlatform` 缺失时的回退（历史数据 = 当前全局平台） */
    private val fallbackPlatform: () -> String?,
) : PlayUrlEnhancer {

    override suspend fun tryResolve(song: Song, quality: Int): ResolveResult? {
        return try {
            val platform = song.networkPlatform ?: fallbackPlatform()
            val source = LxPlatformMap.toLxSource(platform)
            val musicInfo = LxPlatformMap.musicInfoOf(song, platform)
            if (source == null || musicInfo == null) return null

            for (slot in slots()) {
                val loaded = slot.host.info as? LxScriptInfo.Loaded ?: continue
                val declared = loaded.sources[source]?.qualitys ?: continue
                val plan = LxQualityMapper.planFor(quality, declared)
                if (plan.isEmpty()) continue
                if (!registry.tryAcquire(slot.scriptId, source)) continue

                val url = firstPlayable(slot, source, musicInfo, plan)
                if (url == null) {
                    registry.markFailure(slot.scriptId, source)
                    AppLog.d(TAG, "enhance: ${slot.scriptId}/$source 全档失败，回退 Meting")
                    continue
                }
                registry.markSuccess(slot.scriptId, source)
                return url
            }
            null
        } catch (t: Throwable) {
            AppLog.w(TAG, "enhance: 增强层异常，回退 Meting —— ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** @return 命中且验活通过的结果；null = 该脚本该平台全档失败 */
    private suspend fun firstPlayable(
        slot: LxScriptSlot,
        source: String,
        musicInfo: LxMusicInfo,
        plan: List<String>,
    ): ResolveResult? {
        for (quality in plan) {
            val url = slot.host.getMusicUrl(source, musicInfo, quality) ?: continue
            // V4-N01：洛雪脚本给的播放 URL 是远端不可信内容，必须先在出站守卫过一遍，
            // 否则下面的 probe.probe(url) 会直接向 127.0.0.1 / 169.254.169.254 发请求
            // （内网探测 / 云元数据）。⚠️ 用 rejectReasonForPlayback：放行 192.168.x /
            // nas.local 这类用户自己的 NAS 直链，与 guardPlayUrl 同口径。
            val reject = NetAddressGuard.rejectReasonForPlayback(url)
            if (reject != null) {
                AppLog.w(TAG, "enhance: ${slot.scriptId}/$source 拒绝播放直链：$reject")
                continue
            }
            // 保守判据：只有明确 DEAD 才弃用该档；UNKNOWN（网络抖动）照常放行
            if (probe.probe(url) == Playability.DEAD) {
                AppLog.d(TAG, "enhance: ${slot.scriptId}/$source $quality 验活 DEAD")
                continue
            }
            AppLog.d(TAG, "enhance: ${slot.scriptId}/$source 命中 $quality")
            return ResolveResult(url, LxQualityMapper.fromLx(quality))
        }
        return null
    }

    private companion object {
        const val TAG = "LxEnhance"
    }
}
