# -*- coding: utf-8 -*-
"""N14：清理 MODE_CARD 死代码（SongRowModeCard + UnifiedSongGrid + 枚举值）。"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
P = os.path.join(ROOT, "app/src/main/java/com/nasmusic/tv/ui/components/song/UnifiedSongRow.kt")
GRID = os.path.join(ROOT, "app/src/main/java/com/nasmusic/tv/ui/components/song/UnifiedSongGrid.kt")


def read(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()


def write(p, s):
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)


s = read(P)
crlf = "\r\n" in s


def norm(x):
    return x.replace("\n", "\r\n") if crlf else x


def rep(s, a, b, expect=1):
    a, b = norm(a), norm(b)
    n = s.count(a)
    if n != expect:
        print("FAIL count=%d for %r" % (n, a[:80]))
        sys.exit(1)
    return s.replace(a, b)


# 1) 删枚举值 MODE_CARD
s = rep(
    s,
    "    /** 卡片：封面在上，文字在下，用于网格 */\n    MODE_CARD,\n",
    "",
)
s = rep(s, " * 支持三种布局模式，通过参数控制显示内容。", " * 支持两种布局模式（行 / 紧凑），通过参数控制显示内容。")

# 2) 删 when 分支
s = rep(
    s,
    "        SongRowMode.MODE_CARD -> SongRowModeCard(\n"
    "            song = song,\n"
    "            onClick = onClick,\n"
    "            downloadState = downloadState,\n"
    "            focusRequester = focusRequester\n"
    "        )\n",
    "",
)

# 3) 删 SongRowModeCard 整个声明（从 KDoc 到结束大括号）
start_marker = (
    "/**\n"
    " * MODE_CARD：卡片布局（封面在上，文字在下）\n"
    " * 与现有 HomeScreen.HomeSongCard 样式一致\n"
    " */\n"
)
end_marker = "/**\n * MODE_COMPACT：紧凑行（无封面，仅文字 + 序号）\n"
i = s.find(norm(start_marker) if crlf else start_marker)
if i < 0:
    print("FAIL: SongRowModeCard KDoc not found")
    sys.exit(1)
j = s.find(norm(end_marker) if crlf else end_marker, i)
if j < 0:
    print("FAIL: MODE_COMPACT KDoc not found")
    sys.exit(1)
s = s[:i] + s[j:]
write(P, s)
print("OK UnifiedSongRow.kt removed MODE_CARD/SongRowModeCard")

# 4) 删除死文件 UnifiedSongGrid.kt
if os.path.exists(GRID):
    os.remove(GRID)
    print("OK removed UnifiedSongGrid.kt")
else:
    print("SKIP UnifiedSongGrid.kt already gone")

# 5) 校验
s = read(P)
for bad in ("MODE_CARD", "SongRowModeCard"):
    if bad in s:
        print("FAIL residual", bad, s.count(bad))
        sys.exit(1)
print("VERIFY OK; remaining MODE_ refs:", s.count("SongRowMode.MODE_ROW"), s.count("SongRowMode.MODE_COMPACT"))
