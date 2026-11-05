/**
 * 场景单元层 —— 树。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * 移植状态：**照抄**；[kind1]/[kind2]/[kind3] 另含 **改造-⑤**（返回轮廓列表），
 * [kind2] 另含 **改造-⑥**（簇心点 → blob 轮廓）。
 *
 * [branch] 照抄上游 `:919-989`（共享递归骨架，被多个 treeNN 复用）。
 *   上游默认值：hei=300, wid=6, ang=0, det=10, ben=PI*0.2。
 *   返回 [List<List<Point>>]（trlist1, trlist2）。
 *
 * [kind1] = 上游 tree01（`:744-802`）：细枝树。
 *   上游默认值：hei=50, wid=3, col="rgba(100,100,100,0.5)", noi=0.5。reso=10。
 *   ⚠️ 上游在 `i >= reso/4` 时逐点发射**叶簇 blob**（`:772-794`）：`for (j = 0; j < (reso - i) / 5; j++)`
 *   —— 上界是**浮点** ⇒ 每档 i 至少 1 个 ⇒ 实测 **9 个 blob/棵**（2+2+1+1+1+1+1），
 *   每个 blob 抽 **7** 次随机 ⇒ **63 次/棵**（node 实跑上游 JS 核对：tree01 = 63 draws / 11 polys）。
 *   ⚠️ 上游发**两条**独立 poly（line1、line2，`:798-800`）⇒ port 也发两条独立轮廓。
 *
 * [kind2] = 上游 tree02（`:803-841`）：阔叶簇。
 *   上游默认值：hei=16, wid=8, clu=5, col="rgba(100,100,100,0.5)", noi=0.5。
 *   每簇一次 blob 发射 ⇒ 每簇一条轮廓（fill=col / stroke=col / width=0）。
 *
 * [kind3] = 上游 tree03（`:842-917`）：塔形树。
 *   上游默认值：hei=50, wid=5, ben=默认0, col="rgba(100,100,100,0.5)", noi=0.5。reso=10。
 *   ⚠️ 上游在 `i >= reso/5` 时逐点发射**叶簇 blob**（`:877-902`）：`for (j = 0; j < (reso - i) * 2; j++)`
 *   ⇒ i = 2..9 ⇒ 16+14+12+10+8+6+4+2 = **72 个 blob/棵**，每个抽 7 次 ⇒ **504 次/棵**
 *   （node 实跑上游 JS 核对：tree03 = 504 draws / 73 polys）。
 *   ⚠️ 上游的发射顺序是 **trunk 在前、blobs 在后**（`:914-915`）。
 *
 * [kind4]…[kind8] = 上游 tree04…tree08（`:1191-1724`）：五种专属树形。
 *   逐行移植（**不做任何简化**），见各自的 KDoc。
 *   黄金基线：`app/src/test/resources/qianli/shan_shui_tree45678_golden.json`
 *   （由 `node scripts/e50_tree45678_upstream_probe.js` 实跑上游原始 JS 生成）。
 *
 * ## 已知偏差（接受，不修）
 * - **D14：坐标精度** —— port 的 `Point = FloatArray` ⇒ 坐标是 **Float32**，
 *   上游是 Float64；几何一致，坐标有 ~1e-7 相对误差（渲染分辨率下不可见）。
 *   ⚠️ 但**中间量全部保持 Float64**（见 [DPoint]）：上游的 `branch`/`stroke`/`div` 全程 Float64，
 *   若中途量化到 Float32 再继续算，几何会**再偏一次**（~1e-5）⇒ 与上游的 0.01 网格摘要对不上。
 * - **tree 的 `noi` 是上游的死参数**（`:749` / `:809` / `:853` 声明后从未使用）：
 *   上游的 blob 调用**不传** `noi` ⇒ blob 恒用默认值 **0.5**；port 的 kind 也恒传 0.5。
 *
 * ⛔ port 包零 Android 依赖 ⇒ 本对象**不出现** `android.graphics.Path` / `Canvas`。
 * 所有 `Math.random()` → [ShanShuiPrng.global.rnd()]；所有 `Noise.noise()` → [ShanShuiNoise.noise]。
 */

package com.nasmusic.tv.visualizer.qianli.port

object ShanShuiTree {

    // ══════════════════════ kind1 / kind2 / kind3（tree01…tree03）══════════════════════

    /**
     * 照抄上游 `:919-989`：分支骨架。
     * 先生成 g=3 段折线（每段随机偏转 ben），再沿法线扩展为双线 ribbon。
     * 返回 [trlist1, trlist2]（左右两侧点列）。
     *
     * ⚠️ **2026-11-06 重写**：内部改用 **Float64**（[branchD]）⇒ 与上游逐位同构。
     *    旧实现用 `FloatArray` 存中间量 ⇒ 每次调用都多引入一次 Float32 量化。
     *    本函数**全仓零调用点**（原先是死代码），重写不影响任何既有黄金基线。
     */
    fun branch(
        hei: Double,
        wid: Double,
        ang: Double,
        det: Double,
        ben: Double,
    ): List<List<ShanShuiPath.Point>> =
        branchD(hei, wid, ang, det, ben).map { side -> side.map { toPoint(it) } }

    /**
     * 照抄上游 tree01（`:744-802`）：细枝树。
     * reso=10，用 Noise.noise 生成左右偏移，构建 trunk 轮廓。
     * ⚠️ 上游还发**叶簇 blob**（`:772-794`）与**两条**独立 poly（`:798-800`）——
     * port 早先把两条 poly 并成一条闭合轮廓（多一条树顶水平连接线）⇒ 现按上游分开发射。
     *
     * 移植状态：**照抄**（改造-⑤：返回轮廓列表）。
     *
     * ## 发射顺序（与上游一致）
     * 1. 叶簇 blob（按 i = 3..9 递增，i 内的 j 递增）—— 每条 fill=str=叶色 / width=0；
     * 2. `poly(line1, {fil:"none", str:col, wid:1.5})` ⇒ fill=null / stroke=col / width=1.5；
     * 3. `poly(line2, {fil:"none", str:col, wid:1.5})` ⇒ 同上。
     *
     * ## PRNG 抽取次数（上游逐行核对，node 实跑上游 JS：tree01 = 63 draws / 11 polys）
     * - 叶簇：`i >= reso/4`（= 2.5 ⇒ i = 3..9）时 `for (j = 0; j < (reso - i) / 5; j++)`
     *   —— 上界是**浮点** ⇒ 每档至少 1 个 ⇒ 共 **9 个 blob**；
     *   每个 blob 抽 **7** 次（x / y / len / wid / ang / alpha / blob 内部的 n0）
     *   ⇒ **63 次/棵**（与随机值无关，稳定）。
     */
    fun kind1(
        x: Double,
        y: Double,
        hei: Double,
        wid: Double,
        col: ShanShuiColor,
        noi: Double,
    ): List<ShanShuiContour> {
        val reso = 10
        val nslist = ArrayList<Pair<Double, Double>>(reso)
        for (i in 0 until reso) {
            nslist.add(
                ShanShuiNoise.noise(i * 0.5) to ShanShuiNoise.noise(i * 0.5, 0.5),
            )
        }

        val contours = ArrayList<ShanShuiContour>()
        val line1 = ArrayList<ShanShuiPath.Point>(reso)
        val line2 = ArrayList<ShanShuiPath.Point>(reso)
        for (i in 0 until reso) {
            val nx = x
            val ny = y - (i * hei) / reso
            // 上游 :772-794：叶簇（`i >= reso/4`）
            // ⚠️ 上游 `reso / 4` 是**浮点**除法（10/4 = 2.5 ⇒ i = 3..9）；
            //    Int 除法会得 2 ⇒ 多出 i=2 那一档（2 个 blob）⇒ 抽取次数会多 14 次。
            if (i >= reso / 4.0) {
                var j = 0
                // ⚠️ 上界 `(reso - i) / 5` 是**浮点**（JS）⇒ `j < 0.2` 也成立 ⇒ 每档至少 1 个 blob
                while (j < (reso - i) / 5.0) {
                    // ① 上游 :775 —— blob 的 x 实参（第 1 次抽取）
                    val bx = nx + (ShanShuiPrng.global.rnd() - 0.5) * wid * 1.2 * (reso - i)
                    // ② 上游 :776 —— blob 的 y 实参（第 2 次抽取）
                    val by = ny + (ShanShuiPrng.global.rnd() - 0.5) * wid
                    // ③ 上游 :778 —— `len`（第 3 次抽取；对象字面量按源码顺序求值）
                    val blen = ShanShuiPrng.global.rnd() * 20 * (reso - i) * 0.2 + 10
                    // ④ 上游 :779 —— `wid`（第 4 次抽取）
                    val bwid = ShanShuiPrng.global.rnd() * 6 + 3
                    // ⑤ 上游 :780 —— `ang`（第 5 次抽取）
                    val bang = ((ShanShuiPrng.global.rnd() - 0.5) * Math.PI) / 6
                    // ⑥ 上游 :789 —— `col` 的 alpha（第 6 次抽取；上游 `.toFixed(1)` ⇒ 1 位小数）
                    val bcol = ShanShuiColor(
                        col.r,
                        col.g,
                        col.b,
                        ShanShuiUtil.toFixed(ShanShuiPrng.global.rnd() * 0.2 + col.a, 1),
                    )
                    // ⑦ 上游 blob 内部 `:606` 的 `n0`（第 7 次抽取，在 dab 内部）
                    //    ⚠️ 上游的 blob 调用不传 `noi` ⇒ 恒为 blob 默认 0.5
                    val plist = ShanShuiBrush.dab(bx, by, blen, bwid, bang, bcol, 0.5)
                    contours.add(ShanShuiContour(plist, bcol, bcol, 0.0))
                    j++
                }
            }
            line1.add(floatArrayOf(
                (nx + (nslist[i].first - 0.5) * wid - wid / 2).toFloat(),
                ny.toFloat(),
            ))
            line2.add(floatArrayOf(
                (nx + (nslist[i].second - 0.5) * wid + wid / 2).toFloat(),
                ny.toFloat(),
            ))
        }
        // 上游 :798-800：poly(line1,...) + poly(line2,...) ⇒ **两条独立**轮廓（不是一条闭合轮廓）
        contours.add(ShanShuiContour(line1, null, col, 1.5))
        contours.add(ShanShuiContour(line2, null, col, 1.5))
        return contours
    }

