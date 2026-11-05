/**
 * 山石图元层 —— 山、平山、山脚装饰、远山、石、水。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * [mountain]/[flatMount]/[distMount]/[rock]/[water] 移植状态：**照抄**；[flatDec] 移植状态：**改造-③**（内部 tree04/05/08 未移植 ⇒ 替换为 [ShanShuiTree.kind1]/[kind2]/[kind3] 简化版）。
 *
 * [mountain] 照抄上游 [Mount.mountain] `:1804-2081`（山体，10 行 × 50 点，reso=[10,50]）。
 *   上游默认值：hei=100+rand*400, wid=400+rand*200, tex=200, veg=true, ret=0, col=undefined。
 *   ⚠️ 内部 [vegetate]（上游 `:1838-1852` 闭包）共 8 次调用：RIM/TOP/MIDDLE/BOTTOM/BOTT_ARCH/TOP_ARCH/TRANSM/BOTT_ROCK；
 *   ⚠️ 上游 TRANSM（transmissionTower01）未移植 ⇒ 该次调用整体移除（growthRule 不消耗 rnd，无副作用）；
 *   ⚠️ 上游 [Arch.arch02]/[arch03]/[arch04] 未移植 ⇒ 替换为 [ShanShuiArch.kind2]（改造-③，参数映射见下）；
 *   ⚠️ ret≠0 时上游返回 `[ptlist]`（二维），port 适配为扁平化 [ptlist.flatten]（改造-①）。
 *   ⚠️ **实参求值序**：上游的 JS 对象字面量是**左到右**求值 ⇒ arch02/arch03/arch04/rock 的
 *     实参顺序与 port 的 Kotlin 调用顺序必须逐项对齐（见各处注释里的上游行号）。
 *
 * [flatMount] 照抄上游 [Mount.flatMount] `:2083-2232`（平山）。
 *   上游默认值：hei=40+rand*400, wid=400+rand*200, tex=80, cho=0.5, ret=0；reso=[5,50]。
 *   ⚠️ 上游 `ret` 声明后从未使用 ⇒ port **已删**该形参（改造-⑤）。
 *
 * [flatDec] 照抄上游 [Mount.flatDec] `:2234-2367`（山脚装饰）。
 *   ⚠️ 内部 tree04/05/08 未移植 ⇒ 替换为 [ShanShuiTree.kind1]/[kind2]/[kind3]（改造-③）。
 *
 * [distMount] 照抄上游 [Mount.distMount] `:2369-2431`（远山）。
 *   上游默认值：hei=300, len=2000, seg=5。
 *   ✅ 内部 [PolyTools.triangulate]（`:2419-2428`）**已移植**（见 [ShanShuiPoly]）：
 *   每块先发 1 条「块多边形填色」（`:2413-2417`），再对同一块做三角剖分
 *   （`area: 100, convex: true, optimize: false`），并为**每个**三角形发 1 条独立轮廓
 *   （`fil = str = getCol(中点)`、`wid: 1` ⇒ fill = stroke = 该灰、width = 1）。
 *   ⚠️ `triangulate` 是纯几何 ⇒ **不消耗随机数**（移植前后抽取次数不变 = 0）。
 *
 * [rock] 照抄上游 [Mount.rock] `:2433-2522`（石）。
 *   上游默认值：hei=80, wid=100, tex=40, ret=0, sha=10；reso=[10,50]。
 *   ⚠️ 上游 `ret` 声明后从未使用 ⇒ port **已删**该形参（改造-⑤）。
 *
 * [water] 照抄上游 [water] `:3667-3704`（水波，仅 38 行）。
 *   上游默认值：hei=2, len=800, clu=10。
 *   ⚠️ 上游 `seed` 声明后从未使用 ⇒ port 保留形参但忽略（照抄）。
 *
 * ⛔ port 包零 Android 依赖 ⇒ 本对象**不出现** `android.graphics.Path` / `Canvas`。
 * 所有 `Math.random()` → [ShanShuiPrng.global.rnd()]；所有 `Noise.noise()` → [ShanShuiNoise.noise]。
 * 所有 `randChoice` → [ShanShuiUtil.randChoice]。
 *
 * ⚠️ 改造-⑤（轮廓容器）：上游 `poly()`/`stroke()` 返回 SVG 字符串，每次发射是**一条独立折线**，
 * 带自己的 `fil`/`str`/`wid`。port 早期版本把多条发射拍平成一个扁平点列 ⇒ 渲染器拿到的点列里
 * 多条折线首尾相连（画出来是横贯全屏的乱线），且每笔样式全丢 ⇒ E50 §5「按坡面行号染色」
 * 与「白文留白」无法实现。本版本起统一返回 [List]<[ShanShuiContour]>（轮廓列表，坐标已加 xoff/yoff）。
 *
 * ## 样式换算规则（上游 → 轮廓）
 * - 上游 `poly(plist, {fil, str, wid})` ⇒ `fill` = `fil`（`"none"` → `null`）、
 *   `stroke` = `str`（`"none"` → `null`）、`width` = `wid`（缺省 `0`）。
 * - 上游 `stroke(ptlist, {col, …})` 内部等价 `poly(vtxlist, {fil: col, str: col, wid: out})`，
 *   `out` 缺省 `1` ⇒ `fill` = `stroke` = `col`、`width` = `1`。
 *   ⚠️ `stroke` 的**笔宽**（`wid` 形参，如 3 / 1.5 / `sha`）已烘进 ribbon 几何，不是轮廓的 `width`。
 * - 上游 `blob(x, y, {col, …})` 内部等价 `poly(plist, {fil: col, str: col, wid: 0})`
 *   ⇒ `fill` = `stroke` = `col`、`width` = `0`。
 *
 * ## 已知偏差（接受，不修）
 * - **D14：坐标精度** —— `Point = FloatArray` ⇒ 坐标是 **Float32**，上游是 Float64。
 * - ~~**D15：`MEM.planmtx` 的全局性**~~ —— **已对齐**（2026-11-05）：`MEM.planmtx` 改为
 *   [ShanShuiPlanner] 的**全局**计数器表（绝对下标 + 上游的 `|| 0` / `undefined + 1 = NaN`
 *   语义逐字复现）⇒ 见 [ShanShuiPlanner] 的文件头。
 * - **TRANSM 未移植**（上游 `:2041-2057`）：上游的 TRANSM 植被规则极罕见（阈值 0.002），
 *   命中时会在该处消耗随机数 ⇒ port 与上游的随机流在**极端情况下**会分叉。
 * - **tree04/05/08 未移植**（上游 `:2255` / `:2283` / `:2305` / `:2325` / `:2337`）：
 *   `flatDec` 用 kind1/kind2/kind3 顶替（改造-③）⇒ 树形与上游不同，但**抽取次数对齐**。
 * - **[PolyTools.triangulate] 已移植**（上游 `:2419-2428`）：远山现在有「晶面层次」的三角化填色
 *   （见 [ShanShuiPoly]；移植前远景是一条平涂的锯齿带）。
 * - **[ShanShuiArch] 是简化实现**：上游 arch01/02/03/04 内部的 `stroke`/`rail`/`pagroof`/`Man.man`
 *   每次调用要抽几十到上千次随机，port 的 kind1/kind2 **一次都不抽** ⇒
 *   **调用点之后的随机流会与上游分叉**（详见 [ShanShuiArch] 的文件头）。
 */

package com.nasmusic.tv.visualizer.qianli.port

object ShanShui {

