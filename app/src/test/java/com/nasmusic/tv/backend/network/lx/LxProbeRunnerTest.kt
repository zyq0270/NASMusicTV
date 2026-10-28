package com.nasmusic.tv.backend.network.lx

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T21：验证编排（方案 §5.4.1.5）。
 *
 * 全部用假 host + 假 L3 注入，⛔ 不打网络。覆盖 7 条失败路径、四档深度、预算与取消。
 *
 * ⚠️ 夹具是**自写**的（假平台 + 假 id），不含任何第三方脚本正文。
 */
@RunWith(RobolectricTestRunner::class)
class LxProbeRunnerTest {

    // ─────────────────────────── 夹具 ───────────────────────────

    private class FakeHost(
        override val info: LxScriptInfo?,
        private val answers: Map<String, String?> = emptyMap(),
        private val loadDelayMs: Long = 0,
        private val onGet: (() -> Unit)? = null,
    ) : LxHost {
        val calls = mutableListOf<Pair<String, String>>()
        var cancelPendingCalls = 0

        override suspend fun load(): LxScriptInfo {
            if (loadDelayMs > 0) delay(loadDelayMs)
            return info ?: LxScriptInfo.Failed(LxFailReason.L1_SCRIPT_ERROR, "夹具：没有 info")
        }

        override suspend fun getMusicUrl(source: String, musicInfo: LxMusicInfo, quality: String): String? {
            onGet?.invoke()
            calls += source to quality
            return answers["$source:$quality"]
        }

        override fun cancelPending() {
            cancelPendingCalls++
        }

        override fun close() = Unit
    }

    private class FakeInspector(
        private val answers: Map<String, LxL3> = emptyMap(),
    ) : LxUrlInspector() {
        override fun inspect(url: String): LxL3 = answers[url] ?: LxL3.Ok(206, "audio/mpeg", "MP3")
    }

    private fun loaded(vararg pairs: Pair<String, List<String>>): LxScriptInfo.Loaded =
        LxScriptInfo.Loaded(
            sources = pairs.associate { (source, qualities) ->
                source to LxDeclaredSource(name = source, actions = listOf("musicUrl"), qualitys = qualities)
            },
            version = "1.0.0",
            name = "夹具源",
        )

    private fun runner(
        host: LxHost?,
        registry: LxSourceRegistry = LxSourceRegistry { 0L },
        inspector: LxUrlInspector = FakeInspector(),
        anchors: Map<String, LxAnchor> = LxAnchorTable.DEFAULT,
        budgets: LxBudgets = LxBudgets(),
        clock: () -> Long = { 0L },
    ) = LxProbeRunner(
        registry = registry,
        inspector = inspector,
        hostProvider = { host },
        anchors = anchors,
        budgets = budgets,
        clock = clock,
    )

    private fun finished(events: List<LxProbeEvent>): LxProbeReport =
        (events.last() as LxProbeEvent.Finished).report

    // ─────────────────────────── L1 ───────────────────────────

    @Test
    fun missingHostReportsNotInstalled() = runTest {
        val events = runner(host = null).verify("s1").toList()
        assertEquals(LxL1.NotInstalled, (events.first() as LxProbeEvent.L1Done).l1)
        assertEquals(0, finished(events).platforms.size)
    }

    @Test
    fun l1TimeoutIsReportedNotThrown() = runTest {
        val host = FakeHost(loaded("kg" to listOf("320k")), loadDelayMs = 1_000)
        val events = runner(host, budgets = LxBudgets(l1Ms = 5)).verify("s1").toList()
        val l1 = (events.first() as LxProbeEvent.L1Done).l1
        assertTrue(l1 is LxL1.Failed)
        assertEquals(LxFailReason.L1_TIMEOUT, (l1 as LxL1.Failed).reason)
        assertTrue(finished(events).platforms.isEmpty())
    }

    @Test
    fun l1ScriptErrorKeepsDetail() = runTest {
        val host = FakeHost(LxScriptInfo.Failed(LxFailReason.L1_SCRIPT_ERROR, "ReferenceError: lx is not defined"))
        val l1 = (runner(host).verify("s1").toList().first() as LxProbeEvent.L1Done).l1 as LxL1.Failed
        assertEquals(LxFailReason.L1_SCRIPT_ERROR, l1.reason)
        assertEquals("ReferenceError: lx is not defined", l1.detail)
    }

