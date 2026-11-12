package com.nasmusic.tv.backend.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **门禁：来源缓存的可用性判定不得把「重新扫描」锁死**（2026-11-12 电视实测修复）
 *
 * ## 电视实测现象
 *
 * > 「NAS已连接的状态下，照片墙中 重新扫描，无法找到NAS中的照片」
 *
 * ⇒ 根因链（详见 [PhotoScanVerdict] 的 KDoc）：
 * 「上次扫到 0 张」被缓存成 `NO_PHOTO_LIBRARY` ⇒ 聚合器**先问 status、再决定要不要扫**
 * ⇒ 之后每轮扫描都被挡掉 ⇒ **「重新扫描」永远扫不出东西**；
 * 而且**一次网络抖动就足以触发**（查询失败时 `total` 停在 -1 被当成 0）。
 *
 * ⚠️ 本测试**不需要 Robolectric**：判定器是**纯逻辑**（不碰网络、不碰 `android.*`）。
 */
class PhotoScanVerdictTest {

    @Test
    fun `未扫描过时状态是可用的`() {
        val v = PhotoScanVerdict()
        assertEquals(
            "没扫过 ≠ 没有照片 ⇒ 状态必须是可用（否则第一次扫描就被自己挡住）",
            PhotoSourceStatus.OK,
            v.statusWhenConnected(),
        )
    }

    @Test
    fun `扫到零张才判为无照片库`() {
        val v = PhotoScanVerdict()
        v.onScanSucceeded(0)
        assertEquals(
            "服务器确实说 0 张 ⇒ 状态应是「无照片库」（设置页据此提示）",
            PhotoSourceStatus.NO_PHOTO_LIBRARY,
            v.statusWhenConnected(),
        )
    }

    @Test
    fun `查询失败不得判成零张`() {
        // ⛔ 这是最关键的一条：一次网络抖动曾把来源**永久判死**
        val v = PhotoScanVerdict()
        v.onScanFailed()
        assertEquals(
            "查询失败 ≠ 没有照片 ⇒ 状态必须仍是可用（否则来源被永久判死）",
            PhotoSourceStatus.OK,
            v.statusWhenConnected(),
        )
        assertEquals(
            "失败后缓存值应是「未知」",
            PhotoScanVerdict.UNKNOWN_TOTAL,
            v.lastKnownTotal,
        )
    }

    @Test
    fun `作废后状态回到可用`() {
        // 场景：上一轮扫到 0 张 ⇒ 用户在 NAS 上加了照片 ⇒ 点「重新扫描」
        val v = PhotoScanVerdict()
        v.onScanSucceeded(0)
        assertEquals(
            "前置条件：作废前应判为无照片库",
            PhotoSourceStatus.NO_PHOTO_LIBRARY,
            v.statusWhenConnected(),
        )
        v.invalidate()
        assertEquals(
            "作废后必须回到可用 ⇒ 扫描器才真的再去问服务器",
            PhotoSourceStatus.OK,
            v.statusWhenConnected(),
        )
    }

    @Test
    fun `作废也能救回被网络抖动判死的来源`() {
        val v = PhotoScanVerdict()
        v.onScanSucceeded(0)
        v.onScanFailed()      // 抖动
        v.invalidate()
        assertEquals(
            "作废后必须回到可用",
            PhotoSourceStatus.OK,
            v.statusWhenConnected(),
        )
    }

    @Test
    fun `扫描成功后的总数被记住`() {
        val v = PhotoScanVerdict()
        v.onScanSucceeded(123)
        assertEquals(123, v.lastKnownTotal)
        assertEquals(
            "扫到非零张 ⇒ 状态是可用",
            PhotoSourceStatus.OK,
            v.statusWhenConnected(),
        )
    }

    @Test
    fun `负数总数按零处理`() {
        // 防御：服务器若返回负数（不该发生），不能变成「有照片」的假象
        val v = PhotoScanVerdict()
        v.onScanSucceeded(-5)
        assertEquals(0, v.lastKnownTotal)
        assertTrue(
            "负数应按 0 张处理（⇒ 显示为无照片库，而不是谎报有照片）",
            v.statusWhenConnected() == PhotoSourceStatus.NO_PHOTO_LIBRARY,
        )
    }
}
