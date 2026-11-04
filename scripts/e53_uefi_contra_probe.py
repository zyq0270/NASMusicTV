# -*- coding: utf-8 -*-
r"""池外候选逐条取证：MikeWuPing/UEFI_Contra（所有者 2026-11-04 指定复看）。

为什么单开一个脚本
------------------
附录 A 那 122 条（`e53_upstream_verify.py` 的 GROUP_A/GROUP_B）是**两份原表的池子**，
往里塞一条「所有者后来另外指定的仓库」会把正文里十几处「84 条大池 / 122 条」的计数全部弄脏，
还会让 A.1/A.2 的行数不再等于原表行数。所以本仓库**不进 GROUP_A/B**，单独由本脚本取证，
结论落在**附录 C.12**。⛔ 本文档正文不许点它的名字 —— 名字进禁词表的机制见
`e53_body_clean_check.OUT_OF_POOL_NOGO`（它从本脚本的 `REPO` + `VERDICT` 反推，不另立名单）。

取证的三层数字（全部由本脚本再生，⛔ 不许手抄进文档）
------------------------------------------------
① 许可与存在性：`/repos` 的 `license`、`/license` 的状态码（404 ⇒ 仓库里没有 LICENSE 文件，
   哪怕 README 自称 MIT —— 这正是附录 A.5 的「假 MIT」形态）。
② 体量与素材：`/git/trees?recursive=1` 的逐 blob `size`（并断言 `truncated` 为假，
   否则「全仓字节」这个结论不成立）+ 逐文件源码字节/行数 + **include 引用图**（从未被
   include 的头文件 = 死文件，白占仓库）。
③ 移植可行性：UEFI/EDK2 符号的**逐文件命中数**（哪些文件是纯逻辑、哪些是真耦合）、
   浮点/堆分配命中、attract-mode（C8 自动玩）状态机的实测常量。

⚠️ 网络：本机 `git clone` / `raw.githubusercontent.com` 会被连接重置（实测），
   所以源码字节一律走**认证 contents API** 的 `content` 字段（base64）；
   `--offline` 复用上次落盘的镜像，只重算度量。

用法：
    python scripts/e53_uefi_contra_probe.py            # 打 API + 下载镜像 + 度量
    python scripts/e53_uefi_contra_probe.py --offline  # 只重算度量（镜像已在 logs_temp 下）
    python scripts/e53_uefi_contra_probe.py --dir logs_temp/uefi_contra_probe
"""

import argparse
import base64
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from e53_upstream_verify import api_get, get_token  # noqa: E402

REPO = "MikeWuPing/UEFI_Contra"
BRANCH = "main"
MIRROR = "logs_temp/uefi_contra_probe"

# 判定口径与 `e53_upstream_verify.e53_verdict` 的三档一致；本条是本脚本的**结论**，
# 归堆理由同时写进 HARD_EXCLUDE 语义的附录 C.12。⚠️ 改这里 = 改正文禁词表。
VERDICT = ("❌ 不做",
           "① 无 LICENSE 文件（README 自称 MIT ⇒ 附录 A.5 的假 MIT 形态，C4 不成立）；"
           "②《Contra》是 Konami 商业 IP，README 自认素材取自 NES ROM、上游 nes-contra-us 亦无许可"
           "（§8.7 第⑤道）；③ 精灵资源运行期从 FAT 磁盘读 .spr，仓库内 .py/.spr/PNG 全为 0 ⇒ 克隆不可运行")

# 素材头（ROM 提取的图形/关卡数据）——按 README 与文件头注释认定，逐条在本脚本里打印证据行
ASSET_HDRS = {"tiles_c.h", "tiles_full.h", "level_data.h", "intro_tiles.h",
              "intro_nametable.h", "intro_screen.h"}
# 真·UEFI/EDK2 耦合符号（⛔ 不含 UINT8/INT32 这类可机械替换的 typedef）
UEFI_SYM = (r"EFI_[A-Za-z]+|EfiMain|gBS|gRT|gST|BS->|RT->|ST->|"
            r"SimpleFileSystem|SimpleText[A-Za-z]*|GraphicsOutput|AllocatePool|"
            r"AllocateZeroPool|FreePool|UnicodeSPrint|DEBUG\(|CopyMem|ZeroMem")
# 上表里凡是命中这一条的，都只是 **EDK2 的 C 标准库替身**（ZeroMem/CopyMem = memset/memcpy，
# DEBUG() = 一行 printf 包装）—— Kotlin 侧一个函数就换掉，不构成移植障碍。
# ⚠️ 这个拆分是本脚本移植性结论的关键：不拆的话「player.c 命中 1」会被读成「player.c 绑 UEFI」。
TRIVIAL_SYM = r"CopyMem|ZeroMem|DEBUG\("


def fetch(path, token):
    st, body, _ = api_get("https://api.github.com/repos/%s/contents/%s?ref=%s"
                          % (REPO, path, BRANCH), token)
    if st != 200 or not body.get("content"):
        raise RuntimeError("contents API %s -> HTTP %s" % (path, st))
    return base64.b64decode(body["content"])


