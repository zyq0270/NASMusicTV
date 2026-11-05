/**
 * 扁舟与人物图元层 —— 扁舟（`Arch.boat01`）、渔人（`Man.man`）、斗笠（`Man.hat01` /
 * `Man.hat02`）、撑杆（`Man.stick01`）。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 *
 * ## 移植状态一览
 * | 本仓 | 上游 | 行号 | 状态 |
 * |---|---|---|---|
 * | [boat01] | `Arch.boat01` | `:3209-3248` | **照抄** |
 * | [man] | `Man.man` | `:3522-3664` | **照抄** |
 * | [hat01] | `Man.hat01` | `:3406-3449` | **照抄** |
 * | [hat02] | `Man.hat02` | `:3450-3521` | **照抄** |
 * | [stick01] | `Man.stick01` | `:3487-3513` | **照抄**（`boat01` 内部用作 `ite`） |
 * | （私有）[expand] | `Man.expand` | `:3325-3382` | **照抄**（衣袖/身体的布片展开） |
 * | （私有）[tranpoly] | `Man.tranpoly` | `:3384-3399` | **照抄**（帽子的坐标变换） |
 * | （私有）[flipper] | `Man.flipper` | `:3400-3404` | **照抄**（左右翻转） |
 * | （私有）[bezmh] | `bezmh` | `:445-483` | **照抄**（Float64 版，见下） |
 *
 * ## ⛔ 本轮**没有**动 [ShanShuiArch]
 * `ShanShuiArch.kind1`（= 上游 `arch01`）内部**仍然只抽随机、不渲染渔人**
 * （改造-② 的 [ShanShuiArch.burnMan] 保持原样）⇒ **`shan_shui_arch01_golden.json`
 * 不会漂移**。本文件是**新增**的独立图层，接线（把 `arch01` 的渔人真正画出来、
 * 把 `boat01` 接进 planner）由后续轮次完成。
 *
 * ## 上游 `Man.man` 的「画几个衣袖/身体/头」是怎么决定的
 * ⛔ **不是随机的** —— 它是**固定 4 块**，由骨架树 `sct` 决定：
 * ```
 * sct = { 0: { 1: { 2: {}, 5: { 6: {} }, 7: { 8: {} } }, 3: { 4: {} } } }
 *            ↑  根      ↑  躯干   ↑头 ↑左手 ↑右手 ↑左脚 ↑右脚
 * ```
 * - `cloth([pts1, pts7, pts8], fsleeve)` ⇒ **左衣袖**（肩 → 肘 → 手）
 * - `cloth([pts1, pts0, pts3, pts4], fbody)` ⇒ **身体**（肩 → 胯 → 膝 → 脚）
 * - `cloth([pts1, pts5, pts6], fsleeve)` ⇒ **右衣袖**（肩 → 肘 → 手）
 * - `cloth([pts1, pts2], fhead)` ⇒ **头**（颈 → 头心）
 * 每块 `cloth` 发射 **3 条轮廓**（白底多边形 + 两侧描边）⇒ 4 × 3 = **12 条**；
 * 再加「头」（1 条）+「帽子」（1~2 条）+「撑杆 `ite`」（0~1 条）。
 *
 * ### 随机**只**出现在四个地方（**抽取次数与上游逐次对齐**）
 * | 位置 | 内容 | 次数 |
 * |---|---|---|
 * | `:3539` | `ang[2] = normRand(0, 0)`（值恒 0，**仍消耗 1 次**） | 1 |
 * | `:3540` | `ang[3] = (PI/4) * Math.random()` | 1 |
 * | `:3541` | `ang[4] = ((PI*3)/4) * Math.random()` | 1 |
 * | `:3544` | `ang[7] = (-PI*3)/4 - (PI/4) * Math.random()` | 1 |
 * | `:3329` | `expand()` 的 `n0`（**死变量**，但消耗 1 次）—— 每块 `cloth` 1 次 × 4 | 4 |
 * | `:537` | `stroke()` 的 `n0` —— 每条 `stroke` 1 次 × (4 块 × 2 + 头 0) | 8 |
 * | `:3654` | 头部 `expand()` 的 `n0` | 1 |
 * | `:3411` | `hat01` 的 `seed`（**死变量**，但消耗 1 次） | 1 |
 * | **合计（hat01 + 无 ite）** | | **18** |
 * | **合计（hat02 + `ite=stick01`，= `boat01` 内部那次）** | | **19** |
 * | **合计（`boat01` 整体）** | 19 + `stroke` 的 1 次 | **20** |
 *
 * ## ⚠️ 上游的三处「**原地**改写」（JS `Array.prototype.reverse/splice` 的副作用）
 * 1. `cloth`（`:3611`）：`tlist2.reverse()` **原地**改写 `tlist2`
 *    ⇒ 后面 `stroke(tlist2.map(toGlobal))` 用的是**倒序**后的 `tlist2`。本移植**照抄**。
 * 2. 头部（`:3657`）：`hlist2.reverse()` **原地**改写 `hlist2`（此后不再用，无可观测差异）。
 * 3. `boat01`（`:3237`）：`plist2.reverse()` **原地**改写 `plist2`
 *    ⇒ `plist = plist1.concat(plist2.reverse())` 的**下半段是倒序**的。
 *
 * ## ⚠️ `Man.man` 的 `yoff` 会被**改写**
 * 上游 `:3598` 的 `yoff -= pts[4][1]` 改写的是 `toGlobal` 闭包**捕获**的那个 `yoff`
 * ⇒ 之后**所有** `toGlobal(...)` 都用**改写后**的 `yoff`（把人物放到脚底对齐地面）。
 * 本移植用可变的 `gy` 变量逐字复现。
 *
 * ## ⚠️ 浮点精度（为什么本文件自己实现 `bezmh` / `stroke` 的 ribbon）
 * 上游全程 **Float64**；port 的 [ShanShuiPath.Point] 是 **Float32**。
 * ⇒ 本文件内部用 `DoubleArray`（`DPoint`）复刻上游的**每一步中间计算**，
 * 只在**生成轮廓**的那一刻量化到 Float32（与上游 `poly()` 的 `.toFixed(1)` 同序）。
 * ⛔ **不能**直接复用 [ShanShuiPath.bezmh] / [ShanShuiBrush.ribbon]：
 *   两者都在**中途**就量化成 Float32（`floatArrayOf`）⇒ 与上游的 Float64 链差 ~1e-6。
 *   （[ShanShuiPath.bezmh] 的 `midPt` 还用 `(a+b)/2`，上游 `PolyTools.midPt` 是
 *   `b/2 + a/2` ⇒ 舍入次序不同。）
 *
 * ## 门禁
 * `ShanShuiBoatManGateTest`：`boat01` / `man` / `hat01`（及 `manBoat` / `hat02` / `stick01`）
 * 的**轮廓条数 / 总点数 / 结构摘要 / 0.1 网格摘要 / 0.01 网格摘要 / PRNG 抽取次数**
 * 与上游**逐位**相等（期望值在
 * `app/src/test/resources/qianli/shan_shui_boat_man_golden.json`，⛔ 不在测试源码里；
 * 由 `node scripts/e50_boat_man_upstream_probe.js` 实跑上游原始 JS 生成）。
 *
 * ⛔ port 包零 Android 依赖 ⇒ 本对象**不出现** `android.graphics.Path` / `Canvas`。
 * 所有 `Math.random()` → [ShanShuiPrng.global.rnd()]；所有 `Noise.noise()` → [ShanShuiNoise.noise]；
 * 所有 `randChoice` → [ShanShuiUtil.randChoice]；所有 `normRand` → [ShanShuiUtil.normRand]。
 */

