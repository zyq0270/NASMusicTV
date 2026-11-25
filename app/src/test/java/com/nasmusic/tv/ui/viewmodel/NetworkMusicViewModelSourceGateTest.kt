package com.nasmusic.tv.ui.viewmodel

import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.backend.network.baidu.BaiduFileIndexCache
import com.nasmusic.tv.backend.network.baidu.BaiduPanApi
import com.nasmusic.tv.data.model.BaiduFile
import com.nasmusic.tv.data.model.NetworkFavoriteItem
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.data.prefs.BaiduPrefs
import com.nasmusic.tv.data.prefs.HistoryPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkMusicViewModelSourceGateTest {

    private lateinit var app: NasMusicApp
    private lateinit var prefs: AppPreferences
    private lateinit var baiduPrefs: BaiduPrefs
    private lateinit var api: BaiduPanApi
    private lateinit var vm: NetworkMusicViewModel

    private val audio = BaiduFile(
        fsId = 1L,
        path = "/音乐/晴天.mp3",
        serverFilename = "晴天.mp3",
        isDir = false,
        size = 100L,
        category = 1,
        md5 = null,
        serverMtime = 1L,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        prefs = mock(AppPreferences::class.java)
        baiduPrefs = mock(BaiduPrefs::class.java)
        val history = mock(HistoryPrefs::class.java)
        api = mock(BaiduPanApi::class.java)
        val indexCache = mock(BaiduFileIndexCache::class.java)
        app = mock(NasMusicApp::class.java)

        doReturn(prefs).`when`(app).appPreferences
        doReturn(api).`when`(app).baiduPanApi
        doReturn(indexCache).`when`(app).baiduFileIndexCache
        doReturn(baiduPrefs).`when`(prefs).baidu
        doReturn(history).`when`(prefs).history
        doReturn(emptyFlow<NetworkFavoriteItem>()).`when`(history).networkFavorites
        doReturn(true).`when`(prefs).getBaiduActiveSync()
        doReturn("/音乐").`when`(baiduPrefs).getBaiduMusicRootDir()
        doReturn(BaiduPanApi.BaiduListResult(listOf(audio), false)).`when`(api)
            .listDir("/音乐", 0, com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig.PAGE_SIZE, "name", 0)
        doReturn(listOf(audio)).`when`(api)
            .searchAudio("晴天", "/音乐", 0, com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig.SEARCH_PAGE_SIZE)

        vm = NetworkMusicViewModel(app)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `关源清理必须清空目录搜索和分页并禁止播放残留结果`() = runTest {
        vm.listBaiduDir("/音乐")
        vm.searchBaidu("晴天")
        assertTrue(vm.netdiskDirFiles.value.isNotEmpty())
        assertTrue(vm.netdiskSearchResults.value.isNotEmpty())

        var played = false
        vm.onPlayQueue = { _, _ -> played = true }
        doReturn(false).`when`(prefs).getBaiduActiveSync()
        vm.listBaiduDir("/音乐")
        vm.playAllNetdiskSearch()

        assertTrue(vm.netdiskDirFiles.value.isEmpty())
        assertTrue(vm.netdiskSearchResults.value.isEmpty())
        assertEquals("", vm.netdiskSearchKeyword.value)
        assertFalse(vm.netdiskIsLoading.value)
        assertFalse(vm.netdiskLoadingMore.value)
        assertFalse(vm.netdiskHasMore.value)
        assertFalse(played)
    }

    @Test
    fun `关源必须取消分页请求并使在途结果失效`() = runTest {
        val continuation = AtomicReference<Continuation<BaiduPanApi.BaiduListResult>>()
        doAnswer { invocation ->
            if (invocation.getArgument<Int>(1) == 0) {
                BaiduPanApi.BaiduListResult(listOf(audio), true)
            } else {
                @Suppress("UNCHECKED_CAST")
                continuation.set(
                    invocation.rawArguments.last()
                        as Continuation<BaiduPanApi.BaiduListResult>
                )
                COROUTINE_SUSPENDED
            }
        }.`when`(api).listDir(anyString(), anyInt(), anyInt(), anyString(), anyInt())

        vm.listBaiduDir("/音乐")
        assertTrue("首屏必须加载完成", !vm.netdiskIsLoading.value)
        assertTrue("首屏必须还有下一页", vm.netdiskHasMore.value)
        vm.loadMoreBaiduDir()
        val pending = continuation.get()
        assertTrue("分页 API 必须被调用并挂起", pending != null)
        assertTrue("分页请求必须进入 loading", vm.netdiskLoadingMore.value)
        assertTrue(pending.context[Job]?.isActive == true)

        doReturn(false).`when`(prefs).getBaiduActiveSync()
        vm.listBaiduDir("/音乐")
        assertTrue("关源必须取消在途分页请求", pending.context[Job]?.isCancelled == true)
        pending.resume(BaiduPanApi.BaiduListResult(listOf(audio), false))

        assertTrue(vm.netdiskDirFiles.value.isEmpty())
        assertFalse(vm.netdiskLoadingMore.value)
        assertFalse(vm.netdiskHasMore.value)
    }
}
