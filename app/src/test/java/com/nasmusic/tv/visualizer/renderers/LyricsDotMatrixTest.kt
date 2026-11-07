package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * E23 歌词点阵（§B7 · T4.7 · 2026-11-07 合批改造）门禁。
 *
 * 本轮（2026-11-07）修掉两个缺陷：
 * 1. **逐粒子 `Path.addOval`**：E53「歌词舞台」子模式 0 实测 ≈ 11,700 次 JNI/帧 ⇒ ≈30 fps。
 *    ⇒ 改为 `Canvas.drawPoints(float[], offset, count, Paint)`（`Cap.ROUND` + `strokeWidth` = 点直径）。
 * 2. **卡拉OK 扫描 1 Hz**：`ctx.lyricLineProgress` 来自 1 Hz 的 StateFlow ⇒ 扫描每秒跳一次。
 *    ⇒ 宿主（E53）用逐帧的 `LyricsStagePayload` 推给渲染器（带行索引配对）。
 *
 * 门禁分段：
 * - **行为段**：**直接调生产函数**（⛔ 不复制算法 —— 复制必然漂移）：
 *   [LyricsDotMatrixRenderer.calculateRowQuota] / [LyricsDotMatrixRenderer.karaokePacing] /
 *   [LyricsDotMatrixRenderer.resolveLyricProgress] / [LyricsDotMatrixRenderer.lineProgressOf] /
 *   [LyricsDotMatrixRenderer.subIndex] / [LyricsDotMatrixRenderer.visibilityBucketOf]；
 * - **形态段**：源码判据（先**剥注释**）—— 逐粒子的 `addOval` 必须消失、`drawPoints` 必须到位、
 *   绘制路径必须零分配；
 * - **观感段（Q2/Q4）**：亮档 alpha 数值 + `postFx` 字面量；
 * - **保留段（§13.5-D4）**：卡拉OK 三档亮度 + 分档阈值 + 守卫注释必须在位。
 *
 * ⛔ 负向自证 —— 缺一条就可能空转。
 * ⛔ 源码判据一律**先剥注释**（本仓库已踩 5 次：判据命中自己刚写的 KDoc ⇒ 假 FAIL）。
 * ⛔ 负向样本必须与正向**喂同一份谓词**（不要另写「预期为 false」的表达式）。
 */
class LyricsDotMatrixTest {

    private val C = LyricsDotMatrixRenderer.Companion

    // ═══════════════════════════ ① 迁移形态 ═══════════════════════════

    @Test
    fun `① 已迁移 RendererFx - 无 rng 成员、不覆写 final、摘掉旧 import`() {
        val body = classBody(codeOfE23(), "LyricsDotMatrixRenderer")
        assertTrue("类体必须能切出来（空转自证）", body.isNotEmpty())
        assertTrue("必须 `: RendererFx()`", ": RendererFx()" in body)
        assertTrue("必须实现 drawContent",
            Regex("""fun\s+(?:[A-Za-z0-9_.]+\.)?drawContent\s*\(""").containsMatchIn(body))
        assertTrue("必须实现 onEnterContent", "fun onEnterContent(" in body)
        assertTrue("必须实现 onExitContent", "fun onExitContent(" in body)
        // ⛔ 模板方法是 final ⇒ 子类覆写会编译错
        assertFalse("⛔ 不得覆写 final 的 draw",
            Regex("""override\s+fun\s+DrawScope\.draw\s*\(""").containsMatchIn(body))
        assertFalse("⛔ 不得覆写 final 的 onEnter",
            Regex("""override\s+fun\s+onEnter\s*\(""").containsMatchIn(body))
        assertFalse("⛔ 不得覆写 final 的 onExit",
            Regex("""override\s+fun\s+onExit\s*\(""").containsMatchIn(body))
        // ⛔ 基类已提供 rng ⇒ 子类不得再声明一份
        assertFalse("⛔ 不得自己声明 rng（基类已提供）",
            Regex("""(?m)^\s*private\s+val\s+rng\s*=""").containsMatchIn(body))

        val raw = readFile(renderersFile("LyricsDotMatrixRenderer.kt"))
        assertFalse("⛔ 不得再 import VisualizerRenderer",
            "import com.nasmusic.tv.visualizer.VisualizerRenderer" in raw)
        assertFalse("⛔ 不得再 import VisualizerRandom",
            "import com.nasmusic.tv.visualizer.VisualizerRandom" in raw)
    }

    // ═══════════════════════════ ② P2 逐行批读 ═══════════════════════════

