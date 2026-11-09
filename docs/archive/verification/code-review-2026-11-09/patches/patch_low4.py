#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Low 批次 4（UI）：L18 / L19 / L20 / L21 / C31 / L22 / L23 / L24 / L25 / L26"""
import io, sys

FAILS = []

def expect(tag, cond, extra=''):
    if not cond:
        FAILS.append(tag)
        print('FAIL', tag, extra)

def load(rel):
    raw = io.open(rel, 'r', encoding='utf-8', newline='').read()
    crlf = raw.count('\r\n')
    eol = '\r\n' if crlf > (raw.count('\n') - crlf) else '\n'
    return raw.replace('\r\n', '\n'), eol

def save(rel, S, eol):
    out = S.replace('\n', eol) if eol == '\r\n' else S
    io.open(rel, 'w', encoding='utf-8', newline='').write(out)
    print('OK', rel, 'crlf' if eol == '\r\n' else 'lf')

def apply1(tag, rel, old, new, extra_check=None, extra_msg=''):
    S, eol = load(rel)
    if old in S:
        S = S.replace(old, new, 1)
    else:
        expect(tag, False, 'anchor not found')
        return
    if extra_check is not None:
        expect(tag + '-check', extra_check in S, extra_msg)
    if FAILS:
        return
    save(rel, S, eol)

# ================= L18 =================
apply1('L18', 'app/src/main/java/com/nasmusic/tv/ui/theme/Theme.kt',
"""val LocalHighContrast = androidx.compose.runtime.staticCompositionLocalOf { false }""",
"""// L18：用 `compositionLocalOf` 而不是 `staticCompositionLocalOf` ——
// 后者不跟踪读取者，值一变就要**整棵子树**重组（这里是整个主题 content）；
// 高对比度开关会随设置页切换，代价直接体现为「打开开关整屏闪一下」。
// 本值变化频率极低、读取者也不多，动态版本的下沉失效范围更划算。
val LocalHighContrast = androidx.compose.runtime.compositionLocalOf { false }""",
extra_check='compositionLocalOf { false }')

# ================= L19 =================
apply1('L19', 'app/src/main/java/com/nasmusic/tv/ui/MainActivity.kt',
"""    override fun onDestroy() {
        super.onDestroy()
        networkMonitor.unregister()""",
"""    override fun onDestroy() {
        super.onDestroy()
        // L19：`networkMonitor` 是 lateinit，赋值发生在 onCreate 尾部的网络监听装配段。
        // onCreate 若在它之前就抛过异常（ViewModel 构造失败 / 权限拦截 / 主题解析崩溃），
        // 系统仍会走 onDestroy ⇒ 这里直接读 lateinit 会抛 UninitializedPropertyAccessException，
        // 把「一次启动崩溃」变成「崩溃 + 二次崩溃」，原始堆栈被盖掉。加初始化守卫。
        if (::networkMonitor.isInitialized) {
            networkMonitor.unregister()
        }""",
extra_check='::networkMonitor.isInitialized')

# ================= L20 =================
S, eol = load('app/src/main/java/com/nasmusic/tv/ui/screens/TextInputDialog.kt')
old = """    val isTVDevice = context.packageManager.hasSystemFeature("android.software.leanback") ||
            context.packageManager.hasSystemFeature("android.hardware.type.television")"""
new = """    // L20：`hasSystemFeature` 是**跨进程 binder 调用**，原先每次重组都要打两次；
    // 输入框在打字时每敲一个字符就重组一次 ⇒ 每个按键多两次 IPC。
    // 机型不会在运行期变化，remember 一次即可（context 变才重算）。
    val isTVDevice = remember(context) {
        context.packageManager.hasSystemFeature("android.software.leanback") ||
            context.packageManager.hasSystemFeature("android.hardware.type.television")
    }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L20-tv', False, 'anchor not found')

old = """    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())"""
new = """    // L20：Handler 原先每次重组都 new 一个（同样随打字高频发生），remember 一次即可 ——
    // 它只持有 Looper 引用，没有需要随重组重建的状态。
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L20-handler', False, 'anchor not found')
expect('L20-remember', 'remember { android.os.Handler(' in S)
if FAILS:
    print('NOT WRITTEN(1)'); sys.exit(1)
save('app/src/main/java/com/nasmusic/tv/ui/screens/TextInputDialog.kt', S, eol)