def download(token, mirror):
    st, tree, _ = api_get("https://api.github.com/repos/%s/git/trees/%s?recursive=1"
                          % (REPO, BRANCH), token)
    if st != 200:
        raise RuntimeError("tree API -> HTTP %s" % st)
    if tree.get("truncated"):
        print("⛔ tree 被截断 ⇒ 「全仓字节 / 文件清单」不成立，本轮作废（exit 2）")
        sys.exit(2)
    blobs = [t for t in tree["tree"] if t["type"] == "blob"]
    os.makedirs(mirror, exist_ok=True)
    for t in blobs:
        fp = os.path.join(mirror, *t["path"].split("/"))
        os.makedirs(os.path.dirname(fp), exist_ok=True) if os.path.dirname(fp) else None
        raw = fetch(t["path"], token)
        if len(raw) != t["size"]:
            print("⛔ %s 取回 %d B ≠ tree 声明 %d B ⇒ 镜像不完整" % (t["path"], len(raw), t["size"]))
            sys.exit(2)
        with open(fp, "wb") as f:
            f.write(raw)
    print("镜像 %d 个 blob / %d B（逐文件字节与 tree 声明全部一致 ✅）"
          % (len(blobs), sum(t["size"] for t in blobs)))
    return blobs


def license_check(token):
    st, body, _ = api_get("https://api.github.com/repos/" + REPO, token)
    lic = (body.get("license") or {}) if st == 200 else {}
    st2, body2, _ = api_get("https://api.github.com/repos/%s/license" % REPO, token)
    print("/repos.license = %r ｜ /license HTTP=%s (%s)"
          % (lic.get("spdx_id") or None, st2, (body2 or {}).get("message", "")))
    if st == 200:
        print("仓库元数据：stars=%s ｜ created=%s ｜ pushed=%s ｜ fork=%s ｜ archived=%s"
              % (body.get("stargazers_count"), (body.get("created_at") or "")[:10],
                 (body.get("pushed_at") or "")[:10], body.get("fork"), body.get("archived")))
    st3, langs, _ = api_get("https://api.github.com/repos/%s/languages" % REPO, token)
    print("/languages = %s (HTTP %s)" % (json.dumps(langs) if st3 == 200 else "?", st3))
    # 上游引用（README 自认的反汇编来源）也做一次许可取证
    for up in ("vermiceli/nes-contra-us",):
        st4, b4, _ = api_get("https://api.github.com/repos/" + up, token)
        st5, _, _ = api_get("https://api.github.com/repos/%s/license" % up, token)
        print("上游 %s：license=%r ｜ /license HTTP=%s ｜ desc=%r"
              % (up, ((b4 or {}).get("license") or {}).get("spdx_id") or None, st5,
                 (b4 or {}).get("description")))