    /**
     * 照抄上游 [Mount.mountain]（`:1804-2081`）：山体。
     * 上游默认值：hei=100+rand*400, wid=400+rand*200, tex=200, veg=true, ret=0, col=undefined。
     * 移植状态：**照抄**（改造-①：SVG 字符串 → 轮廓列表；改造-③：TRANSM 调用移除、arch02/03/04 → [ShanShuiArch.kind2]；
     * 改造-⑤：返回 [List]<[ShanShuiContour]> 而非扁平点列）。
     *
     * 构建 10 行 × 50 点的山脊点阵（余弦包络 × 噪声），自下而上逐行收窄。
     * 发射顺序（与上游一致）：RIM 树 → 白色背景 → 描边 → 山脚 → 皴 → TOP 树 → MIDDLE/BOTTOM 树 → 建筑 → 石。
     * `veg=false` 时跳过 MIDDLE 植被。
     *
     * ⚠️ 改造-⑤：上游 `ret≠0` 只为把原始点阵 `ptlist` 交给调用方 ⇒ port 拆成独立函数
     * [mountainGrid]（门禁 G-PORT-5 用），本函数不再需要 `ret` 形参。
     *
     * 植被替换映射（改造-③，上游未移植件）：
     * - RIM/TOP（tree02）→ [ShanShuiTree.kind2]（clu=2 / 5）
     * - MIDDLE（tree01）→ [ShanShuiTree.kind1]（ht=((h+y)/h)*70 后 ht*0.3+rnd*ht*0.7）
     * - BOTTOM（tree03）→ [ShanShuiTree.kind3]（ht=((h+y)/h)*120 后 ht*0.5+rnd*ht*0.5，ben={pow(x*bc,bp)}）
     * - BOTT_ARCH（arch02/04）→ [ShanShuiArch.kind2]（tt==1：hei=10, wid=normRand(40,70), sto/sty=randChoice）
     *   （tt==2：hei=15, wid=30, rot=0.7）
     * - TOP_ARCH（arch03）→ [ShanShuiArch.kind2]（hei=10, wid=40+rnd*20, sto=randChoice([5,7])）
     * - BOTT_ROCK → 本对象 [rock]（hei/wid=20+rnd*20, sha=2）
     */
    fun mountain(
        xoff: Double,
        yoff: Double,
        seed: Double,
        hei: Double,
        wid: Double,
        tex: Double,
        veg: Boolean,
        col: ShanShuiColor?,
    ): List<ShanShuiContour> {
        val canv = ArrayList<ShanShuiContour>()
        // 上游 `:1817-1836`：ptlist（10 行 × 50 点）—— 抽成 [mountainPtlist]（改造-⑤）
        val ptlist = mountainPtlist(yoff, seed, hei, wid)
        val h = hei
        // 上游 `:1838-1852`：vegetate 闭包（捕获 ptlist/canv）→ port 为局部函数
        fun vegetate(
            treeFunc: (Double, Double) -> List<ShanShuiContour>,
            growthRule: (Int, Int) -> Boolean,
            proofRule: (List<ShanShuiPath.Point>, Int) -> Boolean,
        ) {
            val veglist = ArrayList<ShanShuiPath.Point>()
            for (i in 0 until ptlist.size) {
                for (j in 0 until ptlist[i].size) {
                    if (growthRule(i, j)) {
                        veglist.add(floatArrayOf(ptlist[i][j][0], ptlist[i][j][1]))
                    }
                }
            }
            for (i in 0 until veglist.size) {
                if (proofRule(veglist, i)) {
                    canv.addAll(treeFunc(veglist[i][0].toDouble(), veglist[i][1].toDouble()))
                }
            }
        }
        // RIM（上游 `:1853-1873`）
        vegetate(
            { x, y ->
                // 上游 `:1858-1860`：col 的 alpha 由 Noise.noise(0.01x, 0.01y) 调制
                //   （noise ∈ [0,1] ⇒ alpha ∈ [0.35, 0.65]；上游 `.toFixed(3)`）
                //   ⚠️ 用**未偏移**的植被坐标 x,y（上游 treeFunc 的入参就是未偏移值）
                val rimCol = ShanShuiColor.rgba(
                    100,
                    100,
                    100,
                    ShanShuiUtil.toFixed(
                        ShanShuiNoise.noise(0.01 * x, 0.01 * y) * 0.5 * 0.3 + 0.5,
                        3,
                    ),
                )
                ShanShuiTree.kind2(
                    x + xoff, y + yoff - 5.0,
                    16.0, 8.0, 2.0, rimCol, 0.5,
                )
            },
            { i, j ->
                val ns = ShanShuiNoise.noise(j * 0.1, seed)
                i == 0 && ns * ns * ns < 0.1 && Math.abs(ptlist[i][j][1].toDouble()) / h > 0.2
            },
            { _, _ -> true },
        )
        // WHITE BG（上游 `:1875-1881`：poly(ptlist[0].concat([[0, reso[0]*4]]), {fil:"white", str:"none"})）
        // ⇒ fill=白 / stroke=null / wid=0（上游未给 wid ⇒ 缺省 0）
        val whiteBg = ArrayList<ShanShuiPath.Point>(ptlist[0].size + 1)
        whiteBg.addAll(offList(ptlist[0], xoff, yoff))
        whiteBg.add(floatArrayOf(xoff.toFloat(), (yoff + 10.0 * 4.0).toFloat()))
        canv.add(ShanShuiContour(whiteBg, whiteFill, null, 0.0))
        // 山体底缘（**派生数据**：上游没有这个图元；⛔ 不改上游语义，上面那条三角形白底照抄保留）。
        // 上游的 WHITE BG 在纸底上**隐形**（`:4028` 整幅 SVG `mix-blend-mode:multiply`
        // ⇒ 纯白 × 米白纸底 = 纸底）⇒ 上游视觉上的「山体下缘」其实是**这条不规则的底行折线**。
        // E50 把山体涂成实色（青绿）后，白底的下缘会露出来 ⇒ 渲染器按这条底缘着色 ⇒ 无硬直边。
        val bodyBase = offList(ptlist[ptlist.size - 1], xoff, yoff)
        canv.add(ShanShuiContour(bodyBase, null, null, 0.0, SHANSHUI_TAG_BODY_BASE))
        // OUTLINE（上游 `:1882-1888`：stroke(ptlist[0], {col:"rgba(100,100,100,0.3)", noi:1, wid:3})）
        // ⇒ stroke 内部 poly(vtxlist, {fil:col, str:col, wid:out=1})；笔宽 3 已烘进 ribbon 几何
        canv.add(
            ShanShuiContour(
                ShanShuiBrush.ribbon(
                    offList(ptlist[0], xoff, yoff),
                    3.0, ::strokeFun, 1.0, ShanShuiColor.outline,
                ),
                ShanShuiColor.outline, ShanShuiColor.outline, 1.0,
            )
        )
        // FOOT（上游 `:1890`：foot(ptlist, {xof, yof})；上游 foot 内部两轮发射）
        // 轮 1（上游 `:1779-1786`）：poly(ftlist[i], {fil:"white", str:"none"}) ⇒ fill=白 / stroke=null / wid=0
        // 轮 2（上游 `:1787-1800`）：stroke(ftlist[j], {col:"rgba(100,100,100,0.1~0.2)", wid:1})
        //   ⇒ fill=str=该灰 / poly 宽 = out = 1（笔宽 1 已烘进 ribbon 几何）
        for (ft in ShanShuiBrush.foot(ptlist, xoff, yoff)) {
            val ftOff = offList(ft, xoff, yoff)
            canv.add(ShanShuiContour(ftOff, whiteFill, null, 0.0))
            val footCol = ShanShuiColor.rgba(100, 100, 100, 0.1 + ShanShuiPrng.global.rnd() * 0.1)
            canv.add(
                ShanShuiContour(
                    ShanShuiBrush.ribbon(ftOff, 1.0, ::strokeFun, 0.5, footCol),
                    footCol, footCol, 1.0,
                )
            )
        }
        // TEXTURE（皴，上游 `:1891-1897`：texture(ptlist, {xof, yof, tex, sha: randChoice([0,0,0,0,5]), col})）
        //   ⚠️ 上游 mountain 的 `col` 缺省是 `undefined` ⇒ texture 用**默认 col 函数**
        //   （`:668-673`：每条皴线抽 1 次随机，alpha = rnd*0.3）⇒ 每座山少抽 tex 次会明显偏黑。
        //   ⚠️ 上游的非 null `col` 会被 texture 当**函数**调用（必抛错）⇒ port 退化为「所有皴线同色」。
        val sha = ShanShuiUtil.randChoice(listOf(0, 0, 0, 0, 5)).toDouble()
        val texColFn: (Double) -> ShanShuiColor = if (col == null) {
            { _ -> ShanShuiBrush.defaultCunCol() }
        } else {
            val fixed = col
            { _ -> fixed }
        }
        val texlist = ShanShuiBrush.cun(ptlist, tex, sha)
        // SHADE（上游 `:720-730`：sha 非 0 时 stride=2，stroke(..., {col:"rgba(100,100,100,0.1)", wid:sha})）
        // ⇒ fill=str=灰0.1 / poly 宽 = out = 1（笔宽 sha 已烘进 ribbon 几何）
        if (sha != 0.0) {
            for (j in 0 until texlist.size step 2) {
                val shadeCol = ShanShuiColor.rgba(100, 100, 100, 0.1)
                canv.add(
                    ShanShuiContour(
                        ShanShuiBrush.ribbon(
                            offList(texlist[j], xoff, yoff),
                            sha, ::strokeFun, 0.5, shadeCol,
                        ),
                        shadeCol, shadeCol, 1.0,
                    )
                )
            }
        }
        // TEXTURE（上游 `:731-739`：起点=sha、stride=1+sha，stroke(..., {col, wid:1.5})）
        // ⇒ fill=str=该条的 col / poly 宽 = out = 1（笔宽 1.5 已烘进 ribbon 几何）
        // ⚠️ 上游的 `col` 是**逐条求值**的函数（`col(j / texlist.length)`）⇒ 每条抽 1 次随机，
        //   且**先于** stroke 内部的 n0 抽取（stroke 的实参先求值）。
        for (j in sha.toInt() until texlist.size step (1 + sha.toInt())) {
            val lineCol = texColFn(j.toDouble() / texlist.size)
            canv.add(
                ShanShuiContour(
                    ShanShuiBrush.ribbon(
                        offList(texlist[j], xoff, yoff),
                        1.5, ::strokeFun, 0.5, lineCol,
                    ),
                    lineCol, lineCol, 1.0,
                )
            )
        }
        // TOP（上游 `:1899-1916`）
        vegetate(
            { x, y ->
                // 上游 `:1903-1906`：alpha 由 Noise.noise(0.01x, 0.01y) 调制（0.35~0.65）
                val topCol = ShanShuiColor.rgba(
                    100,
                    100,
                    100,
                    ShanShuiUtil.toFixed(
                        ShanShuiNoise.noise(0.01 * x, 0.01 * y) * 0.5 * 0.3 + 0.5,
                        3,
                    ),
                )
                ShanShuiTree.kind2(
                    x + xoff, y + yoff,
                    16.0, 8.0, 5.0, topCol, 0.5,
                )
            },
            { i, j ->
                val ns = ShanShuiNoise.noise(i * 0.1, j * 0.1, seed + 2.0)
                ns * ns * ns < 0.1 && Math.abs(ptlist[i][j][1].toDouble()) / h > 0.5
            },
            { _, _ -> true },
        )
        if (veg) {
            // MIDDLE
            vegetate(
                { x, y ->
                    var ht = ((h + y) / h) * 70.0
                    ht = ht * 0.3 + ShanShuiPrng.global.rnd() * ht * 0.7
                    // 上游 `:1928-1930`：alpha 由 Noise.noise(0.01x, 0.01y) 调制（0.15~0.45）
                    val midCol = ShanShuiColor.rgba(
                        100,
                        100,
                        100,
                        ShanShuiUtil.toFixed(
                            ShanShuiNoise.noise(0.01 * x, 0.01 * y) * 0.5 * 0.3 + 0.3,
                            3,
                        ),
                    )
                    ShanShuiTree.kind1(
                        x + xoff, y + yoff,
                        ht, ShanShuiPrng.global.rnd() * 3.0 + 1.0,
                        midCol, 0.5,
                    )
                },
                { i, j ->
                    val ns = ShanShuiNoise.noise(i * 0.2, j * 0.05, seed)
                    j % 2 == 1 &&
                        ns * ns * ns * ns < 0.012 &&
                        Math.abs(ptlist[i][j][1].toDouble()) / h < 0.3
                },
                { veglist, i ->
                    var counter = 0
                    for (j in 0 until veglist.size) {
                        if (
                            i != j &&
                            Math.pow(
                                veglist[i][0].toDouble() - veglist[j][0].toDouble(),
                                2.0,
                            ) +
                            Math.pow(
                                veglist[i][1].toDouble() - veglist[j][1].toDouble(),
                                2.0,
                            ) < 30 * 30
                        ) {
                            counter++
                        }
                        if (counter > 2) return@vegetate true
                    }
                    false
                },
            )
        }
        // BOTTOM
        vegetate(
            { x, y ->
                var ht = ((h + y) / h) * 120.0
                ht = ht * 0.5 + ShanShuiPrng.global.rnd() * ht * 0.5
                val bc = ShanShuiPrng.global.rnd() * 0.1
                val bp = 1.0
                // 上游 `:1973-1975`：alpha 由 Noise.noise(0.01x, 0.01y) 调制（0.15~0.45）
                val botCol = ShanShuiColor.rgba(
                    100,
                    100,
                    100,
                    ShanShuiUtil.toFixed(
                        ShanShuiNoise.noise(0.01 * x, 0.01 * y) * 0.5 * 0.3 + 0.3,
                        3,
                    ),
                )
                ShanShuiTree.kind3(
                    x + xoff, y + yoff,
                    ht, 5.0, { xv -> Math.pow(xv * bc, bp) },
                    botCol, 0.5,
                )
            },
            { i, j ->
                val ns = ShanShuiNoise.noise(i * 0.2, j * 0.05, seed)
                (j == 0 || j == ptlist[i].size - 1) && ns * ns * ns * ns < 0.012
            },
            { _, _ -> true },
        )
        // BOTT ARCH（上游 `:1990-2020`）
        vegetate(
            { x, y ->
                // 上游 `:1993`：tt（1 次抽取）
                val tt = ShanShuiUtil.randChoice(listOf(0, 0, 1, 1, 1, 2))
                when (tt) {
                    1 -> {
                        // 上游 `:1995-2000`：Arch.arch02(x + xoff, y + yoff, seed, {wid, sto, rot, sty})
                        //   ⚠️ 第 3 实参是 mountain 自己的 `seed`（**不抽随机**）
                        //   ⚠️ 对象字面量的求值序 = wid → sto → rot → sty（JS 左到右）
                        val awid = ShanShuiUtil.normRand(40.0, 70.0) // :1996
                        val asto = ShanShuiUtil.randChoice(listOf(1, 2, 2, 3)).toDouble() // :1997
                        val arot = ShanShuiPrng.global.rnd() // :1998
                        val asty = ShanShuiUtil.randChoice(listOf(1, 2, 3)).toDouble() // :1999
                        ShanShuiArch.kind2(
                            x + xoff, y + yoff, seed,
                            10.0, awid, arot, 5.0, asto, asty, false,
                        )
                    }
                    2 -> {
                        // 上游 `:2002-2004`：Arch.arch04(x + xoff, y + yoff, seed, {sto})
                        val asto = ShanShuiUtil.randChoice(listOf(1, 1, 1, 2, 2)).toDouble() // :2003
                        ShanShuiArch.kind2(
                            x + xoff, y + yoff, seed,
                            15.0, 30.0, 0.7, 5.0, asto, 1.0, false,
                        )
                    }
                    else -> emptyList()
                }
            },
            { i, j ->
                val ns = ShanShuiNoise.noise(i * 0.2, j * 0.05, seed + 10.0)
                i != 0 &&
                    (j == 1 || j == ptlist[i].size - 2) &&
                    ns * ns * ns * ns < 0.008
            },
            { _, _ -> true },
        )
        // TOP ARCH（上游 `:2021-2039`）
        vegetate(
            { x, y ->
                // 上游 `:2024-2027`：Arch.arch03(x + xoff, y + yoff, seed, {sto, wid})
                //   ⚠️ 第 3 实参是 mountain 自己的 `seed`（**不抽随机**）
                //   ⚠️ 对象字面量的求值序 = sto → wid（JS 左到右）
                val asto = ShanShuiUtil.randChoice(listOf(5, 7)).toDouble() // :2025
                val awid = 40.0 + ShanShuiPrng.global.rnd() * 20.0 // :2026
                ShanShuiArch.kind2(
                    x + xoff, y + yoff, seed,
                    10.0, awid, 0.7, 5.0, asto, 1.0, false,
                )
            },
            { i, j ->
                i == 1 &&
                    Math.abs(j - ptlist[i].size / 2.0) < 1.0 &&
                    ShanShuiPrng.global.rnd() < 0.02
            },
            { _, _ -> true },
        )
        // TRANSM — 上游 transmissionTower01 未移植 ⇒ 整块移除（growthRule 不消耗 rnd）
        // BOTT ROCK（上游 `:2059-2074`）
        vegetate(
            { x, y ->
                // 上游 `:2062-2066`：Mount.rock(x + xoff, y + yoff, seed, {wid, hei, sha})
                //   ⚠️ 第 3 实参是 mountain 自己的 `seed`（**不抽随机**）
                //   ⚠️ 对象字面量的求值序 = wid → hei（JS 左到右）
                val rwid = 20.0 + ShanShuiPrng.global.rnd() * 20.0 // :2063
                val rhei = 20.0 + ShanShuiPrng.global.rnd() * 20.0 // :2064
                rock(x + xoff, y + yoff, seed, rhei, rwid, 40.0, 2.0)
            },
            { i, j ->
                (j == 0 || j == ptlist[i].size - 1) && ShanShuiPrng.global.rnd() < 0.1
            },
            { _, _ -> true },
        )
        return canv
    }

