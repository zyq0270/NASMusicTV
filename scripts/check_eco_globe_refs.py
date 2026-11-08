# -*- coding: utf-8 -*-
"""静态交叉核对：eco-globe 页面/脚本引用的资产是否真实存在。

vm 加载测试**不发起 fetch** ⇒ 抓不到「引用了但文件不存在」这类缺件
（真机表现是某个模型静默缺失，甚至整页报错）。

两种引用形态都要覆盖：
  ① 完整路径字面量   '/eco-globe/glb/deer.glb'
  ② 目录前缀 + 文件名  CONFIG.bakedDir ('/eco-globe/tex/') + 'earth.png'
     ⚠️ 形态 ② 用正则扫不到 ⇒ 必须单独核对 bakedDir 与其文件名列表（本脚本第 2 段）。

用法（仓库根目录）：python scripts/check_eco_globe_refs.py
退出码 0 = 通过。仅打印 ASCII，避免 GBK 控制台 UnicodeEncodeError。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = os.path.join(ROOT, 'app', 'src', 'main', 'assets')
PAGE_DIR = os.path.join(BASE, 'eco-globe')

REQUIRED_GLB = 16
SHARED = [
    '/globe/polyfill.es5.js',
    '/globe/three.es5.js',
    '/globe/three-globe.es5.js',
]
# 贴图必须以 bakedDir 为前缀逐张装载（形态 ②）—— 少一张会回退程序化噪声
REQUIRED_TEX = ['earth.png', 'bump.png', 'rough.png', 'cloud-a.png', 'cloud-b.png', 'terrain.png']


def resolve(web_path):
    return os.path.join(BASE, web_path.lstrip('/').replace('/', os.sep))


def main():
    problems = []

    js = io.open(os.path.join(PAGE_DIR, 'globe.js'), encoding='utf-8').read()
    html = io.open(os.path.join(PAGE_DIR, 'index.html'), encoding='utf-8').read()

    # ── 段 1：完整路径字面量 ────────────────────────────────────────────────
    refs = set(re.findall(r'''["'](/(?:eco-globe|globe)/[^"'\s]+)["']''', js + html))

    missing = []
    for r in sorted(refs):
        p = resolve(r)
        target = p if not r.endswith('/') else p
        if r.endswith('/'):
            if not os.path.isdir(target):
                missing.append(r + '  (directory)')
        elif not os.path.isfile(target):
            missing.append(r)

    glbs = sorted(r for r in refs if r.endswith('.glb'))
    shared = sorted(r for r in refs if r.startswith('/globe/'))

    print('--- form 1: literal paths ---')
    print('refs total      : %d' % len(refs))
    print('glb refs        : %d (expect %d)' % (len(glbs), REQUIRED_GLB))
    print('shared lib refs : %d %s' % (len(shared), shared))
    print('missing         : %d' % len(missing))
    for m in missing:
        print('  MISSING %s' % m)
        problems.append('missing ' + m)

    if len(glbs) != REQUIRED_GLB:
        problems.append('glb ref count %d != %d' % (len(glbs), REQUIRED_GLB))
    for lib in SHARED:
        if lib not in refs:
            problems.append('page does not reference shared lib ' + lib)

    # ── 段 2：bakedDir + 文件名（贴图）───────────────────────────────────────
    print('--- form 2: bakedDir + basename ---')
    m = re.search(r"bakedDir:\s*'([^']+)'", js)
    baked = m.group(1) if m else None
    print('bakedDir        : %s' % baked)
    if baked != '/eco-globe/tex/':
        problems.append('bakedDir is %r, expected /eco-globe/tex/' % baked)

    tex_ok = 0
    for name in REQUIRED_TEX:
        # 必须真的被代码引用（否则是白带的文件），且磁盘上必须存在
        referenced = ("'%s'" % name) in js
        exists = os.path.isfile(resolve(baked or '/eco-globe/tex/') + os.sep + name) if baked else False
        if referenced and exists:
            tex_ok += 1
        else:
            problems.append('tex %s referenced=%s exists=%s' % (name, referenced, exists))
    print('tex ok          : %d/%d' % (tex_ok, len(REQUIRED_TEX)))

    # ── 段 3：反向核对 —— 磁盘上多带的 GLB 白占 APK ──────────────────────────
    glb_dir = os.path.join(PAGE_DIR, 'glb')
    on_disk = sorted(n for n in os.listdir(glb_dir) if n.endswith('.glb')) if os.path.isdir(glb_dir) else []
    unused = [n for n in on_disk if '/eco-globe/glb/' + n not in refs]
    print('--- form 3: unused on disk ---')
    print('glb on disk     : %d / unused %d %s' % (len(on_disk), len(unused), unused))
    if len(on_disk) != REQUIRED_GLB:
        problems.append('glb on disk %d != %d' % (len(on_disk), REQUIRED_GLB))
    if unused:
        problems.append('unused glb on disk: %s' % unused)

    print('')
    if problems:
        for p in problems:
            print('[FAIL] %s' % p)
        sys.exit(1)
    print('[ok] all references resolve, no unused assets')
    sys.exit(0)


if __name__ == '__main__':
    main()
