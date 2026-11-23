package com.nasmusic.tv.data.stats

import com.nasmusic.tv.data.model.QuickPickStat

/**
 * 拨盘候选热度排序（纯函数，可单测）。
 *
 * 排序规则（所有者定稿）：
 * - 热度 heat(value) = 快捷选择次数 + 搜索次数（同名合并）；
 * - 主序：heat 降序；
 * - 次序（热度相同）：按名称自然序升序（大小写不敏感）；
 * - 无热度的项 heat = 0，排在热项之后，按名称排序；
 * - 冷启动（无统计）时全部 heat = 0，退化为纯名称排序。
 *
 * 本聚合器只用于搜索快速填写；播放切歌纯队列顺序，不调用本类。
 */
object QuickPickRanker {

    private val NAME_COMPARATOR = compareBy<String> { it.lowercase(java.util.Locale.ROOT) }
        .thenBy { it }

    fun <T> rank(
        candidates: List<QuickPickCandidate<T>>,
        pickStats: List<QuickPickStat>,
        searchCounts: Map<String, Int> = emptyMap(),
        limit: Int = 7
    ): List<QuickPickCandidate<T>> {
        if (candidates.isEmpty()) return emptyList()
        val pickCounts = pickStats.associate { it.value to it.count }
        val heatOf: (QuickPickCandidate<T>) -> Int = { c ->
            (pickCounts[c.value] ?: 0) + (searchCounts[c.value] ?: 0)
        }
        val sorted = candidates.sortedWith(
            compareByDescending<QuickPickCandidate<T>> { heatOf(it) }
                .thenBy(NAME_COMPARATOR) { it.value }
        )
        return if (sorted.size <= limit) sorted else sorted.take(limit)
    }
}
