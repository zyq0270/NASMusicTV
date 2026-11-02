package com.nasmusic.tv.player

import android.app.BackgroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 门禁：**主界面启动播放服务必须走带降级的安全入口**。
 *
 * ## 它守住什么
 *
 * 2026-11-02 真机实测的崩溃（手机 22081212C）：
 * ```
 * java.lang.RuntimeException: Unable to start activity .../ui.MainActivity:
 *   android.app.BackgroundServiceStartNotAllowedException:
 *   Not allowed to start service Intent { cmp=com.nasmusic.tv/.player.PlaybackService }:
 *   app is in background ...
 *     at com.nasmusic.tv.ui.MainActivity.onCreate(...:564)
 * ```
 * ⇒ `MainActivity.onCreate` 里的 `startService(PlaybackService)` 在**进程处于后台态**时
 * 被系统拒绝 ⇒ 异常未捕获 ⇒ **整个 Activity 崩掉、App 闪退**。
 *
 * Android 8.0（API 26）起才有这个限制，触发路径**不止 adb**：
 * 通知栏动作 / 快捷方式 / 桌面小组件 / 从最近任务重开 / 系统后台复活进程后再拉起界面。
 *
 * ## 三条断言
 *
 * 1. **普通路径**：进程在前台 ⇒ `startService` 放行 ⇒ **不**降级；
 * 2. **被系统拒绝**（抛 `BackgroundServiceStartNotAllowedException`）⇒ 降级为
 *    `startForegroundService` ⇒ **不抛异常**（这就是防崩的那一步）；
 * 3. **降级也失败** ⇒ **仍然不抛异常**（宁可服务晚点起来，也不能崩）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class PlaybackServiceStartGuardTest {

    /** 占位服务类（⛔ 只用来构造 Intent，不会真的被启动 —— 启动动作被假 Context 拦下）。 */
    private class DummyService : android.app.Service() {
        override fun onBind(intent: Intent?): IBinder? = null
    }

    /** 假 Context：可让 `startService` / `startForegroundService` 抛异常并记录调用次数。 */
    private class FakeContext(base: Context) : ContextWrapper(base) {
        var startServiceCalls = 0
        var startForegroundServiceCalls = 0
        var throwOnStartService: Throwable? = null
        var throwOnStartForegroundService: Throwable? = null

        override fun startService(service: Intent): ComponentName? {
            startServiceCalls++
            throwOnStartService?.let { throw it }
            return null
        }

        override fun startForegroundService(service: Intent): ComponentName? {
            startForegroundServiceCalls++
            throwOnStartForegroundService?.let { throw it }
            return null
        }
    }

    private fun newFake(): FakeContext =
        FakeContext(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun 普通路径成功时不降级() {
        val ctx = newFake()
        startPlaybackServiceSafely(ctx, Intent(ctx, DummyService::class.java))
        assertEquals("普通路径应只调 startService 一次", 1, ctx.startServiceCalls)
        assertEquals("不应降级为前台服务", 0, ctx.startForegroundServiceCalls)
    }

    @Test
    fun 后台启动被拒时降级为前台服务且不抛异常() {
        val ctx = newFake()
        // ⛔ `BackgroundServiceStartNotAllowedException` 是 API 26 才有的类
        //    ⇒ 用 SDK 守卫包住（同时让 lint 的 NewApi 满意）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.throwOnStartService =
                BackgroundServiceStartNotAllowedException("fake background start denied")
        } else {
            ctx.throwOnStartService = IllegalStateException("fake background start denied")
        }

        // ⭐ 这一行就是被门禁守住的行为：**不能抛出去**
        startPlaybackServiceSafely(ctx, Intent(ctx, DummyService::class.java))

        assertEquals("普通路径应先试过一次", 1, ctx.startServiceCalls)
        assertEquals("被拒后必须降级为前台服务", 1, ctx.startForegroundServiceCalls)
    }

    @Test
    fun 连前台服务都失败时也不抛异常() {
        val ctx = newFake()
        ctx.throwOnStartService = IllegalStateException("denied")
        ctx.throwOnStartForegroundService = IllegalStateException("denied too")

        // ⭐ 宁可服务晚点起来，也不能崩
        startPlaybackServiceSafely(ctx, Intent(ctx, DummyService::class.java))

        assertEquals("应尝试过普通路径", 1, ctx.startServiceCalls)
        assertEquals("应尝试过前台服务", 1, ctx.startForegroundServiceCalls)
        assertTrue("两种路径都试过了", true)
    }
}
