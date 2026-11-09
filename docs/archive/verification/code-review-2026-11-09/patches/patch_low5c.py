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

# ================= L34: Partita motion 预计算 =================
REL = 'app/src/main/java/com/nasmusic/tv/visualizer/renderers/lyricsStage/PartitaSubRenderer.kt'
S, eol = load(REL)
old = """        state.profile = profile"""
new = """        state.profile = profile
        // L34：行容器动效（进出场时长/缩放/透明度）只取决于 profile ⇒ 整行算一次。
        // 原实现在 drawPartitaGroup 里每帧调 GlowWordCore.lineContainerMotion(profile)，
        // 每次新建一个 GlowWordLineContainerMotion 对象，违反本文件的 draw 零分配红线。
        state.motion = GlowWordCore.lineContainerMotion(profile)"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L34-build', False, 'anchor not found')

old = """        val profile = state.profile ?: return
        val motion = GlowWordCore.lineContainerMotion(profile)"""
new = """        val profile = state.profile ?: return
        // L34：走 buildState 预计算的那一份（缺失时才现算，正常路径不会走到）
        val motion = state.motion ?: GlowWordCore.lineContainerMotion(profile)"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L34-draw', False, 'anchor not found')

old = """        var groupStart = 0.0
        var exitStart = -1.0
    }"""
new = """        var groupStart = 0.0
        var exitStart = -1.0
        /** L34：行容器动效（buildState 里按 profile 算一次，逐帧零分配） */
        var motion: GlowWordLineContainerMotion? = null
    }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L34-state', False, 'anchor not found')
expect('L34-use', 'state.motion = GlowWordCore.lineContainerMotion(profile)' in S and
       'val motion = GlowWordCore.lineContainerMotion(profile)' not in S)
if FAILS:
    print('NOT WRITTEN(6)'); sys.exit(1)
save(REL, S, eol)

# ================= C51: SentenceLayout 码点中点 =================
REL = 'app/src/main/java/com/nasmusic/tv/visualizer/renderers/lyricsStage/tilt/SentenceLayout.kt'
S, eol = load(REL)
old = """            val midPoint = selectedCandidate.value.length / 2
            val firstHalf = selectedCandidate.value.substring(0, midPoint)
            val secondHalf = selectedCandidate.value.substring(midPoint)"""
new = """            // C51：中点必须落在**码点**边界上。`length / 2` 是按 UTF-16 码元算的，
            // 落在代理对中间时会切出未配对的高/低代理，两半各自渲染成「半个方块」。
            val midPoint = codePointMidpoint(selectedCandidate.value)
            val firstHalf = selectedCandidate.value.substring(0, midPoint)
            val secondHalf = selectedCandidate.value.substring(midPoint)"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('C51', False, 'anchor not found')

old = """    /** 移植 sentenceLayout.ts:523-544 `mergeSentences` */"""
new = """    /**
     * C51：字符串「码点中点」对应的 UTF-16 偏移。
     *
     * 按码点计数后映射回 UTF-16 下标，保证切点不会落在代理对内部；
     * 极端情况下（`offsetByCodePoints` 抛异常）退回原码元中点，至少与改造前等价。
     */
    private fun codePointMidpoint(text: String): Int {
        if (text.isEmpty()) return 0
        return try {
            val codePoints = text.codePointCount(0, text.length)
            text.offsetByCodePoints(0, codePoints / 2)
        } catch (_: Throwable) {
            text.length / 2
        }
    }

    /** 移植 sentenceLayout.ts:523-544 `mergeSentences` */"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('C51-helper', False, 'anchor not found')
expect('C51-use', 'codePointMidpoint(selectedCandidate.value)' in S)
if FAILS:
    print('NOT WRITTEN(7)'); sys.exit(1)
save(REL, S, eol)

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
expect('C52-use', S.count('splitByCodePoint(text)') == 3,
       'n=%d' % S.count('splitByCodePoint(text)'))
if FAILS:
    print('NOT WRITTEN(8)'); sys.exit(1)
save(REL, S, eol)
print('ALL OK')
