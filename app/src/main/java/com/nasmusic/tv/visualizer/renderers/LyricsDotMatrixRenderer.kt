package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath
import kotlin.math.cos
import kotlin.math.sin

// ═══════════════════════════════════════════════════════════════════
// E23 歌词点阵（两行滚动 · 全息粒子版 · 性能优化）
// ═══════════════════════════════════════════════════════════════════

/**
 * 全息粒子歌词（两行滚动版）—— **E53「歌词舞台」的子模式 0**
 * （原 E23「歌词点阵」独立主题，2026-11-07 下线并入 E53）
 *
 * ⚠️ **本类不再是独立主题**（2026-11-07，所有者裁决）：E23 被 E53「歌词舞台」**吸收** ——
 * 它的画面就是 E53 的**子模式 0**，由 [com.nasmusic.tv.visualizer.renderers.lyricsStage.DotMatrixSubRenderer]
 * 委托本类画出 ⇒ 枚举项 `VisualizerTheme.LYRICS_DOT_MATRIX` 已删除，主题名随宿主为
 * `VisualizerTheme.LYRICS_STAGE`（下方 `theme` 覆写即为此故）。
 * ⛔ 老用户存过的 `"LYRICS_DOT_MATRIX"` 由 `AppSettings.kt` 的 `LEGACY_MAP` 回落到 E53。
 *
 * 动画流程：
 * 1. 初始：两行同时凝聚
 * 2. 第一行演唱（卡拉OK逐字点亮）
 * 3. 第一行唱完 → 从左到右逐字消散（稍慢）
 * 4. 第二行上移到第一行位置 + 新行从下方凝聚（从左到右逐字凝聚，稍慢）
 * 5. 循环
 *
 * ## §B7 优化（T4.7）—— 用户要求「以性能优化为主；画面优化不得增加性能消耗」
 *
 * **性能（P）—— 全部为净降耗**
 * - **P2** 两遍 `getPixel()` → **逐行 `getPixels()`**：JNI ≈ 71,000 → ≈ 346（每次换行）、
 *   耗时 35–70 ms → < 1 ms；额外内存仅 `IntArray(bmpW)` ≈ 7 KB（跨次复用）。
 * - **P3** 全局量提到循环外（`sinB` / `amp`）⇒ 每帧省 7,200 次 `sin` + ≈ 14,400 次乘加。
 *   ⚠️ `floatY` 里的 `sin(globalT*2 + localX*10)` 含逐点 `localX`，**提不了**。
 * - **P4** 删死代码：`phaseVal`（零引用）、`textLen`（未使用形参）、`baseSize`（恒 1.0f）。
 * - **P5** `paths.reset()` → `rewind()`（保留内部数据结构供快速复用）。
 *   ⛔ **2026-11-07 起本文件已无 `Path`**（见下方「合批改造」一节）⇒ P5 随之作废。
 * - **P6** `step` 3 → **1**（P2 之后采样已 < 1 ms ⇒ 近乎免费；同样 `cap` 个点覆盖更完整，
 *   点总数与每帧绘制成本**完全不变**）。
 *
 * **观感（Q）—— 零额外 draw / 分配**
 * - **Q2** 亮档去 `Plus` 过曝：core 峰值 0.98 → 0.76，省下的亮度给外辉光（亮部有边界）。
 * - **Q4** 后处理（暗角 0.44 + 颗粒 0.026；`FxLevel.OFF` 档位下整段零开销）。
 *
 * ## ⛔ 本轮不做的两项（§12.4 已登记偏差）
 * - **P1**（`dotPath` 池 + `addPath` 平移复用）：其收益是「消除每点 `Rect` 分配」，而
 *   **T1.6.2 已改用 `asAndroidPath().addOval(l, t, r, b)` float 重载** ⇒ `Rect` 分配早已为 0；
 *   P1 只剩「native 调用 9,000 → 7,416」的边际收益，却要付出半径 **6 档量化（≈ 8% 误差）**
 *   ⇒ 收益/代价不成立，**不做**。
 *   ⛔ **2026-11-07 起这条已被「合批改造」整体取代**（`addOval` 本身已下线 ⇒ P1 无对象）。
 * - **Q3**（4 档亮度 / 8 条 `Path`）：§B7 标注为**可选**（需先确认重建成本），收益小、复杂度高 ⇒ 不做。
 *
 * ## ⚠️ 低画质的降密度（v1.47 真机：本套 7 fps，全档最慢）
 * 本套的提交次数早已合批到位；贵的是每次提交里装的**点个数**。故低档只降 [capacityForTier]
 * 的粒子上限，并按 [fontMaxRatio] 同步收缩字号上限以维持点阵密度（不缩字号 = 低档文字出空洞）。
 *
 * ## ✅ 合批改造（2026-11-07）：逐粒子 `addOval` → `drawPoints`（E53 子模式 0 的帧率根因）
 *
 * ### 病根：每帧上万次 JNI 调用
 * 旧码为**每个粒子**调一次 `Path.addOval`（JNI）⇒ E53「歌词舞台」子模式 0
 * （粒子上限 7200/行）实测 **≈ 11,700 次/帧** ⇒ 该子模式只能跑 **≈30 fps**。
 * 且旧码把上万颗椭圆塞进 **6 条 `Path`** ⇒ 每帧还要为这条巨型路径做曲线细分。
 *
 * ### ✅ 改法：`Canvas.drawPoints(float[], offset, count, Paint)`
 * - `Cap.ROUND` + `strokeWidth = 点直径` ⇒ 每个点画成一个**实心圆盘**，
 *   与旧码的 `addOval`（圆心 `(px - size, py - size)`、半径 `2 * size`）**逐像素等价**；
 * - 每帧的 native 提交从「上万次」降到「几十次」（每个非空子批次一次）。
 * - ⛔ **不得**降粒子密度：那正是 2026-11-07 用户反馈「要再加多粒子」的诉求（见下一节）。
 *
 * ### ⚠️ `drawPoints` 的笔宽是**整批**的 ⇒ 半径必须分档
 * 旧码是**逐粒子**的半径（`size` 随 `visibility` 变）⇒ 合批后必须按半径分组。
 * 分档依据选的是 **`visibility`**（**逐字**量：消散 / 凝聚的逐字进度）⇒
 * 「逐字消散 / 逐字凝聚」的粒度**保留**（[VIS_BUCKETS] = 16 档 ⇒ 半径步进 6.25%），
 * 而不是退化成「整行一起缩放」。
 * ⚠️ 两行**分开统计**（12 个缓冲而不是 6 条 path）：逐字消散 / 凝聚期间两行的半径分布差异极大
 * （合并会让「正在消散」那行的半径污染另一行）⇒ 代表半径必须按行分开。
 *
 * ### ✅ 卡拉OK 扫描改为**逐帧**（同一轮修掉的第二个缺陷）
 * `ctx.lyricLineProgress` 来自 `PlayerManager` 的 **1 Hz** 进度 StateFlow
 * ⇒ 逐字扫描**每秒才跳一次**。宿主（E53 歌词舞台）手里的 `LyricsStagePayload`
 * 是**逐帧**更新的（`timeSec` = 逐帧读到的播放位置）⇒ 由它推给本渲染器
 * （[setLyricsProgressOverride]）。⚠️ 覆盖**带行索引**：宿主推的「当前行」与本渲染器
 * **屏上**那一行可能差一帧 ⇒ 只有行索引对得上时才应用（[resolveLyricProgress]）。
 * ⛔ E23 独立主题已下线 ⇒ **唯一宿主**（`DotMatrixSubRenderer`）逐帧推覆盖
 *    ⇒ 覆盖**总是**生效（本类不再有 1 Hz 的场景）。
 *
 * ## ✅ 点阵密度修复（2026-11-07，用户反馈「要再加多粒子，现在都不够组成文字」）
 *
 * ### 病根 ①：行内取点判据漏取约 46% ⇒ 字形成了「空心轮廓」
 * 旧判据 `((taken - 1) % takeEvery) < 0.5f` 里 `takeEvery = rowInk / targetCount` 是**小数**，
 * 取模后命中的位置**不再是等距的**（实测间隔在 14 / 29 / 43 个墨迹像素之间乱跳），
 * 且只命中 **≈54%** 的目标数 ⇒ 声明的 [CAP_HIGH]=3600 实际只落下 **1959** 个（LOW 档 900 → 479）。
 * 更糟的是落点**偏在笔画的一侧** ⇒ 每个笔画只剩一条边被点亮 ⇒ 整个字看着像「线框」而不是字。
 * ⛔ 这与本文件「每行 cap 个粒子」的声明**不符** —— 性能门禁
 *    （`LowTierElementBudgetTest` ②）也是按 `capacityForTier × 2 行 × 2 层` 建模的，
 *    即**门禁一直以为本套在画满预算**。
 * ✅ 改为 [isSegmentStart]：把本行墨迹像素**均匀分段**、每段取首个
 *    ⇒ 恰好取满 `targetCount` 个、且沿**笔画宽度方向**也铺开。
 *
 * ### 病根 ②：字号上限与粒子预算 `sqrt` 耦合 ⇒ 单纯加粒子会被字号吃掉
 * [fontMaxRatio] 按 `sqrt(cap)` 缩放（设计意图：低档减点时同步缩字号以维持密度）。
 * 但**加**密度时它会**顺带放大字号** ⇒ 新增的粒子被摊到更大的字上，密度反降。
 * 实测（TV 1920×1080，8 字行）：`cap 3600 → 7200` 且字号跟着涨 ⇒ 覆盖率 44.6% → **36.3%**（更差）；
 * 锁住字号上限（同为 7200）⇒ 覆盖率 **62.8%**。
 * ✅ 故字号上限改用 [fontBudgetCap]：**只有当实际预算比档位默认更小时才收缩字号**，
 *    加密度时字号**不动** ⇒ 密度必涨、字不会变大。
 *
 * ### 落点与实测（TV 1920×1080，`你说过不会离开我` 8 字 / 字号 172.8px）
 * | 状态 | 每行粒子 | 每字粒子 | 笔画覆盖率 |
 * |---|---|---|---|
 * | 修复前 | 1959 | 245 | **23.2%** |
 * | 修复后（cap 3600） | 3522 | 440 | **34.5%** |
 * | 修复后 + 歌词舞台加密度（cap 7200） | 7122 | 890 | **62.8%** |
 *
 * 数字由 `scripts/measure_lyrics_dot_matrix_density.java` 再生（⛔ 不许手抄）。
 *
 * ### 作用面
 * ⛔ 本类**不再作为独立主题**（2026-11-07 E23 下线并入 E53）⇒ 只作为 E53「歌词舞台」的
 * **子模式 0** 被 [com.nasmusic.tv.visualizer.renderers.lyricsStage.DotMatrixSubRenderer] 委托。
 * 「加密度」本身（病根 ②）做成**宿主可开关的每实例预算覆盖** [setParticleBudget]，
 * 由 E53 歌词舞台**显式开启**（中/高档 3600 → 7200）。
 *
 * ## ⛔ 卡拉OK 逐字亮度分档必须保留（§13.5-D4）
 * 它不是性能瓶颈，删了反而更慢 —— 详见 [brightnessOf] 内的守卫注释。
 *
 * ## ✅ 三处真机回归修复（2026-11-07，小米 22081212C / 2712×1220 横屏）
 *
 * ### ① 文字太小 ⇒ 字号改为**按本行**算（[fontFor] / [fontSizeFor]）
 *
 * 旧规则用**全曲最长行**当参考 ⇒ 一句长歌词把**整首歌**的字号压到下限
 * （实测 61 px = 屏高 5% ⇒ 一行字只占屏宽 18.7%）。新规则「本行占满屏宽 80%」
 * ⇒ 短行 195 px（占屏宽 ~60%）、长行自动变小但**完整**（不会被截成「…」）。
 *
 * ### ② 实心白块 ⇒ 混合模式改回 **SrcOver**（[pointPaint]）
 *
 * 合批后「一个绘制里有上千个圆盘」；加色混合 ⇒ 重叠处 α 累加 ⇒ 过曝成纯白。
 * 旧码是 `Path` 并集、每层只混合一次 ⇒ 颜色是档位色。改回 SrcOver ⇒ 与旧码同语义。
 *
 * ### ③ 「顶上去之后又聚了一遍」⇒ 凝聚时钟按**本行时间线** + 提升前收尾
 *
 * 旧码把凝聚挂在 `phase == SHIFTING`（只有 700 ms）⇒ 凝聚永远走不完 ⇒
 * 顶上去的是个半成品。改见 [line1CoalesceStartMs] 与 [promoteLine1ToLine0]。
 */
