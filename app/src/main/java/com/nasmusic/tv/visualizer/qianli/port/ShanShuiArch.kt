/**
 * 建筑图元层 —— 草屋、楼阁、屋顶、栏杆、拱桥等。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 *
 * ## 移植状态一览
 * | 函数 | 上游 | port | 说明 |
 * |---|---|---|---|
 * | [kind1] | `Arch.arch01`（`:3002-3059`） | **照抄**（改造-① SVG→轮廓、④ 字符串→纯数据、⑤ 轮廓样式、② 去人物**但保留随机抽取**） | 草屋 + 桥身 + 栏杆×2 |
 * | [hut] | `hut`（`:2542-2607`） | **照抄**（同上） | 10×10 网格 + 一轮皴 |
 * | [kind2] | `Arch.arch02/03/04`（`:3061-3207`） | **改造-③：简化实现**（一条闭合轮廓） | ⛔ 仍未与上游视觉等价 |
 *
 * ## 上游 `Arch.arch01` 的逐层结构（`:3002-3059`）
 * ```
 * arch01(xoff, yoff, seed, {hei, wid, per})
 *   ├─ p  = 0.4 + rnd*0.2            （1 次抽取）→ h0 = hei*p（草屋高）、h1 = hei*(1-p)（桥身高）
 *   ├─ hut(xoff, yoff-hei, {hei:h0, wid})        ⇒ 最多 303 条轮廓（白底 1 + 两侧描边 2 + 皴 300）
 *   │                                               ⛔ 零长度的皴线**不发射**（上游 stroke() 对空点列返回 ""）
 *   │                                               ⇒ 实测（夹具见下）只有 **182** 条
 *   ├─ box(xoff, yoff, {hei:h1, wid:wid*2/3, per, bot:false}) ⇒ 4 条轮廓（桥身 4 根线）
 *   ├─ rail(xoff, yoff, seed, {tra:true, fro:false, hei:10, wid, per:per*2, seg}) ⇒ 竖栏 + 桥栏
 *   ├─ mcnt = randChoice([0,1,1,2]) + Man.man ×(0~2)（渔人）⇒ **本仓不渲染，但保留全部随机抽取**
 *   └─ rail(xoff, yoff, seed, {tra:false, fro:true, hei:10, wid, per:per*2, seg}) ⇒ 桥面栏
 * ```
 *
 * ## ⛔ 上游 `arch01` 的 `rot` **从不被使用**
 * 上游 `:3006` 读了 `rot` 之后再没用过它：`box`/`rail` 收到的 `rot` 是**它们自己的默认值 0.7**
 * （arch01 只传 `hei`/`wid`/`per`/`bot`/`tra`/`fro`/`seg`）⇒ port 的 [kind1] 同样**忽略** `rot`。
 * （旧版 KDoc 写「rot 传给 box」是**记录错误**，本版更正。）
 *
 * ## ⚠️ PRNG 抽取次数：port 与上游**完全一致**（差值 = 0）
 * 上游 `arch01` 一次调用抽 **2534** 次（node 实跑上游原始 JS；
 * 夹具：PRNG 种子 157 + `arch01(0, 0, 3, {hei:90, wid:180, per:0.5})`），
 * 其中渔人（`Man.man`）占 **20** 次/个（`normRand` 1 + `randChoice(fli)` 1 + `Man.man` 内部 18；
 * `mcnt == 2` 时上游两个渔人的 `fli` 是**常量** ⇒ 各 19 次）。
 * 本仓**不渲染**渔人（改造-②「去人物」），但**消耗与上游完全相同的抽取**
 * ⇒ **抽取次数差值 = 0（常量）** ⇒ 调用点之后的随机流与上游**完全对齐**。
 * ⚠️ 若改成「少抽」，则 arch01 之后的所有元素（树、山脚、水纹…）的位置/形状都会与上游分叉。
 * ⚠️ **2534 这个数不是常量**：皴线的 `dis` 是 `wtrand(a=>a*a)`（拒绝采样）⇒ 每条线的
 *   抽取次数随随机流变化 ⇒ 门禁**只在固定夹具**下断言它（`G-PORT-13`，黄金基线由
 *   `scripts/e50_arch01_upstream_probe.js` 生成）。
 *
 * ## 门禁（G-PORT-13）
 * `ShanShuiPortGateTest.gPort13_*`：轮廓条数 / 总点数 / 结构摘要（填充·描边·线宽·点数）/
 * 0.1 网格坐标摘要 / 0.01 网格坐标摘要 / PRNG 抽取次数，全部与上游实跑结果**逐位相等**
 * （期望值在 `app/src/test/resources/qianli/shan_shui_arch01_golden.json`，⛔ 不在测试源码里）。
 * ⚠️ 坐标**不能**逐位比 Float32：V8 与 JVM 的 `Math.cos/sin/atan2` 最多差 1 ULP
 *   ⇒ 量化成 Float32 后偶尔差 1 个 ULP（实测 951 点里 115 点、最大 7.63e-6）⇒ 改比量化网格。
 *
 * ⛔ port 包零 Android 依赖 ⇒ 本对象**不出现** `android.graphics.Path` / `Canvas`。
 * 所有 `Math.random()` → [ShanShuiPrng.global.rnd()]；所有 `Noise.noise()` → [ShanShuiNoise.noise]。
 * 所有 `randChoice` → [ShanShuiUtil.randChoice]；所有 `wtrand` → [ShanShuiUtil.wtrand]。
 * 上游 `poly()`/`stroke()` 返回 SVG 字符串 → 本对象返回 [List]<[ShanShuiContour]>。
 * 所有点坐标已加 xoff/yoff 偏移（**偏移在 Float64 里加完再量化到 Float32**，与上游同序）。
 */

