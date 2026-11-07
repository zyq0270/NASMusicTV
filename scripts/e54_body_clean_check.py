# -*- coding: utf-8 -*-
"""E54 正文净化检查 —— 附录以外的正文里，⛔ 不许再出现「弃用候选」的仓库标识。

背景（所有者 2026-11-04 裁决）：判成为 ❌ 不做 / 🔶 判定前不做 的候选，其逐条评估、
选型与实现细节都从正文清除，**唯一留档是附录 C**。正文里继续点它们的名字就等于没清。

判定口径不重复定义，直接吃 `e54_upstream_verify.e54_verdict()` 与
`logs_temp/e54_upstream_verify.json` ⇒ 归堆一变，本检查自动跟着变。
池外复看候选（所有者在 122 行快照之外另行指定的，不进快照以免弄脏计数）由各自的取证脚本
声明 `REPO` + `VERDICT`，本检查经 `out_of_pool_paths()` 取用 —— ⛔ 别在这里手抄名单。

禁词怎么取的：只取**能唯一指向那个仓库的标识**（全路径 / 仓库名 / 项目名），
不取玩法词。理由：`asteroids` / `snake` / `tetris` 同时是品类名（「飞船打陨石玩法」），
把它们当禁词会把「为什么这个品类现在是空白」这句话都没法写。
⚠️ 例外单列在 `GENRE_WORDS`：那几个词同时是品类名，正文要靠它们解释「这个品类为什么空白」，
   所以检查器对它们改用「带 owner/ 前缀 / 带 URL / 反引号包裹」的严格形态。

⛔ **正文 0 违规本身不构成证据**（禁词表取空、附录边界认错、快照过期都会「0 违规」）。
所以本脚本自带阳性对照：同一张禁词表**必须**在附录里命中大量条目——附录是留档区，
名字全在那里。附录命中数低于 `APPENDIX_MIN_TOKENS` 就 `exit 2`，判本轮检查作废。

用法：
    python scripts/e54_body_clean_check.py            # 违规则 exit 1 并打印行号
    python scripts/e54_body_clean_check.py --verbose  # 顺带打印禁词表
"""

import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from e54_upstream_verify import e54_verdict  # noqa: E402
import e54_uefi_contra_probe as uefi  # noqa: E402

DOC = "docs/e54-run-and-gun-survey.md"
VERIFY = "logs_temp/e54_upstream_verify.json"

# 附录从这里开始（含）——附录里逐条留档是裁决要求的，正文才算违规
APPENDIX_START = "## 附录 A"

# 阳性对照下限：同一张禁词表在附录里至少要对上多少个**不同**弃用候选。
# 附录 A.1/A.2/C 逐条列名 ⇒ 实测命中 132 个不同禁词；掉到远低于这个数，
# 说明的不是「正文干净了」，而是附录被改坏 / 快照与文档不同源 / 边界认错。
APPENDIX_MIN_TOKENS = 100

# 玩法词与仓库名同形的：只用「明确指向仓库」的写法判（owner/repo、URL、反引号裸名）。
# 判据是「这个词离开某个具体仓库还有没有意义」——`tetris` 有（俄罗斯方块玩法），
# `supertux` / `spacehuggers` 没有（只可能是那个项目）⇒ 后者走严格禁词。
GENRE_WORDS = {"asteroids", "snake", "tetris", "breakout", "arkanoid", "pong",
               "life-game", "game-of-life", "tower-defense"}

# 允许出现在正文的 ✅ 池成员（10 条）——它们不是弃用候选
# （由判定自动得出，不在此硬编码名单，⛔ 别在这里加白名单，否则池一变就漏）


def out_of_pool_paths():
    """池外候选的仓库路径 —— 所有者在 122 条快照之外另行指定复看的仓库。

    它们**不在** `logs_temp/e54_upstream_verify.json` 里（进了就会把正文里十几处
    「84 条大池 / 122 条」的计数和 A.1/A.2 的行数弄脏），但同样判了 🔶/❌ ⇒ 同样禁点名。
    ⛔ 本函数**不另立名单**：路径与判定都从各自的取证脚本读（`REPO` + `VERDICT`），
    判成 ✅ 的自动不进禁词表。
    """
    out = []
    for mod in (uefi,):
        if not mod.VERDICT[0].startswith("✅"):
            out.append(mod.REPO)
    return out


