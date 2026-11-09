# -*- coding: utf-8 -*-
"""M47 / M48 / M49 / M50 / M53 / M55 —— 渲染器批次收尾。"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S = ""
CRLF = False


def load(rel):
    global S, CRLF
    p = os.path.join(ROOT, rel)
    S = io.open(p, "r", encoding="utf-8", newline="").read()
    CRLF = "\r\n" in S
    return p


def rep(a, b, tag):
    global S
    a2 = a.replace("\n", "\r\n") if CRLF else a
    b2 = b.replace("\n", "\r\n") if CRLF else b
    n = S.count(a2)
    if n != 1:
        print("FAIL [%s] count=%d" % (tag, n))
        sys.exit(1)
    S = S.replace(a2, b2)


def save(p):
    io.open(p, "w", encoding="utf-8", newline="").write(S)


# ══════════════════════════════════════════════════════════════
# VintageTvRenderer：M47（vignette 缓存键）+ M48（rollBandH 不随尺寸更新）
# ══════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/visualizer/renderers/VintageTvRenderer.kt")
rep(
    """    private var rollBrushBandH = 0f
    private var rollBandH = 0f
""",
    """    private var rollBrushBandH = 0f
    private var rollBandH = 0f

    /** M48：算 [rollBandH] 时依据的画布高度（h 一变就要重算带高，见 [drawRollBand]） */
    private var rollBandBaseH = 0f
""",
    "tv-rollfield",
)
rep(
    """    private var vignetteBrush: Brush? = null
    private var vignetteW = 0f
""",
    """    private var vignetteBrush: Brush? = null
    private var vignetteW = 0f

    /** M47：暗角渐变缓存键必须含 h（center 与 radius 同时依赖 w 与 h） */
    private var vignetteH = 0f
""",
    "tv-vigfield",
)
rep(
    """        if (rollBandH <= 0f) rollBandH = h * ROLL_BAND_FRACTION
""",
    """        // M48：带高按**当前**画布高度算。原实现只在 rollBandH <= 0 时算一次（onEnter 才清 0）
        // ⇒ 手机↔TV、分屏、旋转之后带高仍是旧屏幕的比例值，而渐变缓存键只认带高
        // ⇒ 暗带高度与整屏不匹配且永不纠正。
        if (rollBandH <= 0f || rollBandBaseH != h) {
            rollBandBaseH = h
            rollBandH = h * ROLL_BAND_FRACTION
        }
