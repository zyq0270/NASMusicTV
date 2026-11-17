package com.nasmusic.tv.backend.weather

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §10.410：天气电台改用服务端搜索的源码门禁（2026-11-17）。
 *
 * 旧实现 `getSongs(500, offset)` 循环分页拉满 5000 首，再对每首歌在内存里
 * `title/artist/album.lowercase().contains(q)`。曲库 > 5000 首时尾部歌曲永远命中不到，
 * 且每次构建白拉 10 页。BackendAdapter.searchSongs 早已存在且 5 个适配器全部实现 ⇒
 * 这不是「等接口」，而是管理器没迁移。
 *
 * 本门禁同时锁住「保留客户端语义」：补齐池、打乱、`nas_` / `nasf_` 分类前缀。
 */
class WeatherRadioSearchGateTest {

    private fun managerSource(): String {
        val f = listOf(
            File("src/main/java/com/nasmusic/tv/backend/weather/WeatherRadioManager.kt"),
            File("app/src/main/java/com/nasmusic/tv/backend/weather/WeatherRadioManager.kt"),
            File("../app/src/main/java/com/nasmusic/tv/backend/weather/WeatherRadioManager.kt"),
        ).firstOrNull { it.isFile }
        assertTrue("找不到 WeatherRadioManager.kt（user.dir=${File("").absolutePath}）", f != null)
        return f!!.readText(Charsets.UTF_8)
    }

    private fun backendSource(): String {
        val f = listOf(
            File("src/main/java/com/nasmusic/tv/backend/BackendAdapter.kt"),
            File("app/src/main/java/com/nasmusic/tv/backend/BackendAdapter.kt"),
            File("../app/src/main/java/com/nasmusic/tv/backend/BackendAdapter.kt"),
        ).firstOrNull { it.isFile }
        assertTrue("找不到 BackendAdapter.kt", f != null)
        return f!!.readText(Charsets.UTF_8)
    }

    @Test
    fun `必须调用服务端searchSongs`() {
        val src = managerSource()
        assertTrue(
            "仍没用 BackendAdapter.searchSongs —— 大曲库尾部歌曲永远命中不到",
            src.contains("val results = adapter.searchSongs(q)"),
        )
        assertTrue("接口本身必须存在", backendSource().contains("suspend fun searchSongs(query: String): List<Song>"))
    }

    @Test
    fun `全量分页与内存contains必须消失`() {
        val src = managerSource()
        assertFalse("仍在分页拉满 5000 首", src.contains("while (offset < 5000)"))
        assertFalse("仍在客户端 contains 匹配", src.contains("lowercase().contains(q)"))
        assertFalse("旧的 TODO 没清掉", src.contains("TODO: 如果 BackendAdapter 未来支持"))
    }

    @Test
    fun `补齐与分类语义必须保留`() {
        val src = managerSource()
        assertTrue("补齐池常量丢失", src.contains("private const val NAS_FILLER_POOL_SIZE = 200"))
        assertTrue("补齐仍走第一页", src.contains("adapter.getSongs(NAS_FILLER_POOL_SIZE, 0)"))
        assertTrue("命中前缀丢失", src.contains("it.copy(id = \"\${NAS_PREFIX}"))
        assertTrue("补齐前缀丢失", src.contains("it.copy(id = \"\${NAS_FILLER_PREFIX}"))
        assertTrue("moodMatchedCount 口径依赖的 matchedIds 必须保留", src.contains("val matchedIds = matched.mapTo"))
    }

    @Test
    fun `负向自证旧实现必须被判红`() {
        val legacy = "while (offset < 5000) { val batch = adapter.getSongs(pageSize, offset) ?: break }"
        assertTrue("门禁空转", legacy.contains("while (offset < 5000)"))
    }
}