    @Test
    fun `② P2 采样必须逐行 getPixels - getPixel 为 0 且 rowBuf 复用`() {
        val body = classBody(codeOfE23(), "LyricsDotMatrixRenderer")
        assertFalse("⛔ 不得再出现逐像素 getPixel(", usesGetPixel(body))
        val n = Regex("""\.getPixels\(""").findAll(body).count()
        assertTrue("两遍采样各一次 ⇒ `getPixels(` 至少 2 处（实际 $n）", n >= 2)
        assertTrue("必须用成员 rowBuf 跨次复用（`takeIf { it.size >= bmpW }`）",
            "rowBuf?.takeIf { it.size >= bmpW }" in body)
        assertFalse("⛔ 不得整图读 IntArray(bmpW * bmpH)（§B7 R12）",
            Regex("""IntArray\(\s*bmpW\s*\*\s*bmpH\s*\)""").containsMatchIn(body))
    }

    // ═══════════════════════════ ③ P6 step 恒 1 ═══════════════════════════

    @Test
    fun `③ P6 采样 step 必须恒为 1`() {
        val sample = funBody(classBody(codeOfE23(), "LyricsDotMatrixRenderer"), "sampleLine")
        assertTrue("sampleLine 必须能切出来（空转自证）", sample.isNotEmpty())
        assertTrue("必须 `val step = 1`",
            Regex("""val\s+step\s*=\s*1\b""").containsMatchIn(sample))
        assertFalse("⛔ 不得再有旧的 `coerceIn(1f, 3f)`",
            Regex("""coerceIn\(\s*1f\s*,\s*3f\s*\)""").containsMatchIn(sample))
        assertFalse("⛔ 不得再算 idealStep", "idealStep" in sample)
    }

    // ═══════════════ ④ 合批契约：逐粒子的 addOval 必须消失 ═══════════════

    @Test
    fun `④ 合批契约 - 不得再有逐粒子 addOval 必须改为 drawPoints 合批`() {
        val body = classBody(codeOfE23(), "LyricsDotMatrixRenderer")
        assertFalse("⛔ 不得再有逐粒子的 addOval（每帧上万次 JNI）", usesPerParticleOval(body))
        assertFalse("⛔ 不得再有 asAndroidPath（Path 方案已整体下线）", "asAndroidPath" in body)
        assertFalse("⛔ 不得再持有 android Path", "android.graphics.Path" in body)
        // 合批通道：必须真的用 drawPoints
        val draw = funBody(body, "drawPointBatches")
        assertTrue("drawPointBatches 必须能切出来（空转自证）", draw.isNotEmpty())
        assertTrue("必须调用原生 drawPoints", "drawPoints(" in draw)
        assertTrue("必须用 Cap.ROUND（圆点而非方块）", "Cap.ROUND" in body)
        assertTrue("必须按点直径设置 strokeWidth", "strokeWidth" in draw)
        // ⛔ 负向（喂同一份谓词）：旧写法必须被抓
        assertTrue("负向 N3：旧的逐粒子 addOval 必须被 addOval 判据抓到",
            usesPerParticleOval(OLD_SNIPPET_ADD_OVAL))
    }

    // ═══════════════════════════ ⑤ P3 全局量外提 ═══════════════════════════

    @Test
    fun `⑤ P3 全局量必须在逐粒子扫描之外算一次`() {
        val body = classBody(codeOfE23(), "LyricsDotMatrixRenderer")
        val draw = funBody(body, "drawContent")
        val fill = funBody(body, "fillLine")
        assertTrue("drawContent 必须能切出来（空转自证）", draw.isNotEmpty())
        assertTrue("fillLine 必须能切出来（空转自证）", fill.isNotEmpty())
        assertTrue("drawContent 必须算 sinB",
            Regex("""val\s+sinB\s*=\s*sin\(""").containsMatchIn(draw))
        assertTrue("drawContent 必须算 amp",
            Regex("""val\s+amp\s*=\s*2\.2f""").containsMatchIn(draw))
        // ⛔ 循环体内不得再重算（旧实现每点算一遍 = 7,200 次冗余）
        assertFalse("⛔ 逐粒子扫描内不得再算 frame.bass.coerceIn", "frame.bass.coerceIn" in fill)
        assertFalse("⛔ 逐粒子扫描内不得再声明 amp",
            Regex("""val\s+amp\s*=""").containsMatchIn(fill))
        assertTrue("breath 必须用提好的 sinB", "frameSinB * 0.10f" in body)
        assertTrue("floatY 的逐点 sin 必须保留（提不了）",
            "sin(frameGlobalT * 2.0f + localXs[i] * 10f)" in fill)
    }

