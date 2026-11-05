/**
 * 多边形三角剖分 —— 移植自上游 `PolyTools`（`index.html:206-383`，整个 `<script id="PolyTools">` 块）。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * 移植状态：**照抄**（逐行；唯一改造 = 点类型 `FloatArray`（改造-①/④ 的既有约定），
 * 且**递归内部保持 Double 精度**，只在最终产出时落到 Float32）。
 *
 * ⛔ port 包零 Android 依赖 ⇒ 本对象只出现纯 JVM 类型。
 *
 * ## 上游成员清单（逐个对照）
 *
 * | 上游 | 行号 | 本包 | 备注 |
 * |---|---|---|---|
 * | `PolyTools.midPt` | `:208-222` | [midPt] / [midPtExact] | n 点中点（每点除以 n） |
 * | `PolyTools.triangulate` | `:223-381` | [triangulate] | 入口 |
 * | ├ `lineExpr` | `:229-234` | [lineExpr] | 私有 |
 * | ├ `intersect` | `:235-257` | [intersect] | 私有 |
 * | │  └ `onSeg` | `:244-252` | [onSeg] | 私有（`intersect` 的内嵌闭包） |
 * | ├ `ptInPoly` | `:258-271` | [ptInPoly] | 私有 |
 * | ├ `lnInPoly` | `:272-293` | [lnInPoly] | 私有 |
 * | ├ `sidesOf` | `:295-306` | [sidesOf] | 私有 |
 * | ├ `areaOf` | `:307-314` | [areaOf] | 私有 |
 * | ├ `sliverRatio` | `:315-321` | [sliverRatio] | 私有 |
 * | ├ `bestEar` | `:322-346` | [bestEar] | 私有 |
 * | └ `shatter` | `:347-372` | [shatter] | 私有 |
 *
 * ## 上游有、本包**未移植**的函数及原因
 *
 * - **`unNan`（上游 `:386-392`）** —— 它**不属于 `PolyTools`**（在后面的 `<script id="Util">` 块里），
 *   且上游**零实调用点**（`:393` 只有一行被注释掉的 `console.log`）。
 *   ⇒ 本包不重复实现；同语义的递归去 NaN 已在 [ShanShuiUtil.unNan]（照抄上游 `:386-392`）。
 * - **上游 `:3847-3850` 的 `nch.canv.includes("NaN")` ⇒ `replace(/NaN/g, -1000)`** ——
 *   它在 `chunkloader`（渲染循环）里，同样**不属于 `PolyTools`**。
 *   ⇒ 本包不重复实现；同语义的守卫已在 [ShanShuiPlanner.load] 的 `add` 闭包里
 *     （数值 `isNaN()` 判定 + **扫描整条边**（上游扫描整条 canvas 串 ⇒ port 扫描每条轮廓的每个坐标）
 *     + 替换为 `-1000`）。
 * - **`PolyTools.midPt` 的「多实参」调用形式**（上游 `midPt(P[0], P[1])`，`:448`/`:458`/`:464`）——
 *   上游 `midPt` 用 `arguments.length == 1 ? arguments[0] : Array.apply(null, arguments)` 兼容两种写法。
 *   ⇒ 本包只保留**单数组**形式（[midPt]/[midPtExact]）；上游 `bezmh`（`:445-483`）的多实参用法
 *     已由本包既有的 [ShanShuiPath.midPt]（两点内联版）覆盖，**行为不变**。
 *
 * ## 必须逐行照抄的「怪癖」（⛔ 不许顺手修正）
 *
 * 1. **`areaOf` 的 `undefined` ⇒ NaN**（上游 `:307-314`）
 *    `var a = slist[0], b = slist[1], c = slist[2]`：点数 < 3 时 `b`/`c` 是 `undefined`，
 *    参与算术即 NaN ⇒ `Math.sqrt(NaN…)` = NaN ⇒ `NaN < a` 为 **false** ⇒ 继续细分。
 *    ⇒ 本包用 [Double.NaN] 代替 `undefined`（Kotlin 没有 `undefined`）。
 *    ⚠️ **上游的 `areaOf`/`sidesOf` 里并没有 `| 0`**（`:295-314` 全程是浮点）——
 *       任务书提到的「`areaOf`/`sidesOf` 的 `| 0` 截断」在**这份上游里不存在**，
 *       故不移植（凭空加 `|0` 反而会与上游不符）。
 * 2. **`sliverRatio` 的除零**（上游 `:315-321`）：`A / P`，`P` 为 0 时得 `Infinity`/`NaN`，
 *    **不做保护**。⚠️ 上游这里**也没有** `bds` 初值/`minSliver` 判定（任务书提到的那两个名字
 *    在这份上游的 `sliverRatio`/`bestEar` 里不存在）⇒ 不发明。
 * 3. **`bestEar` 的 `cuts` 遍历与 `>=` 判定**（上游 `:322-346`）：
 *    - `optimize=false` ⇒ **第一个**合法切耳立刻返回（`:332`），**完全不跑** `sliverRatio`；
 *    - `optimize=true` ⇒ 收集**全部**切耳，取 `sliverRatio` 最大者，判定用 **`>=`**（`:340`）
 *      ⇒ **并列时取靠后的那个**；
 *    - `best` 的初值是 `[plist, []]`（`:336`）⇒ 一个切耳都找不到时返回原多边形 + 空表。
 * 4. **`shatter` 的 `sidesOf` 索引推算**（上游 `:347-372`）：
 *    `ind` = **最长边**的下标（`reduce` 用**严格大于** `x > arr[iMax]` ⇒ 并列取靠前的），
 *    `nind = (ind+1) % len`、`lind = (ind+2) % len`；切分点 `mid` 取
 *    `[plist[ind], plist[nind]]` 的中点（**不是** `ind` 与 `lind` 的中点）。
 * 5. **`triangulate` 的 `args.convex` / `args.optimize` 两个分支**（上游 `:226-228`、`:330`、`:332`）：
 *    - `convex=true` ⇒ 每个 `i` 都是合法切耳（**不调用** `lnInPoly` ⇒ 也不走 `ptInPoly`）；
 *    - `convex=false`（默认）⇒ 切耳的对角线 `[lp, np]` 必须**完全落在多边形内**才合法
 *      （`lnInPoly` 用「端点各内缩 1% 的短线段不与任何边相交 + 中点在多边形内」判定）。
 * 6. **`midPt` 的 reduce 顺序**（上游 `:208-222`）：
 *    `v[i] / n + acc[i]`（**先除后加**），初值 `[0, 0]` ⇒
 *    `(((0 + p0/n) + p1/n) + …)`。⛔ 这**不等于** `(p0+p1+…)/n`（浮点结果可能差 1 ulp），
 *    故本包逐字照抄该顺序。
 * 7. **`intersect` 的 `den == 0` 提前返回**（上游 `:239-241`）与 **`onSeg` 的闭区间判定**
 *    （上游 `:244-252`，注释写 "non-inclusive" 但代码是**闭区间** ⇒ 照抄代码，不照抄注释）。
 * 8. **`ptInPoly` 的 `sect != false`**（上游 `:266`）：只要**有交点**就计数（`intersect` 的
 *    `onSeg` 是闭区间判定）⇒ 顶点上的交点会被算进去 ⇒ `scount % 2 == 1` 的奇偶判定带此偏置。
 *
 * ## 与上游的唯一数值差（D14 的既有约定）
 *
 * port 的点类型是 `Point = FloatArray`（**Float32**），上游是 Float64。
 * ⇒ 本包的 **[triangulate] 内部全程用 Double**（与上游逐位同构），
 *   **只在最终产出三角形时才落到 Float32**；输入点也按 Float32 读入（port 的既有约定）。
 * ⚠️ 因此 port 的三角形坐标 = 上游同款算法在 **Float32 输入**上的结果，
 *   与上游 **Float64** 的结果相差 ≤ 1e-5 相对量级（实测：本仓 E50 夹具下 0.1 精度坐标失配 0 处、
 *   色阶失配 0 处 —— 见 `ShanShuiPortGateTest` 的 G-PORT-10）。
 */

