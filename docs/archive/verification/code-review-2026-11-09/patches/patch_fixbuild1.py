# -*- coding: utf-8 -*-
"""编译验证第 1 轮错误修复（13 个 error / 10 个文件）。

分类：
  A AppPreferences    finiteIn 参数类型 Float → Double（备份字段本身是 Double）
  B AppRoot           委托属性 currentSong 不能智能转换
  C KaraokePlayback   二维码 Bitmap 智能转换 + progressMs 改为 State<Long>
  D MvPlaybackScreen  二维码 Bitmap 智能转换
  E UnifiedSongRow    缺 kotlinx.coroutines.withContext import
  F PlayerControls    ProgressSection 的 progressMs 改为 State<Long>
  G BasicRenderers    Stroke.width 是 val，改量化分桶（M52）
  H BatchThreeRenderers 同上（M46）
"""
import io
import sys

ROOT = 'app/src/main/java/com/nasmusic/tv/'
log = []


def patch(rel, pairs, asserts=(), forbid=()):
    p = ROOT + rel
    with io.open(p, 'r', encoding='utf-8', newline='') as f:
        S = f.read()
    eol = '\r\n' if '\r\n' in S else '\n'
    S = S.replace('\r\n', '\n')
    for old, new in pairs:
        n = S.count(old)
        if n != 1:
            print('FAIL %s: anchor count=%d  %r' % (rel, n, old[:70]))
            sys.exit(1)
        S = S.replace(old, new, 1)
    for a in asserts:
        if a not in S:
            print('FAIL %s: assert missing %r' % (rel, a[:70]))
            sys.exit(1)
    for f in forbid:
        if f in S:
            print('FAIL %s: forbidden still present %r' % (rel, f[:70]))
            sys.exit(1)
    with io.open(p, 'w', encoding='utf-8', newline='') as f:
        f.write(S.replace('\n', eol))
    log.append('OK %s (%s)' % (rel, 'CRLF' if eol == '\r\n' else 'LF'))


# ---------------------------------------------------------------- A
patch('data/prefs/AppPreferences.kt', [
    ("""private fun finiteIn(value: Float, min: Float, max: Float, fallback: Float): Float =
    if (value.isFinite()) value.coerceIn(min, max) else fallback""",
     """private fun finiteIn(value: Double, min: Double, max: Double, fallback: Double): Double =
    if (value.isFinite()) value.coerceIn(min, max) else fallback"""),
    (""" * C01：恢复路径专用的数值兜底 —— 非有限值（NaN / ±Inf）落到 [fallback]，其余钳到区间。
 *
 * ⚠️ 不能只写 `coerceIn`：`Float.NaN.coerceIn(a, b)` 返回 NaN（比较恒 false ⇒ 原样返回），""",
     """ * C01：恢复路径专用的数值兜底 —— 非有限值（NaN / ±Inf）落到 [fallback]，其余钳到区间。
 *
 * ⚠️ 参数与返回值都是 **Double**：这三个字段在备份数据类里就是 Double、落盘也用
 * `doublePreferencesKey` ⇒ 用 Float 版本会让每个调用点套一层 `toDouble()`，
 * 更糟的是「声明里的默认值 8.0」与「兜底值 0f」两套精度混用。
 * ⚠️ 不能只写 `coerceIn`：`Double.NaN.coerceIn(a, b)` 返回 NaN（比较恒 false ⇒ 原样返回），"""),
    ("private const val COVER_BLUR_MAX = 40f", "private const val COVER_BLUR_MAX = 40.0"),
    ("finiteIn(data.coverFilterBlurRadius, 0f, COVER_BLUR_MAX, 0f)",
     "finiteIn(data.coverFilterBlurRadius, 0.0, COVER_BLUR_MAX, 0.0)"),
    ("finiteIn(data.coverFilterDarkOverlay, 0f, 1f, 0.3f)",
     "finiteIn(data.coverFilterDarkOverlay, 0.0, 1.0, 0.3)"),
    ("finiteIn(data.lyricsFontScale, 0.7f, 1.6f, 1.0f)",
     "finiteIn(data.lyricsFontScale, 0.7, 1.6, 1.0)"),
], asserts=['prefs[keyLyricsFontScale] = finiteIn(data.lyricsFontScale, 0.7, 1.6, 1.0)'],
   forbid=['finiteIn(data.coverFilterBlurRadius, 0f'])

