package com.nasmusic.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.LyricsHighlightMode
import com.nasmusic.tv.ui.theme.LyricsTheme
import com.nasmusic.tv.ui.theme.LocalLyricsTheme
import com.nasmusic.tv.ui.theme.NasMusicBrushes
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.delay

/** 拖拽跳转指示条的纵向位置（顶部 fade mask 下方） */
private val INDICATOR_OFFSET_Y = 88.dp

/**
 * 歌词视图
 * 支持按当前播放时间滚动显示歌词行
 * 支持逐行/逐字高亮模式切换
 * 使用 TV 标准 Surface 焦点管理，避免与焦点系统冲突
 *
 * @param fadeMaskColor 上下渐隐遮罩的底色。传 null（默认）沿用主题的深蓝底色
 *   [NasMusicBrushes.topFadeMask] / [NasMusicBrushes.bottomFadeMask]；
 *   沉浸播放页为纯黑底，传黑色可让渐隐与背景无缝融合。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LyricsView(
    lyrics: Lyrics?,
    currentTimeMs: State<Long>,
    modifier: Modifier = Modifier,
    highlightMode: LyricsHighlightMode = LyricsHighlightMode.LINE_BY_LINE,
    isPlaying: Boolean = true,
    fontSizeMultiplier: Float = 1.0f,
    fadeMaskColor: Color? = null,
    onSeekToLine: ((Long) -> Unit)? = null,
    longPressSeekEnabled: Boolean = false
) {
    // 手机紧凑模式：恢复原始密度，保持歌词字号不被全局缩放（用户独立调节）
    if (com.nasmusic.tv.ui.theme.LocalPhoneCompact.current) {
        val baseDensity = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                density = baseDensity.density * com.nasmusic.tv.ui.theme.CompactSizes.LYRICS_RECOVER_SCALE,
                fontScale = baseDensity.fontScale
            )
        ) {
            LyricsViewInner(lyrics, currentTimeMs, modifier, highlightMode, isPlaying, fontSizeMultiplier, fadeMaskColor, onSeekToLine, longPressSeekEnabled)
        }
    } else {
        LyricsViewInner(lyrics, currentTimeMs, modifier, highlightMode, isPlaying, fontSizeMultiplier, fadeMaskColor, onSeekToLine, longPressSeekEnabled)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LyricsViewInner(
    lyrics: Lyrics?,
    currentTimeMsState: State<Long>,
    modifier: Modifier = Modifier,
    highlightMode: LyricsHighlightMode = LyricsHighlightMode.LINE_BY_LINE,
    isPlaying: Boolean = true,
    fontSizeMultiplier: Float = 1.0f,
    fadeMaskColor: Color? = null,
    onSeekToLine: ((Long) -> Unit)? = null,
    longPressSeekEnabled: Boolean = false
) {
    // S06-01：进度以 State 下沉到叶子，只有本组件读 .value ⇒
    // 父级（NowPlayingScreen 等）不再是订阅者，1Hz tick 不再整页重组。
    val currentTimeMs = currentTimeMsState.value
    if (lyrics == null || lyrics.isEmpty) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = NasMusicColors.TextSecondary,
                    modifier = Modifier.size(64.dp).padding(bottom = 16.dp)
                )
                Text(
                    text = stringResource(R.string.player_no_lyrics),
                    style = LocalLyricsTheme.current.normalLine,
                    color = NasMusicColors.TextSecondary
                )
            }
        }
        return
    }

    val listState = rememberLazyListState()

    // 空白带修复（2026-09-22 真机截图分析）：首尾 120dp spacer 在手机横屏
    //（高度 ~411dp）上吃掉近 1/3 视口——横屏收敛为 40dp；TV 竖向空间充裕保持 120。
    val isPhoneLandscape = com.nasmusic.tv.ui.theme.LocalUiMode.current ==
        com.nasmusic.tv.ui.theme.UiMode.PhoneLandscape
    val edgeSpacer = if (isPhoneLandscape) 16.dp else 120.dp

    // —— 手势拖拽跳转（2026-09-22 新增，手机端横竖屏）：拖动浏览歌词时顶部出现
    //    虚线指示条，松手后跳到虚线处歌词的起始时间播放。isDragSeeking 期间
    //    暂停自动跟行；tap（未滚动）不触发，普通浏览不受影响。
    var isDragSeeking by remember { mutableStateOf(false) }
    // 修复（17:51 真机反馈）：LazyColumn 的 scrollable 发出的是 DragInteraction
    // 而非 PressInteraction——collectIsPressedAsState 永远收不到事件，
    // isDragSeeking 不会激活，拖拽跳转整体无效。改 collectIsDraggedAsState。
    val listDragged by listState.interactionSource.collectIsDraggedAsState()

    // 逐字模式下使用本地高频时钟插值，平滑过渡（避免 1000ms progress 导致逐字跳动）
    // 基于上次已知 currentTimeMs（1秒锚点）+ 实际流逝时间估算当前进度
    //
    // ⚠️ **`lyricTickMs` 是本组件唯一的「可观察时间源」**（2026-11-12 修复）：
    //    当前行索引的 `derivedStateOf` 只能读它（读普通参数 `currentTimeMs` 会捕获
    //    首次组合的旧值 ⇒ 逐行模式永不跳行）。⇒ 非逐字模式**必须**在这里把它
    //    同步成当前播放进度，否则派生状态看不到任何变化。
    var lyricTickMs by remember(lyrics) { mutableLongStateOf(currentTimeMs) }
    LaunchedEffect(currentTimeMs, isPlaying, highlightMode, lyrics) {
        if (highlightMode == LyricsHighlightMode.WORD_BY_WORD && isPlaying) {
            // 记录锚点：当前已知 progress + 系统时间
            var anchorProgress = currentTimeMs
            var anchorSystemMs = System.currentTimeMillis()
            lyricTickMs = anchorProgress
            while (true) {
                delay(50)  // 50ms 刷新（20fps）
                val elapsed = System.currentTimeMillis() - anchorSystemMs
                lyricTickMs = anchorProgress + elapsed
                // currentTimeMs 更新时（每秒一次），重新校准锚点
                if (currentTimeMs != anchorProgress) {
                    anchorProgress = currentTimeMs
                    anchorSystemMs = System.currentTimeMillis()
                }
            }
        } else {
            // 非逐字模式或暂停：直接跟随当前播放进度
            // ⚠️ 这个 else 分支是**逐行模式能跟行的唯一来源** —— 别删。
            lyricTickMs = currentTimeMs
        }
    }

    // 当前歌词行索引。
    //
    // ⚠️ M36：原实现把「高频时钟」`lyricTickMs` **在本层解包读取**（先算 effectiveTimeMs，
    // 再 indexOfFirst）⇒ 20fps 的 tick 每帧都会把整个 LyricsView（含 LazyColumn 里所有
    // 可见 item 的参数）重新求值一遍。
    // 现在改为 `derivedStateOf`：快照只在**派生结果变化**（换行）时才通知重组，
    // tick 本身只与这个派生状态比较，不触发本层重组；逐字进度则下沉到 item 内部读（见下）。
    // 顺带把 O(N) 的 `indexOfFirst` 换成二分（`findCurrentLineIndex`，与 K 歌页共用）。
    //
    // ⛔⛔ **2026-11-12 真机回归修复：派生状态只能读「可观察的 State」**
    //
    // 症状（所有展示歌词的页面）：**逐字模式歌词能正确走，逐行模式下歌词不动、不跳当前行**。
    //
    // 根因：`derivedStateOf` 的 lambda **只在 remember 的 key 变化时重建一次**，
    // 它捕获的是**首次组合时**那份**普通参数** `currentTimeMs` ⇒
    // 之后每秒更新的播放进度**传不进去** ⇒ 派生值永不重算 ⇒ 当前行永远停在第一行。
    // 逐字模式之所以正常，是因为它读的是 `lyricTickMs`（**State** ⇒ 可观察）。
    //
    // ⚠️ 这与本仓 2026-09-25 审查修复 #16 是**同一类坑**（`SearchTab` / `SongList`）：
    // 「remember 无 key 时 derivedStateOf 捕获首次组合的参数快照」。
    //
    // ⇒ 修法：**两种模式都读 `lyricTickMs`**（唯一的可观察时间源）。
    //    逐行模式下它由上面 LaunchedEffect 的 else 分支随 `currentTimeMs` 一起被写入
    //    （= 当前播放进度），逐字模式下它是 20fps 插值时钟 ⇒ 两者都可观察。
    val currentIndex by remember(lyrics) {
        derivedStateOf {
            findCurrentLineIndex(lyrics.lines, lyricTickMs)
        }
    }

    LaunchedEffect(currentIndex, isDragSeeking) {
        // 拖拽跳转中暂停自动跟行；松手跳转后 currentIndex 变化会自然滚回当前行
        if (currentIndex >= 0 && !isDragSeeking) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    // 拖动即进入（TV/横屏默认）。手机竖屏（longPressSeekEnabled）改长按进入：
    // 普通拖动留给「纵向浏览 + 横向 pager 切页」，避免轴向手势冲突。
    LaunchedEffect(listDragged, listState.isScrollInProgress, longPressSeekEnabled) {
        if (!longPressSeekEnabled && listDragged && listState.isScrollInProgress && !isDragSeeking) {
            isDragSeeking = true
        }
    }

    // 拖拽中：计算虚线指示处的目标行（LazyColumn item index = 行 index + 1，头部有 spacer）
    //
    // L23：原先这是「组合期直接给 State 赋值 + 组合期读 listState.layoutInfo」——
    // 组合期写状态是 Compose 明令禁止的副作用（每次重组都写一次，还可能 写→重组→再写 抖动），
    // 而组合期读 layoutInfo 也会让**每次滚动帧**都触发本组件重组。
    // 改成 derivedStateOf 派生值：只在 isDragSeeking / layoutInfo 变化时重算，
    // 组合期只读不写；事件回调（松手跳转）通过 seekTargetLine 读当前派生值。
    val indicatorDensity = androidx.compose.ui.platform.LocalDensity.current
    val indicatorOffsetPx = remember(indicatorDensity) {
        with(indicatorDensity) { INDICATOR_OFFSET_Y.toPx() }.toInt()
    }
    // key 带上 indicatorOffsetPx：density 变了（分屏/字体缩放）偏移量会变，派生值必须跟着重建
    val seekTargetLineState = remember(indicatorOffsetPx) {
        derivedStateOf {
            if (!isDragSeeking) {
                -1
            } else {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                    item.index in 1..lyrics.lines.size &&
                        item.offset <= indicatorOffsetPx && item.offset + item.size > indicatorOffsetPx
                }?.let { it.index - 1 } ?: -1
            }
        }
    }
    val seekTargetLine by seekTargetLineState

    // 松手（拖动即进入模式）：跳到目标行起始时间播放（DragInteraction.Stop = 手指抬起）。
    // 长按模式的松手由下方 detectDragGesturesAfterLongPress 的 onDragEnd 处理。
    LaunchedEffect(listDragged) {
        if (!longPressSeekEnabled && !listDragged && isDragSeeking) {
            // ⚠️ 顺序不能反：派生值在 isDragSeeking=false 之后立即变成 -1，
            //    必须先读目标行再关标志位。
            val target = seekTargetLine
            isDragSeeking = false
            if (target in lyrics.lines.indices) {
                onSeekToLine?.invoke(lyrics.lines[target].time)
            }
        }
    }

    // Box 叠加：下方是滚动歌词，上下各一层 fade mask
    // 沉浸播放页为纯黑底 → 用同色遮罩，渐隐边缘与背景无缝
    // Brush 是 composable getter，必须先在组合期取出，再作为 remember 的输入；
    // key 带 palette，主题切换时重新生成（否则浅色下沿用深色渐隐底）。
    val palette = com.nasmusic.tv.ui.theme.LocalNasPalette.current
    val themeTopFade = NasMusicBrushes.topFadeMask
    val themeBottomFade = NasMusicBrushes.bottomFadeMask
    val topFadeMask = remember(fadeMaskColor, palette) {
        if (fadeMaskColor != null) {
            Brush.verticalGradient(colors = listOf(fadeMaskColor, Color.Transparent))
        } else {
            themeTopFade
        }
    }
    val bottomFadeMask = remember(fadeMaskColor, palette) {
        if (fadeMaskColor != null) {
            Brush.verticalGradient(colors = listOf(Color.Transparent, fadeMaskColor))
        } else {
            themeBottomFade
        }
    }
    val seekLineColor = NasMusicColors.Primary
    Box(
        modifier = modifier.fillMaxSize().then(
            if (longPressSeekEnabled) {
                Modifier.pointerInput(longPressSeekEnabled) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            // 长按 → 进入跳转模式
                            isDragSeeking = true
                        },
                        onDrag = { change, dragAmount ->
                            // 跳转模式中拖动 = 滚动列表选行（手动驱动，禁用子列表滚动）
                            change.consume()
                            listState.dispatchRawDelta(-dragAmount.y)
                        },
                        onDragEnd = {
                            // 松手 → 跳到目标行起始时间播放并退出跳转模式
                            // ⚠️ 同 L23：先读派生值，再关 isDragSeeking
                            val target = seekTargetLine
                            isDragSeeking = false
                            if (target in lyrics.lines.indices) {
                                onSeekToLine?.invoke(lyrics.lines[target].time)
                            }
                        },
                        onDragCancel = {
                            isDragSeeking = false
                        }
                    )
                }
            } else {
                Modifier
            }
        )
    ) {
        // --- 滚动歌词列表（使用 TV 焦点管理，移除了与焦点冲突的 pointerInput）---
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            // 长按跳转模式下禁用列表自身滚动——拖动由外层 pointerInput 接管并
            // dispatchRawDelta 手动驱动，避免双驱动
            userScrollEnabled = !(longPressSeekEnabled && isDragSeeking),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = edgeSpacer))
            }
            // C31：补稳定 key。歌词是单曲静态列表、行内没有 per-item remember/LaunchedEffect，
            // 所以缺 key 目前不会「串行状态」，但一旦将来在行内加动画/手势就会错位；
            // key 里带 index 是为了防同一时间戳出现两行（重复 key 会直接崩）。
            itemsIndexed(lyrics.lines, key = { index, line -> "$index:${line.time}" }) { index, line ->
                val isCurrent = index == currentIndex
                val played = index < currentIndex
                val near = kotlin.math.abs(index - currentIndex) <= 1
                val isSeekTarget = isDragSeeking && index == seekTargetLine

                val textColor = when {
                    isSeekTarget -> NasMusicColors.Primary
                    isCurrent -> NasMusicColors.Primary
                    played -> NasMusicColors.TextSecondary.copy(alpha = 0.45f)
                    near -> NasMusicColors.TextPrimary
                    else -> NasMusicColors.TextSecondary
                }
                val fontSize = when {
                    isCurrent -> 40.sp * fontSizeMultiplier
                    near -> 28.sp * fontSizeMultiplier
                    else -> 22.sp * fontSizeMultiplier
                }

                // 逐字模式：与 KARAOKE 页一致，使用平滑双层裁剪推进（半个字粒度），
                // 而非逐字跳变。progress 由该行开始/结束时间按当前进度比例计算。
                if (isCurrent && highlightMode == LyricsHighlightMode.WORD_BY_WORD) {
                    val nextLineTime = if (index + 1 < lyrics.lines.size) {
                        lyrics.lines[index + 1].time
                    } else {
                        line.time + 3000L // 默认3秒
                    }
                    // M36：tick 在这里读 —— 于是 20fps 只让「当前这一行」的 item 重组。
                    // ⚠️ 读 `lyricTickMs`（State）而不是 `currentTimeMs`（普通参数）：
                    //    本行是**组合期**读的，普通参数也拿得到最新值，但统一成同一个
                    //    可观察时间源，避免同类「读到冻结快照」的坑再次出现。
                    val tickMs = lyricTickMs
                    val progress = lineProgress(line.time, nextLineTime, tickMs)
                    KaraokeLineText(
                        text = line.text,
                        progress = progress,
                        fontSize = 40.sp * fontSizeMultiplier,
                        textAlign = TextAlign.Center,
                        baseColor = textColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                vertical = if (isCurrent) 22.dp else 14.dp,
                                horizontal = 32.dp
                            )
                    )
                } else {
                    Text(
                        text = line.text,
                        color = textColor,
                        fontSize = fontSize,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                vertical = if (isCurrent) 22.dp else 14.dp,
                                horizontal = 32.dp
                            )
                    )
                }
            }
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = edgeSpacer))
            }
        }

        // —— 拖拽跳转指示：顶部虚线 + 目标行时间（松手跳到该行起始时间）——
        if (isDragSeeking) {
            val targetTime = lyrics.lines.getOrNull(seekTargetLine)?.time
            val timeLabel = if (targetTime != null && targetTime >= 0) {
                val m = (targetTime / 60000).toString().padStart(2, '0')
                val s = ((targetTime % 60000) / 1000).toString().padStart(2, '0')
                "$m:$s"
            } else {
                "—"
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = INDICATOR_OFFSET_Y - 14.dp)
            ) {
                Text(
                    text = timeLabel,
                    color = NasMusicColors.Primary,
                    style = LocalLyricsTheme.current.normalLine,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp)
                        .background(NasMusicColors.Surface.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                )
                Canvas(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(start = 88.dp, end = 24.dp)
                        .height(2.dp)
                        .fillMaxWidth()
                ) {
                    drawLine(
                        color = seekLineColor,
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width, size.height / 2),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                    )
                }
            }
        }

        // --- 顶部渐隐 mask ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .align(Alignment.TopCenter)
                .background(topFadeMask)
        )

        // --- 底部渐隐 mask ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .align(Alignment.BottomCenter)
                .background(bottomFadeMask)
        )
    }
}


