package com.nasmusic.tv.visualizer.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import com.nasmusic.tv.backend.photo.PhotoCaptureDate
import com.nasmusic.tv.backend.photo.PhotoRef
import com.nasmusic.tv.backend.photo.PhotoSource
import com.nasmusic.tv.backend.photo.PhotoSourceKind
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 照片解码缓冲（§14.2.2）
 *
 * ## 线程模型（⚠️ 读这段能省掉一次崩溃排查）
 *
 * | 角色 | 线程 | 干什么 |
 * |---|---|---|
 * | `peek` / `request` / `prefetch` / `invalidateSource` / `clear` / `close` | **主线程** | 读写缓存（缓存**没有任何同步原语**，靠「只在主线程访问」保证） |
 * | 解码 | 单线程 `Executor` | `openStream` + `BitmapFactory`（**串行**，避免多线程争抢内存峰值） |
 * | 解码结果回写 | **主线程** `Handler` | 写入缓存 ⇒ `peek()` 与 `draw()` 同线程，**无可见性问题** |
 *
 * ⇒ 调用方（`PhotoWallController`）必须**在主线程**调用本类全部方法。
 *
 * ## 为什么 `peek` 绝不阻塞
 *
 * `peek()` 在**绘制路径**上（每帧经 `PhotoWallController.applyTo` 调用）。
 * 未就绪一律返回 `null`（画面回落到暗底），**绝不等待解码** —— 等待 = 掉帧。
 * 调用方负责「未命中时 `request`」，下一次 `peek` 就有了。
 *
 * ## 内存预算
 *
 * `maxCached` 是**缓存总容量**（默认 3 = 双缓冲 2 张 + LRU 1 张）。
 * 1080p ARGB_8888 单张 ≈ 8.3 MB ⇒ 3 张 ≈ 24 MB（`Tier.BASIC` 走 `RGB_565` ≈ 12 MB）。
 * ⚠️ 调用方须保证**同时活跃的 `peek` 目标数 ≤ maxCached − 1**（预取占 1 张），
 * 否则活跃位图可能被 LRU 淘汰 —— 此时 [peek] 会返回 `null`（不崩，只是画面短暂回落）。
 *
 * ## ⛔ `close()` 必须 `recycle()`
 *
 * API 22 上 `ImageBitmap` 包装的 `Bitmap` **不会**自动回收（不像 API 29+ 有 NativeAllocationRegistry）。
 * 反复进出照片墙而不 recycle ⇒ 位图内存持续增长。
 */
