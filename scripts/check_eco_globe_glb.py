# -*- coding: utf-8 -*-
"""检查 assets/eco-globe/glb/*.glb 是否自包含（不引用外部贴图）。

GLB 结构：12 字节头 + chunks。每块：4 字节长度 + 4 字节 type + data。
JSON 块（type 'JSON'）里 images/scenes 的 "uri" 字段：
  - "data:..." ⇒ 内嵌（自包含）
  - 其它       ⇒ 外部文件引用 ⇒ 运行时必须能取到该文件，否则 GLTFLoader 报错
用法：python scripts/check_eco_globe_glb.py
"""
import io
import json
import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GLB_DIR = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'eco-globe', 'glb')
SRC_DIR = os.path.join(ROOT, 'docs', 'assets', 'glb')


def parse_glb(path):
    with io.open(path, 'rb') as f:
        data = f.read()
    magic, version, length = struct.unpack('<4sII', data[:12])
    if magic != b'glTF':
        raise ValueError('bad magic %r' % magic)
    off = 12
    js = None
    bin_len = 0
    chunks = []
    while off + 8 <= len(data):
        clen, ctype = struct.unpack('<II', data[off:off + 8])
        chunks.append((ctype, clen))
        if ctype == 0x4E4F534A:  # 'JSON'
            js = json.loads(data[off + 8:off + 8 + clen].decode('utf-8'))
        elif ctype == 0x004E4942:  # 'BIN'
            bin_len = clen
        off += 8 + clen
    return version, length, len(data), chunks, js, bin_len


def main():
    if not os.path.isdir(GLB_DIR):
        print('[FAIL] missing dir: %s' % GLB_DIR)
        return 1
    names = sorted(n for n in os.listdir(GLB_DIR) if n.endswith('.glb'))
    print('glb files: %d' % len(names))
    bad = 0
    for n in names:
        p = os.path.join(GLB_DIR, n)
        try:
            version, declared, actual, chunks, js, bin_len = parse_glb(p)
        except Exception as e:
            print('  [FAIL] %s: %s' % (n, e))
            bad += 1
            continue
        problems = []
        if declared != actual:
            problems.append('length mismatch declared=%d actual=%d' % (declared, actual))
        if js is None:
            problems.append('no JSON chunk')
            uris = []
        else:
            uris = []
            for img in js.get('images', []) or []:
                u = img.get('uri')
                if u and not u.startswith('data:'):
                    uris.append(u)
            for buf in js.get('buffers', []) or []:
                u = buf.get('uri')
                if u and not u.startswith('data:'):
                    uris.append(u)
            if uris:
                problems.append('EXTERNAL refs: %s' % uris)
            if not js.get('meshes'):
                problems.append('no meshes')
            anims = len(js.get('animations', []) or [])
        src = os.path.join(SRC_DIR, n)
        same = ''
        if os.path.isfile(src):
            same = 'same-as-source' if os.path.getsize(src) == os.path.getsize(p) else 'SIZE-DIFF-from-source'
        else:
            same = 'no-source-copy'
        status = '[FAIL] ' + '; '.join(problems) if problems else 'ok'
        if problems:
            bad += 1
        print('  %-16s v%d %7dB chunks=%s %s %s' % (
            n, version, actual, [hex(c[0]) for c in chunks], same, status))
    print('')
    print('[FAIL] %d file(s) problematic' % bad if bad else '[ok] all GLB are self-contained')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
