"""用**探针同口径**的 FNV 算法分别算上游与端口的坐标摘要 ⇒ 判定是数据不符还是实现不符。"""
import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

FNV_OFFSET = 0xcbf29ce484222325
FNV_PRIME = 0x100000001b3
MASK64 = 0xFFFFFFFFFFFFFFFF


def fnv_step(h, v):
    return ((h ^ (v & 0xFFFFFFFF)) * FNV_PRIME) & MASK64


def rounded_digest(shapes, scale):
    h = FNV_OFFSET
    for s in shapes:
        h = fnv_step(h, len(s.get('pts') or []))
        for p in (s.get('pts') or []):
            h = fnv_step(h, round_half_up(p[0] * scale))
            h = fnv_step(h, round_half_up(p[1] * scale))
    return h


def round_half_up(v):
    # JS 的 Math.round：.5 往 +∞ 方向（Python 的 round 是「银行家舍入」⇒ 这里必须自己写）
    import math
    return math.floor(v + 0.5)


up = json.load(open('output/e50_arch02_upstream.json', encoding='utf-8'))['shapes']
po = json.load(open('output/e50_arch02_port.json', encoding='utf-8'))
golden = json.load(open('app/src/test/resources/qianli/shan_shui_arch02_golden.json', encoding='utf-8'))

for key in ['arch02', 'arch03', 'arch04', 'box']:
    if key not in po or key not in up:
        continue
    g = golden[key]
    for scale, field in ((10, 'roundedDigest10'), (100, 'roundedDigest100')):
        du = rounded_digest(up[key], scale)
        dp = rounded_digest(po[key], scale)
        print('%-8s scale=%-4d 上游计算=0x%016x  端口计算=0x%016x  黄金=0x%016x  上游✓=%s 端口✓=%s'
              % (key, scale, du, dp, int(g[field], 16), du == int(g[field], 16), dp == int(g[field], 16)))
    print()
