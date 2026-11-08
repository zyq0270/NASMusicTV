# -*- coding: utf-8 -*-
"""死链检查：全仓 `docs/…` 引用指向的文件是否都存在。

归档搬移（`git mv docs/x.md docs/archive/x.md`）之后，**入链引用必须同步改写**，
否则留下的是「看起来对、点不开」的路径。本脚本全仓扫一遍并报告缺失。

## 判据（三类）

- **OK**      —— 文件在工作树里存在。
- **PRIVATE** —— 文件只存在于本地私有分支 `feat/e51-private`（⛔ 从不推送）
  （例：两份 E52 原型 HTML）⇒ 在 main 上「缺失」是**预期**，不算死链。
- **MISSING** —— 两边都没有 ⇒ 真正的死链。

## 扫描范围

跳过 `.` 开头的目录（工具态目录：`.workbuddy/` / `.superpowers/` / …）与构建产物目录。

用法（仓库根目录）：python scripts/check_docs_links.py
退出码 0 = 无死链。仅打印 ASCII，避免 GBK 控制台 UnicodeEncodeError。
"""
import io
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PRIVATE_REF = 'feat/e51-private'
SKIP_DIRS = {'.git', 'build', '.gradle', 'logs_temp', 'output', '.codegraph', 'node_modules'}
TEXT_EXT = ('.md', '.kt', '.kts', '.py', '.mjs', '.js', '.xml', '.java', '.gradle', '.txt', '.yml', '.yaml')
PAT = re.compile(r'docs/[A-Za-z0-9_./\u4e00-\u9fff-]+\.(?:md|html|json|txt|py|mjs|js|sh)')


def private_tree_files():
    """私有分支上的文件集合（相对仓库根）。"""
    try:
        out = subprocess.check_output(
            ['git', 'ls-tree', '-r', '--name-only', PRIVATE_REF],
            cwd=ROOT, stderr=subprocess.DEVNULL)
        return set(out.decode('utf-8', 'ignore').split('\n'))
    except Exception:
        return set()


def main():
    self_rel = os.path.relpath(os.path.abspath(__file__), ROOT).replace('\\', '/')
    priv = private_tree_files()

    refs = {}
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS and not d.startswith('.')]
        for fn in filenames:
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, ROOT).replace('\\', '/')
            if rel == self_rel:
                continue
            if not rel.endswith(TEXT_EXT):
                continue
            try:
                with io.open(p, 'r', encoding='utf-8', errors='ignore') as f:
                    s = f.read()
            except OSError:
                continue
            for m in PAT.findall(s):
                refs.setdefault(m, set()).add(rel)

    missing, private_only = [], []
    for ref, users in sorted(refs.items()):
        target = os.path.join(ROOT, ref.replace('/', os.sep))
        if os.path.exists(target):
            continue
        if ref in priv:
            private_only.append((ref, sorted(users)))
        else:
            missing.append((ref, sorted(users)))

    print('distinct docs refs : %d' % len(refs))
    print('ok                  : %d' % (len(refs) - len(missing) - len(private_only)))
    print('private-only        : %d  (只在 %s，main 上缺失属预期)' % (len(private_only), PRIVATE_REF))
    for ref, _ in private_only:
        print('  PRIVATE %s' % ref)
    print('missing (dead link) : %d' % len(missing))
    for ref, users in missing:
        print('  MISSING %s' % ref)
        for u in users[:5]:
            print('      <- %s' % u)
        if len(users) > 5:
            print('      <- (+%d more)' % (len(users) - 5))

    print('')
    print('[ok] no dead docs links' if not missing else '[FAIL] dead links found')
    return 0 if not missing else 1


if __name__ == '__main__':
    sys.exit(main())
