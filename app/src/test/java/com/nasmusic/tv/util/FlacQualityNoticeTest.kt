package com.nasmusic.tv.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * 「本机放不了 FLAC ⇒ 音质档位自动降级」的**一次性**提示门禁（2026-11-11）
 *
 * ## 为什么要有这道门禁
 *
 * 用户 2026-11-11 电视实测反馈：「**没有看到 FLAC 的跳过提示**」。
 * ⇒ FLAC 在这台机器上有**两条**失败路径：
 *
 * | 路径 | 用户看到什么 |
 * |---|---|
 * | ① 直链是 FLAC（NAS 直连） | 命中解封装熔断 ⇒ 整曲跳过 ⇒ `PlayerManager` 弹 Toast（说清原因 + 怎么办） |
 * | ② 档位选了「无损」（网络音乐源） | 档位在**请求直链前**就被降级 ⇒ **不报错、不跳过、也没有任何提示** |
 *
 * ⇒ 路径 ② 原本是**完全静默**的：用户只会觉得「音质怎么变差了」而不知道原因。
 * ⇒ 本类补上这条提示，并保证**只弹一次**（这个 provider 每次解析直链都会被调）。
 *
 * ⚠️ **文案从资源取** ⇒ 单测环境没有 app 资源（`isIncludeAndroidResources` 未开）
 * ⇒ 单测通过 [FlacQualityNotice.showIfDowngraded] 的 `text` 参数注入字面量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlacQualityNoticeTest {

    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        FlacQualityNotice.resetForTest()
        ShadowToast.reset()
    }

    @Test
    fun `档位被降级时弹提示`() {
        FlacQualityNotice.showIfDowngraded(ctx, requestedTier = 999, clampedTier = 320, text = "已降级")
        assertEquals(1, FlacQualityNotice.showCount)
        assertEquals("已降级", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `没有降级时不弹`() {
        // 用户没选无损档（或本机能放）⇒ 一句话都不该说
        FlacQualityNotice.showIfDowngraded(ctx, requestedTier = 320, clampedTier = 320, text = "已降级")
        assertEquals(0, FlacQualityNotice.showCount)
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `只弹一次不刷屏`() {
        // provider 每次解析直链都会调 ⇒ 不做一次性保护就会刷屏
        FlacQualityNotice.showIfDowngraded(ctx, requestedTier = 999, clampedTier = 320, text = "已降级")
        FlacQualityNotice.showIfDowngraded(ctx, requestedTier = 999, clampedTier = 320, text = "已降级")
        FlacQualityNotice.showIfDowngraded(ctx, requestedTier = 999, clampedTier = 320, text = "已降级")
        assertEquals(1, FlacQualityNotice.showCount)
    }

    @Test
    fun `降级后第一次提示先于后续的静默`() {
        // 先降级（弹一次）⇒ 之后即使再降级也不重复弹
        FlacQualityNotice.showIfDowngraded(ctx, requestedTier = 999, clampedTier = 320, text = "第一次")
        FlacQualityNotice.showIfDowngraded(ctx, requestedTier = 999, clampedTier = 320, text = "第二次")
        assertEquals("第一次", ShadowToast.getTextOfLatestToast())
        assertEquals(1, FlacQualityNotice.showCount)
    }
}
