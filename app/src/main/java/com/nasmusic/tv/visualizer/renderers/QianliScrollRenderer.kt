package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.fx.AudioSmoother
import com.nasmusic.tv.visualizer.fx.FxLevel
import com.nasmusic.tv.visualizer.qianli.LayerSpec
import com.nasmusic.tv.visualizer.qianli.QianliBlockPool
import com.nasmusic.tv.visualizer.qianli.QianliColorTable
import com.nasmusic.tv.visualizer.qianli.QianliSeal
import com.nasmusic.tv.visualizer.qianli.port.ShanShui
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiColor
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiContour
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPath
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPlanner
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPrng
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiUtil
import kotlin.math.PI
import kotlin.math.max

/**
 * E50「千里江山 · 青绿长卷」滚动渲染器（K6）。
 *
 * ## 移植来源
 * - 上游：`LingDong-/shan-shui-inf`（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * - 方案：`docs/e50-qianli-jiangshan-plan.md` §一 / §3.1′ / §3.2 / §4.1′ / §4.3 / §4.4 /
 *   §4.5 / §5.1 / §5.4 / §七.4。
 * - 几何：port 包 `com.nasmusic.tv.visualizer.qianli.port`（`ShanShui` / `ShanShuiPlanner`）。
 *
 * ## 移植状态：**改造-⑥**（渲染层）
 * 几何一行不重写；渲染层把 port 的「点列」翻译成 `android.graphics.Path`，
 * **在烘焙期**画进位图块，逐帧只做 blit。
 *
 * ## 三条不可动的结构纪律
 * 1. **逐帧只 blit 位图** —— ⛔ 逐帧绝不画非凸 Path（API 22 真机 `canvas.concat` ×
 *    非凸填充会 80 帧 0 像素，E46 实测）。几何复杂度全部留在烘焙期。
 * 2. **每层一个世界** —— 层间速度不同 ⇒ 世界坐标不同 ⇒ ⛔ 不共用 chunk 列表
 *    （唯一例外：L2 与 L2′ 轮廓光**必须**同种子同相位，故共用同一个世界）。
 * 3. **位移走 `drawBitmap` 的 x 参数** —— ⛔ 不碰 `Shader.setLocalMatrix`（E43 真机静默失效）。
 *
 * ## 图层序（= 绘制序，⛔ 不得重排）
 * ```
 * L0 绢底天幕 → L0 做旧罩纱 → L1 远景 → L2 中景 → L2′ 轮廓光 →
 * L4 江水 → L4′ 江水高光 → L3 近景 → L3 近景雾 → L5 光尘 → L6 grade → L7 印章
 * ```
 * ⚠️ L3 近景压在 L4 江水**之上**（近岸在水之前）；L4′ 高光与 L4 同层同速，
 * 故一并画在水上、近景之下。
 *
 * ## 门禁缝（仅观测，不改行为）
 *
 * 末尾的「门禁缝」一节暴露了**只读**的内部状态（各层 offset / α / 基准速度）与一个
 * 逐帧推进入口 `gateFrame`，供 `QianliScrollGateTest` 的 G-SCROLL-1/3/4 在
 * **不构造 Compose `DrawScope`** 的前提下逐帧观测。
 * 它们走的是与 `drawContent` **同一段代码**（`updateEnvelopes` / `advanceLayerOffsets` /
 * `computeAlphas`）⇒ 门禁看到的就是渲染路径真正用的值，⛔ 不可能分叉。
 */
class QianliScrollRenderer : RendererFx() {

    override val theme = VisualizerTheme.QIANLI_SCROLL

    /**
     * ⭐ **字面量 `0.10f` 是刻意写的，而基类那一次全屏暗角永远不画**。
     *
     * 暗角与颗粒已由 [bakeGrade] 烘在同一张 grade 位图里（逐像素只依赖 `(i,j)`
     * ⇒ 天然可分帧 ⇒ 逐帧只 1 次 blit / 1.000 屏）。基类后处理再叠一次就是
     * **第二遍暗角 + 第二遍颗粒**（四角压到死黑 + 两层噪点互咬）。
     *
     * ⚠️ 但**不能**因此把 `postFx` 写成 `PostFx.NONE`：`NONE` 会让
     * `RendererFx.needsDamageCoalescer` 在 LITE/FULL 也为真 ⇒ `OverlayFx.drawDamageCoalescer`
     * 每帧补一次整画布 `drawRect`（肉眼不可见，铺屏整整 1.000 屏），而本效果每帧头一笔
     * 就是全屏 `blitFull(sky)` ⇒ 脏区早已并成一整块，这次补画零收益。
     * ⇒ 与 E46/E47/E48/E49 同款三段式：字面量留着（门禁与 `needsDamageCoalescer` 的账）+
     * [vignetteOverride] 恒 `0f` 关断绘制。⛔ 两条必须一起翻案。
     */
    override val postFx = PostFx(vignette = 0.10f)

    /** 恒 `0f`：暗角由烘焙位图承担，基类那一次必须关掉（见 [postFx]）。⛔ 不许改成别的值。 */
    override fun vignetteOverride(fx: FxFrame): Float = 0f

    // ─────────────────────── 图层规格与运动学 ───────────────────────

    /**
     * 分层速度（px/s @1080p，方案 §4.3 实测表）。
     *
     * ⚠️ L2 与 L2′ **共用** [SPEED_MID] 且共用同一个 offset ⇒ G-SCROLL-1 恒等。
     */
    private val speedFar = SPEED_FAR
    private val speedMid = SPEED_MID
    private val speedNear = SPEED_NEAR
    private val speedWater = SPEED_WATER

    /**
     * 各层的世界位移（px），按**层号**索引（见 [LAYER_*]）。
     *
     * ⛔ **同层同速**（G-SCROLL-1）：L2′（轮廓光）与 L4′（高光丝）**不独立累加** ——
     * 每帧由 [advanceLayerOffsets] 从 L2 / L4 的槽位**复制** ⇒ 两者永远逐位相同
     * （轮廓光绝不脱离山脊）。
     */
    private val layerOff = DoubleArray(8)

    /** 光尘的平铺相位（px）。 */
    private var dustPhaseX = 0.0
    private var dustPhaseY = 0.0

    // ─────────────────────── 音频包络（方案 §5.4） ───────────────────────

    /** 雾 / 水 / 速度：attack 0.10、release 0.035。 */
    private val smBass = AudioSmoother(0.10f, 0.035f)
    private val smEnergy = AudioSmoother(0.10f, 0.035f)

    /** 近景雾（mid 那一路，慢档）。 */
    private val smMidFog = AudioSmoother(0.10f, 0.035f)

    /** 淡金光晕：attack 0.18、release 0.05（唯一允许稍快的一路）。 */
    private val smMidGlint = AudioSmoother(0.18f, 0.05f)

    /** 光尘：attack 0.24、release 0.07。 */
    private val smTreble = AudioSmoother(0.24f, 0.07f)

    /** 段落 / 呼吸：attack 0.04、release 0.02。 */
    private val smSection = AudioSmoother(0.04f, 0.02f)

    // ─────────────────────── 资源（本类全部持有） ───────────────────────

    private var skyBmp: Bitmap? = null
    private var gradeBmp: Bitmap? = null
    private var dustTile: Bitmap? = null
    private var dustShader: BitmapShader? = null

    private var poolFar: QianliBlockPool? = null
    private var poolMid: QianliBlockPool? = null
    private var poolGlint: QianliBlockPool? = null
    private var poolNear: QianliBlockPool? = null
    private var poolWater: QianliBlockPool? = null
    private var poolWaterHi: QianliBlockPool? = null

    /** 四个世界（⛔ 每层一个；L2 与 L2′ 共用 [worldMid]，L4 与 L4′ 共用 [worldWater]）。 */
    private var worldFar: ScrollWorld? = null
    private var worldMid: ScrollWorld? = null
    private var worldNear: ScrollWorld? = null
    private var worldWater: ScrollWorld? = null

    /** 当前画幅 / 档位（重建判据）。 */
    private var laidW = -1
    private var laidH = -1
    private var laidLevel: FxLevel? = null

    // ─────────────────────── 逐帧复用的画笔 ───────────────────────

