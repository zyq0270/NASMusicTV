package com.nasmusic.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.LyricsLine
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.data.prefs.QianliColorVersion
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.CoverPalette
import com.nasmusic.tv.visualizer.EffectFurniture
import com.nasmusic.tv.visualizer.FpsMeter
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.RendererSwapper
import com.nasmusic.tv.visualizer.VisualizerRenderer
import com.nasmusic.tv.visualizer.VisualizerRendererFactory
import com.nasmusic.tv.visualizer.qianli.QianliRenderStyle
import com.nasmusic.tv.visualizer.renderers.LyricsStageRenderer
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricsStageBridge
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricsStageMode
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricsStagePayload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 帧率读数的开关键（`Settings.Global`，只读）。
 *
 * 开：`adb shell settings put global nasmusic_fps 1` ⇒ 重进可视化即右上角显示当前效果帧率。
 * 关：`adb shell settings put global nasmusic_fps 0`（或直接删键）。
 *
 * 走系统全局设置而不是 App 设置页：release 包可用、零持久化 plumbing，
 * 且⛔ 不会在普通用户的播放器 UI 上留下常驻调试信息。
 */
private const val FPS_SETTING_KEY = "nasmusic_fps"

/**
 * 当前生效的渲染器 + 它**对应的请求主题**。
 *
 * ⛔ 存「请求主题」而不是 `renderer.theme` —— 工厂在未启用变体时会回落成别的渲染器
 *    （LOFI_CITY → CircularRingRenderer），那时 `renderer.theme != theme`，比主题会误判。
 */
private data class ActiveRenderer(
    val theme: VisualizerTheme,
    val renderer: VisualizerRenderer,
)

/**
 * 全屏可视化舞台（三层结构）。
 *
 * ```
 * ③ 前景层：顶部歌词 / 左下歌曲信息 / 底部指示器+控制栏
 * ② 效果层：Canvas —— VisualizerRenderer.draw(frame)
 * ① 背景层：纯暗色底（不叠加封面图，突出频谱本身）
 * ```
 *
 * 绘制循环用 `withFrameNanos` 驱动，**不走 Compose 重组**——
 * 频谱以 30fps 变化，若每帧重组会白白消耗性能。
 *
 * **T6 双缓冲配合**：绘制循环不重组，若直接传 `AudioFrame` 引用，
 * 整个动画期间将固定读同一实例——写端下一轮翻转后即开始写该实例，
 * 撕裂依旧。故 [frame] 为**每帧求值**的 provider：draw 开头捕获一次
 * 当前 front（1-writeIndex），绘制期间引用不变。
 */
