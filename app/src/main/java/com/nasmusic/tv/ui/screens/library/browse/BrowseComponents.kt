package com.nasmusic.tv.ui.screens.library.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.components.common.SourceBadge
import com.nasmusic.tv.ui.screens.LibraryTab
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.LocalHighContrast
import com.nasmusic.tv.ui.theme.LocalPhoneCompact
import com.nasmusic.tv.ui.theme.HighContrastColors
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.PinyinUtils

/**
 * R-3 拆分：NAS 曲库浏览子包共享组件（原 LibraryScreen.kt 私有组件迁出并放开可见性）。
 *
 * v2.36.0：`adaptiveColumns()` 已上移至 `ui/components/CommonComponents.kt`（与
 * `songGridColumns()` 并列，改为 `UiMode + widthDp` 双输入），本文件及同包调用点
 * 一律 `import com.nasmusic.tv.ui.components.adaptiveColumns`。
 */

/**
 * 曲库卡片外壳 —— **唯一委托点**。
 *
 * 专辑 / 艺术家 / 流派 / 年代四种块共用同一个参数块（形状、聚焦缩放、时长、三态配色）。
 * ⛔ 不要在卡片文件里重写这些参数 —— 手抄第二份就是漂移的开始。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun MediaCardShell(
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    content: @Composable () -> Unit
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = BrowseTokens.cardShape,
        focusedScale = BrowseTokens.focusScale,
        animationDurationMs = BrowseTokens.animMs,
        containerColor = NasMusicColors.Surface,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = BrowseTokens.focusedContainerAlpha),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary,
        pressedContainerColor = NasMusicColors.Background,
        focusRequester = focusRequester,
        content = content
    )
}

/**
 * 封面右上角数量徽标 —— 专辑与艺术家共用。
 *
 * 此前艺术家把「N首」写在名字下方的正文行里，专辑写在封面右上角徽标里，
 * 两种块并排时读起来不是同一种组件；数量口径统一放徽标位。
 * 字号与来源标签（`SourceBadge`）同为 caption 档，保证同一角落两种徽标等大。
 */
@Composable
internal fun BoxScope.CoverCountBadge(count: Int) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(BrowseTokens.badgeInset)
            .background(NasMusicColors.Primary.copy(alpha = BrowseTokens.badgeFillAlpha), shape = BrowseTokens.countBadgeShape)
            .padding(horizontal = BrowseTokens.badgeHPadding, vertical = BrowseTokens.badgeVPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.library_song_count_short, count),
            color = NasMusicColors.OnPrimary,
            fontSize = FontSize.caption()
        )
    }
}