class LyricsDotMatrixRenderer : RendererFx() {

    override val theme = VisualizerTheme.LYRICS_STAGE

    /** §B7-Q4 收尾后处理（暗角 + 颗粒）。`FxLevel.OFF` 档位下整段零开销 */
    override val postFx = PostFx(vignette = 0.44f, grain = 0.026f)

    // ── 单行粒子数据（两行各一份） ──────────────────────────
    // 每粒子 8 float：x, y, vx, vy, tx, ty, size, phase
    private var line0: FloatArray = FloatArray(0)  // 上行（正在唱 / 正在消散）
    private var line1: FloatArray = FloatArray(0)  // 下行（待唱 / 正在上移）
    private var line0Count = 0
    private var line1Count = 0

    // 每个粒子的字索引（用于逐字消散/凝聚）
    private var line0CharIdx: IntArray = IntArray(0)
    private var line1CharIdx: IntArray = IntArray(0)
    // 行内归一化 x（0..1，用于卡拉OK点亮）
    private var line0LocalX: FloatArray = FloatArray(0)
    private var line1LocalX: FloatArray = FloatArray(0)

    // 行文字缓存
    private var line0Text: String = ""
    private var line1Text: String = ""

    // ── 点批次（3 档亮度 × 2 层 × 2 行 = 12 个缓冲）────────────────────
    //
    // ⛔ 2026-11-07：**逐粒子 `Path.addOval` 已下线**。
    //    旧码每帧为每个粒子调一次 `addOval`（JNI）⇒ E53「歌词舞台」子模式 0
    //    实测 ≈ 11,700 次/帧 ⇒ 该子模式只能跑 ≈30 fps。
    //    改为 `Canvas.drawPoints(float[], offset, count, Paint)`：
    //    `Cap.ROUND` + `strokeWidth = 点直径` ⇒ 与旧 `addOval` **逐像素等价**，
    //    而每帧的 native 提交从「上万次」降到「几十次」。
    //
    // 缓冲索引 = `(line * 3 + tier) * 2 + layer`（layer 0 = core，1 = glow）。
    private val pointBuffers = Array(12) { FloatArray(0) }

    /**
     * 子批次的**点数**（第一遍统计的结果）。
     *
     * 子批次 = 缓冲 × **可见度分档**（见 [VIS_BUCKETS]）：`drawPoints` 的 `strokeWidth`
     * 是**整批**的，而旧码是**逐粒子**的半径 ⇒ 必须把半径相近的粒子合成一批。
     * 分档依据是 `visibility`（**逐字**量：消散/凝聚的逐字进度）⇒
     * 分档后仍保留「逐字消散 / 逐字凝聚」的粒度，不会退化成整行一起缩放。
     */
    private val subCount = IntArray(SUB_BATCHES)

    /** 子批次的 `size` 累计（第一遍统计用；第二遍取均值当代表半径） */
    private val subSizeSum = FloatArray(SUB_BATCHES)

    /** 子批次的**代表半径**（= `size` 均值；core 批） */
    private val subRadius = FloatArray(SUB_BATCHES)

    /** 子批次在其缓冲内的**起点**（单位 = **点**；写点/提交时 ×2 换成 float 下标） */
    private val subOffset = IntArray(SUB_BATCHES)

    /** 子批次的**写入游标**（第二遍写点用） */
    private val subFill = IntArray(SUB_BATCHES)

    /**
     * 每粒子的档位/分档缓存（第一遍算出、第二遍复用）。
     * 编码 = `tier * VIS_BUCKETS + bucket`；`-1` = 该粒子本帧不画。
     * 前 [scratchStride] 个是上行、其后是下行。
     */
    private var tierScratch = ByteArray(0)

    /** 每档（3 档 × 2 层）的最终颜色（逐帧刷新 ⇒ **成员**复用，⛔ 逐帧零分配） */
    private val batchArgbs = IntArray(6)

    /** [tierScratch] 的每行长度（= 每行粒子上限） */
    private var scratchStride = 0

    // ── 画笔（构造期成员 ⇒ 逐帧零分配）───────────────────────────

    /**
     * ⭐ 逐点合批的**唯一**画笔（`drawPoints` 用）。
     *
     * ⚠️ `strokeWidth` = 点的**直径**（旧码 `addOval` 的半径是 `2 * size` ⇒ 直径 `4 * size`），
     *    `Cap.ROUND` ⇒ 每个点画成一个**实心圆盘** ⇒ 与旧码逐像素等价。
     * ⛔ `Style.STROKE` 是必需的：`drawPoints` 只有在**描边**画笔下才按
     *    `strokeWidth` 画圆点（填充画笔画的是 1~2 px 的小方块）。
     *
     * ## ⛔⛔ 混合模式必须是**默认的 SrcOver**（2026-11-07 真机：实心白块）
     *
     * 旧码（`fbcfd5f`）把上千个椭圆塞进 **6 条 `Path`** ⇒ `drawPath` 对每条路径
     * **填一次** ⇒ 一层只混合一次 ⇒ 笔画是**并集**、颜色是**档位色**。
     *
     * 合批后是「每个点一次 `drawPoints`」⇒ 一次绘制里有**上千个圆盘**：
     * - **加色混合（`BlendMode.Plus`）** ⇒ 每个圆盘都往目标像素**加一次** ⇒
     *   重叠处 α 累加 ⇒ 直接**过曝成纯白** ⇒ 「点阵」糊成**实心白块**；
     * - **SrcOver（默认）** ⇒ 重叠处取**最上面那个** ⇒ 与旧码的「并集填一次」等价 ⇒
     *   保留档位色 ⇒ **点阵的颗粒感回来**。
     *
     * ⛔ 因此**不得**再设 `xfermode` / `blendMode`（加色）。
     * ⛔ 亮度补偿：旧码的「core + glow 相加」在 SrcOver 下变成「core 盖住 glow」
     *   ⇒ 笔画略暗一档 ⇒ 由 [drawPointBatches] 的「先 glow 后 core」顺序保住外辉光环。
     */
    private val pointPaint = AndroidPaint().apply {
        style = AndroidPaint.Style.STROKE
        strokeCap = AndroidPaint.Cap.ROUND
        isAntiAlias = true
    }

    // ── 本帧共享量（逐帧刷新；只在本渲染器内使用）─────────────────
    private var frameKProgress = 0f
    private var frameBassPulse = 1f
    private var frameGlobalT = 0f
    private var frameSinB = 0f
    private var frameAmp = 0f
    private var frameEnergy = 0f

    // ── 逐行绘制参数（逐帧重设；**两行轮流用同一份** ⇒ 调用前必须先重设）──
    //
    // ⚠️ **刻意用扁平的成员字段而不是一个嵌套 class** —— 本仓库的门禁
    //    （`LyricsDotMatrixTest` 的 `classBody`）按「下一个**顶层** class」截断类体，
    //    类里再嵌一个 class 就会把类体截断 ⇒ 门禁读不到后面的函数。
    private var lineIsSinging = false
    private var lineIsScattering = false
    private var lineScatterElapsedMs = 0L
    private var lineIsCoalescing = false
    private var lineCoalesceElapsedMs = 0L
    private var lineYOffset = 0f
    private var lineHasLyrics = false

    // ── §B7-P2 采样缓冲（首次采样时分配一次，此后复用 ⇒ 零分配）──
    /** 逐行批读缓冲（长度 ≥ `bmpW`）。⛔ 只读**当前行**，不整图读（§B7 R12） */
    private var rowBuf: IntArray? = null

    // ── 状态机 ──────────────────────────────────────────────
    private enum class Phase {
        INIT_COALESCE,      // 初始：两行同时凝聚
        STEADY,             // 稳定：第一行演唱中，第二行待机
        SCATTERING_TOP,     // 第一行逐字消散
        SHIFTING,           // 行上移 + 新行凝聚
    }
    private var phase = Phase.INIT_COALESCE
    private var phaseStartMs = 0L

    // 显示的行索引（第一行对应第几句歌词）
    private var displayedLineIndex = -1
    /** 已绑定的歌曲 id：renderer 实例跨歌曲复用，需靠它判断切歌 */
    private var boundSongId: String? = null
    /** 上一首歌的标题：songId 为 null 时作为兜底判据依据 */
    private var boundCaption: String? = null

