package com.nasmusic.tv.util

import com.nasmusic.tv.backend.network.QualityTiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **门禁**：设备 FLAC 解码能力判定 + 无损档降级（2026-11-11 电视崩溃修正）
 *
 * ## 这道门禁防的是什么
 *
 * 2026-11-11 目标电视实测：播 FLAC ⇒ 厂商 OMX 一 configure 就触发框架
 * `ACodec.cpp:3858` 的 `CHECK_EQ` 断言 ⇒ **整进程 SIGABRT**（15 分钟内 8 次）。
 *
 * ⇒ 两条防线：
 * 1. **解封装层熔断 FLAC**（`player/FlacGuardExtractors.kt`）⇒ 解码器不会被 configure；
 * 2. **无损档降级**（[QualityTiers.clampForDevice]）⇒ 这台机器根本不去要 FLAC 直链。
 *
 * ## 判定口径必须「宁可误判成不安全」
 *
 * 误判成不安全 ⇒ 只是 FLAC 播不了（功能受限）；
 * 误判成安全 ⇒ **整进程崩溃**。⇒ 取保守值。
 */
class DeviceAudioCapsTest {

    // ────────────────────── ① 判定逻辑（纯函数）──────────────────────

    /**
     * **负向自证**：这台电视的实际情况必须判成「不安全」。
     *
     * 取证：`OMX.RTK.audio.decoder`（Realtek 通用音频解码器）+ API 22 ⇒ 崩。
     */
    @Test
    fun `目标电视的解码器组合必须判为不安全`() {
        assertTrue(
            "Realtek 通用解码器 + API 22 必须判为不安全（否则会重现整进程崩溃）",
            DeviceAudioCaps.detectFlacUnsafe(listOf("OMX.RTK.audio.decoder"), 22),
        )
    }

    /** 框架版本超出 `CHECK_EQ` 存在区间 ⇒ 不判不安全（不误伤新设备） */
    @Test
    fun `新框架版本不判不安全`() {
        assertFalse(
            "API 30 不该判不安全（框架已无那个断言）",
            DeviceAudioCaps.detectFlacUnsafe(listOf("OMX.RTK.audio.decoder"), 30),
        )
    }

    /** 专用 FLAC 解码器 ⇒ 不判不安全（它通常能正确回那个参数） */
    @Test
    fun `专用 FLAC 解码器不判不安全`() {
        assertFalse(
            "专用 FLAC 解码器不该被屏蔽",
            DeviceAudioCaps.detectFlacUnsafe(listOf("OMX.google.flac.decoder"), 22),
        )
    }

    /** 压根没有 FLAC 解码器 ⇒ 不是「会崩」，只是「没有」⇒ 不判不安全 */
    @Test
    fun `没有 FLAC 解码器时不判不安全`() {
        assertFalse(
            "没有 FLAC 解码器时不存在 configure 崩溃风险",
            DeviceAudioCaps.detectFlacUnsafe(emptyList(), 22),
        )
    }

    // ────────────────────── ② 解码器名判定 ──────────────────────

    @Test
    fun `通用厂商解码器判定`() {
        assertTrue("OMX.RTK.audio.decoder 应判为通用解码器", DeviceAudioCaps.isGenericVendorDecoder("OMX.RTK.audio.decoder"))
        assertTrue("大小写不敏感", DeviceAudioCaps.isGenericVendorDecoder("omx.rtk.audio.decoder"))
        assertFalse("专用 FLAC 解码器不算通用", DeviceAudioCaps.isGenericVendorDecoder("OMX.google.flac.decoder"))
        assertFalse("名字里没有 flac 的专用解码器也不算通用", DeviceAudioCaps.isGenericVendorDecoder("OMX.google.aac.decoder"))
    }

    // ────────────────────── ③ 无损档降级 ──────────────────────

    @Test
    fun `不安全设备上无损档降到 320`() {
        assertEquals(
            "FLAC 不可用时无损档（999）必须退到 320",
            QualityTiers.HIGH,
            QualityTiers.clampForDevice(QualityTiers.LOSSLESS, flacUnsafe = true),
        )
    }

    @Test
    fun `安全设备上无损档保持不变`() {
        assertEquals(
            "FLAC 可用时无损档不该被动",
            QualityTiers.LOSSLESS,
            QualityTiers.clampForDevice(QualityTiers.LOSSLESS, flacUnsafe = false),
        )
    }

    @Test
    fun `其它档位一律不动`() {
        for (tier in listOf(QualityTiers.AUTO, QualityTiers.STANDARD, QualityTiers.GOOD, QualityTiers.HIGH)) {
            assertEquals(
                "档位 $tier 不该被降级",
                tier,
                QualityTiers.clampForDevice(tier, flacUnsafe = true),
            )
        }
    }
}
