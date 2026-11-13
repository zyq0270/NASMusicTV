package com.nasmusic.tv.backend.network.lx

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.NetAddressGuard
import com.quickjs.JSArray
import com.quickjs.JSContext
import com.quickjs.JavaCallback
import com.quickjs.QuickJS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 单个洛雪音源脚本的 QuickJS 沙箱（方案 §5.1）。
 *
 * 线程模型（三件事必须同时成立，否则必死锁）：
 * 1. 一个脚本 = 一个 `createRuntimeWithEventQueue()` + 一个 `JSContext`，独占一个库内 HandlerThread；
 * 2. **绝不在 JS 线程上同步等 IO** —— `lx.request` 走 OkHttp `enqueue`，响应回到 IO 线程后
 *    再用 `postEventQueue { … }` 投回 JS 线程；
 * 3. 每次 JS 调用结束后都要**排空 job 队列**（见 [LxJobPump]）—— 否则 `await` 永不返回。
 *
 * 公开的 `suspend` 方法都要求**不在主线程**调用：库的每次 native 调用都会阻塞调用方直到
 * JS 线程执行完，落在主线程上就是掉帧/ANR。
 */
internal class LxScriptHost(
    private val scriptId: String,
    private val scriptText: String,
    private val bootScript: String,
    private val httpClient: OkHttpClient,
    private val jobPump: LxJobPump = JnaLxJobPump,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 等 `inited` 的上限；测试注入短值，生产用 [INIT_TIMEOUT_MS] */
    private val initTimeoutMs: Long = INIT_TIMEOUT_MS,
) : LxHost {

    private var runtime: QuickJS? = null
    private var context: JSContext? = null
    private var runtimeThread: Thread? = null

    @Volatile
    private var runtimePtr: Long = 0L

    @Volatile
    private var closed = false

    @Volatile
    private var initedReady: CompletableDeferred<LxScriptInfo>? = null

    /** 已加载的脚本信息（`load()` 之后才有值） */
    @Volatile
    override var info: LxScriptInfo? = null
        private set

    /**
     * S05-02：注入的 [httpClient] 默认 `followRedirects(true)` 且没有 network interceptor，
     * 脚本拼出的公网 URL 一旦 302 到内网/回环/云元数据，OkHttp 会自动跟过去，
     * 响应体还会经 [postResponse] 原样回传脚本（SSRF + 内网/云元数据内容外带）。
     * 派生一个逐跳复核的 client，与初始 URL 校验同口径用 [NetAddressGuard.rejectReason]。
     */
    private val guardedHttpClient: OkHttpClient = httpClient.newBuilder()
        .addNetworkInterceptor { chain ->
            val target = chain.request().url
            val reason = NetAddressGuard.rejectReason(target.toString())
            if (reason != null) throw IOException("blocked redirect hop: $reason")
            chain.proceed(chain.request())
        }
        .build()

    private val calls = ConcurrentHashMap<Int, Call>()
    private val results = ConcurrentHashMap<Int, CompletableDeferred<String?>>()
    private val timers = ConcurrentHashMap<Int, TimerEntry>()

    private val requestSeq = AtomicInteger()
    private val tokenSeq = AtomicInteger()

    private var timerThread: HandlerThread? = null

    @Volatile
    private var timerHandler: Handler? = null

    /** 脚本头注释（`@name` 等），供 `lx.currentScriptInfo` 使用 */
    private val header = LxScriptHeader.parse(scriptText)

    /**
     * 建立运行时、注入垫片、执行脚本正文，然后等 `inited`。
     *
     * 超时/抛错都会**销毁整个运行时**并把脚本标记为 `Failed` —— 脚本级 Broken 的依据（方案 §5.1）。
     */
    override suspend fun load(): LxScriptInfo {
        val engine = LxEngineAvailability.state
        if (engine is EngineState.Unavailable) {
            return LxScriptInfo.Failed(LxFailReason.L1_SCRIPT_ERROR, "洛雪引擎不可用：${engine.reason}")
                .also { info = it }
        }
        val inited = CompletableDeferred<LxScriptInfo>()
        initedReady = inited

        try {
            bootRuntime()
        } catch (t: Throwable) {
            AppLog.w(DIAG, "[$scriptId] 启动失败：${describe(t)}")
            closeEngine()
            return LxScriptInfo.Failed(LxFailReason.L1_SCRIPT_ERROR, describe(t)).also { info = it }
        }

        val settled = withTimeoutOrNull(initTimeoutMs) { inited.await() }
        val result = settled ?: run {
            closeEngine()
            LxScriptInfo.Failed(LxFailReason.L1_TIMEOUT, "inited 超时（$initTimeoutMs ms）")
        }
        info = result
        return result
    }

    /**
     * 调脚本的 request 处理器取播放直链。
     *
     * @param quality 洛雪档位（`128k` / `320k` / `flac` / `flac24bit`），由 [LxQualityMapper] 决定；
     *                传空串表示不指定，由脚本自己挑
     * @return 直链；脚本报错、超时或返回空均为 null（调用方据此走「平台级 Degraded」）
     */
    override suspend fun getMusicUrl(source: String, musicInfo: LxMusicInfo, quality: String): String? {
        if (closed) return null
        val token = tokenSeq.incrementAndGet()
        val deferred = CompletableDeferred<String?>()
        results[token] = deferred
        return try {
            postAndAwait(INVOKE_TIMEOUT_MS) {
                val ctx = context ?: throw IllegalStateException("宿主尚未加载")
                ctx.executeFunction2("__lxInvoke", token, source, ACTION_MUSIC_URL, musicInfo.toJson(), quality)
            }
            withTimeoutOrNull(INVOKE_TIMEOUT_MS) { deferred.await() }
        } catch (t: Throwable) {
            AppLog.w(DIAG, "[$scriptId] getMusicUrl($source) 失败：${describe(t)}")
            null
        } finally {
            results.remove(token)
        }
    }

    /**
     * 取消所有在途请求与等待中的取链（播放抢占用；方案 §5.4.1.5）。
     *
     * ⛔ 只打断本宿主自己的请求，不碰运行时本身 —— 取消后可继续用。
     */
    override fun cancelPending() {
        val inFlight = ArrayList(calls.values)
        calls.clear()
        inFlight.forEach { runCatching { it.cancel() } }

        val waiting = ArrayList(results.values)
        results.clear()
        waiting.forEach { it.complete(null) }

        val rt = runtime ?: return
        if (closed) return
        // 非阻塞投递：cancelPending 可能从主线程调用，绝不能在这里等 JS 线程
        rt.postEventQueue {
            try {
                context?.executeFunction2("__lxAbortAll")
            } catch (t: Throwable) {
                AppLog.d(DIAG, "[$scriptId] __lxAbortAll 失败：${describe(t)}")
            } finally {
                drainJobs()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        calls.values.forEach { runCatching { it.cancel() } }
        calls.clear()
        results.values.forEach { it.complete(null) }
        results.clear()
        initedReady?.cancel()
        timers.clear()
        runCatching { runtime?.close() }
        runtime = null
        context = null
        runCatching { timerThread?.quitSafely() }
        timerThread = null
        timerHandler = null
    }

    // ─────────────────────────── 启动 ───────────────────────────

    private fun bootRuntime() {
        val rt = QuickJS.createRuntimeWithEventQueue()
        runtime = rt

        val ready = CountDownLatch(1)
        val failure = arrayOfNulls<Throwable>(1)
        rt.postEventQueue {
            try {
                runtimeThread = Thread.currentThread()
                runtimePtr = LxQuickJsInternals.runtimePtrOf(rt)
                // createContext 是库内唯一不走 post 的 native 调用 ⇒ 必须在运行时线程上建
                val ctx = rt.createContext()
                context = ctx
                registerNatives(ctx)
                ctx.set("__scriptInfoJson", header.toJson())
                ctx.set("__rawScript", scriptText)
                ctx.executeVoidScript(bootScript, BOOT_FILE_NAME)
                drainJobs()
                ctx.executeVoidScript(scriptText, "$scriptId.js")
            } catch (t: Throwable) {
                failure[0] = t
            } finally {
                drainJobs()
                ready.countDown()
            }
        }
        if (!ready.await(BOOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("运行时启动超时（${BOOT_TIMEOUT_MS} ms）")
        }
        failure[0]?.let { throw it }
    }

    private fun registerNatives(ctx: JSContext) {
        // ⚠️ 这些回调都从 JS 栈上被调进来，**任何异常都不能往外抛**（会穿过 JNI 回到解释器）
        ctx.registerJavaMethod(JavaCallback { _, args -> onNativeRequest(args) }, "__nativeRequest")
        ctx.registerJavaMethod(JavaCallback { _, args -> onNativeCancel(args) }, "__nativeCancel")
        ctx.registerJavaMethod(JavaCallback { _, args -> onNativeSend(args) }, "__nativeSend")
        ctx.registerJavaMethod(JavaCallback { _, args -> onNativeLog(args) }, "__nativeLog")
        ctx.registerJavaMethod(JavaCallback { _, args -> onNativeTimer(args) }, "__nativeTimer")
        ctx.registerJavaMethod(JavaCallback { _, args -> onNativeClearTimer(args) }, "__nativeClearTimer")
        ctx.registerJavaMethod(JavaCallback { _, args -> onNativeCrypto(args) }, "__nativeCrypto")
        ctx.registerJavaMethod(JavaCallback { _, args -> onNativeZlib(args) }, "__nativeZlib")
        ctx.registerJavaMethod(JavaCallback { _, args -> onNativeResult(args) }, "__nativeResult")
    }

    // ─────────────────────────── JS 线程调度 ───────────────────────────

    /**
     * 在运行时线程上跑一段 JS，并等它结束。
     *
     * 必须是**非主线程**调用：库的 native 调用会阻塞调用方。
     */
    private fun postAndAwait(timeoutMs: Long, block: () -> Any?): Any? {
        val rt = runtime ?: return null
        check(Looper.myLooper() != Looper.getMainLooper()) { "洛雪宿主禁止在主线程调用" }
        if (Thread.currentThread() === runtimeThread) return block()

        val latch = CountDownLatch(1)
        val failure = arrayOfNulls<Throwable>(1)
        rt.postEventQueue {
            try {
                block()
            } catch (t: Throwable) {
                failure[0] = t
            } finally {
                drainJobs()
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("JS 线程无响应（${timeoutMs} ms）")
        }
        failure[0]?.let { throw it }
        return null
    }

    private fun drainJobs() {
        if (closed) return
        val ptr = runtimePtr
        if (ptr == 0L) return
        val executed = try {
            jobPump.drain(ptr)
        } catch (t: Throwable) {
            AppLog.w(DIAG, "[$scriptId] 排空 job 异常：${describe(t)}")
            return
        }
        if (executed < 0) {
            AppLog.w(DIAG, "[$scriptId] JS job 抛出异常：${pendingExceptionText()}")
        }
    }

    private fun pendingExceptionText(): String = try {
        val ctx = context ?: return "context 已释放"
        val pieces = ctx.getNative()._getException(LxQuickJsInternals.contextPtrOf(ctx))
        pieces?.joinToString(" | ") ?: "（无栈）"
    } catch (t: Throwable) {
        "（读取失败：${describe(t)}）"
    }

    // ─────────────────────────── __native* 实现 ───────────────────────────

    private fun onNativeRequest(args: JSArray): Any? {
        if (closed) return null
        return try {
            val id = args.getInteger(0)
            val url = args.getString(1)
            val optsJson = args.getString(2).orEmpty()
            startRequest(id, url, optsJson)
            null
        } catch (t: Throwable) {
            AppLog.w(DIAG, "[$scriptId] __nativeRequest 失败：${describe(t)}")
            null
        }
    }

    private fun onNativeCancel(args: JSArray): Any? {
        return try {
            val id = args.getInteger(0)
            calls.remove(id)?.let { runCatching { it.cancel() } }
            null
        } catch (t: Throwable) {
            null
        }
    }

    private fun startRequest(id: Int, url: String, optsJson: String) {
        // C21：脚本拼出来的 URL 必须过地址守卫。
        // 远端 JS 只要能发请求，就能把 127.0.0.1 / 192.168.x / 169.254.169.254 的响应
        // 原样读回来再回传给脚本（SSRF + 内网/云元数据内容外带）。
        NetAddressGuard.rejectReason(url)?.let { reason ->
            AppLog.w(DIAG, "[$scriptId] 拒绝出站请求：$reason")
            postResponse(id, false, 0, "{}", reason)
            return
        }
        val opts = runCatching { JSONObject(optsJson) }.getOrNull() ?: JSONObject()
        val builder = try {
            Request.Builder().url(url)
        } catch (e: IllegalArgumentException) {
            // 脚本拼出非法 URL：立刻回一个失败，别让它挂在 Promise 上
            postResponse(id, false, 0, "{}", "非法 URL：$url")
            return
        }
        builder.header("User-Agent", USER_AGENT)
        opts.optJSONObject("headers")?.let { headers ->
            for (key in headers.keys()) {
                // C21：Host / Connection / Content-Length 等由 OkHttp 自己算，
                // 脚本手改只会制造畸形请求（Host 还能用来做虚拟主机绕过）。
                if (key.lowercase() in NetAddressGuard.FORBIDDEN_REQUEST_HEADERS) {
                    AppLog.w(DIAG, "[$scriptId] 已忽略脚本设置的请求头：$key")
                    continue
                }
                builder.header(key, headers.optString(key))
            }
        }
        val method = opts.optString("method", "GET").ifBlank { "GET" }.uppercase()
        val bodyText = opts.optString("body", "").ifEmpty {
            opts.optJSONObject("form")?.let { encodeForm(it) }.orEmpty()
        }
        val mediaType = opts.optJSONObject("headers")
            ?.optString("Content-Type")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { it.toMediaTypeOrNull() }.getOrNull() }
        if (method != "GET" && method != "HEAD") {
            builder.method(method, bodyText.toRequestBody(mediaType))
        } else {
            builder.method(method, null)
        }

        val call = guardedHttpClient.newCall(builder.build())
        calls[id] = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                postResponse(id, false, 0, "{}", e.message ?: e.javaClass.simpleName)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    // S05-02：回传脚本前再校验最终 URL（纵深防御）—— 即便注入的 client
                    // 未来换了重定向策略，也不能把内网/元数据响应体经 __lxOnResponse 回传脚本。
                    val reject = NetAddressGuard.rejectReason(it.request.url.toString())
                    if (reject != null) {
                        AppLog.w(DIAG, "[$scriptId] 拒绝回传最终响应：$reject")
                        postResponse(id, false, 0, "{}", reject)
                        return@use
                    }
                    val body = try {
                        it.body?.string().orEmpty()
                    } catch (e: IOException) {
                        ""
                    }
                    postResponse(id, true, it.code, headersToJson(it), body)
                }
            }
        })
    }

    private fun encodeForm(form: JSONObject): String {
        val parts = mutableListOf<String>()
        for (key in form.keys()) {
            val value = form.optString(key)
            parts += "${java.net.URLEncoder.encode(key, "UTF-8")}=${java.net.URLEncoder.encode(value, "UTF-8")}"
        }
        return parts.joinToString("&")
    }

    private fun headersToJson(response: Response): String {
        val obj = JSONObject()
        val headers = response.headers
        for (name in headers.names()) {
            obj.put(name.lowercase(), headers[name].orEmpty())
        }
        return obj.toString()
    }

    private fun postResponse(id: Int, ok: Boolean, status: Int, headersJson: String, body: String) {
        calls.remove(id)
        val rt = runtime ?: return
        if (closed) return
        rt.postEventQueue {
            try {
                context?.executeFunction2("__lxOnResponse", id, ok, status, headersJson, body)
            } catch (t: Throwable) {
                AppLog.d(DIAG, "[$scriptId] __lxOnResponse 失败：${describe(t)}")
            } finally {
                drainJobs()
            }
        }
    }

    private fun onNativeResult(args: JSArray): Any? {
        return try {
            val token = args.getInteger(0)
            val ok = args.getBoolean(1)
            val payload = args.getString(2)
            results.remove(token)?.complete(if (ok) payload?.takeIf { it.isNotBlank() } else null)
            null
        } catch (t: Throwable) {
            null
        }
    }

    private fun onNativeSend(args: JSArray): Any? {
        return try {
            val event = args.getString(0).orEmpty()
            val data = args.getString(1).orEmpty()
            when (event) {
                EVENT_INITED -> completeInited(data)
                EVENT_UPDATE_ALERT -> AppLog.i(DIAG, "[$scriptId] updateAlert: $data")
                else -> AppLog.d(DIAG, "[$scriptId] send($event)")
            }
            null
        } catch (t: Throwable) {
            AppLog.w(DIAG, "[$scriptId] __nativeSend 失败：${describe(t)}")
            null
        }
    }

    private fun completeInited(data: String) {
        val deferred = initedReady ?: return
        if (deferred.isCompleted) return
        val json = runCatching { JSONObject(data) }.getOrNull()
        if (json != null && json.has("status") && !json.optBoolean("status", true)) {
            deferred.complete(LxScriptInfo.Failed(LxFailReason.L1_SCRIPT_ERROR, "脚本自报 status=false"))
            return
        }
        val sources = linkedMapOf<String, LxDeclaredSource>()
        json?.optJSONObject("sources")?.let { obj ->
            for (key in obj.keys()) {
                val item = obj.optJSONObject(key) ?: continue
                sources[key] = LxDeclaredSource(
                    name = item.optString("name").takeIf { it.isNotBlank() },
                    actions = item.optJSONArray("actions").toStringList(),
                    qualitys = item.optJSONArray("qualitys").toStringList(),
                )
            }
        }
        if (sources.isEmpty()) {
            deferred.complete(LxScriptInfo.Failed(LxFailReason.L1_NO_SOURCES, "inited 未声明任何平台"))
            return
        }
        val version = json?.optString("version")?.takeIf { it.isNotBlank() } ?: header.version
        deferred.complete(LxScriptInfo.Loaded(sources, version, header.name))
    }

    private fun onNativeLog(args: JSArray): Any? {
        return try {
            val level = args.getString(0).orEmpty()
            val message = args.getString(1).orEmpty()
            val line = "[lx:$scriptId] $message"
            when (level) {
                "e" -> AppLog.e(DIAG, line)
                "w" -> AppLog.w(DIAG, line)
                "i" -> AppLog.i(DIAG, line)
                else -> AppLog.d(DIAG, line)
            }
            null
        } catch (t: Throwable) {
            null
        }
    }

    private fun onNativeTimer(args: JSArray): Any? {
        return try {
            val kind = args.getString(0).orEmpty()
            val id = args.getInteger(1)
            val delay = args.getInteger(2).toLong().coerceIn(0L, MAX_TIMER_MS)
            val entry = TimerEntry(id, repeat = kind == EVENT_INTERVAL, delayMs = delay)
            timers[id] = entry
            timerHandler().postDelayed(entry, delay)
            null
        } catch (t: Throwable) {
            AppLog.d(DIAG, "[$scriptId] __nativeTimer 失败：${describe(t)}")
            null
        }
    }

    private fun onNativeClearTimer(args: JSArray): Any? {
        return try {
            val id = args.getInteger(0)
            timers.remove(id)?.let { timerHandler?.removeCallbacks(it) }
            null
        } catch (t: Throwable) {
            null
        }
    }

    private fun onNativeCrypto(args: JSArray): Any? {
        return try {
            LxNativeCodec.crypto(args.getString(0).orEmpty(), args.getString(1).orEmpty())
        } catch (t: Throwable) {
            AppLog.w(DIAG, "[$scriptId] crypto 失败：${describe(t)}")
            "$NATIVE_ERROR_PREFIX${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun onNativeZlib(args: JSArray): Any? {
        return try {
            LxNativeCodec.zlib(args.getString(0).orEmpty(), args.getString(1).orEmpty())
        } catch (t: Throwable) {
            AppLog.w(DIAG, "[$scriptId] zlib 失败：${describe(t)}")
            "$NATIVE_ERROR_PREFIX${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun fireTimer(id: Int, repeat: Boolean) {
        val rt = runtime ?: return
        if (closed) return
        rt.postEventQueue {
            try {
                context?.executeFunction2("__runTimer", id, repeat)
            } catch (t: Throwable) {
                AppLog.d(DIAG, "[$scriptId] 定时器回调失败：${describe(t)}")
            } finally {
                drainJobs()
            }
        }
    }

    private fun timerHandler(): Handler {
        timerHandler?.let { return it }
        synchronized(this) {
            timerHandler?.let { return it }
            val thread = HandlerThread("LxTimer-$scriptId").also { it.start() }
            timerThread = thread
            return Handler(thread.looper).also { timerHandler = it }
        }
    }

    private fun closeEngine() {
        runCatching { runtime?.close() }
        runtime = null
        context = null
        runtimePtr = 0L
    }

    private inner class TimerEntry(
        private val id: Int,
        private val repeat: Boolean,
        private val delayMs: Long,
    ) : Runnable {
        override fun run() {
            if (closed) {
                timers.remove(id)
                return
            }
            fireTimer(id, repeat)
            if (repeat && !closed && timers[id] === this) {
                timerHandler().postDelayed(this, delayMs)
            } else if (!repeat) {
                timers.remove(id)
            }
        }
    }

    private companion object {
        const val DIAG = "LxHost"
        const val BOOT_FILE_NAME = "lx-boot.js"
        const val ACTION_MUSIC_URL = "musicUrl"
        const val EVENT_INITED = "inited"
        const val EVENT_UPDATE_ALERT = "updateAlert"
        const val EVENT_INTERVAL = "interval"
        const val NATIVE_ERROR_PREFIX = "__err:"

        /** 洛雪本体也带这个 UA；部分源的网关会按 UA 过滤 */
        const val USER_AGENT = "lx-music-mobile/2.0.0"

        const val INIT_TIMEOUT_MS = 10_000L
        const val BOOT_TIMEOUT_MS = 15_000L
        const val INVOKE_TIMEOUT_MS = 8_000L
        const val MAX_TIMER_MS = 60 * 60 * 1000L

        fun describe(t: Throwable): String = "${t.javaClass.simpleName}: ${t.message}"
    }
}

internal fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) out += optString(i)
    return out
}