package com.nasmusic.tv.visualizer.qianli.port

object ShanShuiArch {

    // ─── 上游 arch01（照抄） ───────────────────────────────────

    /**
     * 上游 [Arch.arch01]（`:3002-3059`）：拱桥（类型 1）。
     *
     * 移植状态：**照抄**（改造-① SVG→轮廓、④ 字符串→纯数据、⑤ 轮廓样式；
     * 改造-②「去人物」——渔人不渲染，但**保留上游的全部随机抽取**）。
     *
     * ## 发射顺序（与上游逐条对应）
     * 1. `p = 0.4 + rnd*0.2`（`:3011`）——**1 次抽取**。
     * 2. [hut]（`:3016`）：`hut(xoff, yoff - hei, {hei: h0, wid: wid})`。
     *    ⚠️ 上游 **不传 `tex`** ⇒ 取上游默认 **300**（皴线数）。
     * 3. [box]（`:3017-3022`）：`box(xoff, yoff, {hei: h1, wid: wid*2/3, per: per, bot: false})`。
     * 4. [rail]（`:3024-3031`）：`tra:true, fro:false`（后栏），`seg = (3 + rnd*3)|0`。
     * 5. 渔人（`:3033-3048`）：`mcnt = randChoice([0,1,1,2])` + 0~2 个 `Man.man`。
     *    ⚠️ **只消耗随机、不渲染**（见 [burnMan]）。
     * 6. [rail]（`:3049-3056`）：`tra:false, fro:true`（前栏），`seg = (3 + rnd*3)|0`。
     *
     * ## 与上游的差异
     * - **渔人不渲染**（改造-②）：上游画 0~2 个渔人（每个 15 条轮廓）；port 不画。
     *   但抽取次数**一模一样** ⇒ 随机流不错位。
     * - `rot` **被收下但不使用**（上游 `arch01` 自己也不使用它，见文件头说明）。
     * - `per` 的语义 = 上游的**屋檐细分参数**（传给 `box` 与 `rail`）。
     *
     * @param seed 上游的 `seed`（喂给 `rail` 的 `Noise.noise(i, j*0.5, seed)`）。
     */
    fun kind1(
        xoff: Double, yoff: Double, seed: Double,
        hei: Double, wid: Double, rot: Double, per: Double,
    ): List<ShanShuiContour> {
        val canv = ArrayList<ShanShuiContour>()

        // 上游 :3011-3013
        val p = 0.4 + ShanShuiPrng.global.rnd() * 0.2
        val h0 = hei * p
        val h1 = hei * (1 - p)

        // 上游 :3016
        canv.addAll(hut(xoff, yoff - hei, h0, wid, HUT_TEX))

        // 上游 :3017-3022
        canv.addAll(box(xoff, yoff, h1, (wid * 2) / 3, per, false))

        // 上游 :3024-3031（seg 的抽取发生在实参求值时 ⇒ 必须在调用 rail 之前抽）
        canv.addAll(rail(xoff, yoff, seed, RAIL_HEI, wid, per * 2, true, false, railSeg()))

        // 上游 :3033-3048（渔人：只消耗随机，不渲染）
        val mcnt = ShanShuiUtil.randChoice(listOf(0, 1, 1, 2))
        if (mcnt == 1) {
            ShanShuiUtil.normRand(-wid / 3, wid / 3) // 上游 :3035 的实参
            ShanShuiUtil.randChoice(listOf(true, false)) // 上游 :3036 的 fli
            burnMan()
        } else if (mcnt == 2) {
            ShanShuiUtil.normRand(-wid / 4, -wid / 5) // 上游 :3040 的实参
            burnMan() // 上游 :3042 的 fli 是**常量 false** ⇒ 无 randChoice 抽取
            ShanShuiUtil.normRand(wid / 5, wid / 4) // 上游 :3044 的实参
            burnMan() // 上游 :3046 的 fli 是**常量 true** ⇒ 无 randChoice 抽取
        }

        // 上游 :3049-3056
        canv.addAll(rail(xoff, yoff, seed, RAIL_HEI, wid, per * 2, false, true, railSeg()))

        return canv
    }