def measure(mirror):
    src = os.path.join(mirror, "src")
    files = {}
    for root, _, fs in os.walk(mirror):
        for f in fs:
            fp = os.path.join(root, f)
            files[os.path.relpath(fp, mirror).replace(os.sep, "/")] = fp
    txt = {p: open(fp, encoding="utf-8-sig", errors="replace").read().replace("\r\n", "\n")
           for p, fp in files.items()}
    size = {p: os.path.getsize(files[p]) for p in files}
    lines = {p: txt[p].count("\n") + 1 for p in txt}
    sp = "src/"
    hdrs = sorted(p for p in files if p.endswith(".h"))
    cs = sorted(p for p in files if p.endswith(".c"))

    included = set()
    for p, t in txt.items():
        for m in re.findall(r'#\s*include\s*"([^"]+\.h)"', t):
            included.add(sp + m)

    print("\n=== ① 全仓清单 ===")
    print("文件 %d 个 / %d B ｜ src/ 下 %d 个 / %d B"
          % (len(files), sum(size.values()),
             len([p for p in files if p.startswith(sp)]),
             sum(size[p] for p in files if p.startswith(sp))))

    print("\n=== ② 头文件 include 引用图（⛔ 从未被 include = 死文件）===")
    dead = []
    for p in hdrs:
        hit = p in included
        if not hit:
            dead.append(p)
        print("%-22s %8d B %5d 行  %s" % (p[len(sp):], size[p], lines[p],
                                          "被引" if hit else "⛔ 从未被 include"))
    db = sum(size[p] for p in dead)
    tot = sum(size.values())
    print("死头文件 %d 个 / %d B（占全仓 %.1f%%）" % (len(dead), db, 100.0 * db / tot))
    aset = [p for p in hdrs if p[len(sp):] in ASSET_HDRS]
    adead = [p for p in dead if p[len(sp):] in ASSET_HDRS]
    print("素材头 %d 个 / %d B / %d 行 ｜ 其中死文件 %d 个 / %d B / %d 行"
          % (len(aset), sum(size[p] for p in aset), sum(lines[p] for p in aset),
             len(adead), sum(size[p] for p in adead), sum(lines[p] for p in adead)))
    logic = [p for p in files if p[len(sp):] not in ASSET_HDRS and not p.endswith(".inf")
             and p.startswith(sp)]
    print("非素材源码 %d 个 / %d B / %d 行" % (len(logic), sum(size[p] for p in logic),
                                        sum(lines[p] for p in logic)))

    print("\n=== ②b 素材头的自述出处（证明是 ROM 数据，不是自绘）===")
    for p in aset:
        print("  %-18s %s" % (p[len(sp):], txt[p].split("\n")[0].strip()))

    print("\n=== ③ UEFI/EDK2 符号逐文件命中（真协议耦合 vs 纯逻辑）===")
    pure, coupled, trivial_only = [], [], []
    for p in cs + [sp + "types.h"]:
        if p not in txt:
            continue
        hits = len(re.findall(UEFI_SYM, txt[p]))
        hard = hits - len(re.findall(TRIVIAL_SYM, txt[p]))
        bucket = (pure if hard == 0 else coupled)
        bucket.append((p[len(sp):], hits, hard))
        print("  %-20s 全符号 %3d ｜ 去掉 memset/memcpy/DEBUG 后 %3d" % (p[len(sp):], hits, hard))
    trivial_only = [x for x in pure if x[1] > 0]
    print("逻辑侧（真协议/服务表命中 = 0，仅剩 memset/memcpy 级调用）%d 个：%s"
          % (len(pure), ", ".join("%s(%d)" % (n, h) for n, h, _ in pure)))
    print("真耦合 %d 个：%s"
          % (len(coupled), ", ".join("%s(%d)" % (n, hd) for n, _, hd in
                                     sorted(coupled, key=lambda y: -y[2]))))
    zf = [(p[len(sp):], len(re.findall(r"\b(?:float|double)\b", txt[p]))) for p in cs]
    print("浮点命中：%s ｜ 堆分配（AllocatePool/FreePool）命中文件：%s"
          % ([x for x in zf if x[1]],
             [p[len(sp):] for p in cs if re.search(r"Alloc\w*Pool|FreePool", txt[p])]))
    print("8.8 定点证据：player.c `>> 8`/`<< 8` 命中 %d 处"
          % len(re.findall(r"(?:>>|<<) 8", txt[sp + "player.c"])))

    print("\n=== ④ attract mode（C8 自动玩）实测 ===")
    gs = txt[sp + "game_state.c"]
    for pat in (r"gDemoMode\s*=\s*\d", r"gIntroTimer > \d+", r"gGameRoutineIndex = \d+"):
        for m in re.finditer(pat, gs):
            ln = gs[:m.start()].count("\n") + 1
            print("  L%-4d %s" % (ln, gs.split("\n")[ln - 1].strip()))
    ph = re.findall(r"if \(phase < (\d+)\)", gs)
    print("  脚本相位分段：%s（phase = gIntroTimer %% %s）⇒ 无录像数据、无 AI，按相位固定「向右走 + 周期跳/射」"
          % (" / ".join(ph), re.findall(r"gIntroTimer % (\d+)", gs)[0]))
    print("  main.c 的 DEMO 标记：%s"
          % [l.strip() for l in txt[sp + "main.c"].split("\n") if "DEMO" in l][:2])

    print("\n=== ⑤ 素材管线缺失（README 声称的工具与资源在不在仓库里）===")
    for k, cond in (("tools/", lambda p: "/tools/" in p or p.startswith("tools/")),
                    (".py", lambda p: p.endswith(".py")),
                    (".spr", lambda p: p.endswith(".spr")),
                    (".png", lambda p: p.endswith(".png")),
                    ("sprites/", lambda p: "/sprites/" in p)):
        print("  %-9s 命中 %d 个" % (k, len([p for p in files if cond(p)])))
    print("  sprites.c 运行期读的外部文件：%s"
          % " ｜ ".join(sorted(set(re.findall(r'L"(sprites\\\\[^"]+\.spr)"',
                                              txt[sp + "sprites.c"])))).replace("\\\\", "\\"))
    print("  .inf 的 Sources 段声明 %d 个文件；src/ 实有 %d 个"
          % (len(set(re.findall(r"^\s{2}(\S+\.(?:c|h))\s*$", txt[sp + "ContraGame.inf"], re.M))),
             len([p for p in files if p.startswith(sp)])))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--offline", action="store_true", help="复用已落盘镜像，只重算度量")
    ap.add_argument("--dir", default=MIRROR)
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    token = get_token()
    print("token: %s" % ("有（认证）" if token else "无（匿名，contents API 会踩限额）"))
    print("仓库：%s ｜ 判定：%s —— %s" % (REPO, VERDICT[0], VERDICT[1]))
    if not os.path.isdir(args.dir):
        args.offline = False
    if args.offline:
        print("（--offline：跳过 API，直接度量 %s）" % args.dir)
    else:
        if not token:
            print("⛔ 无令牌且需要下载镜像：contents API 匿名限额 60/h，且本机 git clone 被重置。中止。")
            return 2
        license_check(token)
        download(token, args.dir)
    measure(args.dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
