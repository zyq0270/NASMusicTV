/**
 * 建筑图元层 —— 草屋、楼阁、塔、拱桥、屋顶、栏杆等。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 *
 * ## 移植状态一览（2026-11-06 本轮：多层楼阁 / 塔 / 拱桥的逐行移植）
 *
 * | 上游函数 | 上游行号 | port | 说明 |
 * |---|---|---|---|
 * | `Arch.arch01` | `:3002-3059` | [kind1] | **照抄**（改造-① SVG→轮廓、④ 字符串→纯数据、⑤ 轮廓样式、② 去人物**但保留随机抽取**） |
 * | `hut` | `:2542-2607` | [hut] | **照抄**（⛔ 本轮**未改动**，黄金基线 `shan_shui_arch01_golden.json` 仍成立） |
 * | `Arch.arch02` | `:3061-3119` | [kind2] | **照抄**（多层楼阁） |
 * | `Arch.arch03` | `:3120-3163` | [kind3] | **照抄**（塔） |
 * | `Arch.arch04` | `:3164-3207` | [kind4] | **照抄**（拱桥） |
 * | `box` | `:2609-2687` | `box` | **照抄**（通用件：`rot`/`tra`/`bot`/`wei`/`dec` 全部透传） |
 * | `deco` | `:2689-2751` | `deco` | **照抄**（立面装饰：窗/门图案） |
 * | `rail` | `:2753-2838` | `rail` | **照抄**（通用件：`rot`/`wei` 透传） |
 * | `roof` | `:2840-2956` | `roof` | **照抄**（含唯一的 `<text>` 题字「Pizza Hut」彩蛋） |
 * | `pagroof` | `:2958-3000` | `pagroof` | **照抄**（攒尖顶） |
 *
 * ## 上游 `Arch.arch02` 的逐层结构（`:3061-3119`）
 * ```
 * arch02(xoff, yoff, seed, {hei, wid, rot, per, sto, sty, rai})
 *   └─ for (i = 0; i < sto; i++)            （`sto` = **层数**）
 *      ├─ hoff = 0（首层）；层间 `hoff += hei * 1.5`
 *      ├─ box(xoff, yoff-hoff, {tra:false, hei, wid:wid*0.85^i, rot, wei:1.5, per,
 *      │        dec: a => deco(sty, {...a, hsp:[[],[1,5],[1,5],[1,4]][sty],
 *      │                                      vsp:[[],[1,2],[1,2],[1,3]][sty]})})
 *      ├─ rai ? rail(xoff, yoff-hoff, i*0.2, {wid:wid*0.85^i*1.1, hei:hei/2, per,
 *      │        rot, wei:0.5, tra:false}) : []      （`seg` 缺省 4、`fro` 缺省 true）
 *      ├─ pla = (sto == 1 && rnd() < 1/3) ? [1, "Pizza Hut"] : undefined   ← **1 次抽取**
 *      └─ roof(xoff, yoff-hoff-hei, {hei, wid:wid*0.9^i, rot, wei:1.5, per, pla})
 * ```
 *
 * ## ⚠️ `Arch.arch02/03/04` 的 `seed` **从不被使用**
 * 上游 `:3071` / `:3128` / `:3172` 读了 `seed` 之后再没用过它：`rail` 收到的第三实参是
 * **`i * 0.2`**（`:3094` / `:3144` / `:3188`），不是 `seed` ⇒ port 同样只把 `seed` 收下不用。
 *
 * ## ⛔ 本轮对 `kind1`（上游 `arch01`）的影响：**零**
 * `box` / `rail` 改成了「通用件」（多了 `rot`/`wei`/`dec`/`tra`/`bot` 形参），
 * 但上游 `arch01` 给它们的实参与**改前完全一致**：
 * - `box`：`{hei, wid, per, bot:false}` ⇒ `rot` 用 box 自己的缺省 **0.7**、`tra` 缺省 **true**、
 *   `wei` 缺省 **3**、`dec` 缺省（返回 `[]`）⇒ **发射与改前逐条相同**；
 * - `rail`：`{tra, fro, hei:10, wid, per:per*2, seg}` ⇒ `rot` 缺省 **0.7**、`wei` 缺省 **1**
 *   ⇒ **发射与改前逐条相同**。
 *
 * ⛔ port 包零 Android 依赖 ⇒ 本对象**不出现** `android.graphics.Path` / `Canvas`。
 * 所有 `Math.random()` → [ShanShuiPrng.global.rnd()]；所有 `Noise.noise()` → [ShanShuiNoise.noise]。
 * 所有 `randChoice` → [ShanShuiUtil.randChoice]；所有 `wtrand` → [ShanShuiUtil.wtrand]。
 * 上游 `poly()`/`stroke()` 返回 SVG 字符串 → 本对象返回 [List]<[ShanShuiContour]>。
 * 所有点坐标已加 xoff/yoff 偏移（**偏移在 Float64 里加完再量化到 Float32**，与上游同序）。
 */

package com.nasmusic.tv.visualizer.qianli.port

