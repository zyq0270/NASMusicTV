package com.nasmusic.tv.util

import com.nasmusic.tv.BuildConfig

/**
 * 应用日志工具（按构建类型分层）。
 *
 * | 级别 | Debug 构建 | Release 构建 |
 * |---|---|---|
 * | d / i | logcat + 落盘 | **完全不产生** |
 * | w | logcat + 落盘 | 落盘 |
 * | e | logcat + 落盘 | logcat + 落盘 |
 * | `diag`（级别 X） | logcat + 落盘 | **落盘**（日志页「调试」档单列） |
 *
 * 落盘（[LogStore]）是为设备侧回看：电视上没法连 adb，用户报「刚才闪退了」时
 * logcat 早没了。日志页（设置 → 日志）可按级别过滤，D/I 只在 Debug 出现。
 *
 * ⚠️ **分级准则**：逐端点 / 逐请求 / 缓存命中等追踪信息用 `d`；
 * 聚合后的功能失败（所有端点都失败、解析失败）用 `w`；
 * 崩溃、数据丢失、用户操作失败用 `e`。⛔ 不要把 `e` 当「留痕」用——
 * release 日志页只应出现真正需要处理的问题。
 *
 * ⚠️ [diag] 是**例外**中的例外：它只承载「用户手机上没法 adb、只能靠截图回传的设备侧读数」
 * （如焦点判据实测值），并且在日志页里**单独一档**显示，不会出现在 W/E 视图里。
 * 每次启动只应产出个位数条，⛔ 不要拿来记逐请求信息（那该用 `d`）。
 *
 * ⚠️ 本类不得反向被 [LogStore] 调用（递归），见 `LogsTabWiringGateTest`。
 */
object AppLog {
    /** Debug 级别日志：仅 Debug 构建产生（logcat + 落盘） */
    fun d(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            LogStore.append(LogStore.LEVEL_DEBUG, tag, message, throwable)
            android.util.Log.d(tag, message, throwable)
        }
    }

    /** Info 级别日志：仅 Debug 构建产生（logcat + 落盘） */
    fun i(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            LogStore.append(LogStore.LEVEL_INFO, tag, message, throwable)
            android.util.Log.i(tag, message, throwable)
        }
    }

    /** Warning 级别：始终落盘；logcat 仅 Debug 输出（release 不刷系统日志） */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        LogStore.append(LogStore.LEVEL_WARN, tag, message, throwable)
        if (BuildConfig.DEBUG) {
            android.util.Log.w(tag, message, throwable)
        }
    }

    /**
     * §10.401：Warning 级别（限流去重）—— 同 tag + 同 message 的连续重复只写首条 +
     * 周期性摘要，避免高频噪音（如 MetingDiag 的端点全失败 W 日志）挤占日志保留窗口。
     *
     * ⚠️ **只限流 W 级**；E 级用 [e]、D/I/X 用 [d]/[i]/[diag]，均不参与限流。
     */
    fun wThrottled(tag: String, message: String, throwable: Throwable? = null) {
        LogStore.appendThrottled(LogStore.LEVEL_WARN, tag, message, throwable)
        if (BuildConfig.DEBUG) {
            android.util.Log.w(tag, message, throwable)
        }
    }

    /** Error 级别：始终落盘 + 始终输出（生产环境也必须可见） */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        LogStore.append(LogStore.LEVEL_ERROR, tag, message, throwable)
        android.util.Log.e(tag, message, throwable)
    }

    /**
     * 调试/诊断级别：**release 也落盘**，logcat 仅 Debug 输出。
     *
     * 只用于「设备侧读数、用户截图回传」这一类取证信息（见类头的分级准则），
     * 在日志页里单独一档（[LogStore.LEVEL_DIAG]），不会污染 I/W/E 视图。
     */
    fun diag(tag: String, message: String) {
        LogStore.append(LogStore.LEVEL_DIAG, tag, message)
        if (BuildConfig.DEBUG) {
            android.util.Log.i(tag, message)
        }
    }
}
