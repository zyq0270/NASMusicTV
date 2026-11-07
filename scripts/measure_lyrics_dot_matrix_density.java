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
 * 歌词点阵的**点阵密度**测量工具（§10.283 的数字由本脚本再生，⛔ 不许手抄）。
 *
 * 复刻 `LyricsDotMatrixRenderer` 的 `computeFontSize()` 与 `sampleLine()`，对比两种行内抽样策略：
 * - `BUGGY`    —— 2026-11-07 修复**前**的判据 `((taken-1) % takeEvery) < 0.5`（小数取模 ⇒ 漏取约 46%，
 *                且落点偏在笔画一侧 ⇒ 字成「空心轮廓」）；
 * - `EVEN_FULL` —— 修复**后**的判据 `isSegmentStart()`（均匀分段 ⇒ 取满配额、沿笔画宽度也铺开）。
 *
 * 输出的指标：
 * - **每行粒子数** `n`（声明的预算是 `cap`，修复前只能落下 ≈54%）；
 * - **每字粒子数**（按字索引分组后的 min..max）；
 * - **笔画覆盖率**（墨迹像素里落在某个粒子绘制圆盘内的比例 ⇒ 「字有没有真的组成」）。
 *
 * 用法（仓库根执行，需要 JDK 11+ 的单文件源码模式）：
 * ```
 * java scripts/measure_lyrics_dot_matrix_density.java "Microsoft YaHei"
 * ```
 * 会在 `output/` 下输出核对图（灰 = 墨迹，青 = 粒子点）供肉眼比对。
 *
 * ⚠️ 用 AWT 的 `Microsoft YaHei` 代替 Android 的 `Typeface.DEFAULT_BOLD`：
 * 两者字形度量接近，但**不是同一个字体** ⇒ 数字用于**相对比较**（修复前 vs 修复后），
 * 绝对值以真机为准。
 */
public class MeasureLyricsDotMatrixDensity {

    static final float REF_FONT = 100f;
    static final float MIN_FONT_S = 0.05f;
    static final float MAX_FONT_S = 0.16f;
    static final int CAP_HIGH = 3600;
    static final int CAP_LOW = 900;

    static float fontMaxRatio(int cap) {
        return MAX_FONT_S * (float) Math.sqrt((float) cap / (float) CAP_HIGH);
    }

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

    static float computeFontSize(String longest, int w, int h, int capRatioCap) {
        Metrics m = metricsAt(REF_FONT);
        int refLineHeight = m.lineHeight();
        float refGlyphAdvance = (longest == null || longest.isEmpty())
                ? REF_FONT : Math.max(advance(fontAt(REF_FONT), longest), REF_FONT * 0.1f);
        float fontFromWidth = REF_FONT * (w * 0.80f) / refGlyphAdvance;
        float fontFromHeight = REF_FONT * (h * 0.40f) / refLineHeight;
        float base = Math.min(fontFromWidth, fontFromHeight);
        float lo = h * MIN_FONT_S, hi = h * fontMaxRatio(capRatioCap);
        if (lo > hi) throw new IllegalArgumentException("coerceIn 越界");
        return Math.min(Math.max(base, lo), hi);
    }

    static final int BUGGY = 0, EVEN_FULL = 1, EVEN_SAME = 2;

    static final class Sample {
        int n, inkPixels, glyphs, bmpW, bmpH;
        float fontSize;
        int[] partX, partY, charIdx;
        boolean[] ink;
    }

