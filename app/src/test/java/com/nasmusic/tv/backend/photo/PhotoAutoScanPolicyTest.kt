package com.nasmusic.tv.backend.photo

import com.nasmusic.tv.visualizer.photo.SourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    // ────────────────────── 结构门禁：读**持久化**值，不是内存镜像 ──────────────────────

    /**
     * **自动扫描必须读持久化的开关值**，不能读 `latestSettings` 内存镜像。
     *
     * ## 踩坑实录（2026-11-12 电视实测）
     *
     * 连接成功**可能早于 DataStore 的首次发射** ⇒ `latestSettings` 此刻还是默认的
     * `AppSettings()`（Jellyfin 开关默认 `false`）⇒ 判据读到 false
     * ⇒ **自动扫描被静默跳过**（设置页既没有照片数、也没有状态行 ⇒ 用户完全看不出发生了什么）。
     *
     * ⇒ 这条门禁直接扫源码：函数体里**不许**出现 `latestSettings`。
     */
    @Test
    fun `onNasConnected 必须读持久化的开关值而不是内存镜像`() {
        val file = File(
            SourceScan.mainSourceRoot(),
            "com/nasmusic/tv/ui/viewmodel/VisualizerViewModel.kt",
        )
        assertTrue("找不到 VisualizerViewModel.kt：${file.absolutePath}", file.isFile)
        val code = SourceScan.stripComments(file.readText())

        val start = code.indexOf("suspend fun onNasConnected(")
        assertTrue("找不到 onNasConnected()", start >= 0)
        val end = code.indexOf("\n    }", start).let { if (it < 0) code.length else it }
        val body = code.substring(start, end)

        assertTrue(
            "onNasConnected() 必须读**持久化**的设置（prefs.appSettings.first()）—— " +
                "读 latestSettings 内存镜像会在「连接早于 DataStore 首次发射」时读到默认 false ⇒ 自动扫描被静默跳过",
            body.contains("prefs.appSettings.first()"),
        )
        assertTrue(
            "onNasConnected() 里不许再读 latestSettings（内存镜像可能还没更新）",
            !body.contains("latestSettings"),
        )
    }
}