@Composable
fun VisualizerStage(
    song: Song?,
    frame: () -> AudioFrame,
    /**
     * 当前播放位置（毫秒）—— **逐帧**读取的 provider。
     *
     * ⛔ **不要**用 `progressMs` 那个参数做逐帧动画：它是 **1 Hz** 的 StateFlow
     *    ⇒ 「逐字揭示 / 相机跟随 / 辉光扫过」会变成**每秒跳一次**（看起来就是卡顿）。
     */
    positionMs: () -> Long,
    cover: ImageBitmap?,
    palette: CoverPalette,
    lyrics: List<LyricsLine>?,
    progressMs: Long,
    theme: VisualizerTheme,
    quality: VisualQuality,
    /**
     * 照片墙编排器（阶段 10，§14.2.5）。
     *
     * ⛔ **只在这里接线，不在这里实现**：本组件只做两件事 ——
     * ① 帧循环里 `onFrame(frame)`（与 draw 同帧、同线程）；
     * ② `renderCtx.update(...)` 之后 `applyTo(renderCtx)`（零分配写 7 个字段）。
     * 「扫哪些来源 / 什么时候换图 / 用哪个转场」全在控制器里。
     *
     * `null` = 未接线（测试 / 预览环境）⇒ 两个调用点都退化为空操作，
     * **其余 35 个渲染器的行为完全不变**。
     */
    photoWall: com.nasmusic.tv.visualizer.photo.PhotoWallController? = null,
    /**
     * 三来源开关之「或」（§7.4）—— 决定底部指示器里**要不要显示**「照片墙」。
     *
     * ⛔ 必须由调用方传入、不能用默认值：三个来源全关时指示器上不该出现一个
     * 左右键也切不到的效果，反之亦然 —— 两处必须用**同一个**布尔值。
     */
    photoWallAvailable: Boolean,
    isTV: Boolean,
    onExit: () -> Unit,
    onNextTheme: () -> Unit,
    onPrevTheme: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val safeArea = 0.05f
    // 手机左上角返回按钮的无障碍描述（原先硬编码 "返回"，按项目约定改用字符串资源）
    val backDescription = stringResource(R.string.common_back)

    // TV 焦点：进入舞台立即夺焦。VisualizerOverlay 直接覆盖在 UI 树上，
    // 若不夺焦，D-Pad 事件仍路由到下方被遮挡界面的焦点节点，onPreviewKeyEvent 收不到，
    // 表现为"遥控器左右键无法切换效果"（真机实测）。
    val focusRequester = remember { FocusRequester() }

    // ── 渲染器生命周期（自动导演档 600ms 交叉淡入）────────────
    val renderCtx = remember { RenderContext() }
    // LocalContext 供默认工厂创建 View 型渲染器（three-globe WebView）用
    val context = LocalContext.current
    val swapper = remember { RendererSwapper(context) }
    // T1.6.3（§四 G16 伴生 · v1.15 修正口径）：原 `var canvasSize by remember { ... }` 已删 ——
    // 它在 Canvas draw 块内每帧写入、又在同一块内被 renderCtx.update 读取（§2.4 曾误判「只写不读」），
    // 改为 draw 块内局部 val（§12.4 偏差记录：状态 → 局部值，行为逐帧等价、少一个 State 订阅）。

    // 淡入透明度：由绘制循环逐帧写入，在绘制阶段读取 → 只重绘不重组
    val fadeAlpha = remember { mutableFloatStateOf(1f) }
    val prevAlpha = remember { mutableFloatStateOf(0f) }
    // 渲染器 draw 异常一次性告警标记（避免逐帧刷日志）
    val rendererFailed = remember { mutableStateOf(false) }
    // 正在淡出的旧渲染器（null = 无旧层）；用 State 以便出现/消失时重组
    val prevRenderer = remember { mutableStateOf<VisualizerRenderer?>(null) }
    // 当前生效渲染器的 State 快照：swapper.current 是普通 var（sync 后才更新、
    // 且不触发重组），view 型旁路的 AndroidView / Canvas 分支切换依赖此快照重组。
    // 首次 sync 前为 null ⇒ 走 Canvas 空帧分支（与改造前行为一致）。
    // ⚠️ 快照里**同时存下请求主题**（[ActiveRenderer]）：舞台要按「效果自己声明的家具」
    //    （[EffectFurniture]）决定叠加层，而渲染器是**在 LaunchedEffect 里**创建的 ⇒
    //    主题刚换的那一帧，State 里还是**上一个**渲染器 ⇒ 必须按主题比对后才敢用它的家具。
    val activeRenderer = remember { mutableStateOf<ActiveRenderer?>(null) }

    // 歌词级常量（最长行字数 / 最长行文本）：只随 lyrics 变化。
    // 每帧重算是 O(N) 全量扫描，必须缓存。
    val lyricMetrics = remember(lyrics) { computeLyricMetrics(lyrics) }

    // ── 帧率读数（真机验收用）───────────────────────────────────
    // 开关走 Settings.Global 而非设置页：⛔ 不在播放器 UI 上留常驻调试信息，
    // 又能 release 包直接开 —— `adb shell settings put global nasmusic_fps 1`（0 或删键即关）。
    // 只在进入舞台时读一次（切效果 / 重启 App 后生效），不做轮询。
    val showFps = remember {
        runCatching {
            android.provider.Settings.Global.getInt(context.contentResolver, FPS_SETTING_KEY, 0) == 1
        }.getOrDefault(false)
    }
    val fpsMeter = remember { FpsMeter() }
    val fpsValue = remember { mutableFloatStateOf(0f) }

    // 主题 / 画质变化 → 同步渲染器（自动导演走淡入，手动切换硬切）
    LaunchedEffect(theme, quality) {
        val lyricInfo = computeLyricInfo(lyrics, progressMs, lyricMetrics)
        renderCtx.update(quality, palette, cover, Size.Zero, 0f,
            System.currentTimeMillis(), song?.title,
            lyricInfo.line, lyricInfo.nextLine, lyricInfo.progress,
            lyricInfo.hasWords, lyricInfo.lineIndex, lyricInfo.wordStartTimes,
            lyricInfo.maxLineChars, lyricInfo.longestLine, song?.id)
        // 照片墙生命周期（阶段 10）：进入 → 开始扫描（若未扫过）；离开 → 停时钟、停预取。
        // ⛔ 必须在帧循环**之前**：本 `LaunchedEffect` 声明在前，先启动 ⇒ `onFrame` 第一帧
        //    就已经是 `active` 状态。放在帧循环之后会白跑一帧。
        // ⚠️ 幂等：画质变化也会重跑这个 effect，`onThemeEntered()` 重复调用无副作用。
        if (theme == VisualizerTheme.PHOTO_WALL) photoWall?.onThemeEntered() else photoWall?.onThemeExited()

        // crossfade 能力保留在 RendererSwapper（有单测覆盖）；自动导演档删除后
        // UI 层已无使用场景，因此恒为 false（原为死参数，现收敛到调用处）
        if (swapper.sync(theme, quality, false, renderCtx, System.currentTimeMillis())) {
            // 快照同步进 State：swapper.current 变化本身不触发重组，
            // 这里写 State 才能让下面的 Canvas / AndroidView 分支切换生效
            // ⚠️ 连**请求主题**一起存（[ActiveRenderer]）⇒ 舞台按主题比对后才用它的家具
            //    （主题刚换的那一帧，State 里还是上一个渲染器）。
            activeRenderer.value = swapper.current?.let { ActiveRenderer(theme, it) }
            prevRenderer.value = swapper.previous
            if (swapper.isCrossfading) {
                fadeAlpha.floatValue = 0f
                prevAlpha.floatValue = 1f
            } else {
                fadeAlpha.floatValue = 1f
                prevAlpha.floatValue = 0f
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 舞台被移除（退出全屏可视化）⇒ 停照片墙的时钟与扫描。
            // ⚠️ 缓存**不**释放（控制器在 ViewModel 里、跨重组存活）⇒ 再进来是瞬时的。
            photoWall?.onThemeExited()
            swapper.release()
        }
    }

    // ── 绘制循环（不触发重组）──────────────────────────────────
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { ns ->
                tick = ns
                if (showFps && fpsMeter.onFrame(ns)) fpsValue.floatValue = fpsMeter.fps
                if (swapper.isCrossfading) {
                    val ms = ns / 1_000_000L
                    swapper.advance(ms)
                    fadeAlpha.floatValue = swapper.currentAlpha(ms)
                    prevAlpha.floatValue = swapper.previousAlpha(ms)
                    prevRenderer.value = swapper.previous
                }
                // 照片墙：与 draw 同帧、同线程（主线程）。
                // ⛔ 必须在下面 Canvas 的 `renderCtx.update(...)` **之前**跑完，
                //    这样 `applyTo` 写进 `RenderContext` 的值与本次 draw 看到的是同一帧。
                photoWall?.onFrame(frame())
            }
        }
    }

    // ── 效果名 Toast ───────────────────────────────────────────
    var toastVisible by remember { mutableStateOf(true) }
    LaunchedEffect(theme) {
        toastVisible = true
        kotlinx.coroutines.delay(2500)
        toastVisible = false
    }

    // ── E50「千里江山」三版设色（V-A / V-B / V-C）────────────────
    //
    // ⛔ **只有「千里江山」主题响应**（遥控器上下键 + 手机上下滑）：
    //    其它效果上这两个输入**维持原语义**（上键 = 空操作、下键 = 不消费、让事件冒泡），
    //    别把 E50 的开关泄漏给别的效果（用户会莫名看到「千里江山 · 设色 V-B」的提示）。
    //
    // ⚠️ 渲染器每帧读的是 [RenderContext.qianliColorVersion]（逐帧直写、不进 `update()`）；
    //    这里的 State 只负责「当前值」与 Toast 文案。
    val appPrefs = remember(context) { AppPreferences.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()
    var qianliColorVersion by remember { mutableIntStateOf(QianliColorVersion.DEFAULT) }

    /**
     * 「用户已经手动切过」标记（普通数组，**不用 State** —— 只在协程里读写，不触发重组）。
     *
     * 用途：挡住「读盘比按键慢」的竞态 —— 读盘回来时若用户已经切过，
     * 就**不要**用读到的旧值盖掉用户刚选的那一版。
     */
    val colorVersionTouched = remember { booleanArrayOf(false) }

    /** 设色版切换的短暂提示（null = 不显示）；自动消失（同效果名 Toast 的定时写法）。 */
    var colorToastText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(colorToastText) {
        if (colorToastText == null) return@LaunchedEffect
        kotlinx.coroutines.delay(1500)
        colorToastText = null
    }

    // 进舞台时读一次持久化值 → 写进 renderCtx（渲染器每帧读它）。
    // ⚠️ 读取完成前 renderCtx 里是默认值 2（= V-C）⇒ 与改造前「编译期固定 V_C」一致，不会闪错版。
    LaunchedEffect(Unit) {
        val stored = appPrefs.qianliColorVersion.first()
        if (colorVersionTouched[0]) return@LaunchedEffect
        qianliColorVersion = stored
        renderCtx.qianliColorVersion = stored
    }

    /** 主题显示名（Toast 文案用；随语言资源走，不写死中文）。 */
    val themeDisplayName = stringResource(theme.displayNameRes)

    /**
     * 切换 E50「千里江山」的设色版（V-A / V-B / V-C，循环）。
     *
     * 一次做完三件事：写 [RenderContext.qianliColorVersion]（渲染器每帧读它）、
     * 持久化到 [AppPreferences]、弹一条短暂 Toast（否则用户看不出切没切）。
     *
     * @param delta `+1` = 下一个（0→1→2→0），`-1` = 上一个（2→1→0→2）。
     * @return 是否真的切了；**非「千里江山」主题返回 false** ⇒ 调用方保持原语义。
     */
    fun applyQianliColorVersion(delta: Int): Boolean {
        // ⛔ 「忠于上游」路径**没有设色版**（上游 `shan-shui-inf` 只有灰阶，见 §7.11）
        //    ⇒ 必须在这里**关掉**这个开关：否则按上下键会弹一条「设色 V-B」的 Toast，
        //    画面却毫无变化 ⇒ 用户以为坏了。
        // ⛔ 放在**这里**（舞台层）而不是 [nextQianliColorVersion]（纯逻辑）：
        //    后者有单测（`QianliColorVersionSwitchTest` 的上/下循环断言），
        //    在里面加守卫会把那些断言全部打红。
        if (QianliRenderStyle.UPSTREAM_DEFAULT) return false
        val next = nextQianliColorVersion(theme, qianliColorVersion, delta) ?: return false
        colorVersionTouched[0] = true
        qianliColorVersion = next
        renderCtx.qianliColorVersion = next
        colorToastText = "$themeDisplayName · 设色 ${QianliColorVersion.label(next)}"
        coroutineScope.launch { appPrefs.setQianliColorVersion(next) }
        return true
    }

    // ── E53「歌词舞台」子模式（5 个**手动切换**的歌词效果）────────
    //
    // ⛔ **仅手动切换**：不自动定时切换、不随歌曲切换、不随段落/副歌切换。
    // 上下键 / 手势上下滑动 ⇒ 下一个 / 上一个子模式（循环）。
    //
    // ⚠️ 渲染器每帧读的是**推给它的** [LyricsStagePayload]
    //    （⛔ 不走 `RenderContext` —— 那个类被本机私有分支的覆盖层遮蔽，
    //     改它会让本地构建与 CI 行为分叉）。
    //
    // 整曲歌词行只在 `lyrics` 变化时重建（逐帧重算是 O(N) 全量扫描，必须缓存）。
    val lyricsStageLines = remember(lyrics) { LyricsStageBridge.buildLines(lyrics ?: emptyList()) }

    /** 逐帧复用的歌词载荷（可变 ⇒ 逐帧零分配） */
    val lyricsStagePayload = remember { LyricsStagePayload() }

    /** 当前子模式（渲染器每次主题切换都会被重建 ⇒ 模式要重新告知它） */
    var lyricsStageMode by remember { mutableStateOf(LyricsStageMode.Default) }

    /** 子模式切换的短暂提示（null = 不显示）；存的是**模式**而非文案 ——
     *  文案要在组合层用 `stringResource` 拼（⛔ `stringResource` 是 @Composable，
     *  普通局部函数里调不了）。 */
    var lyricsStageToastMode by remember { mutableStateOf<LyricsStageMode?>(null) }
    LaunchedEffect(lyricsStageToastMode) {
        if (lyricsStageToastMode == null) return@LaunchedEffect
        kotlinx.coroutines.delay(1500)
        lyricsStageToastMode = null
    }

    /** 子模式 Toast 文案（组合层拼 ⇒ 英文界面显示英文名） */
    val lyricsStageToastText = lyricsStageToastMode?.let { mode ->
        "$themeDisplayName · ${stringResource(mode.displayNameRes)}"
    }

    // 进舞台时读一次持久化的子模式。
    LaunchedEffect(Unit) {
        lyricsStageMode = LyricsStageMode.fromOrdinal(appPrefs.lyricsStageMode.first())
    }

    // 把当前子模式推给渲染器。
    // ⚠️ 渲染器由 [RendererSwapper] 管，主题/画质一变就被重建 ⇒ 每次重组后都要重新推。
    LaunchedEffect(lyricsStageMode, theme, quality) {
        if (theme != VisualizerTheme.LYRICS_STAGE) return@LaunchedEffect
        (swapper.current as? LyricsStageRenderer)?.setMode(lyricsStageMode)
    }

    /**
     * 切换 E53「歌词舞台」的子模式（循环）。
     *
     * 一次做完三件事：更新模式（渲染器随之换子渲染器）、持久化到 [AppPreferences]、
     * 弹一条短暂 Toast（否则用户看不出切没切）。
     *
     * @param delta `+1` = 下一个，`-1` = 上一个。
     * @return 是否真的切了；**非「歌词舞台」主题返回 false** ⇒ 调用方保持原语义。
     */
    fun applyLyricsStageMode(delta: Int): Boolean {
        if (theme != VisualizerTheme.LYRICS_STAGE) return false
        val next = if (delta >= 0) {
            LyricsStageMode.next(lyricsStageMode)
        } else {
            LyricsStageMode.prev(lyricsStageMode)
        }
        lyricsStageMode = next
        (swapper.current as? LyricsStageRenderer)?.setMode(next)
        lyricsStageToastMode = next
        coroutineScope.launch { appPrefs.setLyricsStageMode(next.ordinal) }
        return true
    }

    // 进入舞台立即把焦点交给舞台 Box（TV 遥控器 D-Pad 依赖焦点路由）
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                    when (e.key) {
                        Key.DirectionLeft -> { onPrevTheme(); true }
                        Key.DirectionRight -> { onNextTheme(); true }
                        // 上键：「千里江山」切**下一个**设色版（0→1→2→0）；
                        // 「歌词舞台」切**下一个**歌词子模式；
                        // 其它主题维持原样（无动作，控制栏已移除）⇒ 仍返回 true
                        Key.DirectionUp -> {
                            if (applyLyricsStageMode(+1)) true
                            else {
                                applyQianliColorVersion(+1)
                                true
                            }
                        }
                        // 下键：「千里江山」切**上一个**设色版（2→1→0→2）；
                        // 「歌词舞台」切**上一个**歌词子模式；
                        // 其它主题**维持原样**（不消费 ⇒ 让事件冒泡给下层组件）
                        Key.DirectionDown -> {
                            if (applyLyricsStageMode(-1)) true
                            else if (applyQianliColorVersion(-1)) true else false
                        }
                        else -> false
                    }
                } else false
            }
            .pointerInput(Unit) {
                // 手机：左右滑动切换（阈值 80dp）
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { _, amount -> totalDrag += amount },
                    onDragEnd = {
                        val threshold = with(density) { 80.dp.toPx() }
                        when {
                            totalDrag > threshold -> onPrevTheme()
                            totalDrag < -threshold -> onNextTheme()
                        }
                    }
                )
            }
            .pointerInput(theme) {
                // 手机：上下滑动切换「千里江山」的设色版 / 「歌词舞台」的子模式
                // （阈值与左右一致：80dp）
                //
                // ⚠️ **单独一个 pointerInput 块**（不与横滑合并）：两个 detector 挂同一层
                //    会互相吞事件；分成两块后各自等自己方向的 slop
                //    （横 detector 只在 |dx| > |dy| 时起手、竖 detector 反之）⇒ 互不干扰。
                // ⚠️ key 用 [theme]：块内的主题判据必须是**当前**主题，
                //    用 `Unit` 会把进舞台那一刻的主题**永久捕获**进去（切到「千里江山」后按了没反应）。
                var totalDrag = 0f
                detectVerticalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onVerticalDrag = { _, amount -> totalDrag += amount },
                    onDragEnd = {
                        val threshold = with(density) { 80.dp.toPx() }
                        // 上滑（totalDrag < 0）⇒ 下一个；下滑 ⇒ 上一个（与遥控器上下键同向）
                        when {
                            totalDrag < -threshold -> {
                                if (!applyLyricsStageMode(+1)) applyQianliColorVersion(+1)
                            }
                            totalDrag > threshold -> {
                                if (!applyLyricsStageMode(-1)) applyQianliColorVersion(-1)
                            }
                        }
                    }
                )
            }
    ) {
        // ⭐⭐ **T1.7.1（2026-10-05 性能诊断实验）**：离屏层是否挂载的**判据读在这里**
        //   （**组合作用域**），⛔ 不能只写在下面那个 `graphicsLayer { }` block 里。
        //
        //   ⚠️ **为什么必须提到组合作用域**：`Modifier.graphicsLayer { }` 的 block 在状态失效时
        //   **会**重跑，但它只更新 alpha / translation / scale 这些**标量**；
        //   **离屏层本身是在元素 `onAttached` 时按 `compositingStrategy` 决定要不要建的**，
        //   block 重跑**不会**新建或销毁它。
        //   ⭐ 提到组合作用域后，它翻转时**整个 `graphicsLayer` 元素被重新 apply**
        //   ⇒ `compositingStrategy` 真的生效 ⇒ **交叉淡入期间照样能挂上离屏**，
        //   ⛔ **不会**出现「条件化导致淡入永远不挂」那种失效。
        // ⚠️ 代价：交叉淡入那 ≤600ms 里 `fadeAlpha` 每帧变 ⇒ 本子树每帧重组一次；
        //   静止期它恒 1.0 ⇒ ⛔ 不重组、⛔ 行为与改动前**完全一致**。
        //   （`:161` 说 `fadeAlpha`「只重绘不重组」，那指的是**只写在 block 里**的读法；
        //   ⭐ 在组合作用域读会重组，⛔ 但仅限这 600ms 的过渡期。）
        //
        // ⭐ **定稿后的正式说明**（不再是诊断态）：静止期不挂离屏层 ⇒ 海边 `drawSheen` 的
        //   `BlendMode.Plus` 按 `SrcOver` 呈现 ⇒ 镜面高光不再是加法。
        //   ⭐ **所有者真机确认无影响**（原话：「湿沙效果很好，镜面高光没啥意义」）⇒ 保留现状。
        //   ⛔ 不要为此把高光预合成进湿沙刷（`SeasideRenderer` 的 KDoc 判其为视觉偏差），
        //   ⛔ 也不要把 `Offscreen` 改回无条件挂载。
        // ⚠️ 实测备注：条件化后**帧率无可测收益**（见 `technical-overview.md` §10.211 的
        //   「假说证伪」条）⇒ 保留它的理由是**修掉一条本文件自己写明的 TODO**（静止期
        //   整屏离屏缓冲 1080p ≈ 16.6 MB/帧带宽、收益为零），而不是本机帧率。
        val needOffscreen = fadeAlpha.floatValue < 1f

        // ① 背景层：纯暗色底（不再叠加封面图，突出频谱效果本身；暗底衬托霓虹荧光）
        // 每帧只算一次：新层与渐出旧层共用。
        // 此前两个 Canvas 各算一次（且内部含全量扫描），直接翻倍开销。
        val lyricInfo = computeLyricInfo(lyrics, progressMs, lyricMetrics)

        Box(
            Modifier
                .fillMaxSize()
                .background(palette.background.copy(alpha = 0.72f))
        )

        // ② 效果层（新效果；交叉淡入时从透明渐显）
        // ⚠️ 这里**不**按主题过滤：View 型渲染器（three-globe 的 WebView）的
        //    AndroidView 分支要拿到**当前**渲染器实例；主题刚换时它可能还是上一个
        //    （与改造前同语义：这份 State 在 LaunchedEffect 里才更新）。
        val active = activeRenderer.value?.renderer
        if (active != null && active.isViewBased) {
            // View 型渲染器旁路（three-globe 等 WebView 渲染器）：
            // 用 AndroidView 承载，不经 DrawScope —— draw() 不再被调用。
            // ⛔ 不叠加 graphicsLayer Offscreen/alpha —— 对 View 无效。
            // key(active)：渲染器实例变化（切主题）⇒ 重建整个组 ⇒ AndroidView 的
            // factory 重新执行、旧 View 随组销毁，避免「旧 View 配新渲染器」。
            key(active) {
                AndroidView(
                    factory = { ctx -> active.createView(ctx)!! },
                    modifier = Modifier.fillMaxSize()
                )
                // attach 在组合后（此时 factory 已建好 View）、
                // detach 在离开组合 / 渲染器被 key 换掉时触发
                DisposableEffect(Unit) {
                    active.onViewAttached()
                    onDispose { active.onViewDetached() }
                }
            }
        } else {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    // T9：叠加发光需要离屏层，否则部分 API 版本退化为 SrcOver。
                    // ⚠️ G14 现状说明（§2.4/v1.14 裁决项 10）：这层目前是**无条件**挂载 ——
                    //    交叉淡入的 600ms 之外 fadeAlpha 恒 1.0，整屏离屏缓冲（1080p ≈ 16.6 MB/帧带宽）
                    //    在静止期收益为零、只有成本；T1.7.1 将条件化为 `fadeAlpha < 1f` 才挂。
                    //    ⛔ 不要因为看到这层就以为「Plus 叠加必须有它」而照抄到新画布。
                    //
                    // ⭐⭐ **T1.7.1 已实施**（2026-10-05，**定稿**）：离屏层**条件化挂载**。
                    //   `compositingStrategy` 在此**赋值**（由元素 `onAttached` 读取），
                    //   ⭐ 而**判据取自组合作用域的 [needOffscreen]** ⇒ 它翻转时整个元素被
                    //   重新 apply ⇒ 真正生效（⛔ 不是「block 重跑但没换策略」那种假生效）。
                    //   ⚠️ 代价（仅过渡期）：见 [needOffscreen] 处的注释。
                    // ⚠️ **为什么值得做**：静止期整屏离屏缓冲（1080p ≈ 16.6 MB/帧带宽）**收益为零**
                    //   —— `fadeAlpha` 恒 1.0，alpha 合成与 `SrcOver` 等价。这条 T1.7.1 TODO
                    //   就是上面 `:334-335` 注释自己写下的（见「G14 现状说明」）。
                    // ⭐ **对海边效果的影响（已定稿、所有者真机确认）**：静止期没有离屏层 ⇒
                    //   `drawSheen` 的 `BlendMode.Plus` 按 `SrcOver` 呈现 ⇒ 镜面高光不再是加法。
                    //   所有者当场看过并判定「湿沙效果很好，镜面高光没啥意义」⇒ 保留现状。
                    //   ⛔ 不要为此把高光预合成进湿沙刷（`SeasideRenderer` 的 KDoc 判其为视觉偏差）。
                    .graphicsLayer {
                        compositingStrategy =
                            if (needOffscreen) CompositingStrategy.Offscreen
                            else CompositingStrategy.Auto
                        alpha = fadeAlpha.floatValue
                    }
            ) {
                // T1.6.3：draw 块内局部 val（原 remember State，读写同块、逐帧等价）
                val canvasSize = Size(size.width, size.height)
                // T6：每帧捕获一次 front 引用，绘制期间引用不变
                val f = frame()
                // 计算当前歌词行 & 行内进度（给 E23 歌词点阵用）
                // 复用实例，零分配
                renderCtx.update(quality, palette, cover, canvasSize,
                    minOf(size.width, size.height) * safeArea, f.timeMs, song?.title,
                    lyricInfo.line, lyricInfo.nextLine, lyricInfo.progress,
                    lyricInfo.hasWords, lyricInfo.lineIndex, lyricInfo.wordStartTimes,
                    lyricInfo.maxLineChars, lyricInfo.longestLine, song?.id)
                // 照片墙：零分配写 7 个 `photo*` 字段（§14.2.4 指定「在 update() 之后单独写」）。
                // ⛔ 必须在 `swapper.current.draw(...)` **之前**：`PhotoRenderer` 读的就是这 7 个字段。
                photoWall?.applyTo(renderCtx)
                val cur = swapper.current
                // E53「歌词舞台」：把歌词数据推给渲染器。
                // ⛔ **不走 `RenderContext`**（那个类被本机私有分支的覆盖层遮蔽）。
                // ⚠️ 逐帧只改三个字段、不新建对象 ⇒ 零分配。
                if (cur is LyricsStageRenderer) {
                    // ⛔ **逐帧**读播放位置：`progressMs` 是 1 Hz 的 StateFlow ⇒ 动画每秒跳一次
                    val posMs = positionMs()
                    lyricsStagePayload.lines = lyricsStageLines
                    // 行索引也必须逐帧算（`lyricInfo.lineIndex` 同样是 1 Hz ⇒ 换行会迟 1 秒）
                    lyricsStagePayload.currentIndex = findCurrentLyricLine(lyrics, posMs)
                    lyricsStagePayload.timeSec = posMs / 1000.0
                    cur.applyLyrics(lyricsStagePayload)
                }
                // tick 参与读取以确保每帧重绘
                // ⛔ `!cur.isViewBased` 防御：换主题瞬间（sync 后、State 重组前）本分支
                //    仍是 Canvas，而 swapper.current 已指向新渲染器 —— 若是 view 型
                //    绝不能在这里调它的 draw()，跳过等重组切到 AndroidView 分支。
                if (tick >= 0L && cur != null && !cur.isViewBased && fadeAlpha.floatValue > ALPHA_EPS) {
                    // 渲染器绘制异常若直接抛出会中断整个绘制线程 → 电视上可能表现为
                    // native 崩溃（Skia 收到非法几何/状态）。捕获后跳过该帧并告警。
                    try {
                        with(cur) { draw(f, renderCtx) }
                    } catch (t: Throwable) {
                        if (!rendererFailed.value) {
                            rendererFailed.value = true
                            android.util.Log.w("VisualizerStage",
                                "renderer draw failed, skipped frame: ${cur::class.simpleName}", t)
                        }
                    }
                }
            }
        }

        // ② 效果层（旧效果；仅交叉淡入期间存在，1→0 淡出）
        prevRenderer.value?.let { old ->
            // 防御：view 型渲染器恒硬切（crossfade=false），正常不会进入淡出分支；
            // 且 AndroidView 无法在 DrawScope 里「淡出」，真进来了也只能跳过。
            if (old.isViewBased) return@let
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        alpha = prevAlpha.floatValue
                    }
            ) {
                // T6：旧层独立捕获一次 front，与新层可能差一帧——两帧皆为完整帧
                val f = frame()
                renderCtx.update(quality, palette, cover, Size(size.width, size.height),
                    minOf(size.width, size.height) * safeArea, f.timeMs, song?.title,
                    lyricInfo.line, lyricInfo.nextLine, lyricInfo.progress,
                    lyricInfo.hasWords, lyricInfo.lineIndex, lyricInfo.wordStartTimes,
                    lyricInfo.maxLineChars, lyricInfo.longestLine, song?.id)
                if (tick >= 0L && prevAlpha.floatValue > ALPHA_EPS) {
                    try {
                        with(old) { draw(f, renderCtx) }
                    } catch (t: Throwable) {
                        if (!rendererFailed.value) {
                            rendererFailed.value = true
                            android.util.Log.w("VisualizerStage",
                                "prev renderer draw failed, skipped frame: ${old::class.simpleName}", t)
                        }
                    }
                }
            }
        }

        // ── 舞台叠加层的「家具」由**效果自己声明**（[EffectFurniture]）────────
        //
        // ⚠️ **主题无关**：这里不再有逐主题的特例 ——
        //    「怀旧」要 4:3 内容区 + 深色墨、「歌词舞台」不要顶部歌词条，
        //    全部由各自的渲染器通过 [VisualizerRenderer.furniture] 声明。
        //    ⇒ **新增效果永远不需要改这个文件。**
        //
        // ⚠️ **一帧窗口**：渲染器是在 `LaunchedEffect` 里创建的 ⇒ 主题刚换的那一帧，
        //    State 里还是**上一个**渲染器 ⇒ 用 [ActiveRenderer] 里的「请求主题」比对，
        //    **不匹配就当作没有家具**（退回默认「全显示」）。
        //    ⇒ 屏幕上永远不会出现**错的**家具；最坏只是晚一帧。
        val activeForTheme = activeRenderer.value
        val furniture: EffectFurniture? = if (activeForTheme != null && activeForTheme.theme == theme) {
            activeForTheme.renderer.furniture.value
        } else {
            null
        }

        // 内容区（4:3 之类）左右各留多少：效果声明了比例才有内容区，否则满屏。
        val cfg = LocalConfiguration.current
        val contentAspect = furniture?.contentAspect
        val contentInsetDp = if (contentAspect != null) {
            maxOf((cfg.screenWidthDp - cfg.screenHeightDp * contentAspect) / 2f, 0f).dp
        } else {
            0.dp
        }
        val contentEdgePx = with(density) { contentInsetDp.toPx() }

        // ⚠️ 白字 / 黑字的分界点 —— **屏幕上正好落在内容区的左缘**（4:3 画面的左边界）。
        //    算式看着像「内容区左缘 − 48 dp」，是因为 `clipRect` 用的是**歌曲信息 Box 的局部坐标**，
        //    而该 Box 自身带 `start = 48.dp` 内边距 ⇒ 局部 −48 dp 恰好把**屏幕**分界推到内容区边缘。
        //    ⛔ 这一对「局部 −48 dp / Box +48 dp」互相抵消的算式是**现有观感定稿**
        //       （所有者 2026-11-07 裁定：代码即真相）⇒ **两个 48 dp 都要保持原值**，
        //       ⛔ 不要只改其中一个（会让分界跑偏 48 dp）。
        //    ⛔ 本机 Android 5.1 无 BlendMode.Difference（API 29+），故用双层裁剪实现。
        val splitPx = contentEdgePx - with(density) { 48.dp.toPx() }

        // 内容区是浅底（怀旧：牛皮纸）⇒ 落在内容区里的字改用深色墨；侧边深色区用白字。
        val contentIsLight = furniture?.contentIsLight == true
        // 有内容区（两侧留了深色边）才需要「双层裁剪 + 反色」；否则只有一层白字。
        val hasSideBands = contentInsetDp > 0.dp

        // ③ 前景层
        Column(Modifier.fillMaxSize()) {
            // 顶部歌词提示行
            // ⚠️ 由**效果自己声明**：自己渲染歌词的效果（如「怀旧」「歌词舞台」）
            //    关掉它 ⇒ 顶部不会再把当前行显一遍（字残重复）。
            if (furniture?.lyricTopBar != false) {
                LyricTopBar(
                    lyrics = lyrics,
                    progressMs = progressMs,
                    fallback = song?.let { "${it.title} — ${it.artist ?: ""}" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp, start = 48.dp, end = 48.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            // 效果名 Toast
            AnimatedVisibility(
                visible = toastVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = themeDisplayName,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 20.sp,
                    modifier = Modifier
                        // drawBehind 画圆角矩形：避免 RoundedCornerShape clip 触发
                        // Android 5.1 hwui Region 段错误（见 ThemeIndicator 注释）
                        .drawBehind {
                            drawRoundRect(
                                color = Color.Black.copy(alpha = 0.35f),
                                cornerRadius = CornerRadius(12.dp.toPx())
                            )
                        }
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            // 设色版 Toast（E50「千里江山」三版设色切换时短暂提示，如「千里江山 · 设色 V-B」）
            // ⚠️ 与上面的效果名 Toast **分开一个 AnimatedVisibility**：两个提示的内容与
            //    消失时机都不同（效果名 = 进舞台/切主题；设色版 = 切色版），共用一个
            //    可见性 State 会互相把对方顶掉。
            AnimatedVisibility(
                visible = colorToastText != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = colorToastText.orEmpty(),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                    modifier = Modifier
                        .drawBehind {
                            drawRoundRect(
                                color = Color.Black.copy(alpha = 0.35f),
                                cornerRadius = CornerRadius(12.dp.toPx())
                            )
                        }
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            // 歌词子模式 Toast（E53「歌词舞台」切换子模式时短暂提示，如「歌词舞台 · 逐字行光」）
            // ⚠️ 与效果名 Toast、设色版 Toast **各占一个 AnimatedVisibility**：
            //    三者的内容与消失时机都不同，共用一个可见性 State 会互相把对方顶掉。
            AnimatedVisibility(
                visible = lyricsStageToastText != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = lyricsStageToastText.orEmpty(),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                    modifier = Modifier
                        .drawBehind {
                            drawRoundRect(
                                color = Color.Black.copy(alpha = 0.35f),
                                cornerRadius = CornerRadius(12.dp.toPx())
                            )
                        }
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            // 左下：歌曲信息
            // ⚠️ 由**效果自己声明**要不要显示（[EffectFurniture.songInfo]）。
            if (furniture?.songInfo != false) {
                // ⚠️ 内容区若是**浅底**（怀旧：牛皮纸），单色文字必有一侧不可见
                //    （真机反馈：黑字落在纯黑 band 上看不到）。⇒ 双层裁剪：
                //    侧边深色区用白字、内容区用深色墨；没有内容区的效果只有一层白字。
                //    右端以 `end = contentInsetDp` 的内边距挡在内容区之外。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 48.dp, bottom = 8.dp, end = contentInsetDp)
                ) {
                    // 侧边深色区 → 白字（与背景反色）；分界点 = splitPx
                    if (hasSideBands) {
                        SongInfoTexts(
                            song = song,
                            titleColor = Color.White,
                            artistColor = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.drawWithContent {
                                clipRect(right = splitPx) { this@drawWithContent.drawContent() }
                            }
                        )
                    }
                    // 内容区 → 浅底用黑字、深底用白字（无内容区时 = 整体白字，不裁剪）
                    SongInfoTexts(
                        song = song,
                        titleColor = if (contentIsLight) Color.Black else Color.White,
                        artistColor = if (contentIsLight) Color.Black.copy(alpha = 0.6f)
                        else Color.White.copy(alpha = 0.6f),
                        modifier = if (hasSideBands) Modifier.drawWithContent {
                            clipRect(left = splitPx) { this@drawWithContent.drawContent() }
                        } else Modifier
                    )
                }
            }

            // 底部：指示器（控制栏已移除）
            Column(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                ThemeIndicator(
                    themes = VisualizerTheme.selectable(photoWallAvailable),
                    current = theme,
                    quality = quality,
                    onLightBg = contentIsLight
                )

                Spacer(Modifier.size(24.dp))
            }
        }

        // 右上：帧率读数（真机验收用，⛔ 默认关闭 ⇒ 常驻 UI 上不可见）
        if (showFps) {
            FpsBadge(
                fps = fpsValue,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 20.dp)
            )
        }

        // 左上（仅手机）：返回按钮
        //
        // ⛔ v2.36.2：**不能用 `androidx.tv.material3.IconButton`**（真机反馈「看得见、按不动」）。
        // 它的 `Surface(onClick=)` 走 `Modifier.tvClickable`，而该实现**故意不挂 `Modifier.clickable`**
        // —— `Surface.kt` 原注释：「We are not using "clickable" modifier here because if we set
        // "enabled" to false then the Surface won't be focusable as well. But, in TV use case, a
        // disabled surface should be focusable」—— 它只提供
        //   ① `handleDPadEnter`：**D-Pad 按键**事件
        //   ② `focusable()`：焦点
        //   ③ `semantics { onClick { } }`：**无障碍服务**专用
        // ⇒ **触摸点击永远不会触发 `onClick`**。TV 端本分支不渲染（`isTV` 为 true 时整块跳过），
        //   所以只影响手机；必须改用项目自建的 [FocusableSurface]（内部是 `combinedClickable`，
        //   触摸 / D-Pad 双通道），这也是全项目唯一一处 tv-material3 `IconButton`。
        if (!isTV) {
            FocusableSurface(
                onClick = onExit,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    // 48dp 在竖屏只有 ≈39.4 物理 dp ❌ → §2.7 换算抬到 56dp
                    .size(portraitTouchTarget(48.dp))
                    .semantics { contentDescription = backDescription },
                shape = RoundedCornerShape(12.dp),
                // 覆盖在频谱上：半透明黑底 + 白图标，保证任意主题下都看得清
                containerColor = Color.Black.copy(alpha = 0.35f),
                focusedContainerColor = Color.Black.copy(alpha = 0.55f),
                // 手机没有焦点边框，按下高亮是唯一的"已响应"视觉反馈（同 PhoneTopBarIconButton）
                pressedContainerColor = Color.White.copy(alpha = 0.25f),
                contentColor = Color.White,
                focusedContentColor = Color.White,
                focusedScale = 1.08f,
                pressedScale = 0.92f,
                showFocusBorder = false,
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

/** 交叉淡入下限：低于此值直接跳过绘制（省掉全透明帧的空跑） */
private const val ALPHA_EPS = 0.004f

/**
 * E50「千里江山」设色版切换的**纯函数**（遥控器上下键与手机上下滑共用）。
 *
 * ⛔ **只有「千里江山」主题会切色版**：其它效果上，上下键 / 上下滑必须维持原语义
 *    （上键 = 空操作、下键 = 不消费、让事件冒泡）⇒ 返回 `null` 表示「不切换」。
 *
 * @param theme   当前主题。
 * @param current 当前设色版（0/1/2；越界值按默认版处理）。
 * @param delta   `+1` = 下一个（0→1→2→0），`-1` = 上一个（2→1→0→2）。
 * @return 新的设色版；**非「千里江山」主题返回 null**。
 */
internal fun nextQianliColorVersion(
    theme: VisualizerTheme,
    current: Int,
    delta: Int,
): Int? {
    if (theme != VisualizerTheme.QIANLI_SCROLL) return null
    return if (delta >= 0) {
        QianliColorVersion.next(current)
    } else {
        QianliColorVersion.prev(current)
    }
}

/** 顶部歌词行 —— 脱离频谱刷新作用域，仅在行变化时重组 */
@Composable
private fun LyricTopBar(
    lyrics: List<LyricsLine>?,
    progressMs: Long,
    fallback: String?,
    modifier: Modifier = Modifier
) {
    // 二分查找本身 O(log n)，无需 derivedStateOf：
    // 原写法把 progressMs 放进 remember key，每帧重建 State 对象，等于没缓存。
    val index = findCurrentLyricLine(lyrics, progressMs)

    val text = when {
        lyrics.isNullOrEmpty() -> fallback ?: ""
        index < 0 -> fallback ?: ""
        else -> lyrics[index].text
    }
    if (text.isBlank()) return

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 28.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                // drawBehind 画圆角矩形：避免 RoundedCornerShape clip 触发
                // Android 5.1 hwui Region 段错误（见 ThemeIndicator 注释）
                .drawBehind {
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.35f),
                        cornerRadius = CornerRadius(8.dp.toPx())
                    )
                }
                .padding(horizontal = 24.dp, vertical = 6.dp)
        )
    }
}

/**
 * 查找当前应显示的歌词行索引（二分，避免长歌词逐帧 O(n)）。
 * 返回最后一个 time <= 进度 的行；全部早于进度返回 -1。
 */
internal fun findCurrentLyricLine(lines: List<LyricsLine>?, progressMs: Long): Int {
    if (lines.isNullOrEmpty()) return -1
    var lo = 0
    var hi = lines.lastIndex
    var result = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].time <= progressMs) {
            result = mid
            lo = mid + 1
        } else hi = mid - 1
    }
    return result
}

