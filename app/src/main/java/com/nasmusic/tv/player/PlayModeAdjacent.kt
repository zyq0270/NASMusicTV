package com.nasmusic.tv.player

import com.nasmusic.tv.data.model.PlayMode

/**
 * 手动 next / previous 的「真实目标下标」唯一判据（2026-11-22 队列页修复 P2）。
 *
 * 背景：PlayerViewModel 为了给恢复队列里 streamUrl 为空的网络歌提前解析，
 * 会先猜一个目标下标。旧实现无条件用 `currentIndex ± 1` 环绕，而 PlayerManager
 * 真正执行切歌时还会再看 playMode —— 两处目标可能不是同一首：
 * - SHUFFLE：底层走 playRandom()，预判却播了顺序邻曲，且不写 shuffleHistory；
 * - SEQUENTIAL 边界：底层无动作，预判却绕到另一头播放。
 *
 * 本函数与 PlayerManager.next / previous 的模式分支逐条对齐：
 * - SHUFFLE → null（不预判，交给 playRandom + onMediaItemTransition 的解析链）；
 * - REPEAT_ALL → 两个方向都环绕；
 * - REPEAT_ONE → next 在队尾回第一首（用户主动按下一首不重播当前），previous 队首无动作；
 * - SEQUENTIAL → 两个边界都无动作。
 *
 * null 的语义是「本次不预判」，调用方直接把事件原样交给 PlayerManager，
 * 由它按模式决定动作（包括"什么都不做"）。
 */
internal fun adjacentIndex(
    queueSize: Int,
    currentIndex: Int,
    playMode: PlayMode,
    previous: Boolean
): Int? {
    if (queueSize <= 0 || currentIndex !in 0 until queueSize) return null
    if (playMode == PlayMode.SHUFFLE) return null

    val atEdge = if (previous) currentIndex == 0 else currentIndex == queueSize - 1
    if (!atEdge) return if (previous) currentIndex - 1 else currentIndex + 1

    return when (playMode) {
        PlayMode.REPEAT_ALL -> if (previous) queueSize - 1 else 0
        PlayMode.REPEAT_ONE -> if (previous) null else 0
        else -> null
    }
}
