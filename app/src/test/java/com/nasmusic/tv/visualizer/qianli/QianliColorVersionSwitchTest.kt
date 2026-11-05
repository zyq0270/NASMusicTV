package com.nasmusic.tv.visualizer.qianli

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.data.prefs.QianliColorVersion
import com.nasmusic.tv.ui.components.nextQianliColorVersion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * E50「千里江山」**三版设色运行时切换**门禁（V-A / V-B / V-C）。
 *
 * ## 它守什么
 *
 * | # | 内容 |
 * |---|---|
 * | C1 | 色版循环：上键 `0→1→2→0`、下键 `2→1→0→2`（纯函数） |
 * | C2 | **只对 `QIANLI_SCROLL` 生效**：其它主题上下一律 `null`（= 不切换） |
 * | C3 | 持久化往返：写进去读出来是同一个值；未设置时默认 `2`（V-C） |
 * | C4 | 非法值（< 0 或 > 2）**回落默认 2**（读侧与写侧各挡一道） |
 * | C5 | 接线在位（源码扫描）：`RenderContext.qianliColorVersion` 存在且默认 2；`VisualizerStage` 真的写它 |
 *
 * ## 为什么要有 C5（源码扫描）
 * 「用户按了上下键 ⇒ 渲染器读到的值真的变了」这一段跨三个文件
 * （`RenderContext` 字段 / `VisualizerStage` 的按键与手势 / `QianliScrollRenderer` 的读取），
 * 单测覆盖不到「接线还在不在」—— 少一行 `renderCtx.qianliColorVersion = next`，
 * 编译照样通过、单测照样全绿，只有真机按遥控器才知道没反应。
 * ⇒ 用源码扫描把三段钉住（同仓 `LofiCityManualSwitchGateTest` / `HighwayEnvManualSwitchGateTest` 的既有口径）。
 *
 * ## 负向自证
 * 每条正向断言都配一条**喂坏值**的用例：把坏值喂给**正向用例用的同一份断言**
 * ⇒ 必须抛 `AssertionError`（否则这条断言就是「怎么喂都过」的空壳）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QianliColorVersionSwitchTest {

    private lateinit var prefs: AppPreferences

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = AppPreferences(context)
    }

    /** datastore 在 Windows + Robolectric 下连续快速写同名文件有 rename 竞态 ⇒ 写之间让出句柄。 */
    private suspend fun settle() = delay(30)

    // ══════════════════════════════════════════════════════════════════════
    // 正向断言（负向自证复用**同一份**断言，只换喂进去的坏值）
    // ══════════════════════════════════════════════════════════════════════

    /** 从 V_A 起连按上键：期望依次到达 [expected]。 */
    private fun assertUpCycle(vararg expected: Int) {
        var cur = QianliColorVersion.V_A
        expected.forEach { e ->
            val got = QianliColorVersion.next(cur)
            assertEquals("上键循环：$cur 之后应为 $e，实际 $got", e, got)
            cur = got
        }
    }

    /** 从 V_C 起连按下键：期望依次到达 [expected]。 */
    private fun assertDownCycle(vararg expected: Int) {
        var cur = QianliColorVersion.V_C
        expected.forEach { e ->
            val got = QianliColorVersion.prev(cur)
            assertEquals("下键循环：$cur 之前应为 $e，实际 $got", e, got)
            cur = got
        }
    }

    /** 主题守卫：非「千里江山」必须返回 null；「千里江山」必须返回 [expected]。 */
    private fun assertThemeGuard(theme: VisualizerTheme, expected: Int?) {
        assertEquals(
            "主题 $theme 的上键结果",
            expected,
            nextQianliColorVersion(theme, QianliColorVersion.V_A, +1),
        )
    }

    /** 持久化读回：[expected] 与磁盘上的值必须一致。 */
    private suspend fun assertPersisted(expected: Int) {
        assertEquals("持久化读回", expected, prefs.qianliColorVersion.first())
    }

    // ══════════════════════════════════════════════════════════════════════
    // C1 色版循环
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `上键切下一个色版 0到1到2到0`() {
        assertUpCycle(1, 2, 0)
    }

    @Test
    fun `下键切上一个色版 2到1到0到2`() {
        assertDownCycle(1, 0, 2)
    }

    @Test
    fun `默认版是 V-C 且序号与 QianliColorTable 一致`() {
        assertEquals("默认版必须是 V-C（= 改造前编译期固定的那一版）", 2, QianliColorVersion.DEFAULT)
        assertEquals("V_A", 0, QianliColorVersion.V_A)
        assertEquals("V_B", 1, QianliColorVersion.V_B)
        assertEquals("V_C", 2, QianliColorVersion.V_C)
    }

    // ══════════════════════════════════════════════════════════════════════
    // C2 只对 QIANLI_SCROLL 生效
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `千里江山主题下上下键都切色版`() {
        assertEquals("上键：V_A → V_B", 1, nextQianliColorVersion(VisualizerTheme.QIANLI_SCROLL, 0, +1))
        assertEquals("下键：V_C → V_B", 1, nextQianliColorVersion(VisualizerTheme.QIANLI_SCROLL, 2, -1))
    }

    @Test
    fun `其它主题一律不切色版`() {
        VisualizerTheme.entries
            .filter { it != VisualizerTheme.QIANLI_SCROLL }
            .forEach { t ->
                assertNull("非千里江山主题（$t）上键不该切色版", nextQianliColorVersion(t, 0, +1))
                assertNull("非千里江山主题（$t）下键不该切色版", nextQianliColorVersion(t, 2, -1))
            }
    }

    // ══════════════════════════════════════════════════════════════════════
    // C3 持久化往返
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `未设置时默认为 V-C`() = runBlocking {
        assertPersisted(QianliColorVersion.DEFAULT)
    }

    @Test
    fun `设色版写读往返一致`() = runBlocking {
        prefs.setQianliColorVersion(QianliColorVersion.V_B); settle()
        assertPersisted(QianliColorVersion.V_B)

        prefs.setQianliColorVersion(QianliColorVersion.V_A); settle()
        assertPersisted(QianliColorVersion.V_A)

        prefs.setQianliColorVersion(QianliColorVersion.V_C); settle()
        assertPersisted(QianliColorVersion.V_C)
    }

    @Test
    fun `门面 VisualizerPrefs 读写同一份值`() = runBlocking {
        prefs.visualizer.setQianliColorVersion(QianliColorVersion.V_B); settle()
        assertPersisted(QianliColorVersion.V_B)
        assertEquals("门面读到的必须是同一个值", QianliColorVersion.V_B, prefs.visualizer.qianliColorVersion.first())
    }

    // ══════════════════════════════════════════════════════════════════════
    // C4 非法值回落默认 2
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `写入非法值时写侧就换成默认版`() = runBlocking {
        prefs.setQianliColorVersion(-1); settle()
        assertPersisted(QianliColorVersion.DEFAULT)

        prefs.setQianliColorVersion(7); settle()
        assertPersisted(QianliColorVersion.DEFAULT)
    }

    @Test
    fun `读出非法值时读侧回落默认版`() {
        listOf(-1, -100, 3, 7, Int.MAX_VALUE, Int.MIN_VALUE).forEach { bad ->
            assertEquals("非法值 $bad 必须回落默认版", QianliColorVersion.DEFAULT, QianliColorVersion.normalize(bad))
        }
        listOf(0, 1, 2).forEach { ok ->
            assertEquals("合法值 $ok 必须原样保留", ok, QianliColorVersion.normalize(ok))
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // C5 接线在位（源码扫描）
    // ══════════════════════════════════════════════════════════════════════

    private fun readMain(rel: String): String {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        val dir = candidates.first { it.isDirectory }
        val f = File(dir, rel)
        assertTrue("找不到源码：$rel（工作目录 ${File("").absolutePath}）", f.isFile)
        return f.readText().replace("\r\n", "\n")
    }

    @Test
    fun `RenderContext 有 qianliColorVersion 且默认 2`() {
        val src = readMain("java/com/nasmusic/tv/visualizer/RenderContext.kt")
        assertTrue(
            "RenderContext 必须有 qianliColorVersion 字段（渲染器每帧读它）",
            Regex("""var\s+qianliColorVersion\s*:\s*Int\s*=\s*2""").containsMatchIn(src),
        )
    }

    @Test
    fun `舞台真的把选择写进 RenderContext`() {
        val src = readMain("java/com/nasmusic/tv/ui/components/VisualizerStage.kt")
        assertTrue(
            "舞台必须写 renderCtx.qianliColorVersion（否则按了没反应）",
            src.contains("renderCtx.qianliColorVersion ="),
        )
        assertTrue(
            "舞台必须把设色版持久化（否则重启 App 就丢）",
            src.contains("setQianliColorVersion("),
        )
        assertTrue(
            "上下键必须被 QIANLI_SCROLL 主题守卫（其它效果不许被牵连）",
            src.contains("VisualizerTheme.QIANLI_SCROLL"),
        )
        assertTrue(
            "必须有竖向滑动手势（手机上下滑切设色版）",
            src.contains("detectVerticalDragGestures("),
        )
        assertTrue(
            "竖滑阈值必须与横滑一致（80dp）",
            Regex("""80\.dp\.toPx\(\)""").containsMatchIn(src),
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // 负向自证：坏值喂给**同一份**正向断言 ⇒ 必须抛 AssertionError
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `负向自证 每条正向断言都咬得住坏值`() {
        // ⚠️ JUnit4 要求测试方法返回 `void` ⇒ 这里用块式 `runBlocking`
        //    （写成 `= runBlocking { … }` 时，最后一个表达式是 `assertThrows` 的返回值
        //    ⇒ 方法签名会变成返回 `AssertionError` ⇒ 整个类被判 "Invalid test class"）。
        runBlocking {
            // ① 色版循环：把「V_C 之后回到 V_A」喂成「回到 V_B」必须报
            assertThrows(AssertionError::class.java) { assertUpCycle(1, 2, 1) }
            //    下键同理：把「V_A 之后回到 V_C」喂成「回到 V_B」必须报
            assertThrows(AssertionError::class.java) { assertDownCycle(1, 0, 1) }

            // ② 主题守卫：把「非千里江山返回 null」喂成「也切」必须报
            assertThrows(AssertionError::class.java) {
                assertThemeGuard(VisualizerTheme.CIRCULAR_RING, 1)
            }

            // ③ 持久化往返：磁盘上是 V_B，断言 V_C ⇒ 必须报
            // ⚠️ `assertThrows` 的 lambda **不是 suspend 上下文** ⇒ 挂起调用要自己开一个
            //    `runBlocking`（这里只在测试里用，代价可忽略）。
            prefs.setQianliColorVersion(QianliColorVersion.V_B); settle()
            assertThrows(AssertionError::class.java) {
                runBlocking { assertPersisted(QianliColorVersion.V_C) }
            }
            //    非法值写进去后落盘的应是默认版，断言 V_B ⇒ 必须报
            prefs.setQianliColorVersion(-1); settle()
            assertThrows(AssertionError::class.java) {
                runBlocking { assertPersisted(QianliColorVersion.V_B) }
            }

            // ④ 非法值回落 / 合法值保留：把**同一份断言**喂上相反的取值必须报
            //    （正向断言「非法值 → 默认版」喂合法值；正向断言「合法值 → 原样保留」喂非法值）
            assertThrows(AssertionError::class.java) {
                assertEquals(
                    "非法值必须回落默认版",
                    QianliColorVersion.DEFAULT,
                    QianliColorVersion.normalize(1),
                )
            }
            assertThrows(AssertionError::class.java) {
                assertEquals("合法值必须原样保留", 1, QianliColorVersion.normalize(-1))
            }
        }
    }
}
