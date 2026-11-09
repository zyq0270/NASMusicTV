#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M38: AppRoot 顶层只订 currentSong；M39: SettingsBranch 高频下载流按分区订阅"""
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

# ================= M38: AppRoot =================
REL = 'app/src/main/java/com/nasmusic/tv/ui/components/AppRoot.kt'
S, eol = load(REL)

old = """    val playerState by viewModel.playerVM.playerState.collectAsState()
    val currentSong = playerState.currentSong"""
new = """    // M38：顶层**只订 currentSong**。原先整体收集 playerState（queue / currentIndex /
    // currentSong 三元组），而 AppRoot 只用得到 currentSong ⇒ 往队列里加一首歌、播完一首换
    // currentIndex，都会驱动整棵 UI 树（导航栏 + 各 Screen 分支）重组。三元组本身仍保持
    // 「同帧原子发布」（见 PlayerState 的 KDoc），这里只是在消费端做投影 + 去重。
    val currentSong by remember(viewModel) {
        viewModel.playerVM.playerState
            .map { it.currentSong }
            .distinctUntilChanged()
    }.collectAsState(initial = viewModel.playerVM.playerState.value.currentSong)"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M38-body', False, 'anchor not found')
expect('M38-noplayerstate', 'val playerState by viewModel.playerVM.playerState.collectAsState()' not in S)

# 导入 map / distinctUntilChanged
if 'import kotlinx.coroutines.flow.map' not in S:
    anchor = 'import kotlinx.coroutines.launch\n'
    if anchor in S:
        S = S.replace(anchor,
                      'import kotlinx.coroutines.flow.distinctUntilChanged\n'
                      'import kotlinx.coroutines.flow.map\n'
                      + anchor, 1)
    else:
        expect('M38-import-anchor', False)
expect('M38-import', 'import kotlinx.coroutines.flow.map' in S and
       'import kotlinx.coroutines.flow.distinctUntilChanged' in S)

# VisualizerOverlay：说明 1Hz progress 为何必须留在组合期
old = """    val lyrics by viewModel.currentLyrics.collectAsState(initial = null)
    val progress by viewModel.playerVM.progress.collectAsState(initial = 0L)"""
new = """    val lyrics by viewModel.currentLyrics.collectAsState(initial = null)
    // M38：这条 1 Hz 的 progress **必须**在组合期收集 —— 它唯一的用途是在组合期
    // `computeLyricInfo(lyrics, progressMs, …)` 里定位「当前唱到第几行」/ 行内进度，
    // 属于必须驱动重组的输入（不是可以挪进 draw 阶段的动画量，那类量走 `positionMs` provider）。
    // 唯一的收敛点是把订阅范围收到本 overlay（已是如此），不再上游到 AppRoot 顶层。
    val progress by viewModel.playerVM.progress.collectAsState(initial = 0L)"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M38-overlay-doc', False, 'anchor not found')

if FAILS:
    print('NOT WRITTEN(1)')
    sys.exit(1)
save(REL, S, eol)

# ================= M39: SettingsBranch =================
REL = 'app/src/main/java/com/nasmusic/tv/ui/components/branches/SettingsBranch.kt'
S, eol = load(REL)

