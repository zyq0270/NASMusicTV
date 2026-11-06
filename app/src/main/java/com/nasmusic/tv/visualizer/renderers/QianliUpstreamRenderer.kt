package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.qianli.LayerSpec
import com.nasmusic.tv.visualizer.qianli.QianliBlockPool
import com.nasmusic.tv.visualizer.qianli.QianliSeal
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiColor
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiContour
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiNoise
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPlanner
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPrng
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiUtil
import kotlin.math.max

/**
 * E50「千里江山 · **忠于上游的绢白线描版**」（新渲染路径，与 [QianliScrollRenderer] 并存）。
 *
 * ## 它与 [QianliScrollRenderer] 的关系
 *
 * [QianliScrollRenderer] 走的是**自创构图**：远/中/近/水**四条层带**，各带自己的 y 缩放比例、
 * 自己的滚动速度、自己的 α ⇒ 水与山脚对不齐、山脚被层带底裁出一条水平直边。
 *
 * ⭐ **本类走的是上游的路**：直接用移植层的 [ShanShuiPlanner]（它本身就是上游
 * `mountplanner` / `chunkloader` / `chunkrender` 的照抄）⇒
 *  - **单一世界空间**（世界 x 无界，世界 y ∈ [0, 800]）；
 *  - **一个滚动量**（[cursxWorld]，等价上游 `MEM.cursx`）⇒ **全场景一个速度、无视差**；
 *  - **全局画家序**（chunk 按 y 升序插入 ⇒ 画家序由移植层给出，本类**照抄执行**）；
 *  - **绢白纸底 + 线描**（上游整幅 SVG `mix-blend-mode: multiply` ⇒ 纯白填充在纸底上隐形
 *    ⇒ 上游的山没有面积色，只有线）。
 *
 * ## 与上游的对应关系
 *
 * | 上游 | 本类 |
 * |---|---|
 * | `MEM`（`:3816-3827`） | [ShanShuiPlanner.State] |
 * | `MEM.cursx += v`（`:4060`） | [cursxWorld] += [scrollSpeedWorld] × dt |
 * | `calcViewBox()`（`:3995-3998`，zoom = 1.142） | [scale] = 画幅高 / [VISIBLE_WORLD_H] |
 * | `chunkloader` / `chunkrender`（`:3845-3984`） | [ShanShuiPlanner.load] / [ShanShuiPlanner.render] |
 * | 纸底 canvas（`:4336-4356`） | [bakePaperTile] |
 * | 整幅 SVG `mix-blend-mode: multiply`（`:4028`） | 白填充 ⇒ **纸色**（见 [isPaperWhite]） |
 *
 * ## 三条不可动的结构纪律
 *
 * 1. **逐帧只 blit 位图**（纸底 1 次全屏 `drawRect` + 块池 blit）⇒ 几何全部留在烘焙期。
 * 2. **一个滚动量**（[cursxWorld]）—— ⛔ 不许引入分层速度 / 视差。
 * 3. **位移走 `drawBitmap` 的 x 参数** —— ⛔ 不碰 `Shader.setLocalMatrix`（E43 真机静默失效）。
 *
 * ## ⛔ 本模式**不做**任何后处理
 *
 * 上游 shan-shui-inf 没有暗角 / 颗粒 / 扫描线 / 做旧罩纱 / 光尘 / 印章 / 呼吸 ⇒ 本类
 * [postFx] 恒 [PostFx.NONE]，逐帧只有「纸底 + 场景」两笔。
 *
 * ⚠️ **副作用（如实记录，不为此加任何额外绘制）**：[PostFx.NONE] ⇒
 * `PostFx.hasFullScreenPass()` 恒假 ⇒ 基类 `RendererFx.needsDamageCoalescer(level, postFx)`
 * 在 **LITE/FULL 也为真** ⇒ `OverlayFx.drawDamageCoalescer()` 每帧补一次**整画布** `drawRect`
 * （肉眼不可见，铺屏 1.000 屏）。本效果每帧头一笔就是全屏纸底 `drawRect` ⇒ 脏区早已并成
 * 一整块 ⇒ 这次补画零收益。
 * ⛔ 本仓既有约定（E46/E47/E48/E49/E50 的三段式）会要求写成 `PostFx(vignette = …)` +
 * `vignetteOverride` 恒 `0f`；但那等于给上游画面加一道上游没有的暗角 ⇒ **本类刻意不照抄**，
 * 只在此**如实记录**上面那条副作用。
 *
 * ## 门禁缝（仅观测，不改行为）
 *
 * 末尾的「门禁缝」一节暴露**只读**的内部状态（世界变换、纸色、纸底像素、场景绘制项），
 * 供 `QianliUpstreamGateTest` 在**本机 Robolectric 无法光栅化**（LEGACY 图形模式）的前提下，
 * 用**同一份绘制项**做像素级判定；`QianliUpstreamPreviewTest` 也走同一份绘制项出图。
 */
class QianliUpstreamRenderer : RendererFx() {

    override val theme = VisualizerTheme.QIANLI_SCROLL

    /** 上游没有暗角 / 颗粒 / 扫描线 ⇒ 全关（副作用见类 KDoc）。 */
    override val postFx = PostFx.NONE

    // ─────────────────────── 世界状态 ───────────────────────

