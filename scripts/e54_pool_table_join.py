r"""
E54 附录再生 —— 把 scripts/e54_upstream_verify.py 的 API 快照（数字列）、
scripts/e54_screenshot_probe.py 的截图取证（预览列）与
「开源横版卷轴游戏总表·更新版」（描述列）join 成 markdown，写回 e53 方案文档的四个标记区：

    <!-- E54-POOL:BEGIN -->   → 附录 A.1~A.8（逐条表 + 统计 + 许可分歧明细）
    <!-- E54-NOGO:BEGIN -->   → 附录 C（❌ 不做清单 / 🔶 判定前不做清单 / ✅ 可做池截图核对）
    <!-- E54-POOL10:BEGIN --> → 正文 §五 的「✅ 可做池体量与素材实测」表（2026-11-04 新增，
                                数据源 `logs_temp/e54_pool10_sizes.json`）
    <!-- E54-SCENES:BEGIN --> → 正文 §5.6 方案 C 的逐场景表（数据源
                                `logs_temp/e54_agent_arcade_scenes.json`）

## 为什么是脚本而不是手抄

附录 A.1 有 84 行、A.2 有 38 行。人工誊写 license / stars / pushed 这三列
= 直接把「表内许可不可信」这个 bug 再复制一遍（本次复验发现 24/122 处许可有问题）。
所以 E54 文档里附录表格的**数字列一律由本脚本从 JSON 快照再生**，
描述列（分类 / 简介 / 自动玩方式 / 备注）沿用原表文字 —— 那些不是测量值。

「不做 / 判定前不做」同样不许手抄：判定口径只有一份，定义在
`e54_upstream_verify.e54_verdict()`，本脚本与附录 C 都调它 ——
否则「A.5 说它无 LICENSE、C 说它可以做」这类自相矛盾就会重新长出来。

## 幂等

只重写三个 BEGIN…END 标记之间的内容。标记不存在则报错退出（绝不在文档尾部盲追加第二份）。

## 预览列（截图）的取舍

原「更新版」的截图列 84 行里约 3/4 只是把仓库链接重复一遍，不是图片。
现在这一列**只放 `e54_screenshot_probe.py` 实测通过的直链**（存在性 + 位图 + >2KB 三重判定），
并注明来源（README / 根目录 / 官网 / Release / 原表列）。
取不到图的写**实测出来的原因**（纯文字仓库 / 只有徽章 / README 指向的文件不在该分支 …），
⛔ 不再写「见 README」这种含糊话，也 ⛔ 绝不凭猜测拼一个图片 URL。

## 用法（从仓库根跑）

    python scripts/e54_upstream_verify.py            # ① 快照：仓库存在性 + 许可
    python scripts/e54_screenshot_probe.py           # ② 取证：真实图片直链
    python scripts/e54_pool10_sizes.py               # ③ 取证：✅ 可做池的源码/素材体量（§五 用）
    python scripts/e54_pool_table_join.py            # ④ 灌进文档的三个标记区
"""

import argparse
import json
import os
import re
import sys
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
# 许可与判定口径只有一份，⛔ 不在这里重复定义
from e54_upstream_verify import OSI_OK, e54_verdict, HARD_EXCLUDE, LOOK_EXCLUDE

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BEGIN = "<!-- E54-POOL:BEGIN (由 scripts/e54_pool_table_join.py 再生，勿手改数字列) -->"
END = "<!-- E54-POOL:END -->"
NOGO_BEGIN = "<!-- E54-NOGO:BEGIN (由 scripts/e54_pool_table_join.py 再生，勿手改任何一行) -->"
NOGO_END = "<!-- E54-NOGO:END -->"
POOL10_BEGIN = "<!-- E54-POOL10:BEGIN (由 scripts/e54_pool_table_join.py 再生，勿手改数字) -->"
POOL10_END = "<!-- E54-POOL10:END -->"
SCENES_BEGIN = "<!-- E54-SCENES:BEGIN (由 e54_agent_arcade_scenes.py 取证 + 本脚本再生，勿手改) -->"
SCENES_END = "<!-- E54-SCENES:END -->"

LINES_PER_BYTE = 35.0   # 与 e54_pool10_sizes.py / e54_agent_arcade_scenes.py 同一个折算常数

IMG_HINT = ("raw.githubusercontent.com", "imgur.com", "user-images.githubusercontent.com",
            "supertux.org", "ddnet.org")

# 大池里描述性结论（分类 / 简介 / 自动玩 / 表内核验 / 备注）从原表解析
ROW_RE = re.compile(r"^\|\s*([^|]+?)\s*\|")


