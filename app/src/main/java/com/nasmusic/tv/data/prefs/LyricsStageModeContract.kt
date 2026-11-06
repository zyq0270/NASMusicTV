package com.nasmusic.tv.data.prefs

/**
 * E53「歌词舞台」子模式序号的取值契约。
 *
 * 与 [QianliColorVersion] 同构：这是一个**纯数值**的显示偏好，
 * 放在 `data/prefs` 里，避免 `data` 层反向依赖 `visualizer` 层的枚举。
 *
 * 取值与 `com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricsStageMode` 的
 * 声明顺序一一对应：
 *  0 歌词点阵 / 1 逐字行光 / 2 全屏纵览 / 3 倾斜律动 / 4 分栏引导
 */
object LyricsStageModeContract {

    /** 子模式数量 */
    const val COUNT = 5

    /** 合法序号区间 */
    val RANGE = 0 until COUNT

    /** 默认子模式：歌词点阵 */
    const val DEFAULT = 0

    /**
     * 钳制到合法区间。
     *
     * 偏好值可能来自旧版本、手改的 prefs 文件或备份导入 ⇒ 绝不把非法值喂给渲染器。
     */
    fun normalize(value: Int): Int = if (value in RANGE) value else DEFAULT
}