    // ═══════════════════════════ ⑥ P4 死代码 ═══════════════════════════

    @Test
    fun `⑥ P4 死代码必须已删`() {
        val body = classBody(codeOfE23(), "LyricsDotMatrixRenderer")
        val measure = funBody(body, "measureLine")
        assertFalse("⛔ phaseVal 必须已删（零引用）", "phaseVal" in measure)
        assertFalse("⛔ baseSize 必须已删（恒 1.0f）", "baseSize" in measure)
        assertFalse("⛔ textLen 必须彻底删除（形参 + 两处实参）", "textLen" in body)
    }

    // ═══════════════════════ ⑦ 卡拉OK 必须保留（§13.5-D4）═══════════════════════

    @Test
    fun `⑦ 卡拉OK 三档亮度、分档阈值与守卫注释必须保留`() {
        val body = classBody(codeOfE23(), "LyricsDotMatrixRenderer")
        val bright = funBody(body, "brightnessOf")
        val tier = funBody(body, "tierOf")
        // 三档亮度分支（§B7 专项评估 ①）
        assertTrue("必须有 `!lineHasLyrics -> 0.9f`", "!lineHasLyrics -> 0.9f" in bright)
        assertTrue("必须有 `!lineIsSinging ->`（待唱行）", "!lineIsSinging ->" in bright)
        assertTrue("必须有卡拉OK 前沿判定 `localX - frameKProgress`",
            "localX - frameKProgress" in bright)
        assertTrue("必须有已唱段亮度 0.78f", "0.78f" in bright)
        assertTrue("必须有前沿字亮度 1.0f", "1.0f" in bright)
        // 三档量化
        assertTrue("tier 必须含 0.88f 阈值", "brightness * visibility > 0.88f" in tier)
        assertTrue("tier 必须含 0.45f 阈值", "brightness * visibility > 0.45f" in tier)
        // 3 档 × 2 层的结构常量必须还在
        assertTrue("必须保留 LAYER_CORE / LAYER_GLOW 两层",
            "LAYER_CORE" in body && "LAYER_GLOW" in body)
        assertTrue("必须保留 3 档 × 2 层 × 2 行的子批次结构",
            "2 * 3 * 2 * VIS_BUCKETS" in body)
        // 守卫注释（§15.7 第 18 条）—— 在**未剥注释**的原文里找
        val raw = readFile(renderersFile("LyricsDotMatrixRenderer.kt"))
        assertTrue("必须有「不得以性能为由删除」守卫注释（§15.7 第 18 条）",
            "不得以“性能”为由删除" in raw)
    }

    // ═══════════════════════════ ⑧ Q2 亮档去过曝 ═══════════════════════════

    @Test
    fun `⑧ Q2 亮档 alpha 必须已降过曝`() {
        val draw = funBody(classBody(codeOfE23(), "LyricsDotMatrixRenderer"), "drawPointBatches")
        assertTrue("亮档 core 峰值必须 0.62 + pulse×0.14（峰值 0.76）",
            "0.62f + pulse * 0.14f" in draw)
        assertTrue("亮档外辉光必须提到 0.26",
            "0.26f + pulse * 0.10f" in draw)
        assertFalse("⛔ 不得再有旧的 0.80f + pulse * 0.18f",
            "0.80f + pulse * 0.18f" in draw)
        // 其余四档的 alpha 也不许漂
        assertTrue("暗档 core alpha 必须 0.45", "0.45f" in draw)
        assertTrue("暗档 glow alpha 必须 0.10", "0.10f" in draw)
        assertTrue("中档 core alpha 必须 0.60", "0.60f" in draw)
        assertTrue("中档 glow alpha 必须 0.22", "0.22f" in draw)
    }

    // ═══════════════════════════ ⑨ Q4 postFx ═══════════════════════════

    @Test
    fun `⑨ Q4 postFx 字面量在位且判据正负双证`() {
        val body = classBody(codeOfE23(), "LyricsDotMatrixRenderer")
        assertTrue("postFx 必须含 > 0 的数值字面量（门禁判据）", coveredByPostFx(body))
        assertTrue("必须是暗角 0.44", "vignette = 0.44f" in body)
        assertTrue("必须是颗粒 0.026", "grain = 0.026f" in body)
        // ⛔ 负向：具名常量必须静默判否（门禁只认字面量 —— 已知陷阱）
        assertFalse("⛔ 具名常量必须判否（否则门禁会静默放过）",
            coveredByPostFx("class X : RendererFx() {\n    override val postFx = PostFx(vignette = V, grain = G)\n}"))
        assertFalse("PostFx.NONE 必须判否",
            coveredByPostFx("class X : RendererFx() {\n    override val postFx = PostFx.NONE\n}"))
    }

