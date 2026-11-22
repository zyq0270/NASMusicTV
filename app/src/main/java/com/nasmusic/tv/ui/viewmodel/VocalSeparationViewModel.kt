package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.backend.network.mv.MvSearchManager
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.player.PlayerManager
import com.nasmusic.tv.player.SeparationMode
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 人声分离/K 歌域 ViewModel（R-1 拆分自 MainViewModel）：
 * 人声消除开关、分离模式（快速/高质量）、升降调与变速、K 歌页进出。
 *
 * 依赖：PlayerManager（人声分离控制）、AppPreferences（模式持久化）。
 */
/**
 * 人声分离 / K 歌域 ViewModel（R-1 拆分自 MainViewModel）：
 * 人声消除开关、分离模式（快速/高质量）、升降调与变调、±K 歌页进出。
 *
 * 依赖：PlayerManager（人声分离控制）、AppPreferences（模式持久化）。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 🎤 K 歌双路径调度（2026-09-13 标注）
 * ─────────────────────────────────────────────────────────────────────
 * 本 ViewModel 是 K 歌两条路径的总控点：
 *   ① 实时 DSP 路径（默认/兜底，永远启用）
 *      setVocalRemovalEnabled(true) → PlayerManager 注入的 SpectralMaskProcessor
 *   ② 高质量模型路径（按需启用）
 *      isHighQualityMode() 为真时 → enableHighQualityRemoval()
 *      → HqSeparationOrchestrator → HT-Demucs ONNX 推理
 *
 * 关闭时对称处理：setVocalRemovalEnabled(false) + disableHighQualityRemoval()
 *
 * UI 入口：KaraokePlaybackScreen / KaraokeLyricsView
 * 模型管理：ModelDownloadManager / ModelTransferDialog
 * ─────────────────────────────────────────────────────────────────────
 */