package com.nasmusic.tv.visualizer.qianli.port

object ShanShuiBoat {

    /** 上游 `Man.hat01` / `Man.hat02` 的种类（上游用**函数引用**当参数）。 */
    enum class Hat {
        /** 上游 `Man.hat01`（`:3406-3449`）—— 斗笠。 */
        HAT01,

        /** 上游 `Man.hat02`（`:3450-3521`）—— 另一种帽子（`boat01` 用它）。 */
        HAT02,
    }

    /** 上游 `Man.stick01`（`ite`）的种类。 */
    enum class Ite {
        /** 上游 `man` 的缺省 `ite`（`:3529-3531`）—— 返回空串、**不发射也不抽取**。 */
        NONE,

        /** 上游 `Man.stick01`（`:3487-3513`）—— 撑杆。 */
        STICK01,
    }

    // ─── Arch.boat01（照抄上游 `:3209-3248`） ───────────────────────

    /**
     * 上游 [Arch.boat01]（`:3209-3248`）：扁舟。
     *
     * 移植状态：**照抄**（改造-① SVG→轮廓、④ 字符串→纯数据、⑤ 轮廓样式）。
     *
     * ## 发射顺序（与上游逐条对应）
     * 1. **渔人**（`:3217-3223`）：`Man.man(xoff + 20*sca*dir, yoff, {
     *    ite: Man.stick01, hat: Man.hat02, sca: 0.5*sca, fli: !fli,
     *    len: [0,30,20,30,10,30,30,30,30] })`
     *    ⇒ **19 次抽取** / **15 条轮廓** / **659 点**（上游实跑）。
     * 2. **船身白底**（`:3238`）：`poly(plist, {xof, yof, fil:"white"})`
     *    ⇒ **48 点**（`len*sca = 120` ⇒ `i = 0,5,…,115` 共 24 段 × 上下两条曲线）。
     * 3. **船身描边**（`:3239-3245`）：`stroke(plist.map(...), {wid:1, fun: sin(x*PI*2),
     *    col:"rgba(100,100,100,0.4)"})` ⇒ **95 点**（`stroke` 的 ribbon = 2n-1）。
     *
     * ⇒ 合计 **20 次抽取** / **17 条轮廓** / **802 点**（上游实跑，见黄金基线）。
     *
     * @param seed 上游的 `seed` 形参 —— ⛔ **上游自己从不使用它**（照抄，保留形参）。
     * @param len 上游的 `len`（缺省 120）—— 船身长度（世界单位）。
     * @param sca 上游的 `sca`（缺省 1）—— 缩放。
     * @param fli 上游的 `fli`（缺省 false）—— 是否左右翻转（同时决定渔人的朝向）。
     */
    fun boat01(
        xoff: Double,
        yoff: Double,
        seed: Double,
        len: Double = 120.0,
        sca: Double = 1.0,
        fli: Boolean = false,
    ): List<ShanShuiContour> {
        val canv = ArrayList<ShanShuiContour>()

        // ⚠️ 上游的 `seed` 形参**从未被使用**（照抄；⛔ 不许偷偷拿它去喂噪声）。
        @Suppress("UNUSED_VARIABLE")
        val unusedSeed = seed

        // 上游 :3216 —— dir = fli ? -1 : 1
        val dir = if (fli) -1.0 else 1.0

        // 上游 :3217-3223 —— 船上的渔人（`ite` = 撑杆、`hat` = hat02）
        canv.addAll(
            man(
                xoff + 20 * sca * dir,
                yoff,
                sca = 0.5 * sca,
                hat = Hat.HAT02,
                ite = Ite.STICK01,
                fli = !fli,
                len = MAN_LEN_BOAT,
            )
        )

        // 上游 :3225-3236 —— 上下两条船身曲线
        val fun1: (Double) -> Double = { x -> Math.pow(Math.sin(x * Math.PI), 0.5) * 7 * sca }
        val fun2: (Double) -> Double = { x -> Math.pow(Math.sin(x * Math.PI), 0.5) * 10 * sca }
        val plist1 = ArrayList<DPoint>()
        val plist2 = ArrayList<DPoint>()
        var i = 0.0
        // 上游 :3233 —— `for (var i = 0; i < len * sca; i += 5 * sca)`
        // ⚠️ 步长是 `5 * sca`、上限是 `len * sca`，但曲线取样用的是 `i / len`（**不是** `i / (len*sca)`）。
        while (i < len * sca) {
            plist1.add(doubleArrayOf(i * dir, fun1(i / len)))
            plist2.add(doubleArrayOf(i * dir, fun2(i / len)))
            i += 5 * sca
        }
        // 上游 :3237 —— ⚠️ `plist2.reverse()` **原地**改写 ⇒ 下半段是**倒序**的
        plist2.reverse()
        val plist = ArrayList<DPoint>(plist1.size + plist2.size)
        plist.addAll(plist1)
        plist.addAll(plist2)

        // 上游 :3238 —— poly(plist, {xof: xoff, yof: yoff, fil: "white"})
        val globalPlist = plist.map { doubleArrayOf(it[0] + xoff, it[1] + yoff) }
        canv.add(polyContour(globalPlist, WHITE, WHITE, 0.0))

        // 上游 :3239-3245 —— stroke(plist.map(v => [xoff+v[0], yoff+v[1]]),
        //   {wid:1, fun: x => sin(x*PI*2), col:"rgba(100,100,100,0.4)"})
        // ⚠️ 上游 `stroke` 的缺省 `noi = 0.5`、`out = 1`。
        strokeContour(
            globalPlist,
            wid = 1.0,
            strokeFun = { x -> Math.sin(x * Math.PI * 2) },
            noi = 0.5,
            col = GREY_04,
        )?.let { canv.add(it) }

        return canv
    }