    // ═══════════════════════════ ⑩ 行为：按行配额 ═══════════════════════════

    @Test
    fun `⑩ 行为 - calculateRowQuota 按占比配额且非空行至少 1`() {
        // 3 行：1 / 3 / 0 个有效像素，cap = 100
        val rows = intArrayOf(1, 3, 0)
        val q = C.calculateRowQuota(rows, 4, 100)
        assertEquals("空行配额必须为 0", 0, q[2])
        assertTrue("非空行配额必须 ≥ 1", q[0] >= 1 && q[1] >= 1)
        assertTrue("占比大的行配额必须更大（3 : 1）", q[1] > q[0])
        assertEquals("1/4 占比 ⇒ 25", 25, q[0])
        assertEquals("3/4 占比 ⇒ 75", 75, q[1])
        assertTrue("total = 0 ⇒ 全 0", C.calculateRowQuota(rows, 0, 100).all { it == 0 })
        assertTrue("cap = 0 ⇒ 全 0", C.calculateRowQuota(rows, 4, 0).all { it == 0 })
        // ⛔ 负向（喂同一份谓词）：把「非空行均分」当候选 ⇒ 它不是按占比
        val flat = intArrayOf(50, 50, 0)
        assertTrue("按占比的配额必须与「非空行均分」不同（判据有区分力）",
            q[0] != flat[0] || q[1] != flat[1])
    }

    // ═══════════════════════════ ⑪ 行为：卡拉OK 曲线 ═══════════════════════════

    @Test
    fun `⑪ 行为 - karaokePacing 单调、端点固定、快起慢落`() {
        assertEquals("p(0) 必须为 0", 0f, C.karaokePacing(0f), 1e-6f)
        assertEquals("p(1) 必须为 1", 1f, C.karaokePacing(1f), 1e-6f)
        // 逐点数值（三次缓出 `1-(1-u)³` 的解析值；⛔ 硬编码期望值，不复制算法）
        assertEquals("p(0.25) = 1 - 0.75³", 0.578125f, C.karaokePacing(0.25f), 1e-6f)
        assertEquals("p(1/3) = 1 - (2/3)³", 0.7037037f, C.karaokePacing(1f / 3f), 1e-5f)
        assertEquals("p(0.5) = 1 - 0.5³", 0.875f, C.karaokePacing(0.5f), 1e-6f)
        // 单调递增
        var prev = -1f
        for (i in 0..20) {
            val v = C.karaokePacing(i / 20f)
            assertTrue("必须单调递增（i=$i）", v >= prev - 1e-6f)
            prev = v
        }
        // 快起慢落：前 1/3 进度已过 0.70（线性只有 0.333）
        assertTrue("必须快起慢落（p(1/3) > 0.70）实际 ${C.karaokePacing(1f / 3f)}",
            C.karaokePacing(1f / 3f) > 0.70f)
        // 越界钳制
        assertEquals("越界必须钳到 0", 0f, C.karaokePacing(-1f), 1e-6f)
        assertEquals("越界必须钳到 1", 1f, C.karaokePacing(2f), 1e-6f)
        // ⛔ 负向（喂同一份谓词）：线性实现必须与生产不同
        val linear = { u: Float -> u.coerceIn(0f, 1f) }
        assertTrue("线性实现不得被判为等同（区分力自证）",
            abs(C.karaokePacing(0.5f) - linear(0.5f)) > 1e-3f)
    }

    // ══════════════ ⑫ 逐帧进度覆盖（卡拉OK 不再 1 Hz 跳格）══════════════

    @Test
    fun `⑫ 行为 - 行内进度覆盖只在屏上那一行命中时生效`() {
        // 行索引对得上 ⇒ 用覆盖值
        assertEquals("命中时必须用覆盖值", 0.42f,
            C.resolveLyricProgress(3, 0.42f, 3, 0.9f), 1e-6f)
        // 行索引对不上 ⇒ 回退到 1 Hz 的值（屏上那一行与宿主推的那一行可能差一帧）
        assertEquals("行索引不匹配必须回退", 0.9f,
            C.resolveLyricProgress(3, 0.42f, 4, 0.9f), 1e-6f)
        // 无覆盖 ⇒ 回退
        assertEquals("无覆盖必须回退", 0.9f,
            C.resolveLyricProgress(-1, 0.42f, 3, 0.9f), 1e-6f)
        // 覆盖值必须被夹到 0..1
        assertEquals("覆盖越界必须钳到 1", 1f,
            C.resolveLyricProgress(3, 5f, 3, 0f), 1e-6f)
        assertEquals("覆盖越界必须钳到 0", 0f,
            C.resolveLyricProgress(3, -5f, 3, 1f), 1e-6f)
        assertEquals("回退值也必须被夹住", 1f,
            C.resolveLyricProgress(-1, 0f, 3, 7f), 1e-6f)
        // ⛔ 负向（喂同一份谓词）：「永远用回退值」的破实现必须被区分出来
        val alwaysFallback = { _: Int, _: Float, _: Int, fb: Float -> fb.coerceIn(0f, 1f) }
        assertTrue("永远回退的破实现不得被判为等同（区分力自证）",
            abs(alwaysFallback(3, 0.42f, 3, 0.9f) - C.resolveLyricProgress(3, 0.42f, 3, 0.9f)) > 1e-3f)
    }

