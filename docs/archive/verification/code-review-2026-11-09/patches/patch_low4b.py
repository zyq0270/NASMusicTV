#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Low 批次 4b（UI 剩余）：L22 / L23 / L24 / L25 / L26"""
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

# ================= L22 =================
REL = 'app/src/main/java/com/nasmusic/tv/ui/components/CoverCarousel.kt'
S, eol = load(REL)
OLD22 = "if (!permanentlyFailed) { permanentlyFailed = true; onAllFailed() }"
NEW22 = "ReportAllFailedOnce(permanentlyFailed) { permanentlyFailed = true; onAllFailed() }"
n22 = S.count(OLD22)
if n22 == 4:
    S = S.replace(OLD22, NEW22)
elif 'ReportAllFailedOnce(' in S:
    print('SKIP L22 (already applied)')
else:
    expect('L22-sites', False, 'count=%d (expect 4)' % n22)
expect('L22-all', OLD22 not in S)

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
if 'private fun ReportAllFailedOnce' not in S:
    if anchor in S:
        S = S.replace(anchor, helper, 1)
    else:
        expect('L22-helper', False, 'anchor not found')
expect('L22-helper-use', S.count('ReportAllFailedOnce(') == 5,
       'n=%d' % S.count('ReportAllFailedOnce('))
if FAILS:
    print('NOT WRITTEN(1)'); sys.exit(1)
save(REL, S, eol)

# ================= L23 =================
REL = 'app/src/main/java/com/nasmusic/tv/ui/components/LyricsView.kt'
S, eol = load(REL)
if 'seekTargetLineState' in S:
    print('SKIP L23 (already applied)')
else:
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
    // 组合期写状态是 Compose 明令禁止的副作用（每次重组都写一次，还可能 写→重组→再写 抖动），
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

    pairs = [
        ("""        if (!longPressSeekEnabled && listDragged && listState.isScrollInProgress && !isDragSeeking) {
            isDragSeeking = true
            seekTargetLine = -1
        }""",
         """        if (!longPressSeekEnabled && listDragged && listState.isScrollInProgress && !isDragSeeking) {
            isDragSeeking = true
        }"""),
        ("""        if (!longPressSeekEnabled && !listDragged && isDragSeeking) {
            isDragSeeking = false
            val target = seekTargetLine
            seekTargetLine = -1
            if (target in lyrics.lines.indices) {""",
         """        if (!longPressSeekEnabled && !listDragged && isDragSeeking) {
            // ⚠️ 顺序不能反：派生值在 isDragSeeking=false 之后立即变成 -1，
            //    必须先读目标行再关标志位。
            val target = seekTargetLine
            isDragSeeking = false
            if (target in lyrics.lines.indices) {"""),
        ("""                            // 长按 → 进入跳转模式
                            isDragSeeking = true
                            seekTargetLine = -1""",
         """                            // 长按 → 进入跳转模式
                            isDragSeeking = true"""),
        ("""                        onDragEnd = {
                            // 松手 → 跳到目标行起始时间播放并退出跳转模式
                            val target = seekTargetLine
                            isDragSeeking = false
                            seekTargetLine = -1
                            if (target in lyrics.lines.indices) {""",
         """                        onDragEnd = {
                            // 松手 → 跳到目标行起始时间播放并退出跳转模式
                            // ⚠️ 同 L23：先读派生值，再关 isDragSeeking
                            val target = seekTargetLine
                            isDragSeeking = false
                            if (target in lyrics.lines.indices) {"""),
        ("""                        onDragCancel = {
                            isDragSeeking = false
                            seekTargetLine = -1
                        }""",
         """                        onDragCancel = {
                            isDragSeeking = false
                        }"""),
    ]
    for i, (o, n) in enumerate(pairs):
        if o in S:
            S = S.replace(o, n, 1)
        else:
            expect('L23-a%d' % i, False, 'anchor not found')

    expect('L23-noassign', 'seekTargetLine = ' not in S, 'still assigning')
    if 'import androidx.compose.runtime.derivedStateOf' not in S:
        anchor_imp = 'import androidx.compose.runtime.Composable\n'
        if anchor_imp in S:
            S = S.replace(anchor_imp, anchor_imp + 'import androidx.compose.runtime.derivedStateOf\n', 1)
        else:
            expect('L23-import', False)
    expect('L23-import-ok', 'import androidx.compose.runtime.derivedStateOf' in S)

if FAILS:
    print('NOT WRITTEN(2)'); sys.exit(1)
save(REL, S, eol)

# ================= L24 =================
REL = 'app/src/main/java/com/nasmusic/tv/ui/components/BaiduDirPickerDialog.kt'
S, eol = load(REL)
old = """                                items(dirs) { dir ->"""
new = """                                // L24：补稳定 key（同目录内文件名唯一）。缺 key 时
                                // 每次换目录/刷新都会把整列 item 当作新元素重建，
                                // D-Pad 焦点在刷新后落回第一项。
                                items(dirs, key = { it.serverFilename }) { dir ->"""
if old in S:
    S = S.replace(old, new, 1)
else:
    print('SKIP L24 (already applied)')
expect('L24-ok', 'items(dirs, key = { it.serverFilename })' in S)
if FAILS:
    print('NOT WRITTEN(3)'); sys.exit(1)
save(REL, S, eol)

# ================= L25 =================
REL = 'app/src/main/java/com/nasmusic/tv/ui/screens/netdisk/NetdiskScreen.kt'
S, eol = load(REL)
if 'searchGridState' in S:
    print('SKIP L25 (already applied)')
else:
    old = """    // v2.36.0 竖屏（方案 §4.5 / P0-19）：页 padding 32→16；搜索框 420dp → 整行（原头部三件套一行必溢出）
    val isPhonePortrait = LocalUiMode.current == UiMode.PhonePortrait"""
    new = """    // v2.36.0 竖屏（方案 §4.5 / P0-19）：页 padding 32→16；搜索框 420dp → 整行（原头部三件套一行必溢出）
    val isPhonePortrait = LocalUiMode.current == UiMode.PhonePortrait
    // L25：两个网格的状态都必须**跨 when 分支存活**。原先搜索网格根本没传 state、
    // 目录网格在分支内部 remember ⇒ 清空搜索词 / 进出目录时状态机被销毁，
    // 滚动位置与 D-Pad 焦点一起回到顶部（长目录里等于每次都要重新翻）。
    // 两个网格分开保存，各自记住自己的位置。
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

    old = """                    val gridState = rememberLazyGridState()"""
    new = """                    val gridState = dirGridState"""
    if old in S:
        S = S.replace(old, new, 1)
    else:
        expect('L25-dir', False, 'anchor not found')

expect('L25-states', S.count('rememberLazyGridState()') == 2,
       'n=%d' % S.count('rememberLazyGridState()'))
if FAILS:
    print('NOT WRITTEN(4)'); sys.exit(1)
save(REL, S, eol)

# ================= L26 =================
REL = 'app/src/main/java/com/nasmusic/tv/ui/screens/settings/CacheSettingsSection.kt'
S, eol = load(REL)
if 'entriesByGroup' in S:
    print('SKIP L26 (already applied)')
else:
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
    print('NOT WRITTEN(5)'); sys.exit(1)
save(REL, S, eol)
print('ALL OK')
