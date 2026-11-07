import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;

import javax.imageio.ImageIO;

/**
 * 歌词点阵（E53 子模式 0）的**主机端离线渲染器** —— 用来在**没有设备**的情况下判断
 * 「点阵画面长什么样」，并做 OLD / CURRENT / FIXED 三方对照。
 *
 * ⛔ 本脚本**复刻** `LyricsDotMatrixRenderer` 的采样与绘制（不是调用生产代码 ——
 *    生产代码跑在 Android 上，主机没有 Skia）。
 *    ⇒ 复刻口径必须与生产一致，改生产代码时**必须**同步本脚本，否则对照图会说谎。
 *    复刻的三处：
 *    ① `computeFontSize()`（字号规则）—— 两种：OLD（参考行 = 全曲最长行）/ FIXED（参考行 = 本行）；
 *    ② `sampleLine()`（等距分段取点 + 1:1 映射到画布）；
 *    ③ 绘制：OLD = 逐粒子 `addOval` 合成一条 `Path` ⇒ **并集**、每层只混合一次；
 *           CURRENT/FIXED = `Canvas.drawPoints` 合批 ⇒ **每点一次**（`Plus` 会累加 / `SrcOver` 不会）。
 *
 * 用法（仓库根执行，需要 JDK 11+ 的单文件源码模式）：
 * ```
 * java scripts/render_lyrics_dot_matrix_preview.java "Microsoft YaHei"
 * ```
 * 输出 PNG 落在 `logs_temp/`（旧/现/新三张 + 一张并排对照图）。
 *
 * ⚠️ 用 AWT 的 `Microsoft YaHei` 代替 Android 的 `Typeface.DEFAULT_BOLD`：
 *    两者字形度量接近但**不是同一个字体** ⇒ 数字用于**相对比较**，绝对值以真机为准。
 */
public class RenderLyricsDotMatrixPreview {

    // ── 生产常量（与 LyricsDotMatrixRenderer 的 companion 保持一致）──────────
    static final float REF_FONT = 100f;
    static final float MIN_FONT_S = 0.05f;
    static final float MAX_FONT_S = 0.16f;
    static final int CAP_HIGH = 3600;
    static final int VIS_BUCKETS = 16;
    static final float GLOW_FACTOR_TIER1 = 1.4f;
    static final float GLOW_FACTOR_TIER2 = 2.2f;

    // ── 六档颜色（HSL → RGB）与 alpha（与生产 drawPointBatches 一致）────────
    //   顺序：0 暗glow / 1 暗core / 2 中glow / 3 中core / 4 亮glow / 5 亮core
    static final float[] HUE = { 200f, 200f, 200f, 200f, 195f, 195f };
    static final float[] SAT = { 0.30f, 0.25f, 0.75f, 0.85f, 1.0f, 1.0f };
    static final float[] LIGHT = { 0.70f, 0.65f, 0.70f, 0.65f, 0.75f, 0.70f };
    static final float[] ALPHA = { 0.10f, 0.45f, 0.22f, 0.60f, 0.26f + 0.10f, 0.62f + 0.14f };

    static final int BG_R = 13, BG_G = 18, BG_B = 28;

    static Font BASE;
    static final FontRenderContext FRC = new FontRenderContext(null, false, false);

    static Font fontAt(float size) { return BASE.deriveFont(Font.BOLD, size); }
    static float advance(Font f, String s) { return (float) f.getStringBounds(s, FRC).getWidth(); }

    static final class Metrics {
        final int ascent, descent;
        Metrics(int a, int d) { ascent = a; descent = d; }
        int lineHeight() { return ascent + descent; }
    }

    static Metrics metricsAt(float size) {
        LineMetrics lm = fontAt(size).getLineMetrics("中Ay", FRC);
        return new Metrics(Math.round(lm.getAscent()), Math.round(lm.getDescent()));
    }

    // ══════════════════════ ① 字号规则 ══════════════════════

    /**
     * 生产 `computeFontSize()` 的复刻。
     *
     * @param reference 参考行文本：OLD = 全曲**最长**行；FIXED = **本行**。
     */
    static float computeFontSize(String reference, int w, int h, int capRatioCap) {
        Metrics m = metricsAt(REF_FONT);
        int refLineHeight = m.lineHeight();
        float refGlyphAdvance = (reference == null || reference.isEmpty())
                ? REF_FONT : Math.max(advance(fontAt(REF_FONT), reference), REF_FONT * 0.1f);
        float fontFromWidth = REF_FONT * (w * 0.80f) / refGlyphAdvance;
        float fontFromHeight = REF_FONT * (h * 0.40f) / refLineHeight;
        float base = Math.min(fontFromWidth, fontFromHeight);
        float lo = h * MIN_FONT_S, hi = h * fontMaxRatio(capRatioCap);
        if (lo > hi) throw new IllegalArgumentException("coerceIn 越界");
        return Math.min(Math.max(base, lo), hi);
    }

    static float fontMaxRatio(int cap) {
        return MAX_FONT_S * (float) Math.sqrt((float) cap / (float) CAP_HIGH);
    }

    // ══════════════════════ ② 采样（复刻 sampleLine）══════════════════════

    static final class Particle {
        float x, y;      // 画布坐标
        int bmpX, bmpY;  // 位图坐标（覆盖率统计用）
        int charIdx;
        float localX;
        float size;
        int tier;
    }

    static final class Sample {
        int n;
        Particle[] particles;
        boolean[] ink;      // 位图上的墨迹（bmpW*bmpH）
        int bmpW, bmpH;
        float fontSize;
        String text;
        int inkPixels;
    }

    /** 卡拉OK 亮度（复刻 brightnessOf；energy=0、稳态、无逐字时间戳） */
    static float brightnessOf(float localX, float kProgress, boolean singing) {
        if (!singing) return 0.25f;
        float dist = localX - kProgress;
        if (dist < -0.02f) return 0.78f;
        if (dist <= 0.02f) return 1.0f;
        return 0.18f;
    }

    static int tierOf(float brightness, float visibility) {
        if (brightness * visibility > 0.88f) return 2;
        if (brightness * visibility > 0.45f) return 1;
        return 0;
    }

