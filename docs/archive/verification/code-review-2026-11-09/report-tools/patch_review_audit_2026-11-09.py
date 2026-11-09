# -*- coding: utf-8 -*-
"""复审后的修正：1) 转义 sdkInt<26；2) 修正 P2 Compose 条目错列的 C36/C38；
3) 补「撤销」标签样式；4) 说明 C/M/L/N 编号规则；5) 澄清第 2 节不含新增项；
6) 声明路线图覆盖范围。"""
import io

P = "logs_temp/gen_review_report_2026-11-09.py"
s = io.open(P, encoding="utf-8").read()
before = s
applied = []


def rep(old, new, tag):
    global s
    assert s.count(old) == 1, "[%s] 命中 %d 次" % (tag, s.count(old))
    s = s.replace(old, new)
    applied.append(tag)


# 1) 未转义的 <26 被浏览器当成标签，整句被吞
rep("<b>C46 + N11 clipPath 降级与门禁</b>：sdkInt<26 把 IRIS_*/WIPE_* 降级为 CROSSFADE",
    "<b>C46 + N11 clipPath 降级与门禁</b>：sdkInt&lt;26 把 IRIS_*/WIPE_* 降级为 CROSSFADE",
    "A1 转义 sdkInt<26")

# 2) P2「Compose 重组下沉」误列 C36（下载态 finally）与 C38（网盘加队列，已在 P0）
rep("Compose 重组下沉：C26/C29/C30/C36/C38(组件) 的进度与滚动状态",
    "Compose 重组下沉：C26/C29/C30 的滚动与逐帧进度状态",
    "A2 移除错列条目")

# 2b) C36 改挂到 P3（它是 Low 级的失败态回写缺口，与 Compose 无关）
rep("lateinit 守卫（L19）、噪声/位图缓存（L27/L28/L31/L32）。",
    "lateinit 守卫（L19）、下载失败态未复位（C36）、噪声/位图缓存（L27/L28/L31/L32）。",
    "A2b C36 归位 P3")

# 3) 「撤销」严重度没有对应 CSS，渲染成无底色裸文本
rep(".v-draft{background:#eef3f9;color:#40618a}",
    ".v-draft{background:#eef3f9;color:#40618a}\n"
    ".sev-void{background:#f1f2f4;color:#6b7280;text-decoration:line-through}",
    "A3 撤销标签样式")

rep('    return \'<span class="pill sev-%s">%s</span>\' % (s.lower() if s in SEV_ORDER else "low", e(SEV_LABEL.get(s, s)))',
    '    cls = "void" if s == "撤销" else (s.lower() if s in SEV_ORDER else "low")\n'
    '    return \'<span class="pill sev-%s">%s</span>\' % (cls, e(SEV_LABEL.get(s, s)))',
    "A3b sev_pill 映射")

# 4) 编号规则无任何说明
rep('每个子系统列出全部发现（含复核后严重度、判定、位置、机制与建议）与该模块的强项。标为「初稿」的行未经独立复核。',
    '每个子系统列出全部发现（含复核后严重度、判定、位置、机制与建议）与该模块的强项。'
    '<br><b>编号规则</b>：<code>C</code> = 进入独立复核的高优先级候选（初稿 Critical / High 及 18 条关键 Medium，共 52 条）；'
    '<code>M</code> / <code>L</code> = 未进入复核的 Medium / Low（共 89 条，判定列标注「初稿」）；'
    '<code>N</code> = 复核阶段新增发现。编号在各自前缀内连续，但不代表优先级排序，实际严重度以「严重度」列（复核后）为准。',
    "A4 编号规则说明")

# 5) 第 2 节标题未说明不含第 4 节的 N01（High）
rep("2. 最高优先级修复（复核后 Critical / High，共 %d 条）",
    "2. 最高优先级修复（来自初稿的复核后 Critical / High，共 %d 条；复核新增的 1 条 High 见第 4 节）",
    "A5 标题澄清")

# 6) 路线图未声明覆盖范围
rep('<section id="roadmap"><h2>7. 分阶段整改路线</h2><div class="roadmap">',
    '<section id="roadmap"><h2>7. 分阶段整改路线</h2>'
    '<p class="muted small">覆盖范围：<b>全部 13 条复核后 Critical / High 均已立项</b>（另含新增的 N01），'
    'Medium 立项 47/83（按主题归并，其余 36 条见第 5 节明细，属同类批量修），Low 立项 19/43。'
    '每个条目括号内的编号可直接回第 5 节定位。</p><div class="roadmap">',
    "A6 覆盖范围声明")

assert s != before
io.open(P, "w", encoding="utf-8", newline="\n").write(s)
print("applied:", " | ".join(applied))
