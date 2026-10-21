package com.nasmusic.tv.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 曲库浏览卡片风格门禁（专辑块 = 艺术家块）。
 *
 * ## 为什么需要
 *
 * 同一页里艺术家块与专辑块曾是两份手抄参数：卡片内边距 12/8dp、网格间距 10/12dp、
 * 文字居中/左对齐、歌曲数量分别写在「名字下方正文行」和「封面右上角徽标」。
 * 用户一眼就看出"不像同一个页面的组件"。这类漂移靠肉眼逐组件检查是拦不住的
 * —— 每加一个块就重抄一遍参数块，必然再漂一次。
 *
 * ## 判定规则（唯一真源 + 唯一委托点）
 *
 * - 外壳参数块只写在 `MediaCardShell` 里（⛔ 卡片函数体内不许出现 shape/scale/anim 字面量）
 * - 封面右上角数量徽标只写在 `CoverCountBadge` 里（`library_song_count_short` 全包只出现一次）
 * - 几何取值只在 `BrowseTokens` 里
 * - A-Z 分组头只写在 `LetterGroupHeader` 里
 *
 * 负向自证用例在末尾：把违规写法塞进样本，断言必须判负。
 */
class LibraryBrowseCardStyleGateTest {

    private val browseDir = "screens/library/browse"

    // ─────────────────────────── 委托点存在性 ───────────────────────────

    @Test
    fun `四种块必须共用 MediaCardShell 与 CoverCountBadge 两个委托点`() {
        val components = browseSource("BrowseComponents.kt")
        assertTrue("缺 MediaCardShell —— 卡片外壳的委托点被删了？", components.contains("internal fun MediaCardShell"))
        assertTrue("缺 CoverCountBadge —— 封面数量徽标的委托点被删了？", components.contains("internal fun BoxScope.CoverCountBadge"))
        assertTrue("缺 LetterGroupHeader —— A-Z 分组头的委托点被删了？", components.contains("internal fun LetterGroupHeader"))
    }

    @Test
    fun `专辑卡与艺术家卡必须走同一个外壳并显示右上角数量徽标`() {
        val components = browseSource("BrowseComponents.kt")
        val album = functionBody(components, "internal fun AlbumCard")
        val artist = functionBody(components, "internal fun ArtistCard")
        for ((name, body) in listOf("AlbumCard" to album, "ArtistCard" to artist)) {
            assertTrue("$name 必须用 MediaCardShell（外壳参数块只有一份）", body.contains("MediaCardShell("))
            assertTrue("$name 必须用 CoverCountBadge 显示歌曲数（与另一种块同位置同档）", body.contains("CoverCountBadge("))
            assertTrue("$name 不该自己重写 FocusableSurface 参数块", !body.contains("FocusableSurface("))
            assertTrue("$name 的内边距必须走令牌", body.contains("padding(BrowseTokens.cardPadding)"))
            assertTrue("$name 的封面必须是方形 1:1 + 令牌圆角", body.contains("aspectRatio(1f)") && body.contains("clip(BrowseTokens.coverShape)"))
        }
    }

    /** 艺术家块此前是居中排版，专辑块是左对齐 —— 两种块并排时最刺眼的差异。 */
    @Test
    fun `两张卡片都不许用居中排版`() {
        val components = browseSource("BrowseComponents.kt")
        listOf("internal fun AlbumCard", "internal fun ArtistCard").forEach { signature ->
            val body = functionBody(components, signature)
            assertTrue(
                "${signature.substringAfter("fun ")} 里出现了 CenterHorizontally —— 文字排版又和另一种块分家了",
                !body.contains("CenterHorizontally")
            )
        }
    }

    // ─────────────────────────── 字面量禁令 ───────────────────────────

    @Test
    fun `卡片函数体内不得出现几何与动效字面量`() {
        val components = browseSource("BrowseComponents.kt")
        listOf("internal fun AlbumCard", "internal fun ArtistCard").forEach { signature ->
            val body = functionBody(components, signature)
            val forbidden = listOf("focusedScale", "animationDurationMs", "RoundedCornerShape", "containerColor")
                .filter { body.contains(it) }
            assertEquals("${signature.substringAfter("fun ")} 应只写内容、不写外壳参数", emptyList<String>(), forbidden)
            assertTrue(
                "${signature.substringAfter("fun ")} 里出现了 dp 字面量（取值必须走 BrowseTokens）",
                !Regex("""\d+(\.\d+)?\.dp""").containsMatchIn(body)
            )
        }
    }

