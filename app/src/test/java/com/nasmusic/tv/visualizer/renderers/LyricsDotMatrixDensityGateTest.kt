package com.nasmusic.tv.visualizer.renderers

import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.visualizer.renderers.lyricsStage.DotMatrixSubRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门禁 · 歌词点阵的**点阵密度**（2026-11-07，用户反馈「要再加多粒子，现在都不够组成文字」）。
 *
 * ## 背景（实测，非推断）
 * 旧的行内取点判据 `((taken - 1) % takeEvery) < 0.5f` 里 `takeEvery = rowInk / targetCount`
 * 是**小数** ⇒ 取模命中的位置**不再等距**（实测间隔在 14 / 29 / 43 个墨迹像素之间乱跳），
 * 且只命中 **≈54%** 的目标数：
 * - TV 1920×1080 / 8 字行：`cap 3600` 实际只落下 **1959** 个粒子（每字 245 个）；
 * - 同上，低档 `cap 900` 只落下 **479** 个。
 * 更糟的是落点**偏在笔画的一侧** ⇒ 每个笔画只剩一条边被点亮 ⇒ 字看着像「线框」而不是字。
 *
 * 本门禁把三条性质钉死：
 * ① 取点**恰好取满**本行配额（不再浪费 ~46% 的预算）；
 * ② 取点沿**整行铺开**（间隔均匀）⇒ 点落在笔画宽度方向上也铺开，不是贴着一条边；
 * ③ 加密度时**字号不得跟着涨**（否则新增的粒子被摊到更大的字上 ⇒ 密度反降）。
 *
 * ## 负向自证
 * 旧判据在**同一批样本**上必须被 ①② 拒掉（否则本门禁可能整条判据都失效）。
 */
class LyricsDotMatrixDensityGateTest {

    /** 被测：渲染器的 `internal` 纯函数（不复制算法 ⇒ 不会漂移） */
    private fun selected(rowInk: Int, targetCount: Int): List<Int> =
        (0 until rowInk).filter {
            LyricsDotMatrixRenderer.isSegmentStart(it, rowInk, targetCount)
        }

    /**
     * 旧实现（已删除）—— 仅作**负向样本**。
     * 保留 `filled >= targetCount` 的截断，与当年的代码行为一致。
     */
    private fun selectedByOld(rowInk: Int, targetCount: Int): List<Int> {
        val takeEvery = (rowInk.toFloat() / targetCount).coerceAtLeast(1f)
        val out = mutableListOf<Int>()
        var filled = 0
        for (i in 0 until rowInk) {
            if (((i).toFloat() % takeEvery) < 0.5f) {
                out += i
                filled++
                if (filled >= targetCount) break
            }
        }
        return out
    }

    /** 相邻取点之间的间隔序列 */
    private fun gaps(idx: List<Int>): List<Int> =
        idx.zipWithNext { a, b -> b - a }

    /** 样本：真实的行宽 × 配额（`rowInk` 取各种奇偶，确保 `rowInk % targetCount != 0`） */
    private val samples = listOf(
        100 to 7,
        100 to 13,
        57 to 5,
        214 to 11,
        31 to 4,
        480 to 23,
    )

    // ── ① 取点必须恰好取满配额 ────────────────────────────────

    @Test
    fun `① 行内取点必须恰好取满配额 不再浪费近半的预算`() {
        for ((rowInk, targetCount) in samples) {
            val got = selected(rowInk, targetCount)
            assertEquals(
                "rowInk=$rowInk target=$targetCount：必须恰好取 $targetCount 个点",
                targetCount, got.size
            )
        }
        // 配额 ≥ 墨迹像素数时：每个像素都取（不可能比像素还多）
        assertEquals(9, selected(9, 20).size)
        assertEquals(9, selected(9, 9).size)
        // 退化输入不得抛异常、不得取点
        assertEquals(0, selected(0, 7).size)
        assertEquals(0, selected(10, 0).size)
    }

    // ── ② 取点必须沿整行铺开 ────────────────────────────────

    @Test
    fun `② 取点间隔必须均匀 沿笔画宽度方向也铺开`() {
        for ((rowInk, targetCount) in samples) {
            val g = gaps(selected(rowInk, targetCount))
            assertTrue("rowInk=$rowInk target=$targetCount：取点间隔必须非空", g.isNotEmpty())
            assertTrue(
                "rowInk=$rowInk target=$targetCount：间隔极差必须 ≤ 1（否则点只贴着笔画的一条边 ⇒ 空心轮廓）" +
                    "，实测 ${g.max()} vs ${g.min()}",
                g.max() - g.min() <= 1
            )
            // 首点必须落在本行开头（段首取点）
            assertEquals("rowInk=$rowInk：首点必须是段首", 0, selected(rowInk, targetCount).first())
        }
    }

    // ── ③ 负向自证：旧判据必须被同一条判据拒掉 ──────────────

