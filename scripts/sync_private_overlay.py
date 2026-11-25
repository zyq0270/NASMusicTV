#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把隐藏分支（feat/e51-private）的 E51/E52 源码与资产同步到本机的**仓库外覆盖层**。

为什么需要这个脚本
------------------
本机（仅本机！）有一个**私有覆盖层**（在仓库**树内**，但被 `.gitignore` 挡住）：

    app/src/e51-overlay/ovl/app/src/main/java/com/nasmusic/tv/...
    app/src/e51-overlay/ovl/app/src/main/assets/...

仓库内的挂载正文 `scripts/local-e51-overlay.gradle` 把它加成 app 模块 main 源集的额外源目录
（Kotlin 源码走 java 源目录、资产走 assets 源目录），并在 **Kotlin 编译任务层**排除仓库里
那几份同名文件 ⇒ **本地构建实际编译的是覆盖层那份**。

（正文本就在仓库里，可版本化；本机 `~/.gradle/init.d/10-nasmusic-e51-local.gradle` 只是那枚
**壳** —— Gradle 只自动扫 `$GRADLE_USER_HOME/init.d/`，仓库里的文件不会被自动加载。
壳由 `python scripts/install_local_init_shim.py` 生成 / 刷新 / 校验。）

⭐ 于是「**在 main 分支上本地编译也携带隐藏分支（E51 像素城市 / E52 旷野公路）的效果**」
这件事，靠的就是这份覆盖层；而 GitHub / CI 上没有这份覆盖层 ⇒ 公开包里一行隐藏分支代码都没有。

⭐ **E55「街机合集」已不在此列**（2026-11-10 所有者第三次裁决：它是公开的、在 main 分支下的）
⇒ 它的实现与图集都在 `main` 上 ⇒ 不再进覆盖层（见 SHADOWED_JAVA / SHADOWED_ASSETS 的说明）。

⚠️⚠️ **代价（2026-11-02 踩过）**：改了隐藏分支的 E51/E52 代码而**没**同步覆盖层
⇒ 本地包**根本不含**新代码，而 CI（无覆盖层）却能编过 ⇒ **本地与 CI 行为分叉**，极难察觉。
⇒ 本脚本负责同步；漂移门禁测试负责**守住**这条不变量。

用法（**从仓库根**跑）：

    python scripts/sync_private_overlay.py            # 同步 + 打印摘要
    python scripts/sync_private_overlay.py --check    # 只检查漂移，不写入（退出码 1 = 有漂移）

源（source of truth）怎么取
---------------------------
    · 当前工作树里**有**私有源码（= 正处在分支 feat/e51-private 上）⇒ 用**工作树**
      （这样未提交的改动也会被带进覆盖层）；
    · 否则（当前在 main 等其它分支）⇒ 从**本地分支 ref** `feat/e51-private` 抽出
      （⛔ 该分支无 upstream、从不推送，只在本机存在；用 `git archive` 取，不切分支）。

环境变量
--------
    `PRIVATE_OVERLAY_DIR` —— 覆盖层**源目录根**（即挂载正文里的 OVERLAY）。
                             ⭐ 2026-11-10 起**缺省从仓库根推导**（<repo>/app/src/e51-overlay），
                               不再是写死的本机路径 ⇒ 仓库换位置不用改任何东西。
    `E51_OVERLAY_DIR`     —— 旧名，语义是「java 包根」（.../ovl/app/src/main/java/com/nasmusic/tv），
                             本脚本会自动折算成源目录根，两个都可用。

退出码
------
    0 = 一致（或已同步完成）；1 = **有漂移**（仅 `--check` 时）；2 = **无法比较**
    （机器上没有覆盖层，或本地没有私有分支 ref —— CI / 别人的机器）。
    漂移门禁测试 `PrivateOverlayDriftGateTest` 只在退出码 1 时判红，2 视为跳过。