    /**
     * 上游 [hut]（`:2542-2607`）：草屋。
     *
     * 移植状态：**照抄**（改造-①/④/⑤）。
     *
     * ## 几何（上游 `:2548-2560`）
     * `reso = [10, 10]` 的网格；**每列**抽 1 次随机决定该列的高度：
     * `heir = hei + hei*0.2*rnd()`；顶点为
     * `nx = wid * (i/(reso[0]-1) - 0.5) * pow(j/(reso[1]-1), 0.7)`、
     * `ny = heir * j/(reso[1]-1)`（`i` = 列 0..9，`j` = 行 0..9）。
     *
     * ## 发射（与上游逐条对应）
     * 1. **白底**（`:2562-2567`）：`poly(ptlist[0].slice(0,-1) ++ ptlist[last].slice(0,-1).reverse(),
     *    {fil:"white", str:"none"})` ⇒ 18 点，`fill = white`、`stroke = null`、`width = 0`。
     * 2. **左侧描边**（`:2568-2574`）：`poly(ptlist[0], {fil:"none", str:"rgba(100,100,100,0.3)", wid:2})`
     *    ⇒ 10 点。
     * 3. **右侧描边**（`:2575-2581`）：同上，作用于 `ptlist[last]` ⇒ 10 点。
     * 4. **一轮皴**（`:2583-2600`）：`texture(ptlist, {tex, wid:1, len:0.25,
     *    col: rgba(120,120,120, 0.3+rnd*0.3), dis: wtrand(a=>a*a), noi: ()=>5})`。
     *    ⚠️ `tex` 条里**长度为零的线不发射**（上游 `stroke()` 对空点列返回 `""`）⇒
     *    实测 300 条里只有 179 条真正发射。
     *
     * @param tex 上游 `hut` 的 `tex`（**皴线数**，默认 300）——⛔ 不是「弧顶采样段数」。
     */
    fun hut(xoff: Double, yoff: Double, hei: Double, wid: Double, tex: Double): List<ShanShuiContour> {
        val reso0 = 10
        val reso1 = 10
        val ptlist = ArrayList<MutableList<DPoint>>(reso0)

        // 上游 :2551-2560
        for (i in 0 until reso0) {
            val row = ArrayList<DPoint>(reso1)
            // 上游 :2553 —— **每列抽 1 次**
            val heir = hei + hei * 0.2 * ShanShuiPrng.global.rnd()
            for (j in 0 until reso1) {
                val nx = wid * (i.toDouble() / (reso0 - 1) - 0.5) *
                    Math.pow(j.toDouble() / (reso1 - 1), 0.7)
                val ny = heir * (j.toDouble() / (reso1 - 1))
                row.add(doubleArrayOf(nx, ny))
            }
            ptlist.add(row)
        }

        val canv = ArrayList<ShanShuiContour>()
        val last = ptlist[ptlist.size - 1]

        // 上游 :2562-2567 —— 白底
        val white = ArrayList<DPoint>(18)
        for (k in 0 until ptlist[0].size - 1) white.add(ptlist[0][k])
        for (k in last.size - 2 downTo 0) white.add(last[k])
        canv.add(ShanShuiContour(white.map { off(it, xoff, yoff) }, whiteFill, null, 0.0))

        // 上游 :2568-2581 —— 两侧描边
        canv.add(ShanShuiContour(ptlist[0].map { off(it, xoff, yoff) }, null, archOutline, 2.0))
        canv.add(ShanShuiContour(last.map { off(it, xoff, yoff) }, null, archOutline, 2.0))

        // 上游 :2583-2600 —— 一轮皴
        canv.addAll(
            texture(
                ptlist, xoff, yoff, tex,
                wid = 1.0, len = 0.25,
                noi = { 5.0 },
                dis = { ShanShuiUtil.wtrand { a -> a * a } },
                col = { hutCunColor() },
            )
        )
        return canv
    }