    @Test
    fun `⑬ 行为 - 行内进度换算与 computeLyricInfo 同口径`() {
        assertEquals("行首必须是 0", 0f, C.lineProgressOf(10.0, 14.0, 10.0), 1e-6f)
        assertEquals("行中必须是 0.5", 0.5f, C.lineProgressOf(10.0, 14.0, 12.0), 1e-6f)
        assertEquals("行尾必须是 1", 1f, C.lineProgressOf(10.0, 14.0, 14.0), 1e-6f)
        assertEquals("未到行首必须钳到 0", 0f, C.lineProgressOf(10.0, 14.0, 3.0), 1e-6f)
        assertEquals("过行尾必须钳到 1", 1f, C.lineProgressOf(10.0, 14.0, 99.0), 1e-6f)
        assertEquals("零长行必须给 1（与 computeLyricInfo 同口径）",
            1f, C.lineProgressOf(10.0, 10.0, 10.0), 1e-6f)
        // ⛔ 负向：线性但**不钳位**的破实现必须被区分出来
        val unclamped = { s: Double, e: Double, t: Double -> ((t - s) / (e - s)).toFloat() }
        assertTrue("不钳位的破实现不得被判为等同（区分力自证）",
            abs(unclamped(10.0, 14.0, 99.0) - C.lineProgressOf(10.0, 14.0, 99.0)) > 1e-3f)
    }

    // ══════════════ ⑭ 子批次结构与分档粒度（逐字消散/凝聚不得退化）══════════════

    @Test
    fun `⑭ 子批次结构必须是 2 行 × 3 档 × 2 层 且 分档粒度保留逐字效果`() {
        assertEquals("可见度分档数必须是 16", 16, C.VIS_BUCKETS)
        assertEquals("子批次总数 = 2 行 × 3 档 × 2 层 × 分档数",
            2 * 3 * 2 * 16, C.SUB_BATCHES)
        // 索引必须互不相同且落在范围内
        val seen = HashSet<Int>()
        for (line in 0..1) for (tier in 0..2) for (layer in 0..1) for (b in 0 until C.VIS_BUCKETS) {
            val idx = C.subIndex(line, tier, layer, b)
            assertTrue("子批次索引必须落在 [0, SUB_BATCHES)", idx in 0 until C.SUB_BATCHES)
            assertTrue("子批次索引必须互不相同（line=$line tier=$tier layer=$layer b=$b）",
                seen.add(idx))
        }
        assertEquals("必须恰好用满全部子批次", C.SUB_BATCHES, seen.size)
        // 分档必须单调、且能区分不同的可见度 ⇒ 逐字消散/凝聚的粒度保留
        var prev = -1
        for (i in 0..20) {
            val v = i / 20f
            val b = C.visibilityBucketOf(v)
            assertTrue("分档必须单调（v=$v）", b >= prev)
            prev = b
        }
        assertTrue("可见度 0.25 与 0.75 必须落在不同档（否则逐字缩放退化成整行一起）",
            C.visibilityBucketOf(0.25f) != C.visibilityBucketOf(0.75f))
        assertEquals("可见度 1 必须落在最后一档", C.VIS_BUCKETS - 1, C.visibilityBucketOf(1f))
        assertEquals("可见度 0 必须落在第一档", 0, C.visibilityBucketOf(0f))
        // ⛔ 负向（喂同一份谓词）：「不分档」的破实现必须被判别出来
        val noBucket = { _: Float -> 0 }
        assertTrue("恒定 0 档的破实现不得被判为等同（区分力自证）",
            noBucket(0.75f) != C.visibilityBucketOf(0.75f))
    }

    // ══════════════ ⑮ 绘制路径零分配（缓冲只能在 onEnterContent 分配）══════════════

