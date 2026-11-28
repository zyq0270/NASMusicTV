package com.nasmusic.tv.player

/** 播放 URL 纯预刷策略；不持有 Service、播放器或网络状态。 */
object PlayUrlPrefetchScheduler {
    const val TTL_MS = 5 * 60_000L
    const val REFRESH_WINDOW_MS = 60_000L
    const val MAX_RETRY = 3
    const val TICK_MS = 5_000L

    /** 仅播放中且调用方已确认网络可用时运行；能力在线不等于互联网可达。 */
    fun shouldRun(statePlaying: Boolean, networkAvailable: Boolean): Boolean =
        statePlaying && networkAvailable

    /** 到期前最后 [REFRESH_WINDOW_MS] 进入刷新窗口；边界当 Refresh。 */
    fun shouldRefresh(cachedAtMs: Long, nowMs: Long): Boolean {
        val age = nowMs - cachedAtMs
        return age >= TTL_MS - REFRESH_WINDOW_MS
    }

    /** 5s、10s、20s；未知/失败次数按 0 处理，三次重试后不再增加等待。 */
    fun nextDelayMs(attempt: Int): Long = TICK_MS shl attempt.coerceIn(0, MAX_RETRY - 1).coerceAtMost(3)

    /** 当前曲之后最多预刷三首；空队列、非法下标或队尾返回空表。 */
    fun targets(queueSize: Int, currentIndex: Int): List<Int> {
        if (queueSize <= 0 || currentIndex !in 0 until queueSize) return emptyList()
        return ((currentIndex + 1) until minOf(queueSize, currentIndex + 4)).toList()
    }
}
