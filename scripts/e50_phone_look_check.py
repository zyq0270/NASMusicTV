"""E50 真机截图 ⇒ **对标上游基线**的一键复核（可复用）。

用途：新版本（忠于上游）装到真机后，光看截图不足以判断「像不像上游」。
本脚本量出真机截图的**量化指标**，并与上游参考图的基线逐项对比，给出 PASS/FAIL。

用法（从**仓库根**跑）：

    python scripts/e50_phone_look_check.py <真机截图.png> [--json=<输出路径>]

基线来源：`app/src/test/resources/qianli/shan_shui_upstream_look_baseline.json`
（由 `python scripts/e50_upstream_metrics.py output/e50_upstream_ref.png --json=<该路径>` 生成）。

判据（阈值为经验值，依据写在 `WHY` 里）：
- 纸底基准色：必须**暖米白**（r>230、g>215、b>185，且 r>g>b）⇒ 上游绢白；
  我们的旧版是深色天幕 (76,90,94) ⇒ 会 FAIL。
- 纸底占比：必须 ≥ 0.45 ⇒ 「线描为主」，不是色块堆。
- 墨平均灰度：必须 ≤ 215 ⇒ 上游墨是 `rgba(100,100,100,α)` 的浅灰，不是纯黑。
- 彩色像素占比：必须 ≤ 0.05 ⇒ 上游是**纯灰阶**。
- 顶部 0–0.25 墨占比：必须 > 0.005 ⇒ 上游的墨从画面上部就有
  （旧版的顶部 30% 全空 ⇒ 会 FAIL）。
- 底部 0.85–1.0 墨占比：必须 > 0.05 ⇒ 近景岸要有横向密纹。

⛔ 本脚本只做**测量与提示**，不做门禁；Kotlin 门禁在 `QianliUpstreamGateTest`。
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from e50_upstream_metrics import metrics  # noqa: E402

BASELINE = os.path.join(
    "app", "src", "test", "resources", "qianli", "shan_shui_upstream_look_baseline.json"
)


def band_value(bands, prefix):
    for label, v in bands:
        if label.startswith(prefix):
            return v
    return 0.0


def check(m):
    pr, pg, pb = m["paper_rgb"]
    top = band_value(m["bands"], "top-")
    bottom = band_value(m["bands"], "bottom-")
    checks = [
        ("纸底是暖米白 (r>230,g>215,b>185,r>g>b)",
         pr > 230 and pg > 215 and pb > 185 and pr > pg > pb,
         "纸底=%s" % (m["paper_rgb"],)),
        ("纸底占比 ≥ 0.45（线描为主）",
         m["paper_ratio"] >= 0.45,
         "%.3f" % m["paper_ratio"]),
        ("墨平均灰度 ≤ 215（浅灰墨，不是纯黑）",
         m["ink_gray"] <= 215.0,
         "%.1f" % m["ink_gray"]),
        ("彩色像素占比 ≤ 0.05（纯灰阶）",
         m["color_ratio"] <= 0.05,
         "%.4f" % m["color_ratio"]),
        ("顶部 0–0.25 墨占比 > 0.005（画面上部要有山）",
         top > 0.005,
         "%.4f" % top),
        ("底部 0.85–1.0 墨占比 > 0.05（近景岸要有密纹）",
         bottom > 0.05,
         "%.4f" % bottom),
    ]
    return checks


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--json=")]
    json_out = None
    for a in sys.argv[1:]:
        if a.startswith("--json="):
            json_out = a[len("--json="):]
    if not args:
        print(__doc__)
        sys.exit(2)

    path = args[0]
    m = metrics(path)
    w, h = m["size"]

    print("=" * 72)
    print("真机截图      :", path)
    print("尺寸          : %dx%d" % (w, h))
    print("纸底基准色    :", m["paper_rgb"])
    print("纸底占比      : %.3f" % m["paper_ratio"])
    print("墨占比        : %.3f" % m["ink_ratio"])
    print("墨平均灰度    : %.1f" % m["ink_gray"])
    print("彩色像素占比  : %.4f" % m["color_ratio"])

    # 上游基线对照
    if os.path.exists(BASELINE):
        with open(BASELINE, encoding="utf-8") as f:
            base = json.load(f)["files"][0]
        print()
        print("── 上游基线对照（上游参考图 %dx%d）──" % tuple(base["size"]))
        print("  纸底基准色  上游 %s  |  真机 %s" % (base["paperRgb"], list(m["paper_rgb"])))
        print("  纸底占比    上游 %.3f  |  真机 %.3f" % (base["paperRatio"], m["paper_ratio"]))
        print("  墨占比      上游 %.3f  |  真机 %.3f" % (base["inkRatio"], m["ink_ratio"]))
        print("  墨平均灰度  上游 %.1f  |  真机 %.1f" % (base["inkGray"], m["ink_gray"]))
        print("  彩色占比    上游 %.4f  |  真机 %.4f" % (base["colorRatio"], m["color_ratio"]))
        for (bl, bv), (ml, mv) in zip(base["bands"], m["bands"]):
            print("  %-18s 上游 %.4f  |  真机 %.4f" % (bl, bv, mv))
    else:
        print()
        print("⚠️ 未找到基线文件：", BASELINE)

    print()
    print("── 判定 ──")
    ok = True
    for name, passed, detail in check(m):
        flag = "PASS" if passed else "FAIL"
        if not passed:
            ok = False
        print("  [%s] %-46s %s" % (flag, name, detail))

    if json_out:
        with open(json_out, "w", encoding="utf-8") as f:
            json.dump(
                {
                    "path": path,
                    "size": [w, h],
                    "paperRgb": list(m["paper_rgb"]),
                    "paperRatio": round(m["paper_ratio"], 4),
                    "inkRatio": round(m["ink_ratio"], 4),
                    "inkGray": round(m["ink_gray"], 1),
                    "colorRatio": round(m["color_ratio"], 5),
                    "bands": [[label, round(v, 4)] for label, v in m["bands"]],
                },
                f,
                ensure_ascii=False,
                indent=1,
            )
            f.write("\n")
        print("metrics json written:", json_out)

    print()
    print("总体：", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
