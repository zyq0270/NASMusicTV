package com.nasmusic.tv.backend.local

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CoverUrlPersistentCacheRaceTest {
    /**
     * F-D01 回归：保存期间到达的 put 必须让 `dirty` 保持为真，下一轮才会再落盘。
     *
     * ⚠️ 这里直接断言内部 `hasPendingWrites` 而不是再读一次磁盘：`File.renameTo` 在
     * Windows 上不覆盖已存在的目标文件，第二次保存会走「保留旧文件」分支，文件内容
     * 断言会变成平台相关（Linux CI 绿、本机红）。脏位语义才是这条缺陷的判据。
     */
    @Test
    fun `put arriving during save keeps dirty for next flush`(): Unit = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val cacheFile = File(app.filesDir, "cover_url_cache.json")
        cacheFile.delete()
        val cache = CoverUrlPersistentCache(app, { 0L })
        cache.debounceMs = 20L
        val injected = AtomicBoolean(false)
        val hookReturned = CountDownLatch(1)
        // 在 saveLocked() 之后、清 dirty 之前插入一条 put：这正是旧实现丢落盘的窗口。
        cache.afterSaveHook = {
            if (injected.compareAndSet(false, true)) {
                cache.putAlbumCover("race", "https://cdn/race.jpg")
                hookReturned.countDown()
            }
        }
        cache.putAlbumCover("first", "https://cdn/first.jpg")

        assertTrue("保存钩子未触发", hookReturned.await(3, TimeUnit.SECONDS))
        // 第二次落盘要等一个 debounce（20 ms），此刻读脏位不会与清位竞争。
        assertTrue("保存期间到达的写入被 dirty=false 吞掉", cache.hasPendingWrites)

        // 首次保存的内容已落盘；内存快照两条都在。
        withTimeout(3_000L) {
            while (!(cacheFile.exists() && cacheFile.readText().contains("first"))) delay(10L)
        }
        assertEquals("https://cdn/first.jpg", cache.getAlbumCover("first"))
        assertEquals("https://cdn/race.jpg", cache.getAlbumCover("race"))
        cache.afterSaveHook = null
        cache.close()
        cacheFile.delete()
    }

    @Test
    fun `rapid puts all persist after debounce`(): Unit = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val cacheFile = File(app.filesDir, "cover_url_cache.json")
        cacheFile.delete()
        val cache = CoverUrlPersistentCache(app, { 0L })
        cache.debounceMs = 30L
        repeat(8) { cache.putAlbumCover("a$it", "https://cdn/$it.jpg") }
        withTimeout(3_000L) {
            while (true) {
                val text = if (cacheFile.exists()) cacheFile.readText() else ""
                if ((0 until 8).all { text.contains("https://cdn/$it.jpg") }) break
                delay(20L)
            }
        }
        cache.close()
        cacheFile.delete()
    }
}
