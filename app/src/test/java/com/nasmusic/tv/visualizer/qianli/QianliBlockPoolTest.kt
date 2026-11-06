package com.nasmusic.tv.visualizer.qianli

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E50 K6 分块缓冲门禁（G-POOL-1 ~ G-POOL-3）。
 *
 * 方案：`docs/archive/shan-shui-inf-porting-plan.md` §十 10.3。
 *
 * ## 判据清单
 *
 * | 判据 | 内容 | 阈值 |
 * |---|---|---|
 * | G-POOL-1 | 块宽 | `blockWidth ≥ 1.05 × 屏宽` |
 * | G-POOL-2 | 重生不阻塞绘制 | advance 后 pendingRegen=true，但 draw 仍画出**旧块**，⛔ 无空白 |
 * | G-POOL-3 | 预算耗尽降级 | 预算内做不完时仍不画空白（画出来仍有内容） |
 *
 * ## 负向自证规矩
 *
 * 每条判据都配一条「故意破坏真实值」的负向用例，且咬在自己的 tag 上：
 * 负向用例把**故意坏值**喂给**正向用例用的同一份断言** ⇒ 断言必须抛 AssertionError。
 * 绝不许通过放宽阈值让它变绿。
 *
 * ## 为什么用「记录型画布」而不是直接读画布像素
 *
 * Robolectric 的默认（LEGACY）图形模式**不光栅化** `Canvas` 的绘制
 * ⇒ 目标位图永远是全透明的（本仓 `QianliSealGlyphGateTest` 的落墨比因此实测为 0，
 * 它当前是红的 ⇒ 这是既有问题，不是本门禁引入的）。
 *
 * ⇒ 本门禁改为观测两件**确实可观测**的事，二者合起来就是「画出来不是空白」：
 * ① 池**实际 blit 了哪一张位图**（实例同一性 ⇒ 「旧块」还是「新块」）；
 * ② 那张位图**自身**有不透明像素（内容非空）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QianliBlockPoolTest {

    // ── 夹具 ──────────────────────────────────────────────────

    /** 1080p 基准屏宽。 */
    private val screenW = 1920

    /** 层带高（⛔ 不是全屏）。 */
    private val bandH = 300

    /**
     * 预热绘制次数上限（避免死循环）。
     *
     * 池每帧至少推进 1 个待重生块 ⇒ 凑齐 2 块最多 2~3 次绘制 ⇒ 该上限只作兜底。
     */
    private val MAX_WARMUP_DRAWS = 8

    /** 跑一段断言，断言它必须抛 AssertionError（负向自证的标准动作）。 */
    private fun gateMustCatch(what: String, block: () -> Unit) {
        var caught = false
        try {
            block()
        } catch (e: AssertionError) {
            caught = true
        }
        assertTrue(what, caught)
    }

    /** 造一个块池（块宽与池内公式一致）。 */
    private fun newPool(blocks: Int, gen: (Double) -> Bitmap): QianliBlockPool =
        QianliBlockPool(
            LayerSpec(id = 1, bandY0 = 0.0, bandY1 = 1.0, speed = 10.0, alphaFloor = 0.10),
            blocks,
            screenW,
            bandH,
            gen,
        )

    /** 块生成器：每次产出一张**纯色不透明**的位图，颜色随次序不同（便于分辨新旧块）。 */
    private class BlockGen(
        private val blockW: Int,
        private val blockH: Int,
        private val slowMillis: Long = 0,
    ) {
        /** 已生成的位图（按次序）。 */
        val made = mutableListOf<Bitmap>()

        private var counter = 0

        fun gen(x0: Double): Bitmap {
            if (slowMillis > 0) {
                try {
                    Thread.sleep(slowMillis)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            counter++
            val bmp = Bitmap.createBitmap(blockW, blockH, Bitmap.Config.ARGB_8888)
            val px = IntArray(blockW * blockH)
            val color = colorOf(counter)
            for (i in px.indices) px[i] = color
            bmp.setPixels(px, 0, blockW, 0, 0, blockW, blockH)
            made.add(bmp)
            return bmp
        }

        /** 第 n 块的颜色（⛔ 手工打包 ARGB：不依赖 android.graphics.Color 的桩实现）。 */
        private fun colorOf(n: Int): Int {
            val r = (n * 61) and 0xFF
            val g = (n * 113) and 0xFF
            val b = (n * 29) and 0xFF
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** 记录 blit 调用的画布（见类头：Robolectric LEGACY 模式不光栅化）。 */
    private class RecordingCanvas : Canvas() {
        /** 被 blit 过的位图（按次序）。 */
        val drawn = mutableListOf<Bitmap>()

        override fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
            drawn.add(bitmap)
            super.drawBitmap(bitmap, left, top, paint)
        }
    }

    /** 位图里不透明像素的个数（抽样 16×16 个点，够判「空白 / 非空白」）。 */
    private fun opaquePixelCount(bmp: Bitmap): Int {
        var n = 0
        val stepX = (bmp.width / 16).coerceAtLeast(1)
        val stepY = (bmp.height / 16).coerceAtLeast(1)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val px = bmp.getPixel(x, y)
                if ((px ushr 24) and 0xFF != 0) n++
                x += stepX
            }
            y += stepY
        }
        return n
    }

    // ══════════════════════ G-POOL-1 块宽 ══════════════════════

    /** G-POOL-1：`blockWidth ≥ 1.05 × 屏宽`（保证「覆盖整屏时另一块可在后台重生」）。 */
    @Test
    fun gPool1_blockWidthCoversScreen() {
        for (w in intArrayOf(1920, 1280)) {
            val blockW = (w * 1.05).toInt()
            val gen = BlockGen(blockW, bandH)
            val pool = QianliBlockPool(
                LayerSpec(1, 0.0, 1.0, 10.0, 0.10), 2, w, bandH, { x0 -> gen.gen(x0) },
            )
            assertBlockWidthCoversScreen(pool.blockWidth, w)
            pool.release()
        }
    }

    /** G-POOL-1 负向自证：块宽比屏窄 ⇒ 同一份断言必须判红。 */
    @Test
    fun gPool1_negative_narrowBlockBreaksGate() {
        gateMustCatch("块宽 1919 < 1.05×1920 必须被判据咬住") {
            assertBlockWidthCoversScreen(1919, 1920)
        }
    }

    private fun assertBlockWidthCoversScreen(blockWidth: Int, screenW: Int) {
        assertTrue(
            "块宽 $blockWidth 必须 ≥ 1.05 × 屏宽 $screenW = ${1.05 * screenW}",
            blockWidth >= 1.05 * screenW,
        )
    }

    // ══════════════════════ G-POOL-2 重生不阻塞绘制 ══════════════════════

    /**
     * G-POOL-2：advance 后 pendingRegen=true，但 draw 仍画出**旧块**（⛔ 无空白）。
     *
     * 场景：2 块、块宽 1.05×屏宽 ⇒ 滚过第 0 块后，第 1 块正好盖住整屏
     * ⇒ 那一帧画的必然是**旧块**，而刚重生的新块在屏幕右侧（不可见）。
     */
    @Test
    fun gPool2_pendingRegenStillDrawsTheOldBlock() {
        val blockW = (screenW * 1.05).toInt()
        val gen = BlockGen(blockW, bandH)
        val pool = newPool(2) { x0 -> gen.gen(x0) }

        val first = RecordingCanvas()
        pool.draw(first, 0.0, 1f)
        assertTrue("首帧必须 blit 出块", first.drawn.isNotEmpty())

        // 预热：池按挂钟预算限流（每帧最多 BLOCK_BUDGET_MS，判定在块间生效）
        // ⇒ 机器负载高时首帧只生成 1 块 ⇒ 不可假设「首帧一定生成 2 块」。
        // 这里反复 draw（offset 恒为 0 ⇒ 不触发 advance、不触发换入与 recycle）
        // 直到池里凑齐 2 块；生成次序恒为 ring 索引序 ⇒ made[1] 就是 ring 索引 1 那一块。
        val warmupCanvas = RecordingCanvas()
        var warmupDraws = 0
        while (gen.made.size < 2 && warmupDraws < MAX_WARMUP_DRAWS) {
            pool.draw(warmupCanvas, 0.0, 1f)
            warmupDraws++
        }
        if (gen.made.size < 2) {
            fail("块池未能在 $MAX_WARMUP_DRAWS 次绘制内生成 2 块（实测 ${gen.made.size} 块）")
            return
        }

        val oldBlock1 = gen.made[1]

        // 滚出第 0 块 ⇒ 标记重生（本仓的两段式：⛔ 不同步生成）
        val offset = pool.blockWidth + 1.0
        pool.advance(offset)
        assertTrue("advance 后必须 pendingRegen", pool.pendingRegen)

        val second = RecordingCanvas()
        pool.draw(second, offset, 1f)
        assertTrue("pendingRegen 期间 draw 仍必须 blit 出块", second.drawn.isNotEmpty())
        assertTrue(
            "必须画出**旧块**（第 1 块），而不是被换掉的新块",
            second.drawn.any { it === oldBlock1 },
        )
        assertTrue(
            "刚重生的新块在屏幕右侧，不该被画出来",
            second.drawn.none { it === gen.made[gen.made.size - 1] },
        )
        assertDrawnNotBlank(second.drawn)
        pool.release()
    }

    /** G-POOL-2 负向自证：画出来的是空白位图 ⇒ 同一份断言必须判红。 */
    @Test
    fun gPool2_negative_blankDrawBreaksGate() {
        val blank = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        gateMustCatch("空白位图必须被判据咬住") { assertDrawnNotBlank(listOf(blank)) }
        gateMustCatch("一次都没画也必须被判据咬住") { assertDrawnNotBlank(emptyList()) }
    }

    /**
     * 断言「画出来的东西不是空白」—— 至少画了一张位图，且它有不透明像素。
     */
    private fun assertDrawnNotBlank(drawn: List<Bitmap>) {
        assertTrue("必须至少画出一张位图", drawn.isNotEmpty())
        var maxOpaque = 0
        for (b in drawn) {
            if (b.isRecycled) continue
            maxOpaque = maxOf(maxOpaque, opaquePixelCount(b))
        }
        assertTrue("画出的位图必须有内容（⛔ 不许空白），实测不透明像素 $maxOpaque", maxOpaque > 0)
    }

    // ══════════════════════ G-POOL-3 预算耗尽降级 ══════════════════════

    /**
     * G-POOL-3：预算内做不完时**不画空白**（画出来仍有内容）。
     *
     * 场景：3 块、生成耗时 3× 预算 ⇒ 每帧预算只够做完**一块** ⇒ 剩下的块继续用旧位图。
     * ⇒ 画面上仍有内容，且 pendingRegen 保持为真（剩余工作下一帧继续）。
     *
     * ⚠️ 「先砍装饰」在**渲染器的烘焙层**（几何的植被/皴线），块池这一层没有装饰概念；
     *    池这一层能保证的是「⛔ 不画空白」⇒ 本判据只钉这一条。
     */
    @Test
    fun gPool3_budgetExhaustionStillDrawsContent() {
        val blockW = (screenW * 1.05).toInt()
        val gen = BlockGen(blockW, bandH, slowMillis = QianliBlockPool.BLOCK_BUDGET_MS * 3L)
        val pool = newPool(3) { x0 -> gen.gen(x0) }

        val canvas = RecordingCanvas()
        pool.draw(canvas, 0.0, 1f)

        assertTrue("预算耗尽时仍必须 blit 出块", canvas.drawn.isNotEmpty())
        assertDrawnNotBlank(canvas.drawn)
        assertTrue(
            "预算内做不完时 pendingRegen 必须保持为真（剩余工作下一帧继续）",
            pool.pendingRegen,
        )
        pool.release()
    }

    /** G-POOL-3 负向自证：预算耗尽时若画出空白 ⇒ 同一份断言必须判红。 */
    @Test
    fun gPool3_negative_blankOnBudgetExhaustionBreaksGate() {
        val blank = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        gateMustCatch("预算耗尽时画出的空白必须被判据咬住") {
            assertDrawnNotBlank(listOf(blank))
        }
    }

    /** 门槛自证：块池的预算常量必须存在（否则「预算耗尽」这条判据会空转）。 */
    @Test
    fun gPool3_budgetConstantIsPositive() {
        assertTrue(
            "每帧重生预算必须是正数（实测 ${QianliBlockPool.BLOCK_BUDGET_MS}）",
            QianliBlockPool.BLOCK_BUDGET_MS > 0,
        )
    }
}