    // ─── kind2（arch02/03/04）：仍是简化实现 ───────────────────

    /**
     * 上游 [arch02]（`:3061-3119`）/ [arch03]（`:3120-3163`）/ [arch04]（`:3164-3207`）：拱桥（类型 2/3/4）。
     *
     * 移植状态：**改造-③：简化实现，与上游无视觉等价关系**（另加改造-①、改造-⑤）。
     *
     * ## 与上游的差异（本轮**未动**）
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
     * - ⚠️ **PRNG 抽取次数与上游不一致**：port 一次都不抽 ⇒ 调用点之后的随机流会与上游分叉。
     *   ⛔ 这是 E50 arch02/03/04 的**遗留偏差**，与本轮的 arch01 修复无关（见 [kind1]）。
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

    // ─── private 辅助（上游组合体的零件） ───────────────────────

    /**
     * 上游 [box]（`:2609-2687`）：桥身。
     *
     * 移植状态：**照抄**（改造-①/④/⑤）。
     *
     * ## 几何（上游 `:2625-2651`）
     * `mid = -wid*0.5 + wid*rot`、`bmid = -wid*0.5 + wid*(1-rot)`；
     * 点列 = 左边柱（5 等分）+ 右边柱（5 等分）+（`bot` 时）两条底边 + 中柱（5 等分）
     * +（`tra` 时）两条反斜边 + 反斜中柱（5 等分）。
     *
     * ## 发射（与上游逐条对应）
     * - `tra = true` ⇒ **不发**白底多边形（上游 `:2662-2669` 的 `if (!tra)`）。
     * - 每条点列发一次 `stroke(..., {col:"rgba(100,100,100,0.4)", noi:1, wid:wei(3), fun:()=>1})`
     *   ⇒ 4 条轮廓（`fill = stroke = rgba(100,100,100,0.4)`、`width = 1`）。
     * - `dec` 回调缺省返回 `[]` ⇒ 桥身**没有**立面装饰。
     *
     * ⚠️ 上游 `arch01` **不向 box 传 `rot`** ⇒ 这里用的是 **box 自己的默认值 0.7**。
     */
    private fun box(
        xoff: Double, yoff: Double,
        hei: Double, wid: Double, per: Double, bot: Boolean,
    ): List<ShanShuiContour> {
        val mid = -wid * 0.5 + wid * BOX_ROT
        val bmid = -wid * 0.5 + wid * (1 - BOX_ROT)
        val ptlist = ArrayList<MutableList<DPoint>>()

        // 上游 :2628-2629
        ptlist.add(div(listOf(dpoint(-wid * 0.5, -hei), dpoint(-wid * 0.5, 0.0)), 5))
        ptlist.add(div(listOf(dpoint(wid * 0.5, -hei), dpoint(wid * 0.5, 0.0)), 5))
        // 上游 :2630-2633
        if (bot) {
            ptlist.add(div(listOf(dpoint(-wid * 0.5, 0.0), dpoint(mid, per)), 5))
            ptlist.add(div(listOf(dpoint(wid * 0.5, 0.0), dpoint(mid, per)), 5))
        }
        // 上游 :2634
        ptlist.add(div(listOf(dpoint(mid, -hei), dpoint(mid, per)), 5))
        // 上游 :2635-2641（tra 恒为 true）
        if (bot) {
            ptlist.add(div(listOf(dpoint(-wid * 0.5, 0.0), dpoint(bmid, -per)), 5))
            ptlist.add(div(listOf(dpoint(wid * 0.5, 0.0), dpoint(bmid, -per)), 5))
        }
        ptlist.add(div(listOf(dpoint(bmid, -hei), dpoint(bmid, -per)), 5))
        // 上游 :2643-2651：dec 缺省返回 [] ⇒ 无追加

        val canv = ArrayList<ShanShuiContour>(ptlist.size)
        for (pts in ptlist) {
            val c = archStroke
            val ribbon = ShanShuiBrush.ribbon(pts.map { off(it, xoff, yoff) }, BOX_WEI, { 1.0 }, 1.0, c)
            canv.add(ShanShuiContour(ribbon, c, c, 1.0))
        }
        return canv
    }