    @Test
    fun emptyDeclaredSourcesFailsL1() = runTest {
        val host = FakeHost(loaded())
        val l1 = (runner(host).verify("s1").toList().first() as LxProbeEvent.L1Done).l1 as LxL1.Failed
        assertEquals(LxFailReason.L1_NO_SOURCES, l1.reason)
    }

    // ─────────────────────────── L2 失败路径 ───────────────────────────

    @Test
    fun missingUrlIsDeadWithEmptyUrlReason() = runTest {
        val host = FakeHost(loaded("wy" to listOf("320k")))
        val report = finished(runner(host).verify("s1").toList())
        val wy = report.platforms.single()
        assertEquals(LxPlatformVerdict.DEAD, wy.verdict)
        assertEquals(LxFailReason.EMPTY_URL, wy.reason)
        assertEquals(LxL2.Error(LxFailReason.EMPTY_URL, null), wy.attempts.single().l2)
    }

    @Test
    fun blankUrlIsDeadNotUrlError() = runTest {
        val host = FakeHost(loaded("wy" to listOf("320k")), mapOf("wy:320k" to "   "))
        val wy = finished(runner(host).verify("s1").toList()).platforms.single()
        assertEquals(LxPlatformVerdict.DEAD, wy.verdict)
        assertEquals(LxL2.Empty, wy.attempts.single().l2)
        assertEquals(LxFailReason.EMPTY_URL, wy.reason)
    }

    @Test
    fun undefinedUrlIsRejectedAsBadUrl() = runTest {
        // §2.3 的 `undefined` 事故：脚本拼出 "undefined" 时必须当场判 BAD_URL，⛔ 不能拿去测
        val host = FakeHost(loaded("wy" to listOf("320k")), mapOf("wy:320k" to "undefined"))
        val wy = finished(runner(host).verify("s1").toList()).platforms.single()
        assertEquals(LxFailReason.BAD_URL, wy.reason)
        assertTrue(wy.attempts.single().l3 == null)
    }

    @Test
    fun http403IsReportedAsAuthFailure() = runTest {
        val host = FakeHost(loaded("wy" to listOf("320k")), mapOf("wy:320k" to "https://cdn/auth.mp3"))
        val inspector = FakeInspector(mapOf("https://cdn/auth.mp3" to LxL3.Dead(403, null, LxFailReason.HTTP_403)))
        val wy = finished(runner(host, inspector = inspector).verify("s1").toList()).platforms.single()
        assertEquals(LxPlatformVerdict.DEAD, wy.verdict)
        assertEquals(LxFailReason.HTTP_403, wy.reason)
    }

    @Test
    fun http5xxIsReportedAsUpstreamFailure() = runTest {
        val host = FakeHost(loaded("kg" to listOf("320k")), mapOf("kg:320k" to "https://cdn/bad.mp3"))
        val inspector = FakeInspector(mapOf("https://cdn/bad.mp3" to LxL3.Dead(503, null, LxFailReason.HTTP_5XX)))
        val kg = finished(runner(host, inspector = inspector).verify("s1").toList()).platforms.single()
        assertEquals(LxFailReason.HTTP_5XX, kg.reason)
    }

    @Test
    fun unverifiedAnchorYieldsNoAnchorAndNeverCallsScript() = runTest {
        // kw/mg 没有可信样本 ⇒ NO_ANCHOR。⛔ 绝不用编造的 id 去测（404 会被显示成「源不支持该平台」）
        val host = FakeHost(loaded("kw" to listOf("320k")))
        val kw = finished(runner(host).verify("s1").toList()).platforms.single()
        assertEquals(LxPlatformVerdict.UNTESTED, kw.verdict)
        assertEquals(LxFailReason.NO_ANCHOR, kw.reason)
        assertTrue(kw.attempts.isEmpty())
        assertEquals(emptyList<Pair<String, String>>(), host.calls)
    }

    // ─────────────────────────── 四档深度 ───────────────────────────

