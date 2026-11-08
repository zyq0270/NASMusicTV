# -*- coding: utf-8 -*-
"""一次性核对：release 包里是否带着本地变体的 E51/E52 隐藏分支效果。

⚠️ 带 E51 的包是**本地专用**（⛔ 不 push / 不 tag / 不发布）—— 这个脚本只是确认
「本地构建确实携带了隐藏分支效果」（AGENTS.md 的本地构建口径）。
用法：python scripts/check_release_local_variant.py <apk>
"""
import sys
import zipfile

DEFAULT = 'app/build/outputs/apk/release/NASMusicTV-release-v2-39-14.apk'
KEYS = [b'LofiCityRenderer', b'HighwayGLSurfaceView', b'QianliUpstreamRenderer',
        b'EcoGlobeRenderer']


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else DEFAULT
    z = zipfile.ZipFile(apk)
    names = z.namelist()
    e52 = [x for x in names if x.startswith('assets/e52_highway/')]
    print('APK                    : %s' % apk)
    print('assets/e52_highway/    : %d' % len(e52))
    blob = b''.join(z.read(x) for x in names if x.endswith('.dex'))
    for k in KEYS:
        print('dex %-24s: %d' % (k.decode(), blob.count(k)))
    print('[ok] local variant (E51/E52) present' if e52 else '[FAIL] no e52 assets')
    return 0 if e52 else 1


if __name__ == '__main__':
    sys.exit(main())
