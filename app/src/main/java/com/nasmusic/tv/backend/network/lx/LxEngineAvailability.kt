package com.nasmusic.tv.backend.network.lx

/**
 * 本机 JS 引擎可用性判据（方案 §7.7 第 7 条）。
 *
 * ⛔ **判据只能是「这台设备真的 `dlopen` 成功了吗」**，不能是 `Build.VERSION.SDK_INT`：
 * QuickJS-android 的 AAR 是 `minSdkVersion 18`、`android.` 包引用全是 API 1，
 * API 22 的老电视完全能跑；同一版 Android 上 ABI 缺失 / ROM 裁剪 native 支持 /
 * 内存不足都会让加载失败，反过来也有低版本设备能跑。按版本号硬卡会**既误杀又漏放**。
 *
 * ⚠️ `loadLibrary` 要读磁盘 + 重定位，首次求值 **必须在后台线程**（主线程会掉帧）。
 */
internal sealed interface EngineState {
    data object Available : EngineState
    data class Unavailable(val reason: String) : EngineState
}

internal object LxEngineAvailability {

    @Volatile
    private var cached: EngineState? = null

    /** 求值一次并缓存（`@Volatile` 双检，允许并发调用但只真正加载一次） */
    val state: EngineState
        get() = cached ?: synchronized(this) {
            cached ?: probe().also { cached = it }
        }

    private fun probe(): EngineState = try {
        System.loadLibrary("quickjs")
        System.loadLibrary("quickjs-android")
        EngineState.Available
    } catch (e: UnsatisfiedLinkError) {
        EngineState.Unavailable(e.message ?: e.javaClass.simpleName)
    } catch (e: SecurityException) {
        // 少数 ROM 禁自定义 native 库
        EngineState.Unavailable(e.message ?: e.javaClass.simpleName)
    }

    /** 供单测注入 / 复位（生产不调用） */
    internal fun overrideForTest(state: EngineState?) {
        cached = state
    }
}
