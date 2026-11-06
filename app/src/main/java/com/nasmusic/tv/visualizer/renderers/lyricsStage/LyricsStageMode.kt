package com.nasmusic.tv.visualizer.renderers.lyricsStage

import androidx.annotation.StringRes
import com.nasmusic.tv.R

/**
 * 歌词舞台（LYRICS_STAGE）的子模式枚举。
 *
 * 子模式**仅手动切换**（上下键 / 手势上下滑动）：
 * 不自动定时切换、不随歌曲切换、不随段落或副歌切换。
 * 选择持久化到 `AppPreferences.lyricsStageModeOrdinal`。
 *
 * @param displayNameRes 子模式的显示名（⛔ 不硬编码中文 —— 走字符串资源，
 *   与 `visualizer_theme_*` 的既有做法一致；英文界面显示英文名）。
 */
enum class LyricsStageMode(@StringRes val displayNameRes: Int) {
    /** 子模式 0：歌词点阵（NASMusicTV E23 原创，复用现有效果） */
    DOT_MATRIX(R.string.lyrics_stage_mode_dot_matrix),

    /** 子模式 1：逐字行光（移植自 Folia `classic`） */
    GLOW_LINE(R.string.lyrics_stage_mode_glow_line),

    /** 子模式 2：全屏纵览（移植自 Folia `fume`，需要 HIGH 画质） */
    FULL_VIEW(R.string.lyrics_stage_mode_full_view),

    /** 子模式 3：倾斜律动（移植自 Folia `tilt`） */
    TILT_PULSE(R.string.lyrics_stage_mode_tilt_pulse),

    /** 子模式 4：分栏引导（移植自 Folia `partita`） */
    PARTITA(R.string.lyrics_stage_mode_partita),
    ;

    companion object {
        val Default: LyricsStageMode = DOT_MATRIX

        fun fromOrdinal(ord: Int): LyricsStageMode =
            entries.getOrElse(ord) { Default }

        /** 下一个子模式（循环） */
        fun next(current: LyricsStageMode): LyricsStageMode =
            entries[(current.ordinal + 1) % entries.size]

        /** 上一个子模式（循环） */
        fun prev(current: LyricsStageMode): LyricsStageMode =
            entries[(current.ordinal - 1 + entries.size) % entries.size]
    }
}
