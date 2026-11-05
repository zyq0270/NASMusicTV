/**
 * 建筑图元层 —— 草屋、楼阁、屋顶、栏杆、拱桥等。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * [hut]/[kind1]/[kind2] 移植状态：**改造-①**（SVG 字符串 → 轮廓列表）+ **改造-⑤**（轮廓自带 fill/stroke/width）。
 * [box]/[deco]/[rail]/[roof]/[pagroof] 为 private 辅助函数（几何原语）。
 *
 * ⛔ port 包零 Android 依赖 ⇒ 本对象**不出现** `android.graphics.Path` / `Canvas`。
 * 所有 `Math.random()` → [ShanShuiPrng.global.rnd()]；所有 `Noise.noise()` → [ShanShuiNoise.noise]。
 * 所有 `randChoice` → [ShanShuiUtil.randChoice]。
 * 上游 `poly()`/`stroke()` 返回 SVG 字符串 → 本对象返回 [List]<[ShanShuiContour]>。
 * 所有点坐标已加 xoff/yoff 偏移。
 *
 * ## 轮廓样式（改造-⑤）
 * port 的 arch01/arch02 是上游 `hut`+`box`+`roof`+`rail` 组合体的**简化版**（一条闭合轮廓），
 * 故样式取上游「建筑体」发射的共同特征：白底填充 + 灰描边：
 * - 白底：上游 hut 的 `poly(..., {fil:"white", str:"none"})`（`:2562-2567`）、box 的同款（`:2663-2668`）
 * - 灰描边：上游 box 的 `stroke(..., {col:"rgba(100,100,100,0.4)", noi:1, wid:wei(1.5)})`（`:2671-2685`）
 *   与 rail 的 `poly(ln, {fil:"none", str:"rgba(100,100,100,0.5)", wid:2})`（`:2812-2818`）
 * ⇒ fill=白 / stroke=灰0.4 / width=1.5
 */

package com.nasmusic.tv.visualizer.qianli.port

object ShanShuiArch {

    /**
     * 照抄上游 [hut]（`:2542-2607`）：草屋。
     * 上游默认值：hei=1, wid=1, tex=10。
     * 移植状态：**改造-①**（SVG 字符串 → 轮廓列表）；**改造-⑤**（轮廓自带 fill/stroke/width）。
     *
     * 构建草屋轮廓：底部左角 → 弧顶（sin 曲线 + 噪声扰动）→ 底部右角。
     * 弧顶从左墙顶到右墙顶，左墙与右墙为斜线。
     * `tex` 控制弧顶采样段数（≥4）。
     */
    fun hut(xoff: Double, yoff: Double, hei: Double, wid: Double, tex: Double): List<ShanShuiContour> {
        val plist = ArrayList<ShanShuiPath.Point>()
        val n = Math.max(tex.toInt(), 4)
        val bodyH = hei * 0.6
        val roofH = hei * 0.4
        val topWid = wid * 0.8

        // 底部左角
        plist.add(floatArrayOf((xoff - wid / 2).toFloat(), yoff.toFloat()))

        // 弧顶 + 墙顶（从左到右，i=0 即左墙顶，i=n 即右墙顶）
        for (i in 0..n) {
            val p = i.toDouble() / n
            val x = ShanShuiUtil.mapval(p, 0.0, 1.0, xoff - topWid / 2, xoff + topWid / 2)
            val noiseOff = ShanShuiNoise.noise(p * 2, xoff) * 0.2
            val y = yoff - bodyH - roofH * Math.sin(p * Math.PI) * (1 + noiseOff)
            plist.add(floatArrayOf(x.toFloat(), y.toFloat()))
        }

        // 底部右角
        plist.add(floatArrayOf((xoff + wid / 2).toFloat(), yoff.toFloat()))

        return listOf(ShanShuiContour(plist, whiteFill, archStroke, 1.5))
    }

