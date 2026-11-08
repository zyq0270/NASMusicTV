# -*- coding: utf-8 -*-
"""归档搬移后的**入链引用改写**（一次性脚本）。

把 `docs/<name>.md` → `docs/archive/<name>.md` 的引用全仓改掉，
⛔ 只改带 `docs/` 前缀的引用；裸文件名引用不在本脚本射程内（另用 grep 人工核对）。

用法（仓库根目录）：python scripts/sweep_archive_refs.py <旧路径> <新路径>
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SKIP_DIRS = {'.git', 'build', '.gradle', 'logs_temp', 'output', '.codegraph', '.opencode'}


def main():
    if len(sys.argv) < 3:
        print('usage: sweep_archive_refs.py <old> <new>')
        return 1
    old, new = sys.argv[1], sys.argv[2]

    changed = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, ROOT).replace('\\', '/')
            if not rel.endswith(('.md', '.kt', '.kts', '.py', '.mjs', '.js', '.xml', '.java')):
                continue
            try:
                with io.open(p, 'r', encoding='utf-8', newline='') as f:
                    s = f.read()
            except (UnicodeDecodeError, OSError):
                continue
            if old not in s:
                continue
            n = s.count(old)
            # newline='' ⇒ 不做换行转换，保住原来的 CRLF/LF
            with io.open(p, 'w', encoding='utf-8', newline='') as f:
                f.write(s.replace(old, new))
            changed.append('%s  (%d)' % (rel, n))

    print('rewritten %d file(s):' % len(changed))
    for c in changed:
        print('  ' + c)
    return 0


if __name__ == '__main__':
    sys.exit(main())