    // 行偏移（用于行移动动画，0=正常位置，-1=上移一行）
    private var rowOffsetY = 0f  // 像素偏移量

    // 行间距（像素）：**固定**为屏高 20%（与字号无关）
    //
    // ⚠️ 旧码用 `字号 * 1.4` 当行距 ⇒ 字号一小两行就挤成一团、字号一大两行就重叠。
    //    行距是**版面**量、字号是**排版**量 ⇒ 必须解耦。
    private var rowGapPx = 0f
    private var topY = 0f    // 上行 Y 中心
    private var bottomY = 0f // 下行 Y 中心

    /**
     * 字号测量用的画笔（**成员** ⇒ 测量路径零分配；只在换行时用一次）。
     *
     * ⚠️ 测量必须用**固定参考字号** [REF_FONT]：字宽与行高都是字号的线性量，
     *    先在参考字号下量、再折算 ⇒ 换行时重算一次即可（不必每帧量）。
     */
    private val measurePaint = AndroidPaint().apply {
        isAntiAlias = false
        textSize = REF_FONT
        typeface = Typeface.DEFAULT_BOLD
        isFakeBoldText = true
    }

    // 动画时长
    private val initCoalesceMs = 1200L        // 初始两行凝聚（放慢，便于看清）
    private val scatterPerCharMs = 90L   // 每字消散延迟
    private val scatterFlyMs = 600L      // 单字飞散时长
    private val shiftMs = 700L           // 行上移动画时长
    private val coalescePerCharMs = 160L // 每字凝聚延迟（放慢）
    private val coalesceArriveMs = 1200L // 单字凝聚到达时长（放慢）

    // 即将进入的下下行文本（在 SHIFTING 阶段使用）
    private var incomingNextText: String = ""

    /**
     * 下行「凝聚」动画的**起点**（= 下行被采样的时刻）。
     *
     * ⛔⛔ 凝聚进度必须按**本行自己的时间线**算，不能挂在 `phase == SHIFTING` 上：
     *    上移阶段只有 700 ms，而凝聚需要 `160·字数 + 1200 ms`（8 字 ≈ 2.3 s）
     *    ⇒ 挂在阶段上 ⇒ 凝聚**永远走不完** ⇒ 行顶上去时是个没聚完的行
     *    ⇒ 用户看到「顶上去之后又聚了一遍」。
     */
    private var line1CoalesceStartMs = 0L

    // 每行粒子数上限：高/中档取最高密度，低档必须降（见 companion 的 capacityForTier）
    private fun capacityFor(ctx: RenderContext): Int = effectiveCapacity(capacityForTier(ctx.quality))

    /**
     * 每行粒子上限的**宿主覆盖**（0 = 用档位默认，见 [capacityForTier]）。
     *
     * ⛔ 只改**粒子数**，不改**字号**（字号上限走 [fontBudgetCap]）⇒ 覆盖调大时密度必涨、字不会变大。
     */
    private var particleBudgetOverride = 0

    /**
     * 实际生效的每行粒子上限（**纯函数式**，门禁可直接调）。
     *
     * @param tierCap 档位默认上限（见 [capacityForTier]）
     */
    internal fun effectiveCapacity(tierCap: Int): Int =
        if (particleBudgetOverride > 0) particleBudgetOverride else tierCap

    /**
     * 设置每行粒子上限覆盖（<= 0 表示恢复档位默认）。
     *
     * 供宿主（如 E53「歌词舞台」子模式 0）按需**加密度**用：
     * ⚠️ 唯一调用方是 E53「歌词舞台」的子模式 0 ⇒ 它**总是**被调用（中/高档 ×2）。
     *
     * @param perLine 每行粒子数上限
     */
    internal fun setParticleBudget(perLine: Int) {
        particleBudgetOverride = perLine.coerceIn(0, MAX_PARTICLE_BUDGET)
    }

    // ── 逐帧行内进度覆盖（宿主推入）──────────────────────────────

    /**
     * **逐帧**行内进度覆盖（0..1）。`-1f` = 无覆盖。
     *
     * ⛔ [RenderContext.lyricLineProgress] 是 **1 Hz** 的（它来自 `PlayerManager` 的
     *    1 Hz 进度 StateFlow）⇒ 卡拉OK 的逐字扫描**每秒才跳一次**。
     *    E53「歌词舞台」的 `LyricsStagePayload` 则是**逐帧**更新的
     *    （`timeSec` = 逐帧读到的播放位置）⇒ 宿主可把它推给本渲染器。
     */
    private var progressOverride = -1f

    /**
     * 覆盖所属的**歌词行索引**（与 [RenderContext.lyricLineIndex] 同一套索引）。
     * `-1` = 无覆盖。
     *
     * ⚠️ **必须带行索引**：宿主推的是「当前正在唱的那一行」的进度，
     *    而本渲染器**屏上**那一行未必是它（`ctx.lyricLineIndex` 是 1 Hz 的、
     *    `payload.currentIndex` 是逐帧的 ⇒ 两者可差一帧）⇒
     *    只有行索引对得上时才应用覆盖（见 [resolveLyricProgress]）。
     */
    private var progressOverrideLine = -1

    /**
     * 设置逐帧行内进度覆盖。
     *
     * @param lineIndex 该进度所属的歌词行索引
     * @param progress 行内进度（0..1，超出范围会被夹住）
     */
    internal fun setLyricsProgressOverride(lineIndex: Int, progress: Float) {
        progressOverrideLine = lineIndex
        progressOverride = progress
    }

    /** 清除逐帧行内进度覆盖 ⇒ 回退到 [RenderContext.lyricLineProgress]（1 Hz） */
    internal fun clearLyricsProgressOverride() {
        progressOverrideLine = -1
        progressOverride = -1f
    }

    /**
     * 本渲染器**当前正在唱**（卡拉OK 扫过）的那一行在歌词表里的索引。
     *
     * ⚠️ 它是**本渲染器自己的行号**（[displayedLineIndex]），未必等于宿主的
     *    `payload.currentIndex` ⇒ 宿主推覆盖时必须用它来配对。
     */
    internal fun singingLineIndex(): Int = displayedLineIndex

    /**
     * 字号上限所用的预算档（见 companion 的 [fontBudgetCap]）。
     *
     * ⚠️ 减点 ⇒ 沿用档位默认（同步缩字号，维持密度）；
     *    加点 ⇒ 字号**不动** ⇒ 密度必涨、字不会变大。
     */
    private fun fontCapFor(ctx: RenderContext): Int =
        fontBudgetCap(capacityForTier(ctx.quality), capacityFor(ctx))

    // ── 自适应字号 ──────────────────────────────────────────
    // 规则（**按本行**算 ⇒ 每行都占满屏宽 80%）：
    //   1. 用**本行文本**在参考字号下量出字宽 → 折算出「占满屏宽 80%」的字号；
    //   2. 约束：行高 ≤ 屏高 40%（预留两行显示空间）；
    //   3. 硬性区间 [MIN, MAX]：字号过大→粒子太稀→文字空洞，故上限随该档粒子数收缩。
    //
    // ## ⛔⛔ 为什么**不能**用「全曲最长行」当参考（2026-11-07 真机：文字太小了）
    //
    // 旧码的参考行是 `ctx.longestLyricLine`（全曲**最长**的一句）⇒
    // 「最长行占满屏宽 80%」把**所有**行都按那一句的字号缩小 ⇒
    // 只要歌里有一句特别长的，整首歌的字幕就都是**蚂蚁大小**：
    // - 旧截图那首歌的最长行 12 字 ⇒ 字号顶到**上限** `0.16·h = 195 px`（大）；
    // - 新截图那首歌的最长行 ≈38 字 ⇒ 字号压到**下限** `0.05·h = 61 px`（小，实测 508 px/屏宽 18.7%）。
    //
    // ⇒ 改成「**按本行**算」后：短行自己占满屏宽 80%（大），长行自己占满 80%（小但完整），
    //   **任何一行都不会被别的行拖小、也不会被截断成「…」**（宽度约束按行成立 ⇒ 必然放得下）。
    private fun fontFor(text: String, w: Float, h: Float, cap: Int): Float {
        if (text.isBlank()) return h * MIN_FONT_S
        val fm = measurePaint.fontMetrics
        val refLineHeight = (fm.bottom - fm.top).coerceAtLeast(1f)
        val refAdvance = measurePaint.measureText(text).coerceAtLeast(REF_FONT * 0.1f)
        return fontSizeFor(refAdvance, refLineHeight, w, h, cap)
    }

    protected override fun onEnterContent(ctx: RenderContext) {
        val cap = capacityFor(ctx)
        line0 = FloatArray(cap * STRIDE)
        line1 = FloatArray(cap * STRIDE)
        line0CharIdx = IntArray(cap)
        line1CharIdx = IntArray(cap)
        line0LocalX = FloatArray(cap)
        line1LocalX = FloatArray(cap)
        // 点批次缓冲：每个（行 × 档 × 层）一批，每批 ≤ 该行粒子上限个点（2 float/点）
        // ⚠️ 只在这里分配一次 ⇒ 绘制路径零分配（`PerfBudgetContractTest` 的硬要求）。
        for (i in pointBuffers.indices) pointBuffers[i] = FloatArray(cap * 2)
        tierScratch = ByteArray(cap * 2)
        scratchStride = cap
        line0Count = 0
        line1Count = 0
        line0Text = ""
        line1Text = ""
        phase = Phase.INIT_COALESCE
        phaseStartMs = 0L
        displayedLineIndex = -1
        rowOffsetY = 0f
        boundSongId = null
        boundCaption = null
        // 切歌/重进：凝聚时钟归零（下一帧按新行重新起算）
        line1CoalesceStartMs = 0L
    }

    protected override fun onExitContent() {
        line0 = FloatArray(0)
        line1 = FloatArray(0)
        line0CharIdx = IntArray(0)
        line1CharIdx = IntArray(0)
        line0LocalX = FloatArray(0)
        line1LocalX = FloatArray(0)
        for (i in pointBuffers.indices) pointBuffers[i] = FloatArray(0)
        tierScratch = ByteArray(0)
        scratchStride = 0
        line0Count = 0
        line1Count = 0
    }

    // ── 文字采样 ────────────────────────────────────────────

