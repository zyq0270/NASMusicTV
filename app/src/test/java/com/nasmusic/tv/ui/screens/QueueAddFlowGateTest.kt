package com.nasmusic.tv.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「全部加入队列」链路门禁：只增不删、口径统一。
 *
 * 背景（2026-11-21 流程分析）：曲库搜索/发现/网盘搜索早已改为 addSongsToQueue，
 * 但专辑、歌手、「我的」三页的批量按钮仍是 `songs.forEach { onToggleQueue(it) }`，
 * toggle 的语义是「在队列则移除」⇒ 点一次「加入队列」会把已入队歌曲反向踢出、打乱顺序。
 * Composable 行为测试本仓库跑不了，沿用源码文本门 + 负自检（语句级断言，
 * 先确认读到的确是目标文件，避免注释里解释禁写模式造成自伤）。
 */
class QueueAddFlowGateTest {

    private fun flatten(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),
            File("app/src/main/java/com/nasmusic/tv"),
            File("../app/src/main/java/com/nasmusic/tv")
        )
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue("找不到源码目录；已尝试：${candidates.joinToString { it.absolutePath }}", root != null)
        return root!!
    }

    private fun source(relativePath: String): String {
        val file = sourceRoot().resolve(relativePath)
        assertTrue("找不到源码文件：${file.absolutePath}", file.isFile)
        val text = file.readText(Charsets.UTF_8)
        assertTrue("源码文件读出来是空的：${file.absolutePath}", text.isNotBlank())
        return text
    }

    private val forbiddenToggleLoop = "forEach { song -> onToggleQueue(song) }"

    @Test
    fun `T1 三个页面的批量加入不得再用逐首 toggle`() {
        val pages = mapOf(
            "ui/screens/AlbumDetailScreen.kt" to "fun AlbumDetailScreen(",
            "ui/screens/ArtistDetailScreen.kt" to "fun ArtistDetailScreen(",
            "ui/screens/MineScreen.kt" to "fun MineScreen("
        )
        for ((path, signature) in pages) {
            val src = flatten(source(path))
            assertTrue("$path 空转自证：读到的不是目标 Composable", src.contains(signature))
            assertFalse(
                "$path 批量「加入队列」仍是逐首 toggle —— 已入队歌曲会被反向移除。",
                src.contains(forbiddenToggleLoop)
            )
            assertTrue(
                "$path 缺 onAddSongsToQueue 参数，批量按钮无法走只增不删入口。",
                src.contains("onAddSongsToQueue: (List<Song>) -> Unit")
            )
            assertTrue(
                "$path 行内单曲按钮丢了 onToggleQueue（单曲切换语义必须保留）。",
                src.contains("onToggleQueue = { onToggleQueue(song) }")
            )
        }
    }

    @Test
    fun `T2 批量按钮必须调用 onAddSongsToQueue`() {
        val album = flatten(source("ui/screens/AlbumDetailScreen.kt"))
        assertTrue("专辑页批量按钮没走只增不删入口", album.contains("onClick = { onAddSongsToQueue(songs) }"))
        val artist = flatten(source("ui/screens/ArtistDetailScreen.kt"))
        assertTrue("歌手页批量按钮没走只增不删入口", artist.contains("onClick = { onAddSongsToQueue(songs) }"))
        val mine = flatten(source("ui/screens/MineScreen.kt"))
        assertTrue(
            "「我的」收藏 pane 没走只增不删入口",
            mine.contains("onAddAllToQueue = { onAddSongsToQueue(mergedFavorites) }")
        )
        // 「我的」的两个批量入口分别使用收藏合并结果与歌曲列表；
        // 只要求都走只增不删入口，不要求两处实参是同一个片段。
        val addArguments = Regex("""onAddSongsToQueue\(\s*([^)]*?)\s*\)""")
            .findAll(mine)
            .map { it.groupValues[1].trim() }
            .toList()
        assertEquals("「我的」应恰好有两处批量加入入口", 2, addArguments.size)
        assertEquals(
            "「我的」批量加入实参必须是收藏合并结果与歌曲列表",
            setOf("mergedFavorites", "songs"),
            addArguments.toSet()
        )
    }

    @Test
    fun `T3 三个 Branch 必须把批量入口接到 playerVM 的只增不删实现`() {
        for (path in listOf(
            "ui/components/branches/AlbumDetailBranch.kt",
            "ui/components/branches/ArtistDetailBranch.kt",
            "ui/components/branches/MineBranch.kt"
        )) {
            val src = flatten(source(path))
            assertTrue("$path 未声明批量入队回调", src.contains("onAddSongsToQueue = {"))
            assertTrue(
                "$path 未接到 playerVM.addSongsToQueue ⇒ 批量加入仍可能走 toggle。",
                src.contains("viewModel.playerVM.addSongsToQueue(")
            )
        }
    }

    @Test
    fun `T4 既有修复点防回归`() {
        val library = flatten(source("ui/components/branches/LibraryBranch.kt"))
        assertTrue(
            "搜索 Tab 批量加入退回旧实现",
            library.contains("viewModel.playerVM.addSongsToQueue(searchResultsList)")
        )
        assertTrue(
            "发现 Tab 批量加入退回旧实现",
            library.contains("viewModel.playerVM.addSongsToQueue(browseResultsList)")
        )
        val netdisk = flatten(source("ui/screens/netdisk/NetdiskScreen.kt"))
        assertTrue(
            "网盘搜索页批量加入退回旧实现（C38）",
            netdisk.contains("viewModel.playerVM.addSongsToQueue(searchResults)")
        )
    }

    @Test
    fun `T5 底层入队必须统一去重口径且不再要求 player 已有当前条目`() {
        val pm = flatten(source("player/PlayerManager.kt"))
        assertTrue(
            "批量入队没走唯一去重口径",
            pm.contains("QueueAddition.compute(currentQueue, songs)")
        )
        assertTrue(
            "单曲入队没走唯一去重口径",
            pm.contains("QueueAddition.compute(currentQueue, listOf(song))")
        )
        assertFalse(
            "单曲入队仍要求 player 已有 currentMediaItem 才写 ExoPlayer ⇒ 镜像与实际播放列表不同步。",
            pm.contains("if (player?.currentMediaItem != null)")
        )
        val addition = flatten(source("player/QueueAddition.kt"))
        assertTrue(
            "去重判据缺失：必须同时排除已在队列者并对候选自身去重。",
            addition.contains("song.id !in existingIds && seen.add(song.id)")
        )
        val vm = flatten(source("ui/viewmodel/PlayerViewModel.kt"))
        assertFalse(
            "PlayerViewModel 又自建了一套入队过滤，口径会再次漂移。",
            vm.contains("existingIds = playerManager.playerState.value.queue.map")
        )
    }

    @Test
    fun `T6 负自检：旧模式判据必须能判负`() {
        val oldCode = "onClick = { songs.forEach { song -> onToggleQueue(song) } }"
        assertTrue("护栏失效：旧模式未被识别", flatten(oldCode).contains(forbiddenToggleLoop))
        val addButtonBody = flatten(source("ui/screens/AlbumDetailScreen.kt"))
            .substringAfter("val addQueueButton")
            .substringBefore("if (isPhonePortrait)")
        assertFalse("护栏失效：判据被自身注释污染", addButtonBody.contains(forbiddenToggleLoop))
    }
}