    /**
     * 上游 [Mount.mountain] 的**点阵构建段**（`:1817-1836`）—— 抽出为独立函数（改造-⑤）。
     *
     * 移植状态：**照抄**（照抄上游 `:1822-1836` 的 10 行 × 50 列余弦包络点阵）。
     *
     * ⚠️ 上游 `mountain` 的 `ret≠0` 分支（`:2078-2080`）只为把这份原始点阵交给调用方
     * ⇒ port 拆成公开函数 [mountainGrid]，供门禁 G-PORT-5「10 行 × 50 列 / 无 NaN」直接取用。
     *
     * @param xoff 世界坐标 x 偏移（**本函数已加到返回的点列上**；[mountain] 内部用的是未偏移的原始点阵）
     * @param yoff 世界坐标 y 偏移（同时参与上游 `hoff` 的随机累积）
     * @return 10 行 × 50 列的点阵（世界坐标）
     */
    fun mountainGrid(
        xoff: Double,
        yoff: Double,
        seed: Double,
        hei: Double,
        wid: Double,
    ): List<List<ShanShuiPath.Point>> {
        val raw = mountainPtlist(yoff, seed, hei, wid)
        return raw.map { row -> row.map { off(it, xoff, yoff) } }
    }

    /**
     * 上游 [Mount.mountain] 的原始点阵（**未加 xoff/yoff 偏移**）—— [mountain] 与 [mountainGrid] 共用。
     *
     * 移植来源：上游 `:1822-1836`。移植状态：**照抄**（改造-⑤：抽出为独立私有函数）。
     *
     * ⚠️ 上游的 `texture()`/`foot()` 收到的就是这份**未偏移**点阵（偏移在 poly/stroke 发射时才加），
     * 故 [mountain] 必须用本函数而非 [mountainGrid]。
     */
    private fun mountainPtlist(
        yoff: Double,
        seed: Double,
        hei: Double,
        wid: Double,
    ): List<List<ShanShuiPath.Point>> {
        val reso0 = 10
        val reso1 = 50
        var hoff = 0.0
        val ptlist = ArrayList<List<ShanShuiPath.Point>>(reso0)
        for (j in 0 until reso0) {
            hoff += ShanShuiPrng.global.rnd() * yoff / 100.0
            val row = ArrayList<ShanShuiPath.Point>(reso1)
            for (i in 0 until reso1) {
                val x = (i.toDouble() / reso1 - 0.5) * Math.PI
                var y = Math.cos(x)
                y *= ShanShuiNoise.noise(x + 10.0, j * 0.15, seed)
                val p = 1.0 - j.toDouble() / reso0
                row.add(
                    floatArrayOf(
                        (x / Math.PI * wid * p).toFloat(),
                        (-y * hei * p + hoff).toFloat(),
                    )
                )
            }
            ptlist.add(row)
        }
        return ptlist
    }