    /**
     * 复刻 `sampleLine()`：位图渲染 → 逐行 `getPixels` → 等距分段取点 → 1:1 映射到画布。
     */
    static Sample sample(String text, int w, int h, float fontSize, int cap, float kProgress) {
        Font f = fontAt(fontSize);
        Metrics fm = metricsAt(fontSize);
        float maxLineWidth = w * 0.88f;
        String displayText;
        if (advance(f, text) <= maxLineWidth) {
            displayText = text;
        } else {
            String trimmed = text;
            while (!trimmed.isEmpty() && advance(f, trimmed + "…") > maxLineWidth) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            displayText = trimmed + "…";
        }
        float textWidth = advance(f, displayText);
        int fontHeight = fm.lineHeight();
        int bmpH = Math.max((int) (fontHeight + fontSize * 0.4f), 40);
        int bmpW = Math.max((int) (textWidth * 1.15f), 100);
        float xOff = (bmpW - textWidth) / 2f;

        BufferedImage img = new BufferedImage(bmpW, bmpH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setColor(Color.WHITE);
        g.setFont(f);
        g.drawString(displayText, xOff, fm.ascent);
        g.dispose();

        boolean[] ink = new boolean[bmpW * bmpH];
        int[] rowPixelCount = new int[bmpH];
        int totalPixels = 0;
        for (int y = 0; y < bmpH; y++) {
            int cnt = 0;
            for (int x = 0; x < bmpW; x++) {
                if ((img.getRGB(x, y) & 0xFF000000) != 0) { ink[y * bmpW + x] = true; cnt++; }
            }
            rowPixelCount[y] = cnt;
            totalPixels += cnt;
        }

        // 配额（复刻 calculateRowQuota）
        int[] quota = new int[bmpH];
        if (totalPixels > 0 && cap > 0) {
            for (int ri = 0; ri < bmpH; ri++) {
                int cnt = rowPixelCount[ri];
                if (cnt <= 0) continue;
                quota[ri] = (int) Math.max(1L, (long) cnt * cap / totalPixels);
            }
        }

        // 逐字 x 边界（复刻 charXBounds）
        int[] charXBounds = new int[displayText.length() + 1];
        charXBounds[0] = (int) xOff;
        for (int i = 0; i < displayText.length(); i++) {
            charXBounds[i + 1] = (int) (xOff + advance(f, displayText.substring(0, i + 1)));
        }

        ArrayList<Particle> pts = new ArrayList<>();
        int curRowFill = 0;
        for (int ri = 0; ri < bmpH; ri++) {
            int y = ri;
            int rowInk = rowPixelCount[ri];
            if (rowInk == 0) continue;
            int targetCount = quota[ri];
            if (targetCount <= 0) continue;
            int inkIdx = 0, filled = 0;
            for (int x = 0; x < bmpW; x++) {
                if (curRowFill >= cap) break;
                if (!ink[y * bmpW + x]) continue;
                boolean take = isSegmentStart(inkIdx, rowInk, targetCount);
                inkIdx++;
                if (!take) continue;
                int ci = 0;
                while (ci < displayText.length() && x >= charXBounds[ci + 1]) ci++;
                Particle p = new Particle();
                p.charIdx = Math.min(ci, displayText.length() - 1);
                p.localX = Math.min(Math.max((x - xOff) / Math.max(textWidth, 1f), 0f), 1f);
                pts.add(p);
                curRowFill++;
                filled++;
                if (filled >= targetCount) break;
            }
            if (curRowFill >= cap) break;
        }

        // 映射到画布（复刻 sampleLine 尾段）：以**墨迹**中心对齐 targetY
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < pts.size(); i++) {
            // 取点位置即墨迹像素位置（上面已按 x 记录）
        }
        // 重新扫描一遍以拿到取点的 (x,y)
        // —— 上面的循环没存 x/y，这里补一次（等距分段取点是确定性的）
        ArrayList<int[]> xy = new ArrayList<>();
        curRowFill = 0;
        for (int ri = 0; ri < bmpH; ri++) {
            int y = ri;
            int rowInk = rowPixelCount[ri];
            if (rowInk == 0) continue;
            int targetCount = quota[ri];
            if (targetCount <= 0) continue;
            int inkIdx = 0, filled = 0;
            for (int x = 0; x < bmpW; x++) {
                if (curRowFill >= cap) break;
                if (!ink[y * bmpW + x]) continue;
                boolean take = isSegmentStart(inkIdx, rowInk, targetCount);
                inkIdx++;
                if (!take) continue;
                xy.add(new int[]{x, y});
                curRowFill++;
                filled++;
                if (filled >= targetCount) break;
            }
            if (curRowFill >= cap) break;
        }
        for (int i = 0; i < xy.size(); i++) {
            minX = Math.min(minX, xy.get(i)[0]);
            maxX = Math.max(maxX, xy.get(i)[0]);
            minY = Math.min(minY, xy.get(i)[1]);
            maxY = Math.max(maxY, xy.get(i)[1]);
        }
        float xCenter = (minX + maxX) / 2f;
        float yCenter = (minY + maxY) / 2f;
        float targetY = h * 0.40f;   // 上行（正在唱）
        for (int i = 0; i < pts.size(); i++) {
            Particle p = pts.get(i);
            p.x = w / 2f + (xy.get(i)[0] - xCenter);
            p.y = targetY + (xy.get(i)[1] - yCenter);
            float brightness = brightnessOf(p.localX, kProgress, true);
            float visibility = 1f;
            // size（复刻 measureLine）：breath=1、bassPulse=1
            p.size = (0.85f + brightness * 0.4f) * visibility;
            p.tier = tierOf(brightness, visibility);
        }

        Sample s = new Sample();
        s.n = pts.size();
        s.particles = pts.toArray(new Particle[0]);
        s.ink = ink;
        s.bmpW = bmpW; s.bmpH = bmpH;
        s.fontSize = fontSize;
        s.text = displayText;
        s.inkPixels = totalPixels;
        return s;
    }

    /** 复刻 `isSegmentStart()` */
    static boolean isSegmentStart(int inkIdx, int rowInk, int targetCount) {
        if (rowInk <= 0 || targetCount <= 0) return false;
        if (inkIdx < 0 || inkIdx >= rowInk) return false;
        int chunk = (int) (((long) inkIdx * targetCount) / rowInk);
        int prevChunk = (inkIdx == 0) ? -1 : (int) (((long) (inkIdx - 1) * targetCount) / rowInk);
        return chunk != prevChunk;
    }

    // ══════════════════════ ③ 绘制 ══════════════════════

    /** 画布（RGB int[]），支持 Plus（加色）与 SrcOver 两种混合 */
    static final class Canvas {
        final int w, h;
        final int[] px;
        Canvas(int w, int h) { this.w = w; this.h = h; this.px = new int[w * h]; }

        void fill(int r, int g, int b) {
            for (int i = 0; i < px.length; i++) px[i] = (r << 16) | (g << 8) | b;
        }

