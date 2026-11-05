package com.nasmusic.tv.visualizer.qianli

import com.nasmusic.tv.visualizer.qianli.port.ShanShuiColor

/**
 * 三版染色表 —— V-A 灰阶 / V-B 行号染色 / V-C 大青绿。
 *
 * 按坡面行号 [rowJ] 取色（V-B/V-C），⛔ 按 y 取色。
 * 颜色由该笔触在坡面点阵里的行号决定（j=0 山脊 → j=R 山脚）。
 *
 * V-A：原样 rgba(100,100,100,α)（对照组，对应上游 `getCol` 的灰阶噪声）。
 * V-B：按方案 §3.3 六族色表染色，α 随行号递减（山脊 0.6 → 山脚 0.3）。
 * V-C：V-B + 加大饱和度（分层平涂设色）+ 皴密度下调，α 略高于 V-B。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * 移植状态：**改造-⑤**（新增三版染色表，上游只有 V-A 灰阶噪声）。
 */
object QianliColorTable {

    enum class Version { V_A, V_B, V_C }

    /**
     * 按坡面行号取色。
     *
     * @param version    染色版本。
     * @param rowJ       当前行号（0 = 山脊，totalRows = 山脚）。
     * @param totalRows  坡面总行数 R；<= 0 时退化为 V-A 默认色。
     * @return 该行对应的 [ShanShuiColor]。
     */
    fun colorFor(version: Version, rowJ: Int, totalRows: Int): ShanShuiColor {
        if (totalRows <= 0) return ShanShuiColor.default
        val t = (rowJ.toDouble() / totalRows).coerceIn(0.0, 1.0)
        return when (version) {
            Version.V_A -> ShanShuiColor(100, 100, 100, 0.5)
            Version.V_B -> colorAt(V_B_STOPS, t, alphaAt(t, 0.6, 0.3))
            Version.V_C -> colorAt(V_C_STOPS, t, alphaAt(t, 0.65, 0.35))
        }
    }

    /** 色表节点：比例 t ∈ [0,1] 对应的 RGB。 */
    private data class Stop(val t: Double, val r: Int, val g: Int, val b: Int)

    /** V-B 六族色表（方案 §3.3）。 */
    private val V_B_STOPS = listOf(
        Stop(0.0, 0x5a, 0x8f, 0x7b), // 石绿，偏青
        Stop(0.2, 0xb8, 0x76, 0x3d), // 赭石，偏暖
        Stop(0.4, 0x3a, 0x6b, 0x8a), // 花青，偏蓝
        Stop(0.6, 0x4a, 0x7b, 0x9a), // 石青，偏蓝
        Stop(0.8, 0x6a, 0x9b, 0x5a), // 汁绿，偏绿
        Stop(1.0, 0x2a, 0x2a, 0x2a), // 淡墨，偏暗
    )

    /** V-C 大青绿色表：在 V-B 基础上加大饱和度，山脊偏青绿、山脚偏墨。 */
    private val V_C_STOPS = listOf(
        Stop(0.0, 0x2a, 0x5a, 0x4a), // 青绿
        Stop(0.2, 0x8a, 0x4a, 0x2a), // 深赭
        Stop(0.4, 0x1a, 0x4a, 0x6a), // 深花青
        Stop(0.6, 0x2a, 0x5a, 0x8a), // 深石青
        Stop(0.8, 0x3a, 0x6a, 0x2a), // 深汁绿
        Stop(1.0, 0x1a, 0x1a, 0x1a), // 墨
    )

    /** α 线性插值：山脊 [top] → 山脚 [bottom]。 */
    private fun alphaAt(t: Double, top: Double, bottom: Double): Double =
        top + (bottom - top) * t

    /** 在色表 [stops] 中按比例 [t] 线性插值取 RGB，α 取 [a]。 */
    private fun colorAt(stops: List<Stop>, t: Double, a: Double): ShanShuiColor {
        if (t <= stops.first().t) {
            val s = stops.first()
            return ShanShuiColor(s.r, s.g, s.b, a)
        }
        if (t >= stops.last().t) {
            val s = stops.last()
            return ShanShuiColor(s.r, s.g, s.b, a)
        }
        for (i in 0 until stops.size - 1) {
            val s0 = stops[i]
            val s1 = stops[i + 1]
            if (t >= s0.t && t <= s1.t) {
                val f = (t - s0.t) / (s1.t - s0.t)
                return ShanShuiColor(
                    lerp(s0.r, s1.r, f),
                    lerp(s0.g, s1.g, f),
                    lerp(s0.b, s1.b, f),
                    a
                )
            }
        }
        return ShanShuiColor.default
    }

    private fun lerp(a: Int, b: Int, f: Double): Int =
        (a + (b - a) * f).toInt().coerceIn(0, 255)
}
