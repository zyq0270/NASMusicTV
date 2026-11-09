# -*- coding: utf-8 -*-
"""重做 C44 / C42 / M51 / L29 / N10（EcoGlobe）与 C50 / M54（歌词舞台）。

⚠️ 上一版脚本用了「loader() 返回闭包」的写法：闭包里的 nonlocal s 只改函数内绑定，
   模块级的 s 仍是原始字符串 ⇒ write 把原文写回去，改动静默丢失。
   本脚本统一用模块级 global s，末尾逐项校验。
"""
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


def expect(p, needles):
    body = io.open(p, "r", encoding="utf-8", newline="").read()
    for n in needles:
        c = body.count(n)
        print("   verify %-22s x%d" % (n, c))
        if c == 0:
            print("   !!! missing", n)
            sys.exit(1)


# ══════════════════════════════════════════════════════════════════
# 1) WorldGlobeRenderer：C44（只差这一段，C41/L29/N10 上轮已落地）
# ══════════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/visualizer/renderers/WorldGlobeRenderer.kt")
rep(
    "    private fun ema(old: Float, v: Float): Float = old + (v - old) * EMA_ALPHA\n",
    """    /**
     * C44：EMA 平滑（NaN/Inf 免疫）。
     *
     * ⛔ 不能用一行 `old + (v - old) * ALPHA`：上游音量一旦出现 NaN（解码异常、
     * 除零、残留脏帧），NaN 会永久粘在这个状态里（NaN 与任何数运算仍是 NaN），
     * 之后每帧 `sendAudio` 都会把 NaN 交给 `gson.toJson` —— Gson 默认**拒绝** NaN
     * （抛 IllegalArgumentException）⇒ 100ms tick 链路整条被打死，画面冻结。
     * 所以入口把非有限值当 0 处理，状态里永远只有有限值。
     */
    private fun ema(old: Float, v: Float): Float {
        val input = if (v.isFinite()) v else 0f
        val cur = if (old.isFinite()) old else 0f
        return cur + (input - cur) * EMA_ALPHA
    }

    /** C44：交 JS 前把量规整到 0..1 并对 NaN/Inf 兜底（`coerceIn` 对 NaN 无效） */
    private fun safeUnit(v: Float): Float = if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
""",
    "world-ema",
)
rep(
    """            "energy" to emaEnergy.coerceIn(0f, 1f),
            "bass" to emaBass.coerceIn(0f, 1f),
            "mid" to emaMid.coerceIn(0f, 1f),
            "treble" to emaTreble.coerceIn(0f, 1f),
            "beat" to emaBeat.coerceIn(0f, 1f)
""",
    """            "energy" to safeUnit(emaEnergy),
            "bass" to safeUnit(emaBass),
            "mid" to safeUnit(emaMid),
            "treble" to safeUnit(emaTreble),
            "beat" to safeUnit(emaBeat)
""",
    "world-sendaudio",
)
save(p)
print("OK WorldGlobeRenderer")
expect(p, ["private fun safeUnit", "safeUnit(emaBeat)", "destroyWebView", "allowFileAccess = false"])