/** 当前歌词行信息（给 E23 歌词点阵用） */
internal data class LyricInfo(
    val line: String?,
    val nextLine: String?,
    val progress: Float,
    val hasWords: Boolean,
    val lineIndex: Int,
    val wordStartTimes: List<Long>,
    val maxLineChars: Int = 0,
    val longestLine: String? = null
)

/**
 * 计算当前歌词行、行内进度、是否有逐字时间戳。
 * 用于 E23 歌词点阵效果的逐字点亮动画。
 */
/** 整曲歌词级常量：只随歌词内容变化，与播放进度无关 */
internal data class LyricMetrics(val maxChars: Int, val longest: String?)

/**
 * 汇总整曲歌词的最长句（E23 自适应字号用）。
 * 为 O(N) 全量扫描，必须由调用方缓存，不能每帧调用。
 */
internal fun computeLyricMetrics(lines: List<LyricsLine>?): LyricMetrics {
    if (lines.isNullOrEmpty()) return LyricMetrics(0, null)
    var maxChars = 0
    var longest: String? = null
    for (l in lines) {
        val len = l.text?.length ?: 0
        if (len > maxChars) {
            maxChars = len
            longest = l.text
        }
    }
    return LyricMetrics(maxChars, longest)
}

/**
 * 计算当前歌词行、行内进度、是否有逐字时间戳。
 * 用于 E23 歌词点阵效果的逐字点亮动画。
 *
 * @param metrics 整曲常量（由 computeLyricMetrics 缓存），避免每帧重算
 */
