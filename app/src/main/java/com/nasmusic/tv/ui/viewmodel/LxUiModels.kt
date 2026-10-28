package com.nasmusic.tv.ui.viewmodel

import com.nasmusic.tv.backend.network.lx.LxL1
import com.nasmusic.tv.backend.network.lx.LxPlatformProbe
import com.nasmusic.tv.backend.network.lx.LxSourceEntry
import com.nasmusic.tv.backend.network.lx.LxStoredPlatform
import com.nasmusic.tv.backend.network.lx.LxStoredStatus

/**
 * 洛雪子块的 UI 视图模型（设置页直读）。
 *
 * 本次会话的探测结果（[livePlatforms]）**优先于**上次落盘结果（[result]）：
 * 用户刚点完「验证」，界面上的结论必须是这轮实测，而不是 24 小时前的。
 */
internal enum class LxVerifyStage {
    /** 从未验证 */
    IDLE,

    /**
     * 按需补下脚本正文（v2.39.8）。
     *
     * ⚠️ 内置源出厂**只登记 URL、不内置正文**（§9.4），所以第一次点「验证」必须先下载。
     * 没有这个阶段，用户会看到按钮闪一下又变回「重试」，完全不知道发生了什么。
     */
    DOWNLOADING,

    /** L1：加载脚本 / 等 `inited` */
    LOADING,

    /** 逐平台 L2 → L3 */
    PROBING,

    /** 跑完且 L1 通过 */
    DONE,

    /** L1 失败（脚本装不上，详情见 [LxScriptView.l1]） */
    FAILED,
}

internal data class LxScriptView(
    val entry: LxSourceEntry,
    val running: Boolean = false,
    val stage: LxVerifyStage = LxVerifyStage.IDLE,
    val currentSource: String? = null,
    val index: Int = 0,
    val total: Int = 0,
    val l1: LxL1? = null,
    /** 本轮已完成的平台（逐个刷新，不等全部跑完） */
    val livePlatforms: Map<String, LxPlatformProbe> = emptyMap(),
    /** 本轮结果；null = 回退到 [LxSourceEntry.lastStatus] */
    val result: LxStoredStatus? = null,
) {
    /** 统一取平台结论：本轮实测 > 上次落盘 */
    fun platformOf(source: String): LxStoredPlatform? =
        livePlatforms[source]?.toStored() ?: result?.platforms?.get(source)
}

internal fun LxPlatformProbe.toStored(): LxStoredPlatform = LxStoredPlatform(
    verdict = verdict,
    reason = reason,
    quality = bestQuality,
    container = actualContainer,
    mismatch = qualityMismatch,
)

/** 「全部验证」的串行进度（§7.2：不能让用户对着一分钟的转圈猜还剩多久） */
internal data class LxVerifyAllState(
    val running: Boolean,
    val done: Int,
    val total: Int,
    val currentName: String?,
)