    // ─── Man.man（照抄上游 `:3522-3664`） ───────────────────────────

    /**
     * 上游 [Man.man]（`:3522-3664`）：渔人 / 亭中人。
     *
     * 移植状态：**照抄**（改造-①/④/⑤）。
     *
     * ## 发射顺序（与上游逐条对应）
     * 1. **撑杆 `ite`**（`:3646`）：`ite(toGlobal(pts[8]), toGlobal(pts[6]), {fli})`
     *    —— 缺省 `ite` 返回空串 ⇒ **不发射、也不抽取**；`stick01` ⇒ 1 条 / 12 点 / 1 次抽取。
     * 2. **四块 `cloth`**（`:3648-3651`）：左衣袖 / 身体 / 右衣袖 / 头。
     *    每块 3 条轮廓（白底 + 两条描边）⇒ 12 条；每块 3 次抽取（`expand` 的 n0 + 2 次 `stroke`）。
     * 3. **头**（`:3653-3659`）：`bezmh([pts1,pts2], 2)` → `expand` → 掐头去尾 ⇒ 1 条 / 21 点 / 1 次抽取。
     * 4. **帽子 `hat`**（`:3661`）：`hat01` ⇒ 2 条 / 17 点 / 1 次抽取；
     *    `hat02` ⇒ 1 条 / 10 点 / 1 次抽取。
     *
     * ## 骨架树 `sct`（上游 `:3554-3556`）
     * `{0:{1:{2:{},5:{6:{}},7:{8:{}}},3:{4:{}}}}`
     * ⇒ `gpar`（父链）：`0→[0]`、`1→[0,1]`、`2→[0,1,2]`、`3→[0,3]`、`4→[0,3,4]`、
     * `5→[0,1,5]`、`6→[0,1,5,6]`、`7→[0,1,7]`、`8→[0,1,7,8]`。
     * 关节坐标 `gpos` = 沿父链把 `len[a] * (cos(Σang), sin(Σang))` 累加。
     *
     * @param sca 上游的 `sca`（缺省 0.5）—— 同时缩放 `len` 与三块布的宽度。
     * @param hat 上游的 `hat`（缺省 `Man.hat01`）。
     * @param ite 上游的 `ite`（缺省「返回空串的函数」）。
     * @param fli 上游的 `fli`（缺省 **true**）—— 左右翻转。
     * @param ang 上游的 `ang`（缺省 = 含 4 次随机的那一组角度）；给了就**不抽**那 4 次。
     * @param len 上游的 `len`（缺省 `[0,30,20,30,30,30,30,30,30]`）；会再乘 `sca`。
     */
    fun man(
        xoff: Double,
        yoff: Double,
        sca: Double = 0.5,
        hat: Hat = Hat.HAT01,
        ite: Ite = Ite.NONE,
        fli: Boolean = true,
        ang: DoubleArray? = null,
        len: DoubleArray? = null,
    ): List<ShanShuiContour> {
        val canv = ArrayList<ShanShuiContour>()

        // ── 上游 :3533-3546 —— `ang` 的缺省数组（**左到右**求值 ⇒ 4 次抽取） ──
        // ⚠️ JS 的数组字面量按**下标顺序**求值 ⇒ 抽取次序 = normRand → rnd → rnd → rnd。
        val angArr: DoubleArray = ang ?: doubleArrayOf(
            0.0, // :3537
            -Math.PI / 2, // :3538
            ShanShuiUtil.normRand(0.0, 0.0), // :3539（**消耗 1 次抽取**；值恒为 0）
            (Math.PI / 4) * ShanShuiPrng.global.rnd(), // :3540
            ((Math.PI * 3) / 4) * ShanShuiPrng.global.rnd(), // :3541
            (Math.PI * 3) / 4, // :3542
            -Math.PI / 4, // :3543
            (-Math.PI * 3) / 4 - (Math.PI / 4) * ShanShuiPrng.global.rnd(), // :3544
            -Math.PI / 4, // :3545
        )

        // ── 上游 :3547-3552 —— `len` 的缺省数组 × sca ──
        val lenArr: DoubleArray = (len ?: MAN_LEN_DEFAULT).map { it * sca }.toDoubleArray()

        // ⚠️ 上游 :3598 的 `yoff -= pts[4][1]` **改写**了 `toGlobal` 闭包捕获的 `yoff`
        var gy = yoff

        // 上游 :3557-3559 —— toGlobal
        fun toGlobal(v: DPoint): DPoint =
            doubleArrayOf((if (fli) -1.0 else 1.0) * v[0] + xoff, v[1] + gy)

        // ── 上游 :3594-3597 —— 9 个关节的坐标 ──
        val pts = ArrayList<DPoint>(angArr.size)
        for (i in angArr.indices) {
            pts.add(gpos(i, angArr, lenArr))
        }

        // ── 上游 :3598 —— 把人物挪到「脚底贴地」 ──
        gy -= pts[4][1]

        // ── 上游 :3600-3605 —— **死代码**（算出来的 `s` 从未被使用）──
        // ⛔ 它**不发射**任何轮廓、也**不消耗随机数** ⇒ 本移植不重写（只在此留档）。

        // ── 上游 :3626-3644 —— 三块布的宽度函数 ──
        val fsleeve: (Double) -> Double = { x ->
            sca * 8 * (Math.sin(0.5 * x * Math.PI) * Math.pow(Math.sin(x * Math.PI), 0.1) +
                (1 - x) * 0.4)
        }
        val fbody: (Double) -> Double = { x ->
            sca * 11 * (Math.sin(0.5 * x * Math.PI) * Math.pow(Math.sin(x * Math.PI), 0.1) +
                (1 - x) * 0.5)
        }
        val fhead: (Double) -> Double = { x ->
            sca * 7 * Math.pow(0.25 - Math.pow(x - 0.5, 2.0), 0.3)
        }

        // ── 上游 :3607-3624 —— cloth（衣袖/身体/头的布片展开）──
        fun cloth(plist: List<DPoint>, wfun: (Double) -> Double): List<ShanShuiContour> {
            val out = ArrayList<ShanShuiContour>(3)
            // 上游 :3609 —— bezmh(plist, 2)
            val tlist = bezmh(plist, 2.0)
            // 上游 :3610 —— expand 的 n0 **消耗 1 次抽取**
            val (tlist1, tlist2) = expand(tlist, wfun)
            // 上游 :3611 —— ⚠️ `tlist2.reverse()` **原地**改写 ⇒ 下面的 stroke 用的是**倒序**的 tlist2
            tlist2.reverse()
            // 上游 :3611-3613 —— 白底多边形
            out.add(polyContour((tlist1 + tlist2).map { toGlobal(it) }, WHITE, WHITE, 0.0))
            // 上游 :3614-3617 —— 左侧描边
            strokeContour(
                tlist1.map { toGlobal(it) },
                wid = 1.0,
                strokeFun = { x -> Math.sin(x * Math.PI) },
                noi = 0.5,
                col = GREY_05,
            )?.let { out.add(it) }
            // 上游 :3618-3621 —— 右侧描边（**倒序**的 tlist2）
            strokeContour(
                tlist2.map { toGlobal(it) },
                wid = 1.0,
                strokeFun = { x -> Math.sin(x * Math.PI) },
                noi = 0.5,
                col = GREY_06,
            )?.let { out.add(it) }
            return out
        }

        // ── 上游 :3646 —— 撑杆（`ite`）──
        if (ite == Ite.STICK01) {
            canv.addAll(stick01(toGlobal(pts[8]), toGlobal(pts[6]), fli))
        }

        // ── 上游 :3648-3651 —— 四块布（左衣袖 / 身体 / 右衣袖 / 头）──
        canv.addAll(cloth(listOf(pts[1], pts[7], pts[8]), fsleeve))
        canv.addAll(cloth(listOf(pts[1], pts[0], pts[3], pts[4]), fbody))
        canv.addAll(cloth(listOf(pts[1], pts[5], pts[6]), fsleeve))
        canv.addAll(cloth(listOf(pts[1], pts[2]), fhead))

        // ── 上游 :3653-3659 —— 头（掐掉头尾各一段）──
        val hlist = bezmh(listOf(pts[1], pts[2]), 2.0)
        val (hlist1, hlist2) = expand(hlist, fhead)
        // 上游 :3655-3656 —— splice(0, floor(len * 0.1)) / splice(0, floor(len * 0.95))
        hlist1.subList(0, Math.floor(hlist1.size * 0.1).toInt()).clear()
        hlist2.subList(0, Math.floor(hlist2.size * 0.95).toInt()).clear()
        // 上游 :3657 —— ⚠️ `hlist2.reverse()` **原地**改写
        hlist2.reverse()
        canv.add(polyContour((hlist1 + hlist2).map { toGlobal(it) }, GREY_06, GREY_06, 0.0))

        // ── 上游 :3661 —— 帽子 ──
        canv.addAll(
            when (hat) {
                Hat.HAT01 -> hat01(toGlobal(pts[1]), toGlobal(pts[2]), fli)
                Hat.HAT02 -> hat02(toGlobal(pts[1]), toGlobal(pts[2]), fli)
            }
        )

        return canv
    }