    /**
     * 对单行文字进行采样，将结果写入目标数组。
     * @return 采样到的粒子数
     */
    private fun sampleLine(
        text: String,
        target: FloatArray,
        charIndices: IntArray,
        localXs: FloatArray,
        cap: Int,
        canvasW: Float,
        fontSize: Float,
        targetY: Float   // 行的目标 Y 位置（文字垂直中心）
    ): Int {
        if (text.isBlank()) return 0

        val paint = AndroidPaint().apply {
            isAntiAlias = false
            textSize = fontSize
            color = android.graphics.Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            isFakeBoldText = true
        }

        val maxLineWidth = canvasW * 0.88f
        val displayText = if (paint.measureText(text) <= maxLineWidth) {
            text
        } else {
            var trimmed = text
            while (trimmed.isNotEmpty() && paint.measureText(trimmed + "…") > maxLineWidth) {
                trimmed = trimmed.dropLast(1)
            }
            trimmed + "…"
        }

        val textWidth = paint.measureText(displayText)

        // 标准基线计算：让字形在 bitmap 内完整绘制
        // baseline 置于字形顶部处，则字形占据 [0, fontHeight] 区间，绝无截断
        val fm = paint.fontMetrics
        val fontHeight = fm.bottom - fm.top          // 正数（最高字形到最低字形）
        // baseline 放在 y = -fm.top 处，使字形顶部贴合 y=0
        val baselineY = -fm.top
        // bitmap 高度：精确等于字形总高度 + 少量上下留白
        val bmpH = (fontHeight + fontSize * 0.4f).toInt().coerceAtLeast(40)
        // 宽度留白
        val bmpW = (textWidth * 1.15f).toInt().coerceAtLeast(100)
        // T7 修复（2026-09-13）：try-finally 包裹 Bitmap 创建与回收，避免异常路径泄漏
        // 采样结果（n/minX/maxX/minY/maxY）与 bmp 声明在 try 外，
        // 供 finally 回收与 try 之后的坐标映射段访问
        var n = 0
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var bmp: Bitmap? = null
        try {
        val b = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        bmp = b
        val canvas = AndroidCanvas(b)

        val xOff = (bmpW - textWidth) / 2
        canvas.drawText(displayText, xOff, -fm.top, paint)

        // §B7-P6：采样改逐行批读后（P2）采样耗时已 < 1 ms ⇒ `step` 恒 1。
        // 旧的 `coerceIn(1f, 3f)` 是为了限制采样耗时而故意加粗的，正是"点阵有空洞 / 笔画断"的直接原因。
        // ⚠️ 点总数不变（仍 `cap`/行）⇒ **每帧绘制成本完全不变**，只是同样多的点覆盖更完整。
        val step = 1

        // 计算每个字符的 x 范围（用于字索引映射）
        val charXBounds = FloatArray(displayText.length + 1)
        charXBounds[0] = xOff
        for (i in displayText.indices) {
            charXBounds[i + 1] = xOff + paint.measureText(displayText.substring(0, i + 1))
        }

        // ── 采样 ──────────────────────────────────────────────
        // 关键修复：不能按行从顶部扫到底部时用 cap 截断（会丢失字形下半部）。
        // 改为两遍采样：
        //   第一遍：收集全部有效像素的坐标到临时数组；
        //   第二遍：按字形自然密度均匀抽取 cap 个，保证垂直方向完整覆盖。

        // 第一遍：统计有效像素数量（先估算容量上限）
        // 有效像素总数，按 step 网格统计
        var totalPixels = 0
        // 一次遍历统计个数
        // 先用较小的整型数组容不下全部，因此用两个 pass：先数数，再按比例抽取。
        // 为节省内存，先快速数总数并用均匀行距抽样。

        // 收集所有有效像素的行（y）坐标分布，用于按行配额
        // 记录每一行（按 step 步进）的有效像素计数
        // §B7-P2：两遍里的 `getPixel(x, y)` → **每行一次** `getPixels`，行内改纯数组下标访问。
        // JNI：`2 × ceil(bmpW/step) × ceil(bmpH/step)`（≈ 71,000）→ `2 × ceil(bmpH/step)`（≈ 346）。
        // 额外内存仅 `IntArray(bmpW)` ≈ 7 KB（跨次复用）。⛔ 不整图读 `IntArray(bmpW * bmpH)`（R12）。
        val rowBufLocal = rowBuf?.takeIf { it.size >= bmpW } ?: IntArray(bmpW).also { rowBuf = it }
        val maxRows = (bmpH + step - 1) / step
        val rowPixelCount = IntArray(maxRows)
        for (y in 0 until bmpH step step) {
            b.getPixels(rowBufLocal, 0, bmpW, 0, y, bmpW, 1)
            var cnt = 0
            for (x in 0 until bmpW step step) {
                if (rowBufLocal[x] and 0xFF000000.toInt() != 0) cnt++
            }
            rowPixelCount[y / step] = cnt
            totalPixels += cnt
            if (cnt > 0) {
                if (y < minY) minY = y.toFloat()
                if (y > maxY) maxY = y.toFloat()
            }
        }

        // 第二遍：按每行像素占比配额采样，垂直方向完整覆盖
        val quota = calculateRowQuota(rowPixelCount, totalPixels, cap)
        var curRowFill = 0
        for (ri in rowPixelCount.indices) {
            val y = ri * step
            val rowInk = rowPixelCount[ri]
            if (rowInk == 0) continue
            val targetCount = quota[ri]
            if (targetCount <= 0) continue
            var inkIdx = 0      // 本行第几个有效像素
            var filled = 0
            b.getPixels(rowBufLocal, 0, bmpW, 0, y, bmpW, 1)
            for (x in 0 until bmpW step step) {
                if (curRowFill >= cap) break
                if (rowBufLocal[x] and 0xFF000000.toInt() != 0) {
                    // ✅ 行内**均匀分段**取点（[isSegmentStart]）：本行 rowInk 个有效像素
                    //    均分成 targetCount 段、每段取首个 ⇒ 恰好取满 targetCount 个，
                    //    且沿**笔画宽度方向**也铺开。
                    // ⛔ 旧判据 `((taken-1) % takeEvery) < 0.5f`（takeEvery 是小数）会漏取约 46%
                    //    （声明 3600 实落 1959），且落点偏在笔画一侧 ⇒ 字成了「空心轮廓」。
                    val takeThis = isSegmentStart(inkIdx, rowInk, targetCount)
                    inkIdx++
                    if (takeThis) {
                        val o = n * STRIDE
                        target[o + TX] = x.toFloat()
                        target[o + TY] = y.toFloat()
                        target[o + SIZE] = 1.0f
                        target[o + PHASE] = rng.next() * 6.2831853f

                        var ci = 0
                        while (ci < displayText.length && x >= charXBounds[ci + 1]) ci++
                        charIndices[n] = ci.coerceAtMost(displayText.length - 1)
                        localXs[n] = ((x - xOff) / textWidth.coerceAtLeast(1f)).coerceIn(0f, 1f)

                        if (x < minX) minX = x.toFloat()
                        if (x > maxX) maxX = x.toFloat()

                        n++
                        curRowFill++
                        filled++
                        if (filled >= targetCount) break
                    }
                }
            }
            if (curRowFill >= cap) break
        }

        } finally {
            bmp?.recycle()
        }

        if (n == 0) return 0

        // 将粒子从 bitmap 坐标映射到画布坐标
        // 水平居中
        val xCenter = (minX + maxX) / 2f
        // 垂直居中到 targetY
        val yCenter = (minY + maxY) / 2f

        for (i in 0 until n) {
            val o = i * STRIDE
            val bmpX = target[o + TX]
            val bmpY = target[o + TY]

            // 映射：bitmap 中心 → canvas 中心（targetY）
            val canvasX = canvasW / 2f + (bmpX - xCenter)
            val canvasY = targetY + (bmpY - yCenter)

            target[o + TX] = canvasX
            target[o + TY] = canvasY
        }

        return n
    }

    /** 为一行粒子初始化凝聚起始位置（从四周飞来） */
    private fun initCoalesce(arr: FloatArray, count: Int, w: Float, h: Float, fromBelow: Boolean = false) {
        for (i in 0 until count) {
            val o = i * STRIDE
            val tx = arr[o + TX]
            val ty = arr[o + TY]

            val angle: Float
            val dist: Float
            if (fromBelow) {
                // 从下方飞入
                angle = -3.14159f / 2f + rng.nextSigned() * 0.8f
                dist = h * 0.4f + rng.next() * h * 0.2f
            } else {
                // 从四周飞入
                angle = rng.next() * 6.2831853f
                dist = (w * 0.35f + h * 0.2f) * (0.5f + rng.next() * 0.5f)
            }

            val startX = tx + cos(angle) * dist
            val startY = ty + sin(angle) * dist

            arr[o + X] = startX
            arr[o + Y] = startY

            val dx = tx - startX
            val dy = ty - startY
            val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
            val sp = 4f + rng.next() * 4f
            val perpX = -dy / len * 2f
            val perpY = dx / len * 2f
            arr[o + VX] = dx / len * sp + perpX
            arr[o + VY] = dy / len * sp + perpY
        }
    }

    // ── 主绘制 ──────────────────────────────────────────────

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = size.width
        val h = size.height
        val cap = line0.size / STRIDE

        // ── 自适应字号 ──────────────────────────────────────
        // ⚠️ 字号**按行**算（见 [fontFor]）：每一行各自占满屏宽 80% ⇒ 短行不会被长行拖小。
        //    只在**换行采样**时算一次（每行一次，不是每帧）⇒ 测量路径零成本。
        val fontCap = fontCapFor(ctx)

        val lyricLine = ctx.currentLyricLine?.takeIf { it.isNotBlank() }
        val nextLine = ctx.nextLyricLine?.takeIf { it.isNotBlank() }
        val text = lyricLine ?: (ctx.caption?.takeIf { it.isNotBlank() } ?: "NASMusicTV")
        val nextText = nextLine ?: ""
        val hasLyrics = lyricLine != null
        val currentIdx = ctx.lyricLineIndex

        // 两行的 Y 中心位置：对称分布在垂直中心两侧，避开 0.5h 的干扰
        topY = h * 0.40f
        bottomY = h * 0.60f
        // 行间距：**固定** = 两行中心之差（与字号解耦 ⇒ 字号再小两行也不挤、再大也不叠）
        rowGapPx = bottomY - topY

