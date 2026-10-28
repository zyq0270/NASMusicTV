package com.nasmusic.tv.backend.network.lx

import com.nasmusic.tv.backend.network.Playability
import com.nasmusic.tv.backend.network.PlayabilityProbe
import com.nasmusic.tv.backend.network.QualityTiers
import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T11：洛雪增强层（方案 §5.4）。
 *
 * 覆盖红线：任何异常 ⇒ null（不外抛）、非空 URL ≠ 可播（必须验活）、
 * 空交集直接放弃、降级只在 DEAD 时发生。
 *
 * Robolectric：`LxPlayUrlEnhancer` 经 [com.nasmusic.tv.util.AppLog] 走 `android.util.Log`。
 */
@RunWith(RobolectricTestRunner::class)
class LxPlayUrlEnhancerTest {

    // ─────────────────────────── 夹具 ───────────────────────────

    private class FakeHost(
        override val info: LxScriptInfo?,
        private val answers: Map<String, String?> = emptyMap(),
        private val throwOnInfo: Boolean = false,
    ) : LxHost {
        val calls = mutableListOf<String>()
        var closed = false

        override suspend fun load(): LxScriptInfo =
            if (throwOnInfo) throw IllegalStateException("夹具：加载即失败") else info ?: LxScriptInfo.Failed(LxFailReason.L1_SCRIPT_ERROR, null)

        override suspend fun getMusicUrl(source: String, musicInfo: LxMusicInfo, quality: String): String? {
            calls += quality
            return answers[quality]
        }

        override fun cancelPending() = Unit
        override fun close() { closed = true }
    }

    private fun loaded(vararg pairs: Pair<String, List<String>>): LxScriptInfo.Loaded =
        LxScriptInfo.Loaded(
            sources = pairs.associate { (source, qualities) ->
                source to LxDeclaredSource(name = source, actions = listOf("musicUrl"), qualitys = qualities)
            },
            version = "1.0.0",
            name = "夹具",
        )

    private fun probe(result: Playability) = object : PlayabilityProbe {
        override fun probe(url: String) = result
    }

    private fun song(platform: String? = "netease") = Song(
        id = "ntwk_meting_1",
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        isNetworkSong = true,
        networkSource = "meting",
        networkId = "186016",
        networkPlatform = platform,
        durationMs = 269_000,
    )

    private fun enhancer(
        registry: LxSourceRegistry = LxSourceRegistry { 0L },
        slots: List<LxScriptSlot>,
        playability: Playability = Playability.PLAYABLE,
        fallback: String? = "netease",
    ) = LxPlayUrlEnhancer(
        registry = registry,
        slots = { slots },
        probe = probe(playability),
        fallbackPlatform = { fallback },
    )

    // ─────────────────────────── 用例 ───────────────────────────

    @Test
    fun happyPathReturnsUrlAndMapsQuality() = runTest {
        val host = FakeHost(loaded("wy" to listOf("320k", "128k")), mapOf("320k" to "https://cdn/a.mp3"))
        val result = enhancer(slots = listOf(LxScriptSlot("s1", host))).tryResolve(song(), QualityTiers.HIGH)
        assertEquals("https://cdn/a.mp3", result?.url)
        assertEquals(QualityTiers.HIGH, result?.actualQuality)
    }

    @Test
    fun losslessHitReportsLosslessNotDowngraded() = runTest {
        val host = FakeHost(loaded("wy" to listOf("flac", "320k")), mapOf("flac" to "https://cdn/a.flac"))
        val result = enhancer(slots = listOf(LxScriptSlot("s1", host))).tryResolve(song(), QualityTiers.LOSSLESS)
        assertEquals(QualityTiers.LOSSLESS, result?.actualQuality)
        assertEquals(false, result?.isDowngradedFrom(QualityTiers.LOSSLESS))
    }

    @Test
    fun autoUsesDeclaredOrder() = runTest {
        val host = FakeHost(
            loaded("wy" to listOf("flac", "320k")),
            mapOf("flac" to "https://cdn/a.flac", "320k" to "https://cdn/a.mp3"),
        )
        val result = enhancer(slots = listOf(LxScriptSlot("s1", host))).tryResolve(song(), QualityTiers.AUTO)
        assertEquals("https://cdn/a.flac", result?.url)
        assertEquals(listOf("flac"), host.calls)
    }

    @Test
    fun deadProbeFallsThroughToNextQuality() = runTest {
        val host = FakeHost(
            loaded("wy" to listOf("320k", "128k")),
            mapOf("320k" to "https://cdn/dead.mp3", "128k" to "https://cdn/ok.mp3"),
        )
        val playability = object : PlayabilityProbe {
            override fun probe(url: String) =
                if (url.contains("dead")) Playability.DEAD else Playability.PLAYABLE
        }
        val result = LxPlayUrlEnhancer(
            registry = LxSourceRegistry { 0L },
            slots = { listOf(LxScriptSlot("s1", host)) },
            probe = playability,
            fallbackPlatform = { "netease" },
        ).tryResolve(song(), QualityTiers.HIGH)
        assertEquals("https://cdn/ok.mp3", result?.url)
        // DEAD 的 320k 换成 128k：actualQuality 与请求不同，降级信号必须为真
        assertEquals(QualityTiers.STANDARD, result?.actualQuality)
        assertEquals(true, result?.isDowngradedFrom(QualityTiers.HIGH))
    }

