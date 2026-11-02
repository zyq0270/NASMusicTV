package com.nasmusic.tv.player

import android.content.Context
import android.content.Intent
import android.os.Build
import com.nasmusic.tv.util.AppLog

/**
 * 启动 [PlaybackService] 的**安全**入口（⛔ 永不因「后台启动限制」抛异常）。
 *
 * @param intent 要启动的服务的 Intent（由调用方构造 —— ⛔ 本函数**故意不引用**
 *   `PlaybackService` 类型，避免把 Media3 的 `@UnstableApi` opt-in 标记带进来）。
 *
 * ## 为什么要它
 *
 * Android 8.0（API 26）起有「**后台启动 Service 限制**」：进程处于后台态时调用
 * [Context.startService] 会抛 [android.app.BackgroundServiceStartNotAllowedException]
 * （它是 [IllegalStateException] 的子类）⇒ **若在 `Activity.onCreate` 里不捕获，
 * 异常会一路冒泡 ⇒ 整个 Activity 崩掉 ⇒ App 直接闪退**。
 *
 * 触发路径**不止 adb**：通知栏动作 / 快捷方式 / 桌面小组件 / 从最近任务重开 /
 * MIUI 等系统「后台复活」进程后再拉起界面 ⇒ 都是常见路径。
 *
 * ## 三级降级
 *
 * 1. **普通路径**：[Context.startService]（前台态 ⇒ 正常放行）；
 * 2. **被系统拒绝** ⇒ 降级为**前台服务** [Context.startForegroundService]
 *    （`PlaybackService` 已在 manifest 声明 `foregroundServiceType="mediaPlayback"`，
 *    且权限 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 齐备；
 *    服务 `onCreate` 里的 `ensureMediaSessionActive()` 会**立刻** `startForeground`
 *    ⇒ 满足 API 26+ 「5 秒内必须前台化」的要求）；
 * 3. **再失败** ⇒ 只记日志，**不崩溃**（播放服务晚点由前台交互再拉起）。
 *
 * ⛔ 注意：`stopService` **没有**这个限制 ⇒ 停止路径无需改动。
 */
internal fun startPlaybackServiceSafely(context: Context, intent: Intent) {
    try {
        context.startService(intent)
        return
    } catch (e: IllegalStateException) {
        // ⭐ `BackgroundServiceStartNotAllowedException`（API 26+）是 `IllegalStateException` 的子类
        AppLog.e("MainActivity", "startService 被系统拒绝（后台启动限制）⇒ 降级为前台服务", e)
    } catch (e: Throwable) {
        AppLog.e("MainActivity", "startService 失败 ⇒ 跳过启动播放服务（不崩溃）", e)
        return
    }

    // ② 降级：前台服务
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    } catch (e: Throwable) {
        // ③ 再失败也**不崩**：播放服务由后续前台交互再拉起
        AppLog.e("MainActivity", "前台服务也启动失败 ⇒ 跳过启动播放服务（不崩溃）", e)
    }
}