package com.nasmusic.tv.visualizer.qianli.port

object ShanShuiPoly {

    // ─── 公开 API ──────────────────────────────────────────────

    /**
     * 移植自上游 `PolyTools.midPt`（`:208-222`）：**n 点**中点（每点除以 n）。
     *
     * ⚠️ 上游的 `midPt` 同时接受「一个数组」与「多个点」两种调用形式
     *   （`arguments.length == 1 ? arguments[0] : Array.apply(null, arguments)`）；
     *   本包只提供**单数组**形式（多实参形式已被 [ShanShuiPath.midPt] 覆盖，行为不变）。
     *
     * ⚠️ 返回值是 **Float32**（port 的 `Point = FloatArray` 约定）⇒ 与上游的 Float64 差 ≤ 1e-7 相对量级。
     *   需要上游的**原值**（Float64）时请用 [midPtExact]。
     */
    fun midPt(plist: List<ShanShuiPath.Point>): ShanShuiPath.Point {
        val d = midPtExact(plist)
        return floatArrayOf(d[0].toFloat(), d[1].toFloat())
    }

    /**
     * 移植自上游 `PolyTools.midPt`（`:208-222`）：**n 点**中点，**保留 Double 精度**。
     *
     * ⚠️ 这是上游 `PolyTools.midPt` 的**原值**（上游全程 Float64）——
     *   [ShanShui.distMount] 用它算三角形中点的灰阶（上游 `:2425-2426`），
     *   以免在「中点」这一步再多引入一次 Float32 量化。
     *
     * @return `[x, y]`（Double，**不**做 Float32 量化）
     */
    fun midPtExact(plist: List<ShanShuiPath.Point>): DoubleArray =
        midPtD(plist.map { doubleArrayOf(it[0].toDouble(), it[1].toDouble()) })

