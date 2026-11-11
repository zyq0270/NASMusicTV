r"""
E54 截图取证 —— 对快照里**存在**的仓库，逐条抓一张**真实可访问的图片直链**，
写入 logs_temp/e54_screenshots.json，供 scripts/e54_pool_table_join.py 灌进附录 A.1 的「预览」列。

## 为什么必须脚本抓，⛔ 不许手写

附录里每一条图片链接都是一句断言：「这个仓库长这样」。
本轮已经踩过一次——凭印象写了 `franga2000/zoomies`，404 的只是我的猜测，不是事实。
图片链接比仓库链接更容易腐烂（README 改版、图被移走），所以规则是：

1. 链接只能来自**抓取结果**（README 内嵌图 / 仓库根图片 / 官网首页图 / Release / 文件树），
2. 每个候选都要**实测存在**：仓库内文件走认证 `contents` API（`type == file` + `size`），
   站外图走一次真实 GET —— 必须是 2xx、`content-type` 是 `image/*`、体积 > 2 KB；
   ⚠️ 仓库内文件**不走** `raw.githubusercontent.com`：本机 Python 侧对该域名会被连接重置
   （curl 却正常），照直连 GET 判会得出成片假阴性，见 `via_contents_api()`。
3. 拿不到图的，必须写**原因**（不是留空、更不是「见 README」这种含糊话），
   原因同样由脚本实测得出：`该仓库没有任何位图文件` / `六档取证里没有一处可认作实机图的引用（但仓库里有 N 个位图，全是贴图）` /
   `只有徽标/图标/视频封面/贴图类引用` /
   `README 指向的文件不在该分支（contents API 404）`。

## 取值优先级（先实机图，后装饰图；六档都落空才写「取不到」）

    ① README 的 ![..](..) 与 <img src>
    ② 仓库根目录 contents 里的位图
    ③ homepage（GitHub Pages / 官网）里的 <img>
    ④ 原表「截图」列那条链接（**同样要实测**，不因为原表写了就当真）
    ⑤ Releases 正文与附件（游戏图常只贴在发布说明里）
    ⑥ 整棵文件树里**文件名带 screenshot/preview/gameplay** 的位图
       ⚠️ 这一档必须限词（`TREE_MUST` / `TREE_SKIP`）：游戏仓库 data/ gfx/ 下的 PNG 是贴图与精灵，
          拿它当「跑起来什么样子」是误导，不是取证。

打分见 `score()`：命中 screenshot/preview/gameplay 一类词加分，
命中 badge/logo/icon/shield 一类词**减分并直接淘汰**（那不是截图）。
`.svg` 一律淘汰 —— 附录要的是「跑起来什么样子」，矢量 logo 答不了这个问题。

## 用法（从仓库根跑）

    python scripts/e54_upstream_verify.py        # 先有快照（决定探哪些仓库）
    python scripts/e54_screenshot_probe.py       # 再取证图片
    python scripts/e54_pool_table_join.py        # 最后灌进文档

    python scripts/e54_screenshot_probe.py --limit 5 --stdout   # 小样本自查

## 防退化自检，以及为什么阈值是 30% 而不是 50%

- **阳性对照**：SuperTux 取不到图 ⇒ 抓取逻辑坏了（它 README 第一屏就是官方实机图），`exit 2` 不写产物。
- **验证通过率 <30% 即失败**：阈值压低是因为**本机的网络边界**，不是解析退化 ——
  `i.imgur.com` 这类图床在本机（curl 也一样）连不上，而它在 jam 项目 README 里极其常见。
  这类候选不算「验证通过」，改标 `verified=false` + 原因，交给文档如实写成「未验证」。
  ⛔ 三条纪律：**不可达 ≠ 不存在**、**不可达 ≠ 已验证**、**可以给链接但必须标注未验证**。
"""

import argparse
import concurrent.futures
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from e54_upstream_verify import get_token  # 令牌来源只有一份

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

