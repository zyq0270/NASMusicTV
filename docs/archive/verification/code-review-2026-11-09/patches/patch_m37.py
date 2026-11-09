#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M37: VisualEqualizer 绘制期零分配"""
import io, sys

REL = 'app/src/main/java/com/nasmusic/tv/ui/components/VisualEqualizer.kt'
FAILS = []

def expect(tag, cond, extra=''):
    if not cond:
        FAILS.append(tag)
        print('FAIL', tag, extra)

raw = io.open(REL, 'r', encoding='utf-8', newline='').read()
crlf = raw.count('\r\n')
eol = '\r\n' if crlf > (raw.count('\n') - crlf) else '\n'
S = raw.replace('\r\n', '\n')

# ---------- 1) 复用缓冲类 + 霓虹色常量 ----------
old = """/**
 * 可视化均衡器柱状频谱图"""
new = """/**
 * 绘制期复用缓冲（M37）：本组件以约 30fps 重绘，此前 `computeBarWidths` / `drawClassicalWave`
 * / `drawNeonPulse` 每次 draw 都会新建 FloatArray、Path、List，长时间停在均衡器页时 GC 抖动明显。
 * 这些对象的尺寸只与 `barCount` 有关，抽成 remember 的 scratch 后 draw 内只剩 rewind / 覆写。
 */
private class EqDrawScratch(barCount: Int) {
    val barWidths = FloatArray(barCount)
    val centers = FloatArray(barCount)
    val amplitudes = FloatArray(barCount)
    val fillPath = Path()
    val linePath = Path()
    val waveStroke = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val waveHighlightStroke = Stroke(width = 1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
}

/** 霓虹配色（M37：原实现每帧 `listOf(...)` 新建一个 List 并把 6 个 Color 装箱） */
private val NEON_COLORS = arrayOf(
    Color(0xFFFF1493),  // 深粉红
    Color(0xFF00FFFF),  // 青色
    Color(0xFFFFD700),  // 金色
    Color(0xFF00FF7F),  // 春绿
    Color(0xFF7B68EE),  // 中紫
    Color(0xFFFF4500)   // 橙红
)

/**
 * 可视化均衡器柱状频谱图"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M37-class', False, 'anchor not found')

# ---------- 2) 组合期 remember 缓冲 + LaunchedEffect key ----------
old = """    val barHeights = remember { Array(barCount) { 0f } }
    val targetHeights = remember { Array(barCount) { 0f } }
    var tick by remember { mutableIntStateOf(0) }"""
new = """    // M37：barCount 变化时必须重建缓冲（原实现 `remember { }` 无 key，改数量后会越界）
    val barHeights = remember(barCount) { Array(barCount) { 0f } }
    val targetHeights = remember(barCount) { Array(barCount) { 0f } }
    val rawBuf = remember(barCount) { FloatArray(barCount) }
    val smoothBuf = remember(barCount) { FloatArray(barCount) }
    val gateBuf = remember(barCount) { FloatArray(barCount) }
    val scratch = remember(barCount) { EqDrawScratch(barCount) }
    var tick by remember { mutableIntStateOf(0) }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M37-remember', False, 'anchor not found')

old = '    LaunchedEffect(isPlaying) {\n'
new = '    // key 加 barCount：上面的缓冲按 barCount 重建，循环必须跟着重启才能看到新尺寸\n    LaunchedEffect(isPlaying, barCount) {\n'
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M37-launched', False)

# ---------- 3) tick 循环内的平滑/门限就地化 ----------
old = """                val rawTargets = FloatArray(barCount) { i ->
                    if (i < data.size) data[i].coerceIn(0.02f, 1f) else 0.02f
                }
                val smoothedTargets = applyFrequencySmoothing(rawTargets)
                val gatedTargets = applyNoiseGate(smoothedTargets)
                for (i in 0 until barCount) {
                    targetHeights[i] = gatedTargets[i]
                }"""