    /**
     * 上游 [rail]（`:2753-2838`）：栏杆。
     *
     * 移植状态：**照抄**（改造-①/④/⑤）。
     *
     * ## 几何（上游 `:2766-2792`）
     * `mid = -wid*0.5 + wid*rot`、`bmid = -wid*0.5 + wid*(1-rot)`；
     * 4 条点列（每条 `seg` 等分 ⇒ `seg+1` 点）：后栏下沿 / 后栏上沿 / 前栏下沿 / 前栏上沿
     * ——具体哪几条由 `tra`/`fro` 决定；`tra` 时还会随机「开口」一条（上游 `:2786-2792`，
     * **抽 1 次随机**）。
     *
     * ## 发射（与上游逐条对应）
     * 1. **竖栏**（`:2795-2820`）：`ptlist[i][j]` 与 `ptlist[对侧][j]` 连成 3 点短线
     *    （`poly(..., {fil:"none", str:"rgba(100,100,100,0.5)", wid:2})`），
     *    两端各按 `Noise.noise` 上下抖动 `hei`，短线首点再随机横移（**每条抽 1 次随机**）。
     * 2. **栏身**（`:2822-2836`）：每条点列发一次
     *    `stroke(..., {col:"rgba(100,100,100,0.5)", noi:0.5, wid:wei(1), fun:()=>1})` ⇒ 4 条轮廓。
     *
     * ⚠️ 上游 `arch01` **不向 rail 传 `rot`** ⇒ 这里用的是 **rail 自己的默认值 0.7**。
     */
    private fun rail(
        xoff: Double, yoff: Double, seed: Double,
        hei: Double, wid: Double, per: Double,
        tra: Boolean, fro: Boolean, seg: Int,
    ): List<ShanShuiContour> {
        val mid = -wid * 0.5 + wid * RAIL_ROT
        val bmid = -wid * 0.5 + wid * (1 - RAIL_ROT)
        val ptlist = ArrayList<MutableList<DPoint>>()

        // 上游 :2770-2773
        if (fro) {
            ptlist.add(div(listOf(dpoint(-wid * 0.5, 0.0), dpoint(mid, per)), seg))
            ptlist.add(div(listOf(dpoint(mid, per), dpoint(wid * 0.5, 0.0)), seg))
        }
        // 上游 :2774-2777
        if (tra) {
            ptlist.add(div(listOf(dpoint(-wid * 0.5, 0.0), dpoint(bmid, -per)), seg))
            ptlist.add(div(listOf(dpoint(bmid, -per), dpoint(wid * 0.5, 0.0)), seg))
        }
        // 上游 :2778-2781
        if (fro) {
            ptlist.add(div(listOf(dpoint(-wid * 0.5, -hei), dpoint(mid, -hei + per)), seg))
            ptlist.add(div(listOf(dpoint(mid, -hei + per), dpoint(wid * 0.5, -hei)), seg))
        }
        // 上游 :2782-2785
        if (tra) {
            ptlist.add(div(listOf(dpoint(-wid * 0.5, -hei), dpoint(bmid, -hei - per)), seg))
            ptlist.add(div(listOf(dpoint(bmid, -hei - per), dpoint(wid * 0.5, -hei)), seg))
        }
        // 上游 :2786-2792 —— 随机开口（**抽 1 次随机**）
        if (tra) {
            val open = Math.floor(ShanShuiPrng.global.rnd() * ptlist.size).toInt()
            ptlist[open] = dropLast(ptlist[open])
            val nxt = (open + ptlist.size) % ptlist.size
            ptlist[nxt] = dropLast(ptlist[nxt])
        }

        val canv = ArrayList<ShanShuiContour>()

        // 上游 :2795-2820 —— 竖栏（poly）
        for (i in 0 until ptlist.size / 2) {
            for (j in ptlist[i].indices) {
                ptlist[i][j][1] += (ShanShuiNoise.noise(i.toDouble(), j * 0.5, seed) - 0.5) * hei
                val other = (ptlist.size / 2 + i) % ptlist.size
                val olen = ptlist[other].size
                ptlist[other][j % olen][1] += (ShanShuiNoise.noise(i + 0.5, j * 0.5, seed) - 0.5) * hei
                val ln = div(listOf(ptlist[i][j], ptlist[other][j % olen]), 2)
                ln[0][0] += (ShanShuiPrng.global.rnd() - 0.5) * hei * 0.5
                canv.add(ShanShuiContour(ln.map { off(it, xoff, yoff) }, null, railStroke, 2.0))
            }
        }

        // 上游 :2822-2836 —— 栏身（stroke）
        for (pts in ptlist) {
            val c = railStroke
            val ribbon = ShanShuiBrush.ribbon(pts.map { off(it, xoff, yoff) }, RAIL_WEI, { 1.0 }, 0.5, c)
            canv.add(ShanShuiContour(ribbon, c, c, 1.0))
        }
        return canv
    }