    /**
     * 照抄上游 tree02（`:803-841`）：阔叶簇。
     * 无 trunk 线，每簇一次 `blob(...)` 发射。
     *
     * 移植状态：**照抄** + **改造-⑥**（上游一次 blob 发射 = 一条轮廓）。
     *
     * ⚠️ 改造-⑥：port 早期版本只返回簇**中心点**（无法成轮廓）⇒ 改为每个簇发一条 blob 轮廓。
     * 上游 `:822-838` 的求值次序（JS 对象字面量按源码顺序求值）：
     * ① `x + randGaussian()*clu*4` ② `y + randGaussian()*clu*4`
     * ③ `wid = Math.random()*wid*0.75 + wid*0.5` ④ `len = Math.random()*hei*0.75 + hei*0.5`
     * ⑤ `blob` 内部再抽一次 `n0`（见 [ShanShuiBrush.dab]）。
     *
     * 轮廓样式：上游 `blob` 内部等价 `poly(plist, {fil:col, str:col, wid:0})`
     * ⇒ fill=col / stroke=col / width=0。
     *
     * ⚠️ 上游的 blob 调用**不传** `noi` ⇒ 恒为 blob 默认 0.5（tree 的 `noi` 是上游死参数 `:809`）。
     */
    fun kind2(
        x: Double,
        y: Double,
        hei: Double,
        wid: Double,
        clu: Double,
        col: ShanShuiColor,
        noi: Double,
    ): List<ShanShuiContour> {
        val contours = ArrayList<ShanShuiContour>(clu.toInt())
        for (i in 0 until clu.toInt()) {
            // 上游 `:824-825`：blob 的前两个实参
            val cx = x + ShanShuiUtil.randGaussian() * clu * 4
            val cy = y + ShanShuiUtil.randGaussian() * clu * 4
            // 上游 `:834-835`：对象字面量里的 wid 先于 len 求值（源码顺序）
            val blobWid = ShanShuiPrng.global.rnd() * wid * 0.75 + wid * 0.5
            val blobLen = ShanShuiPrng.global.rnd() * hei * 0.75 + hei * 0.5
            val plist = ShanShuiBrush.dab(
                cx, cy, blobLen, blobWid, Math.PI / 2, col, 0.5, ::tree02Fun,
            )
            contours.add(ShanShuiContour(plist, col, col, 0.0))
        }
        return contours
    }

    /**
     * 照抄上游 tree03（`:842-917`）：塔形树。
     * reso=10，ben 为函数参数（控制树干弯曲），构建 trunk 轮廓。
     *
     * 移植状态：**照抄**（改造-⑤：返回轮廓列表）。
     *
     * ## 发射顺序（与上游一致）
     * 1. `poly(lc, {fil:"white", str:col, wid:1.5})` ⇒ fill=白 / stroke=col / width=1.5（**trunk 在前**）；
     * 2. 叶簇 blob（上游 `:915` 的 `canv += blobs` ⇒ **blobs 在后**）——
     *    每条 fill=str=叶色 / width=0。
     *
     * ## PRNG 抽取次数（上游逐行核对，node 实跑上游 JS：tree03 = 504 draws / 73 polys）
     * - 叶簇：`i >= reso/5`（= 2 ⇒ i = 2..9）时 `for (j = 0; j < (reso - i) * 2; j++)`
     *   ⇒ 16+14+12+10+8+6+4+2 = **72 个 blob**；
     *   每个 blob 抽 **7** 次（ox / randChoice([-1,1]) / y / wid / ang / alpha / blob 内部的 n0）
     *   ⇒ **504 次/棵**（与随机值无关，稳定）。
     */
    fun kind3(
        x: Double,
        y: Double,
        hei: Double,
        wid: Double,
        ben: (Double) -> Double,
        col: ShanShuiColor,
        noi: Double,
    ): List<ShanShuiContour> {
        val reso = 10
        val nslist = ArrayList<Pair<Double, Double>>(reso)
        for (i in 0 until reso) {
            nslist.add(
                ShanShuiNoise.noise(i * 0.5) to ShanShuiNoise.noise(i * 0.5, 0.5),
            )
        }

        val contours = ArrayList<ShanShuiContour>()
        val blobContours = ArrayList<ShanShuiContour>()
        val line1 = ArrayList<ShanShuiPath.Point>(reso)
        val line2 = ArrayList<ShanShuiPath.Point>(reso)
        for (i in 0 until reso) {
            val nx = x + ben(i.toDouble() / reso) * 100
            val ny = y - (i * hei) / reso
            // 上游 :877-902：叶簇（`i >= reso/5` ⇒ i = 2..9）
            // ⚠️ 上游 `reso / 5` 是浮点除法（10/5 = 2.0）；写成 5.0 以免误读成整数语义
            if (i >= reso / 5.0) {
                var j = 0
                while (j < (reso - i) * 2) {
                    // ① 上游 :882 —— `ox`（第 1 次抽取）
                    val ox = ShanShuiPrng.global.rnd() * wid * 2 * tree03Shape((reso - i) / reso.toDouble())
                    // ② 上游 :884 —— `randChoice([-1, 1])`（第 2 次抽取）
                    val sgn = ShanShuiUtil.randChoice(listOf(-1, 1))
                    val bx = nx + ox * sgn
                    // ③ 上游 :885 —— blob 的 y 实参（第 3 次抽取）
                    val by = ny + (ShanShuiPrng.global.rnd() - 0.5) * wid * 2
                    // ④ 上游 :888 —— `wid`（第 4 次抽取；`len: ox * 2` 不抽随机）
                    val bwid = ShanShuiPrng.global.rnd() * 6 + 3
                    // ⑤ 上游 :889 —— `ang`（第 5 次抽取）
                    val bang = ((ShanShuiPrng.global.rnd() - 0.5) * Math.PI) / 6
                    // ⑥ 上游 :898 —— `col` 的 alpha（第 6 次抽取；上游 `.toFixed(3)` ⇒ 3 位小数）
                    val bcol = ShanShuiColor(
                        col.r,
                        col.g,
                        col.b,
                        ShanShuiUtil.toFixed(ShanShuiPrng.global.rnd() * 0.2 + col.a, 3),
                    )
                    // ⑦ 上游 blob 内部 `:606` 的 `n0`（第 7 次抽取，在 dab 内部）
                    //    ⚠️ 上游的 blob 调用不传 `noi` ⇒ 恒为 blob 默认 0.5
                    val plist = ShanShuiBrush.dab(bx, by, ox * 2, bwid, bang, bcol, 0.5)
                    blobContours.add(ShanShuiContour(plist, bcol, bcol, 0.0))
                    j++
                }
            }
            line1.add(floatArrayOf(
                (nx + (((nslist[i].first - 0.5) * wid - wid / 2) * (reso - i)) / reso).toFloat(),
                ny.toFloat(),
            ))
            line2.add(floatArrayOf(
                (nx + (((nslist[i].second - 0.5) * wid + wid / 2) * (reso - i)) / reso).toFloat(),
                ny.toFloat(),
            ))
        }
        // 上游 :913-914：lc = line1.concat(line2.reverse()) → poly(lc, {fil:"white", str:col, wid:1.5})
        val lc = line1 + line2.reversed()
        contours.add(ShanShuiContour(lc, whiteFill, col, 1.5))
        // 上游 :915：canv += blobs（**在 trunk 之后**）
        contours.addAll(blobContours)
        return contours
    }

    // ══════════════════════ kind4…kind8（tree04…tree08）══════════════════════

