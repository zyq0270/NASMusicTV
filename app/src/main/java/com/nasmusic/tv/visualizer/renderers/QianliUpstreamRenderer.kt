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
        loadRange(x0World, x0World + blockWorldW)

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
     * 状态回收：世界走得够远就把 [state] 换成一份**锚在当前世界**的新状态。
     *
     * ⚠️ 上游 `MEM.chunks` 是**永不回收**的数组 ⇒ 长驻进程必须自己设上限。
     * 新状态的 `xmin/xmax` 直接锚到当前世界 ⇒ [ShanShuiPlanner.load] 只会向前规划
     * （外加一次 512 宽的回看），不会从 0 开始重扫。
     */
    private fun ensureStateAnchor(x0World: Double) {
        if (x0World - stateAnchorWorld <= STATE_RESET_SPAN) return
        state = ShanShuiPlanner.State().also {
            it.xmin = x0World
            it.xmax = x0World
        }
        stateAnchorWorld = x0World
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
            val fill = if (isWhite) paperColor() else raw
            out.add(DrawItem(px, fill, c.stroke, c.width, minX, maxX, isWhite))
        }
        return out
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
     * ⭐ 换算口径：现有的中景层速度是 [SPEED_MID_PX] px/s（方案 §4.3 实测表）⇒
     * 除以 [scale]（px / 世界单位）⇒ 世界单位 / 秒 ⇒ **视觉滚动速度与现状一致**
     * （不会让用户觉得突然变慢 / 变快）。
     */
    private fun scrollSpeedWorld(): Double = SPEED_MID_PX / scale

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
        loadRange(x0World, x1World)
        return buildItems(state.canv, originPx)
    }

    /** 门禁缝：当前的世界状态（chunk 列表 ⇒ 画家序）。 */
    internal fun gateStateForGate(): ShanShuiPlanner.State = state

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
         * 视觉滚动速度（px/s）—— 取现有渲染器的**中景层**速度（`SPEED_MID` = 10.0）。
         *
         * ⚠️ 这里是**换算的基准**，⛔ 不是本模式直接使用的速度（世界单位/秒由它除以 scale 得到）。
         */
        const val SPEED_MID_PX = 10.0

        /**
         * 世界状态的**回收跨度**（世界单位）。
         *
         * ⚠️ 上游 `MEM.chunks` 永不回收 ⇒ 长驻进程必须自己设上限。
         * 取 8000 世界单位 ≈ 20 分钟滚动（@1080p、10 px/s）⇒ 内存有界、又不频繁重建。
         */
        const val STATE_RESET_SPAN = 8000.0
    }
}
