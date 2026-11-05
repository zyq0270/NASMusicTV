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
)