    /**
     * 照抄上游 [Mount.flatMount]（`:2083-2232`）：平山。
     * 上游默认值：hei=40+rand*400, wid=400+rand*200, tex=80, cho=0.5, ret=0；reso=[5,50]。
     * 移植状态：**照抄**（改造-①：SVG 字符串 → 轮廓列表；改造-⑤：每条轮廓自带 fill/stroke/width）。
     *
     * 构建 5 行 × 50 点的平山点阵（cos(x*2)+1 包络，底部按 cho 截平）。
     * 发射顺序（与上游一致）：白色背景 → 描边 → 皴 → grlist 白底 → grlist 描边 → [flatDec]。
     * `flat` 收集底部截平段；`grlist1/grlist2` 为左右轮廓，向两端各延伸 3 点后等分（divide），
     * 再施加 v-噪声横移（上游 `:2186-2190`）。
     * ⚠️ 上游 `ret` 声明后从未使用 ⇒ port 删掉该形参（改造-⑤）。
     */
    fun flatMount(
        xoff: Double,
        yoff: Double,
        seed: Double,
        hei: Double,
        wid: Double,
        tex: Double,
        cho: Double,
    ): List<ShanShuiContour> {
        val canv = ArrayList<ShanShuiContour>()
        val ptlist = ArrayList<MutableList<ShanShuiPath.Point>>()
        val flat = ArrayList<MutableList<ShanShuiPath.Point>>()
        val reso0 = 5
        val reso1 = 50
        var hoff = 0.0
        for (j in 0 until reso0) {
            hoff += ShanShuiPrng.global.rnd() * yoff / 100.0
            ptlist.add(ArrayList())
            flat.add(ArrayList())
            for (i in 0 until reso1) {
                val x = (i.toDouble() / reso1 - 0.5) * Math.PI
                var y = Math.cos(x * 2.0) + 1.0
                y *= ShanShuiNoise.noise(x + 10.0, j * 0.1, seed)
                val p = 1.0 - (j.toDouble() / reso0) * 0.6
                var nx = x / Math.PI * wid * p
                var ny = -y * hei * p + hoff
                val hh = 100.0
                if (ny < -hh * cho + hoff) {
                    ny = -hh * cho + hoff
                    if (flat[flat.size - 1].size % 2 == 0) {
                        flat[flat.size - 1].add(floatArrayOf(nx.toFloat(), ny.toFloat()))
                    }
                } else {
                    if (flat[flat.size - 1].size % 2 == 1) {
                        flat[flat.size - 1].add(
                            ptlist[ptlist.size - 1][ptlist[ptlist.size - 1].size - 1],
                        )
                    }
                }
                ptlist[ptlist.size - 1].add(floatArrayOf(nx.toFloat(), ny.toFloat()))
            }
        }
        // WHITE BG（上游 `:2127-2133`：poly(ptlist[0].concat([[0, reso[0]*4]]), {fil:"white", str:"none"})）
        // ⇒ fill=白 / stroke=null / wid=0
        val whiteBg = ArrayList<ShanShuiPath.Point>(ptlist[0].size + 1)
        whiteBg.addAll(offList(ptlist[0], xoff, yoff))
        whiteBg.add(floatArrayOf(xoff.toFloat(), (yoff + reso0 * 4.0).toFloat()))
        canv.add(ShanShuiContour(whiteBg, whiteFill, null, 0.0))
        // OUTLINE（上游 `:2134-2140`：stroke(ptlist[0], {col:"rgba(100,100,100,0.3)", noi:1, wid:3})）
        // ⇒ fill=str=灰0.3 / poly 宽 = out = 1（笔宽 3 已烘进 ribbon 几何）
        canv.add(
            ShanShuiContour(
                ShanShuiBrush.ribbon(
                    offList(ptlist[0], xoff, yoff),
                    3.0, ::strokeFun, 1.0, ShanShuiColor.outline,
                ),
                ShanShuiColor.outline, ShanShuiColor.outline, 1.0,
            )
        )
        // TEXTURE（上游 `:2143-2155`：texture(ptlist, {xof, yof, tex, wid: 2, dis: 自定义})）
        // ⇒ stroke(texlist[j], {col, wid:2}) ⇒ fill=str=该条的 col / poly 宽 = out = 1（笔宽 2 已烘进 ribbon 几何）
        // ⚠️ 上游**不传 col** ⇒ 用默认 col 函数（每条皴线抽 1 次随机，alpha = rnd*0.3）
        // ⚠️ 上游**传了自定义 dis**（0.1 / 0.9 两端分布）⇒ 皴线贴着两端，不是默认的三分分布
        val texlist = ShanShuiBrush.cun(ptlist, tex, 0.0, ::flatMountDis)
        for (j in 0 until texlist.size) {
            // 上游 `:731-739`（sha=0 ⇒ 从 j=0 起、步长 1）：col 逐条求值（每条抽 1 次随机）
            val lineCol = ShanShuiBrush.defaultCunCol(j.toDouble() / texlist.size)
            canv.add(
                ShanShuiContour(
                    ShanShuiBrush.ribbon(
                        offList(texlist[j], xoff, yoff),
                        2.0, ::strokeFun, 0.5, lineCol,
                    ),
                    lineCol, lineCol, 1.0,
                )
            )
        }
        // grlist（上游 `:2155-2185`）
        val grlist1 = ArrayList<ShanShuiPath.Point>()
        val grlist2 = ArrayList<ShanShuiPath.Point>()
        for (i in 0 until flat.size step 2) {
            if (flat[i].size >= 2) {
                grlist1.add(flat[i][0])
                grlist2.add(flat[i][flat[i].size - 1])
            }
        }
        if (grlist1.isEmpty()) {
            return canv
        }
        var wb = floatArrayOf(grlist1[0][0], grlist2[0][0])
        for (i in 0 until 3) {
            val p = 0.8 - i * 0.2
            grlist1.add(
                0,
                floatArrayOf(
                    (wb[0].toDouble() * p).toFloat(),
                    (grlist1[0][1].toDouble() - 5.0).toFloat(),
                )
            )
            grlist2.add(
                0,
                floatArrayOf(
                    (wb[1].toDouble() * p).toFloat(),
                    (grlist2[0][1].toDouble() - 5.0).toFloat(),
                )
            )
        }
        wb = floatArrayOf(grlist1[grlist1.size - 1][0], grlist2[grlist2.size - 1][0])
        for (i in 0 until 3) {
            val p = 0.6 - i * i * 0.1
            grlist1.add(
                floatArrayOf(
                    (wb[0].toDouble() * p).toFloat(),
                    (grlist1[grlist1.size - 1][1].toDouble() + 1.0).toFloat(),
                )
            )
            grlist2.add(
                floatArrayOf(
                    (wb[1].toDouble() * p).toFloat(),
                    (grlist2[grlist2.size - 1][1].toDouble() + 1.0).toFloat(),
                )
            )
        }
        val d = 5
        val grlist1d = ShanShuiPath.divide(grlist1, d)
        val grlist2d = ShanShuiPath.divide(grlist2, d)
        val grlist = ArrayList<ShanShuiPath.Point>(grlist1d.size + grlist2d.size + 1)
        grlist.addAll(grlist1d.reversed())
        grlist.addAll(grlist2d)
        // 上游 `:2186`：`grlist1.reverse().concat(grlist2.concat([grlist1[0]]))`
        //   ⚠️ JS 的 `reverse()` **原地改写** grlist1 ⇒ 此时的 `grlist1[0]` 是**原末点**（不是原首点）。
        //   ⇒ 闭合点取 grlist1d 的**最后一个**点；取原首点会让平山的白色填充多边形不闭合，
        //     并在描边里多出一条从右下到左上的长斜线（横贯平山底部）。
        grlist.add(grlist1d.last())
        // v-噪声横移（上游 `:2186-2190`）
        for (i in 0 until grlist.size) {
            val v = (1.0 - Math.abs((i % d) - d / 2.0) / (d / 2.0)) * 0.12
            grlist[i][0] =
                (grlist[i][0].toDouble() * (1 - v + ShanShuiNoise.noise(grlist[i][1].toDouble() * 0.5) * v)).toFloat()
        }
        // WHITE BG（grlist，上游 `:2195-2201`：poly(grlist, {str:"none", fil:"white", wid:2})）
        // ⇒ fill=白 / stroke=null / wid=2
        canv.add(ShanShuiContour(offList(grlist, xoff, yoff), whiteFill, null, 2.0))
        // OUTLINE（grlist，上游 `:2202-2205`：stroke(grlist, {wid:3, col:"rgba(100,100,100,0.2)"})）
        // ⇒ fill=str=灰0.2 / poly 宽 = out = 1（笔宽 3 已烘进 ribbon 几何）
        val grCol = ShanShuiColor.rgba(100, 100, 100, 0.2)
        canv.add(
            ShanShuiContour(
                ShanShuiBrush.ribbon(offList(grlist, xoff, yoff), 3.0, ::strokeFun, 0.5, grCol),
                grCol, grCol, 1.0,
            )
        )
        // bound + flatDec（上游 `:2220-2231`）
        fun bound(plist: List<ShanShuiPath.Point>): ShanShuiPlanner.Bounds {
            var xmin = Double.NaN
            var xmax = Double.NaN
            var ymin = Double.NaN
            var ymax = Double.NaN
            for (i in 0 until plist.size) {
                val px = plist[i][0].toDouble()
                val py = plist[i][1].toDouble()
                if (xmin.isNaN() || px < xmin) xmin = px
                if (xmax.isNaN() || px > xmax) xmax = px
                if (ymin.isNaN() || py < ymin) ymin = py
                if (ymax.isNaN() || py > ymax) ymax = py
            }
            return ShanShuiPlanner.Bounds(xmin, xmax, ymin, ymax)
        }
        canv.addAll(flatDec(xoff, yoff, bound(grlist)))
        return canv
    }

