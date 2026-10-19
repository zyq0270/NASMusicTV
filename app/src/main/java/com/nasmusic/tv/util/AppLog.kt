package com.nasmusic.tv.util

import com.nasmusic.tv.BuildConfig

/**
 * 应用日志工具
 *
 * 仅在 Debug 构建中输出日志到 logcat（Error 级始终输出）。
 *
 * v2.40.0 起 **警告与错误同时落盘**（[LogStore]）：
 * 电视上没法连 adb 看现场，用户报「刚才闪退了」时日志早就没了 ——
 * 落盘后设置页「日志」tab 可反复回看（重启不丢）。
 * ⚠️ `d` / `i` **不落盘**：量太大，且对排障价值低（会把崩溃现场挤掉）。
 * ⚠️ 本类不得反向被 [LogStore] 调用（递归），见 `LogsTabWiringGateTest`。
 */
object AppLog {
    /** Debug 级别日志，仅在 BuildConfig.DEBUG 为 true 时输出（不落盘） */
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(tag, message)
        }
    }

    /** Info 级别日志，仅在 BuildConfig.DEBUG 为 true 时输出（不落盘） */
    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.i(tag, message)
        }
    }

    /** Warning 级别日志：始终落盘；logcat 仍只在 Debug 输出（保持原行为） */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        LogStore.append(LogStore.LEVEL_WARN, tag, message, throwable)
        if (BuildConfig.DEBUG) {
            android.util.Log.w(tag, message, throwable)
        }
    }

    /** Error 级别日志：始终落盘 + 始终输出（错误信息在生产环境也需要可见） */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        LogStore.append(LogStore.LEVEL_ERROR, tag, message, throwable)
        android.util.Log.e(tag, message, throwable)
    }
}
