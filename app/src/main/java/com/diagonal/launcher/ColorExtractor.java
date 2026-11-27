package com.diagonal.launcher;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Picks the BACKGROUND colour of an app icon: the colour of its outer opaque edge.
 * TikTok (black tile) → black stripe, YouTube (white tile) → white stripe,
 * a round blue icon → blue stripe.
 */
final class ColorExtractor {
    private static final int PROBES_PER_SIDE = 16;

    private ColorExtractor() {}

    static int dominantColor(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int inset = Math.max(2, Math.min(w, h) / 30); // step past the anti-aliased rim
        int[] samples = new int[PROBES_PER_SIDE * 4];
        int n = 0;

        for (int k = 0; k < PROBES_PER_SIDE; k++) {
            // probe positions spread over the middle 80% of each side
            float f = 0.1f + 0.8f * k / (PROBES_PER_SIDE - 1);
            int px = Math.round(f * (w - 1));
            int py = Math.round(f * (h - 1));
            n = probe(bmp, px, 0, 0, 1, inset, samples, n);       // from the top, going down
            n = probe(bmp, px, h - 1, 0, -1, inset, samples, n);  // from the bottom, going up
            n = probe(bmp, 0, py, 1, 0, inset, samples, n);       // from the left
            n = probe(bmp, w - 1, py, -1, 0, inset, samples, n);  // from the right
        }
        if (n == 0) return Color.DKGRAY;

        // Most common colour (quantised to 4 bits per channel), averaged.
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

    /** Walks from (x,y) in direction (dx,dy) to the first opaque pixel, then `inset` px further. */
    private static int probe(Bitmap bmp, int x, int y, int dx, int dy, int inset, int[] out, int n) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int limit = Math.max(w, h) / 2;
        for (int step = 0; step < limit; step++) {
            int cx = x + dx * step, cy = y + dy * step;
            if (cx < 0 || cy < 0 || cx >= w || cy >= h) return n;
            if (Color.alpha(bmp.getPixel(cx, cy)) >= 200) {
                int ix = Math.max(0, Math.min(w - 1, cx + dx * inset));
                int iy = Math.max(0, Math.min(h - 1, cy + dy * inset));
                int c = bmp.getPixel(ix, iy);
                if (Color.alpha(c) >= 200) out[n++] = c | 0xFF000000;
                return n;
            }
        }
        return n;
    }

    private static int quant(int c) {
        return ((Color.red(c) >> 4) << 8) | ((Color.green(c) >> 4) << 4) | (Color.blue(c) >> 4);
    }

    static int textColorFor(int bg) {
        double lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255.0;
        return lum > 0.6 ? 0xFF111111 : 0xFFFFFFFF;
    }
}
