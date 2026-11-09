#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Batch D 补充：NowPlayingScreen（纯 CRLF 文件），先归一化到 LF 打补丁再写回 CRLF。"""
import io, sys

REL = 'app/src/main/java/com/nasmusic/tv/ui/screens/NowPlayingScreen.kt'
FAILS = []

def expect(tag, cond, extra=''):
    if not cond:
        FAILS.append(tag)
        print('FAIL', tag, extra)

raw = io.open(REL, 'r', encoding='utf-8', newline='').read()
crlf = raw.count('\r\n')
eol = '\r\n' if crlf > (raw.count('\n') - crlf) else '\n'
S = raw.replace('\r\n', '\n')

# ---------------- M25 ----------------
old_m25 = """    // L11 修复：onClick 允许 null —— 纯展示标签（如 LOCAL_FILE 来源标记）不应可聚焦
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

new_m25 = """    // L11 修复：onClick 允许 null —— 纯展示标签（如 LOCAL_FILE 来源标记）不应可聚焦
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

if old_m25 in S:
    S = S.replace(old_m25, new_m25, 1)
else:
    expect('M25-body', False, 'anchor not found')
expect('M25-fork', 'if (onClick == null) {' in S)
expect('M25-nofallback', 'onClick?.let { cb -> { if (available) cb() } } ?: {}' not in S)

# ---------------- M28 ----------------
old_m28 = """                        // D8：封面右上角 ⓘ 图标按钮 → 歌曲信息（不用长按）
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

new_m28 = """                        // 点击封面 → 沉浸模式
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(onClick = onToggleImmersive)
                        )
                        // D8：封面右上角 ⓘ 图标按钮 → 歌曲信息（不用长按）
                        // M28：本层必须声明在上面的全屏 clickable **之后**。Compose 的命中测试按
                        // 声明顺序倒序进行，后声明者先拿到事件；此前 ⓘ 声明在前，被全屏 clickable
                        // 完全盖住 —— 点 ⓘ 只会进沉浸模式，歌曲信息面板永远打不开。
                        Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                            PortraitTopBarButton(
                                label = "\\u24D8",
                                contentDescription = stringResource(R.string.np_song_info_cd),
                                onClick = { showInfoPanel = !showInfoPanel },
                            )
                        }"""

if old_m28 in S:
    S = S.replace(old_m28, new_m28, 1)
else:
    expect('M28-body', False, 'anchor not found')
i_imm = S.find('.clickable(onClick = onToggleImmersive)')
i_info = S.find('label = "\\u24D8"')
expect('M28-order', 0 <= i_imm < i_info, 'imm=%d info=%d' % (i_imm, i_info))

# ---------------- 写回 ----------------
if FAILS:
    print('NOT WRITTEN')
    sys.exit(1)

out = S.replace('\n', eol) if eol == '\r\n' else S
io.open(REL, 'w', encoding='utf-8', newline='').write(out)
print('OK', REL, 'crlf' if eol == '\r\n' else 'lf')
print('ALL OK')