    /**
     * 上游 `MEM` 的等价物（移植层的 [ShanShuiPlanner.State]）。
     *
     * ⛔ **可替换**（[ensureStateAnchor]）：上游 `MEM.chunks` 是**永不回收**的 JS 数组
     * （页面生命周期内）⇒ 移植到长驻进程必须自己设上限，否则长时间运行会无界增长。
     */
    private var state: ShanShuiPlanner.State = ShanShuiPlanner.State()

    /** 当前 [state] 的世界锚点（状态回收用）。 */
    private var stateAnchorWorld = 0.0

    /**
     * **唯一的滚动量**（世界单位）—— 上游 `MEM.cursx` 的等价物。
     *
     * ⛔ 全场景只有这一个量（上游 `MEM.cursx += v`，`:4060`）⇒ **无视差**。
     */
    private var cursxWorld = 0.0

    /**
     * 世界 → 屏幕的**均匀**缩放（px / 世界单位）。
     *
     * ⭐ 上游 `calcViewBox()`（`:3995-3998`）的 zoom = 1.142 ⇒ 可见世界高
     * `MEM.windy / zoom` = 800 / 1.142 = [VISIBLE_WORLD_H] ⇒ 缩放 = 画幅高 / 可见世界高。
     *
     * ⛔ **x 与 y 必须是同一个 scale**（上游 zoom 是均匀的）。
     */
    private var scale = 0.0

    // ─────────────────────── 资源 ───────────────────────

    /** 绢白纸底 tile（上游 `:4336-4356` 的 512×512 canvas）。 */
    private var paperTile: Bitmap? = null

    /** 纸底 tile 的原始像素（门禁/预览用；⛔ 不参与渲染）。 */
    private var paperPixels: IntArray? = null

    /** 纸底的平铺着色器（⛔ 不用 `setLocalMatrix`，E43 真机静默失效）。 */
    private var paperShader: BitmapShader? = null

    /**
     * 「白填充 ⇒ 纸色」用的**纸色均值**（**RGB-only**，0xRRGGBB；⛔ 不含 alpha 字节）。
     *
     * ⚠️ 取自 tile 的平均 RGB ⇒ **不写死魔数**（上游的 `c` 随噪声在 [225, 255] 波动）。
     *
     * ⛔ **必须 RGB-only**（2026-11-06 真踩过）：`Color.rgb(...)` 返回的是 **0xFFRRGGBB**
     *   （alpha=255 ⇒ 作为 Int 是**负数**），而门禁侧的 `rgbOf()` 会**掩掉 alpha**（0xRRGGBB）
     *   ⇒ 同一个颜色会变成两个不同的整数 ⇒ 白填充与纸色的比对**恒不相等**；
     *   而且用带 alpha 的值算「纸色混合线斜率 `(pr−pg)/(pg−pb)`」时 `shr 8` 会取到**错的通道**
     *   ⇒ 斜率算错 ⇒ 纸底像素被误判成「彩色像素」。
     */
    private var paperColorRgb = 0

    /** 场景块池（**只有一个** —— 本模式没有层带）。 */
    private var pool: QianliBlockPool? = null

    /** 当前画幅（重建判据）。 */
    private var laidW = -1
    private var laidH = -1

    // ─────────────────────── 逐帧复用的画笔 ───────────────────────

    private val ptPaper = Paint()
    private val ptFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ptStroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    // ─────────────────────── 生命周期 ───────────────────────

    /**
     * 进入效果：**首行释放旧资源**。
     *
     * ⛔ 首行这个 `releaseAll()` 不是洁癖：**画质变化会重入 `onEnter`**（不经过 `onExit`）。
     * ⚠️ 位图不在这里分配：`onEnter` 时 `ctx.canvasSize` 还是 `Size.Zero` ⇒ 走 [rebuild]。
     */
    override fun onEnterContent(ctx: RenderContext) {
        releaseAll()
        resetWorld()
    }

    /** 离效果：逐张 `recycle`（API 22–25 的 `ARGB_8888` 像素在 native 堆、不受 GC 自愈）。 */
    override fun onExitContent() {
        releaseAll()
        resetWorld()
    }

    /**
     * 位图 + 块池（**重排与离效果都要走这里**）。⛔ 画笔先置 `null`。
     *
     * ⚠️ **不碰世界状态**（[state] / [cursxWorld] / [stateAnchorWorld]）：
     * 画幅变化（[rebuild]）**不该**把镜头弹回世界原点 ⇒ 世界状态由 [resetWorld] 单独复位
     * （只在进入 / 离开效果时复位）。
     */
    private fun releaseAll() {
        // ⚠️ 位图回收前必须先把画笔上的着色器摘掉（否则着色器仍引用着已回收的位图）。
        ptPaper.shader = null
        paperShader = null
        paperTile?.let { if (!it.isRecycled) it.recycle() }
        paperTile = null
        paperPixels = null
        pool?.release()
        pool = null
        // 印章缓存是 `QianliSeal` 的**静态**缓存 ⇒ 与设色路径对称地释放（否则离效果后仍占着位图）。
        QianliSeal.releaseAll()
        laidW = -1
        laidH = -1
    }

    /** 世界状态复位（进入 / 离开效果时）。 */
    private fun resetWorld() {
        state = ShanShuiPlanner.State()
        stateAnchorWorld = 0.0
        cursxWorld = 0.0
    }

    // ─────────────────────── 帧体 ───────────────────────

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = ctx.canvasSize.width
        val h = ctx.canvasSize.height
        if (w < 2f || h < 2f) return
        val iw = w.toInt()
        val ih = h.toInt()
        if (iw < 2 || ih < 2) return