"""

from __future__ import print_function

import filecmp
import os
import shutil
import subprocess
import sys
import tempfile

# ── 覆盖层位置 ──────────────────────────────────────────────────────────────
# 覆盖层里 java 包根相对「源目录根」的位置（多一层 ovl/ ⇒ 挂载正文的排除模式命不中覆盖层）
OVERLAY_JAVA_REL = os.path.join('ovl', 'app', 'src', 'main', 'java')
OVERLAY_ASSETS_REL = os.path.join('ovl', 'app', 'src', 'main', 'assets')

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO_JAVA = os.path.join(REPO_ROOT, 'app', 'src', 'main', 'java', 'com', 'nasmusic', 'tv')
REPO_ASSETS = os.path.join(REPO_ROOT, 'app', 'src', 'main', 'assets')

# 覆盖层**放在仓库树内**（2026-11-07 从仓库外迁入；所有者裁定）。
#
# ⚠️ **必须 gitignore**（见 .gitignore 的 `/app/src/e51-overlay/`）：
#    它是 `feat/e51-private`（⛔ 从不 push）的**物化副本**，内含**不可再分发**的上游衍生源码
#    ⇒ 一旦被提交进公开仓库（origin 是 public）就是泄漏。
#    仓库里已有同样的先例：`app/src/lofi-upstream/`、`app/src/globe-upstream/`。
#
# ✅ 放树内的好处：它跟项目在一起、看得见、不会像「项目外的陌生目录」那样被误当成无关物删掉。
DEFAULT_OVERLAY_ROOT = os.path.join(REPO_ROOT, 'app', 'src', 'e51-overlay')

PRIVATE_REF = 'feat/e51-private'

# ── 需要同步的路径（相对 REPO_JAVA / REPO_ASSETS）────────────────────────────
# ⛔ 与 scripts/local-e51-overlay.gradle 的 E51_TRACKED 一一对应
#    （⛔ 改这里必须同步改那个文件；由 OverlaySourceListGateTest 守住这条不变量）。
# 「目录」= 整棵树；「文件」= 单个文件。
SHADOWED_JAVA = [
    ('visualizer/e51', 'dir'),
    ('visualizer/highway', 'dir'),
    ('ui/components/LofiCityCard.kt', 'file'),
    ('visualizer/renderers/LofiCityRenderer.kt', 'file'),
    ('visualizer/RenderContext.kt', 'file'),
    ('visualizer/VisualizerRendererFactory.kt', 'file'),
    ('ui/components/VisualizerStage.kt', 'file'),
]

# E52 的资产（上游 Slow Roads 贴图与模型）—— 只在 main 上靠覆盖层带进来
#
# ⭐ 2026-11-10 所有者第三次裁决（「魂斗罗应该是公开的、在 main 分支下的」）后，
#    E55「街机合集」的实现与图集**已入 main** ⇒ 它不再是「隐藏分支物化」，
#    ⛔ 已从 SHADOWED_JAVA / SHADOWED_ASSETS 里删掉（`visualizer/arcade` 与 `e54_arcade`）。
#    理由不是省事：挂载正文在「仓库里没有隐藏分支源码」（= 在 main 上）时会**排除仓库里
#    那几份同名文件**、改用覆盖层那份 ⇒ 不删就会让 main 刚入仓的 arcade 源码被旧副本顶掉。
#    ⛔ 上游原件（app/src/e54-upstream/）仍然 .gitignore、仍然不入仓。
SHADOWED_ASSETS = [
    ('e52_highway', 'dir'),
]


def overlay_root():
    """覆盖层「源目录根」：挂载正文把它加成额外源目录的那一层。"""
    for env in ('PRIVATE_OVERLAY_DIR', 'E51_OVERLAY_DIR'):
        v = os.environ.get(env)
        if not v:
            continue
        v = v.rstrip('\\/')
        # 旧语义：给的是 java 包根 ⇒ 折算回源目录根
        suffix = os.path.join('ovl', 'app', 'src', 'main', 'java', 'com', 'nasmusic', 'tv')
        norm = v.replace('/', '\\').lower()
        if norm.endswith(suffix.lower()):
            v = v[: -len(suffix)].rstrip('\\/')
        return v
    return DEFAULT_OVERLAY_ROOT


def _run_git(args):
    r = subprocess.run(['git'] + args, cwd=REPO_ROOT, capture_output=True)
    return r.returncode, r.stdout, r.stderr


def _have_private_ref():
    rc, out, _ = _run_git(['rev-parse', '--verify', '--quiet', PRIVATE_REF + '^{commit}'])
    return rc == 0 and out.strip() != b''


def _extract_ref_to(tmp_root):
    """把 PRIVATE_REF 里的相关路径抽到 tmp_root（返回一个「仓库形态」的根目录）。"""
    paths = []
    for rel, _kind in SHADOWED_JAVA:
        paths.append('app/src/main/java/com/nasmusic/tv/' + rel.replace('\\', '/'))
    for rel, _kind in SHADOWED_ASSETS:
        paths.append('app/src/main/assets/' + rel.replace('\\', '/'))
    rc, out, err = _run_git(['archive', '--format=tar', PRIVATE_REF] + paths)
    if rc != 0:
        raise RuntimeError('git archive 失败：%s' % err.decode('utf-8', 'replace')[:400])
    tar_path = os.path.join(tmp_root, 'private.tar')
    with open(tar_path, 'wb') as f:
        f.write(out)
    extract_dir = os.path.join(tmp_root, 'extract')
    os.makedirs(extract_dir, exist_ok=True)
    if sys.platform.startswith('win'):
        subprocess.run(['tar', '-xf', tar_path, '-C', extract_dir], check=True)
    else:
        subprocess.run(['tar', '-xf', tar_path, '-C', extract_dir], check=True)
    return extract_dir


def _source_roots():
    """返回 (java_root, assets_root, 说明文字)。"""
    # ① 工作树里有私有源码（正处在私有分支上）⇒ 用工作树（含未提交改动）
    if os.path.isdir(os.path.join(REPO_JAVA, 'visualizer', 'e51')):
        return REPO_JAVA, REPO_ASSETS, '工作树（%s）' % _current_branch()
    # ② 否则从本地私有分支 ref 抽
    if _have_private_ref():
        tmp = tempfile.mkdtemp(prefix='nasmusic-overlay-')
        root = _extract_ref_to(tmp)
        return (os.path.join(root, 'app', 'src', 'main', 'java', 'com', 'nasmusic', 'tv'),
                os.path.join(root, 'app', 'src', 'main', 'assets'),
                '分支 ref %s' % PRIVATE_REF)
    return None, None, None


def _current_branch():
    rc, out, _ = _run_git(['branch', '--show-current'])
    return out.decode('utf-8', 'replace').strip() or '(detached)'


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
        pa, pb = os.path.join(a, rel), os.path.join(b, rel)
        if la[rel] != lb[rel] or not filecmp.cmp(pa, pb, shallow=False):
            return False
    return True


def _copy_tree(src, dst):
    """目录级覆盖（先删后拷，避免残留已删除的文件）。"""
    if os.path.isdir(dst):
        shutil.rmtree(dst)
    shutil.copytree(src, dst)


def _relative_files(root):
    out = {}
    if not os.path.isdir(root):
        return out
    for dp, _dn, fn in os.walk(root):
        for name in fn:
            path = os.path.join(dp, name)
            out[os.path.relpath(path, root).replace(os.sep, '/')] = path
    return out


def _expected_overlay_files(src_java, src_assets):
    expected = {}
    missing = []
    for root_kind, src_root, entries in (
        ('java', src_java, SHADOWED_JAVA),
        ('assets', src_assets, SHADOWED_ASSETS),
    ):
        for rel, kind in entries:
            label = ('assets/' if root_kind == 'assets' else '') + rel
            src = os.path.join(src_root, *rel.split('/'))
            if not os.path.exists(src):
                missing.append('missing-source:' + label)
                continue
            if kind == 'dir':
                if not os.path.isdir(src):
                    missing.append('invalid-source:' + label)
                    continue
                for child_rel, child_path in _relative_files(src).items():
                    expected[(root_kind, rel + '/' + child_rel)] = (child_path, label)
            else:
                if not os.path.isfile(src):
                    missing.append('invalid-source:' + label)
                    continue
                expected[(root_kind, rel)] = (src, label)
    return expected, missing


def compare_overlay(src_java, src_assets, ov_java, ov_assets):
    """返回覆盖层漂移清单；缺失必需源与目标独有文件都算漂移。"""
    expected, drift = _expected_overlay_files(src_java, src_assets)
    actual = {
        **{('java', rel): path for rel, path in _relative_files(ov_java).items()},
        **{('assets', rel): path for rel, path in _relative_files(ov_assets).items()},
    }
    for key in actual:
        if key not in expected:
            kind, rel = key
            drift.append('extra:' + ('assets/' if kind == 'assets' else '') + rel)

    changed_labels = set()
    for key, (src, label) in expected.items():
        dst = actual.get(key)
        if dst is None or not filecmp.cmp(src, dst, shallow=False):
            changed_labels.add(label)
    drift.extend(sorted(changed_labels))
    return sorted(set(drift))


def _remove_overlay_extras(src_java, src_assets, ov_java, ov_assets):
    expected, _ = _expected_overlay_files(src_java, src_assets)
    for root_kind, root in (('java', ov_java), ('assets', ov_assets)):
        for rel, path in _relative_files(root).items():
            if (root_kind, rel) not in expected:
                os.remove(path)
        for dp, dn, _fn in os.walk(root, topdown=False):
            if os.path.abspath(dp) == os.path.abspath(root):
                continue
            try:
                os.rmdir(dp)
            except OSError:
                pass


def main(argv):
    # ⛔ 本机 Windows 控制台默认 CP936(GBK) ⇒ 打印 '✓'(U+2713) 会抛 UnicodeEncodeError
    #    ⇒ 脚本崩在第一次成功行 ⇒ 退出码 1 ⇒ 漂移门禁恒红（误判）。
    #    强制 stdout 走 UTF-8 + 容错替换；测试侧也按 UTF-8 读这个输出（两边一致）。
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

    check_only = '--check' in argv
    ov = overlay_root()
    if not os.path.isdir(ov):
        if check_only:
            print('覆盖层不存在：%s' % ov)
            print('（没有覆盖层的机器 —— CI / 别人的机器 —— 不需要同步）')
            return 2  # 2 = 无法比较（不是漂移）
        # 同步模式：覆盖层在**仓库树内**（2026-11-07 迁入）⇒ 缺失就建出来。
        try:
            os.makedirs(ov)
            print('覆盖层不存在，已新建：%s' % ov)
        except Exception as exc:
            print('❌ 无法创建覆盖层目录 %s：%s' % (ov, exc))
            return 2

    src_java, src_assets, src_desc = _source_roots()
    if src_java is None:
        print('❌ 找不到隐藏分支的源码：')
        print('   · 工作树里没有 app/src/main/java/com/nasmusic/tv/visualizer/e51/')
        print('   · 本地也没有分支 ref `%s`' % PRIVATE_REF)
        print('   ⇒ 覆盖层无法同步（请先确认私有分支还在本机）')
        return 2  # 2 = 无法比较（不是漂移）

    print('覆盖层：%s' % ov)
    print('源    ：%s' % src_desc)
    print('')

    ov_java = os.path.join(ov, OVERLAY_JAVA_REL, 'com', 'nasmusic', 'tv')
    ov_assets = os.path.join(ov, OVERLAY_ASSETS_REL)

    drift = compare_overlay(src_java, src_assets, ov_java, ov_assets)
    source_errors = [item for item in drift if item.startswith(('missing-source:', 'invalid-source:'))]
    if source_errors:
        for item in source_errors:
            print('  ⛔ %s' % item)
        print('')
        print('隐藏分支缺少清单中的必需源码/资产；为避免静默产出不完整覆盖层，已停止。')
        return 1

    if check_only:
        for item in drift:
            print('  漂移 ✗  %s' % item)
        if drift:
            print('')
            print('⛔ 覆盖层与隐藏分支源码有 %d 处漂移：%s' % (len(drift), ', '.join(drift)))
            print('   跑 `python scripts/sync_private_overlay.py` 同步。')
            return 1
        print('')
        print('完成：漂移 0 处')
        return 0

    for rel, kind in SHADOWED_JAVA:
        src = os.path.join(src_java, *rel.split('/'))
        dst = os.path.join(ov_java, *rel.split('/'))
        if kind == 'dir':
            _copy_tree(src, dst)
        else:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)
        print('  已同步 ✓  %s' % rel)

    for rel, kind in SHADOWED_ASSETS:
        src = os.path.join(src_assets, *rel.split('/'))
        dst = os.path.join(ov_assets, *rel.split('/'))
        if kind == 'dir':
            _copy_tree(src, dst)
        else:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)
        print('  已同步 ✓  assets/%s' % rel)

    _remove_overlay_extras(src_java, src_assets, ov_java, ov_assets)
    print('')
    print('完成：漂移 %d 处（已同步）' % len(drift))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