    /**
     * 照抄上游 [Mount.flatDec]（`:2234-2367`）：山脚装饰。
     * 移植状态：**改造-③**（tree04/05/08 未移植 ⇒ 替换为 [ShanShuiTree.kind1]/[kind2]/[kind3]）；**改造-⑤**（返回轮廓列表）。
     *
     * 在 grbd 边界内随机撒石/树/拱桥。分支 tt=0..4：
     * 0=大石；1=横向树列 + 大石；2=单树 + 大石；3=塔形树列；4=横向树列。
     * 末尾固定撒 tree02 散点（50*rnd 棵）与 arch01（ts==1 且 tt!=4 时）。
     *
     * 树映射（改造-③）：tree08→[ShanShuiTree.kind2]（hei=60+rnd*40, clu=5）；
     * tree05→[ShanShuiTree.kind1]（hei=100+rnd*200）；
     * tree04→[ShanShuiTree.kind3]（hei=50, ben={0.0}）；
     * tree06→[ShanShuiTree.kind3]（hei=60+rnd*60）；
     * tree07→[ShanShuiTree.kind1]（hei=normRand(40,80)）；
     * tree02→[ShanShuiTree.kind2]（默认）；
     * arch01→[ShanShuiArch.kind1]（hei=normRand(80,100), wid=normRand(160,200), rot=0.7）。
     */
    fun flatDec(
        xoff: Double,
        yoff: Double,
        grbd: ShanShuiPlanner.Bounds,
    ): List<ShanShuiContour> {
        val canv = ArrayList<ShanShuiContour>()
        val tt = ShanShuiUtil.randChoice(listOf(0, 0, 1, 2, 3, 4))
        // 底部小石（上游 `:2239-2250`）
        var j = 0
        while (j < ShanShuiPrng.global.rnd() * 5.0) {
            // 上游 `:2240-2249`：实参求值序 = x(normRand) → y(normRand) → seed(rnd*100) → wid → hei
            val rx = xoff + ShanShuiUtil.normRand(grbd.xmin, grbd.xmax)
            val ry = yoff + (grbd.ymin + grbd.ymax) / 2 + ShanShuiUtil.normRand(-10.0, 10.0) + 10.0
            val rs = ShanShuiPrng.global.rnd() * 100.0
            val rwid = 10.0 + ShanShuiPrng.global.rnd() * 20.0
            val rhei = 10.0 + ShanShuiPrng.global.rnd() * 20.0
            canv.addAll(rock(rx, ry, rs, rhei, rwid, 40.0, 2.0))
            j++
        }
        // tree08 组（上游 `:2251-2263`）
        j = 0
        while (j < ShanShuiUtil.randChoice(listOf(0, 0, 1, 2))) {
            val xr = xoff + ShanShuiUtil.normRand(grbd.xmin, grbd.xmax)
            val yr = yoff + (grbd.ymin + grbd.ymax) / 2 + ShanShuiUtil.normRand(-5.0, 5.0) + 20.0
            var k = 0
            while (k < 2.0 + ShanShuiPrng.global.rnd() * 3.0) {
                canv.addAll(
                    ShanShuiTree.kind2(
                        xr + Math.min(Math.max(ShanShuiUtil.normRand(-30.0, 30.0), grbd.xmin), grbd.xmax),
                        yr,
                        60.0 + ShanShuiPrng.global.rnd() * 40.0, 8.0, 5.0,
                        ShanShuiColor.default, 0.5,
                    )
                )
                k++
            }
            j++
        }
        if (tt == 0) {
            // 大石（上游 `:2263-2276`）
            j = 0
            while (j < ShanShuiPrng.global.rnd() * 3.0) {
                // 上游 `:2265-2274`：实参求值序 = x(normRand) → y(normRand) → seed(rnd*100) → wid → hei
                val rx = xoff + ShanShuiUtil.normRand(grbd.xmin, grbd.xmax)
                val ry = yoff + (grbd.ymin + grbd.ymax) / 2 + ShanShuiUtil.normRand(-5.0, 5.0) + 20.0
                val rs = ShanShuiPrng.global.rnd() * 100.0
                val rwid = 50.0 + ShanShuiPrng.global.rnd() * 20.0
                val rhei = 40.0 + ShanShuiPrng.global.rnd() * 20.0
                canv.addAll(rock(rx, ry, rs, rhei, rwid, 40.0, 5.0))
                j++
            }
        }
        if (tt == 1) {
            // 横向树列（tree05）+ 大石（上游 `:2280-2305`）
            val pmin = ShanShuiPrng.global.rnd() * 0.5
            val pmax = ShanShuiPrng.global.rnd() * 0.5 + 0.5
            var xmin = grbd.xmin * (1 - pmin) + grbd.xmax * pmin
            var xmax = grbd.xmin * (1 - pmax) + grbd.xmax * pmax
            var i = xmin
            while (i < xmax) {
                canv.addAll(
                    ShanShuiTree.kind1(
                        xoff + i + 20.0 * ShanShuiUtil.normRand(-1.0, 1.0),
                        yoff + (grbd.ymin + grbd.ymax) / 2 + 20.0,
                        100.0 + ShanShuiPrng.global.rnd() * 200.0, 3.0,
                        ShanShuiColor.default, 0.5,
                    )
                )
                i += 30.0
            }
            j = 0
            while (j < ShanShuiPrng.global.rnd() * 4.0) {
                // 上游 `:2290-2299`：实参求值序 = x(normRand) → y(normRand) → seed(rnd*100) → wid → hei
                val rx = xoff + ShanShuiUtil.normRand(grbd.xmin, grbd.xmax)
                val ry = yoff + (grbd.ymin + grbd.ymax) / 2 + ShanShuiUtil.normRand(-5.0, 5.0) + 20.0
                val rs = ShanShuiPrng.global.rnd() * 100.0
                val rwid = 50.0 + ShanShuiPrng.global.rnd() * 20.0
                val rhei = 40.0 + ShanShuiPrng.global.rnd() * 20.0
                canv.addAll(rock(rx, ry, rs, rhei, rwid, 40.0, 5.0))
                j++
            }
        } else if (tt == 2) {
            // 单树（tree04）+ 大石（上游 `:2307-2328`）
            for (i in 0 until ShanShuiUtil.randChoice(listOf(1, 1, 1, 1, 2, 2, 3))) {
                val xr = ShanShuiUtil.normRand(grbd.xmin, grbd.xmax)
                val yr = (grbd.ymin + grbd.ymax) / 2
                canv.addAll(
                    ShanShuiTree.kind3(
                        xoff + xr, yoff + yr + 20.0,
                        50.0, 5.0, { _ -> 0.0 },
                        ShanShuiColor.default, 0.5,
                    )
                )
                j = 0
                while (j < ShanShuiPrng.global.rnd() * 2.0) {
                    // 上游 `:2307-2320`：实参求值序 = x(normRand) → y(normRand) → seed(j*i*rnd*100) → wid → hei
                    val rx = xoff + Math.max(
                        grbd.xmin,
                        Math.min(grbd.xmax, xr + ShanShuiUtil.normRand(-50.0, 50.0)),
                    )
                    val ry = yoff + yr + ShanShuiUtil.normRand(-5.0, 5.0) + 20.0
                    val rs = j.toDouble() * i.toDouble() * ShanShuiPrng.global.rnd() * 100.0
                    val rwid = 50.0 + ShanShuiPrng.global.rnd() * 20.0
                    val rhei = 40.0 + ShanShuiPrng.global.rnd() * 20.0
                    canv.addAll(rock(rx, ry, rs, rhei, rwid, 40.0, 5.0))
                    j++
                }
            }
        } else if (tt == 3) {
            // 塔形树列（tree06，上游 `:2330-2341`）
            for (i in 0 until ShanShuiUtil.randChoice(listOf(1, 1, 1, 1, 2, 2, 3))) {
                canv.addAll(
                    ShanShuiTree.kind3(
                        xoff + ShanShuiUtil.normRand(grbd.xmin, grbd.xmax),
                        yoff + (grbd.ymin + grbd.ymax) / 2,
                        60.0 + ShanShuiPrng.global.rnd() * 60.0, 5.0, { _ -> 0.0 },
                        ShanShuiColor.default, 0.5,
                    )
                )
            }
        } else if (tt == 4) {
            // 横向树列（tree07，上游 `:2343-2356`）
            val pmin = ShanShuiPrng.global.rnd() * 0.5
            val pmax = ShanShuiPrng.global.rnd() * 0.5 + 0.5
            var xmin = grbd.xmin * (1 - pmin) + grbd.xmax * pmin
            var xmax = grbd.xmin * (1 - pmax) + grbd.xmax * pmax
            var i = xmin
            while (i < xmax) {
                canv.addAll(
                    ShanShuiTree.kind1(
                        xoff + i + 20.0 * ShanShuiUtil.normRand(-1.0, 1.0),
                        yoff + (grbd.ymin + grbd.ymax) / 2 + ShanShuiUtil.normRand(-1.0, 1.0),
                        ShanShuiUtil.normRand(40.0, 80.0), 3.0,
                        ShanShuiColor.default, 0.5,
                    )
                )
                i += 20.0
            }
        }
        // tree02 散点（上游 `:2358-2362`）
        var i2 = 0
        while (i2 < 50.0 * ShanShuiPrng.global.rnd()) {
            canv.addAll(
                ShanShuiTree.kind2(
                    xoff + ShanShuiUtil.normRand(grbd.xmin, grbd.xmax),
                    yoff + ShanShuiUtil.normRand(grbd.ymin, grbd.ymax),
                    16.0, 8.0, 5.0,
                    ShanShuiColor.default, 0.5,
                )
            )
            i2++
        }
        // arch01（上游 `:2352-2364`）
        val ts = ShanShuiUtil.randChoice(listOf(0, 0, 0, 0, 1))
        if (ts == 1 && tt != 4) {
            // 上游 `:2354-2363`：Arch.arch01(x, y, Math.random(), {wid, hei, per})
            //   ⚠️ 实参求值序 = x(normRand) → y(无随机) → seed(rnd) → wid(normRand) → hei(normRand) → per(rnd)
            //   ⚠️ 对象字面量里的顺序是 **wid → hei → per**（JS 左到右）
            val ax = xoff + ShanShuiUtil.normRand(grbd.xmin, grbd.xmax)
            val ay = yoff + (grbd.ymin + grbd.ymax) / 2 + 20.0
            val asd = ShanShuiPrng.global.rnd()
            val awid = ShanShuiUtil.normRand(160.0, 200.0)
            val ahei = ShanShuiUtil.normRand(80.0, 100.0)
            val aper = ShanShuiPrng.global.rnd()
            canv.addAll(
                ShanShuiArch.kind1(
                    ax, ay, asd,
                    ahei, awid, 0.7, aper,
                )
            )
        }
        return canv
    }

