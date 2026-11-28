package com.nasmusic.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 跨曲交叉淡入淡出控制器（F2-5）——双实例方案。
 *
 * 切歌窗口：主 player 旧歌淡出、crossfadePlayer 新歌淡入（50ms 步进**等功率**斜坡），
 * 窗口结束回调 onCrossfadeComplete → PlayerManager.transitionToIndex 完成切歌。
 *
 * 音量曲线（P1#3 修复，2026-09-14）：由线性斜坡改为**等功率（constant-power）**——
 * 线性斜坡下两路幅度和为 1（`out=1-frac`、`in=frac`），但人耳感知的是功率（幅度²），
 * 中段（frac≈0.5）总功率仅 `0.5²+0.5²=0.5`，听感上表现为 crossfade 中途**音量下陷**。
 * 现改为 `out=cos(frac·π/2)`、`in=sin(frac·π/2)`，两者平方和恒为 1，功率全程恒定；
 * 端点仍精确为 (1,0) → (0,1)，不改变淡入淡出的起止语义。
 *
 * 边界条件（计划 §5.3）：
 * - enabled=false / K歌 MTV 模式（suppressPlayback）/ REPEAT_ONE / 队列仅 1 首 → 不触发
 * - 下一首 streamUrl 为空（网络歌曲懒加载）→ 放弃 crossfade 走普通切换
 * - 手动切歌（next/previous）→ 立即中断进行中的 crossfade，资源彻底释放
 *
 * 音频焦点：crossfadePlayer setAudioAttributes(USAGE_MEDIA, handleAudioFocus=false)——
 * 焦点仍由主 player 独占持有。
 *
 * 应用内音量（F2-7）：所有音量写入经 [volume] 按 appVolume（PlayerManager.appVolume，
 * 0.0–1.0）等比缩放——等功率曲线形状不变，整体增益随 appVolume 缩放。
 */
