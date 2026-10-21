package com.nasmusic.tv.ui.screens.library.browse

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 曲库浏览卡片的尺寸与动效令牌（专辑 / 艺术家 / 流派 / 年代 共用）。
 *
 * 存在理由：专辑块与艺术家块原本是两份手抄参数 —— 卡片内边距 8/12dp、网格间距 12/10dp、
 * 文字左对齐/居中、歌曲数量的位置（封面右上角徽标 vs 名字下方正文行）各不相同，
 * 同一个页面里两种块看起来像来自两个 App。改任何一处只动这里，⛔ 不要在卡片文件里写字面量。
 *
 * 两级委托点：
 * - `MediaCardShell` —— 外壳参数块（形状 / 聚焦缩放 / 时长 / 三态配色）只写在那一个 composable 里
 * - `CoverCountBadge` —— 封面右上角数量徽标只写在那一个 composable 里
 *
 * 徽标有两种形状且不可互换：`countBadgeShape` 是**度量胶囊**（数字），
 * 来源标签走公共组件 `SourceBadge` 的 4dp 方角**标签**（文字），语义不同、不是漂移。
 */
internal object BrowseTokens {
    val cardShape = RoundedCornerShape(10.dp)
    val coverShape = RoundedCornerShape(8.dp)
    val countBadgeShape = RoundedCornerShape(12.dp)

    val cardPadding = 8.dp
    val coverTextGap = 4.dp
    val badgeInset = 4.dp
    val badgeHPadding = 6.dp
    val badgeVPadding = 2.dp
    val inlineGap = 4.dp

    val gridGap = 12.dp
    val headerCountGap = 12.dp
    val headerLetterStart = 4.dp
    val headerLetterTop = 8.dp
    val headerLetterBottom = 4.dp

    val focusScale = 1.06f
    const val animMs = 200
    const val focusedContainerAlpha = 0.2f
    const val badgeFillAlpha = 0.95f
}
