"""上游参考图 vs 本仓「忠于上游」模式：**并排对比图**。

用法：python scripts/e50_upstream_compare.py [上游参考图.png] [本仓预览.png] [输出.png]
"""
import os
import sys

from PIL import Image, ImageDraw

sys.stdout.reconfigure(encoding='utf-8')

REF = sys.argv[1] if len(sys.argv) > 1 else 'output/e50_upstream_ref.png'
OURS = sys.argv[2] if len(sys.argv) > 2 else 'output/e50_upstream_mode.png'
OUT = sys.argv[3] if len(sys.argv) > 3 else 'output/e50_upstream_compare.png'

W = 1500


def load(path, w):
    im = Image.open(path).convert('RGB')
    h = max(1, int(im.height * w / im.width))
    return im.resize((w, h), Image.LANCZOS)


a = load(REF, W)
b = load(OURS, W)
pad = 26
canvas = Image.new('RGB', (W, a.height + b.height + pad * 3), (255, 255, 255))
d = ImageDraw.Draw(canvas)
d.text((8, 6), '上游 shan-shui-inf 参考图（3000x800）', fill=(40, 40, 40))
canvas.paste(a, (0, pad))
y2 = pad + a.height + pad
d.text((8, y2 - 22), '本仓「忠于上游」模式（1920x1080，绢白纸底 + 上游线描）', fill=(40, 40, 40))
canvas.paste(b, (0, y2))
canvas.save(OUT)
print('对比图已写出:', OUT, canvas.size)