# ================= L21 =================
S, eol = load('app/src/main/java/com/nasmusic/tv/ui/viewmodel/NetworkMusicViewModel.kt')
old = """                AppLog.d("NetworkMusicViewModel", "playNetworkSong: ${song.title} → $playUrl")"""
new = """                // L21：`playUrl` 是**解析后的直链** —— 百度 dlink、各家临时签名全在 query 里。
                // 全量打日志等于把凭据写进 LogStore（落盘、可在设置页回看/导出）。
                AppLog.d("NetworkMusicViewModel", "playNetworkSong: ${song.title} → ${redactUrl(playUrl)}")"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L21', False, 'anchor not found')

old = """    // ---- 百度网盘连接状态 ----"""
new = """    /**
     * L21：日志脱敏 —— 去掉 URL 的 query 与 fragment（签名 / token / dlink 都在那里）。
     *
     * 保留 scheme+host+path，足以判断「打到了哪个源、哪条路径」。
     */
    private fun redactUrl(url: String): String {
        val cut = url.indexOfFirst { it == '?' || it == '#' }
        return if (cut > 0) url.substring(0, cut) + "?<redacted>" else url
    }

    // ---- 百度网盘连接状态 ----"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L21-helper', False, 'anchor not found')
expect('L21-use', 'redactUrl(playUrl)' in S)
if FAILS:
    print('NOT WRITTEN(2)'); sys.exit(1)
save('app/src/main/java/com/nasmusic/tv/ui/viewmodel/NetworkMusicViewModel.kt', S, eol)

# ================= C31 =================
apply1('C31', 'app/src/main/java/com/nasmusic/tv/ui/components/LyricsView.kt',
"""            itemsIndexed(lyrics.lines) { index, line ->""",
"""            // C31：补稳定 key。歌词是单曲静态列表、行内没有 per-item remember/LaunchedEffect，
            // 所以缺 key 目前不会「串行状态」，但一旦将来在行内加动画/手势就会错位；
            // key 里带 index 是为了防同一时间戳出现两行（重复 key 会直接崩）。
            itemsIndexed(lyrics.lines, key = { index, line -> "$index:${line.time}" }) { index, line ->""",
extra_check='key = { index, line -> "$index:${line.time}" }')

# ================= L22 =================
S, eol = load('app/src/main/java/com/nasmusic/tv/ui/components/CoverCarousel.kt')
OLD22 = "if (!permanentlyFailed) { permanentlyFailed = true; onAllFailed() }"
NEW22 = "ReportAllFailedOnce(permanentlyFailed) { permanentlyFailed = true; onAllFailed() }"
n22 = S.count(OLD22)
if n22 == 4:
    S = S.replace(OLD22, NEW22)
else:
    expect('L22-sites', False, 'count=%d (expect 4)' % n22)
expect('L22-all', OLD22 not in S)

# 追加辅助 composable
anchor = """/**
 * 封面占位符（音符图标）
 */"""
helper = """/**
 * L22：`onAllFailed` 的统一出口。
 *
 * 原实现在**组合期**直接 `permanentlyFailed = true; onAllFailed()` —— 组合期写 State 并调
 * 外部回调是 Compose 明令禁止的副作用（每次重组都重复执行，而 `onAllFailed` 在调用方
 * 往往又是「写自己的 State」⇒ 重组 → 再触发 的抖动）。
 * 这里统一收进 `LaunchedEffect`，只在进入该分支时执行一次。
 */
@Composable
private fun ReportAllFailedOnce(alreadyReported: Boolean, onReport: () -> Unit) {
    LaunchedEffect(Unit) {
        if (!alreadyReported) onReport()
    }
}

/**
 * 封面占位符（音符图标）
 */"""
if anchor in S:
    S = S.replace(anchor, helper, 1)
else:
    expect('L22-helper', False, 'anchor not found')
expect('L22-helper-use', S.count('ReportAllFailedOnce(') == 5,
       'n=%d' % S.count('ReportAllFailedOnce('))
if FAILS:
    print('NOT WRITTEN(3)'); sys.exit(1)
save('app/src/main/java/com/nasmusic/tv/ui/components/CoverCarousel.kt', S, eol)

# ================= L23 =================
S, eol = load('app/src/main/java/com/nasmusic/tv/ui/components/LyricsView.kt')
old = """    var isDragSeeking by remember { mutableStateOf(false) }
    var seekTargetLine by remember { mutableStateOf(-1) }"""
new = """    var isDragSeeking by remember { mutableStateOf(false) }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L23-decl', False, 'anchor not found')

old = """    // 拖拽中：计算虚线指示处的目标行（LazyColumn item index = 行 index + 1，头部有 spacer）
    if (isDragSeeking) {
        val indicatorPx = with(androidx.compose.ui.platform.LocalDensity.current) { INDICATOR_OFFSET_Y.toPx() }.toInt()
        val info = listState.layoutInfo
        seekTargetLine = info.visibleItemsInfo.firstOrNull { item ->
            item.index in 1..lyrics.lines.size &&
                item.offset <= indicatorPx && item.offset + item.size > indicatorPx
        }?.let { it.index - 1 } ?: -1
    }"""
new = """    // 拖拽中：计算虚线指示处的目标行（LazyColumn item index = 行 index + 1，头部有 spacer）
    //
    // L23：原先这是「组合期直接给 State 赋值 + 组合期读 listState.layoutInfo」——
    // 组合期写状态是 Compose 明令禁止的副作用（每次重组都写一次，且可能 写→重组→再写 抖动），
    // 而组合期读 layoutInfo 也会让**每次滚动帧**都触发本组件重组。
    // 改成 derivedStateOf 派生值：只在 isDragSeeking / layoutInfo 变化时重算，
    // 组合期只读不写；事件回调（松手跳转）通过 seekTargetLine 读当前派生值。
    val indicatorDensity = androidx.compose.ui.platform.LocalDensity.current
    val indicatorOffsetPx = remember(indicatorDensity) {
        with(indicatorDensity) { INDICATOR_OFFSET_Y.toPx() }.toInt()
    }
    val seekTargetLineState = remember {
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
    val seekTargetLine by seekTargetLineState"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L23-calc', False, 'anchor not found')

# 移除三处 `seekTargetLine = ...` 赋值；并把「先读 target 再翻 isDragSeeking」的顺序修正
old = """        if (!longPressSeekEnabled && listDragged && listState.isScrollInProgress && !isDragSeeking) {
            isDragSeeking = true
            seekTargetLine = -1
        }"""
new = """        if (!longPressSeekEnabled && listDragged && listState.isScrollInProgress && !isDragSeeking) {
            isDragSeeking = true
        }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L23-a1', False, 'anchor not found')

old = """        if (!longPressSeekEnabled && !listDragged && isDragSeeking) {
            isDragSeeking = false
            val target = seekTargetLine
            seekTargetLine = -1
            if (target in lyrics.lines.indices) {"""
new = """        if (!longPressSeekEnabled && !listDragged && isDragSeeking) {
            // ⚠️ 顺序不能反：派生值在 isDragSeeking=false 之后立即变成 -1，
            //    必须先读目标行再关标志位。
            val target = seekTargetLine
            isDragSeeking = false
            if (target in lyrics.lines.indices) {"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L23-a2', False, 'anchor not found')

old = """                            // 长按 → 进入跳转模式
                            isDragSeeking = true
                            seekTargetLine = -1"""
new = """                            // 长按 → 进入跳转模式
                            isDragSeeking = true"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L23-a3', False, 'anchor not found')

old = """                        onDragEnd = {
                            // 松手 → 跳到目标行起始时间播放并退出跳转模式
                            val target = seekTargetLine
                            isDragSeeking = false
                            seekTargetLine = -1
                            if (target in lyrics.lines.indices) {"""
new = """                        onDragEnd = {
                            // 松手 → 跳到目标行起始时间播放并退出跳转模式
                            // ⚠️ 同 L23：先读派生值，再关 isDragSeeking
                            val target = seekTargetLine
                            isDragSeeking = false
                            if (target in lyrics.lines.indices) {"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L23-a4', False, 'anchor not found')

old = """                        onDragCancel = {
                            isDragSeeking = false
                            seekTargetLine = -1
                        }"""
new = """                        onDragCancel = {
                            isDragSeeking = false
                        }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L23-a5', False, 'anchor not found')

expect('L23-noassign', 'seekTargetLine = ' not in S, 'still assigning')
if 'import androidx.compose.runtime.derivedStateOf' not in S:
    anchor = 'import androidx.compose.runtime.Composable\n'
    if anchor in S:
        S = S.replace(anchor, anchor + 'import androidx.compose.runtime.derivedStateOf\n', 1)
    else:
        expect('L23-import', False)
expect('L23-import-ok', 'import androidx.compose.runtime.derivedStateOf' in S)
if FAILS:
    print('NOT WRITTEN(4)'); sys.exit(1)
save('app/src/main/java/com/nasmusic/tv/ui/components/LyricsView.kt', S, eol)

# ================= L24 =================
apply1('L24', 'app/src/main/java/com/nasmusic/tv/ui/components/BaiduDirPickerDialog.kt',
"""                                items(dirs) { dir ->""",
"""                                // L24：补稳定 key（同目录内文件名唯一）。缺 key 时
                                // 每次换目录/刷新都会把整列 item 当作新元素重建，
                                // D-Pad 焦点在刷新后落回第一项。
                                items(dirs, key = { it.serverFilename }) { dir ->""",
extra_check='items(dirs, key = { it.serverFilename })')

# ================= L25 =================
S, eol = load('app/src/main/java/com/nasmusic/tv/ui/screens/netdisk/NetdiskScreen.kt')
old = """    // v2.36.0 竖屏（方案 §4.5 / P0-19）：页 padding 32→16；搜索框 420dp → 整行（原头部三件套一行必溢出）
    val isPhonePortrait = LocalUiMode.current == UiMode.PhonePortrait"""
new = """    // v2.36.0 竖屏（方案 §4.5 / P0-19）：页 padding 32→16；搜索框 420dp → 整行（原头部三件套一行必溢出）
    val isPhonePortrait = LocalUiMode.current == UiMode.PhonePortrait
    // L25：两个网格的状态都必须**跨 when 分支存活**。原先搜索网格根本没传 state、
    // 目录网格在分支内部 remember ⇒ 清空搜索词 / 进出目录时状态机被销毁，
    // 滚动位置与 D-Pad 焦点一起回到顶部（长目录里等于每次都要重新翻）。
    // 两个网格局分开保存，各自记住自己的位置。
    val searchGridState = rememberLazyGridState()
    val dirGridState = rememberLazyGridState()"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L25-decl', False, 'anchor not found')

old = """                    LazyVerticalGrid(
                        columns = songGridColumns(),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 操作栏：播放全部 + 加入队列"""
new = """                    LazyVerticalGrid(
                        columns = songGridColumns(),
                        state = searchGridState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 操作栏：播放全部 + 加入队列"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L25-search', False, 'anchor not found')

old = """                    val gridState = rememberLazyGridState()
                    LaunchedEffect(gridState, hasMore, dirFiles.size) {
                        if (!hasMore) return@LaunchedEffect
                        snapshotFlow {
                            val info = gridState.layoutInfo"""
new = """                    val gridState = dirGridState
                    LaunchedEffect(gridState, hasMore, dirFiles.size) {
                        if (!hasMore) return@LaunchedEffect
                        snapshotFlow {
                            val info = gridState.layoutInfo"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L25-dir', False, 'anchor not found')
expect('L25-states', S.count('rememberLazyGridState()') == 2,
       'n=%d' % S.count('rememberLazyGridState()'))
if FAILS:
    print('NOT WRITTEN(5)'); sys.exit(1)
save('app/src/main/java/com/nasmusic/tv/ui/screens/netdisk/NetdiskScreen.kt', S, eol)

# ================= L26 =================
S, eol = load('app/src/main/java/com/nasmusic/tv/ui/screens/settings/CacheSettingsSection.kt')
old = """    var refreshKey by remember { mutableStateOf(0) }"""
new = """    var refreshKey by remember { mutableStateOf(0) }
    // L26：19 条登记项的分组是**编译期常量关系**，与运行时无关。
    // 原实现在组合期对 5 个 group 各跑一次 `CacheInventory.entries.filter { ... }`
    //（5 × 19 = 95 次比较，每次重组都重来，且每次 filter 还新建一个 List）。
    // remember 一次分组结果即可。
    val entriesByGroup = remember { CacheInventory.entries.groupBy { it.group } }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L26-remember', False, 'anchor not found')

old = """                CacheInventory.entries.filter { it.group == group }.forEach { entry ->"""
new = """                entriesByGroup[group].orEmpty().forEach { entry ->"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L26-use', False, 'anchor not found')
expect('L26-ok', 'entriesByGroup[group].orEmpty()' in S)
if FAILS:
    print('NOT WRITTEN(6)'); sys.exit(1)
save('app/src/main/java/com/nasmusic/tv/ui/screens/settings/CacheSettingsSection.kt', S, eol)
print('ALL OK')
