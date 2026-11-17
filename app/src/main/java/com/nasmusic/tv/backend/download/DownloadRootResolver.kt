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
 *   必须「路径不同于内置 + 已挂载 + 可写 + 至少 512MB 可用」才生效；
 *   不可用就回落内置并节流记 W，不让设置页变成静默假开关。
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

    /** 内置下载根目录，保留原来的 `filesDir/music` 兜底。 */
    fun internalRoot(context: Context): File =
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "music")

    /** 当前写入根目录；CUSTOM 不可用时回落内置。 */
    fun currentRoot(context: Context, location: String): File {
        if (location != CUSTOM_LOCATION) return internalRoot(context)
        val external = externalRoot(context)
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
     * 只扫新根会让它们从曲库消失（虽然文件没丢）。
     */
    fun scanRoots(context: Context, location: String): List<File> {
        val internal = internalRoot(context)
        if (location != CUSTOM_LOCATION) return listOf(internal)
        val external = externalRoot(context) ?: return listOf(internal)
        return if (external.absolutePath == internal.absolutePath) {
            listOf(internal)
        } else {
            listOf(internal, external)
        }
    }

    /** 设置页用：当前设备上「外部存储」是否真的可选。 */
    fun isCustomAvailable(context: Context): Boolean = externalRoot(context) != null

    private fun externalRoot(context: Context): File? {
        val candidates = runCatching { context.getExternalFilesDirs(Environment.DIRECTORY_MUSIC) }
            .getOrNull() ?: return null
        if (candidates.size < 2) return null
        val candidate = candidates[1] ?: return null
        if (candidate.absolutePath == internalRoot(context).absolutePath) return null
        val state = runCatching { Environment.getExternalStorageState(candidate) }.getOrNull()
        if (state != Environment.MEDIA_MOUNTED) return null
        if (!candidate.exists() && !candidate.mkdirs()) return null
        if (!candidate.canWrite()) return null
        if (candidate.usableSpace < MIN_CUSTOM_FREE_BYTES) return null
        return candidate
    }
}
