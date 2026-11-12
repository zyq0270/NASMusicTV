package com.nasmusic.tv.player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **门禁：ExoPlayer / MediaSession 的 player 对象只能在主线程访问**（2026-11-12 真机日志实证）
 *
 * ## 它防的是什么
 *
 * 真机日志页（设置 → 日志）里反复出现：
 * ```
 * 11-12 21:17:43  W  PlaybackService
 * loadArtwork failed: Player is accessed on the wrong thread.
 * Current thread: 'DefaultDispatcher-worker-2'
 * Expected thread: 'main'
 * ```
 *
 * ⇒ 根因：`PlaybackService.loadArtworkForCurrentItem()` 在 `serviceScope.launch(Dispatchers.IO)`
 *   里读 `mediaLibrarySession?.player?.currentMediaItem` —— Media3 对 player 的**线程归属**
 *   有断言，跨线程访问直接抛 `IllegalStateException`（封面加载整体失败，通知没有新封面）。
 *
 * ⇒ 修法：IO 线程只负责把图拉下来；「是否仍是同一首」的校验与 `cachedArtworkBitmap` 的写入
 *   全部用 `withContext(Dispatchers.Main)` 包起来。
 *
 * ## 两条判据
 *
 * ① **任何 IO 协程块内不许访问 player**（`Dispatchers.IO` / `Dispatchers.Default`）——
 *    块内若确实需要读 player，必须显式 `withContext(Dispatchers.Main)` 包住；
 * ② **`PlayerManager` 内不许出现后台调度器** —— 它的 player 访问必须留在调用线程
 *    （即 UI 主线程）；一旦有人在里面加 `launch(Dispatchers.IO)` 并顺手套 player，
 *    判据 ① 覆盖不到（PlayerManager 的方法是在**调用方**的线程上跑的）。
 */
class PlayerThreadAccessGateTest {

    @Test
    fun `IO 协程块内不许访问 player`() {
        val offenders = mutableListOf<String>()
        playerHoldingSources().forEach { file ->
            // ⚠️ 必须用**剔除注释后**的代码：注释里会写「player 只能在主线程访问」这类说明，
            //   不剔注释的话门禁会把自己写的说明当成违规（自己告自己）。
            val code = stripComments(file.readText(Charsets.UTF_8))
            ioBlocks(code).forEach { block ->
                // 块内若显式切回主线程，那部分访问是合法的 ⇒ 先剥掉再判定
                val rest = stripMainBlocks(block)
                if (mentionsPlayer(rest)) {
                    offenders += "${file.name}: IO 协程块里访问了 player → " +
                        rest.trim().lineSequence().firstOrNull { mentionsPlayer(it) }?.trim()
                }
            }
        }
        assertTrue(
            "player 对象只能在主线程访问（Media3 断言：Player is accessed on the wrong thread）。" +
                "IO 协程里要读 player，必须 withContext(Dispatchers.Main) 包住：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `PlayerManager 内不许出现后台调度器`() {
        val code = stripComments(
            File(settingsRoot(), "player/PlayerManager.kt").readText(Charsets.UTF_8)
        )
        val forbidden = listOf(
            "Dispatchers.IO" to "IO",
            "Dispatchers.Default" to "Default",
            "Dispatchers.Unconfined" to "Unconfined",
        )
        forbidden.forEach { (token, name) ->
            assertTrue(
                "PlayerManager 里不许用 $name 调度器：它的 player 访问必须留在调用线程（UI 主线程）" +
                    "（后台调度 + 碰 player = Media3 线程断言）。",
                !code.contains(token),
            )
        }
    }

    /** 负向自证：IO 块里访问 player 必须被判正（防止上面两条空转）。 */
    @Test
    fun `负向自证 IO 块里访问 player 必须判正`() {
        val bad = """
            artworkLoadJob = serviceScope.launch(Dispatchers.IO) {
                val mediaItem = mediaLibrarySession?.player?.currentMediaItem
            }
        """.trimIndent()
        val good = """
            artworkLoadJob = serviceScope.launch(Dispatchers.IO) {
                val bitmap = coil.execute(request)
                withContext(Dispatchers.Main) {
                    mediaLibrarySession?.player?.currentMediaItem?.mediaId
                }
            }
        """.trimIndent()
        assertTrue("护栏空转：IO 块里访问 player 的样例被误判通过", mentionsPlayer(stripMainBlocks(bad)))
        assertTrue(
            "护栏空转：已切回主线程的样例被误判",
            !mentionsPlayer(stripMainBlocks(good)),
        )
    }

    // ─────────────────── 工具 ───────────────────

    /**
     * 代码里是否出现了 `player` 这个**独立的词**。
     *
     * 用 `\bplayer\b` 而不是 `player.`：这样 `mediaLibrarySession?.player`、
     * `session.player.currentMediaItem`、`player.isPlaying` 全都命中，
     * 而 `playerManager` / `playerListener` / `servicePlayer` 都不命中（词边界）。
     */
    private fun mentionsPlayer(code: String): Boolean =
        Regex("""\bplayer\b""").containsMatchIn(code)

    /** 取出所有 `launch(Dispatchers.IO)` / `withContext(Dispatchers.IO)` 的块体。 */
    private fun ioBlocks(code: String): List<String> {
        val result = mutableListOf<String>()
        val regex = Regex("""(?:launch|withContext)\(\s*Dispatchers\.(?:IO|Default)\s*\)""")
        var match = regex.find(code)
        while (match != null) {
            val open = code.indexOf('{', match.range.last + 1)
            if (open >= 0) {
                val body = blockAt(code, open)
                if (body != null) result += body
            }
            match = regex.find(code, match.range.last + 1)
        }
        return result
    }

    /** 剥掉块内 `withContext(Dispatchers.Main)` / `launch(Dispatchers.Main)` 的块体。 */
    private fun stripMainBlocks(code: String): String {
        var result = code
        val regex = Regex("""(?:launch|withContext)\(\s*Dispatchers\.Main\s*\)""")
        var match = regex.find(result)
        while (match != null) {
            val open = result.indexOf('{', match.range.last + 1)
            if (open < 0) break
            val body = blockAt(result, open)
            if (body == null) break
            result = result.replace(body, " ")
            match = regex.find(result, match.range.last + 1)
        }
        return result
    }

    /** 从 `{` 起取配对的大括号块体（不含最外层括号）。 */
    private fun blockAt(code: String, open: Int): String? {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
            i++
        }
        return null
    }

