# -*- coding: utf-8 -*-
"""一次性实证：APK 里确实带着 E54「生态地球」的主题、脚本与资产。

用法：python scripts/verify_eco_globe_in_apk.py [apk路径]
退出码 0 = 全部就位。仅打印 ASCII。
"""
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT = os.path.join(
    ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'NASMusicTV-debug-v2-39-13.apk'
)

EXPECT_ASSETS = 25          # 16 GLB + 6 贴图 + index.html + globe.js + loaders.es5.js
EXPECT_GLB = 16
EXPECT_TEX = 6
KEYWORDS = [b'EcoGlobeRenderer', b'visualizer_theme_eco_globe', b'EcoGlobe']


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else DEFAULT
    if not os.path.isfile(apk):
        print('[FAIL] APK not found: %s' % apk)
        return 1
    z = zipfile.ZipFile(apk)
    names = z.namelist()
    eco = [n for n in names if n.startswith('assets/eco-globe/')]
    glb = [n for n in eco if n.endswith('.glb')]
    tex = [n for n in eco if n.endswith('.png')]

    blob = b''
    for n in names:
        if n.endswith('.dex') or n.endswith('resources.arsc'):
            blob += z.read(n)

    print('APK              : %s (%.1f MB)' % (os.path.basename(apk), os.path.getsize(apk) / 1048576.0))
    print('eco-globe assets : %d (expect %d)' % (len(eco), EXPECT_ASSETS))
    print('  glb            : %d (expect %d)' % (len(glb), EXPECT_GLB))
    print('  tex            : %d (expect %d)' % (len(tex), EXPECT_TEX))
    for f in ('index.html', 'globe.js', 'loaders.es5.js'):
        print('  %-14s : %s' % (f, 'assets/eco-globe/' + f in eco))

    ok = len(eco) == EXPECT_ASSETS and len(glb) == EXPECT_GLB and len(tex) == EXPECT_TEX
    for kw in KEYWORDS:
        c = blob.count(kw)
        print('dex/arsc %-26s: %d' % (kw.decode(), c))
        if c == 0:
            ok = False

    # ── 回归判据（2026-11-08 真机故障直接固化进打包门禁）────────────────────────
    js = z.read('assets/eco-globe/globe.js').decode('utf-8')
    html = z.read('assets/eco-globe/index.html').decode('utf-8')

    # ① 动物加载：ESM 具名导入的局部绑定必须补回（漏了 ⇒ ReferenceError ⇒ 动物全挂）
    if 'var cloneSkeleton' not in js:
        print('[FAIL] globe.js 缺少 cloneSkeleton 局部绑定 ⇒ 动物会全部加载失败')
        ok = False
    else:
        print('globe.js cloneSkeleton binding : ok')

    # ② 画面上不能有演示页文案（标题 / 加载遮罩文案 / 状态条 / 百分比）
    banned = [
        ('class="title"', '标题遮罩'),
        ('id="status"', '状态条'),
        ('loadPct', '加载百分比文字'),
        ('正在唤醒', '加载遮罩文案'),
        ('自动旋转演示', '标题里的演示字样'),
    ]
    for needle, label in banned:
        if needle in html:
            print('[FAIL] index.html 仍有画面文案：%s（%s）' % (label, needle))
            ok = False
        else:
            print('index.html no %-14s: ok' % label)

    print('[ok] E54 fully packaged' if ok else '[FAIL] packaging incomplete')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
