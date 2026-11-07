package com.nasmusic.tv.visualizer

import androidx.compose.runtime.State

/**
 * 效果对**舞台叠加层**（前景层）的诉求。
 *
 * ## 为什么要有它
 *
 * 舞台（`ui/components/VisualizerStage.kt`）本来**硬编码**了逐主题的特例
 * （「怀旧」要 4:3 黑边 + 反色文字、「歌词舞台」不要顶部歌词条）。
 * ⇒ **新增一套效果就得改舞台** —— 而舞台文件被本机私有分支的覆盖层遮蔽
 * （`app/src/e51-overlay/…`），改它会让本地构建与 CI 行为分叉、还要重新同步覆盖层。
 *
 * ⇒ 改成**效果自己声明**（[VisualizerRenderer.furniture]）：舞台只按声明渲染，
 *    **默认「全显示」** ⇒ 新增效果**永远不需要碰舞台**。
 *
 * ## 字段语义
 *
 * - [lyricTopBar] / [songInfo]：舞台要不要画这两样叠加层。
 * - [contentAspect]：画面**内容区**的宽高比（`null` = 满屏、没有内容区）。
 * - [contentIsLight]：内容区底色**浅** ⇒ 落在内容区里的叠加层改用**深色**墨。
 */
data class EffectFurniture(
    /** 顶部歌词提示行。 */
    val lyricTopBar: Boolean = true,
    /** 左下角歌曲信息（歌名 + 艺术家）。 */
    val songInfo: Boolean = true,
    /**
     * 画面**内容区**的宽高比（null = 满屏、无内容区）。
     *
     * ⛔ 契约：非 null ⇒ 本效果**自己**把内容画在居中的该比例区域里、两侧留**深色边**
     *    ⇒ 舞台据此把叠加层让开两侧黑边，并可把内容区那一侧的字换成深色墨。
     */
    val contentAspect: Float? = null,
    /**
     * 内容区底色**浅** ⇒ 落在内容区里的叠加层用**深色**墨。
     *
     * ⛔ 仅当 [contentAspect] 非 null 时有意义。
     */
    val contentIsLight: Boolean = false,
) {
    companion object {
        /** 「全显示」的默认家具（没有任何效果特别声明时就用它）。 */
        val DEFAULT = EffectFurniture()

        /**
         * 全局**只读**默认。
         *
         * ⚠️ 必须是**只读**的：共享的可变默认会让一个渲染器改坏其它所有效果的家具。
         *    ⇒ 这里用一个**匿名 [State]** 实现（`androidx.compose.runtime.State` 只有
         *    `value` 一个成员）⇒ 拿不到 setter，也拿不到底层可变状态。
         *
         * ⚠️ 实现类要改就**自己持一份** `mutableStateOf` 并覆写 `VisualizerRenderer.furniture`。
         */
        val DEFAULT_STATE: State<EffectFurniture> = object : State<EffectFurniture> {
            override val value: EffectFurniture get() = DEFAULT
        }
    }
}
