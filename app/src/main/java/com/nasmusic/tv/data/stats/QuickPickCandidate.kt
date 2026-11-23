package com.nasmusic.tv.data.stats

/** 候选项来源：基础曲库数据 / 历史统计回填 */
enum class PickSource { BASE, HISTORY }

/**
 * 拨盘候选项包装。
 *
 * [item] 为原始领域对象（Album/Artist/Song 等）；历史回填项没有领域对象，item = null，
 * 只有 [value]（曾经选中过的名称字符串），填入搜索框后由服务端重新解析。
 * [source] 决定 UI 的文字风格（历史项用 Secondary 色 + 历史图标）。
 */
data class QuickPickCandidate<T>(
    val item: T?,
    val value: String,
    val source: PickSource
)