    /**
     * 上游 [texture]（`:653-741`）：皴。
     *
     * 移植状态：**照抄**（改造-①/④/⑤）。
     *
     * ⚠️ **不能复用** [ShanShuiBrush.cun]：它把 `len` 固定成 0.2、`noi` 固定成 `30/x`，
     * 而上游 `hut` 传的是 **`len = 0.25`、`noi = () => 5`** ⇒ 几何与抽取次数都会不同。
     * 本函数是上游 `texture` 的**逐行复刻**（含 `len`/`noi` 两个形参）。
     *
     * ## 抽取次序（与上游一致）
     * - **第一轮**（上游 `:686-718`，逐条 `i`）：`dis()`（`wtrand` 的拒绝采样，每次 2 次抽取/轮）
     *   → 1 次抽取（`hlen`）。
     * - **第二轮**（上游 `:732-739`，逐条 `j`）：`col()`（1 次抽取）→ `stroke()`（1 次抽取）。
     * ⇒ 两轮的抽取**不交错**（上游先把整张 texlist 建完，再逐条发射）。
     */
    private fun texture(
        ptlist: List<List<DPoint>>,
        xof: Double, yof: Double,
        tex: Double,
        wid: Double, len: Double,
        noi: (Double) -> Double,
        dis: () -> Double,
        col: () -> ShanShuiColor,
    ): List<ShanShuiContour> {
        val texi = tex.toInt()
        val reso0 = ptlist.size
        val reso1 = ptlist[0].size
        val texlist = ArrayList<MutableList<DPoint>>(texi)

        // 上游 :686-718
        for (i in 0 until texi) {
            val mid = (dis() * reso1).toInt()
            val hlen = Math.floor(ShanShuiPrng.global.rnd() * (reso1 * len)).toInt()
            var start = mid - hlen
            var end = mid + hlen
            start = minOf(maxOf(start, 0), reso1)
            end = minOf(maxOf(end, 0), reso1)
            val layer = (i.toDouble() / texi) * (reso0 - 1)
            val row = ArrayList<DPoint>()
            for (j in start until end) {
                val p = layer - Math.floor(layer)
                val fl = Math.floor(layer).toInt()
                val ce = Math.ceil(layer).toInt()
                val x = ptlist[fl][j][0] * p + ptlist[ce][j][0] * (1 - p)
                val y = ptlist[fl][j][1] * p + ptlist[ce][j][1] * (1 - p)
                val ns0 = noi(layer + 1) * (ShanShuiNoise.noise(x, j * 0.5) - 0.5)
                val ns1 = noi(layer + 1) * (ShanShuiNoise.noise(y, j * 0.5) - 0.5)
                row.add(doubleArrayOf(x + ns0, y + ns1))
            }
            texlist.add(row)
        }

        // 上游 :731-739 —— TEXTURE 轮
        val canv = ArrayList<ShanShuiContour>(texi)
        for (j in texlist.indices) {
            // ⚠️ 上游 `col(...)` 是 `stroke(...)` 的**实参** ⇒ **先求值** ⇒
            //    即使该条皴线长度为零（`stroke()` 对空点列返回 `""`）也**照样抽 1 次随机**。
            //    ⛔ 漏掉这一次抽取 ⇒ 后续所有皴线/桥身/栏杆的随机值全部错位
            //    （实测会少抽 121 次 = 300 条里零长度的条数）。
            val c = col()
            // 上游 `stroke()` 对空点列返回 "" ⇒ 不发射（`:531-533`）
            if (texlist[j].isEmpty()) continue
            val ribbon = ShanShuiBrush.ribbon(
                texlist[j].map { off(it, xof, yof) }, wid, { x -> Math.sin(x * Math.PI) }, 0.5, c,
            )
            canv.add(ShanShuiContour(ribbon, c, c, 1.0))
        }
        return canv
    }