    @Test
    fun `⑮ 绘制路径必须零分配 - 缓冲只在 onEnterContent 里分配`() {
        val body = classBody(codeOfE23(), "LyricsDotMatrixRenderer")
        val drawFns = listOf(
            "drawContent", "measureLine", "fillLine", "drawPointBatches",
            "resetSubBatches", "finalizeSubBatches", "writePoint", "visibilityOf",
            "brightnessOf", "tierOf", "visibilityBucket"
        )
        for (name in drawFns) {
            val fn = funBody(body, name)
            assertTrue("$name 必须能切出来（空转自证）", fn.isNotEmpty())
            assertFalse("⛔ $name 内不得新建 FloatArray（缓冲只能在 onEnterContent 分配）",
                "FloatArray(" in fn)
            assertFalse("⛔ $name 内不得新建 IntArray", "IntArray(" in fn)
            assertFalse("⛔ $name 内不得用 listOf/mapOf", "listOf(" in fn || "mapOf(" in fn)
        }
        // 缓冲的分配点必须且只能在 onEnterContent（onExitContent 只做释放）
        val enter = funBody(body, "onEnterContent")
        val exit = funBody(body, "onExitContent")
        assertTrue("onEnterContent 必须分配点缓冲", "pointBuffers[i] = FloatArray(" in enter)
        assertTrue("onExitContent 必须释放点缓冲", "pointBuffers[i] = FloatArray(0)" in exit)
    }

    // ══════════════ ⑯ 字号规则：必须**按本行**算（2026-11-07 真机：文字太小）══════════════

    @Test
    fun `⑯ 字号必须按本行算 - 短行不得被全曲最长行拖小`() {
        // 生产纯函数（⛔ 门禁直接调 ⇒ 不复制算法）
        val short = LyricsDotMatrixRenderer.fontSizeFor(
            refAdvance = 900f, refLineHeight = 140f, w = 2712f, h = 1220f, cap = 3600
        )
        val long = LyricsDotMatrixRenderer.fontSizeFor(
            refAdvance = 3800f, refLineHeight = 140f, w = 2712f, h = 1220f, cap = 3600
        )
        assertTrue("短行的字号必须大于长行的字号：short=$short long=$long", short > long)
        // 上限 = 屏高 16%（档位默认 cap）
        assertEquals("短行必须顶到上限（屏高 16%）", 1220f * 0.16f, short, 0.01f)
        // 下限 = 屏高 5%（可读性底线）
        assertEquals("长行必须被夹到下限（屏高 5%）", 1220f * 0.05f, long, 0.01f)
        // ⛔ 负向自证：旧规则（拿全曲最长行当参考）会把短行压到长行那个字号
        val oldRuleShort = LyricsDotMatrixRenderer.fontSizeFor(
            refAdvance = 3800f, refLineHeight = 140f, w = 2712f, h = 1220f, cap = 3600
        )
        assertTrue(
            "旧规则（参考=全曲最长行）必须比新规则小 —— 否则本门禁没有鉴别力：" +
                "old=$oldRuleShort new=$short",
            oldRuleShort < short
        )
        // 高度约束：行高 ≤ 屏高 40%（极宽的参考行时由高度约束接手）
        val wide = LyricsDotMatrixRenderer.fontSizeFor(
            refAdvance = 100f, refLineHeight = 140f, w = 2712f, h = 1220f, cap = 3600
        )
        assertTrue("极短的参考行不得超过上限：$wide", wide <= 1220f * 0.16f + 0.01f)
    }

    // ══════════════ ⑰ 合批绘制不得用加色混合（实心白块）══════════════

    @Test
    fun `⑰ 合批绘制不得用加色混合 - 重叠点会累加成实心白块`() {
        val body = classBody(codeOfE23(), "LyricsDotMatrixRenderer")
        // ⛔ 加色混合：合批后一次绘制里有上千个圆盘 ⇒ 重叠处 α 累加 ⇒ 过曝成纯白
        assertFalse("⛔ 不得使用 BlendMode.PLUS", "BlendMode.PLUS" in body)
        assertFalse("⛔ 不得使用 PorterDuff.Mode.ADD", "Mode.ADD" in body)
        assertFalse("⛔ 不得再持有 PorterDuffXfermode", "PorterDuffXfermode" in body)
        assertFalse("⛔ 不得再持有 android.graphics.BlendMode", "graphics.BlendMode" in body)
        // 负向自证：旧写法必须被判据抓到
        assertTrue(
            "负向：旧写法（加色混合）必须被抓到",
            "BlendMode.PLUS" in OLD_SNIPPET_PLUS_BLEND
        )
    }

