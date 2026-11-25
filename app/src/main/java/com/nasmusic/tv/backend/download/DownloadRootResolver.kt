package com.nasmusic.tv.backend.download

import android.content.Context
import android.os.Environment
import com.nasmusic.tv.util.AppLog
import java.io.File

/**
 * 下载根目录解析器（§10.411）。
 *
 * 背景：`settings_download_location` 以前是个假开关——设置页写 INTERNAL/CUSTOM，
 * 但 A/B/C/D 四个注入点 + `SongDownloadManager` 三处硬读全部写死
 * `getExternalFilesDir(DIRECTORY_MUSIC)`，切「外部存储」只换标签。
 *
 * 这里把所有决策收口：
 * - **INTERNAL**：应用专属音乐目录（原行为，永远可用）；
 * - **CUSTOM**：第二外置卷上的应用专属音乐目录（真 SD 卡 / 外接存储），
 *   已挂载且可读即可扫描旧下载；新写入还要求可写 + 至少 512MB 可用；
 *   不可写或空间不足就回落内置并节流记 W，不让设置页变成静默假开关。
 *
 * ⚠️ 创维 API 22 上外置卷可能是 RAM-backed 小分区 ⇒ 空间门槛不能省。
 * ⚠️ 切换目录**不搬迁**旧文件：`audioPath` 是绝对路径，仍可播放；
 * 本地曲库通过 [scanRoots] 同时扫两个根，避免切换后旧下载从曲库消失。
 */
object DownloadRootResolver {
    private const val TAG = "DownloadRootResolver"
    private const val CUSTOM_LOCATION = "CUSTOM"
    /** 外置卷至少要有 512MB 可用才允许用户当下载盘 */
    private const val MIN_CUSTOM_FREE_BYTES = 512L * 1024 * 1024
    /** 不可用提示 5 分钟最多一条，避免刷屏占掉日志页窗口（§10.401） */
    private const val WARN_THROTTLE_MS = 5 * 60 * 1000L

    @Volatile
    private var lastUnavailableWarnAt = 0L

    /** 外部卷的运行时状态；把空间门槛留给写入判据，避免扫描误过滤旧下载。 */
    internal data class ExternalRootCandidate(
        val root: File,
        val mounted: Boolean,
        val readable: Boolean,
        val writable: Boolean,
        val usableSpace: Long,
    )

    /** 内置下载根目录，保留原来的 `filesDir/music` 兜底。 */
    fun internalRoot(context: Context): File =
        // cache-inventory-exempt: 外部音乐目录的回落路径，与 CacheInventory 的 EXTERNAL_MUSIC（dir=""）是同一份数据，重复登记会双计体积
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "music")

    /** 当前写入根目录；CUSTOM 不可用时回落内置。 */
    fun currentRoot(context: Context, location: String): File {
        if (location != CUSTOM_LOCATION) return internalRoot(context)
        val external = resolveWriteRoot(internalRoot(context), location, externalCandidate(context))
        if (external != null) return external
        val now = System.currentTimeMillis()
        if (now - lastUnavailableWarnAt >= WARN_THROTTLE_MS) {
            lastUnavailableWarnAt = now
            AppLog.w(TAG, "CUSTOM download location unavailable; writing to internal root")
        }
        return internalRoot(context)
    }

    /**
     * 本地曲库需要扫描的根集合。
     *
     * CUSTOM 生效时**两个根都扫**：切换前 downloaded 的文件还在内置目录，
     * 只扫新根会让它们从曲库消失（虽然文件没丢）。外部卷剩余空间不足只影响
     * 新写入资格，不影响已挂载、可读卷上的旧文件发现。
     */
    fun scanRoots(context: Context, location: String): List<File> =
        resolveScanRoots(internalRoot(context), location, externalCandidate(context))

    /** 设置页用：当前设备上「外部存储」是否真的可选。 */
    fun isCustomAvailable(context: Context): Boolean =
        resolveWriteRoot(internalRoot(context), CUSTOM_LOCATION, externalCandidate(context)) != null

    internal fun resolveScanRoots(
        internal: File,
        location: String,
        external: ExternalRootCandidate?,
    ): List<File> {
        if (location != CUSTOM_LOCATION) return listOf(internal)
        val root = external?.takeIf {
            it.mounted && it.readable && it.root.absolutePath != internal.absolutePath
        }?.root ?: return listOf(internal)
        return listOf(internal, root)
    }

    internal fun resolveWriteRoot(
        internal: File,
        location: String,
        external: ExternalRootCandidate?,
    ): File? {
        if (location != CUSTOM_LOCATION) return internal
        return external?.takeIf {
            it.mounted && it.readable && it.writable &&
                it.root.exists() && it.root.absolutePath != internal.absolutePath &&
                it.usableSpace >= MIN_CUSTOM_FREE_BYTES
        }?.root
    }

    private fun externalCandidate(context: Context): ExternalRootCandidate? {
        val candidates = runCatching { context.getExternalFilesDirs(Environment.DIRECTORY_MUSIC) }
            .getOrNull() ?: return null
        if (candidates.size < 2) return null
        val candidate = candidates[1] ?: return null
        if (candidate.absolutePath == internalRoot(context).absolutePath) return null
        val state = runCatching { Environment.getExternalStorageState(candidate) }.getOrNull()
            ?: return null
        if (state != Environment.MEDIA_MOUNTED) return null
        if (!candidate.exists() && !candidate.mkdirs()) return null
        return ExternalRootCandidate(
            root = candidate,
            mounted = true,
            readable = candidate.canRead(),
            writable = candidate.canWrite(),
            usableSpace = candidate.usableSpace,
        )
    }
}