    /**
     * 移植自上游 `PolyTools.triangulate`（`:223-381`）：多边形三角剖分。
     *
     * 上游默认值：`area = 100`、`convex = false`、`optimize = true`（上游 `:226-228`）。
     *
     * ## 算法（与上游逐行同构）
     * 1. 点数 ≤ 3 ⇒ 直接 [shatter]（按面积阈值递归切分）；
     * 2. 点数 > 3 ⇒ [bestEar] 选一个「耳朵」切掉，再把剩下的多边形**递归**剖分，
     *    最后把两边的结果**拼接**（上游 `:376-379` 的 `shatter(cut[0]).concat(triangulate(cut[1]))`）。
     *
     * @param plist 输入多边形的顶点（**顺序即边界顺序**，首尾自动闭合）
     * @param area 面积阈值（上游 `areaOf(plist) < area` ⇒ 停止细分）
     * @param convex `true` ⇒ 每个顶点都算合法耳朵（上游 `:330` 的 `convex ||`）；
     *                `false` ⇒ 耳朵的对角线必须落在多边形内（`lnInPoly`）
     * @param optimize `true` ⇒ 在**全部**合法耳朵里挑「细长比」最大的（上游 `:338-344`）；
     *                   `false` ⇒ 取**第一个**合法耳朵（上游 `:332`，**不**跑 `sliverRatio`）
     * @return 三角形的列表（每个三角形 3 个点）；**不消耗随机数**（纯几何）
     */
    fun triangulate(
        plist: List<ShanShuiPath.Point>,
        area: Double = 100.0,
        convex: Boolean = false,
        optimize: Boolean = true,
    ): List<List<ShanShuiPath.Point>> {
        val dp = plist.map { doubleArrayOf(it[0].toDouble(), it[1].toDouble()) }
        val tris = triangulateD(dp, area, convex, optimize)
        // ⚠️ 递归内部是 Double（与上游同构）⇒ **只在最终产出**时落到 Float32。
        return tris.map { tri -> tri.map { p -> floatArrayOf(p[0].toFloat(), p[1].toFloat()) } }
    }

    // ─── 私有实现（逐行对应上游） ────────────────────────────────