        // ── 状态切换逻辑 ────────────────────────────────────
        // 切歌重置：renderer 实例由 swapper 跨歌曲复用（不会重新 onEnter），
        // 若不在歌曲变化时回到初始态，displayedLineIndex 会停在上首歌的行号，
        // 新歌 idx 从 0 开始 -> diff 为负 -> 永不触发行切换，画面卡在旧歌词。
        // songId 为 null 时（本地扫描歌曲可能没有 id）用标题兜底，
        // 否则两首歌 id 均为 null 时不会触发重置。
        if (ctx.songId != boundSongId || ctx.caption != boundCaption) {
            boundSongId = ctx.songId
            boundCaption = ctx.caption
            displayedLineIndex = -1
            phase = Phase.INIT_COALESCE
            phaseStartMs = frame.timeMs
            rowOffsetY = 0f
            line0Text = ""
            line1Text = ""
        }

        if (displayedLineIndex < 0 && phase == Phase.INIT_COALESCE) {
            // 首次初始化
            if (lyricLine != null && currentIdx >= 0) {
                displayedLineIndex = currentIdx
                line0Text = text
                line1Text = nextText
                line0Count = sampleLine(text, line0, line0CharIdx, line0LocalX, cap, w,
                    fontFor(text, w, h, fontCap), topY)
                line1Count = sampleLine(nextText, line1, line1CharIdx, line1LocalX, cap, w,
                    fontFor(nextText, w, h, fontCap), bottomY)
                initCoalesce(line0, line0Count, w, h)
                initCoalesce(line1, line1Count, w, h)
                line1CoalesceStartMs = frame.timeMs
                phaseStartMs = frame.timeMs
            } else {
                // 无歌词时显示标题作为占位
                displayedLineIndex = 0
                line0Text = text
                line1Text = ""
                line0Count = sampleLine(text, line0, line0CharIdx, line0LocalX, cap, w,
                    fontFor(text, w, h, fontCap), topY)
                line1Count = 0
                initCoalesce(line0, line0Count, w, h)
                line1CoalesceStartMs = frame.timeMs
                phaseStartMs = frame.timeMs
            }
        }

        // 检测行切换（当实际播放行前进了 1 行或更多）
        if (hasLyrics && currentIdx >= 0 && displayedLineIndex >= 0) {
            val diff = currentIdx - displayedLineIndex
            if (diff >= 1 && phase == Phase.STEADY) {
                // 保存下下行文本（此时 ctx.nextLyricLine 是新当前行的下一行）
                incomingNextText = nextText
                // 开始消散第一行
                phase = Phase.SCATTERING_TOP
                phaseStartMs = frame.timeMs
            }
        }

        // ── 推进状态机 ──────────────────────────────────────
        val elapsed = frame.timeMs - phaseStartMs
        // 行偏移（SHIFTING 阶段控制 line0 上移）
        var shiftProgress = 0f

        when (phase) {
            Phase.INIT_COALESCE -> {
                if (elapsed >= initCoalesceMs) {
                    phase = Phase.STEADY
                    phaseStartMs = frame.timeMs
                }
            }
            Phase.STEADY -> {
                rowOffsetY = 0f
            }
            Phase.SCATTERING_TOP -> {
                // 计算消散总时长
                val maxCharIdx = line0Text.length.coerceAtLeast(1) - 1
                val totalScatterMs = maxCharIdx * scatterPerCharMs + scatterFlyMs
                if (elapsed >= totalScatterMs) {
                    // 消散完成，准备 SHIFTING
                    // 1. 将原来的 line1（第二行）"提升"为新的 line0（第一行）
                    promoteLine1ToLine0()
                    // 2. 采样新行到 line1（从下方进入）
                    line1Text = incomingNextText
                    line1Count = sampleLine(incomingNextText, line1, line1CharIdx, line1LocalX,
                        cap, w, fontFor(incomingNextText, w, h, fontCap), bottomY)
                    if (line1Count > 0) {
                        initCoalesce(line1, line1Count, w, h, fromBelow = true)
                    }
                    // ⛔ 凝聚时钟从**采样时刻**起算（与阶段无关）⇒ 凝聚一定能走完
                    line1CoalesceStartMs = frame.timeMs
                    displayedLineIndex++
                    phase = Phase.SHIFTING
                    phaseStartMs = frame.timeMs
                }
            }
            Phase.SHIFTING -> {
                // 行上移进度：0 → 1
                shiftProgress = (elapsed.toFloat() / shiftMs).coerceIn(0f, 1f)
                // line0 从 bottomY 上移到 topY（偏移从 0 到 -rowGapPx）
                rowOffsetY = -rowGapPx * easeOutCubic(shiftProgress)

                if (elapsed >= shiftMs) {
                    // 动画结束，实际调整粒子 Y 坐标
                    applyLine0Shift()
                    rowOffsetY = 0f
                    phase = Phase.STEADY
                    phaseStartMs = frame.timeMs
                }
            }
        }

        // ── 下行的「凝聚」进度（**从采样时刻起算**，与阶段无关）────
        // ⛔⛔ 旧码用 `phase == SHIFTING` 当凝聚开关 ⇒ 凝聚只在**上移那 700 ms**里推进，
        //    之后被硬生生掐断（可见度直接跳到 1、位置被 `updateHolding` 硬吸附）
        //    ⇒ 行「顶上去」时看到的其实是一个**没聚完**的行 ⇒ 视觉上就是「又聚了一遍」。
        // ✅ 改成按**本行自己的时间线**推进 ⇒ 凝聚在下方**走完** ⇒ 顶上去时已经是成形的一行。
        val line1CoalesceElapsed =
            if (line1Count > 0) (frame.timeMs - line1CoalesceStartMs).coerceAtLeast(0L) else 0L
        val line1IsCoalescing =
            line1Count > 0 && line1CoalesceElapsed < coalesceTotalMs(line1Text)

        // ── 更新粒子 ────────────────────────────────────────
        updateLine0(frame, elapsed, w, h)
        updateLine1(frame, elapsed, w, h, line1CoalesceElapsed, line1IsCoalescing)

        // ── 批量绘制 ────────────────────────────────────────
        // 行内进度：**优先**用宿主持有的**逐帧**覆盖（E53 歌词舞台推入），
        // 否则回退到 [RenderContext.lyricLineProgress]（1 Hz ⇒ 扫描每秒才跳一次）。
        val lineProgress = if (hasLyrics) {
            resolveLyricProgress(
                progressOverrideLine, progressOverride, displayedLineIndex, ctx.lyricLineProgress
            )
        } else 1f
        val kProgress = karaokePacing(lineProgress)

        val accent = ctx.palette.accent
        // P1#6：本处在每帧绘制路径上，改用零分配的 hueOf（原来 rgbToHsl 每帧分配一个 Triple）
        val baseHue = VisualizerMath.hueOf(accent)
        val activeHue = if (baseHue in 0f..60f || baseHue in 300f..360f) 300f else 195f

        val bassPulse = 1f + frame.bass * 0.05f
        val t = frame.timeMs * 0.004f

        // §B7-P3：把「与逐点无关的全局量」提到循环外算一次。
        // ⛔ `breath` 里的 `sin(globalT * 2)` 可提；`floatY` 里的 `sin(globalT * 2 + localX * 10)`
        //    含逐点 `localX`，**提不了**（旧口径写"省 14,400 次 sin"高估了 2 倍）。
        val sinB = sin(t * 2.0f)
        val amp = 2.2f * (0.35f + frame.bass.coerceIn(0f, 1f) * 2.2f)

        // 本帧共享量（逐帧刷新；供下面的两遍扫描用 ⇒ 零分配）
        frameKProgress = kProgress
        frameBassPulse = bassPulse
        frameGlobalT = t
        frameSinB = sinB
        frameAmp = amp
        frameEnergy = frame.energy

        // ── 两遍扫描 + 合批提交 ───────────────────────────────
        // 第一遍：统计每个子批次的点数与代表半径（`drawPoints` 的笔宽是**整批**的
        //         ⇒ 必须先知道每批的半径才能写点：点的坐标含 `-半径` 的偏移）
        // 第二遍：把点写进各子批次的区间
        // 第三步：每个**非空**子批次一次 `drawPoints`
        resetSubBatches()

        // 上行（正在唱 / 正在消散）
        lineIsSinging = phase != Phase.SCATTERING_TOP
        lineIsScattering = phase == Phase.SCATTERING_TOP
        lineScatterElapsedMs = if (phase == Phase.SCATTERING_TOP) elapsed else 0L
        lineIsCoalescing = false
        lineCoalesceElapsedMs = 0L
        lineYOffset = rowOffsetY
        lineHasLyrics = hasLyrics
        measureLine(line0Count, line0CharIdx, line0LocalX, 0)
        finalizeSubBatches()
        fillLine(line0, line0Count, line0LocalX, 0)

        // 下行（永远不处于「正在唱」状态；**按本行时间线**逐字凝聚）
        lineIsSinging = false
        lineIsScattering = false
        lineScatterElapsedMs = 0L
        lineIsCoalescing = line1IsCoalescing
        lineCoalesceElapsedMs = line1CoalesceElapsed
        lineYOffset = rowOffsetY
        lineHasLyrics = hasLyrics && line1Text.isNotBlank()
        measureLine(line1Count, line1CharIdx, line1LocalX, 1)
        finalizeSubBatches()
        fillLine(line1, line1Count, line1LocalX, 1)

