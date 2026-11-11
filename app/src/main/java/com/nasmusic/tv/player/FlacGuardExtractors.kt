package com.nasmusic.tv.player

import android.net.Uri
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.DeviceAudioCaps
import java.io.IOException

/**
 * FLAC **解封装层熔断**（2026-11-11 电视崩溃修正）
 *
 * ## 要挡的是什么
 *
 * 部分老电视（创维 9R54_G8S / Realtek RTD2990 / Android 5.1）的厂商 OMX 音频解码器
 * **宣称支持 FLAC，却不实现 `OMX_IndexParamAudioFlac` 参数**；而 Android 5.x 框架的
 * `ACodec::getPortFormat()` 对这个参数是 `CHECK_EQ` 断言 ⇒
 * **一 configure FLAC 解码器就 `SIGABRT`，整个进程被打掉**（Java 层拦不住）。
 *
 * ⇒ 实测：15 分钟内 8 次崩溃，**全部**是这一条断言（`ACodec.cpp:3858`）。
 *
 * ## 为什么熔断在「解封装层」而不是「codec 选择器」
 *
 * | 路线 | 结论 |
 * |---|---|
 * | `MediaCodecSelector` 屏蔽 `audio/flac` | ❌ **做不到**：这台机器的 FLAC 解码器是**通用解码器**（`OMX.RTK.audio.decoder`，同时顶 mp3/aac/flac）。Media3 的选择器接口**看不到请求的格式**，屏蔽它会连 mp3/aac 一起废掉 |
 * | **解封装层熔断**（本实现） | ✅ 格式一旦被解出来就抛 `IOException` ⇒ 解码器根本不会被 configure ⇒ Media3 抛**可捕获**的 `PlaybackException` ⇒ 应用「提示 + 跳歌」 |
 *
 * ## 为什么拦「格式」而不是「解封装器类型」
 *
 * Media3 既可能按 URI 后缀直接选 `FlacExtractor`，也可能先给一个「嗅探型」解封装器
 * ⇒ 由它在读内容时才发现是 FLAC。**两种路径最终都会写 track 格式** ⇒
 * 统一在 [FlacGuardTrackOutput.format] 处拦（那是格式唯一被「交出去」的地方）。
 *
 * ## 为什么不走「软件 FLAC 解码」
 *
 * Media3 1.2.1 **不含** FLAC 软件解码器（`media3-exoplayer` 的 classes.jar 里 `flac` 命中 0），
 * 要用得引入 `media3-decoder-flac` 并自行接渲染器 ⇒ 在这台 2GB 老电视上，
 * 纯 Java 解码的 CPU 占用与本项目「人声消除 / PCM 频谱」链路叠加后风险更高。
 *
 * ## ⛔ 必须用在**两个**播放器上
 *
 * 主播放器（`PlaybackService`）+ 交叉淡入的副播放器（`CrossfadeController`）——
 * 否则副播放器先解出 FLAC，一样会把进程带走。
 */
// Media3 的 UnstableApi 走 androidx 的 @RequiresOptIn 机制，必须用 androidx.annotation.OptIn
// （kotlin.OptIn 对它无效，且自身会被 lint 标记）—— 与本包既有写法一致。
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object FlacGuard {
    /**
     * 给 [androidx.media3.exoplayer.source.DefaultMediaSourceFactory] 用的解封装工厂。
     *
     * 非「FLAC 一用就崩」的设备上**原样返回**（零开销、零行为变化）。
     */
    fun extractorsFactory(delegate: ExtractorsFactory): ExtractorsFactory {
        if (!DeviceAudioCaps.isFlacDecodeUnsafe()) return delegate
        return FlacGuardExtractorsFactory(delegate)
    }
}

/**
 * FLAC 不可用时抛出的异常（**可捕获** ⇒ 应用能「提示 + 跳歌」，而不是整进程猝死）。
 */
internal class FlacUnsupportedException : IOException(
    "本机音频解码器不支持 FLAC（该设备的 OMX 实现会在配置 FLAC 解码器时触发框架断言）"
)