    @Test
    fun `③ 负向自证 旧判据在同一样本上必须取不满或间隔不均`() {
        var caughtByCount = 0
        var caughtByGap = 0
        for ((rowInk, targetCount) in samples) {
            val old = selectedByOld(rowInk, targetCount)
            val g = gaps(old)
            val underFills = old.size < targetCount
            val uneven = g.isNotEmpty() && g.max() - g.min() > 1
            assertTrue(
                "旧判据在 rowInk=$rowInk target=$targetCount 上两条性质都过了" +
                    "（取满=${old.size}、间隔极差=${if (g.isEmpty()) -1 else g.max() - g.min()}）" +
                    "⇒ 判据对该样本没有鉴别力",
                underFills || uneven
            )
            if (underFills) caughtByCount++
            if (uneven) caughtByGap++
        }
        // ①（取满配额）与 ②（间隔均匀）各自都得有能抓到旧实现的样本
        assertTrue("判据 ① 必须至少抓到一个旧实现的样本", caughtByCount > 0)
        assertTrue("判据 ② 必须至少抓到一个旧实现的样本", caughtByGap > 0)
        // 旧判据只在「整除」时才凑巧正确 —— 这正是它长期没被发现的原因
        assertEquals(10, selectedByOld(100, 10).size)
    }

    // ── ④ 加密度不得放大字号 ────────────────────────────────

    @Test
    fun `④ 加密度时字号上限必须锁在档位默认 减密度时仍按sqrt收缩`() {
        val hi = LyricsDotMatrixRenderer.capacityForTier(VisualQuality.HIGH)
        val lo = LyricsDotMatrixRenderer.capacityForTier(VisualQuality.LOW)

        // 加密度：字号上限**不动** ⇒ 新增粒子落在同样大小的字上 ⇒ 密度必涨
        assertEquals(
            "加密度时字号上限必须锁在档位默认",
            hi, LyricsDotMatrixRenderer.fontBudgetCap(hi, hi * 2)
        )
        assertEquals(
            "加密度时字号比例必须与档位默认完全一致",
            LyricsDotMatrixRenderer.fontMaxRatio(hi),
            LyricsDotMatrixRenderer.fontMaxRatio(LyricsDotMatrixRenderer.fontBudgetCap(hi, hi * 2)),
            1e-6f
        )
        // 减密度：仍按 sqrt 收缩（低档降密度换帧率的既有设计不得被破坏）
        assertEquals(
            "减密度时字号上限必须跟着收缩",
            lo, LyricsDotMatrixRenderer.fontBudgetCap(hi, lo)
        )
        assertTrue(
            "低档字号上限仍必须 ≥ 可读性下限（否则 coerceIn 抛）",
            LyricsDotMatrixRenderer.fontMaxRatio(lo) >= LyricsDotMatrixRenderer.MIN_FONT_S
        )
    }

    // ── ⑤ 加密度是 opt-in：E23 独立主题的预算档位不变 ────────

    @Test
    fun `⑤ E23独立主题不受加密度影响 歌词舞台子模式0显式加密度`() {
        // E23 独立主题的档位预算**不变**（门禁 `LowTierElementBudgetTest` ① 也钉着这两个数）
        assertEquals(3600, LyricsDotMatrixRenderer.capacityForTier(VisualQuality.MEDIUM))
        assertEquals(3600, LyricsDotMatrixRenderer.capacityForTier(VisualQuality.HIGH))
        assertTrue(
            "低档必须严格小于中档",
            LyricsDotMatrixRenderer.capacityForTier(VisualQuality.LOW) <
                LyricsDotMatrixRenderer.capacityForTier(VisualQuality.MEDIUM)
        )

        // 未开启覆盖时：实际预算 = 档位默认
        val plain = LyricsDotMatrixRenderer()
        assertEquals(
            "默认必须用档位预算",
            3600, plain.effectiveCapacity(LyricsDotMatrixRenderer.capacityForTier(VisualQuality.MEDIUM))
        )
        // 开启覆盖后：实际预算 = 覆盖值
        plain.setParticleBudget(7200)
        assertEquals(
            "覆盖后必须用覆盖值",
            7200, plain.effectiveCapacity(LyricsDotMatrixRenderer.capacityForTier(VisualQuality.MEDIUM))
        )
        // 覆盖值不得越过防呆上界
        plain.setParticleBudget(Int.MAX_VALUE)
        assertEquals(
            "覆盖值必须被夹到上界",
            LyricsDotMatrixRenderer.MAX_PARTICLE_BUDGET,
            plain.effectiveCapacity(LyricsDotMatrixRenderer.capacityForTier(VisualQuality.MEDIUM))
        )
        // 归零 = 恢复档位默认
        plain.setParticleBudget(0)
        assertEquals(
            "归零必须恢复档位默认",
            3600, plain.effectiveCapacity(LyricsDotMatrixRenderer.capacityForTier(VisualQuality.MEDIUM))
        )

        // 歌词舞台子模式 0：中/高档加密度、低档不加
        assertTrue(
            "歌词舞台中档必须比 E23 更密",
            DotMatrixSubRenderer.budgetFor(VisualQuality.MEDIUM) >
                LyricsDotMatrixRenderer.capacityForTier(VisualQuality.MEDIUM)
        )
        assertTrue(
            "歌词舞台高档必须比 E23 更密",
            DotMatrixSubRenderer.budgetFor(VisualQuality.HIGH) >
                LyricsDotMatrixRenderer.capacityForTier(VisualQuality.HIGH)
        )
        assertEquals(
            "低档不加密度（弱机优先帧率）",
            LyricsDotMatrixRenderer.capacityForTier(VisualQuality.LOW),
            DotMatrixSubRenderer.budgetFor(VisualQuality.LOW)
        )
        assertTrue(
            "歌词舞台的预算不得越过防呆上界",
            DotMatrixSubRenderer.budgetFor(VisualQuality.HIGH) <=
                LyricsDotMatrixRenderer.MAX_PARTICLE_BUDGET
        )
    }
}