    /**
     * 照抄上游 [tree04]（`:1191-1277`）：带树皮与细枝的乔木。
     *
     * 移植状态：**照抄**（改造-⑤：返回轮廓列表）。
     *
     * ## 上游结构
     * 1. `branch({hei, wid, ang:-PI/2})` ⇒ 主干骨架（`:1202`）；
     * 2. `barkify(x, y, trlist)` ⇒ 树皮（`:1203`）——⚠️ **会就地改写 trlist 的点**（见 [barkify]）；
     * 3. 沿主干每隔一段长一根**侧枝**（`:1208-1253`）：侧枝再 `barkify` + `twig`；
     * 4. `poly(trmlist, {xof:x, yof:y, fil:"white", str:col, wid:0})` ⇒ 主干白底（`:1254`）；
     * 5. `stroke(trmlist, {wid:2.5, noi:0.9, out:0})` ⇒ 主干描边（`:1258-1272`）。
     *
     * ## 发射顺序（= 上游 SVG 的**拼接序**，见文件头）
     * `canv = poly(主干) + stroke(主干) + txcanv(树皮) + twcanv(细枝)`
     * ⇒ **主干在前、树皮与细枝在后**。
     *
     * ## PRNG 抽取次数（node 实跑上游 JS，seed=157，x=y=0）
     * **8793 次**；轮廓 **767 条**；总点数 **18469**。
     * （黄金基线：`app/src/test/resources/qianli/shan_shui_tree45678_golden.json`）
     */
    fun kind4(
        x: Double,
        y: Double,
        hei: Double,
        wid: Double,
        col: ShanShuiColor,
        noi: Double,
    ): List<ShanShuiContour> {
        val bark = ArrayList<ShanShuiContour>()
        val twigC = ArrayList<ShanShuiContour>()

        // 上游 :1202 —— branch({hei, wid, ang:-PI/2}) ⇒ det/ben 取默认值
        val trPair = branchD(hei, wid, -Math.PI / 2, BRANCH_DET_DEFAULT, BRANCH_BEN_DEFAULT)
        // 上游 :1203 —— barkify（⚠️ 会就地改写 trPair 的点 ⇒ 必须在主干拼装之前）
        barkify(x, y, trPair, bark)
        // 上游 :1204 —— trlist = trlist[0].concat(trlist[1].reverse())
        val trlist = trPair[0] + trPair[1].asReversed()

        val trmlist = ArrayList<DPoint>()
        for (i in trlist.indices) {
            val len = trlist.size
            // 上游 :1209-1214
            // ⚠️ `Math.random() < 0.1` 在 `i >= len*0.3 && i <= len*0.7` **之后**才求值（短路）
            //    ⇒ 区间外的 i **不抽**随机。
            val enter = (i >= len * 0.3 &&
                i <= len * 0.7 &&
                ShanShuiPrng.global.rnd() < 0.1) ||
                (i == len / 2 - 1)
            if (enter) {
                // 上游 :1215
                val ba = Math.PI * 0.2 - Math.PI * 1.4 * (if (i > len / 2) 1.0 else 0.0)
                // 上游 :1217 —— 实参里的 `Math.random()` 先于 branch() 的调用
                val brHei = hei * (ShanShuiPrng.global.rnd() + 1) * 0.3
                // 上游 :1216-1220 —— branch({hei, wid:wid*0.5, ang:ba})
                val brPair = branchD(brHei, wid * 0.5, ba, BRANCH_DET_DEFAULT, BRANCH_BEN_DEFAULT)
                // 上游 :1222-1223 —— brlist[0].splice(0,1) / brlist[1].splice(0,1)
                val br0 = ArrayList<DPoint>(brPair[0]).also { it.removeAt(0) }
                val br1 = ArrayList<DPoint>(brPair[1]).also { it.removeAt(0) }
                // 上游 :1224-1227 —— foff 把侧枝的局部坐标平移到主干坐标
                val foff = { v: DPoint -> doubleArrayOf(v[0] + trlist[i][0], v[1] + trlist[i][1]) }
                barkify(x, y, listOf(br0.map { foff(it) }, br1.map { foff(it) }), bark)
                // 上游 :1229-1243 —— 细枝
                for (j in br0.indices) {
                    if (ShanShuiPrng.global.rnd() < 0.2 || j == br0.size - 1) {
                        twig(
                            tx = br0[j][0] + trlist[i][0] + x,
                            ty = br0[j][1] + trlist[i][1] + y,
                            dep = 1,
                            dir = if (ba > -Math.PI / 2) 1.0 else -1.0,
                            sca = (0.5 * hei) / 300,
                            wid = hei / 300,
                            ang = if (ba > -Math.PI / 2) ba else ba + Math.PI,
                            lea = true,
                            leaSize = TWIG_LEA_DEFAULT,
                            out = twigC,
                        )
                    }
                }
                // 上游 :1244-1249
                val brFlat = br0 + br1.asReversed()
                trmlist.addAll(
                    brFlat.map { doubleArrayOf(it[0] + trlist[i][0], it[1] + trlist[i][1]) },
                )
            } else {
                // 上游 :1251
                trmlist.add(trlist[i])
            }
        }

        // 上游 :1254 —— 主干白底
        val trunk = polyContour(trmlist, x, y, whiteFill, col, 0.0)
        // 上游 :1256-1257 —— 去掉首尾两点
        val trunkPts = trimEnds(trmlist)
        // 上游 :1258-1272 —— 主干描边（颜色每次重抽 ⇒ 1 次随机）
        val trunkStroke = strokeD(
            trunkPts.map { doubleArrayOf(it[0] + x, it[1] + y) },
            2.5,
            { Math.sin(1.0) },
            0.9,
            ShanShuiColor(100, 100, 100, ShanShuiUtil.toFixed(0.4 + ShanShuiPrng.global.rnd() * 0.1, 3)),
            0.0,
        )
        // 上游 :1274-1275 —— canv = poly + stroke + txcanv + twcanv
        return listOfNotNull(trunk, trunkStroke) + bark + twigC
    }

    /**
     * 照抄上游 [tree05]（`:1279-1372`）：横向展开的疏枝树。
     *
     * 移植状态：**照抄**（改造-⑤：返回轮廓列表）。
     *
     * ## 与 tree04 的差异（逐条）
     * - `branch({..., ben: 0})`（`:1290`）—— 侧枝不偏转；
     * - 侧枝条件是 `i % 3 == 0 && Math.random() > p`（`:1299-1302`）⇒ **每次判定都抽 1 次**；
     * - 侧枝**不** barkify（上游 `:1320` 那行被注释掉了）；
     * - 细枝 `dep = 0`、`lea = [true, 5]`（`:1324-1335`）。
     *
     * ## 发射顺序（= 上游 SVG 拼接序）：主干白底 → 主干描边 → 树皮 → 细枝。
     *
     * ## PRNG 抽取次数（node 实跑上游 JS，seed=157，x=y=0）
     * **2079 次**；轮廓 **266 条**；总点数 **7931**。
     */
    fun kind5(
        x: Double,
        y: Double,
        hei: Double,
        wid: Double,
        col: ShanShuiColor,
        noi: Double,
    ): List<ShanShuiContour> {
        val bark = ArrayList<ShanShuiContour>()
        val twigC = ArrayList<ShanShuiContour>()

        // 上游 :1290 —— branch({hei, wid, ang:-PI/2, ben:0})
        val trPair = branchD(hei, wid, -Math.PI / 2, BRANCH_DET_DEFAULT, 0.0)
        // 上游 :1291
        barkify(x, y, trPair, bark)
        // 上游 :1292
        val trlist = trPair[0] + trPair[1].asReversed()

        val trmlist = ArrayList<DPoint>()
        for (i in trlist.indices) {
            val len = trlist.size
            // 上游 :1297
            val p = Math.abs(i - len * 0.5) / (len * 0.5)
            // 上游 :1298-1304
            // ⚠️ `Math.random() > p` 在 `i%3==0` **之后**才求值（短路）⇒ 不满足前三个条件就不抽。
            val enter = (i >= len * 0.2 &&
                i <= len * 0.8 &&
                i % 3 == 0 &&
                ShanShuiPrng.global.rnd() > p) ||
                (i == len / 2 - 1)
            if (enter) {
                // 上游 :1305
                val bar = ShanShuiPrng.global.rnd() * 0.2
                // 上游 :1306-1307
                val ba = -bar * Math.PI -
                    (1 - bar * 2) * Math.PI * (if (i > len / 2) 1.0 else 0.0)
                // 上游 :1309 —— 实参里的 `Math.random()` 先于 branch() 的调用
                val brHei = hei * (0.3 * p - ShanShuiPrng.global.rnd() * 0.05)
                // 上游 :1308-1313 —— branch({hei, wid:wid*0.5, ang:ba, ben:0.5})
                val brPair = branchD(brHei, wid * 0.5, ba, BRANCH_DET_DEFAULT, 0.5)
                // 上游 :1315-1316
                val br0 = ArrayList<DPoint>(brPair[0]).also { it.removeAt(0) }
                val br1 = ArrayList<DPoint>(brPair[1]).also { it.removeAt(0) }
                // 上游 :1320 —— ⚠️ 侧枝的 barkify 在上游是**注释掉的** ⇒ 不发射
                // 上游 :1322-1337 —— 细枝（`j % 20 == 0 || j == 末点`）
                for (j in br0.indices) {
                    if (j % 20 == 0 || j == br0.size - 1) {
                        twig(
                            tx = br0[j][0] + trlist[i][0] + x,
                            ty = br0[j][1] + trlist[i][1] + y,
                            dep = 0,
                            dir = if (ba > -Math.PI / 2) 1.0 else -1.0,
                            sca = (0.2 * hei) / 300,
                            wid = hei / 300,
                            ang = if (ba > -Math.PI / 2) ba else ba + Math.PI,
                            lea = true,
                            leaSize = 5.0,
                            out = twigC,
                        )
                    }
                }
                // 上游 :1338-1343
                val brFlat = br0 + br1.asReversed()
                trmlist.addAll(
                    brFlat.map { doubleArrayOf(it[0] + trlist[i][0], it[1] + trlist[i][1]) },
                )
            } else {
                // 上游 :1345
                trmlist.add(trlist[i])
            }
        }

        // 上游 :1349
        val trunk = polyContour(trmlist, x, y, whiteFill, col, 0.0)
        // 上游 :1351-1352
        val trunkPts = trimEnds(trmlist)
        // 上游 :1353-1367
        val trunkStroke = strokeD(
            trunkPts.map { doubleArrayOf(it[0] + x, it[1] + y) },
            2.5,
            { Math.sin(1.0) },
            0.9,
            ShanShuiColor(100, 100, 100, ShanShuiUtil.toFixed(0.4 + ShanShuiPrng.global.rnd() * 0.1, 3)),
            0.0,
        )
        // 上游 :1369-1370
        return listOfNotNull(trunk, trunkStroke) + bark + twigC
    }