    // ══════════════ ⑱ 凝聚必须走得完（顶上去之后不得再聚一遍）══════════════

    @Test
    fun `⑱ 下行凝聚不得挂在 SHIFTING 阶段上 - 否则永远聚不完`() {
        val body = classBody(codeOfE23(), "LyricsDotMatrixRenderer")
        // ⛔ 凝聚开关不得由阶段决定（上移阶段只有 700 ms，而凝聚要 2 s+）
        assertFalse(
            "⛔ 凝聚开关不得由 `phase == SHIFTING` 决定",
            Regex("""lineIsCoalescing\s*=\s*phase\s*==""").containsMatchIn(body)
        )
        assertTrue("必须按下行采样时刻起算凝聚时钟", "line1CoalesceStartMs" in body)
        // 提升（顶替）前必须把凝聚收尾 ⇒ 顶上去的必须是成形的一行
        val promote = funBody(body, "promoteLine1ToLine0")
        assertTrue("promoteLine1ToLine0 必须能切出来（空转自证）", promote.isNotEmpty())
        assertTrue(
            "提升前必须把粒子吸附到目标位（否则顶上去的是半成品 ⇒ 又聚一遍）",
            "TX" in promote && "TY" in promote
        )
    }

    // ══════════════ ⑲ 行间距必须与字号解耦 ══════════════

    @Test
    fun `⑲ 行间距必须与字号解耦 - 字号一小两行就挤成一团`() {
        val body = classBody(codeOfE23(), "LyricsDotMatrixRenderer")
        assertFalse(
            "⛔ 行间距不得再用 `字号 * 1.4`（字号一小两行就重叠）",
            Regex("""lineHeightPx""").containsMatchIn(body)
        )
        assertTrue("必须用固定的行间距（屏高 20%）", "rowGapPx" in body)
    }

    // ═══════════════════ 负向 N1/N2/N3：源码判据必须能抓到旧写法 ═══════════════════

    @Test
    fun `负向N1 旧逐像素 getPixel 必须被判据抓到`() {
        assertTrue("负向 N1：旧 `b.getPixel(x, y)` 必须被 getPixel 判据抓到",
            usesGetPixel(OLD_SNIPPET_GETPIXEL))
        assertFalse("正向：新实现的 sampleLine 不得有 getPixel",
            usesGetPixel(funBody(classBody(codeOfE23(), "LyricsDotMatrixRenderer"), "sampleLine")))
        // ⛔ 词边界：`getPixels(` 不得被误判为 `getPixel(`
        assertFalse("判据不得误伤 getPixels（词边界）",
            usesGetPixel("b.getPixels(buf, 0, bmpW, 0, y, bmpW, 1)"))
    }

    @Test
    fun `负向N2 旧 coerceIn(1f,3f) 与 step 理想值必须被判据抓到`() {
        val oldStep = """
            val idealStep = kotlin.math.sqrt(estPixels / cap.toFloat())
                .coerceIn(1f, 3f).toInt().coerceAtLeast(1)
            val step = idealStep
        """.trimIndent()
        assertTrue("负向 N2a：旧 step 计算必须被 `coerceIn(1f, 3f)` 判据抓到",
            Regex("""coerceIn\(\s*1f\s*,\s*3f\s*\)""").containsMatchIn(oldStep))
        assertFalse("正向：新实现的 sampleLine 不得有 coerceIn(1f, 3f)",
            Regex("""coerceIn\(\s*1f\s*,\s*3f\s*\)""")
                .containsMatchIn(funBody(classBody(codeOfE23(), "LyricsDotMatrixRenderer"), "sampleLine")))
    }

    @Test
    fun `负向N3 旧逐粒子 addOval 必须被判据抓到`() {
        assertTrue("负向 N3：旧的逐粒子 addOval 必须被 addOval 判据抓到",
            usesPerParticleOval(OLD_SNIPPET_ADD_OVAL))
        assertFalse("正向：新实现不得有 addOval",
            usesPerParticleOval(classBody(codeOfE23(), "LyricsDotMatrixRenderer")))
        // ⛔ 词边界：注释里提到 addOval 不算（判据只跑剥过注释的源码）
        assertFalse("判据不得被注释里的 addOval 误伤（已剥注释）",
            usesPerParticleOval(stripComments("// 旧码用 addOval 画点")))
    }

    // ═══════════════════════════ 辅助 ═══════════════════════════