def parse_pool(path):
    """解析「更新版」的 9 列表 → {小写路径: {...}} 与 {小写名称: {...}}"""
    by_path, by_name = {}, {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.startswith("|"):
                continue
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if len(cells) < 8 or cells[0] in ("名称", "---") or set(cells[0]) <= {"-", " "}:
                continue
            name, url, lic, cat, desc, auto, shot, status = cells[0], cells[1], cells[2], cells[3], cells[4], cells[5], cells[6], cells[7]
            note = cells[8] if len(cells) > 8 else ""
            m = re.search(r"github\.com/([\w.-]+/[\w.-]+)", url)
            rec = {"name": name, "lic": lic, "cat": cat, "desc": desc, "auto": auto,
                   "status": status, "note": note, "shot": shot}
            img = next((u for u in re.findall(r"https?://[^\s)\]]+", shot) if any(h in u for h in IMG_HINT)), "")
            rec["img"] = img
            if m:
                by_path[m.group(1).lower()] = rec
            by_name[name.lower()] = rec
    return by_path, by_name


def row_md(r, pool_by_path, pool_by_name, shots=None):
    p = pool_by_path.get((r.get("renamed_to") or r["path"]).lower()) \
        or pool_by_path.get(r["path"].lower()) \
        or pool_by_name.get(r["name"].lower())
    p = p or {"cat": "?", "desc": "?", "auto": "?", "status": "?", "note": "", "img": "", "lic": "?"}
    path = r.get("renamed_to") or r["path"]
    if "alt_hit" in r:
        path = "%s ⭐备选 %s 存在" % (r["path"], r["alt_hit"]["path"])
    repo = "[%s](https://github.com/%s)" % (path, path) if "/" in path and not path.startswith("(") else path
    if r.get("http") == 0:
        http = "未探测"
    elif r.get("renamed_to") and r["renamed_to"] != r["path"]:
        http = "301→%s" % r["renamed_to"].split("/")[-1]
    else:
        http = {200: "✅", 404: "❌404", 451: "❌451 DMCA"}.get(r["http"], "❓%s" % r["http"])
    pushed = r["pushed"] or "—"
    archived = "⚠️已归档" if r["archived"] else ""
    stars = str(r["stars"]) if r["stars"] is not None else "—"
    vd, _ = e54_verdict(r)
    note = p["note"].replace("|", "/")
    return "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
        r["name"], repo, p["cat"], p["desc"], p["auto"], p["status"], http,
        (r["license"] or "—"), vd, (stars + (" " + archived if archived else "")), pushed,
        shot_cell(r, shots), note)


def load_shots(path):
    """截图取证产物；缺文件返回 None（此时预览列写「本轮未取证」，⛔ 不假装有图）。"""
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    return {"generated_at": d.get("generated_at"), "probed": d.get("probed"),
            "with_image": d.get("with_image"), "unverified": d.get("unverified", 0),
            "by_path": {s["path"].lower(): s for s in d["rows"]}}


def cell(s):
    """markdown 表格单元：竖线会拆断整行，一律折成 `/`。"""
    return str(s).replace("|", "/")


def shot_cell(r, shots):
    """预览列：三种状态必须分开写，⛔ 不许含糊。

      ✅ 实测通过   直链 + 来源（存在性 + 位图 + >2KB 三重判定都过了）
      ⚠️ 未验证     给了 README/官网里的那条链接，但本机网络到不了那个图床 —— 不是「没图」，也不是「验过」
      ⛔ 无截图     六档取证都落空，后面跟**实测出来的原因**

    ⛔ 这里绝不出现「见 README」，也绝不出现**假装验证过**的链接。
    ⚠️ 表格单元里的 `|` 一律折成 `/`（URL 带竖线会直接拆断整行），所以转义在本函数内做完，
    调用方不必再记着 replace。
    """
    return cell(_shot_raw(r, shots))


def _shot_raw(r, shots):
    if not shots:
        return "本轮未取证（先跑 e54_screenshot_probe.py）"
    s = shots["by_path"].get((r.get("renamed_to") or r["path"] or "").lower())
    if s is None:
        return "⛔ 无法取证：本轮快照里该仓库不是 200，没有可读的 README"
    if s.get("url") and s.get("verified"):
        return "[实机图](<%s>)（实测通过，来源：%s）" % (s["url"], s.get("source") or "?")
    if s.get("url"):
        return "[图（⚠️ 本机无法验证）](<%s>)（来源：%s）" % (s["url"], s.get("source") or "?")
    return "⛔ 无截图 —— %s" % (s.get("reason") or "未记录原因")


def shot_state(r, shots):
    """'ok' / 'unverified' / 'none' —— C.10 与 C.11 的覆盖率只按这三档统计。"""
    if not shots:
        return "none"
    s = shots["by_path"].get((r.get("renamed_to") or r["path"] or "").lower())
    if not s:
        return "none"
    if s.get("url") and s.get("verified"):
        return "ok"
    return "unverified" if s.get("url") else "none"



def load_sizes(path):
    """体量取证产物（`e54_pool10_sizes.py`）；缺文件返回 None ⇒ §五 的表写「本轮未取证」，⛔ 不编数。"""
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def mib(n):
    """字节数折成可读体积：>=1 MiB 用 MiB，否则 KB。None 写「—」，0 就老实写 0。"""
    if n is None:
        return "—"
    return ("%.1f MiB" % (n / 1048576.0)) if n >= 1048576 else ("%s KB" % round(n / 1024.0, 1))


