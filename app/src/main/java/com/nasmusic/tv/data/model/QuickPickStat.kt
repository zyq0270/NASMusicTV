package com.nasmusic.tv.data.model

/**
 * 快捷选择统计条目（Quick Dial）
 *
 * 用户在搜索快速填写拨盘中选中某个内容（专辑名/歌手名/歌曲名等）时写入。
 * 与 [SearchHistoryItem] 不同：本模型带 [category] 维度（对应曲库各搜索 tab），
 * 用于「按类别分别统计」与拨盘候选的热度排序。
 *
 * 持久化到 DataStore `quick_pick_stats`（JSON 列表），不设 TTL，靠数量上限裁剪。
 * 播放切歌场景不写本模型（切歌纯队列顺序，不做热度排序）。
 */
data class QuickPickStat(
    val category: String,
    val value: String,
    val count: Int = 1,
    val lastPickedAt: Long = System.currentTimeMillis()
)
