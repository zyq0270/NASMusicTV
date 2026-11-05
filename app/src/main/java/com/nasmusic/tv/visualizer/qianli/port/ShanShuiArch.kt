/**
 * 建筑图元层 —— 草屋、楼阁、屋顶、栏杆、拱桥等。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 *
 * ⛔ **本文件的移植状态统一是「改造-③：简化实现，与上游无视觉等价关系」** —— ⚠️ 早期版本
 * 把这些函数标成「照抄上游」，那是**记录不符**（连函数签名都改了，几何更是另写一套）：
 * 上游 arch01/02/03/04 是 `hut` + `box` + `roof` + `rail` + `pagroof`（+ `Man.man`）的**组合体**，
 * 每个组合体由若干条独立的 poly/stroke 发射拼成；port 的 kind1/kind2 只产出**一条**闭合轮廓。
 *
 * ## 与上游的具体差异（逐项）
 * | 项 | 上游 | port |
 * |---|---|---|
 * | 发射条数 | arch01 = hut(1 白底 + 2 描边) + box(1 描边×N) + rail×2 + Man.man（多条） | **1 条**闭合轮廓 |
 * | 几何 | `box` 的 `div(...)` 等分折线 + `roof` 的翘檐曲线 + `rail` 的竖栏 | **半圆拱 + 桥面**（kind1/2） |
 * | `hut` | 10×10 弧顶网格 + `texture` 皴（`:2583-2600`） | 弧顶采样（`tex` 段），无皴 |
 * | 参数 | `hei/wid/rot/per/tra/fro/wei/seg/dec/…` 共十余个 | 只保留 `hei/wid/rot/per/sto/sty/rai/seed` 的**名义** |
 * | 颜色 | `rgba(100,100,100,0.4)` 描边 + `white` 填充 | 同（白底 + 灰 0.4 描边，width=1.5） |
 *
 * ## ⚠️ 被收下但**从不使用**的参数（逐个说明）
 * - [kind1] 的 `rot`：上游 arch01 的 `rot` 传给 `box`（决定建筑体的左右偏心）；port 的 kind1
 *   是半圆拱 ⇒ `rot` 无处可用（**忽略**）。
 * - [kind1] 的 `per`：上游 arch01 的 `per` 是屋檐细分；port 用固定的 8 段半圆 ⇒ `per` **忽略**。
 * - [kind1] 的 `hei`：上游 `hei` 是楼体总高（`h0`/`h1` 拆分给 hut 与 box）；port 用它当拱高 ⇒ **语义不同**。
 * - [kind2] 的 `rot`：上游 arch02/03/04 的 `rot` 传 `box`/`roof`/`rail`/`pagroof`；port 忽略。
 * - [kind2] 的 `sto`：上游 `sto` 是**层数**（`for (i = 0; i < sto; i++)` 逐层叠楼）；
 *   port 只用它给噪声加偏移 ⇒ **语义不同**，层数不生效。
 * - [kind2] 的 `sty`：上游 `sty` 是立面装饰样式（`deco(sty, …)` 的窗/门图案）；port 忽略。
 * - [kind2] 的 `rai`：上游 `rai` 控制是否叠 `rail`；port 只在 `rai=true` 时追加一段矩形栏杆 ⇒ 形状不同。
 * - [kind2] 的 `per`：上游 `per` 是屋檐细分；port 用固定的 8 段半圆 ⇒ **忽略**。
 * - [hut] 的 `tex`：上游 `tex` 是**皴线数**（`texture(ptlist, {tex})`）；port 用它当弧顶采样段数
 *   ⇒ **语义不同**。
 *
 * ## ⚠️ PRNG 抽取次数与上游**不一致**（已知偏差，本轮不修）
 * 上游 arch01/02/03/04 内部每次 `stroke()` 抽 1 次（`n0`）、每次 `rail()` 另抽，
 * `arch01` 还会画人物 ⇒ 单次调用的抽取次数随参数变化（node 实跑上游 JS 实测：
 * arch01 ≈ **2522** 次、arch02(sto=3) = **45** 次、arch03(sto=5) = **160** 次、
 * arch04(sto=5) = **192** 次）。port 的 [kind1]/[kind2] **一次都不抽** ⇒
 * **调用点之后的所有随机流都会与上游分叉**（山体里 arch 的位置/数量因此可能不同）。
 *
 * ## ⚠️ 死代码
 * [box]/[deco]/[rail]/[roof]/[pagroof] 是 **private 且从未被调用**（死代码）：它们是上游
 * `box`/`deco`/`rail`/`roof`/`pagroof` 的**另写简化版**，既不参与渲染也不参与随机流。
 * 保留它们只为留档「上游组合体里有什么」；⛔ 不要误以为 port 用到了上游那套建筑几何。
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
     * 上游 [hut]（`:2542-2607`）：草屋。
     *
     * 移植状态：**改造-③：简化实现，与上游无视觉等价关系**（另加改造-① SVG→轮廓、改造-⑤ 轮廓样式）。
     *
     * ## 与上游的差异
     * - 上游是 `reso=[10,10]` 的**网格**（`nx = wid * (i/(reso[0]-1) - 0.5) * pow(j/(reso[1]-1), 0.7)`，
     *   `ny = heir * j/(reso[1]-1)`，其中 `heir = hei + hei*0.2*Math.random()` **抽 1 次随机**），
     *   再发 3 条 poly（白底 + 两条侧描边）+ 一轮 `texture` 皴（`tex` 条，每条 3 次抽取）。
     * - port 是**一条**闭合轮廓：底左 → 弧顶（`tex+1` 点，`sin` 包络 + 噪声）→ 底右。
     * - ⚠️ **`tex` 语义不同**：上游 `tex` 是**皴线数**；port 用它当弧顶**采样段数**（下限 4）。
     * - ⚠️ port **不抽任何随机数**（上游 `hut` 至少 1 + 3·tex 次）⇒ 调用点后的随机流会分叉。
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
     * 上游 [arch01]（`:3002-3059`）：拱桥（类型 1）。
     *
     * 移植状态：**改造-③：简化实现，与上游无视觉等价关系**（另加改造-①、改造-② 去人物、改造-⑤）。
     *
     * ## 与上游的差异
     * - 上游 arch01 = `hut`（草屋）+ `box`（楼体）+ `rail`×2（栏杆）+ `Man.man`（人物，可 0~2 个），
     *   共发射**十余条** poly/stroke；port 只发**一条**闭合轮廓（半圆拱 + 桥面）。
     * - ⚠️ **被收下但从不使用的参数**：
     *   - `rot`：上游传给 `box`（决定楼体左右偏心）；port 是半圆拱 ⇒ **忽略**。
     *   - `per`：上游是屋檐细分（并传给 `box`/`rail`）；port 用固定的 8 段半圆 ⇒ **忽略**。
     *   - `hei`：上游是楼体总高（`h0 = hei*p` / `h1 = hei*(1-p)`，`p = 0.4 + rnd*0.2` **抽 1 次随机**）；
     *     port 用它当**拱高** ⇒ **语义不同**。
     * - ⚠️ **PRNG 抽取次数与上游不一致**：上游 arch01 实测约 **2522** 次/调用（`stroke` 的 n0、
     *   `rail` 的分段、`Man.man` 的笔画随机…）；port **一次都不抽** ⇒ 调用点之后的随机流会分叉。
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
     * 上游 [arch02]（`:3061-3119`）/ [arch03]（`:3120-3163`）/ [arch04]（`:3164-3207`）：拱桥（类型 2/3/4）。
     *
     * 移植状态：**改造-③：简化实现，与上游无视觉等价关系**（另加改造-①、改造-⑤）。
     *
     * ## 与上游的差异
     * - 上游 arch02/03/04 是 `box` + `rail` + `roof`/`pagroof` 的**多层组合体**
     *   （`for (i = 0; i < sto; i++)` 逐层叠楼，每层发 4~6 条 poly/stroke）；
     *   port 只发**一条**闭合轮廓（半圆拱 + 桥面 + 可选一段矩形栏杆）。
     * - ⚠️ **被收下但从不使用的参数**：
     *   - `rot`：上游传给 `box`/`roof`/`rail`/`pagroof`；port **忽略**。
     *   - `sto`：上游是**层数**（`for (i = 0; i < sto; i++)`）；port 只用它给噪声加偏移
     *     ⇒ **语义不同**，层数不生效。
     *   - `sty`：上游是立面装饰样式（`deco(sty, …)` 的窗/门图案）；port **忽略**。
     *   - `rai`：上游控制是否叠 `rail`；port 在 `rai=true` 时追加一段矩形栏杆 ⇒ 形状不同。
     *   - `per`：上游是屋檐细分；port 用固定的 8 段半圆 ⇒ **忽略**。
     * - ⚠️ **PRNG 抽取次数与上游不一致**：上游实测 arch02(sto=3) = **45** 次、arch03(sto=5) = **160** 次、
     *   arch04(sto=5) = **192** 次/调用；port **一次都不抽** ⇒ 调用点之后的随机流会分叉。
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
     * 上游 [box]（`:2609-2687`）：矩形楼阁。
     *
     * 移植状态：**改造-③：简化实现，与上游无视觉等价关系**。
     * ⚠️ **死代码**：private 且**从未被调用**（上游的 box 由 arch01/02/03/04 组合调用，
     * port 的 kind1/kind2 不复刻那套组合体）⇒ 既不参与渲染也不参与随机流。
     * 保留只为留档「上游组合体里有什么」。
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
     * 上游 [deco]（`:2689-2751`）：建筑立面装饰（窗/门图案）。
     *
     * 移植状态：**改造-③：简化实现，与上游无视觉等价关系**。
     * ⚠️ **死代码**：private 且**从未被调用**（上游由 `box` 的 `dec` 回调驱动，
     * port 的 kind1/kind2 不复刻那套组合体）。
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
     * 上游 [rail]（`:2753-2838`）：栏杆。
     *
     * 移植状态：**改造-③：简化实现，与上游无视觉等价关系**。
     * ⚠️ **死代码**：private 且**从未被调用**（上游由 arch01/02/03/04 组合调用，
     * port 的 kind1/kind2 只在 `rai=true` 时追加一段**另写**的矩形栏杆）。
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
     * 上游 [roof]（`:2840-2956`）：传统中式曲面屋顶。
     *
     * 移植状态：**改造-③：简化实现，与上游无视觉等价关系**。
     * ⚠️ **死代码**：private 且**从未被调用**（上游由 arch02/03/04 组合调用，
     * port 的 kind1/kind2 不复刻那套组合体）。
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
     * 上游 [pagroof]（`:2958-3000`）：宝塔顶。
     *
     * 移植状态：**改造-③：简化实现，与上游无视觉等价关系**。
     * ⚠️ **死代码**：private 且**从未被调用**（上游由 arch03/04 组合调用，
     * port 的 kind1/kind2 不复刻那套组合体）。
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
