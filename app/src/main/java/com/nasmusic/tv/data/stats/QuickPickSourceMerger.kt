package com.nasmusic.tv.data.stats

import com.nasmusic.tv.data.model.QuickPickStat
import com.nasmusic.tv.util.ArtistSplitter

/**
 * 拨盘候选源合成（纯函数，可单测）。
 *
 * 解决「只连网络源、无 NAS/网盘时 ARTISTS/ALBUMS 基础列表为空 ⇒ 快选窗无数据」：
 * 用统计里的历史数据回填，基础数据存在时历史也一起进列表。
 *
 * 规则：
 * 1. 基础列表全部纳入，source = BASE；
 * 2. 该类别的 quick_pick_stats 值中，不在基础集合中的补入，source = HISTORY；
 * 3. 以归一化 key 去重（复用 [ArtistSplitter.normalizeKey]），基础与历史同名时保留 BASE；
 * 4. 合成结果不再排序（排序由 [QuickPickRanker] 负责）。
 */
object QuickPickSourceMerger {

    fun <T> merge(
        base: List<T>,
        valueOf: (T) -> String,
        stats: List<QuickPickStat>
    ): List<QuickPickCandidate<T>> {
        val result = mutableListOf<QuickPickCandidate<T>>()
        val seen = HashSet<String>()

        base.forEach { item ->
            val value = valueOf(item).trim()
            if (value.isEmpty()) return@forEach
            val key = ArtistSplitter.normalizeKey(value)
            if (key.isEmpty() || !seen.add(key)) return@forEach
            result += QuickPickCandidate(item = item, value = value, source = PickSource.BASE)
        }

        stats.forEach { stat ->
            val value = stat.value.trim()
            if (value.isEmpty()) return@forEach
            val key = ArtistSplitter.normalizeKey(value)
            if (key.isEmpty() || !seen.add(key)) return@forEach
            result += QuickPickCandidate(item = null, value = value, source = PickSource.HISTORY)
        }

        return result
    }
}