    /**
     * 照抄上游 [tree06]（`:1374-1488`）：递归分叉的树丛。
     *
     * 移植状态：**照抄**（改造-⑤：返回轮廓列表）。
     *
     * ## 上游结构
     * - 内部函数 `fracTree(xoff, yoff, dep, args)`（`:1385-1456`）递归 3 层；
     * - 每层：先 `branch({..., det: hei/20})` + `barkify`，再沿骨架挑点长子分支；
     * - ⚠️ 判定条件里 `Math.random() < 0.025` 在**最前**（`:1407`）⇒ **每个 i 都抽 1 次**；
     * - 顶层 `fracTree(x, y, 3, {hei, wid, ang:-PI/2, ben:0})`（`:1458-1463`）。
     *
     * ## 发射顺序（= 上游 SVG 拼接序）：主干白底 → 主干描边 → 树皮（各层，按递归序） → 细枝。
     *
     * ## PRNG 抽取次数（node 实跑上游 JS，seed=157，x=y=0）
     * **16771 次**；轮廓 **873 条**；总点数 **21546**。
     */
    fun kind6(
        x: Double,
        y: Double,
        hei: Double,
        wid: Double,
        col: ShanShuiColor,
        noi: Double,
    ): List<ShanShuiContour> {
        val bark = ArrayList<ShanShuiContour>()
        val twigC = ArrayList<ShanShuiContour>()

        // 上游 :1458-1463
        val trmlist = fracTree6(x, y, 3, hei, wid, -Math.PI / 2, 0.0, bark, twigC)

        // 上游 :1465
        val trunk = polyContour(trmlist, x, y, whiteFill, col, 0.0)
        // 上游 :1467-1468
        val trunkPts = trimEnds(trmlist)
        // 上游 :1469-1483
        val trunkStroke = strokeD(
            trunkPts.map { doubleArrayOf(it[0] + x, it[1] + y) },
            2.5,
            { Math.sin(1.0) },
            0.9,
            ShanShuiColor(100, 100, 100, ShanShuiUtil.toFixed(0.4 + ShanShuiPrng.global.rnd() * 0.1, 3)),
            0.0,
        )
        // 上游 :1485-1486
        return listOfNotNull(trunk, trunkStroke) + bark + twigC
    }

    /**
     * 照抄上游 [tree06] 的内部函数 `fracTree`（`:1385-1456`）。
     *
     * ⚠️ `det = hei / 20`（`:1397`）⇒ `det` **可能是小数** ⇒
     *    - `tl = 3 * det` 是小数 ⇒ 上游 `for (i = 0; i < tl; i++)` 的循环次数是 **ceil(tl)**；
     *    - `p = (i % span) / span`（span = det）⇒ `p == 0` 只在 i 是 det 的整数倍时成立。
     *    ⇒ 本移植用 `while (i < tl)` 逐字复现（**不是** `tl.toInt()`）。
     */
    private fun fracTree6(
        xoff: Double,
        yoff: Double,
        dep: Int,
        hei: Double,
        wid: Double,
        ang: Double,
        ben: Double,
        bark: MutableList<ShanShuiContour>,
        twigC: MutableList<ShanShuiContour>,
    ): List<DPoint> {
        // 上游 :1392-1398 —— branch({hei, wid, ang, ben, det: hei/20})
        val trPair = branchD(hei, wid, ang, hei / 20, ben)
        // 上游 :1399
        barkify(xoff, yoff, trPair, bark)
        // 上游 :1400
        val trlist = trPair[0] + trPair[1].asReversed()

        val trmlist = ArrayList<DPoint>()
        for (i in trlist.indices) {
            val len = trlist.size
            // 上游 :1405
            val p = Math.abs(i - len * 0.5) / (len * 0.5)
            // 上游 :1406-1413
            // ⚠️ `Math.random() < 0.025` 在**最前** ⇒ 每个 i 都抽 1 次（哪怕 dep == 0）。
            val enter = ((ShanShuiPrng.global.rnd() < 0.025 &&
                i >= len * 0.2 &&
                i <= len * 0.8) ||
                (i == len / 2 - 1) ||
                (i == len / 2 + 1)) &&
                dep > 0
            if (enter) {
                // 上游 :1414
                val bar = 0.02 + ShanShuiPrng.global.rnd() * 0.08
                // 上游 :1415-1416
                val ba = bar * Math.PI - bar * 2 * Math.PI * (if (i > len / 2) 1.0 else 0.0)
                // 上游 :1423 —— 实参里的 `Math.random()` 先于递归调用
                val childHei = hei * (0.7 + ShanShuiPrng.global.rnd() * 0.2)
                // 上游 :1418-1428
                val brList = fracTree6(
                    trlist[i][0] + xoff,
                    trlist[i][1] + yoff,
                    dep - 1,
                    childHei,
                    wid * 0.6,
                    ang + ba,
                    0.55,
                    bark,
                    twigC,
                )
                // 上游 :1430-1444 —— 细枝（`Math.random() < 0.03`）
                for (j in brList.indices) {
                    if (ShanShuiPrng.global.rnd() < 0.03) {
                        // 上游 :1437 —— 实参里的 `Math.random()` 先于 twig() 的调用
                        val twigAng = ba * (ShanShuiPrng.global.rnd() * 0.5 + 0.75)
                        twig(
                            tx = brList[j][0] + trlist[i][0] + xoff,
                            ty = brList[j][1] + trlist[i][1] + yoff,
                            dep = 2,
                            dir = if (ba > 0) 1.0 else -1.0,
                            sca = 0.3,
                            wid = TWIG_WID_DEFAULT,
                            ang = twigAng,
                            lea = false,
                            leaSize = 0.0,
                            out = twigC,
                        )
                    }
                }
                // 上游 :1446-1450
                trmlist.addAll(
                    brList.map { doubleArrayOf(it[0] + trlist[i][0], it[1] + trlist[i][1]) },
                )
            } else {
                // 上游 :1452
                trmlist.add(trlist[i])
            }
        }
        return trmlist
    }

    /**
     * 照抄上游 [tree07]（`:1490-1580`）：三角剖分 + 噪声着色的团簇树。
     *
     * 移植状态：**照抄**（改造-⑤：返回轮廓列表）。
     *
     * ## 上游结构
     * - 10 步骨架 + 每步一个 `blob`（`i >= reso/4` ⇒ i = 3..9 ⇒ **7 个**，`:1524-1561`）；
     * - 每个 blob 走 `PolyTools.triangulate(bpl, {area:50, convex:true, optimize:false})`；
     * - 骨架本身走 `triangulate(..., {area:50, convex:true, optimize:true})`；
     * - 每个三角形的颜色 = `Noise.noise(中点*0.02) * 200 + 50 | 0`（`:1573-1578`）。
     *
     * ⚠️ `reso = 10` 在上游是**隐式全局**（`:1503` 没有 `var`）—— port 用局部常量，行为一致。
     *
     * ## PRNG 抽取次数（node 实跑上游 JS，seed=157，x=y=0）
     * **42 次**（7 个 blob × 6 次）；轮廓 **165 条**；总点数 **495**。
     */
    fun kind7(
        x: Double,
        y: Double,
        hei: Double,
        wid: Double,
        ben: (Double) -> Double,
        col: ShanShuiColor,
        noi: Double,
    ): List<ShanShuiContour> {
        val reso = 10
        // 上游 :1504-1507
        val nslist = ArrayList<Pair<Double, Double>>(reso)
        for (i in 0 until reso) {
            nslist.add(
                ShanShuiNoise.noise(i * 0.5) to ShanShuiNoise.noise(i * 0.5, 0.5),
            )
        }
        // 上游 :1508-1516 —— leafcol（由 col 的 CSS 串解析而来；port 的 col 已是解析后的纯数据）
        val leafCol = ShanShuiColor(
            col.r,
            col.g,
            col.b,
            ShanShuiUtil.toFixed(col.a, 3),
        )

        val line1 = ArrayList<DPoint>(reso)
        val line2 = ArrayList<DPoint>(reso)
        // 上游 :1520 —— T（先攒叶簇三角形，最后再拼上骨架三角形）
        val leafTris = ArrayList<List<ShanShuiPath.Point>>()
        for (i in 0 until reso) {
            // 上游 :1522-1523
            val nx = x + ben(i.toDouble() / reso) * 100
            val ny = y - (i * hei) / reso
            // 上游 :1524 —— `i >= reso / 4`（10/4 = 2.5 ⇒ i = 3..9）
            if (i >= reso / 4.0) {
                for (j in 0 until 1) {
                    // ① 上游 :1527 —— blob 的 x 实参
                    val bx = nx + (ShanShuiPrng.global.rnd() - 0.5) * wid * 1.2 * (reso - i) * 0.5
                    // ② 上游 :1528 —— blob 的 y 实参
                    val by = ny + (ShanShuiPrng.global.rnd() - 0.5) * wid * 0.5
                    // ③ 上游 :1530 —— `len`（对象字面量按源码顺序求值）
                    val blen = ShanShuiPrng.global.rnd() * 50 + 20
                    // ④ 上游 :1531 —— `wid`
                    val bwid = ShanShuiPrng.global.rnd() * 12 + 12
                    // ⑤ 上游 :1532 —— `ang`
                    val bang = (-ShanShuiPrng.global.rnd() * Math.PI) / 6
                    // ⑥ 上游 blob 内部 `:606` 的 `n0`（在 dab 内部）
                    // ⚠️ 上游的 blob 调用**不传** `noi` ⇒ 恒为 blob 默认 0.5
                    val bpl = ShanShuiBrush.dab(bx, by, blen, bwid, bang, leafCol, 0.5, ::tree07Fun)
                    // 上游 :1553-1559 —— triangulate(bpl, {area:50, convex:true, optimize:false})
                    leafTris.addAll(ShanShuiPoly.triangulate(bpl, 50.0, true, false))
                }
            }
            // 上游 :1562-1563
            line1.add(doubleArrayOf(nx + (nslist[i].first - 0.5) * wid - wid / 2, ny))
            line2.add(doubleArrayOf(nx + (nslist[i].second - 0.5) * wid + wid / 2, ny))
        }
        // 上游 :1567-1571 —— 骨架三角形在前，叶簇三角形在后
        val trunkTris = ShanShuiPoly.triangulate(
            (line1 + line2.asReversed()).map { toPoint(it) },
            50.0,
            true,
            true,
        )
        val allTris = trunkTris + leafTris

        val contours = ArrayList<ShanShuiContour>(allTris.size)
        for (k in allTris.indices) {
            // 上游 :1574-1576
            val m = ShanShuiPoly.midPt(allTris[k])
            val c = (ShanShuiNoise.noise(m[0] * 0.02, m[1] * 0.02) * 200 + 50).toInt()
            val co = ShanShuiColor(c, c, c, 0.8)
            // 上游 :1577 —— poly(T[k], {fil:co, str:co, wid:0})
            contours.add(ShanShuiContour(allTris[k], co, co, 0.0))
        }
        return contours
    }

