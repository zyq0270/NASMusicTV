# -*- coding: utf-8 -*-
"""修正执行摘要中两处统计口径错误（只改生成器文本，不动数据）。"""
import io

P = "logs_temp/gen_review_report_2026-11-09.py"
s = io.open(P, encoding="utf-8").read()
orig = s

# 1) 核心结论：30 条含 2 条撤销，需写成 28 下调 + 2 撤销 = 30 变化
old1 = "按严重度变化计，52 条复核项共有 <b>30 条</b>被下调、2 条撤销，无一条上调。"
new1 = "按严重度变化计，52 条复核项中有 <b>30 条</b>严重度发生变化（28 条下调、2 条撤销），无一条上调。"
assert s.count(old1) == 1, s.count(old1)
s = s.replace(old1, new1)

# 2) 复核项分布误用了全库 final_count，改为只用 52 条复核项的分布
old2 = ("parts.append('<p class=\"small muted\">52 条复核项复核后：Critical %d / High %d / Medium %d / Low %d / 撤销 %d。"
        "其余 89 条（Medium 55、Low 34）维持初稿判定，报告中标为「初稿」且未经独立复核。"
        "连同复核阶段新增的 15 条（High 1 / Medium 11 / Low 3），全库有效发现合计 <b>154 条</b>："
        "Critical 1 / High 13 / Medium 94 / Low 46。</p>' % (final_count[\"Critical\"], final_count[\"High\"], "
        "final_count[\"Medium\"], final_count[\"Low\"], final_count[\"撤销\"]))")
new2 = ("parts.append('<p class=\"small muted\">52 条复核项复核后：Critical %d / High %d / Medium %d / Low %d / 撤销 %d。"
        "其余 89 条（Medium 55、Low 34）维持初稿判定，报告中标为「初稿」且未经独立复核。"
        "全库 141 条复核后终稿分布为 Critical %d / High %d / Medium %d / Low %d / 撤销 %d；"
        "连同复核阶段新增的 15 条（High 1 / Medium 11 / Low 3），全库有效发现合计 <b>154 条</b>："
        "Critical 1 / High 13 / Medium 94 / Low 46。</p>' % (rv_count[\"Critical\"], rv_count[\"High\"], rv_count[\"Medium\"], "
        "rv_count[\"Low\"], rv_count[\"撤销\"], final_count[\"Critical\"], final_count[\"High\"], final_count[\"Medium\"], "
        "final_count[\"Low\"], final_count[\"撤销\"]))")
assert s.count(old2) == 1, s.count(old2)
s = s.replace(old2, new2)

# 3) 在 final_count 之后补一个 rv_count（仅统计已复核项）
anchor = "verdict_count = {}"
assert s.count(anchor) == 1, s.count(anchor)
s = s.replace(anchor, (
    "rv_count = {\"Critical\": 0, \"High\": 0, \"Medium\": 0, \"Low\": 0, \"撤销\": 0}\n"
    "for f in findings:\n"
    "    if f[4] != \"初稿\":\n"
    "        rv_count[f[3]] = rv_count.get(f[3], 0) + 1\n"
    + anchor
))

assert s != orig
io.open(P, "w", encoding="utf-8", newline="\n").write(s)
print("patched ok; rv_count present:", "rv_count = {}" in s, "| new1:", new1[:24])
