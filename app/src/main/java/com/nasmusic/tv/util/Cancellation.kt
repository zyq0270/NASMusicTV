package com.nasmusic.tv.util

import kotlinx.coroutines.CancellationException

/**
 * 若当前异常是**协程取消信号**则原样重抛（C22）。
 *
 * ## 为什么需要它
 *
 * `CancellationException` 是 `IllegalStateException` 的子类 ⇒ 任何
 * `catch (e: Exception)` 都会把它一起吞掉。而协程的取消是**协作式**的：
 * 只要有一层 catch 把取消信号转成「失败 / null / 空列表」返回，上层就再也
 * 看不到取消，`job.cancel()` 形同虚设，后续逻辑会继续在已废弃的上下文里干活
 * （典型后果：把结果写回已销毁的 ViewModel、在已 `release()` 的播放器上 `play()`、
 * `async` 里的取消被吞后父作用域挂不上取消）。
 *
 * ## 用法
 *
 * ```kotlin
 * } catch (e: Exception) {
 *     e.rethrowIfCancellation()   // ⛔ 必须是 catch 体的第一句
 *     ...原有的兜底逻辑...
 * }
 * ```
 *
 * ## 适用范围
 *
 * 只对**挂起上下文**（`suspend fun` / `async {}` / `launch {}`）里的 catch 有必要。
 * 纯阻塞回调（OkHttp `Callback`、`Runnable`、工作线程循环体）没有协程取消语义，
 * 不必加 —— 加了也不会错，只是噪音。
 *
 * ⚠️ 不要写成 `catch (e: CancellationException) { throw e }` 的等价展开到处复制：
 * 那样每个文件都要多一个 `kotlinx.coroutines.CancellationException` 导入，
 * 且容易漏改其中一处。统一走本函数，便于后续用门禁扫描「catch 体首句」。
 */
fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