    /**
     * 照抄上游 [tree08]（`:1582-1724`）：分形小枝（无树皮）。
     *
     * 移植状态：**照抄**（改造-⑤：返回轮廓列表）。
     *
     * ## 上游结构
     * - `normRand(-1, 1) * PI * 0.2` 给整棵树一个随机倾角（`:1593`）；
     * - `branch({hei, wid, ang:-PI/2+ang, ben:PI*0.2, det:hei/20})`（`:1595-1601`）；
     * - ⚠️ 上游 `:1602` 的 `barkify` 是**注释掉的** ⇒ 不发射树皮；
     * - 沿骨架每个点以 0.2 的概率长一根 `fracTree`（`:1686-1700`）；
     * - `fracTree`（`:1606-1684`）递归分叉，每层发一笔 `stroke`。
     *
     * ## 发射顺序（= 上游 SVG 拼接序）：主干白底 → 主干描边 → 分形细枝。
     *
     * ## PRNG 抽取次数（node 实跑上游 JS，seed=157，x=y=0）
     * **201 次**；轮廓 **31 条**；总点数 **680**。
     */
    fun kind8(
        x: Double,
        y: Double,
        hei: Double,
        wid: Double,
        col: ShanShuiColor,
        noi: Double,
    ): List<ShanShuiContour> {
        val twigC = ArrayList<ShanShuiContour>()

        // 上游 :1593
        val ang = ShanShuiUtil.normRand(-1.0, 1.0) * Math.PI * 0.2
        // 上游 :1595-1601 —— branch({hei, wid, ang:-PI/2+ang, ben:PI*0.2, det:hei/20})
        val trPair = branchD(hei, wid, -Math.PI / 2 + ang, hei / 20, Math.PI * 0.2)
        // 上游 :1602 —— ⚠️ barkify 在上游是**注释掉的** ⇒ 不发射
        // 上游 :1604
        val trlist = trPair[0] + trPair[1].asReversed()

        // 上游 :1686-1700
        for (i in trlist.indices) {
            if (ShanShuiPrng.global.rnd() < 0.2) {
                // ⚠️ 实参求值顺序（上游 JS 左→右）：dep 的随机先于 ang 的随机
                val dep = Math.floor(4 * ShanShuiPrng.global.rnd())
                val twigAng = -Math.PI / 2 - ang * ShanShuiPrng.global.rnd()
                twigC.addAll(
                    fracTree8(
                        x + trlist[i][0],
                        y + trlist[i][1],
                        dep.toInt(),
                        twigAng,
                        FRACTREE_LEN_DEFAULT,
                        FRACTREE_BEN_DEFAULT,
                    ),
                )
            } else if (i == Math.floor(trlist.size / 2.0).toInt()) {
                twigC.addAll(
                    fracTree8(
                        x + trlist[i][0],
                        y + trlist[i][1],
                        3,
                        -Math.PI / 2 + ang,
                        FRACTREE_LEN_DEFAULT,
                        FRACTREE_BEN_DEFAULT,
                    ),
                )
            }
        }

        // 上游 :1702
        val trunk = polyContour(trlist, x, y, whiteFill, col, 0.0)
        // 上游 :1704-1718
        val trunkStroke = strokeD(
            trlist.map { doubleArrayOf(it[0] + x, it[1] + y) },
            2.5,
            { Math.sin(1.0) },
            0.9,
            ShanShuiColor(100, 100, 100, ShanShuiUtil.toFixed(0.6 + ShanShuiPrng.global.rnd() * 0.1, 3)),
            0.0,
        )
        // 上游 :1720-1721
        return listOfNotNull(trunk, trunkStroke) + twigC
    }

    /**
     * 照抄上游 [tree08] 的内部函数 `fracTree`（`:1606-1684`）：分形小枝。
     *
     * ⚠️ **实参求值顺序**（上游 JS 左→右，移植必须一致）：
     * `randChoice([normRand(-1, 0.5), normRand(0.5, 1)])` ——
     * **数组字面量先求值** ⇒ 先抽 `normRand(-1,0.5)`、再抽 `normRand(0.5,1)`、
     * **最后**才是 `randChoice` 自己那一次 ⇒ 共 3 次。
     * 随后 `len: len * normRand(0.8, 0.9)` 再抽 1 次 ⇒ 每个子调用 4 次。
     */
    private fun fracTree8(
        xoff: Double,
        yoff: Double,
        dep: Int,
        ang: Double,
        len: Double,
        ben: Double,
    ): List<ShanShuiContour> {
        // 上游 :1612-1619
        val strokeFun: (Double) -> Double =
            if (dep == 0) {
                { x -> Math.cos(0.5 * Math.PI * x) }
            } else {
                { 1.0 }
            }
        // 上游 :1620-1621
        val spt = doubleArrayOf(xoff, yoff)
        val ept = doubleArrayOf(xoff + Math.cos(ang) * len, yoff + Math.sin(ang) * len)
        // 上游 :1623
        val trmlist = ArrayList<DPoint>()
        trmlist.add(doubleArrayOf(xoff, yoff))
        trmlist.add(doubleArrayOf(xoff + len, yoff))
        // 上游 :1625-1632 —— randChoice([sin, -sin])（抽 1 次）
        val bfun = ShanShuiUtil.randChoice(
            listOf<(Double) -> Double>(
                { x -> Math.sin(x * Math.PI) },
                { x -> -Math.sin(x * Math.PI) },
            ),
        )
        // 上游 :1634 —— div(trmlist, 10)
        val divList = divD(trmlist, 10)
        trmlist.clear()
        trmlist.addAll(divList)
        // 上游 :1636-1638
        for (i in trmlist.indices) {
            trmlist[i][1] += bfun(i.toDouble() / trmlist.size) * 2
        }
        // 上游 :1639-1644
        for (i in trmlist.indices) {
            val d = ShanShuiUtil.distance(trmlist[i], spt)
            val a = Math.atan2(trmlist[i][1] - spt[1], trmlist[i][0] - spt[0])
            trmlist[i][0] = spt[0] + d * Math.cos(a + ang)
            trmlist[i][1] = spt[1] + d * Math.sin(a + ang)
        }

        val tcanv = ArrayList<ShanShuiContour>()
        // 上游 :1647-1651 —— stroke(trmlist, {fun, wid:0.8, col:"rgba(100,100,100,0.5)"})
        // ⚠️ 上游不传 `out` ⇒ 缺省 1；不传 `noi` ⇒ 缺省 0.5
        strokeD(trmlist, 0.8, strokeFun, 0.5, twigGray, 1.0)?.let { tcanv.add(it) }
        // 上游 :1652-1682
        if (dep != 0) {
            // 上游 :1653
            val nben = ben + ShanShuiUtil.randChoice(listOf(-1.0, 1.0)) * Math.PI * 0.001 * dep * dep
            if (ShanShuiPrng.global.rnd() < 0.5) {
                // 上游 :1655-1664 —— ⚠️ 数组字面量先求值 ⇒ normRand ×2 → randChoice ×1
                val ang1 = ang + ben + Math.PI * ShanShuiUtil.randChoice(
                    listOf(
                        ShanShuiUtil.normRand(-1.0, 0.5),
                        ShanShuiUtil.normRand(0.5, 1.0),
                    ),
                ) * 0.2
                // 上游 :1662 —— `len` 在 `ang` 之后求值
                val len1 = len * ShanShuiUtil.normRand(0.8, 0.9)
                tcanv.addAll(fracTree8(ept[0], ept[1], dep - 1, ang1, len1, nben))
                // 上游 :1665-1674
                val ang2 = ang + ben + Math.PI * ShanShuiUtil.randChoice(
                    listOf(
                        ShanShuiUtil.normRand(-1.0, -0.5),
                        ShanShuiUtil.normRand(0.5, 1.0),
                    ),
                ) * 0.2
                val len2 = len * ShanShuiUtil.normRand(0.8, 0.9)
                tcanv.addAll(fracTree8(ept[0], ept[1], dep - 1, ang2, len2, nben))
            } else {
                // 上游 :1676-1680
                val len3 = len * ShanShuiUtil.normRand(0.8, 0.9)
                tcanv.addAll(fracTree8(ept[0], ept[1], dep - 1, ang + ben, len3, nben))
            }
        }
        return tcanv
    }