    /**
     * 移植自上游 `PolyTools.midPt`（`:208-222`）的内部实现（Double 精度）。
     *
     * ⚠️ **先除后加**的 reduce 顺序（上游 `:218`：`v[0] / plist.length + acc[0]`）必须照抄：
     *   `(((0 + p0/n) + p1/n) + …)` ≠ `(p0+p1+…)/n`（浮点可能差 1 ulp）。
     */
    private fun midPtD(plist: List<DoubleArray>): DoubleArray {
        val n = plist.size
        var ax = 0.0
        var ay = 0.0
        for (v in plist) {
            ax = v[0] / n + ax
            ay = v[1] / n + ay
        }
        return doubleArrayOf(ax, ay)
    }

    /** 移植自上游 `PolyTools.triangulate`（`:223-381`）的主体（Double 精度）。 */
    private fun triangulateD(
        plist: List<DoubleArray>,
        area: Double,
        convex: Boolean,
        optimize: Boolean,
    ): List<List<DoubleArray>> {
        // 上游 `:373-380`
        return if (plist.size <= 3) {
            shatter(plist, area)
        } else {
            val cut = bestEar(plist, convex, optimize)
            // 上游 `:377-378`：shatter(cut[0]) **拼接** triangulate(cut[1])
            shatter(cut.first, area) + triangulateD(cut.second, area, convex, optimize)
        }
    }

    /**
     * 移植自上游 `lineExpr`（`:229-234`）：两点所在直线的斜率与截距。
     *
     * ⚠️ `den == 0` 时斜率取 `Infinity`（上游 `:231`）⇒ 截距变 `-Infinity`/`NaN`，照抄不修。
     */
    private fun lineExpr(pt0: DoubleArray, pt1: DoubleArray): DoubleArray {
        val den = pt1[0] - pt0[0]
        val m = if (den == 0.0) Double.POSITIVE_INFINITY else (pt1[1] - pt0[1]) / den
        val k = pt0[1] - m * pt0[0]
        return doubleArrayOf(m, k)
    }

    /**
     * 移植自上游 `intersect`（`:235-257`）：两条线段的交点。
     *
     * ⚠️ 上游用 `false` 表示「无交点」，本包用 `null`（`false` 不是合法的点类型）。
     * ⚠️ `den == 0`（两条直线平行）⇒ 直接返回「无交点」（上游 `:239-241`）。
     * ⚠️ 交点必须**同时**落在两条线段上（[onSeg] 的**闭区间**判定）才算相交（上游 `:253`）。
     */
    private fun intersect(ln0: List<DoubleArray>, ln1: List<DoubleArray>): DoubleArray? {
        // 上游 `:236-237`：lineExpr(...ln0) / lineExpr(...ln1)
        val le0 = lineExpr(ln0[0], ln0[1])
        val le1 = lineExpr(ln1[0], ln1[1])
        val den = le0[0] - le1[0]
        if (den == 0.0) return null
        val x = (le1[1] - le0[1]) / den
        val y = le0[0] * x + le0[1]
        if (onSeg(doubleArrayOf(x, y), ln0) && onSeg(doubleArrayOf(x, y), ln1)) {
            return doubleArrayOf(x, y)
        }
        return null
    }

    /**
     * 移植自上游 `intersect` 内的 `onSeg`（`:244-252`）：点是否落在线段上。
     *
     * ⚠️ 上游注释写 "non-inclusive"，但代码是**闭区间**（`<=` / `>=`）⇒ **照抄代码**。
     */
    private fun onSeg(p: DoubleArray, ln: List<DoubleArray>): Boolean {
        return Math.min(ln[0][0], ln[1][0]) <= p[0] &&
            p[0] <= Math.max(ln[0][0], ln[1][0]) &&
            Math.min(ln[0][1], ln[1][1]) <= p[1] &&
            p[1] <= Math.max(ln[0][1], ln[1][1])
    }