    /**
     * 照抄上游 [Mount.distMount]（`:2369-2431`）：远山。
     * 上游默认值：hei=300, len=2000, seg=5。
     * 移植状态：**照抄**（改造-⑤：返回轮廓列表）。
     *
     * 构建 len/span/seg 列远山点阵（span=10）：每列先按正弦包络 + 噪声生成
     * seg+1 个山脊点，再在头部插入 seg/2+1 个谷底点。
     *
     * ## 发射（与上游逐条对应）
     * 1. **块多边形填色**（上游 `:2413-2417`）：`poly(ptlist[i], {fil: getCol(行末点), str:"none", wid:1})`
     *    ⇒ 一条轮廓：fill = 该灰、stroke = null（"none"）、width = 1。
     * 2. **三角剖分 + 逐三角形填色并描边**（上游 `:2419-2428`）：
     *    `PolyTools.triangulate(ptlist[i], {area:100, convex:true, optimize:false})`，
     *    每个三角形取中点 `m` ⇒ `getCol(m[0], m[1])` ⇒
     *    `poly(T[k], {fil: co, str: co, wid: 1})`
     *    ⇒ **每个三角形一条独立轮廓**：fill = stroke = 该灰、width = 1。
     *
     * ⛔ **PRNG 抽取次数 = 0**（上游的 `triangulate` 是纯几何，不碰 `Math.random`）——
     *   移植前后**不变**（门禁 G-PORT-10 断言）。
     * ⚠️ 上游的 `getCol` 闭包（`:2409-2412`）里的 `yoff` 是 **distMount 的入参**
     *   （所有块共用），**不是** chunk 的 y。
     */
    fun distMount(
        xoff: Double,
        yoff: Double,
        seed: Double,
        hei: Double,
        len: Double,
        seg: Double,
    ): List<ShanShuiContour> {
        val canv = ArrayList<ShanShuiContour>()
        val span = 10.0
        val ptlist = ArrayList<MutableList<ShanShuiPath.Point>>()
        for (i in 0 until (len / span / seg).toInt()) {
            val row = ArrayList<ShanShuiPath.Point>()
            for (j in 0 until seg.toInt() + 1) {
                val k = (i * seg + j).toDouble()
                row.add(
                    floatArrayOf(
                        (xoff + k * span).toFloat(),
                        (yoff - hei * ShanShuiNoise.noise(k * 0.05, seed) *
                            Math.pow(Math.sin(Math.PI * k / (len / span)), 0.5)).toFloat(),
                    )
                )
            }
            // 上游 `:2395` 的 `for (var j = 0; j < seg / 2 + 1; j++)` —— ⚠️ 条件是**浮点比较**
            //   （seg=5 ⇒ 3.5 ⇒ j=0,1,2,3 共 **4** 次；上游实测 block 0 = 4 底点 + 6 脊点 = 10 点）。
            //   ⛔ 写成 `0 until (seg/2+1).toInt()` 会截成 3 ⇒ 少一个底点（点序与上游不符）。
            var jb = 0
            while (jb < seg / 2.0 + 1.0) {
                val k = (i * seg + jb * 2).toDouble()
                row.add(
                    0,
                    floatArrayOf(
                        (xoff + k * span).toFloat(),
                        (yoff + 24.0 * ShanShuiNoise.noise(k * 0.05, 2.0, seed) *
                            Math.pow(Math.sin(Math.PI * k / (len / span)), 1.0)).toFloat(),
                    )
                )
                jb++
            }
            ptlist.add(row)
        }
        for (row in ptlist) {
            // ① 块多边形填色（上游 `:2413-2417`：poly(ptlist[i], {fil: getCol(行末点), str: "none", wid: 1})）
            // ⚠️ 改造-① 的「颜色计算省略」在改造-⑤ 后**不再需要** —— 轮廓自带 fill ⇒ 照抄上游 getCol
            val last = row[row.size - 1]
            val c = (ShanShuiNoise.noise(
                last[0].toDouble() * 0.02,
                last[1].toDouble() * 0.02,
                yoff,
            ) * 55 + 200).toInt()
            canv.add(ShanShuiContour(row, ShanShuiColor(c, c, c, 1.0), null, 1.0))
            // ② 三角剖分 + 逐三角形填色并描边（上游 `:2419-2428`）
            //   上游：var T = PolyTools.triangulate(ptlist[i], {area: 100, convex: true, optimize: false});
            //         for (var k = 0; k < T.length; k++) {
            //           var m = PolyTools.midPt(T[k]);
            //           var co = getCol(m[0], m[1]);
            //           canv += poly(T[k], { fil: co, str: co, wid: 1 });
            //         }
            //   ⇒ port 侧每个三角形发**一条独立轮廓**：fill = str = co、width = 1（上游 wid: 1）。
            // ⚠️ `getCol` 的 `yoff` 是 **distMount 的入参 yoff**（上游 `:2409-2412` 的闭包捕获），
            //   **不是** chunk 的 y，也不是该块自己的偏移（所有块共用同一个 yoff）。
            // ⚠️ `triangulate` 是**纯几何** ⇒ **不消耗随机数**（抽取次数与移植前一致 = 0）。
            // ⚠️ `m` 用 [ShanShuiPoly.midPtExact]（Double 精度）⇒ 与上游的 Float64 中点一致；
            //   顶点坐标本身是 Float32（port 的 D14 既有约定）。
            val tris = ShanShuiPoly.triangulate(
                row,
                area = 100.0,
                convex = true,
                optimize = false,
            )
            for (tri in tris) {
                val m = ShanShuiPoly.midPtExact(tri)
                val tc = (ShanShuiNoise.noise(m[0] * 0.02, m[1] * 0.02, yoff) * 55 + 200).toInt()
                val co = ShanShuiColor(tc, tc, tc, 1.0)
                canv.add(ShanShuiContour(tri, co, co, 1.0))
            }
        }
        return canv
    }