class CrossfadeController(
    private val context: Context,
    private val mainPlayerProvider: () -> ExoPlayer?,
    private val appVolumeProvider: () -> Float,
    private val onCrossfadeComplete: (Int) -> Unit,
    private val volumeWriter: ((player: ExoPlayer, appVolume: Float, songKey: String?, fadeFactor: Float) -> Unit)? = null,
    private val currentSongKeyProvider: () -> String? = { null },
    private val nextSongKeyProvider: (Int) -> String? = { null },
    private val queueIdentityProvider: () -> Any = { Unit },
    private val crossfadePlayerFactory: (() -> ExoPlayer)? = null,
) {
    data class PlaybackIdentity(
        val queue: List<Song>,
        val currentIndex: Int,
        val playMode: PlayMode,
        val playWhenReady: Boolean,
        val isPlaying: Boolean,
    )

    sealed interface State {
        data object Off : State
        data object Preparing : State
        /** 淡入淡出进行中（剩余毫秒） */
        data class Fading(val remainingMs: Long) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state

    private var crossfadePlayer: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var fadeRunnable: Runnable? = null
    private var readinessTimeout: Runnable? = null
    private var activeNextIndex: Int = -1
    private var activeQueueIdentity: Any? = null

    /** 兼容旧构造面的音量出口；正式注入时由 PlayerManager 按两曲 key 分发增益。 */
    private val fallbackVolumeWriter by lazy { ReplayGainController() }
    private val writePlayerVolume: (ExoPlayer, Float, String?, Float) -> Unit =
        volumeWriter ?: { p, v, key, fade -> fallbackVolumeWriter.writeVolume(p, v, key, fade) }
    private var fadingNextKey: String? = null

    /** 应用内音量缩放：f × appVolume（clamped 0–1），所有音量写入统一走这里 */
    private fun volume(f: Float) = f * appVolumeProvider().coerceIn(0f, 1f)

    private fun writeMain(f: Float) {
        mainPlayerProvider()?.let { writePlayerVolume(it, appVolumeProvider(), currentSongKeyProvider(), f) }
    }

    private fun writeNext(p: ExoPlayer, f: Float) {
        writePlayerVolume(p, appVolumeProvider(), fadingNextKey, f)
    }

    /**
     * 尝试启动 crossfade（进度轮询钩子调用）。
     * 全部前置条件满足才启动，否则静默跳过（调用方走普通切歌路径）。
     *
     * @param enabled 设置开关
     * @param durationSec 时长（秒），<=0 视为关闭
     * @param suppressPlayback K歌/MTV 模式标志（true 时禁用）
     * @param repeatOne 单曲循环（同曲重叠无意义）
     * @param queueSize 队列长度
     * @param currentIndex 当前索引
     * @param nextMediaItemFactory 下一首 MediaItem 构造（streamUrl 校验通过后调用）
     */
    fun maybeStartCrossfade(
        enabled: Boolean,
        durationSec: Int,
        suppressPlayback: Boolean,
        repeatOne: Boolean,
        queueSize: Int,
        currentIndex: Int,
        nextMediaItemFactory: (nextIndex: Int) -> androidx.media3.common.MediaItem?
    ): Boolean {
        if (!enabled || durationSec <= 0) return false
        if (suppressPlayback || repeatOne) return false
        if (queueSize <= 1) return false
        if (_state.value != State.Off) return false
        val nextIndex = currentIndex + 1
        if (nextIndex >= queueSize) return false
        if (activeNextIndex == nextIndex) return false

        val main = mainPlayerProvider() ?: return false
        val nextItem = nextMediaItemFactory(nextIndex) ?: return false
        if (nextItem.localConfiguration?.uri.toString().isNullOrBlank()) return false
        val identity = queueIdentityProvider()
        val durationMs = durationSec * 1000L

        activeNextIndex = nextIndex
        activeQueueIdentity = identity
        fadingNextKey = nextSongKeyProvider(nextIndex)
        _state.value = State.Preparing
        try {
            val cfPlayer = (crossfadePlayerFactory ?: ::createCrossfadePlayer).invoke()
            crossfadePlayer = cfPlayer
            cfPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (crossfadePlayer !== cfPlayer) return
                    when (playbackState) {
                        Player.STATE_READY -> beginFade(cfPlayer, durationMs)
                        Player.STATE_IDLE, Player.STATE_ENDED -> abort()
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    if (crossfadePlayer === cfPlayer) abort()
                }
            })
            cfPlayer.setMediaItem(nextItem)
            writeNext(cfPlayer, 0f)
            cfPlayer.prepare()
            cfPlayer.play()

            if (cfPlayer.playbackState == Player.STATE_READY) {
                beginFade(cfPlayer, durationMs)
            } else {
                val timeout = Runnable {
                    if (crossfadePlayer === cfPlayer && _state.value == State.Preparing) {
                        AppLog.w(TAG, "crossfade next track not ready before timeout")
                        abort()
                    }
                }
                readinessTimeout = timeout
                handler.postDelayed(timeout, READY_TIMEOUT_MS)
            }
            AppLog.d(TAG, "crossfade preparing: index=$currentIndex -> $nextIndex, ${durationSec}s")
            return true
        } catch (e: Exception) {
            AppLog.e(TAG, "crossfade start failed", e)
            abort()
            return false
        }
    }

    private fun beginFade(cfPlayer: ExoPlayer, durationMs: Long) {
        if (crossfadePlayer !== cfPlayer || _state.value != State.Preparing) return
        if (!isCurrentIdentity()) {
            abort()
            return
        }
        readinessTimeout?.let { handler.removeCallbacks(it) }
        readinessTimeout = null
        writeMain(1f)
        val totalSteps = (durationMs / STEP_MS).toInt().coerceAtLeast(1)
        val volumeStep = 1f / totalSteps
        var step = 0

        val runnable = object : Runnable {
            override fun run() {
                if (!isCurrentIdentity() || crossfadePlayer !== cfPlayer) {
                    abort()
                    return
                }
                step++
                val frac = (step * volumeStep).coerceAtMost(1f)
                try {
                    writeMain(kotlin.math.cos(frac * HALF_PI))
                    writeNext(cfPlayer, kotlin.math.sin(frac * HALF_PI))
                } catch (e: Exception) {
                    AppLog.w(TAG, "fade step failed: ${e.message}")
                    abort()
                    return
                }
                _state.value = State.Fading((durationMs - step * STEP_MS).coerceAtLeast(0L))
                if (step >= totalSteps) {
                    complete()
                } else {
                    handler.postDelayed(this, STEP_MS)
                }
            }
        }
        fadeRunnable = runnable
        handler.postDelayed(runnable, STEP_MS)
        AppLog.d(TAG, "crossfade started: index=${activeNextIndex - 1} -> $activeNextIndex, ${durationMs}ms")
    }

    private fun isCurrentIdentity(): Boolean =
        queueIdentityProvider() == activeQueueIdentity

    /** 窗口结束：主播放器切到新歌（transitionToIndex）并释放 crossfade 资源 */
    private fun complete() {
        if (!isCurrentIdentity()) {
            abort()
            return
        }
        val nextIndex = activeNextIndex
        cleanupPlayer(restoreMainVolume = false) // 主 player 音量由 transitionToIndex 后恢复
        _state.value = State.Off
        activeNextIndex = -1
        activeQueueIdentity = null
        fadingNextKey = null
        if (nextIndex >= 0) {
            onCrossfadeComplete(nextIndex)
        }
        // 恢复主播放器音量（切歌完成后，按提交后的新 songKey）
        writeMain(1f)
    }

    /** 立即中断（手动切歌/出错）：释放资源，不切歌 */
    fun abort() {
        cleanupPlayer(restoreMainVolume = true)
        _state.value = State.Off
        activeNextIndex = -1
        activeQueueIdentity = null
        fadingNextKey = null
    }

    private fun cleanupPlayer(restoreMainVolume: Boolean) {
        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable = null
        readinessTimeout?.let { handler.removeCallbacks(it) }
        readinessTimeout = null
        crossfadePlayer?.let { p ->
            try {
                p.stop()
                p.release()
            } catch (_: Exception) {}
        }
        crossfadePlayer = null
        if (restoreMainVolume) {
            try { writeMain(1f) } catch (_: Exception) {}
        }
    }

    // Media3 的 UnstableApi 走 androidx 的 @RequiresOptIn 机制，必须用 androidx.annotation.OptIn
    // （kotlin.OptIn 对它无效，且自身会被 lint 标记）。
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun createCrossfadePlayer(): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        // ⛔ 2026-11-11 电视崩溃修正：副播放器**同样**要熔断 FLAC
        //（老电视的厂商 OMX 一 configure FLAC 解码器就触发框架断言 ⇒ 整进程猝死）。
        // ⇒ 与主播放器用同一套解封装熔断（见 DeviceAudioCaps）。
        val extractors = FlacGuard.extractorsFactory(
            androidx.media3.extractor.DefaultExtractorsFactory()
        )
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context, extractors)
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, false) // 焦点由主 player 独占
            .build()
    }

    /** 是否正在准备或淡化 crossfade */
    fun isFading(): Boolean = _state.value != State.Off

    companion object {
        private const val TAG = "Crossfade"
        const val STEP_MS = 50L
        private const val READY_TIMEOUT_MS = 5_000L

        /** P1#3：等功率曲线的半周期（π/2），供 sin/cos 增益计算复用 */
        private val HALF_PI = (Math.PI / 2.0).toFloat()
    }
}
