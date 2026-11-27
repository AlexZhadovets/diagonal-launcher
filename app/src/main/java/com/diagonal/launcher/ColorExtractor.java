package com.diagonal.launcher;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Works out an app's MAIN colour and a one-colour "glyph" of its icon.
 *
 * - Icon on a coloured or dark tile (Telegram, TikTok): stripe = tile colour.
 * - Icon on a white/light-grey tile (DeepSeek, YouTube): stripe = the icon's main
 *   brand colour (blue, red…), so there is not a sea of white stripes.
 * The glyph keeps everything that is not the tile background and paints it in the
 * text colour, e.g. DeepSeek → white whale on a blue stripe.
 */
final class ColorExtractor {

    static final class Result {
        final int color;
        final int textColor;
        final Bitmap glyph;

        Result(int color, int textColor, Bitmap glyph) {
            this.color = color;
            this.textColor = textColor;
            this.glyph = glyph;
        }
    }

    private static final int PROBES_PER_SIDE = 16;
    private static final int BUCKETS = 36;
    private static final int NONE = 0;

    private ColorExtractor() {}

    static Result analyze(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);

        int bg = edgeColor(px, w, h);
        boolean bgLight = bg != NONE && lum(bg) > 0.78 && sat(bg) < 0.2;

        // Foreground statistics (everything that is clearly not the tile background).
        float[] weight = new float[BUCKETS];
        float[] rS = new float[BUCKETS], gS = new float[BUCKETS], bS = new float[BUCKETS];
        float[] hsv = new float[3];
        long fr = 0, fg = 0, fb = 0;
        int fgCount = 0;
        int step = Math.max(1, Math.min(w, h) / 48);
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int c = px[y * w + x];
                if (Color.alpha(c) < 200) continue;
                if (bg != NONE && dist(c, bg) < 90) continue;
                int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                fr += r; fg += g; fb += b; fgCount++;
                Color.RGBToHSV(r, g, b, hsv);
                if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue;
                int k = Math.min(BUCKETS - 1, (int) (hsv[0] / 360f * BUCKETS));
                float wt = hsv[1] * hsv[2];
                weight[k] += wt; rS[k] += r * wt; gS[k] += g * wt; bS[k] += b * wt;
            }
        }

        int color;
        if (bg == NONE || bgLight) {
            int best = -1;
            float bw = 0f;
            for (int k = 0; k < BUCKETS; k++) if (weight[k] > bw) { bw = weight[k]; best = k; }
            if (best >= 0) {
                color = Color.rgb(Math.round(rS[best] / bw), Math.round(gS[best] / bw), Math.round(bS[best] / bw));
            } else if (fgCount > 0) {
                color = Color.rgb((int) (fr / fgCount), (int) (fg / fgCount), (int) (fb / fgCount));
            } else {
                color = bg == NONE ? Color.DKGRAY : bg;
            }
        } else {
            color = bg;
        }
        int text = textColorFor(color);
        Bitmap glyph = makeGlyph(px, w, h, bg, text);
        if (glyph == null) glyph = bmp; // icon is one flat colour: keep the original
        return new Result(color, text, glyph);
    }

    /** Pixels far from the tile background become the text colour; the rest transparent. */
    private static Bitmap makeGlyph(int[] px, int w, int h, int bg, int ink) {
        int[] out = new int[w * h];
        int inkRgb = ink & 0x00FFFFFF;
        int covered = 0;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int a = Color.alpha(c);
            if (a == 0) continue;
            float keep = bg == NONE ? 1f : smooth(60f, 150f, dist(c, bg));
            int na = Math.round(a * keep);
            if (na > 0) {
                out[i] = (na << 24) | inkRgb;
                if (na > 128) covered++;
            }
        }
        if (covered < px.length / 40) return null;
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
    }

    /** Colour of the icon's outer opaque edge (its tile), or NONE. */
    private static int edgeColor(int[] px, int w, int h) {
        int inset = Math.max(2, Math.min(w, h) / 30);
        int[] samples = new int[PROBES_PER_SIDE * 4];
        int n = 0;
        for (int k = 0; k < PROBES_PER_SIDE; k++) {
            float f = 0.1f + 0.8f * k / (PROBES_PER_SIDE - 1);
            int x = Math.round(f * (w - 1)), y = Math.round(f * (h - 1));
            n = probe(px, w, h, x, 0, 0, 1, inset, samples, n);
            n = probe(px, w, h, x, h - 1, 0, -1, inset, samples, n);
            n = probe(px, w, h, 0, y, 1, 0, inset, samples, n);
            n = probe(px, w, h, w - 1, y, -1, 0, inset, samples, n);
        }
        if (n == 0) return NONE;
        int[] counts = new int[4096];
        int best = quant(samples[0]);
        for (int i = 0; i < n; i++) {
            int q = quant(samples[i]);
            if (++counts[q] > counts[best]) best = q;
        }
        long r = 0, g = 0, b = 0;
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (quant(samples[i]) != best) continue;
            int c = samples[i];
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); m++;
        }
        return Color.rgb((int) (r / m), (int) (g / m), (int) (b / m));
    }

    private static int probe(int[] px, int w, int h, int x, int y, int dx, int dy, int inset, int[] out, int n) {
        int limit = Math.max(w, h) / 2;
        for (int s = 0; s < limit; s++) {
            int cx = x + dx * s, cy = y + dy * s;
            if (cx < 0 || cy < 0 || cx >= w || cy >= h) return n;
            if (Color.alpha(px[cy * w + cx]) >= 200) {
                int ix = Math.max(0, Math.min(w - 1, cx + dx * inset));
                int iy = Math.max(0, Math.min(h - 1, cy + dy * inset));
                int c = px[iy * w + ix];
                if (Color.alpha(c) >= 200) out[n++] = c | 0xFF000000;
                return n;
            }
        }
        return n;
    }

    private static int quant(int c) {
        return ((Color.red(c) >> 4) << 8) | ((Color.green(c) >> 4) << 4) | (Color.blue(c) >> 4);
    }

    static int dist(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b))
                + Math.abs(Color.blue(a) - Color.blue(b));
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    private static double lum(int c) {
        return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0;
    }

    private static float sat(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        return hsv[1];
    }

    /** A slightly greyer version of the colour, for seams between equal stripes. */
    static int seamColor(int c) {
        float k = 0.82f;
        int grey = lum(c) > 0.5 ? 150 : 90;
        return Color.rgb(Math.round(Color.red(c) * k + grey * (1 - k)),
                Math.round(Color.green(c) * k + grey * (1 - k)),
                Math.round(Color.blue(c) * k + grey * (1 - k)));
    }

    static int textColorFor(int bg) {
        return lum(bg) > 0.62 ? 0xFF111111 : 0xFFFFFFFF;
    }
}