# ---------------------------------------------------------------- B
patch('ui/components/AppRoot.kt', [
    ("""            if (currentScreen != Screen.NowPlaying && currentSong != null) {
                // 播放页自身即播放器，不重复显示 MiniPlayer
                MiniPlayer(
                    song = currentSong,""",
     """            // M38：currentSong 是 `by` 委托属性（collectAsState 的 State 委托），
            // 直接传参过不了智能转换（"Smart cast to 'Song' is impossible"）⇒ 先取本地 val。
            val miniPlayerSong = currentSong
            if (currentScreen != Screen.NowPlaying && miniPlayerSong != null) {
                // 播放页自身即播放器，不重复显示 MiniPlayer
                MiniPlayer(
                    song = miniPlayerSong,"""),
], asserts=['song = miniPlayerSong,'])

# ---------------------------------------------------------------- C
patch('ui/components/KaraokePlaybackScreen.kt', [
    ("""        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),""",
     """        // 委托属性不能智能转换 ⇒ 先取本地 val（同一帧内不会变）
        val qr = qrBitmap
        if (qr != null) {
            Image(
                bitmap = qr.asImageBitmap(),"""),
    ("""    /** 当前播放进度（毫秒），驱动歌词定位 —— 必须与播放页传入的同一 progressMs 来源 */
    progressMs: Long,""",
     """    /**
     * 当前播放进度（毫秒），驱动歌词定位 —— 必须与播放页传入的同一 progressMs 来源。
     *
     * M26：收 `State<Long>` 而不是裸 `Long` ⇒ 每秒的进度推送**不会**重组播放页整棵子树，
     * 只有本屏里真正读 `.value` 的叶子（歌词区 / 进度细线）重组。
     */
    progressMs: androidx.compose.runtime.State<Long>,"""),
    ("""                KaraokeLyricsView(
                    lyrics = lyrics,
                    progressMs = progressMs,""",
     """                KaraokeLyricsView(
                    lyrics = lyrics,
                    progressMs = progressMs.value,"""),
    ("(progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)",
     "(progressMs.value.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)"),
], asserts=['progressMs = progressMs.value,', 'bitmap = qr.asImageBitmap(),'])

# ---------------------------------------------------------------- D
patch('ui/components/MvPlaybackScreen.kt', [
    ("""        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),""",
     """        // 委托属性不能智能转换 ⇒ 先取本地 val（同一帧内不会变）
        val qr = qrBitmap
        if (qr != null) {
            Image(
                bitmap = qr.asImageBitmap(),"""),
], asserts=['bitmap = qr.asImageBitmap(),'])

# ---------------------------------------------------------------- E
patch('ui/components/song/UnifiedSongRow.kt', [
    ("import kotlinx.coroutines.launch\n",
     "import kotlinx.coroutines.launch\nimport kotlinx.coroutines.withContext\n"),
], asserts=['import kotlinx.coroutines.withContext'])

# ---------------------------------------------------------------- F
patch('ui/components/PlayerControls.kt', [
    ("fun ProgressSection(\n    progressMs: Long,",
     """fun ProgressSection(
    /**
     * M26：进度收 `State<Long>` —— 每秒 tick 只重组本小节（时长文本 / 进度填充），
     * ⛔ 不要改回裸 `Long`：那会让整张播放页跟着每秒重组。
     */
    progressMs: androidx.compose.runtime.State<Long>,"""),
    ("""    // 手势处理器读取最新值（progressMs 每秒更新，不能作为 pointerInput key 否则手势每秒重启）
    val latestProgressMs by rememberUpdatedState(progressMs)
    val latestDurationMs by rememberUpdatedState(durationMs)""",
     """    // 手势处理器读取最新值（progressMs 每秒更新，不能作为 pointerInput key 否则手势每秒重启）
    // progressMs 本身就是 State（稳定引用）⇒ 闭包里直接读 `.value` 即可取最新进度，
    // 不需要再套 rememberUpdatedState。
    val latestDurationMs by rememberUpdatedState(durationMs)"""),
    ("                   else TimeUtils.formatDuration(progressMs),",
     "                   else TimeUtils.formatDuration(progressMs.value),"),
    ("val newPosition = (progressMs + delta).coerceIn(0, durationMs)",
     "val newPosition = (progressMs.value + delta).coerceIn(0, durationMs)"),
    ("else if (durationMs > 0) progressMs.toFloat() / durationMs else 0f",
     "else if (durationMs > 0) progressMs.value.toFloat() / durationMs else 0f"),
], asserts=['progressMs.value.toFloat() / durationMs'], forbid=['latestProgressMs'])

