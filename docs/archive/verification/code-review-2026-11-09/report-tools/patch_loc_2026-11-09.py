# -*- coding: utf-8 -*-
"""可读性修正：位置列不再按字符硬断，改为只在 / 与「扩展名:行号」处换行。"""
import io

P = "logs_temp/gen_review_report_2026-11-09.py"
s = io.open(P, encoding="utf-8").read()
before = s

old_css = 'td.loc{font-family:Consolas,"Courier New",monospace;font-size:12px;color:#33465e;word-break:break-all;min-width:190px}'
new_css = ('td.loc{font-family:Consolas,"Courier New",monospace;font-size:11.5px;color:#33465e;'
           'word-break:normal;overflow-wrap:break-word;min-width:186px}')
assert s.count(old_css) == 1
s = s.replace(old_css, new_css)

assert s.count("e(loc)") == 4, s.count("e(loc)")
s = s.replace("e(loc)", "loc_html(loc)")

helper = (
    "def loc_html(loc):\n"
    "    \"\"\"位置串：在 / 与「扩展名:行号」之间插零宽空格，避免被按字符硬断成 SongDownloa/dManager.kt。\"\"\"\n"
    "    t = e(loc).replace(\"/\", \"/&#8203;\")\n"
    "    return re.sub(r\"(\\.[A-Za-z0-9]+)(:\\d)\", r\"\\1&#8203;\\2\", t)\n"
    "\n"
    "\n"
)
assert s.count("def verdict_pill(v):") == 1
s = s.replace("def verdict_pill(v):", helper + "def verdict_pill(v):")

assert s.count("import html, os\n") == 1
s = s.replace("import html, os\n", "import html, os, re\n")

assert s != before
io.open(P, "w", encoding="utf-8", newline="\n").write(s)
print("ok: loc_html 已接入 4 处表格，并补充 import re")
