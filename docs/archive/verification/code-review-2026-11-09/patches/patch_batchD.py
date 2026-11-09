#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Batch D: M24 / M25 / M27 / M28 / M29 / M30"""
import io, sys

FAILS = []

def read(rel):
    with io.open(rel, 'r', encoding='utf-8', newline='') as f:
        return f.read()

def eol_of(s):
    crlf = s.count('\r\n')
    lf = s.count('\n') - crlf
    return '\r\n' if crlf > lf else '\n'

def write(rel, s, eol):
    with io.open(rel, 'w', encoding='utf-8', newline='') as f:
        f.write(s)
    print('OK', rel, 'crlf' if eol == '\r\n' else 'lf')

def expect(tag, cond, extra=''):
    if not cond:
        FAILS.append(tag)
        print('FAIL', tag, extra)

# ============ M24: QueueScreen.QueueItemActionMenu 注册 BACK ============
rel = 'app/src/main/java/com/nasmusic/tv/ui/screens/QueueScreen.kt'
S = read(rel); eol = eol_of(S); orig = S

old = """    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {"""
new = """    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    // M24：本菜单是自建 Box 覆盖层（不是真 Dialog 窗口），BACK 必须显式接管，
    // 否则手机竖屏按返回键会穿透到 Level 1.5（列表回顶）/ Level 2（页面返回）——
    // 用户看到的是「菜单还在、页面已经退了一层」。约定见 DialogBackHandler.kt 文件头。
    RegisterDialogBackHandler(onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M24-body', False)
expect('M24-use', S.count('RegisterDialogBackHandler(onDismiss)') == 1,
       'count=%d' % S.count('RegisterDialogBackHandler(onDismiss)'))

# import：插在 com.nasmusic.tv.R 之后（保持字母序）
old_imp = 'import com.nasmusic.tv.R\n'
new_imp = 'import com.nasmusic.tv.R\nimport com.nasmusic.tv.ui.RegisterDialogBackHandler\n'
if old_imp in S and 'import com.nasmusic.tv.ui.RegisterDialogBackHandler' not in S:
    S = S.replace(old_imp, new_imp, 1)
expect('M24-import', 'import com.nasmusic.tv.ui.RegisterDialogBackHandler' in S)
if S != orig:
    write(rel, S, eol)

# ============ M25: NowPlayingScreen.SourceTag 纯展示不走 FocusableSurface ============
rel = 'app/src/main/java/com/nasmusic/tv/ui/screens/NowPlayingScreen.kt'
S = read(rel); eol = eol_of(S); orig = S

old = """    // L11 修复：onClick 允许 null —— 纯展示标签（如 LOCAL_FILE 来源标记）不应可聚焦
    onClick: (() -> Unit)?
) {
    FocusableSurface(
        onClick = onClick?.let { cb -> { if (available) cb() } } ?: {},
        modifier = Modifier,
        shape = RoundedCornerShape(6.dp),
        focusedScale = 1.1f,
        animationDurationMs = 150,
        containerColor = if (!available) NasMusicColors.Surface.copy(alpha = 0.3f)
                         else if (selected) NasMusicColors.Primary
                         else NasMusicColors.Surface.copy(alpha = 0.8f),
        focusedContainerColor = if (selected) NasMusicColors.Primary
                                else NasMusicColors.Primary.copy(alpha = 0.3f),
        contentColor = if (!available) NasMusicColors.TextSecondary.copy(alpha = 0.4f)
                       else if (selected) NasMusicColors.OnPrimary
                       else NasMusicColors.TextPrimary,
        focusedContentColor = if (selected) NasMusicColors.OnPrimary else NasMusicColors.Primary,
        pressedScale = 0.95f,
        showFocusBorder = available && onClick != null  // L11：无行为标签不画焦点框
    ) {
        Text(
            text = label,
            color = if (!available) NasMusicColors.TextSecondary.copy(alpha = 0.4f)
                    else if (selected) NasMusicColors.OnPrimary
                    else NasMusicColors.TextPrimary,
            fontSize = FontSize.small(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}"""
new = """    // L11 修复：onClick 允许 null —— 纯展示标签（如 LOCAL_FILE 来源标记）不应可聚焦
    onClick: (() -> Unit)?
) {
    val tagContainer = if (!available) NasMusicColors.Surface.copy(alpha = 0.3f)
                       else if (selected) NasMusicColors.Primary
                       else NasMusicColors.Surface.copy(alpha = 0.8f)
    val tagContent = if (!available) NasMusicColors.TextSecondary.copy(alpha = 0.4f)
                     else if (selected) NasMusicColors.OnPrimary
                     else NasMusicColors.TextPrimary

    // M25：onClick == null 的纯展示标签此前仍走 FocusableSurface，只是把回调兜底成空 lambda
    // 并把焦点框关掉 —— 按钮照样参与 D-Pad 焦点遍历，遥控器会在一个「按了没反应」的格子上
    // 停住（用户以为焦点丢了）。现在直接分叉成不可聚焦的 Box，焦点链路里彻底不出现它。
    if (onClick == null) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(tagContainer)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(text = label, color = tagContent, fontSize = FontSize.small())
        }
        return
    }

    FocusableSurface(
        onClick = { if (available) onClick() },
        modifier = Modifier,
        shape = RoundedCornerShape(6.dp),
        focusedScale = 1.1f,
        animationDurationMs = 150,
        containerColor = tagContainer,
        focusedContainerColor = if (selected) NasMusicColors.Primary
                                else NasMusicColors.Primary.copy(alpha = 0.3f),
        contentColor = tagContent,
        focusedContentColor = if (selected) NasMusicColors.OnPrimary else NasMusicColors.Primary,
        pressedScale = 0.95f,
        showFocusBorder = available  // L11：不可用的来源不画焦点框
    ) {
        Text(
            text = label,
            color = tagContent,
            fontSize = FontSize.small(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M25-body', False, 'anchor not found')
expect('M25-fork', 'if (onClick == null) {' in S)
expect('M25-nofallback', 'onClick?.let { cb -> { if (available) cb() } } ?: {}' not in S)

# ============ M28: 竖屏封面页 ⓘ 挪到全屏 clickable 之后 ============
old = """                        // D8：封面右上角 ⓘ 图标按钮 → 歌曲信息（不用长按）
                        Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                            PortraitTopBarButton(
                                label = "\\u24D8",
                                contentDescription = stringResource(R.string.np_song_info_cd),
                                onClick = { showInfoPanel = !showInfoPanel },
                            )
                        }
                        // 点击封面 → 沉浸模式
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(onClick = onToggleImmersive)
                        )"""
new = """                        // 点击封面 → 沉浸模式
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(onClick = onToggleImmersive)
                        )
                        // D8：封面右上角 ⓘ 图标按钮 → 歌曲信息（不用长按）
                        // M28：本层必须声明在上面的全屏 clickable **之后**。Compose 的命中测试按
                        // 声明顺序**倒序**进行，后声明者先收到事件；此前 ⓘ 声明在前，被全屏
                        // clickable 完全盖住 ⇒ 点 ⓘ 只会进沉浸模式，信息面板永远打不开。
                        Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                            PortraitTopBarButton(
                                label = "\\u24D8",
                                contentDescription = stringResource(R.string.np_song_info_cd),
                                onClick = { showInfoPanel = !showInfoPanel },
                            )
                        }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M28-body', False, 'anchor not found')
expect('M28-order',
       S.find('.clickable(onClick = onToggleImmersive)') < S.find('label = "\\u24D8"'),
       'immersive before info?')

if S != orig:
    write(rel, S, eol)

# ============ M27: PlaylistManagementScreen DisposableEffect 陈旧 isPhonePortrait ============
rel = 'app/src/main/java/com/nasmusic/tv/ui/screens/PlaylistManagementScreen.kt'
S = read(rel); eol = eol_of(S); orig = S

old = """    val isPhonePortrait = LocalUiMode.current == UiMode.PhonePortrait
    var portraitPlaylistName by remember { mutableStateOf<String?>(null) }

    // Level 1.5: 任意列表已滚动时按 BACK 先回顶并聚焦第一个
    DisposableEffect(Unit) {
        val handler: () -> Boolean = {
            // 竖屏二级页（歌曲明细）先回一级（播放列表）
            if (isPhonePortrait && portraitPlaylistName != null) {"""
new = """    val isPhonePortrait = LocalUiMode.current == UiMode.PhonePortrait
    var portraitPlaylistName by remember { mutableStateOf<String?>(null) }

    // M27：`DisposableEffect(Unit)` 只在首次组合执行一次，闭包会把首帧的 isPhonePortrait
    // 固化下来 —— 旋屏后 handler 仍按旧形态判断（该先回一级时不回、不该回时乱回）。
    // 用 rememberUpdatedState 把当前值喂给已注册的闭包，避免每次旋屏都重建 handler
    // （重建会先 onDispose 置 null、再注册，中间存在 BACK 穿透窗口，见 DialogBackHandler 注释）。
    val portraitModeNow by rememberUpdatedState(isPhonePortrait)

    // Level 1.5: 任意列表已滚动时按 BACK 先回顶并聚焦第一个
    DisposableEffect(Unit) {
        val handler: () -> Boolean = {
            // 竖屏二级页（歌曲明细）先回一级（播放列表）
            if (portraitModeNow && portraitPlaylistName != null) {"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M27-body', False, 'anchor not found')
expect('M27-use', 'if (portraitModeNow && portraitPlaylistName != null)' in S)

if 'import androidx.compose.runtime.rememberUpdatedState' not in S:
    anchor = 'import androidx.compose.runtime.remember\n'
    if anchor in S:
        S = S.replace(anchor, anchor + 'import androidx.compose.runtime.rememberUpdatedState\n', 1)
    else:
        expect('M27-import-anchor', False)
expect('M27-import', 'import androidx.compose.runtime.rememberUpdatedState' in S)

if S != orig:
    write(rel, S, eol)

# ============ M29: 列表 key 加索引前缀 ============
for rel in ('app/src/main/java/com/nasmusic/tv/ui/screens/AlbumDetailScreen.kt',
            'app/src/main/java/com/nasmusic/tv/ui/screens/ArtistDetailScreen.kt'):
    S = read(rel); eol = eol_of(S); orig = S
    old = 'itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->'
    new = ('// M29：歌单/专辑/电台里同一首歌完全可能重复出现，只用 song.id 做 key 会撞\n'
           '                    // 「Key was already used」直接崩溃 ⇒ 索引前缀保证唯一。\n'
           '                    itemsIndexed(songs, key = { index, song -> "${index}_${song.id}" }) { index, song ->')
    if old in S:
        S = S.replace(old, new, 1)
    else:
        expect('M29-' + rel.split('/')[-1], False)
    expect('M29-use-' + rel.split('/')[-1], '"${index}_${song.id}"' in S)
    if S != orig:
        write(rel, S, eol)

rel = 'app/src/main/java/com/nasmusic/tv/ui/screens/WeatherRadioScreen.kt'
S = read(rel); eol = eol_of(S); orig = S
old = '                    key = { _, song -> song.id }'
new = '                    // M29：同 AlbumDetailScreen —— 索引前缀防重复曲目 key 冲突\n                    key = { index, song -> "${index}_${song.id}" },'
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M29-Weather', False)
if S != orig:
    write(rel, S, eol)

# ============ M30: PlaylistViewModel 错误消息接上 UI 通道 ============
rel = 'app/src/main/java/com/nasmusic/tv/ui/viewmodel/PlaylistViewModel.kt'
S = read(rel); eol = eol_of(S); orig = S

old = """    // ---- 常规错误消息（沿用 MainViewModel 的 errorMessage 通道）----
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private fun showError(msg: String) {
        _errorMessage.value = msg
    }

    /** 消费错误消息（UI 显示后调用，由 MainViewModel 的 errorMessage 消费方统一接管时可移除） */
    fun consumeErrorMessage() {
        _errorMessage.value = null
    }"""
new = """    /**
     * 操作反馈出口（M30）：由 `MainViewModel` 的接线 init 注入 `showError`。
     *
     * ⚠️ 此前这里只往一个**无人订阅**的 `_errorMessage` 里写，歌单创建/重命名/删除/加歌失败
     * 对用户完全静默（点了没反应，只有 logcat 有记录）。同一工程里
     * `SearchViewModel` / `NetworkMusicViewModel` / `PlayerViewModel` 都已是这套注入约定。
     */
    var showMessage: ((String) -> Unit)? = null

    /** UI 展示用的错误文案（无注入方时的兜底通道，例如单测里直接 new 本类） */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private fun showError(msg: String) {
        val sink = showMessage
        if (sink != null) sink(msg) else _errorMessage.value = msg
    }

    /** 消费兜底通道里的错误消息（仅在 [showMessage] 未注入时才有内容） */
    fun consumeErrorMessage() {
        _errorMessage.value = null
    }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M30-body', False)
expect('M30-sink', 'var showMessage: ((String) -> Unit)? = null' in S)
if S != orig:
    write(rel, S, eol)

rel = 'app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt'
S = read(rel); eol = eol_of(S); orig = S
old = """        // ---- NetworkMusicViewModel 接线 ----
        netVM.onPlayQueue = { songs, startIndex -> playQueue(songs, startIndex) }
        netVM.showMessage = { showError(it) }"""
new = """        // M30：PlaylistViewModel 的歌单 CRUD 失败也要能到达用户（此前只写无人订阅的 StateFlow）
        playlistVM.showMessage = { showError(it) }

        // ---- NetworkMusicViewModel 接线 ----
        netVM.onPlayQueue = { songs, startIndex -> playQueue(songs, startIndex) }
        netVM.showMessage = { showError(it) }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M30-wire', False, 'anchor not found')
expect('M30-wire-use', 'playlistVM.showMessage = { showError(it) }' in S)
if S != orig:
    write(rel, S, eol)

print()
if FAILS:
    print('FAILED:', FAILS)
    sys.exit(1)
print('ALL OK')