    /**
     * 改造-②「去人物」：**不渲染**渔人，但**消耗与上游完全相同的随机抽取**。
     *
     * 上游 [Man.man]（`:3522-3664`）的抽取点（逐个核对）：
     * | 位置 | 内容 | 次数 |
     * |---|---|---|
     * | `:3536-3546` | `ang` 默认数组：`normRand(0,0)` + 3 次 `Math.random()` | 4 |
     * | `:3607-3624` | `cloth`（调用 4 次）：`expand` 的 `n0`（`:3329`）+ 2 次 `stroke` 的 `n0`（`:537`） | 4×3 = 12 |
     * | `:3653-3655` | 头部 `expand` 的 `n0` | 1 |
     * | `:3411` | `hat01` 的 `seed = Math.random()` | 1 |
     * | **合计** | | **18** |
     *
     * ⇒ 上游每个渔人 = `arch01` 层的 `normRand`(1) + `randChoice(fli)`(1) + 本函数(18) = **20** 次
     * （`mcnt == 2` 时上游的两个渔人的 `fli` 是**常量** ⇒ 各 19 次）。
     *
     * ⛔ **不许改成「少抽」**：少抽会让 arch01 之后的随机流整体错位
     * ⇒ 树、山脚、水纹……的位置与形状全部与上游对不上。
     */
    private fun burnMan() {
        repeat(MAN_DRAWS) { ShanShuiPrng.global.rnd() }
    }

    // ─── 内部辅助 ──────────────────────────────────────────────

    /** 上游的中间点用 Float64（port 的 [ShanShuiPath.Point] 是 Float32 ⇒ 精度不够）。 */
    private typealias DPoint = DoubleArray

    /** 上游 `hut` 的默认皴线数（`:2546`）。 */
    private const val HUT_TEX = 300.0

    /** 上游 `box` 的默认 `rot`（`:2613`）——`arch01` 不给 box 传 rot ⇒ 用它自己的默认值。 */
    private const val BOX_ROT = 0.7

    /** 上游 `box` 的默认 `wei`（`:2617`）。 */
    private const val BOX_WEI = 3.0

    /** 上游 `rail` 的默认 `rot`（`:2757`）——`arch01` 不给 rail 传 rot ⇒ 用它自己的默认值。 */
    private const val RAIL_ROT = 0.7

    /** 上游 `rail` 的默认 `wei`（`:2760`）。 */
    private const val RAIL_WEI = 1.0

    /** 上游 `arch01` 给两条 rail 的 `hei`（`:3027` / `:3052`）。 */
    private const val RAIL_HEI = 10.0

    /** 上游 `Man.man` 内部的抽取次数（见 [burnMan] 的逐项核对）。 */
    private const val MAN_DRAWS = 18

    /**
     * 上游 `fil: "white"` 对应的白色填充（上游 hut/box 的白底发射用）。
     *
     * 移植来源：上游 `:2566` / `:2667`。移植状态：**照抄**（改造-④：字符串 → 纯数据）。
     */
    private val whiteFill: ShanShuiColor = ShanShuiColor(255, 255, 255, 1.0)

    /**
     * 草屋两侧描边灰 —— 上游 hut 的 `poly(..., {str:"rgba(100,100,100,0.3)", wid:2})`（`:2572`）。
     *
     * 移植来源：上游 `:2572` / `:2579`。移植状态：**照抄**（改造-④：字符串 → 纯数据）。
     */
    private val archOutline: ShanShuiColor = ShanShuiColor.rgba(100, 100, 100, 0.3)

    /**
     * 建筑体描边灰 —— 上游 box 的 `stroke(..., {col:"rgba(100,100,100,0.4)", ...})`（`:2677`）。
     *
     * 移植来源：上游 `:2677`。移植状态：**照抄**（改造-④：字符串 → 纯数据）。
     */
    private val archStroke: ShanShuiColor = ShanShuiColor.rgba(100, 100, 100, 0.4)