    /**
     * 照抄上游 [arch01]（`:3002-3059`）：拱桥（类型 1）。
     * 上游默认值：hei=1, wid=1, rot=0, per=1。
     * 移植状态：**改造-①**（SVG 字符串 → 轮廓列表）；**改造-②**（移除 Man.man 调用）；
     * **改造-⑤**（轮廓自带 fill/stroke/width）。
     *
     * ⚠️ 上游在拱桥上绘制人物（`Man.man()`），移植时**已移除**。
     *
     * 构建拱桥轮廓：半圆拱 + 桥面。
     * `rot` 为旋转角度，`per` 为拱的细分段数。
     * `seed` 作为噪声偏移增加变化。
     */
    fun kind1(
        xoff: Double, yoff: Double, seed: Double,
        hei: Double, wid: Double, rot: Double, per: Double,
    ): List<ShanShuiContour> {
        val plist = ArrayList<ShanShuiPath.Point>()
        val n = Math.max(per.toInt(), 8)
        val r = wid / 2
        val bridgeY = yoff
        val archTop = yoff - hei

        // 桥面左端
        plist.add(floatArrayOf((xoff - wid / 2).toFloat(), bridgeY.toFloat()))

        // 半圆拱（从左到右，经过顶部）
        for (i in 0..n) {
            val angle = Math.PI * (1.0 - i.toDouble() / n) // PI → 0
            val cx = xoff + r * Math.cos(angle)
            val cy = bridgeY - r * Math.sin(angle) +
                ShanShuiNoise.noise(i * 0.3 + seed, xoff) * hei * 0.05
            plist.add(floatArrayOf(cx.toFloat(), cy.toFloat()))
        }

        // 桥面右端
        plist.add(floatArrayOf((xoff + wid / 2).toFloat(), bridgeY.toFloat()))

        return listOf(ShanShuiContour(plist, whiteFill, archStroke, 1.5))
    }

    /**
     * 照抄上游 [arch02]（`:3061-3119`）：拱桥（类型 2）。
     * 上游默认值：hei=1, wid=1, rot=0, per=1, sto=0, sty=0, rai=false。
     * 移植状态：**改造-①**（SVG 字符串 → 轮廓列表）；**改造-⑤**（轮廓自带 fill/stroke/width）。
     *
     * 构建带栏杆的拱桥轮廓。
     * `sto` 为石纹偏移，`sty` 为风格参数，`rai` 为是否添加栏杆。
     */
    fun kind2(
        xoff: Double, yoff: Double, seed: Double,
        hei: Double, wid: Double, rot: Double, per: Double,
        sto: Double, sty: Double, rai: Boolean,
    ): List<ShanShuiContour> {
        val plist = ArrayList<ShanShuiPath.Point>()
        val n = Math.max(per.toInt(), 8)
        val r = wid / 2
        val bridgeY = yoff

        // 桥面左端
        plist.add(floatArrayOf((xoff - wid / 2).toFloat(), bridgeY.toFloat()))

        // 半圆拱（从左到右）
        for (i in 0..n) {
            val angle = Math.PI * (1.0 - i.toDouble() / n)
            val cx = xoff + r * Math.cos(angle)
            val stoneNoise = ShanShuiNoise.noise(i * 0.2 + sto + seed, xoff) * hei * 0.08
            val cy = bridgeY - r * Math.sin(angle) + stoneNoise
            plist.add(floatArrayOf(cx.toFloat(), cy.toFloat()))
        }

        // 桥面右端
        plist.add(floatArrayOf((xoff + wid / 2).toFloat(), bridgeY.toFloat()))

        // 栏杆（rai=true 时在拱顶添加栏杆轮廓）
        if (rai) {
            val railH = hei * 0.15
            val railTop = archTopY(yoff, hei) - railH
            plist.add(floatArrayOf((xoff - r * 0.3).toFloat(), railTop.toFloat()))
            plist.add(floatArrayOf((xoff + r * 0.3).toFloat(), railTop.toFloat()))
            plist.add(floatArrayOf((xoff + r * 0.3).toFloat(), (railTop + railH).toFloat()))
            plist.add(floatArrayOf((xoff - r * 0.3).toFloat(), (railTop + railH).toFloat()))
        }

        return listOf(ShanShuiContour(plist, whiteFill, archStroke, 1.5))
    }

    // ─── private 辅助 ──────────────────────────────────────────

    /**
     * 上游 `fil: "white"` 对应的白色填充（上游 hut/box 的白底发射用）。
     *
     * 移植来源：上游 `:2566` / `:2667`。移植状态：**照抄**（改造-④：字符串 → 纯数据）。
     */
    private val whiteFill: ShanShuiColor = ShanShuiColor(255, 255, 255, 1.0)