    // ══════════════════════ 共享辅助（上游 `branch` / `twig` / `barkify`）══════════════════════

    /**
     * 照抄上游 [branch]（`:919-989`）的 **Float64** 复刻（共享递归骨架）。
     *
     * ## 逐条对应
     * - `:925` `ben` 缺省 `PI*0.2`；`:924` `det` 缺省 `10`；
     * - `:933-938` 三段折线：每段 `a0 += (ben/2 + (rnd()*ben)/2) * randChoice([-1,1])`
     *   ⇒ **每段 2 次抽取**（先 `Math.random()` 再 `randChoice`）；
     * - `:944-951` 整体旋转到目标角；
     * - `:960-986` 沿法线扩成双线 ribbon：`p == 0`（= `i % det == 0`）时抽 1 次随机做宽度抖动。
     *
     * ⚠️ **循环上界是 `while (i < tl)`**（上游 `tl = 3*det` 可能是小数）——
     *    `tl.toInt()` 会少跑一轮 ⇒ 点数与随机流都会错。
     *
     * ⚠️ 返回的点是 **Float64**（上游同款）⇒ tree04–08 的中间量全程 Float64，
     *    只在生成 [ShanShuiContour] 时才落到 Float32（D14）。
     */
    private fun branchD(
        hei: Double,
        wid: Double,
        ang: Double,
        det: Double,
        ben: Double,
    ): List<List<DPoint>> {
        // 上游 :927-930
        val tlist = ArrayList<DPoint>(4)
        var nx = 0.0
        var ny = 0.0
        tlist.add(doubleArrayOf(nx, ny))
        // 上游 :931-938
        var a0 = 0.0
        val g = 3
        for (i in 0 until g) {
            a0 += (ben / 2 + (ShanShuiPrng.global.rnd() * ben) / 2) *
                ShanShuiUtil.randChoice(listOf(-1.0, 1.0))
            nx += (Math.cos(a0) * hei) / g
            ny -= (Math.sin(a0) * hei) / g
            tlist.add(doubleArrayOf(nx, ny))
        }
        // 上游 :939-942
        val ta = Math.atan2(tlist[tlist.size - 1][1], tlist[tlist.size - 1][0])
        // 上游 :944-951 —— 整体旋转
        for (i in tlist.indices) {
            val a = Math.atan2(tlist[i][1], tlist[i][0])
            val d = Math.sqrt(tlist[i][0] * tlist[i][0] + tlist[i][1] * tlist[i][1])
            tlist[i][0] = d * Math.cos(a - ta + ang)
            tlist[i][1] = d * Math.sin(a - ta + ang)
        }
        // 上游 :953-986
        val trlist1 = ArrayList<DPoint>()
        val trlist2 = ArrayList<DPoint>()
        val span = det
        val tl = (tlist.size - 1) * span
        var lx = 0.0
        var ly = 0.0
        var i = 0
        while (i < tl) {
            val lastp = tlist[Math.floor(i / span).toInt()]
            val nextp = tlist[Math.ceil(i / span).toInt()]
            val p = (i % span) / span
            val nx2 = lastp[0] * (1 - p) + nextp[0] * p
            val ny2 = lastp[1] * (1 - p) + nextp[1] * p
            val ang2 = Math.atan2(ny2 - ly, nx2 - lx)
            val woff = (ShanShuiNoise.noise(i * 0.3) - 0.5) * wid * hei / 80
            var b = 0.0
            // 上游 :971-973
            if (p == 0.0) {
                b = ShanShuiPrng.global.rnd() * wid
            }
            val nw = wid * (((tl - i) / tl) * 0.5 + 0.5)
            trlist1.add(
                doubleArrayOf(
                    nx2 + Math.cos(ang2 + Math.PI / 2) * (nw + woff + b),
                    ny2 + Math.sin(ang2 + Math.PI / 2) * (nw + woff + b),
                ),
            )
            trlist2.add(
                doubleArrayOf(
                    nx2 + Math.cos(ang2 - Math.PI / 2) * (nw - woff + b),
                    ny2 + Math.sin(ang2 - Math.PI / 2) * (nw - woff + b),
                ),
            )
            lx = nx2
            ly = ny2
            i++
        }
        return listOf(trlist1, trlist2)
    }

    /**
     * 照抄上游 [twig]（`:991-1062`）：细枝（含叶簇）。
     *
     * ## 抽取顺序（上游逐行）
     * - `:1002` `hs`（1 次）；
     * - `:1010` `randChoice([fun2])`（1 次，**单元素数组也要抽**）；
     * - `:1011` `a0`（1 次）；
     * - `:1024-1030` 递归子枝：`dir: dir * randChoice([-1,1])` 是**实参** ⇒ 先抽（1 次），
     *   然后子枝自己再抽 3 次；
     * - `:1033-1051` 末梢 5 片叶：每片抽 **4** 次（wid / len / ang / blob 内部的 n0）⇒ 20 次；
     * - `:1054-1060` 枝干 `stroke`（1 次，stroke 内部的 n0）。
     *
     * ⚠️ 上游 `fun2`（`:1007-1008`）闭包捕获了外层的 `i`（`:1013`）——
     *    因为传入的实参恰是 `i / tl` ⇒ `fun2(x)` 等价于 `1 - 1/(x+1)^5`（本移植照此写死）。
     */
    private fun twig(
        tx: Double,
        ty: Double,
        dep: Int,
        dir: Double,
        sca: Double,
        wid: Double,
        ang: Double,
        lea: Boolean,
        leaSize: Double,
        out: MutableList<ShanShuiContour>,
    ) {
        // 上游 :1001-1002
        val tl = 10
        val hs = ShanShuiPrng.global.rnd() * 0.5 + 0.5
        // 上游 :1010 —— randChoice([fun2])（单元素数组也要抽 1 次）
        val tfun: (Double) -> Double = { x -> -1.0 / Math.pow(x + 1.0, 5.0) + 1.0 }
        ShanShuiUtil.randChoice(listOf(tfun))
        // 上游 :1011
        val a0 = ((ShanShuiPrng.global.rnd() * Math.PI) / 6) * dir + ang

        val twlist = ArrayList<DPoint>(tl)
        for (i in 0 until tl) {
            // 上游 :1013-1022
            val mx = dir * tfun(i.toDouble() / tl) * 50 * sca * hs
            val my = -i * 5 * sca
            val a = Math.atan2(my, mx)
            val d = Math.pow(mx * mx + my * my, 0.5)
            val nx = Math.cos(a + a0) * d
            val ny = Math.sin(a + a0) * d
            twlist.add(doubleArrayOf(nx + tx, ny + ty))
            // 上游 :1023-1031 —— 递归子枝
            if ((i == ((tl / 3) or 0) || i == (((tl * 2) / 3) or 0)) && dep > 0) {
                // ⚠️ 上游 `dir: dir * randChoice([-1,1])` 是**实参** ⇒ 先于递归调用求值
                val ndir = dir * ShanShuiUtil.randChoice(listOf(-1.0, 1.0))
                twig(
                    tx = nx + tx,
                    ty = ny + ty,
                    dep = dep - 1,
                    dir = ndir,
                    sca = sca * 0.8,
                    wid = wid,
                    ang = ang,
                    lea = lea,
                    leaSize = leaSize,
                    out = out,
                )
            }
            // 上游 :1032-1052 —— 末梢叶簇（5 片）
            if (i == tl - 1 && lea) {
                for (j in 0 until 5) {
                    val dj = (j - 2.5) * 5
                    // ① 上游 :1039 —— `wid`
                    val bwid = (6 + 3 * ShanShuiPrng.global.rnd()) * wid
                    // ② 上游 :1040 —— `len`
                    val blen = (15 + 12 * ShanShuiPrng.global.rnd()) * wid
                    // ③ 上游 :1042 —— `ang`
                    val bang = ang / 2 + Math.PI / 2 + Math.PI * 0.2 *
                        (ShanShuiPrng.global.rnd() - 0.5)
                    // ④ 上游 blob 内部 `:606` 的 `n0`（在 dab 内部）
                    val bcol = ShanShuiColor(
                        100,
                        100,
                        100,
                        ShanShuiUtil.toFixed(0.5 + dep * 0.2, 3),
                    )
                    val plist = ShanShuiBrush.dab(
                        nx + tx + Math.cos(ang) * dj * wid,
                        ny + ty + (Math.sin(ang) * dj - leaSize / (dep + 1)) * wid,
                        blen,
                        bwid,
                        bang,
                        bcol,
                        BLOB_NOI_DEFAULT,
                        ::twigLeafFun,
                    )
                    out.add(ShanShuiContour(plist, bcol, bcol, 0.0))
                }
            }
        }
        // 上游 :1054-1060 —— 枝干描边（不传 `out` ⇒ 缺省 1；不传 `noi` ⇒ 缺省 0.5）
        strokeD(twlist, 1.0, { x -> Math.cos((x * Math.PI) / 2) }, 0.5, twigGray, 1.0)
            ?.let { out.add(it) }
    }

