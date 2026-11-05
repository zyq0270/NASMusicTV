"""找出端口与上游在「0.1 网格取整」上**逐点不一致**的位置。

背景：G-PORT-14 的坐标摘要是 FNV 哈希 ⇒ 只要**有一个点**在 `Math.round(v*10)` 上差 1，
整个哈希就完全不同（而坐标本身只差 Float32 的 ~4e-7）。

用法：python scripts/e50_arch_round_diff.py [夹具名...]
"""
import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

UP = 'output/e50_arch02_upstream.json'
PO = 'output/e50_arch02_port.json'

up = json.load(open(UP, encoding='utf-8'))['shapes']
po = json.load(open(PO, encoding='utf-8'))

only = sys.argv[1:]
keys = only if only else [k for k in po.keys() if k in up]

total_bad = 0
for k in keys:
    a = up[k]
    b = po.get(k) or []
    bad = []
    for i in range(min(len(a), len(b))):
        pa = a[i].get('pts') or []
        pb = b[i].get('pts') or []
        for j in range(min(len(pa), len(pb))):
            for axis in (0, 1):
                ua = pa[j][axis]
                ub = pb[j][axis]
                ra = round(ua * 10.0)
                rb = round(ub * 10.0)
                if ra != rb:
                    bad.append((i, j, axis, ua, ub, ra, rb))
    total_bad += len(bad)
    print('%-12s 轮廓 %3d / %3d   网格取整不一致的点数 = %d' % (k, len(a), len(b), len(bad)))
    for rec in bad[:6]:
        i, j, axis, ua, ub, ra, rb = rec
        print('    shape[%d] pt[%d] axis=%d  上游=%.17g (round10=%d)  端口=%.17g (round10=%d)'
              % (i, j, axis, ua, ra, ub, rb))
print()
print('0.1 网格上不一致的总点数：', total_bad)