/**
 * **屋顶题字**的轮廓标记（上游 `roof` 的 `<text>` 元素，`:2929-2954`）。
 *
 * ⛔ **上游没有「图元」这个概念** —— 上游 `roof` 直接往 SVG 字符串里拼一段 `<text>`：
 * `font-size = hei*0.6`、`style='fill:rgba(100,100,100,0.9)'`、`text-anchor='middle'`、
 * `transform='translate(mp[0]+xoff, mp[1]+yoff) rotate(adeg)'`，内容是 **「Pizza Hut」**。
 *
 * port 的轮廓模型只有「点列 + 填充 + 描边 + 线宽」⇒ 只能表达其中一部分：
 * | 上游 `<text>` 属性 | port 的表达 |
 * |---|---|
 * | `translate(x, y)` | 轮廓的**唯一那个点** = 题字锚点 |
 * | `fill:rgba(100,100,100,0.9)` | 轮廓的 `fill` |
 * | `font-size = hei*0.6` | 轮廓的 `width`（上游没有别的「字号」位置可放） |
 * | `rotate(adeg)` | ⛔ **无处安放**（见 [ShanShuiArch.roof] 的说明） |
 * | 文本内容「Pizza Hut」 | ⛔ **无处安放**（轮廓模型没有「文本」字段） |
 *
 * ⇒ 渲染器侧**看不到**这段题字（它只会把这条轮廓当成一个 1 点的退化路径画），
 * 但「题字存在 / 在哪 / 多大 / 什么颜色」全部保留下来，且**可与上游逐位对账**。
 */