/**
 * 判断一个播放错误**是不是**「本机不支持 FLAC」熔断造成的。
 *
 * ⚠️ Media3 会把底层的 `IOException` 包进 `PlaybackException` ⇒ 要沿 **cause 链**找。
 */
internal fun PlaybackException.isFlacUnsupported(): Boolean {
    var cause: Throwable? = this.cause
    var depth = 0
    while (cause != null && depth < MAX_CAUSE_DEPTH) {
        if (cause is FlacUnsupportedException) return true
        cause = cause.cause
        depth++
    }
    return false
}

/** cause 链的最大深度（防御异常自引用导致的死循环） */
private const val MAX_CAUSE_DEPTH = 8

/**
 * 解封装工厂：把解封装器一律换成会熔断 FLAC 的包装器。
 *
 * ⚠️ 熔断发生在**解封装**阶段 ⇒ 解码器永远不会被 `configure` ⇒ 不会触发框架断言。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class FlacGuardExtractorsFactory(
    private val delegate: ExtractorsFactory,
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map { FlacGuardExtractor(it) }.toTypedArray()
}

/**
 * 解封装器包装：把 [ExtractorOutput] 也包一层 ⇒ 格式被写出来时就能拦。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class FlacGuardExtractor(
    private val delegate: Extractor,
) : Extractor {

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        delegate.init(FlacGuardOutput(output))
    }

    override fun read(input: ExtractorInput, positionHolder: PositionHolder): Int =
        delegate.read(input, positionHolder)

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()
}

/** 输出包装：把每条 track 的输出也包一层（格式就在这里被交出去） */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class FlacGuardOutput(
    private val delegate: ExtractorOutput,
) : ExtractorOutput {

    override fun track(index: Int, type: Int): TrackOutput =
        FlacGuardTrackOutput(delegate.track(index, type))

    override fun endTracks() = delegate.endTracks()

    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

/**
 * track 输出包装：**格式是 FLAC 就抛异常**。
 *
 * ⚠️ `TrackOutput.format(...)` 在 Java 侧**不声明** `throws IOException` ——
 * 但 Kotlin 没有受检异常 ⇒ 这里照抛即可；异常会沿 `Extractor.read(...)`（**声明了**
 * `throws IOException`）向上冒泡 ⇒ Media3 把它当成「源解析失败」⇒ 可捕获 ✓。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class FlacGuardTrackOutput(
    private val delegate: TrackOutput,
) : TrackOutput {

    override fun format(format: Format) {
        if (format.sampleMimeType.equals(MimeTypes.AUDIO_FLAC, ignoreCase = true)) {
            AppLog.w("FlacGuard", "解出 FLAC 格式 ⇒ 熔断（这台机器的 FLAC 解码器会触发框架断言）")
            throw FlacUnsupportedException()
        }
        delegate.format(format)
    }

    override fun sampleData(
        dataReader: DataReader,
        sampleFlags: Int,
        sampleOnly: Boolean,
    ): Int = delegate.sampleData(dataReader, sampleFlags, sampleOnly)

    override fun sampleData(
        dataReader: DataReader,
        sampleFlags: Int,
        sampleOnly: Boolean,
        bytesRemaining: Int,
    ): Int = delegate.sampleData(dataReader, sampleFlags, sampleOnly, bytesRemaining)

    override fun sampleData(
        byteArray: ParsableByteArray,
        sampleFlags: Int,
    ) = delegate.sampleData(byteArray, sampleFlags)

    override fun sampleData(
        byteArray: ParsableByteArray,
        sampleFlags: Int,
        size: Int,
    ) = delegate.sampleData(byteArray, sampleFlags, size)

    override fun sampleMetadata(
        timeUs: Long,
        sampleFlags: Int,
        sampleDataSize: Int,
        sampleDataOffset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) = delegate.sampleMetadata(timeUs, sampleFlags, sampleDataSize, sampleDataOffset, cryptoData)
}