    @Test
    fun allQualitiesDeadYieldsNullAndCountsAFailure() = runTest {
        val registry = LxSourceRegistry { 0L }
        val host = FakeHost(
            loaded("wy" to listOf("320k", "128k")),
            mapOf("320k" to "https://cdn/a.mp3", "128k" to "https://cdn/b.mp3"),
        )
        val result = enhancer(registry, listOf(LxScriptSlot("s1", host)), Playability.DEAD)
            .tryResolve(song(), QualityTiers.HIGH)
        assertNull(result)
        assertEquals(1, registry.currentStreak("s1", "wy"))
    }

    @Test
    fun unknownPlayabilityIsNotTreatedAsDead() = runTest {
        // 保守判据：网络抖动（UNKNOWN）绝不能把好歌判死
        val host = FakeHost(loaded("wy" to listOf("320k")), mapOf("320k" to "https://cdn/a.mp3"))
        val result = enhancer(slots = listOf(LxScriptSlot("s1", host)), playability = Playability.UNKNOWN)
            .tryResolve(song(), QualityTiers.HIGH)
        assertEquals("https://cdn/a.mp3", result?.url)
    }

    @Test
    fun emptyQualityIntersectionGivesUpWithoutCallingScript() = runTest {
        // 源只声明 128k，用户选无损 ⇒ 放弃洛雪，不静默降级
        val host = FakeHost(loaded("wy" to listOf("128k")), mapOf("128k" to "https://cdn/a.mp3"))
        val result = enhancer(slots = listOf(LxScriptSlot("s1", host))).tryResolve(song(), QualityTiers.LOSSLESS)
        assertNull(result)
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test
    fun sourceNotDeclaredIsSkipped() = runTest {
        // 脚本只支持酷狗，歌是网易 ⇒ 直接跳过，不调脚本
        val host = FakeHost(loaded("kg" to listOf("320k")), mapOf("320k" to "https://cdn/a.mp3"))
        val result = enhancer(slots = listOf(LxScriptSlot("s1", host))).tryResolve(song(), QualityTiers.HIGH)
        assertNull(result)
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test
    fun throwingHostNeverEscapes() = runTest {
        val host = FakeHost(null, throwOnInfo = true)
        val result = enhancer(slots = listOf(LxScriptSlot("s1", host))).tryResolve(song(), QualityTiers.HIGH)
        assertNull("增强层异常必须吞掉并回退 Meting", result)
    }

    @Test
    fun brokenScriptIsNotAsked() = runTest {
        val registry = LxSourceRegistry { 0L }
        registry.markBroken("s1")
        val host = FakeHost(loaded("wy" to listOf("320k")), mapOf("320k" to "https://cdn/a.mp3"))
        val result = enhancer(registry, listOf(LxScriptSlot("s1", host))).tryResolve(song(), QualityTiers.HIGH)
        assertNull(result)
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test
    fun degradedSourceIsNotAskedButOtherScriptStillIs() = runTest {
        val registry = LxSourceRegistry { 0L }
        repeat(3) { registry.markFailure("s1", "wy") }
        val first = FakeHost(loaded("wy" to listOf("320k")), mapOf("320k" to "https://cdn/1.mp3"))
        val second = FakeHost(loaded("wy" to listOf("320k")), mapOf("320k" to "https://cdn/2.mp3"))
        val result = enhancer(registry, listOf(LxScriptSlot("s1", first), LxScriptSlot("s2", second)))
            .tryResolve(song(), QualityTiers.HIGH)
        assertEquals("https://cdn/2.mp3", result?.url)
        assertEquals(emptyList<String>(), first.calls)
    }

    @Test
    fun slotsAreTriedInUserOrder() = runTest {
        val first = FakeHost(loaded("wy" to listOf("320k")), mapOf("320k" to "https://cdn/1.mp3"))
        val second = FakeHost(loaded("wy" to listOf("320k")), mapOf("320k" to "https://cdn/2.mp3"))
        val result = enhancer(slots = listOf(LxScriptSlot("s1", first), LxScriptSlot("s2", second)))
            .tryResolve(song(), QualityTiers.HIGH)
        assertEquals("https://cdn/1.mp3", result?.url)
        assertEquals(emptyList<String>(), second.calls)
    }

    @Test
    fun fallbackPlatformRescuesLegacySongs() = runTest {
        // 历史数据没有 networkPlatform，用当前全局平台兜底
        val host = FakeHost(loaded("wy" to listOf("320k")), mapOf("320k" to "https://cdn/a.mp3"))
        val result = enhancer(slots = listOf(LxScriptSlot("s1", host)), fallback = "netease")
            .tryResolve(song(platform = null), QualityTiers.HIGH)
        assertEquals("https://cdn/a.mp3", result?.url)
    }

    @Test
    fun songWithoutNetworkIdIsNotResolved() = runTest {
        val host = FakeHost(loaded("wy" to listOf("320k")), mapOf("320k" to "https://cdn/a.mp3"))
        val bare = song().copy(networkId = null)
        val result = enhancer(slots = listOf(LxScriptSlot("s1", host))).tryResolve(bare, QualityTiers.HIGH)
        assertNull(result)
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test
    fun unloadedHostIsSkipped() = runTest {
        // info 还没出来（load 未完成 / 失败）：不能当成「声明为空」去调脚本
        val host = FakeHost(null)
        val result = enhancer(slots = listOf(LxScriptSlot("s1", host))).tryResolve(song(), QualityTiers.HIGH)
        assertNull(result)
        assertEquals(emptyList<String>(), host.calls)
    }
}