internal fun computeLyricInfo(
    lines: List<LyricsLine>?,
    progressMs: Long,
    metrics: LyricMetrics = LyricMetrics(0, null)
): LyricInfo {
    if (lines.isNullOrEmpty()) return LyricInfo(null, null, 0f, false, -1, emptyList())
    val idx = findCurrentLyricLine(lines, progressMs)
    if (idx < 0) return LyricInfo(null, null, 0f, false, -1, emptyList())
    val current = lines[idx]
    val next = lines.getOrNull(idx + 1)
    val duration = (next?.time ?: current.time + 3000L) - current.time
    val elapsed = progressMs - current.time
    val progress = if (duration > 0) (elapsed.toFloat() / duration).coerceIn(0f, 1f) else 1f
    val hasWords = !current.wordTimestamps.isNullOrEmpty()
    val wordStarts = current.wordTimestamps.map { it.startMs - current.time }
    return LyricInfo(
        current.text, next?.text, progress, hasWords, idx, wordStarts,
        metrics.maxChars, metrics.longest ?: current.text
    )
}

/**
 * 右上角帧率读数（真机验收用）。
 *
 * 单独成 `@Composable` ⇒ 2 Hz 的 State 写入只重组这一个节点，不带着整个前景层重算。
 * 底色用 `drawBehind` 画圆角矩形，⛔ 不用 `RoundedCornerShape` clip（同 Toast 的理由：
 * Android 5.1 的 hwui Region 路径会段错误）。
 */
