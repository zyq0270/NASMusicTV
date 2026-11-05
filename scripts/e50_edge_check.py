"""E50「千里江山」**水平直边 / 山体下缘形状**取证脚本（可复用）。

用途：E50 的中景山体曾在层带下沿被「齐刷刷切一刀」⇒ 画面出现一条贯穿全宽的水平直边
（真机 2712×1220 截图：最强水平边在 y=673 = fracY 0.552 = `MID_Y1`，边像素 788，第二名仅 317）。
本脚本用来复现/复核这条判据，**不靠目测**。

用法（从**仓库根**跑）：

    python scripts/e50_edge_check.py edges  <png> [阈值]
    python scripts/e50_edge_check.py bottom <png> [列起点] [列终点]

- `edges`  ：逐行统计「与上一行颜色差 > 阈值」的像素数 ⇒ 取前 10 名（y / fracY / 边像素数 / 占宽比）。
  **判据**：若最强水平边落在层带下沿（中景 0.55~0.60 / 近景 0.88）且边像素数远超第二名
  ⇒ 那条直边还在。
- `bottom` ：逐列找「最底的非背景像素」⇒ 打印下缘折线（x / y / fracY）⇒ 判断下缘是
  **水平直线**（坏）还是**不规则折线**（好）。

⛔ 本脚本只做**测量**，不做判红；判据在 `QianliBodyBaseGateTest`（Kotlin 门禁）里。
"""
import sys
from collections import Counter

from PIL import Image


def edges(path, thr=60):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()
    rows = []
    for y in range(1, h):
        cnt = 0
        for x in range(0, w, 2):
            r0, g0, b0 = px[x, y - 1]
            r1, g1, b1 = px[x, y]
            if abs(r0 - r1) + abs(g0 - g1) + abs(b0 - b1) > thr:
                cnt += 1
        rows.append((y, cnt))
    print("file=%s %dx%d  阈值=%d" % (path, w, h, thr))
    print("=== 最强的 10 条水平边 ===")
    for y, n in sorted(rows, key=lambda kv: -kv[1])[:10]:
        print("   y=%5d fracY=%.3f  边像素=%5d (%.0f%% 宽)" % (y, y / h, n, 100.0 * n / (w / 2)))
    print()
    print("参考：层带下沿 —— 中景 MID_Y1=0.60 / 近景 NEAR_Y1=0.88（直边若出现在这两处即为回归）")


def bottom(path, lo=0, hi=60):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()
    bg = Counter(px[x, y] for y in range(0, h, 3) for x in range(0, w, 3)).most_common(1)[0][0]
    print("file=%s %dx%d  背景色=%s" % (path, w, h, bg))
    pts = []
    for x in range(0, w, 20):
        last = None
        for y in range(h - 1, -1, -1):
            c = px[x, y]
            if sum(abs(c[i] - bg[i]) for i in range(3)) > 40:
                last = y
                break
        if last is not None:
            pts.append((x, last))
    print("=== 下缘折线（每 20px 取一列）===")
    for x, y in pts[lo:hi]:
        print("   x=%5d  底=%5d  fracY=%.3f" % (x, y, y / h))
    ys = [y for _, y in pts]
    if ys:
        print()
        print("下缘 y 的极差 = %d px（=0 表示一条水平直线 ⇒ 坏）" % (max(ys) - min(ys)))


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    mode = sys.argv[1]
    path = sys.argv[2]
    if mode == "edges":
        edges(path, int(sys.argv[3]) if len(sys.argv) > 3 else 60)
    elif mode == "bottom":
        bottom(
            path,
            int(sys.argv[3]) if len(sys.argv) > 3 else 0,
            int(sys.argv[4]) if len(sys.argv) > 4 else 60,
        )
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main()
