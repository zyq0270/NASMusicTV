"""端口 vs 上游：逐轮廓逐点对账（E50 arch 夹具）。

用法：python scripts/e50_arch_diff.py [夹具名...]
"""
import json
import os
import sys

sys.stdout.reconfigure(encoding='utf-8')

UP = 'output/e50_arch02_upstream.json'
PO = 'output/e50_arch02_port.json'

up = json.load(open(UP, encoding='utf-8'))['shapes']
po = json.load(open(PO, encoding='utf-8'))

only = sys.argv[1:]
keys = only if only else [k for k in po.keys() if k in up]


def css(c):
    """把颜色统一成 (r,g,b,a) —— 上游是 CSS 串（'white' / 'none' / 'rgba(...)'），
    端口是 'r,g,b,a' 串 ⇒ ⛔ 必须先规范化，否则同一颜色会被判成不同。"""
    if c is None:
        return None
    if isinstance(c, (list, tuple)):
        r, g, b = c[0], c[1], c[2]
        a = c[3] if len(c) > 3 else 1.0
        return (r, g, b, round(float(a), 3))
    s = str(c).strip()
    if s == 'none':
        return None
    if s == 'white':
        return (255, 255, 255, 1.0)
    if s == 'black':
        return (0, 0, 0, 1.0)
    if s.startswith('rgba') or s.startswith('rgb'):
        nums = [float(v) for v in s[s.index('(') + 1:s.index(')')].split(',')]
        if len(nums) == 3:
            nums.append(1.0)
        return (nums[0], nums[1], nums[2], round(nums[3], 3))
    parts = [p for p in s.split(',') if p != '']
    if len(parts) >= 3:
        return (int(parts[0]), int(parts[1]), int(parts[2]),
                round(float(parts[3]) if len(parts) > 3 else 1.0, 3))
    return s


for k in keys:
    a = up[k]
    b = po.get(k) or []
    print('=' * 70)
    print('%s : 上游 %d 条 / 端口 %d 条' % (k, len(a), len(b)))
    n = min(len(a), len(b))
    diff_shapes = 0
    first_bad = None
    for i in range(n):
        sa, sb = a[i], b[i]
        na = sa.get('n')
        nb = sb.get('n')
        fa, fb = css(sa.get('fil')), css(sb.get('fill'))
        sta, stb = css(sa.get('str')), css(sb.get('stroke'))
        wa, wb = sa.get('wid'), sb.get('wid')
        pa = sa.get('pts') or []
        pb = sb.get('pts') or []
        bad = []
        if na != nb:
            bad.append('n %s!=%s' % (na, nb))
        if fa != fb:
            bad.append('fil %s!=%s' % (fa, fb))
        if sta != stb:
            bad.append('str %s!=%s' % (sta, stb))
        if wa != wb:
            bad.append('wid %s!=%s' % (wa, wb))
        maxd = 0.0
        worst = None
        for j in range(min(len(pa), len(pb))):
            dx = abs(pa[j][0] - pb[j][0])
            dy = abs(pa[j][1] - pb[j][1])
            d = max(dx, dy)
            if d > maxd:
                maxd = d
                worst = (j, pa[j], pb[j])
        if maxd > 0.051:
            bad.append('坐标最大差 %.3f @%s' % (maxd, worst))
        if bad:
            diff_shapes += 1
            if first_bad is None:
                first_bad = (i, bad, worst, pa[:3], pb[:3])
    print('  轮廓不一致：%d / %d' % (diff_shapes, n))
    if first_bad:
        i, bad, worst, pa3, pb3 = first_bad
        print('  第一条不同：shape[%d]' % i)
        for b in bad:
            print('     - ' + str(b))
        if worst:
            print('     差得最多的点：idx=%d 上游=%s 端口=%s' % worst)
        print('     上游前 3 点：%s' % (pa3,))
        print('     端口前 3 点：%s' % (pb3,))
