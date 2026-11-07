#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""E54 §五 选型的**体量证据**取证：只打「✅ 可做」池，把每个上游的真实体积量出来。

为什么要这个脚本（2026-11-04）：v3.0/v4.0 的选型表里「代码量」那一列一直是**人工估计**
（「~420 行」「5-8K 行」），而 §五 的人日预算完全建立在它上面。观感裁决把 38 条裁掉以后，
剩下的池子只有 10 条，「按估计排优先级」这件事第一次变得可以逐条实测 —— 所以必须实测。

口径（写清楚，避免被当成别的东西读）：
- `src_bytes` = GitHub `/languages` 返回的**全部语言字节数之和**，⚠️ 它是「这个仓库里被
  语言识别器认出来的文本总量」，含关卡数据里的 JSON/JS、含测试、含工具脚本 —— 所以它是
  **上限而不是精确行数**。
- `est_lines` = `src_bytes / LINES_PER_BYTE` 折算出的**估算行数**，只是为了和旧的「行数」口径
  对话；⛔ 不许把它当测量值引用，选型时须读源码确认（对应决策 D2）。
- `size_kb` = 仓库**整体**体积（含贴图/音频/历史外的全部文件），单位 KiB。
  ⚠️ 它和 `src_bytes` 的比值就是「素材占比」的信号：比值大 ⇒ 这个上游是**贴图驱动**的，
  移植时要另算资产预算（§七）。

输出：`logs_temp/e54_pool10_sizes.json`，由 `scripts/e54_pool_table_join.py` 的
`E54-POOL10` 标记区消费。⛔ 正文里那 10 行的数字只能来自这里，不许手抄。

用法：
    python scripts/e54_pool10_sizes.py            # 需要 GITHUB_TOKEN / gh auth token
