package com.nasmusic.tv.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NetdiskEntryGateTest {

    private fun source(path: String): String {
        val root = File(System.getProperty("user.dir", ""))
        val candidates = listOf(
            File(root, "src/main/java/com/nasmusic/tv/$path"),
            File(root, "app/src/main/java/com/nasmusic/tv/$path"),
            File(root, "../app/src/main/java/com/nasmusic/tv/$path"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("找不到 $path", file != null)
        return file!!.readText()
    }

    @Test
    fun `我的页必须提供可达网盘入口`() {
        val mine = source("ui/screens/MineScreen.kt")
        assertTrue("网盘入口必须实际渲染", mine.contains("MineQuickLink(stringResource(R.string.netdisk_title), onOpenNetdisk)"))
        val branch = source("ui/components/branches/MineBranch.kt")
        assertTrue("入口必须导航到网盘页", branch.contains("onOpenNetdisk = { viewModel.navVM.navigateTo(Screen.Netdisk) }"))
    }

    @Test
    fun `网盘浏览请求必须在源开关处收敛`() {
        val vm = source("ui/viewmodel/NetworkMusicViewModel.kt")
        val listBody = vm.substringAfter("fun listBaiduDir(").substringBefore("fun loadMoreBaiduDir(")
        val listGate = listBody.indexOf("if (!isBaiduSourceActive)")
        val listRequest = listBody.indexOf("baiduApi.listDir(")
        assertTrue("列目录必须保留源开关检查", listGate >= 0)
        assertTrue("列目录发起前必须再次检查源开关", listBody.indexOf("if (!isBaiduSourceActive)", listGate + 1) in (listGate + 1) until listRequest)
        assertTrue("列目录必须在请求前检查源开关", listRequest > listGate)
        val searchBody = vm.substringAfter("fun searchBaidu(").substringBefore("fun clearNetdiskSearch(")
        val searchGate = searchBody.indexOf("if (!isBaiduSourceActive)")
        val searchRequest = searchBody.indexOf("baiduApi.searchAudio(")
        assertTrue("搜索必须保留源开关检查", searchGate >= 0)
        assertTrue("搜索必须在请求前检查源开关", searchRequest > searchGate)
        val loadMoreBody = vm.substringAfter("fun loadMoreBaiduDir(").substringBefore("suspend fun listBaiduDirs(")
        assertTrue("加载更多必须检查源开关", loadMoreBody.contains("if (!isBaiduSourceActive)"))
        assertTrue("加载更多关闭源时必须清理状态", loadMoreBody.contains("onBaiduSourceDisabled()"))
        assertTrue("加载更多必须保留分页守卫", loadMoreBody.contains("if (!_netdiskHasMore.value || _netdiskLoadingMore.value || _netdiskIsLoading.value) return"))
        assertTrue("目录树必须检查源开关", vm.contains("if (isBaiduSourceActive) baiduApi.listDir(path).files else emptyList()"))
        val screen = source("ui/screens/netdisk/NetdiskScreen.kt")
        assertTrue("页面首次加载也必须检查源开关", screen.contains("if (viewModel.netVM.isBaiduSourceActive)"))
    }

    @Test
    fun `关闭百度源必须即时清理网盘并门控播放`() {
        val vm = source("ui/viewmodel/NetworkMusicViewModel.kt")
        assertTrue("关源必须取消请求并清空状态", vm.contains("fun onBaiduSourceDisabled()"))
        assertTrue("关源必须递增请求世代", vm.contains("netdiskGeneration++"))
        assertTrue("关源必须取消分页请求", vm.contains("netdiskLoadMoreJob?.cancel()"))
        val playBody = vm.substringAfter("fun playAllNetdiskSearch()").substringBefore("fun playAllNetdiskDir(")
        assertTrue("搜索播放必须检查源开关", playBody.contains("if (!isBaiduSourceActive)"))
        val main = source("ui/viewmodel/MainViewModel.kt")
        assertTrue("设置关源时必须通知网盘清理", main.contains("if (!enabled) netVM.onBaiduSourceDisabled()"))
    }
}