new = """                // M37：三段处理全部就地写进 remember 的缓冲，tick 内零新建
                for (i in 0 until barCount) {
                    rawBuf[i] = if (i < data.size) data[i].coerceIn(0.02f, 1f) else 0.02f
                }
                applyFrequencySmoothingInto(rawBuf, barCount, smoothBuf)
                applyNoiseGateInto(smoothBuf, barCount, gateBuf)
                for (i in 0 until barCount) {
                    targetHeights[i] = gateBuf[i]
                }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M37-tick', False, 'anchor not found')

# ---------- 4) Canvas 调用点传 scratch ----------
old = """                0 -> drawColorFlow(barHeights, barCount, maxBarHeight, hueOffset, size)
                1 -> drawNeonPulse(barHeights, barCount, maxBarHeight, size)
                else -> drawClassicalWave(barHeights, barCount, maxBarHeight, size)"""
new = """                0 -> drawColorFlow(barHeights, barCount, maxBarHeight, hueOffset, size, scratch)
                1 -> drawNeonPulse(barHeights, barCount, maxBarHeight, size, scratch)
                else -> drawClassicalWave(barHeights, barCount, maxBarHeight, size, scratch)"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M37-callsite', False, 'anchor not found')

# ---------- 5) drawColorFlow ----------
old = """    hueOffset: Float,
    canvasSize: Size
) {
    val paddingPx = 8f
    val usableWidth = canvasSize.width - paddingPx * 2
    val startX = paddingPx
    val bottomY = canvasSize.height - 2f
    val usableHeight = canvasSize.height * maxBarHeight

    val baseW = usableWidth / barCount
    val barWidths = computeBarWidths(barCount, baseW, usableWidth)"""