    @Test
    fun `数量文案在浏览包里只能有一个出口`() {
        val occurrences = browseFiles().values.sumOf { text ->
            Regex("""library_song_count_short""").findAll(text).count()
        }
        assertTrue(
            "library_song_count_short 出现 $occurrences 次（应为 1 次，只在 CoverCountBadge 里）—— " +
                "专辑与艺术家的数量口径又分家了",
            occurrences == 1
        )
    }

    @Test
    fun `网格间距与分组头必须由令牌与委托点提供`() {
        listOf("AlbumGrid.kt", "ArtistList.kt", "GenreYearLists.kt").forEach { fileName ->
            val text = browseSource(fileName)
            val literalGaps = Regex("""spacedBy\(\s*\d+(\.\d+)?\.dp""").findAll(text).map { it.value }.toList()
            assertEquals("$fileName 的网格间距应走 BrowseTokens.gridGap", emptyList<String>(), literalGaps)
            val literalHeaderGaps = Regex("""padding\(bottom = \d+(\.\d+)?\.dp""").findAll(text).map { it.value }.toList()
            assertEquals("$fileName 的标题条间距应走 BrowseTokens.headerCountGap", emptyList<String>(), literalHeaderGaps)
        }
        assertTrue("AlbumGrid 未复用 LetterGroupHeader", browseSource("AlbumGrid.kt").contains("LetterGroupHeader("))
        assertTrue("ArtistList 未复用 LetterGroupHeader", browseSource("ArtistList.kt").contains("LetterGroupHeader("))
        // 分组头逐行重复的指纹：两份 dividerThickness
        listOf("AlbumGrid.kt", "ArtistList.kt").forEach { fileName ->
            assertTrue("$fileName 仍自己画分组头分隔线", !browseSource(fileName).contains("dividerThickness"))
        }
    }

    @Test
    fun `令牌取值必须有层级差且只在 BrowseTokens 出现`() {
        val tokens = browseSource("BrowseTokens.kt")
        assertTrue("cardShape 必须是 10.dp（外壳）", Regex("""cardShape\s*=\s*RoundedCornerShape\(10\.dp\)""").containsMatchIn(tokens))
        assertTrue("coverShape 必须是 8.dp（封面，比外壳更紧一档）", Regex("""coverShape\s*=\s*RoundedCornerShape\(8\.dp\)""").containsMatchIn(tokens))
        assertTrue("countBadgeShape 必须是 12.dp（度量胶囊）", Regex("""countBadgeShape\s*=\s*RoundedCornerShape\(12\.dp\)""").containsMatchIn(tokens))
        assertTrue("gridGap 必须是 12.dp（专辑与艺术家块同间距）", Regex("""gridGap\s*=\s*12\.dp""").containsMatchIn(tokens))
        assertTrue("focusScale 必须是 1.06f", Regex("""focusScale\s*=\s*1\.06f""").containsMatchIn(tokens))
    }

    // ─────────────────────────── 来源标签与颜色 ───────────────────────────

    @Test
    fun `来源标签必须走公共 SourceBadge 且角落徽标不用纯白色值`() {
        val components = browseSource("BrowseComponents.kt")
        val album = functionBody(components, "internal fun AlbumCard")
        assertTrue("专辑卡的来源标签必须复用 ui/components/common/SourceBadge", album.contains("SourceBadge("))
        assertTrue("浏览卡片里出现了裸 Color.White —— 实底徽标前景必须用 NasMusicColors.OnPrimary", !components.contains("Color.White"))
        val badge = functionBody(components, "internal fun BoxScope.CoverCountBadge")
        assertTrue("数量徽标前景必须用 OnPrimary 令牌", badge.contains("NasMusicColors.OnPrimary"))
    }

