"""量「远山」区域的像素：上游参考图 vs 我们的设备渲染。

远山 = 上游 distMount：白块（multiply 下 ⇒ 纸色）+ getCol 灰阶刻面 [200,255]。
⇒ 预期：远山区域的**主色 = 纸色**，另有少量**极浅灰**刻面。
"""
import os
import sys
from collections import Counter

from PIL import Image

sys.stdout.reconfigure(encoding='utf-8')


def top_colors(path, box, n=6):
    im = Image.open(path).convert('RGB')
    crop = im.crop(box)
    c = Counter(crop.getdata())
    total = sum(c.values())
    print('  %s  区域%s  (%dx%d)' % (os.path.basename(path), box, crop.width, crop.height))
    for col, cnt in c.most_common(n):
        r, g, b = col
        kind = '纸色' if (r > 225 and g > 215 and b > 190 and r > g > b) else (
            '浅灰' if abs(r - g) <= 6 and abs(g - b) <= 6 else '其它')
        print('    RGB(%3d,%3d,%3d)  %6.2f%%  [%s]' % (r, g, b, 100.0 * cnt / total, kind))
    return total


print('=== 我们的设备渲染（1920x1080）：左上方那片远山 ===')
top_colors('output/e50_device_render.png', (60, 180, 780, 340))

print('')
print('=== 上游参考图（3000x800）：先看远山在哪（上部 0~0.5 高）===')
im = Image.open('output/e50_upstream_ref.png').convert('RGB')
w, h = im.size
print('  上游参考图尺寸: %dx%d' % (w, h))
top_colors('output/e50_upstream_ref.png', (0, 0, int(w * 0.45), int(h * 0.42)), n=6)

print('')
print('=== 上游参考图：找「非纸色的浅灰刻面」占比（远山刻面是 getCol 灰阶）===')
for name, path, box in (
    ('我们-远山区', 'output/e50_device_render.png', (60, 180, 780, 340)),
    ('上游-上部区', 'output/e50_upstream_ref.png', (0, 0, int(w * 0.45), int(h * 0.42))),
):
    im2 = Image.open(path).convert('RGB')
    crop = im2.crop(box)
    c = Counter(crop.getdata())
    total = sum(c.values())
    paperish = 0
    grayfacet = 0
    other = 0
    for col, cnt in c.items():
        r, g, b = col
        if abs(r - g) <= 6 and abs(g - b) <= 6 and 200 <= r <= 252:
            grayfacet += cnt
        elif r > 225 and g > 215 and b > 190 and r > g > b:
            paperish += cnt
        else:
            other += cnt
    print('  %s: 纸色 %.1f%% / 浅灰刻面 %.1f%% / 其它 %.1f%%'
          % (name, 100.0 * paperish / total, 100.0 * grayfacet / total, 100.0 * other / total))
