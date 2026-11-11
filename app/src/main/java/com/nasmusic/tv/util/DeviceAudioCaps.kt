package com.nasmusic.tv.util

import android.media.MediaCodecList
import android.os.Build

/**
 * 设备**音频解码能力**探测（2026-11-11 电视崩溃取证后新增的一层）
 *
 * ## 为什么需要它
 *
 * 2026-11-11 目标电视（创维 9R54_G8S / Realtek RTD2990 / Android 5.1）实测：
 * 应用在播放 **FLAC** 时反复整进程崩溃（15 分钟内 8 次），崩溃栈在
 * `MediaCodec_loop`（**音频解码线程**），**不是** Java 异常：
 *
 * ```
 * D/RTK_OMX_AUDIO_DEC   : Audio Format audio_decoder.flac
 * E/RTK_OMX_NodeInstance: getParameter(54:RTK.decoder, ParamAudioFlac(0x400001c)) ERROR: Unsupported
 * F/ACodec              : frameworks/av/media/libstagefright/ACodec.cpp:3858
 *                         CHECK_EQ(mOMX->getParameter(mNode, OMX_IndexParamAudioFlac, ...), OK)
 *                         failed: -1010 vs. 0
 * ```
 *
 * ⇒ 电视固件的**厂商通用 OMX 音频解码器宣称支持 FLAC，却不实现 `OMX_IndexParamAudioFlac` 参数**；
 * 而 Android 5.x 框架的 `ACodec::getPortFormat()` 对这个参数是 **`CHECK_EQ` 断言**
 * ⇒ 断言失败即 `SIGABRT`，**整个进程被打掉**（`try/catch` 拦不住 native 崩溃）。
 *
 * ⛔ **与照片墙 / 可视化无关**：崩的是音频解码路径。
 *
 * ## 放在 `util` 的原因
 *
 * 三处都要用它，而 `backend`（音质档位解析）**不能反向依赖 `player`**：
 * - `player`：解封装层熔断 FLAC（见 `player/FlacGuardExtractors.kt`）
 * - `data/prefs`：**无损档降级**（这台机器放不了 FLAC ⇒ 无损档自动退到 320kbps）
 * - `backend/network`：单曲音质覆盖的档位也要一起降
 *
 * ## 判定口径（宁可「不能播」，也不「崩溃」）
 *
 * 只有同时满足两条才判定为「一用就崩」：
 * 1. 框架版本在 `CHECK_EQ` 存在的区间内（`SDK_INT ≤ 22`，即 Android 5.x / 6.0）；
 * 2. 宣称支持 `audio/flac` 的是**厂商通用解码器**（`*.audio.decoder` —— 一个解码器顶多种格式），
 *    而不是**专用 FLAC 解码器**（名字里带 `flac`）。
 */
object DeviceAudioCaps {

    private const val TAG = "DeviceAudioCaps"

    /** FLAC 的 mime */
    private const val MIME_FLAC = "audio/flac"

    /**
     * 框架里 `CHECK_EQ(OMX_IndexParamAudioFlac)` 存在的版本上界（API 22 = Android 5.1）。
     *
     * ⚠️ 依据是 2026-11-11 在 API 22 电视上的实测崩溃栈（`ACodec.cpp:3858`）。
     */
    private const val LAST_UNSAFE_SDK_INT = 22

    /** 进程级缓存（枚举 codec 列表不是免费的，探测只做一次） */
    private val flacUnsafe: Boolean by lazy { detectFlacUnsafe() }

    /**
     * 这台设备的 FLAC 解码器**是否一用就崩**。
     *
     * `true` ⇒ ① 无损档必须降级；② 播放路径必须熔断 FLAC（否则整进程猝死）。
     */
    fun isFlacDecodeUnsafe(): Boolean = flacUnsafe

    // ────────────────────────── 内部实现 ──────────────────────────

    private fun detectFlacUnsafe(): Boolean {
        val risky = detectFlacUnsafe(flacDecoderNames(), Build.VERSION.SDK_INT)
        if (risky) {
            AppLog.w(
                TAG,
                "厂商通用解码器宣称支持 FLAC，且框架版本（SDK ${Build.VERSION.SDK_INT}）存在 " +
                    "ACodec.cpp 的 CHECK_EQ 崩溃 ⇒ 无损档降级 + 播放路径熔断 FLAC（宁可不能播也不崩）",
            )
        }
        return risky
    }

    /**
     * 判定逻辑的**纯函数**版本（便于单测：不用真机也能覆盖各条分支）。
     *
     * @param decoderNames 宣称支持 `audio/flac` 的解码器名
     * @param sdkInt `Build.VERSION.SDK_INT`
     */
    internal fun detectFlacUnsafe(decoderNames: List<String>, sdkInt: Int): Boolean =
        sdkInt <= LAST_UNSAFE_SDK_INT && decoderNames.any { isGenericVendorDecoder(it) }

    /** 枚举支持 `audio/flac` 的**解码器**名（取不到就当作没有） */
    private fun flacDecoderNames(): List<String> = try {
        @Suppress("DEPRECATION")
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info ->
                // ⚠️ `isDecoder()` 在 API 30+ 的 SDK 里已被移除（只剩 `isEncoder()`）⇒ 用取反判定
                !info.isEncoder && info.supportedTypes.any { it.equals(MIME_FLAC, ignoreCase = true) }
            }
            .map { it.name }
    } catch (t: Throwable) {
        AppLog.w(TAG, "枚举 codec 列表失败：$t")
        emptyList()
    }

    /**
     * 是否是「厂商通用音频解码器」—— 一个解码器顶多种格式的那种。
     *
     * 已知命中：`OMX.RTK.audio.decoder`（Realtek 通用音频解码器，本项目目标电视即是）。
     * ⛔ **专用** FLAC 解码器（名字里带 `flac`）不在此列：它通常能正确回那个参数。
     */
    internal fun isGenericVendorDecoder(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.contains("flac")) return false
        return lower.endsWith(".audio.decoder") || lower.endsWith(".audio.decoder.raw")
    }
}
