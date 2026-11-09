# -*- coding: utf-8 -*-
"""校正生成脚本中的汇总口径，逐项 assert 确保落地。"""
import io, os

p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "gen_review_report_2026-11-09.py")
s = io.open(p, encoding="utf-8").read()

REPL = [
    # 1. 初稿统计卡
    ('[("Critical", 2), ("High", 32), ("Medium", 74), ("Low", 33)]',
     '[("Critical", 2), ("High", 32), ("Medium", 73), ("Low", 34)]'),
    # 2. 分布条
    ('width:52.5%;background:#d19a22"></i><i style="width:23.4%;background:#94a3b8"',
     'width:51.8%;background:#d19a22"></i><i style="width:24.1%;background:#94a3b8"'),
    # 3. legend
    ('</b>Medium 74</span><span><b style="background:#94a3b8"></b>Low 33</span>',
     '</b>Medium 73</span><span><b style="background:#94a3b8"></b>Low 34</span>'),
    # 4. 摘要核心结论
    ('初稿 141 条发现中，高优先级的 34 条经复核后仅 <b>13 条</b>仍保持 Critical/High，21 条因「兜底已存在、机制说错、影响被夸大」而降级，2 条被撤销。',
     '初稿 141 条发现中，34 条 Critical/High 经复核后仅 <b>13 条</b>仍处于该档（其中 1 条由 Critical 降为 High），21 条离开该档（18 → Medium、2 → Low、1 撤销）；按严重度变化计，52 条复核项共有 <b>30 条</b>被下调、2 条撤销，无一条上调。主要降级原因是「上层兜底已存在、机制描述说错、影响范围被夸大」。'),
    # 5. KPI 卡
    ('("REFUTED", verdict_count.get("REFUTED", 0)), ("降级", 21)]',
     '("REFUTED", verdict_count.get("REFUTED", 0)), ("离开 Crit/High", 21)]'),
    # 6. 未复核与全库合计
    ('其余 89 条（Medium 56、Low 33）维持初稿判定，报告中标为「初稿」且未经独立复核。连同复核阶段新增的 15 条，全库有效发现合计 <b>154 条</b>：Critical 1 / High 13 / Medium 95 / Low 45。',
     '其余 89 条（Medium 55、Low 34）维持初稿判定，报告中标为「初稿」且未经独立复核。连同复核阶段新增的 15 条（High 1 / Medium 11 / Low 3），全库有效发现合计 <b>154 条</b>：Critical 1 / High 13 / Medium 94 / Low 46。'),
    # 7. 3.2 标题
    ('<h3>3.2 严重度降级清单（21 条）</h3>',
     '<h3>3.2 严重度降级清单（30 条；其中 22 条来自原 Critical/High，含 1 条 Critical → High）</h3>'),
    # 8. Strengths 里的字面实体
    ('（含 \' → &#39;）', '（含单引号转义）'),
]

for old, new in REPL:
    cnt = s.count(old)
    assert cnt == 1, "替换项未唯一命中 (%d): %s" % (cnt, old[:70])
    s = s.replace(old, new, 1)

io.open(p, "w", encoding="utf-8").write(s)
print("patched", len(REPL), "items; bytes", os.path.getsize(p))