def pool10_block(rows, shots, sizes):
    """§五 的**体量与素材实测表**（`E54-POOL10` 标记区）。

    这张表为什么必须存在：v3.0~v4.1 的选型全建立在「代码量」那一列的**人工估计**上，
    而 §七 又断言「所有推荐候选都是全程序化绘制，没有一张图片文件」—— 两句都是猜的。
    2026-11-04 观感裁决把池子缩到 10 条以后，逐条实测变得可行，于是：
      - 源码字节取 `/languages`（识别器认出的全部文本，⚠️ 是**上限**，不是行数）；
      - 位图/音频字节取 `/git/trees?recursive=1` 的 `blob.size` 按后缀归类求和。
    ⛔ 正文不许再出现手抄的体量数字；§七 的贴图预算直接引用这里的「位图」列。
    """
    if not sizes:
        return ("_本轮未取证 —— 先跑 `python scripts/e54_pool10_sizes.py` 再复跑本脚本。"
                "⛔ 不取证就不写数字。_")
    ok = [r for r in rows if e54_verdict(r)[0].startswith("✅")]
    ok.sort(key=lambda r: (sizes.get(((r.get("renamed_to") or r["path"] or "")).lower(), {})
                           .get("src_bytes") or 10 ** 12))
    out = ["| 上游 | 许可(API) | 源码字节 → 折算行数(估) | 位图（张 / 体积） | 音频体积 | "
           "仓库总体积 | ★ / 最近推送 | 实机图 |",
           "|---|---|---|---|---|---|---|---|"]
    n_zero = n_exact = 0
    SMALL = 512 * 1024   # 位图分档阈值：≤512 KB 视为「只有图标 / README 截图」，重画成本低；超过它按贴图驱动算
    n_small = n_big = 0
    for r in ok:
        key = ((r.get("renamed_to") or r["path"] or "")).lower()
        v = sizes.get(key) or {}
        t = v.get("tree") or {}
        if not t.get("truncated"):
            n_exact += 1
        path = v.get("repo") or key
        src, est = v.get("src_bytes"), v.get("est_lines")
        if src == 0:
            srccell = "⚠️ 识别器给出 **0 字节**（文件树 %s 个文件）⇒ 体量无 API 证据，须人工读源" % t.get("n_files")
        elif src is None:
            srccell = "—（本轮没取到）"
        else:
            srccell = "%s B → 约 %s 行(估)" % (format(src, ","),
                                              format(est, ",") if est else "?")
        ib = t.get("image_bytes")
        if t.get("image_count") == 0:
            n_zero += 1
            imgcell = "**0 张**（真·全程序化）"
        elif t.get("image_count") is None:
            imgcell = "—"
        else:
            if ib is not None and ib <= SMALL:
                n_small += 1
            else:
                n_big += 1
            imgcell = "%d 张 / %s%s" % (t["image_count"], mib(ib),
                                        "（⚠️ 树被截断，取的是下界）" if t.get("truncated") else "")
        stars = v.get("stars")
        out.append("| [%s](https://github.com/%s) | %s | %s | %s | %s | %s%s | %s / %s | %s |"
                   % (cell(path), cell(path), r.get("license") or "—", cell(srccell), cell(imgcell),
                      mib(t.get("audio_bytes")), mib(1024.0 * (v.get("size_kb") or 0)),
                      " ⚠️已归档" if v.get("archived") else "",
                      stars if stars is not None else "—", v.get("pushed_at") or "—",
                      shot_cell(r, shots)))
    out.append("")
    out.append("> 读法：**「折算行数」不是测量值**，只是把源码字节除以常数 35，好和旧版那些手写行数对话；"
               "真正可以当测量值引用的是 **源码字节** 与 **位图张数 / 体积** 两列"
               "（后者是文件树逐 blob 求和，10 条里 %d 条 `truncated=false` ⇒ 精确值）。" % n_exact)
    out.append("> ⭐ 这张表推翻了旧 §七 的前提「所有推荐候选都是全程序化绘制，没有一张图片文件」——"
               "按**位图总体积**分档实测：0 张 **%d** 条 / ≤%d KB **%d** 条（只有图标与 README 截图，重画成本低）/ "
               "> %d KB **%d** 条（移植时素材预算必须另算，见 §七）。"
               % (n_zero, SMALL // 1024, n_small, SMALL // 1024, n_big))
    out.append("> ⚠️ **但「体积大」不等于「贴图驱动」**：单张 README 截图就能把总体积顶过阈值"
               "（本表就踩到了——阈值档只按字节分，不做用途判断）。逐条区分「代码画的 / 现成小图 / 真贴图驱动」"
               "看 §7.1 的档位列；那里的**数字**仍来自本表，只是多了一层人工判读。")
    return "\n".join(out)


def load_scenes(path):
    """方案 C 的逐场景取证产物（`e54_agent_arcade_scenes.py`）；缺文件返回 None ⇒ 表写「本轮未取证」。"""
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8-sig") as f:
        return json.load(f)


def scenes_block(sc):
    """§5.6 方案 C 的**逐场景体量表**（`E54-SCENES` 标记区）。

    为什么要给这一小节单开一个产物：「一仓几个游戏」既是方案 C 的卖点又是计费基数
    （工时 = 场景数 × 单游戏 3-6 人日），而该仓库里存在**两份**场景源码
    （`src/**/*.ts` 与 `.github/extensions/arcade-canvas/**/*.js`）。
    手工数目录会数出 12 个 ⇒ 工时翻倍。取证脚本按 `src/` 过滤并把镜像单独记账。
    """
    if not sc:
        return ("_本轮未取证 —— 先跑 `python scripts/e54_agent_arcade_scenes.py` 再复跑本脚本。"
                "⛔ 不取证就不写「几个游戏」和逐场景字节。_")
    out = ["| 场景文件（实测字节） | 折算行数（估） | 品类（⚠️ 按文件名推断，须读源核实） |",
           "|---|---|---|"]
    for s in sorted(sc["scenes"], key=lambda r: -r["bytes"]):
        out.append("| `%s`（%s B） | ~%s | 待读源 |"
                   % (s["path"].rsplit("/", 1)[-1], format(s["bytes"], ","),
                      format(round(s["bytes"] / LINES_PER_BYTE), ",")))
    out.append("| **%d 个场景合计** | **%s B ≈ ~%s 行（估）** | 见下方「该路线的实际形态」 |"
               % (sc["n_scenes"], format(sc["scenes_total_bytes"], ","),
                  format(sc["scenes_est_lines"], ",")))
    for b in sc.get("base_classes") or []:
        out.append("| `%s`（%s B） | ~%s | 共享基类 = 现成的切换 / 输入 / HUD 框架，正是 §4.1 参考的那部分 |"
                   % (b["path"].rsplit("/", 1)[-1], format(b["bytes"], ","),
                      format(round(b["bytes"] / LINES_PER_BYTE), ",")))
    mirrors = sc.get("mirror_copies") or []
    out.append("")
    out.append("> ⚠️ **上表由 `scripts/e54_agent_arcade_scenes.py` 再生**（产物 `logs_temp/e54_agent_arcade_scenes.json`），⛔ 不许手抄。"
               "该仓库里有**两份**场景源码：`src/**/*.ts`（真源码，本表只数这一份）与 "
               "`.github/extensions/arcade-canvas/**/*.js`（VS Code 扩展的打包副本，**%d 个文件 / %s B**，字节还更大）"
               "—— 不按 `src/` 过滤就会数出「%d 个游戏」，工时直接翻倍。"
               % (len(mirrors), format(sum(m["bytes"] for m in mirrors), ","),
                  sc["n_scenes"] + len(mirrors)))
    out.append("> 分支 `%s` ｜ 树截断＝%s ｜ 引擎绑定（`package.json` 的 `dependencies` 实测）：%s"
               % (sc.get("branch"), sc.get("truncated"),
                  json.dumps(sc.get("dependencies"), ensure_ascii=False)
                  if sc.get("dependencies") else "未取到"))
    return "\n".join(out)


HDR_A = ("| 名称 | 仓库（复验后路径） | 分类 | 简介 | 自动玩方式 | 原表核验 | 本次复验 | 实际许可(API) | E54判定 | stars | 最近推送 | 预览 | 备注 |\n"
         "|---|---|---|---|---|---|---|---|---|---|---|---|---|")
HDR_B = ("| 候选 | 仓库 | 出处 | 原记许可 | 本次复验 | 实际许可(API) | E54判定 | stars | 最近推送 | 预览 | 结论 |\n"
         "|---|---|---|---|---|---|---|---|---|---|---|")


def stats_block(rows):
    from e54_upstream_verify import lic_class
    a = [r for r in rows if not r["tag"].startswith("正文")]
    b = [r for r in rows if r["tag"].startswith("正文")]
    hard, none_, undet, arch, stale = [], [], [], [], []
    for r in rows:
        if not r["exists"]:
            continue
        k = lic_class(r["claimed_lic"], r["license"])
        if k == "HARD":
            hard.append(r)
        elif k == "CLAIM_OK_ACTUAL_NONE":
            none_.append(r)
        elif k == "CLAIM_OK_ACTUAL_UNDET":
            undet.append(r)
        if r["archived"]:
            arch.append(r)
        if r["pushed"] and r["pushed"] < STALE_BEFORE:
            stale.append(r)
    out = []
    out.append("### A.3 统计（本次复验，122 条 = 大池 84 + 正文候选 38）\n")
    out.append("| 指标 | 大池 | 正文候选 | 合计 |")
    out.append("|---|---|---|---|")
    for label, pred in [
        ("存在（200）", lambda r: r["exists"] is True),
        ("404 不存在", lambda r: r["exists"] is False),
        ("状态未知（403/网络，⛔ 不算失效）", lambda r: r["exists"] is None and r.get("http") != 0),
        ("未定位到仓库（本轮未探测）", lambda r: r.get("http") == 0),
    ]:
        out.append("| %s | %d | %d | %d |" % (label, sum(1 for r in a if pred(r)),
                                              sum(1 for r in b if pred(r)),
                                              sum(1 for r in rows if pred(r))))
    out.append("| 上游已归档（archived，不再更新） | %d | %d | %d |"
               % (sum(1 for r in a if r["archived"]), sum(1 for r in b if r["archived"]), len(arch)))
    out.append("| 停更超过两年（pushed < %s） | %d | %d | %d |"
               % (STALE_BEFORE, sum(1 for r in a if r["archived"] is False and r["pushed"] and r["pushed"] < STALE_BEFORE),
                  sum(1 for r in b if r["archived"] is False and r["pushed"] and r["pushed"] < STALE_BEFORE), len(stale)))
    out.append("")
    out.append("> **与《核验报告》v1 的口径对账**：原报告 84 条 = 42 有效 / 42 失效；本次 = %d 有效 / %d 失效。"
               "差值 **5** 恰好等于《更新版》本轮修正路径的 5 条（Blob Wars、Contra Remake、NESlib、Infra Arcana、Open Sonic），"
               "两份文档在这一点上自洽。另有 2 条由**备选路径**救回（见 A.6），原路径仍是 404，所以不计进上面的「有效」数。\n"
               % (sum(1 for r in a if r["exists"]), sum(1 for r in a if r["exists"] is False)))
    out.append("### A.4 许可硬冲突（族 / 主版本不符 —— 表内那一列不可信）\n")
    out.append("| 名称 | 表内写 | API 实际 | 后果 |")
    out.append("|---|---|---|---|")
    consequence = {
        "SuperTux": "表内 GPLv2+ 实际 GPL-3.0 —— 对 GPL v3 项目影响不大，但表内口径是错的",
        "SpaceHuggers": "表内 MIT 实际 GPL-3.0，且 README 写明「不允许再分发」⇒ 与正文 §2.1 的顾虑一致，排除",
        "OpenTomb": "表内 GPLv2 实际 LGPL-3.0（且已归档）",
        "BGUG": "表内 GPLv3 实际 **MIT** —— 方向是变宽松，横版射击品类因此多一个合规候选",
        "NESlib": "表内 MIT 实际 Zlib（都是 OSI，无实质影响；且它是开发库不是游戏）",
        "Open Sonic": "表内 GPLv3 实际 GPL-2.0 —— GPL-2.0 -only 的代码与 GPL v3 项目需按兼容矩阵处理",
    }
    for r in hard:
        out.append("| %s | %s | %s | %s |" % (r["name"], r["claimed_lic"], r["license"],
                                              consequence.get(r["name"], "采用前必须人工读 LICENSE")))
    out.append("")
    out.append("### A.5 ⛔ 表内写了 OSI 许可，但仓库里没有 LICENSE 文件（写了 MIT 实为 all-rights-reserved）\n")
    out.append("| 名称 | 表内写 | API | 出处（池内状态 / 正文章节） |")
    out.append("|---|---|---|---|")
    for r in none_:
        out.append("| %s | %s | NONE | %s |" % (r["name"], r["claimed_lic"], r["tag"]))
    out.append("")
    out.append("### A.6 备选路径救回（原表「404 失效」其实是路径写错，不是作者删库）\n")
    for r in rows:
        if "alt_hit" in r:
            out.append("- **%s**：%s → 404；正确路径 `%s`（%s stars，%s，许可 API=%s）"
                       % (r["name"], r["path"], r["alt_hit"]["path"], r["alt_hit"]["stars"],
                          r["alt_hit"]["pushed"], r["alt_hit"]["license"]))
    out.append("")
    out.append("### A.7 🔶 表内写了许可但 API 判不出来（自定义 / 混合许可，须人工读 LICENSE）\n")
    for r in undet:
        out.append("- %s（%s）：表内写 %s，API = %s" % (r["name"], r["path"], r["claimed_lic"], r["license"]))
    out.append("")
    out.append("### A.8 上游已归档（不再维护 —— 移植即终态，反而利于快照）\n")
    for r in arch:
        out.append("- %s（%s，最后一次推送 %s）" % (r["name"], r["path"], r["pushed"]))
    return "\n".join(out)


STALE_BEFORE = "2024-11-03"


def row_b(r, shots=None):
    """正文候选表：判定与原因都走 `e54_verdict()` 这一份口径（⛔ 不在此处再写一遍规则）。

    以前这里自己拼结论，结果 §2.1 那批「无 LICENSE」的行被拼成了「✅ 与原表一致」——
    那是一句真话（和原表一致）**但答错了问题**（读者要的是「能不能做」）。
    现在结论列直接给出三档判定 + 原因。
    """
    from e54_upstream_verify import lic_class
    claimed, lic = r["claimed_lic"], (r["license"] or "—")
    vd, why = e54_verdict(r)
    hard = ""
    if r["exists"] and lic_class(claimed, r["license"]) == "HARD":
        hard = "（另：表内 %s ↔ 实际 %s，见 A.4）" % (claimed, lic)
    http = {200: "✅", 404: "❌404"}.get(r["http"], "❓%s" % r["http"])
    concl = "%s：%s%s" % (vd, why, hard)
    return "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
        r["name"], r["path"] if r["path"] else "(未定位)", r["tag"], claimed, http, lic, vd,
        r["stars"] if r["stars"] is not None else "—", r["pushed"] or "—",
        shot_cell(r, shots), concl.replace("|", "/"))


BUCKET_TITLE = [
    ("MANUAL", "C.2 ❌/🔶 人工裁决（IP / 作者禁止再分发 / 不是游戏本体）",
     "这一类机检答不出来：LICENSE 文件只写了「代码怎么许可」，答不了「素材是谁的」「作者是否不许再分发」。"
     "每条的判据都写进原因列，可回溯到正文章节或 README 原文。"
     "⚠️ 这是**成因**归堆，不是档位：绝大多数裁决成 ❌ 不做，但 Open Sonic 那条裁决给的是 🔶"
     "（引擎代码许可成立、SEGA 素材来源待确认），所以本表的「判定」列与 C.1 的档位表可能不同 —— 两个轴切的都是同一批 122 条。"),
    ("LOOK", "C.3 ❌ 不做 · 所有者观感裁决（实机图不好看，2026-11-04）",
     "⚠️ **这一堆与许可无关**：下面 %d 条的存在性与 LICENSE 文件本轮全部复验**通过**（判据、图链见附录 A.1/A.2 同行），"
     "是所有者 2026-11-04 逐条看过实机图后裁决「观感不达标」，移出移植池。"
     "登记在 `e54_upstream_verify.LOOK_EXCLUDE`（与授权类的 `HARD_EXCLUDE` 分开，⛔ 不要合并 —— "
     "合并后读的人会以为这些仓库有授权问题）。正文里关于它们的评估、选型与实现细节已按要求清除，此处是唯一的留档。"),
    ("NOLIC", "C.4 ❌ 不做 · 仓库里没有 LICENSE 文件",
     "API `license.spdx_id = NONE` ⇒ 默认 all-rights-reserved，C4 不成立。"
     "「表内写了 MIT」这一列越自信越危险 —— 下面 %d 条里有 %d 条表内写的是 MIT。"),
    ("NONOSI", "C.5 ❌ 不做 · 机检到非 OSI 许可",
     "API 认出了许可，但它不在 OSI 清单里（C4 直接不成立）。"),
    ("UNDET", "C.6 🔶 判定前不做 · API 判不出许可（自定义 / 混合）",
     "`NOASSERTION` ≠ 有许可，也 ≠ 没许可 —— 它只表示「机器读不出标准 SPDX」。"
     "按 C4 的口径（只认 LICENSE 文件内容），**人工读完之前一律不做**，读完后可以升可以降。"),
    ("COPYLEFT", "C.7 🔶 判定前不做 · copyleft 相容性待确认",
     "本项目是 GPL-3.0。上游若是 GPL-2.0 **-only** 就不相容；若写的是 GPL-2.0+ 则可以。"
     "这件事 API 答不了（它只给一个 spdx_id），必须读 LICENSE 原文。"),
    ("UNK", "C.8 🔶 判定前不做 · 本轮状态未知（限流 / 网络）",
     "⛔ 绝不把 403/超时折成「失效」—— 这是《核验报告》v1 就立下的纪律。重跑脚本即可复位。"),
    ("DEAD", "C.9 ❌ 不做 · 仓库已失效（404 / 451 / 未定位到仓库）",
     "无源码可取，也不构成「作者删库」的证据 —— 只构成「原表这条链接写错了或上游确实不在了」，"
     "两者都需要人工确认才能定性。"),
]


def bucket_of(r, vd, why):
    path = ((r.get("renamed_to") or r.get("path") or "") or "").lower()
    if path in HARD_EXCLUDE:
        return "MANUAL"
    if path in LOOK_EXCLUDE:
        return "LOOK"
    if vd.startswith("❌"):
        if r["exists"] is False or r.get("http") == 0:
            return "DEAD"
        if r["license"] in (None, "NONE"):
            return "NOLIC"
        return "NONOSI"
    if vd.startswith("🔶"):
        if r["exists"] is not True:
            return "UNK"
        if r["license"] in ("NOASSERTION", "UNKNOWN", "DMCA_BLOCKED"):
            return "UNDET"
        return "COPYLEFT"
    return "OK"


def no_go_block(rows, shots):
    """附录 C：把「不做 / 判定前不做」集中一处，并逐条写明原因。

    ⚠️ 判定只来自 `e54_verdict()`；这里再加一条规则就会和附录 A.1 的判定列分叉。
    """
    groups = {}
    for r in rows:
        vd, why = e54_verdict(r)
        groups.setdefault(bucket_of(r, vd, why), []).append((r, vd, why))
    n_pool = lambda ks: sum(1 for r, _, _ in ks if not r["tag"].startswith("正文"))
    out = []
    total = len(rows)
    ok = groups.get("OK", [])
    # 档位轴（✅/🔶/❌）必须由 e54_verdict 直接数出来，⛔ 不能拿成因桶相加代替 ——
    # 成因桶和档位不是同一个切法（Open Sonic 的成因是人工裁决、档位是 🔶），
    # 用桶加出来会得到 64/10，而判定列逐条读出来是 63/11，两处一对不上就是 v4.0 的老毛病。
    tier = {}
    for r in rows:
        vd = e54_verdict(r)[0]
        tier.setdefault(vd.split()[0], []).append((r, vd, ""))
    n_pend = len(tier.get("🔶", []))
    n_dead = len(tier.get("❌", []))
    out.append("### C.1 汇总（%d 条 = 大池 84 + 正文候选 38，判定口径见 §8.7 与 `e54_verdict`）\n" % total)
    out.append("**先按档位数（这一张表的三个数，就是附录 A.1 / A.2「E54判定」列逐条读出来的结果）**\n")
    out.append("| 档位 | 大池 | 正文候选 | 合计 | 含义 |")
    out.append("|---|---|---|---|---|")
    for mark, label, meaning in (("✅", "✅ 可做",
                                  "许可机检成立，可进移植池（**不等于**已经能自动玩，可行性另看 §2.2 / §三）"),
                                 ("🔶", "🔶 判定前不做",
                                  "证据不足以下结论 ⇒ **本轮一律不做**，人工确认后再升 / 再降"),
                                 ("❌", "❌ 不做",
                                  "证据已足够否定它（无 LICENSE / 非 OSI / 404 / 已登记的人工裁决）")):
        g = tier.get(mark, [])
        out.append("| %s | %d | %d | %d | %s |"
                   % (label, n_pool(g), len(g) - n_pool(g), len(g), meaning))
    out.append("| **合计** | %d | %d | %d | |"
               % (sum(1 for r in rows if not r["tag"].startswith("正文")),
                  sum(1 for r in rows if r["tag"].startswith("正文")), total))
    out.append("")
    out.append("**再按成因数（下面 C.2~C.9 每节就是这一行）**；两轴切的都是同一批 122 条，所以会交叉：`人工裁决` 那行里有 1 条档位是 🔶\n")
    out.append("| 成因 | 大池 | 正文候选 | 合计 | 落在哪一档 |")
    out.append("|---|---|---|---|---|")
    for k, label, vd_hint in (
            ("MANUAL", "人工裁决（IP / 不许再分发 / 不是游戏）", "❌（Open Sonic 一条为 🔶）"),
            ("LOOK", "观感裁决（所有者 2026-11-04 看过实机图后裁掉）", "❌（许可本身成立）"),
            ("NOLIC", "仓库里没有 LICENSE 文件", "❌"),
            ("NONOSI", "机检到非 OSI 许可", "❌"),
            ("DEAD", "仓库已失效（404 / 451 / 未定位）", "❌"),
            ("UNDET", "API 判不出许可（自定义 / 混合）", "🔶"),
            ("COPYLEFT", "copyleft 版本表达式待确认", "🔶"),
            ("UNK", "本轮状态未知（限流 / 网络）", "🔶"),
            ("OK", "许可成立且观感未被裁掉", "✅")):
        g = groups.get(k, [])
        out.append("| %s | %d | %d | %d | %s |" % (label, n_pool(g), len(g) - n_pool(g), len(g), vd_hint))
    out.append("| **合计** | %d | %d | %d | |"
               % (sum(1 for r in rows if not r["tag"].startswith("正文")),
                  sum(1 for r in rows if r["tag"].startswith("正文")), total))
    out.append("")
    out.append("> ⚠️ **「❌ 不做」不等于「这个项目不好」**。授权类（C.2、C.4、C.5、C.9）只说明：按 E54 现在的硬约束"
               "（C4 只认 LICENSE 文件、C8 不碰他人 IP），**拿它的源码来移植这件事不成立**；"
               "作为玩法参考、作为「上游有没有自动跑能力」的证据，它们仍然可以读。\n")
    out.append("> ⛔ **但 C.3（观感裁决）是例外**：这 %d 条是所有者看过实机图后直接裁掉的，"
               "正文里关于它们的逐条评估、选型引用与实现细节**已按要求清除**，本节是唯一的留档 —— "
               "不要再把它们当「可读的玩法参考」放回正文。\n"
               % len(groups.get("LOOK", [])))
    out.append("> ⚠️ **不做 %d 条 + 判定前不做 %d 条 = %d 条，占 %d%%**（按档位算，与上面第一张表同源）；"
               "其中「不做」有 %d 条来自观感裁决，只有 %d 条仍在可做池。\n"
               % (n_dead, n_pend, n_dead + n_pend, round(100.0 * (n_dead + n_pend) / total),
                  len(groups.get("LOOK", [])), len(ok)))
    for code, title, note in BUCKET_TITLE:
        g = groups.get(code, [])
        out.append("### %s（%d 条）\n" % (title, len(g)))
        if code == "NOLIC" and g:
            n_mit = sum(1 for r, _, _ in g if "MIT" in (r["claimed_lic"] or "").upper())
            out.append(note % (len(g), n_mit) + "\n")
        elif code == "LOOK" and g:
            out.append(note % len(g) + "\n")
        else:
            out.append(note + "\n")
        if not g:
            out.append("_本轮为空。_\n")
            continue
        out.append("| 名称 | 仓库 | 出处 | 判定 | 表内写 | 实际许可(API) | 为什么不做 / 为什么判定前不做 |")
        out.append("|---|---|---|---|---|---|---|")
        for r, vd, why in sorted(g, key=lambda x: x[0]["name"].lower()):
            path = r.get("renamed_to") or r["path"] or "(未定位到仓库)"
            out.append("| %s | %s | %s | %s | %s | %s | %s |" % (
                r["name"], path, r["tag"], cell(vd), cell(r["claimed_lic"] or "—"),
                cell(r["license"] or "—"), cell(why)))
        out.append("")
    # C.10 可做池逐条 + 截图核对（要求「能做的都有图，取不到的写原因」）
    out.append("### C.10 ✅ 可做池逐条（%d 条）—— 截图逐条核对\n" % len(ok))
    out.append("这一张表就是「能做的都有截图」的落实处：每条的预览列要么给**实测通过的直链**"
               "（存在性 + 位图 + >2KB 三重判定，来源标注 README / 根目录 / 官网 / 原表截图列 / Release / 文件树），"
               "要么写明**实测出来的原因**。⛔ 没有「见 README」这种含糊话。\n")
    if not shots:
        out.append("> ⚠️ 本轮没有截图取证产物（`logs_temp/e54_screenshots.json` 不存在）"
                   "⇒ 请先跑 `python scripts/e54_screenshot_probe.py` 再再生本文档。\n")
    out.append("| 名称 | 仓库 | 出处 | 实际许可(API) | 预览（实测） |")
    out.append("|---|---|---|---|---|")
    for r, vd, why in sorted(ok, key=lambda x: x[0]["name"].lower()):
        path = r.get("renamed_to") or r["path"] or "(未定位到仓库)"
        out.append("| %s | %s | %s | %s | %s |" % (
            r["name"], path, r["tag"], r["license"], shot_cell(r, shots)))
    out.append("")
    if shots:
        st_ok = [x for x in ok if shot_state(x[0], shots) == "ok"]
        st_un = [x for x in ok if shot_state(x[0], shots) == "unverified"]
        st_no = [x for x in ok if shot_state(x[0], shots) == "none"]
        out.append("### C.11 可做池的截图覆盖率：%d 实测通过 / %d 未验证 / %d 取不到（合计 %d）\n"
                   % (len(st_ok), len(st_un), len(st_no), len(ok)))
        out.append("三种状态的含义不同，⛔ 不许合并成一个「有图率」：%d 条是**验证过的**，"
                   "%d 条只是**上游 README 里写了这条链接、但本机网络到不了那个图床**（对能访问的读者可用），"
                   "%d 条是**六档取证都落空**、下面逐条给出实测原因。\n" % (len(st_ok), len(st_un), len(st_no)))
        if st_un:
            out.append("#### C.11.1 ⚠️ 链接给了但未验证（%d 条）\n" % len(st_un))
            out.append("| 名称 | 仓库 | 链接来源 | 为什么没验证 |")
            out.append("|---|---|---|---|")
            for r, vd, why in sorted(st_un, key=lambda x: x[0]["name"].lower()):
                s = shots["by_path"].get((r.get("renamed_to") or r["path"] or "").lower()) or {}
                out.append("| %s | %s | %s | %s |" % (
                    r["name"], r.get("renamed_to") or r["path"], s.get("source") or "?",
                    (s.get("reason") or "").replace("|", "/")))
            out.append("")
        out.append("#### C.11.2 ⛔ 取不到图的 %d 条，逐条实测原因\n" % len(st_no))
        if st_no:
            out.append("| 名称 | 仓库 | 取不到图的原因（实测） |")
            out.append("|---|---|---|")
            for r, vd, why in sorted(st_no, key=lambda x: x[0]["name"].lower()):
                s = shots["by_path"].get((r.get("renamed_to") or r["path"] or "").lower()) or {}
                out.append("| %s | %s | %s |" % (r["name"], r.get("renamed_to") or r["path"],
                                                 (s.get("reason") or "本轮未探到该仓库（快照里不是 200）").replace("|", "/")))
        else:
            out.append("_可做池每一条都给得出图（实测通过或未验证）。_")
        out.append("")
        out.append("> 取证时间：%s ｜ 探测 %s 个存在的仓库 ｜ 实测通过 %s 条 ｜ 未验证 %s 条。\n"
                   % (shots["generated_at"], shots["probed"], shots["with_image"], shots["unverified"]))
    return "\n".join(out)



def swap(doc, begin, end, body):
    """把 `begin … end` 之间整段换成 `begin + body + end`（**字面**替换）。

    两个坑，都拦：
    ① `re.sub` 会把替换串里的 `\\1` / `\\g<name>` 当分组引用解释 —— 生成内容里只要出现
       反斜杠（markdown 转义的 `\\|` 就有）会静默吃掉一段；所以用函数式替换，不走解释。
    ② 配对是「就近」的：若 BEGIN 与它真正的 END 之间夹着**另一个区的 BEGIN**，
       说明 END 找错了（正文里抄了一份完整 `:END` 字面量就会触发），
       那样会把中间整节内容一起替换掉。⇒ 直接拒绝写入，⛔ 不猜。
    """
    i = doc.index(begin)
    j = doc.index(end, i)
    inner = doc[i + len(begin):j]
    for other in (BEGIN, NOGO_BEGIN, POOL10_BEGIN, SCENES_BEGIN):
        if other != begin and other in inner:
            print("⛔ 标记区 %s 内部出现了另一个区的 BEGIN ⇒ END 配对错了，拒绝写入。" % begin[:40])
            sys.exit(1)
    return doc[:i] + begin + "\n\n" + body + "\n\n" + end + doc[j + len(end):]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--doc", default="docs/e54-run-and-gun-survey.md")
    ap.add_argument("--pool", default="docs/开源横版卷轴游戏总表_更新版.md")
    ap.add_argument("--json", default="logs_temp/e54_upstream_verify.json")
    ap.add_argument("--shots", default="logs_temp/e54_screenshots.json")
    ap.add_argument("--sizes", default="logs_temp/e54_pool10_sizes.json",
                    help="✅ 可做池的体量取证产物（e54_pool10_sizes.py）")
    ap.add_argument("--scenes", default="logs_temp/e54_agent_arcade_scenes.json",
                    help="方案 C 的逐场景取证产物（e54_agent_arcade_scenes.py）")
    ap.add_argument("--stdout", action="store_true", help="只打印，不写文档")
    args = ap.parse_args()

    with open(args.json, encoding="utf-8") as f:
        snap = json.load(f)
    rows = snap["rows"]
    pool_by_path, pool_by_name = parse_pool(args.pool)
    shots = load_shots(args.shots)
    if shots is None:
        print("⚠️ 没有 %s ⇒ 预览列一律写「本轮未取证」（⛔ 不会假装「见 README」）。"
              "先跑 python scripts/e54_screenshot_probe.py。" % args.shots)
    sizes = load_sizes(args.sizes)
    if sizes is None:
        print("⚠️ 没有 %s ⇒ §五 的体量表写「本轮未取证」。"
              "先跑 python scripts/e54_pool10_sizes.py。" % args.sizes)
    scenes = load_scenes(args.scenes)
    if scenes is None:
        print("⚠️ 没有 %s ⇒ §5.6 的逐场景表写「本轮未取证」。"
              "先跑 python scripts/e54_agent_arcade_scenes.py。" % args.scenes)

    a_rows = [r for r in rows if not r["tag"].startswith("正文")]   # 附录 A.1 = 84 条大池
    b_rows = [r for r in rows if r["tag"].startswith("正文")]        # 附录 A.2 = E54 正文自己的候选

    exists_a = sum(1 for r in a_rows if r["exists"])
    gone_a = sum(1 for r in a_rows if r["exists"] is False)

    out = []
    out.append("### A.1 大池逐条（%d 行 = 池内全部候选，数字列由脚本再生）\n" % len(a_rows))
    out.append("快照时间：%s ｜ 认证 GitHub API：%s ｜ 存在 %d / 404 %d ｜ 截图取证：%s\n"
               % (snap["generated_at"], "是" if snap["authed"] else "否", exists_a, gone_a,
                  ("%s（%d/%d 有图）" % (shots["generated_at"], shots["with_image"], shots["probed"]))
                  if shots else "未跑"))
    out.append(HDR_A)
    for r in a_rows:
        out.append(row_md(r, pool_by_path, pool_by_name, shots))
    out.append("")
    out.append("### A.2 正文候选逐条（%d 行）\n" % len(b_rows))
    out.append(HDR_B)
    for r in b_rows:
        out.append(row_b(r, shots))
    out.append("")
    out.append(stats_block(rows))
    text = "\n".join(out)
    nogo = no_go_block(rows, shots)
    p10 = pool10_block(rows, shots, sizes)
    scn = scenes_block(scenes)

    if args.stdout:
        print(text)
        print("\n<!-- 下面是附录 C（另一个标记区） -->\n")
        print(nogo)
        print("\n<!-- 下面是 §五 的体量表（第三个标记区） -->\n")
        print(p10)
        print("\n<!-- 下面是 §5.6 的逐场景表（第四个标记区） -->\n")
        print(scn)
        return

    with open(args.doc, encoding="utf-8") as f:
        doc = f.read()
    for mark, name in ((BEGIN, "E54-POOL:BEGIN"), (END, "E54-POOL:END"),
                       (NOGO_BEGIN, "E54-NOGO:BEGIN"), (NOGO_END, "E54-NOGO:END"),
                       (POOL10_BEGIN, "E54-POOL10:BEGIN"), (POOL10_END, "E54-POOL10:END"),
                       (SCENES_BEGIN, "E54-SCENES:BEGIN"), (SCENES_END, "E54-SCENES:END")):
        if mark not in doc:
            print("文档里找不到 %s 标记，拒绝写入（避免尾部重复追加）" % name)
            sys.exit(1)
    doc = swap(doc, BEGIN, END, text)
    doc = swap(doc, NOGO_BEGIN, NOGO_END, nogo)
    doc = swap(doc, POOL10_BEGIN, POOL10_END, p10)
    doc = swap(doc, SCENES_BEGIN, SCENES_END, scn)
    with open(args.doc, "w", encoding="utf-8") as f:
        f.write(doc)
    print("已写入 %s（附录 A.1 %d 行 + A.2 %d 行 + A.3~A.8 汇总 + 附录 C + §五 体量表 %d 行 + §5.6 场景 %s 个）"
          % (args.doc, len(a_rows), len(b_rows),
             sum(1 for r in rows if e54_verdict(r)[0].startswith("✅")),
             str(scenes["n_scenes"]) if scenes else "未取证"))


if __name__ == "__main__":
    main()