    @Test
    fun sweepOnlyProbesKgAndMarksTheRestSkippedByDepth() = runTest {
        val host = FakeHost(
            loaded("kg" to listOf("320k"), "wy" to listOf("320k"), "tx" to listOf("320k")),
            mapOf(
                "kg:320k" to "https://cdn/kg.mp3",
                "wy:320k" to "https://cdn/wy.mp3",
                "tx:320k" to "https://cdn/tx.mp3",
            ),
        )
        val report = finished(runner(host).verify("s1", LxProbeDepth.SWEEP).toList())
        assertEquals(LxProbeDepth.SWEEP, report.depth)
        val bySource = report.platforms.associateBy { it.source }

        assertEquals(1, bySource.getValue("kg").attempts.size)
        assertEquals(LxPlatformVerdict.PLAYABLE, bySource.getValue("kg").verdict)
        for (other in listOf("wy", "tx")) {
            val probe = bySource.getValue(other)
            assertTrue("$other 不该被调用", probe.attempts.isEmpty())
            // ⛔ 必须是 SKIPPED_BY_DEPTH，不是 NO_ANCHOR：后者会让用户以为「测不了」
            assertEquals(LxFailReason.SKIPPED_BY_DEPTH, probe.reason)
            assertEquals(LxPlatformVerdict.UNTESTED, probe.verdict)
        }
        assertEquals(listOf("kg" to "320k"), host.calls)
    }

    @Test
    fun quickProbesEveryDeclaredPlatformAtTopQuality() = runTest {
        val host = FakeHost(
            loaded("kg" to listOf("flac", "320k"), "wy" to listOf("320k")),
            mapOf("kg:flac" to "https://cdn/kg.flac", "wy:320k" to "https://cdn/wy.mp3"),
        )
        val report = finished(
            runner(host, inspector = FakeInspector(mapOf("https://cdn/kg.flac" to LxL3.Ok(206, "audio/flac", "FLAC"))))
                .verify("s1", LxProbeDepth.QUICK)
                .toList()
        )
        assertEquals(2, report.platforms.size)
        assertTrue(report.platforms.all { it.attempts.size == 1 })
        // 每平台只试自身最高档
        assertEquals(listOf("kg" to "flac", "wy" to "320k"), host.calls)
        assertEquals(LxContainer.FLAC, report.platforms.first { it.source == "kg" }.actualContainer)
    }

    @Test
    fun matrixRunsEveryDeclaredQualityInsteadOfStoppingAtFirstHit() = runTest {
        val host = FakeHost(
            loaded("kg" to listOf("flac", "320k")),
            mapOf("kg:flac" to "https://cdn/kg.flac", "kg:320k" to "https://cdn/kg.mp3"),
        )
        val kg = finished(
            runner(host, inspector = FakeInspector(mapOf("https://cdn/kg.flac" to LxL3.Ok(206, "audio/flac", "FLAC"))))
                .verify("s1", LxProbeDepth.MATRIX)
                .toList()
        ).platforms.single()
        assertEquals(2, kg.attempts.size)
        assertEquals("flac", kg.bestQuality)
    }

    @Test
    fun fullStopsAtFirstHit() = runTest {
        val host = FakeHost(
            loaded("kg" to listOf("flac", "320k", "128k")),
            mapOf(
                "kg:flac" to "https://cdn/kg.flac",
                "kg:320k" to "https://cdn/kg.mp3",
                "kg:128k" to "https://cdn/kg128.mp3",
            ),
        )
        val kg = finished(
            runner(host, inspector = FakeInspector(mapOf("https://cdn/kg.flac" to LxL3.Ok(206, "audio/flac", "FLAC"))))
                .verify("s1", LxProbeDepth.FULL)
                .toList()
        ).platforms.single()
        // FULL = 最高档 + 次高档，命中即停 ⇒ 128k 不应被尝试
        assertEquals(listOf("kg" to "flac"), host.calls)
        assertEquals(1, kg.attempts.size)
    }

    // ─────────────────────────── 虚标 ───────────────────────────

    @Test
    fun losslessClaimWithMp3ContainerIsFlaggedAsMismatch() = runTest {
        val host = FakeHost(loaded("kg" to listOf("flac")), mapOf("kg:flac" to "https://cdn/fake.flac"))
        val kg = finished(
            runner(host, inspector = FakeInspector(mapOf("https://cdn/fake.flac" to LxL3.Ok(206, "audio/mpeg", "MP3"))))
                .verify("s1")
                .toList()
        ).platforms.single()
        assertEquals(LxPlatformVerdict.PLAYABLE, kg.verdict)
        assertTrue(kg.qualityMismatch)
        assertEquals(LxContainer.MP3, kg.actualContainer)
    }

    @Test
    fun mp3ClaimNeverFlagsMismatch() = runTest {
        val host = FakeHost(loaded("kg" to listOf("320k")), mapOf("kg:320k" to "https://cdn/a.mp3"))
        val kg = finished(runner(host).verify("s1").toList()).platforms.single()
        assertFalse(kg.qualityMismatch)
    }