UA = "e53-screenshot-probe"
IMG_EXT = (".png", ".jpg", ".jpeg", ".gif", ".webp")
# 「实机图」信号词：文件名或 alt 文本命中越多，越像真截图
GOOD = ("screen", "preview", "gameplay", "game", "screenshot", "shot", "play",
        "gallery", "demo", "ingame", "in-game", "trailer", "action", "teaser", "cover")
# 装饰物：命中即淘汰（徽章/logo/图标/捐赠按钮都不是截图）。
# ⚠️ youtube/ytimg 也在这一列，是**本机可达性**决定而非内容判断：
#    img.youtube.com 在本机连 curl 都连不上（见 §A.0 预览列说明），无法验证的链接一律不进附录。
BAD = ("badge", "logo", "icon", "favicon", "shield", "button", "paypal", "donate",
       "sponsor", "build", "status", "license", "star", "maintainer", "prs",
       "issue", "workflow", "circleci", "travis", "appveyor", "codecov", "version",
       "youtube", "ytimg")

# 第 6 档（文件树）的词闸：只认名字里带这些词的位图，
# 且必须躲开 TREE_SKIP —— 否则会把「精灵表/贴图」当成实机图，那是误导不是取证。
TREE_MUST = ("screenshot", "screen", "preview", "gameplay", "gallery", "shots", "teaser")
TREE_SKIP = ("sprite", "tile", "tiles", "texture", "atlas", "gfx", "assets", "data/",
             "icons", "logo", "font", "ui/", "raw_", "player", "enemy", "background",
             "thumb_icon", "favicon")

REF_RE = re.compile(
    r"""(?:!\[[^\]]*\]\(\s*<?([^)\s>]+)?>?(?:\s+["'][^"']*["'])?\s*\)"""
    r"""|<img[^>]+src\s*=\s*["']([^"']+)["'])""",
    re.I)


def get_text(url, token=None, accept=None, timeout=12):
    """返回 (status, text_or_None)。任何异常都折成 (状态码或 -1, None)，绝不抛。

    ⚠️ 超时压到 12s（图片探测 8s）：一个死域名按 25s 算，85 个仓库串跑能拖到半小时，
    看着像「抓取器卡死」而不是「这个仓库没图」。
    """
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    if accept:
        req.add_header("Accept", accept)
    if token and url.startswith("https://api.github.com"):
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, None
    except Exception:
        return -1, None


def get_json(url, token):
    st, txt = get_text(url, token, accept="application/vnd.github+json")
    try:
        return st, json.loads(txt)
    except Exception:
        return st, None


RAW_RE = re.compile(r"https://raw\.githubusercontent\.com/([\w.-]+/[\w.-]+)/([^/]+)/(.+)")
BLOB_RE = re.compile(r"https://github\.com/([\w.-]+/[\w.-]+)/blob/([^/]+)/(.+)")


def via_contents_api(url, token):
    """仓库内文件的存在性证明：走认证 contents API，⛔ 不靠直接 GET raw 域名。

    本机实测：`raw.githubusercontent.com` 被 Python urllib 侧连接重置
    （[WinError 10054]），而 curl 同一 URL 返回 200 —— 这是本机的网络中间层问题，
    不是「这个仓库没有图」。改走 api.github.com（稳定可用）后，
    存在性由 `type == file` 证明、体积由 `size` 给出、直链用 API 自己返回的 `download_url`
    （GitHub 给的链接，⛔ 不是我拼出来的）。
    """
    m = RAW_RE.match(url) or BLOB_RE.match(url)
    if not m:
        return None
    repo, ref, rel = m.group(1), m.group(2), m.group(3)
    api = "https://api.github.com/repos/%s/contents/%s?ref=%s" % (
        repo, urllib.parse.quote(rel, safe="/%"), ref)
    st, body = get_json(api, token)
    if st == 200 and isinstance(body, dict) and body.get("type") == "file":
        size = body.get("size") or 0
        name = str(body.get("name", "")).lower()
        if not name.endswith(IMG_EXT):
            return False, None, "不是位图（%s）" % name.rsplit(".", 1)[-1]
        if size < 2048:
            return False, None, "过小(%dB，多半是图标)" % size
        return True, (body.get("download_url") or url), "ok"
    if st == 404:
        return False, None, "README 指向的文件不在该分支（contents API 404）"
    return False, None, "contents API HTTP %s" % st


