# -*- coding: utf-8 -*-
"""M52 / M46：把 drawContent 内联构造的 Stroke 改成复用实例。"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S = ""
CRLF = False


def load(rel):
    global S, CRLF
    p = os.path.join(ROOT, rel)
    S = io.open(p, "r", encoding="utf-8", newline="").read()
    CRLF = "\r\n" in S
    return p


def rep(a, b, tag):
    global S
    a2 = a.replace("\n", "\r\n") if CRLF else a
    b2 = b.replace("\n", "\r\n") if CRLF else b
    n = S.count(a2)
    if n != 1:
        print("FAIL [%s] count=%d" % (tag, n))
        sys.exit(1)
    S = S.replace(a2, b2)


def save(p):
    io.open(p, "w", encoding="utf-8", newline="").write(S)


# ── M52：CircularRingRenderer ──
p = load("app/src/main/java/com/nasmusic/tv/visualizer/renderers/BasicRenderers.kt")
rep(
    """    override val postFx = PostFx(vignette = 0.44f, grain = 0.028f)
""",
    """    override val postFx = PostFx(vignette = 0.44f, grain = 0.028f)

    /**
     * M52：复用 [Stroke] 实例（`width` 是可写属性）⇒ `drawContent` 真正零分配。
     *
     * \u26d4 不要写成内联 `Stroke(width = …)`：本渲染器每帧要画「外圈细线环 + 节拍爆环 +
     * 中央圆盘亮边」三个描边圆，内联构造就是每帧 3 个短命对象，和本文件反复强调的
     * 逐帧零分配红线直接冲突（GC 会落在渲染线程上）。
     */
    private val ringStroke = Stroke(width = 1.5f)
    private val beatStroke = Stroke(width = 4.5f)
    private val edgeStroke = Stroke(width = 0.5f)
""",
    "ring-strokes",
)
rep(
    """            alpha = 0.30f + frame.pulse * 0.45f,
            style = Stroke(width = 1.5f + frame.pulse * 1.5f)
""",
    """            alpha = 0.30f + frame.pulse * 0.45f,
            style = ringStroke.also { it.width = 1.5f + frame.pulse * 1.5f }
""",
    "ring-use",
)
rep(
    """                alpha = 0.65f, style = Stroke(width = 4.5f))""",
    """                alpha = 0.65f, style = beatStroke)""",
    "beat-use",
)
rep(
    """            alpha = 0.65f + frame.energy * 0.35f,
            style = Stroke(width = 0.5f))""",
    """            alpha = 0.65f + frame.energy * 0.35f,
            style = edgeStroke)""",
    "edge-use",
)
save(p)
print("OK BasicRenderers (M52)")
body = io.open(p, "r", encoding="utf-8", newline="").read()
assert "style = Stroke(" not in body, "still has inline Stroke style"
assert "Stroke(width = …)" in body  # 仅 KDoc 里那一处引用
print("   inline Stroke styles left:", body.count("style = Stroke("))

# ── M46：RadarGridRenderer ──
p = load("app/src/main/java/com/nasmusic/tv/visualizer/renderers/BatchThreeRenderers.kt")
rep(
    """    private val gridBright = VisualizerMath.towardWhite(gridColor, 0.5f)
""",
    """    private val gridBright = VisualizerMath.towardWhite(gridColor, 0.5f)

    /**
     * M46：复用 [Stroke] 实例 ⇒ `drawContent` 真正零分配（本类头注释的既有红线）。
     *
     * \u26d4 同心圆 5 条 + 回波最多 29 条原本都是内联 `Stroke(width = …)`，
     * 合计每帧 34 个短命对象；宽度随 `i` / `peak` 变化不是问题 ——
     * `Stroke.width` 可写，改完立刻用即可（`drawCircle/drawArc` 是同步消费）。
     */
    private val ringStroke = Stroke(width = 1f)
    private val echoStroke = Stroke(width = 2.5f, cap = StrokeCap.Round)
""",
    "radar-strokes",
)
rep(
    """                style = Stroke(if (i == RINGS) 1.8f else 1f), alpha = ringAlpha""",
    """                style = ringStroke.also { it.width = if (i == RINGS) 1.8f else 1f },
                alpha = ringAlpha""",
    "radar-ring-use",
)
rep(
    """                    style = Stroke(width = 2.5f + peak * 3.5f, cap = StrokeCap.Round),""",
    """                    style = echoStroke.also { it.width = 2.5f + peak * 3.5f },""",
    "radar-echo-use",
)
save(p)
print("OK BatchThreeRenderers (M46)")
body = io.open(p, "r", encoding="utf-8", newline="").read()
print("   Stroke( count:", body.count("Stroke("))
assert "Stroke(if (i == RINGS)" not in body
assert "Stroke(width = 2.5f + peak" not in body
print("ALL DONE")
