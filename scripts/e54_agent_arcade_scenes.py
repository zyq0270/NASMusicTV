# -*- coding: utf-8 -*-
"""§5.6 方案 C 的证据脚本：agent-arcade 的**逐场景字节**与引擎绑定。

为什么单开一个脚本（而不是并进 e54_pool10_sizes.py）：
`e54_pool10_sizes.py` 量的是「整仓」的四类资产占比，回答「要不要算贴图驱动」；
§5.6 要回答的是另一个问题——**这一个仓库里到底有几个游戏、每个多大**，
粒度必须下探到 `src/game/scenes/` 的单个文件，还要读 `package.json` 确认引擎版本。

⛔ 正文里那张场景表只许由本脚本再生。第一次写的时候我是手工 curl 抄进文档的，
一旦上游加/删场景，正文就会静默失真——而「几个游戏」正是方案 C 的全部卖点。

用法：
    python scripts/e54_agent_arcade_scenes.py            # 需要 GITHUB_TOKEN / gh auth token

产物：logs_temp/e54_agent_arcade_scenes.json
"""

import json
import os
import sys
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from e54_upstream_verify import api_get, get_token  # noqa: E402

REPO = "DanWahlin/agent-arcade"
OUT = "logs_temp/e54_agent_arcade_scenes.json"
LINES_PER_BYTE = 35.0   # 与 e54_pool10_sizes.py 同一个折算常数，保持两表可对话

# 场景基类不是游戏，单独记账；正文里那行「共享基类 = 现成的切换框架」靠它
BASE_HINTS = ("basescene", "base-scene", "basescene.ts")


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    token = get_token()
    if not token:
        print("⛔ 没有 GitHub 令牌（GITHUB_TOKEN / gh auth token）⇒ 不跑。")
        return 2

    st, repo, _ = api_get("https://api.github.com/repos/%s" % REPO, token)
    if st != 200 or not isinstance(repo, dict):
        print("⛔ 仓库读不到（HTTP %s）⇒ 结论作废，不写产物。" % st)
        return 2
    branch = repo.get("default_branch") or "main"

    st, tree, _ = api_get("https://api.github.com/repos/%s/git/trees/%s?recursive=1"
                          % (REPO, urllib.parse.quote(branch)), token)
    if st != 200 or not isinstance(tree, dict):
        print("⛔ 文件树读不到（HTTP %s）⇒ 不写产物。" % st)
        return 2
    if tree.get("truncated"):
        print("⚠️ 树被截断 ⇒ 逐场景清单可能不全，正文必须标注「下界」。")

    scenes, base, mirrors = [], [], []
    for x in (tree.get("tree") or []):
        if x.get("type") != "blob":
            continue
        p = x.get("path") or ""
        low = p.lower()
        if not low.endswith((".ts", ".tsx", ".js")):
            continue
        if "/scenes/" not in "/" + low:
            continue
        rec = {"path": p, "bytes": int(x.get("size") or 0)}
        is_base = any(h in low for h in BASE_HINTS)
        # ⚠️ 该仓库有**两份**场景源码：`src/**/*.ts`（真源码）与
        # `.github/extensions/arcade-canvas/**/*.js`（VS Code 扩展的打包副本，字节更大）。
        # 两边都数会把「几个游戏」翻倍 —— 实测第一版就数出 12 个。计数只认 src/，
        # 副本单独记账，供正文说明「为什么目录树里会看到两套」。
        if not low.startswith("src/"):
            mirrors.append(rec)
        elif is_base:
            base.append(rec)
        else:
            scenes.append(rec)

    if len(scenes) < 1:
        # 阳性对照式护栏：`src/` 下找不到场景只有两种可能——抓取坏了，或上游把源码搬走了。
        # 两种都必须响，⛔ 不能让正文悄悄停在旧数字上。
        print("⛔ 在 %s 的 `src/**/scenes/` 里没找到任何场景脚本（镜像副本 %d 个不算）"
              "⇒ 目录结构变了或抓取失败，不写产物（⛔ 正文不许沿用上一次清单）。"
              % (branch, len(mirrors)))
        return 2
    if len(mirrors) >= 2 * len(scenes):
        print("⚠️ 镜像副本数量（%d）≥ 源码场景的 2 倍 —— 检查一下是否漏算了新的游戏目录。"
              % len(mirrors))

    deps = None
    st, content, _ = api_get("https://api.github.com/repos/%s/contents/package.json?ref=%s"
                             % (REPO, branch), token)
    if st == 200 and isinstance(content, dict):
        import base64
        try:
            pkg = json.loads(base64.b64decode(content.get("content") or "").decode("utf-8"))
            deps = pkg.get("dependencies")
        except Exception as exc:                                   # noqa: BLE001
            print("⚠️ package.json 解析失败（%s）⇒ 引擎版本一列写「未取到」。" % exc)

    total = sum(s["bytes"] for s in scenes)
    out = {
        "repo": REPO, "branch": branch, "truncated": bool(tree.get("truncated")),
        "scenes": scenes, "scenes_total_bytes": total,
        "scenes_est_lines": int(round(total / LINES_PER_BYTE)),
        "base_classes": base, "mirror_copies": mirrors,
        "dependencies": deps, "n_scenes": len(scenes),
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)

    print("%s：分支 %s ｜ 场景 %d 个 ｜ 合计 %s B ≈ %s 行(估) ｜ 截断=%s"
          % (REPO, branch, len(scenes), format(total, ","),
             format(out["scenes_est_lines"], ","), out["truncated"]))
    for s in sorted(scenes, key=lambda r: -r["bytes"]):
        print("  %-40s %9d B  ~%6d 行(估)" % (s["path"], s["bytes"],
                                             round(s["bytes"] / LINES_PER_BYTE)))
    for b in base:
        print("  [基类] %-34s %9d B" % (b["path"], b["bytes"]))
    print("  [镜像副本，不计入] %d 个文件 / %s B（VS Code 扩展打包的那份）"
          % (len(mirrors), format(sum(m["bytes"] for m in mirrors), ",")))
    print("  package.json dependencies: %s" % (json.dumps(deps, ensure_ascii=False)
                                               if deps else "未取到"))
    print("已写 %s" % OUT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
