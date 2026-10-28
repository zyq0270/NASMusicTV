package com.nasmusic.tv.backend.network.lx

import com.nasmusic.tv.backend.network.QualityTiers

/**
 * 项目音质档位 ↔ 洛雪 `info.type`（方案 §6.1 / §6.2 / §6.3）。
 *
 * 两条必须守住的语义：
 * 1. **`AUTO` 不是 320k**：它的语义是「不传 br，交给端点」，对应洛雪只能是
 *    「按脚本自己声明的顺序给」。硬编成 320k 既替源做了选择，也拿不到源本会给的 FLAC。
 * 2. **空交集 ⇒ 放弃**（返回空列表），由调用方回退 Meting。
 *    源只声明 128k 时硬喂 128k 给「选了无损」的用户，就是静默降级——比不用更糟。
 */
internal object LxQualityMapper {

    /**
     * 无损档的候选队首：脚本自己声明的「比 flac 还高」的档位（星海 / 墨澜都声明了）。
     * ⚠️ `qualitys` 是**声明**不是能力，这里只决定尝试顺序（§6.1）。
     */
    private val HIGH_END_FIRST = listOf("master", "hires", "atmos", "atmos_plus")

    /**
     * 项目档位 → 候选洛雪档位（按优先序，**未与脚本声明求交**）。
     *
     * @return 空列表 = `AUTO`（由调用方改为「脚本声明顺序」）
     */
    fun candidatesFor(tier: Int): List<String> = when (tier) {
        QualityTiers.AUTO -> emptyList()
        QualityTiers.STANDARD -> listOf("128k")
        // 洛雪没有 192k：GOOD 与 HIGH 同队，先 320k 再退 128k
        QualityTiers.GOOD, QualityTiers.HIGH -> listOf("320k", "128k")
        QualityTiers.LOSSLESS -> HIGH_END_FIRST + listOf("flac24bit", "flac", "320k")
        // 非法持久化值：按 HIGH 处理，不抛（设置项被改坏也不该让播放崩）
        else -> listOf("320k", "128k")
    }

    /**
     * 档位 + 脚本声明 → 实际尝试顺序。
     *
     * - `AUTO`：原样用脚本声明顺序（脚本最懂自己）
     * - 其余：候选 ∩ 声明，**保持候选的优先序**（不是声明序）
     * - 交集为空：空列表 ⇒ 调用方放弃洛雪、回退 Meting
     */
    fun planFor(tier: Int, declared: List<String>): List<String> {
        if (tier == QualityTiers.AUTO) return declared
        if (declared.isEmpty()) return emptyList()
        val declaredSet = declared.toSet()
        return candidatesFor(tier).filter { it in declaredSet }
    }

    /**
     * 洛雪档位 → 项目档位（方案 §6.3）。
     *
     * ⚠️ `master` / `hires` / `atmos` **必须**落到 LOSSLESS：正向映射把它们放在无损候队首，
     * 反向落到 AUTO 就会出现「请求无损、actualQuality=0」⇒ 降级提示失效。
     */
    fun fromLx(quality: String): Int = when (quality) {
        "128k" -> QualityTiers.STANDARD
        "320k" -> QualityTiers.HIGH
        "flac", "flac24bit" -> QualityTiers.LOSSLESS
        "master", "hires", "atmos", "atmos_plus" -> QualityTiers.LOSSLESS
        else -> QualityTiers.AUTO
    }
}