# ══════════════════════════════════════════════════════════════════
# 2) EcoGlobeRenderer：C42 + M51 + L29 + N10 + C44
# ══════════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/visualizer/renderers/EcoGlobeRenderer.kt")
rep(
    """            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
""",
    """            settings.javaScriptEnabled = true
            // ⛔ L29（与 E41 同款）：页面与脚本全部经虚拟资产域由
            //    shouldInterceptRequest 流式提供，不需要 file:// 能力。
            settings.allowFileAccess = false
""",
    "eco-fileaccess",
)
rep(
    """                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
""",
    """                /**
                 * M51/L29：完全离线的单页应用，任何离开资产域的主框架导航都是异常。
                 * 默认实现会照单全收并真联网（本页 `javaScriptEnabled=true`，一旦有外部
                 * 输入流进 JS 就是真实面），这里一律拦截并留痕。
                 */
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url ?: return true
                    if (url.host == ASSET_HOST) return false
                    AppLog.w("EcoGlobe", "blocked outbound navigation: ${url.scheme}://${url.host}${url.path}")
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
""",
    "eco-override",
)
rep(
    """    override fun onViewDetached() {
        attached = false
        handler.removeCallbacks(tickRunnable)
        // \u26d4 不能同步 destroy：onDispose 触发时 AndroidView 的 view 可能仍挂在组合
        //    销毁流程中，直接 destroy 会崩。post 到主线程队列（此时 view 已 detach）
        //    并先摘除父容器再销毁。
        val wv = webView
        webView = null
        wv?.post {
            try {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            } catch (_: Exception) {
            }
        }
    }

    override fun onExit() {
        attached = false
        handler.removeCallbacks(tickRunnable)
        // WebView 销毁统一交给 onViewDetached（AndroidView 组销毁路径）
    }
""",
    """    override fun onViewDetached() {
        attached = false
        handler.removeCallbacks(tickRunnable)
        // \u26d4 不能同步 destroy：onDispose 触发时 AndroidView 的 view 可能仍挂在组合
        //    销毁流程中，直接 destroy 会崩。先后置空引用、再由主线程队列延后销毁。
        //
        // C42：\u26d4 此处**不能**用 `wv.post { }`。view 已 detach（无 attachInfo）时
        // View.post 只是把 Runnable 塞进自身的 HandlerActionQueue，等**下一次 attach**
        // 才执行；本场景是「已 detach 且不会再 attach」⇒ Runnable 永不执行 ⇒ WebView
        // 永久泄漏。改由本类主线程 Handler 代发。
        val wv = webView
        webView = null
        if (wv != null) handler.post { destroyWebView(wv) }
    }

    override fun onExit() {
        attached = false
        handler.removeCallbacks(tickRunnable)
        // C42：onExit 之后 AndroidView 的 onDispose 不一定发生（切换渲染器时组合可能
        // 被缓存/复用）⇒ 这里补一次销毁，destroyWebView 幂等，重复调用安全。
        val wv = webView
        webView = null
        if (wv != null) handler.post { destroyWebView(wv) }
    }

    /** 幂等的 WebView 销毁：摘父容器 + destroy（重复调用安全） */
    private fun destroyWebView(wv: WebView) {
        try {
            (wv.parent as? ViewGroup)?.removeView(wv)
        } catch (_: Exception) {
        }
        try {
            wv.destroy()
        } catch (_: Exception) {
        }
    }
""",
    "eco-destroy",
)
rep(
    """    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!attached || webView == null) return
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }
""",
    """    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!attached || webView == null) return
            // N10：tick() 内任何意外异常都会打断重排程 ⇒ 100ms 链路永久停摆且无日志。
            // try/finally 保证「只要还 attached 就继续排下一次」。
            try {
                tick()
            } catch (e: Exception) {
                AppLog.e("EcoGlobe", "tick failed", e)
            } finally {
                if (attached) handler.postDelayed(this, TICK_MS)
            }
        }
    }
""",
    "eco-tick",
)
rep(
    "    private fun ema(old: Float, v: Float): Float = old + (v - old) * EMA_ALPHA\n",
    """    /**
     * C44：EMA 平滑（NaN/Inf 免疫）——NaN 一旦进状态就会永久粘连，
     * 之后每帧 `gson.toJson` 都会因 Gson 拒 NaN 抛 IllegalArgumentException，
     * 把 100ms tick 链路整条打死。入口把非有限值当 0。
     */
    private fun ema(old: Float, v: Float): Float {
        val input = if (v.isFinite()) v else 0f
        val cur = if (old.isFinite()) old else 0f
        return cur + (input - cur) * EMA_ALPHA
    }

    /** C44：交 JS 前把量规整到 0..1 并对 NaN/Inf 兜底（`coerceIn` 对 NaN 无效） */
    private fun safeUnit(v: Float): Float = if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
""",
    "eco-ema",
)
rep(
    """            "energy" to emaEnergy.coerceIn(0f, 1f),
            "bass" to emaBass.coerceIn(0f, 1f),
            "mid" to emaMid.coerceIn(0f, 1f),
            "treble" to emaTreble.coerceIn(0f, 1f),
            "beat" to emaBeat.coerceIn(0f, 1f)
""",
    """            "energy" to safeUnit(emaEnergy),
            "bass" to safeUnit(emaBass),
            "mid" to safeUnit(emaMid),
            "treble" to safeUnit(emaTreble),
            "beat" to safeUnit(emaBeat)
""",
    "eco-sendaudio",
)
save(p)
print("OK EcoGlobeRenderer")
expect(p, ["private fun safeUnit", "destroyWebView", "allowFileAccess = false", "shouldOverrideUrlLoading", "tick failed"])

# ══════════════════════════════════════════════════════════════════
# 3) LyricsStagePayload：linesGeneration
# ══════════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/visualizer/renderers/lyricsStage/LyricsStagePayload.kt")
rep(
    """    /** 整曲歌词行（Folia 的 `lines`），FULL_VIEW 子模式要把它排成整版版面 */
    var lines: List<LyricLine> = emptyList(),
""",
    """    /**
     * 整曲歌词行（Folia 的 `lines`），FULL_VIEW 子模式要把它排成整版版面。
     *
     * \u26a0\ufe0f 这里是**普通构造参数**、不是属性：同名属性 [lines] 在类体内声明
     * （要自定义 setter 维护 [linesGeneration]），初始值取自本参数。这样
     * `LyricsStagePayload(lines = …)` 这类命名参数调用点全部不受影响。
     */
    lines: List<LyricLine> = emptyList(),
""",
    "payload-ctor",
)
rep(
    """    /** 播放进度（秒） */
    var timeSec: Double = 0.0,
) {
""",
    """    /** 播放进度（秒） */
    var timeSec: Double = 0.0,
) {
    /**
     * 整曲歌词行。
     *
     * \u26a0\ufe0f **整曲身份**（C50/M54）：切歌、歌词异步到达、重新加载都会换一个
     * `List` 实例。子渲染器普遍按「当前行 `startTime`」判换行 —— 而两首歌的首行
     * `startTime` 常常都是 0.0 ⇒ 只比 `startTime` 会把「上一首最后一行 →
     * 下一首第一行」误判成「同一行」，版面与词表全部沿用旧曲。所以这里在赋值处
     * 集中维护 [linesGeneration]，子渲染器只比代数即可。
     */
    var lines: List<LyricLine> = lines
        set(value) {
            if (value !== field) {
                field = value
                linesGeneration++
            }
        }

    /** 整曲歌词的「身份代数」：每次 [lines] 换成新实例 +1（单调递增，永不复用） */
    var linesGeneration: Int = 0
        private set

""",
    "payload-gen",
)
save(p)
print("OK LyricsStagePayload")
expect(p, ["var linesGeneration: Int = 0", "linesGeneration++"])

