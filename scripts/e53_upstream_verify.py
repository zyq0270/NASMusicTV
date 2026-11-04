r"""
E53 上游候选池复验 —— 对 docs/e53-run-and-gun-survey.md 附录 A/B 的 84 + 18 个仓库逐条打 GitHub API。

## 为什么需要这个脚本

两份「开源横版卷轴游戏总表」（原表 → 核验报告 → 更新版）是人工 + 网页抓取产出的，
本次复验发现两类系统性错误：

1. **许可证列不可信**：多行与 GitHub API 的 license.spdx_id 不符（SuperTux 写 GPLv2+ 实为 GPL-3.0、
   SpaceHuggers 写 MIT 实为 GPL-3.0、BGUG 写 GPLv3 实为 MIT、NESlib 写 MIT 实为 Zlib …）。
   E53 的 C4 约束（OSI 许可）直接依赖这一列 ⇒ 错了就会把不合规的上游移植进 GPL 仓库。
2. **「失效」与「路径写错」混为一谈**：Cendric / Hawkthorne 并非作者删库，而是路径写错
   （实际在 tizian/Cendric2 / hawkthorne/hawkthorne-journey）。

所以附录 A 的每一个数字都必须由本脚本再生，⛔ 不许手抄。

## 状态码语义（关键，别再用 curl 的退出码判断）

- 200 → 存在
- 301 → **仓库改名/转移**，新路径在 Location 头里（openlierox、DungeonRush 属此类）
- 404 → 该路径不存在（删除或从未存在）
- 403 → **限流，不是失效**！必须单独标 UNKNOWN，绝不能折进 ❌
- 451 → 因 DMCA 被屏蔽 ⇒ 法律风险信号，单独标

## 自检（known-positive control）

先打一个已知存在且 MIT 的仓库（jakesgordon/javascript-tetris）。若它读不出来，
说明 token / 解析 / 网络坏了 —— 此时整轮的「404」全是假结论，直接 exit 2。

## 用法

    python scripts/e53_upstream_verify.py            # 全量（84 + 18 条，需 gh 登录或 GITHUB_TOKEN）
    python scripts/e53_upstream_verify.py --group A  # 只跑附录 A
    python scripts/e53_upstream_verify.py --no-api    # 只重排上次的 JSON 快照，不打 API

匿名限额 60 次/小时 ⇒ 102 条必须用 token（5000 次/小时）。取 token 的顺序：
环境变量 GITHUB_TOKEN → `gh auth token` → 匿名（会踩限流，脚本会如实标 UNKNOWN）。
产物落在 logs_temp/e53_upstream_verify.{md,json}。
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# ---------------------------------------------------------------- 候选清单
# (名称, 表内路径, 表内许可, 表内核验, [备选路径])
GROUP_A = [
    ("SuperTux", "SuperTux/supertux", "GPLv2+", "✅", []),
    ("ReTux", "retux-game/retux", "GPL", "✅", []),
    ("Frogatto & Friends", "frogatto/frogatto", "zlib+CC0", "✅", []),
    ("DDNet", "ddnet/ddnet", "Zlib", "✅", []),
    ("doukutsu-rs", "doukutsu-rs/doukutsu-rs", "MIT", "✅", []),
    ("godot-platformer-2d", "gdquest-demos/godot-platformer-2d", "MIT", "✅", []),
    ("OpenBOR", "DCurrent/openbor", "BSD-3", "✅", []),
    ("Trip on the Funny Boat", "puskutraktori/funnyboat", "GPLv2+", "❌", []),
    ("SpaceHuggers", "KilledByAPixel/SpaceHuggers", "MIT", "✅", []),
    ("SlayHorizon 2d-platformer", "SlayHorizon/2d-platformer-demo", "MIT", "❌", []),
    ("SquareSouls", "RTD-Tech/SquareSouls", "MIT", "✅", []),
    ("Teeworlds", "teeworlds/teeworlds", "Zlib", "✅", []),
    ("Cendric", "Ironbell/Cendric", "MIT", "❌", ["tizian/Cendric2", "tizian/Cendric"]),
    ("OpenLieroX", "openlierox/openlierox", "GPLv2", "✅", ["albertz/openlierox"]),
    ("Moustachevania", "swivelmaster/moustachevania", "MIT", "✅", []),
    ("Hatman Adventure", "HatmanGame/Hatman-Adventure", "MIT", "❌", []),
    ("Wave of Fate", "North-Abyss/wave-of-fate", "MIT", "✅", []),
    ("Hawkthorne", "hawkthorne/hawkthorne", "MIT", "❌", ["hawkthorne/hawkthorne-journey"]),
    ("OpenTomb", "opentomb/OpenTomb", "GPLv2", "✅", []),
    ("Chaos Projectile", "chaosprojectile/chaosprojectile", "GPLv3", "❌", []),
    ("Captain Holetooth", "ChronosNexus/Captain-Holetooth", "MIT", "❌", []),
    ("8-Bit Commando", "2dengine/8bitcommando", "Zlib", "❌", []),
    ("Nova The Squirrel", "NovaSquirrel/NovaTheSquirrel", "GPLv3", "✅", []),
    ("Exponaut", "Pawlogates/Exponaut", "MIT", "✅", []),
    ("Portal 2D", "Calinou/portal-2d", "MIT", "❌", []),
    ("Fulfilling Knight", "psychoPT4/Fulfilling-knight", "MIT", "✅", []),
    ("Ardentryst", "elletrudgett/Ardentryst", " GPLv3", "❌", []),
    ("Clonepoint", "Clonepoint/Clonepoint", "GPLv3", "❌", ["riyyas/clonepoint"]),
    ("Soldat", "soldat/soldat", "MIT", "✅", []),
    ("Lorz", "andykswong/lorz", "MIT", "✅", []),
    ("Xye", "vevix/xye", "Zlib", "❌", ["xye-game/xye"]),
    ("VVVVVV", "TerryCavanagh/VVVVVV", "VVVVVV License", "✅", []),
    ("Prince of Persia (Apple II)", "jmechner/Prince-of-Persia-Apple-II", "MIT", "✅", []),
    ("TSC (Dr. M.)", "SecretChronicles/tsc", "GPLv3", "✅", []),
    ("Azimuth", "mdsteele/azimuth", "GPLv3+", "✅", []),
    ("Moondust Project", "WohlSoft/Moondust-Project", "GPLv3", "✅", []),
    ("Ninslash", "Siile/Ninslash", "GPLv2", "✅", []),
    ("OpenMetroid", "OpenMetroid/OpenMetroid", "MIT", "❌", []),
    ("OpenAxiom", "OpenAxiomGame/OpenAxiom", "MIT", "❌", []),
    ("BGUG", "bluefireteam/bgug", "GPLv3", "✅", []),
    ("Cannonball", "djmips/cannonball", "GPLv2", "❌", []),
    ("MetalSlug-OpenSource", "ChongQingNO1/MetalSlug-OpenSource", "MIT", "❌", []),
    ("Sneak", "andrew-j-armstrong/sneak", "MIT", "❌", []),
    ("Pygame Platformer", "clear-code-projects/Pygame-Platformer", "MIT", "❌", []),
    ("Tiny Crate", "HarmonyHoney/tiny_crate", "Unlicense", "✅", []),
    ("Froglet Frenzy", "da-Kai/FrogletFrenzy", "MIT", "✅", []),
    ("Sealed Bite", "securas/sealed-bite", "MIT", "❌", []),
    ("Enjoined", "akrck02/enjoined", "MIT", "✅", []),
    ("Chronofist", "bates64/chronofist", "MIT", "✅", []),
    ("Clown Panic", "emredogann/clownpanic", "MIT", "✅", []),
    ("Fish Blaster", "jrob774/fish-blaster", "MIT", "✅", []),
    ("Primrose", "shadowmitia/primrose", "MIT", "✅", []),
    ("Phase Reveal", "avtne/phase-reveal", "CC BY-NC-SA", "✅", []),
    ("Retrochase", "securas/retrochase", "MIT", "❌", []),
    ("13th Floor", "js13kGames/13th-floor", "MIT", "✅", []),
    ("Nothing", "tsoding/nothing", "MIT", "✅", []),
    ("CloneKeen", "keenspace/clonekeen", "GPLv2", "❌", []),
    ("Battle City Remake", "baiXfeng/battle-city-remake", "MIT", "✅", []),
    ("OpenMegaMan", "OpenMegaMan/OpenMegaMan", "GPLv3", "❌", []),
    ("L'Abbaye des Morts", "nevat/abbayedesmorts-gpl", "GPLv3", "✅", []),
    ("OpenStreets", "bsilvapinto/openstreets", "GPLv3", "❌", []),
    ("Blob Wars", "perpendicular-dimensions/blobwars", "GPLv2", "✅", ["BlobWars/blobwars"]),
    ("OpenTyrian", "opentyrian/opentyrian", "GPLv2", "✅", []),
    ("Golden Axe Open", "retro-arcade/golden-axe-open", "MIT", "❌", []),
    ("Contra Remake", "david-campos/contra", "MIT", "✅", ["retro-games-dev/contra-sdl"]),
    ("Alien Syndrome Open", "arcade-retro/alien-syndrome-open", "GPLv2", "❌", []),
    ("PICO-8", "lexaloffle/pico8", "PICO-8 License", "❌", []),
    ("NESlib", "8bitworkshop/neslib", "MIT", "✅", ["nesdoug/NESlib"]),
    ("Catacomb Kids", "bitfort/catacomb-kids", "MIT", "❌", ["catacomb-kids/catacomb-kids"]),
    ("Rogue Heroes", "roguetech/rogue-heroes", "MIT", "❌", []),
    ("TinyKeep", "photonstorm/TinyKeep", "MIT", "❌", []),
    ("DungeonRush", "yujqiao/DungeonRush", "GPLv3", "✅", ["rapiz1/DungeonRush"]),
    ("Crimsonland Open", "crimsonland/crimsonland-sdl", "GPLv2", "❌", []),
    ("Space Shooter Rogue", "tsoding/space-shooter-roguelike", "MIT", "❌", ["tsoding/space-shooter-rogue"]),
    ("RogueInvader", "retro-games-dev/RogueInvader", "MIT", "❌", []),
    ("Infra Arcana", "martin-tornqvist/ia", "GPLv2", "✅", ["infraarcana/infraarcana"]),
    ("Hollow Rogue", "skullbtw/HollowRogue", "MIT", "❌", []),
    ("RogueSword", "andrew-j-armstrong/roguesword", "MIT", "❌", []),
    ("Loot Goblin", "securas/loot-goblin", "MIT", "❌", []),
    ("RoguelikePlatformer", "clear-code-projects/RoguelikePlatformer", "MIT", "❌", []),
    ("Open Sonic", "3ds-brew/open-sonic-3ds", "GPLv3", "✅", ["OpenSonic/opensonic"]),
    ("Editarrr", "LPGameDevs/EditarrrPublic", "MIT", "✅", []),
    ("Minego", "Commoon/Minego", "MIT", "✅", []),
    ("Co-op Platformer", "ChronosNexus/godot-coop-platformer", "MIT", "❌", []),
]

# E53 正文 §二 / §三 自己列出的候选（同一口径复验，别只有附录有数）
GROUP_B = [
    ("commander-in-chief", "shoemoney/commander-in-chief", "MIT", "正文§二.1", []),
    ("last-bastion", "chenmoulaile/last-bastion", "MIT", "正文§二.1", []),
    ("freedom-force", "Madmartigan1/freedom-force", "MIT", "正文§二.1", []),
    ("polished-arcade-shooter", "phaethix/polished-arcade-shooter", "MIT", "正文§二.1", []),
    ("abuse-vrenna", "Esl1h/abuse-vrenna", "GPL-2.0", "正文§二.1", []),
    ("Blastix", "DanMat/Blastix", "MIT", "正文§二.1", []),
    ("sgt-ruka", "ulebule/sgt-ruka", "MIT", "正文§二.1", []),
    ("cdogs-sdl", "cxong/cdogs-sdl", "GPL-2.0", "正文§二.1", []),
    ("Zora", "RANJEETJ06/Zora", "无 LICENSE", "正文§二.1 排除", []),
    ("flex-commando", "BuneZT/flex-commando", "无 LICENSE", "正文§二.1 排除", []),
    ("hivefall", "aimanh2250-lab/hivefall", "无 LICENSE", "正文§二.1 排除", []),
    ("steel-breakout-frontline", "xingyuliu586-tech/steel-breakout-frontline", "未授权", "正文§二.1 排除", []),
    ("eon-commando", "drsamonline/eon-commando", "PolyForm NC", "正文§二.1 排除", []),
    ("metal-slug(fans)", "alfredo1995/metal-slug", "无", "正文§二.1 排除", []),
    ("ArkanoidJS", "bastilimbach/ArkanoidJS", "MIT", "正文§3.1", []),
    ("javascript-breakout", "jakesgordon/javascript-breakout", "待核实", "正文§3.1", []),
    ("asteroids(foxfire)", "foxfirecodes/asteroids", "MIT", "正文§3.2", []),
    ("asteroids(jsocol)", "jsocol/asteroids", "BSD-2", "正文§3.2", []),
    ("space-invaders", "pi0/space-invaders", "MIT", "正文§3.2", []),
    ("404-js13k-2020", "deathraygames/404-js13k-2020", "MIT", "正文§3.2", []),
    ("tiny-platformer", "jakesgordon/javascript-tiny-platformer", "MIT", "正文§3.3", []),
    ("tower-defense(Casmo)", "Casmo/tower-defense", "MIT", "正文§3.4", []),
    ("JavaScript-Snake", "patorjk/JavaScript-Snake", "MIT", "正文§3.5", []),
    ("snake(cleytonrs)", "cleytonrs/snake", "MIT", "正文§3.5", []),
    ("javascript-tetris", "jakesgordon/javascript-tetris", "MIT", "正文§3.6", []),
    ("tetris(MehmetMHY)", "MehmetMHY/tetris", "MIT", "正文§3.6", []),
    ("life-game", "theajack/life-game", "MIT", "正文§3.7", []),
    ("Game-Of-Life", "Physics-Simulations/Game-Of-Life", "Apache-2.0", "正文§3.7", []),
    ("js1024_sakura", "rrafols/js1024_sakura", "MIT", "正文§3.7", []),
    ("html5-games", "tvanas/html5-games", "MIT", "正文§3.8", []),
    ("sprig", "hackclub/sprig", "MIT", "正文§3.8", []),
    ("agent-arcade", "DanWahlin/agent-arcade", "MIT", "正文§3.8", []),
    ("Pong-Godot", "xRedan/Pong-Godot", "MIT", "正文§3.9", []),
    ("godot-demo-projects", "godotengine/godot-demo-projects", "MIT", "正文§3.9", []),
    ("canvas_danmaku", "andyp123/canvas_danmaku", "无许可", "正文§3.10", []),
    ("oniki", "peachey2k2/oniki", "待验证", "正文§3.10", []),
    ("javascript-racer", "jakesgordon/javascript-racer", "MIT(素材侵权)", "正文§3.11", []),
    # Zoomies!（正文 §一 lib-1 只给了名字）—— ⚠️ 无可靠路径，不探测。
    # 曾猜 franga2000/zoomies 打回 404，但那**只证伪这个猜测**，不能作为「项目已下架」的证据。
    ("Zoomies!", "", "待核实", "正文§一 lib-1（未定位到仓库）", []),
]

OSI_OK = {
    "MIT", "MIT License", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC",
    "GPL-2.0", "GPL-3.0", "LGPL-2.1", "LGPL-3.0", "MPL-2.0", "Unlicense",
    "Zlib", "Artistic-2.0", "ECL-2.0",
}
# 对 GPL v3 项目可直接合入的（copyleft 上游只允许本地私有分支使用，公开构建不含）
GPL_COMPATIBLE = OSI_OK | {"OTHER", "NOASSERTION"}


def get_token():
    t = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if t:
        return t.strip()
    gh = shutil.which("gh")
    if gh:
        try:
            out = subprocess.run([gh, "auth", "token"], capture_output=True, text=True, timeout=30)
            if out.returncode == 0 and out.stdout.strip():
                return out.stdout.strip()
        except Exception:
            pass
    return None


class _StopRedirect(urllib.request.HTTPRedirectHandler):
    """301 的 Location 就是改名后的新路径 —— 必须自己拿到，不能让 urllib 跟进去。"""

    def redirect_request(self, *a, **k):
        return None


def api_get(url, token):
    """返回 (status, body_dict_or_None, redirect_location_or_None)。404/403/301 都不抛异常。"""
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "e53-upstream-verify",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    if token:
        req.add_header("Authorization", "Bearer " + token)

    opener = urllib.request.build_opener(_StopRedirect)
    try:
        with opener.open(req, timeout=25) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8")), None
    except urllib.error.HTTPError as e:
        body = None
        try:
            body = json.loads(e.read().decode("utf-8"))
        except Exception:
            pass
        return e.code, body, (e.headers.get("Location") if e.headers else None)
    except Exception as e:
        return -1, {"message": "network/timeout: %r" % (e,)}, None


def probe(path, token):
    status, body, loc = api_get("https://api.github.com/repos/" + path, token)
    if status == 200:
        return {
            "path": path, "http": 200, "exists": True,
            "license": (body.get("license") or {}).get("spdx_id") or "NONE",
            "stars": body.get("stargazers_count"),
            "pushed": (body.get("pushed_at") or "")[:10],
            "archived": body.get("archived"),
            "desc": (body.get("description") or "")[:120],
            "renamed_to": None,
        }
    if status in (301, 302, 307) and loc:
        _, nbody, _ = api_get(loc, token)
        npath = None
        if nbody and "full_name" in nbody:
            npath = nbody["full_name"]
            return {
                "path": path, "http": status, "exists": True, "renamed_to": npath,
                "license": (nbody.get("license") or {}).get("spdx_id") or "NONE",
                "stars": nbody.get("stargazers_count"),
                "pushed": (nbody.get("pushed_at") or "")[:10],
                "archived": nbody.get("archived"),
                "desc": (nbody.get("description") or "")[:120],
            }
        return {"path": path, "http": status, "exists": True, "renamed_to": npath or loc,
                "license": "UNKNOWN", "stars": None, "pushed": "", "archived": None, "desc": ""}
    if status == 404:
        return {"path": path, "http": 404, "exists": False, "license": None,
                "stars": None, "pushed": "", "archived": None, "desc": "", "renamed_to": None}
    if status == 451:
        return {"path": path, "http": 451, "exists": False, "license": "DMCA_BLOCKED",
                "stars": None, "pushed": "", "archived": None, "desc": "451 unavailable_for_legal_reasons",
                "renamed_to": None}
    # 403 / 401 / -1 / 其它 —— 一律 UNKNOWN，绝不折成「失效」
    return {"path": path, "http": status, "exists": None, "license": "UNKNOWN",
            "stars": None, "pushed": "", "archived": None,
            "desc": "UNKNOWN(" + str((body or {}).get("message", ""))[:60] + ")", "renamed_to": None}


def canon_lic(s):
    """把「GPLv3+ / GPL-3.0 / GPL 3」这类写法归一成 GPL3，避免把排版差异误判成分歧。

    ⚠️ 这一步只做**族 + 主版本**归一：GPLv2 ≠ GPL-3.0 仍算分歧（对 GPL v3 项目方向相反时后果不同）。
    """
    if not s:
        return ""
    u = s.upper().replace(" ", "")
    for fam in ("LGPL", "GPL", "BSD", "MIT", "APACHE", "ZLIB", "UNLICENSE", "MPL", "ISC", "CC"):
        if fam in u:
            tail = u.split(fam, 1)[1]
            m = re.search(r"(\d)", tail)
            return fam + (m.group(1) if m else "")
    return re.sub(r"[^A-Z0-9.]", "", u)


# 表内本来就**没宣称** OSI 许可的写法。它们落在 NONE/NOASSERTION 上是「与原表一致」，
# 不能混进「写了 MIT 其实没 LICENSE」那类高风险分歧里。
NON_OSI_CLAIMED = {
    "", "无", "无 LICENSE", "无LICENSE", "无许可", "未授权", "待核实", "待验证",
    "PolyForm NC", "PICO-8 License", "CC BY-NC-SA", "VVVVVV License",
}


def lic_class(claimed, actual):
    """三档：AGREE / SOFT（族与主版本一致，只是写法不同）/ 硬冲突。
    硬冲突再细分两型，因为后果完全不同：
      - CLAIM_OK_ACTUAL_NONE：表内写了 OSI 许可，仓库里根本没有 LICENSE 文件 ⇒ 最危险
      - CLAIM_OK_ACTUAL_UNDET：API 判不了（自定义/混合许可）⇒ 必须人工读 LICENSE
    """
    c, a = canon_lic(claimed), canon_lic(actual or "")
    if not actual or a == "UNKNOWN":
        return "SKIP"
    if claimed.strip() in NON_OSI_CLAIMED:
        return "AGREE"
    if actual in ("NONE",):
        return "CLAIM_OK_ACTUAL_NONE" if c else "AGREE"
    if actual in ("NOASSERTION", "DMCA_BLOCKED"):
        return "CLAIM_OK_ACTUAL_UNDET" if c and c not in ("NOASSERTION",) else "AGREE"
    if not c:
        return "SOFT"
    if c == a:
        return "AGREE"
    if c.rstrip("0123456789") == a.rstrip("0123456789") and (not c[-1].isdigit() or not a[-1].isdigit()):
        return "SOFT"  # 同族但一方没写版本号
    return "HARD"


def verdict(name, res):
    """对 E53 硬约束的粗筛结论（只按可机检的 C4/C8 信号给，人工项标「需读源」）。"""
    if res.get("http") == 0:
        return "❓ 未定位到仓库，本轮未探测"
    if res["exists"] is None:
        return "❓ 状态未知（限流/网络），不得当作失效"
    if not res["exists"]:
        return "❌ 路径不存在"
    lic = res["license"]
    if lic in (None, "NONE"):
        return "❌ 无 LICENSE ⇒ all-rights-reserved，C4 不合规"
    if lic in ("NOASSERTION", "UNKNOWN"):
        return "⚠️ 许可证无法自动判定，必须人工读 LICENSE"
    if lic not in OSI_OK:
        return "❌ 非 OSI 许可（%s），C4 不合规" % lic
    return "✅ 许可合规（%s）" % lic


# ⚠️⚠️ 这一份是**人工裁决**，不是测量值。
# 机检只答得出「LICENSE 文件里写了什么」，答不出「作者是否禁止再分发」「素材是不是别人家的 IP」。
# 因此每条都必须写明判据出处（正文章节 / 原表备注 / README 原文），便于复核时回溯。
# 键一律**小写仓库路径**，与改名后的实际路径对齐。
HARD_EXCLUDE = {
    "killedbyapixel/spacehuggers": ("❌ 不做", "README 写明 “not intended to be redistributed”，且表内 MIT 实为 GPL-3.0 —— 正文 §2.1 已排除"),
    "ulebule/sgt-ruka": ("❌ 不做", "无 LICENSE 文件（表内 MIT 判错）—— 正文 §2.1 已排除；此处登记是为了让它进「不做」清单而不只靠许可桶"),
    "drsamonline/eon-commando": ("❌ 不做", "PolyForm Noncommercial 1.0.0 —— 禁商用，非 OSI 认可；正文 §2.1 已排除"),
    "alfredo1995/metal-slug": ("❌ 不做", "SNK《Metal Slug》粉丝重制：IP 不在任何代码许可范围内 + 无 LICENSE；正文 §2.1 已排除"),
    "xingyuliu586-tech/steel-breakout-frontline": ("❌ 不做", "页面明确「未授予全项目开源许可」；正文 §2.1 已排除"),
    "ranjeetj06/zora": ("❌ 不做", "无 LICENSE 文件；正文 §2.1 已排除"),
    "bunezt/flex-commando": ("❌ 不做", "无 LICENSE 文件；正文 §2.1 已排除"),
    "aimanh2250-lab/hivefall": ("❌ 不做", "无 LICENSE 文件；正文 §2.1 已排除"),
    "terrycavanagh/vvvvvv": ("❌ 不做", "VVVVVV 自定义源码公开许可：可读可学习，禁止商业使用与衍生再分发 —— 非 OSI（C4）"),
    "avtne/phase-reveal": ("❌ 不做", "CC BY-NC-SA（原表已记）：非商业 + 相同方式共享，非 OSI 认可（C4）"),
    "david-campos/contra": ("❌ 不做", "复刻 Konami《Contra》：角色/关卡 IP 不归代码许可覆盖，且仓库无 LICENSE（C4 + C8 双重不成立）"),
    "jmechner/prince-of-persia-apple-ii": ("❌ 不做", "1989 原版 Apple II 源码快照：代码放出 ≠ Ubisoft 放弃 IP；许可本身也是 NOASSERTION（C4 + C8）"),
    "jakesgordon/javascript-racer": ("❌ 不做", "原表自带注记「MIT(素材侵权)」：赛车/赛道图形不在 MIT 授予范围内（C8）"),
    "8bitworkshop/neslib": ("❌ 不做", "是 NES 开发库/框架而非可运行的游戏本体（正文只作技术参考，不进移植池）"),
    "3ds-brew/open-sonic-3ds": ("🔶 判定前不做", "《Sonic》角色与关卡是 SEGA IP：引擎代码 GPL-2.0，但素材来源需人工确认（C8）"),
}

# ⚠️ 第二份人工裁决登记表，但它和 HARD_EXCLUDE **答的不是同一个问题**：
#   HARD_EXCLUDE 答「授权成不成立」（无 LICENSE / 非 OSI / 他人 IP / 作者不许再分发）；
#   LOOK_EXCLUDE  答「好不好看」—— 下面 38 条的许可与存在性本轮全部复验**通过**（当时都是 ✅ 可做），
#   所有者 2026-11-04 逐条看过实机图后裁决观感不达标，移出移植池。
#   ⇒ 归堆必须单开一节（附录 C.3），⛔ 不许合进授权堆，否则读的人会以为这些仓库许可有问题。
# 键一律小写仓库路径；值是**附加说明**（可空），公共判语见 LOOK_RULING。
LOOK_RULING = "所有者 2026-11-04 逐条看过实机图后裁决「观感不达标」⇒ 不进移植池" \
              "（⚠️ 许可与存在性均已复验通过，这条不是授权问题）"
LOOK_EXCLUDE = {
    "deathraygames/404-js13k-2020": "",
    "js13kgames/13th-floor": "；另记：截图观感像恐怖游戏，题材与合集不合（所有者原话）",
    "foxfirecodes/asteroids": "",
    "jsocol/asteroids": "",
    "baixfeng/battle-city-remake": "",
    "danmat/blastix": "",
    "emredogann/clownpanic": "",
    "shoemoney/commander-in-chief": "",
    "doukutsu-rs/doukutsu-rs": "",
    "yujqiao/dungeonrush": "",
    "lpgamedevs/editarrrpublic": "",
    "akrck02/enjoined": "",
    "jrob774/fish-blaster": "",
    "madmartigan1/freedom-force": "",
    "physics-simulations/game-of-life": "",
    "godotengine/godot-demo-projects": "",
    "gdquest-demos/godot-platformer-2d": "",
    "tvanas/html5-games": "",
    "patorjk/javascript-snake": "",
    "jakesgordon/javascript-tetris": "",
    "rrafols/js1024_sakura": "",
    "theajack/life-game": "",
    "andykswong/lorz": "",
    "wohlsoft/moondust-project": "",
    "tsoding/nothing": "",
    "novasquirrel/novathesquirrel": "",
    "dcurrent/openbor": "",
    "opentomb/opentomb": "",
    "xredan/pong-godot": "；⚠️ 所有者注记为「404」，但 2026-11-03 认证 API 实测该仓库**存在**"
                         "（HTTP 200，README 有实测通过的实机图）⇒ 本条按观感裁决登记，⛔ 不写成失效",
    "retux-game/retux": "",
    "cleytonrs/snake": "",
    "hackclub/sprig": "",
    "supertux/supertux": "",
    "mehmetmhy/tetris": "",
    "harmonyhoney/tiny_crate": "",
    "jakesgordon/javascript-tiny-platformer": "",
    "secretchronicles/tsc": "",
    "casmo/tower-defense": "",
}


# 本项目是 GPL-3.0（AGENTS.md）。GPL-2.0 **-only** 与 GPL-3.0 不相容，
# 所以它不是「合规」而是「要看上游到底写的 -only 还是 +」。
COPYLEFT_OK = ("GPL-3.0", "LGPL-3.0", "LGPL-2.1")


def e53_verdict(r):
    """E53 口径的逐条判定 —— 返回 (判定, 原因)。

    判定只有三档，附录 A.1 / A.2 / 附录 C 共用这一份定义：
      ✅ 可做   许可机检成立，可进移植池
      🔶 判定前不做 机检不足以下结论（自定义许可 / copyleft 相容性 / 状态未知），本轮一律按不做处理
      ❌ 不做   证据已经足够否定它（无 LICENSE / 非 OSI / 人工裁决 / 404）

    ⚠️ 「不做」的成因必须是**实测或已登记的人工裁决**，⛔ 不许在这里临场发明理由。
    人工裁决有两张表：`HARD_EXCLUDE`（授权：IP / 不许再分发 / 不是游戏）与
    `LOOK_EXCLUDE`（观感：所有者 2026-11-04 看过实机图后裁掉，许可本身成立）。
    """
    path = ((r.get("renamed_to") or r.get("path") or "") or "").lower()
    if path in HARD_EXCLUDE:
        return HARD_EXCLUDE[path]
    if path in LOOK_EXCLUDE:
        return ("❌ 不做", LOOK_RULING + LOOK_EXCLUDE[path])
    if r.get("http") == 0:
        return ("❌ 不做", "未定位到仓库 ⇒ 无法取证，不作为任何结论的证据")
    if r["exists"] is None:
        return ("🔶 判定前不做", "本轮状态未知（限流/网络），不得当失效；重跑或人工读页面后再定")
    if r["exists"] is False:
        return ("❌ 不做", "仓库已不存在（HTTP %s），无源码可取" % r.get("http"))
    lic = r["license"]
    if lic in (None, "NONE"):
        extra = "（表内却写了 %s —— 该列不可信）" % r["claimed_lic"] if canon_lic(r["claimed_lic"]) else ""
        return ("❌ 不做", "仓库里没有 LICENSE 文件 ⇒ 默认 all-rights-reserved（C4）" + extra)
    if lic in ("NOASSERTION", "UNKNOWN", "DMCA_BLOCKED"):
        return ("🔶 判定前不做", "API 判不出许可（自定义/混合）⇒ 人工读 LICENSE 前不做（C4）")
    if lic not in OSI_OK:
        return ("❌ 不做", "非 OSI 许可（%s），C4 不合规" % lic)
    if lic == "GPL-2.0":
        return ("🔶 判定前不做", "GPL-2.0 若为 -only 则与本项目 GPL-3.0 不相容，须读 LICENSE 确认版本表达式")
    if lic in COPYLEFT_OK:
        return ("✅ 可做", "%s copyleft 坐实：衍生代码须同许可发布 ⇒ 只进私有分支，公开构建不含（C4 附加条件）" % lic)
    return ("✅ 可做", "%s 许可坐实" % lic)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--group", choices=["A", "B", "all"], default="all")
    ap.add_argument("--no-api", action="store_true", help="只重排上次快照")
    args = ap.parse_args()

    outdir = "logs_temp"
    os.makedirs(outdir, exist_ok=True)
    snap = os.path.join(outdir, "e53_upstream_verify.json")

    if args.no_api:
        with open(snap, encoding="utf-8") as f:
            data = json.load(f)
        rows = data["rows"]
    else:
        token = get_token()
        print("token: %s" % ("有（认证，限额 5000/h）" if token else "无（匿名，限额 60/h —— 会大面积 UNKNOWN）"))

        # ---- 自检：known-positive control。它读不出来 ⇒ 整轮结论作废
        ctl = probe("jakesgordon/javascript-tetris", token)
        if not ctl["exists"] or ctl["license"] != "MIT":
            print("自检失败：已知存在且 MIT 的 jakesgordon/javascript-tetris 读成 %s / %s"
                  % (ctl["http"], ctl["license"]))
            print("⇒ 本轮的 404 全部不可信（token 或解析坏了），中止。")
            sys.exit(2)
        print("自检通过：javascript-tetris = 200 / MIT\n")

        groups = {"A": GROUP_A, "B": GROUP_B, "all": GROUP_A + GROUP_B}[args.group]
        rows = []
        for name, path, claimed, tag, alts in groups:
            if not path:  # 未定位到仓库 —— 绝不用猜测路径去打 API
                rows.append({"name": name, "path": "(未定位)", "claimed_lic": claimed.strip(),
                             "tag": tag, "alts": [], "http": 0, "exists": None,
                             "license": None, "stars": None, "pushed": "", "archived": None,
                             "desc": "未定位到仓库路径，本轮不探测", "renamed_to": None})
                continue
            res = probe(path, token)
            if not res["exists"] and res["exists"] is not None and res["http"] == 404:
                for a in alts:
                    alt = probe(a, token)
                    if alt["exists"]:
                        res["alt_hit"] = alt
                        break
            res.update({"name": name, "claimed_lic": claimed.strip(), "tag": tag, "alts": alts})
            rows.append(res)
            print("%-28s %-42s http=%-4s lic=%-12s %s"
                  % (name, res.get("renamed_to") or path, res["http"], res["license"],
                     "⭐备选命中 " + res["alt_hit"]["path"] if "alt_hit" in res else ""))

        with open(snap, "w", encoding="utf-8") as f:
            json.dump({"generated_at": datetime.now(timezone.utc).isoformat(),
                       "authed": bool(token), "rows": rows}, f, ensure_ascii=False, indent=1)

    # ---------------------------------------------------------------- 汇总
    exists = [r for r in rows if r["exists"]]
    gone = [r for r in rows if r["exists"] is False]
    unknown = [r for r in rows if r["exists"] is None and r.get("http") != 0]
    renamed = [r for r in rows if r.get("renamed_to") and r["renamed_to"] != r["path"]]
    found_by_alt = [r for r in rows if "alt_hit" in r]
    cls = {}
    for r in rows:
        cls[r["name"]] = lic_class(r["claimed_lic"], r["license"]) if r["exists"] else "SKIP"
    hard = [r for r in rows if cls[r["name"]] == "HARD"]
    claim_none = [r for r in rows if cls[r["name"]] == "CLAIM_OK_ACTUAL_NONE"]
    claim_undet = [r for r in rows if cls[r["name"]] == "CLAIM_OK_ACTUAL_UNDET"]
    undetectable = [r for r in rows if r["exists"] and r["license"] in ("NONE", "NOASSERTION")]

    lines = []
    w = lines.append
    w("# E53 上游候选池复验（脚本再生，勿手抄）\n")
    w("生成时间：%s ｜ 认证：%s\n"
      % (json.load(open(snap, encoding="utf-8"))["generated_at"],
         "是" if json.load(open(snap, encoding="utf-8"))["authed"] else "否"))
    w("## 统计\n")
    w("| 指标 | 数量 |")
    w("|---|---|")
    w("| 合计 | %d |" % len(rows))
    w("| 存在（200/301） | %d |" % len(exists))
    w("| 404 不存在 | %d |" % len(gone))
    w("| 状态未知（403/网络，⛔ 不算失效） | %d |" % len(unknown))
    w("| 命中重定向（改名/转移） | %d |" % len(renamed))
    w("| 备选路径救回（表内写错而非删库） | %d |" % len(found_by_alt))
    w("| **许可硬冲突**（族/主版本不同，如 MIT↔GPL-3.0） | %d |" % len(hard))
    w("| **表内写了 OSI 许可、仓库里却没有 LICENSE 文件** | %d |" % len(claim_none))
    w("| 表内写了许可、API 判不出来（自定义/混合，须人工读） | %d |" % len(claim_undet))
    w("| 存在但许可证无法自动判定（NONE+NOASSERTION） | %d |" % len(undetectable))
    w("\n## 逐条\n")
    w("| 名称 | 路径 | HTTP | 实际许可 | 表内许可 | stars | 最近推送 | archived | 对 E53 的判定 |")
    w("|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        path = r.get("renamed_to") or r["path"]
        if "alt_hit" in r:
            path += "（备选 " + r["alt_hit"]["path"] + "）"
        w("| %s | %s | %s | %s | %s | %s | %s | %s | %s |"
          % (r["name"], path, r["http"], r["license"], r["claimed_lic"], r["stars"],
             r["pushed"], r["archived"], verdict(r["name"], r)))
    w("\n## 许可硬冲突（族或主版本不符 —— 表内那一列不可信）\n")
    for r in hard:
        w("- %s：API 读到 **%s**，表内写 %s" % (r["name"], r["license"], r["claimed_lic"]))
    w("\n## ⛔ 表内写了 OSI 许可，但仓库里没有 LICENSE 文件（最高风险：写了 MIT 实为 all-rights-reserved）\n")
    for r in claim_none:
        w("- %s（%s）：表内写 %s，API = NONE" % (r["name"], r["path"], r["claimed_lic"]))
    w("\n## 🔶 表内写了许可，API 判不出来（自定义/混合许可，须人工读 LICENSE）\n")
    for r in claim_undet:
        w("- %s（%s）：表内写 %s，API = %s" % (r["name"], r["path"], r["claimed_lic"], r["license"]))
    w("\n## 许可证无法自动判定（全部：NONE + NOASSERTION）\n")
    for r in undetectable:
        w("- %s（%s）" % (r["name"], r["path"]))
    w("\n## 备选路径救回（原表「404 失效」其实是路径写错）\n")
    for r in found_by_alt:
        w("- %s：%s 404 → %s 存在（%s，%s stars）"
          % (r["name"], r["path"], r["alt_hit"]["path"], r["alt_hit"]["license"], r["alt_hit"]["stars"]))
    w("\n## 命中重定向\n")
    for r in renamed:
        w("- %s：%s → %s" % (r["name"], r["path"], r["renamed_to"]))

    text = "\n".join(lines) + "\n"
    mdpath = os.path.join(outdir, "e53_upstream_verify.md")
    with open(mdpath, "w", encoding="utf-8") as f:
        f.write(text)
    print("\n" + text)
    print("[written] %s\n[written] %s" % (mdpath, snap))


if __name__ == "__main__":
    main()