    /**
     * 照抄上游 [barkify]（`:1064-1189`）：树皮（树皮块 + 裂纹 + 苔点）。
     *
     * ## 抽取顺序（上游逐行）
     * - 主循环每个 i：`p`（1 次）→ `Math.random() < 0.2`（1 次）→
     *   [blob 分支：blob 内部 n0（1 次）] 或 [bark 分支：len / n0 / fr / stroke 的 n0（4 次）]
     *   → `Math.random() < 0.05`（1 次）→ 若成立：`jl`（1 次）+ `randChoice`（1 次）
     *   + 每个苔点 `len`（1 次）+ blob 内部 n0（1 次）；
     * - 分组：`Math.random() < 0.5`（每点 1 次）；
     * - 抖动：`randGaussian()`（次数不定，拒绝采样）。
     *
     * ⚠️⚠️ **上游的「别名改动」必须复现**：`:1174` 的 `rglist[i] = div(rglist[i], 4)`
     *    里 `div`（上游 `:647-649`）把**原数组的末点按引用**放进结果 ⇒
     *    随后的 `rglist[i][j][0] += …`（`:1176-1179`）**就地改写了 `trlist` 的点**！
     *    ⇒ tree04/05/06 的主干轮廓是**被树皮抖动改过**的。
     *    本移植的 [divD] 同样按引用放入末点 ⇒ 行为与上游一致。
     */
    private fun barkify(
        x: Double,
        y: Double,
        trlist: List<List<DPoint>>,
        out: MutableList<ShanShuiContour>,
    ) {
        // 上游 :1114-1162 —— 主循环
        for (i in 2 until trlist[0].size - 1) {
            // 上游 :1115-1122
            val a0 = Math.atan2(
                trlist[0][i][1] - trlist[0][i - 1][1],
                trlist[0][i][0] - trlist[0][i - 1][0],
            )
            val a1 = Math.atan2(
                trlist[1][i][1] - trlist[1][i - 1][1],
                trlist[1][i][0] - trlist[1][i - 1][0],
            )
            // 上游 :1123
            val p = ShanShuiPrng.global.rnd()
            val nx = trlist[0][i][0] * (1 - p) + trlist[1][i][0] * p
            val ny = trlist[0][i][1] * (1 - p) + trlist[1][i][1] * p
            // 上游 :1126
            if (ShanShuiPrng.global.rnd() < 0.2) {
                // 上游 :1127-1133 —— blob（`noi: 1` ⇒ 恒 1；`len: 15` 固定）
                val plist = ShanShuiBrush.dab(
                    nx + x,
                    ny + y,
                    15.0,
                    6 - Math.abs(p - 0.5) * 10,
                    (a0 + a1) / 2,
                    barkBlobGray,
                    1.0,
                )
                out.add(ShanShuiContour(plist, barkBlobGray, barkBlobGray, 0.0))
            } else {
                // 上游 :1135-1140 —— bark
                bark(nx + x, ny + y, 5 - Math.abs(p - 0.5) * 10, (a0 + a1) / 2, out)
            }
            // 上游 :1143-1161 —— 苔点
            if (ShanShuiPrng.global.rnd() < 0.05) {
                // 上游 :1144
                val jl = ShanShuiPrng.global.rnd() * 2 + 2
                // 上游 :1145-1148
                val xya = ShanShuiUtil.randChoice(
                    listOf(
                        doubleArrayOf(trlist[0][i][0], trlist[0][i][1], a0),
                        doubleArrayOf(trlist[1][i][0], trlist[1][i][1], a1),
                    ),
                )
                // 上游 :1149 —— `jl` 是**浮点** ⇒ 循环次数 = ceil(jl)
                var j = 0
                while (j < jl) {
                    // 上游 :1155 —— `len`
                    val blen = 4 + 6 * ShanShuiPrng.global.rnd()
                    val plist = ShanShuiBrush.dab(
                        xya[0] + x + Math.cos(xya[2]) * (j - jl / 2) * 4,
                        xya[1] + y + Math.sin(xya[2]) * (j - jl / 2) * 4,
                        blen,
                        4.0,
                        a0 + Math.PI / 2,
                        barkBlobGray,
                        BLOB_NOI_DEFAULT,
                    )
                    out.add(ShanShuiContour(plist, barkBlobGray, barkBlobGray, 0.0))
                    j++
                }
            }
        }

        // 上游 :1163 —— trflist = trlist[0].concat(trlist[1].slice().reverse())
        val trflist = trlist[0] + trlist[1].asReversed()
        // 上游 :1164-1171 —— 分组
        val rglist = ArrayList<MutableList<DPoint>>()
        rglist.add(ArrayList())
        for (v in trflist) {
            if (ShanShuiPrng.global.rnd() < 0.5) {
                rglist.add(ArrayList())
            } else {
                rglist[rglist.size - 1].add(v)
            }
        }
        // 上游 :1173-1187
        for (i in rglist.indices) {
            // ⚠️ 上游的 `div` 把**原数组的末点按引用**放进结果（见本函数 KDoc 的别名说明）
            val row = divD(rglist[i], 4)
            for (j in row.indices) {
                row[j][0] += (ShanShuiNoise.noise(i.toDouble(), j * 0.1, 1.0) - 0.5) *
                    (15 + 5 * ShanShuiUtil.randGaussian())
                row[j][1] += (ShanShuiNoise.noise(i.toDouble(), j * 0.1, 2.0) - 0.5) *
                    (15 + 5 * ShanShuiUtil.randGaussian())
            }
            // 上游 :1181-1186 —— stroke(..., {wid:1.5, col:"rgba(100,100,100,0.7)", out:0})
            strokeD(
                row.map { doubleArrayOf(it[0] + x, it[1] + y) },
                1.5,
                { t -> Math.sin(t * Math.PI) },
                0.5,
                barkStrokeGray,
                0.0,
            )?.let { out.add(it) }
        }
    }

    /**
     * 照抄上游 `barkify` 的内嵌函数 `bark`（`:1065-1111`）：一块树皮。
     *
     * 抽取顺序：`len`（1 次）→ `n0`（1 次）→ `fr`（1 次）→ `stroke` 的 n0（1 次）。
     */
    private fun bark(
        x: Double,
        y: Double,
        wid: Double,
        ang: Double,
        out: MutableList<ShanShuiContour>,
    ) {
        // 上游 :1066
        val len = 10 + 10 * ShanShuiPrng.global.rnd()
        // 上游 :1067
        val noi = 0.5
        // 上游 :1073
        val reso = 20.0
        // 上游 :1076-1084
        val lalist = ArrayList<DPoint>(21)
        for (i in 0..reso.toInt()) {
            val p = (i / reso) * 2
            val xo = len / 2 - Math.abs(p - 1) * len
            val yo = (barkFun(p) * wid) / 2
            val a = Math.atan2(yo, xo)
            val l = Math.sqrt(xo * xo + yo * yo)
            lalist.add(doubleArrayOf(l, a))
        }
        // 上游 :1085-1091
        val nslist = ArrayList<Double>(lalist.size)
        val n0 = ShanShuiPrng.global.rnd() * 10
        for (i in 0 until lalist.size) {
            nslist.add(ShanShuiNoise.noise(i * 0.05, n0))
        }
        ShanShuiUtil.loopNoise(nslist)
        // 上游 :1092-1098
        val brklist = ArrayList<DPoint>(lalist.size)
        for (i in lalist.indices) {
            val ns = nslist[i] * noi + (1 - noi)
            val nx = x + Math.cos(lalist[i][1] + ang) * lalist[i][0] * ns
            val ny = y + Math.sin(lalist[i][1] + ang) * lalist[i][0] * ns
            brklist.add(doubleArrayOf(nx, ny))
        }
        // 上游 :1099
        val fr = ShanShuiPrng.global.rnd()
        // 上游 :1100-1108 —— stroke(brklist, {wid:0.8, noi:0, col:"rgba(100,100,100,0.4)", out:0})
        strokeD(
            brklist,
            0.8,
            { t -> Math.sin((t + fr) * Math.PI * 3) },
            0.0,
            barkLineGray,
            0.0,
        )?.let { out.add(it) }
    }

    // ─── 内部辅助 ──────────────────────────────────────────────

    /** 上游的中间点用 Float64（port 的 [ShanShuiPath.Point] 是 Float32 ⇒ 精度不够）。 */
    private typealias DPoint = DoubleArray

    /** 上游 `branch` 的 `det` 缺省值（`:924`）。 */
    private const val BRANCH_DET_DEFAULT = 10.0

    /** 上游 `branch` 的 `ben` 缺省值（`:925`）。 */
    private const val BRANCH_BEN_DEFAULT = Math.PI * 0.2

    /** 上游 `twig` 的 `wid` 缺省值（`:995`）。 */
    private const val TWIG_WID_DEFAULT = 1.0

    /** 上游 `twig` 的 `lea` 缺省值 `[true, 12]`（`:997`）。 */
    private const val TWIG_LEA_DEFAULT = 12.0