    static Sample sample(String text, int w, int h, float fontSize, int cap, int mode) {
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

        int[] quota = new int[bmpH];
        if (totalPixels > 0 && cap > 0) {
            for (int ri = 0; ri < bmpH; ri++) {
                int cnt = rowPixelCount[ri];
                if (cnt <= 0) continue;
                quota[ri] = (int) Math.max(1L, (long) cnt * cap / totalPixels);
            }
        }

        int[] charXBounds = new int[displayText.length() + 1];
        charXBounds[0] = (int) xOff;
        for (int i = 0; i < displayText.length(); i++) {
            charXBounds[i + 1] = (int) (xOff + advance(f, displayText.substring(0, i + 1)));
        }

        ArrayList<int[]> pts = new ArrayList<>();
        ArrayList<Integer> cidx = new ArrayList<>();
        int curRowFill = 0;
        for (int ri = 0; ri < bmpH; ri++) {
            int y = ri;
            int rowInk = rowPixelCount[ri];
            if (rowInk == 0) continue;
            int targetCount = quota[ri];
            if (targetCount <= 0) continue;

            // EVEN_SAME：先算出 BUGGY 策略在本行会取几个（保持个数不变，只修分布）
            int sameCount = targetCount;
            if (mode == EVEN_SAME) {
                float takeEvery = Math.max(1f, (float) rowInk / (float) targetCount);
                int cnt = 0;
                for (int x = 0; x < bmpW && cnt < targetCount; x++) {
                    if (!ink[y * bmpW + x]) continue;
                    int taken = ++cnt;
                    if (((taken - 1f) % takeEvery) < 0.5f) sameCount++;
                }
            }

            int taken = 0, filled = 0;
            float takeEvery = Math.max(1f, (float) rowInk / (float) targetCount);
            for (int x = 0; x < bmpW; x++) {
                if (curRowFill >= cap) break;
                if (!ink[y * bmpW + x]) continue;
                boolean pick;
                int i = taken;
                if (mode == BUGGY) {
                    taken++;
                    pick = ((taken - 1f) % takeEvery) < 0.5f;
                } else {
                    // EVEN：与 shipped 的 isSegmentStart() 完全一致
                    int N = (mode == EVEN_FULL) ? targetCount : sameCount;
                    if (N <= 0) { taken++; continue; }
                    int chunk = (int) (((long) i * N) / rowInk);
                    int prevChunk = (i == 0) ? -1 : (int) (((long) (i - 1) * N) / rowInk);
                    pick = (chunk != prevChunk);
                    taken++;
                }
                if (pick) {
                    int ci = 0;
                    while (ci < displayText.length() && x >= charXBounds[ci + 1]) ci++;
                    pts.add(new int[]{x, y});
                    cidx.add(Math.min(ci, displayText.length() - 1));
                    curRowFill++;
                    filled++;
                    if (mode == BUGGY && filled >= targetCount) break;
                    if (mode != BUGGY && filled >= (mode == EVEN_FULL ? targetCount : sameCount)) break;
                }
            }
            if (curRowFill >= cap) break;
        }

        Sample s = new Sample();
        s.n = pts.size();
        s.inkPixels = totalPixels;
        s.glyphs = displayText.length();
        s.fontSize = fontSize;
        s.bmpW = bmpW; s.bmpH = bmpH;
        s.partX = new int[s.n]; s.partY = new int[s.n]; s.charIdx = new int[s.n];
        for (int i = 0; i < s.n; i++) {
            s.partX[i] = pts.get(i)[0]; s.partY[i] = pts.get(i)[1]; s.charIdx[i] = cidx.get(i);
        }
        s.ink = ink;
        return s;
    }

