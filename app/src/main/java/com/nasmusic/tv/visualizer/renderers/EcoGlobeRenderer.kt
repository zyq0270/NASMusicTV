package com.nasmusic.tv.visualizer.renderers

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.google.gson.Gson
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRenderer

/**
 * E54「生态地球」渲染器 —— 把 `docs/globe-present.html` 那份演示页搬进 App。
 *
 * 方案与改造清单：`docs/archive/eco-globe-webview-plan.md`（§4.5 六处改造 / §5 宿主接线）。
 *
 * ## 与 E41「世界」的关系
 * **并列的两个独立场景**，不合并：E41 是「航线地球」，本类是一次性摆满
 * 150 棵树 / 19 只动物 / 山脉 / 龙卷风 / 三层云 / 极光 / 流星 / 四季的生态地球。
 * 两者的宿主代码同源（同一套虚拟资产域 + WebView + 100ms 音频桥），
 * 但**页面与资产各自独立**：
 * - 共享（不复制）⇒ `/globe/polyfill.es5.js`、`/globe/three.es5.js`、`/globe/three-globe.es5.js`
 * - 本主题独有 ⇒ `/eco-globe/` 下的 index.html、globe.js、loaders.es5.js、glb 里的 .glb、tex 里的 .png
 *
 * ## 为什么自己带一份 WebViewClient（而不是复用 [WorldGlobeRenderer] 的）
 * 页面要**同时**引用 `/globe/`（共享库）与 `/eco-globe/`（本主题资产）两个前缀 ⇒ 拦截器必须
 * 路由**两个前缀**。把它抽成共用函数的收益不足以抵掉「动 E41 生产代码」的风险
 * （方案 §九 R6：⛔ 不碰 `assets/globe/` 与 E41 源码）⇒ 本类独立实现，E41 零改动。
 *
 * ## API 契约（与 `assets/eco-globe/globe.js` 末尾的 `window.EcoGlobe` 一一对应）
 * ```
 * window.EcoGlobe.setAudio({energy,bass,mid,treble,beat})  // 全 0..1
 * window.EcoGlobe.initApp()                               // 页面内部自己调用
 * window.EcoGlobe.fps()                                   // → 实际渲染帧率
 * ```
 * 音频只影响**自转 / 云层速度**（原型页本身没有音频，见 globe.js 顶部 AUDIO 注释）；
 * 摆放、季节、龙卷风时序等一律保持演示页原值 ⇒ 观感可逐帧对账。
 *
 * ⚠️ 电视端仍黑屏：这是 WebView 宿主的既有问题（`docs/technical-overview.md` §10.215），
 * 与 E41 完全同源，本类不做电视兼容（方案 §4.3）。
 */
internal class EcoGlobeRenderer(context: Context) : VisualizerRenderer {
    override val theme: VisualizerTheme = VisualizerTheme.ECO_GLOBE

    /** View 型渲染器旁路：舞台用 AndroidView 承载，draw() 不再被调用 */
    override val isViewBased: Boolean get() = true

    /** 接口要求实现 draw；View 型渲染器旁路下舞台不调用它，恒为空实现 */
    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {}

    /** 音频桥接粒度（ms）：与 E41 一致 */
    private val TICK_MS = 100L

    /** EMA 平滑系数（0..1，越大越跟手）：与 E41 一致 */
    private val EMA_ALPHA = 0.35f

    /** 帧率回读间隔（ms）—— 每 500ms 向页面问一次，避免 10Hz 的 JS 桥往返 */
    private val FPS_POLL_MS = 500L

    private val appContext = context.applicationContext
    private val gson = Gson()

    /** 100ms 音频事件定时器（主线程） */
    private val handler = Handler(Looper.getMainLooper())

    private var webView: WebView? = null
    private var attached = false
    private var pageReady = false

    // ── 音频 EMA 平滑态（0..1，桥接给 JS）────────────────────────────────
    private var emaEnergy = 0f
    private var emaBass = 0f
    private var emaMid = 0f
    private var emaTreble = 0f
    private var emaBeat = 0f

    /** 页面自报的实际渲染帧率（≤0 = 未知/canvas 未起） */
    @Volatile
    private var lastFps = 0f

    private var lastFpsPollMs = 0L

    // ── 生命周期（VisualizerRenderer 接口）───────────────────────────────