# ---------------------------------------------------------------- G
patch('visualizer/renderers/BasicRenderers.kt', [
    ("""    /**
     * M52：复用 [Stroke] 实例（`width` 是可写属性）⇒ `drawContent` 真正零分配。
     *
     * ⛔ 不要写成内联 `Stroke(width = …)`：本渲染器每帧要画「外圈细线环 + 节拍爆环 +
     * 中央圆盘亮边」三个描边圆，内联构造就是每帧 3 个短命对象，和本文件反复强调的
     * 逐帧零分配红线直接冲突（GC 会落在渲染线程上）。
     */
    private val ringStroke = Stroke(width = 1.5f)""",
     """    /**
     * M52：复用 [Stroke] 实例 ⇒ `drawContent` 真正零分配。
     *
     * ⚠️ Compose 的 `Stroke.width` 是 **val**（只读，不像 `Paint.strokeWidth`），
     * 所以「改 width 复用同一个对象」这条捷径不存在（`also { it.width = … }` 编译不过）。
     * 随 `pulse` 变化的外圈线宽改用**量化分桶**：预建 8 档 Stroke，每帧只查一次表。
     * ⛔ 不要退回内联 `Stroke(width = …)`：本渲染器每帧画 3 个描边圆，内联就是每帧
     * 3 个短命对象，与本文件反复强调的逐帧零分配红线冲突（GC 会落在渲染线程上）。
     */
    private val ringPulseStrokes = Array(8) { i -> Stroke(width = 1.5f + 1.5f * i / 7f) }"""),
    ("            style = ringStroke.also { it.width = 1.5f + frame.pulse * 1.5f }",
     "            // pulse∈[0,1] → 8 档线宽（1.5f..3.0f），查表零分配\n"
     "            style = ringPulseStrokes[(frame.pulse.coerceIn(0f, 1f) * 7f).toInt()]"),
], asserts=['ringPulseStrokes[(frame.pulse.coerceIn(0f, 1f) * 7f).toInt()]'],
   forbid=['.also { it.width ='])

# ---------------------------------------------------------------- H
patch('visualizer/renderers/BatchThreeRenderers.kt', [
    ("""    /**
     * M46：复用 [Stroke] 实例 ⇒ `drawContent` 真正零分配（本类头注释的既有红线）。
     *
     * ⛔ 同心圆 5 条 + 回波最多 29 条原本都是内联 `Stroke(width = ...)`，
     * 合计每帧 34 个短命对象；宽度随 `i` / `peak` 变化不是问题 ——
     * `Stroke.width` 可写，改完立刻用即可（`drawCircle/drawArc` 是同步消费）。
     */
    private val ringStroke = Stroke(width = 1f)
    private val echoStroke = Stroke(width = 2.5f, cap = StrokeCap.Round)""",
     """    /**
     * M46：复用 [Stroke] 实例 ⇒ `drawContent` 真正零分配（本类头注释的既有红线）。
     *
     * ⚠️ Compose 的 `Stroke.width` 是 **val**（只读）⇒ 不能「改 width 复用同一对象」。
     * 同心圆只有两档线宽（内圈 1f / 最外圈 1.8f）⇒ 直接两个字段；
     * 回波线宽随 `peak` 连续变化 ⇒ 预建 8 档量化表，每帧查表。
     * ⛔ 不要退回内联 `Stroke(width = ...)`：合计每帧 34 个短命对象。
     */
    private val ringStroke = Stroke(width = 1f)
    private val ringStrokeEdge = Stroke(width = 1.8f)
    private val echoStrokes = Array(8) { i ->
        Stroke(width = 2.5f + 3.5f * i / 7f, cap = StrokeCap.Round)
    }"""),
    ("                style = ringStroke.also { it.width = if (i == RINGS) 1.8f else 1f },",
     "                style = if (i == RINGS) ringStrokeEdge else ringStroke,"),
    ("                    style = echoStroke.also { it.width = 2.5f + peak * 3.5f },",
     "                    style = echoStrokes[(peak.coerceIn(0f, 1f) * 7f).toInt()],"),
], asserts=['style = if (i == RINGS) ringStrokeEdge else ringStroke,',
           'style = echoStrokes[(peak.coerceIn(0f, 1f) * 7f).toInt()],'],
   forbid=['.also { it.width ='])

print('\n'.join(log))
print('ALL OK')
