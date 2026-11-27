package com.diagonal.launcher;

import android.graphics.Bitmap;
import android.graphics.Color;

/** Picks the dominant, saturated colour of an app icon without any library. */
final class ColorExtractor {
    private static final int BUCKETS = 36;

    private ColorExtractor() {}

    static int dominantColor(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        // Sample a ~24x24 grid instead of every pixel: same result, far less work.
        int step = Math.max(1, Math.min(w, h) / 24);
        int[] px = new int[((w + step - 1) / step) * ((h + step - 1) / step)];
        int n = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) px[n++] = bmp.getPixel(x, y);
        }

        float[] weight = new float[BUCKETS];
        float[] rSum = new float[BUCKETS];
        float[] gSum = new float[BUCKETS];
        float[] bSum = new float[BUCKETS];
        float[] hsv = new float[3];
        long ar = 0, ag = 0, ab = 0;
        int opaque = 0;

        for (int k = 0; k < n; k++) {
            int c = px[k];
            if (Color.alpha(c) < 128) continue;
            int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            ar += r; ag += g; ab += b; opaque++;
            Color.RGBToHSV(r, g, b, hsv);
            float s = hsv[1], v = hsv[2];
            if (s < 0.25f || v < 0.2f) continue; // skip greys, black, white
            int bucket = Math.min(BUCKETS - 1, (int) (hsv[0] / 360f * BUCKETS));
            float wgt = s * v;
            weight[bucket] += wgt;
            rSum[bucket] += r * wgt;
            gSum[bucket] += g * wgt;
            bSum[bucket] += b * wgt;
        }

        int best = -1;
        float bestW = 0f;
        for (int i = 0; i < BUCKETS; i++) {
            if (weight[i] > bestW) { bestW = weight[i]; best = i; }
        }

        int color;
        if (best >= 0 && bestW > 0f) {
            color = Color.rgb(
                    Math.round(rSum[best] / bestW),
                    Math.round(gSum[best] / bestW),
                    Math.round(bSum[best] / bestW));
        } else if (opaque > 0) {
            color = Color.rgb((int) (ar / opaque), (int) (ag / opaque), (int) (ab / opaque));
        } else {
            color = Color.GRAY;
        }

        // Make sure the line is clearly visible on the dark background.
        Color.colorToHSV(color, hsv);
        if (hsv[2] < 0.55f) hsv[2] = 0.55f;
        return Color.HSVToColor(hsv);
    }

    static int textColorFor(int bg) {
        double lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255.0;
        return lum > 0.6 ? 0xFF111111 : 0xFFFFFFFF;
    }
}
