"""量「远山」区域的像素：纸底 / 暖调墨晕 / 冷灰 / 其它。

远山（上游 `Mount.distMount`）= 白块（multiply 下 ⇒ 纸色）+ `getCol` 灰阶刻面。
本仓按所有者裁定（2026-11-06）把刻面改成「前景山墨色族 + alpha 0.25」的**墨晕**
⇒ 合成后应是**暖调灰**（r > g > b），不再是冷灰、也不再是纸色。

对照：
  · 上游参考图（`output/e50_upstream_ref.png`）的远山 = 淡暖灰（multiply 后的 getCol 灰阶）
  · 本仓真机 / 离线渲染的远山 = 墨晕（alpha 0.25 的前景山墨色）
"""
import os
import sys
from collections import Counter

from PIL import Image

sys.stdout.reconfigure(encoding='utf-8')


def classify(r, g, b):
    """把一个像素归类。"""
    if r > 225 and g > 215 and b > 190 and r > g > b:
        return '纸色'
    if abs(r - g) <= 6 and abs(g - b) <= 6:
        return '冷灰'
    if r > g > b:
        return '暖调墨'
    return '其它'


def report(path, box, n=6):
    im = Image.open(path).convert('RGB')
    crop = im.crop(box)
    c = Counter(crop.getdata())
    total = sum(c.values())
    print('  %s  区域%s  (%dx%d)' % (os.path.basename(path), box, crop.width, crop.height))
    buckets = Counter()
    for col, cnt in c.items():
        buckets[classify(*col)] += cnt
    for k in ('纸色', '暖调墨', '冷灰', '其它'):
        if buckets[k]:
            print('    %-6s %6.2f%%' % (k, 100.0 * buckets[k] / total))
    print('    主流色：')
    for col, cnt in c.most_common(n):
        r, g, b = col
        print('      RGB(%3d,%3d,%3d)  %6.2f%%  [%s]' % (r, g, b, 100.0 * cnt / total, classify(r, g, b)))
    return buckets


def main():
    print('=== 本仓：设备渲染（真机 instrumented）远山区 ===')
    if os.path.exists('output/e50_device_render.png'):
        report('output/e50_device_render.png', (60, 180, 780, 340))
    else:
        print('  （无 output/e50_device_render.png）')

    print('')
    print('=== 本仓：离线预览（当前代码）远山区 ===')
    if os.path.exists('output/e50_upstream_mode.png'):
        report('output/e50_upstream_mode.png', (600, 280, 1200, 380))
    else:
        print('  （无 output/e50_upstream_mode.png）')

    print('')
    print('=== 上游参考图：上部远山带 ===')
    if os.path.exists('output/e50_upstream_ref.png'):
        im = Image.open('output/e50_upstream_ref.png').convert('RGB')
        w, h = im.size
        report('output/e50_upstream_ref.png', (0, 0, int(w * 0.45), int(h * 0.42)))
    else:
        print('  （无 output/e50_upstream_ref.png）')


if __name__ == '__main__':
    main()