    // ─── Man.hat01（照抄上游 `:3406-3449`） ───────────────────────

    /**
     * 上游 [Man.hat01]（`:3406-3448`）：斗笠。
     *
     * 移植状态：**照抄**（改造-①/④/⑤）。
     *
     * ## 发射（与上游逐条对应）
     * 1. **笠身**（`:3418-3433`）：`poly(tranpoly(p0, p1, f([7 点])), {fil:"rgba(100,100,100,0.8)"})`
     *    ⇒ **7 点**；`str` 缺省 = `fil` ⇒ 描边同色；`wid` 缺省 = 0。
     * 2. **笠檐**（`:3435-3445`）：10 个点，`x = -0.3 - Noise.noise(i*0.2, seed)*i*0.1`、
     *    `y = 0.5 - i*0.3`；`poly(..., {str:"rgba(100,100,100,0.8)", wid:1})`
     *    ⇒ **10 点**；`fil` 缺省 = `"rgba(0,0,0,0)"`（透明）。
     *
     * ⇒ **1 次抽取**（`:3411` 的 `seed`，**死变量**）/ **2 条轮廓** / **17 点**（上游实跑）。
     */
    fun hat01(p0: DoubleArray, p1: DoubleArray, fli: Boolean): List<ShanShuiContour> {
        val canv = ArrayList<ShanShuiContour>(2)

        // ⚠️ 上游 :3411 的 `seed` **从未被使用**（死变量）⇒ 但它**消耗 1 次随机抽取** ⇒ 必须保留
        @Suppress("UNUSED_VARIABLE")
        val seed = ShanShuiPrng.global.rnd()

        // 上游 :3412-3416 —— f = fli ? flipper : identity
        val f: (List<DPoint>) -> List<DPoint> = if (fli) ::flipper else { it -> it }

        // 上游 :3418-3433 —— 笠身
        canv.add(
            polyContour(
                tranpoly(
                    p0,
                    p1,
                    f(
                        listOf(
                            doubleArrayOf(-0.3, 0.5),
                            doubleArrayOf(0.3, 0.8),
                            doubleArrayOf(0.2, 1.0),
                            doubleArrayOf(0.0, 1.1),
                            doubleArrayOf(-0.3, 1.15),
                            doubleArrayOf(-0.55, 1.0),
                            doubleArrayOf(-0.65, 0.5),
                        )
                    ),
                ),
                GREY_08,
                GREY_08,
                0.0,
            )
        )

        // 上游 :3435-3445 —— 笠檐
        val qlist1 = ArrayList<DPoint>(10)
        for (i in 0 until 10) {
            qlist1.add(
                doubleArrayOf(
                    -0.3 - ShanShuiNoise.noise(i * 0.2, seed) * i * 0.1,
                    0.5 - i * 0.3,
                )
            )
        }
        canv.add(
            polyContour(
                tranpoly(p0, p1, f(qlist1)),
                TRANSPARENT,
                GREY_08,
                1.0,
            )
        )

        return canv
    }

