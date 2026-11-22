package com.nasmusic.tv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 睡眠定时器（F2-2）：到点自动暂停播放。
 *
 * 设计（docs/archive/feature-dev-plan-2026-09.md §2.3）：
 * - 不持久化：电视场景"定时 = 今晚"，重启即重置（设计取舍）
 * - 预设档位 15/30/60/90 分钟
 * - 到期回调 [onExpired]，由 PlayerManager 触发 pause() + 通知刷新
 * - remaining() 供通知栏显示剩余分钟
 *
 * P1#4（2026-09-14）：到期调度由 `Handler.postDelayed` 改为**协程 `delay`**。
 * 原实现自持一个 `Handler`，并用 `AtomicLong` 令牌防御 cancel 与到期的竞争；
 * 协程化后取消语义由 [Job] 直接承载（`cancel()` 即取消在途 delay），令牌守卫不再必要，
 * 与本项目"协程优先"的调度风格统一，且**单测可用虚拟时间验证真实到期路径**
 * （原 `Handler` 版本在 Robolectric 下不驱动 Looper，只能靠公开的 [tickExpired]
 * 手工驱动，测不到调度本身）。
 *
 * 时间源与调度器均可注入（[scope] / [nowMsProvider]），单测无需真实等待。
 * [scope] 默认主线程 immediate 调度器，与原先 `Handler(Looper.getMainLooper())` 等价；
 * 其生命周期与 [PlayerManager]（app 级单例）一致。
 */
class SleepTimerController(
    private val onExpired: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()),
    private val nowMsProvider: () -> Long = System::currentTimeMillis
) {
    sealed interface State {
        data object Off : State
        /**
         * [remainingMinutes] 随计时推进周期性更新（详见 [start] 的 UI tick 协程）。
         *
         * ⚠️ 2026-11-22 修复：此前只有 endsAtMs/totalMinutes 两个固定字段 ⇒ StateFlow 在
         * start 之后到到期之前**不再 emit**，而 M26 优化后播放页不再每秒重组 ⇒ 按钮上的
         * 「剩余 N 分钟」冻结在启动那一刻。携带一个会变化的值让 collectAsState 收到新状态，
         * UI 自然重算显示（单一真相源，不改播放页的重组边界）。
         */
        data class Running(val endsAtMs: Long, val totalMinutes: Int, val remainingMinutes: Int) : State
        data object Finished : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state

    /** 在途的到期调度（P1#4：以 Job 承载取消语义，替代原 Handler + AtomicLong 令牌） */
    private var expiryJob: Job? = null

    /** 在途的 UI 分钟 tick（与到期调度分离，取消一个不影响另一个） */
    private var tickJob: Job? = null

    companion object {
        /** UI 分钟刷新的粒度：30 秒足够让向上取整的分钟显示及时变化，又不至于刷屏 */
        private const val UI_TICK_INTERVAL_MS = 30_000L
    }

    /** 剩余毫秒（未运行返回 0） */
    fun remaining(): Long = when (val s = _state.value) {
        is State.Running -> (s.endsAtMs - nowMsProvider()).coerceAtLeast(0)
        else -> 0
    }

    /** 剩余分钟（向上取整，供通知显示） */
    fun remainingMinutes(): Int = ((remaining() + 59_999) / 60_000).toInt()

    /** 启动定时；minutes <= 0 视为取消 */
    fun start(minutes: Int) {
        cancel()
        if (minutes <= 0) return
        val endsAt = nowMsProvider() + minutes * 60_000L
        _state.value = State.Running(endsAt, minutes, minutes)
        expiryJob = scope.launch {
            delay(minutes * 60_000L)
            tickExpired()
        }
        // UI 分钟 tick：定期重算剩余分钟并 emit 新状态，让订阅者（播放页/通知）重绘。
        // 到期后 tickExpired 把状态置为 Finished，本协程下一轮读到非 Running 自动退出。
        tickJob = scope.launch {
            while (true) {
                delay(UI_TICK_INTERVAL_MS)
                val current = _state.value as? State.Running ?: break
                val remain = ((current.endsAtMs - nowMsProvider() + 59_999) / 60_000)
                    .toInt().coerceAtLeast(1)
                if (remain != current.remainingMinutes) {
                    _state.value = current.copy(remainingMinutes = remain)
                }
            }
        }
    }

    /**
     * 到期处理（单测公开入口）：仅 Running 态触发一次回调，转 Finished。
     * 非 Running 态调用为 no-op（幂等，不重复触发）。
     */
    fun tickExpired() {
        if (_state.value is State.Running) {
            _state.value = State.Finished
            onExpired()
        }
    }

    /** 取消定时（含 Finished 状态复位） */
    fun cancel() {
        expiryJob?.cancel()
        expiryJob = null
        tickJob?.cancel()
        tickJob = null
        _state.value = State.Off
    }

    /** 是否处于计时中 */
    fun isRunning(): Boolean = _state.value is State.Running
}

/**
 * L11 修复（2026-10-06，代码审查报告 §5）：剩余分钟公式收敛——此前 NowPlayingScreen
 * 三处内联 `(endsAtMs - now + 59_999) / 60_000`，与 [SleepTimerController.remainingMinutes]
 * （可注入时钟版）发生口径漂移。本顶层扩展与 controller 版逐位同义（向上取整），
 * 并补 UI 所需下限 1（显示层不出现「0 分钟」，到期前最后一分钟仍显示 1）。
 * ⚠️ 必须是**文件顶层**扩展（类内成员扩展跨文件不可见，首次实现曾因此编译失败）。
 *
 * 2026-11-22：默认读 [SleepTimerController.State.Running.remainingMinutes]（由 tick 协程
 * 按同一公式每 30 秒刷新），保证「UI 显示」与「状态 emit」同源；仍保留 [nowMs] 参数供
 * 单测/特殊场景按指定时钟精算。
 */
fun SleepTimerController.State.Running.remainingMinutesDisplay(
    nowMs: Long? = null
): Int = if (nowMs == null) {
    remainingMinutes.coerceAtLeast(1)
} else {
    ((endsAtMs - nowMs + 59_999) / 60_000).toInt().coerceAtLeast(1)
}