""",
    "tv-rollh",
)
rep(
    """        if (vignetteBrush == null || vignetteW != w) {
            val pictureW = minOf(w, h * ASPECT)""",
    """        // M47：⛔ 缓存键不能只记 w —— 下面 center 用 (w/2, h/2)、radius 用 h，
        // 同一宽度换高度（或 h 单独变化）时必须重建，否则暗角中心/半径沿用旧屏幕。
        if (vignetteBrush == null || vignetteW != w || vignetteH != h) {
            val pictureW = minOf(w, h * ASPECT)""",
    "tv-vigcheck",
)
rep(
    """            vignetteW = w
        }
        drawRect(vignetteBrush!!)""",
    """            vignetteW = w
            vignetteH = h
        }
        drawRect(vignetteBrush!!)""",
    "tv-vigset",
)
save(p)
print("OK VintageTvRenderer (M47/M48)")

# ══════════════════════════════════════════════════════════════
# LyricsDotMatrixRenderer：M49（尺寸变化后点阵坐标失效）
# ══════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/visualizer/renderers/LyricsDotMatrixRenderer.kt")
rep(
    """    private fun sampleLine(
        text: String,
        target: FloatArray,
        charIndices: IntArray,
        localXs: FloatArray,
        cap: Int,
        canvasW: Float,
        fontSize: Float,
        targetY: Float   // 行的目标 Y 位置（文字垂直中心）
    ): Int {
        if (text.isBlank()) return 0
""",
    """    private fun sampleLine(
        text: String,
        target: FloatArray,
        charIndices: IntArray,
        localXs: FloatArray,
        cap: Int,
        canvasW: Float,
        fontSize: Float,
        targetY: Float   // 行的目标 Y 位置（文字垂直中心）
    ): Int {
        // M49：记录本次采样用的几何。点阵坐标在本函数末尾就被烘成**绝对画布像素**
        // 并跨帧保存，画布一变这些坐标就全错，且代码里没有任何失效判据
        // ⇒ 由 `drawContent` 用这两个值决定要不要重采样（见那里的判据）。
        sampleW = canvasW
        sampleTargetY = targetY
        if (text.isBlank()) return 0
""",
    "dot-record",
)
rep(
    """    // ── 文字采样 ────────────────────────────────────────────
""",
    """    // ── 文字采样 ────────────────────────────────────────────

    /** M49：上次采样时的画布宽度与行目标 Y（点阵坐标是绝对像素 ⇒ 变化必须重采样） */
    private var sampleW = 0f
    private var sampleTargetY = 0f
""",
    "dot-fields",
)
rep(
    """        // 行间距：**固定** = 两行中心之差（与字号解耦 ⇒ 字号再小两行也不挤、再大也不叠）
        rowGapPx = bottomY - topY
""",
    """        // 行间距：**固定** = 两行中心之差（与字号解耦 ⇒ 字号再小两行也不挤、再大也不叠）
        rowGapPx = bottomY - topY

        // M49：画布尺寸/行位变化 ⇒ 上一轮烘进 target[] 的绝对像素坐标已经失效，必须重采样。
        // 判据用「上次采样用的宽度」+「上次采样用的行中心是否仍是 topY/bottomY 之一」：
        // 采样本就只可能发生在两行之一上，所以这个组合既能抓住宽度变化，
        // 也能抓住 h 变化导致的 topY/bottomY 漂移，且不会因为 line0/line1 交替采样而抖动。
        if (displayedLineIndex >= 0 &&
            (sampleW != w || (sampleTargetY != topY && sampleTargetY != bottomY))
        ) {
            resync(ctx, displayedLineIndex, frame.timeMs, w, h)
        }
""",
    "dot-check",
)
save(p)
print("OK LyricsDotMatrixRenderer (M49)")

# ══════════════════════════════════════════════════════════════
# TripodFogRenderer：M50（drawLinPoly 每帧新建 LinearGradient）
# ══════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/visualizer/renderers/TripodFogRenderer.kt")
rep(
    """    /** [gradSlot] 的输出旁路：本次取色要用的 paint α（`amax`，0 ⇒ 整条指令跳过） */
    private var gradAm = 0
""",
    """    /** [gradSlot] 的输出旁路：本次取色要用的 paint α（`amax`，0 ⇒ 整条指令跳过） */
    private var gradAm = 0

    /**
     * M50：[drawLinPoly] 专用的**屏幕空间**单位渐变缓存（与 [shaders] 分开存放）。
     *
     * ⛔ 不能直接改 [shaders] 里那个实例的 localMatrix：它被凸路径（LIN_RECT /
     * RAD_ELLIPSE）按「单位空间 + Canvas 变换」复用，一旦留下屏幕空间的矩阵，
     * 那些图元会被二次变换。
     *
     * 复用方式：单位轴 (0,0)→(1,0) 的渐变建一次，之后每帧只 `setLocalMatrix`
     * 把单位轴映射到指令轴 ⇒ 零新建 Shader（原先每帧约 6 个 native 对象）。
     */
    private val polyShaders = arrayOfNulls<LinearGradient>(shaderSlots)

    /** 建 [polyShaders] 时用的色标数组实例（[gradSlot] 变更为新数组 ⇒ 实例不等 ⇒ 重建） */
    private val polyStopRef = arrayOfNulls<IntArray>(shaderSlots)

    /** [drawLinPoly] 的复用矩阵（单位轴 → 指令轴） */
    private val polyMatrix = Matrix()
""",
    "tripod-fields",
)
rep(
    """        val cnt = plan.cmdVCnt[i]
        if (cnt < 3) return
        buildScreenPath(cnt, plan.cmdV0[i])
        val pt = paintFor(fillPaint, i)
        pt.shader = LinearGradient(ax0, ay0, ax1, ay1, bc, bp, Shader.TileMode.CLAMP)
        pt.alpha = gradAm
        c.drawPath(polyPath, pt)
""",
    """        val cnt = plan.cmdVCnt[i]
        if (cnt < 3) return
        buildScreenPath(cnt, plan.cmdV0[i])
        // M50：着色器按槽复用（只在色标实例变化时重建），轴向交给 localMatrix。
        // 原实现每条指令都 `LinearGradient(ax0, ay0, ax1, ay1, …)` ⇒ 每帧约 6 个
        // native 对象，全部落在渲染线程上。
        var sh = polyShaders[ci]
        if (sh == null || polyStopRef[ci] !== bc) {
            sh = LinearGradient(0f, 0f, 1f, 0f, bc, bp, Shader.TileMode.CLAMP)
            polyShaders[ci] = sh
            polyStopRef[ci] = bc
        }
        // 单位轴 (u,0) → (ax0, ay0) + u·len·(cosθ, sinθ)：
        // Android 的 postX 是「左乘」，所以按 Scale → postRotate → postTranslate 拼出 T·R·S。
        val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (!(len > 1e-6f)) return
        polyMatrix.setScale(len, 1f)
        polyMatrix.postRotate(Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat())
        polyMatrix.postTranslate(ax0, ay0)
        sh.setLocalMatrix(polyMatrix)
        val pt = paintFor(fillPaint, i)
        pt.shader = sh
        pt.alpha = gradAm
        c.drawPath(polyPath, pt)
""",
    "tripod-poly",
)
rep(
    """     * ⚠️ 代价：色标缓存只能复用到数组，`LinearGradient` 必须按指令的轴逐条新建（每帧腿 3 +
     * 机身 1 + 光柱 ≤2 ≈ 6 个 native 对象）。
""",
    """     * ⚠️ 原实现代价：`LinearGradient` 按指令的轴逐条新建（每帧腿 3 + 机身 1 + 光柱 ≤2
     * ≈ 6 个 native 对象）。M50 已改为「每槽一个单位空间渐变 + `setLocalMatrix` 摆轴」，
     * 新建只发生在色标变化的帧。
""",
    "tripod-doc",
)
save(p)
print("OK TripodFogRenderer (M50)")

# ══════════════════════════════════════════════════════════════
# SnowWorld / SnowAudio：M53（音频 NaN 入口兜底）
# ══════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/visualizer/renderers/SnowWorld.kt")
rep(
    """    /** 一帧一次；`dtSec` 直接来自帧循环（⛔ 隐含 60fps —— [AudioSmoother.update] 那条老路）。 */
    fun update(rawBass: Double, rawEnergy: Double, rawTreble: Double, dtSec: Double) {
        smBass.updateDt(rawBass.toFloat(), dtSec.toFloat())
        smEnergy.updateDt(rawEnergy.toFloat(), dtSec.toFloat())
        smTreble.updateDt(rawTreble.toFloat(), dtSec.toFloat())
    }
""",
    """    /**
     * 一帧一次；`dtSec` 直接来自帧循环（⛔ 隐含 60fps —— [AudioSmoother.update] 那条老路）。
     *
     * M53：入口对 NaN/Inf 兜底。⛔ [AudioSmoother] 是纯 EMA —— NaN 进来就**永久粘连**
     * （NaN 与任何数运算仍是 NaN），随后经 `SnowWorld.state()` 污染风场参数与雪粒坐标，
     * 整幅画永久静止，而且换画幅也不会自愈（`reset()` 只在重建时调用）。
     * 这里把非有限值一律当 0，`dtSec` 同样兜底（NaN 的 dt 会把所有积分器打死）。
     */
    fun update(rawBass: Double, rawEnergy: Double, rawTreble: Double, dtSec: Double) {
        val dt = if (dtSec.isFinite()) dtSec.toFloat() else 0f
        smBass.updateDt(safe(rawBass), dt)
        smEnergy.updateDt(safe(rawEnergy), dt)
        smTreble.updateDt(safe(rawTreble), dt)
    }

    /** 非有限值 → 0（见 [update]） */
    private fun safe(v: Double): Float = if (v.isFinite()) v.toFloat() else 0f
""",
    "snow-nan",
)
save(p)
print("OK SnowWorld (M53)")

# ══════════════════════════════════════════════════════════════
# LyricsStagePayload：M55（endTime >= startTime 规整）
# ══════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/visualizer/renderers/lyricsStage/LyricsStagePayload.kt")
rep(
    """        val startTime = line.time / 1000.0
        val endTimeMs = nextLineTimeMs ?: (line.time + DEFAULT_LINE_DURATION_MS)
        val endTime = endTimeMs / 1000.0
""",
    """        val startTime = line.time / 1000.0
        val endTimeMs = nextLineTimeMs ?: (line.time + DEFAULT_LINE_DURATION_MS)
        // M55：相邻行时间戳相同或倒序（解析器兜底、坏 LRC）会让 endTime <= startTime
        // ⇒ 行时长为 0/负 ⇒ 逐字揭示的进度分母为 0，第一帧就判「整行唱完」，
        // 高亮直接跳过这一行。这里统一兜到最短行时长，保证单调。
        val endTime = maxOf(endTimeMs / 1000.0, startTime + MIN_LINE_DURATION_SEC)
""",
    "m55-clamp",
)
rep(
    "    private const val DEFAULT_LINE_DURATION_MS = 3000L\n",
    """    private const val DEFAULT_LINE_DURATION_MS = 3000L

    /** M55：行时长下限（秒）—— 时间戳相同/倒序时的兜底，见 [buildLine] */
    private const val MIN_LINE_DURATION_SEC = 0.15
""",
    "m55-const",
)
save(p)
print("OK LyricsStagePayload (M55)")

print("ALL DONE")
