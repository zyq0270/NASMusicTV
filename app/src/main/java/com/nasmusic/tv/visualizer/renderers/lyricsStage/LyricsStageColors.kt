package com.nasmusic.tv.visualizer.renderers.lyricsStage

import androidx.compose.ui.graphics.toArgb
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath

/**
 * 歌词舞台的配色映射 —— 修正 Folia 主题色与本仓调色板的**语义对应**。
 *
 * ## Folia 的三个主题色各是什么
 * Folia `src/types.ts:109-111` 定义了 `primaryColor` / `accentColor` / `secondaryColor`，
 * 默认值见 `themeSanitizer.ts`：
 *
 * | Folia 主题色 | 默认值 | 语义 |
 * |---|---|---|
 * | `primaryColor` | `#111827`（暗色主题）/ `#f8fafc`（亮色主题） | **基础文字色**（中性近白 / 近黑） |
 * | `accentColor` | `#2563eb` / `#7dd3fc` | **高亮色**（鲜艳蓝） |
 * | `secondaryColor` | —— | 次级强调色 |
 *
 * ## ⛔ 原先的映射是错的
 * 原先按名字直接对应：`theme.primaryColor → ctx.palette.accent`。
 * 但本仓 `CoverPalette.accent` **恒为鲜艳蓝**（`CoverPaletteProvider.kt:49`：
 * `hsl(195°, 100%, litMain)`，只有明度随封面微调）⇒
 * 把它当「基础文字色」⇒ **满屏歌词变成刺眼的霓虹蓝**，与 Folia 的近白正文完全不同。
 *
 * ⇒ 这里改成按**语义**对应：
 *  - 基础文字色 = 从背景提亮出来的近白（Folia 的 `#f8fafc`）
 *  - 高亮色 = `palette.accent`（鲜艳蓝，≈ Folia 的蓝 accent ✓）
 *  - 次级强调色 = `palette.secondary`（≈ Folia 的 `secondaryColor` ✓）
 */
object LyricsStageColors {

    /** Folia `theme.primaryColor` —— 基础文字色（近白，带一点封面色调） */
    fun baseText(ctx: RenderContext): Int =
        VisualizerMath.towardWhite(ctx.palette.background, 0.88f).toArgb()

    /** Folia `theme.accentColor` —— 高亮 / 激活文字色 */
    fun accent(ctx: RenderContext): Int = ctx.palette.accent.toArgb()

    /** Folia `theme.secondaryColor` —— 次级强调色 */
    fun secondary(ctx: RenderContext): Int = ctx.palette.secondary.toArgb()

    /**
     * Folia `resolveWordColor(word.text, theme.wordColors, theme.accentColor)`
     * 的等价物：本仓**没有** `theme.wordColors`（逐词关键词配色表）⇒
     * 一律回落到 [accent]，即「所有词同一个高亮色」。
     *
     * ⚠️ 这是与上游的**可见差异**（Folia 会按词文本哈希出不同的高亮色）。
     */
    fun wordAccent(ctx: RenderContext, wordText: String): Int = accent(ctx)
}