def name_forms(rows):
    """弃用候选的标识集合：全路径 + 仓库名 + 项目名（小写、按长度降序）。

    ⚠️ 有些弃用仓库的 basename 本身就是**玩法词**（`jsocol/asteroids`、`MehmetMHY/tetris`、
    `cleytonrs/snake`）。把裸词当严格禁词会让「为什么这一品类现在是空白」这句话都没法写，
    所以这类词降级到 genre 桶 —— 只按「owner/repo / URL / 反引号裸名」的严格形态判。
    """
    strict, genre = set(), set()
    for path in out_of_pool_paths():
        base = path.split("/")[-1].lower()
        strict.add(path.lower())
        (genre if base in GENRE_WORDS else strict).add(base)
    for r in rows:
        if e54_verdict(r)[0].startswith("✅"):
            continue
        path = (r.get("renamed_to") or r.get("path") or "")
        nm = re.sub(r"\s+", " ", (r.get("name") or "").strip())
        if path:
            base = path.split("/")[-1].lower()
            strict.add(path.lower())
            (genre if base in GENRE_WORDS else strict).add(base)
        if nm:
            low = nm.lower()
            (genre if low in GENRE_WORDS else strict).add(low)
    return sorted(strict, key=len, reverse=True), sorted(genre, key=len, reverse=True)


def body_lines(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        text = f.read().replace("\r\n", "\n")
    lines = text.split("\n")
    for i, l in enumerate(lines):
        if l.startswith(APPENDIX_START):
            return lines[:i], lines[i:]
    return lines, []


def esc(tok):
    return re.escape(tok).replace(r"\ ", r"[\s_\-]*")


def find_violations(body, strict, genre):
    hits = []
    for n, line in enumerate(body, 1):
        low = line.lower()
        for tok in strict:
            if re.search(r"(?<![a-z0-9_/])" + esc(tok) + r"(?![a-z0-9_])", low):
                hits.append((n, tok, line))
                break
        else:
            # 玩法词只抓「明确指向仓库」的写法：owner/repo、github.com/...、反引号包裹的裸仓库名
            for tok in genre:
                pats = [r"(?:https?://\S*)?" + r"[a-z0-9_.\-]+/" + esc(tok) + r"(?![a-z0-9_])",
                        r"`" + esc(tok) + r"`"]
                if any(re.search(p, low) for p in pats):
                    hits.append((n, tok, line))
                    break
    return hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--doc", default=DOC)
    ap.add_argument("--json", default=VERIFY)
    ap.add_argument("--verbose", action="store_true")
    ap.add_argument("--limit", type=int, default=200, help="最多打印多少处违规（默认 200）")
    args = ap.parse_args()

    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if not os.path.exists(args.json):
        print("⛔ 缺少 %s —— 先跑 python scripts/e54_upstream_verify.py（没有判定快照就没法知道谁被弃用）。"
              % args.json)
        return 2
    with open(args.json, encoding="utf-8-sig") as f:
        rows = json.load(f)["rows"]

    body, appendix = body_lines(args.doc)
    if not appendix:
        print("⛔ 没在 %s 里找到附录边界行 %r —— 边界一坏，整篇都会被当正文扫，"
              "「违规 0」就是假的。拒绝给结论。" % (args.doc, APPENDIX_START))
        return 2
    strict, genre = name_forms(rows)
    if args.verbose:
        print("严格禁词 %d 个（示例）：%s" % (len(strict), ", ".join(strict[:15])))
        print("玩法词按严格形态判：%s" % ", ".join(genre))
    ap_hits = find_violations(appendix, strict, genre)
    ap_tokens = set(t for _, t, _ in ap_hits)
    if len(ap_tokens) < APPENDIX_MIN_TOKENS:
        print("⛔ 阳性对照没过：禁词表在**附录**里只命中 %d 个不同候选（下限 %d，本轮实测正常值 ~132）。"
              "这说明禁词表或附录出了问题，正文「0 违规」不可信 —— 整轮作废，先查快照与文档是否同源。"
              % (len(ap_tokens), APPENDIX_MIN_TOKENS))
        return 2
    hits = find_violations(body, strict, genre)
    print("正文 %d 行 / 附录 %d 行 ｜ 禁词 %d 个 ｜ 违规 %d 处 ｜ 阳性对照：附录命中 %d 个不同弃用候选 ✅"
          % (len(body), len(appendix), len(strict) + len(genre), len(hits), len(ap_tokens)))
    if not hits:
        print("✅ 正文没有任何弃用候选的仓库标识。")
        return 0
    for n, tok, line in hits[:args.limit]:
        print("  L%-5d [%s] %s" % (n, tok, line.strip()[:110]))
    if len(hits) > args.limit:
        print("  …还有 %d 处" % (len(hits) - args.limit))
    return 1


if __name__ == "__main__":
    sys.exit(main())