def image_ok(url, token):
    """实测一张图，返回 (可用?, 可写的直链或 None, 说明)。

    判定口径：必须是位图（`.svg` 一律淘汰 —— 附录要回答「跑起来什么样子」，矢量 logo 答不了）、
    真的存在（2xx / contents API 命中）、体积 > 2 KB（图标/徽章折掉）。
    """
    low = url.lower().split("?")[0]
    if low.endswith(".svg"):
        return False, None, "矢量图(非截图)"
    hit = via_contents_api(url, token)
    if hit is not None:
        return hit
    if low.endswith(".ico"):
        return False, None, "站点图标"
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Range": "bytes=0-65535"})
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            ct = (r.headers.get("content-type") or "").lower()
            n = len(r.read())
            status = r.status
    except Exception as e:
        return False, None, "本机不可达(%s)" % type(e).__name__
    if status not in (200, 206):
        return False, None, "HTTP %s" % status
    if not ct.startswith("image/"):
        return False, None, "非图片(%s)" % (ct or "无 content-type")
    if "svg" in ct:
        return False, None, "矢量图(非截图)"
    if n < 2048:
        return False, None, "过小(%dB，多半是图标)" % n
    return True, url, "ok"


def refs_from_md(text):
    """README 里的图片引用：[(url, 上下文关键词)]"""
    out = []
    for m in REF_RE.finditer(text or ""):
        url = m.group(1) or m.group(2)
        ctx = text[max(0, m.start() - 80):m.start()] + (m.group(0)[:0])
        alt = re.search(r"!\[([^\]]*)\]", text[m.start():m.start() + 300])
        out.append((url, ((alt.group(1) if alt else "") + " " + ctx).lower()))
    return out


def resolve(url, owner_repo, branch):
    """相对路径 → raw.githubusercontent 直链；data:/anchor/协议外的直接丢掉。"""
    u = (url or "").strip()
    if not u or u.startswith(("#", "data:", "mailto:")):
        return None
    if u.startswith("//"):
        u = "https:" + u
    low = u.lower()
    if low.startswith(("http://", "https://")):
        if "github.com" in low and "/blob/" in low:
            # README 常写 https://github.com/o/r/blob/BRANCH/path.png —— 那是网页不是图
            m = re.match(r"https://github\.com/([\w.-]+/[\w.-]+)/blob/([^/]+)/(.+)", u)
            if m:
                return "https://raw.githubusercontent.com/%s/%s/%s" % (m.group(1), m.group(2), m.group(3))
        if low.startswith("http://"):
            u = "https://" + u[7:]
        return u
    base = "https://raw.githubusercontent.com/%s/%s" % (owner_repo, branch)
    rel = u.lstrip("./").lstrip("/")
    return base + "/" + rel


def score(url, ctx):
    s = 0
    low = url.lower()
    for k in GOOD:
        if k in low or k in ctx:
            s += 3
    for k in BAD:
        if any(t in low for t in ("/" + k, "-" + k, "_" + k, k + ".")) or low.rstrip("/").endswith(k):
            return -99
    if low.split("?")[0].endswith(IMG_EXT):
        s += 4
    else:
        return -99
    if any(k in low for k in ("screen", "preview", "gameplay")):
        s += 3
    if "/media/" in low or "/images/" in low or "/screenshots/" in low or "/assets/" in low:
        s += 1
    return s