    // ─────────────────────────── 预算 / 取消 ───────────────────────────

    @Test
    fun scriptBudgetTruncationIsReportedAsBudgetNotDead() = runTest {
        var now = 0L
        for (depth in listOf(LxProbeDepth.FULL, LxProbeDepth.MATRIX)) {
            val host = FakeHost(
                loaded("kg" to listOf("320k"), "wy" to listOf("320k")),
                mapOf("kg:320k" to "https://cdn/kg.mp3", "wy:320k" to "https://cdn/wy.mp3"),
                onGet = { now += 100_000 },
            )
            val report = finished(
                runner(host, budgets = LxBudgets(scriptMs = 60_000), clock = { now })
                    .verify("s1", depth)
                    .toList()
            )
            assertTrue("$depth 超预算必须置 truncated", report.truncated)
            val wy = report.platforms.first { it.source == "wy" }
            assertEquals(LxFailReason.BUDGET, wy.reason)
            assertEquals(LxPlatformVerdict.UNTESTED, wy.verdict)
        }
    }

    @Test
    fun probingBlocksPlaybackResolutionWhileVerifying() = runTest {
        val registry = LxSourceRegistry { 0L }
        var acquiredDuringProbe: Boolean? = null
        val host = FakeHost(
            loaded("kg" to listOf("320k")),
            mapOf("kg:320k" to "https://cdn/a.mp3"),
            onGet = { acquiredDuringProbe = registry.tryAcquire("s1", "kg") },
        )
        runner(host, registry = registry).verify("s1").toList()
        // 探测期间 tryAcquire 必须返回 false：否则播放会排在探测后面干等（用户按了播放不出声）
        assertEquals(false, acquiredDuringProbe)
        assertFalse("验证结束必须复位 probing", registry.isProbing("s1"))
    }

    @Test
    fun cancellationClearsProbingAndInterruptsPendingRequest() = runTest {
        val registry = LxSourceRegistry { 0L }
        val host = FakeHost(loaded("kg" to listOf("320k")), loadDelayMs = 60_000)
        val job = launch { runner(host, registry = registry).verify("s1").collect { } }
        runCurrent()
        assertTrue(registry.isProbing("s1"))
        job.cancel()
        job.join()
        assertFalse(registry.isProbing("s1"))
        assertEquals(1, host.cancelPendingCalls)
    }

    @Test
    fun registryIsNeverMutatedByVerification() = runTest {
        // ⛔ 纯观察者：验证失败不清直链缓存、不写 Degraded、不改 enabled
        val registry = LxSourceRegistry { 0L }
        val host = FakeHost(loaded("kg" to listOf("320k")))
        runner(host, registry = registry).verify("s1").toList()
        assertEquals(0, registry.currentStreak("s1", "kg"))
        assertTrue(registry.isHealthy("s1", "kg"))
        assertFalse(registry.isBroken("s1"))
    }

    @Test
    fun eventsCarryProgressForUi() = runTest {
        val host = FakeHost(
            loaded("kg" to listOf("320k"), "wy" to listOf("320k")),
            mapOf("kg:320k" to "https://cdn/kg.mp3", "wy:320k" to "https://cdn/wy.mp3"),
        )
        val events = runner(host).verify("s1").toList()
        val starts = events.filterIsInstance<LxProbeEvent.PlatformStart>()
        assertEquals(listOf(0 to 2, 1 to 2), starts.map { it.index to it.total })
        assertEquals(listOf("kg", "wy"), starts.map { it.source })
        val dones = events.filterIsInstance<LxProbeEvent.PlatformDone>()
        assertEquals(listOf("kg", "wy"), dones.map { it.probe.source })
        assertNotNull(finished(events))
    }

    @Test
    fun cancelledPlatformIsNotReportedAsDead() = runTest {
        // 取消是「用户主动停」，⛔ 不能把没测完的平台说成死链：健康状态必须完全没动
        val registry = LxSourceRegistry { 0L }
        val host = FakeHost(loaded("kg" to listOf("320k")), loadDelayMs = 60_000)
        val job = launch { runner(host, registry = registry).verify("s1").collect { } }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(0, registry.currentStreak("s1", "kg"))
        assertTrue(registry.isHealthy("s1", "kg"))
    }
}