# ══════════════════════════════════════════════════════════════════
# 4) GlowLineSubRenderer：C50 整曲更换硬复位
# ══════════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/visualizer/renderers/lyricsStage/GlowLineSubRenderer.kt")
rep(
    """    private var curLayout: LineLayout? = null
    private var prevLayout: LineLayout? = null
""",
    """    private var curLayout: LineLayout? = null
    private var prevLayout: LineLayout? = null

    /**
     * 上一次建版面时对应的整曲歌词代数（C50）。
     *
     * [Int.MIN_VALUE] = 还没比过；与 `lyrics.linesGeneration` 不等 ⇒ 整曲换了，
     * 必须硬复位（见 `draw` 开头）。
     */
    private var lastLinesGeneration: Int = Int.MIN_VALUE
""",
    "glow-field",
)
rep(
    """        val nowSec = lyrics.timeSec
        val line = lyrics.current

        if (line != null) {""",
    """        val nowSec = lyrics.timeSec
        val line = lyrics.current

        // C50：整曲歌词整体更换（切歌 / 歌词异步到达 / 重新加载）⇒ **硬复位**。
        // \u26d4 只靠下面 `layout.key != line.startTime` 不够：两首歌的首行 startTime
        //    经常都是 0.0（或都从同一秒开始），换歌会被误判成「同一行」——
        //    版面、词表、逐字状态机全部沿用上一首，画面上就是「换歌后歌词还停在旧曲」。
        //    同时清掉 prevLayout：旧曲那一行的离场动画没有意义，留着会叠在新曲上。
        if (lastLinesGeneration != lyrics.linesGeneration) {
            lastLinesGeneration = lyrics.linesGeneration
            curLayout = null
            prevLayout = null
        }

        if (line != null) {""",
    "glow-draw",
)
save(p)
print("OK GlowLineSubRenderer")
expect(p, ["lastLinesGeneration != lyrics.linesGeneration"])

# ══════════════════════════════════════════════════════════════════
# 5) FullViewSubRenderer：M54 尺寸/density 变化也重建
# ══════════════════════════════════════════════════════════════════
p = load("app/src/main/java/com/nasmusic/tv/visualizer/renderers/lyricsStage/FullViewSubRenderer.kt")
rep(
    "    private var builtLines: List<LyricLine>? = null\n",
    """    private var builtLines: List<LyricLine>? = null

    /** 上次建版面时的画布尺寸（像素）与 density（M54：任一变化都必须重排） */
    private var builtViewportW: Float = -1f
    private var builtViewportH: Float = -1f
    private var builtDensity: Float = -1f
""",
    "fv-field",
)
rep(
    """        // 换歌 ⇒ 重建版面
        if (lyrics.lines !== builtLines) {
            rebuildArticle(lyrics.lines, density, ctx)
        }
""",
    """        // 换歌 ⇒ 重建版面。
        // M54：画布尺寸 / density 变化**同样**必须重排 —— 版面里的字形度量、行高、
        // 纸张宽度、相机世界范围全部是按 `canvasSize × density` 烘死的，只认
        // `lines` 引用的话，TV↔手机切换、画中画、分屏、系统字号改 density 之后
        // 整篇版面永久错位（而且再也不会纠正，因为 lines 引用没变）。
        if (lyrics.lines !== builtLines ||
            builtViewportW != ctx.canvasSize.width ||
            builtViewportH != ctx.canvasSize.height ||
            builtDensity != density
        ) {
            rebuildArticle(lyrics.lines, density, ctx)
        }
""",
    "fv-check",
)
rep(
    """        builtLines = lines
        builtSourceLines = lines
""",
    """        builtLines = lines
        builtSourceLines = lines
        builtViewportW = ctx.canvasSize.width
        builtViewportH = ctx.canvasSize.height
        builtDensity = density
""",
    "fv-set",
)
save(p)
print("OK FullViewSubRenderer")
expect(p, ["builtDensity != density", "builtViewportW = ctx.canvasSize.width"])

print("ALL DONE")