        /** 一个圆盘（覆盖率 0..1），按混合模式合成 */
        void disc(float cx, float cy, float radius, int r, int g, int b, float alpha, boolean plus) {
            int x0 = (int) Math.floor(cx - radius - 1), x1 = (int) Math.ceil(cx + radius + 1);
            int y0 = (int) Math.floor(cy - radius - 1), y1 = (int) Math.ceil(cy + radius + 1);
            if (x0 < 0) x0 = 0; if (y0 < 0) y0 = 0;
            if (x1 >= w) x1 = w - 1; if (y1 >= h) y1 = h - 1;
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    float dx = x + 0.5f - cx, dy = y + 0.5f - cy;
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    float cov = radius + 0.5f - d;              // 1px 宽的线性过渡
                    if (cov <= 0) continue;
                    if (cov > 1) cov = 1;
                    float a = alpha * cov;
                    int i = y * w + x;
                    if (plus) {
                        int dr = (px[i] >> 16) & 0xFF, dg = (px[i] >> 8) & 0xFF, db = px[i] & 0xFF;
                        dr = Math.min(255, dr + Math.round(r * a));
                        dg = Math.min(255, dg + Math.round(g * a));
                        db = Math.min(255, db + Math.round(b * a));
                        px[i] = (dr << 16) | (dg << 8) | db;
                    } else {
                        int dr = (px[i] >> 16) & 0xFF, dg = (px[i] >> 8) & 0xFF, db = px[i] & 0xFF;
                        dr = Math.round(r * a + dr * (1 - a));
                        dg = Math.round(g * a + dg * (1 - a));
                        db = Math.round(b * a + db * (1 - a));
                        px[i] = (dr << 16) | (dg << 8) | db;
                    }
                }
            }
        }
    }

    static int[] hslToRgb(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s;
        float hp = (h % 360) / 60f;
        float x = c * (1 - Math.abs(hp % 2 - 1));
        float r1, g1, b1;
        if (hp < 1) { r1 = c; g1 = x; b1 = 0; }
        else if (hp < 2) { r1 = x; g1 = c; b1 = 0; }
        else if (hp < 3) { r1 = 0; g1 = c; b1 = x; }
        else if (hp < 4) { r1 = 0; g1 = x; b1 = c; }
        else if (hp < 5) { r1 = x; g1 = 0; b1 = c; }
        else { r1 = c; g1 = 0; b1 = x; }
        float m = l - c / 2;
        int r = Math.round(Math.min(255, Math.max(0, (r1 + m) * 255)));
        int g = Math.round(Math.min(255, Math.max(0, (g1 + m) * 255)));
        int b = Math.round(Math.min(255, Math.max(0, (b1 + m) * 255)));
        return new int[]{r, g, b};
    }

    /**
     * OLD 绘制：逐粒子 `addOval` → **并集**成一条 path ⇒ 每层只混合一次。
     * 圆心 = (px - size, py - size)、半径 = 2 * size（与旧码逐像素一致）。
     */
    static void drawOld(Canvas cv, Sample s, boolean plus) {
        // 每层一张覆盖率图（并集）：层索引 = tier*2 + layer
        float[][] cov = new float[6][];
        for (int i = 0; i < 6; i++) cov[i] = new float[cv.w * cv.h];
        for (Particle p : s.particles) {
            float coreR = 2f * p.size;
            float cx = p.x - p.size, cy = p.y - p.size;
            stampUnion(cov[p.tier * 2], cv.w, cv.h, cx, cy, coreR);
            if (p.tier >= 1) {
                float glowR = p.size * (p.tier == 2 ? GLOW_FACTOR_TIER2 : GLOW_FACTOR_TIER1);
                stampUnion(cov[p.tier * 2 + 1], cv.w, cv.h, cx, cy, 2f * glowR);
            }
        }
        // 合成顺序：旧码是 暗glow→暗core→中glow→中core→亮glow→亮core
        for (int layerIdx = 0; layerIdx < 6; layerIdx++) {
            int[] rgb = hslToRgb(HUE[layerIdx], SAT[layerIdx], LIGHT[layerIdx]);
            float a = ALPHA[layerIdx];
            float[] c = cov[layerIdx];
            for (int i = 0; i < c.length; i++) {
                float k = c[i];
                if (k <= 0.0005f) continue;
                float alpha = a * k;
                int dr = (cv.px[i] >> 16) & 0xFF, dg = (cv.px[i] >> 8) & 0xFF, db = cv.px[i] & 0xFF;
                if (plus) {
                    dr = Math.min(255, dr + Math.round(rgb[0] * alpha));
                    dg = Math.min(255, dg + Math.round(rgb[1] * alpha));
                    db = Math.min(255, db + Math.round(rgb[2] * alpha));
                } else {
                    dr = Math.round(rgb[0] * alpha + dr * (1 - alpha));
                    dg = Math.round(rgb[1] * alpha + dg * (1 - alpha));
                    db = Math.round(rgb[2] * alpha + db * (1 - alpha));
                }
                cv.px[i] = (dr << 16) | (dg << 8) | db;
            }
        }
    }

    /** 把一个圆盘并进覆盖率图（取 max） */
    static void stampUnion(float[] cov, int w, int h, float cx, float cy, float radius) {
        int x0 = (int) Math.floor(cx - radius - 1), x1 = (int) Math.ceil(cx + radius + 1);
        int y0 = (int) Math.floor(cy - radius - 1), y1 = (int) Math.ceil(cy + radius + 1);
        if (x0 < 0) x0 = 0; if (y0 < 0) y0 = 0;
        if (x1 >= w) x1 = w - 1; if (y1 >= h) y1 = h - 1;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float dx = x + 0.5f - cx, dy = y + 0.5f - cy;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                float v = radius + 0.5f - d;
                if (v <= 0) continue;
                if (v > 1) v = 1;
                int i = y * w + x;
                if (v > cov[i]) cov[i] = v;
            }
        }
    }

    /**
     * CURRENT / FIXED 绘制：`drawPoints` 合批 ⇒ **每点一次**合成（Plus 会累加）。
     * 分桶 = (tier, layer, 可见度档)；笔宽 = 该桶的**代表半径** × 4（= 直径）。
     */
    static void drawBatched(Canvas cv, Sample s, boolean plus, boolean perPointUnion) {
        // 可见度恒为 1 ⇒ 全部落在最后一档（桶 15）
        // 统计每桶的代表半径（= 批内 size 均值）
        for (int tier = 0; tier < 3; tier++) {
            for (int layer = 0; layer < 2; layer++) {
                if (layer == 1 && tier == 0) continue;  // 暗档没有外辉光
                float sum = 0; int cnt = 0;
                for (Particle p : s.particles) {
                    if (p.tier != tier) continue;
                    if (layer == 1 && p.tier < 1) continue;
                    sum += p.size; cnt++;
                }
                if (cnt == 0) continue;
                float radius = sum / cnt;                  // 代表半径
                float r = (layer == 0) ? radius
                        : radius * (tier == 2 ? GLOW_FACTOR_TIER2 : GLOW_FACTOR_TIER1);
                int[] rgb = hslToRgb(HUE[tier * 2 + layer], SAT[tier * 2 + layer], LIGHT[tier * 2 + layer]);
                float alpha = ALPHA[tier * 2 + layer];
                // 批内每个点一次合成（strokeWidth = 直径 ⇒ Cap.ROUND 圆盘，半径 = r）
                for (Particle p : s.particles) {
                    if (p.tier != tier) continue;
                    if (layer == 1 && p.tier < 1) continue;
                    float cx = p.x - radius, cy = p.y - radius;
                    cv.disc(cx, cy, r, rgb[0], rgb[1], rgb[2], alpha, plus);
                }
            }
        }
    }

    // ══════════════════════ 指标 ══════════════════════

    /** 笔画覆盖率：墨迹像素里落在某个粒子圆盘内的比例 */
    static double coverage(Sample s) {
        boolean[] cov = new boolean[s.bmpW * s.bmpH];
        for (Particle p : s.particles) {
            float R = 2f * p.size;
            int cx = Math.round(p.x - p.size), cy = Math.round(p.y - p.size);
            int r = (int) Math.ceil(R);
            for (int dy = -r; dy <= r; dy++) {
                int yy = cy + dy; if (yy < 0 || yy >= s.bmpH) continue;
                for (int dx = -r; dx <= r; dx++) {
                    int xx = cx + dx; if (xx < 0 || xx >= s.bmpW) continue;
                    if (dx * dx + dy * dy <= R * R) cov[yy * s.bmpW + xx] = true;
                }
            }
        }
        int inkCov = 0;
        for (int i = 0; i < s.ink.length; i++) if (s.ink[i] && cov[i]) inkCov++;
        return s.inkPixels == 0 ? 0 : (100.0 * inkCov / s.inkPixels);
    }

    /** 文本墨迹在画布上的包围盒 + 墨迹覆盖率（画布上） */
    static int[] inkBBox(Canvas cv, int thr) {
        int minX = Integer.MAX_VALUE, maxX = -1, minY = Integer.MAX_VALUE, maxY = -1, n = 0;
        for (int y = 0; y < cv.h; y++) {
            for (int x = 0; x < cv.w; x++) {
                int i = y * cv.w + x;
                int r = (cv.px[i] >> 16) & 0xFF, g = (cv.px[i] >> 8) & 0xFF, b = cv.px[i] & 0xFF;
                if (r + g + b > thr) {
                    n++;
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return new int[]{0, 0, 0, 0, 0};
        return new int[]{minX, maxX, minY, maxY, n};
    }

    static BufferedImage toImage(Canvas cv) {
        BufferedImage img = new BufferedImage(cv.w, cv.h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, cv.w, cv.h, cv.px, 0, cv.w);
        return img;
    }

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "Microsoft YaHei";
        boolean timelineOnly = args.length > 1 && args[1].contains("timeline");
        BASE = new Font(spec, Font.BOLD, 100);
        System.out.println("字体: " + BASE.getFontName());

        new File("logs_temp").mkdirs();

        if (!timelineOnly) {
            runStillImages();
        }
        runTimelineSimulation();
    }

    /** 旧的 still-image 对照（OLD / CURRENT / FIXED 三张 PNG + 并排图） */
    static void runStillImages() throws Exception {
        final int W = 2712, H = 1220;                 // 小米 22081212C 横屏
        final int CAP = 7200;                          // E53 子模式 0（中/高档 ×2）
        final String LINE = "他等过你 已来不及";          // 屏上正在唱的那一行
        final String REF_OLD = "你笑我太疯癫 我爱你太真";  // 旧截图那首歌的最长行（≈12 字）
        final String REF_NEW = "他等过你 已来不及我等过你好久好久不见你你就这样远走";  // 新截图那首歌的最长行（≈30 字）
        final float K = 0.40f;

        System.out.println();
        System.out.println("=== 字号规则 ===");
        float fontOld = computeFontSize(REF_OLD, W, H, CAP_HIGH);
        float fontCur = computeFontSize(REF_NEW, W, H, CAP_HIGH);
        float fontFix = computeFontSize(LINE, W, H, CAP_HIGH);
        System.out.printf("旧（参考=全曲最长行 %d 字）⇒ font=%.1f%n", REF_OLD.length(), fontOld);
        System.out.printf("现（参考=全曲最长行 %d 字）⇒ font=%.1f  ← 被压到可读性下限%n", REF_NEW.length(), fontCur);
        System.out.printf("新（参考=本行 %d 字）      ⇒ font=%.1f%n", LINE.length(), fontFix);

        // ── 三张对照图 ──────────────────────────────────────
        Canvas cvOld = new Canvas(W, H); cvOld.fill(BG_R, BG_G, BG_B);
        Sample sOld = sample(LINE, W, H, fontOld, CAP, K);
        drawOld(cvOld, sOld, true);                    // 旧码：Path 并集 + Plus

        Canvas cvCur = new Canvas(W, H); cvCur.fill(BG_R, BG_G, BG_B);
        Sample sCur = sample(LINE, W, H, fontCur, CAP, K);
        drawBatched(cvCur, sCur, true, false);         // 现码：drawPoints 合批 + Plus

        Canvas cvFix = new Canvas(W, H); cvFix.fill(BG_R, BG_G, BG_B);
        Sample sFix = sample(LINE, W, H, fontFix, CAP, K);
        drawBatched(cvFix, sFix, false, false);        // 新码：drawPoints 合批 + SrcOver

        ImageIO.write(toImage(cvOld), "png", new File("logs_temp/dotmatrix_OLD.png"));
        ImageIO.write(toImage(cvCur), "png", new File("logs_temp/dotmatrix_CURRENT.png"));
        ImageIO.write(toImage(cvFix), "png", new File("logs_temp/dotmatrix_FIXED.png"));

        System.out.println();
        System.out.println("=== 画面指标 ===");
        report("OLD   (Path 并集 + Plus)", sOld, cvOld, fontOld);
        report("CURRENT (drawPoints + Plus)", sCur, cvCur, fontCur);
        report("FIXED  (drawPoints + SrcOver)", sFix, cvFix, fontFix);

        // ── 填充率成本量：光栅化面积（与**字号无关**，见 [rasterAreaPerFrame]）────
        System.out.println();
        System.out.println("=== 填充率成本量（每帧光栅化面积；点的半径**不随字号涨**）===");
        System.out.printf("%-28s 字号=%6.1f  每行点数=%5d  代表半径=%.2f px  每帧面积=%9.0f px  @120fps=%6.1f Mpx/s%n",
                "OLD (参考=全曲最长行)", fontOld, sOld.n, meanRadius(sOld),
                rasterAreaPerFrame(sOld, 2), rasterAreaPerFrame(sOld, 2) * 120 / 1e6);
        System.out.printf("%-28s 字号=%6.1f  每行点数=%5d  代表半径=%.2f px  每帧面积=%9.0f px  @120fps=%6.1f Mpx/s%n",
                "CURRENT (参考=全曲最长行)", fontCur, sCur.n, meanRadius(sCur),
                rasterAreaPerFrame(sCur, 2), rasterAreaPerFrame(sCur, 2) * 120 / 1e6);
        System.out.printf("%-28s 字号=%6.1f  每行点数=%5d  代表半径=%.2f px  每帧面积=%9.0f px  @120fps=%6.1f Mpx/s%n",
                "FIXED (参考=本行)", fontFix, sFix.n, meanRadius(sFix),
                rasterAreaPerFrame(sFix, 2), rasterAreaPerFrame(sFix, 2) * 120 / 1e6);
        System.out.println("⇒ 字号 85.8 → 195.2（2.27×）时，**每帧光栅化面积几乎不变**（点数与半径都没变）");
        System.out.println("  ⇒ 「字号变大 ⇒ 每个点的面积 ×5.2 ⇒ 填充率 ×5」这条**不成立**（半径与字号无关）；");
        System.out.println("  ⇒ 覆盖率的变化只来自**点的分布**（见 measure_lyrics_dot_matrix_density.java 的 34.5% / 62.8%）。");

        // ── 并排对照图（裁到文本行附近，纵向拼接）──────────────
        makeSideBySide(cvOld, cvCur, cvFix, "logs_temp/dotmatrix_compare.png");
    }

    static void report(String label, Sample s, Canvas cv, float font) {
        int[] bb = inkBBox(cv, 330);
        System.out.printf("%-28s font=%6.1f 粒子=%5d 笔画覆盖率=%5.1f%%  屏上墨迹 x[%d..%d] w=%d (%.1f%%) y[%d..%d] h=%d%n",
                label, font, s.n, coverage(s),
                bb[0], bb[1], bb[1] - bb[0] + 1, 100.0 * (bb[1] - bb[0] + 1) / cv.w,
                bb[2], bb[3], bb[3] - bb[2] + 1);
    }

    /**
     * 一帧的**光栅化面积**估算（= 填充率的成本量）。
     *
     * ⛔⛔ **点的半径与字号无关**（生产代码：`size = breath² · bassPulse · (0.85+brightness·0.4) · visibility`
     *    —— **没有任何字号项**；`drawPointBatches` 里 `strokeWidth = radius · 4` ⇒ 圆盘半径 = 2·size ≈ 2 px）。
     * ⇒ 字号变大只改变**点的分布**，**不改变每个点的面积** ⇒ 光栅化面积不变。
     *
     * 本函数把「每帧要混合的像素数」算出来，用于把「填充率」与「滞后」两条线索**分开量化**。
     *
     * @param s        一行的采样结果
     * @param lineRows 屏上同时存在的行数（2 行）
     */
    static double rasterAreaPerFrame(Sample s, int lineRows) {
        double area = 0;
        for (Particle p : s.particles) {
            float size = p.size;
            // core：`strokeWidth = radius·4` ⇒ 半径 = 2·size
            area += Math.PI * (2.0 * size) * (2.0 * size);
            if (p.tier >= 1) {
                // glow：半径 × 1.4（中档）/ 2.2（亮档）
                float f = (p.tier == 2) ? GLOW_FACTOR_TIER2 : GLOW_FACTOR_TIER1;
                area += Math.PI * (2.0 * size * f) * (2.0 * size * f);
            }
        }
        return area * lineRows;
    }

    /** 一行的代表半径（= 批内 size 均值；生产代码里就是 `strokeWidth/4`） */
    static float meanRadius(Sample s) {
        if (s.n == 0) return 0f;
        float sum = 0f;
        for (Particle p : s.particles) sum += p.size;
        return sum / s.n;
    }

    /** 三张竖排拼接（各自裁到中间那行文字附近），便于一眼比对 */
    static void makeSideBySide(Canvas a, Canvas b, Canvas c, String path) throws Exception {
        int y0 = (int) (a.h * 0.40f) - 220, y1 = (int) (a.h * 0.40f) + 220;
        int cropH = y1 - y0;
        BufferedImage out = new BufferedImage(a.w, cropH * 3 + 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        Canvas[] all = {a, b, c};
        for (int i = 0; i < 3; i++) {
            BufferedImage img = toImage(all[i]);
            g.drawImage(img, 0, i * (cropH + 20), a.w, cropH, 0, y0, a.w, y1, null);
        }
        g.dispose();
        ImageIO.write(out, "png", new File(path));
    }

    // ══════════════════════════════════════════════════════════════
    // ④ 时间线仿真（2026-11-07：歌词跟不上速度）
    // ══════════════════════════════════════════════════════════════
    //
    // 复刻 `LyricsDotMatrixRenderer` 的**状态机**（两版时序），喂一条合成歌词时间线，
    // 逐帧统计：
    //   ① 每个行边界的「应顶替时刻（歌词时间线）」与「状态机实际顶替时刻」⇒ 延迟；
    //   ② **同时存活的点数**（旧行 + 成形行 + 顶替行）—— 填充率的成本量；
    //   ③ **非空子批次数**（= 每帧 `drawPoints` 的提交数）—— `strokeWidth` 是整批的，
    //      可见度铺满 16 档 ⇒ 提交数从十几次涨到上百次 ⇒ **帧成本的真凶**。
    //
    // ⛔ 本脚本**复刻**生产逻辑（不是调用生产代码 —— 生产代码跑在 Android 上）。
    //    改生产代码时**必须**同步本脚本，否则对照会说谎。

    // ── 仿真参数（与生产常量一致）────────────────────────────────
    static final int SIM_CAP = 7200;          // E53 子模式 0（中/高档 ×2）
    static final long SIM_FRAME_MS = 8;       // 120 Hz ≈ 8.33 ms（取整）
    static final long SIM_TICK_MS = 1000;     // `ctx.lyricLineIndex` 的 1 Hz 更新
    static final long OLD_SCATTER_PER_CHAR = 90;
    static final long OLD_SCATTER_FLY = 600;
    static final long OLD_COALESCE_PER_CHAR = 160;
    static final long OLD_COALESCE_ARRIVE = 1200;
    static final long OLD_SHIFT_MS = 700;
    static final long INIT_COALESCE_MS = 1200;
    static final float COALESCE_WINDOW_FRACTION = 0.75f;
    static final long MIN_COALESCE_WINDOW_MS = 600;
    static final long MAX_COALESCE_WINDOW_MS = 8000;
    static final long MIN_COALESCE_TOTAL_MS = 300;
    static final long MIN_SLIDE_MS = 300;
    static final long MAX_SLIDE_MS = 700;
    static final long DEFAULT_SLIDE_MS = 700;

    /** 生产 `coalesceTotalFor()` 的复刻 */
    static long coalesceTotalFor(int chars, long windowMs) {
        long fixed = (long) (chars - 1) * OLD_COALESCE_PER_CHAR + OLD_COALESCE_ARRIVE;
        long win = Math.min(Math.max(windowMs, MIN_COALESCE_WINDOW_MS), MAX_COALESCE_WINDOW_MS);
        long capped = (long) (win * COALESCE_WINDOW_FRACTION);
        return Math.max(Math.min(fixed, capped), MIN_COALESCE_TOTAL_MS);
    }

    /** 生产 `coalesceArriveFor()` 的复刻 */
    static long coalesceArriveFor(long totalMs) {
        return Math.min(OLD_COALESCE_ARRIVE, Math.max(totalMs / 2, 1));
    }

    /** 生产 `coalescePerCharFor()` 的复刻 */
    static long coalescePerCharFor(int chars, long totalMs, long arriveMs) {
        if (chars <= 1) return 0;
        return Math.max((totalMs - arriveMs) / (chars - 1), 0);
    }

    /** 生产 `scatterFlyFor()` 的复刻 */
    static long scatterFlyFor(long windowMs) {
        return Math.max((long) (windowMs * 0.45f), 80L);
    }

    /** 生产 `scatterPerCharFor()` 的复刻 */
    static long scatterPerCharFor(int chars, long windowMs, long flyMs) {
        if (chars <= 1) return 0;
        return Math.max((windowMs - flyMs) / (chars - 1), 0);
    }

    /** 生产 `slideMsFor()` 的复刻 */
    static long slideMsFor(long lineDurationMs) {
        if (lineDurationMs <= 0) return DEFAULT_SLIDE_MS;
        return Math.min(Math.max(lineDurationMs / 5, MIN_SLIDE_MS), MAX_SLIDE_MS);
    }

    /**
     * **旧时序**的凝聚总时长（固定公式，与歌词时间线无关）。
     *
     * ⛔ 这就是 2026-11-07 之前的口径：`160·字数 + 1200 ms`
     *    ⇒ 20+ 字的长句需要 4.4 s，而一句歌词只有 3.5 s
     *    ⇒ **永远聚不完** ⇒ 下行永远是「半散」的
     *    ⇒ 可见度铺满 16 档 ⇒ 每帧的 `drawPoints` 提交数居高不下。
     */
    static long oldCoalesceTotal(int chars) {
        return (long) (Math.max(1, chars) - 1) * OLD_COALESCE_PER_CHAR + OLD_COALESCE_ARRIVE;
    }

    /** 合成歌词的一行 */
    static final class SimLine {
        final String text;
        final long startMs, endMs;
        final int chars;
        SimLine(String text, long startMs, long endMs) {
            this.text = text;
            this.startMs = startMs;
            this.endMs = endMs;
            this.chars = Math.max(1, text.replace(" ", "").length());
        }
    }

    /** 某一帧里一行的状态 */
    static final class RowState {
        int chars = 1;
        /** 完全成形（可见度恒 1） */
        boolean solid = true;
        /** 凝聚中：自采样起经过的时间 / 逐字延迟 / 单字到达时长 */
        long coalesceElapsed, coalescePerChar, coalesceArrive;
        /** 消散中：自消散起经过的时间 / 逐字延迟 / 飞散时长 */
        long scatterElapsed, scatterPerChar, scatterFly;
        /** 是否在唱（决定亮度档数 ⇒ 子批次数：5 个 vs 1 个） */
        boolean singing;

        boolean scattering() { return scatterFly > 0; }
        boolean coalescing() { return !solid && !scattering(); }
    }

    /** 一帧的统计量 */
    static final class FrameStat {
        double aliveDots;      // 存活点数（按可见度加权）
        int subBatches;        // 非空子批次数（= drawPoints 提交数）
    }

    /** 一行的存活点数（按可见度加权） */
    static double aliveOf(RowState r) {
        if (r.chars <= 0) return 0;
        if (r.solid) return SIM_CAP;
        double per = (double) SIM_CAP / r.chars;
        double sum = 0;
        if (r.scattering()) {
            for (int ci = 0; ci < r.chars; ci++) {
                long e = r.scatterElapsed - (long) ci * r.scatterPerChar;
                if (e <= 0) { sum += per; continue; }         // 还没开始消散 ⇒ 仍可见
                float v = 1f - (float) e / r.scatterFly;
                if (v <= 0.01f) continue;
                sum += per * v;
            }
        } else {
            for (int ci = 0; ci < r.chars; ci++) {
                long e = r.coalesceElapsed - (long) ci * r.coalescePerChar;
                if (e <= 0) continue;                          // 还没开始凝聚 ⇒ 不可见
                float v = Math.min(1f, (float) e / r.coalesceArrive);
                if (v <= 0.01f) continue;
                sum += per * v;
            }
        }
        return sum;
    }

    /** 一行的非空子批次数（= drawPoints 提交数） */
    static int subBatchesOf(RowState r) {
        if (r.chars <= 0) return 0;
        if (r.solid) {
            // 成形行：全部落在最后一个可见度档 ⇒ 每（档 × 层）一个子批次
            //   在唱：3 档（暗/中/亮）×（core + 亮/中的 glow）= 5
            //   不在唱：只有暗档 core = 1
            return r.singing ? 5 : 1;
        }
        // 散开行：可见度铺在多个档上 ⇒ 每个非空档一个子批次（暗档只有 core）
        boolean[] bucket = new boolean[VIS_BUCKETS];
        if (r.scattering()) {
            for (int ci = 0; ci < r.chars; ci++) {
                long e = r.scatterElapsed - (long) ci * r.scatterPerChar;
                float v = e <= 0 ? 1f : 1f - (float) e / r.scatterFly;
                if (v <= 0.01f) continue;
                bucket[visibilityBucketOf(v)] = true;
            }
        } else {
            for (int ci = 0; ci < r.chars; ci++) {
                long e = r.coalesceElapsed - (long) ci * r.coalescePerChar;
                if (e <= 0) continue;
                float v = Math.min(1f, (float) e / r.coalesceArrive);
                if (v <= 0.01f) continue;
                bucket[visibilityBucketOf(v)] = true;
            }
        }
        int n = 0;
        for (boolean b : bucket) if (b) n++;
        return n;
    }

    /** 生产 `visibilityBucketOf()` 的复刻 */
    static int visibilityBucketOf(float visibility) {
        int b = (int) (visibility * VIS_BUCKETS);
        return Math.min(Math.max(b, 0), VIS_BUCKETS - 1);
    }

    /** 合成歌词表：6 句，每句 3.5 s（逐字时间按字数均分） */
    static SimLine[] buildSimLyrics() {
        String[] texts = {
            "他等过你 已来不及",
            "我等过你好久好久不见你",
            "就这样远走 留下我一个人",
            "风吹过 花开又落",
            "你说的话 我都记得",
            "那么多年 谢谢你来过",
        };
        SimLine[] lines = new SimLine[texts.length];
        long t = 0;
        for (int i = 0; i < texts.length; i++) {
            lines[i] = new SimLine(texts[i], t, t + 3500);
            t += 3500;
        }
        return lines;
    }

    /** 一版时序的仿真结果 */
    static final class SimResult {
        String label;
        long[] expectPromoteMs;   // 歌词时间线给出的应顶替时刻
        long[] actualPromoteMs;   // 状态机实际顶替时刻
        double peakAlive;
        double avgAlive;
        int peakSubBatches;
        double avgSubBatches;
        double frames;
    }

    /** 时序版本：true = 旧（消散挡路 + 1 Hz 检测），false = 新（时间线驱动） */
    static SimResult simulate(SimLine[] lines, boolean oldTiming) {
        SimResult res = new SimResult();
        res.label = oldTiming ? "旧时序（消散挡路 + 1 Hz 检测）" : "新时序（歌词时间线驱动）";
        int n = lines.length;
        long lineDuration = lines[0].endMs - lines[0].startMs;
        res.expectPromoteMs = new long[n - 1];
        res.actualPromoteMs = new long[n - 1];

        // ── 每个边界的「检测 / 顶替 / 上移结束」时刻 ──────────────
        long[] detect = new long[n - 1];
        long[] promote = new long[n - 1];
        long[] shiftEnd = new long[n - 1];
        for (int i = 0; i < n - 1; i++) {
            long boundary = lines[i].endMs;
            res.expectPromoteMs[i] = boundary;
            if (oldTiming) {
                // 旧：换行由 1 Hz 的 `ctx.lyricLineIndex` 发现（向上取整到秒）
                detect[i] = ((boundary + SIM_TICK_MS - 1) / SIM_TICK_MS) * SIM_TICK_MS;
                // 旧：顶替必须等消散跑完（90·字数 + 600 ms）
                long scatter = (long) (lines[i].chars - 1) * OLD_SCATTER_PER_CHAR + OLD_SCATTER_FLY;
                promote[i] = detect[i] + scatter;
                shiftEnd[i] = promote[i] + OLD_SHIFT_MS;
            } else {
                // 新：逐帧发现 ⇒ 顶替发生在边界的下一帧
                detect[i] = boundary;
                promote[i] = ((boundary + SIM_FRAME_MS - 1) / SIM_FRAME_MS) * SIM_FRAME_MS;
                shiftEnd[i] = promote[i] + slideMsFor(lineDuration);
            }
            res.actualPromoteMs[i] = promote[i];
        }

        // ── 逐帧统计 ─────────────────────────────────────────────
        long totalMs = lines[n - 1].endMs;
        double aliveSum = 0, subSum = 0, frames = 0;
        double peakAlive = 0;
        int peakSub = 0;
        for (long t = 0; t <= totalMs; t += SIM_FRAME_MS) {
            RowState top = new RowState();
            RowState bottom = new RowState();

            // 找到最近一次「顶替」的下标
            int b = -1;
            for (int i = 0; i < n - 1; i++) if (promote[i] <= t) b = i;

            if (b < 0) {
                // 初始：两行同时凝聚
                top.chars = lines[0].chars;
                bottom.chars = lines[1].chars;
                top.solid = false; bottom.solid = false;
                top.coalesceElapsed = t; bottom.coalesceElapsed = t;
                if (oldTiming) {
                    // 旧时序：凝聚走**固定公式**（与歌词时间线无关 ⇒ 常常走不完）
                    top.coalesceArrive = OLD_COALESCE_ARRIVE;
                    top.coalescePerChar = OLD_COALESCE_PER_CHAR;
                    bottom.coalesceArrive = OLD_COALESCE_ARRIVE;
                    bottom.coalescePerChar = OLD_COALESCE_PER_CHAR;
                } else {
                    long topTotal = coalesceTotalFor(top.chars, lineDuration);
                    long botTotal = coalesceTotalFor(bottom.chars, lineDuration);
                    top.coalesceArrive = coalesceArriveFor(topTotal);
                    top.coalescePerChar = coalescePerCharFor(top.chars, topTotal, top.coalesceArrive);
                    bottom.coalesceArrive = coalesceArriveFor(botTotal);
                    bottom.coalescePerChar = coalescePerCharFor(bottom.chars, botTotal, bottom.coalesceArrive);
                }
                if (t >= INIT_COALESCE_MS) {
                    // 初始凝聚结束 ⇒ 上行转成形（开始唱）
                    top.solid = true;
                    top.singing = true;
                }
            } else if (oldTiming) {
                // 旧：消散阶段（顶替还没发生）
                if (t < promote[b]) {
                    top.chars = lines[b].chars;
                    top.solid = false;
                    top.scatterElapsed = t - detect[b];
                    top.scatterPerChar = OLD_SCATTER_PER_CHAR;
                    top.scatterFly = OLD_SCATTER_FLY;
                    // 下行 = 下一句（早已采样 ⇒ 旧公式下大概率**还没聚完**）
                    bottom.chars = lines[b + 1].chars;
                    long sampleAt = (b == 0) ? 0 : promote[b - 1];
                    long total = oldCoalesceTotal(bottom.chars);
                    if (t - sampleAt >= total) {
                        bottom.solid = true;
                    } else {
                        bottom.solid = false;
                        bottom.coalesceElapsed = t - sampleAt;
                        bottom.coalesceArrive = OLD_COALESCE_ARRIVE;
                        bottom.coalescePerChar = OLD_COALESCE_PER_CHAR;
                    }
                } else {
                    // 顶替之后：上行 = 顶上来的一行（成形、在唱），下行 = 再下一句（凝聚中）
                    top.chars = lines[b + 1].chars;
                    top.solid = true;
                    top.singing = true;
                    bottom.chars = (b + 2 < n) ? lines[b + 2].chars : 0;
                    if (bottom.chars > 0) {
                        long total = oldCoalesceTotal(bottom.chars);
                        long sampleAt = promote[b];
                        if (t - sampleAt >= total) {
                            bottom.solid = true;
                        } else {
                            bottom.solid = false;
                            bottom.coalesceElapsed = t - sampleAt;
                            bottom.coalesceArrive = OLD_COALESCE_ARRIVE;
                            bottom.coalescePerChar = OLD_COALESCE_PER_CHAR;
                        }
                    }
                }
            } else {
                // 新：上移阶段（顶替已发生 ⇒ 顶替行在上移，旧行是余烬）
                if (t < shiftEnd[b]) {
                    top.chars = lines[b + 1].chars;
                    top.solid = true;
                    top.singing = true;
                    bottom.chars = lines[b].chars;
                    bottom.solid = false;
                    bottom.scatterElapsed = t - promote[b];
                    long fly = scatterFlyFor(slideMsFor(lineDuration));
                    bottom.scatterFly = fly;
                    bottom.scatterPerChar = scatterPerCharFor(bottom.chars, slideMsFor(lineDuration), fly);
                } else {
                    top.chars = lines[b + 1].chars;
                    top.solid = true;
                    top.singing = true;
                    bottom.chars = (b + 2 < n) ? lines[b + 2].chars : 0;
                    if (bottom.chars > 0) {
                        long window = lineDuration - slideMsFor(lineDuration);
                        long total = coalesceTotalFor(bottom.chars, window);
                        long sampleAt = shiftEnd[b];
                        if (t - sampleAt >= total) {
                            bottom.solid = true;
                        } else {
                            bottom.solid = false;
                            bottom.coalesceElapsed = t - sampleAt;
                            bottom.coalesceArrive = coalesceArriveFor(total);
                            bottom.coalescePerChar = coalescePerCharFor(bottom.chars, total, bottom.coalesceArrive);
                        }
                    }
                }
            }

            double alive = aliveOf(top) + aliveOf(bottom);
            int subs = subBatchesOf(top) + subBatchesOf(bottom);
            aliveSum += alive; subSum += subs; frames++;
            if (alive > peakAlive) peakAlive = alive;
            if (subs > peakSub) peakSub = subs;
        }
        res.peakAlive = peakAlive;
        res.avgAlive = aliveSum / frames;
        res.peakSubBatches = peakSub;
        res.avgSubBatches = subSum / frames;
        res.frames = frames;
        return res;
    }

    /** 时间线仿真（主入口） */
    static void runTimelineSimulation() {
        SimLine[] lines = buildSimLyrics();
        SimResult oldRes = simulate(lines, true);
        SimResult newRes = simulate(lines, false);

        System.out.println();
        System.out.println("=== ④ 时间线仿真（歌词时间线 = 6 句 × 3.5 s，逐字按字数均分） ===");
        System.out.printf("每行粒子上限 %d（E53 子模式 0 中/高档 ×2），帧步长 %d ms（120 Hz）%n", SIM_CAP, SIM_FRAME_MS);
        System.out.println();
        System.out.printf("%-4s %-10s %-12s %-12s %-10s%n",
                "边界", "应顶替(ms)", "旧: 实际(ms)", "旧: 延迟(ms)", "新: 实际(ms)");
        for (int i = 0; i < lines.length - 1; i++) {
            long expect = oldRes.expectPromoteMs[i];
            System.out.printf("%-4d %-10d %-12d %-12d %-10d%n",
                    i + 1, expect,
                    oldRes.actualPromoteMs[i], oldRes.actualPromoteMs[i] - expect,
                    newRes.actualPromoteMs[i]);
        }
        long oldMaxLag = 0, newMaxLag = 0;
        for (int i = 0; i < lines.length - 1; i++) {
            oldMaxLag = Math.max(oldMaxLag, oldRes.actualPromoteMs[i] - oldRes.expectPromoteMs[i]);
            newMaxLag = Math.max(newMaxLag, newRes.actualPromoteMs[i] - newRes.expectPromoteMs[i]);
        }
        System.out.println();
        System.out.printf("最大延迟：旧 = %d ms（≈ 消散时长 + 1 Hz 检测量化）%n", oldMaxLag);
        System.out.printf("          新 = %d ms（≤ 1 帧 = %d ms）%n", newMaxLag, SIM_FRAME_MS);

        System.out.println();
        System.out.println("--- 同时存活点数（填充率的成本量）---");
        System.out.printf("%-34s 峰值 %8.0f   平均 %8.0f   （上限 %d = 2 行）%n",
                oldRes.label, oldRes.peakAlive, oldRes.avgAlive, SIM_CAP * 2);
        System.out.printf("%-34s 峰值 %8.0f   平均 %8.0f   （上限 %d = 2 行）%n",
                newRes.label, newRes.peakAlive, newRes.avgAlive, SIM_CAP * 2);

        System.out.println();
        System.out.println("--- 非空子批次数（= 每帧 drawPoints 提交数；strokeWidth 是整批的）---");
        System.out.printf("%-34s 峰值 %4d   平均 %6.2f%n", oldRes.label, oldRes.peakSubBatches, oldRes.avgSubBatches);
        System.out.printf("%-34s 峰值 %4d   平均 %6.2f%n", newRes.label, newRes.peakSubBatches, newRes.avgSubBatches);
        System.out.println("（说明：旧时序的「下一句」永远聚不完 ⇒ 可见度铺满 16 档 ⇒ 提交数居高不下；");
        System.out.println("  新时序的凝聚被**压进可用窗口** ⇒ 该行大部分时间是成形的 ⇒ 只占 1 档。）");

        // 落一份 JSON 便于机器比对
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"cap\": ").append(SIM_CAP).append(",\n");
            sb.append("  \"lineDurationMs\": 3500,\n");
            sb.append("  \"old\": {\"maxLagMs\": ").append(oldMaxLag)
              .append(", \"peakAliveDots\": ").append((long) oldRes.peakAlive)
              .append(", \"avgAliveDots\": ").append((long) oldRes.avgAlive)
              .append(", \"peakSubBatches\": ").append(oldRes.peakSubBatches)
              .append(", \"avgSubBatches\": ").append(String.format("%.2f", oldRes.avgSubBatches))
              .append("},\n");
            sb.append("  \"new\": {\"maxLagMs\": ").append(newMaxLag)
              .append(", \"peakAliveDots\": ").append((long) newRes.peakAlive)
              .append(", \"avgAliveDots\": ").append((long) newRes.avgAlive)
              .append(", \"peakSubBatches\": ").append(newRes.peakSubBatches)
              .append(", \"avgSubBatches\": ").append(String.format("%.2f", newRes.avgSubBatches))
              .append("}\n}\n");
            java.nio.file.Files.write(
                java.nio.file.Paths.get("logs_temp/dotmatrix_timeline_sim.json"),
                sb.toString().getBytes("UTF-8"));
            System.out.println();
            System.out.println("仿真摘要已写入 logs_temp/dotmatrix_timeline_sim.json");
        } catch (Exception e) {
            System.out.println("（仿真摘要写盘失败: " + e + "）");
        }
    }
}
