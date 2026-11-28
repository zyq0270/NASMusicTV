package com.nasmusic.tv.data.stats

import com.nasmusic.tv.data.model.DuplicateResolution
import java.util.TreeMap

/**
 * 播放统计读取期 alias：隐藏只影响展示与聚合键，不修改历史计数。
 *
 * 规则：
 * - 沿 hide -> keep 链解析到末端；
 * - 环选择环内字典序最小 key，保证结果稳定且总次数守恒；
 * - 当前 map 中 canonical 不存在时回退到原 key，避免把计数搬到没有元数据的键；
 * - 同一条目被多条 resolution 指向时合并整组，取组内字典序最小 key。
 */
class StatsAliasResolver(
    private val resolutions: Map<String, DuplicateResolution>,
) {
    /**
     * keepKey 与 hideKeys 视为同一实体组：多份 resolution 互相指向时合并整组，
     * 组内字典序最小的 key 作为 canonical（环也因此有稳定结果）。
     */
    private val canonicalByKey: Map<String, String> = run {
        val parent = HashMap<String, String>()
        fun find(value: String): String {
            var root = value
            while (parent[root] != root) root = parent[root]!!
            var cursor = value
            while (parent[cursor] != root) {
                val next = parent[cursor]!!
                parent[cursor] = root
                cursor = next
            }
            return root
        }
        fun union(a: String, b: String) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA == rootB) return
            if (rootA < rootB) parent[rootB] = rootA else parent[rootA] = rootB
        }
        resolutions.values.forEach { resolution ->
            val keep = resolution.keepKey
            if (keep.isBlank()) return@forEach
            if (keep !in parent) parent[keep] = keep
            resolution.hideKeys.filter { it.isNotBlank() }.forEach { hidden ->
                if (hidden !in parent) parent[hidden] = hidden
                union(keep, hidden)
            }
        }
        parent.keys.associateWith(::find)
    }

    fun resolveKey(key: String, availableKeys: Set<String>? = null): String {
        val canonical = canonicalOf(key)
        return if (availableKeys == null || canonical in availableKeys) canonical else key
    }

    fun resolveCounts(
        counts: Map<String, Int>,
        availableKeys: Set<String> = counts.keys,
    ): Map<String, Int> {
        if (counts.isEmpty()) return emptyMap()
        val sums = sortedMapOf<String, Long>()
        counts.forEach { (key, count) ->
            val target = resolveKey(key, availableKeys)
            sums[target] = (sums[target] ?: 0L) + count.toLong()
        }
        return sums.mapValuesTo(sortedMapOf<String, Int>()) { (_, value) -> Math.toIntExact(value) }
    }

    fun resolveMonthly(monthly: Map<String, Map<String, Int>>): Map<String, Map<String, Int>> =
        monthly.mapValuesTo(sortedMapOf<String, Map<String, Int>>()) { (_, counts) -> resolveCounts(counts) }

    /** 兼容按日 -> 歌曲 -> 次数的新统计形态。 */
    fun resolveDailyBySong(daily: Map<String, Map<String, Int>>): Map<String, Map<String, Int>> =
        daily.mapValuesTo(sortedMapOf<String, Map<String, Int>>()) { (_, counts) -> resolveCounts(counts) }

    /** 现有按日 -> 总次数没有歌曲维度，alias 前后总数必须逐日完全一致。 */
    fun resolveDaily(daily: Map<String, Int>): Map<String, Int> = daily.toSortedMap()

    /** 聚合时优先使用 canonical 元数据；canonical 缺失时保留可见条目的元数据。 */
    fun <T> resolveMetadata(metadata: Map<String, T>): Map<String, T> {
        val available = metadata.keys
        val result = sortedMapOf<String, T>()
        metadata.entries.sortedBy { it.key }.forEach { (key, value) ->
            val target = resolveKey(key, available)
            if (key == target || target !in result) result[target] = value
        }
        return result
    }

    private fun canonicalOf(key: String): String = canonicalByKey[key] ?: key

    private fun <K, V> sortedMapOf(): MutableMap<K, V> = TreeMap()
}
