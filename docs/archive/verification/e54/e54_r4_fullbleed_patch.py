# -*- coding: utf-8 -*-
"""第四轮①：画面横向占满全屏（撤销 4:3 居中，横纵各自铺满）。一次落盘 + 逐项 assert。"""
from pathlib import Path

p = Path("app/src/main/java/com/nasmusic/tv/visualizer/arcade/render/ArcadeGlScene.kt")
s = p.read_text(encoding="utf-8")

# ① 类头缩放口径注释
old = """ * ## 缩放口径（§4.6.1）
 * 逻辑坐标恒 **256×240**（与 sim / 上游像素坐标一一对应）；顶点横向 ×**1.25**
 * ⇒ 等效画幅 **320×240 = 4:3 严格成立**；整数倍 `k = floor(屏高 / 240)`
 * （1080p ⇒ k=4 / 1280×960；4K ⇒ k=9 / 2880×2160）。
 * ⛔ 不沿用上游 `MAX_SCALE 4`（那是 GOP 1024×768 的残留）。"""
new = """ * ## 缩放口径（§4.6.1，第四轮修订）
 * 逻辑坐标恒 **256×240**（与 sim / 上游像素坐标一一对应）；顶点横向 ×**1.25** ⇒ 等效画幅 320×240。
 * ⛔ 整数倍 k + 4:3 居中已撤销：所有者 2026-11-10 裁定「地面和背景应该横向占满全屏」
 * ⇒ 横纵各自铺满（`scaleX = viewportW/320`、`scaleY = viewportH/240`），
 * 16:9 电视上精灵横向拉伸 ≈1.33 倍、**无左右黑边**（GL_NEAREST 逐像素取点）。"""
assert s.count(old) == 1, "① 类头注释匹配失败"
s = s.replace(old, new)

# ② 字段
old = """    /** 逻辑 → 屏幕的缩放（整数）与偏移 */
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f"""
new = """    /** 逻辑 → 屏幕的缩放：横纵各自铺满（⛔ 不要恢复 4:3 居中 —— 真机左右黑边被裁定不要） */
    private var scaleX = 1f
    private var scaleY = 1f"""
assert s.count(old) == 1, "② 字段匹配失败"
s = s.replace(old, new)

# ③ onSurfaceChanged
old = """        // 整数倍 k：纵向铺满、横向保持 4:3（等效 320×240）
        val k = maxOf(1, (viewportH / ContraConst.LOGICAL_H.toFloat()).toInt())
        scale = k.toFloat()
        val drawW = 320f * scale
        val drawH = 240f * scale
        offsetX = (viewportW - drawW) * 0.5f
        offsetY = (viewportH - drawH) * 0.5f"""
new = """        // 横纵各自铺满：16:9 电视上不再有左右黑边（第四轮所有者裁定）。
        // 逻辑 256×240，顶点横向 ×1.25 ⇒ 逻辑宽 320；精灵会横向拉伸 ≈1.33 倍。
        scaleX = viewportW / (ContraConst.LOGICAL_W * 1.25f)
        scaleY = viewportH / ContraConst.LOGICAL_H.toFloat()"""
assert s.count(old) == 1, "③ onSurfaceChanged 匹配失败"
s = s.replace(old, new)

# ④ quad() 顶点变换
old = """        val sx = offsetX + x * 1.25f * scale
        val sy = offsetY + y * scale
        val sw = w * 1.25f * scale
        val sh = h * scale"""
new = """        val sx = x * 1.25f * scaleX
        val sy = y * scaleY
        val sw = w * 1.25f * scaleX
        val sh = h * scaleY"""
assert s.count(old) == 1, "④ quad 顶点变换匹配失败"
s = s.replace(old, new)

# 复核：旧字段/居中逻辑不得残留
for gone in ("private var scale =", "private var offsetX", "private var offsetY",
             "val k = maxOf(1, (viewportH", "* 1.25f * scale\n"):
    assert gone not in s, f"残留：{gone!r}"
assert "scaleX = viewportW / (ContraConst.LOGICAL_W * 1.25f)" in s
assert "val sx = x * 1.25f * scaleX" in s
assert s.count("fun flush") == 1 and s.rstrip().endswith("}")

p.write_text(s, encoding="utf-8")
print("OK 画面横纵铺满落盘（4 处替换，无残留）")
