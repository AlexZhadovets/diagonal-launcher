package com.diagonal.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Simple hand-drawn line icons for the quick panel (no resources, no fonts). */
final class Icons {
    static final int BLUETOOTH = 0, INTERNET = 1, WALLET = 2, TORCH = 3, WIFI = 4, SOUND = 5,
            DND = 6, ROTATE = 7, NFC = 8, HOTSPOT = 9, LOCATION = 10, ALARM = 11, AIRPLANE = 12,
            CAMERA = 13, SHADE = 14, SETTINGS = 15;

    private static final Path path = new Path();
    private static final RectF r = new RectF();

    private Icons() {}

    /** Draws icon `type` centred at (cx, cy) inside a box of size `s`, with paint colour already set. */
    static void draw(Canvas c, int type, float cx, float cy, float s, Paint p) {
        float u = s / 24f;              // 24-unit grid like Material icons
        float x0 = cx - 12 * u, y0 = cy - 12 * u;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f * u);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        switch (type) {
            case BLUETOOTH:
                path.moveTo(x0 + 7 * u, y0 + 7 * u);
                path.lineTo(x0 + 17 * u, y0 + 16 * u);
                path.lineTo(x0 + 12 * u, y0 + 21 * u);
                path.lineTo(x0 + 12 * u, y0 + 3 * u);
                path.lineTo(x0 + 17 * u, y0 + 8 * u);
                path.lineTo(x0 + 7 * u, y0 + 17 * u);
                c.drawPath(path, p);
                break;
            case WIFI:
            case INTERNET:
                for (int i = 0; i < 3; i++) {
                    float rad = (4 + i * 4.5f) * u;
                    r.set(cx - rad, y0 + 18 * u - rad, cx + rad, y0 + 18 * u + rad);
                    c.drawArc(r, 225, 90, false, p);
                }
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, y0 + 18 * u, 1.6f * u, p);
                if (type == INTERNET) {   // little arrows = data
                    p.setStyle(Paint.Style.STROKE);
                    c.drawLine(x0 + 20 * u, y0 + 13 * u, x0 + 20 * u, y0 + 20 * u, p);
                    c.drawLine(x0 + 18 * u, y0 + 18 * u, x0 + 20 * u, y0 + 20 * u, p);
                }
                break;
            case WALLET:
                r.set(x0 + 3 * u, y0 + 6 * u, x0 + 21 * u, y0 + 19 * u);
                c.drawRoundRect(r, 2 * u, 2 * u, p);
                r.set(x0 + 14 * u, y0 + 10.5f * u, x0 + 21 * u, y0 + 14.5f * u);
                c.drawRoundRect(r, 1 * u, 1 * u, p);
                break;
            case TORCH:
                path.moveTo(x0 + 7 * u, y0 + 3 * u);
                path.lineTo(x0 + 17 * u, y0 + 3 * u);
                path.lineTo(x0 + 17 * u, y0 + 7 * u);
                path.lineTo(x0 + 14.5f * u, y0 + 11 * u);
                path.lineTo(x0 + 14.5f * u, y0 + 21 * u);
                path.lineTo(x0 + 9.5f * u, y0 + 21 * u);
                path.lineTo(x0 + 9.5f * u, y0 + 11 * u);
                path.lineTo(x0 + 7 * u, y0 + 7 * u);
                path.close();
                c.drawPath(path, p);
                c.drawLine(x0 + 12 * u, y0 + 13 * u, x0 + 12 * u, y0 + 15 * u, p);
                break;
            case SOUND:
                path.moveTo(x0 + 3 * u, y0 + 9 * u);
                path.lineTo(x0 + 7 * u, y0 + 9 * u);
                path.lineTo(x0 + 12 * u, y0 + 4 * u);
                path.lineTo(x0 + 12 * u, y0 + 20 * u);
                path.lineTo(x0 + 7 * u, y0 + 15 * u);
                path.lineTo(x0 + 3 * u, y0 + 15 * u);
                path.close();
                c.drawPath(path, p);
                r.set(x0 + 10 * u, y0 + 8 * u, x0 + 18 * u, y0 + 16 * u);
                c.drawArc(r, -60, 120, false, p);
                r.set(x0 + 8 * u, y0 + 4 * u, x0 + 22 * u, y0 + 20 * u);
                c.drawArc(r, -55, 110, false, p);
                break;
            case DND:
                c.drawCircle(cx, cy, 9 * u, p);
                c.drawLine(x0 + 7.5f * u, cy, x0 + 16.5f * u, cy, p);
                break;
            case ROTATE:
                r.set(x0 + 4 * u, y0 + 4 * u, x0 + 20 * u, y0 + 20 * u);
                c.drawArc(r, 200, 270, false, p);
                c.drawLine(x0 + 4.5f * u, y0 + 7 * u, x0 + 5 * u, y0 + 11.5f * u, p);
                c.drawLine(x0 + 5 * u, y0 + 11.5f * u, x0 + 9.5f * u, y0 + 10.5f * u, p);
                break;
            case NFC:
                r.set(x0 + 4 * u, y0 + 4 * u, x0 + 20 * u, y0 + 20 * u);
                c.drawRoundRect(r, 3 * u, 3 * u, p);
                path.moveTo(x0 + 9 * u, y0 + 16 * u);
                path.lineTo(x0 + 9 * u, y0 + 8 * u);
                path.lineTo(x0 + 15 * u, y0 + 16 * u);
                path.lineTo(x0 + 15 * u, y0 + 8 * u);
                c.drawPath(path, p);
                break;
            case HOTSPOT:
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, 2f * u, p);
                p.setStyle(Paint.Style.STROKE);
                for (int i = 0; i < 2; i++) {
                    float rad = (5 + i * 4f) * u;
                    r.set(cx - rad, cy - rad, cx + rad, cy + rad);
                    c.drawArc(r, 135, 90, false, p);
                    c.drawArc(r, -45, 90, false, p);
                }
                break;
            case LOCATION:
                path.moveTo(cx, y0 + 21 * u);
                path.lineTo(x0 + 6.5f * u, y0 + 12.5f * u);
                r.set(x0 + 5.5f * u, y0 + 3 * u, x0 + 18.5f * u, y0 + 16 * u);
                path.arcTo(r, 150, 240);
                path.close();
                c.drawPath(path, p);
                c.drawCircle(cx, y0 + 9.5f * u, 2.3f * u, p);
                break;
            case ALARM:
                c.drawCircle(cx, y0 + 13 * u, 8 * u, p);
                c.drawLine(cx, y0 + 13 * u, cx, y0 + 9 * u, p);
                c.drawLine(cx, y0 + 13 * u, cx + 3 * u, y0 + 15 * u, p);
                c.drawLine(x0 + 3 * u, y0 + 6 * u, x0 + 6 * u, y0 + 3 * u, p);
                c.drawLine(x0 + 21 * u, y0 + 6 * u, x0 + 18 * u, y0 + 3 * u, p);
                break;
            case AIRPLANE:
                c.drawLine(cx, y0 + 3 * u, cx, y0 + 19 * u, p);
                path.moveTo(x0 + 3 * u, y0 + 14 * u);
                path.lineTo(cx, y0 + 9 * u);
                path.lineTo(x0 + 21 * u, y0 + 14 * u);
                c.drawPath(path, p);
                c.drawLine(x0 + 8.5f * u, y0 + 21 * u, cx, y0 + 18 * u, p);
                c.drawLine(x0 + 15.5f * u, y0 + 21 * u, cx, y0 + 18 * u, p);
                break;
            case CAMERA:
                r.set(x0 + 3 * u, y0 + 7 * u, x0 + 21 * u, y0 + 20 * u);
                c.drawRoundRect(r, 2 * u, 2 * u, p);
                c.drawCircle(cx, y0 + 13.5f * u, 3.5f * u, p);
                c.drawLine(x0 + 9 * u, y0 + 7 * u, x0 + 10.5f * u, y0 + 4 * u, p);
                c.drawLine(x0 + 10.5f * u, y0 + 4 * u, x0 + 13.5f * u, y0 + 4 * u, p);
                c.drawLine(x0 + 13.5f * u, y0 + 4 * u, x0 + 15 * u, y0 + 7 * u, p);
                break;
            case SHADE:
                c.drawLine(x0 + 4 * u, y0 + 7 * u, x0 + 20 * u, y0 + 7 * u, p);
                c.drawLine(x0 + 4 * u, y0 + 12 * u, x0 + 20 * u, y0 + 12 * u, p);
                c.drawLine(x0 + 4 * u, y0 + 17 * u, x0 + 14 * u, y0 + 17 * u, p);
                break;
            case SETTINGS:
            default:
                // the launcher's own mark: three diagonal stripes
                p.setStrokeWidth(3f * u);
                c.drawLine(x0 + 4 * u, y0 + 12 * u, x0 + 12 * u, y0 + 4 * u, p);
                c.drawLine(x0 + 6 * u, y0 + 18 * u, x0 + 18 * u, y0 + 6 * u, p);
                c.drawLine(x0 + 12 * u, y0 + 20 * u, x0 + 20 * u, y0 + 12 * u, p);
                break;
        }
        p.setStyle(Paint.Style.FILL);
    }
}
