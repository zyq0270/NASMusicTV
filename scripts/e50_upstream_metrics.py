"""E50「千里江山」**画面量化对标**脚本（可复用）。

用途：判断「忠于上游」的新版本是否真的接近上游 `shan-shui-inf`，**不靠目测**。

做法：把一张图拆成「纸底」与「墨」两类，算出可比较的指标：

- `paper_ratio`  纸底像素占比（上游 ≈ 0.75~0.90 ⇒ 线描为主，不是色块）
- `ink_ratio`    墨像素占比
- `ink_gray`     墨像素的平均灰度（上游墨线是 `rgba(100,100,100,α)` ⇒ 偏浅灰）
- `row_ink[]`    每一行（fracY）的墨像素占比曲线（看纵深结构：远山带 / 中景山 / 近景岸）
- `band_ink`     四个纵向区间的墨占比（远/中/近/底）
- `color_ratio`  **彩色像素**占比（`|r-g|>12 且 |g-b|>12`）⇒ 上游是**纯灰阶**，这个值必须 ≈ 0

用法（从**仓库根**跑）：

    python scripts/e50_upstream_metrics.py <png> [<png2> ...]

⛔ 本脚本只做**测量**，不做判红；判据在 Kotlin 门禁里。
"""
import sys
from collections import Counter

from PIL import Image


def classify(c, paper_rgb, paper_tol=26):
    """判像素是「纸底」还是「墨」。"""
    d = sum(abs(c[i] - paper_rgb[i]) for i in range(3))
    if d <= paper_tol:
        return "paper"
    return "ink"


def metrics(path):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()

    # 纸底基准色 = 四角 9x9 区域的中位色（画面四角几乎必是纸）
    corner = []
    for cx in range(0, 9):
        for cy in range(0, 9):
            ox = min(cx * (w // 8), w - 1)
            oy = min(cy * (h // 8), h - 1)
            corner.append(px[ox, oy])
            corner.append(px[w - 1 - ox, oy])
            corner.append(px[ox, h - 1 - oy])
            corner.append(px[w - 1 - ox, h - 1 - oy])
    paper_rgb = Counter(corner).most_common(1)[0][0]

    n = 0
    n_paper = 0
    n_color = 0
    ink_gray_sum = 0.0
    n_ink = 0
    row_ink = [0] * h
    row_n = [0] * h
    step = 2

    for y in range(0, h, step):
        for x in range(0, w, step):
            c = px[x, y]
            n += 1
            row_n[y] += 1
            if classify(c, paper_rgb) == "ink":
                n_ink += 1
                row_ink[y] += 1
                ink_gray_sum += 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]
                if abs(c[0] - c[1]) > 12 and abs(c[1] - c[2]) > 12:
                    n_color += 1
            else:
                n_paper += 1

    rows = []
    for y in range(h):
        if row_n[y]:
            rows.append((y / h, row_ink[y] / row_n[y]))

    bands = []
    for lo, hi, label in [
        (0.00, 0.25, "top-0.00-0.25"),
        (0.25, 0.55, "mid-0.25-0.55"),
        (0.55, 0.85, "low-0.55-0.85"),
        (0.85, 1.00, "bottom-0.85-1.00"),
    ]:
        vals = [v for f, v in rows if lo <= f < hi]
        bands.append((label, sum(vals) / len(vals) if vals else 0.0))

    return {
        "path": path,
        "size": (w, h),
        "paper_rgb": paper_rgb,
        "paper_ratio": n_paper / n,
        "ink_ratio": n_ink / n,
        "ink_gray": (ink_gray_sum / n_ink) if n_ink else 0.0,
        "color_ratio": n_color / n,
        "bands": bands,
        "rows": rows,
    }


def main(paths):
    for p in paths:
        m = metrics(p)
        w, h = m["size"]
        print("=" * 72)
        print("file        :", m["path"])
        print("size        : %dx%d" % (w, h))
        print("纸底基准色  :", m["paper_rgb"])
        print("纸底占比    : %.3f" % m["paper_ratio"])
        print("墨占比      : %.3f" % m["ink_ratio"])
        print("墨平均灰度  : %.1f" % m["ink_gray"])
        print("彩色像素占比: %.4f  (上游纯灰阶 ⇒ 应 ≈ 0)" % m["color_ratio"])
        print("纵向分区墨占比:")
        for label, v in m["bands"]:
            print("   %s : %.4f" % (label, v))
        print("逐行墨占比（每 10% 一行）:")
        rows = m["rows"]
        for k in range(10):
            lo = k * 0.1
            vals = [v for f, v in rows if lo <= f < lo + 0.1]
            bar = "#" * int(60 * (sum(vals) / len(vals) if vals else 0))
            print("   %.1f-%.1f : %.4f %s" % (lo, lo + 0.1, sum(vals) / len(vals) if vals else 0, bar))
        print()


def dump_json(paths, out):
    """把指标写成机器可比的 JSON（供 Kotlin 门禁读）。"""
    import json

    data = {"files": []}
    for p in paths:
        m = metrics(p)
        data["files"].append({
            "path": p,
            "size": list(m["size"]),
            "paperRgb": list(m["paper_rgb"]),
            "paperRatio": round(m["paper_ratio"], 4),
            "inkRatio": round(m["ink_ratio"], 4),
            "inkGray": round(m["ink_gray"], 1),
            "colorRatio": round(m["color_ratio"], 5),
            "bands": [[label, round(v, 4)] for label, v in m["bands"]],
            "decade": [
                round(
                    sum(v for f, v in m["rows"] if k * 0.1 <= f < k * 0.1 + 0.1)
                    / max(1, len([1 for f, _ in m["rows"] if k * 0.1 <= f < k * 0.1 + 0.1])),
                    4,
                )
                for k in range(10)
            ],
        })
    with open(out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print("metrics json written:", out)


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--json=")]
    json_out = None
    for a in sys.argv[1:]:
        if a.startswith("--json="):
            json_out = a[len("--json="):]
    if not args:
        print(__doc__)
        sys.exit(2)
    if json_out:
        dump_json(args, json_out)
    else:
        main(args)
