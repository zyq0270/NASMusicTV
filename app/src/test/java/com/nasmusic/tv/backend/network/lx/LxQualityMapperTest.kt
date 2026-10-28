package com.nasmusic.tv.backend.network.lx

import com.nasmusic.tv.backend.network.QualityTiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T9：音质档位映射（方案 §6.1 / §6.2 / §6.3）。
 *
 * 三条不能破的语义：AUTO ≠ 320k、空交集 ⇒ 放弃、`master/hires/atmos` 反向必须是 LOSSLESS。
 */
class LxQualityMapperTest {

    @Test
    fun autoKeepsScriptDeclaredOrder() {
        // AUTO 的语义是「交给端点」，洛雪侧对应「按脚本自己声明的顺序」
        val declared = listOf("320k", "flac", "128k")
        assertEquals(declared, LxQualityMapper.planFor(QualityTiers.AUTO, declared))
    }

    @Test
    fun autoWithNothingDeclaredYieldsEmpty() {
        assertTrue(LxQualityMapper.planFor(QualityTiers.AUTO, emptyList()).isEmpty())
    }

    @Test
    fun standardOnlyAsksFor128k() {
        assertEquals(listOf("128k"), LxQualityMapper.planFor(QualityTiers.STANDARD, listOf("320k", "128k")))
        // 脚本没声明 128k ⇒ 不硬喂 320k（那是静默升档，同样不合用户所选）
        assertTrue(LxQualityMapper.planFor(QualityTiers.STANDARD, listOf("320k", "flac")).isEmpty())
    }

    @Test
    fun goodAndHighTierAreTheSameQueue() {
        val declared = listOf("128k", "320k", "flac")
        val expected = listOf("320k", "128k")
        assertEquals(expected, LxQualityMapper.planFor(QualityTiers.GOOD, declared))
        assertEquals(expected, LxQualityMapper.planFor(QualityTiers.HIGH, declared))
    }

    @Test
    fun losslessPutsHighEndQualitiesFirst() {
        val declared = listOf("128k", "320k", "flac", "flac24bit", "master", "hires", "atmos", "atmos_plus")
        assertEquals(
            listOf("master", "hires", "atmos", "atmos_plus", "flac24bit", "flac", "320k"),
            LxQualityMapper.planFor(QualityTiers.LOSSLESS, declared),
        )
    }

    @Test
    fun losslessFallsBackTo320kWhenSourceHasNoLossless() {
        // 源只到 320k 时，无损请求会降级到 320k —— 这是**候选队**里明确列出的一档，
        // actualQuality=320 会让播放侧弹降级提示，不是静默行为
        assertEquals(listOf("320k"), LxQualityMapper.planFor(QualityTiers.LOSSLESS, listOf("128k", "320k")))
    }

    @Test
    fun emptyIntersectionMeansGiveUp() {
        // 源只声明 128k，用户选了无损 ⇒ 放弃洛雪（回退 Meting），绝不静默降级
        assertTrue(LxQualityMapper.planFor(QualityTiers.LOSSLESS, listOf("128k")).isEmpty())
    }

    @Test
    fun candidateOrderBeatsDeclaredOrder() {
        // 求交后保持**候选**优先序，不是脚本声明序
        assertEquals(listOf("320k", "128k"), LxQualityMapper.planFor(QualityTiers.HIGH, listOf("128k", "320k")))
    }

    @Test
    fun unknownTierFallsBackToHighQueue() {
        // 持久化值被改坏也不该让播放崩
        assertEquals(listOf("320k", "128k"), LxQualityMapper.candidatesFor(-1))
        assertEquals(listOf("320k", "128k"), LxQualityMapper.candidatesFor(256))
    }

    @Test
    fun reverseMappingNeverDegradesHighEndToAuto() {
        assertEquals(QualityTiers.STANDARD, LxQualityMapper.fromLx("128k"))
        assertEquals(QualityTiers.HIGH, LxQualityMapper.fromLx("320k"))
        assertEquals(QualityTiers.LOSSLESS, LxQualityMapper.fromLx("flac"))
        assertEquals(QualityTiers.LOSSLESS, LxQualityMapper.fromLx("flac24bit"))
        // 正向把 high-end 放在无损候队首 ⇒ 反向落到 AUTO 会让降级提示失效
        for (high in listOf("master", "hires", "atmos", "atmos_plus")) {
            assertEquals(high, QualityTiers.LOSSLESS, LxQualityMapper.fromLx(high))
        }
    }

    @Test
    fun unknownLxQualityMapsToAuto() {
        assertEquals(QualityTiers.AUTO, LxQualityMapper.fromLx("192k"))
        assertEquals(QualityTiers.AUTO, LxQualityMapper.fromLx(""))
    }

    @Test
    fun everyForwardCandidateMapsBackToARealTier() {
        // 正向候选里的每一档都必须能反向映射成非 AUTO 的档位，否则降级提示永远不触发
        val candidates = listOf(QualityTiers.STANDARD, QualityTiers.GOOD, QualityTiers.HIGH, QualityTiers.LOSSLESS)
            .flatMap { LxQualityMapper.candidatesFor(it) }
            .distinct()
        for (quality in candidates) {
            val tier = LxQualityMapper.fromLx(quality)
            assertTrue("$quality 反向映射成了 AUTO", tier != QualityTiers.AUTO)
        }
    }
}
