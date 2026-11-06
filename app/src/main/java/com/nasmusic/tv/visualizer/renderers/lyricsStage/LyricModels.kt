package com.nasmusic.tv.visualizer.renderers.lyricsStage

/**
 * 歌词舞台（LYRICS_STAGE）内部数据模型。
 *
 * 移植自 Folia（AGPL-3.0）: src/types.ts 的 `Word` / `Line` 类型，
 * 以及 src/utils/lyrics/renderHints.ts 的 `LineRenderHints`。
 *
 * Folia 版本: 0.5.27-a16525c
 *
 * 这些类型**不是** RenderContext 的字段 —— RenderContext 只传基本类型，
 * 渲染器在 onEnter 时自行解析构造。
 */

/** 移植 Folia src/types.ts `Word` */
data class LyricWord(
    /** 词文本 */
    val text: String,
    /** 开始时间（秒） */
    val startTime: Double,
    /** 结束时间（秒） */
    val endTime: Double,
    /** 音节（逐字时间戳）；为空时按词均分 */
    val syllables: List<Syllable>? = null,
)

/** 移植 Folia src/types.ts `Syllable` */
data class Syllable(
    val text: String,
    val startTime: Double,
    val endTime: Double,
)

/** 移植 Folia src/types.ts `Line` */
data class LyricLine(
    val fullText: String,
    val startTime: Double,
    val endTime: Double,
    val words: List<LyricWord>,
    val renderHints: LineRenderHints? = null,
    /** 副歌标记 —— 触发合唱涟漪 */
    val isChorus: Boolean = false,
)

/** 移植 Folia src/utils/lyrics/renderHints.ts `LineRenderHints` */
data class LineRenderHints(
    val rawDuration: Double,
    /** "normal" | "short" | "micro" */
    val timingClass: String,
    val renderEndTime: Double,
    /** "normal" | "fast" | "none" */
    val lineTransitionMode: String,
    /** "normal" | "fast" | "instant" */
    val wordRevealMode: String,
)

/** 移植 Folia src/utils/lyrics/graphemeTiming.ts `GraphemeTiming` */
data class GraphemeTiming(
    val char: String,
    val startTime: Double,
    val endTime: Double,
    val wordIndex: Int? = null,
)