    /** 剔除注释（门禁只看**真实代码**）。 */
    private fun stripComments(code: String): String {
        val out = StringBuilder(code.length)
        var i = 0
        while (i < code.length) {
            val c = code[i]
            val next = if (i + 1 < code.length) code[i + 1] else ' '
            when {
                c == '/' && next == '*' -> {
                    val end = code.indexOf("*/", i + 2)
                    i = if (end < 0) code.length else end + 2
                    out.append(' ')
                }
                c == '/' && next == '/' -> {
                    val end = code.indexOf('\n', i)
                    i = if (end < 0) code.length else end
                    out.append(' ')
                }
                c == '"' && code.startsWith("\"\"\"", i) -> {
                    val end = code.indexOf("\"\"\"", i + 3)
                    i = if (end < 0) code.length else end + 3
                    out.append(" \"\"\" ")
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    out.append(c)
                    i++
                    while (i < code.length) {
                        val s = code[i]
                        out.append(s)
                        i++
                        if (s == '\\' && i < code.length) {
                            out.append(code[i])
                            i++
                        } else if (s == quote) {
                            break
                        }
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * **持有 player 引用**的那些源文件（只有它们才可能触发 Media3 的线程断言）。
     *
     * ⚠️ 为什么不全仓扫：源码里 `player` 这个词本身是**普通名词**（"播放器"），
     * 全仓 48 个文件、243 处命中（`playerListener` / `playerManager` / `player` 形参 / 注释…），
     * 全仓扫会被淹在误报里 ⇒ 门禁被稀释、真正的违规反而看不见。
     *
     * ⇒ 判据收窄为「**确实持有 player 引用**」：文件里出现
     * `ExoPlayer` / `MediaController` / `mediaLibrarySession` 三者之一。
     * 能触发 `Player is accessed on the wrong thread` 的代码，必然持有这三者之一 ⇒ 不漏。
     */
    private fun playerHoldingSources(): List<File> {
        val holding = mutableListOf<File>()
        mainSources().forEach { file ->
            val code = stripComments(file.readText(Charsets.UTF_8))
            if (holdsPlayerRef(code)) holding += file
        }
        assertTrue(
            "扫描源文件列表为空（路径解析失效？）",
            holding.isNotEmpty(),
        )
        return holding
    }

    /** 代码里是否持有 player 引用。 */
    private fun holdsPlayerRef(code: String): Boolean =
        code.contains("ExoPlayer") ||
            code.contains("MediaController") ||
            code.contains("mediaLibrarySession")

    /** 主源码目录下的全部 Kotlin 文件（跳过测试目录）。 */
    private fun mainSources(): List<File> {
        val root = File(settingsRoot())
        assertTrue("找不到主源码目录：${root.absolutePath}", root.isDirectory)
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filterNot { it.absolutePath.contains(File.separator + "test" + File.separator) }
            .toList()
    }

    private fun settingsRoot(): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),
            File("app/src/main/java/com/nasmusic/tv"),
            File("../app/src/main/java/com/nasmusic/tv"),
        )
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "找不到源码目录（已尝试：${candidates.joinToString { it.absolutePath }}）",
            root != null,
        )
        return root!!.absolutePath
    }
}