        drawPointBatches(activeHue, frame.pulse)
    }

    // ── 两遍扫描：统计 → 写点 ────────────────────────────────

    /** 清空本帧的子批次统计（逐帧 ⇒ 只清计数，**不重新分配**） */
    private fun resetSubBatches() {
        for (i in subCount.indices) {
            subCount[i] = 0
            subSizeSum[i] = 0f
            subRadius[i] = 0f
            subOffset[i] = 0
            subFill[i] = 0
        }
    }

    /**
     * 第一遍：统计每个子批次的**点数**与**代表半径**。
     *
     * ⛔ **不得**在这里写点：`drawPoints` 的 `strokeWidth` 是**整批**的，
     *    而旧码是**逐粒子**的半径 ⇒ 必须先知道每批的半径（= 批内 `size` 的均值）才能写点。
     *
     * ⚠️ 分档依据是 `visibility`（**逐字**量）⇒ 「逐字消散 / 逐字凝聚」的粒度保留。
     */
    private fun measureLine(
        count: Int,
        charIndices: IntArray,
        localXs: FloatArray,
        lineIdx: Int
    ) {
        if (count <= 0) return
        val base = lineIdx * scratchStride
        for (i in 0 until count) {
            val ci = charIndices[i]
            val visibility = visibilityOf(ci)
            if (visibility <= 0.01f) {
                tierScratch[base + i] = -1
                continue
            }
            val brightness = brightnessOf(localXs[i])
            val tier = tierOf(brightness, visibility)
            // 整体呼吸：所有粒子用同一全局相位同步缩放（避免各粒子独立相位造成的「抖动感」）
            // §B7-P3：`sinB` 已提到循环外；`baseSize` 恒为 1.0f（`sampleLine` 是唯一写入点）⇒ 内联
            val breath = 1f + frameSinB * 0.10f * (0.25f + brightness * 0.75f)
            val size = breath * breath * frameBassPulse * (0.85f + brightness * 0.4f) * visibility
            if (size < 0.3f) {
                tierScratch[base + i] = -1
                continue
            }
            val bucket = visibilityBucket(visibility)
            tierScratch[base + i] = (tier * VIS_BUCKETS + bucket).toByte()
            val core = subIndex(lineIdx, tier, LAYER_CORE, bucket)
            subCount[core]++
            subSizeSum[core] += size
            if (tier >= 1) subCount[subIndex(lineIdx, tier, LAYER_GLOW, bucket)]++
        }
    }

    /** 统计完一行后：算出各子批次的代表半径与在缓冲内的起点 */
    private fun finalizeSubBatches() {
        for (bufIdx in 0 until 12) {
            var off = 0
            for (b in 0 until VIS_BUCKETS) {
                val sub = bufIdx * VIS_BUCKETS + b
                subOffset[sub] = off
                off += subCount[sub]
                subRadius[sub] = if (subCount[sub] > 0) subSizeSum[sub] / subCount[sub] else 0f
            }
        }
    }

    /**
     * 第二遍：把粒子写进各子批次的点缓冲。
     *
     * ⚠️ 几何逐像素对齐旧码的 `addOval`：
     *   旧码圆心 = `(px - size, py - size)`、半径 = `size * 2` ⇒ 直径 `4 * size`；
     *   ⇒ 这里把点放在 `(px - size, py - size)`、笔宽给 `4 * size`（`Cap.ROUND` ⇒ 实心圆盘）。
     */
    private fun fillLine(
        arr: FloatArray,
        count: Int,
        localXs: FloatArray,
        lineIdx: Int
    ) {
        if (count <= 0) return
        val base = lineIdx * scratchStride
        for (i in 0 until count) {
            val code = tierScratch[base + i].toInt()
            if (code < 0) continue
            val tier = code shr VIS_BUCKET_BITS
            val bucket = code and (VIS_BUCKETS - 1)
            val core = subIndex(lineIdx, tier, LAYER_CORE, bucket)
            val size = subRadius[core]
            val o = i * STRIDE
            val px = arr[o + X]
            // 节奏性律动：所有粒子同步以低音（bass）驱动上下起伏，
            // 叠加一个依赖粒子横向位置的固定波相位，形成整行的规整波浪，
            // 鼓点（bass 峰值）到来时浮动幅度增大，节奏感强、不发散。
            // §B7-P3：`amp` 已提到循环外；此处只剩含逐点 `localX` 的那次 `sin`（提不了）
            val floatY = sin(frameGlobalT * 2.0f + localXs[i] * 10f) * frameAmp
            val py = arr[o + Y] + lineYOffset + floatY
            writePoint(core, px - size, py - size)
            if (tier >= 1) {
                val glow = subIndex(lineIdx, tier, LAYER_GLOW, bucket)
                val glowR = size * if (tier == 2) GLOW_FACTOR_TIER2 else GLOW_FACTOR_TIER1
                writePoint(glow, px - glowR, py - glowR)
            }
        }
    }

    /** 把一个点写进子批次的点缓冲（x, y 交替） */
    private fun writePoint(sub: Int, x: Float, y: Float) {
        val buf = pointBuffers[sub / VIS_BUCKETS]
        val idx = (subOffset[sub] + subFill[sub]++) * 2
        buf[idx] = x
        buf[idx + 1] = y
    }

    /** 该粒子的可见度（消散 / 凝聚的逐字进度；稳态恒 1） */
    private fun visibilityOf(ci: Int): Float = when {
        lineIsScattering -> {
            val charElapsed = lineScatterElapsedMs - ci * scatterPerCharMs
            if (charElapsed <= 0) 1f
            else (1f - charElapsed.toFloat() / scatterFlyMs).coerceIn(0f, 1f)
        }
        lineIsCoalescing -> {
            val charElapsed = lineCoalesceElapsedMs - ci * coalescePerCharMs
            if (charElapsed <= 0) 0f
            else (charElapsed.toFloat() / coalesceArriveMs).coerceIn(0f, 1f)
        }
        else -> 1f
    }

    /**
     * 卡拉OK亮度（仅正在唱的行）。
     * ⛔ **不得以“性能”为由删除**（§13.5-D4 / §B7 专项评估）：它是「字色逐字变化」观感的唯一来源；
     *    删掉后统一取「正在唱」会让亮点数 **+60%**（更慢），统一取「待唱」则丢掉逐字推进感。
     */
    private fun brightnessOf(localX: Float): Float = when {
        !lineHasLyrics -> 0.9f
        !lineIsSinging -> 0.25f + frameEnergy * 0.08f  // 待唱行：暗
        else -> {
            val dist = localX - frameKProgress
            when {
                dist < -0.02f -> 0.78f
                dist <= 0.02f -> 1.0f
                else -> 0.18f + frameEnergy * 0.07f
            }
        }
    }

    /** 亮度分档（0 / 1 / 2） */
    private fun tierOf(brightness: Float, visibility: Float): Int = when {
        brightness * visibility > 0.88f -> 2
        brightness * visibility > 0.45f -> 1
        else -> 0
    }

    /** 可见度分档（`drawPoints` 的笔宽是整批的 ⇒ 半径相近的粒子必须合成一批） */
    private fun visibilityBucket(visibility: Float): Int = visibilityBucketOf(visibility)

    /**
     * 合批提交：每个**非空**子批次一次 `drawPoints`。
     *
     * ⚠️ 旧码是 6 次 `drawPath`（3 档 × 2 层，两行合并在同一条 path 里）；
     *    新码按**行**分开（12 个缓冲），因为逐字消散 / 凝聚期间两行的半径分布差异极大
     *    （合并会让「正在消散」那行的半径污染另一行）⇒ 代表半径必须按行分开统计。
     *
     * ## ⛔ 合成顺序：**先所有辉光、再所有本体**（SrcOver 的画家算法）
     *
     * 旧码的加色混合是**顺序无关**的（加法可交换）⇒ 旧顺序（暗→亮，glow 在 core 前）
     * 在加色下看不出差别。改成 SrcOver 后**顺序就有意义了**：
     * 若按旧顺序（逐档 glow→core）画，亮档的辉光会**盖在**中档的本体上（发灰）。
     * ⇒ 这里改成「先把三层辉光画完，再把三层本体画完」⇒ 本体永远在最上层、
     *   辉光只在本体之外露出一圈 ⇒ 与旧观感一致。
     */
    private fun DrawScope.drawPointBatches(activeHue: Float, pulse: Float) {
        val canvas = drawContext.canvas.nativeCanvas
        val midHue = VisualizerMath.hueGradient(200f, activeHue, 0.6f)
        // 每档（3 档 × 2 层）的最终颜色 —— 逐帧算一次（⛔ 零分配：复用成员 IntArray）
        val argbs = batchArgbs
        argbs[0] = argbWithAlpha(hslArgb(200f, 0.3f, 0.7f), 0.10f)                                  // 暗档 glow
        argbs[1] = argbWithAlpha(hslArgb(200f, 0.25f, 0.65f), 0.45f)                                // 暗档 core
        argbs[2] = argbWithAlpha(hslArgb(midHue, 0.75f, 0.7f), 0.22f)                               // 中档 glow
        argbs[3] = argbWithAlpha(hslArgb(midHue, 0.85f, 0.65f), 0.60f)                              // 中档 core
        argbs[4] = argbWithAlpha(hslArgb(activeHue, 1.0f, 0.75f), 0.26f + pulse * 0.10f)             // 亮档 glow
        argbs[5] = argbWithAlpha(hslArgb(activeHue, 1.0f, 0.7f), 0.62f + pulse * 0.14f)              // 亮档 core

        for (lineIdx in 0 until 2) {
            // layer：1 = glow（先画）、0 = core（后画）⇒ 本体永远压住辉光
            for (layer in 1 downTo 0) {
                for (tier in 0 until 3) {
                    if (layer == LAYER_GLOW && tier == 0) continue   // 暗档没有外辉光
                    val bufIdx = (lineIdx * 3 + tier) * 2 + layer
                    val buf = pointBuffers[bufIdx]
                    if (buf.isEmpty()) continue
                    val coreBufIdx = (lineIdx * 3 + tier) * 2 + LAYER_CORE
                    for (b in 0 until VIS_BUCKETS) {
                        val sub = bufIdx * VIS_BUCKETS + b
                        val n = subCount[sub]
                        if (n <= 0) continue
                        val coreRadius = subRadius[coreBufIdx * VIS_BUCKETS + b]
                        val radius = if (layer == LAYER_GLOW) {
                            coreRadius * if (tier == 2) GLOW_FACTOR_TIER2 else GLOW_FACTOR_TIER1
                        } else {
                            coreRadius
                        }
                        // ⚠️ `strokeWidth` = 点的**直径**（`Cap.ROUND` ⇒ 半径 = 直径/2 = 2×半径）
                        pointPaint.strokeWidth = radius * 4f
                        pointPaint.color = argbs[tier * 2 + layer]
                        canvas.drawPoints(buf, subOffset[sub] * 2, n * 2, pointPaint)
                    }
                }
            }
        }
    }

    /** HSL 色的 ARGB（Compose `Color` → 原生 int；alpha 恒 255） */
    private fun hslArgb(hue: Float, saturation: Float, lightness: Float): Int =
        VisualizerMath.hsl(hue, saturation, lightness).toArgb()

    /** 给 ARGB 套上 alpha（0..1） */
    private fun argbWithAlpha(argb: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    // ── 粒子物理更新 ────────────────────────────────────────

    private fun updateLine0(frame: AudioFrame, elapsedMs: Long, w: Float, h: Float) {
        val d = line0
        val n = line0Count
        if (n == 0) return

        when (phase) {
            Phase.INIT_COALESCE -> {
                val progress = (elapsedMs.toFloat() / initCoalesceMs).coerceIn(0f, 1f)
                updateCoalescing(d, n, progress)
            }
            Phase.STEADY, Phase.SHIFTING -> {
                // STEADY 和 SHIFTING 阶段，line0 都是稳定/演唱状态
                //（SHIFTING 时 line0 是新提升上来的行，已经在目标位置附近了）
                updateHolding(d, n, frame, w, h)
            }
            Phase.SCATTERING_TOP -> {
                // 逐字飞散
                for (i in 0 until n) {
                    val o = i * STRIDE
                    val ci = line0CharIdx[i]
                    val charDelay = ci * scatterPerCharMs
                    val charElapsed = elapsedMs - charDelay
                    if (charElapsed <= 0) {
                        // 还没到这个字，保持稳定
                        updateHoldingSingle(d, o, frame, w, h)
                    } else {
                        // 开始飞散
                        val p = (charElapsed.toFloat() / scatterFlyMs).coerceIn(0f, 1f)
                        d[o + VX] += rng.nextSigned() * 0.5f
                        d[o + VY] += -0.15f + rng.nextSigned() * 0.3f
                        d[o + VX] *= 0.98f
                        d[o + VY] *= 0.985f
                        d[o + X] += d[o + VX] * (1f + p * 0.6f)
                        d[o + Y] += d[o + VY] * (1f + p * 0.6f)
                    }
                }
            }
        }
    }

    private fun updateLine1(
        frame: AudioFrame,
        elapsedMs: Long,
        w: Float,
        h: Float,
        coalesceElapsedMs: Long,
        isCoalescing: Boolean
    ) {
        val d = line1
        val n = line1Count
        if (n == 0) return

        when (phase) {
            Phase.INIT_COALESCE -> {
                val progress = (elapsedMs.toFloat() / initCoalesceMs).coerceIn(0f, 1f)
                updateCoalescing(d, n, progress)
            }
            Phase.STEADY, Phase.SCATTERING_TOP -> {
                // ⛔⛔ 凝聚**未完成**时继续凝聚（不再用 `updateHolding` 硬吸附）
                //    ⇒ 下行在下方**聚完** ⇒ 顶上去时已是成形的一行 ⇒ 不会「再聚一遍」。
                if (isCoalescing) {
                    coalesceLine1(d, n, coalesceElapsedMs)
                } else {
                    updateHolding(d, n, frame, w, h)
                }
            }
            Phase.SHIFTING -> {
                coalesceLine1(d, n, coalesceElapsedMs)
            }
        }
    }

    /**
     * 下行的**逐字凝聚**（从左到右）。
     *
     * @param coalesceElapsedMs 自下行被采样起经过的时间（**与阶段无关**）
     */
    private fun coalesceLine1(d: FloatArray, n: Int, coalesceElapsedMs: Long) {
        for (i in 0 until n) {
            val o = i * STRIDE
            val ci = line1CharIdx[i]
            val charDelay = ci * coalescePerCharMs
            val charElapsed = coalesceElapsedMs - charDelay
            if (charElapsed <= 0) {
                // 还没到这个字，保持在起始位置不动
                // （已经在 initCoalesce 中设置了起始位置）
            } else {
                // 开始凝聚
                val p = (charElapsed.toFloat() / coalesceArriveMs).coerceIn(0f, 1f)
                val accel = 0.01f + p * 0.05f
                val drag = 0.82f + p * 0.08f
                d[o + VX] += (d[o + TX] - d[o + X]) * accel + rng.nextSigned() * 0.3f
                d[o + VY] += (d[o + TY] - d[o + Y]) * accel + rng.nextSigned() * 0.3f
                d[o + VX] *= drag
                d[o + VY] *= drag
                d[o + X] += d[o + VX]
                d[o + Y] += d[o + VY]
            }
        }
    }

    /** 一行「逐字凝聚」走完需要的总时长（**纯函数式**） */
    private fun coalesceTotalMs(text: String): Long =
        (text.length.coerceAtLeast(1) - 1) * coalescePerCharMs + coalesceArriveMs

    private fun updateCoalescing(d: FloatArray, n: Int, progress: Float) {
        val accel = 0.012f + progress * 0.06f
        val drag = 0.80f + progress * 0.10f
        for (i in 0 until n) {
            val o = i * STRIDE
            d[o + VX] += (d[o + TX] - d[o + X]) * accel + rng.nextSigned() * 0.4f
            d[o + VY] += (d[o + TY] - d[o + Y]) * accel + rng.nextSigned() * 0.4f
            d[o + VX] *= drag
            d[o + VY] *= drag
            d[o + X] += d[o + VX]
            d[o + Y] += d[o + VY]
        }
    }

    private fun updateHolding(d: FloatArray, n: Int, frame: AudioFrame, w: Float, h: Float) {
        // 稳定后粒子不再抖动，精确吸附到目标位置
        // 呼吸感由绘制时的 breath 计算保留
        // 注意：不做任何低音推挤，否则会破坏两行的垂直位置（文字会飘离目标行）
        for (i in 0 until n) {
            val o = i * STRIDE
            // 直接让粒子贴紧目标位置（强力吸附，无抖动）
            d[o + X] += (d[o + TX] - d[o + X]) * 0.25f
            d[o + Y] += (d[o + TY] - d[o + Y]) * 0.25f
            d[o + VX] *= 0.5f
            d[o + VY] *= 0.5f
        }
    }

    private fun updateHoldingSingle(d: FloatArray, o: Int, frame: AudioFrame, w: Float, h: Float) {
        // 稳定后粒子不再抖动
        d[o + X] += (d[o + TX] - d[o + X]) * 0.25f
        d[o + Y] += (d[o + TY] - d[o + Y]) * 0.25f
        d[o + VX] *= 0.5f
        d[o + VY] *= 0.5f
    }

    // ── 辅助函数 ────────────────────────────────────────────

    /**
     * 将 line1 提升为 line0（行切换时使用）。
     * 复制所有粒子数据，TY 保持在下行位置（SHIFTING 阶段通过 rowOffsetY 做上移动画）。
     *
     * ## ⛔⛔ 提升前必须**把凝聚收尾**（吸附到目标位）
     *
     * 旧码直接复制 ⇒ 若下行还没聚完，被提升的这一行**带着半途的位置**顶上去
     * ⇒ 用户看到「顶上去之后又聚了一遍」。
     * ⇒ 这里先吸附（位置 = 目标、速度 = 0）⇒ 顶上去的**一定是成形的一行**。
     */
    private fun promoteLine1ToLine0() {
        // ① 凝聚收尾：吸附到目标位（⛔ 不得在这里再聚一次）
        for (i in 0 until line1Count) {
            val o = i * STRIDE
            line1[o + X] = line1[o + TX]
            line1[o + Y] = line1[o + TY]
            line1[o + VX] = 0f
            line1[o + VY] = 0f
        }
        // 复制粒子数组
        System.arraycopy(line1, 0, line0, 0, line1Count * STRIDE)
        // 复制字索引
        System.arraycopy(line1CharIdx, 0, line0CharIdx, 0, line1Count)
        // 复制 localX
        System.arraycopy(line1LocalX, 0, line0LocalX, 0, line1Count)
        // 复制计数和文字
        line0Count = line1Count
        line0Text = line1Text
    }

    /**
     * SHIFTING 结束后调用：将 line0 的粒子 Y 坐标实际上移一行，
     * 这样 rowOffsetY 回到 0 时粒子正好在上行位置。
     */
    private fun applyLine0Shift() {
        for (i in 0 until line0Count) {
            val o = i * STRIDE
            line0[o + TY] -= rowGapPx
            line0[o + Y] -= rowGapPx
        }
    }

    internal companion object {
        private const val X = 0
        private const val Y = 1
        private const val VX = 2
        private const val VY = 3
        private const val TX = 4
        private const val TY = 5
        private const val SIZE = 6
        private const val PHASE = 7
        private const val STRIDE = 8

        // ── 点批次结构（3 档亮度 × 2 层 × 2 行 × 可见度分档）────────────

        /** core 层（点本体） */
        internal const val LAYER_CORE = 0

        /** glow 层（外辉光） */
        internal const val LAYER_GLOW = 1

        /**
         * **可见度分档数** —— `drawPoints` 的 `strokeWidth` 是**整批**的，
         * 而旧码是**逐粒子**的半径 ⇒ 半径相近的粒子必须合成一批。
         *
         * ⚠️ 分档依据是 `visibility`（**逐字**量：消散 / 凝聚的逐字进度）⇒
         *    「逐字消散 / 逐字凝聚」的粒度保留（16 档 ⇒ 半径步进 6.25%）。
         */
        internal const val VIS_BUCKETS = 16

        /** 分档数的位宽（编码进 `tierScratch` 的一个字节里） */
        internal const val VIS_BUCKET_BITS = 4

        /** 子批次总数 = 2 行 × 3 档 × 2 层 × [VIS_BUCKETS] */
        internal const val SUB_BATCHES = 2 * 3 * 2 * VIS_BUCKETS

        /** 外辉光半径系数：亮档 */
        internal const val GLOW_FACTOR_TIER2 = 2.2f

        /** 外辉光半径系数：中档 */
        internal const val GLOW_FACTOR_TIER1 = 1.4f

        /**
         * 可见度 → 分档（**纯函数**，门禁可直接调）。
         *
         * ⚠️ 必须**单调**且能区分不同的可见度 ⇒ 「逐字消散 / 逐字凝聚」的粒度才保留
         *    （否则整行会一起缩放 ⇒ 逐字效果消失）。
         *
         * @param visibility 可见度（0..1）
         */
        internal fun visibilityBucketOf(visibility: Float): Int =
            (visibility * VIS_BUCKETS).toInt().coerceIn(0, VIS_BUCKETS - 1)

        /**
         * 子批次索引（**纯函数**，门禁可直接调）。
         *
         * @param line 行号（0 = 上行，1 = 下行）
         * @param tier 亮度档（0 / 1 / 2）
         * @param layer 层（[LAYER_CORE] / [LAYER_GLOW]）
         * @param bucket 可见度分档（0..[VIS_BUCKETS]-1）
         */
        internal fun subIndex(line: Int, tier: Int, layer: Int, bucket: Int): Int =
            ((line * 3 + tier) * 2 + layer) * VIS_BUCKETS + bucket

        /**
         * 解析本帧的**行内进度**（**纯函数**，门禁可直接调）。
         *
         * - 覆盖的**行索引**与**屏上**那一行一致 ⇒ 用覆盖值（宿主的逐帧进度）；
         * - 否则 ⇒ 回退到 `ctx.lyricLineProgress`（1 Hz）。
         *
         * ⚠️ 覆盖的行索引与屏上那一行**可能差一帧**（宿主推的是它自己的「当前行」，
         *    而屏上那一行是本渲染器的 [displayedLineIndex]）⇒ 必须配对后才用。
         *
         * @param overrideLineIndex 覆盖所属的行索引（-1 = 无覆盖）
         * @param overrideProgress 覆盖的行内进度
         * @param singingLineIndex 屏上正在唱的那一行的索引
         * @param fallbackProgress 回退值（1 Hz 的 `ctx.lyricLineProgress`）
         */
        internal fun resolveLyricProgress(
            overrideLineIndex: Int,
            overrideProgress: Float,
            singingLineIndex: Int,
            fallbackProgress: Float
        ): Float {
            if (overrideLineIndex < 0 || overrideLineIndex != singingLineIndex) {
                return fallbackProgress.coerceIn(0f, 1f)
            }
            return overrideProgress.coerceIn(0f, 1f)
        }

        /**
         * 由「行的起止时间 + 当前播放位置」算行内进度（**纯函数**，门禁可直接调）。
         *
         * ⚠️ 口径必须与 `computeLyricInfo` 一致（同一行的进度算法只能有一处）。
         *
         * @param startSec 行首时间（秒）
         * @param endSec 行尾时间（秒）
         * @param timeSec 当前播放位置（秒）
         */
        internal fun lineProgressOf(startSec: Double, endSec: Double, timeSec: Double): Float {
            val span = endSec - startSec
            if (span <= 0.0) return 1f
            return ((timeSec - startSec) / span).coerceIn(0.0, 1.0).toFloat()
        }

        /** 字号测量参考值：在参考字号下测字宽/行高，再换算成真实字号 */
        internal const val REF_FONT = 100f
        /** 字号下限（屏高比例）：太小时字形笔画挤作一团难辨。⛔ 必须 ≤ 低档的 [fontMaxRatio]，否则 `coerceIn` 抛异常 */
        internal const val MIN_FONT_S = 0.05f
        /** 字号上限（屏高比例）：超过则粒子太稀、文字空洞。⚠️ 实际取 [fontMaxRatio]，随粒子数收缩 */
        private const val MAX_FONT_S = 0.16f

        /**
         * **字号规则**（**纯函数**）—— 门禁与离线渲染器直接调 ⇒ 不复制算法。
         *
         * 公式（三项约束取中间值，再夹进硬区间）：
         * ```
         * fontFromWidth  = REF_FONT * (w * 0.80) / refAdvance     // 本行占满屏宽 80%
         * fontFromHeight = REF_FONT * (h * 0.40) / refLineHeight  // 行高 ≤ 屏高 40%
         * font           = clamp(min(fontFromWidth, fontFromHeight), h*MIN_FONT_S, h*fontMaxRatio(cap))
         * ```
         *
         * ⚠️ `refAdvance` / `refLineHeight` 必须是**参考字号 [REF_FONT] 下**量出来的值
         *    （本行文本的字宽 / 行高）⇒ 换行时量一次即可。
         *
         * @param refAdvance 本行文本在 [REF_FONT] 下的字宽（px）
         * @param refLineHeight 参考字号下的行高（px）
         * @param cap 字号上限所用的**预算档**（见 [fontBudgetCap]；加密度时传档位默认）
         */
        internal fun fontSizeFor(
            refAdvance: Float,
            refLineHeight: Float,
            w: Float,
            h: Float,
            cap: Int
        ): Float {
            val fontFromWidth = REF_FONT * (w * 0.80f) / refAdvance.coerceAtLeast(REF_FONT * 0.1f)
            val fontFromHeight = REF_FONT * (h * 0.40f) / refLineHeight.coerceAtLeast(1f)
            val base = minOf(fontFromWidth, fontFromHeight)
            return base.coerceIn(h * MIN_FONT_S, h * fontMaxRatio(cap))
        }

        /**
         * 每行粒子上限。⚠️ 本套的提交次数早已合批到位（每帧几十次 `drawPoints`），
         * 贵的是**点个数**（= 粒子数 × (1~2 层)）—— 低画质下 3600×2 行最多 10,800 个点，
         * 而本机实测曲线是「≈500 个 ⇒ 7.6 fps / ≈24 个 ⇒ 59 fps」（§11.3.6 P-1，数字雨合并前后），
         * 所以低档**只能减点**，v1.47 真机低画质扫描里本套是全档最慢的一套（7 fps）。
         */
        private const val CAP_HIGH = 3600
        private const val CAP_LOW = 900

        /**
         * 宿主覆盖每行粒子上限时的**上界**（防呆：别把覆盖值写成天文数字）。
         * ⛔ 覆盖值越大 ⇒ 每帧点数越多 ⇒ 帧率越低（见 [CAP_HIGH] 的实测曲线）。
         */
        internal const val MAX_PARTICLE_BUDGET = 12000

        /** 按档位选每行粒子上限（**纯函数**，门禁可直接调用） */
        internal fun capacityForTier(
            quality: com.nasmusic.tv.data.model.VisualQuality
        ): Int = if (quality == com.nasmusic.tv.data.model.VisualQuality.LOW) CAP_LOW else CAP_HIGH

        /**
         * 字号上限随粒子上限收缩的比例（**纯函数**，门禁可直接调用）。
         *
         * 点阵密度 = `cap / 字面积`，要维持密度不变 ⇒ 字面积 ∝ `cap` ⇒ 线性字号 ∝ `sqrt(cap)`。
         * 以 [CAP_HIGH] 档的 [MAX_FONT_S] 为基准做等比缩放。
         */
        internal fun fontMaxRatio(cap: Int): Float =
            MAX_FONT_S * kotlin.math.sqrt(cap.toFloat() / CAP_HIGH.toFloat())

        /**
         * 字号上限所用的「预算档」（**纯函数**，门禁可直接调用）。
         *
         * ⚠️ **只有当实际粒子预算比档位默认更小时才用它**：
         * - 减点（低档降密度）⇒ 沿用 [fontMaxRatio] 的 `sqrt` 收缩，维持密度不变（既有设计）；
         * - 加点（宿主覆盖调大）⇒ **字号不动** ⇒ 新增的粒子落在**同样大小**的字上 ⇒ 密度必涨。
         *
         * ⛔ 反过来做（字号跟着 `sqrt(cap)` 一起涨）会把新增粒子摊到更大的字上，密度反降：
         * 实测 TV 1920×1080 / 8 字行：`cap 3600→7200` 字号同步涨 ⇒ 覆盖率 44.6% → **36.3%**。
         */
        internal fun fontBudgetCap(tierCap: Int, actualCap: Int): Int =
            if (actualCap < tierCap) actualCap else tierCap

        /**
         * 行内均匀取点判据（**纯函数**，门禁可直接调）。
         *
         * 把本行 [rowInk] 个有效像素**均匀分段**，返回第 [inkIdx] 个像素是否是某一段的**首个**
         * （段首 ⇒ 取点）。⇒ 恰好取 [targetCount] 个点，且沿**笔画宽度方向**也铺开
         * （不是只贴着笔画的一侧 ⇒ 字不会变成「空心轮廓」）。
         *
         * @param inkIdx 本行第几个有效像素（0 起）
         * @param rowInk 本行有效像素总数
         * @param targetCount 本行要取的点数
         */
        internal fun isSegmentStart(inkIdx: Int, rowInk: Int, targetCount: Int): Boolean {
            if (rowInk <= 0 || targetCount <= 0) return false
            if (inkIdx < 0 || inkIdx >= rowInk) return false
            // 段号 = 该像素落在第几段；段号变了 ⇒ 段首 ⇒ 取点。
            // ⚠️ 乘法不会溢出：`inkIdx < rowInk ≤ bmpW`（≈4e3）、`targetCount ≤ MAX_PARTICLE_BUDGET`（1.2e4）
            //    ⇒ 乘积 ≤ 5e7 < Int.MAX_VALUE。
            val chunk = inkIdx * targetCount / rowInk
            val prevChunk = if (inkIdx == 0) -1 else (inkIdx - 1) * targetCount / rowInk
            return chunk != prevChunk
        }

        // ── §B7 纯函数（`internal` 供门禁直接调；⛔ 测试不复制算法 —— 复制必然漂移）──

        /**
         * §B7-Q1：按每行有效像素占比，把 `cap` 个粒子配额分配给各行。
         * 确保字形密集的上/中部和下/底部都按实际占比得到粒子，垂直方向完整覆盖。
         *
         * ⛔ 这是本项目**已验证的正确算法**（旧"行优先 + 满了就 break"会丢字形下半部）。
         */
        internal fun calculateRowQuota(rowPixelCount: IntArray, totalPixels: Int, cap: Int): IntArray {
            val quota = IntArray(rowPixelCount.size)
            if (totalPixels <= 0 || cap <= 0) return quota
            for (ri in rowPixelCount.indices) {
                val cnt = rowPixelCount[ri]
                if (cnt <= 0) continue
                quota[ri] = (cnt.toLong() * cap / totalPixels).toInt().coerceAtLeast(1)
            }
            return quota
        }

        /** §B7-Q1：卡拉OK 推进曲线（快起慢落的三次缓出，`u ∈ [0,1]`） */
        internal fun karaokePacing(t: Float): Float {
            val u = t.coerceIn(0f, 1f)
            return 1f - (1f - u) * (1f - u) * (1f - u)
        }

        /** 行上移缓动（与 [karaokePacing] 同式，语义不同：这里是空间位移） */
        internal fun easeOutCubic(t: Float): Float {
            val u = t.coerceIn(0f, 1f)
            return 1f - (1f - u) * (1f - u) * (1f - u)
        }
    }
}