    // ─── Man.hat02（照抄上游 `:3450-3521`） ───────────────────────

    /**
     * 上游 [Man.hat02]（`:3450-3485`）：另一种帽子（`boat01` 的渔人戴它）。
     *
     * 移植状态：**照抄**（改造-①/④/⑤）。
     *
     * ## 发射（与上游逐条对应）
     * - **帽身**（`:3465-3483`）：`poly(tranpoly(p0, p1, f([10 点])), {fil:"rgba(100,100,100,0.8)"})`
     *   ⇒ **10 点**；`str` 缺省 = `fil`；`wid` 缺省 = 0。
     * - ⚠️ 上游 `:3462-3464` 的另一条 `poly` **被注释掉了** ⇒ 不发射。
     *
     * ⇒ **1 次抽取**（`:3455` 的 `seed`，**死变量**）/ **1 条轮廓** / **10 点**（上游实跑）。
     */
    fun hat02(p0: DoubleArray, p1: DoubleArray, fli: Boolean): List<ShanShuiContour> {
        val canv = ArrayList<ShanShuiContour>(1)

        // ⚠️ 上游 :3455 的 `seed` **从未被使用**（死变量）⇒ 但它**消耗 1 次随机抽取** ⇒ 必须保留
        @Suppress("UNUSED_VARIABLE")
        val seed = ShanShuiPrng.global.rnd()

        // 上游 :3457-3461 —— f = fli ? flipper : identity
        val f: (List<DPoint>) -> List<DPoint> = if (fli) ::flipper else { it -> it }

        // 上游 :3465-3483 —— 帽身
        canv.add(
            polyContour(
                tranpoly(
                    p0,
                    p1,
                    f(
                        listOf(
                            doubleArrayOf(-0.3, 0.5),
                            doubleArrayOf(-1.1, 0.5),
                            doubleArrayOf(-1.2, 0.6),
                            doubleArrayOf(-1.1, 0.7),
                            doubleArrayOf(-0.3, 0.8),
                            doubleArrayOf(0.3, 0.8),
                            doubleArrayOf(1.0, 0.7),
                            doubleArrayOf(1.3, 0.6),
                            doubleArrayOf(1.2, 0.5),
                            doubleArrayOf(0.3, 0.5),
                        )
                    ),
                ),
                GREY_08,
                GREY_08,
                0.0,
            )
        )

        return canv
    }

    // ─── Man.stick01（照抄上游 `:3487-3513`） ──────────────────────

    /**
     * 上游 [Man.stick01]（`:3487-3513`）：撑杆（`man` 的 `ite`）。
     *
     * 移植状态：**照抄**（改造-①/④/⑤）。
     *
     * ## 发射（与上游逐条对应）
     * - 12 个点：`x = -Noise.noise(i*0.1, seed) * 0.1 * sin((i/12)*PI) * 5`、`y = i*0.3`；
     *   `poly(tranpoly(p0, p1, f(qlist1)), {str:"rgba(100,100,100,0.5)", wid:1})`
     *   ⇒ **12 点**；`fil` 缺省 = `"rgba(0,0,0,0)"`（透明）。
     *
     * ⇒ **1 次抽取**（`:3492` 的 `seed`，**死变量**）/ **1 条轮廓** / **12 点**（上游实跑）。
     */
    fun stick01(p0: DoubleArray, p1: DoubleArray, fli: Boolean): List<ShanShuiContour> {
        val canv = ArrayList<ShanShuiContour>(1)

        // ⚠️ 上游 :3492 的 `seed` **从未被使用**（死变量）⇒ 但它**消耗 1 次随机抽取** ⇒ 必须保留
        @Suppress("UNUSED_VARIABLE")
        val seed = ShanShuiPrng.global.rnd()

        // 上游 :3493-3497 —— f = fli ? flipper : identity
        val f: (List<DPoint>) -> List<DPoint> = if (fli) ::flipper else { it -> it }

        // 上游 :3499-3510 —— 杆身
        val qlist1 = ArrayList<DPoint>(12)
        val l = 12
        for (i in 0 until l) {
            qlist1.add(
                doubleArrayOf(
                    -ShanShuiNoise.noise(i * 0.1, seed) * 0.1 * Math.sin((i.toDouble() / l) * Math.PI) * 5,
                    0.0 + i * 0.3,
                )
            )
        }
        canv.add(
            polyContour(
                tranpoly(p0, p1, f(qlist1)),
                TRANSPARENT,
                GREY_05,
                1.0,
            )
        )

        return canv
    }

