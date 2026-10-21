package com.nasmusic.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 主题对比度门禁（浅色主题方案 §3.3 / §7）。
 *
 * 判据一律 WCAG 2.x 相对亮度公式：正文 4.5:1，大字号与非文本控件 3:1。
 * 两套 palette 共用同一张「前景 / 底」清单 —— 浅色是新增面，深色是回归面。
 *
 * ⛔ 失败时**必须**打印完整对比度表：这是 §3.3 实测数字的唯一再生源，
 * 改色后不要手抄文档，跑这条测试取数。
 */
class ThemeContrastTest {

    /** `minRatio == null` 表示仅登记不设门槛（装饰线，方案 §3.3 明示不设） */
    private data class PairSpec(
        val label: String,
        val foreground: (NasPalette) -> Color,
        val background: (NasPalette) -> Color,
        val minRatio: Double?,
    )

    private val palettes = linkedMapOf("Dark" to NasPalettes.Dark, "Light" to NasPalettes.Light)

    private val pairs = listOf(
        // 正文
        PairSpec("TextPrimary / Background", { it.textPrimary }, { it.background }, 4.5),
        PairSpec("TextPrimary / Surface", { it.textPrimary }, { it.surface }, 4.5),
        PairSpec("TextPrimary / SurfaceVariant", { it.textPrimary }, { it.surfaceVariant }, 4.5),
        PairSpec("TextSecondary / Background", { it.textSecondary }, { it.background }, 4.5),
        PairSpec("TextSecondary / Surface", { it.textSecondary }, { it.surface }, 4.5),
        PairSpec("TextSecondary / SurfaceVariant", { it.textSecondary }, { it.surfaceVariant }, 4.5),
        // 主色 / 次色作为文字
        PairSpec("Primary / Background", { it.primary }, { it.background }, 4.5),
        PairSpec("Primary / Surface", { it.primary }, { it.surface }, 4.5),
        PairSpec("Primary / SurfaceVariant", { it.primary }, { it.surfaceVariant }, 4.5),
        PairSpec("Secondary / Background", { it.secondary }, { it.background }, 4.5),
        PairSpec("Secondary / Surface", { it.secondary }, { it.surface }, 4.5),
        // 状态色作为文字
        PairSpec("Danger / Background", { it.danger }, { it.background }, 4.5),
        PairSpec("Danger / Surface", { it.danger }, { it.surface }, 4.5),
        PairSpec("Warning / Background", { it.warning }, { it.background }, 4.5),
        PairSpec("Warning / Surface", { it.warning }, { it.surface }, 4.5),
        PairSpec("Success / Background", { it.success }, { it.background }, 4.5),
        PairSpec("Success / Surface", { it.success }, { it.surface }, 4.5),
        // 逐字歌词「已唱」高亮（播放页底色是 Background → SurfaceVariant 渐变）
        PairSpec("KaraokeHighlight / Background", { it.karaokeHighlight }, { it.background }, 4.5),
        PairSpec("KaraokeHighlight / SurfaceVariant", { it.karaokeHighlight }, { it.surfaceVariant }, 4.5),
        // 语义实底上的前景（§3.2 / §3.6）
        PairSpec("onPrimary / Primary", { it.onPrimary }, { it.primary }, 4.5),
        PairSpec("onDanger / Danger", { it.onDanger }, { it.danger }, 4.5),
        PairSpec("onWarning / Warning", { it.onWarning }, { it.warning }, 4.5),
        // 非文本：焦点环
        PairSpec("FocusRing / Background（非文本）", { it.focusRing }, { it.background }, 3.0),
        PairSpec("FocusRing / Surface（非文本）", { it.focusRing }, { it.surface }, 3.0),
        // 装饰性描边：浅色下 FocusableSurface 白卡靠它补层级，但方案不设门槛
        PairSpec("Border / Background（装饰，仅登记）", { it.border }, { it.background }, null),
        PairSpec("Border / Surface（装饰，仅登记）", { it.border }, { it.surface }, null),
    )