    /**
     * 移植自上游 `ptInPoly`（`:258-271`）：点是否在多边形内（射线法，奇偶计数）。
     *
     * ⚠️ 上游的射线是「从 `pt` 指向 `pt + (999, 999)`」（上游 `:264`）—— 方向固定，**不是**水平射线。
     * ⚠️ `sect != false`：与多边形的**任一边**有交点就计数（[onSeg] 是闭区间 ⇒ 顶点上的交点也算）。
     */
    private fun ptInPoly(pt: DoubleArray, plist: List<DoubleArray>): Boolean {
        var scount = 0
        for (i in plist.indices) {
            // 上游 `:261`：np = plist[i != plist.length - 1 ? i + 1 : 0]
            val np = plist[if (i != plist.size - 1) i + 1 else 0]
            val sect = intersect(
                listOf(plist[i], np),
                listOf(pt, doubleArrayOf(pt[0] + 999, pt[1] + 999)),
            )
            if (sect != null) scount++
        }
        // 上游 `:270`：scount % 2 == 1
        return scount % 2 == 1
    }

    /**
     * 移植自上游 `lnInPoly`（`:272-293`）：线段是否**完全**落在多边形内。
     *
     * 判定：把线段两端各内缩 1%（上游 `ep = 0.01`）得到短线段 `lnc`，
     * 若 `lnc` 与多边形的**任一边**相交 ⇒ 不在内部（上游 `:281-286`）；
     * 再看线段中点是否在多边形内（上游 `:288-291`）。
     *
     * ⚠️ `lnc[0]` 用 `(1-ep)` 取 `ln[0]`、`ep` 取 `ln[1]`；`lnc[1]` **反过来**（上游 `:276-279`）——照抄。
     */
    private fun lnInPoly(ln: List<DoubleArray>, plist: List<DoubleArray>): Boolean {
        val lnc = Array(2) { doubleArrayOf(0.0, 0.0) }
        val ep = 0.01
        lnc[0][0] = ln[0][0] * (1 - ep) + ln[1][0] * ep
        lnc[0][1] = ln[0][1] * (1 - ep) + ln[1][1] * ep
        lnc[1][0] = ln[0][0] * ep + ln[1][0] * (1 - ep)
        lnc[1][1] = ln[0][1] * ep + ln[1][1] * (1 - ep)
        for (i in plist.indices) {
            val pt = plist[i]
            val np = plist[if (i != plist.size - 1) i + 1 else 0]
            if (intersect(listOf(lnc[0], lnc[1]), listOf(pt, np)) != null) {
                return false
            }
        }
        // 上游 `:288`：PolyTools.midPt(ln)（2 点 ⇒ 除以 2）
        val mid = midPtD(ln)
        if (!ptInPoly(mid, plist)) return false
        return true
    }

    /**
     * 移植自上游 `sidesOf`（`:295-306`）：多边形各边的长度（**闭合**：末点连回首点）。
     *
     * ⚠️ 全程浮点，**没有** `| 0` 截断（上游 `:300-302` 是 `Math.sqrt(Math.pow(…)`）。
     */
    private fun sidesOf(plist: List<DoubleArray>): List<Double> {
        val slist = ArrayList<Double>(plist.size)
        for (i in plist.indices) {
            val pt = plist[i]
            val np = plist[if (i != plist.size - 1) i + 1 else 0]
            val s = Math.sqrt(
                Math.pow(np[0] - pt[0], 2.0) + Math.pow(np[1] - pt[1], 2.0),
            )
            slist.add(s)
        }
        return slist
    }

    /**
     * 移植自上游 `areaOf`（`:307-314`）：三角形面积（海伦公式）。
     *
     * ⚠️ 上游只取**前三条边**（`slist[0..2]`）⇒ 点数 < 3 时 `b`/`c` 是 `undefined`，
     *   参与算术即 **NaN** ⇒ 结果 NaN ⇒ `NaN < a` 为 **false**（⇒ 继续细分，不会提前停）。
     *   本包用 [Double.NaN] 代替 `undefined`（Kotlin 没有 `undefined`）。
     */
    private fun areaOf(plist: List<DoubleArray>): Double {
        val slist = sidesOf(plist)
        val a = slist.getOrElse(0) { Double.NaN }
        val b = slist.getOrElse(1) { Double.NaN }
        val c = slist.getOrElse(2) { Double.NaN }
        val s = (a + b + c) / 2
        return Math.sqrt(s * (s - a) * (s - b) * (s - c))
    }