old = """                    val modelDownloaded by viewModel.downloadVM.modelDownloaded.collectAsState()
                    // === F2-2b 睡眠定时器（常驻按钮状态，本页内收集） ===
                    val sleepTimerSt by viewModel.playerVM.sleepTimerState.collectAsState()
                    val modelDownloading by viewModel.downloadVM.modelDownloading.collectAsState()
                    val modelDownloadProgress by viewModel.downloadVM.modelDownloadProgress.collectAsState()
                    val modelDownloadedMB by viewModel.downloadVM.modelDownloadedMB.collectAsState()
                    val modelTotalMB by viewModel.downloadVM.modelTotalMB.collectAsState()
"""
new = """                    // v2.36.0 竖屏设置两级页：当前进入的分区（null = 一级列表）。
                    // 状态归 NavigationViewModel —— AppRoot 的 BACK handler 需要读它（方案 §6.2 / K2）
                    //
                    // M39：这条从下面提前到这里，是因为紧接着的下载进度要按「当前分区」决定是否订阅。
                    val settingsSection by viewModel.navVM.settingsSection.collectAsState(initial = null)
                    val modelDownloaded by viewModel.downloadVM.modelDownloaded.collectAsState()
                    // === F2-2b 睡眠定时器（常驻按钮状态，本页内收集） ===
                    val sleepTimerSt by viewModel.playerVM.sleepTimerState.collectAsState()
                    // M39：下载进度是本分支**唯一的高频流**（下载器每写一个分块就发一次），
                    // 而它只被「歌词 / K 歌」分区消费 —— 现在只在那一栏打开时才真正订阅。
                    // 原先无条件收集 ⇒ 下载期间每秒数次驱动整棵设置页重组；本分支顶层一共 ~50 个
                    // StateFlow，但其余全是低频（用户操作 / 一次性事实），逐个下沉到分区子 composable
                    // 的改动量与回归面远大于收益，故只把高频这一组拆掉（下沉口径见 F-2 / M7）。
                    val karaokeSectionOpen = settingsSection == SettingsSection.LYRICS_KARAOKE
                    val modelDownloading: Flow<Boolean> = remember(karaokeSectionOpen) {
                        if (karaokeSectionOpen) viewModel.downloadVM.modelDownloading else flowOf(false)
                    }
                    val modelDownloadProgressFlow: Flow<Float> = remember(karaokeSectionOpen) {
                        if (karaokeSectionOpen) viewModel.downloadVM.modelDownloadProgress else flowOf(0f)
                    }
                    val modelDownloadedMBFlow: Flow<Long> = remember(karaokeSectionOpen) {
                        if (karaokeSectionOpen) viewModel.downloadVM.modelDownloadedMB else flowOf(0L)
                    }
                    val modelTotalMBFlow: Flow<Long> = remember(karaokeSectionOpen) {
                        if (karaokeSectionOpen) viewModel.downloadVM.modelTotalMB else flowOf(0L)
                    }
                    val modelDownloadingVal by modelDownloading.collectAsState(initial = false)
                    val modelDownloadProgress by modelDownloadProgressFlow.collectAsState(initial = 0f)
                    val modelDownloadedMB by modelDownloadedMBFlow.collectAsState(initial = 0L)
                    val modelTotalMB by modelTotalMBFlow.collectAsState(initial = 0L)
"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M39-block', False, 'anchor not found')

# 删掉原来那条 settingsSection 声明（含两行注释）
old = """                    // v2.36.0 竖屏设置两级页：当前进入的分区（null = 一级列表）。
                    // 状态归 NavigationViewModel —— AppRoot 的 BACK handler 需要读它（方案 §6.2 / K2）
                    val settingsSection by viewModel.navVM.settingsSection.collectAsState(initial = null)
                    // v2.36.0 屏幕方向（L1 全局策略）"""
new = """                    // v2.36.0 屏幕方向（L1 全局策略）"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M39-dedup', False, 'anchor not found')
expect('M39-single-decl',
       S.count('val settingsSection by viewModel.navVM.settingsSection.collectAsState') == 1,
       'n=%d' % S.count('val settingsSection by viewModel.navVM.settingsSection.collectAsState'))

# 变量名使用点：modelDownloading 现在改名成 modelDownloadingVal，需要在 SettingsScreen 调用处替换
expect('M39-rename-use', 'modelDownloading = modelDownloading,' in S,
       'call site not found')
S = S.replace('modelDownloading = modelDownloading,', 'modelDownloading = modelDownloadingVal,')

# 导入
if 'import kotlinx.coroutines.flow.Flow' not in S:
    anchor = 'import kotlinx.coroutines.launch\n'
    if anchor in S:
        S = S.replace(anchor,
                      'import kotlinx.coroutines.flow.Flow\n'
                      'import kotlinx.coroutines.flow.flowOf\n'
                      + anchor, 1)
    else:
        expect('M39-import-anchor', False)
expect('M39-import', 'import kotlinx.coroutines.flow.flowOf' in S and
       'import kotlinx.coroutines.flow.Flow' in S)

if FAILS:
    print('NOT WRITTEN(2)')
    sys.exit(1)
save(REL, S, eol)
print('ALL OK')