class PhotoBuffer(
    /** 来源查找：`null` = 该来源未启用 / 不存在 */
    private val sourceProvider: (PhotoSourceKind) -> PhotoSource?,
    /** 解码目标宽（CROP 时取 `max(屏宽, 屏高)`） */
    private val targetWidth: Int,
    /** 解码目标高 */
    private val targetHeight: Int,
    /** 缓存**总容量**（含双缓冲 2 张） */
    private val maxCached: Int = DEFAULT_MAX_CACHED,
    /** `Tier.BASIC` 档降级：用 `RGB_565`（省一半内存，渐变会有色带） */
    private val allowRgb565: Boolean = false,
    /** 解码结果回写用的 Handler；`null` = 用主 Looper（单测可注入以精确控制时机） */
    private val mainHandler: Handler? = null,
    /** 解码线程；`null` = 新建单线程池（单测可注入以同步执行） */
    private val decoderExecutor: ExecutorService? = null,
    /**
     * 解码函数（默认走 `BitmapFactory`）。
     *
     * ⚠️ 注入点只为单测存在：让「缓存命中 / LRU 淘汰 / `close()` 释放」这些**与像素无关**的
     * 逻辑能在假解码器下验证（Robolectric 的 `BitmapFactory` 不真解码，拿不到确定尺寸）。
     * 采样率计算本身由 `PhotoBufferMath` 单独覆盖。
     */
    private val decoder: ((ref: PhotoRef, bytes: ByteArray) -> Bitmap?)? = null,
    /**
     * 「拍摄时间」读取函数（默认走 `ExifInterface`，见 [readExifCapturedAt]）。
     *
     * ⚠️ 同样只为单测存在：单测用的假字节没有 EXIF 段，也不该真去解一次 EXIF。
     */
    private val exifReader: ((ref: PhotoRef, bytes: ByteArray) -> Long)? = null,
) {

    /** 缓存条目：同时持有 `Bitmap`（供 `recycle`）与 `ImageBitmap`（供 draw 路径**零分配**读取） */
    private class Entry(
        val bitmap: Bitmap,
        val image: ImageBitmap,
        /** 解码期定下的拍摄时间（epoch 秒，见 [PhotoRef.capturedAt]）；`0` = 未知 */
        val capturedAt: Long,
    ) {
        val bytes: Long get() = bitmap.byteCount.toLong()
    }

    private val handler: Handler = mainHandler ?: Handler(Looper.getMainLooper())

    private val executor: ExecutorService =
        decoderExecutor ?: Executors.newSingleThreadExecutor { r ->
            Thread(r, "PhotoBuffer-decode")
        }

    /**
     * LRU 缓存（`accessOrder = true`）。
     *
     * ⚠️ 只被**主线程**访问 ⇒ 不加锁。
     * 淘汰时立即 `recycle()`（被淘汰的必然是「非活跃」项，见类文档的内存预算约定）。
     */
    private val cache = object : LinkedHashMap<String, Entry>(INITIAL_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean {
            if (size <= maxCached) return false
            eldest.value.bitmap.recycle()
            return true
        }
    }

    /** 已排队 / 正在解码的 id（幂等去重）—— 只被主线程访问 */
    private val pending = HashSet<String>()

    /**
     * 世代号：`clear()` / `invalidateSource()` 时自增。
     *
     * 用途：丢弃「解码启动时那一代」的在途结果 —— 否则拔盘后，已经发出的解码任务完成时
     * 会把一张**已失效**的照片写回缓存（用户看到「拔了盘还在显示」）。
     * ⚠️ 代价：`invalidateSource` 也会顺带丢弃**其他来源**的在途结果（下次 request 会重来一次）。
     * 拔盘是低频事件，用「全局世代」换取实现简单，值。
     */
    private val generation = AtomicInteger(0)

    private val closed = AtomicBoolean(false)

    // ────────────────────────── 查询 ──────────────────────────

    /**
     * 取**已解码**的位图；未就绪返回 `null`（**绝不阻塞、不排队**）。
     *
     * ⚠️ 命中会刷新 LRU 顺序。已 `recycle` 的条目（理论上不该出现，见类文档）会被剔除并返回 `null`
     * —— 宁可画面回落，也不能把已回收的位图交给 Skia。
     */
    fun peek(ref: PhotoRef): ImageBitmap? {
        if (closed.get()) return null
        val entry = cache[ref.id] ?: return null
        if (entry.bitmap.isRecycled) {
            cache.remove(ref.id)
            return null
        }
        return entry.image
    }

    /** 已缓存张数（观察 / 单测用） */
    val cachedCount: Int get() = cache.size

    /**
     * 某张照片的**拍摄时间**（epoch 秒）。
     *
     * - 已解码 ⇒ 解码期定下的值（EXIF 优先，见 [decodeEntry]）
     * - 未解码 / 已淘汰 ⇒ 回落来源给的近似值 [PhotoRef.capturedAt]（照片墙只画已解码的图，
     *   这一步只是保险，避免「刚切到的那一帧恰好被 LRU 挤出」时水印闪空）
     *
     * ⚠️ 与 [peek] 一样**只读主线程缓存、绝不阻塞**；返回 `0` = 这张没有可用的日期（不显示水印）。
     */
    fun capturedAt(ref: PhotoRef): Long {
        if (!closed.get()) {
            val entry = cache[ref.id]
            if (entry != null && !entry.bitmap.isRecycled && entry.capturedAt > 0L) {
                return entry.capturedAt
            }
        }
        return PhotoCaptureDate.resolve(
            exif = 0L,
            fromSource = ref.capturedAt,
            lastModified = ref.lastModified,
            dateAdded = ref.dateAdded,
        )
    }

    /** 已缓存位图的实际字节数（观察用；与 [estimatedBytes] 的**预算口径**不同） */
    val cachedBytes: Long get() = cache.values.sumOf { it.bytes }

    /**
     * **预算口径**的上界（`maxCached` 张满尺寸位图）。
     *
     * ⚠️ 这是「最多会用多少」而不是「现在用了多少」—— 与 `maxCached` 直接相关，
     * 所以门禁 G10 能通过调大 `maxCached` 断言超预算（§14.4）。
     * 实际占用看 [cachedBytes]。
     */
    val estimatedBytes: Long
        get() = PhotoBufferMath.estimatedTotal(targetWidth, targetHeight, maxCached, allowRgb565)

    // ────────────────────────── 请求解码 ──────────────────────────

    /** 请求解码（**幂等**：已缓存 / 已在队列则忽略） */
    fun request(ref: PhotoRef) {
        if (closed.get()) return
        if (cache.containsKey(ref.id)) return
        if (!pending.add(ref.id)) return
        val gen = generation.get()
        executor.execute { decodeAndPublish(ref, gen) }
    }

    /**
     * 预取下一张。
     *
     * ⚠️ 与 [request] 走**同一条单线程队列**（不另设优先级）：照片解码是串行的，
     * 抢先解码「下一张」反而会挤掉「当前张」的补解码。调用方在切换前 1~2 个 HOLD 周期调用即可。
     */
    fun prefetch(ref: PhotoRef?) {
        if (ref != null) request(ref)
    }

    // ────────────────────────── 失效 / 释放 ──────────────────────────

    /**
     * 丢弃某个来源的全部缓存（拔盘 / 换目录 / SAF 授权失效）。
     *
     * ⚠️ 同时自增世代号 ⇒ 该来源（以及其他来源）在途的解码结果会被丢弃，
     * 不会「拔盘后还写回一张已失效的照片」。
     */
    fun invalidateSource(kind: PhotoSourceKind) {
        generation.incrementAndGet()
        val it = cache.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            // 缓存键是 PhotoRef.id（"<kind>:<payload>"）⇒ 从 id 反解来源，避免额外维护映射
            if (kindOf(e.key) == kind) {
                e.value.bitmap.recycle()
                it.remove()
            }
        }
        pending.removeAll { kindOf(it) == kind }
        AppLog.d(TAG, "invalidated source ${kind.name}, cached=${cache.size}")
    }

    /** 清空全部缓存（不关闭线程） */
    fun clear() {
        generation.incrementAndGet()
        for (e in cache.values) e.bitmap.recycle()
        cache.clear()
        pending.clear()
    }

    /**
     * 关闭：停线程 + **逐张 `recycle()`**。
     *
     * 幂等；调用后所有方法变成空操作（[peek] 恒返回 `null`）。
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
        onMain {
            for (e in cache.values) e.bitmap.recycle()
            cache.clear()
            pending.clear()
        }
    }

    // ────────────────────────── 解码（后台线程）──────────────────────────

    private fun decodeAndPublish(ref: PhotoRef, gen: Int) {
        val entry = try {
            decodeEntry(ref)
        } catch (t: Throwable) {
            if (isDecodeCancelled(t)) {
                // close() 用 shutdownNow() 停解码线程（页面销毁 / 画质档变化导致 buffer 重建），
                // 在途的 runBlocking / 读流会被 InterruptedException 打断，message 通常为 null
                // ⇒ 真机日志里就是 "decode failed for xxx.jpg: null"。这是**预期取消**，
                // 结果本来也会在下面按 closed / 世代丢弃，不是照片解码失败。
                // ⛔ 别打成 E：release 日志页只应出现真正要处理的问题（AppLog 分级准则），
                // 而日志保留窗口仅约 2 分钟（§10.401），假错误会把真错误挤掉。
                AppLog.d(TAG, "decode cancelled for ${ref.displayName}: ${t.javaClass.simpleName}")
            } else {
                AppLog.e(TAG, "decode failed for ${ref.displayName}: ${t.message}", t)
            }
            null
        }
        onMain {
            pending.remove(ref.id)
            if (closed.get() || generation.get() != gen) {
                // 已关闭 / 已被 invalidate ⇒ 丢弃结果，别把过期位图塞进缓存
                entry?.bitmap?.recycle()
                return@onMain
            }
            if (entry != null) cache[ref.id] = entry
        }
    }

    /**
     * 解码抛异常时，判断「降为 `AppLog.d`」还是「记 `AppLog.e`」。
     *
     * ⚠️ **结果作废与日志分级解耦**：`clear()` / `invalidateSource()` / `close()` 只让
     * [decodeAndPublish] 在回写时丢弃结果，**不能**据此把异常降级 —— 世代作废期间抛出的
     * OOM / 坏图 / IO 等真失败仍必须记 E，否则 release 日志里零痕迹（原实现把
     * `closed || generation != gen` 当作取消的充分条件，会一并吞掉这些真失败）。
     * ⇒ 只以异常链确为 `InterruptedException` / `CancellationException` 作为「预期取消」依据，
     * `close()` 的 `shutdownNow()` 中断正落在这一支。
     *
     * ⚠️ 沿 cause 链判定：`runBlocking` 有时把 `InterruptedException` 包一层抛出。
     * ⚠️ 不恢复中断标志：解码线程池此时已 shutdownNow，恢复反而会污染单测里的直通 Executor。
     */
    private fun isDecodeCancelled(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth++ < 8) {
            if (cur is InterruptedException || cur is CancellationException) return true
            if (cur === cur.cause) break
            cur = cur.cause
        }
        return false
    }

    /**
     * 读字节 → 解码。
     *
     * ⛔ **只开一次流**（见 [decodeWithFactory] 的 KDoc）—— 这是本方法把「读字节」与
     * 「解码」分开写的原因：`BitmapFactory` 的流只能读一次，而拿宽高通常要读两次。
     *
     * 拍摄时间也在这里定：EXIF 从**同一份字节**里读（`ExifInterface(ByteArray)`），
     * 不额外开流 ⇒ 对 Jellyfin 仍是**一次 HTTP**。
     */
    private fun decodeEntry(ref: PhotoRef): Entry? {
        val source = sourceProvider(ref.source) ?: return null

        // suspend 函数在后台线程里阻塞调用 —— 本方法已不在主线程，安全
        val bytes: ByteArray = runBlocking {
            source.openStream(ref)?.use { it.readBytes() }
        } ?: return null

        val bitmap = decoder?.invoke(ref, bytes) ?: decodeWithFactory(ref, bytes) ?: return null
        val capturedAt = PhotoCaptureDate.resolve(
            exif = readExifCapturedAt(ref, bytes),
            fromSource = ref.capturedAt,
            lastModified = ref.lastModified,
            dateAdded = ref.dateAdded,
        )
        return Entry(bitmap, bitmap.asImageBitmap(), capturedAt)
    }

    /**
     * 从图片字节里读 **EXIF 拍摄时间**（epoch 秒）；读不出返回 `0`。
     *
     * ⛔ **为什么不依赖 `ImageIO` / `android.media.ExifInterface` 的流版本**：
     * 我们手里已经有完整的 `ByteArray`（为了「只开一次流」），用 `ByteArray` 构造最省事，
     * 也不会有「流被 `BitmapFactory` 消费掉」的顺序问题。
     *
     * ⚠️ 只读头部：`ExifInterface` 自己会 `mark/reset` 读完所需段落后停下，
     * 不会把整张图再扫一遍。
     *
     * ⚠️ 任何异常都吞掉（坏图 / 非 JPEG 的 PNG/BMP/WebP 没有 EXIF 段）——
     * 日期是**装饰信息**，不能因为它让一张能显示的照片变成解码失败。
     */
    private fun readExifCapturedAt(ref: PhotoRef, bytes: ByteArray): Long {
        // ⚠️ 单测注入假解码器时（bytes 只有几十字节）不必再费劲解析 —— 直接跳过
        val reader = exifReader
        if (reader != null) return runCatching { reader.invoke(ref, bytes) }.getOrDefault(0L)
        return runCatching {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            PhotoCaptureDate.parse(raw)
        }.getOrDefault(0L)
    }

    /**
     * 真正解码。
     *
     * ## ⛔ 「Jellyfin 只发 1 次 HTTP」是怎么保证的
     *
     * `BitmapFactory` 的流**只能读一次**，而拿宽高通常要「先 `inJustDecodeBounds` 再解码」
     * —— 直接开两次流 = **两次 HTTP 请求**。所以分两条路：
     *
     * | 情形 | 做法 | 开流次数 |
     * |---|---|---|
     * | `width/height > 0`（MediaStore / Jellyfin 免费带回来） | 直接算 `inSampleSize` + `decodeByteArray` | **1** |
     * | `width/height == 0`（`ExternalFilePhotoSource` 刻意不探测） | `inJustDecodeBounds` 读头部 → 算采样率 → `decodeByteArray` | **1** |
     *
     * 代价是内存里多一份原始字节（一张几 MB 的 JPEG）。换来的是**不重复开流** ——
     * 对 Jellyfin 就是少一次网络往返，对外接存储就是少一次磁盘读。
     */
    private fun decodeWithFactory(ref: PhotoRef, bytes: ByteArray): Bitmap? {
        val config = if (allowRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888

        return if (ref.width > 0 && ref.height > 0) {
            // 有尺寸 ⇒ 直接用元数据算采样率，不再开流
            val opts = BitmapFactory.Options().apply {
                inSampleSize = PhotoBufferMath.computeInSampleSize(
                    ref.width, ref.height, targetWidth, targetHeight,
                )
                inPreferredConfig = config
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } else {
            // 无尺寸 ⇒ 先只读头部拿宽高（不分配像素内存），再算采样率解一次
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = PhotoBufferMath.computeInSampleSize(
                    bounds.outWidth, bounds.outHeight, targetWidth, targetHeight,
                )
                inPreferredConfig = config
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }

    // ────────────────────────── 工具 ──────────────────────────

    /** 已在主线程就直接执行，否则 post（让 `close()` / 回写在单测里可确定地跑完） */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    /** 从 `PhotoRef.id` 反解来源；解析不出时返回 `null`（不误伤任何来源） */
    private fun kindOf(id: String): PhotoSourceKind? =
        com.nasmusic.tv.backend.photo.PhotoIds.kindOf(id)

    companion object {
        /** 双缓冲 2 张 + LRU 1 张 */
        const val DEFAULT_MAX_CACHED = 3
        private const val INITIAL_CAPACITY = 8
        private const val TAG = "PhotoBuffer"
    }
}

/**
 * [PhotoBuffer] 的**纯计算**部分（无 Android 依赖、无状态）
 *
 * 单独抽出来是为了让门禁 G10（`PhotoBufferBudgetTest`）能在**纯 JVM** 上跑 ——
 * 不必为了验一个「字节数乘不乘得对」去起 Robolectric 与真实 `Bitmap`。
 *
 * ⛔ 与 `PhotoBuffer` 同文件（不新增文件）：§14.1 的文件清单只有 `PhotoBuffer.kt`。
 */
internal object PhotoBufferMath {

    /** `ARGB_8888` 每像素字节数 */
    const val BYTES_ARGB_8888 = 4L

    /** `RGB_565` 每像素字节数（`Tier.BASIC` 降级档） */
    const val BYTES_RGB_565 = 2L

    /** 文档 §八 的预算口径：40 MiB（≈ 41.9 MB） */
    const val DEFAULT_BUDGET_BYTES = 40L * 1024 * 1024

    /** 单张位图字节数；宽高非法时返回 0（不抛，调用方按「无预算」处理） */
    fun bytesPerBitmap(width: Int, height: Int, allowRgb565: Boolean): Long {
        if (width <= 0 || height <= 0) return 0L
        val perPixel = if (allowRgb565) BYTES_RGB_565 else BYTES_ARGB_8888
        return width.toLong() * height.toLong() * perPixel
    }

    /** 缓存**上限**字节数 = 单张 × 容量（⚠️ 不是当前实际占用，见 [PhotoBuffer.estimatedBytes]） */
    fun estimatedTotal(width: Int, height: Int, maxCached: Int, allowRgb565: Boolean): Long =
        bytesPerBitmap(width, height, allowRgb565) * maxCached.coerceAtLeast(0).toLong()

    /** 是否在预算内（`<=`，与文档「≤ 41 MB」一致） */
    fun isWithinBudget(totalBytes: Long, budgetBytes: Long = DEFAULT_BUDGET_BYTES): Boolean =
        totalBytes <= budgetBytes

    /**
     * 计算 `inSampleSize`（2 的幂）。
     *
     * 目标是「**缩到刚好不小于**目标尺寸」—— 即 `src / sample >= dst`。
     * ⛔ 不能用 `src / dst` 取整再取 2 的幂：那会**过度降采样**（例如 1000 → 目标 600，
     * `1000/600 = 1` ⇒ sample 1 正确；但 1000 → 目标 300 时 `1000/300 = 3` ⇒ 若向上取到 4，
     * 结果只有 250px < 300px，糊了）。
     *
     * 任一参数非法时返回 1（= 不降采样，宁可多占内存也不糊）。
     */
    fun computeInSampleSize(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return 1
        var sample = 1
        // 只要再降一半仍不小于目标，就继续降
        while (srcW / (sample * 2) >= dstW && srcH / (sample * 2) >= dstH) {
            sample *= 2
        }
        return sample
    }
}
