"""Kotlin 块注释**配对**检查（可复用门禁脚本）。

## 为什么需要它

⛔ **Kotlin 的块注释是「可嵌套」的**：注释正文里出现 `/*` 会被词法器当成
**嵌套注释的开始** ⇒ 外层那个 `*/` 只关掉内层 ⇒ 外层再也没被关上
⇒ `Syntax error: Unclosed comment`（编译器把该文件后面的内容全吞进去，
报错位置还常常指向「第 1 行」，**极难定位**）。

**真实事故（2026-11-06）**：在 `VisualizerRendererFactory.kt` 的注释里写了
`` `qianli/port/**` ``（本意是「port 目录下的全部」）⇒ `/**` 开了个内层注释
⇒ 整个 main 源集编译失败 ⇒ **同时**打断了 4 条并行 lane 的构建。

⇒ 本脚本模拟 Kotlin 的可嵌套语义（逐字符扫描 + 深度计数），把问题在**编译前**抓出来。

## 用法（从**仓库根**跑）

    python scripts/kotlin_comment_balance.py                 # 扫描 E50 相关文件
    python scripts/kotlin_comment_balance.py <文件或目录>...  # 扫描指定路径

退出码：0 = 全部配对；1 = 有不配对（**会让编译失败**）。
"""
import os
import re
import sys

# 去掉字符串字面量（避免把 "/*" 之类算进去）
STR = re.compile(r'"(?:\\.|[^"\\])*"')

DEFAULT_FILES = [
    'app/src/main/java/com/nasmusic/tv/visualizer/qianli/QianliRenderStyle.kt',
    'app/src/main/java/com/nasmusic/tv/visualizer/qianli/port/ShanShui.kt',
    'app/src/main/java/com/nasmusic/tv/visualizer/qianli/port/ShanShuiPlanner.kt',
    'app/src/main/java/com/nasmusic/tv/visualizer/qianli/port/ShanShuiArch.kt',
    'app/src/main/java/com/nasmusic/tv/visualizer/qianli/port/ShanShuiTree.kt',
    'app/src/main/java/com/nasmusic/tv/visualizer/qianli/port/ShanShuiBoat.kt',
    'app/src/main/java/com/nasmusic/tv/visualizer/renderers/QianliUpstreamRenderer.kt',
    'app/src/main/java/com/nasmusic/tv/visualizer/renderers/QianliScrollRenderer.kt',
    'app/src/main/java/com/nasmusic/tv/visualizer/VisualizerRendererFactory.kt',
    'app/src/main/java/com/nasmusic/tv/ui/components/VisualizerStage.kt',
    'app/src/test/java/com/nasmusic/tv/visualizer/qianli/ShanShuiPortGateTest.kt',
    'app/src/test/java/com/nasmusic/tv/visualizer/qianli/QianliUpstreamGateTest.kt',
    'app/src/test/java/com/nasmusic/tv/visualizer/qianli/QianliUpstreamPreviewTest.kt',
    'app/src/test/java/com/nasmusic/tv/visualizer/qianli/ShanShuiArch23GateTest.kt',
    'app/src/test/java/com/nasmusic/tv/visualizer/qianli/ShanShuiBoatManGateTest.kt',
    'app/src/test/java/com/nasmusic/tv/visualizer/fx/FxCoverageScanTest.kt',
]


def scan_text(s):
    """模拟 Kotlin 的可嵌套块注释 ⇒ 返回 (深度, 首个多余的 `*/` 的行号)。"""
    s2 = STR.sub('""', s)
    depth = 0
    extra_close_line = None
    i = 0
    while i < len(s2) - 1:
        if s2[i] == '/' and s2[i + 1] == '*':
            depth += 1
            i += 2
            continue
        if s2[i] == '*' and s2[i + 1] == '/':
            depth -= 1
            if depth < 0 and extra_close_line is None:
                extra_close_line = s2[:i].count('\n') + 1
            i += 2
            continue
        i += 1
    return depth, extra_close_line


def scan_file(path):
    s = open(path, encoding='utf-8', errors='replace').read()
    depth, extra = scan_text(s)
    return depth, extra, s


def expand(paths):
    out = []
    for p in paths:
        if os.path.isdir(p):
            for root, _dirs, files in os.walk(p):
                for f in files:
                    if f.endswith('.kt'):
                        out.append(os.path.join(root, f))
        elif p.endswith('.kt') and os.path.isfile(p):
            out.append(p)
    return out


def main(paths):
    targets = expand(paths) if paths else DEFAULT_FILES
    bad = 0
    for p in targets:
        try:
            depth, extra, s = scan_file(p)
        except Exception as e:
            print('MISSING  %s  (%s)' % (p, e))
            continue
        opens = len(re.findall(re.escape('/*'), STR.sub('""', s)))
        closes = len(re.findall(re.escape('*/'), STR.sub('""', s)))
        status = 'OK'
        if depth != 0:
            status = '<<<< UNCLOSED (depth=%d) —— 编译会失败' % depth
            bad += 1
        elif extra:
            status = '<<<< EXTRA `*/` at line %d' % extra
            bad += 1
        print('%-48s open=%-4d close=%-4d %s' % (os.path.basename(p), opens, closes, status))
    print()
    print('不配对的文件数：', bad)
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