    @Test
    fun `分组头字号必须走档位而不是写死 sp`() {
        val header = functionBody(browseSource("BrowseComponents.kt"), "internal fun LetterGroupHeader")
        assertTrue("分组头必须用 FontSize 档位（TV 端要随大屏字号放大）", header.contains("FontSize.button()"))
        assertTrue("分组头里出现了 sp 字面量", !Regex("""\d+(\.\d+)?\.sp""").containsMatchIn(header))
    }

    // ─────────────────────────── 骨架屏与真数据同形 ───────────────────────────

    @Test
    fun `艺术家骨架必须与专辑骨架同形`() {
        val shimmer = uiSource("components/Shimmer.kt")
        val artistSkeleton = functionBody(shimmer, "fun ArtistSkeletonGrid")
        assertTrue("ArtistSkeletonGrid 必须复用 AlbumSkeletonGrid（两种块同结构后骨架必然同形）", artistSkeleton.contains("AlbumSkeletonGrid()"))
        assertTrue("骨架里残留圆形头像 —— 艺术家块已改成方形封面", !shimmer.contains("CircleShape"))
    }

    // ─────────────────────────── 负向自证（护栏有效性） ───────────────────────────

    @Test
    fun `负向：卡片里重写外壳参数时必须判负`() {
        val sabotaged = """
            internal fun ArtistCard() {
                FocusableSurface(onClick = {}, shape = RoundedCornerShape(12.dp), focusedScale = 1.08f, animationDurationMs = 250) {
                    Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally)
                }
            }
        """.trimIndent() + "\n}\n"
        val body = functionBody(sabotaged, "internal fun ArtistCard")
        assertTrue("护栏失效：重写外壳参数却没被抓到", body.contains("FocusableSurface(") && body.contains("focusedScale"))
        assertTrue("护栏失效：居中排版却没被抓到", body.contains("CenterHorizontally"))
        assertTrue("护栏失效：dp 字面量却没被抓到", Regex("""\d+(\.\d+)?\.dp""").containsMatchIn(body))
    }

    @Test
    fun `负向：数量文案各写一份时必须判负`() {
        val twoCopies = "CoverCountBadge: stringResource(R.string.library_song_count_short, a)\nAlbumCard: stringResource(R.string.library_song_count_short, b)"
        assertEquals("两处出口必须被判为违规", 2, Regex("""library_song_count_short""").findAll(twoCopies).count())
    }

    @Test
    fun `负向：函数体提取失败时断言不能空转`() {
        val withoutCards = "package x\n"
        try {
            functionBody(withoutCards, "internal fun AlbumCard")
            fail("函数缺失时 functionBody 必须抛异常，否则上面所有卡片断言都会空转通过")
        } catch (e: AssertionError) {
            assertTrue(e.message!!.contains("AlbumCard"))
        }
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private fun browseFiles(): Map<String, String> =
        listOf("BrowseComponents.kt", "BrowseTokens.kt", "AlbumGrid.kt", "ArtistList.kt", "GenreYearLists.kt")
            .associateWith { browseSource(it) }

    private fun browseSource(fileName: String): String = read(uiSourceRoot().resolve("$browseDir/$fileName"), fileName)

    private fun uiSource(fileName: String): String = read(uiSourceRoot().resolve(fileName), fileName)

    private fun read(file: File, fileName: String): String {
        assertTrue("找不到 $fileName（user.dir=${File("").absolutePath}）", file.isFile)
        // 仓库里源文件是 CRLF，统一成 LF 才能按行定位函数体
        return file.readText().replace("\r\n", "\n")
    }

    private fun uiSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/ui"),
            File("app/src/main/java/com/nasmusic/tv/ui"),
            File("../app/src/main/java/com/nasmusic/tv/ui"),
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "找不到 UI 源码目录（user.dir=${File("").absolutePath}）；已试：${candidates.joinToString { it.absolutePath }}",
            found != null
        )
        return found!!
    }

    /** 取函数体（签名到第一个顶格 `}`）；签名缺失即抛，避免断言空转。 */
    private fun functionBody(source: String, signature: String): String {
        assertTrue("源码里找不到 $signature —— 组件被改名或删除？", source.contains(signature))
        val after = source.substringAfter(signature)
        val end = after.indexOf("\n}\n")
        assertTrue("$signature 的函数体定位失败（缩进或右括号形态变了）", end > 0)
        return after.substring(0, end)
    }
}
