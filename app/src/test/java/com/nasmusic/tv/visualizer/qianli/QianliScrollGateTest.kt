package com.nasmusic.tv.visualizer.qianli

import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.renderers.QianliScrollRenderer
import com.nasmusic.tv.visualizer.renderers.QianliScrollRenderer.GateLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * E50 K6 渲染器骨架门禁（G-SCROLL-1 ~ G-SCROLL-5）。
 *
 * 方案：`docs/shan-shui-inf-porting-plan.md` §十 10.2。
 *
 * ## 判据清单
 *
 * | 判据 | 内容 | 口径 |
 * |---|---|---|
 * | G-SCROLL-1 | 同层同速 | L2 与 L2′ 的 offset 逐位恒等（跑 60 帧逐步断言） |
 * | G-SCROLL-2 | 通道隔离 | 源码扫描：出现 frame.spectrum 或 .spectrum[ 即失败 |
 * | G-SCROLL-3 | α 下限 | 静音帧：L1 雾 ≥0.10 / L2′ 光晕 ≥0.10 / L4 水 ≥0.16 / L5 尘 ≥0.05；L0、L2、L3 山体 ≡1.0 |
 * | G-SCROLL-4 | 速度上限 | v_layer(eff) ≤ v_layer(base) × 1.30 |
 * | G-SCROLL-5 | 印章图层序 | 印章画在 grade 之后（drawContent 内的调用先后） |
 *
 * ## 负向自证规矩
 *
 * 每条判据都配一条「故意破坏真实值」的负向用例，且咬在自己的 tag 上：
 * 负向用例把**故意坏值**喂给**正向用例用的同一份断言** ⇒ 断言必须抛 AssertionError。
 * 绝不许通过放宽阈值让它变绿。
 *
 * ## 为什么需要 Robolectric
 *
 * [QianliScrollRenderer] 的构造会创建 `android.graphics.Paint` / `Path` ⇒ 纯 JVM 下是桩类。
 *
 * ## 门禁缝
 *
 * G-SCROLL-1 / 3 / 4 需要观测渲染器内部状态 ⇒ 渲染器里开了**只读的门禁缝**
 * （`gateFrame` / `layerOffsetForGate` / `layerAlphaForGate` / `layerSpeedForGate`），
 * 它们走的是与 `drawContent` **同一段代码**，⛔ 不改变任何渲染行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QianliScrollGateTest {

    // ── 夹具 ──────────────────────────────────────────────────

    /** 每帧时长（秒）—— 60fps。 */
    private val dt = 1.0 / 60.0

    /** 判据用的音频帧（静音帧：所有分析通道保持 0）。 */
    private fun silentFrame(): AudioFrame = AudioFrame(64, 128)

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

    // ══════════════════════ G-SCROLL-1 同层同速 ══════════════════════

    /**
     * G-SCROLL-1：L2 与 L2′ 的 offset **恒等**（逐帧断言，60 帧）。
     *
     * ⚠️ 逐位相等（容差 0）：L2′ 是 L2 的轮廓光，两者共用同一个世界 ⇒
     * 偏移只要差一个 ULP，轮廓光就会脱离山脊。
     */
    @Test
    fun gScroll1_l2AndGlintShareTheSameOffset() {
        val r = QianliScrollRenderer()
        val frame = silentFrame()
        for (i in 0 until 60) {
            frame.energy = if (i % 3 == 0) 0f else 1f
            r.gateFrame(frame, dt)
            assertSameOffset(
                "第 $i 帧：L2 与 L2′ 的 offset 必须恒等",
                r.layerOffsetForGate(GateLayer.MID),
                r.layerOffsetForGate(GateLayer.GLINT),
            )
        }
    }

    /** G-SCROLL-1 负向自证：偏移漂移 1e-9 ⇒ 同一份断言必须判红。 */
    @Test
    fun gScroll1_negative_offsetDriftBreaksSameSpeedGate() {
        val r = QianliScrollRenderer()
        val frame = silentFrame()
        r.gateFrame(frame, dt)
        val mid = r.layerOffsetForGate(GateLayer.MID)
        val glint = r.layerOffsetForGate(GateLayer.GLINT)
        val broken = glint + 1e-9
        gateMustCatch("L2′ 偏移漂移 1e-9 ⇒ 同层同速判据必须判红") {
            assertSameOffset("负样本", mid, broken)
        }
    }

    private fun assertSameOffset(what: String, mid: Double, glint: Double) {
        assertEquals("$what（实测 $mid / $glint）", mid, glint, 0.0)
    }

    // ══════════════════════ G-SCROLL-2 通道隔离 ══════════════════════

    /**
     * G-SCROLL-2：渲染器源码里**不得**出现 `frame.spectrum` / `.spectrum[`。
     *
     * ⚠️ 判据跑在**已剥注释**的源码上（文档里提到 spectrum 不算违规）。
     */
    @Test
    fun gScroll2_rendererNeverTouchesSpectrumChannel() {
        val src = strippedRenderer()
        assertFalse("渲染器不得读 frame.spectrum（显示通道）", spectrumUse.containsMatchIn(src))
        assertFalse("渲染器不得读 .spectrum[", spectrumIndex.containsMatchIn(src))
    }

    /** G-SCROLL-2 负向自证：把 `frame.spectrum` 塞进源码 ⇒ 同一份判据必须判红。 */
    @Test
    fun gScroll2_negative_injectedSpectrumBreaksGate() {
        val src = strippedRenderer()
        val injected = src.replace(
            "val c = drawContext.canvas.nativeCanvas",
            "val spectrum = frame.spectrum\n        val c = drawContext.canvas.nativeCanvas",
        )
        assertTrue("负样本必须真的被改过（否则这条自证空转）", injected != src)
        assertTrue(
            "负样本：出现 frame.spectrum ⇒ 同一份判据必须判红",
            spectrumUse.containsMatchIn(injected),
        )
        val injectedIndex = src.replace(
            "val c = drawContext.canvas.nativeCanvas",
            "val bar = frame.spectrum[0]\n        val c = drawContext.canvas.nativeCanvas",
        )
        assertTrue(
            "负样本：出现 .spectrum[ ⇒ 同一份判据必须判红",
            spectrumIndex.containsMatchIn(injectedIndex),
        )
    }

    private val spectrumUse = Regex("""frame\s*\.\s*spectrum""")
    private val spectrumIndex = Regex("""\.\s*spectrum\s*\[""")

    // ══════════════════════ G-SCROLL-3 α 下限 ══════════════════════

    /**
     * G-SCROLL-3：静音帧下各层的 α 下限（方案 §5.5 α 下限表）。
     *
     * L1 远景雾 ≥0.10 / L2′ 轮廓光晕 ≥0.10 / L4 江水 ≥0.16 / L5 光尘 ≥0.05；
     * L0 绢底天幕、L2 中景山体、L3 近景山体 ≡ 1.0。
     */
    @Test
    fun gScroll3_silentFrameAlphaFloors() {
        val r = QianliScrollRenderer()
        val frame = silentFrame()
        r.gateFrame(frame, dt)

        assertAlphaFloor("L1 远景雾", r.layerAlphaForGate(GateLayer.FAR), 0.10f)
        assertAlphaFloor("L2′ 轮廓光晕", r.layerAlphaForGate(GateLayer.GLINT), 0.10f)
        assertAlphaFloor("L4 江水", r.layerAlphaForGate(GateLayer.WATER), 0.16f)
        assertAlphaFloor("L5 光尘", r.layerAlphaForGate(GateLayer.DUST), 0.05f)

        assertEquals("L0 绢底天幕 α 必须 ≡ 1.0", 1f, r.layerAlphaForGate(GateLayer.SKY), 0f)
        assertEquals("L2 中景山体 α 必须 ≡ 1.0", 1f, r.layerAlphaForGate(GateLayer.MID), 0f)
        assertEquals("L3 近景山体 α 必须 ≡ 1.0", 1f, r.layerAlphaForGate(GateLayer.NEAR), 0f)
    }

    /** G-SCROLL-3 负向自证：α 掉到下限以下 ⇒ 同一份断言必须判红。 */
    @Test
    fun gScroll3_negative_alphaBelowFloorBreaksGate() {
        gateMustCatch("L1 雾 α 0.05 必须被判据咬住") {
            assertAlphaFloor("L1 远景雾", 0.05f, 0.10f)
        }
        gateMustCatch("L4 水 α 0.10 必须被判据咬住") {
            assertAlphaFloor("L4 江水", 0.10f, 0.16f)
        }
        gateMustCatch("L5 尘 α 0.01 必须被判据咬住") {
            assertAlphaFloor("L5 光尘", 0.01f, 0.05f)
        }
    }

    private fun assertAlphaFloor(name: String, actual: Float, floor: Float) {
        assertTrue("$name 的 α $actual 必须 ≥ $floor", actual >= floor)
    }

    // ══════════════════════ G-SCROLL-4 速度上限 ══════════════════════

    /**
     * G-SCROLL-4：`v_layer(eff) ≤ v_layer(base) × 1.30`（层速度随 energy 调制后仍不破上限）。
     *
     * 逐层取样：L1 远景 / L2 中景 / L2′ 轮廓光 / L3 近景 / L4 江水 / L4′ 高光。
     */
    @Test
    fun gScroll4_effectiveSpeedNeverExceeds130Percent() {
        val r = QianliScrollRenderer()
        val frame = silentFrame()
        for (energy in listOf(0f, 0.25f, 0.5f, 1f)) {
            frame.energy = energy
            val layers = listOf(
                GateLayer.FAR, GateLayer.MID, GateLayer.GLINT,
                GateLayer.NEAR, GateLayer.WATER, GateLayer.WATER_HI,
            )
            for (layer in layers) {
                val before = r.layerOffsetForGate(layer)
                r.gateFrame(frame, dt)
                val after = r.layerOffsetForGate(layer)
                val vEff = (after - before) / dt
                val vBase = r.layerSpeedForGate(layer)
                assertSpeedWithinCap("层 $layer", vEff, vBase, 1.30)
            }
        }
    }

    /**
     * G-SCROLL-4 负向自证：energy 超出契约（>1）⇒ 调制系数突破 1.30 ⇒ 同一份断言必须判红。
     *
     * ⚠️ 必须先跑够多帧：energy 走平滑器（attack 0.10），单帧只到 0.2 ⇒ 调制系数才 1.06。
     */
    @Test
    fun gScroll4_negative_energyAboveContractBreaksSpeedCap() {
        val r = QianliScrollRenderer()
        val frame = silentFrame()
        frame.energy = 2f
        for (i in 0 until 300) r.gateFrame(frame, dt)

        val before = r.layerOffsetForGate(GateLayer.MID)
        r.gateFrame(frame, dt)
        val after = r.layerOffsetForGate(GateLayer.MID)
        val vEff = (after - before) / dt
        val vBase = r.layerSpeedForGate(GateLayer.MID)
        gateMustCatch("energy=2 时有效速度 $vEff 破上限 ⇒ 同一份断言必须判红") {
            assertSpeedWithinCap("层 2", vEff, vBase, 1.30)
        }
    }

    private fun assertSpeedWithinCap(name: String, vEff: Double, vBase: Double, cap: Double) {
        val limit = vBase * cap
        // ⚠️ 相对容差 1e-9：只吸收 double 的舍入（1 ULP），⛔ 不放宽 1.30 这个上限本身。
        assertTrue(
            "$name 的有效速度 $vEff 必须 ≤ 基准 $vBase × $cap = $limit",
            vEff <= limit * (1.0 + 1e-9),
        )
    }

    // ══════════════════════ G-SCROLL-5 印章图层序 ══════════════════════

    /**
     * G-SCROLL-5：印章画在 grade **之后**（否则暗角把朱砂压暗）。
     *
     * 判据形态：`drawContent` 体内 grade blit 的位置必须早于印章绘制。
     */
    @Test
    fun gScroll5_sealIsDrawnAfterGrade() {
        val body = drawContentBody()
        assertTrue("drawContent 体内必须找到 grade blit", isSealAfterGrade(body))
    }

    /** G-SCROLL-5 负向自证：把印章挪到 grade 之前 ⇒ 同一份判据必须判红。 */
    @Test
    fun gScroll5_negative_sealBeforeGradeBreaksLayerOrderGate() {
        val body = drawContentBody()
        val swapped = body.replace(GRADE_MARKER, "@@GRADE@@").replace(SEAL_MARKER, GRADE_MARKER)
            .replace("@@GRADE@@", SEAL_MARKER)
        assertTrue("负样本必须真的被换过（否则这条自证空转）", swapped != body)
        assertFalse("负样本：印章画在 grade 之前 ⇒ 同一份判据必须判红", isSealAfterGrade(swapped))
    }

    private fun isSealAfterGrade(body: String): Boolean {
        val grade = body.indexOf(GRADE_MARKER)
        val seal = body.indexOf(SEAL_MARKER)
        if (grade < 0 || seal < 0) return false
        return seal > grade
    }

    private companion object {
        /** L6 grade 的 blit 标记（源码里逐字存在）。 */
        const val GRADE_MARKER = "gradeBmp?.let { c.drawBitmap(it, 0f, 0f, ptBlit) }"

        /** L7 印章的绘制标记（源码里逐字存在）。 */
        const val SEAL_MARKER = "drawSeal(c, ctx, w, h)"
    }

    // ── 源码扫描（照抄 FxCoverageScanTest 的做法） ────────────────

    /** 渲染器源码根目录。 */
    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val cand = File(dir, "app/src/main/java/com/nasmusic/tv/visualizer")
            if (cand.isDirectory) return cand
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 visualizer 源码根：user.dir = ${System.getProperty("user.dir")}")
    }

    /** 剥掉注释后的渲染器源码。 */
    private fun strippedRenderer(): String = stripComments(
        File(mainSourceRoot(), "renderers/QianliScrollRenderer.kt").readText(),
    )

    /** `drawContent` 的函数体（剥注释后，按花括号配对取出）。 */
    private fun drawContentBody(): String {
        val src = strippedRenderer()
        val head = src.indexOf("override fun DrawScope.drawContent")
        assertTrue("必须找到 drawContent", head >= 0)
        val open = src.indexOf('{', head)
        assertTrue("drawContent 必须有函数体", open >= 0)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        error("drawContent 的花括号未闭合")
    }

    /**
     * 剥掉块注释（Kotlin 块注释可嵌套）与行注释。⚠️ 必须保持行数与原文件一致。
     *
     * （照抄 `FxCoverageScanTest` / `DesertSmokeLayerOrderGateTest` 的同一实现。）
     */
    private fun stripComments(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        var inString = false
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                sb.append(c)
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(s[i + 1]); i += 2; continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            if (c == '"') {
                inString = true
                sb.append(c)
                i++
                continue
            }
            if (c == '/' && i + 1 < s.length && s[i + 1] == '*') {
                i += 2
                var depth = 1
                while (i < s.length && depth > 0) {
                    if (s[i] == '/' && i + 1 < s.length && s[i + 1] == '*') {
                        depth++
                        i += 2
                        continue
                    }
                    if (s[i] == '*' && i + 1 < s.length && s[i + 1] == '/') {
                        depth--
                        i += 2
                        continue
                    }
                    if (s[i] == '\n') sb.append('\n')
                    i++
                }
                continue
            }
            if (c == '/' && i + 1 < s.length && s[i + 1] == '/') {
                while (i < s.length && s[i] != '\n') i++
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
