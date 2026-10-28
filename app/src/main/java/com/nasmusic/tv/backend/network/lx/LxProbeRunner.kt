package com.nasmusic.tv.backend.network.lx

import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/** 探测预算（方案 §5.4.1.5 的表；电视实测值） */
internal data class LxBudgets(
    val l1Ms: Long = 10_000,
    val musicUrlMs: Long = 8_000,
    val l3Ms: Long = 3_000,
    val platformMs: Long = 20_000,
    val scriptMs: Long = 60_000,
)

/**
 * 源验证编排（方案 §5.4.1.5）：L1 → 逐平台 L2 → L3，逐条吐事件。
 *
 * 红线（§5.4.1.7）：
 * - ⛔ **纯观察者**：不写 `Degraded`、不清直链缓存、不改 `enabled`；
 * - 但**必须**在探测期间设 `probing`（否则 `tryAcquire` 不拦，播放会排在探测后面干等）；
 * - 复用**同一个** `LxHost`（不新建 runtime，电视内存经不起第二份）；
 * - 取消要真的打断在途请求 ⇒ `finally { host.cancelPending() }`。
 */
internal class LxProbeRunner(
    private val registry: LxSourceRegistry,
    private val inspector: LxUrlInspector,
    /** `scriptId → host`；null = 没安装（验证会如实报 `L1_NOT_INSTALLED`） */
    private val hostProvider: (String) -> LxHost?,
    private val anchors: Map<String, LxAnchor> = LxAnchorTable.DEFAULT,
    private val budgets: LxBudgets = LxBudgets(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun verify(scriptId: String, depth: LxProbeDepth = LxProbeDepth.QUICK): Flow<LxProbeEvent> = flow {
        val startedAt = clock()
        val host = hostProvider(scriptId)
        // 探测期间必须挡住播放取链：两者共用同一 host（内部单线程串行），
        // 不拦就是「用户按了播放却不出声」（§5.4.1.7 第 3 条）
        if (host != null) registry.setProbing(scriptId, true)
        try {
            val l1 = if (host == null) LxL1.NotInstalled else runL1(host)
            emit(LxProbeEvent.L1Done(l1))

            if (host == null || l1 !is LxL1.Loaded) {
                emit(
                    LxProbeEvent.Finished(
                        LxProbeReport(
                            scriptId = scriptId,
                            scriptName = "",
                            version = (l1 as? LxL1.Loaded)?.version,
                            l1 = l1,
                            platforms = emptyList(),
                            depth = depth,
                            startedAt = startedAt,
                            elapsedMs = clock() - startedAt,
                            truncated = false,
                        ),
                    ),
                )
                return@flow
            }

            var truncated = false
            val probes = ArrayList<LxPlatformProbe>()
            for ((index, source) in l1.sources.withIndex()) {
                currentCoroutineContext().ensureActive()
                emit(LxProbeEvent.PlatformStart(source, index, l1.sources.size))

                val declared = declaredQualitiesOf(host, source)
                val probe = when {
                    // 批量筛选只探 kg：其它平台如实记「没轮到」，⛔ 不是「没锚点」也不是「坏了」
                    depth == LxProbeDepth.SWEEP && source != SWEEP_SOURCE ->
                        skip(source, declared, LxFailReason.SKIPPED_BY_DEPTH)
                    clock() - startedAt > budgets.scriptMs -> {
                        truncated = true
                        skip(source, declared, LxFailReason.BUDGET)
                    }
                    else -> probePlatform(host, source, declared, depth)
                }
                probes += probe
                emit(LxProbeEvent.PlatformDone(probe))
            }

            emit(
                LxProbeEvent.Finished(
                    LxProbeReport(
                        scriptId = scriptId,
                        scriptName = (host.info as? LxScriptInfo.Loaded)?.name.orEmpty(),
                        version = l1.version,
                        l1 = l1,
                        platforms = probes,
                        depth = depth,
                        startedAt = startedAt,
                        elapsedMs = clock() - startedAt,
                        truncated = truncated,
                    ),
                ),
            )
        } finally {
            if (host != null) {
                registry.setProbing(scriptId, false)
                // 取消必须真的打断在途请求，否则脚本还会跑到 8 s 超时，用户点了「停止」CPU 还在烧
                host.cancelPending()
            }
        }
    }

    // ─────────────────────────── L1 ───────────────────────────

    private suspend fun runL1(host: LxHost): LxL1 {
        val info = try {
            withTimeoutOrNull(budgets.l1Ms) { host.load() }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            AppLog.w(TAG, "L1 异常：${t.javaClass.simpleName}: ${t.message}")
            null
        } ?: return LxL1.Failed(LxFailReason.L1_TIMEOUT, null)

        return when (info) {
            is LxScriptInfo.Loaded -> {
                val sources = info.sources.keys.toList()
                if (sources.isEmpty()) LxL1.Failed(LxFailReason.L1_NO_SOURCES, null)
                else LxL1.Loaded(sources, info.version)
            }
            is LxScriptInfo.Failed -> LxL1.Failed(info.reason, info.detail)
        }
    }

    // ─────────────────────────── L2 + L3 ───────────────────────────

    private suspend fun probePlatform(
        host: LxHost,
        source: String,
        declared: List<String>,
        depth: LxProbeDepth,
    ): LxPlatformProbe {
        val anchor = anchors[source]
        if (anchor == null || !anchor.verified) {
            // 没有可信样本 ⇒ 不测。⛔ 绝不伪造 id（§5.4.1.4 第 3 条）
            return LxPlatformProbe(
                source = source,
                declaredQualities = declared,
                attempts = emptyList(),
                verdict = LxPlatformVerdict.UNTESTED,
                bestQuality = null,
                actualContainer = null,
                qualityMismatch = false,
                reason = LxFailReason.NO_ANCHOR,
            )
        }

        val plan = qualityPlan(declared, depth)
        if (plan.isEmpty()) {
            return LxPlatformProbe(
                source = source,
                declaredQualities = declared,
                attempts = emptyList(),
                verdict = LxPlatformVerdict.UNTESTED,
                bestQuality = null,
                actualContainer = null,
                qualityMismatch = false,
                reason = LxFailReason.NO_ANCHOR,
            )
        }

        val attempts = ArrayList<LxAttempt>(plan.size)
        val platformStart = clock()
        var budgetHit = false
        var okQuality: String? = null
        var doubtful: String? = null
        var actualContainer: LxContainer? = null

        for (quality in plan) {
            currentCoroutineContext().ensureActive()
            if (clock() - platformStart > budgets.platformMs) {
                budgetHit = true
                break
            }
            val l2 = runL2(host, source, anchor.toMusicInfo(), quality)
            val l3 = if (l2 is LxL2.Url) runL3(l2.url) else null
            attempts += LxAttempt(quality, l2, l3)

            when (l3) {
                is LxL3.Ok -> {
                    if (okQuality == null) {
                        okQuality = quality
                        actualContainer = LxMagic.containerOf(l3.magic)
                    }
                    // 命中即停；`MATRIX` 例外——它要回答「每档到底拿到什么」，必须跑完
                    if (depth != LxProbeDepth.MATRIX) break
                }
                is LxL3.Doubtful -> {
                    if (doubtful == null) doubtful = quality
                }
                else -> Unit
            }
        }

        val verdict = when {
            okQuality != null -> LxPlatformVerdict.PLAYABLE
            doubtful != null -> LxPlatformVerdict.DOUBTFUL
            // 超预算 ⇒ 未测（⛔ 不能把「没测完」说成「坏了」，§5.4.1.6 的 verdict 表）
            budgetHit -> LxPlatformVerdict.UNTESTED
            else -> LxPlatformVerdict.DEAD
        }

        return LxPlatformProbe(
            source = source,
            declaredQualities = declared,
            attempts = attempts,
            verdict = verdict,
            bestQuality = okQuality ?: doubtful,
            actualContainer = actualContainer,
            qualityMismatch = isQualityMismatch(declared, actualContainer, verdict),
            reason = when (verdict) {
                LxPlatformVerdict.PLAYABLE, LxPlatformVerdict.DOUBTFUL -> null
                LxPlatformVerdict.UNTESTED -> if (budgetHit) LxFailReason.BUDGET else LxFailReason.NO_ANCHOR
                LxPlatformVerdict.DEAD -> attempts.lastOrNull()?.failureReason() ?: LxFailReason.EMPTY_URL
            },
        )
    }

    private suspend fun runL2(
        host: LxHost,
        source: String,
        musicInfo: LxMusicInfo,
        quality: String,
    ): LxL2 {
        val raw = try {
            withTimeoutOrNull(budgets.musicUrlMs) { host.getMusicUrl(source, musicInfo, quality) }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            return LxL2.Error(LxFailReason.SCRIPT_ERROR, t.message)
        }
        // ⚠️ 宿主内部已经把「脚本报错 / 超时 / 空串」统一折成 null（LxHost 契约），
        // 所以这里分不清三者的比例；能确定的是「没拿到 URL」。TIMEOUT 只在宿主自己卡死时出现。
        if (raw == null) return LxL2.Error(LxFailReason.EMPTY_URL, null)
        val url = raw.trim()
        if (url.isEmpty()) return LxL2.Empty
        // ⚠️ 必须用前缀判断：`Regex("""^https?://""").matches()` 是**全串匹配**，
        // 任何真实直链都会被判 BAD_URL ⇒ 每个平台都被打成 DEAD。
        if (!url.startsWith("http://") && !url.startsWith("https://") ||
            url.contains("undefined") || url.contains("null")
        ) {
            return LxL2.Error(LxFailReason.BAD_URL, url.take(200))
        }
        return LxL2.Url(url)
    }

    private suspend fun runL3(url: String): LxL3 =
        try {
            withTimeoutOrNull(budgets.l3Ms) { inspector.inspect(url) }
                ?: LxL3.Error(LxFailReason.TIMEOUT, null)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            LxL3.Error(LxFailReason.TIMEOUT, t.message)
        }

    // ─────────────────────────── 小工具 ───────────────────────────

    private fun declaredQualitiesOf(host: LxHost, source: String): List<String> =
        (host.info as? LxScriptInfo.Loaded)?.sources?.get(source)?.qualitys.orEmpty()

    /** 深度 → 实际尝试的档位（声明序，最高档在前由脚本自己决定） */
    private fun qualityPlan(declared: List<String>, depth: LxProbeDepth): List<String> = when (depth) {
        LxProbeDepth.SWEEP, LxProbeDepth.QUICK -> declared.take(1)
        LxProbeDepth.FULL -> declared.take(2)
        LxProbeDepth.MATRIX -> declared
    }

    private fun skip(source: String, declared: List<String>, reason: LxFailReason) = LxPlatformProbe(
        source = source,
        declaredQualities = declared,
        attempts = emptyList(),
        verdict = LxPlatformVerdict.UNTESTED,
        bestQuality = null,
        actualContainer = null,
        qualityMismatch = false,
        reason = reason,
    )

    /** 声明无损、实测 MP3 系容器 ⇒ 虚标（§5.4.1.3 顺手白捡的信号） */
    private fun isQualityMismatch(
        declared: List<String>,
        container: LxContainer?,
        verdict: LxPlatformVerdict,
    ): Boolean {
        if (verdict != LxPlatformVerdict.PLAYABLE || container == null) return false
        val claimsLossless = declared.any { it == "flac" || it == "flac24bit" }
        return claimsLossless && container != LxContainer.FLAC
    }

    private fun LxAttempt.failureReason(): LxFailReason? = when (val l3 = l3) {
        null -> when (val l2 = l2) {
            is LxL2.Error -> l2.reason
            LxL2.Empty -> LxFailReason.EMPTY_URL
            is LxL2.Url -> null
        }
        is LxL3.Dead -> l3.reason
        is LxL3.Error -> l3.reason
        is LxL3.Doubtful -> null
        is LxL3.Ok -> null
    }

    private companion object {
        const val TAG = "LxProbeRunner"

        /** 批量筛选的唯一探针：`kg` 是唯一对未授权用户开放的平台（§2.2.1） */
        const val SWEEP_SOURCE = "kg"
    }
}