new = """    hueOffset: Float,
    canvasSize: Size,
    scratch: EqDrawScratch
) {
    val paddingPx = 8f
    val usableWidth = canvasSize.width - paddingPx * 2
    val startX = paddingPx
    val bottomY = canvasSize.height - 2f
    val usableHeight = canvasSize.height * maxBarHeight

    val baseW = usableWidth / barCount
    computeBarWidthsInto(barCount, baseW, usableWidth, scratch.barWidths)
    val barWidths = scratch.barWidths"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M37-colorflow', False, 'anchor not found')

# ---------- 6) drawNeonPulse ----------
old = """    maxBarHeight: Float,
    canvasSize: Size
) {
    val paddingPx = 8f
    val usableWidth = canvasSize.width - paddingPx * 2
    val startX = paddingPx
    val bottomY = canvasSize.height - 2f
    val usableHeight = canvasSize.height * maxBarHeight

    // 当前帧平均振幅——用于整体亮度脉动
    val avgAmp = barHeights.take(barCount).average().toFloat().coerceIn(0.05f, 1f)
    val pulse = 0.7f + 0.3f * avgAmp  // 0.7~1.0 范围

    val baseW = usableWidth / barCount
    val barWidths = computeBarWidths(barCount, baseW, usableWidth)

    // 三组霓虹色
    val neonColors = listOf(
        Color(0xFFFF1493),  // 深粉红
        Color(0xFF00FFFF),  // 青色
        Color(0xFFFFD700),  // 金色
        Color(0xFF00FF7F),  // 春绿
        Color(0xFF7B68EE),  // 中紫
        Color(0xFFFF4500)   // 橙红
    )
"""
new = """    maxBarHeight: Float,
    canvasSize: Size,
    scratch: EqDrawScratch
) {
    val paddingPx = 8f
    val usableWidth = canvasSize.width - paddingPx * 2
    val startX = paddingPx
    val bottomY = canvasSize.height - 2f
    val usableHeight = canvasSize.height * maxBarHeight

    // 当前帧平均振幅——用于整体亮度脉动（M37：原 `take(barCount).average()` 每帧建一个 List）
    var ampSum = 0f
    for (i in 0 until barCount) ampSum += barHeights[i]
    val avgAmp = (if (barCount > 0) ampSum / barCount else 0f).coerceIn(0.05f, 1f)
    val pulse = 0.7f + 0.3f * avgAmp  // 0.7~1.0 范围

    val baseW = usableWidth / barCount
    computeBarWidthsInto(barCount, baseW, usableWidth, scratch.barWidths)
    val barWidths = scratch.barWidths
    val neonColors = NEON_COLORS
"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M37-neon-head', False, 'anchor not found')

# ---------- 7) drawClassicalWave ----------
old = """    maxBarHeight: Float,
    canvasSize: Size
) {
    val paddingPx = 8f
    val usableWidth = canvasSize.width - paddingPx * 2
    val startX = paddingPx
    val bottomY = canvasSize.height - 2f
    val usableHeight = canvasSize.height * maxBarHeight

    val baseW = usableWidth / barCount
    val barWidths = computeBarWidths(barCount, baseW, usableWidth)

    // 确定每个柱子的中心 X 坐标
    val centers = FloatArray(barCount)
    var cx = startX
    for (i in 0 until barCount) {
        centers[i] = cx + barWidths[i] / 2f
        cx += barWidths[i]
    }

    // 计算各个点的幅值，并做平滑插值（用于波形线）
    val amplitudes = FloatArray(barCount) { i ->
        bottomY - barHeights[i].coerceIn(0.01f, 1f) * usableHeight
    }"""
new = """    maxBarHeight: Float,
    canvasSize: Size,
    scratch: EqDrawScratch
) {
    val paddingPx = 8f
    val usableWidth = canvasSize.width - paddingPx * 2
    val startX = paddingPx
    val bottomY = canvasSize.height - 2f
    val usableHeight = canvasSize.height * maxBarHeight

    val baseW = usableWidth / barCount
    computeBarWidthsInto(barCount, baseW, usableWidth, scratch.barWidths)
    val barWidths = scratch.barWidths

    // 确定每个柱子的中心 X 坐标（M37：复用 scratch.centers）
    val centers = scratch.centers
    var cx = startX
    for (i in 0 until barCount) {
        centers[i] = cx + barWidths[i] / 2f
        cx += barWidths[i]
    }

    // 计算各个点的幅值，并做平滑插值（用于波形线）（M37：复用 scratch.amplitudes）
    val amplitudes = scratch.amplitudes
    for (i in 0 until barCount) {
        amplitudes[i] = bottomY - barHeights[i].coerceIn(0.01f, 1f) * usableHeight
    }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M37-wave-head', False, 'anchor not found')

old = """    // ① 填充面积（半透明渐变）
    val fillPath = Path().apply {
        moveTo(centers[0], bottomY)"""
new = """    // ① 填充面积（半透明渐变）
    val fillPath = scratch.fillPath
    fillPath.rewind()
    fillPath.apply {
        moveTo(centers[0], bottomY)"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M37-fillpath', False, 'anchor not found')

old = """    // ② 波形主线
    val linePath = Path().apply {
        moveTo(centers[0], amplitudes[0])"""
new = """    // ② 波形主线
    val linePath = scratch.linePath
    linePath.rewind()
    linePath.apply {
        moveTo(centers[0], amplitudes[0])"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M37-linepath', False, 'anchor not found')

old = """    drawPath(
        path = linePath,
        color = waveColor,
        style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )"""
new = """    drawPath(
        path = linePath,
        color = waveColor,
        style = scratch.waveStroke
    )"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M37-stroke1', False, 'anchor not found')

old = """    drawPath(
        path = linePath,
        color = waveColor.copy(alpha = 0.3f),
        style = Stroke(width = 1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )"""
new = """    drawPath(
        path = linePath,
        color = waveColor.copy(alpha = 0.3f),
        style = scratch.waveHighlightStroke
    )"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M37-stroke2', False, 'anchor not found')

# ---------- 8) 工具函数就地化 ----------
old = """private fun computeBarWidths(barCount: Int, baseW: Float, usableWidth: Float): FloatArray {
    val barWidths = FloatArray(barCount) { i ->
        when (i) {
            in 5..19 -> baseW * 1.3f
            in 20..27 -> baseW * 1.0f
            else -> baseW * 0.7f
        }
    }
    val totalRaw = barWidths.sum()
    val scale = usableWidth / totalRaw
    for (i in 0 until barCount) {
        barWidths[i] *= scale
    }
    return barWidths
}"""
new = """/** M37：写进调用方缓冲，不再返回新数组（返回新数组正是每次 draw 的分配源） */
private fun computeBarWidthsInto(
    barCount: Int,
    baseW: Float,
    usableWidth: Float,
    out: FloatArray
) {
    var totalRaw = 0f
    for (i in 0 until barCount) {
        val w = when (i) {
            in 5..19 -> baseW * 1.3f
            in 20..27 -> baseW * 1.0f
            else -> baseW * 0.7f
        }
        out[i] = w
        totalRaw += w
    }
    if (totalRaw <= 0f) return
    val scale = usableWidth / totalRaw
    for (i in 0 until barCount) {
        out[i] *= scale
    }
}"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M37-barwidths', False, 'anchor not found')

old = """private fun applyFrequencySmoothing(data: FloatArray): FloatArray {
    val size = data.size
    if (size < 3) return data.copyOf()
    val smoothed = FloatArray(size)
    for (i in 0 until size) {
        val left = if (i > 0) data[i - 1] else data[i]
        val right = if (i < size - 1) data[i + 1] else data[i]
        smoothed[i] = left * 0.25f + data[i] * 0.5f + right * 0.25f
    }
    return smoothed
}

private fun applyNoiseGate(data: FloatArray): FloatArray {
    val sum = data.sum()
    val avg = sum / data.size
    val threshold = avg * 0.15f
    return data.map { if (it < threshold) 0f else it }.toFloatArray()
}"""
new = """/** M37：就地平滑（原实现每 tick 新建 FloatArray） */
private fun applyFrequencySmoothingInto(src: FloatArray, count: Int, out: FloatArray) {
    if (count < 3) {
        for (i in 0 until count) out[i] = src[i]
        return
    }
    for (i in 0 until count) {
        val left = if (i > 0) src[i - 1] else src[i]
        val right = if (i < count - 1) src[i + 1] else src[i]
        out[i] = left * 0.25f + src[i] * 0.5f + right * 0.25f
    }
}

/** M37：就地噪声门（原实现 `map { }.toFloatArray()` 每 tick 新建 ArrayList + FloatArray） */
private fun applyNoiseGateInto(src: FloatArray, count: Int, out: FloatArray) {
    var sum = 0f
    for (i in 0 until count) sum += src[i]
    val avg = if (count > 0) sum / count else 0f
    val threshold = avg * 0.15f
    for (i in 0 until count) {
        val v = src[i]
        out[i] = if (v < threshold) 0f else v
    }
}"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('M37-helpers', False, 'anchor not found')

# ---------- 校验 ----------
expect('V-no-oldbarwidths', 'computeBarWidths(' not in S)
expect('V-nolistof', 'val neonColors = listOf(' not in S)
expect('V-nopathnew', 'Path().apply' not in S)
expect('V-scratch-uses', S.count('scratch.') >= 12, 'n=%d' % S.count('scratch.'))

if FAILS:
    print('NOT WRITTEN')
    sys.exit(1)

out = S.replace('\n', eol) if eol == '\r\n' else S
io.open(REL, 'w', encoding='utf-8', newline='').write(out)
print('OK', REL, 'crlf' if eol == '\r\n' else 'lf')
print('ALL OK')
