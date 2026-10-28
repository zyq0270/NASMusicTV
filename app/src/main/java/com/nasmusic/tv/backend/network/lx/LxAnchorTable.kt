package com.nasmusic.tv.backend.network.lx

/**
 * 验证用的锚点曲目（方案 §5.4.1.4）。
 *
 * ⛔ **必须按 source 分表**：同一首《晴天》在 `tx` 是 `0039MnYb0qxYhV`、在 `wy` 是 `186016`，
 * 而两者共用 `songmid` 这个键名 —— 合成一个 `musicInfo` 就必然互相覆盖。
 *
 * @param fields 完整字段集（能填的都填）。实测 Flower 按平台分字段、Huibq 用
 *   `hash ?: songmid` 兜底，只给一个字段会在某些源上莫名失败（§2.3）。
 * @param verified `false` = 我们没有可靠样本 ⇒ 不做 L2/L3，结论记 `NO_ANCHOR`。
 *   ⛔ **不许伪造 id**：拿编造的 id 去测，404 会被显示成「源不支持该平台」——
 *   把「我们没样本」谎报成「源不行」，是验证功能最坏的失败模式。
 */
internal data class LxAnchor(
    val source: String,
    val fields: Map<String, String>,
    val verified: Boolean,
) {
    fun toMusicInfo(): LxMusicInfo = LxMusicInfo(
        songmid = fields["songmid"],
        hash = fields["hash"],
        name = fields["name"],
        singer = fields["singer"],
        albumName = fields["albumName"],
        interval = fields["interval"],
    )
}

/**
 * 内置锚点表（2026-10-28 实测，方案 §2.4.1）。
 *
 * ⚠️ `wy` 的 id 是**对的**，但当前这批 12 个源全部取不到链 ⇒ 验证的期望结果就是 ❌。
 * 这不是锚点的问题，别为了「让结果好看」而换 id。
 * ⚠️ `kw` / `mg` 保持 `verified = false`：qijieya 搜 kuwo 返回空数组、migu 返回网易 id，
 * 没有可信样本（§5.4.1.4）。
 */
internal object LxAnchorTable {
    val DEFAULT: Map<String, LxAnchor> = listOf(
        LxAnchor(
            source = "kg",
            fields = mapOf(
                "hash" to "b3a52a7a958bf0aed0ebfba2e9a818b7",
                "name" to "晴天",
                "singer" to "周杰伦",
                "albumName" to "叶惠美",
                "interval" to "04:29",
            ),
            verified = true,
        ),
        LxAnchor(
            source = "tx",
            fields = mapOf(
                "songmid" to "0039MnYb0qxYhV",
                "name" to "晴天",
                "singer" to "周杰伦",
                "albumName" to "叶惠美",
                "interval" to "04:29",
            ),
            verified = true,
        ),
        LxAnchor(
            source = "wy",
            fields = mapOf(
                "songmid" to "186016",
                "name" to "晴天",
                "singer" to "周杰伦",
                "albumName" to "叶惠美",
                "interval" to "04:29",
            ),
            verified = true,
        ),
        LxAnchor(source = "kw", fields = emptyMap(), verified = false),
        LxAnchor(source = "mg", fields = emptyMap(), verified = false),
    ).associateBy { it.source }
}