/** A-Z 分组标题（字母 + 分隔线）—— 专辑与艺术家网格共用，两处曾逐行重复。 */
@Composable
internal fun LetterGroupHeader(letter: Char) {
    Column {
        Text(
            text = letter.toString(),
            color = NasMusicColors.Primary,
            fontSize = FontSize.button(),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(
                start = BrowseTokens.headerLetterStart,
                top = BrowseTokens.headerLetterTop,
                bottom = BrowseTokens.headerLetterBottom
            )
        )
        val dividerThickness = if (LocalHighContrast.current) 2.dp else 1.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(dividerThickness)
                .background(if (LocalHighContrast.current) HighContrastColors.BorderStrong else NasMusicColors.Border)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    onPlay: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    MediaCardShell(onClick = onClick, focusRequester = focusRequester) {
        Column(modifier = Modifier.fillMaxWidth().padding(BrowseTokens.cardPadding)) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(BrowseTokens.coverShape).background(NasMusicColors.SurfaceVariant)
            ) {
                if (!album.coverUrl.isNullOrBlank()) {
                    AsyncImage(model = album.coverUrl, contentDescription = album.name, modifier = Modifier.fillMaxSize())
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "♪", color = LocalFocusableContentColor.current, fontSize = FontSize.displayLarge())
                    }
                }
                // Task 9: 右上角来源徽标（走公共 SourceBadge）；无来源时同位置显示歌曲数
                if (album.sourceType != null) {
                    SourceBadge(
                        source = album.sourceType,
                        modifier = Modifier.align(Alignment.TopEnd).padding(BrowseTokens.badgeInset)
                    )
                } else {
                    CoverCountBadge(album.songCount)
                }
            }
            Spacer(modifier = Modifier.height(BrowseTokens.coverTextGap))
            Text(text = album.name, color = NasMusicColors.TextPrimary, fontSize = FontSize.body(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            // Task 9: 年份 + 流派行
            val infoText = buildString {
                if (album.year != null) append(album.year.toString())
                if (!album.genre.isNullOrBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(album.genre)
                }
            }
            if (infoText.isNotEmpty()) {
                Text(text = infoText, color = NasMusicColors.TextSecondary, fontSize = FontSize.caption(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // ⛔ 不再画展示型播放文案：整卡进详情，播放统一走详情页的「播放全部」。
                // TV 不在可聚焦卡内再塞第二个焦点靶，避免焦点顺序与门禁双份外壳参数漂移。
                Text(text = album.artist.ifBlank { "—" }, color = LocalFocusableContentColor.current, fontSize = FontSize.small(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * 艺术家卡片 —— 与 [AlbumCard] 同结构：方形封面 + 右上角数量徽标 + 左对齐三行文字。
 *
 * 行的语义对应关系：标题=艺术家名，副信息行=`N专辑`（专辑卡在同类位置是「年份 · 流派」），
 * 末行=主要流派（专辑卡同类位置是艺术家名）。整卡进详情，播放统一走详情页，卡内不放第二焦点靶。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ArtistCard(
    artist: String,
    coverUrl: String? = null,
    songCount: Int,
    albumCount: Int = 0,
    primaryGenre: String? = null,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    MediaCardShell(onClick = onClick, focusRequester = focusRequester) {
        Column(modifier = Modifier.fillMaxWidth().padding(BrowseTokens.cardPadding)) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(BrowseTokens.coverShape).background(NasMusicColors.SurfaceVariant)
            ) {
                if (!coverUrl.isNullOrBlank()) {
                    AsyncImage(model = coverUrl, contentDescription = artist, modifier = Modifier.fillMaxSize())
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = artist.firstOrNull()?.uppercase() ?: "?",
                            color = LocalFocusableContentColor.current,
                            fontSize = FontSize.displayLarge()
                        )
                    }
                }
                CoverCountBadge(songCount)
            }
            Spacer(modifier = Modifier.height(BrowseTokens.coverTextGap))
            Text(text = artist, color = NasMusicColors.TextPrimary, fontSize = FontSize.body(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            // Task 10: 专辑数（与专辑卡副信息行同位同档）
            if (albumCount > 0) {
                Text(
                    text = stringResource(R.string.library_album_count_short, albumCount),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.caption(),
                    maxLines = 1
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Task 10: 主要流派（与专辑卡末行「艺术家名」同位）
                // ⛔ 不画"▶播放"字样：整卡进详情、播放统一走详情页的「播放全部」（与 AlbumCard 同口径）。
                // 原实现让 onPlay 只作显示判据、文本却非独立焦点靶，点它会落回卡片 onClick 进详情 = 误导。
                Text(
                    text = primaryGenre ?: "—",
                    color = LocalFocusableContentColor.current,
                    fontSize = FontSize.small(),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ButtonChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        focusedScale = 1.08f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Primary,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
        contentColor = NasMusicColors.OnPrimary,
        focusedContentColor = NasMusicColors.OnPrimary,
        pressedScale = 0.95f
    ) {
        Text(text = text, color = NasMusicColors.OnPrimary, fontSize = FontSize.button(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/**
 * 库内容为空时的提示组件
 *
 * 根据连接状态和可用数据源显示不同提示：
 * - 未连接 NAS 且无任何数据源 → 提示连接服务器或扫描本地音乐
 * - 已连接 NAS 但库为空 → 提示库为空
 * - **加载失败（v2.39.3）** → 显示具体原因 + 「重试」按钮，而不是笼统的「暂无歌曲」
 *
 * @param errorText 非空时优先展示该错误（已本地化，如「服务端返回错误：code=1000xx」）
 * @param onRetry 非空时展示「重试」按钮；通常由 ViewModel 的 retry 闭包提供
 * @param errorTitleRes 错误态标题文案（SONGS 与 ARTISTS 各用自己的，避免艺术家失败显示「歌曲加载失败」）
 */
@Composable
internal fun EmptyHint(
    isConnected: Boolean,
    tab: LibraryTab = LibraryTab.ALBUMS,
    errorText: String? = null,
    onRetry: (() -> Unit)? = null,
    errorTitleRes: Int = R.string.library_songs_load_failed
) {
    // v2.39.3：错误态优先于「库为空」——服务端有 1541 首却拉到 0 条时，
    // 用户需要看到原因（code/msg），否则完全无从反馈。
    val hasError = !errorText.isNullOrBlank()
    val (icon, title, subtitle) = when {
        hasError -> Triple(
            Icons.Default.Warning,
            stringResource(errorTitleRes, errorText!!),
            ""
        )
        !isConnected -> Triple(
            Icons.Default.CloudOff,
            stringResource(R.string.common_not_connected),
            stringResource(R.string.library_connect_or_local_hint)
        )
        tab == LibraryTab.ARTISTS -> Triple(
            Icons.Default.Person,
            stringResource(R.string.library_empty_artists),
            stringResource(R.string.library_empty_artists_hint)
        )
        tab == LibraryTab.SONGS -> Triple(
            Icons.Default.MusicNote,
            stringResource(R.string.library_empty_songs),
            stringResource(R.string.library_empty_songs_hint)
        )
        else -> Triple(
            Icons.Default.Album,
            stringResource(R.string.library_empty_albums),
            stringResource(R.string.library_empty_albums_hint)
        )
    }
    val accent = if (hasError) NasMusicColors.Danger else NasMusicColors.TextSecondary
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = if (hasError) NasMusicColors.Danger else NasMusicColors.TextSecondary.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                color = accent,
                fontSize = FontSize.title(),
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    color = NasMusicColors.TextSecondary.copy(alpha = 0.7f),
                    fontSize = FontSize.button()
                )
            }
            if (hasError && onRetry != null) {
                Spacer(modifier = Modifier.height(20.dp))
                FocusableSurface(
                    onClick = onRetry,
                    shape = RoundedCornerShape(10.dp),
                    focusedScale = 1.06f,
                    animationDurationMs = 200,
                    containerColor = NasMusicColors.Surface,
                    focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                    contentColor = NasMusicColors.TextPrimary,
                    focusedContentColor = NasMusicColors.Primary,
                    pressedContainerColor = NasMusicColors.Background
                ) {
                    Text(
                        text = stringResource(R.string.library_songs_retry),
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                        fontSize = FontSize.button()
                    )
                }
            }
        }
    }
}

/**
 * 侧边 A-Z 索引条
 *
 * - 触摸：拖拽选择字母，松手回弹
 * - 遥控器：聚焦后上下键选择，确认键跳转
 * - currentLetter：当前可见区域的首字母，高亮显示
 * - activeLetters：实际有数据的字母集合，其余灰显
 * - onLetterSelect：点击/拖拽到某字母时回调，调用方负责 scroll
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SideLetterIndex(
    currentLetter: Char?,
    activeLetters: Set<Char>,
    onLetterSelect: (Char) -> Unit,
    contentFocusRequester: FocusRequester,
    letterFocusRequester: FocusRequester = remember { FocusRequester() },
    modifier: Modifier = Modifier
) {
    val allLetters = remember { PinyinUtils.getAllGroupLetters() }
    var focusedLetter by remember { mutableStateOf<Char?>(null) }
    val focusManager = LocalFocusManager.current

    // 手机横屏时字母排不下，用更小尺寸 + 可滚动
    val isPhone = LocalPhoneCompact.current
    val letterSize = if (isPhone) 14.dp else 20.dp
    val letterFontSize = if (isPhone) 7.sp else 10.sp
    val scrollState = rememberScrollState()

    // 固定每项高度，使触摸映射精确（不再依赖 SpaceBetween 坐标计算）
    val itemHeight = letterSize

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(letterSize + 8.dp)
            .padding(end = 4.dp)
            .then(if (isPhone) Modifier.verticalScroll(scrollState) else Modifier)
            .focusRequester(letterFocusRequester)
            .focusTarget()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val current = focusedLetter ?: currentLetter ?: return@onKeyEvent false
                val idx = allLetters.indexOf(current)
                when (event.key) {
                    Key.DirectionUp -> {
                        val prev = allLetters.getOrNull(idx - 1)
                        if (prev != null) { focusedLetter = prev; onLetterSelect(prev) }
                        true
                    }
                    Key.DirectionDown -> {
                        val next = allLetters.getOrNull(idx + 1)
                        if (next != null) { focusedLetter = next; onLetterSelect(next) }
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        focusedLetter?.let(onLetterSelect)
                        true
                    }
                    Key.DirectionLeft -> {
                        // 从字母索引条返回左侧内容区
                        // firstItemFocusRequester 绑定在 index 1，列表滚动后该 item 可能不在视口内
                        // （未组合 → requestFocus 抛 IllegalStateException），用 runCatching 防崩溃；
                        // 失败时用 moveFocus(Left) 回退到最近的可聚焦 grid item
                        val focused = runCatching { contentFocusRequester.requestFocus() }.isSuccess
                        if (!focused) focusManager.moveFocus(FocusDirection.Left)
                        true
                    }
                    else -> false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        allLetters.forEach { letter ->
            val isActive = letter in activeLetters
            val isCurrent = letter == (focusedLetter ?: currentLetter)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .clickable {
                        focusedLetter = letter
                        onLetterSelect(letter)
                    }
                    .then(
                        if (isCurrent) Modifier.background(
                            NasMusicColors.Primary.copy(alpha = 0.3f),
                            RoundedCornerShape(4.dp)
                        ) else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letter.toString(),
                    color = when {
                        isCurrent -> NasMusicColors.Primary
                        isActive -> NasMusicColors.TextSecondary
                        else -> NasMusicColors.TextSecondary.copy(alpha = 0.3f)
                    },
                    fontSize = letterFontSize,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
