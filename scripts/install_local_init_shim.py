#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""安装 / 刷新 / 校验本机 Gradle 初始化脚本的「壳」，把仓库内的覆盖层挂载正文接进 Gradle。

为什么需要这个脚本
------------------
Gradle **只**自动加载 `$GRADLE_USER_HOME/init.d/*.gradle`（默认 `~/.gradle/init.d/`），
仓库里的脚本文件**不会被**自动扫到 ⇒ 覆盖层挂载逻辑（`scripts/local-e51-overlay.gradle`）
必须由那枚**壳**引进来：

    ~/.gradle/init.d/10-nasmusic-e51-local.gradle
        └── apply from: <本次构建的根项目目录>/scripts/local-e51-overlay.gradle

⭐ **壳里不含任何绝对路径**：它按**当前构建的根项目目录**去找正文
   ⇒ 仓库改名 / 换盘 / 换克隆 / 用 worktree 都无须重新安装，壳的内容对所有克隆都一模一样。
⭐ **正文改了不用重装**：壳是运行时按路径找到正文的，改完直接构建即可。

⚠️ 为什么不能完全不要壳：Gradle 没有「扫仓库内 init 脚本」这种机制。
   替代方案是每次构建都带 `--init-script scripts/local-e51-overlay.gradle`（参数易漏 ⇒ 漏了就是
   「本地编出没有 E51 的包」且**不报错**）。壳把这件事变成一次性、机器级的事实。

用法（仓库根跑）：

    python scripts/install_local_init_shim.py            # 安装 / 刷新（幂等）
    python scripts/install_local_init_shim.py --check    # 只校验不写入（退出码 0 = 一致 / 1 = 不一致或缺失）
    python scripts/install_local_init_shim.py --print    # 打印壳的内容（不写盘）
    python scripts/install_local_init_shim.py --uninstall # 删掉壳（恢复「本机不挂覆盖层」）
    python scripts/install_local_init_shim.py --force    # 覆盖一个**不是本脚本生成**的壳（先备份成 .bak）

退出码
------
    0 = 成功 / 校验一致；1 = 校验不一致或操作失败；2 = 找不到 Gradle 用户目录（无法安装）

⚠️ 本脚本只管「壳」；覆盖层**内容**的同步是另一件事 ⇒ `scripts/sync_private_overlay.py`。
"""

from __future__ import print_function

import os
import shutil
import sys

# 壳的文件名与标记（标记用于识别「这份文件是本脚本生成的」）
SHIM_NAME = '10-nasmusic-e51-local.gradle'
# ⛔ 只匹配**标识符本身**，不带注释前缀（模板里它是 Groovy 注释 `// nasmusic-e51-local-shim:v1`，
#    这里若写成 Python 风格的 '# …' 就永远匹配不上 ⇒ 幂等判定失效）
MARKER = 'nasmusic-e51-local-shim:v1'

# 正文在仓库里的相对位置（壳按构建根目录找它）
BODY_REL = 'scripts/local-e51-overlay.gradle'

# 壳的内容 —— ⭐ 刻意**不含任何绝对路径**（见文件头说明）⇒ 对所有克隆完全一致、可逐字节校验
SHIM_TEMPLATE = """// ⚠️ 自动生成，勿手改 —— 由 scripts/install_local_init_shim.py 生成
// nasmusic-e51-local-shim:v1
//
// Gradle 只自动加载 `$GRADLE_USER_HOME/init.d/*.gradle`，仓库里的脚本文件不会被自动扫到
// ⇒ 这枚壳负责把仓库内的「覆盖层挂载正文」apply 进当前构建：
//
//     <本次构建的根项目目录>/scripts/local-e51-overlay.gradle
//
// ⭐ 壳里**不含绝对路径** ⇒ 仓库改名 / 换盘 / 换克隆都不用重新安装。
// ⭐ 正文改了不用重装：壳是运行时按路径找到正文的，改完直接构建即可。
//
// 生成 / 刷新 / 校验：python scripts/install_local_init_shim.py [--check]