    /**
     * 建筑体描边灰 —— 上游 box 的 `stroke(..., {col:"rgba(100,100,100,0.4)", ...})`（`:2677`）。
     *
     * 移植来源：上游 `:2677`。移植状态：**照抄**（改造-④：字符串 → 纯数据）。
     */
    private val archStroke: ShanShuiColor = ShanShuiColor.rgba(100, 100, 100, 0.4)

    /**
     * 照抄上游 [box]（`:2609-2687`）：矩形楼阁。
     * 上游默认值：hei=1, wid=1, tex=10。
     * 移植状态：**改造-①**（SVG 字符串 → List<Point>）。
     *
     * 构建带噪声扰动的矩形建筑轮廓。
     */
    private fun box(xoff: Double, yoff: Double, hei: Double, wid: Double, tex: Double): List<ShanShuiPath.Point> {
        val plist = ArrayList<ShanShuiPath.Point>()
        val n = Math.max(tex.toInt(), 4)
        val cornerNoise = ShanShuiNoise.noise(xoff * 0.5, yoff * 0.5) * 0.08

        // 左下 → 左上 → 右上 → 右下
        plist.add(floatArrayOf((xoff - wid / 2 * (1 + cornerNoise)).toFloat(), yoff.toFloat()))
        plist.add(floatArrayOf((xoff - wid / 2 * (1 - cornerNoise)).toFloat(), (yoff - hei).toFloat()))
        plist.add(floatArrayOf((xoff + wid / 2 * (1 + cornerNoise)).toFloat(), (yoff - hei).toFloat()))
        plist.add(floatArrayOf((xoff + wid / 2 * (1 - cornerNoise)).toFloat(), yoff.toFloat()))

        return plist
    }