    /**
     * 照抄上游 [Mount.rock]（`:2433-2522`）：石。
     * 上游默认值：hei=80, wid=100, tex=40, ret=0, sha=10；reso=[10,50]。
     * 移植状态：**照抄**（改造-⑤：返回轮廓列表）。
     *
     * 构建 10 行 × 50 点的石形点阵（椭圆极坐标 + 噪声环带）。
     * 发射顺序（与上游一致）：白色背景 → 描边 → 皴（shade + texture）。
     * ⚠️ 上游 `ret` 声明后从未使用 ⇒ port 删掉该形参（改造-⑤）。
     */
    fun rock(
        xoff: Double,
        yoff: Double,
        seed: Double,
        hei: Double,
        wid: Double,
        tex: Double,
        sha: Double,
    ): List<ShanShuiContour> {
        val canv = ArrayList<ShanShuiContour>()
        val reso0 = 10
        val reso1 = 50
        val ptlist = ArrayList<MutableList<ShanShuiPath.Point>>()
        for (i in 0 until reso0) {
            val row = ArrayList<ShanShuiPath.Point>(reso1)
            val nslist = ArrayList<Double>(reso1)
            for (j in 0 until reso1) {
                nslist.add(ShanShuiNoise.noise(i.toDouble(), j * 0.2, seed))
            }
            ShanShuiUtil.loopNoise(nslist)
            for (j in 0 until reso1) {
                val a = (j.toDouble() / reso1) * Math.PI * 2.0 - Math.PI / 2.0
                var l = (wid * hei) /
                    Math.sqrt(Math.pow(hei * Math.cos(a), 2.0) + Math.pow(wid * Math.sin(a), 2.0))
                l *= 0.7 + 0.3 * nslist[j]
                val p = 1.0 - i.toDouble() / reso0
                var nx = Math.cos(a) * l * p
                var ny = -Math.sin(a) * l * p
                if (Math.PI < a || a < 0.0) ny *= 0.2
                ny += hei * (i.toDouble() / reso0) * 0.2
                row.add(floatArrayOf(nx.toFloat(), ny.toFloat()))
            }
            ptlist.add(row)
        }
        // WHITE BG（上游 `:2484-2490`：poly(ptlist[0].concat([[0, 0]]), {fil:"white", str:"none"})）
        // ⇒ fill=白 / stroke=null / wid=0
        val whiteBg = ArrayList<ShanShuiPath.Point>(ptlist[0].size + 1)
        whiteBg.addAll(offList(ptlist[0], xoff, yoff))
        whiteBg.add(floatArrayOf(xoff.toFloat(), yoff.toFloat()))
        canv.add(ShanShuiContour(whiteBg, whiteFill, null, 0.0))
        // OUTLINE（上游 `:2491-2497`：stroke(ptlist[0], {col:"rgba(100,100,100,0.3)", noi:1, wid:3})）
        // ⇒ fill=str=灰0.3 / poly 宽 = out = 1（笔宽 3 已烘进 ribbon 几何）
        canv.add(
            ShanShuiContour(
                ShanShuiBrush.ribbon(
                    offList(ptlist[0], xoff, yoff),
                    3.0, ::strokeFun, 1.0, ShanShuiColor.outline,
                ),
                ShanShuiColor.outline, ShanShuiColor.outline, 1.0,
            )
        )
        // TEXTURE（上游 `:2498-2516`：texture(ptlist, {tex, wid: 3, sha, col: rgba(180,180,180,0.3+rnd*0.3), dis: 0.15/0.85 两端分布})）
        // ⚠️ 上游传了**自定义 dis** 与 **自定义 col**（每条皴线抽 1 次随机）
        val texlist = ShanShuiBrush.cun(ptlist, tex, sha, ::rockDis)
        // SHADE（上游 `:720-730`：sha 非 0 时 stride=2，stroke(..., {col:"rgba(100,100,100,0.1)", wid:sha})）
        // ⇒ fill=str=灰0.1 / poly 宽 = out = 1（笔宽 sha 已烘进 ribbon 几何）
        if (sha != 0.0) {
            for (j in 0 until texlist.size step 2) {
                val shadeCol = ShanShuiColor.rgba(100, 100, 100, 0.1)
                canv.add(
                    ShanShuiContour(
                        ShanShuiBrush.ribbon(
                            offList(texlist[j], xoff, yoff),
                            sha, ::strokeFun, 0.5, shadeCol,
                        ),
                        shadeCol, shadeCol, 1.0,
                    )
                )
            }
        }
        // TEXTURE（上游 `:731-739`：起点=sha、stride=1+sha，stroke(..., {col, wid:3})）
        // ⇒ fill=str=该条的 col / poly 宽 = out = 1（笔宽 3 已烘进 ribbon 几何）
        for (j in sha.toInt() until texlist.size step (1 + sha.toInt())) {
            // 上游 `:2504-2508`：col 逐条求值（每条抽 1 次随机，alpha = 0.3 + rnd*0.3）
            val lineCol = rockTexCol()
            canv.add(
                ShanShuiContour(
                    ShanShuiBrush.ribbon(
                        offList(texlist[j], xoff, yoff),
                        3.0, ::strokeFun, 0.5, lineCol,
                    ),
                    lineCol, lineCol, 1.0,
                )
            )
        }
        return canv
    }