    static double coverage(Sample s, double size) {
        double R = 2.0 * size;
        boolean[] cov = new boolean[s.bmpW * s.bmpH];
        int r = (int) Math.ceil(R);
        for (int i = 0; i < s.n; i++) {
            int cx = s.partX[i], cy = s.partY[i];
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

    /** 渲染核对图：左=墨迹（灰） 右=粒子点（青） */
    static void render(Sample s, String path, int cropX, int cropW, double size) throws Exception {
        int y0 = 0, y1 = s.bmpH;
        BufferedImage out = new BufferedImage(cropW, y1 - y0, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.BLACK); g.fillRect(0, 0, out.getWidth(), out.getHeight());
        for (int y = y0; y < y1; y++) {
            for (int x = cropX; x < Math.min(cropX + cropW, s.bmpW); x++) {
                if (s.ink[y * s.bmpW + x]) { g.setColor(new Color(70, 70, 70)); g.fillRect(x - cropX, y - y0, 1, 1); }
            }
        }
        double R = 2.0 * size;
        g.setColor(new Color(80, 230, 255));
        for (int i = 0; i < s.n; i++) {
            int x = s.partX[i] - cropX, y = s.partY[i] - y0;
            if (x < 0 || x >= out.getWidth()) continue;
            g.fillOval((int) (x - R), (int) (y - R), (int) (2 * R + 1), (int) (2 * R + 1));
        }
        g.dispose();
        ImageIO.write(out, "png", new File(path));
    }

    static int[] perGlyph(Sample s) {
        int[] cnt = new int[s.glyphs];
        for (int i = 0; i < s.n; i++) cnt[s.charIdx[i]]++;
        return cnt;
    }

    static void report(String label, String text, int w, int h, int cap, int fontRatioCap, int mode, double size) {
        float font = computeFontSize(text, w, h, fontRatioCap);
        Sample s = sample(text, w, h, font, cap, mode);
        int[] pg = perGlyph(s);
        int minPg = Integer.MAX_VALUE, maxPg = 0;
        for (int c : pg) { minPg = Math.min(minPg, c); maxPg = Math.max(maxPg, c); }
        double cov = coverage(s, size);
        System.out.printf(
                "%-16s | %-22s | cap=%-5d fontCap=%-5d | font=%6.1f glyphs=%2d ink=%6d n=%4d | 每字粒子 min=%3d max=%3d | 覆盖率=%5.1f%%%n",
                label, text, cap, fontRatioCap, font, s.glyphs, s.inkPixels, s.n,
                s.glyphs == 0 ? 0 : minPg, maxPg, cov);
    }

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "Microsoft YaHei";
        BASE = new Font(spec, Font.BOLD, 100);
        System.out.println("字体: " + BASE.getFontName());
        System.out.println();

        String[] lines = {
                "你说过不会离开我",
                "当世界上只剩下你的声音",
                "我们一路走过山川湖海",
        };
        final double SZ = 1.16;

        System.out.println("=== A. 现状抽样（BUGGY）vs 修好后填满预算（EVEN_FULL）===");
        for (String t : lines) {
            report("A1 现状", t, 1920, 1080, 3600, 3600, BUGGY, SZ);
            report("A2 修好+填满", t, 1920, 1080, 3600, 3600, EVEN_FULL, SZ);
            report("A3 修好同个数", t, 1920, 1080, 3600, 3600, EVEN_SAME, SZ);
            System.out.println();
        }

        System.out.println("=== B. 修好后，cap 扫描（字号上限锁 3600 = 解耦）===");
        for (int cap : new int[]{3600, 5400, 7200, 9000, 12000}) {
            for (String t : lines) report("cap=" + cap, t, 1920, 1080, cap, 3600, EVEN_FULL, SZ);
            System.out.println();
        }

        System.out.println("=== C. 修好后，低档（cap=900，字号上限 900）===");
        for (String t : lines) {
            report("C1 低档现状", t, 1920, 1080, 900, 900, BUGGY, SZ);
            report("C2 低档修好", t, 1920, 1080, 900, 900, EVEN_FULL, SZ);
        }
        System.out.println();

        System.out.println("=== D. 修好后 + 点更大（size 1.5 / 1.9）===");
        for (String t : lines) {
            report("D1 cap3600 sz1.5", t, 1920, 1080, 3600, 3600, EVEN_FULL, 1.5);
            report("D2 cap3600 sz1.9", t, 1920, 1080, 3600, 3600, EVEN_FULL, 1.9);
            report("D3 cap7200 sz1.9", t, 1920, 1080, 7200, 3600, EVEN_FULL, 1.9);
        }
        System.out.println();

        // 渲染核对图
        new File("output").mkdirs();
        String t0 = "你说过不会离开我";
        float font = computeFontSize(t0, 1920, 1080, 3600);
        render(sample(t0, 1920, 1080, font, 3600, BUGGY), "output/A_buggy_cap3600.png", 0, 520, SZ);
        render(sample(t0, 1920, 1080, font, 3600, EVEN_FULL), "output/B_even_cap3600.png", 0, 520, SZ);
        render(sample(t0, 1920, 1080, font, 7200, EVEN_FULL), "output/C_even_cap7200.png", 0, 520, SZ);
        render(sample(t0, 1920, 1080, font, 3600, EVEN_FULL), "output/D_even_cap3600_sz19.png", 0, 520, 1.9);
        render(sample(t0, 1920, 1080, font, 7200, EVEN_FULL), "output/E_even_cap7200_sz19.png", 0, 520, 1.9);
        render(sample(t0, 1920, 1080, font, 5400, EVEN_FULL), "output/F_even_cap5400.png", 0, 520, SZ);
        render(sample(t0, 1920, 1080, font, 9000, EVEN_FULL), "output/G_even_cap9000.png", 0, 520, SZ);
        render(sample(t0, 1920, 1080, font, 5400, EVEN_FULL), "output/H_even_cap5400_sz19.png", 0, 520, 1.9);
        System.out.println("核对图已输出到 output/");
    }
}
