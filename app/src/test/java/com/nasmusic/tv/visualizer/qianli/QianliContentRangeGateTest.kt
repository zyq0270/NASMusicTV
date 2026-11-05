package com.nasmusic.tv.visualizer.qianli

import com.nasmusic.tv.visualizer.renderers.QianliScrollRenderer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * G-REND-8：**块的内容范围必须覆盖它的真实几何**（2026-11-05 真机「半边山」的门禁）。
 *
 * ## 为什么要有这道门禁
 *
 * `QianliScrollRenderer.bakeBlock` 选 chunk 的条件是
 * `spec.maxX < x0 || spec.minX > x1`（按**内容的世界 x 范围**与块的区间相交）。
 * ⇒ **凡是不与块的区间相交的 chunk，整个都不会被画** ⇒ 它覆盖的那一段世界坐标
 * **永远不出现内容** ⇒ 画面上是一道**笔直的竖直缺口** ⇒ 用户看到的「半边山」。
 *
 * ## 缺陷是怎么来的（已修）
 *
 * 旧实现用**名义参数**推算内容范围：
 * - 中景 `spec.x ± (wid/2 + 90)`（`wid` = 名义山宽 400–600）
 * - 近景 `spec.x ± (wid/2 + 140)`（`wid` = 名义平山宽 600–1000）
 *
 * 但上游 `flatMount` 的左右轮廓（`grlist`）**向两端各延伸 3 点再等分**、
 * `mount` 的山脊树与山脚石也超出山体名义跨度 ⇒ **真实几何超出名义范围**
 * ⇒ 超出部分**任何块都不选这个 chunk** ⇒ 近景的平山被拦腰竖切 ⇒ **「半边山」**。
 *
 * ⇒ 现在改成 `fitContentX()`：**按轮廓点的真实 x 极值**定范围。
 * ⇒ 这道门禁守住该不变量：**任一层的任一 chunk，其每个轮廓点的 x 都必须落在 [minX, maxX] 内**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QianliContentRangeGateTest {

    /** 四层（远景 / 中景 / 近景 / 水）逐 chunk 核对。 */
    @Test
    fun 每层每个chunk的内容范围必须覆盖真实几何() {
        val renderer = QianliScrollRenderer()
        var checkedChunks = 0
        var checkedPoints = 0
        for (layer in LAYERS) {
            val chunks = renderer.gateChunksForGate(layer)
            assertTrue("层 $layer 必须有已规划的 chunk", chunks.isNotEmpty())
            for (chunk in chunks) {
                checkedChunks++
                for (c in chunk.contours) {
                    for (p in c.pts) {
                        checkedPoints++
                        assertTrue(
                            "层 $layer 的 chunk 内容范围 [${chunk.minX}, ${chunk.maxX}] " +
                                "没有覆盖轮廓点 x=${p[0]} ⇒ 该段内容任何块都不会画 ⇒ 竖直缺口",
                            p[0].toDouble() >= chunk.minX && p[0].toDouble() <= chunk.maxX,
                        )
                    }
                }
            }
        }
        assertTrue("必须真的检查到 chunk（否则门禁是空的）", checkedChunks >= 4)
        assertTrue("必须真的检查到轮廓点（否则门禁是空的）", checkedPoints >= 1000)
    }

    /**
     * **负向自证**：把范围**收窄**后喂给上面**同一份断言** ⇒ 必须判失败。
     *
     * ⛔ 用同一份判据（`p[0] ∈ [minX, maxX]`），不是另写一条 ⇒ 证明门禁真的会红。
     */
    @Test
    fun 负向自证_收窄内容范围必须被判失败() {
        val renderer = QianliScrollRenderer()
        val chunk = renderer.gateChunksForGate(QianliScrollRenderer.GateLayer.NEAR).first()
        // 收窄到几何内部（必然盖不住右端的点）
        val narrowedMax = chunk.minX + (chunk.maxX - chunk.minX) * 0.5
        val violated = chunk.contours.any { c ->
            c.pts.any { p -> p[0].toDouble() < chunk.minX || p[0].toDouble() > narrowedMax }
        }
        assertTrue("收窄范围后必须存在盖不住的点（否则自证样本无效）", violated)

        var caught = false
        try {
            for (c in chunk.contours) {
                for (p in c.pts) {
                    if (p[0].toDouble() < chunk.minX || p[0].toDouble() > narrowedMax) {
                        throw AssertionError("范围未覆盖 x=${p[0]}")
                    }
                }
            }
        } catch (e: AssertionError) {
            caught = true
        }
        assertTrue("负向样本必须被判失败（证明门禁判据是活的）", caught)
    }

    private companion object {
        val LAYERS = listOf(
            QianliScrollRenderer.GateLayer.FAR,
            QianliScrollRenderer.GateLayer.MID,
            QianliScrollRenderer.GateLayer.NEAR,
            QianliScrollRenderer.GateLayer.WATER,
        )
    }
}
