package com.nasmusic.tv.data.prefs

/**
 * E50「千里江山」三版设色的**版本号契约**（V-A / V-B / V-C）。
 *
 * ## 为什么单独收口一个文件
 * 同一个数字要在三处**逐位一致**：
 * ① 持久化的偏好值；② [com.nasmusic.tv.visualizer.RenderContext.qianliColorVersion]
 * 的逐帧直写值；③ 渲染器映射到 `QianliColorTable.Version` 的序号。
 * 任何一处各写各的常量 ⇒ 迟早出现「存的是 1、画的是 V-C」这类**静默错位**
 * （编译通过、单测全绿、只有真机看得出来）⇒ 契约收口在这里，
 * 持久化（[AppPreferences]）与舞台（`VisualizerStage`）都引它。
 *
 * ## 序号与渲染器的对应
 * `0 = V_A`（上游原样灰阶）/ `1 = V_B`（六族色表）/ `2 = V_C`（大青绿，**当前默认**）。
 * ⚠️ 序号与 `QianliColorTable.Version` 的枚举**声明顺序**一一对应
 * （V_A=0 / V_B=1 / V_C=2）⇒ 渲染器那侧可以用 `Version.entries[index]` 直接取，
 * 但**仍应显式 `when` 映射**、别依赖枚举顺序 —— 枚举顺序一变就静默画错。
 *
 * ## 非法值
 * 偏好值可能来自旧版本、手改的 prefs 文件或备份导入 ⇒ 读出来一律先过 [normalize]：
 * **越界即回落到默认版 V-C**，绝不把非法值喂给渲染器。
 * （写入侧 [AppPreferences.setQianliColorVersion] 也过同一道normalize ⇒ 存进去的就是合法值。）
 */
object QianliColorVersion {

    /** V-A：上游原样灰阶（对照组）。 */
    const val V_A = 0

    /** V-B：按坡面行号取六族色表。 */
    const val V_B = 1

    /** V-C：大青绿（加大饱和度）—— **当前默认**。 */
    const val V_C = 2

    /** 默认版（= [V_C]）：与改造前「编译期固定 V_C」逐字节一致 ⇒ 升级后行为不变。 */
    const val DEFAULT = V_C

    /** 版数（V-A / V-B / V-C 共三版）。 */
    const val COUNT = 3

    /** 合法取值区间：`0 until COUNT`（= 0..2）。 */
    val RANGE: IntRange = 0 until COUNT

    /** 越界（< 0 或 >= [COUNT]）⇒ 回落到 [DEFAULT]。 */
    fun normalize(version: Int): Int = if (version in RANGE) version else DEFAULT

    /** 下一个色版（循环）：`0 → 1 → 2 → 0`。 */
    fun next(version: Int): Int = (normalize(version) + 1) % COUNT

    /** 上一个色版（循环）：`2 → 1 → 0 → 2`。 */
    fun prev(version: Int): Int = (normalize(version) + COUNT - 1) % COUNT

    /** 展示名（用于 Toast 文案）：`V-A` / `V-B` / `V-C`。 */
    fun label(version: Int): String = when (normalize(version)) {
        V_A -> "V-A"
        V_B -> "V-B"
        else -> "V-C"
    }
}