    override fun onEnter(ctx: RenderContext) {
        // 画质档位对本效果无影响：数量全部写死在 JS 侧（needsParticleBudget = false）
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun createView(context: Context): View {
        val wv = WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.domStorageEnabled = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            // ⛔ 禁焦三件套（E41 同款）：WebView 子树必须完全让出 Android 视图焦点，
            //    否则遥控器方向键全部路由到 WebView，Compose 焦点系统（切效果/返回键）失效。
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            isClickable = false
            isLongClickable = false
            webViewClient = object : WebViewClient() {
                // ⛔ file:// 页面在 Android 12+（targetSdk 30+）被禁加载其它 file:// 子资源
                //    ⇒ 三份共享库被静默拦截 ⇒ THREE 未定义 ⇒ 黑屏（§10.215 的既有结论）。
                //    方案同 E41：页面从虚拟 HTTPS 资产域加载，脚本由本方法从 assets 流式提供。
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    // 只接管虚拟资产域，判定用 **host**（favicon 是从域根发起的，不带子路径）
                    if (Uri.parse(url).host != ASSET_HOST) return null
                    val path = Uri.parse(url).path ?: "/"
                    // favicon：WebView 的自动请求，assets 里没有 ⇒ 200 + 空 body
                    if (path.endsWith("/favicon.ico") || path == "favicon.ico") {
                        return emptyBody("image/x-icon")
                    }
                    // 前缀路由：本页**同时**引用 /globe/（共享库）与 /eco-globe/（本主题）
                    val dir: String
                    val assetName: String
                    when {
                        path.startsWith("/eco-globe/") -> {
                            dir = "eco-globe"
                            assetName = path.removePrefix("/eco-globe/")
                        }
                        path.startsWith("/globe/") -> {
                            dir = "globe"
                            assetName = path.removePrefix("/globe/")
                        }
                        // ⛔ 资产域内请求**永不返回 null**（返回 null 会 fallback 真联网，
                        //    该保留域无公网 DNS ⇒ ERR_NAME_NOT_RESOLVED ⇒ 纯噪音甚至黑屏）
                        else -> return emptyBody("text/plain")
                    }
                    return try {
                        val stream = appContext.assets.open("$dir/$assetName")
                        WebResourceResponse(mimeOf(assetName), "UTF-8", stream)
                    } catch (e: Exception) {
                        // 缺资产 ⇒ 页面自身会报错并显示错误框（⛔ 没有 CDN 回退）
                        AppLog.e("EcoGlobe", "asset not found: $dir/$assetName", e)
                        emptyBody("text/plain")
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                    // 本页没有需要 Kotlin 注入的数据（城市坐标是 E41 的契约，这里不需要）
                }

                // 资源/页面加载失败时留痕（黑屏诊断用）。重载从 API 23 起才回调 ⇒ 标 @RequiresApi。
                @RequiresApi(Build.VERSION_CODES.M)
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    val url = request?.url?.toString() ?: ""
                    if (url.endsWith("/favicon.ico")) {
                        AppLog.d(
                            "EcoGlobe",
                            "favicon fetch failed (ignorable): ${error?.errorCode} url=$url"
                        )
                        return
                    }
                    AppLog.e(
                        "EcoGlobe",
                        "page/resource error: ${error?.errorCode} ${error?.description} url=$url"
                    )
                }
            }
            // ⛔ JS console/异常转发到 logcat：没有它，globe.js 的运行时错误
            //    （WebGL 不可用 / 模型缺失 / 契约错）全部吞掉，黑屏无从诊断。
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    val level = msg.messageLevel()
                    val text = "JS[$level] ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}"
                    when (level) {
                        // ERROR 恒 e：JS 运行时错误是黑屏诊断的主要线索，⛔ 不许降级
                        ConsoleMessage.MessageLevel.ERROR -> AppLog.e("EcoGlobe", text)
                        ConsoleMessage.MessageLevel.WARNING -> {
                            val benign =
                                // CLI 在无 ?debug=1 时也会报的 three.js 弃用提示（ES5 UMD 策略固有）
                                text.contains("deprecated with r150") ||
                                // three-globe.es5.js 内置一份 three.core，与 three.es5.js 重复注册。
                                // 既定用法（页面只绑 window.THREE）下真机渲染正常 ⇒ 降 d。
                                (text.contains("Multiple instances of Three.js") &&
                                    text.contains("three-globe.es5.js"))
                            if (benign) AppLog.d("EcoGlobe", text) else AppLog.w("EcoGlobe", text)
                        }
                        else -> AppLog.i("EcoGlobe", text)
                    }
                    return true
                }
            }
            loadUrl("$ASSET_BASE/eco-globe/index.html")
        }
        webView = wv
        return wv
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".html") -> "text/html"
        name.endsWith(".js") -> "application/javascript"
        name.endsWith(".json") -> "application/json"
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".glb") -> "model/gltf-binary"
        else -> "application/octet-stream"
    }

    /**
     * 空 body 响应（200 + 0 字节）：favicon 与资产域内越界路径专用。
     *
     * ⛔ 这些路径**绝不能返回 null**：返回 null 会让 WebView fallback 真联网，而
     * `appassets.androidplatform.net` 是无公网 DNS 的保留域 ⇒ 国内直接
     * ERR_NAME_NOT_RESOLVED（纯噪音；极端情况下阻塞渲染）。
     */
    private fun emptyBody(mime: String): WebResourceResponse =
        WebResourceResponse(mime, "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))

    companion object {
        /** 虚拟资产域 host：与 E41 共用（同一 host，不同子路径） */
        private const val ASSET_HOST = "appassets.androidplatform.net"

        /** 虚拟资产域根：`/globe`（共享库）与 `/eco-globe`（本主题）都挂在它下面 */
        private const val ASSET_BASE = "https://appassets.androidplatform.net"
    }

    override fun onViewAttached() {
        if (attached) return
        attached = true
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    override fun onViewDetached() {
        attached = false
        handler.removeCallbacks(tickRunnable)
        // ⛔ 不能同步 destroy：onDispose 触发时 AndroidView 的 view 可能仍挂在组合
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

    /**
     * 实际渲染帧率给**页面自报**（500ms 滑窗，见 globe.js 的 `FPS`）。
     *
     * ⛔ 必须覆写：View 型渲染器有自己的 rAF 循环，舞台的 Choreographer 口径对它是
     *    假读数（「显示 59 fps 但画面很卡」）。≤0 时舞台自动退回旧口径。
     */
    override fun renderFps(): Float = lastFps

    // ── 音频桥接（100ms 事件粒度）────────────────────────────────────────

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!attached || webView == null) return
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun tick() {
        if (webView == null || !pageReady) return
        val frame = spectrumFrame()
        if (frame != null) {
            emaEnergy = ema(emaEnergy, frame.energy)
            emaBass = ema(emaBass, frame.bass)
            emaMid = ema(emaMid, frame.mid)
            emaTreble = ema(emaTreble, frame.treble)
            emaBeat = if (frame.beat) 1f else emaBeat * 0.80f
            sendAudio()
        }
        pollFps(frame?.timeMs ?: 0L)
    }

    private fun spectrumFrame(): AudioFrame? = try {
        (appContext as NasMusicApp).playerManager.spectrumRepository.frame
    } catch (t: Throwable) {
        null
    }

    private fun ema(old: Float, v: Float): Float = old + (v - old) * EMA_ALPHA

    private fun sendAudio() {
        val params = mapOf(
            "energy" to emaEnergy.coerceIn(0f, 1f),
            "bass" to emaBass.coerceIn(0f, 1f),
            "mid" to emaMid.coerceIn(0f, 1f),
            "treble" to emaTreble.coerceIn(0f, 1f),
            "beat" to emaBeat.coerceIn(0f, 1f)
        )
        evalJs("EcoGlobe.setAudio(${gson.toJson(params)})")
    }

    /**
     * 向页面要一次实际帧率。
     *
     * [nowMs] 为 0（没有音频帧时）退化为墙钟，避免暂停时帧率读数永久冻结在旧值。
     */
    private fun pollFps(nowMs: Long) {
        val now = if (nowMs > 0L) nowMs else System.currentTimeMillis()
        if (now - lastFpsPollMs < FPS_POLL_MS) return
        lastFpsPollMs = now
        try {
            webView?.evaluateJavascript("EcoGlobe.fps()") { value ->
                val v = value?.trim()?.trim('"')?.toFloatOrNull() ?: 0f
                // 页面还没跑第一帧时是 0 ⇒ 保留 0（舞台会退回 Choreographer 口径）
                lastFps = if (v.isFinite() && v > 0f) v else 0f
            }
        } catch (t: Throwable) {
            // 页面卸载竞态等：静默忽略，下一次轮询重试
        }
    }

    private fun evalJs(script: String) {
        try {
            webView?.evaluateJavascript(script, null)
        } catch (t: Throwable) {
            // 页面卸载竞态等：静默忽略，下一 tick 重发
        }
    }
}