        // ① 画幅变化 ⇒ 整组重烘（⛔ 不返回：宁可少画，也不要黑屏）。
        if (iw != laidW || ih != laidH) rebuild(iw, ih)

        // ② 推进**唯一**的滚动量（世界单位 / 秒）。
        //    ⚠️ `fx.dt` **已经是秒**（FrameClock），⛔ 不再除 1000。
        //    ⛔ 上游是**恒速**（`xcroll(v)` 的 v 是常数）⇒ 本模式**不做**能量调速。
        cursxWorld += scrollSpeedWorld() * fx.dt.toDouble()

        val c = drawContext.canvas.nativeCanvas

        // ③ 纸底（全屏矩形 + 绢纹平铺）—— 上游的 `backgroundImage`。
        drawPaper(c, w, h)

        // ④ 场景层：块池（滚出视口才重烘）。
        val p = pool ?: return
        val offsetPx = cursxWorld * scale
        p.advance(offsetPx)
        p.draw(c, offsetPx, 1f)

        // ⑤ 印章（所有者 2026-11-06 裁定：**印章保留**，其余自创内容暂关）
        //    ⇒ 这是上游**没有**的唯一自创保留项 ⇒ 画在最后（不被场景盖住）。
        drawSeal(c, ctx, w, h)
    }

    /**
     * 印章（右上角，朱砂方印）。
     *
     * ⛔ 上游 `shan-shui-inf` **没有**印章（它的落款是屋顶招牌上的 `"Pizza Hut"` 彩蛋，
     *   见 `index.html:3105`）⇒ 这是**自创**项，但所有者明确要求保留。
     * ⇒ 与设色路径用同一套 [QianliSeal]（同一枚印、同一个尺寸系数）⇒ 两条路径一致。
     */
    private fun drawSeal(c: Canvas, ctx: RenderContext, w: Float, h: Float) {
        val s = SEAL_SIZE_K * h
        if (s < 4f) return
        val inset = ctx.safeAreaPx
        QianliSeal.bake(c, w - inset - s / 2f, inset + s / 2f, s)
    }

    /** 纸底：一张全屏矩形 + 平铺的绢纹。⛔ 不用 `Shader.setLocalMatrix`（E43 真机静默失效）。 */
    private fun drawPaper(c: Canvas, w: Float, h: Float) {
        val sh = paperShader ?: return
        ptPaper.style = Paint.Style.FILL
        ptPaper.shader = sh
        c.drawRect(0f, 0f, w, h, ptPaper)
        ptPaper.shader = null
    }

    // ─────────────────────── 建素材（首帧 / 换画幅） ───────────────────────

    /**
     * 重建全部资源。
     *
     * ⚠️ 顺序很重要：**先烘纸底、后烘场景** —— 纸底要消耗 66,049 次 PRNG（上游的
     * `Math.random()` 在纸底脚本里第一次被调用）⇒ 先烘纸底才能对上上游的随机流次序。
     */
    private fun rebuild(w: Int, h: Int) {
        releaseAll()
        laidW = w
        laidH = h

        // ① 均匀缩放（上游 zoom 的等价）。
        scale = h / VISIBLE_WORLD_H

        // ② 纸底 tile（上游 `:4336-4356`）+ 它的平铺着色器。
        val baked = bakePaperTile()
        paperTile = baked.bitmap
        paperPixels = baked.pixels
        paperColorRgb = baked.avgRgb and 0xFFFFFF   // ⛔ RGB-only（见 [paperColorRgb] 的说明）
        paperShader = BitmapShader(baked.bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)

        // ③ 场景块池（**一个**；w = 屏宽，h = **整屏高** —— 本模式没有层带）。
        pool = makePool(w, h)
    }

    /**
     * 造场景块池。
     *
     * ⛔ [LayerSpec] 的 `bandY0/bandY1` 是 **0.0 / 1.0**（整屏），`speed = 0.0`
     * （**滚动不在块池里** ⇒ 偏移由 [cursxWorld] 单独给），`alphaFloor = 1.0`（不透明）。
     */
    private fun makePool(w: Int, h: Int): QianliBlockPool {
        val spec = LayerSpec(
            id = 0,
            bandY0 = 0.0,
            bandY1 = 1.0,
            speed = 0.0,
            alphaFloor = 1.0,
        )
        val created = QianliBlockPool(spec, BLOCK_COUNT, w, h) { x0Px ->
            bakeSceneBlock(x0Px, h)
        }
        pool = created
        return created
    }

    // ─────────────────────── 场景块烘焙 ───────────────────────

    /**
     * 烘一块场景（世界 x ∈ `[x0World, x0World + blockWorldW)`）。
     *
     * ### 坐标空间
     *
     * - 块池是 **px 空间**（`blockX0` / `offset` 都是 px）⇒ 本函数收到的 `x0Px` 是 px。
     * - 世界 x = `x0Px / scale`；块的世界宽 = `pool.blockWidth / scale`。
     *
     * ### 画家序
     *
     * ⭐ [ShanShuiPlanner.render] 返回的 [ShanShuiPlanner.State.canv] **已经**按上游的
     * **全局 y 升序**排好（上游 `:3852-3870` 的插入排序；水的排序键被压成 `y − 10000`
     * ⇒ 水永远画在最前 = 最靠后）⇒ 本函数**照这个顺序**画，不重排。
     *
     * ### 性能注记
     *
     * 上游每帧都重画整幅 SVG；本实现**滚出视口才重烘** ⇒ 更省。
     * 这是**性能优化**，不是画面差异（块内的内容与顺序完全一致）。
     */
    private fun bakeSceneBlock(x0Px: Double, screenH: Int): Bitmap {
        val blockW = pool?.blockWidth ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val x0World = x0Px / scale
        val blockWorldW = blockW.toDouble() / scale

        ensureStateAnchor(x0World)
        // ⭐ **必须带余量**（2026-11-06 修「画面有断」）：`ShanShuiPlanner.render` 按 **chunk 中心 x** 过滤
        //   （`xmin − cwid < chunk.x < xmax + cwid`，cwid = 512）。
        //   本仓的**块宽** = `2016 px / scale` ≈ **1308 世界单位**（`scale = h / 700.5` ≈ 1.54 @1080p），
        //   而**屏幕宽** = `1920 px / scale` ≈ 1245 世界单位 ⇒ 可见世界只有上游（3000 单位）的 41%。
        //   ⇒ 中心落在块外、形状却伸进块内的图元（山体半宽 ≤ 300，近景楼阁/树/舟更宽）
        //     **根本不在画布里** ⇒ 被**竖直切断** ⇒ 画面出现一道竖直断缝。
        // ⭐ 上游不会这样：它的屏幕宽 3000 世界单位（`MEM.windx`）⇒ 同样的 512 余量足够覆盖
        //   ⇒ 切口的残缺发生在**屏幕外**⇒ 看不见。
        // ⇒ 解法：烘焙时向外多规划/多渲染一个余量（≥ 最大图元半宽 + 一点）。
        loadRange(
            x0World - BLOCK_LOOKAROUND,
            x0World + blockWorldW + BLOCK_LOOKAROUND,
        )

        val bmp = Bitmap.createBitmap(blockW, screenH, Bitmap.Config.ARGB_8888)
        paintItems(Canvas(bmp), buildItems(state.canv, x0Px))
        return bmp
    }

    /** 上游的 `chunkloader` + `chunkrender`（照抄移植层，不重排）。 */
    private fun loadRange(x0World: Double, x1World: Double) {
        ShanShuiPlanner.load(state, x0World, x1World)
        ShanShuiPlanner.render(state, x0World, x1World)
    }

    /**
     * 门禁缝：**照抄生产块烘焙**的加载区间（含 [BLOCK_LOOKAROUND] 余量）。
     *
     * ⭐ 与 [bakeSceneBlock] 的 `loadRange` 参数**逐字一致** ⇒ 门禁看到的加载区间
     *   与真机烘焙的加载区间**完全相同** ⇒ 这条门禁守得住「跨块边界的图元不被切断」。
     *
     * ⛔ 门禁的其它入口（[gateItemsForRange]）**刻意不带余量** —— 那是为了快，
     *   而这条缝专门用来守「余量够不够」。
     *
     * @param x0Px 块左缘（**px**，与生产的 `bakeSceneBlock(x0Px, …)` 同口径）。
     */
    internal fun gateBlockForGate(x0Px: Double): GateBlock {
        val blockW = pool?.blockWidth ?: return GateBlock(0.0, 0.0, emptyList())
        val x0World = x0Px / scale
        val blockWorldW = blockW.toDouble() / scale
        ensureStateAnchor(x0World)
        // ⭐ 与生产**逐字一致**的加载区间。
        loadRange(
            x0World - BLOCK_LOOKAROUND,
            x0World + blockWorldW + BLOCK_LOOKAROUND,
        )
        return GateBlock(
            x0World,
            x0World + blockWorldW,
            buildItems(state.canv, x0Px),
        )
    }

    /** 门禁缝的返回值：一块场景的**世界区间** + 它**实际画出的**绘制项。 */
    internal data class GateBlock(
        val worldX0: Double,
        val worldX1: Double,
        val items: List<DrawItem>,
    )

    /**
     * 状态回收：世界走得够远就**裁掉滚远了的图元**（⛔ **不再整体重置**）。
     *
     * ⚠️ 上游 `MEM.chunks` 是**永不回收**的数组 ⇒ 世界理论上无限长、画面天然连续。
     *
     * ⭐ **2026-11-06 改法**：早期做法是「世界走远就把整份 [state] 换成新的」（整体重置），
     * ⛔ 但那会**丢掉全部历史** ⇒ 新生成的块只剩自己那一小段区间的内容
     * ⇒ 与旧块（内容丰富）在边界处**内容密度突变** ⇒ 画面出现「断」（真机截图证据）。
     * ⇒ 改成**只裁掉滚远了的图元**（[ShanShuiPlanner.trimChunks]）⇒
     * · 历史保留 ⇒ 新旧块**同源**（同一份累积画布）⇒ **画面无限连续** ✓
     * · 内存有界 ✓
     */
    private fun ensureStateAnchor(x0World: Double) {
        if (x0World - stateAnchorWorld <= TRIM_SPAN) return
        stateAnchorWorld = x0World
        ShanShuiPlanner.trimChunks(state, x0World - KEEP_BEHIND_WORLD)
        // ⚠️ 安全网：万一图元数仍过多（例如窗口被大幅缩小），才整体重置。
        if (state.chunks.size > STATE_HARD_RESET_CHUNKS) {
            state = ShanShuiPlanner.State().also {
                it.xmin = x0World
                it.xmax = x0World
            }
        }
    }

    // ─────────────────────── 绘制项（生产与门禁/预览共用） ───────────────────────

    /**
     * 一条绘制项 —— 上游一次 `poly()` 发射的等价物（**块内像素坐标**）。
     *
     * ⭐ 生产（Android `Canvas`）与门禁/预览（软件光栅器）**消费同一份绘制项**
     * ⇒ 门禁看到的与真机画出的**是同一套变换与同一套颜色**。
     */
    internal class DrawItem(
        /** 块内像素坐标（`x,y,x,y,…`）。 */
        val pts: FloatArray,
        /** 解析后的填充色（白填充已被换成纸色）；`null` = 不填充。 */
        val fill: ShanShuiColor?,
        /** 描边色（轮廓自带）；`null` = 不描边。 */
        val stroke: ShanShuiColor?,
        /** 描边宽度（上游 `poly` 的 `wid`）。 */
        val width: Double,
        /** 世界 x 的最小值（门禁用：验「单一 x 偏移」）。 */
        val worldMinX: Double,
        /** 世界 x 的最大值。 */
        val worldMaxX: Double,
        /** 该项的填充**是不是**上游的白填充（被换成了纸色）。 */
        val paperFilled: Boolean,
    )

    /**
     * 把上游轮廓翻译成绘制项。
     *
     * @param contours 上游的轮廓列表（[ShanShuiPlanner.State.canv]，**已按全局画家序排好**）。
     * @param originPx 绘制项的原点（px）：块内坐标用块的左缘；整幅预览用 `cursxWorld × scale`。
     */
    private fun buildItems(contours: List<ShanShuiContour>, originPx: Double): List<DrawItem> {
        val out = ArrayList<DrawItem>(contours.size)
        for (c in contours) {
            val n = c.pts.size
            if (n < 2) continue
            val px = FloatArray(n * 2)
            var minX = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            for (i in 0 until n) {
                val wx = c.pts[i][0].toDouble()
                val wy = c.pts[i][1].toDouble()
                px[i * 2] = (wx * scale - originPx).toFloat()
                px[i * 2 + 1] = (wy * scale).toFloat()
                if (wx < minX) minX = wx
                if (wx > maxX) maxX = wx
            }
            val raw = c.fill
            val isWhite = raw != null && isPaperWhite(raw)
            // ⭐ 白填充 ⇒ **纸色**（不透明）—— 等价上游 multiply 的「擦成纸底」。
            val fill = if (isWhite) paperColor() else raw?.let { multiplyByPaper(it) }
            // ⚠️ 描边同样要乘纸色（上游是**整幅** multiply，不是只对白填充）⇒ 见 [multiplyByPaper]。
            val stroke = c.stroke?.let { multiplyByPaper(it) }
            out.add(DrawItem(px, fill, stroke, c.width, minX, maxX, isWhite))
        }
        return out
    }

    /**
     * 上游的**整幅 multiply**：`mix-blend-mode: multiply` 加在**整个 `<svg>`** 上
     * （上游 `index.html:4028`）。
     *
     * ## 上游的合成语义
     *
     * CSS Compositing 的 multiply 公式（backdrop = 纸底，不透明）：
     * `最终色 = p × (1 − α + α·c/255)`（逐通道）。
     *
     * ⭐ **这个公式可以化简成「先把颜色乘纸色、再按原 α 做 source-over」**：
     * `p×(1−α) + (p·c/255)×α = p×(1−α+α·c/255)` ✓ **完全等价**。
     * 而 source-over 对颜色是**线性**的 ⇒ 只要在**生成绘制项时**把每个颜色乘上纸色，
     * 组内合成与最终合成两步都自动正确 ⇒ **不需要任何 blend mode**（⇒ 不受 API 22 限制）。
     *
     * ## 数值验证（用上游参考图实测反推）
     *
     * 上游远山刻面实测 `RGB(213,202,181)`；纸底 `(245,232,208)` ⇒ 反推原始灰
     * `221.7 / 222.0 / 221.9`（三通道一致 ⇒ 确为「纯灰 × 纸底」）。
     * 本实现：`222×245/255 = 213.2`、`222×232/255 = 202.0`、`222×208/255 = 181.1`
     * ⇒ **(213,202,181)** 与上游**逐通道吻合** ✓
     *
     * ## 已知近似
     *
     * ⚠️ 这里用的是**纸色均值**（平色）当 backdrop ⇒ 墨色区域**不透出纸纹**；
     * 上游的纸纹会透过墨色（真 multiply 逐像素相乘）。
     * ⇒ 与「白填充用平色纸」是**同一类近似**（已记在 §7.11.3）。
     *
     * ⛔ **印章不参与 multiply**：上游没有印章（它的落款是屋顶招牌上的 `"Pizza Hut"` 彩蛋）
     * ⇒ 印章是我们唯一的自创保留项 ⇒ 保持原色（朱砂），不乘纸色。
     */
    private fun multiplyByPaper(c: ShanShuiColor): ShanShuiColor {
        val pr = (paperColorRgb shr 16) and 0xFF
        val pg = (paperColorRgb shr 8) and 0xFF
        val pb = paperColorRgb and 0xFF
        // ⚠️ `+127` 是四舍五入（浏览器/Skia 的 8 位乘法是四舍五入，不是截断）。
        return ShanShuiColor(
            (c.r * pr + 127) / 255,
            (c.g * pg + 127) / 255,
            (c.b * pb + 127) / 255,
            c.a,
        )
    }

    /**
     * 把绘制项画进画布（生产路径：Android `Canvas`）。
     *
     * ⚠️ 逐条「先填后描」—— 与上游 `poly()` 的发射顺序一致（上游一条 `poly` 同时带
     * `fill` 与 `stroke` ⇒ 浏览器先填后描）⇒ 层叠关系与上游一致。
     *
     * ⛔ 逐帧**绝不**画非凸 Path —— 这里画的是**软件画布**（`Canvas(Bitmap)`），
     * 非凸自交填充在软件画布上是安全的（真机事故只发生在硬件画布 + `concat`）。
     */
    private fun paintItems(cv: Canvas, items: List<DrawItem>) {
        for (it in items) {
            val fill = it.fill
            if (fill != null) {
                buildPath(it.pts, close = true)
                ptFill.style = Paint.Style.FILL
                ptFill.color = argbOf(fill)
                cv.drawPath(path, ptFill)
            }
            val stroke = it.stroke
            if (stroke != null) {
                buildPath(it.pts, close = false)
                ptStroke.style = Paint.Style.STROKE
                ptStroke.strokeWidth = max(1f, it.width.toFloat())
                ptStroke.color = argbOf(stroke)
                cv.drawPath(path, ptStroke)
            }
        }
    }

    /** 点列 → `Path`（`close = true` 用于填充，闭合是上游 `poly` 填充的隐式行为）。 */
    private fun buildPath(pts: FloatArray, close: Boolean) {
        path.reset()
        if (pts.size < 4) return
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) {
            path.lineTo(pts[i], pts[i + 1])
            i += 2
        }
        if (close) path.close()
    }

    private fun argbOf(c: ShanShuiColor): Int =
        Color.argb((c.a.coerceIn(0.0, 1.0) * 255.0).toInt().coerceIn(0, 255), c.r, c.g, c.b)

    // ─────────────────────── 纸底（上游绢白） ───────────────────────

    /** 纸底烘焙的产物。 */
    private class PaperTile(
        val bitmap: Bitmap,
        val pixels: IntArray,
        val avgRgb: Int,
    )

    /**
     * 纸底 tile —— 照抄上游 `:4336-4356`。
     *
     * ```
     * reso = 512
     * c = 245 + Noise.noise(i*0.1, j*0.1) * 10 - Math.random() * 20
     * r = c.toFixed(0); g = (c*0.95).toFixed(0); b = (c*0.85).toFixed(0)
     * 画 4 个对称像素：(i,j) (reso-i,j) (i,reso-j) (reso-i,reso-j)
     * ```
     *
     * ⛔ 用 [ShanShuiPrng.global.rnd]（不是 `Math.random()`）⇒ **可复现**。
     * ⛔ 四个象限的**写入次序**照抄上游（后写覆盖先写）⇒ 逐像素与上游一致。
     * ⛔ 上游 `fillRect(reso-i, …)` 在 `i = 0` 时落在 x = 512（画布外）⇒ 被裁掉 ⇒ 这里同样跳过越界像素。
     */
    private fun bakePaperTile(): PaperTile {
        val reso = PAPER_RESO
        val px = IntArray(reso * reso)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var i = 0
        while (i <= reso / 2) {
            var j = 0
            while (j <= reso / 2) {
                val c = 245.0 + ShanShuiNoise.noise(i * 0.1, j * 0.1) * 10.0 -
                    ShanShuiPrng.global.rnd() * 20.0
                val r = ShanShuiUtil.toFixed(c, 0).toInt()
                val g = ShanShuiUtil.toFixed(c * 0.95, 0).toInt()
                val b = ShanShuiUtil.toFixed(c * 0.85, 0).toInt()
                val col = Color.rgb(r, g, b)
                setPx(px, reso, i, j, col)
                setPx(px, reso, reso - i, j, col)
                setPx(px, reso, i, reso - j, col)
                setPx(px, reso, reso - i, reso - j, col)
                j++
            }
            i++
        }
        // 纸色均值（供「白填充 ⇒ 纸色」用；⛔ 不写死魔数）。
        for (c in px) {
            sumR += (c shr 16) and 0xFF
            sumG += (c shr 8) and 0xFF
            sumB += c and 0xFF
        }
        val n = px.size
        val avg = Color.rgb(
            (sumR / n).toInt(),
            (sumG / n).toInt(),
            (sumB / n).toInt(),
        )
        return PaperTile(Bitmap.createBitmap(px, reso, reso, Bitmap.Config.ARGB_8888), px, avg)
    }

    /** 写一个像素（越界跳过 —— 对应上游 `fillRect` 被画布裁掉）。 */
    private fun setPx(px: IntArray, reso: Int, x: Int, y: Int, col: Int) {
        if (x < 0 || y < 0 || x >= reso || y >= reso) return
        px[y * reso + x] = col
    }

    /** 纸色（`0xRRGGBB`）。 */
    private fun paperColor(): ShanShuiColor = ShanShuiColor(
        (paperColorRgb shr 16) and 0xFF,
        (paperColorRgb shr 8) and 0xFF,
        paperColorRgb and 0xFF,
        1.0,
    )

    // ─────────────────────── 白填充判定 ───────────────────────

    /**
     * 上游的 `fil: "white"` 判定 —— **精确的纯白**（r = g = b = 255）。
     *
     * ### 为什么不是「r/g/b 都 ≥ 235」
     *
     * 现有渲染器的 `isWhiteish()` 用的是 `≥ 235`；**在本模式里那个阈值会误伤上游的远山**：
     * 上游 `Mount.distMount` 的三角剖分用 `getCol()` 上色（`:2409-2412`），
     * `c = (Noise.noise(x*0.02, y*0.02, yoff) * 55 + 200) | 0` ⇒ **灰阶 ∈ [200, 255]**。
     * ⇒ 用 `≥ 235` 会把远山约三分之一的刻面当成「白」⇒ 擦成纸底 ⇒ 远山的明暗层次被抹平。
     *
     * ⭐ 上游的 `fil: "white"` 在移植层里是**精确的** `(255, 255, 255, 1.0)`
     * （`ShanShui.kt` 的 `whiteFill`）⇒ 用精确判定既忠于上游、又不会误伤灰阶刻面。
     * （`getCol` 的上界是 251 —— perlin 噪声的振幅和恒为 0.9375 ⇒ 取不到 255。）
     */
    private fun isPaperWhite(c: ShanShuiColor): Boolean =
        c.r == 255 && c.g == 255 && c.b == 255

    // ─────────────────────── 运动学 ───────────────────────

    /**
     * 滚动速度（**世界单位 / 秒**）。
     *
     * ⭐ **取上游口径：100 世界单位 / 秒**
     * （上游 `index.html:4211` `INC_STEP = 200`，`:4072` `autoxcroll` 每 **2000 ms** 调一次
     *   `xcroll(v)` ⇒ `200 / 2 = 100` 世界单位/秒）。
     * ⇒ 观感与上游网页一致：上游可见世界宽 2627 单位 ⇒ 约 26 s 走完一屏。
     *
     * ⛔ **不要**沿用设色路径的「视觉 px/s 相同」口径（`SPEED_MID_PX / scale`）：
     *   那是 `10 / 1.542 ≈ 6.5` 世界单位/s ⇒ **比上游慢 15 倍**（2026-11-06 修正）。
     */
    private fun scrollSpeedWorld(): Double = SCROLL_SPEED_WORLD

    /**
     * 门禁缝：把**完整一帧**（纸底 + 场景）画进给定的 [Canvas]。
     *
     * ⛔ 只给**门禁 / 预览**用（Robolectric 的 **NATIVE 图形模式** ⇒ 走**真正的 Android Canvas / Skia**）。
     *
     * ⇒ 与生产的差别只有「一次画完」vs「分块烘焙 + blit」；
     *    **变换、颜色、Paint、Path、纸纹 Shader 全是生产同一套** ⇒ 可用来做设备级画面校验。
     *
     * @param x0World 可见世界区间起点（世界单位）。
     * @param x1World 可见世界区间终点（世界单位）。
     */
    internal fun gateDrawFrameForGate(
        cv: Canvas,
        w: Int,
        h: Int,
        x0World: Double,
        x1World: Double,
    ) {
        if (w != laidW || h != laidH) rebuild(w, h)
        drawPaper(cv, w.toFloat(), h.toFloat())
        ensureStateAnchor(x0World)
        loadRange(x0World, x1World)
        // 屏幕 x = (worldX − cursxWorld)·scale ⇒ 原点即 cursxWorld·scale（与生产的 offsetPx 同口径）。
        paintItems(cv, buildItems(state.canv, cursxWorld * scale))
    }

    // ─────────────────────── 门禁缝（仅观测，不改行为） ───────────────────────

    /** 世界 → 屏幕的**均匀**变换（门禁用它验「x/y 同一缩放」）。 */
    internal class WorldTransform(val scale: Double, val cursxWorld: Double) {
        /** 世界 x → 屏幕 x（px）。 */
        fun screenX(worldX: Double): Double = (worldX - cursxWorld) * scale

        /** 世界 y → 屏幕 y（px）。 */
        fun screenY(worldY: Double): Double = worldY * scale
    }

    /** 门禁缝：当前的世界变换。 */
    internal fun gateTransformForGate(): WorldTransform = WorldTransform(scale, cursxWorld)

    /** 门禁缝：当前的**唯一**滚动量（世界单位）。 */
    internal fun gateScrollForGate(): Double = cursxWorld

    /** 门禁缝：当前的纸色（`0xRRGGBB`）。 */
    internal fun gatePaperColorRgbForGate(): Int = paperColorRgb

    /** 门禁缝：纸底 tile 的原始像素（长度 = 512×512，0xAARRGGBB）。 */
    internal fun gatePaperPixelsForGate(): IntArray? = paperPixels

    /** 门禁缝：场景块的宽度（px）—— 预览要按它出「单块」图。 */
    internal fun gateBlockWidthForGate(): Int = pool?.blockWidth ?: 0

    /** 门禁缝：按给定画幅走**生产的 rebuild 路径**（⇒ 纸底 / 缩放 / 块池都是真的）。 */
    internal fun gatePrepareForGate(w: Int, h: Int) {
        rebuild(w, h)
    }

    /**
     * 门禁缝：取某段世界的**绘制项**（与生产同一条路径：同一条 load/render、同一条变换）。
     *
     * @param x0World 段起点（世界单位）。
     * @param x1World 段终点（世界单位）。
     * @param originPx 绘制项的原点（px）。传 `cursxWorld × scale` ⇒ 坐标即**屏幕坐标**。
     */
    internal fun gateItemsForRange(
        x0World: Double,
        x1World: Double,
        originPx: Double,
    ): List<DrawItem> {
        ensureStateAnchor(x0World)
        // ⚠️ **这里刻意不加 [BLOCK_LOOKAROUND] 余量**（与生产的块烘焙不同）：
        //   余量是为「跨块边界的图元要完整」服务的**渲染**需求；
        //   而门禁每次调用都会新建渲染器 ⇒ 带余量会让每个门禁多规划 3000 世界单位
        //   ⇒ 反复生成山体 ⇒ 实测把 E50 门禁从 ~2 分钟拖到 >40 分钟（2026-11-06 真踩）。
        //   ⇒ 门禁只验「图元本身的几何/颜色」⇒ 用**精确区间**即可。
        loadRange(x0World, x1World)
        return buildItems(state.canv, originPx)
    }

    /** 门禁缝：当前的世界状态（chunk 列表 ⇒ 画家序）。 */
    internal fun gateStateForGate(): ShanShuiPlanner.State = state

    /**
     * 门禁缝：把一个颜色按上游的**整幅 multiply** 变换（[multiplyByPaper]）。
     *
     * ⛔ 只给门禁/测试用（用来钉死「墨色乘纸色」这条上游语义）。
     */
    internal fun gateMultiplyByPaperForGate(c: ShanShuiColor): ShanShuiColor =
        multiplyByPaper(c)

    /** 门禁缝：设定滚动量（**只用于预览**：把镜头挪到世界的某一段）。 */
    internal fun gateSetScrollForGate(v: Double) {
        cursxWorld = v
    }

    // ─────────────────────── 常量 ───────────────────────

    private companion object {

        /** 上游 `calcViewBox()` 的 zoom（`:3996`）。 */
        const val UPSTREAM_ZOOM = 1.142

        /** 上游 `MEM.windy`（`:3825`）。 */
        const val UPSTREAM_WINDY = 800.0

        /**
         * 可见世界高（世界单位）= `MEM.windy / zoom` = 800 / 1.142 = 700.5253940455342。
         *
         * ⛔ 这是**上游算出来的数**，⛔ 不许手抄成 700.525（会差 4e-4 世界单位）。
         */
        val VISIBLE_WORLD_H: Double = UPSTREAM_WINDY / UPSTREAM_ZOOM

        /** 纸底 tile 的分辨率（上游 `:4340`）。 */
        const val PAPER_RESO = 512

        /**
         * 印章边长（占屏高比例）—— 与设色路径同一系数（[QianliScrollRenderer] 的 `SEAL_SIZE_K`）。
         *
         * ⛔ 上游**没有**印章（它的落款是屋顶招牌上的 `"Pizza Hut"`）⇒ 这是所有者裁定的自创保留项。
         */
        const val SEAL_SIZE_K = 0.09f

        /** 块数（同现有渲染器：N = 2 ⇒ 覆盖整屏时另一块可在后台重生）。 */
        const val BLOCK_COUNT = 2

        /**
         * **滚动速度（世界单位 / 秒）** —— 上游口径 **100**。
         *
         * 依据：上游 `index.html:4211` `INC_STEP = 200`（默认增量），
         * `:4072` `autoxcroll` 每 **2000 ms** 调一次 `xcroll(v)` ⇒ `200 / 2 = 100` 世界单位/秒。
         *
         * ⛔ **不是**「视觉 px/s」—— 上游的 SVG 是 3000×800（zoom 1.142），
         *   它的 100 世界单位/秒 在自己画布上就是 100 px/s ⇒ **观感与上游网页一致**。
         */
        const val SCROLL_SPEED_WORLD = 100.0

        /**
         * **裁剪**的触发跨度（世界单位）：世界走了这么多就裁一次滚远了的图元。
         *
         * ⛔ **不是**「整体重置」的跨度 —— 见 [ensureStateAnchor] 的说明。
         */
        const val TRIM_SPAN = 512.0

        /**
         * 裁剪时**保留**镜头后方这么多世界单位（≈ 2.4 屏）。
         *
         * ⛔ 必须 ≥ 块宽（≈1245）+ [BLOCK_LOOKAROUND] ⇒ 否则仍在屏幕上的图元会被裁掉 ⇒ 又出现「断」。
         */
        const val KEEP_BEHIND_WORLD = 3000.0

        /**
         * 烘焙时的**余量**（世界单位）：向外多规划/多渲染这么多。
         *
         * ⭐ 必须 ≥ 最大的图元半宽（山体 `wid ≤ 600` ⇒ 半宽 300；再加 `xof` 的 ±250 抖动 ⇒ ≈550）
         *   ⇒ 取 900 留足余量。
         * ⛔ 不带余量 ⇒ 跨块边界的图元在烘焙时不在画布里 ⇒ 被**竖直切断**（真机截图证据）。
         */
        const val BLOCK_LOOKAROUND = 900.0

        /**
         * 安全网：图元数超过这个值就**整体重置**（正常情况下永远达不到 ——
         * 裁剪会把滚远的图元清掉 ⇒ 数量只与「镜头附近的图元密度」有关）。
         */
        const val STATE_HARD_RESET_CHUNKS = 20000
    }
}