    /**
     * 照抄上游 [water]（`:3667-3704`）：水波（仅 38 行）。
     * 上游默认值：hei=2, len=800, clu=10。
     * 移植状态：**照抄**（改造-⑤：返回轮廓列表）。
     *
     * 构建 clu 条水平波线（sin 波 + 噪声 + 随机垂向漂移），仅对第 1 行起发射描边 stroke。
     * ⚠️ 上游 `seed` 声明后从未使用 ⇒ port 保留形参但忽略。
     */
    fun water(
        xoff: Double,
        yoff: Double,
        seed: Double,
        hei: Double,
        len: Double,
        clu: Double,
    ): List<ShanShuiContour> {
        val canv = ArrayList<ShanShuiContour>()
        val ptlist = ArrayList<MutableList<ShanShuiPath.Point>>()
        var yk = 0.0
        for (i in 0 until clu.toInt()) {
            val row = ArrayList<ShanShuiPath.Point>()
            val xk = (ShanShuiPrng.global.rnd() - 0.5) * (len / 8.0)
            yk += ShanShuiPrng.global.rnd() * 5.0
            val lk = len / 4.0 + ShanShuiPrng.global.rnd() * (len / 4.0)
            var j = -lk
            while (j < lk) {
                row.add(
                    floatArrayOf(
                        (j + xk).toFloat(),
                        (Math.sin(j * 0.2) * hei * ShanShuiNoise.noise(j * 0.1) - 20.0 + yk).toFloat(),
                    )
                )
                j += 5.0
            }
            ptlist.add(row)
        }
        for (j in 1 until ptlist.size) {
            // 上游 `:3690-3701`：stroke(ptlist[j], {col:"rgba(100,100,100,0.3~0.6)", wid:1})
            // ⇒ fill=str=该灰 / poly 宽 = out = 1（笔宽 1 已烘进 ribbon 几何）
            val waveCol = ShanShuiColor.rgba(100, 100, 100, 0.3 + ShanShuiPrng.global.rnd() * 0.3)
            canv.add(
                ShanShuiContour(
                    ShanShuiBrush.ribbon(
                        offList(ptlist[j], xoff, yoff),
                        1.0, ::strokeFun, 0.5, waveCol,
                    ),
                    waveCol, waveCol, 1.0,
                )
            )
        }
        return canv
    }

    // ─── private 辅助 ──────────────────────────────────────────

    /**
     * 上游 `flatMount` 的 `texture` **自定义 dis**（`:2148-2154`）：
     * `if (Math.random() > 0.5) return 0.1 + 0.4 * Math.random(); else return 0.9 - 0.4 * Math.random();`
     *
     * ⇒ 每次调用抽 **2** 次随机（比较 1 次 + 取值 1 次）；分布是 0.1..0.5 / 0.5..0.9 两端。
     */
    private fun flatMountDis(): Double =
        if (ShanShuiPrng.global.rnd() > 0.5) {
            0.1 + 0.4 * ShanShuiPrng.global.rnd()
        } else {
            0.9 - 0.4 * ShanShuiPrng.global.rnd()
        }

    /**
     * 上游 `rock` 的 `texture` **自定义 dis**（`:2509-2515`）：
     * `if (Math.random() > 0.5) return 0.15 + 0.15 * Math.random(); else return 0.85 - 0.15 * Math.random();`
     *
     * ⇒ 每次调用抽 **2** 次随机；分布是 0.15..0.3 / 0.7..0.85 两端。
     */
    private fun rockDis(): Double =
        if (ShanShuiPrng.global.rnd() > 0.5) {
            0.15 + 0.15 * ShanShuiPrng.global.rnd()
        } else {
            0.85 - 0.15 * ShanShuiPrng.global.rnd()
        }

    /**
     * 上游 `rock` 的 `texture` **自定义 col**（`:2504-2508`）：
     * `"rgba(180,180,180," + (0.3 + Math.random() * 0.3).toFixed(3) + ")"`
     *
     * ⇒ 每次调用抽 **1** 次随机（上游在 TEXTURE 循环里逐条求值 ⇒ 每条皴线抽 1 次）。
     */
    private fun rockTexCol(): ShanShuiColor =
        ShanShuiColor.rgba(
            180,
            180,
            180,
            ShanShuiUtil.toFixed(0.3 + ShanShuiPrng.global.rnd() * 0.3, 3),
        )

    /**
     * 上游 `fil: "white"` 对应的白色填充（上游多处 WHITE BG 发射用）。
     *
     * 移植来源：上游 `:1879` / `:2131` / `:2488` 等的 `fil: "white"`。移植状态：**照抄**（改造-④：字符串 → 纯数据）。
     */
    private val whiteFill: ShanShuiColor = ShanShuiColor(255, 255, 255, 1.0)

    /** 点偏移（上游 `x + xof` / `y + yof` 的内联版）。 */
    private fun off(p: ShanShuiPath.Point, xof: Double, yoff: Double): ShanShuiPath.Point =
        floatArrayOf((p[0].toDouble() + xof).toFloat(), (p[1].toDouble() + yoff).toFloat())

    /** 点列偏移。 */
    private fun offList(
        plist: List<ShanShuiPath.Point>,
        xof: Double,
        yoff: Double,
    ): List<ShanShuiPath.Point> = plist.map { off(it, xof, yoff) }

    /** 上游 [stroke] 默认 fun：`x => Math.sin(x*Math.PI)`（`:524-529`）。 */
    private fun strokeFun(x: Double): Double = Math.sin(x * Math.PI)
}