    private val ptBlit = Paint()
    private val ptFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ptStroke = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 轮廓光的**晕**层。
     *
     * ⚠️ [BlurMaskFilter] 只在**软件画布**上有效 —— 本类的晕是烘焙进位图的（`Canvas(Bitmap)`
     * 恒为软件画布）⇒ 逐帧 blit 时它已经是一张烘好的柔光图，⛔ 逐帧不做任何模糊。
     */
    private val ptGlintGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        maskFilter = BlurMaskFilter(GLINT_BLUR_PX, BlurMaskFilter.Blur.NORMAL)
    }
    private val ptGlintCore = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ptWaterHi = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ptDust = Paint()
    private val ptSky = Paint()
    private val ptGrade = Paint()
    private val ptAging = Paint()
    private val ptNearFog = Paint()
    private val path = Path()

    // ─────────────────────── 生命周期 ───────────────────────

    /**
     * 进入效果：**首行释放旧资源**，再复位时间轴与音频。
     *
     * ⛔ 首行这个 `releaseAll()` 不是洁癖：**画质变化会重入 `onEnter`**（不经过 `onExit`）。
     * ⚠️ 位图不在这里分配：`onEnter` 时 `ctx.canvasSize` 还是 `Size.Zero` ⇒ 走 [rebuild]。
     */
    override fun onEnterContent(ctx: RenderContext) {
        releaseAll()
        resetAudio()
    }

    /** 离效果：逐张 `recycle`（API 22–25 的 `ARGB_8888` 像素在 native 堆、不受 GC 自愈）。 */
    override fun onExitContent() {
        releaseAll()
        resetAudio()
    }

    private fun resetAudio() {
        smBass.reset(); smEnergy.reset(); smMidFog.reset()
        smMidGlint.reset(); smTreble.reset(); smSection.reset()
        layerOff.fill(0.0)
        dustPhaseX = 0.0; dustPhaseY = 0.0
    }

    /** 位图 + 块池 + 世界（**重排与离效果都要走这里**）。⛔ 画笔先置 `null`。 */
    private fun releaseAll() {
        // ⚠️ 位图回收前必须先把画笔上的着色器摘掉（否则着色器仍引用着已回收的位图）。
        ptDust.shader = null
        dustShader = null
        recycle(skyBmp); skyBmp = null
        recycle(gradeBmp); gradeBmp = null
        recycle(dustTile); dustTile = null
        poolFar?.release(); poolFar = null
        poolMid?.release(); poolMid = null
        poolGlint?.release(); poolGlint = null
        poolNear?.release(); poolNear = null
        poolWater?.release(); poolWater = null
        poolWaterHi?.release(); poolWaterHi = null
        worldFar = null; worldMid = null; worldNear = null; worldWater = null
        QianliSeal.releaseAll()
        laidW = -1; laidH = -1; laidLevel = null
    }

    private fun recycle(b: Bitmap?) {
        if (b != null && !b.isRecycled) b.recycle()
    }

    // ─────────────────────── 帧体 ───────────────────────

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = ctx.canvasSize.width
        val h = ctx.canvasSize.height
        if (w < 2f || h < 2f) return
        val iw = w.toInt()
        val ih = h.toInt()
        if (iw < 2 || ih < 2) return

        // ① 画幅变化 ⇒ 整组重烘（⛔ 不返回：宁可少画几层，也不黑屏）。
        if (iw != laidW || ih != laidH) rebuild(iw, ih)
        // ② 档位变化 ⇒ 只切换 L4′（⛔ 不动其余层 ⇒ 不清屏）。
        if (fx.level != laidLevel) applyLevel(fx.level)

        // ③ 音频包络。⚠️ `fx.dt` **已经是秒**（FrameClock），⛔ 不再除 1000（E44 偏差 D31）。
        val dt = fx.dt.toDouble()
        updateEnvelopes(frame, dt.toFloat())
        val bassS = envBass
        val energyS = envEnergy
        val midFogS = envMidFog
        val midGlintS = envMidGlint
        val trebleS = envTreble
        val sectionS = envSection

        // ④ 速度：v(eff) = v(base) × (1 + 0.30 × energyS)，上限恰 1.30×。
        //    ⛔ 不用 `beat`；⛔ 各层共用同一个 energyS（层间速度比恒定，否则视差穿帮）。
        advanceLayerOffsets(dt, energyS)
        val dustTileSize = dustTile?.width?.toDouble() ?: 256.0
        dustPhaseX = wrap(dustPhaseX + SPEED_DUST * dt, dustTileSize)
        dustPhaseY = wrap(dustPhaseY + SPEED_DUST * 0.3 * dt, dustTileSize)

        // ⑤ α 通道（⛔ 任何通道不得把对应层降到 0 —— 方案 §5.5 α 下限表）。
        computeAlphas(bassS, midGlintS, midFogS, trebleS, sectionS)

        // ⑥ 整体呼吸：scale ∈ [1.000, 1.030]，驱动 = sectionEnergy + pulse（⛔ 不乘 bass）。
        //    只做**纵向**微扩（各层上下沿），⛔ 不用全屏缩放 redraw。
        val breathK = (sectionS * 0.6 + frame.pulse * 0.4).coerceIn(0.0, 1.0)
        val breath = (BREATH_MIN + (BREATH_MAX - BREATH_MIN) * breathK).toFloat()

        val c = drawContext.canvas.nativeCanvas

        // ⑦ L0 绢底天幕（全屏 1 blit）—— ⛔ 永远第一个画，绝不黑屏。
        skyBmp?.let { c.drawBitmap(it, 0f, 0f, ptBlit) }

        // ⑧ L0 做旧罩纱（段落响度决定画面情绪；⛔ 只改 α，不重烘）。
        drawAging(c, w, h, alphaAging.toDouble())

        // ⑨ 场景层（带呼吸）。
        c.save()
        c.scale(1f, breath, w / 2f, h / 2f)
        drawLayer(c, poolFar, layerOff[LAYER_FAR], alphaFar, bandTop(FAR_Y0, ih))
        drawLayer(c, poolMid, layerOff[LAYER_MID], 1f, bandTop(MID_Y0, ih))
        // ⚠️ L2′ 与 L2 **同一个 offset、同一批 chunk** ⇒ 轮廓光绝不脱离山脊（G-SCROLL-1）。
        drawLayer(c, poolGlint, layerOff[LAYER_GLINT], alphaGlint, bandTop(MID_Y0, ih))
        drawLayer(c, poolWater, layerOff[LAYER_WATER], alphaWater, bandTop(WATER_Y0, ih))
        drawLayer(c, poolWaterHi, layerOff[LAYER_WATER_HI], alphaWaterHi, bandTop(WATER_Y0, ih))
        drawLayer(c, poolNear, layerOff[LAYER_NEAR], 1f, bandTop(NEAR_Y0, ih))
        c.restore()

        // ⑩ L3 近景雾（mid → α）。
        drawNearFog(c, w, h, ih, alphaNearFog.toDouble())

        // ⑪ L5 光尘（treble → α + 平铺相位）。
        drawDust(c, w, h, alphaDust.toDouble())
        // ⑫ L6 grade（暗角 + 颗粒，烘在一起 ⇒ 1 blit / 1.000 屏）。
        gradeBmp?.let { c.drawBitmap(it, 0f, 0f, ptBlit) }

        // ⑬ L7 印章（⛔ 画在 grade 之后，否则暗角把朱砂压暗；尺寸/位置逐帧恒定）。
        drawSeal(c, ctx, w, h)
    }

    // ─────────────────────── 逐帧推进的唯一入口（包络 / 偏移 / α） ───────────────────────

    /**
     * 本帧的音频包络 —— **字段**，⛔ 不是对象（逐帧零分配红线）。
     *
     * ⚠️ 六个包络的推进次序与原 [drawContent] 内联的次序**逐字一致**
     * （各平滑器彼此独立 ⇒ 次序不影响数值，但改次序会让逐帧复现失效）。
     */
    private var envBass = 0.0
    private var envEnergy = 0.0
    private var envMidFog = 0.0
    private var envMidGlint = 0.0
    private var envTreble = 0.0
    private var envSection = 0.0

    private fun updateEnvelopes(frame: AudioFrame, dt: Float) {
        envBass = smBass.updateDt(frame.bass, dt).toDouble()
        envEnergy = smEnergy.updateDt(frame.energy, dt).toDouble()
        envMidFog = smMidFog.updateDt(frame.mid, dt).toDouble()
        envMidGlint = smMidGlint.updateDt(frame.mid, dt).toDouble()
        envTreble = smTreble.updateDt(frame.treble, dt).toDouble()
        envSection = smSection.updateDt(frame.sectionEnergy, dt).toDouble()
    }

    /**
     * 推进各层的世界位移 —— **唯一**的偏移推进点。
     *
     * ⛔ **同层同速**（G-SCROLL-1）：L2′（轮廓光）与 L4′（高光丝）**不独立累加**，
     * 每帧从 L2 / L4 的槽位**复制** ⇒ 两者永远逐位相同。
     *
     * ⛔ 速度调制上限恰 1.30×（`1 + ENERGY_SPEED_GAIN`，energy ∈ [0,1] ⇒ speedK ∈ [1, 1.30]）。
     */
    private fun advanceLayerOffsets(dt: Double, energyS: Double) {
        val speedK = 1.0 + ENERGY_SPEED_GAIN * energyS
        layerOff[LAYER_FAR] += speedFar * speedK * dt
        layerOff[LAYER_MID] += speedMid * speedK * dt
        layerOff[LAYER_NEAR] += speedNear * speedK * dt
        layerOff[LAYER_WATER] += speedWater * speedK * dt
        // 同层同速：轮廓光跟随中景、高光丝跟随江水。
        layerOff[LAYER_GLINT] = layerOff[LAYER_MID]
        layerOff[LAYER_WATER_HI] = layerOff[LAYER_WATER]
    }

    /**
     * 本帧各层的 α —— **字段**，⛔ 不是对象（逐帧零分配红线）。
     *
     * ⛔ α 下限表（方案 §5.5）：任何通道不得把对应层降到 0
     * （L1 远景 ≥0.10 / L2′ 轮廓光 ≥0.10 / L4 江水 ≥0.16 / L5 光尘 ≥0.05）。
     */
    private var alphaFar = 0f
    private var alphaGlint = 0f
    private var alphaWater = 0f
    private var alphaWaterHi = 0f
    private var alphaNearFog = 0f
    private var alphaDust = 0f
    private var alphaAging = 0f

    private fun computeAlphas(
        bassS: Double,
        midGlintS: Double,
        midFogS: Double,
        trebleS: Double,
        sectionS: Double,
    ) {
        val far = lerp(FAR_ALPHA_MIN, FAR_ALPHA_MAX, bassS)
        val glint = lerp(GLINT_ALPHA_MIN, GLINT_ALPHA_MAX, midGlintS)
        val water = lerp(WATER_ALPHA_MIN, WATER_ALPHA_MAX, bassS)
        alphaFar = far.toFloat()
        alphaGlint = glint.toFloat()
        alphaWater = water.toFloat()
        alphaWaterHi = (water * WATER_HI_ALPHA_K).toFloat()
        alphaNearFog = lerp(NEAR_FOG_MIN, NEAR_FOG_MAX, midFogS).toFloat()
        alphaDust = lerp(DUST_ALPHA_MIN, DUST_ALPHA_MAX, trebleS).toFloat()
        alphaAging = lerp(AGING_ALPHA_MIN, AGING_ALPHA_MAX, sectionS).toFloat()
    }

    // ─────────────────────── 门禁缝（仅观测，不改行为） ───────────────────────

    /**
     * ⭐ **仅门禁用的可观测缝** —— **不改变任何渲染行为**。
     *
     * 逐帧推进：音频包络（[updateEnvelopes]）+ 各层偏移（[advanceLayerOffsets]）
     * + 各层 α（[computeAlphas]），与 [drawContent] 走**同一段代码**
     * （⛔ 这里不另写一份公式）。
     *
     * 用途：`QianliScrollGateTest` 的 G-SCROLL-1（同层同速）/ G-SCROLL-3（α 下限）/
     * G-SCROLL-4（速度上限）要在**不构造 Compose `DrawScope`** 的前提下逐帧观测状态。
     */
    internal fun gateFrame(frame: AudioFrame, dtSeconds: Double) {
        updateEnvelopes(frame, dtSeconds.toFloat())
        advanceLayerOffsets(dtSeconds, envEnergy)
        computeAlphas(envBass, envMidGlint, envMidFog, envTreble, envSection)
    }

    /** 门禁缝：某层当前的世界位移（px）。 */
    internal fun layerOffsetForGate(id: Int): Double = layerOff[id]

    /**
     * 门禁缝：某层当前的 α（0..1）—— **就是本帧渲染实际用的那个值**
     * （[computeAlphas] 写进字段，门禁只读 ⇒ 不可能与渲染路径分叉）。
     */
    internal fun layerAlphaForGate(id: Int): Float = when (id) {
        GateLayer.SKY -> 1f
        GateLayer.FAR -> alphaFar
        GateLayer.MID -> 1f
        GateLayer.GLINT -> alphaGlint
        GateLayer.NEAR -> 1f
        GateLayer.WATER -> alphaWater
        GateLayer.WATER_HI -> alphaWaterHi
        GateLayer.DUST -> alphaDust
        else -> error("未知层号：$id")
    }

    /** 门禁缝：某层的**基准**速度（px/s，未乘能量调制）。 */
    internal fun layerSpeedForGate(id: Int): Double = when (id) {
        GateLayer.FAR -> speedFar
        GateLayer.MID -> speedMid
        GateLayer.GLINT -> speedMid
        GateLayer.NEAR -> speedNear
        GateLayer.WATER -> speedWater
        GateLayer.WATER_HI -> speedWater
        GateLayer.DUST -> SPEED_DUST
        GateLayer.SKY -> 0.0
        else -> error("未知层号：$id")
    }

    /**
     * 门禁用的层号（**仅门禁缝使用**）。
     *
     * ⚠️ 1..6 与内部 [LAYER_*] 常量**同号**
     * （1 = L1 远景 / 2 = L2 中景 / 3 = L2′ 轮廓光 / 4 = L3 近景 / 5 = L4 江水 / 6 = L4′ 高光）；
     * 另加 0 = L0 绢底天幕、7 = L5 光尘（这两层没有块池 ⇒ 没有内部层号）。
     */
    internal object GateLayer {
        const val SKY = 0
        const val FAR = 1
        const val MID = 2
        const val GLINT = 3
        const val NEAR = 4
        const val WATER = 5
        const val WATER_HI = 6
        const val DUST = 7
    }

    // ─────────────────────── 门禁缝（轮廓启发式 / 暗角 / 分块复现） ───────────────────────

    /**
     * 门禁缝：「山体白底」轮廓的下标（染色时该用坡面色、其余白块用绢白的那一条）。
     *
     * ⛔ 仅门禁用，不改行为：这里只转调 [bodyFillIndex]，
     * ⛔ 不复制它的判定逻辑（复制 ⇒ 门禁与渲染路径分叉 ⇒ 门禁就成了摆设）。
     */
    internal fun bodyFillIndexForGate(contours: List<ShanShuiContour>): Int = bodyFillIndex(contours)

    /**
     * 门禁缝：山脊描边轮廓的下标（轮廓光 L2′ 描的那一条）。
     *
     * ⛔ 仅门禁用，不改行为：只转调 [ridgeIndex]，⛔ 不复制判定逻辑。
     */
    internal fun ridgeIndexForGate(contours: List<ShanShuiContour>): Int = ridgeIndex(contours)

    /**
     * 门禁缝：本帧的暗角覆盖值（[vignetteOverride] 的实际返回值）。
     *
     * ⛔ 仅门禁用，不改行为：[vignetteOverride] 在基类是 protected ⇒ 门禁看不到；
     * 这里只做一次转调，⛔ 不新增任何分支、不改任何数值。
     */
    internal fun vignetteOverrideForGate(fx: FxFrame): Float = vignetteOverride(fx)

    /**
     * 门禁缝的产物：一块中景位图 + 该世界的内容指纹源（每个 chunk 的轮廓）。
     *
     * ⚠️ 指纹源是位图内容的唯一决定因素（[drawChunk] 是纯函数：同样的轮廓 +
     * 同样的层带映射 ⇒ 同样的像素）⇒ 指纹源相同 ⇒ 位图逐像素相同。
     * ⛔ 因此在 Robolectric 的 LEGACY 图形模式（Canvas 不栅格化）下，
     *    指纹源仍是可判定的那一份内容证据。
     */
    internal class GateMidBlock(
        val bitmap: Bitmap,
        val contours: List<List<ShanShuiContour>>,
    )

    /**
     * 门禁缝：烘一块中景山体（G-REND-4「分块生成可复现」）。
     *
     * ⛔ 仅门禁用，不改行为：它另起一个一次性的 [ScrollWorld]
     * （与 [rebuild] 里的四个世界无关 ⇒ 门禁反复调用不污染渲染器自己的世界状态），
     * 走的是同一段 [ScrollWorld.ensure] → [prepareMid] → [bakeBlock] 代码。
     */
    internal fun gateBakeMidBlockForGate(x0: Double, blockW: Int, blockH: Int): GateMidBlock {
        val world = ScrollWorld(TAG_MOUNT, PLAN_AHEAD_MID, ::prepareMid)
        val map = BandMap(
            bandHeightPx = bandHeight(MID_Y0, MID_Y1, blockH),
            refBaseY = MID_REF_BASE_Y,
            baseSpread = MID_BASE_SPREAD,
            heightScale = MID_HEIGHT_SCALE,
        )
        val bmp = bakeBlock(world, x0, map, Kind.MOUNTAIN, blockW, blockH)
        return GateMidBlock(bmp, world.specs.map { it.contours })
    }

    // ─────────────────────── 逐帧绘制小工具 ───────────────────────

    /** 一层块池的落笔：带顶由画布平移承担（`QianliBlockPool` 在 y=0 落笔）。 */
    private fun drawLayer(
        c: Canvas,
        pool: QianliBlockPool?,
        offset: Double,
        alpha: Float,
        bandTopPx: Float,
    ) {
        val p = pool ?: return
        p.advance(offset)
        if (bandTopPx != 0f) {
            c.save()
            c.translate(0f, bandTopPx)
            p.draw(c, offset, alpha)
            c.restore()
        } else {
            p.draw(c, offset, alpha)
        }
    }

    /** 做旧罩纱：一张**全屏渐变**（暖褐），α 由段落能量驱动。⛔ 不额外占位图内存。 */
    private fun drawAging(c: Canvas, w: Float, h: Float, alpha: Double) {
        if (alpha <= 0.001) return
        ptAging.style = Paint.Style.FILL
        ptAging.shader = agingShader(w, h)
        ptAging.alpha = (alpha * 255.0).toInt().coerceIn(0, 255)
        c.drawRect(0f, 0f, w, h, ptAging)
        ptAging.shader = null
    }

    /** 近景雾：一条压在近景带上的淡青雾纱。 */
    private fun drawNearFog(c: Canvas, w: Float, h: Float, ih: Int, alpha: Double) {
        if (alpha <= 0.001) return
        val top = bandTop(NEAR_Y0, ih)
        val bottom = bandTop(NEAR_Y1, ih)
        ptNearFog.style = Paint.Style.FILL
        ptNearFog.shader = nearFogShader(top, bottom)
        ptNearFog.alpha = (alpha * 255.0).toInt().coerceIn(0, 255)
        c.drawRect(0f, top, w, bottom, ptNearFog)
        ptNearFog.shader = null
    }

    /** 光尘：平铺位图 + 画布平移做相位（⛔ 位图**位移**那条红线针对的是块的滚动位移）。 */
    private fun drawDust(c: Canvas, w: Float, h: Float, alpha: Double) {
        val sh = dustShader ?: return
        val tile = dustTile ?: return
        if (alpha <= 0.001) return
        ptDust.style = Paint.Style.FILL
        ptDust.shader = sh
        ptDust.alpha = (alpha * 255.0).toInt().coerceIn(0, 255)
        c.save()
        c.translate((-dustPhaseX).toFloat(), (-dustPhaseY).toFloat())
        c.drawRect(0f, 0f, w + tile.width, h + tile.height, ptDust)
        c.restore()
        ptDust.shader = null
    }

    /** 印章：右上角，消费 `safeAreaPx`；⛔ 尺寸/位置逐帧恒定（不随音频动）。 */
    private fun drawSeal(c: Canvas, ctx: RenderContext, w: Float, h: Float) {
        val s = SEAL_SIZE_K * h
        if (s < 4f) return
        val inset = ctx.safeAreaPx
        val cx = w - inset - s / 2f
        val cy = inset + s / 2f
        QianliSeal.bake(c, cx, cy, s)
    }

    // ─────────────────────── 建素材（首帧 / 换画幅 / 换档位） ───────────────────────

    /**
     * 重建全部资源。
     *
     * ⚠️ 天幕与 grade 是全屏位图（@1080p 各 ≈ 7.9 MiB）⇒ 分配 + 烘焙有一次明显卡顿。
     * 这是**一次性**的（换画幅时重来一次）；逐帧续烘交给块池自己的预算片。
     */
    private fun rebuild(w: Int, h: Int) {
        releaseAll()
        laidW = w
        laidH = h

        // ① L0 绢底天幕
        skyBmp = bakeSky(w, h)
        // ② L6 grade（暗角 + 颗粒）
        gradeBmp = bakeGrade(w, h)
        // ③ L5 光尘 tile + 平铺着色器
        val tile = bakeDustTile()
        dustTile = tile
        dustShader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)

        // ④ 四个世界（⛔ 每层一个；L2/L2′ 共用 mid，L4/L4′ 共用 water）
        val far = ScrollWorld(TAG_DISTMOUNT, PLAN_AHEAD_FAR, ::prepareFar)
        val mid = ScrollWorld(TAG_MOUNT, PLAN_AHEAD_MID, ::prepareMid)
        val near = ScrollWorld(TAG_FLATMOUNT, PLAN_AHEAD_NEAR, ::prepareNear)
        val water = ScrollWorld(TAG_MOUNT, PLAN_AHEAD_WATER, ::prepareWater)
        worldFar = far; worldMid = mid; worldNear = near; worldWater = water

        // ⑤ 层带映射（世界 y → 层带局部 y）
        val farMap = BandMap(
            bandHeightPx = bandHeight(FAR_Y0, FAR_Y1, h),
            refBaseY = FAR_REF_BASE_Y,
            baseSpread = FAR_BASE_SPREAD,
            heightScale = FAR_HEIGHT_SCALE,
        )
        val midMap = BandMap(
            bandHeightPx = bandHeight(MID_Y0, MID_Y1, h),
            refBaseY = MID_REF_BASE_Y,
            baseSpread = MID_BASE_SPREAD,
            heightScale = MID_HEIGHT_SCALE,
        )
        val nearMap = BandMap(
            bandHeightPx = bandHeight(NEAR_Y0, NEAR_Y1, h),
            refBaseY = NEAR_REF_BASE_Y,
            baseSpread = NEAR_BASE_SPREAD,
            heightScale = NEAR_HEIGHT_SCALE,
        )
        val waterMap = BandMap(
            bandHeightPx = bandHeight(WATER_Y0, WATER_Y1, h),
            refBaseY = 0.0,
            baseSpread = 0.0,
            heightScale = 0.0,
            waterSpan = WATER_LOCAL_SPAN,
            waterOffset = WATER_LOCAL_OFFSET,
        )

        // ⑥ 块池（N = 2，块宽 = 1.05 × 屏宽 ⇒ 覆盖整屏时另一块可在后台重生）
        poolFar = makePool(LAYER_FAR, FAR_Y0.toDouble(), FAR_Y1.toDouble(), speedFar, FAR_ALPHA_MIN, far, farMap, Kind.MOUNTAIN, w, h)
        poolMid = makePool(LAYER_MID, MID_Y0.toDouble(), MID_Y1.toDouble(), speedMid, 1.0, mid, midMap, Kind.MOUNTAIN, w, h)
        poolGlint = makePool(LAYER_GLINT, MID_Y0.toDouble(), MID_Y1.toDouble(), speedMid, GLINT_ALPHA_MIN, mid, midMap, Kind.GLINT, w, h)
        poolNear = makePool(LAYER_NEAR, NEAR_Y0.toDouble(), NEAR_Y1.toDouble(), speedNear, 1.0, near, nearMap, Kind.MOUNTAIN, w, h)
        poolWater = makePool(LAYER_WATER, WATER_Y0.toDouble(), WATER_Y1.toDouble(), speedWater, WATER_ALPHA_MIN, water, waterMap, Kind.WATER, w, h)
        poolWaterHi = makePool(LAYER_WATER_HI, WATER_Y0.toDouble(), WATER_Y1.toDouble(), speedWater, WATER_ALPHA_MIN, water, waterMap, Kind.WATER_HI, w, h)

        // ⚠️ 档位**不**在这里写死：`laidLevel` 留 null ⇒ 首帧的 [applyLevel] 会补写一次
        //    （它的 early-return 只针对「从某个档位切到另一个档位」的重烘路径）。
    }

    /**
     * 档位切换：**只**动 L4′（江水高光）。
     *
     * ⚠️ ⛔ 不重建其余层 —— E48 2026-10-16 真机第二轮返工的教训：
     * 「档位一变就清空整幅画面」⇒ 中间那几帧把已经烘好的天幕也丢了 ⇒ 读成黑屏一下。
     * 本主题 L0 天幕与各层块**与档位无关** ⇒ 一律不动。
     */
    private fun applyLevel(level: FxLevel) {
        laidLevel = level
        val wantWaterHi = level != FxLevel.OFF
        if (wantWaterHi == (poolWaterHi != null)) return
        if (!wantWaterHi) {
            // LOW 档省 L4′（⛔ 但 α 照常计算，与 v1.x 同制）。
            poolWaterHi?.release()
            poolWaterHi = null
            return
        }
        val water = worldWater ?: return
        val w = laidW
        val h = laidH
        if (w <= 0 || h <= 0) return
        val map = BandMap(
            bandHeightPx = bandHeight(WATER_Y0, WATER_Y1, h),
            refBaseY = 0.0,
            baseSpread = 0.0,
            heightScale = 0.0,
            waterSpan = WATER_LOCAL_SPAN,
            waterOffset = WATER_LOCAL_OFFSET,
        )
        poolWaterHi = makePool(
            LAYER_WATER_HI, WATER_Y0.toDouble(), WATER_Y1.toDouble(), speedWater,
            WATER_ALPHA_MIN, water, map, Kind.WATER_HI, w, h,
        )
    }

    /** 造一个块池；块宽由池自己算（⛔ 不在这里复刻公式，避免两边漂移）。 */
    private fun makePool(
        id: Int,
        bandY0: Double,
        bandY1: Double,
        speed: Double,
        alphaFloor: Double,
        world: ScrollWorld,
        map: BandMap,
        kind: Kind,
        w: Int,
        h: Int,
    ): QianliBlockPool {
        var blockW = w
        val pool = QianliBlockPool(
            LayerSpec(id, bandY0, bandY1, speed, alphaFloor),
            BLOCK_COUNT, w, h,
        ) { x0 -> bakeBlock(world, x0, map, kind, blockW, h) }
        blockW = pool.blockWidth
        return pool
    }

    // ─────────────────────── 几何生成（上游 chunkloader 的参数表） ───────────────────────

    /**
     * L1 远景：`distMount`（上游 `index.html:3919-3928`）。
     *
     * ⚠️ 参数与调用次序照抄上游：`rnd()*100` 先、`randChoice` 后（JS 实参左到右求值）。
     */
    private fun prepareFar(spec: ChunkSpec) {
        val rnd = ShanShuiPrng.global
        val seed = rnd.rnd() * 100.0
        val len = ShanShuiUtil.randChoice(listOf(500, 1000, 1500)).toDouble()
        spec.baseY = spec.y
        spec.height = DISTMOUNT_HEI
        spec.minX = spec.x - 60.0
        spec.maxX = spec.x + len + 80.0
        spec.contours = ShanShui.distMount(spec.x, spec.y, seed, DISTMOUNT_HEI, len, DISTMOUNT_SEG)
    }

    /**
     * L2 中景：`mountain`（上游 `index.html:3889-3896`）。
     *
     * ⚠️ 参数与次序照抄：`idx*2.0*rnd()` → `100+rnd()*400` → `400+rnd()*200` → `200` → `true` → `null`。
     */
    private fun prepareMid(spec: ChunkSpec) {
        val rnd = ShanShuiPrng.global
        val seed = spec.idx * 2.0 * rnd.rnd()
        val hei = 100.0 + rnd.rnd() * 400.0
        val wid = 400.0 + rnd.rnd() * 200.0
        spec.baseY = spec.y
        spec.height = hei
        spec.minX = spec.x - wid / 2.0 - 90.0
        spec.maxX = spec.x + wid / 2.0 + 90.0
        spec.contours = ShanShui.mountain(spec.x, spec.y, seed, hei, wid, MOUNTAIN_TEX, true, null)
    }

    /**
     * L3 近景：`flatMount` + `rock`（上游 `index.html:3903-3918` + `flatDec` 的石块）。
     *
     * ⚠️ `flatMount` 的参数与次序照抄上游；石块用上游 BOTT_ROCK 的量级
     * （hei/wid = 20+rnd*20、tex = 40、sha = 2）。
     */
    private fun prepareNear(spec: ChunkSpec) {
        val rnd = ShanShuiPrng.global
        val seed = 2.0 * rnd.rnd() * PI
        val wid = 600.0 + rnd.rnd() * 400.0
        val cho = 0.5 + rnd.rnd() * 0.2
        spec.baseY = spec.y
        spec.height = FLATMOUNT_HEI
        spec.minX = spec.x - wid / 2.0 - 140.0
        spec.maxX = spec.x + wid / 2.0 + 140.0
        val cs = ArrayList<ShanShuiContour>()
        cs.addAll(ShanShui.flatMount(spec.x, spec.y, seed, FLATMOUNT_HEI, wid, FLATMOUNT_TEX, cho))
        val rockCount = 1 + (rnd.rnd() * 2.0).toInt().coerceIn(0, 1)
        for (i in 0 until rockCount) {
            val rx = spec.x + (rnd.rnd() - 0.5) * wid * 0.55
            val ry = spec.y - rnd.rnd() * 20.0
            val rseed = rnd.rnd() * 100.0
            val rhei = 20.0 + rnd.rnd() * 20.0
            val rwid = 20.0 + rnd.rnd() * 20.0
            cs.addAll(ShanShui.rock(rx, ry, rseed, rhei, rwid, ROCK_TEX, ROCK_SHA))
        }
        spec.contours = cs
    }

    /**
     * L4 江水：`water`（上游 `index.html:3897-3902`，与 `"mount"` 同批 chunk 的水）。
     *
     * ⚠️ 上游的水挂在 mount chunk 上（同一个 `pl[idx].x / pl[idx].y`）⇒ 本世界同样只吃
     * `"mount"` tag，但**只生成水**（不生成山体几何）。
     */
    private fun prepareWater(spec: ChunkSpec) {
        spec.baseY = spec.y
        spec.height = WATER_LOCAL_SPAN
        spec.minX = spec.x - WATER_HALF_SPAN - 40.0
        spec.maxX = spec.x + WATER_HALF_SPAN + 40.0
        spec.contours = ShanShui.water(spec.x, spec.y, spec.idx * 2.0, WATER_HEI, WATER_LEN, WATER_CLU)
    }

    // ─────────────────────── 烘焙（位图块） ───────────────────────

    /**
     * 烘一块（世界 x ∈ `[x0, x0 + blockW)`）。
     *
     * ⛔ 逐帧不画非凸 Path —— 这里画进的是**软件画布**（`Canvas(Bitmap)`），
     * 非凸自交填充在软件画布上是安全的（真机事故只发生在硬件画布 + `concat`）。
     */
    private fun bakeBlock(
        world: ScrollWorld,
        x0: Double,
        map: BandMap,
        kind: Kind,
        blockW: Int,
        blockH: Int,
    ): Bitmap {
        world.ensure(x0)
        val bmp = Bitmap.createBitmap(blockW, blockH, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val x1 = x0 + blockW.toDouble()
        val specs = world.specs
        for (i in specs.indices) {
            val spec = specs[i]
            if (spec.tag != world.wantTag) continue
            // ⚠️ 按**内容范围**相交来选，不是按 chunk 原点 ——
            //    distMount 的轮廓向右伸到 `x + len`（最多 1500），按原点选会在块边界切断远山。
            if (spec.maxX < x0 || spec.minX > x1) continue
            drawChunk(cv, spec, x0, map, kind)
        }
        return bmp
    }

    /** 画一个 chunk 的轮廓（两趟：先 fill，再 stroke）。 */
    private fun drawChunk(cv: Canvas, spec: ChunkSpec, x0: Double, map: BandMap, kind: Kind) {
        val contours = spec.contours
        if (contours.isEmpty()) return
        when (kind) {
            Kind.MOUNTAIN -> {
                // ⭐ 上游的白有**两种**用途（方案 §3.6.5）：
                //   ① 山体白底（最大的一块白）⇒ 染成坡面色（青绿）；
                //   ② 叶簇 / 水花的小白块 ⇒ 换**绢白**（⛔ 不留纯白，也不染成坡面色
                //      ——那会让树与山体同色、树直接消失）。
                val bodyIdx = bodyFillIndex(contours)
                for (i in contours.indices) {
                    val c = contours[i]
                    val f = c.fill ?: continue
                    buildPath(c.pts, x0, spec, map)
                    path.close()
                    ptFill.style = Paint.Style.FILL
                    val col = if (i == bodyIdx) {
                        fillColor(rowJOf(spec, c, i, contours.size, kind), f)
                    } else {
                        whiteFillColor(f)
                    }
                    ptFill.color = argbOf(col)
                    cv.drawPath(path, ptFill)
                }
                for (i in contours.indices) {
                    val c = contours[i]
                    val s = c.stroke ?: continue
                    buildPath(c.pts, x0, spec, map)
                    ptStroke.style = Paint.Style.STROKE
                    ptStroke.strokeWidth = max(1f, c.width.toFloat())
                    ptStroke.color = argbOf(strokeColor(rowJOf(spec, c, i, contours.size, kind), s))
                    cv.drawPath(path, ptStroke)
                }
            }

            Kind.GLINT -> {
                // 轮廓光：**只取山脊** —— 横向跨度最大的那条描边轮廓（皴/树都短得多）。
                val idx = ridgeIndex(contours)
                if (idx < 0) return
                val c = contours[idx]
                buildPath(c.pts, x0, spec, map)
                val w = max(1f, c.width.toFloat())
                ptGlintGlow.style = Paint.Style.STROKE
                ptGlintGlow.strokeWidth = max(3f, w * GLINT_GLOW_W)
                ptGlintGlow.color = Color.argb((GLINT_GLOW_A * 255f).toInt(), GLINT_R, GLINT_G, GLINT_B)
                cv.drawPath(path, ptGlintGlow)
                ptGlintCore.style = Paint.Style.STROKE
                ptGlintCore.strokeWidth = w
                ptGlintCore.color = Color.argb(255, GLINT_R, GLINT_G, GLINT_B)
                cv.drawPath(path, ptGlintCore)
            }

            Kind.WATER -> {
                for (i in contours.indices) {
                    val c = contours[i]
                    val s = c.stroke ?: continue
                    buildPath(c.pts, x0, spec, map)
                    ptStroke.style = Paint.Style.STROKE
                    ptStroke.strokeWidth = max(1f, c.width.toFloat())
                    ptStroke.color = argbOf(fillColor(rowJOf(spec, c, i, contours.size, kind), s))
                    cv.drawPath(path, ptStroke)
                }
            }

            Kind.WATER_HI -> {
                // 高光丝：α 最高的一批波线（⛔ LOW 档整层可省，但 α 照常计算）。
                val thr = waterHiThreshold(contours)
                for (i in contours.indices) {
                    val c = contours[i]
                    val s = c.stroke ?: continue
                    if (s.a < thr) continue
                    buildPath(c.pts, x0, spec, map)
                    ptWaterHi.style = Paint.Style.STROKE
                    ptWaterHi.strokeWidth = max(1f, c.width.toFloat() * 1.25f)
                    ptWaterHi.color = Color.argb(
                        (s.a.coerceIn(0.0, 1.0) * WATER_HI_A * 255.0).toInt().coerceIn(0, 255),
                        WATER_HI_R, WATER_HI_G, WATER_HI_B,
                    )
                    cv.drawPath(path, ptWaterHi)
                }
            }
        }
    }

    /** 把轮廓点列映射进块位图（x 为 1:1；y 走层带映射）。 */
    private fun buildPath(
        pts: List<ShanShuiPath.Point>,
        x0: Double,
        spec: ChunkSpec,
        map: BandMap,
    ) {
        path.reset()
        if (pts.isEmpty()) return
        path.moveTo(
            (pts[0][0] - x0).toFloat(),
            map.bandY(spec.baseY, pts[0][1].toDouble()),
        )
        for (i in 1 until pts.size) {
            path.lineTo(
                (pts[i][0] - x0).toFloat(),
                map.bandY(spec.baseY, pts[i][1].toDouble()),
            )
        }
    }

    /** 山脊 = 横向跨度最大的描边轮廓。 */
    private fun ridgeIndex(contours: List<ShanShuiContour>): Int {
        var best = -1
        var bestSpan = -1.0
        for (i in contours.indices) {
            val c = contours[i]
            if (c.stroke == null) continue
            val span = xSpan(c)
            if (span > bestSpan) {
                bestSpan = span
                best = i
            }
        }
        return best
    }

    /**
     * 「山体白底」的下标 = 白填充里横向跨度最大的那条。
     *
     * ⛔ 找不到（没有白填充）时返回 −1 ⇒ 全部白块都按叶簇处理（退化成绢白）。
     */
    private fun bodyFillIndex(contours: List<ShanShuiContour>): Int {
        var best = -1
        var bestSpan = -1.0
        for (i in contours.indices) {
            val c = contours[i]
            val f = c.fill ?: continue
            if (!isWhiteish(f)) continue
            val span = xSpan(c)
            if (span > bestSpan) {
                bestSpan = span
                best = i
            }
        }
        return best
    }

    /** 轮廓的横向跨度（世界单位）。 */
    private fun xSpan(c: ShanShuiContour): Double {
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        for (p in c.pts) {
            val x = p[0].toDouble()
            if (x < minX) minX = x
            if (x > maxX) maxX = x
        }
        return if (minX > maxX) 0.0 else maxX - minX
    }

    /** 上游的 `white` 判定（叶簇 / 水花 / 山体白底都走这条）。 */
    private fun isWhiteish(c: ShanShuiColor): Boolean =
        c.r >= 235 && c.g >= 235 && c.b >= 235

    /**
     * 叶簇 / 水花的白 ⇒ **绢白**（⛔ 不留纯白，方案 §3.6.5）。
     *
     * α 沿用轮廓自带的（上游的白块本来就半透明）⇒ 叶簇仍是「若隐若现」的点。
     */
    private fun whiteFillColor(own: ShanShuiColor): ShanShuiColor =
        ShanShuiColor(
            SILK_WHITE_R, SILK_WHITE_G, SILK_WHITE_B,
            (own.a.coerceIn(0.0, 1.0) * WHITE_FILL_A_K).coerceIn(0.0, 1.0),
        )

    /** 高光丝的 α 阈值：绝对下限与「最亮者的 80%」取大者；一个都不够亮时退化为取最亮。 */
    private fun waterHiThreshold(contours: List<ShanShuiContour>): Double {
        var maxA = 0.0
        var any = false
        for (c in contours) {
            val s = c.stroke ?: continue
            any = true
            if (s.a > maxA) maxA = s.a
        }
        if (!any) return Double.MAX_VALUE
        return max(WATER_HI_MIN_ALPHA, maxA * WATER_HI_RATIO)
    }

    // ─────────────────────── 染色 ───────────────────────

    /**
     * 填充色。
     *
     * - V-A（对照组）：⛔ 原样用轮廓自带的颜色（上游灰阶线稿）。
     * - V-B / V-C：用 [QianliColorTable] 的坡面行号染色（**按行号，⛔ 不按 y**）。
     */
    private fun fillColor(rowJ: Int, own: ShanShuiColor): ShanShuiColor =
        if (COLOR_VERSION == QianliColorTable.Version.V_A) own
        else QianliColorTable.colorFor(COLOR_VERSION, rowJ, TOTAL_ROWS)

    /**
     * 描边色（皴线 / 轮廓）。
     *
     * V-B / V-C 下描边取**墨**（色表最后一站）—— 方案 §3.3：「皴线、矶石、远树、暗角」归墨族，
     * ⛔ 不让皴线跟着坡面色跑（那会把「色重皴轻」反过来）。
     * α 沿用轮廓自带的（上游的干湿浓淡），⛔ 不用色表的 α 覆盖。
     */
    private fun strokeColor(rowJ: Int, own: ShanShuiColor): ShanShuiColor {
        if (COLOR_VERSION == QianliColorTable.Version.V_A) return own
        val ink = QianliColorTable.colorFor(COLOR_VERSION, TOTAL_ROWS - 1, TOTAL_ROWS)
        return ShanShuiColor(ink.r, ink.g, ink.b, own.a.coerceIn(0.0, 1.0))
    }

    /**
     * 坡面行号（染色用）。
     *
     * ⭐ **按轮廓在 chunk 高度里的纵向位置**（0 = 山脊 → TOTAL_ROWS = 山脚），
     * ⛔ 不按画面 y（那是 v1.x 的错法，色表会被拉成一条直线）。
     * 水波层没有「山脊/山脚」概念 ⇒ 用**轮廓序号**当行号（近的在上、远的在下）。
     */
    private fun rowJOf(
        spec: ChunkSpec,
        c: ShanShuiContour,
        index: Int,
        count: Int,
        kind: Kind,
    ): Int {
        if (kind == Kind.WATER || kind == Kind.WATER_HI) {
            return ((index.toLong() * TOTAL_ROWS) / max(1, count)).toInt()
                .coerceIn(0, TOTAL_ROWS - 1)
        }
        var minY = Double.MAX_VALUE
        for (p in c.pts) {
            val y = p[1].toDouble()
            if (y < minY) minY = y
        }
        if (minY == Double.MAX_VALUE) return 0
        val h = if (spec.height <= 0.0) 1.0 else spec.height
        val t = ((spec.baseY - minY) / h).coerceIn(0.0, 1.0)
        return (t * TOTAL_ROWS).toInt().coerceIn(0, TOTAL_ROWS - 1)
    }

    private fun argbOf(c: ShanShuiColor): Int =
        Color.argb((c.a.coerceIn(0.0, 1.0) * 255.0).toInt().coerceIn(0, 255), c.r, c.g, c.b)

    // ─────────────────────── 烘焙：天幕 / grade / 光尘 ───────────────────────

    /**
     * L0 绢底天幕：竖向渐变 + 绢纹 + 底部的旧色。
     *
     * ⚠️ 渐变的 stop 位置是**非均匀**的（天顶冷、地平线暖绢、江面偏橄榄）⇒
     * ⛔ 不用 Compose 的 `Brush.verticalGradient`（它只吃均匀分布的 stop 列表，
     * 会把色带位置整体挪掉 —— E48 已踩过）。
     */
    private fun bakeSky(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        ptSky.style = Paint.Style.FILL
        ptSky.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(SKY_TOP, SKY_MID, SKY_HORIZON, SKY_BOTTOM),
            floatArrayOf(0f, 0.34f, HORIZON_Y, 1f),
            Shader.TileMode.CLAMP,
        )
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), ptSky)
        ptSky.shader = null

        // 绢纹：极淡的横竖细线（织物的经纬）。
        val hs = buildSilkLines(w, h)
        if (hs.isNotEmpty()) {
            ptSky.style = Paint.Style.STROKE
            ptSky.strokeWidth = 1f
            ptSky.color = Color.argb(SILK_ALPHA, SILK_R, SILK_G, SILK_B)
            cv.drawLines(hs, ptSky)
            ptSky.style = Paint.Style.FILL
        }
        return bmp
    }

    /** 绢纹线表（横线 + 竖线，一个 FloatArray 一次画完）。 */
    private fun buildSilkLines(w: Int, h: Int): FloatArray {
        val stepH = max(3, (h / 220))
        val stepV = max(4, (w / 260))
        // ⚠️ 行数必须按**上取整**算（`0, step, 2·step, … < w` 的个数 = ⌈w/step⌉），
        //    用整除会少算一行 ⇒ 往下的 while 循环会越界写数组。
        val rows = (h + stepH - 1) / stepH
        val cols = (w + stepV - 1) / stepV
        val arr = FloatArray((rows + cols + 2) * 4)
        var k = 0
        var y = 0f
        while (y < h.toFloat()) {
            arr[k++] = 0f; arr[k++] = y; arr[k++] = w.toFloat(); arr[k++] = y
            y += stepH
        }
        var x = 0f
        while (x < w.toFloat()) {
            arr[k++] = x; arr[k++] = 0f; arr[k++] = x; arr[k++] = h.toFloat()
            x += stepV
        }
        return if (k == arr.size) arr else arr.copyOf(k)
    }

    /**
     * L6 grade：暗角 + 颗粒，**烘在同一张位图** ⇒ 逐帧只 1 次 blit。
     *
     * ⚠️ 颗粒走**平铺 tile**（256×256）而不是逐像素全屏噪声 ⇒ 烘焙代价从 O(2 Mpx)
     * 降到 O(65 Kpx)，而暗角只是一次全屏 `drawRect`。
     */
    private fun bakeGrade(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        // ① 暗角（墨深锚，四角压暗）
        ptGrade.style = Paint.Style.FILL
        ptGrade.shader = RadialGradient(
            w / 2f, h / 2f, max(w, h) * 0.72f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                Color.argb((GRADE_VIGNETTE_A * 255f).toInt(), GRADE_INK_R, GRADE_INK_G, GRADE_INK_B),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), ptGrade)
        ptGrade.shader = null
        // ② 颗粒（平铺）
        val tile = bakeGrainTile()
        ptGrade.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), ptGrade)
        ptGrade.shader = null
        tile.recycle()
        return bmp
    }

    /** 颗粒 tile：256×256 的半透明噪点（暗面 + 亮面各半）。 */
    private fun bakeGrainTile(): Bitmap {
        val n = GRAIN_TILE_PX
        val px = IntArray(n * n)
        for (i in px.indices) {
            val a = (rng.next() * GRAIN_MAX_ALPHA).toInt().coerceIn(0, 255)
            val light = rng.next() < 0.5f
            px[i] = if (light) {
                Color.argb(a, GRAIN_LIGHT_R, GRAIN_LIGHT_G, GRAIN_LIGHT_B)
            } else {
                Color.argb(a, GRAIN_DARK_R, GRAIN_DARK_G, GRAIN_DARK_B)
            }
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    /** L5 光尘 tile：细碎光点（周期对细碎光点无害 ⇒ 可平铺）。 */
    private fun bakeDustTile(): Bitmap {
        val n = DUST_TILE_PX
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        for (i in 0 until DUST_DOTS) {
            val x = rng.next() * n
            val y = rng.next() * n
            val r = 0.5f + rng.next() * 1.3f
            val a = ((0.22f + rng.next() * 0.78f) * 255f).toInt().coerceIn(0, 255)
            p.color = Color.argb(a, DUST_R, DUST_G, DUST_B)
            cv.drawCircle(x, y, r, p)
        }
        return bmp
    }

    // ─────────────────────── 缓存的着色器 ───────────────────────

    private var agingCache: LinearGradient? = null
    private var agingCacheW = -1f
    private var agingCacheH = -1f

    private fun agingShader(w: Float, h: Float): Shader? {
        val cached = agingCache
        if (cached != null && agingCacheW == w && agingCacheH == h) return cached
        val sh = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.argb(0, AGING_R, AGING_G, AGING_B),
                Color.argb(90, AGING_R, AGING_G, AGING_B),
                Color.argb(150, AGING_R, AGING_G, AGING_B),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        agingCache = sh
        agingCacheW = w
        agingCacheH = h
        return sh
    }

    private var nearFogCache: LinearGradient? = null
    private var nearFogTop = -1f
    private var nearFogBottom = -1f

    private fun nearFogShader(top: Float, bottom: Float): Shader? {
        val cached = nearFogCache
        if (cached != null && nearFogTop == top && nearFogBottom == bottom) return cached
        val sh = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                Color.argb(0, NEAR_FOG_R, NEAR_FOG_G, NEAR_FOG_B),
                Color.argb(200, NEAR_FOG_R, NEAR_FOG_G, NEAR_FOG_B),
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        nearFogCache = sh
        nearFogTop = top
        nearFogBottom = bottom
        return sh
    }

    // ─────────────────────── 小工具 ───────────────────────

    private fun bandTop(yFrac: Float, h: Int): Float = yFrac * h

    private fun bandHeight(y0: Float, y1: Float, h: Int): Float =
        max(1f, (y1 - y0) * h)

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t.coerceIn(0.0, 1.0)

    private fun wrap(v: Double, period: Double): Double {
        if (period <= 0.0) return v
        var r = v % period
        if (r < 0.0) r += period
        return r
    }

    // ─────────────────────── 内部类型 ───────────────────────

    /** 一条 chunk 的绘制口径。 */
    private enum class Kind {
        /** 山体（填充 + 皴线）。 */
        MOUNTAIN,

        /** 轮廓光（只画山脊描边 + 晕）。 */
        GLINT,

        /** 江水底纹。 */
        WATER,

        /** 江水高光丝。 */
        WATER_HI,
    }

    /**
     * 一层的「世界 y → 层带局部 y」映射。
     *
     * - 山体类：`bandY = 基线 + (chunkBaseY − worldY) × heightScale`
     *   （每个 chunk 站在自己的基线上 ⇒ 山脚高低错落 ⇒ 「层峦叠嶂」而不是一条尺子线）。
     * - 水波类：`bandY = (worldY − chunkBaseY + waterOffset) / waterSpan × 带高`
     *   （chunk 局部 y 铺满整条江水带 ⇒ 各 chunk 的水纹在同一段高度上叠成连续水面）。
     */
    private class BandMap(
        val bandHeightPx: Float,
        val refBaseY: Double,
        val baseSpread: Double,
        val heightScale: Double,
        val waterSpan: Double = 0.0,
        val waterOffset: Double = 0.0,
    ) {
        fun bandY(chunkBaseY: Double, worldY: Double): Float {
            if (waterSpan > 0.0) {
                val t = (worldY - chunkBaseY + waterOffset) / waterSpan
                return (t * bandHeightPx).toFloat()
            }
            val base = bandHeightPx - (chunkBaseY - refBaseY) * baseSpread
            return (base + (chunkBaseY - worldY) * heightScale).toFloat()
        }
    }

    /** 一条已规划的 chunk（几何**惰性生成一次**后缓存）。 */
    private class ChunkSpec(
        val tag: String,
        val x: Double,
        val y: Double,
        val h: Double,
        val idx: Int,
    ) {
        /** 内容在世界 x 上的范围。 */
        var minX = 0.0
        var maxX = 0.0

        /** 该 chunk 的基线世界 y（山脚 / 水面基准）。 */
        var baseY = 0.0

        /** 该 chunk 的高度（世界单位）—— 染色行号的分母。 */
        var height = 1.0

        /** 几何。 */
        var contours: List<ShanShuiContour> = emptyList()
    }

    /**
     * 一层自己的「世界」—— 规划游标 + 已规划 chunk 列表。
     *
     * ⛔ `plan()` **只向右推进**（连续扫描）⇒ 块间连续、构图天然接得上（方案 §4.2′）。
     * ⛔ 每个世界**独立**（⛔ 不共用 chunk 列表）—— 层间速度不同 ⇒ 世界坐标不同；
     *    唯一的例外是 L2 与 L2′（轮廓光必须与山同种子同相位）。
     */
    private class ScrollWorld(
        val wantTag: String,
        private val planAhead: Double,
        private val prepare: (ChunkSpec) -> Unit,
    ) {
        val specs = ArrayList<ChunkSpec>()
        private var cursor = 0.0

        /** 推进规划游标到覆盖 [x0 + planAhead]。⛔ 只向右。 */
        fun ensure(x0: Double) {
            val target = x0 + planAhead
            while (cursor < target) {
                val batch = ShanShuiPlanner.plan(cursor, cursor + PLAN_STEP)
                for (i in batch.indices) {
                    val c = batch[i]
                    val spec = ChunkSpec(c.tag, c.x, c.y, c.h, i)
                    if (c.tag == wantTag) prepare(spec)
                    specs.add(spec)
                }
                cursor += PLAN_STEP
            }
            // 修剪：已经滚出选择范围的 chunk 不再保留（⛔ 否则长时间运行会无界增长）。
            val keepFrom = x0 - KEEP_BACK_PX
            var w = 0
            while (w < specs.size) {
                if (specs[w].maxX < keepFrom) specs.removeAt(w) else w++
            }
        }
    }

    // ─────────────────────── 常量 ───────────────────────

    private companion object {

        // ── 构图锚点（归一化屏高，⛔ 不写死 px）──
        const val HORIZON_Y = 0.620f
        const val FAR_Y0 = 0.42f
        const val FAR_Y1 = 0.58f
        const val MID_Y0 = 0.20f
        const val MID_Y1 = 0.55f
        const val NEAR_Y0 = 0.66f
        const val NEAR_Y1 = 0.88f
        const val WATER_Y0 = 0.62f
        const val WATER_Y1 = 1.00f

        // ── 分层速度（px/s，方案 §4.3）──
        const val SPEED_FAR = 4.0
        const val SPEED_MID = 10.0
        const val SPEED_NEAR = 22.0
        const val SPEED_WATER = 14.0
        const val SPEED_DUST = 3.0

        /** 能量对速度的推动（上限恰 1.30×，方案 §4.4）。 */
        const val ENERGY_SPEED_GAIN = 0.30

        // ── α 通道上下限（方案 §5.1 / §5.5）──
        const val FAR_ALPHA_MIN = 0.10
        const val FAR_ALPHA_MAX = 0.30
        const val GLINT_ALPHA_MIN = 0.10
        const val GLINT_ALPHA_MAX = 0.34
        const val WATER_ALPHA_MIN = 0.16
        const val WATER_ALPHA_MAX = 0.34
        const val NEAR_FOG_MIN = 0.06
        const val NEAR_FOG_MAX = 0.16
        const val DUST_ALPHA_MIN = 0.05
        const val DUST_ALPHA_MAX = 0.22
        const val AGING_ALPHA_MIN = 0.03
        const val AGING_ALPHA_MAX = 0.12

        /** 江水高光相对水纹的 α 系数。 */
        const val WATER_HI_ALPHA_K = 0.85

        // ── 呼吸 ──
        const val BREATH_MIN = 1.000f
        const val BREATH_MAX = 1.030f

        // ── 印章 ──
        const val SEAL_SIZE_K = 0.09f

        // ── 分块与规划 ──
        const val BLOCK_COUNT = 2
        const val PLAN_STEP = 512.0

        /** 规划前瞻（世界单位）：必须覆盖该层内容向右伸出的最大距离。 */
        const val PLAN_AHEAD_FAR = 2100.0
        const val PLAN_AHEAD_MID = 1100.0
        const val PLAN_AHEAD_NEAR = 1200.0
        const val PLAN_AHEAD_WATER = 1100.0

        /** 修剪时保留的历史长度（世界单位）。 */
        const val KEEP_BACK_PX = 2400.0

        // ── 上游 chunk tag ──
        const val TAG_MOUNT = "mount"
        const val TAG_FLATMOUNT = "flatmount"
        const val TAG_DISTMOUNT = "distmount"

        // ── 上游图元参数（照抄 chunkloader）──
        const val MOUNTAIN_TEX = 200.0
        const val DISTMOUNT_HEI = 150.0
        const val DISTMOUNT_SEG = 5.0
        const val FLATMOUNT_HEI = 100.0
        const val FLATMOUNT_TEX = 80.0
        const val ROCK_TEX = 40.0
        const val ROCK_SHA = 2.0
        const val WATER_HEI = 2.0
        const val WATER_LEN = 800.0
        const val WATER_CLU = 10.0

        /** 水纹的横向半幅（世界单位）—— 选 chunk 时用它算内容范围。 */
        const val WATER_HALF_SPAN = 450.0

        /** 水纹的局部 y 跨度（世界单位）与零点偏移。 */
        const val WATER_LOCAL_SPAN = 80.0
        const val WATER_LOCAL_OFFSET = 20.0

        // ── 层带映射常量（⛔ 便于后续调）──
        const val FAR_REF_BASE_Y = 255.0
        const val FAR_BASE_SPREAD = 0.20
        const val FAR_HEIGHT_SCALE = 0.47
        const val MID_REF_BASE_Y = 540.0
        const val MID_BASE_SPREAD = 0.12
        const val MID_HEIGHT_SCALE = 0.643
        const val NEAR_REF_BASE_Y = 650.0
        const val NEAR_BASE_SPREAD = 0.15
        const val NEAR_HEIGHT_SCALE = 1.31

        // ── 染色 ──
        const val TOTAL_ROWS = 10

        /** 染色版本（V-A = 上游原样灰阶对照组 / V-B 六族 / V-C 大青绿）。 */
        val COLOR_VERSION: QianliColorTable.Version = QianliColorTable.Version.V_C

        // ── 轮廓光（方案 §3.4-4：只有一种色 = 赭石亮锚 `#A97D40`）──
        const val GLINT_R = 0xA9
        const val GLINT_G = 0x7D
        const val GLINT_B = 0x40
        const val GLINT_GLOW_W = 2.6f
        const val GLINT_GLOW_A = 0.62f
        const val GLINT_BLUR_PX = 3.5f

        // ── 江水高光（方案 §3.3：水面亮锚 `#828156`）──
        const val WATER_HI_R = 0x82
        const val WATER_HI_G = 0x81
        const val WATER_HI_B = 0x56
        const val WATER_HI_A = 0.9
        const val WATER_HI_MIN_ALPHA = 0.45
        const val WATER_HI_RATIO = 0.80

        // ── 天幕（方案 §3.3 命名色表）──
        const val SKY_TOP = 0x4C5A5E
        const val SKY_MID = 0x7A7358
        const val SKY_HORIZON = 0x91764B
        const val SKY_BOTTOM = 0x5D603E
        const val SILK_R = 0xE8
        const val SILK_G = 0xDC
        const val SILK_B = 0xC3
        const val SILK_ALPHA = 12

        /** 绢白（叶簇 / 水花的白 ⇒ 换它，⛔ 不留纯白；方案 §3.6.5）。 */
        const val SILK_WHITE_R = 0xE8
        const val SILK_WHITE_G = 0xDC
        const val SILK_WHITE_B = 0xC3

        /** 绢白块的 α 系数（上游的白块本就半透明 ⇒ 保留「若隐若现」）。 */
        const val WHITE_FILL_A_K = 0.85

        // ── 做旧罩纱（暖褐）──
        const val AGING_R = 0x79
        const val AGING_G = 0x57
        const val AGING_B = 0x2B

        // ── 近景雾 ──
        const val NEAR_FOG_R = 0xB8
        const val NEAR_FOG_G = 0xC2
        const val NEAR_FOG_B = 0xB4

        // ── grade（暗角墨深锚 + 颗粒）──
        const val GRADE_INK_R = 0x2C
        const val GRADE_INK_G = 0x2B
        const val GRADE_INK_B = 0x17
        const val GRADE_VIGNETTE_A = 0.10f
        const val GRAIN_TILE_PX = 256
        const val GRAIN_MAX_ALPHA = 14f
        const val GRAIN_LIGHT_R = 0xEB
        const val GRAIN_LIGHT_G = 0xE4
        const val GRAIN_LIGHT_B = 0xD2
        const val GRAIN_DARK_R = 0x28
        const val GRAIN_DARK_G = 0x26
        const val GRAIN_DARK_B = 0x1C

        // ── 光尘 ──
        const val DUST_TILE_PX = 256
        const val DUST_DOTS = 110
        const val DUST_R = 0xE2
        const val DUST_G = 0xD6
        const val DUST_B = 0xB2

        // ── 层号 ──
        const val LAYER_FAR = 1
        const val LAYER_MID = 2
        const val LAYER_GLINT = 3
        const val LAYER_NEAR = 4
        const val LAYER_WATER = 5
        const val LAYER_WATER_HI = 6
    }
}
