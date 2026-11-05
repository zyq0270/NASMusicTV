package com.nasmusic.tv.visualizer.qianli.port

/**
 * 一个图元轮廓 —— 上游**一次** `poly()` 发射的等价物。
 *
 * 移植来源：上游 `index.html:485-510` 的 `poly(plist, args)`（`fil` / `str` / `wid` 三参数）。
 * 移植状态：**改造-⑤**（SVG 字符串 → 纯数据轮廓）。
 *
 * ⚠️ 改造理由：port 早期版本把上游的多条独立 `poly()` 发射拍平成一个 `List<Point>`，
 * 渲染器无法区分轮廓边界 ⇒ 画出来是横贯全屏的乱线，且每笔的 fill/stroke/width 丢失
 * ⇒ E50 §5 的「按坡面行号染色」与「白文留白」无法实现。
 */
data class ShanShuiContour(
    /** 点列（世界坐标，已加 xoff/yoff 偏移）。 */
    val pts: List<ShanShuiPath.Point>,
    /** 填充色；`null` = 不填充（上游 `fil: "none"`）。 */
    val fill: ShanShuiColor?,
    /** 描边色；`null` = 不描边（上游 `str: "none"`）。 */
    val stroke: ShanShuiColor?,
    /** 描边宽度（上游 `poly` 的 `wid`）。 */
    val width: Double,
    /**
     * **派生数据标记**（上游没有这个概念，默认 `null` = 上游原生图元）。
     *
     * 目前只有 [SHANSHUI_TAG_BODY_BASE] 一个取值。
     */
    val tag: String? = null,
)

/**
 * 派生数据标记：**山体底缘折线**（`Mount.mountain` 的点阵末行）。
 *
 * ⛔ **上游没有这个图元** —— 它是给渲染器用的派生数据（上游那条三角形白底照抄保留，语义未动）。
 *
 * 为什么需要它：上游的 WHITE BG（`index.html:1875-1881`）在纸底上**隐形**
 * （`:4028` 整幅 SVG `mix-blend-mode:multiply` ⇒ 纯白 × 米白纸底 = 纸底），
 * 所以上游视觉上的「山体下缘」其实是**这条不规则的底行折线**。
 * E50 把山体涂成实色（青绿）后，白底的下缘就会露出来 ⇒ 必须按真实底缘着色，才不会有硬直边。
 */
const val SHANSHUI_TAG_BODY_BASE = "bodyBase"