def pick(cands, token, limit=8):
    """按分数从高到低**实测**候选。

    返回 (命中的直链, 来源, 本机不可达的最佳候选直链, 其来源, 失败说明列表, 实测个数)。

    ⚠️ 第三/四个返回值是**诚实性**用的，不是偷懒：i.imgur.com / img.youtube.com 这类图床
    在本机（含 curl）完全连不上，但链接本身来自上游 README、对翻墙环境的读者是可用的。
    所以：能验证的绝不退而求其次；一条都验证不了时，把「最佳候选 + 未验证」如实交出去，
    由文档标成「⚠️ 本机无法验证」，⛔ 既不假装验证过、也不谎报「仓库没有图」。
    """
    dead, tried, unv = [], 0, (None, None)
    for sc, url, src in cands:
        if sc <= -99:
            continue
        if tried >= limit:
            break
        tried += 1
        ok, canon, why = image_ok(url, token)
        if ok:
            return canon or url, src, unv[0], unv[1], dead, tried
        dead.append(why)
        if why.startswith("本机不可达") and unv[0] is None:
            unv = (url, src)
    return None, None, unv[0], unv[1], dead, tried


def probe_repo(path, token, pool_img=""):
    """返回一条取证记录。reason 只在 url 为空时填，且必须是实测出来的原因。"""
    rec = {"path": path, "url": "", "source": "", "verified": None, "n_refs": 0, "reason": ""}
    st, meta = get_json("https://api.github.com/repos/" + path, token)
    if st != 200 or not meta:
        rec["reason"] = "仓库信息取不到（API HTTP %s）" % st
        return rec
    branch = meta.get("default_branch") or "master"
    homepage = (meta.get("homepage") or "").strip()

    # 1) README 原图
    st, readme = get_text("https://api.github.com/repos/%s/readme?ref=%s" % (path, branch),
                          token, accept="application/vnd.github.raw+json")
    cands = []
    if st == 200 and readme:
        for url, ctx in refs_from_md(readme):
            r = resolve(url, path, branch)
            if r:
                cands.append((score(r, ctx), r, "README"))
    else:
        rec["no_readme"] = True

    # 2) 仓库根目录的图片（有些项目 README 全文字、图却躺在根目录）
    st, listing = get_json("https://api.github.com/repos/%s/contents?ref=%s" % (path, branch), token)
    if st == 200 and isinstance(listing, list):
        for it in listing:
            if it.get("type") == "file" and str(it.get("name", "")).lower().endswith(IMG_EXT):
                r = it.get("download_url")
                if r:
                    cands.append((score(r, it["name"].lower()), r, "根目录"))

    # 3) 官网 / GitHub Pages 首页的 <img>
    if homepage:
        st, html = get_text(homepage, None)
        if st == 200 and html:
            for m in re.finditer(r"""<img[^>]+src\s*=\s*["']([^"']+)["']""", html, re.I):
                src = m.group(1)
                if src.startswith("//"):
                    src = "https:" + src
                elif src.startswith("/"):
                    mm = re.match(r"(https?://[^/]+)", homepage)
                    src = (mm.group(1) if mm else homepage.rstrip("/")) + src
                elif not src.startswith("http"):
                    src = homepage.rstrip("/") + "/" + src.lstrip("./")
                cands.append((score(src, "homepage"), src, "官网"))

    # 4) 原表「截图」列里那条链接：本轮也要实测一遍才允许进附录
    if pool_img:
        cands.append((score(pool_img, "pool") - 1, pool_img, "原表列"))

    rec["n_refs"] = len(cands)
    cands.sort(key=lambda c: -c[0])
    unv_url = unv_src = None
    url, src, u1, u2, dead, tried = pick(cands, token)
    if url:
        rec.update(url=url, source=src, verified=True)
        return rec
    unv_url, unv_src = u1, u2

    # 5) 兜底：Releases —— 游戏的实机图常常只贴在发布说明里，README 全是徽章
    st, rel = get_json("https://api.github.com/repos/%s/releases?per_page=5" % path, token)
    more = []
    if st == 200 and isinstance(rel, list):
        for r_ in rel:
            for u2_, ctx in refs_from_md(r_.get("body") or ""):
                rr = resolve(u2_, path, branch)
                if rr:
                    more.append((score(rr, ctx + " release"), rr, "Release 正文"))
            for x in (r_.get("assets") or []):
                if str(x.get("name", "")).lower().endswith(IMG_EXT):
                    du = x.get("browser_download_url") or x.get("url")
                    if du:
                        more.append((score(du, x["name"].lower() + " release shot"), du, "Release 附件"))
    if more:
        rec["n_refs"] += len(more)
        more.sort(key=lambda c: -c[0])
        url, src, u1, u2, dead2, tried2 = pick(more, token)
        if url:
            rec.update(url=url, source=src, verified=True)
            return rec
        dead += dead2
        tried += tried2
        unv_url, unv_src = unv_url or u1, unv_src or u2

    # 6) 最后一档：整棵文件树里**文件名带截图词**的位图（一次 recursive tree 调用）
    #    ⚠️ 必须限词：游戏仓库 data/ assets/ gfx/ 下的 PNG 是**贴图与精灵**，
    #       拿它当「跑起来什么样子」是误导 —— 所以只认名字里带 screenshot/preview/gameplay 的那一类。
    even = []
    tree_imgs = 0          # 文件树里的位图总数（过闸前），用来把「没有实机图」和「仓库没图」分开写
    st, tree = get_json("https://api.github.com/repos/%s/git/trees/%s?recursive=1"
                        % (path, urllib.parse.quote(branch)), token)
    if st == 200 and isinstance(tree, dict):
        for t in (tree.get("tree") or []):
            fp = str(t.get("path", ""))
            low = fp.lower()
            if t.get("type") != "blob" or not low.endswith(IMG_EXT):
                continue
            tree_imgs += 1
            if not any(k in low for k in TREE_MUST) or any(k in low for k in TREE_SKIP):
                continue
            url6 = "https://raw.githubusercontent.com/%s/%s/%s" % (path, branch, urllib.parse.quote(fp))
            even.append((score(url6, os.path.basename(low)), url6, "仓库文件（名含截图词）"))
    rec["tree_bitmaps"] = tree_imgs
    if even:
        rec["n_refs"] += len(even)
        even.sort(key=lambda c: -c[0])
        url, src, u1, u2, dead3, tried3 = pick(even, token, limit=5)
        if url:
            rec.update(url=url, source=src, verified=True)
            return rec
        dead += dead3
        tried += tried3
        unv_url, unv_src = unv_url or u1, unv_src or u2

    # 六档都验证不出可用图，但存在「本机网络到不了」的候选 ⇒ 如实交出链接并**标注未验证**
    if unv_url:
        host = urllib.parse.urlparse(unv_url).netloc
        rec.update(url=unv_url, source=unv_src, verified=False)
        rec["reason"] = ("链接来自 %s，但本机连不上 %s（curl 同样失败，不是解析 bug）⇒ 未验证"
                         % (unv_src, host))
        return rec

    # 给不出图 ⇒ 必须写**实测出来的**原因（含糊话等于没写）
    allc = cands + more + even
    deco = sum(1 for c in allc if c[0] <= -99)
    n_total = len(allc)
    if rec.get("no_readme") and n_total == 0:
        rec["reason"] = ("没有 README；根目录 / 官网 / 原表截图列 / 发布说明 / 文件树也没有可认作实机图的位图"
                         + ("（文件树里另有 %d 个位图，全被贴图/精灵词闸淘汰）" % tree_imgs if tree_imgs else ""))
    elif n_total == 0:
        # ⚠️ 这一句必须区分两种「没有」：仓库真的一张图都没有，还是有几百张但全是素材贴图。
        #    把后者写成「没有找到图片引用」= 误导（读的人会以为这是个纯代码仓库）。
        rec["reason"] = ("该仓库**没有任何位图文件**（六档取证：README / 根目录 / 官网 / 原表截图列 / 发布说明 / 文件树）"
                         if tree_imgs == 0 else
                         "六档取证里没有一处可认作**实机图**的引用 —— 但仓库里有 %d 个位图，"
                         "全部落在贴图 / 精灵 / UI / 图标类（词闸淘汰，见脚本 TREE_MUST / TREE_SKIP），"
                         "⛔ 拿它们当「跑起来什么样子」是误导，不是取证" % tree_imgs)
    elif tried == 0:
        rec["reason"] = ("只找到 %d 个徽标/图标/视频封面/贴图类引用，没有实机图（全部按装饰物或按词闸淘汰）" % deco)
    else:
        kinds = {}
        for d in dead:
            kinds[d] = kinds.get(d, 0) + 1
        rec["reason"] = "有 %d 个图片引用（其中装饰物 %d），实测 %d 个全不可用：%s" % (
            n_total, deco, tried,
            "、".join("%s×%d" % (k, v) for k, v in sorted(kinds.items(), key=lambda x: -x[1])))
    return rec


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--snapshot", default="logs_temp/e54_upstream_verify.json")
    ap.add_argument("--out", default="logs_temp/e54_screenshots.json")
    ap.add_argument("--pool", default="docs/archive/开源横版卷轴游戏总表_更新版.md",
                    help="原表路径，用于把它「截图」列的链接一起实测（不作为未验证的兜底）")
    ap.add_argument("--limit", type=int, default=0, help="只探前 N 个（自查用）")
    ap.add_argument("--stdout", action="store_true")
    args = ap.parse_args()

    token = get_token()
    if not token:
        print("⛔ 没有 GitHub 令牌：README/contents 匿名 60 次/小时必被限流，取证会大面积假阴性。")
        sys.exit(3)

    pool_imgs = {}
    if os.path.exists(args.pool):
        from e54_pool_table_join import parse_pool
        pool_imgs = {p: v.get("img", "") for p, v in parse_pool(args.pool)[0].items()}

    with open(args.snapshot, encoding="utf-8") as f:
        snap = json.load(f)
    targets = []
    for r in snap["rows"]:
        if r["exists"] is not True:
            continue
        p = r.get("renamed_to") or r["path"]
        if "/" in p:
            targets.append((r["name"], p))
    seen, uniq = set(), []
    for name, p in targets:
        if p.lower() not in seen:
            seen.add(p.lower())
            uniq.append((name, p))
    if args.limit:
        uniq = uniq[:args.limit]

    print("探测 %d 个仓库（认证 API，8 线程并发）…" % len(uniq), flush=True)
    done = []

    def work(item):
        name, p = item
        rec = probe_repo(p, token, pool_imgs.get(p.lower(), ""))
        rec["name"] = name
        done.append((name, p, rec))
        tag = ("✅ " + rec["source"]) if rec["url"] and rec.get("verified") \
            else (("⚠️ 未验证 " + rec["source"]) if rec["url"] else "—")
        print("  [%2d/%2d] %-26s %s %s" % (
            len(done), len(uniq), name, tag, rec["url"] or rec["reason"]), flush=True)
        return rec

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
        recs = list(ex.map(work, uniq))

    got = sum(1 for r in recs if r["url"] and r.get("verified"))
    unver = sum(1 for r in recs if r["url"] and not r.get("verified"))
    # 阳性对照：SuperTux 是公认有实机图的仓库；它取不到 = 抓取器坏了，不是「这个仓库没图」
    ctl = next((r for r in recs if r["path"].lower() == "supertux/supertux"), None)
    if ctl and not ctl["url"]:
        print("⛔ 阳性对照失败：SuperTux 都没取到图（原因：%s）⇒ 抓取逻辑有问题，不写产物" % ctl["reason"])
        sys.exit(2)
    rate = got / max(1, len(recs))
    if rate < 0.3:
        print("⛔ 验证通过率 %.0f%%（<30%%）：多半是限流或解析退化，不写产物" % (rate * 100))
        sys.exit(2)

    payload = {"generated_at": datetime.now(timezone.utc).isoformat(),
               "probed": len(recs), "with_image": got, "unverified": unver, "rows": recs}
    if args.stdout:
        print(json.dumps(payload, ensure_ascii=False, indent=1))
        return
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=1)
    print("已写 %s：%d / %d 取到**实测通过**的图片直链，另有 %d 条只有「本机连不上的图床」链接（标为未验证）"
          % (args.out, got, len(recs), unver))


if __name__ == "__main__":
    main()