    // ─── 私有：上游的内部辅助 ─────────────────────────────────────

    /**
     * 上游 [Man.expand]（`:3325-3382`）：把一条折线「展开」成**上下两条**边（布片的缝合边）。
     *
     * 移植状态：**照抄**（Float64）。
     *
     * - `n0`（`:3329`）是**死变量**（⛔ 但消耗 1 次随机抽取 ⇒ 必须保留）。
     * - 内层循环用 `wfun(i / ptlist.length)`（分母是**点数**，不是 `点数-1`）。
     * - 两端各补一个端点（`unshift` + `push`）⇒ 输出长度 = 输入长度。
     */
    private fun expand(
        ptlist: List<DPoint>,
        wfun: (Double) -> Double,
    ): Pair<MutableList<DPoint>, MutableList<DPoint>> {
        val vtxlist0 = ArrayList<DPoint>(ptlist.size)
        val vtxlist1 = ArrayList<DPoint>(ptlist.size)

        // ⚠️ 上游 :3329 的 `n0` **从未被使用**（死变量）⇒ 但它**消耗 1 次随机抽取** ⇒ 必须保留
        @Suppress("UNUSED_VARIABLE")
        val n0 = ShanShuiPrng.global.rnd() * 10

        // 上游 :3330-3352
        for (i in 1 until ptlist.size - 1) {
            val w = wfun(i.toDouble() / ptlist.size)
            val a1 = Math.atan2(
                ptlist[i][1] - ptlist[i - 1][1],
                ptlist[i][0] - ptlist[i - 1][0],
            )
            val a2 = Math.atan2(
                ptlist[i][1] - ptlist[i + 1][1],
                ptlist[i][0] - ptlist[i + 1][0],
            )
            var a = (a1 + a2) / 2
            if (a < a2) {
                a += Math.PI
            }
            vtxlist0.add(
                doubleArrayOf(
                    ptlist[i][0] + w * Math.cos(a),
                    ptlist[i][1] + w * Math.sin(a),
                )
            )
            vtxlist1.add(
                doubleArrayOf(
                    ptlist[i][0] - w * Math.cos(a),
                    ptlist[i][1] - w * Math.sin(a),
                )
            )
        }

        // 上游 :3353-3380 —— 两端的端点
        val l = ptlist.size - 1
        val a0 = Math.atan2(
            ptlist[1][1] - ptlist[0][1],
            ptlist[1][0] - ptlist[0][0],
        ) - Math.PI / 2
        val a1 = Math.atan2(
            ptlist[l][1] - ptlist[l - 1][1],
            ptlist[l][0] - ptlist[l - 1][0],
        ) - Math.PI / 2
        val w0 = wfun(0.0)
        val w1 = wfun(1.0)
        vtxlist0.add(
            0,
            doubleArrayOf(
                ptlist[0][0] + w0 * Math.cos(a0),
                ptlist[0][1] + w0 * Math.sin(a0),
            ),
        )
        vtxlist1.add(
            0,
            doubleArrayOf(
                ptlist[0][0] - w0 * Math.cos(a0),
                ptlist[0][1] - w0 * Math.sin(a0),
            ),
        )
        vtxlist0.add(
            doubleArrayOf(
                ptlist[l][0] + w1 * Math.cos(a1),
                ptlist[l][1] + w1 * Math.sin(a1),
            ),
        )
        vtxlist1.add(
            doubleArrayOf(
                ptlist[l][0] - w1 * Math.cos(a1),
                ptlist[l][1] - w1 * Math.sin(a1),
            ),
        )

        return Pair(vtxlist0, vtxlist1)
    }

    /**
     * 上游 [Man.tranpoly]（`:3384-3399`）：把「局部坐标的帽子」搬到 `p0→p1` 这条轴上。
     *
     * 移植状态：**照抄**（Float64）。
     */
    private fun tranpoly(p0: DPoint, p1: DPoint, ptlist: List<DPoint>): List<DPoint> {
        // 上游 :3385-3387 —— 局部坐标先左右翻转
        val plist = ptlist.map { doubleArrayOf(-it[0], it[1]) }
        // 上游 :3388
        val ang = Math.atan2(p1[1] - p0[1], p1[0] - p0[0]) - Math.PI / 2
        // 上游 :3389
        val scl = ShanShuiUtil.distance(p0, p1)
        // 上游 :3390-3397
        return plist.map { v ->
            val d = ShanShuiUtil.distance(v, ORIGIN)
            val a = Math.atan2(v[1], v[0])
            doubleArrayOf(
                p0[0] + d * scl * Math.cos(ang + a),
                p0[1] + d * scl * Math.sin(ang + a),
            )
        }
    }

    /** 上游 [Man.flipper]（`:3400-3404`）：左右翻转（返回**新**数组，不原地改写）。 */
    private fun flipper(plist: List<DPoint>): List<DPoint> =
        plist.map { doubleArrayOf(-it[0], it[1]) }