    @Test
    fun `深浅两套调色板的文字与语义容器对比度必须达标`() {
        val rows = mutableListOf<String>()
        val violations = mutableListOf<String>()

        for ((name, palette) in palettes) {
            for (spec in pairs) {
                val ratio = contrastRatio(spec.foreground(palette), spec.background(palette))
                val threshold = spec.minRatio
                val result = when {
                    threshold == null -> "登记"
                    ratio + EPSILON >= threshold -> "PASS"
                    else -> "FAIL".also {
                        violations += "$name: ${spec.label} 实测 ${fmt(ratio)} < ${fmt(threshold)}"
                    }
                }
                rows += row(name, spec.label, ratio, threshold, result)
            }
        }

        assertTrue(
            buildString {
                appendLine("对比度未达标 ${violations.size} 项：")
                violations.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("说明：浅色的正文色 / 状态色若确实需要更浅，应改调色板而不是放宽门槛；")
                appendLine("      深色侧除 §3.6 的 onX 修正外不得改动（§3.6 修订后对比度只会更高）。")
                appendLine()
                append(renderTable(rows))
            },
            violations.isEmpty()
        )
    }

    /** 浅色页面底是所有者 2026-10-21 拍板的 `#F3F5F9`（方案 §3.1）。 */
    @Test
    fun `浅色 Background 必须保持已拍板的值`() {
        assertEquals(0xFFF3F5F9.toInt(), NasPalettes.Light.background.toArgb())
        assertEquals(0xFFFFFFFF.toInt(), NasPalettes.Light.surface.toArgb())
    }

    /**
     * 深色 hex 冻结（方案开篇的硬约束 + §3.6 允许的三项新增前景）。
     *
     * 这份表**故意**把数字写死：深色回归是本次改造唯一不能让步的东西，
     * 任何"顺手调深色"的改动都应在这里被拦下并显式评审。
     */
    @Test
    fun `深色调色板 hex 已冻结`() {
        val expected = linkedMapOf(
            "background" to 0xFF0C1222,
            "surface" to 0xFF162032,
            "surfaceVariant" to 0xFF1E2D42,
            "primary" to 0xFF2DD4BF,
            "onPrimary" to 0xFF0C1222,
            "secondary" to 0xFF60A5FA,
            "primaryEmphasis" to 0xFF5EEAD4,
            "textPrimary" to 0xFFE8EDF5,
            "textSecondary" to 0xFF8899B0,
            "karaokeHighlight" to 0xFFFFFF00,
            "border" to 0xFF2A3A52,
            "danger" to 0xFFF87171,
            "onDanger" to 0xFF0C1222,
            "warning" to 0xFFFBBF24,
            "onWarning" to 0xFF0C1222,
            "success" to 0xFF34D399,
            "focusRing" to 0xFF2DD4BF,
            "accentGlow" to 0x262DD4BF,
            "accentGlowStrong" to 0x4D2DD4BF,
        )
        val p = NasPalettes.Dark
        val actual = linkedMapOf(
            "background" to p.background, "surface" to p.surface, "surfaceVariant" to p.surfaceVariant,
            "primary" to p.primary, "onPrimary" to p.onPrimary, "secondary" to p.secondary,
            "primaryEmphasis" to p.primaryEmphasis, "textPrimary" to p.textPrimary,
            "textSecondary" to p.textSecondary, "karaokeHighlight" to p.karaokeHighlight,
            "border" to p.border, "danger" to p.danger,
            "onDanger" to p.onDanger, "warning" to p.warning, "onWarning" to p.onWarning,
            "success" to p.success, "focusRing" to p.focusRing,
            "accentGlow" to p.accentGlow, "accentGlowStrong" to p.accentGlowStrong,
        )
        val drift = expected.mapNotNull { (key, hex) ->
            val got = actual.getValue(key).toArgb()
            if (got == hex.toInt()) null else "$key 期望 ${hexStr(hex.toInt())} 实得 ${hexStr(got)}"
        }
        assertTrue(
            "深色调色板发生了非预期改动（深色回归是硬约束）：\n" + drift.joinToString("\n"),
            drift.isEmpty()
        )
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private fun contrastRatio(fg: Color, bg: Color): Double {
        val a = relativeLuminance(fg)
        val b = relativeLuminance(bg)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /** WCAG 2.x 相对亮度（sRGB 线性化） */
    private fun relativeLuminance(c: Color): Double =
        0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)

    private fun linear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun row(palette: String, label: String, ratio: Double, threshold: Double?, result: String) =
        "| $palette | $label | ${fmt(ratio)} | ${threshold?.let { fmt(it) } ?: "—"} | $result |"

    private fun renderTable(rows: List<String>) = buildString {
        appendLine("| 调色板 | 前景 / 底 | 实测 | 门槛 | 结果 |")
        appendLine("|---|---|---|---|---|")
        rows.forEach { appendLine(it) }
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)

    private fun hexStr(argb: Int) = String.format(Locale.US, "#%08X", argb)

    private companion object {
        /** 浮点累加误差（16 位色算出的比值与门槛相等时不应判红） */
        const val EPSILON = 1e-9
    }
}
