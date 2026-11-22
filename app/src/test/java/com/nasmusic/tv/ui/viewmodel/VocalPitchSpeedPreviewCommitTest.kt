package com.nasmusic.tv.ui.viewmodel

import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.data.prefs.AppPreferences
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * K 歌升降调 / 变速：**会话内临时效果**。
 *
 * 所有者裁定「退出 K 歌即恢复原调原速、不记忆」——preview / commit / reset 一律只改
 * 播放器与 VM 状态、**不写 DataStore**；退出 K 歌页无条件复位为 0 / 1.0；冷启动强制
 * 原调原速，不从存储恢复历史值。
 *
 * ⚠️ 协程调度：reset / exitKaraoke 走 `viewModelScope`（`Dispatchers.Main`），Robolectric
 * 的主线程 Looper 在 `runBlocking` 期间不会自己转 ⇒ 必须
 * `Dispatchers.setMain(UnconfinedTestDispatcher())`，否则在途协程永远醒不过来。
 * 存储读取一律用挂起的 `first()`，不经 fire-and-forget。
 *
 * ⚠️ 存储实例：本测试里的 prefs 用独占临时目录构造（原因见 [setUp]），既用于验证
 * 「不写盘」确实成立，也避免撞 datastore 的 `There are multiple DataStores active for
 * the same file`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NasMusicApp::class)
class VocalPitchSpeedPreviewCommitTest {

    private val app: NasMusicApp get() = ApplicationProvider.getApplicationContext()

    /** 本用例独占的存储实例（独占 DataStore 文件，见 [setUp]） */
    private lateinit var prefs: AppPreferences

    /** 本用例独占的 filesDir；DataStore 的实例数检查是按**文件路径**算的 */
    private lateinit var prefsDir: File

    private fun vm() = VocalSeparationViewModel(app, app.playerManager, prefs)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // 每条用例一个独立临时目录，用它包一层 ContextWrapper 喂给 internal 构造器
        // （AppPreferences.kt:117 直接读 `context.filesDir`，不经 applicationContext）。
        // 两个坑都实测踩过：① 进程单例的文件属于第一个 sandbox 的 dataDir，写落不到当前
        // 用例；② 直接 AppPreferences(app) 与 onCreate 建的单例开在同一文件，撞多实例检查。
        prefsDir = File(System.getProperty("java.io.tmpdir"), "nasmusictv-prefs-${System.nanoTime()}")
        prefsDir.mkdirs()
        val isolatedContext = object : android.content.ContextWrapper(app) {
            override fun getFilesDir(): File = prefsDir
        }
        prefs = AppPreferences(isolatedContext)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        Dispatchers.resetMain()
        prefsDir.deleteRecursively()
    }

    @Test
    fun `preview 改状态但不写盘`() = runBlocking {
        val viewModel = vm()
        assertEquals("初始持久化应为原调", 0, prefs.player.pitchSemitones.first())

        viewModel.previewPitch(3)

        assertEquals("preview 必须立即更新状态（按钮标签与弹窗档位跟着变）", 3, viewModel.pitchSemitones.value)
        delay(200)
        assertEquals("preview 绝不能写 DataStore", 0, prefs.player.pitchSemitones.first())
    }

    @Test
    fun `commit 只确认当前值、不写持久化`() = runBlocking {
        // 回归：旧实现在这里 viewModelScope.launch { setPitchSemitones(value) }，
        // 与「退出即复位、不记忆」相悖；新语义下 commit 只是对试听值的明确确认。
        val viewModel = vm()

        viewModel.previewPitch(-5)
        assertEquals(-5, viewModel.pitchSemitones.value)
        viewModel.commitPitch()
        delay(200)

        assertEquals(-5, viewModel.pitchSemitones.value)
        assertEquals("确认操作也不得写盘", 0, prefs.player.pitchSemitones.first())
    }

    @Test
    fun `速度 preview 与 commit 同样不写盘`() = runBlocking {
        val viewModel = vm()

        viewModel.previewSpeed(0.8)
        assertEquals(0.8, viewModel.playbackSpeed.value, 0.0)
        delay(200)
        assertEquals("preview 不写盘", 1.0, prefs.player.playbackSpeed.first(), 0.0)

        viewModel.commitSpeed()
        delay(200)
        assertEquals("确认操作也不得写盘", 1.0, prefs.player.playbackSpeed.first(), 0.0)
        assertEquals(0.8, viewModel.playbackSpeed.value, 0.0)
    }

    @Test
    fun `preview 越界值一律夹到合法区间`() {
        val viewModel = vm()

        viewModel.previewPitch(20)
        assertEquals("+20 必须夹到 +12", 12, viewModel.pitchSemitones.value)
        viewModel.previewPitch(-99)
        assertEquals("-99 必须夹到 -12", -12, viewModel.pitchSemitones.value)

        viewModel.previewSpeed(5.0)
        assertEquals("5.0 倍必须夹到 2.0", 2.0, viewModel.playbackSpeed.value, 0.0)
        viewModel.previewSpeed(0.1)
        assertEquals("0.1 倍必须夹到 0.5", 0.5, viewModel.playbackSpeed.value, 0.0)
    }

    @Test
    fun `冷启动 VM 初值恒为原调原速`() = runBlocking {
        // 老版本可能把调/速写进过存储；即便有残留，新 VM 也必须从 0 / 1.0 起步。
        prefs.player.setPitchSemitones(3)
        prefs.player.setPlaybackSpeed(0.8)
        assertEquals(3, prefs.player.pitchSemitones.first())

        val viewModel = vm()

        assertEquals("VM 不得从存储恢复升降调", 0, viewModel.pitchSemitones.value)
        assertEquals("VM 不得从存储恢复变速", 1.0, viewModel.playbackSpeed.value, 0.0)
    }

    @Test
    fun `退出 K 歌页必须复位原调原速且不写盘`() = runBlocking {
        val viewModel = vm()
        viewModel.previewPitch(3)
        viewModel.commitPitch()
        viewModel.previewSpeed(0.8)
        viewModel.commitSpeed()
        assertEquals(3, viewModel.pitchSemitones.value)
        assertEquals(0.8, viewModel.playbackSpeed.value, 0.0)

        viewModel.exitKaraoke()

        assertEquals("退出必须恢复原调", 0, viewModel.pitchSemitones.value)
        assertEquals("退出必须恢复原速", 1.0, viewModel.playbackSpeed.value, 0.0)
        assertEquals("退出不得把 0 写进存储", 0, prefs.player.pitchSemitones.first())
        assertEquals("退出不得把 1.0 写进存储", 1.0, prefs.player.playbackSpeed.first(), 0.0)
    }

    @Test
    fun `本案的存储实例必须真的可写可读`() = runBlocking {
        // 上面若干「不写盘」断言的前提：若实例坏了（IO 异常 / DataStore 多实例），
        // 写会带着真实异常红掉，而不是让「不写盘」假绿。
        prefs.player.setPitchSemitones(-7)
        assertEquals(-7, prefs.player.pitchSemitones.first())

        prefs.player.setPlaybackSpeed(1.25)
        assertEquals(1.25, prefs.player.playbackSpeed.first(), 0.0)
    }
}
