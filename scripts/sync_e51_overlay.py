#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把 E51 本地覆盖层里「与仓库同名」的那几份文件同步成仓库当前版本。

为什么需要这个脚本
------------------
本机（仅本机！）有一个 **E51 本地覆盖层**：

    D:/hxzhang/MyGithubSoftware/NASMusicTV-e51-local/src/ovl/app/src/main/java/com/nasmusic/tv/...

`~/.gradle/init.d/10-nasmusic-e51-local.gradle` 把它加成 app 模块 main 源集的
额外源目录，并在 **Kotlin 编译任务层**排除仓库里那几份同名文件
（否则 Redeclaration）⇒ **本地构建实际编译的是覆盖层那份**。

⚠️⚠️ **代价（2026-11-02 踩过）**：改仓库里的 `VisualizerStage.kt` 而**没**同步覆盖层
⇒ 本地包**根本不含**新代码 ⇒ 真机表现为「功能没反应 / 日志一条都没有」，
而 CI（无覆盖层）却能编过 ⇒ **本地与 CI 行为分叉**，极难察觉。

⇒ 本脚本把仓库当前版本同步到覆盖层；`E51OverlayDriftGateTest` 负责**守住**这条不变量。

用法（**从仓库根**跑）：

    python scripts/sync_e51_overlay.py            # 同步 + 打印摘要
    python scripts/sync_e51_overlay.py --check    # 只检查漂移，不写入（退出码 1 = 有漂移）

环境变量 `E51_OVERLAY_DIR` 可覆盖覆盖层根目录（默认本机路径）。
"""

from __future__ import print_function

import filecmp
import os
import shutil
import sys

# 覆盖层根（`ovl/app/src/main/java/com/nasmusic/tv` 这一层）
DEFAULT_OVERLAY = (
    r'D:\hxzhang\MyGithubSoftware\NASMusicTV-e51-local\src'
    r'\ovl\app\src\main\java\com\nasmusic\tv'
)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO_JAVA = os.path.join(REPO_ROOT, 'app', 'src', 'main', 'java', 'com', 'nasmusic', 'tv')

# 与 init 脚本的 `E51_TRACKED` 一一对应（⛔ 改这里必须同步改 init 脚本）
SHADOWED = [
    ('visualizer/e51', 'dir'),
    ('ui/components/LofiCityCard.kt', 'file'),
    ('visualizer/renderers/LofiCityRenderer.kt', 'file'),
    ('visualizer/RenderContext.kt', 'file'),
    ('visualizer/VisualizerRendererFactory.kt', 'file'),
    ('ui/components/VisualizerStage.kt', 'file'),
]


def overlay_root():
    return os.environ.get('E51_OVERLAY_DIR', DEFAULT_OVERLAY)


def copy_tree(src, dst):
    """目录级覆盖（先删后拷，避免残留已删除的文件）。"""
    if os.path.isdir(dst):
        shutil.rmtree(dst)
    shutil.copytree(src, dst)


def main(argv):
    check_only = '--check' in argv
    ov = overlay_root()
    if not os.path.isdir(ov):
        print('覆盖层不存在：%s' % ov)
        print('（没有覆盖层的机器 —— CI / 别人的机器 —— 不需要同步）')
        return 0

    drift = []
    for rel, kind in SHADOWED:
        src = os.path.join(REPO_JAVA, *rel.split('/'))
        dst = os.path.join(ov, *rel.split('/'))
        if not os.path.exists(src):
            print('  跳过（仓库里没有）：%s' % rel)
            continue
        if kind == 'dir':
            same = os.path.isdir(dst) and _tree_same(src, dst)
        else:
            same = os.path.exists(dst) and filecmp.cmp(src, dst, shallow=False)
        if same:
            print('  一致 ✓  %s' % rel)
            continue
        drift.append(rel)
        if check_only:
            print('  漂移 ✗  %s' % rel)
        else:
            if kind == 'dir':
                copy_tree(src, dst)
            else:
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copy2(src, dst)
            print('  已同步 ✓ %s' % rel)

    if check_only and drift:
        print('')
        print('⛔ 覆盖层与仓库有 %d 处漂移：%s' % (len(drift), ', '.join(drift)))
        print('   跑 `python scripts/sync_e51_overlay.py` 同步。')
        return 1
    print('')
    print('完成：漂移 %d 处%s' % (len(drift), '（已同步）' if drift and not check_only else ''))
    return 0


def _tree_same(a, b):
    """目录内容逐文件比对（相对路径 + 字节）。"""
    def listing(root):
        out = {}
        for dp, _dn, fn in os.walk(root):
            for f in fn:
                p = os.path.join(dp, f)
                out[os.path.relpath(p, root)] = os.path.getsize(p)
        return out

    la, lb = listing(a), listing(b)
    if set(la) != set(lb):
        return False
    for rel in la:
        if not filecmp.cmp(os.path.join(a, rel), os.path.join(b, rel), shallow=False):
            return False
    return True


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
