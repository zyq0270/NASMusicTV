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

# ================= L31: MatrixRain 本地节拍 =================
REL = 'app/src/main/java/com/nasmusic/tv/visualizer/renderers/AdvancedRenderers.kt'
S, eol = load(REL)
old = """        val tick = (frame.timeMs / 300L).toInt()"""
new = """        // L31：数字翻转节拍不能用绝对 `frame.timeMs` —— 暂停恢复后它跟着播放位置一次跳一大段，
        // 整屏数字会集体翻转（视觉跳变）。改成按 fx.dt 累加的本地节拍，与暂停/恢复解耦
        //（同文件 LiquidGridRenderer 已是这个口径）。
        tickAcc += fx.dt * MATRIX_FLIP_HZ
        val tick = tickAcc.toInt()"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L31', False, 'anchor not found')

if 'private var tickAcc' not in S:
    anchor = '    internal var keyN = 0'
    if anchor in S:
        S = S.replace(anchor, anchor + """
    /** L31：数字翻转的本地节拍累加器（× 300ms 周期），不依赖绝对 timeMs */
    private var tickAcc = 0f""", 1)
    else:
        expect('L31-field', False, 'anchor keyN not found')

if 'MATRIX_FLIP_HZ' in S and 'MATRIX_FLIP_HZ =' not in S:
    anchor = '    private var tickAcc = 0f'
    S = S.replace(anchor, anchor + """
    /** 300ms 翻一次 ⇒ 每秒 1000/300 拍 */
    private val MATRIX_FLIP_HZ = 1000f / 300f""", 1)
expect('L31-use', 'tickAcc += fx.dt * MATRIX_FLIP_HZ' in S)
if FAILS:
    print('NOT WRITTEN(3)'); sys.exit(1)
save(REL, S, eol)

# ================= L32: MoonAudio clampUnit =================
REL = 'app/src/main/java/com/nasmusic/tv/visualizer/renderers/MoonAudio.kt'
S, eol = load(REL)
old = """    private fun clampUnit(v: Double): Double = if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v"""
new = """    // L32：NaN 必须显式拦掉 —— 原写法 `if (v < 0.0) … else if (v > 1.0) … else v`
    // 对 NaN 两个比较都是 false，于是 NaN 原样穿透到七个幅度函数（再传进
    // `sin/cos/pow` 与 canvas 的 alpha/尺寸计算，表现为整层效果消失而非报错）。
    private fun clampUnit(v: Double): Double =
        if (v.isNaN()) 0.0 else if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L32', False, 'anchor not found')
expect('L32-nan', 'if (v.isNaN()) 0.0' in S)
if FAILS:
    print('NOT WRITTEN(4)'); sys.exit(1)
save(REL, S, eol)

# ================= L33: PhotoWallController =================
REL = 'app/src/main/java/com/nasmusic/tv/visualizer/photo/PhotoWallController.kt'
S, eol = load(REL)
old = """    private val ownScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)"""
new = """    // L33：本作用域里除了扫描还有预取/解码等 launch。没有 handler 时任何逃逸异常
    // （权限被撤、DB 损坏、解码 OOM）都会交给线程默认处理器 ⇒ **主线程崩溃**。
    // 加一个只记日志的 handler 兜住它们（扫描那条另有 try/catch，见 [startScan]）。
    private val ownScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, t -> AppLog.e(TAG, "photo wall coroutine failed", t) }
    )"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L33-scope', False, 'anchor not found')

# 扫描体加 try/catch：把 launch 内整段包起来
old = """        scanJob = scope.launch {
            val enabled = enabledKinds(settings)"""
new = """        scanJob = scope.launch {
            // L33：扫描链（聚合 → 人脸过滤 → 建池）任何一处抛出都会走协程的默认 handler；
            // 在 `scope` 是外部 ViewModel 作用域时那是「未捕获异常 ⇒ 进程崩溃」。
            // 这里收口为「记日志 + 置 needScan」——下次设置变化或重新进舞台会自动补扫。
            try {
            val enabled = enabledKinds(settings)"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L33-try', False, 'anchor not found')

old = """            if (needScan) {
                scanJob = null
                startScan()
            }
        }
    }"""
new = """            if (needScan) {
                scanJob = null
                startScan()
            }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLog.e(TAG, "scan failed, marked for retry", t)
                needScan = true
            }
        }
    }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L33-catch', False, 'anchor not found')
S = need_import(S, 'import kotlinx.coroutines.CoroutineExceptionHandler', [
    'import kotlinx.coroutines.CoroutineScope\n'])
expect('L33-import', 'import kotlinx.coroutines.CoroutineExceptionHandler' in S)
if FAILS:
    print('NOT WRITTEN(5)'); sys.exit(1)
save(REL, S, eol)

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
expect('L34-use', S.count('GlowWordCore.lineContainerMotion(') == 2,
       'n=%d' % S.count('GlowWordCore.lineContainerMotion('))
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
