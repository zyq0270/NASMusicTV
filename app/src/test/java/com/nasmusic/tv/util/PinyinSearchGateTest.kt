package com.nasmusic.tv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「首字母 / 全拼搜索」链路源码门禁（v2.39.26）。
 *
 * 这几条缺陷的共同形态是**两套匹配器能力不等价** + **拼音分支不回退服务端**，
 * 都会被常规单测漏掉（匹配器本身"能跑"，只是调用点用错了），所以在源码文本层钉死：
 * 每组带负自检（把修复形态抹掉后断言必须判负），防止判据恒真空转。
 */
class PinyinSearchGateTest {

    private fun flatten(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun src(relativePath: String): String = flatten(mainSourceFile(relativePath))

    private fun region(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("找不到起始签名：$start", from >= 0)
        val to = source.indexOf(end, from + start.length)
        assertTrue("找不到结束签名：$end", to > from)
        return source.substring(from, to)
    }

    private fun count(source: String, fragment: String): Int =
        Regex(Regex.escape(fragment)).findAll(source).count()

    @Test
    fun `T1 匹配能力必须只有一套（PinyinUtils），且含全拼与首字母`() {
        assertFalse(
            "PinyinMatcher.kt 又出现了：两套匹配器能力不等价正是全拼被裁掉的根因",
            mainSourceRoot().resolve("util/PinyinMatcher.kt").exists()
        )
        val utils = src("util/PinyinUtils.kt")
        val body = region(
            utils,
            "fun matches(text: String, query: String): Boolean",
            "fun matchesQuery(text: String, query: String): Boolean"
        )
        for (fragment in listOf(
            "t.contains(q)",
            "toPinyin(text).contains(q)",
            "toPinyinInitials(text)"
        )) {
            assertTrue("PinyinUtils.matches 缺路径：$fragment", body.contains(fragment))
        }
        // 首字母缓存必须真的被读写（§10.89.3 只做了 toPinyin 一半）
        assertTrue("initialsCache 未接上读写", count(utils, "initialsCache[") >= 2)
        assertFalse(
            "T1 护栏失效：删掉全拼路径仍判正",
            body.replace("toPinyin(text).contains(q)", "").contains("toPinyin(text).contains(q)")
        )
    }

    @Test
    fun `T2 三源拼音分支零命中必须回退服务端`() {
        val aggregator = src("backend/SearchAggregator.kt")
        val fallback = "if (localHits.isNotEmpty()) localHits else searchServer()"
        assertEquals(
            "NAS / 百度 / 本地三处引脚零命中都要回退服务端，实际命中 $fallback 的次数不对",
            3,
            count(aggregator, fallback)
        )
        // 旧形态：百度拼音分支直接 emptyList() ⇒ 没建索引就搜不到任何东西
        assertFalse(
            "百度拼音分支又回到 `else if (isPinyinQuery && isTVDevice) { emptyList() }`",
            aggregator.contains("else if (isPinyinQuery && isTVDevice)")
        )
        assertTrue(
            "拼音形态判据必须走 SearchQuery.isPinyinLike（不要内联 keyword.all）",
            aggregator.contains("val isPinyinQuery = SearchQuery.isPinyinLike(keyword)")
        )
        assertFalse(
            "T2 护栏失效：删掉回退仍判正",
            count(aggregator.replace(fallback, ""), fallback) > 0
        )
    }

    @Test
    fun `T3 百度索引拼音匹配必须离开主线程且只对拼音形态求值`() {
        // 2026-11-29：拼音索引求值随共享搜索入口迁入 SharedSearchService
        // （界面 / 遥控 / 语音共用同一份「只对拼音形态求值 + 离开主线程」语义）。
        val svc = src("backend/search/SharedSearchService.kt")
        val block = region(
            svc,
            "val caches = if (SearchQuery.isPinyinLike(query))",
            "} else {",
        )
        assertTrue("百度索引求值没有切到 Dispatchers.Default", block.contains("withContext(Dispatchers.Default)"))
        assertTrue("百度索引只在拼音形态下求值", block.contains("baiduLocalSongsProvider(query)"))
        val vm = src("ui/viewmodel/SearchViewModel.kt")
        assertTrue("百度索引拼音匹配开关丢了", vm.contains("pinyinMatch = true"))
        assertTrue("搜索页必须走共享搜索入口", vm.contains("sharedSearchService.searchNormalized("))
        // 旧形态：直接写在实参位置 ⇒ 参数求值发生在聚合器的 withContext 之前
        assertFalse(
            "baiduLocalSongs 又写成聚合器的实参直调（主线程 3.8 万条拼音匹配）",
            vm.contains("baiduLocalSongs = nasMusicApp.baiduFileIndexCache.searchSongs(")
        )
        assertFalse(
            "T3 护栏失效：把 withContext 抹掉仍判正",
            block.replace("withContext(Dispatchers.Default)", "").contains("withContext(Dispatchers.Default)")
        )
    }

    @Test
    fun `T4 切 Tab 必须按新维度重搜，电台必须有拼音兜底`() {
        val vm = src("ui/viewmodel/MainViewModel.kt")
        val select = region(vm, "fun selectLibraryTab(tab: LibraryTab)", "// --- 曲库页滚动位置记忆")
        assertTrue("切 Tab 不重搜：缺 searchSongsOnServer 调用", select.contains("searchVM.searchSongsOnServer(keyword"))
        assertTrue("切 Tab 不该记搜索历史", select.contains("recordHistory = false"))
        assertTrue("切 Tab 只对用到搜索结果的 Tab 重搜", select.contains("LibraryTab.SEARCH, LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.SONGS"))

        val radio = region(vm, "fun searchRadio(keyword: String)", "private fun loadRadioStations(")
        assertTrue("电台搜索没开拼音兜底", radio.contains("pinyinFallback = true"))
        val load = region(vm, "private fun loadRadioStations(", "fun playRadioStation(")
        assertTrue("电台兜底没走客户端拼音筛", load.contains("PinyinUtils.matchesQuery(station.name, query)"))
        assertTrue("电台兜底没合并去重", load.contains("(direct + fallback).distinctBy { it.uuid }"))
        assertFalse(
            "T4 护栏失效：删掉重搜仍判正",
            select.replace("searchVM.searchSongsOnServer(keyword", "").contains("searchVM.searchSongsOnServer(keyword")
        )
    }

    @Test
    fun `T5 网盘页搜索必须先查本地索引再合并服务端`() {
        val vm = src("ui/viewmodel/NetworkMusicViewModel.kt")
        val body = region(vm, "fun searchBaidu(keyword: String)", "fun clearNetdiskSearch()")
        assertTrue("网盘搜索没有本地拼音索引", body.contains("baiduIndexCache.searchSongs(keyword, pinyinMatch = true"))
        assertTrue("本地索引求值没有离开主线程", body.contains("Dispatchers.Default"))
        assertTrue("本地命中未与服务端结果合并", body.contains("mergeNetdiskSearchResults(localHits, remote)"))
        assertTrue("服务端失败时丢了本地命中", body.contains("_netdiskSearchResults.value = localHits"))
        assertFalse(
            "T5 护栏失效：删掉合并仍判正",
            body.replace("mergeNetdiskSearchResults(localHits, remote)", "").contains("mergeNetdiskSearchResults(localHits, remote)")
        )
    }

    private fun mainSourceFile(relativePath: String): String {
        val file = mainSourceRoot().resolve(relativePath)
        assertTrue("找不到源码文件：${file.absolutePath}", file.isFile)
        val text = file.readText(Charsets.UTF_8)
        assertTrue("源码文件读出来是空的：${file.absolutePath}", text.isNotBlank())
        return text
    }

    private fun mainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),
            File("app/src/main/java/com/nasmusic/tv"),
            File("../app/src/main/java/com/nasmusic/tv")
        )
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "找不到源码目录（user.dir=${File("").absolutePath}）；已尝试：${candidates.joinToString { it.absolutePath }}",
            root != null
        )
        return root!!
    }
}