    /**
     * 上游 [bezmh]（`:445-483`）：过中点二次贝塞尔平滑（`pl = 20`）。
     *
     * 移植状态：**照抄**（**Float64** 版 —— ⛔ 不能用 [ShanShuiPath.bezmh]，那个在中途就量化成 Float32）。
     *
     * ⚠️ 上游 `PolyTools.midPt`（`:208-222`）的两点版是 `b/2 + a/2`（reduce 的累加次序）
     * ⇒ 本文件的 [midPt] 逐字复现，⛔ 不许换成 `(a + b) / 2`。
     */
    private fun bezmh(P: List<DPoint>, w: Double): List<DPoint> {
        val pl = 20
        val plist = ArrayList<DPoint>()
        // 上游 :447-449
        val p: List<DPoint> = if (P.size == 2) {
            listOf(P[0], midPt(P[0], P[1]), P[1])
        } else {
            P
        }
        // 上游 :451-481
        for (j in 0 until p.size - 2) {
            val p0 = if (j == 0) p[j] else midPt(p[j], p[j + 1])
            val p1 = p[j + 1]
            val p2 = if (j == p.size - 3) p[j + 2] else midPt(p[j + 1], p[j + 2])
            for (i in 0 until (pl + if (j == p.size - 3) 1 else 0)) {
                val t = i.toDouble() / pl
                val u = Math.pow(1 - t, 2.0) + 2 * t * (1 - t) * w + t * t
                plist.add(
                    doubleArrayOf(
                        (
                            Math.pow(1 - t, 2.0) * p0[0] +
                                2 * t * (1 - t) * p1[0] * w +
                                t * t * p2[0]
                            ) / u,
                        (
                            Math.pow(1 - t, 2.0) * p0[1] +
                                2 * t * (1 - t) * p1[1] * w +
                                t * t * p2[1]
                            ) / u,
                    )
                )
            }
        }
        return plist
    }

    /**
     * 上游 [PolyTools.midPt]（`:208-222`）的**两点**版。
     *
     * ⚠️ 上游是 `reduce` ⇒ 结果 = `b/2 + a/2`（**不是** `(a+b)/2`）⇒ 舍入次序不同。
     */
    private fun midPt(a: DPoint, b: DPoint): DPoint =
        doubleArrayOf(b[0] / 2.0 + a[0] / 2.0, b[1] / 2.0 + a[1] / 2.0)

    /**
     * 上游 [stroke]（`:516-576`）的 **Float64** 复刻 —— 变宽笔触（ribbon）。
     *
     * 移植状态：**照抄**（⛔ 不能用 [ShanShuiBrush.ribbon]：那个在中途就量化成 Float32）。
     *
     * ⚠️ 上游的 `stroke()` 对**空点列**返回 `""`（`:531-533`）⇒ **不发射、也不抽取随机数**
     * ⇒ 本函数返回 `null` 表示「未发射」。
     *
     * @param wid 上游的 `wid`（笔宽）—— 上游 `stroke` 的缺省是 2。
     * @param strokeFun 上游的 `fun`（缺省 `Math.sin(x*Math.PI)`）—— 上游参数名 `fun` 是 Kotlin 关键字。
     * @param noi 上游的 `noi`（缺省 0.5）—— 噪声干湿比例。
     * @param col 上游的 `col`（缺省 `rgba(200,200,200,0.9)`）—— 发射成 `fil = str = col`、`wid = 1`。
     */
    private fun strokeContour(
        pts: List<DPoint>,
        wid: Double,
        strokeFun: (Double) -> Double,
        noi: Double,
        col: ShanShuiColor,
    ): ShanShuiContour? {
        // 上游 :531-533 —— 空点列直接返回 ""（**不抽取随机数**）
        if (pts.isEmpty()) return null

        val vtxlist0 = ArrayList<DPoint>(pts.size)
        val vtxlist1 = ArrayList<DPoint>(pts.size)

        // 上游 :537 —— n0（**消耗 1 次随机抽取**）
        val n0 = ShanShuiPrng.global.rnd() * 10

        // 上游 :538-561
        for (i in 1 until pts.size - 1) {
            var w = wid * strokeFun(i.toDouble() / pts.size)
            w = w * (1 - noi) + w * noi * ShanShuiNoise.noise(i * 0.5, n0)
            val a1 = Math.atan2(
                pts[i][1] - pts[i - 1][1],
                pts[i][0] - pts[i - 1][0],
            )
            val a2 = Math.atan2(
                pts[i][1] - pts[i + 1][1],
                pts[i][0] - pts[i + 1][0],
            )
            var a = (a1 + a2) / 2
            if (a < a2) {
                a += Math.PI
            }
            vtxlist0.add(
                doubleArrayOf(
                    pts[i][0] + w * Math.cos(a),
                    pts[i][1] + w * Math.sin(a),
                )
            )
            vtxlist1.add(
                doubleArrayOf(
                    pts[i][0] - w * Math.cos(a),
                    pts[i][1] - w * Math.sin(a),
                )
            )
        }

        // 上游 :563-567 —— [首] + 上缘 + [末] + 下缘(倒序) + [首]
        val vtxlist = ArrayList<DPoint>(pts.size * 2 + 2)
        vtxlist.add(pts[0])
        vtxlist.addAll(vtxlist0)
        vtxlist.add(pts[pts.size - 1])
        vtxlist.addAll(vtxlist1.asReversed())
        vtxlist.add(pts[0])

        // 上游 :569-574 —— poly(vtxlist, {fil: col, str: col, wid: out=1})
        return polyContour(vtxlist, col, col, 1.0)
    }

