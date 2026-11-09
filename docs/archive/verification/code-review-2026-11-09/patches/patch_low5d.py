#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Low 批次 5（渲染器/歌词切分）：L27 / L28 / L30 / L31 / L32 / L33 / L34 / C51 / C52"""
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

def need_import(S, imp, anchor_list):
    if imp in S:
        return S
    for a in anchor_list:
        if a in S:
            return S.replace(a, imp + '\n' + a, 1)
    expect('IMPORT:' + imp, False, 'no anchor')
    return S

# ================= C52: GraphemeSplitter 兜底按码点 =================
REL = 'app/src/main/java/com/nasmusic/tv/visualizer/renderers/lyricsStage/GraphemeSplitter.kt'
S, eol = load(REL)
n = S.count('text.map { it.toString() }')
if n == 2:
    S = S.replace('text.map { it.toString() }', 'splitByCodePoint(text)')
else:
    expect('C52-sites', False, 'n=%d (expect 2)' % n)

old = """    private fun splitWithIcu(icu: Any, text: String): List<String> {"""
new = """    /**
     * C52：逐**码点**切分的兜底（原实现 `text.map { it.toString() }` 是逐 UTF-16 码元，
     * 会把代理对劈成两个孤立代理 ⇒ 渲染成两个方块）。
     *
     * 走到这里的路径已经很窄（`resolveIcu()` 失败会先走 `java.text.BreakIterator`，
     * 只有「ICU 实例已建但反射中途抛异常」才会落到本函数），但不该在这里留下已知会产坏字形的实现。
     * ⚠️ 码点切分仍不等于字素簇切分（组合字符/ZWJ emoji 会被拆开），
     * 这是刻意接受的降级上限：能走到这里的设备本就连 BreakIterator 都不可用。
     */
    private fun splitByCodePoint(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            out.add(text.substring(i, i + n))
            i += n
        }
        return out
    }

    private fun splitWithIcu(icu: Any, text: String): List<String> {"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('C52-helper', False, 'anchor not found')
expect('C52-use', S.count('splitByCodePoint(text)') == 2 and
       'private fun splitByCodePoint(text: String)' in S,
       'n=%d' % S.count('splitByCodePoint(text)'))
if FAILS:
    print('NOT WRITTEN(8)'); sys.exit(1)
save(REL, S, eol)
print('ALL OK')