    /**
     * 栏杆描边灰 —— 上游 rail 的 `poly(..., {str:"rgba(100,100,100,0.5)"})`（`:2816`）与
     * `stroke(..., {col:"rgba(100,100,100,0.5)"})`（`:2828`）。
     *
     * 移植来源：上游 `:2816` / `:2828`。移植状态：**照抄**（改造-④：字符串 → 纯数据）。
     */
    private val railStroke: ShanShuiColor = ShanShuiColor.rgba(100, 100, 100, 0.5)

    /**
     * 上游 `rail` 的 `seg`（上游 `:3030` / `:3055`：`seg = (3 + Math.random() * 3) | 0`）。
     *
     * ⚠️ **抽 1 次随机** ⇒ 必须在调用 `rail` **之前**求值（上游是实参求值）。
     */
    private fun railSeg(): Int = (3 + ShanShuiPrng.global.rnd() * 3).toInt()

    /**
     * 上游 `hut` 的皴线颜色（`:2589-2593`）：
     * `"rgba(120,120,120," + (0.3 + Math.random() * 0.3).toFixed(3) + ")"`。
     *
     * ⚠️ **每条皴线抽 1 次随机**；`.toFixed(3)` 的舍入由 [ShanShuiUtil.toFixed] 复现。
     */
    private fun hutCunColor(): ShanShuiColor =
        ShanShuiColor(120, 120, 120, ShanShuiUtil.toFixed(0.3 + ShanShuiPrng.global.rnd() * 0.3, 3))

    /** Float64 点（上游的中间点精度）。 */
    private fun dpoint(x: Double, y: Double): DPoint = doubleArrayOf(x, y)

    /** 加偏移并量化到 Float32（上游 `poly()` 是在 Float64 里加完偏移再 `.toFixed(1)`）。 */
    private fun off(p: DPoint, xof: Double, yof: Double): ShanShuiPath.Point =
        floatArrayOf((p[0] + xof).toFloat(), (p[1] + yof).toFloat())

    /** 上游 `div`（`:627-651`）的 **Float64** 复刻（折线等分）。 */
    private fun div(plist: List<DPoint>, reso: Int): MutableList<DPoint> {
        val tl = (plist.size - 1) * reso
        val rlist = ArrayList<DPoint>(tl + 1)
        for (i in 0 until tl) {
            val lastp = plist[Math.floor(i.toDouble() / reso).toInt()]
            val nextp = plist[Math.ceil(i.toDouble() / reso).toInt()]
            val p = (i % reso).toDouble() / reso
            val nx = lastp[0] * (1 - p) + nextp[0] * p
            val ny = lastp[1] * (1 - p) + nextp[1] * p
            rlist.add(doubleArrayOf(nx, ny))
        }
        // 上游 :647-649 —— 末点**原样**入列（不复制）
        if (plist.isNotEmpty()) rlist.add(plist[plist.size - 1])
        return rlist
    }

    /** 上游 `slice(0, -1)`（`:2788-2791`）。 */
    private fun dropLast(src: List<DPoint>): MutableList<DPoint> {
        val out = ArrayList<DPoint>(src.size - 1)
        for (i in 0 until src.size - 1) out.add(src[i])
        return out
    }

    /** 拱顶 Y 坐标（供 [kind2] 栏杆使用）。 */
    private fun archTopY(yoff: Double, hei: Double): Double = yoff - hei

    /**
     * 上游 [deco]（`:2689-2751`）：建筑立面装饰（窗/门图案）。
     *
     * 移植状态：**改造-③：简化实现，与上游无视觉等价关系**。
     * ⚠️ **死代码**：private 且**从未被调用**（上游由 `box` 的 `dec` 回调驱动，
     *   而 `arch01` 的 box 用的是**缺省 `dec`** ⇒ 返回 `[]` ⇒ 没有立面装饰）。
     * 保留只为留档「上游组合体里有什么」。
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
     * 上游 [roof]（`:2840-2956`）：传统中式曲面屋顶。
     *
     * 移植状态：**改造-③：简化实现，与上游无视觉等价关系**。
     * ⚠️ **死代码**：private 且**从未被调用**（上游由 arch02/03/04 组合调用，
     *   而本文件的 kind2 仍是简化实现）。
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
     *   而本文件的 kind2 仍是简化实现）。
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
}
