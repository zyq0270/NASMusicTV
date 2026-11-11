package com.nasmusic.tv.visualizer.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **门禁：每个照片墙转场都必须在切换窗口内「走完」**（2026-11-11 电视实测）
 *
 * ## 用户实测现象
 *
 * > 「还是会有切换时间内没完成，后面的照片还没出来全，就停着展示的情况」
 *
 * ⇒ 不是「转场慢了」（那条已由 §10.387 的时长封顶修掉），而是
 * **转场在 `p = 1` 时画面本身还没变成新照片** ⇒ HOLD 期（照片定格）就一直显示着半新半旧的画面。
 *
 * ## 根因：只有「遮罩型」效果会留下旧图
 *
 * 转场画「新照片」的方式只有三类，**只有第一类会在 `p = 1` 留洞**：
 *
 * | 类型 | 机制 | `p = 1` 时 | 会不会留旧图 |
 * |---|---|---|---|
 * | **遮罩型** | `saveLayer` + 遮罩 `BlendMode.DstIn` | 遮罩 alpha 不为 255 的格子**仍是旧图** | ⛔ **会** ⇒ 必须有「末段补画」 |
 * | 裁剪型 | `clipPath(形状)` | 形状在 `p = 1` 盖满整幅 | 不会（几何保证，G21 门禁钉住多边形） |
 * | 几何型 | 透明度 / 缩放 / 条带 / 分块 | 各参数在 `p = 1` 到达终值 | 不会 |
 *
 * ⇒ **遮罩型的 15 个**（P0/P1 的 `NOISE_DISSOLVE` / `THRESHOLD_SWEEP` / `DEPTH_BLUR`
 * + P2 档的 12 个）都必须有「末段整幅补画」（`settleFrom` / `SETTLE_FROM`）。
 *
 * ## 这道门禁怎么守
 *
 * 1. 以**注册表**（`PhotoTransitionRegistry`）为权威映射，拿到「id → 实现类」；
 * 2. 按**效果**（不是按文件）判定机制：自身类体 + 父类体 + 内嵌类体合并后看
 *   （见 [TransitionSourceIndex] 的说明：文件粒度会误判，2026-11-11 踩过）；
 * 3. 含 `BlendMode.DstIn` 的 ⇒ **必须**含补画标记（`settleFrom` / `SETTLE_FROM`）；
 * 4. 用 `clipPath(` 的 ⇒ **必须**登记在 `CLIP_BASED`；登记在 `CLIP_BASED` 的 ⇒ **必须**真用 `clipPath`
 *   （否则老平台白白降级，用户根本看不到那个效果 —— `WIPE_CROSS` 就是这么被误登记的）。
 *
 * ⛔ 本门禁是**源码级**的（不是像素级）：Windows 上 Robolectric 的 NATIVE 图形模式不可用
 * ⇒ 像素回读做不了（见 `PhotoTransitionP2Test` 的说明）。源码扫描是本仓既有的门禁手法
 * （见 `PhotoTransitionClipGateTest`）。
 */
class PhotoTransitionCompletionGateTest {

    private val index: TransitionSourceIndex by lazy { TransitionSourceIndex.build() }

    /** 全部已注册效果 */
    private val allIds: List<String> get() = index.ids.toList()

    // ────────────────────── 门禁 ①：每个效果都有实现 ──────────────────────

    @Test
    fun `每个已注册效果都能定位到实现类与实现文件`() {
        assertTrue("注册表里一个效果都没有", allIds.isNotEmpty())
        for (id in allIds) {
            val cls = index.classOfId[id]
            assertTrue("效果 $id 在注册表里没有对应的实现类", !cls.isNullOrEmpty())
            val file = index.fileOf(id)
            assertTrue("效果 $id（实现类 $cls）找不到实现文件", !file.isNullOrEmpty())
        }
    }

    @Test
    fun `枚举的每一项都必须进注册表`() {
        val missing = PhotoTransitionId.entries.map { it.name }.filter { it !in index.ids }
        assertTrue(
            "这些效果在枚举里有、注册表里没有 ⇒ 用户选中它没反应：$missing",
            missing.isEmpty(),
        )
        assertEquals(
            "注册表的效果数应等于枚举数（${PhotoTransitionId.entries.size}）",
            PhotoTransitionId.entries.size,
            index.ids.size,
        )
    }

    // ────────────────────── 门禁 ②：遮罩型必须补末段整幅新图 ──────────────────────

