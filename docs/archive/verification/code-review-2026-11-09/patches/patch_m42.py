#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M42: JamendoTab 顶部筛选行竖屏分行 + tag 行横滑"""
import io, sys

REL = 'app/src/main/java/com/nasmusic/tv/ui/screens/library/JamendoTab.kt'
FAILS = []

def expect(tag, cond, extra=''):
    if not cond:
        FAILS.append(tag)
        print('FAIL', tag, extra)

raw = io.open(REL, 'r', encoding='utf-8', newline='').read()
crlf = raw.count('\r\n')
eol = '\r\n' if crlf > (raw.count('\n') - crlf) else '\n'
S = raw.replace('\r\n', '\n')

# ---- 1) 竖屏判定 ----
old = """    // 2026-09-25 审查修复（#15）：裸 LazyListState() 每次重组新建，滚动位置被重置回顶
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
"""
new = """    // 2026-09-25 审查修复（#15）：裸 LazyListState() 每次重组新建，滚动位置被重置回顶
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // M42：340dp 搜索框与「热门 + N 个预设 tag」挤在同一行 —— 360dp 竖屏上 tag 全部溢出屏幕，
    // 且 Row 不可滚动、D-Pad 也移不过去，等于这些筛选器在手机上不存在。
    // 与 RadioTab（方案 §2.4 / P0-23）同一套处理：竖屏改「搜索框整行 + tag 行横滑」。
    val isPhonePortrait = LocalUiMode.current == UiMode.PhonePortrait
"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M42-detect', False, 'anchor not found')

# ---- 2) 抽出搜索框与 tag 行，按形态分流 ----
old = """    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶部筛选行 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchField(
                query = jamendoActiveTag,
                placeholder = stringResource(R.string.network_search_hint),
                onOpenSearch = { showSearchDialog = true },
                onClear = { onLoadHot() },
                modifier = Modifier.width(340.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))

            val hotSelected = jamendoActiveTag.isBlank()"""
new = """    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶部筛选行 ──
        val jamendoSearchField: @Composable (Modifier) -> Unit = { m ->
            SearchField(
                query = jamendoActiveTag,
                placeholder = stringResource(R.string.network_search_hint),
                onOpenSearch = { showSearchDialog = true },
                onClear = { onLoadHot() },
                modifier = m
            )
        }
        val jamendoTagRow: @Composable (Modifier) -> Unit = { m ->
            Row(modifier = m, verticalAlignment = Alignment.CenterVertically) {
            val hotSelected = jamendoActiveTag.isBlank()"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M42-head', False, 'anchor not found')

# 原 Row 的收尾（tag 循环结束 + Spacer + Row 结束）改成 tagRow 结束 + 形态分流
old = """                    Text(
                        text = tag,
                        color = LocalFocusableContentColor.current,
                        fontSize = FontSize.small(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))"""
new = """                    Text(
                        text = tag,
                        color = LocalFocusableContentColor.current,
                        fontSize = FontSize.small(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            }
        }

        if (isPhonePortrait) {
            // 竖屏：搜索框整行 + tag 行可横滑（同 RadioTab）
            jamendoSearchField(Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            jamendoTagRow(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                jamendoSearchField(Modifier.width(340.dp))
                Spacer(modifier = Modifier.width(8.dp))
                jamendoTagRow(Modifier)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M42-tail', False, 'anchor not found')

# ---- 3) 导入 ----
adds = [
    ('import androidx.compose.foundation.horizontalScroll\n', 'import androidx.compose.foundation.layout.Arrangement\n'),
    ('import androidx.compose.foundation.rememberScrollState\n', 'import androidx.compose.foundation.shape.RoundedCornerShape\n'),
    ('import com.nasmusic.tv.ui.theme.LocalUiMode\n', 'import com.nasmusic.tv.ui.theme.NasMusicColors\n'),
    ('import com.nasmusic.tv.ui.theme.UiMode\n', 'import com.nasmusic.tv.ui.theme.NasMusicColors\n'),
]
for imp, anchor in adds:
    if imp.strip() in S:
        continue
    if anchor in S:
        S = S.replace(anchor, imp + anchor, 1)
    else:
        expect('M42-imp:' + imp.strip(), False)

for imp in ('import androidx.compose.foundation.horizontalScroll',
            'import androidx.compose.foundation.rememberScrollState',
            'import com.nasmusic.tv.ui.theme.LocalUiMode',
            'import com.nasmusic.tv.ui.theme.UiMode'):
    expect('M42-has:' + imp.split('.')[-1], imp in S)

expect('M42-uses', 'horizontalScroll(rememberScrollState())' in S)
expect('M42-portrait-branch', 'if (isPhonePortrait) {' in S)

if FAILS:
    print('NOT WRITTEN')
    sys.exit(1)

out = S.replace('\n', eol) if eol == '\r\n' else S
io.open(REL, 'w', encoding='utf-8', newline='').write(out)
print('OK', REL, 'crlf' if eol == '\r\n' else 'lf')
print('ALL OK')