@Composable
private fun FpsBadge(fps: FloatState, modifier: Modifier = Modifier) {
    val v = fps.floatValue
    Text(
        text = if (v <= 0f) "-- fps" else "${(v * 10).toInt() / 10f} fps",
        color = Color(0xFF9BFFB4),
        fontSize = 12.sp,
        maxLines = 1,
        modifier = modifier
            .drawBehind {
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.45f),
                    cornerRadius = CornerRadius(6.dp.toPx())
                )
            }
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** 左下角歌曲信息（歌曲名 + 艺术家）；颜色/裁剪由调用方传入（怀旧反色模式双层绘制） */
@Composable
private fun SongInfoTexts(
    song: Song?,
    titleColor: Color,
    artistColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            text = song?.title ?: "",
            color = titleColor,
            fontSize = 24.sp,
            maxLines = 1
        )
        Text(
            text = song?.artist ?: "",
            color = artistColor,
            fontSize = 16.sp,
            maxLines = 1
        )
    }
}

/** 底部圆点指示器（含 AUTO 档） */
@Composable
private fun ThemeIndicator(
    themes: List<VisualizerTheme>,
    current: VisualizerTheme,
    quality: VisualQuality,
    onLightBg: Boolean = false,
    modifier: Modifier = Modifier
) {
    // 圆点用 Canvas drawCircle 绘制，而非 21 个 CircleShape clip：
    // Android 5.1 (创维 rtd299o) 的 hwui Region::createTJunctionFreeRegion 对
    // 大量圆形 clip 的拓扑合并存在段错误（RenderThread SIGSEGV，真机三次复现）。
    // drawCircle 是普通绘制指令，不产生 RenderNode clip region。
    val density = LocalDensity.current
    val spacingDp = 17.dp
    val radiusDp = 5.dp
    val smallRadiusDp = 3.5.dp
    val totalWidthDp = (themes.size - 1).coerceAtLeast(0) * spacingDp.value + 2 * radiusDp.value
    // 当前主题点色先在组合期读取（舞台恒为强制深色，取到的就是深色 palette）
    val activeDotColor = NasMusicColors.Primary
    Canvas(
        modifier = modifier
            .padding(vertical = 10.dp)
            .size(totalWidthDp.dp, 10.dp)
    ) {
        val cx0 = radiusDp.toPx()
        val cy = size.height / 2
        themes.forEachIndexed { i, t ->
            val supported = quality.supports(t)
            val active = t == current
            drawCircle(
                color = when {
                    // 浅底（怀旧黄白）上白点不可见 → 换深色点
                    !supported -> if (onLightBg) Color.Black.copy(alpha = 0.22f)
                    else Color.White.copy(alpha = 0.18f)
                    active -> activeDotColor
                    else -> if (onLightBg) Color.Black.copy(alpha = 0.45f)
                    else Color.White.copy(alpha = 0.42f)
                },
                radius = if (active) radiusDp.toPx() else smallRadiusDp.toPx(),
                center = Offset(cx0 + i * spacingDp.toPx(), cy)
            )
        }
    }
}
