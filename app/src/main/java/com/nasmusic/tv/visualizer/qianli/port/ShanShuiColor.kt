/**
 * 纯数据颜色类型 —— port 包零 Android 依赖。
 *
 * 上游用 CSS 字符串（如 `"rgba(100,100,100,0.5)"`）；port 包内用纯数据 `(r, g, b, a)`。
 * 渲染器侧再映射到 `android.graphics.Color` / Compose `Color`。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * 移植状态：**改造-④**（JS 字符串 → 纯数据类）。
 */

package com.nasmusic.tv.visualizer.qianli.port

data class ShanShuiColor(val r: Int, val g: Int, val b: Int, val a: Double) {

    companion object {
        /** 从 `rgba(r,g,b,a)` / `rgb(r,g,b)` 字符串解析。上游大量使用此格式。 */
        fun parseRgba(s: String): ShanShuiColor {
            val m = Regex("rgba?\\(([^)]+)\\)").find(s)
            if (m != null) {
                val parts = m.groupValues[1].split(",").map { it.trim().toDouble() }
                return when (parts.size) {
                    4 -> ShanShuiColor(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), parts[3])
                    3 -> ShanShuiColor(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), 1.0)
                    else -> ShanShuiColor(100, 100, 100, 0.5)
                }
            }
            // named colors
            return when (s.trim()) {
                "white" -> ShanShuiColor(255, 255, 255, 1.0)
                "black" -> ShanShuiColor(0, 0, 0, 1.0)
                "red" -> ShanShuiColor(255, 0, 0, 1.0)
                "green" -> ShanShuiColor(0, 128, 0, 1.0)
                else -> ShanShuiColor(100, 100, 100, 0.5)
            }
        }

        /** 便捷构造：`rgba(r,g,b,a)`。 */
        fun rgba(r: Int, g: Int, b: Int, a: Double): ShanShuiColor = ShanShuiColor(r, g, b, a)

        /** 上游默认灰阶色 `rgba(100,100,100,0.5)`。 */
        val default: ShanShuiColor = ShanShuiColor(100, 100, 100, 0.5)

        /** 上游默认灰阶色 `rgba(100,100,100,0.3)`。 */
        val outline: ShanShuiColor = ShanShuiColor(100, 100, 100, 0.3)
    }
}