    /**
     * 上游 [poly]（`:485-510`）的等价物 —— 一次发射 = 一条 [ShanShuiContour]。
     *
     * ⚠️ 上游 `poly` 的缺省：`fil = "rgba(0,0,0,0)"`、`str = fil`、`wid = 0`。
     * ⇒ 调用方**必须显式**给出 `str`（否则上游的 `str` 会等于 `fil`）。
     *
     * ⛔ **坐标在此才量化到 Float32**（上游是在 `poly()` 里 `.toFixed(1)`，
     * 之前的所有中间计算都是 Float64）。
     */
    private fun polyContour(
        pts: List<DPoint>,
        fil: ShanShuiColor?,
        str: ShanShuiColor?,
        wid: Double,
    ): ShanShuiContour = ShanShuiContour(
        pts.map { floatArrayOf(it[0].toFloat(), it[1].toFloat()) },
        fil,
        str,
        wid,
    )

    // ─── 骨架树（上游 `Man.man` 的 `sct`） ─────────────────────────

    /** 骨架树的一个节点（`children` 的**顺序** = JS `Object.keys` 的顺序）。 */
    private class Bone(val children: List<Pair<Int, Bone>>)

    /** 叶子节点。 */
    private val leaf: Bone = Bone(emptyList())

    /**
     * 上游 `:3554-3556` 的 `sct`：
     * `{0:{1:{2:{},5:{6:{}},7:{8:{}}},3:{4:{}}}}`。
     *
     * - 0 = 胯（根）
     * - 1 = 肩；2 = 头；5/6 = 右肘/右手；7/8 = 左肘/左手
     * - 3 = 膝；4 = 脚
     */
    private val skeleton: Bone = Bone(
        listOf(
            0 to Bone(
                listOf(
                    1 to Bone(
                        listOf(
                            2 to leaf,
                            5 to Bone(listOf(6 to leaf)),
                            7 to Bone(listOf(8 to leaf)),
                        )
                    ),
                    3 to Bone(listOf(4 to leaf)),
                )
            ),
        )
    )

    /**
     * 上游 `gpar`（`:3561-3574`）：求关节 `ind` 的**父链**（自根到该关节）。
     *
     * ⚠️ 上游用 `Object.keys`（**整数键按升序**）遍历，⛔ 本移植的 `children` 顺序必须与之一致。
     */
    private fun gpar(ind: Int): List<Int>? = gparIn(skeleton.children, ind)

    private fun gparIn(nodes: List<Pair<Int, Bone>>, ind: Int): List<Int>? {
        for ((key, bone) in nodes) {
            if (key == ind) return listOf(ind)
            val r = gparIn(bone.children, ind)
            if (r != null) return listOf(key) + r
        }
        return null
    }

    /** 上游 `grot`（`:3575-3582`）：父链上的角度之和。 */
    private fun grot(ind: Int, ang: DoubleArray): Double {
        val par = gpar(ind) ?: return 0.0
        var rot = 0.0
        for (a in par) {
            rot += ang[a]
        }
        return rot
    }

    /** 上游 `gpos`（`:3583-3592`）：沿父链累加骨节向量得到的关节坐标。 */
    private fun gpos(ind: Int, ang: DoubleArray, len: DoubleArray): DPoint {
        val par = gpar(ind) ?: return doubleArrayOf(0.0, 0.0)
        val pos = doubleArrayOf(0.0, 0.0)
        for (a in par) {
            val rot = grot(a, ang)
            pos[0] += len[a] * Math.cos(rot)
            pos[1] += len[a] * Math.sin(rot)
        }
        return pos
    }

    // ─── 常量 ─────────────────────────────────────────────────────

    /** 上游的中间点用 Float64（port 的 [ShanShuiPath.Point] 是 Float32 ⇒ 精度不够）。 */
    private typealias DPoint = DoubleArray

    /** 原点（上游 `tranpoly` 里的 `distance(v, [0,0])`）。 */
    private val ORIGIN: DPoint = doubleArrayOf(0.0, 0.0)

    /** 上游 `man` 的 `len` 缺省值（`:3548`）。 */
    private val MAN_LEN_DEFAULT: DoubleArray =
        doubleArrayOf(0.0, 30.0, 20.0, 30.0, 30.0, 30.0, 30.0, 30.0, 30.0)

    /** 上游 `boat01` 传给 `man` 的 `len`（`:3222`）—— 第 5 项是 **10**（不是 30）。 */
    private val MAN_LEN_BOAT: DoubleArray =
        doubleArrayOf(0.0, 30.0, 20.0, 30.0, 10.0, 30.0, 30.0, 30.0, 30.0)

    /** 上游 `fil: "white"`（`:3238` / `:3612`）—— 白色填充（且 `str` 缺省 = `fil` ⇒ 白色描边）。 */
    private val WHITE: ShanShuiColor = ShanShuiColor(255, 255, 255, 1.0)

    /** 上游 `poly` 的 `fil` 缺省 `"rgba(0,0,0,0)"`（透明）。 */
    private val TRANSPARENT: ShanShuiColor = ShanShuiColor(0, 0, 0, 0.0)

    /** 上游 `rgba(100,100,100,0.8)`（`:3432` / `:3443` / `:3482`）—— 帽子。 */
    private val GREY_08: ShanShuiColor = ShanShuiColor.rgba(100, 100, 100, 0.8)

    /** 上游 `rgba(100,100,100,0.6)`（`:3619` / `:3658`）—— 右描边 / 头。 */
    private val GREY_06: ShanShuiColor = ShanShuiColor.rgba(100, 100, 100, 0.6)

    /** 上游 `rgba(100,100,100,0.5)`（`:3616` / `:3508`）—— 左描边 / 撑杆。 */
    private val GREY_05: ShanShuiColor = ShanShuiColor.rgba(100, 100, 100, 0.5)

    /** 上游 `rgba(100,100,100,0.4)`（`:3244`）—— 船身描边。 */
    private val GREY_04: ShanShuiColor = ShanShuiColor.rgba(100, 100, 100, 0.4)
}
