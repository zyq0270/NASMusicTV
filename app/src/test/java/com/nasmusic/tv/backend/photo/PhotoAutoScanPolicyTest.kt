package com.nasmusic.tv.backend.photo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **门禁：NAS 连上后自动扫照片墙的判定**（2026-11-12 用户裁定）
 *
 * > 「应该增加自动扫描的功能，无需手动，也可以强制手动重新扫描。
 * > 只要连接了 NAS Jellyfin 服务器，并且照片墙中 Jellyfin 的开关打开的情况下，
 * > 连接 NAS 成功后，应该自动扫描」
 *
 * ⚠️ 纯 JVM（判据是纯函数，不碰网络、不碰 `android.*`）。
 */
class PhotoAutoScanPolicyTest {

    @Test
    fun `Jellyfin 且开关打开时自动扫描`() {
        assertTrue(
            "连的是 Jellyfin + 照片墙的 Jellyfin 开关开着 ⇒ 必须自动扫",
            PhotoAutoScanPolicy.shouldAutoScan(
                backendType = PhotoAutoScanPolicy.JELLYFIN_BACKEND_TYPE,
                jellyfinSourceEnabled = true,
            ),
        )
    }

    @Test
    fun `开关关着时不自动扫描`() {
        assertFalse(
            "用户关掉了这个来源 ⇒ 不扫（也不能产生任何网络请求）",
            PhotoAutoScanPolicy.shouldAutoScan(
                backendType = PhotoAutoScanPolicy.JELLYFIN_BACKEND_TYPE,
                jellyfinSourceEnabled = false,
            ),
        )
    }

    @Test
    fun `非 Jellyfin 后端不自动扫描`() {
        // 照片墙的 NAS 来源**只支持 Jellyfin**（其它后端 buildAuthenticatedUrl 返回 null）
        for (type in listOf("navidrome", "subsonic", "feiniu", "daoliyu")) {
            assertFalse(
                "后端 $type 没有照片通道 ⇒ 不该扫",
                PhotoAutoScanPolicy.shouldAutoScan(
                    backendType = type,
                    jellyfinSourceEnabled = true,
                ),
            )
        }
    }

    @Test
    fun `未连接时不自动扫描`() {
        assertFalse(
            "没连上（backendType = null）⇒ 不扫",
            PhotoAutoScanPolicy.shouldAutoScan(
                backendType = null,
                jellyfinSourceEnabled = true,
            ),
        )
    }
}