const val SHANSHUI_TAG_ARCH_TEXT = "archText"

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
     *    ⚠️ 上游**不传** `rot`/`tra`/`wei`/`dec` ⇒ 走 box 自己的缺省（0.7 / true / 3 / 返回 `[]`）。
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
        //   ⚠️ 上游**不传** `rot` / `tra` / `wei` / `dec`
        //      ⇒ `rot` = box 自己的缺省 0.7、`tra` = true、`wei` = 3、`dec` = 返回 `[]`
        canv.addAll(
            box(
                xoff, yoff, h1, (wid * 2) / 3, BOX_ROT, per,
                tra = true, bot = false, wei = BOX_WEI,
                dec = { _: DecArgs -> emptyList() },
            )
        )

        // 上游 :3024-3031（seg 的抽取发生在实参求值时 ⇒ 必须在调用 rail 之前抽）
        canv.addAll(
            rail(
                xoff, yoff, seed, RAIL_HEI, wid, RAIL_ROT, per * 2, railSeg(), RAIL_WEI,
                tra = true, fro = false,
            )
        )

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
        canv.addAll(
            rail(
                xoff, yoff, seed, RAIL_HEI, wid, RAIL_ROT, per * 2, railSeg(), RAIL_WEI,
                tra = false, fro = true,
            )
        )

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

    // ─── 上游 arch02 / arch03 / arch04（照抄） ───────────────────

    /**
     * 上游 [Arch.arch02]（`:3061-3119`）：**多层楼阁**。
     *
     * 移植状态：**照抄**（改造-①/④/⑤）。
     *
     * ## 逐层结构
     * ```
     * hoff = 0
     * for (i = 0; i < sto; i++) {                 // sto = 层数
     *   box(xoff, yoff-hoff, {tra:false, hei, wid:wid*0.85^i, rot, wei:1.5, per,
     *        dec: a => deco(sty, {...a, hsp:HSP[sty], vsp:VSP[sty]})})
     *   rai ? rail(xoff, yoff-hoff, i*0.2, {wid:wid*0.85^i*1.1, hei:hei/2, per, rot,
     *        wei:0.5, tra:false}) : []
     *   pla = (sto == 1 && rnd() < 1/3) ? [1, "Pizza Hut"] : undefined   // 1 次抽取
     *   roof(xoff, yoff-hoff-hei, {hei, wid:wid*0.9^i, rot, wei:1.5, per, pla})
     *   hoff += hei * 1.5
     * }
     * ```
     *
     * ## 与上游的差异
     * - `seed` **收下但不用**（上游也不用；`rail` 拿到的是 `i*0.2`，见文件头说明）。
     * - `rot` / `per` / `sto` / `sty` / `rai` 全部**按上游语义生效**：
     *   `rot` 决定屋面的镜像（`rot < 0.5` ⇒ 上游 `flip`）、`sto` 是层数、
     *   `sty` 是立面装饰样式、`rai` 决定是否叠栏杆。
     * - 上游的 `<text>` 题字被表达成一条带 [SHANSHUI_TAG_ARCH_TEXT] 标记的轮廓
     *   （见该常量的说明）。
     *
     * @param sto 上游的**层数**（缺省 3）。
     * @param sty 上游的立面装饰样式（1 / 2 / 3；缺省 1）。
     * @param rai 上游是否叠栏杆（缺省 false）。
     */
    fun kind2(
        xoff: Double, yoff: Double, seed: Double,
        hei: Double, wid: Double, rot: Double, per: Double,
        sto: Double, sty: Double, rai: Boolean,
    ): List<ShanShuiContour> {
        val canv = ArrayList<ShanShuiContour>()
        var hoff = 0.0
        var i = 0
        while (i < sto) {
            val wi = wid * Math.pow(0.85, i.toDouble())

            // 上游 :3076-3092
            canv.addAll(
                box(
                    xoff, yoff - hoff, hei, wi, rot, per,
                    tra = false, bot = true, wei = 1.5,
                    dec = { a ->
                        deco(
                            sty.toInt(), a,
                            hsp = decoHsp(sty.toInt()), vsp = decoVsp(sty.toInt()),
                        )
                    },
                )
            )

            // 上游 :3093-3102
            if (rai) {
                canv.addAll(
                    rail(
                        xoff, yoff - hoff, i * 0.2,
                        hei = hei / 2, wid = wi * 1.1, rot = rot, per = per,
                        seg = RAIL_SEG_DEFAULT, wei = 0.5,
                        tra = false, fro = true,
                    )
                )
            }

            // 上游 :3103-3106 —— **1 次抽取**（`sto == 1` 时才抽）
            var pla: Pair<Int, String>? = null
            if (sto == 1.0 && ShanShuiPrng.global.rnd() < 1.0 / 3.0) {
                pla = 1 to "Pizza Hut"
            }

            // 上游 :3107-3114
            canv.addAll(
                roof(
                    xoff, yoff - hoff - hei, hei, wid * Math.pow(0.9, i.toDouble()),
                    rot = rot, per = per, cor = ROOF_COR_DEFAULT, wei = 1.5, pla = pla,
                )
            )

            // 上游 :3116
            hoff += hei * 1.5
            i++
        }
        return canv
    }

    /**
     * 上游 [Arch.arch03]（`:3120-3163`）：**塔**。
     *
     * 移植状态：**照抄**（改造-①/④/⑤）。
     *
     * ## 逐层结构
     * ```
     * hoff = 0
     * for (i = 0; i < sto; i++) {                 // sto = 层数（缺省 7）
     *   box(xoff, yoff-hoff, {tra:false, hei, wid:wid*0.85^i, rot, wei:1.5, per:per/2,
     *        dec: a => deco(1, {...a, hsp:[1,4], vsp:[1,2]})})
     *   rail(xoff, yoff-hoff, i*0.2, {seg:5, wid:wid*0.85^i*1.1, hei:hei/2, per:per/2,
     *        rot, wei:0.5, tra:false})
     *   pagroof(xoff, yoff-hoff-hei, {hei:hei*1.5, wid:wid*0.9^i, rot, wei:1.5, per})
     *   hoff += hei * 1.5
     * }
     * ```
     *
     * ## 与上游的差异
     * - `seed` **收下但不用**（上游也不用）。
     * - 上游 `pagroof` 的 `rot` 是**死参数**（上游 `pagroof` 读了但从不用）⇒ port 同样不用。
     */
    fun kind3(
        xoff: Double, yoff: Double, seed: Double,
        hei: Double, wid: Double, rot: Double, per: Double, sto: Double,
    ): List<ShanShuiContour> {
        val canv = ArrayList<ShanShuiContour>()
        var hoff = 0.0
        var i = 0
        while (i < sto) {
            val wi = wid * Math.pow(0.85, i.toDouble())

            // 上游 :3133-3143
            canv.addAll(
                box(
                    xoff, yoff - hoff, hei, wi, rot, per / 2,
                    tra = false, bot = true, wei = 1.5,
                    dec = { a -> deco(1, a, hsp = intArrayOf(1, 4), vsp = intArrayOf(1, 2)) },
                )
            )

            // 上游 :3144-3152
            canv.addAll(
                rail(
                    xoff, yoff - hoff, i * 0.2,
                    hei = hei / 2, wid = wi * 1.1, rot = rot, per = per / 2,
                    seg = 5, wei = 0.5,
                    tra = false, fro = true,
                )
            )

            // 上游 :3153-3159
            canv.addAll(
                pagroof(
                    xoff, yoff - hoff - hei, hei * 1.5, wid * Math.pow(0.9, i.toDouble()),
                    rot = rot, per = per, cor = PAGROOF_COR_DEFAULT, sid = PAGROOF_SID_DEFAULT,
                    wei = 1.5,
                )
            )

            // 上游 :3160
            hoff += hei * 1.5
            i++
        }
        return canv
    }

    /**
     * 上游 [Arch.arch04]（`:3164-3207`）：**拱桥**（塔式，带斜撑）。
     *
     * 移植状态：**照抄**（改造-①/④/⑤）。
     *
     * ## 逐层结构
     * ```
     * hoff = 0
     * for (i = 0; i < sto; i++) {                 // sto = 层数（缺省 2）
     *   box(xoff, yoff-hoff, {tra:true, hei, wid:wid*0.85^i, rot, wei:1.5, per:per/2,
     *        dec: a => []})
     *   rail(xoff, yoff-hoff, i*0.2, {seg:3, wid:wid*0.85^i*1.2, hei:hei/3, per:per/2,
     *        rot, wei:0.5, tra:true})
     *   pagroof(xoff, yoff-hoff-hei, {hei:hei*1, wid:wid*0.9^i, rot, wei:1.5, per})
     *   hoff += hei * 1.2
     * }
     * ```
     *
     * ## 与上游的差异
     * - `seed` **收下但不用**（上游也不用）。
     * - 上游 `arch04` 的 `rail` **不传 `fro`** ⇒ 走 rail 自己的缺省 **true**
     *   ⇒ 点列 4 条、且 `tra == true` ⇒ 会抽 **1 次**「随机开口」的随机数。
     */
    fun kind4(
        xoff: Double, yoff: Double, seed: Double,
        hei: Double, wid: Double, rot: Double, per: Double, sto: Double,
    ): List<ShanShuiContour> {
        val canv = ArrayList<ShanShuiContour>()
        var hoff = 0.0
        var i = 0
        while (i < sto) {
            val wi = wid * Math.pow(0.85, i.toDouble())

            // 上游 :3177-3187
            canv.addAll(
                box(
                    xoff, yoff - hoff, hei, wi, rot, per / 2,
                    tra = true, bot = true, wei = 1.5,
                    dec = { _: DecArgs -> emptyList() },
                )
            )

            // 上游 :3188-3196
            canv.addAll(
                rail(
                    xoff, yoff - hoff, i * 0.2,
                    hei = hei / 3, wid = wi * 1.2, rot = rot, per = per / 2,
                    seg = 3, wei = 0.5,
                    tra = true, fro = true,
                )
            )

            // 上游 :3197-3203
            canv.addAll(
                pagroof(
                    xoff, yoff - hoff - hei, hei * 1.0, wid * Math.pow(0.9, i.toDouble()),
                    rot = rot, per = per, cor = PAGROOF_COR_DEFAULT, sid = PAGROOF_SID_DEFAULT,
                    wei = 1.5,
                )
            )

            // 上游 :3204
            hoff += hei * 1.2
            i++
        }
        return canv
    }

    // ─── private 辅助（上游组合体的零件） ───────────────────────

    /**
     * 上游 [box] 的 `dec` 回调实参（上游 `:2644-2651`）。
     *
     * 上游的 `dec` 收到的是一个对象字面量：
     * `{pul: [surf*wid*0.5, -hei], pur: [mid, -hei+per], pdl: [surf*wid*0.5, 0], pdr: [mid, per]}`。
     *
     * ⛔ 上游 `arch02` 的 `dec` 回调会再 `Object.assign` 上 `hsp`/`vsp`（`:3083-3091`）
     * ⇒ 本移植层把 `hsp`/`vsp` 单独传给 [deco]（等价，且不用再造一个对象）。
     */
    internal class DecArgs(
        val pul: DoubleArray,
        val pur: DoubleArray,
        val pdl: DoubleArray,
        val pdr: DoubleArray,
    )

    /**
     * 上游 [box]（`:2609-2687`）：**楼身**（通用件）。
     *
     * 移植状态：**照抄**（改造-①/④/⑤）。
     *
     * ## 几何（上游 `:2625-2651`）
     * `mid = -wid*0.5 + wid*rot`、`bmid = -wid*0.5 + wid*(1-rot)`；
     * 点列 = 左边柱（5 等分）+ 右边柱（5 等分）+（`bot` 时）两条底边 + 中柱（5 等分）
     * +（`tra` 时）两条反斜边 + 反斜中柱（5 等分）。
     *
     * ## 发射（与上游逐条对应）
     * - `tra = false` ⇒ **发**白底多边形（上游 `:2662-2669` 的 `if (!tra)`）⇒ 5 点。
     * - 每条点列发一次 `stroke(..., {col:"rgba(100,100,100,0.4)", noi:1, wid:wei, fun:()=>1})`
     *   ⇒ 轮廓的 `fill = stroke = rgba(100,100,100,0.4)`、`width = 1`（上游 `stroke` 的
     *   `out` 缺省 1）。
     * - `dec` 回调的返回值**追加进点列**（上游 `:2644-2651` 的 `ptlist.concat(dec(...))`）
     *   ⇒ 每条追加的点列也各发一条 stroke 轮廓。
     *
     * ⚠️ **本函数是通用件**：`arch01` 用的是缺省（`rot=0.7`/`tra=true`/`bot=false`/`wei=3`/
     *   `dec` 返回 `[]`），`arch02`/`arch03` 用 `tra=false` + `deco`，`arch04` 用 `tra=true`。
     */
    internal fun box(
        xoff: Double, yoff: Double,
        hei: Double, wid: Double, rot: Double, per: Double,
        tra: Boolean, bot: Boolean, wei: Double,
        dec: (DecArgs) -> List<MutableList<DoubleArray>>,
    ): List<ShanShuiContour> {
        val canv = ArrayList<ShanShuiContour>()

        // 上游 :2625-2626
        val mid = -wid * 0.5 + wid * rot
        val bmid = -wid * 0.5 + wid * (1 - rot)

        val ptlist = ArrayList<MutableList<DoubleArray>>()

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
        // 上游 :2635-2641
        if (tra) {
            if (bot) {
                ptlist.add(div(listOf(dpoint(-wid * 0.5, 0.0), dpoint(bmid, -per)), 5))
                ptlist.add(div(listOf(dpoint(wid * 0.5, 0.0), dpoint(bmid, -per)), 5))
            }
            ptlist.add(div(listOf(dpoint(bmid, -hei), dpoint(bmid, -per)), 5))
        }

        // 上游 :2643 —— `var surf = (rot < 0.5) * 2 - 1;`
        //   ⚠️⚠️ **JS 里 `(rot < 0.5)` 是布尔** ⇒ `true * 2 - 1 = +1`、`false * 2 - 1 = -1`
        //   ⇒ **rot < 0.5 ⇒ surf = +1**（⛔ 不是 −1！本移植层此前写反了）。
        //   后果：整个 `deco`（楼身立面装饰）被**左右镜像** ⇒ 与上游的坐标逐点不符
        //   （真机对账抓到：上游 x=+18、端口 x=−22；`arch04` 因上游 `dec` 返回空数组而无此差异）。
        val surf = if (rot < 0.5) 1.0 else -1.0
        ptlist.addAll(
            dec(
                DecArgs(
                    dpoint(surf * wid * 0.5, -hei),
                    dpoint(mid, -hei + per),
                    dpoint(surf * wid * 0.5, 0.0),
                    dpoint(mid, per),
                )
            )
        )

        // 上游 :2653-2659
        val polist = listOf(
            dpoint(-wid * 0.5, -hei),
            dpoint(wid * 0.5, -hei),
            dpoint(wid * 0.5, 0.0),
            dpoint(mid, per),
            dpoint(-wid * 0.5, 0.0),
        )

        // 上游 :2661-2669 —— `if (!tra)` 才发白底
        if (!tra) {
            canv.add(ShanShuiContour(polist.map { off(it, xoff, yoff) }, whiteFill, null, 0.0))
        }

        // 上游 :2671-2685
        for (pts in ptlist) {
            val ribbon = ShanShuiBrush.ribbon(
                pts.map { off(it, xoff, yoff) }, wei, { 1.0 }, 1.0, archStroke,
            )
            // ⚠️ 上游 `stroke()` 对空点列返回 ""（不发 poly）⇒ 这里也不发轮廓
            if (ribbon.isEmpty()) continue
            canv.add(ShanShuiContour(ribbon, archStroke, archStroke, 1.0))
        }
        return canv
    }

    /**
     * 上游 [deco]（`:2689-2751`）：**立面装饰**（窗/门图案）。
     *
     * 移植状态：**照抄**（改造-④/⑤：返回点列的列表，不返回 SVG）。
     *
     * ## 三种样式（上游 `:2704-2749`）
     * | `style` | 形状 | 说明 |
     * |---|---|---|
     * | 1 | `-\| \|-` | 两侧各一列竖棂（`mml→ml`、`mmr→mr`）+ 中间两根竖柱 |
     * | 2 | `\|\|\|\|` | `du`/`dd` 之间逐列连横（`mu→md`） |
     * | 3 | `\|##\|` | 两侧竖棂 + 中间两根横梁（`mml→mmr`、`mmu→mmd`） |
     *
     * ## 抽样的四根边界（上游 `:2699-2702`）
     * `dl = div([pul,pdl], vsp[1])`、`dr = div([pur,pdr], vsp[1])`、
     * `du = div([pul,pur], hsp[1])`、`dd = div([pdl,pdr], hsp[1])`。
     *
     * ⛔ 上游的怪癖照抄：`mrd` 取的是 **`dd[du.length-1-hsp[0]]`**（用的是 `du.length`，
     *   不是 `dd.length`）——本移植层保持一致（两者长度相同 ⇒ 无实际差别，但语义照抄）。
     */
    internal fun deco(
        style: Int,
        args: DecArgs,
        hsp: IntArray,
        vsp: IntArray,
    ): List<MutableList<DoubleArray>> {
        val plist = ArrayList<MutableList<DoubleArray>>()

        // 上游 :2699-2702
        val dl = div(listOf(args.pul, args.pdl), vsp[1])
        val dr = div(listOf(args.pur, args.pdr), vsp[1])
        val du = div(listOf(args.pul, args.pur), hsp[1])
        val dd = div(listOf(args.pdl, args.pdr), hsp[1])

        if (style == 1) {
            // 上游 :2705-2720
            val mlu = du[hsp[0]]
            val mru = du[du.size - 1 - hsp[0]]
            val mld = dd[hsp[0]]
            val mrd = dd[du.size - 1 - hsp[0]]

            var i = vsp[0]
            while (i < dl.size - vsp[0]) {
                val mml = div(listOf(mlu, mld), vsp[1])[i]
                val mmr = div(listOf(mru, mrd), vsp[1])[i]
                val ml = dl[i]
                val mr = dr[i]
                plist.add(div(listOf(mml, ml), 5))
                plist.add(div(listOf(mmr, mr), 5))
                i += vsp[0]
            }
            plist.add(div(listOf(mlu, mld), 5))
            plist.add(div(listOf(mru, mrd), 5))
        } else if (style == 2) {
            // 上游 :2722-2728
            var i = hsp[0]
            while (i < du.size - hsp[0]) {
                val mu = du[i]
                val md = dd[i]
                plist.add(div(listOf(mu, md), 5))
                i += hsp[0]
            }
        } else if (style == 3) {
            // 上游 :2730-2748
            val mlu = du[hsp[0]]
            val mru = du[du.size - 1 - hsp[0]]
            val mld = dd[hsp[0]]
            val mrd = dd[du.size - 1 - hsp[0]]

            var i = vsp[0]
            while (i < dl.size - vsp[0]) {
                val mml = div(listOf(mlu, mld), vsp[1])[i]
                val mmr = div(listOf(mru, mrd), vsp[1])[i]
                val mmu = div(listOf(mlu, mru), vsp[1])[i]
                val mmd = div(listOf(mld, mrd), vsp[1])[i]

                val ml = dl[i]
                val mr = dr[i]
                plist.add(div(listOf(mml, mmr), 5))
                plist.add(div(listOf(mmu, mmd), 5))
                i += vsp[0]
            }
            plist.add(div(listOf(mlu, mld), 5))
            plist.add(div(listOf(mru, mrd), 5))
        }
        return plist
    }

    /**
     * 上游 [rail]（`:2753-2838`）：**栏杆**（通用件）。
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
     * 1. **竖栏**（`:2795-2820`）：`ptlist[i][j]` 与 `ptlist[对侧][j % 对侧长度]` 连成 3 点短线
     *    （`poly(..., {fil:"none", str:"rgba(100,100,100,0.5)", wid:2})`），
     *    两端各按 `Noise.noise` 上下抖动 `hei`，短线首点再随机横移（**每条抽 1 次随机**）。
     * 2. **栏身**（`:2822-2836`）：每条点列发一次
     *    `stroke(..., {col:"rgba(100,100,100,0.5)", noi:0.5, wid:wei, fun:()=>1})`。
     *
     * ## ⛔ 上游的怪癖照抄：「随机开口」**切两次**
     * 上游 `:2789-2791` 的 `(open + ptlist.length) % ptlist.length` **恒等于 `open`**
     * ⇒ 同一条点列被 `slice(0,-1)` **两次** ⇒ 少 **2** 个点（不是 1 个）。
     */
    internal fun rail(
        xoff: Double, yoff: Double, seed: Double,
        hei: Double, wid: Double, rot: Double, per: Double, seg: Int, wei: Double,
        tra: Boolean, fro: Boolean,
    ): List<ShanShuiContour> {
        // 上游 :2766-2767
        val mid = -wid * 0.5 + wid * rot
        val bmid = -wid * 0.5 + wid * (1 - rot)
        val ptlist = ArrayList<MutableList<DoubleArray>>()

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
            val ribbon = ShanShuiBrush.ribbon(
                pts.map { off(it, xoff, yoff) }, wei, { 1.0 }, 0.5, railStroke,
            )
            if (ribbon.isEmpty()) continue
            canv.add(ShanShuiContour(ribbon, railStroke, railStroke, 1.0))
        }
        return canv
    }

    /**
     * 上游 [roof]（`:2840-2956`）：**中式曲面屋顶**（通用件）。
     *
     * 移植状态：**照抄**（改造-①/④/⑤）。
     *
     * ## 几何（上游 `:2850-2900`）
     * - `opf(ptlist)`：`rot < 0.5` ⇒ 调上游的 `flip`（`:2526-2540`）**把每个点的 x 取反**
     *   （`axis = 0` ⇒ `x' = 0 - (x - 0) = -x`）；否则原样返回。
     * - `rrot = rot < 0.5 ? 1 - rot : rot`；
     *   `mid = -wid*0.5 + wid*rrot`、`bmid = -wid*0.5 + wid*(1-rrot)`（⛔ **上游算了但从不用**）、
     *   `quat = (mid + wid*0.5)*0.5 - mid`。
     * - 6 条点列（各 `div(..., 5)`）+ 1 个 5 点白底多边形。
     *
     * ## 发射（与上游逐条对应）
     * 1. **白底**（`:2911`）：`poly(polist, {str:"none", fil:"white"})` ⇒ 5 点。
     * 2. **6 条描边**（`:2913-2927`）：`stroke(..., {col:"rgba(100,100,100,0.4)", noi:1,
     *    wid:wei, fun:()=>1})`。
     * 3. **题字**（`:2929-2954`，仅当 `pla[0] == 1`）：上游拼一段 `<text>`（见
     *    [SHANSHUI_TAG_ARCH_TEXT]）。⚠️ 上游的 `rotate(adeg)`（题字角度）在 port 的
     *    轮廓数据模型里**无处安放** ⇒ 只保留「锚点 + 字号 + 颜色」。
     */
    internal fun roof(
        xoff: Double, yoff: Double,
        hei: Double, wid: Double, rot: Double, per: Double, cor: Double, wei: Double,
        pla: Pair<Int, String>?,
    ): List<ShanShuiContour> {
        val canv = ArrayList<ShanShuiContour>()

        // 上游 :2850-2857
        val flip = rot < 0.5
        val rrot = if (rot < 0.5) 1 - rot else rot

        // 上游 :2859-2861
        val mid = -wid * 0.5 + wid * rrot
        // ⛔ 上游 `:2860` 的 `bmid` 算了但**从不被使用** ⇒ 这里也不保留
        val quat = (mid + wid * 0.5) * 0.5 - mid

        /** 上游 `opf`（`:2850-2856`）：`rot < 0.5` ⇒ `flip`（把每个点的 x 取反）。 */
        fun opf(pts: List<DoubleArray>): List<DoubleArray> =
            if (flip) pts.map { doubleArrayOf(-it[0], it[1]) } else pts

        val ptlist = ArrayList<MutableList<DoubleArray>>()

        // 上游 :2864-2873
        ptlist.add(
            div(
                opf(
                    listOf(
                        dpoint(-wid * 0.5 + quat, -hei - per / 2),
                        dpoint(-wid * 0.5 + quat * 0.5, -hei / 2 - per / 4),
                        dpoint(-wid * 0.5 - cor, 0.0),
                    )
                ),
                5,
            )
        )
        // 上游 :2874-2883
        ptlist.add(
            div(
                opf(
                    listOf(
                        dpoint(mid + quat, -hei),
                        dpoint((mid + quat + wid * 0.5) / 2, -hei / 2),
                        dpoint(wid * 0.5 + cor, 0.0),
                    )
                ),
                5,
            )
        )
        // 上游 :2884-2893
        ptlist.add(
            div(
                opf(
                    listOf(
                        dpoint(mid + quat, -hei),
                        dpoint(mid + quat / 2, -hei / 2 + per / 2),
                        dpoint(mid + cor, per),
                    )
                ),
                5,
            )
        )
        // 上游 :2895-2896
        ptlist.add(div(opf(listOf(dpoint(-wid * 0.5 - cor, 0.0), dpoint(mid + cor, per))), 5))
        ptlist.add(div(opf(listOf(dpoint(wid * 0.5 + cor, 0.0), dpoint(mid + cor, per))), 5))
        // 上游 :2898-2900
        ptlist.add(
            div(opf(listOf(dpoint(-wid * 0.5 + quat, -hei - per / 2), dpoint(mid + quat, -hei))), 5)
        )

        // 上游 :2902-2911 —— 白底
        val polist = opf(
            listOf(
                dpoint(-wid * 0.5, 0.0),
                dpoint(-wid * 0.5 + quat, -hei - per / 2),
                dpoint(mid + quat, -hei),
                dpoint(wid * 0.5, 0.0),
                dpoint(mid, per),
            )
        )
        canv.add(ShanShuiContour(polist.map { off(it, xoff, yoff) }, whiteFill, null, 0.0))

        // 上游 :2913-2927 —— 6 条描边
        for (pts in ptlist) {
            val ribbon = ShanShuiBrush.ribbon(
                pts.map { off(it, xoff, yoff) }, wei, { 1.0 }, 1.0, archStroke,
            )
            if (ribbon.isEmpty()) continue
            canv.add(ShanShuiContour(ribbon, archStroke, archStroke, 1.0))
        }

        // 上游 :2929-2954 —— 题字（仅 `pla[0] == 1`）
        if (pla != null && pla.first == 1) {
            var pp = opf(
                listOf(
                    dpoint(mid + quat / 2, -hei / 2 + per / 2),
                    dpoint(-wid * 0.5 + quat * 0.5, -hei / 2 - per / 4),
                )
            )
            // 上游 :2934-2936
            if (pp[0][0] > pp[1][0]) {
                pp = listOf(pp[1], pp[0])
            }
            // 上游 :2937 —— PolyTools.midPt(pp)
            val mp = midPtD(pp)
            // 上游 :2938-2939 —— 角度（⛔ port 的轮廓模型无处安放 ⇒ 只算不用）
            @Suppress("UNUSED_VARIABLE")
            val adeg = (Math.atan2(pp[1][1] - pp[0][1], pp[1][0] - pp[0][0]) * 180) / Math.PI
            // 上游 :2940-2953 的 <text> ⇒ port 的等价表达（见 SHANSHUI_TAG_ARCH_TEXT）
            canv.add(
                ShanShuiContour(
                    listOf(floatArrayOf((mp[0] + xoff).toFloat(), (mp[1] + yoff).toFloat())),
                    archTextFill,
                    null,
                    hei * 0.6,
                    SHANSHUI_TAG_ARCH_TEXT,
                )
            )
        }
        return canv
    }

    /**
     * 上游 [pagroof]（`:2958-3000`）：**攒尖顶**（通用件）。
     *
     * 移植状态：**照抄**（改造-①/④/⑤）。
     *
     * ## 几何（上游 `:2968-2980`）
     * `sid` 根椽子（缺省 4）；`fx = wid*((i/(sid-1)) - 0.5)`、
     * `fy = per*(1 - |i/(sid-1) - 0.5|*2)`、`fxx = (wid+cor)*((i/(sid-1)) - 0.5)`。
     * 点列 = 每两根椽子之间一条「连接线」（2 点）+ 每根椽子本身（3 点）⇒ 共 `2*sid-1` 条。
     *
     * ## 发射（与上游逐条对应）
     * 1. **白底**（`:2982`）：`poly(polist, {str:"none", fil:"white"})` ⇒ `1 + sid` 点。
     * 2. **每条点列一条描边**（`:2983-2997`）：`stroke(div(ptlist[i], 5).map(...), {...})`。
     *
     * ⛔ 上游 `pagroof` 的 `rot` 是**死参数**（读了但从不用）⇒ port 同样不用。
     */
    internal fun pagroof(
        xoff: Double, yoff: Double,
        hei: Double, wid: Double, rot: Double, per: Double, cor: Double, sid: Int, wei: Double,
    ): List<ShanShuiContour> {
        val canv = ArrayList<ShanShuiContour>()
        val ptlist = ArrayList<MutableList<DoubleArray>>()
        // 上游 :2969
        val polist = ArrayList<DoubleArray>()
        polist.add(dpoint(0.0, -hei))

        // 上游 :2971-2980
        for (i in 0 until sid) {
            val fx = wid * ((i * 1.0) / (sid - 1) - 0.5)
            val fy = per * (1 - Math.abs((i * 1.0) / (sid - 1) - 0.5) * 2)
            val fxx = (wid + cor) * ((i * 1.0) / (sid - 1) - 0.5)
            if (i > 0) {
                // ⚠️ 上游 `ptlist[ptlist.length-1][2]` 是**同一个点对象**（不是副本）
                ptlist.add(
                    mutableListOf(
                        ptlist[ptlist.size - 1][2],
                        dpoint(fxx, fy),
                    )
                )
            }
            ptlist.add(
                mutableListOf(
                    dpoint(0.0, -hei),
                    dpoint(fx * 0.5, (-hei + fy) * 0.5),
                    dpoint(fxx, fy),
                )
            )
            polist.add(dpoint(fxx, fy))
        }

        // 上游 :2982 —— 白底
        canv.add(ShanShuiContour(polist.map { off(it, xoff, yoff) }, whiteFill, null, 0.0))

        // 上游 :2983-2997
        for (pts in ptlist) {
            val ribbon = ShanShuiBrush.ribbon(
                div(pts, 5).map { off(it, xoff, yoff) }, wei, { 1.0 }, 1.0, archStroke,
            )
            if (ribbon.isEmpty()) continue
            canv.add(ShanShuiContour(ribbon, archStroke, archStroke, 1.0))
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
    internal const val BOX_ROT = 0.7

    /** 上游 `box` 的默认 `wei`（`:2617`）。 */
    internal const val BOX_WEI = 3.0

    /** 上游 `rail` 的默认 `rot`（`:2757`）——`arch01` 不给 rail 传 rot ⇒ 用它自己的默认值。 */
    internal const val RAIL_ROT = 0.7

    /** 上游 `rail` 的默认 `wei`（`:2760`）。 */
    internal const val RAIL_WEI = 1.0

    /** 上游 `rail` 的默认 `seg`（`:2759`）——`arch02` 不给 rail 传 seg ⇒ 用它自己的默认值。 */
    internal const val RAIL_SEG_DEFAULT = 4

    /** 上游 `roof` 的默认 `cor`（`:2846`）——`arch02` 不给 roof 传 cor ⇒ 用它自己的默认值。 */
    internal const val ROOF_COR_DEFAULT = 5.0

    /** 上游 `pagroof` 的默认 `cor`（`:2964`）。 */
    internal const val PAGROOF_COR_DEFAULT = 10.0

    /** 上游 `pagroof` 的默认 `sid`（`:2965`）。 */
    internal const val PAGROOF_SID_DEFAULT = 4

    /** 上游 `arch01` 给两条 rail 的 `hei`（`:3027` / `:3052`）。 */
    private const val RAIL_HEI = 10.0

    /** 上游 `Man.man` 内部的抽取次数（见 [burnMan] 的逐项核对）。 */
    private const val MAN_DRAWS = 18

    /**
     * 上游 `fil: "white"` 对应的白色填充（上游 hut/box/roof/pagroof 的白底发射用）。
     *
     * 移植来源：上游 `:2566` / `:2667` / `:2911` / `:2982`。移植状态：**照抄**（改造-④：字符串 → 纯数据）。
     */
    private val whiteFill: ShanShuiColor = ShanShuiColor(255, 255, 255, 1.0)

    /**
     * 草屋两侧描边灰 —— 上游 hut 的 `poly(..., {str:"rgba(100,100,100,0.3)", wid:2})`（`:2572`）。
     *
     * 移植来源：上游 `:2572` / `:2579`。移植状态：**照抄**（改造-④：字符串 → 纯数据）。
     */
    private val archOutline: ShanShuiColor = ShanShuiColor.rgba(100, 100, 100, 0.3)

    /**
     * 建筑体描边灰 —— 上游 box/roof/pagroof 的
     * `stroke(..., {col:"rgba(100,100,100,0.4)", ...})`（`:2677` / `:2919` / `:2989`）。
     *
     * 移植来源：上游 `:2677` / `:2919` / `:2989`。移植状态：**照抄**（改造-④：字符串 → 纯数据）。
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
     * 屋顶题字色 —— 上游 `roof` 的 `<text ... style='fill:rgba(100,100,100,0.9)'>`（`:2944`）。
     *
     * 移植来源：上游 `:2944`。移植状态：**照抄**（改造-④：字符串 → 纯数据）。
     */
    private val archTextFill: ShanShuiColor = ShanShuiColor.rgba(100, 100, 100, 0.9)

    /**
     * 上游 `arch02` 的 `dec` 回调里的 `hsp`（上游 `:3087`：`[[], [1,5], [1,5], [1,4]][sty]`）。
     *
     * ⚠️ 上游的**下标 0 是 `[]`**（`sty == 0` 会让 `hsp[0]`/`hsp[1]` 越界 ⇒ 上游自己会崩）
     * ⇒ port 只在 `sty ∈ {1,2,3}` 时被调用（上游的 `randChoice([1,2,3])` 保证）。
     */
    internal fun decoHsp(sty: Int): IntArray = when (sty) {
        1 -> intArrayOf(1, 5)
        2 -> intArrayOf(1, 5)
        3 -> intArrayOf(1, 4)
        else -> intArrayOf(1, 5)
    }

    /**
     * 上游 `arch02` 的 `dec` 回调里的 `vsp`（上游 `:3088`：`[[], [1,2], [1,2], [1,3]][sty]`）。
     */
    internal fun decoVsp(sty: Int): IntArray = when (sty) {
        1 -> intArrayOf(1, 2)
        2 -> intArrayOf(1, 2)
        3 -> intArrayOf(1, 3)
        else -> intArrayOf(1, 2)
    }

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

    /**
     * 上游 [PolyTools.midPt]（`:208-222`）的 **Float64** 复刻（n 点中点）。
     *
     * ⚠️ **先除后加**的 reduce 顺序（上游 `:218`：`v[0] / plist.length + acc[0]`）必须照抄。
     */
    private fun midPtD(plist: List<DPoint>): DPoint {
        val n = plist.size
        var ax = 0.0
        var ay = 0.0
        for (v in plist) {
            ax = v[0] / n + ax
            ay = v[1] / n + ay
        }
        return doubleArrayOf(ax, ay)
    }
}