环境变量 GITHUB_TOKEN → `gh auth token` → 匿名（60 次/小时，10 条 ×2 请求勉强够，但会如实标 UNKNOWN）。
"""

import json
import os
import sys
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from e54_upstream_verify import api_get, get_token, e54_verdict  # noqa: E402

OUT = "logs_temp/e54_pool10_sizes.json"
VERIFY = "logs_temp/e54_upstream_verify.json"

LINES_PER_BYTE = 35.0  # 折算系数（JS/C/GDScript/Dart 的常见量级），⚠️ 只用于对话旧口径

# 文件树按后缀归成四类，用来回答 §七 那个「需不需要贴图」的问题 —— 旧正文是**猜**的
# （「所有推荐候选都是全程序化绘制，没有一张图片文件」），现在用 blob.size 直接量。
KINDS = {
    "image": ("png", "jpg", "jpeg", "gif", "webp", "bmp", "tif", "tiff", "ico", "kra", "aseprite", "xpm", "tga"),
    "audio": ("mp3", "ogg", "wav", "flac", "m4a", "opus", "mod", "xm", "s3m", "it"),
    "model": ("glb", "gltf", "fbx", "obj", "dae", "blend"),
    "video": ("mp4", "webm", "mov", "avi", "mkv"),
    "font": ("ttf", "otf", "woff", "woff2", "fnt"),
}


def kind_of(path):
    low = str(path).lower()
    ext = low.rsplit(".", 1)[-1] if "." in low.rsplit("/", 1)[-1] else ""
    for kind, exts in KINDS.items():
        if ext in exts:
            return kind
    return "other"


def tree_bytes(path, branch, token):
    """一次 recursive tree 调用，按类型汇总 blob 字节。返回 None = 取证失败。

    ⚠️ GitHub 对超大树会置 `truncated=true`（此时汇总只是**下界**，必须如实标注）。
    """
    st, tree, _ = api_get("https://api.github.com/repos/%s/git/trees/%s?recursive=1"
                          % (path, urllib.parse.quote(branch or "HEAD")), token)
    if st != 200 or not isinstance(tree, dict):
        return None
    agg = {"n_files": 0, "truncated": bool(tree.get("truncated"))}
    for k in list(KINDS) + ["other"]:
        agg[k + "_bytes"] = 0
        agg[k + "_count"] = 0
    for x in (tree.get("tree") or []):
        if x.get("type") != "blob":
            continue
        k = kind_of(x.get("path", ""))
        sz = int(x.get("size") or 0)
        agg["n_files"] += 1
        agg[k + "_bytes"] += sz
        agg[k + "_count"] += 1
    return agg


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if not os.path.exists(VERIFY):
        print("缺少 %s —— 先跑 python scripts/e54_upstream_verify.py" % VERIFY)
        return 2
    with open(VERIFY, encoding="utf-8-sig") as f:
        snap = json.load(f)
    rows = snap["rows"]
    if not snap.get("authed"):
        print("⛔ 快照 %s 是**未认证**那一轮取的 ⇒ 数字作废，先复跑 e54_upstream_verify.py" % VERIFY)
        return 2
    token = get_token()
    if not token:
        print("⚠️ 没有令牌（匿名配额 60 次/小时）—— 取到的数字可能大面积 UNKNOWN")

    ok = [r for r in rows if e54_verdict(r)[0].startswith("✅")]
    print("✅ 可做池 %d 条，逐条取证体量…" % len(ok))

    out = {}
    bad = 0
    for r in ok:
        path = (r.get("renamed_to") or r.get("path") or "").strip()
        key = path.lower()
        rec = {"repo": path}
        st, meta, _ = api_get("https://api.github.com/repos/%s" % path, token)
        if st != 200 or not isinstance(meta, dict):
            print("  ⛔ %s -> repo HTTP %s" % (path, st))
            rec["error"] = "repo HTTP %s" % st
            bad += 1
            out[key] = rec
            continue
        rec.update(
            size_kb=meta.get("size"),
            lang=meta.get("language"),
            stars=meta.get("stargazers_count"),
            pushed_at=(meta.get("pushed_at") or "")[:10],
            archived=bool(meta.get("archived")),
            fork=bool(meta.get("fork")),
            desc=(meta.get("description") or "").strip(),
            homepage=(meta.get("homepage") or "").strip(),
            default_branch=meta.get("default_branch"),
        )
        st2, langs, _ = api_get("https://api.github.com/repos/%s/languages" % path, token)
        if st2 == 200 and isinstance(langs, dict) and langs:
            top = sorted(langs.items(), key=lambda kv: -kv[1])
            rec["top"] = [[k, v] for k, v in top[:4]]
            rec["src_bytes"] = sum(langs.values())
            rec["est_lines"] = int(rec["src_bytes"] / LINES_PER_BYTE)
            rec["asset_ratio"] = round(1024.0 * rec["size_kb"] / rec["src_bytes"], 1)
        else:
            # ⚠️ 200 + 空映射 是**实测到的事实**，不是抓取失败：语言识别给不出任何源码字节
            #    （soldat/soldat 实测如此 ⇒ 体量只能人工读源定，⛔ 不许编一个数）。
            rec["src_bytes"] = 0 if st2 == 200 else None
            rec["langs_status"] = st2
            if st2 != 200:
                rec["error"] = "languages HTTP %s" % st2
                bad += 1
        tree = tree_bytes(path, rec.get("default_branch"), token)
        if tree is None:
            rec["tree_error"] = True
            bad += 1
        else:
            rec["tree"] = tree
        print("  %-34s %8s KiB  src=%9s B  ~%6s 行  图%9s B/%-4s 音%8s B  ★%-5s %s"
              % (path, rec.get("size_kb"), rec.get("src_bytes"), rec.get("est_lines"),
                 (tree or {}).get("image_bytes"), (tree or {}).get("image_count"),
                 (tree or {}).get("audio_bytes"), rec.get("stars"), rec.get("pushed_at")))
        out[key] = rec

    os.makedirs("logs_temp", exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("\n写入 %s（%d 条，失败 %d 条）" % (OUT, len(out), bad))
    # 自检：✅ 池一条都不许缺，缺了就说明这张表会在正文里少一行
    if bad:
        print("⛔ 有 %d 条没取到体量 ⇒ §五 的表会缺证据，本轮产物不采信" % bad)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
