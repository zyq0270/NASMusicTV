package com.nasmusic.tv.visualizer.photo

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **门禁 G5**（§14.4）—— 随机池规则
 *
 * ## 判据原文
 *
 * > 池内**无音频反应类**；老平台（`sdkInt = 22`）下 `LIQUIFY` 与 `NOISE_DISSOLVE` **不同时出现**
 * > 负向自证：用 `sdkInt = 33` 跑同一断言 ⇒ 两者可共存（证明降级去重确实生效）
 *
 * ## 为什么池要「按降级后的标识」去重
 *
 * 电视（API 22）没有 AGSL，`LIQUIFY`（M5）降级后**实际就是 `NOISE_DISSOLVE`**（§4.4）。
 * 若池里两者都在，抽到「LIQUIFY」和抽到「NOISE_DISSOLVE」会画出**一模一样**的效果
 * —— 用户会觉得「随机池里有重复项」，而设置页的选择器里它们又是两个不同条目。
 *
 * ⚠️ 本测试**不需要 Robolectric**：全部输入用 `PhotoTransitionId.entries`（不碰注册表）。
 * 「池必须由 `PhotoTransitionRegistry.available()` 构造」这条跨模块一致性放在
 * `PhotoTransitionRegistryTest`（那边本来就要起 Robolectric）。
 */
class PhotoTransitionPoolTest {

    /** 全部 76 项 —— 用完整枚举当池源，才能同时覆盖 P1 / P2 的规则 */
    private val all = PhotoTransitionId.entries.toList()

    private val audioReactive = PhotoTransitionId.entries.filter { it.audioReactive }

    // ────────────────────── ① 排除音频反应类 ──────────────────────

    /** §5.9：`SPECTRUM_BARS` / `BEAT_CUT` / `BASS_BLOOM` / `WAVEFORM_WIPE` / `PULSE_DISSOLVE` 不进随机池 */
    @Test
    fun `pool excludes every audio reactive effect`() {
        val pool = PhotoTransitionId.randomPool(all, sdkInt = 34).toSet()
        val leaked = audioReactive.filter { it.ordinal in pool }.map { it.name }
        assertTrue(
            "音频反应类只能由用户主动选中，不该进随机池 —— 泄漏了 $leaked",
            leaked.isEmpty(),
        )
    }

    /**
     * **负向自证（防空转）**：确认「音频反应类」这个标记**真的有人被打上**。
     *
     * 若有人把 `audioReactive` 全部删掉，上一条断言会**恒真**（池里当然没有它们）——
     * 这条断言就是防那种「门禁空转」的。
     */
    @Test
    fun `negative proof - there really are audio reactive effects to exclude`() {
        assertTrue(
            "§4.2 I 类应有 5 项，实际 ${audioReactive.size} 项：${audioReactive.map { it.name }}",
            audioReactive.size == 5,
        )
        val names = audioReactive.map { it.name }.toSet()
        assertTrue(
            "5 项音频反应类应正好是 §4.2 I 类，实际 $names",
            names == setOf("SPECTRUM_BARS", "BEAT_CUT", "BASS_BLOOM", "WAVEFORM_WIPE", "PULSE_DISSOLVE"),
        )
    }

    // ────────────────────── ② 降级去重 ──────────────────────

    /** 老平台（API 22）：`LIQUIFY` 降级成 `NOISE_DISSOLVE` ⇒ 池里只留一个 */
    @Test
    fun `old platform deduplicates liquify onto noise dissolve`() {
        assertTrue(
            "前置条件：LIQUIFY 在老平台的降级目标是 NOISE_DISSOLVE，实际 ${PhotoTransitionId.LIQUIFY.degradeTo}",
            PhotoTransitionId.effective(PhotoTransitionId.LIQUIFY, 22) == PhotoTransitionId.NOISE_DISSOLVE,
        )

        val pool = PhotoTransitionId.randomPool(all, sdkInt = 22).toSet()
        assertTrue("老平台池里应有 NOISE_DISSOLVE", PhotoTransitionId.NOISE_DISSOLVE.ordinal in pool)
        assertTrue(
            "老平台池里不该再有 LIQUIFY（降级后与 NOISE_DISSOLVE 是同一个效果）",
            PhotoTransitionId.LIQUIFY.ordinal !in pool,
        )
    }

    /**
     * **负向自证**（§14.4 原文指定）：同一份代码、`sdkInt = 33` ⇒ 两者可共存。
     *
     * 两条路的池大小差**恰好 1** —— 证明「去重」确实发生了一次，
     * 而不是因为别的原因（比如 `LIQUIFY` 根本没进池）。
     */
    @Test
    fun `negative proof - on a modern platform both can coexist`() {
        assertTrue(
            "前置条件：sdkInt = 33 时 LIQUIFY 不降级",
            PhotoTransitionId.effective(PhotoTransitionId.LIQUIFY, 33) == PhotoTransitionId.LIQUIFY,
        )

        val old = PhotoTransitionId.randomPool(all, sdkInt = 22).toSet()
        val modern = PhotoTransitionId.randomPool(all, sdkInt = 33).toSet()

        assertTrue("新平台池里应有 LIQUIFY", PhotoTransitionId.LIQUIFY.ordinal in modern)
        assertTrue("新平台池里也应有 NOISE_DISSOLVE", PhotoTransitionId.NOISE_DISSOLVE.ordinal in modern)
        // 老平台（sdk 22）有**两类**降级，二者都表现为池内去重：
        // ① shader 降级：LIQUIFY → degradeTo；
        // ② clipPath 降级（C46）：CLIP_BASED 全部 → CROSSFADE。
        val collapsed = collapsedOnOldPlatform()
        assertTrue(
            "两条路的池大小应恰好差 ${collapsed.size}（老平台把降级项去重掉了）：old=${old.size} modern=${modern.size}",
            modern.size - old.size == collapsed.size,
        )
        assertTrue(
            "老平台池应是新平台池去掉降级项后的子集",
            old == modern - collapsed,
        )
    }