gradle.projectsLoaded {
    gradle.rootProject { rp ->
        def body = new File(rp.projectDir, 'scripts/local-e51-overlay.gradle')
        if (!body.isFile()) {
            return
        }
        rp.apply(from: body.absolutePath)
    }
}
"""


def repo_root():
    """仓库根（本文件所在 scripts/ 的上一层）。"""
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def gradle_user_home():
    """Gradle 用户目录（`$GRADLE_USER_HOME` 或 `~/.gradle`）。"""
    env = os.environ.get('GRADLE_USER_HOME')
    if env:
        return os.path.abspath(env)
    return os.path.join(os.path.expanduser('~'), '.gradle')


def shim_path():
    """壳的完整路径：`$GRADLE_USER_HOME/init.d/10-nasmusic-e51-local.gradle`。"""
    return os.path.join(gradle_user_home(), 'init.d', SHIM_NAME)


def expected_content():
    """壳的应有内容（bytes，UTF-8 + LF）。"""
    return SHIM_TEMPLATE.replace('\\', '/').encode('utf-8')


def is_generated(path):
    """这份壳是不是本脚本生成的（看标记行）。"""
    try:
        with open(path, 'rb') as fh:
            return MARKER.encode('utf-8') in fh.read()
    except OSError:
        return False


def read_bytes(path):
    try:
        with open(path, 'rb') as fh:
            return fh.read()
    except OSError:
        return None


def cmd_check():
    path = shim_path()
    want = expected_content()
    got = read_bytes(path)
    if got is None:
        print('[FAIL] 壳不存在：%s' % path)
        print('       ⇒ 修法：python scripts/install_local_init_shim.py')
        return 1
    if got != want:
        print('[FAIL] 壳的内容与本脚本生成的版本不一致：%s' % path)
        print('       ⇒ 修法：python scripts/install_local_init_shim.py --force')
        return 1
    print('[ OK ] 壳已就位且内容一致：%s' % path)
    return 0


def cmd_install(force):
    path = shim_path()
    want = expected_content()
    init_dir = os.path.dirname(path)

    body = os.path.join(repo_root(), BODY_REL.replace('/', os.sep))
    if not os.path.isfile(body):
        print('[FAIL] 仓库里找不到正文：%s' % body)
        print('       ⇒ 本脚本只安装壳；正文缺失说明工作树不完整（误删 / 未 checkout）。')
        return 1

    existed = os.path.isfile(path)
    if existed and not is_generated(path) and not force:
        print('[FAIL] 目标位置已有一个**不是本脚本生成**的文件，拒绝覆盖：%s' % path)
        print('       ⇒ 那多半是手写的 init 脚本；确认后可加 --force（会先备份成 %s.bak）' % SHIM_NAME)
        return 1

    if existed and not is_generated(path):
        backup = path + '.bak'
        if not os.path.exists(backup):
            shutil.copy2(path, backup)
            print('[INFO] 原文件已备份：%s' % backup)

    if not os.path.isdir(init_dir):
        os.makedirs(init_dir)
        print('[INFO] 已创建目录：%s' % init_dir)

    with open(path, 'wb') as fh:
        fh.write(want)
    print('[ OK ] 壳已写入：%s（%d 字节）' % (path, len(want)))
    print('       正文：%s' % body)
    print('       ⇒ 正文改了不用重装；只有壳被误删/误改时才需要重跑本脚本。')
    return 0


def cmd_uninstall():
    path = shim_path()
    if not os.path.isfile(path):
        print('[INFO] 壳不存在，无需卸载：%s' % path)
        return 0
    os.remove(path)
    print('[ OK ] 壳已删除：%s' % path)
    print('       ⇒ 本机此后不再挂覆盖层（本地构建将不含 E51/E52 ⇒ 需把 lofi.city / highway.enabled 置 false）。')
    return 0


def cmd_print():
    sys.stdout.write(expected_content().decode('utf-8'))
    return 0


def main(argv):
    args = [a for a in argv[1:]]
    force = '--force' in args
    uninstall = '--uninstall' in args
    check = '--check' in args
    show = '--print' in args

    if show:
        return cmd_print()
    if uninstall:
        return cmd_uninstall()
    if check:
        return cmd_check()
    return cmd_install(force)


if __name__ == '__main__':
    try:
        sys.exit(main(sys.argv))
    except OSError as exc:
        print('[FAIL] %s' % exc)
        sys.exit(2)
