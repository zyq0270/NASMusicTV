package com.nasmusic.tv.backend.network.lx

import com.nasmusic.tv.util.AppLog
import com.quickjs.JSContext
import com.quickjs.QuickJS
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * 排空 QuickJS 微任务（job）队列。
 *
 * 为什么需要它：`quickjs-android:1.4.6` 的 JNI 只有 `JS_Eval` / `JS_Call`，
 * **从不调用 `JS_ExecutePendingJob`**（`libquickjs-android.so` 里连该符号引用都没有）。
 * 于是 `Promise.then` / `async` / `await` 的回调永远不会被推进 ——
 * 而洛雪脚本的 request 处理器几乎全是 `async`（星海就是），
 * `lx.request` 也是 Promise 语义 ⇒ 不排空 job 队列，取链必然返回空。
 *
 * 补法：`libquickjs.so`（AAR 内另一个 .so）**导出了** `JS_ExecutePendingJob`，
 * 用 JNA 直接调用即可，不引入自编译 `.so`，也不需要 NDK。
 * 代价：3 个 ABI 的 `libjnidispatch.so` 合计约 408 KB。
 */
internal interface LxJobPump {
    /**
     * @return 执行掉的 job 数；`< 0` 表示某个 job 里抛了 JS 异常（异常挂在 context 上）
     */
    fun drain(runtimePtr: Long): Int
}

internal object JnaLxJobPump : LxJobPump {
    private const val DIAG = "LxJobPump"

    /** 防止 `setInterval` 里互相 resolve 形成活锁；正常脚本一次排空是几十个以内 */
    private const val MAX_JOBS_PER_DRAIN = 4096

    private val executePendingJob: Function? by lazy {
        try {
            NativeLibrary.getInstance("quickjs").getFunction("JS_ExecutePendingJob")
        } catch (t: Throwable) {
            AppLog.w(DIAG, "无法绑定 JS_ExecutePendingJob：${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * `JS_ExecutePendingJob` 的 `pctx` 出参缓冲。
     *
     * 每个宿主 JS 线程自己用一个：drain 只在 JS 线程上跑，共享一块会被并发写；
     * 每次 drain 都新建则在 `setInterval` 场景下持续产出 native 内存（只有 GC 能回收）。
     */
    private val contextOut = object : ThreadLocal<Memory>() {
        override fun initialValue(): Memory = Memory(Native.POINTER_SIZE.toLong())
    }

    override fun drain(runtimePtr: Long): Int {
        val fn = executePendingJob ?: return 0
        if (runtimePtr == 0L) return 0
        // 签名：int JS_ExecutePendingJob(JSRuntime *rt, JSContext **pctx)，pctx 不可为 NULL
        val contextOut = contextOut.get() ?: return 0
        var executed = 0
        while (executed < MAX_JOBS_PER_DRAIN) {
            val code = try {
                fn.invokeInt(arrayOf<Any>(Pointer(runtimePtr), contextOut))
            } catch (t: Throwable) {
                AppLog.w(DIAG, "排空 job 失败：${t.javaClass.simpleName}: ${t.message}")
                return executed
            }
            if (code <= 0) return if (code < 0) -1 else executed
            executed++
        }
        AppLog.w(DIAG, "单次排空达到上限 $MAX_JOBS_PER_DRAIN，疑似脚本自激")
        return executed
    }
}

/**
 * 读 `com.quickjs` 的包私有指针字段。
 *
 * 这两个字段是库内部唯一的 native 句柄来源（JNI 层也这么用），
 * 且 release 构建会被 R8 改名 ⇒ `proguard-rules.pro` 里必须保留 `com.quickjs.**`。
 */
internal object LxQuickJsInternals {
    private val runtimeField by lazy {
        runCatching { QuickJS::class.java.getDeclaredField("runtimePtr").also { it.isAccessible = true } }.getOrNull()
    }
    private val contextField by lazy {
        runCatching { JSContext::class.java.getDeclaredField("contextPtr").also { it.isAccessible = true } }.getOrNull()
    }

    fun runtimePtrOf(quickJS: QuickJS): Long =
        runCatching { runtimeField?.getLong(quickJS) ?: 0L }.getOrDefault(0L)

    fun contextPtrOf(context: JSContext): Long =
        runCatching { contextField?.getLong(context) ?: 0L }.getOrDefault(0L)
}