    /**
     * **遮罩型效果必须有「末段补画」** —— 否则 `p = 1` 时遮罩暗格仍是旧图，
     * HOLD 期就一直显示半新半旧的画面（用户实测：「照片还没出来全，就停着展示」）。
     */
    @Test
    fun `遮罩型转场必须补末段整幅新图`() {
        val offenders = ArrayList<String>()
        for (id in allIds) {
            if (!index.isMaskType(id)) continue
            if (!index.hasSettle(id)) offenders.add("$id（${index.fileOf(id)}）")
        }
        assertTrue(
            "以下遮罩型转场没有「末段补画」（settleFrom / SETTLE_FROM）—— " +
                "p = 1 时遮罩暗格仍是旧图 ⇒ HOLD 期定格在半新半旧：${offenders.joinToString("; ")}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `遮罩型必须存在且不少于 15 个`() {
        // 15 = P0/P1 的 3 个（NOISE_DISSOLVE / THRESHOLD_SWEEP / DEPTH_BLUR）+ P2 的 12 个。
        // ⚠️ 写死 15 是**故意的**：遮罩型少一个 ⇒ 说明某个效果被改写成了另一种机制，
        // 那必须同时更新本门禁与 §10.389 的分类表（不许悄悄变）。
        val mask = allIds.filter { index.isMaskType(it) }
        assertTrue(
            "遮罩型应恰为 15 个（3 个 P0/P1 + 12 个 P2），实际 ${mask.size}：$mask",
            mask.size == 15,
        )
    }

    // ────────────────────── 门禁 ③：clip 型必须与 CLIP_BASED 严格一致 ──────────────────────

    /**
     * **用了 `clipPath` 的必须登记进 `CLIP_BASED`**（否则 API 22 上直接 SIGSEGV）。
     */
    @Test
    fun `凡用 clipPath 的效果都必须登记进 CLIP_BASED`() {
        val clipNames = PhotoTransitionId.CLIP_BASED.map { it.name }.toSet()
        val missing = allIds.filter { index.usesClipPath(it) && it !in clipNames }
        assertTrue(
            "以下效果用了 clipPath 却没进 CLIP_BASED（sdkInt<26 不会降级 ⇒ 老平台崩溃）：$missing",
            missing.isEmpty(),
        )
    }

    /**
     * **登记在 `CLIP_BASED` 的必须真用 `clipPath`** —— 否则就是**白白降级**：
     * 老平台上它会被换成交叉淡化，用户**永远看不到**这个效果。
     *
     * ⛔ 2026-11-11 实测踩坑：`WIPE_CROSS` 与其它 `clipPath` 效果**同文件**
     * （`IrisP1Transitions.kt`），但它用的是 `clipRect` ⇒ 早前的「文件粒度」门禁
     * 把它一并登记进了 `CLIP_BASED` ⇒ API 22 电视上这个效果一直没出现过。
     */
    @Test
    fun `CLIP_BASED 里不得有不用 clipPath 的条目`() {
        val stale = PhotoTransitionId.CLIP_BASED.map { it.name }.filter { !index.usesClipPath(it) }
        assertTrue(
            "CLIP_BASED 里这些效果并没有用 clipPath（要么实现改法了，要么登记写错了）—— " +
                "它们在 API < 26 上会被白白降级成交叉淡化，用户看不到：$stale",
            stale.isEmpty(),
        )
    }

    // ────────────────────── 门禁 ④：分类完备性 ──────────────────────

    /**
     * **分类完备性**：每个已注册效果都必须被归到三类之一。
     *
     * ⚠️ 这条是为了**逼着新增效果的人写清完成机制** —— 门禁失败时，
     * 去本类的分类口径里补上，并说明依据。
     */
    @Test
    fun `每个效果都被归类到一种完成机制`() {
        val clipNames = PhotoTransitionId.CLIP_BASED.map { it.name }.toSet()
        val mask = allIds.filter { index.isMaskType(it) }
        val clip = allIds.filter { it in clipNames }
        val plain = allIds.filter { !index.isMaskType(it) && it !in clipNames }

        assertEquals(
            "分类总数应等于已注册效果数（${allIds.size}）",
            allIds.size,
            mask.size + clip.size + plain.size,
        )
        // 三类都非空 —— 任一类为空说明分类规则漏了一种机制（门禁自身失效）
        assertTrue("遮罩型不应为空（NOISE_DISSOLVE 等）", mask.isNotEmpty())
        assertTrue("裁剪型不应为空（IRIS_CIRCLE 等）", clip.isNotEmpty())
        assertTrue("几何型不应为空（CROSSFADE 等）", plain.isNotEmpty())
        // 遮罩型与裁剪型不该重叠（一个效果只走一种机制）
        assertTrue(
            "遮罩型与裁剪型重叠了：${mask.filter { it in clipNames }}",
            mask.none { it in clipNames },
        )
    }
}