    /**
     * 移植自上游 `sliverRatio`（`:315-321`）：面积 / 周长（用来挑「不那么细长」的耳朵）。
     *
     * ⚠️ `P == 0` 时得 `NaN`/`Infinity`，**不做保护**（上游 `:320`）。
     */
    private fun sliverRatio(plist: List<DoubleArray>): Double {
        val a = areaOf(plist)
        var p = 0.0
        for (n in sidesOf(plist)) p += n
        return a / p
    }

    /**
     * 移植自上游 `bestEar`（`:322-346`）：挑一个「耳朵」（切掉一个顶点得到一个三角形）。
     *
     * @return `[耳朵三角形, 剩下的多边形]`（上游 `c = [[lp, pt, np], qlist]`）
     */
    private fun bestEar(
        plist: List<DoubleArray>,
        convex: Boolean,
        optimize: Boolean,
    ): Pair<List<DoubleArray>, List<DoubleArray>> {
        val cuts = ArrayList<Pair<List<DoubleArray>, List<DoubleArray>>>()
        for (i in plist.indices) {
            val pt = plist[i]
            // 上游 `:326-327`
            val lp = plist[if (i != 0) i - 1 else plist.size - 1]
            val np = plist[if (i != plist.size - 1) i + 1 else 0]
            // 上游 `:328-329`：qlist = plist.slice(); qlist.splice(i, 1) ⇒ 去掉第 i 个顶点
            val qlist = plist.toMutableList().also { it.removeAt(i) }
            // 上游 `:330`：convex ⇒ 全部合法；否则对角线 [lp, np] 必须落在多边形内
            if (convex || lnInPoly(listOf(lp, np), plist)) {
                val c = listOf(lp, pt, np) to qlist
                // 上游 `:332`：!optimize ⇒ **第一个**合法耳朵立刻返回（不跑 sliverRatio）
                if (!optimize) return c
                cuts.add(c)
            }
        }
        // 上游 `:336-344`
        var best: Pair<List<DoubleArray>, List<DoubleArray>> = plist to emptyList()
        var bestRatio = 0.0
        for (c in cuts) {
            val r = sliverRatio(c.first)
            // 上游 `:340`：**>=** ⇒ 并列时取**靠后**的那个
            if (r >= bestRatio) {
                best = c
                bestRatio = r
            }
        }
        return best
    }

    /**
     * 移植自上游 `shatter`（`:347-372`）：按面积阈值递归切分三角形。
     *
     * ⚠️ 切分方式（上游 `:354-370`）：
     * - `ind` = **最长边**的下标（`reduce` 用**严格大于** ⇒ 并列取靠前的）；
     * - `nind = (ind+1) % len`、`lind = (ind+2) % len`；
     * - 切分点 `mid` 取 `[plist[ind], plist[nind]]` 的中点（**不是** `ind` 与 `lind` 的中点）；
     * - 递归产出 `[ind, mid, lind]` 与 `[lind, nind, mid]` 两个三角形（**顺序照抄**）。
     */
    private fun shatter(plist: List<DoubleArray>, a: Double): List<List<DoubleArray>> {
        // 上游 `:348-350`
        if (plist.isEmpty()) return emptyList()
        // 上游 `:351-352`
        if (areaOf(plist) < a) {
            return listOf(plist)
        }
        // 上游 `:354-358`：reduce((iMax, x, i, arr) => (x > arr[iMax] ? i : iMax), 0)
        val slist = sidesOf(plist)
        var ind = 0
        for (i in slist.indices) {
            if (slist[i] > slist[ind]) ind = i
        }
        // 上游 `:359-360`
        val nind = (ind + 1) % plist.size
        val lind = (ind + 2) % plist.size
        // 上游 `:361-367`：midPt 包在 try/catch 里 ⇒ 出错则整块放弃（返回 []）
        val mid = try {
            midPtD(listOf(plist[ind], plist[nind]))
        } catch (e: Exception) {
            return emptyList()
        }
        // 上游 `:368-370`
        return shatter(listOf(plist[ind], mid, plist[lind]), a) +
            shatter(listOf(plist[lind], plist[nind], mid), a)
    }
}
