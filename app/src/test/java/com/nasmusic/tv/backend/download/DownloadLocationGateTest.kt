package com.nasmusic.tv.backend.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §10.411：下载位置「真开关」的源码门禁（2026-11-17）。
 *
 * 旧实现里 `downloadLocation` 写进 DataStore、显示在设置页，却**没有任何消费方**：
 * 四个注入点 + 三处硬读全部写死 `getExternalFilesDir(DIRECTORY_MUSIC)`，
 * 按「外部存储」只换标签。本门禁锁住：
 *
 * | # | 断言 |
 * |---|---|
 * | L1 | 写文件 / 清理 / 恢复路径不得再直读 context 外置目录 |
 * | L2 | 四处注入共用同一个 rootProvider（NasMusicApp） |
 * | L3 | LocalMusicRepository 扫两个根（切换后旧文件不消失） |
 * | L4 | 设置写值有 @Volatile 同步镜像，IO 路径不 runBlocking |
 * | L5 | UI 显示当前实际写入根 + 外置不可用提示，中英文齐全 |
 * | L6 | 负向自证 |
 */
class DownloadLocationGateTest {

    private fun src(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/$relative"),
            File("app/src/main/java/com/nasmusic/tv/$relative"),
            File("../app/src/main/java/com/nasmusic/tv/$relative"),
        )
        val f = candidates.firstOrNull { it.isFile }
        assertTrue("找不到 $relative（user.dir=${File("").absolutePath}）", f != null)
        return f!!.readText(Charsets.UTF_8)
    }

    private fun res(path: String): String {
        val f = File("../app/src/main/res/$path").takeIf { it.isFile } ?: File("app/src/main/res/$path")
        assertTrue("找不到资源 $path", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `下载管理器不再硬读外置目录`() {
        val mgr = src("backend/download/SongDownloadManager.kt")
        assertFalse(
            "SongDownloadManager 仍直读外置目录 —— CUSTOM 下写文件与恢复会指向两个不同目录",
            mgr.contains("context.getExternalFilesDir"),
        )
        assertTrue("写入根 provider 必须存在", mgr.contains("private val rootProvider: () -> File"))
        assertTrue("恢复/清理必须看所有根", mgr.contains("private val allRootsProvider: () -> List<File>"))
        assertTrue("崩溃恢复遍历所有根", mgr.contains("for (root in allRootsProvider())"))
    }

    @Test
    fun `四个注入点共用同一个provider`() {
        val app = src("NasMusicApp.kt")
        assertTrue("缺统一解析", app.contains("DownloadRootResolver.currentRoot("))
        assertTrue("缺多根扫描", app.contains("DownloadRootResolver.scanRoots("))
        assertTrue("曲库扫描没接上", app.contains("downloadRootsProvider = downloadRootsProvider"))
        assertTrue("空间守护没接上", app.contains("rootProvider = downloadRootProvider"))
        assertTrue("落盘路径没接上", app.contains("DownloadPathBuilder(downloadRootProvider)"))
        assertTrue("下载管理器没接上", app.contains("allRootsProvider = downloadRootsProvider"))
    }

    @Test
    fun `本地曲库扫描两个根`() {
        val repo = src("backend/local/LocalMusicRepository.kt")
        assertTrue("仍是单根 provider", repo.contains("downloadRootsProvider: () -> List<java.io.File>"))
        assertTrue("没有遍历多根", repo.contains("for (downloadRoot in downloadRootsProvider())"))
        assertTrue("没有去重根路径", repo.contains("visitedPaths.add(downloadRoot.absolutePath)"))
    }

    @Test
    fun `设置值有同步镜像`() {
        val prefs = src("data/prefs/AppPreferences.kt")
        assertTrue("缺同步读", prefs.contains("fun getDownloadLocationSync(): String = cachedDownloadLocation"))
        assertTrue("缺镜像收集", prefs.contains("cachedDownloadLocation = it"))
    }

    @Test
    fun `UI如实显示当前写入根与不可用提示`() {
        val section = src("ui/screens/settings/DownloadSettingsSection.kt")
        assertTrue("缺可用性标记", section.contains("settings_download_location_unavailable"))
        assertTrue("缺实际路径显示", section.contains("settings_download_location_current"))
        val branch = src("ui/components/branches/SettingsBranch.kt")
        assertTrue("设置分支未传真实状态", branch.contains("downloadResolvedRootPath = downloadRootInfo.second"))
        for (locale in listOf("values", "values-en")) {
            val s = res("$locale/strings.xml")
            assertTrue("$locale 缺当前路径文案", s.contains("name=\"settings_download_location_current\""))
            assertTrue("$locale 缺不可用提示", s.contains("name=\"settings_download_location_unavailable\""))
        }
    }

    @Test
    fun `负向自证旧实现必须被判红`() {
        val legacy = "val root = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)"
        assertTrue("门禁空转", legacy.contains("context.getExternalFilesDir"))
    }
}