    // ────────────────────── ③ 池的大小与合法性 ──────────────────────

    /** 池大小可精确核算 ⇒ 「多了 / 少了」都能被发现 */
    @Test
    fun `pool size matches the rules exactly`() {
        // 76 项 − 5 音频反应 = 71；老平台在此基础上再折叠降级项（LIQUIFY + clip 类）= 62
        val modern = PhotoTransitionId.randomPool(all, 33)
        assertTrue(
            "新平台池应为 71 项（76 − 5 音频），实际 ${modern.size}",
            modern.size == 71,
        )
        val collapsed = collapsedOnOldPlatform().count { it in modern }
        assertTrue(
            "老平台池应为 ${71 - collapsed} 项（71 − 降级去重 $collapsed），实际 ${PhotoTransitionId.randomPool(all, 22).size}",
            PhotoTransitionId.randomPool(all, 22).size == 71 - collapsed,
        )
    }

    /** 池内**无重复**、全部是合法 ordinal */
    @Test
    fun `pool has no duplicates and only valid ordinals`() {
        for (sdk in listOf(22, 33, 34)) {
            val pool = PhotoTransitionId.randomPool(all, sdkInt = sdk)
            assertTrue("sdk=$sdk 的池有重复项", pool.toSet().size == pool.size)
            for (ordinal in pool) {
                assertTrue("sdk=$sdk 的池含非法 ordinal=$ordinal", ordinal in PhotoTransitionId.entries.indices)
            }
        }
    }

    /** 池里每一项都能取回一个真实效果（防止 ordinal 与 entries 索引错位） */
    @Test
    fun `every pool entry resolves back to an effect`() {
        val pool = PhotoTransitionId.randomPool(all, sdkInt = 22)
        for (ordinal in pool) {
            val id = PhotoTransitionId.entries[ordinal]
            assertTrue("ordinal=$ordinal 取回的是 $id，但它的 ordinal 是 ${id.ordinal}", id.ordinal == ordinal)
        }
    }

    // ────────────────────── ④ 分期重载 ──────────────────────

    /**
     * 按分期的便捷重载。
     *
     * P0（15 项）里没有音频反应类、也没有 `LIQUIFY`（P2）⇒ 池 = 15。
     * P1（累计 43 项）里有 2 个音频反应类（`SPECTRUM_BARS` / `BEAT_CUT`）⇒ 池 = 41。
     * P2（累计 76 项）⇒ 与完整枚举一致。
     */
    @Test
    fun `phase overload follows the phase plan`() {
        // 新平台（sdk 33）不触发任何降级 ⇒ 池大小 = 分期项数 − 音频反应类。
        // 老平台（sdk 22）在此基础上再折叠 LIQUIFY 与 clip 类（C46 ⇒ CROSSFADE）。
        val collapsed = collapsedOnOldPlatform()
        for ((phase, expectedModern) in listOf(
            PhotoTransitionId.Phase.P0 to 15,  // P0 里没有音频反应类
            PhotoTransitionId.Phase.P1 to 41,  // 43 − 2 音频反应类
            PhotoTransitionId.Phase.P2 to 71,  // 76 − 5 音频反应类
        )) {
            val modern = PhotoTransitionId.randomPool(phase, sdkInt = 33)
            assertTrue(
                "$phase 新平台池应为 $expectedModern 项，实际 ${modern.size}",
                modern.size == expectedModern,
            )
            val expectedOld = expectedModern - collapsed.count { it in modern }
            val old = PhotoTransitionId.randomPool(phase, sdkInt = 22)
            assertTrue(
                "$phase 老平台池应为 $expectedOld 项（降级去重），实际 ${old.size}",
                old.size == expectedOld,
            )
        }
    }

    /**
     * 老平台（`sdkInt < 26`）会把被降级的项折叠掉，池里因此少了这些 ordinal：
     * [PhotoTransitionId.LIQUIFY]（shader 降级）+ 全部 [PhotoTransitionId.CLIP_BASED]
     * （clipPath 降级到 CROSSFADE）。⛔ 写死在断言里会在新增 clip 类时立刻失真。
     */
    private fun collapsedOnOldPlatform(): Set<Int> =
        (setOf(PhotoTransitionId.LIQUIFY) + PhotoTransitionId.CLIP_BASED)
            .map { it.ordinal }
            .toSet()

    /** 分期越靠后池越大（单调）—— 防止「加了分期但池没扩」 */
    @Test
    fun `pool grows monotonically with the phase`() {
        val sizes = PhotoTransitionId.Phase.entries.map {
            PhotoTransitionId.randomPool(it, sdkInt = 22).size
        }
        for (i in 1 until sizes.size) {
            assertTrue("分期 ${PhotoTransitionId.Phase.entries[i]} 的池（${sizes[i]}）应大于前一档（${sizes[i - 1]}）",
                sizes[i] > sizes[i - 1])
        }
    }

    /** 分期重载的结果必须是完整枚举池的**子集**（不能凭空多出项） */
    @Test
    fun `phase pools are subsets of the full pool`() {
        val full = PhotoTransitionId.randomPool(all, sdkInt = 22).toSet()
        for (phase in PhotoTransitionId.Phase.entries) {
            val pool = PhotoTransitionId.randomPool(phase, sdkInt = 22).toSet()
            assertTrue(
                "$phase 的池不是完整池的子集，多出 ${pool - full}",
                full.containsAll(pool),
            )
        }
    }
}