    /** 门禁原版判据（与 `FxCoverageScanTest` 逐字一致）：只认 `postFx` 里的**数值字面量** */
    private val postFxRe = Regex("""override\s+val\s+postFx\s*=\s*PostFx\(([^)]*)\)""")
    private val numRe = Regex("""=\s*([0-9]*\.?[0-9]+)f""")

    private fun coveredByPostFx(code: String): Boolean {
        val m = postFxRe.find(code) ?: return false
        return numRe.findAll(m.groupValues[1]).any { it.groupValues[1].toFloat() > 0f }
    }

    /** ⛔ 词边界：`getPixels(` 里 `getPixel` 后面是 `s`，不得匹配 */
    private fun usesGetPixel(code: String): Boolean =
        Regex("""(?<![A-Za-z0-9_])getPixel\(""").containsMatchIn(code)

    /** ⛔ 逐粒子椭圆（每帧上万次 JNI）—— 合批改造后必须归零 */
    private fun usesPerParticleOval(code: String): Boolean =
        Regex("""(?<![A-Za-z0-9_])addOval\(""").containsMatchIn(code)

    /** 旧实现片段（负向样本；**只**用于证明判据能抓到它） */
    private val OLD_SNIPPET_GETPIXEL = """
        for (y in 0 until bmpH step step) {
            var cnt = 0
            for (x in 0 until bmpW step step) {
                if (b.getPixel(x, y) and 0xFF000000.toInt() != 0) cnt++
            }
        }
    """.trimIndent()

    /** 旧的逐粒子 `addOval`（负向样本；**只**用于证明判据能抓到它） */
    private val OLD_SNIPPET_ADD_OVAL = """
        paths[coreIdx].asAndroidPath().addOval(
            px - size - size * 2f, py - size - size * 2f,
            px - size + size * 2f, py - size + size * 2f,
            android.graphics.Path.Direction.CCW)
    """.trimIndent()

    /** 旧的加色混合写法（负向样本；**只**用于证明判据能抓到它） */
    private val OLD_SNIPPET_PLUS_BLEND = """
        private fun applyPlusBlendApi29(paint: AndroidPaint) {
            paint.blendMode = android.graphics.BlendMode.PLUS
        }
    """.trimIndent()

    /** 切出 `class <name>` 的类体（到下一个顶层 class / 文件尾） */
    private fun classBody(txt: String, name: String): String {
        val m = Regex("""(?m)^\s*(?:(?:internal|open|abstract|private)\s+)*class\s+$name\b""").find(txt)
            ?: return ""
        val rest = txt.substring(m.range.last + 1)
        val nxt = Regex("""(?m)^\s*(?:(?:internal|open|abstract|private)\s+)*class\s+\w+""").find(rest)
        return if (nxt == null) txt.substring(m.range.first) else txt.substring(m.range.first, m.range.last + 1 + nxt.range.first)
    }

    /** 从**已剥注释**的类体里取某个函数的 `{...}` 体（签名要认 receiver `DrawScope.`） */
    private fun funBody(classBody: String, name: String): String {
        val m = Regex("""fun\s+(?:[A-Za-z0-9_.]+\.)?$name\s*\(""").find(classBody) ?: return ""
        val brace = classBody.indexOf('{', m.range.last)
        if (brace < 0) return ""
        var depth = 0
        var i = brace
        while (i < classBody.length) {
            when (classBody[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return classBody.substring(brace, i + 1)
                }
            }
            i++
        }
        return classBody.substring(brace)
    }

    /**
     * 去注释（**行注释 + 块注释（含嵌套）+ 字符串感知**）。
     * ⛔ 只去行注释不够 —— 判据会命中 KDoc 正文里举的写法（本仓库已踩 5 次）。
     */
    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '"') {
                sb.append(c); i++
                while (i < src.length) {
                    sb.append(src[i])
                    if (src[i] == '\\' && i + 1 < src.length) {
                        sb.append(src[i + 1]); i += 2; continue
                    }
                    i++
                    if (src[i - 1] == '"') break
                }
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                var depth = 1
                while (i < src.length && depth > 0) {
                    if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') {
                        depth++; i += 2
                    } else if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') {
                        depth--; i += 2
                    } else {
                        if (src[i] == '\n') sb.append('\n')
                        i++
                    }
                }
                continue
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /** `LyricsDotMatrixRenderer.kt` 的**已剥注释**全文 */
    private fun codeOfE23(): String = stripComments(readFile(renderersFile("LyricsDotMatrixRenderer.kt")))

    private fun readFile(f: File): String = f.readText()

    private fun renderersFile(name: String): File =
        File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers/$name")

    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir")!!)
        repeat(6) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 app/src/main/java")
    }
}