class VocalSeparationViewModel(
    app: Application,
    private val playerManager: PlayerManager,
    /**
     * 持久化依赖。默认取 `NasMusicApp` 的进程单例（生产路径一律如此）；
     * 开成构造参数只为单测能注入**本 sandbox 独立实例** —— 单例的 DataStore 文件绑在
     * 第一个创建它的 Robolectric sandbox 上，跨用例复用会让"等落盘"类断言时绿时红
     * （与 `AppPreferences` 的 `internal constructor` 同一隔离口径，见其类注释）。
     */
    private val prefs: AppPreferences = (app as NasMusicApp).appPreferences
) : AndroidViewModel(app) {

    /** 进入 K 歌/MTV 模式时启动遥控服务器的回调（由 MainViewModel 注入，避免跨域直连） */
    var onEnsureRemoteControlStarted: (() -> Unit)? = null

    // --- KARAOKE 伴奏模式（人声消除）---
    private val _vocalRemovalEnabled = MutableStateFlow(false)
    val vocalRemovalEnabled: StateFlow<Boolean> = _vocalRemovalEnabled.asStateFlow()

    fun toggleVocalRemoval() {
        val newValue = !_vocalRemovalEnabled.value

        if (newValue) {
            // 开启人声消除（伴唱模式）
            // 正在转换中，不允许重复触发
            if (playerManager.separating.value) {
                AppLog.w("VocalSeparationViewModel", "toggleVocalRemoval: separating in progress, ignored")
                return
            }
            // 快速模式：实时 DSP（始终启用，作为兜底）
            playerManager.setVocalRemovalEnabled(true)
            // 高质量模式：额外切换到伴奏文件
            if (playerManager.isHighQualityMode()) {
                playerManager.enableHighQualityRemoval()
            }
        } else {
            // 关闭人声消除（原唱模式）
            // 快速模式：关闭实时 DSP
            playerManager.setVocalRemovalEnabled(false)
            // 高质量模式：额外切换回原始文件
            if (playerManager.isHighQualityMode()) {
                playerManager.disableHighQualityRemoval()
            }
        }

        _vocalRemovalEnabled.value = newValue
        AppLog.d("VocalSeparationViewModel", "toggleVocalRemoval -> $newValue (hq=${playerManager.isHighQualityMode()})")
    }

    // --- 分离模式（快速/高质量）---
    /** 当前分离模式（同步 prefs → PlayerManager） */
    val separationMode: StateFlow<SeparationMode> = prefs.player.separationMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SeparationMode.FAST
    ).also { flow ->
        // 启动时将持久化的模式同步到 PlayerManager
        viewModelScope.launch {
            val savedMode = flow.value
            playerManager.setSeparationMode(savedMode)
            AppLog.d("VocalSeparationViewModel", "init: loaded separationMode=$savedMode from prefs")
        }
    }
    /** 高质量分离是否正在进行（委托 PlayerManager 状态） */
    val separating: StateFlow<Boolean> = playerManager.separating
    /** 高质量分离进度与阶段描述（委托 PlayerManager 状态） */
    val separationProgress: StateFlow<Pair<Float, String>> = playerManager.separationProgress
    /** 高质量分离错误信息（非空表示最近一次失败，UI 应提示用户） */
    val hqError: StateFlow<String?> = playerManager.hqError
    /** 高质量分离失败的完整诊断详情（设置页长期留存，供用户截图回传） */
    val hqErrorDetail: StateFlow<String?> = playerManager.hqErrorDetail
    /** 高质量分离成功信息（非空表示最近一次成功，UI 应提示用户） */
    val hqSuccess: StateFlow<String?> = playerManager.hqSuccess

    /** 清除高质量分离错误信息 */
    fun clearHqError() {
        playerManager.clearHqError()
    }

    /** 清除高质量分离成功信息 */
    fun clearHqSuccess() {
        playerManager.clearHqSuccess()
    }

    /** 设置分离模式（从设置页调用） */
    fun setSeparationMode(mode: SeparationMode) {
        applySeparationMode(mode)
    }

    /** 切换分离模式（快速↔高质量），持久化到 AppPreferences */
    fun toggleSeparationMode() {
        val currentMode = separationMode.value
        val newMode = if (currentMode == SeparationMode.FAST) {
            SeparationMode.HIGH_QUALITY
        } else {
            SeparationMode.FAST
        }
        applySeparationMode(newMode)
    }

    /** 删除模型后回退快速模式（由 DownloadViewModel 回调） */
    fun onModelDeleted() {
        if (separationMode.value == SeparationMode.HIGH_QUALITY) {
            applySeparationMode(SeparationMode.FAST)
        }
    }

    /** 统一分离模式切换逻辑 */
    private fun applySeparationMode(newMode: SeparationMode) {
        // 切换到高质量模式前检查模型是否已下载
        if (newMode == SeparationMode.HIGH_QUALITY && !modelDownloaded.value) {
            AppLog.w("VocalSeparationViewModel", "applySeparationMode: model not downloaded, blocked")
            return
        }

        playerManager.setSeparationMode(newMode)
        viewModelScope.launch { prefs.player.setSeparationMode(newMode) }

        if (_vocalRemovalEnabled.value) {
            // K歌模式正在伴唱，切换模式时保持伴唱状态
            if (newMode == SeparationMode.HIGH_QUALITY) {
                // 快速→高质量：关闭 DSP，切换到伴奏文件
                playerManager.setVocalRemovalEnabled(false)
                playerManager.enableHighQualityRemoval()
            } else {
                // 高质量→快速：切换回原始文件，重新开启 DSP
                playerManager.disableHighQualityRemoval()
                playerManager.setVocalRemovalEnabled(true)
            }
        } else {
            // 非K歌/原唱模式，只切换模式标记
            if (newMode == SeparationMode.HIGH_QUALITY) {
                // 不主动触发分离，等用户进入K歌时再分离
            } else {
                playerManager.disableHighQualityRemoval()
            }
        }
        AppLog.d("VocalSeparationViewModel", "applySeparationMode -> $newMode (vocalRemoval=${_vocalRemovalEnabled.value})")
    }

    /** 模型下载状态（镜像自 DownloadViewModel 语义：此处仅做模式切换的门槛判断） */
    private val _modelDownloaded = MutableStateFlow(false)
    val modelDownloaded: StateFlow<Boolean> = _modelDownloaded

    /** 由 MainViewModel 在模型下载状态变化时同步（DownloadViewModel 拥有事实源） */
    fun setModelDownloaded(downloaded: Boolean) {
        _modelDownloaded.value = downloaded
    }

    // --- K 歌页面：升降调 & 变速（仅会话内生效，退出复位，不记忆）---

    private val _pitchSemitones = MutableStateFlow(0)
    val pitchSemitones: StateFlow<Int> = _pitchSemitones.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0)
    val playbackSpeed: StateFlow<Double> = _playbackSpeed.asStateFlow()

    /**
     * 预览升降调（半音 -12~+12）：只改播放器与页面状态，**不写 DataStore**。
     * K 歌步进弹窗换档时实时试听用（方案 §3-A3 / 决策 D2）；[commitPitch] 也只做确认。
     */
    fun previewPitch(semitones: Int) {
        val clamped = semitones.coerceIn(-12, 12)
        _pitchSemitones.value = clamped
        playerManager.setPitch(clamped)
        AppLog.d("VocalSeparationViewModel", "previewPitch -> $clamped")
    }

    /** 确认当前升降调值（弹窗「确定」）：会话内已生效，**不写 DataStore**，退出 K 歌页复位 */
    fun commitPitch() {
        val value = _pitchSemitones.value
        AppLog.d("VocalSeparationViewModel", "commitPitch -> $value (session only)")
    }

    /**
     * 预览播放速度（0.5~2.0）：只改播放器与页面状态，**不写 DataStore**。
     */
    fun previewSpeed(speed: Double) {
        val clamped = speed.coerceIn(0.5, 2.0)
        _playbackSpeed.value = clamped
        playerManager.setSpeed(clamped.toFloat())
        AppLog.d("VocalSeparationViewModel", "previewSpeed -> $clamped")
    }

    /** 确认当前播放速度（弹窗「确定」）：会话内已生效，**不写 DataStore**，退出 K 歌页复位 */
    fun commitSpeed() {
        val value = _playbackSpeed.value
        AppLog.d("VocalSeparationViewModel", "commitSpeed -> $value (session only)")
    }

    /** 重置升降调到原调（只改播放器与页面状态，不写 DataStore） */
    fun resetPitch() {
        _pitchSemitones.value = 0
        playerManager.resetPitch()
        AppLog.d("VocalSeparationViewModel", "resetPitch -> 0")
    }

    /** 重置播放速度到原速（只改播放器与页面状态，不写 DataStore） */
    fun resetSpeed() {
        _playbackSpeed.value = 1.0
        playerManager.resetSpeed()
        AppLog.d("VocalSeparationViewModel", "resetSpeed -> 1.0")
    }

    // --- K 歌页面状态（切 Tab 时保持，退出 K 歌页时清除） ---
    private val _showKaraoke = MutableStateFlow(false)
    val showKaraoke: StateFlow<Boolean> = _showKaraoke.asStateFlow()

    /** 进入 K 歌页面：开启人声消除 + 启动遥控服务器 */
    fun enterKaraoke() {
        _showKaraoke.value = true
        if (!_vocalRemovalEnabled.value) {
            toggleVocalRemoval()
        }
        onEnsureRemoteControlStarted?.invoke()
        AppLog.d("VocalSeparationViewModel", "enterKaraoke")
    }

    /**
     * 退出 K 歌页面：关闭人声消除、恢复原唱，并把升降调/播放速度复位为原调原速；
     * 不改变播放队列或当前歌曲。
     *
     * 升降调/变速是**会话内临时效果**：preview/commit 只改播放器与 VM 状态、不写持久化；
     * 冷启动也强制 0/1.0。因此退出必须无条件复位，否则用户退出 K 歌页后仍带着调/速，
     * 与「退出即恢复原调原速」的语义相悖。
     */
    fun exitKaraoke() {
        _showKaraoke.value = false
        // 关闭人声消除（恢复原唱）
        if (_vocalRemovalEnabled.value) {
            toggleVocalRemoval()
        }
        // 恢复原调原速（无条件：VM 状态与播放器可能因外部路径失步）
        resetPitch()
        resetSpeed()
        AppLog.d("VocalSeparationViewModel", "exitKaraoke: vocalRemoval, pitch, speed restored")
    }

    /** 清除全部分离产物与临时残留（设置页"缓存清理"手动清除用），返回释放的字节数 */
    fun clearAllSeparationArtifacts(): Long {
        return playerManager.clearAllSeparationArtifacts()
    }
}