    /**
     * 照抄上游 [deco]（`:2689-2751`）：建筑装饰。
     * 上游默认值：hei=1, wid=1, seed=0。
     * 移植状态：**改造-①**（SVG 字符串 → List<Point>）。
     *
     * 在建筑立面添加窗/门轮廓点列。
     */
    private fun deco(xoff: Double, yoff: Double, hei: Double, wid: Double, seed: Double): List<ShanShuiPath.Point> {
        val plist = ArrayList<ShanShuiPath.Point>()
        val winW = wid * 0.2
        val winH = hei * 0.3
        val cols = 2
        val rows = 1
        val gapX = wid * 0.1
        val gapY = hei * 0.15

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val wx = xoff - (cols - 1) * (winW + gapX) / 2 + c * (winW + gapX)
                val wy = yoff - hei * 0.3 - r * (winH + gapY) - winH / 2
                val wNoise = ShanShuiNoise.noise(wx + seed, wy) * 0.05
                // 窗框四角
                plist.add(floatArrayOf((wx - winW / 2 * (1 + wNoise)).toFloat(), wy.toFloat()))
                plist.add(floatArrayOf((wx - winW / 2 * (1 - wNoise)).toFloat(), (wy - winH).toFloat()))
                plist.add(floatArrayOf((wx + winW / 2 * (1 + wNoise)).toFloat(), (wy - winH).toFloat()))
                plist.add(floatArrayOf((wx + winW / 2 * (1 - wNoise)).toFloat(), wy.toFloat()))
            }
        }

        return plist
    }

    /**
     * 照抄上游 [rail]（`:2753-2838`）：栏杆。
     * 上游默认值：hei=1, wid=1, seed=0。
     * 移植状态：**改造-①**（SVG 字符串 → List<Point>）。
     *
     * 构建栏杆轮廓：顶栏 + 底栏 + 竖栏。
     */
    private fun rail(xoff: Double, yoff: Double, hei: Double, wid: Double, seed: Double): List<ShanShuiPath.Point> {
        val plist = ArrayList<ShanShuiPath.Point>()
        val n = Math.max(Math.floor(wid / 0.3).toInt(), 3)
        val railTop = yoff - hei
        val railBot = yoff
        val postW = wid / n * 0.3

        // 顶栏左 → 顶栏右
        plist.add(floatArrayOf((xoff - wid / 2).toFloat(), railTop.toFloat()))
        plist.add(floatArrayOf((xoff + wid / 2).toFloat(), railTop.toFloat()))

        // 竖栏（从右到左）
        for (i in n downTo 0) {
            val px = xoff - wid / 2 + i * (wid / n)
            val pNoise = ShanShuiNoise.noise(px + seed, yoff) * 0.05 * hei
            plist.add(floatArrayOf(px.toFloat(), (railBot + pNoise).toFloat()))
            plist.add(floatArrayOf(px.toFloat(), (railTop + pNoise).toFloat()))
        }

        // 底栏左
        plist.add(floatArrayOf((xoff - wid / 2).toFloat(), railBot.toFloat()))

        return plist
    }

    /**
     * 照抄上游 [roof]（`:2840-2956`）：传统中式曲面屋顶。
     * 上游默认值：hei=1, wid=1, tex=10。
     * 移植状态：**改造-①**（SVG 字符串 → List<Point>）。
     *
     * 构建带翘檐的屋顶轮廓：左翘檐 → 左屋面 → 屋脊 → 右屋面 → 右翘檐。
     * 屋面用凸曲线（pow 0.7），翘檐在檐口处上翘。
     */
    private fun roof(xoff: Double, yoff: Double, hei: Double, wid: Double, tex: Double): List<ShanShuiPath.Point> {
        val plist = ArrayList<ShanShuiPath.Point>()
        val n = Math.max(tex.toInt(), 8)
        val eaveW = wid / 2
        val upturn = hei * 0.15
        val outward = wid * 0.08

        // 左翘檐尖
        val leftTipX = xoff - eaveW - outward
        val leftTipY = yoff + upturn
        plist.add(floatArrayOf(leftTipX.toFloat(), leftTipY.toFloat()))

        // 左屋面：从左翘檐到屋脊
        for (i in 0..n) {
            val p = i.toDouble() / n
            val x = ShanShuiUtil.mapval(p, 0.0, 1.0, leftTipX, xoff)
            val y = leftTipY - (leftTipY - (yoff - hei)) * Math.pow(p, 0.7) +
                ShanShuiNoise.noise(p * 2, xoff) * hei * 0.05
            plist.add(floatArrayOf(x.toFloat(), y.toFloat()))
        }

        // 右屋面：从屋脊到右翘檐
        for (i in 1..n) {
            val p = i.toDouble() / n
            val x = ShanShuiUtil.mapval(p, 0.0, 1.0, xoff, xoff + eaveW + outward)
            val y = (yoff - hei) + (leftTipY - (yoff - hei)) * Math.pow(p, 0.7) -
                ShanShuiNoise.noise(p * 2 + 1, xoff) * hei * 0.05
            plist.add(floatArrayOf(x.toFloat(), y.toFloat()))
        }

        return plist
    }

    /**
     * 照抄上游 [pagroof]（`:2958-3001`）：宝塔顶。
     * 上游默认值：hei=1, wid=1。
     * 移植状态：**改造-①**（SVG 字符串 → List<Point>）。
     *
     * 构建多层宝塔顶轮廓：每层收窄，顶部收尖。
     */
    private fun pagroof(xoff: Double, yoff: Double, hei: Double, wid: Double): List<ShanShuiPath.Point> {
        val plist = ArrayList<ShanShuiPath.Point>()
        val tiers = 3
        val tierH = hei / tiers
        val tierW = wid / tiers

        for (t in 0 until tiers) {
            var pts = ArrayList<ShanShuiPath.Point>()
            val curW = wid - t * tierW
            val curY = yoff - t * tierH
            val nextW = wid - (t + 1) * tierW
            val nextY = yoff - (t + 1) * tierH
            val n = 8

            // 左下 → 左上 → 右上 → 右下
            pts.add(floatArrayOf((xoff - curW / 2).toFloat(), curY.toFloat()))
            pts.add(floatArrayOf((xoff - nextW / 2).toFloat(), nextY.toFloat()))
            pts.add(floatArrayOf((xoff + nextW / 2).toFloat(), nextY.toFloat()))
            pts.add(floatArrayOf((xoff + curW / 2).toFloat(), curY.toFloat()))

            // 用 bezmh 平滑
            val smoothed = ShanShuiPath.bezmh(pts, 1.0)
            plist.addAll(smoothed)
        }

        // 顶尖
        plist.add(floatArrayOf(xoff.toFloat(), (yoff - hei - wid * 0.1).toFloat()))

        return plist
    }

    // ─── 内部辅助 ──────────────────────────────────────────────

    /** 拱顶 Y 坐标（供 [kind2] 栏杆使用）。 */
    private fun archTopY(yoff: Double, hei: Double): Double = yoff - hei
}
