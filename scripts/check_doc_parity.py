#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""逐字节比对 `main` 与本地私有分支 `feat/e51-private` 的 `docs/` 树。

为什么需要它
------------
所有者 2026-11-10 裁决：「两分支 **文档类的内容**，应该保持一致，例如：technical-overview.md，
应该都一样才行。」

问题根源：两个分支各自独立追加 `technical-overview.md` 的 §10 ⇒ **同号不同内容**
（§10.374–§10.378 五节各有两版）⇒ 文档在两分支对不上。
AGENTS.md 给的处置是「**写一份 → 覆盖到私有分支**」，但「靠自觉」守不住 —— 本脚本是那道闸。

例外（不可公开的上游衍生件 ⇒ 只在私有分支，⛔ 不得进公开仓库）
--------------------------------------------------------------
判据见 AGENTS.md「文档类资料是全局可公开的」：文档**不因为「只在某个分支存在」就自动私有** ——
判据是「是否含上游代码 / 是上游代码的翻译件或派生物本体」；我们自己的调研与研究**可以**公共发布。

| 路径 | 为什么例外 |
|---|---|
| `docs/archive/e52-highway-preview.html` | E52「旷野公路」原型页，**内含上游移植件本体** |
| `docs/archive/e52-highway-webgl-preview.html` | 同上（WebGL 版） |
| `docs/archive/verification/results/lofi_registry_2026-10-22.json` | E51 上游城市注册表（AGENTS.md 明列 ⛔ 不可公开） |
| `docs/archive/verification/results/e52_golden_vectors.json` | 上游算法的**逐位数值向量**（派生物） |
| `docs/archive/verification/results/e52_highway_harness_final.txt` | 上游对账明细（含上游方法清单与逐位结果） |
| `docs/archive/verification/scripts/e52_highway_driver.mjs` | 驱动上游页面的工具 |
| `docs/archive/verification/scripts/e52_highway_harness.js` | 复现上游行为的 harness |

用法（**从仓库根**跑）：

    python scripts/check_doc_parity.py            # 打印差异（有差异时退出码 1）
    python scripts/check_doc_parity.py --check    # 同上（语义与 sync_private_overlay.py --check 一致）

退出码
------
    0 = 两分支 docs 逐字节一致（白名单外零差异）
    1 = **有差异**（白名单外）
    2 = **无法比较**（本机没有私有分支 ref / 不是 git 仓库 / git 不可用）⇒ CI 与别人的机器
"""

from __future__ import print_function

import os
import subprocess
import sys

PRIVATE_REF = "feat/e51-private"
PUBLIC_REF = "main"
DOCS_PREFIX = "docs/"

# ⛔ 白名单：只在私有分支存在、且含上游衍生内容 ⇒ 不可公开
PRIVATE_ONLY_EXCEPTIONS = [
    "docs/archive/e52-highway-preview.html",
    "docs/archive/e52-highway-webgl-preview.html",
    "docs/archive/verification/results/lofi_registry_2026-10-22.json",
    "docs/archive/verification/results/e52_golden_vectors.json",
    "docs/archive/verification/results/e52_highway_harness_final.txt",
    "docs/archive/verification/scripts/e52_highway_driver.mjs",
    "docs/archive/verification/scripts/e52_highway_harness.js",
]


def _git(args):
    try:
        r = subprocess.run(["git"] + args, capture_output=True)
    except Exception as exc:  # git 不可用
        print("❌ 调不起 git：%s" % exc)
        return None
    if r.returncode != 0:
        return None
    return r.stdout


def _tree(ref):
    """返回 {路径: blob sha}；ref 不存在或出错 ⇒ None。"""
    out = _git(["ls-tree", "-r", "-z", ref, "--", DOCS_PREFIX])
    if out is None:
        return None
    tree = {}
    for entry in out.split(b"\0"):
        if not entry.strip():
            continue
        try:
            meta, path = entry.split(b"\t", 1)
            sha = meta.split(b" ")[2].decode("ascii")
        except (ValueError, IndexError):
            continue
        tree[path.decode("utf-8")] = sha
    return tree


def main(argv):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    pub = _tree(PUBLIC_REF)
    if pub is None:
        print("❌ 读不到 `%s` 的 docs 树（不是 git 仓库 / git 不可用）" % PUBLIC_REF)
        return 2
    priv = _tree(PRIVATE_REF)
    if priv is None:
        print("本机没有本地分支 `%s` —— 无法比较。" % PRIVATE_REF)
        print("（CI 与别人的机器上属正常：私有分支 ⛔ 从不 push）")
        return 2

    exceptions = set(PRIVATE_ONLY_EXCEPTIONS)
    only_pub = sorted(set(pub) - set(priv))
    only_priv = sorted(set(priv) - set(pub))
    common = sorted(set(pub) & set(priv))
    differ = [p for p in common if pub[p] != priv[p]]

    real_only_pub = [p for p in only_pub if p not in exceptions]
    real_only_priv = [p for p in only_priv if p not in exceptions]
    real_differ = [p for p in differ if p not in exceptions]
    used_exceptions = sorted(
        p for p in exceptions
        if (p in only_priv) or (p in differ) or (p in only_pub)
    )

    print("docs 文件：%s %d 个 / %s %d 个"
          % (PUBLIC_REF, len(pub), PRIVATE_REF, len(priv)))
    print("白名单例外（上游衍生件，只在私有分支）：%d 项" % len(used_exceptions))
    for p in used_exceptions:
        print("  · %s" % p)
    print("")

    if not real_only_pub and not real_only_priv and not real_differ:
        print("✅ 两分支 docs 逐字节一致（白名单外零差异）")
        return 0

    print("⛔ 两分支 docs 不一致：")
    for p in real_only_pub:
        print("  · 只在 %s 有：%s" % (PUBLIC_REF, p))
    for p in real_only_priv:
        print("  · 只在 %s 有：%s" % (PRIVATE_REF, p))
    for p in real_differ:
        print("  · 两边内容不同：%s" % p)
    print("")
    print("修法（AGENTS.md「写一份 → 覆盖到私有分支」，⛔ 不要 cherry-pick + 顺延编号）：")
    print("  1) 在**权威侧**改好那一份；")
    print("  2) `git checkout <另一分支> -- <路径>` 把**同一份**覆盖过去；")
    print("  3) 复跑本脚本 ⇒ 必须 0 差异。")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
