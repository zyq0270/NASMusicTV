package com.nasmusic.tv.ui.components

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.nasmusic.tv.visualizer.photo.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **门禁：歌词「当前行」必须跟得上播放进度**（2026-11-12 电视实测回归修复）
 *
 * ## 它防的是什么
 *
 * 真机反馈：**所有展示歌词的页面上，逐字模式歌词能正确走，逐行模式下歌词不动、
 * 不会自动跳当前行**。
 *
 * ⇒ 根因（M36 / `de2f233` 引入）：
 * `LyricsView` 把「当前行索引」改成 `derivedStateOf { … }` 之后，
 * 派生块的 lambda **只在 remember 的 key 变化时重建一次**，
 * 它捕获的是**首次组合时**那份**普通参数** `currentTimeMs`
 * ⇒ 之后每秒更新的播放进度**传不进去** ⇒ 派生值永不重算 ⇒ 当前行永远停在第一行。
 * 逐字模式之所以正常，是因为它读的是 `lyricTickMs`（**State** ⇒ 可观察）。
 *
 * ⚠️ 这与本仓 2026-09-25 审查修复 #16 是**同一类坑**（`SearchTab` / `SongList`）：
 * 「remember 无 key 时 derivedStateOf 捕获首次组合的参数快照」。
 *
 * ## 三条断言
 *
 * ① **机制复现（纯 Kotlin 建模，可执行）**：把 Compose 的
 *    「`remember` 保留**第一次**的派生块」+「lambda 捕获**第一次**那个 val 参数槽」
 *    建模出来，证明「读普通参数 ⇒ 参数更新不生效；读 State ⇒ 生效」。
 * ② **结构门禁**：`LyricsView` 里当前行索引的派生块**不许**读普通参数
 *    `currentTimeMs`，**必须**读可观察的 `lyricTickMs`。
 * ③ **负向自证**：同一谓词喂反例必须判负（防门禁空转）。
 */
class LyricsLineFollowGateTest {

    // ─────────────────── ① 机制复现（纯 Kotlin 建模） ───────────────────

    /**
     * 精确建模 Compose 的两个语义：
     *
     * 1. `remember(key…) { derivedStateOf { … } }`：**key 不变 ⇒ 派生块不重建**，
     *    后续「重组」仍然用**第一次**那个块；
     * 2. 派生块的 lambda 捕获的是**第一次调用**的 `val` 参数槽
     *    ⇒ 后续轮次传进来的同名参数是**另一个槽**，块里看不到。
     */
    private class RememberedDerived {
        private var derived: (() -> Long)? = null

        /** 模拟一次组合：`param` 是本轮的普通参数，`clock` 是可观察的 State */
        fun compose(param: Long, clock: MutableState<Long>): () -> Long {
            derived?.let { return it }
            // ⛔ 捕获的是**本轮**的 `param`（函数的 val 参数）——
            //    后续轮次传进来的 param 是另一个槽，派生块看不到。
            val created: () -> Long = { param }
            derived = created
            return created
        }
    }

    @Test
    fun `派生块读普通参数时参数更新不生效 读 State 才跟随`() {
        val clock = mutableStateOf(0L)
        val remembered = RememberedDerived()

        val fromParam = remembered.compose(param = 0L, clock = clock)
        val fromState: () -> Long = { clock.value }

        assertEquals(0L, fromParam())
        assertEquals(0L, fromState())

        // 「第二次组合」：播放进度推进到 100ms ⇒ 新的一轮参数是新槽
        val param2 = 100L
        clock.value = param2

        assertEquals(
            "读**普通参数**的派生块不会跟随参数更新 —— 这就是逐行模式歌词不动的机制",
            0L,
            fromParam(),
        )
        assertEquals(
            "读 **State** 的派生块会跟随 ⇒ 修复后必须走这条路",
            param2,
            fromState(),
        )
    }

    // ─────────────────── ② 结构门禁：源码里不许读普通参数 ───────────────────

    @Test
    fun `当前行索引的派生块必须读可观察的时间源`() {
        val code = stripComments(mainSource("com/nasmusic/tv/ui/components/LyricsView.kt"))

        val body = currentLineDerivedBody(code)
        assertTrue("取不到 LyricsView 里「当前行索引」的派生块", body.isNotEmpty())

        assertTrue(
            "当前行索引的派生块必须读 `lyricTickMs`（State ⇒ 可观察）",
            body.contains("lyricTickMs"),
        )
        assertFalse(
            "当前行索引的派生块**不许**读 `currentTimeMs`（普通参数 ⇒ 捕获首次组合的旧值，" +
                "逐行模式永远不跳行；真机 2026-11-12 反馈）",
            body.contains("currentTimeMs"),
        )
    }

    @Test
    fun `负向自证 读普通参数的派生块必须判负`() {
        val sample = """
            val currentIndex by remember(lyrics, wordByWordActive) {
                derivedStateOf {
                    val tick = if (wordByWordActive) lyricTickMs else currentTimeMs
                    findCurrentLineIndex(lyrics.lines, tick)
                }
            }
        """.trimIndent()
        assertTrue(
            "护栏空转：读普通参数的样例被误判通过",
            readsPlainTimeParam(sample),
        )
    }

    // ─────────────────── 工具 ───────────────────

    /**
     * 判定「当前行索引的派生块里是否读了普通时间参数」。
     *
     * ⛔ 只看**含 `findCurrentLineIndex` 的那个** `derivedStateOf` 块：
     * 同一文件里还有 `seekTargetLineState` 的派生块（拖拽跳转指示），
     * 它与当前行无关，不能拿它当判据。
     * 而 `LaunchedEffect` 的 else 分支**合法地**读 `currentTimeMs`
     * （那是「把播放进度写进 State」的地方，必须保留）⇒ 也不能拿全文当判据。
     */
    private fun readsPlainTimeParam(code: String): Boolean =
        currentLineDerivedBody(code).contains("currentTimeMs")

    /** 取出「当前行索引」那个 `derivedStateOf { … }` 的块体（大括号配对） */
    private fun currentLineDerivedBody(code: String): String {
        var idx = 0
        while (true) {
            val start = code.indexOf("derivedStateOf", idx)
            if (start < 0) return ""
            val open = code.indexOf('{', start)
            if (open < 0) return ""
            var depth = 0
            var i = open
            var end = code.length
            while (i < code.length) {
                when (code[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            end = i
                            break
                        }
                    }
                }
                i++
            }
            val body = code.substring(open + 1, end)
            if (body.contains("findCurrentLineIndex")) return body
            idx = end + 1
        }
    }

    private fun mainSource(relPath: String): String {
        val file = File(SourceScan.mainSourceRoot(), relPath)
        assertTrue("找不到源文件：${file.absolutePath}", file.isFile)
        return file.readText(Charsets.UTF_8)
    }

    private fun stripComments(src: String): String = SourceScan.stripComments(src)
}
