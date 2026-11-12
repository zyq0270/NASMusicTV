package com.nasmusic.tv.visualizer.photo

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.geometry.Size
import com.nasmusic.tv.backend.photo.PhotoIds
import com.nasmusic.tv.backend.photo.PhotoRef
import com.nasmusic.tv.backend.photo.PhotoSource
import com.nasmusic.tv.backend.photo.PhotoSourceKind
import com.nasmusic.tv.backend.photo.PhotoSourceStatus
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRandom
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * **门禁：照片墙的时钟必须走「墙钟」，不能走 `frame.timeMs`**（§10.393）
 *
 * ## 它防的是什么（2026-11-12 电视实测）
 *
 * 电视实测：NAS 照片已经能取到、能渲染了（`Jellyfin 20000` 张，照片墙上墙），
 * 但**照片停住不动** —— 连拍 5 张截图（间隔 14 秒，共 56 秒）全是同一张，
 * 而设置里的「停留时长」是 **8.0 秒**。
 *
 * ⇒ 根因：`frame.timeMs` 是**音频帧**里的时间戳，只在**音频回调真的跑**时更新。
 * 音乐**暂停**时音频回调不跑 ⇒ `frame.timeMs` 冻结 ⇒ 照片墙的 HOLD 永远走不完
 * ⇒ **整面墙停在第一张**。一放歌，音频回调恢复 ⇒ 立刻开始换图（实测有效）。
 *
 * ⇒ 这就是用户第一轮报的「**照片没出来全就停着**」的真正机制。
 *
 * ## 本门禁怎么钉
 *
 * 喂**恒定不变**的 `frame.timeMs`（= 「没有声音在放」）+ **正常推进**的墙钟，
 * 断言照片墙**照样换图**。
 * ⇒ 谁再把时钟接回 `frame.timeMs`，本用例立刻红。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoWallClockSourceTest {

    /** 假墙钟：单测自己推进，与真实时间无关 */
    private class FakeClock(var now: Long = 0L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    private val ctx = RenderContext().apply { canvasSize = Size(1920f, 1080f) }

    @Test
    fun `音频帧时间冻结时照片墙照样换图`() = runTest {
        val clock = FakeClock()
        val names = listOf("a", "b", "c")
        val c = controller(this, clock, names)

        c.onSettingsChanged(settings(), VisualQuality.HIGH, SDK)
        c.onThemeEntered()
        advanceUntilIdle()

        // ⚠️ 必须先 `applyTo` 暂存画布尺寸 ⇒ 下一帧 `reconcileBuffer()` 才建得出缓冲
        //    （缓冲没建 ⇒ `tryStartSwap` 拿不到位图 ⇒ 第一张永远出不来）
        c.applyTo(ctx)

        // 先出第一张（此时音频帧时间仍是 0）
        pump(c, clock, frames = 4)
        c.applyTo(ctx)
        assertNotNull("第一张应当出现", ctx.photoA)

        // ⚠️ 关键：**frame.timeMs 恒为 0**（模拟「没有声音在放」⇒ 音频回调不跑），
        //    只有墙钟在推进。
        //    停留 1 秒 + 转场 0.7 秒 ⇒ 推 4.8 秒足够换一张。
        pump(c, clock, frames = 300)
        c.applyTo(ctx)

        // 换图后：`photoB` = 正在入场的**下一张**，必须与 `photoA` 不是同一张。
        // ⛔ 时钟冻结时 `clock.finished` 永远为 false ⇒ `photoB` 永远是 null ⇒ 本断言红。
        // ⚠️ 不能拿「第一张的宽度」当基准 —— 池是**打乱**的（`PhotoSourceAggregator.shuffled`），
        //    第一张未必是列表里的第一个。
        assertNotNull("墙钟推进后必须换下一张（photoB = 正在入场的那张）", ctx.photoB)
        assertNotEquals(
            "下一张必须与当前这张不是同一张",
            ctx.photoA!!.width,
            ctx.photoB!!.width,
        )
    }

    // ══════════════════════════ 装配 ══════════════════════════

    /**
     * ⚠️ 必须把 `runTest` 的 `TestScope` 传进来：`this` 在成员函数里是测试类，不是作用域。
     */
    private fun controller(
        scope: kotlinx.coroutines.CoroutineScope,
        clock: FakeClock,
        names: List<String>,
    ): PhotoWallController {
        val sources = mapOf<PhotoSourceKind, PhotoSource>(PhotoSourceKind.EXTERNAL to FakeSource(names))
        return PhotoWallController(
            sources = sources,
            random = VisualizerRandom(1u),
            externalScope = scope,
            nowMsProvider = { clock.now },
            bufferFactory = { w, h, rgb ->
                PhotoBuffer(
                    sourceProvider = { sources[it] },
                    targetWidth = w,
                    targetHeight = h,
                    allowRgb565 = rgb,
                    decoderExecutor = DirectExecutor,
                    // 假解码器把「照片身份」编进位图宽度 ⇒ 换图 = 宽度变了
                    decoder = { ref, _ ->
                        Bitmap.createBitmap(
                            names.indexOf(ref.id.substringAfter(':')) + 1,
                            1,
                            Bitmap.Config.ARGB_8888,
                        )
                    },
                )
            },
        )
    }

    /**
     * 推进若干帧。
     *
     * ⚠️ `frame.timeMs` **恒为 0** —— 这正是本测试要模拟的场景。
     */
    private fun pump(c: PhotoWallController, clock: FakeClock, frames: Int) {
        repeat(frames) {
            clock.advance(FRAME_MS)
            val f = AudioFrame(4, 8)
            f.timeMs = FROZEN_FRAME_MS
            c.onFrame(f)
            idleLooper()
        }
    }

    private fun settings(): AppSettings = AppSettings(
        photoWallExternalEnabled = true,
        // 停留 1 秒（真实默认是 8 秒）⇒ 单测不用推太多帧
        photoWallHoldMs = 1_000,
        photoWallTransitionMs = 700,
        photoWallRandomTransition = true,
    )

    private fun idleLooper() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    /** 假来源：`status()` 恒 OK，`listPhotos()` 返回构造时给的那批 */
    private class FakeSource(private val names: List<String>) : PhotoSource {
        override val kind: PhotoSourceKind = PhotoSourceKind.EXTERNAL
        override suspend fun status(): PhotoSourceStatus = PhotoSourceStatus.OK
        override suspend fun listPhotos(): List<PhotoRef> = names.map { name ->
            PhotoRef(
                id = PhotoIds.of(PhotoSourceKind.EXTERNAL, name),
                displayName = "$name.jpg",
                width = 1920,
                height = 1080,
                size = 1_024L,
                lastModified = 0L,
                dateAdded = 0L,
                source = PhotoSourceKind.EXTERNAL,
            )
        }

        override suspend fun openStream(ref: PhotoRef): InputStream? =
            ByteArrayInputStream(byteArrayOf(1))
    }

    /** 同步执行器：把「后台解码」变成调用即完成，避免单测依赖线程调度 */
    private object DirectExecutor : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = true
        override fun isTerminated(): Boolean = true
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private companion object {
        const val SDK = 34
        const val FRAME_MS = 16L

        /** 恒定不变的「音频帧时间」—— 模拟没有声音在放 */
        const val FROZEN_FRAME_MS = 0L
    }
}