    /** 上游 `blob` 的 `noi` 缺省值（`:584`）。 */
    private const val BLOB_NOI_DEFAULT = 0.5

    /** 上游 `tree08` 的 `fracTree` 的 `len` 缺省值（`:1609`）。 */
    private const val FRACTREE_LEN_DEFAULT = 15.0

    /** 上游 `tree08` 的 `fracTree` 的 `ben` 缺省值（`:1610`）。 */
    private const val FRACTREE_BEN_DEFAULT = 0.0

    /** 上游树皮裂纹线色 `"rgba(100,100,100,0.4)"`（上游 `:1103`）。 */
    private val barkLineGray: ShanShuiColor = ShanShuiColor(100, 100, 100, 0.4)

    /** 上游树皮块描边色 `"rgba(100,100,100,0.7)"`（上游 `:1185`）。 */
    private val barkStrokeGray: ShanShuiColor = ShanShuiColor(100, 100, 100, 0.7)

    /** 上游树皮 blob 色 `"rgba(100,100,100,0.6)"`（上游 `:1132` / `:1157`）。 */
    private val barkBlobGray: ShanShuiColor = ShanShuiColor(100, 100, 100, 0.6)

    /** 上游细枝线色 `"rgba(100,100,100,0.5)"`（上游 `:1060` / `:1650`）。 */
    private val twigGray: ShanShuiColor = ShanShuiColor(100, 100, 100, 0.5)

    /**
     * 上游 `stroke`（`:516-576`）的 **Float64** 复刻。
     *
     * ⚠️ 为什么不用 [ShanShuiBrush.ribbon]：它只吃 `Float32` 点 ⇒ 中途会多一次量化
     *    （~1e-5）⇒ 与上游的 0.01 网格摘要对不上。本移植的中间量全程 Float64，
     *    只在生成 [ShanShuiContour] 时才落到 Float32。
     *
     * @param out 上游 `stroke` 的 `out` 形参（poly 的线宽，缺省 **1**）——上游 tree04–08
     *            的 stroke 调用有显式 `out: 0`（⇒ 线宽 0），twig/fracTree 不传（⇒ 缺省 1）。
     */
    private fun strokeD(
        pts: List<DPoint>,
        wid: Double,
        strokeFun: (Double) -> Double,
        noi: Double,
        col: ShanShuiColor,
        out: Double,
    ): ShanShuiContour? {
        // 上游 :531-533 —— 空点列返回 "" ⇒ **不发射**轮廓
        if (pts.isEmpty()) return null
        // 上游 :534-561
        val vtxlist0 = ArrayList<DPoint>()
        val vtxlist1 = ArrayList<DPoint>()
        val n0 = ShanShuiPrng.global.rnd() * 10
        for (i in 1 until pts.size - 1) {
            var w = wid * strokeFun(i.toDouble() / pts.size)
            w = w * (1 - noi) + w * noi * ShanShuiNoise.noise(i * 0.5, n0)
            val a1 = Math.atan2(pts[i][1] - pts[i - 1][1], pts[i][0] - pts[i - 1][0])
            val a2 = Math.atan2(pts[i][1] - pts[i + 1][1], pts[i][0] - pts[i + 1][0])
            var a = (a1 + a2) / 2
            if (a < a2) a += Math.PI
            vtxlist0.add(
                doubleArrayOf(pts[i][0] + w * Math.cos(a), pts[i][1] + w * Math.sin(a)),
            )
            vtxlist1.add(
                doubleArrayOf(pts[i][0] - w * Math.cos(a), pts[i][1] - w * Math.sin(a)),
            )
        }
        // 上游 :563-567 —— [首] + 上缘 + [末] + 下缘倒序 + [首]
        val vtx = ArrayList<DPoint>(pts.size * 2 + 2)
        vtx.add(pts[0])
        vtx.addAll(vtxlist0)
        vtx.add(pts[pts.size - 1])
        vtx.addAll(vtxlist1.asReversed())
        vtx.add(pts[0])
        // 上游 :569-574 —— poly(vtxlist, {fil:col, str:col, wid:out})
        return ShanShuiContour(
            vtx.map { toPoint(it) },
            col,
            col,
            out,
        )
    }

    /**
     * 上游 `poly`（`:485-510`）的等价物：加 xof/yof 偏移 + 生成一条轮廓。
     *
     * ⚠️ 上游 `poly` 的 `str` 缺省等于 `fil`；本函数两个都显式传。
     */
    private fun polyContour(
        pts: List<DPoint>,
        xof: Double,
        yof: Double,
        fill: ShanShuiColor?,
        stroke: ShanShuiColor?,
        wid: Double,
    ): ShanShuiContour =
        ShanShuiContour(
            pts.map { floatArrayOf((it[0] + xof).toFloat(), (it[1] + yof).toFloat()) },
            fill,
            stroke,
            wid,
        )

    /**
     * 上游 `div`（`:627-651`）的 **Float64** 复刻（折线等分）。
     *
     * ⚠️⚠️ **末点按引用放入**（上游 `:648`：`rlist.push(plist[plist.length - 1])`）——
     *    这是上游 `barkify` 抖动会**改写 `trlist`** 的根源（见 [barkify]）⇒ 必须照抄。
     */
    private fun divD(plist: List<DPoint>, reso: Int): MutableList<DPoint> {
        val tl = (plist.size - 1) * reso
        // ⚠️ 空点列 ⇒ `tl` 为负 ⇒ 不能按 `tl + 1` 预分配（上游 `var rlist = []` 无预分配）
        val rlist = ArrayList<DPoint>(if (tl > 0) tl + 1 else 0)
        for (i in 0 until tl) {
            val lastp = plist[Math.floor(i.toDouble() / reso).toInt()]
            val nextp = plist[Math.ceil(i.toDouble() / reso).toInt()]
            val p = (i % reso).toDouble() / reso
            val nx = lastp[0] * (1 - p) + nextp[0] * p
            val ny = lastp[1] * (1 - p) + nextp[1] * p
            rlist.add(doubleArrayOf(nx, ny))
        }
        // 上游 :647-649 —— 末点**原样**入列（不复制 ⇒ 别名）
        if (plist.isNotEmpty()) rlist.add(plist[plist.size - 1])
        return rlist
    }

    /**
     * 上游 `splice(0, 1)` + `splice(len - 1, 1)`（tree04 `:1256-1257` 等）：去掉首尾两点。
     *
     * ⚠️ JS 的 `splice(-1, 1)` 在空数组上**不删任何东西** ⇒ 这里做空表保护。
     */
    private fun trimEnds(src: List<DPoint>): List<DPoint> {
        val out = ArrayList<DPoint>(src.size)
        if (src.isEmpty()) return out
        for (i in 1 until src.size - 1) out.add(src[i])
        return out
    }

    /** Float64 点 → Float32（port 的 [ShanShuiPath.Point]）。 */
    private fun toPoint(p: DPoint): ShanShuiPath.Point =
        floatArrayOf(p[0].toFloat(), p[1].toFloat())

    /**
     * 上游 tree02 自带的 blob fun（`:829-833`）。
     *
     * 移植来源：上游 `:829-833`。移植状态：**照抄**。
     */
    private fun tree02Fun(x: Double): Double =
        if (x <= 1) {
            Math.pow(Math.sin(x * Math.PI) * x, 0.5)
        } else {
            -Math.pow(Math.sin((x - 2) * Math.PI * (x - 2)), 0.5)
        }

    /**
     * 上游 tree03 叶簇的 `shape` 函数（`:879-881`）：
     * `function(x){ return Math.log(50 * x + 1) / 3.95; }`
     *
     * 移植来源：上游 `:879-881`。移植状态：**照抄**。
     */
    private fun tree03Shape(x: Double): Double = Math.log(50 * x + 1) / 3.95

    /**
     * 上游 `twig` 末梢叶的 blob fun（`:1044-1048`）。
     *
     * 移植来源：上游 `:1044-1048`。移植状态：**照抄**。
     */
    private fun twigLeafFun(x: Double): Double =
        if (x <= 1) {
            Math.pow(Math.sin(x * Math.PI) * x, 0.5)
        } else {
            -Math.pow(Math.sin((x - 2) * Math.PI * (x - 2)), 0.5)
        }

    /**
     * 上游 `barkify` 的 `bark` 的 blob fun（`:1068-1072`）。
     *
     * 移植来源：上游 `:1068-1072`。移植状态：**照抄**。
     */
    private fun barkFun(x: Double): Double =
        if (x <= 1) {
            Math.pow(Math.sin(x * Math.PI), 0.5)
        } else {
            -Math.pow(Math.sin((x + 1) * Math.PI), 0.5)
        }

    /**
     * 上游 tree07 叶簇的 blob fun（`:1543-1547`）。
     *
     * 移植来源：上游 `:1543-1547`。移植状态：**照抄**。
     */
    private fun tree07Fun(x: Double): Double =
        if (x <= 1) {
            2.75 * x * Math.pow(1 - x, 1 / 1.8)
        } else {
            2.75 * (x - 2) * Math.pow(x - 1, 1 / 1.8)
        }

    /**
     * 上游 `fil: "white"` 对应的白色填充（上游 tree03 `:914` / tree04–08 的主干白底用）。
     *
     * 移植来源：上游 `:914`。移植状态：**照抄**（改造-④：字符串 → 纯数据）。
     */
    private val whiteFill: ShanShuiColor = ShanShuiColor(255, 255, 255, 1.0)
}
