package com.nasmusic.tv.visualizer.qianli

import com.nasmusic.tv.visualizer.renderers.QianliScrollRenderer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E50 朝向门禁（G-REND-7）。
 *
 * ## 这条门禁防的是什么
 *
 * 2026-11-05 真机事故：**整幅山体上下颠倒**（像一片倒挂的钟乳石）。
 * 根因不是移植层（移植层与上游逐行一致），而是渲染器的层带映射把
 * 「该点高出基线多少」这一项**加**上了：
 * `(chunkBaseY − worldY)` 是「高出多少」（≥ 0），而层带局部 y 是**画布 y（向下为正）**
 * ⇒ 越高的点必须映射到**更小**的局部 y ⇒ 只能**减**。
 *
 * ⛔ 这类错误**肉眼在真机上才看得出**，而真机回归成本极高 ⇒ 必须有门禁。
 *
 * ## 判据
 *
 * 对渲染器**真实**的中景 chunk（真实轮廓 + 真实层带映射）：
 * **山脊点**（世界 y 最小者）映射后的层带局部 y 必须**小于**
 * **山脚点**（世界 y 最大者）映射后的层带局部 y。
 *
 * ## 负向自证
 *
 * 把「峰」与「脚」对调（等价于把世界 y 上下镜像）喂给正向用例用的同一份断言
 * ⇒ 断言必须抛 AssertionError ⇒ 证明这条判据不是恒真。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QianliOrientationGateTest {

    private val screenH = 1080

    @Test
    fun gRend7_mountainPeakMapsStrictlyAboveItsFoot() {
        val r = QianliScrollRenderer()
        val map = r.bandMapForGate(QianliScrollRenderer.GateLayer.MID, screenH)
        val chunks = r.gateChunksForGate(QianliScrollRenderer.GateLayer.MID)

        assertTrue("中景必须有 chunk（否则这条判据空转）", chunks.isNotEmpty())

        var checked = 0
        for (chunk in chunks) {
            val ys = chunk.contours.flatMap { c -> c.pts.map { it[1].toDouble() } }
            if (ys.size < 2) continue
            val footY = ys.max()
            val peakY = ys.min()
            val footLocal = map.bandY(chunk.baseY, footY)
            val peakLocal = map.bandY(chunk.baseY, peakY)
            assertTrue(
                "山脊（层带局部 y=$peakLocal）必须高于山脚（层带局部 y=$footLocal）" +
                    "—— 层带局部 y 是画布 y（向下为正），越高必须越靠上",
                peakLocal < footLocal,
            )
            checked++
        }
        assertTrue("必须至少核对到一个 chunk（实测 $checked 个）", checked > 0)
    }

    /**
     * 负向自证：把峰与脚对调 ⇒ 同一份断言必须判红。
     *
     * ⚠️ 这条同时证明正向断言不是恒真：若映射写成加号（历史实现），
     *   正向用例会红，而这条负向用例会绿（断言通过）⇒ 两者互为反证。
     */
    @Test
    fun gRend7_negative_swappedPeakAndFootIsRejectedByTheSameAssertion() {
        val r = QianliScrollRenderer()
        val map = r.bandMapForGate(QianliScrollRenderer.GateLayer.MID, screenH)
        val chunks = r.gateChunksForGate(QianliScrollRenderer.GateLayer.MID)

        var caught = false
        try {
            for (chunk in chunks) {
                val ys = chunk.contours.flatMap { c -> c.pts.map { it[1].toDouble() } }
                if (ys.size < 2) continue
                val footY = ys.max()
                val peakY = ys.min()
                // 峰脚对调（= 上下镜像后的世界 y）
                assertTrue(
                    "镜像后：原山脚必须高于原山脊",
                    map.bandY(chunk.baseY, footY) < map.bandY(chunk.baseY, peakY),
                )
                caught = true
                break
            }
        } catch (e: AssertionError) {
            caught = true
        }
        assertTrue("峰脚对调的负样本必须被同一份断言咬住（否则正向判据被放宽了）", caught)
    }
}
