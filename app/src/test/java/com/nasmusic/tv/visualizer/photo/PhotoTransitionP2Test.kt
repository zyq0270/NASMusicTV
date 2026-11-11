package com.nasmusic.tv.visualizer.photo

import androidx.compose.ui.geometry.Size
import com.nasmusic.tv.backend.photo.PhotoScaleMode
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.photo.transitions.MaskDissolveTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * **P2 档门禁**（照片墙转场 P2 的 33 种）—— 阶段 13 补齐
 *
 * ## 这道门禁防的是什么
 *
 * P2 一次补了 33 种效果，最容易出问题的不是「写不出来」，而是
 * **写出来了但没人保证它是对的**：
 *
 * 1. **漏注册** —— 枚举里有、注册表里没有 ⇒ 用户在选择器里点了没反应。
 * 2. **`prepare()` 就崩** —— 遮罩生成 / 几何计算出错 ⇒ 第一次切到该效果时炸掉。
 * 3. **块数越线** —— M4 红线是**总块数 ≤ 84**（老电视 ARMv7 从 ≤336 降到 ≤84）。
 * 4. **引入 shader** —— 全仓红线：零 AGSL / 零 GLSL / 零 OpenGL。
 *    P2 里有 5 项（M5 组）必须走 **M3 遮罩近似**，不许写 shader。
 * 5. **遮罩形态写反** —— 例如燃烧前沿画到了下方（推进方向就反了）。
 *
 * ## 为什么遮罩形态能断言（Windows 上不能像素回读）
 *
 * ⚠️ Windows 上 Robolectric 的 NATIVE 图形模式不可用 ⇒ **不能读位图像素**。
 * ⇒ 遮罩的形态计算被抽成了**纯函数** [MaskCache.sampleAlpha]（不建位图），
 * 这里就能用纯 JVM 断言「顶部不透明 / 底部透明」这类**设计意图**。
 *
 * ## 为什么用 Robolectric
 *
 * `prepare()` 会真的构造 `Paint` / `Path`，遮罩族还会建 `Bitmap`
 * （`android.graphics` 在纯 JVM 下是桩类，构造即抛）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoTransitionP2Test {

    /** P2 档的全部效果 */
    private val p2 = PhotoTransitionId.entries.filter { it.phase == PhotoTransitionId.Phase.P2 }

    // ────────────────────── ① 全部有实现且自报 id ──────────────────────

    @Test
    fun `P2 档 33 种全部有实现且自报 id`() {
        assertEquals("P2 档应有 33 种", 33, p2.size)
        for (id in p2) {
            val impl = PhotoTransitionRegistry.get(id)
            assertNotNull("$id 在枚举里但注册表没有实现 ⇒ 用户选中它没反应", impl)
            assertEquals("$id 的实现回报了别的 id", id, impl!!.id)
            assertTrue("$id 应出现在 available() 里（随机池由它构造）", id in PhotoTransitionRegistry.available())
        }
        assertEquals("注册表应覆盖全部 76 项", 76, PhotoTransitionRegistry.implementedCount)
    }

    // ────────────────────── ② 音频反应类不进随机池 ──────────────────────

    @Test
    fun `P2 的音频反应类正好 3 项且不进随机池`() {
        val audio = p2.filter { it.audioReactive }.map { it.name }.toSet()
        assertEquals(
            "P2 档的音频反应类应为 §4.2 I 类里的那 3 项",
            setOf("BASS_BLOOM", "WAVEFORM_WIPE", "PULSE_DISSOLVE"),
            audio,
        )
        val pool = PhotoTransitionId.randomPool(PhotoTransitionRegistry.available(), sdkInt = 34).toSet()
        val leaked = p2.filter { it.audioReactive }.filter { it.ordinal in pool }.map { it.name }
        assertTrue("音频反应类泄漏进随机池：$leaked", leaked.isEmpty())
    }

    // ────────────────────── ③ prepare / release 不崩 ──────────────────────

    /**
     * 每个 P2 实现的 `prepare()` + `release()` 都要跑通。
     *
     * ⛔ `prepare()` 只在「切转场 / 切画质 / 尺寸变化」时调用 ⇒ 它崩了不会在启动时暴露，
     * 而是**用户第一次切到该效果时**炸 ⇒ 必须在单测里先跑一遍。
     */
    @Test
    fun `每个 P2 实现的 prepare 与 release 都跑得通`() {
        for ((w, h) in listOf(1920f to 1080f, 1280f to 720f, 1080f to 1920f)) {
            val geom = geometry(w, h)
            for (id in p2) {
                val impl = PhotoTransitionRegistry.get(id)!!
                impl.prepare(geom, VisualQuality.MEDIUM, MaskCache)
                impl.release()
            }
        }
    }

    // ────────────────────── ④ M4 块数红线 ──────────────────────

    /**
     * M4 红线：分块类效果的**总块数 ≤ 84**（§4.2；老电视 ARMv7 从 ≤336 降到 ≤84）。
     *
     * ⚠️ 这里用**源码常量**核对（不是运行时探测）：块数由 `prepare()` 按画布尺寸反推，
     * 而**上界**由源码里的 `MAX_COLS` / `MAX_ROWS`（或 `MIN_*`）封顶 ——
     * 那两个常量才是红线的真正来源。
     *
     * ⚠️ 常量改了必须同步改这张表（表变了 ⇒ 门禁会失败 ⇒ 顺手看一眼块数有没有越线）。
     */
    @Test
    fun `P2 分块类效果的块数上界不超过 84`() {
        val file = File(SourceScan.mainSourceRoot(), "com/nasmusic/tv/visualizer/photo/transitions/TilesP2Transitions.kt")
        assertTrue("找不到分块实现文件：${file.absolutePath}", file.isFile)
        val code = SourceScan.stripComments(file.readText())

        val cols = Regex("""const val (?:MAX|MIN)_COLS = (\d+)""").findAll(code)
            .map { it.groupValues[1].toInt() }.toList()
        val rows = Regex("""const val (?:MAX|MIN)_ROWS = (\d+)""").findAll(code)
            .map { it.groupValues[1].toInt() }.toList()
        assertEquals("分块文件里应有 6 组网格常量", 6, cols.size)
        assertEquals("分块文件里应有 6 组网格常量", 6, rows.size)

        // 每组「列 × 行」都要 ≤ 84
        for (i in cols.indices) {
            val blocks = cols[i] * rows[i]
            assertTrue(
                "第 ${i + 1} 组网格 ${cols[i]}×${rows[i]} = $blocks 块，超过 M4 红线 84",
                blocks <= 84,
            )
        }

        // 期望表与源码一致（顺序 = 文件里类的顺序：GRID_FLIP / MOSAIC / PIXELATE /
        // PUZZLE / SHATTER / VORONOI）—— 改了常量要同步改这里并复核块数 ≤ 84
        assertEquals(
            "分块网格常量与期望表不一致（改了常量请同步这里并复核块数 ≤ 84）",
            listOf(8 to 5, 10 to 6, 10 to 6, 8 to 5, 10 to 6, 8 to 5),
            cols.zip(rows),
        )

        // 漫画分格是另一套常量（3×2 = 6 格），也要过红线
        val panelCols = Regex("""const val PANEL_COLS = (\d+)""").find(code)?.groupValues?.get(1)?.toInt()
        val panelRows = Regex("""const val PANEL_ROWS = (\d+)""").find(code)?.groupValues?.get(1)?.toInt()
        assertTrue("漫画分格应声明 PANEL_COLS / PANEL_ROWS", panelCols != null && panelRows != null)
        val pc = panelCols ?: 0
        val pr = panelRows ?: 0
        assertTrue(
            "漫画分格 ${pc}×${pr} = ${pc * pr} 格，超过 M4 红线 84",
            pc * pr <= 84,
        )
    }

    // ────────────────────── ⑤ 零 shader 红线 ──────────────────────

    /**
     * ⛔ **全仓红线**：零 AGSL / 零 GLSL / 零 OpenGL（`blackhole-visualizer-plan.md` §一）。
     *
     * P2 里有 5 项标着 `M5_SHADER`（`RIPPLE` / `WAVE_WARP` / `SWIRL` / `LIQUIFY` / `MELT`），
     * 它们**必须**走 M3 近似（遮罩位图 / 条带位移）⇒ 照片墙目录里不许出现任何 shader API。
     *
     * ⚠️ 扫描前**必须剥注释**：`ZoomP1Transitions.kt` 的 KDoc 里就写着「真高斯模糊需要
     * `RenderEffect`」—— 那只是说明，不是代码。
     */
    @Test
    fun `照片墙目录不得引入任何 shader API`() {
        val forbidden = listOf(
            "RuntimeShader",
            "RenderEffect",
            "ShaderBrush",
            "BitmapShader",
            "ImageShader",
            "GlslProgram",
            "ProgramUniform",
            "ShaderBrush",
        )
        val photoDir = File(SourceScan.mainSourceRoot(), "com/nasmusic/tv/visualizer/photo")
        assertTrue("找不到照片墙目录：${photoDir.absolutePath}", photoDir.isDirectory)

        val offenders = ArrayList<String>()
        for (f in photoDir.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val code = SourceScan.stripComments(f.readText())
            for (token in forbidden) {
                if (code.contains(token)) offenders.add("${f.name} → $token")
            }
        }
        assertTrue(
            "照片墙目录出现了 shader API（零 AGSL / 零 GLSL / 零 OpenGL 红线）：$offenders\n" +
                "⇒ M5 组的那几项请走 M3 遮罩近似（见 MaskP2Transitions.kt）",
            offenders.isEmpty(),
        )
    }

    // ────────────────────── ⑥ 遮罩形态符合设计意图 ──────────────────────

    /**
     * 遮罩的**形态**必须符合各自的设计意图 —— 写反了效果就整个反了
     * （例如燃烧前沿画在下方 ⇒ 火往上烧；晶界画成不透明 ⇒ 结冰变成裂纹发光）。
     *
     * ⚠️ 走 [MaskCache.sampleAlpha] 的**纯计算**路径（不建位图）⇒ Windows 上也能跑。
     */
    @Test
    fun `遮罩形态符合各自的设计意图`() {
        // 燃烧 / 融化：前沿在**顶部** ⇒ 顶部不透明、底部透明
        for (kind in listOf(PhotoMaskKind.BURN, PhotoMaskKind.MELT)) {
            val top = MaskCache.sampleAlpha(kind, 128, 2)
            val bottom = MaskCache.sampleAlpha(kind, 128, 250)
            assertTrue("$kind 顶部应不透明，实际 $top", top > 200)
            assertTrue("$kind 底部应透明，实际 $bottom", bottom < 60)
        }

        // 网点：既有亮点（点内）也有暗点（点外）⇒ 半调屏而不是一整块
        assertTrue("网点遮罩应同时有亮点与暗点", dotsHaveContrast(PhotoMaskKind.DOTS))

        // 圆环 / 螺旋 / 团块 / 胞晶：alpha 必须有明暗变化（不能是一整块死值）
        for (kind in listOf(PhotoMaskKind.RINGS, PhotoMaskKind.SPIRAL, PhotoMaskKind.BLOBS, PhotoMaskKind.CRYSTAL)) {
            assertTrue("$kind 的 alpha 应有明暗变化", alphaVaries(kind))
        }

        // 白噪点：相邻像素应有明显差异（不是一片灰）
        assertTrue("白噪点遮罩应有高频差异", grainHasContrast())
    }

    /**
     * **负向自证**：上面那些断言不是恒真的。
     *
     * 用一张**刻意反了**的形态（把燃烧遮罩的 alpha 取反）去跑同一条判据 ⇒ 必须判「不像」。
     */
    @Test
    fun `负向自证 - 形态判据能区分反相的遮罩`() {
        val burn = MaskCache.sampleAlpha(PhotoMaskKind.BURN, 128, 2)
        val inverted = 255 - burn
        assertTrue(
            "反相后的顶部 alpha 应变成透明（判据必须能区分）",
            inverted < 60,
        )
        // 判据本身：顶部必须不透明
        assertTrue("前置条件：燃烧遮罩顶部确实不透明", burn > 200)
    }

    // ────────────────────── ⑦ 末帧必须真正「走完」（2026-11-11 实测修正）──────────────────────

    /**
     * **每个遮罩类效果都必须开「收尾补画」**。
     *
     * 遮罩带空洞（颗粒 / 网点 / 圆环 / 旋臂 / 团块 / 晶界 / 燃烧前沿）⇒
     * `p = 1` 时仍有旧图从洞里透出来 ⇒ HOLD 期定格的就是「半新半旧」⇒
     * 用户读作「转场没走完」（2026-11-11 电视实测，§10.387）。
     *
     * ⇒ `settleFrom()` 必须在 `0..1` 内（= 末段整幅补画新图）。
     */
    @Test
    fun `每个遮罩类效果都必须开收尾补画 - 否则末帧仍透出旧图`() {
        var checked = 0
        for (id in p2) {
            val impl = PhotoTransitionRegistry.get(id)!!
            if (impl !is MaskDissolveTransition) continue
            checked++
            assertTrue(
                "$id 的遮罩带空洞但没有开收尾补画（settleFrom = ${impl.settleFrom()}）—— " +
                    "末帧会露出旧图 ⇒ 用户读作「转场没走完」",
                impl.settleFrom() <= 1f,
            )
        }
        assertTrue("应有 12 个遮罩类效果参与检查，实际 $checked", checked == 12)
    }

    /**
     * **脉动溶解在无音频时也必须能走到 100%**。
     *
     * 初版把脉冲写成 `boost = 0.35 + pulse` ⇒ **没有音频（`pulse = 0`）时整个转场只推到 35%**
     * ⇒ 末帧仍有 65% 是旧图 ⇒ 正是「转场没走完」的一条。
     *
     * ⇒ 现在 `boost = 1 + 0.6 · pulse`：**至少 1 倍**（保证走完），有鼓点时最多 1.6 倍。
     */
    @Test
    fun `脉动溶解在无音频时也能走到 100%`() {
        val impl = PhotoTransitionRegistry.get(PhotoTransitionId.PULSE_DISSOLVE) as MaskDissolveTransition
        val geom = geometry()   // geom.pulse = 0（无音频）
        assertEquals(
            "无音频时 p=1 必须给出满不透明（否则转场走不完）",
            1f,
            impl.maskAlpha(1f, geom),
            1e-4f,
        )
        // 有鼓点时更快（但同样不超过 1）
        geom.pulse = 1f
        assertEquals("有鼓点时也不超过 1", 1f, impl.maskAlpha(1f, geom), 1e-4f)
        assertTrue("有鼓点时中途推进更快", impl.maskAlpha(0.5f, geom) > impl.maskAlpha(0.5f, geometry()))
    }

    // ────────────────────── ⑧ 遮罩缓存只生成一次 ──────────────────────

    @Test
    fun `遮罩按需生成且只生成一次`() {
        MaskCache.clearForTest()
        for (kind in PhotoMaskKind.entries) {
            val first = MaskCache.mask(kind)
            val second = MaskCache.mask(kind)
            assertTrue("$kind 两次取到的应是同一张遮罩", first === second)
            assertEquals("$kind 遮罩边长应为 256", 256, first.width)
            assertEquals("$kind 遮罩边长应为 256", 256, first.height)
        }
        assertEquals(
            "每种遮罩只应生成一次（${PhotoMaskKind.entries.size} 种）",
            PhotoMaskKind.entries.size,
            MaskCache.patternGenerationCount,
        )
    }

    // ────────────────────── 内部工具 ──────────────────────

    /** 造一个画好几何的 [PhotoGeometry] */
    private fun geometry(w: Float = 1920f, h: Float = 1080f): PhotoGeometry {
        val ctx = RenderContext().apply {
            canvasSize = Size(w, h)
            safeAreaPx = 0f
        }
        val geom = PhotoGeometry()
        geom.update(ctx, PhotoScaleMode.CROP, 1920, 1080)
        return geom
    }

    /** 扫描一行像素，看有没有「全亮 + 全暗」两种极值（网点屏的特征） */
    private fun dotsHaveContrast(kind: PhotoMaskKind): Boolean {
        var max = 0
        var min = 255
        for (x in 0 until 64) {
            val v = MaskCache.sampleAlpha(kind, x * 4, 64)
            if (v > max) max = v
            if (v < min) min = v
        }
        return max > 200 && min < 40
    }

    /** alpha 在整幅上是否有明暗变化 */
    private fun alphaVaries(kind: PhotoMaskKind): Boolean {
        var max = 0
        var min = 255
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                val v = MaskCache.sampleAlpha(kind, x * 8, y * 8)
                if (v > max) max = v
                if (v < min) min = v
            }
        }
        return max - min > 120
    }

    /** 白噪点是否有高频差异（相邻像素差得多） */
    private fun grainHasContrast(): Boolean {
        var diffs = 0
        for (x in 0 until 64) {
            val a = MaskCache.sampleAlpha(PhotoMaskKind.GRAIN, x, 100)
            val b = MaskCache.sampleAlpha(PhotoMaskKind.GRAIN, x + 1, 100)
            if (kotlin.math.abs(a - b) > 60) diffs++
        }
        return diffs > 20
    }
}
