package com.diagonal.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** A small slanted stripe in the app's colour with its icon — the launcher's stripe in miniature. */
final class AppTileView extends View {
    private final AppEntry app;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    AppTileView(Context c, AppEntry app) {
        super(c);
        this.app = app;
        fill.setColor(app == null ? 0xFF2A2A31 : app.color);
        mark.setColor(0xFFFFFFFF);
        mark.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
        mark.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), slant = h * 0.5f;
        path.reset();
        path.moveTo(slant, 0);
        path.lineTo(w, 0);
        path.lineTo(w - slant, h);
        path.lineTo(0, h);
        path.close();
        canvas.drawPath(path, fill);
        float s = h * 0.62f, cx = w / 2f, cy = h / 2f;
        if (app == null) {   // "auto": three little stripes
            for (int i = -1; i <= 1; i++) {
                float o = i * s * 0.3f;
                canvas.drawLine(cx - s * 0.2f + o, cy + s * 0.25f, cx + s * 0.2f + o, cy - s * 0.25f, mark);
            }
            return;
        }
        if (app.icon == null) return;
        r.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
        bmp.setColorFilter(app.iconFilter());
        canvas.drawBitmap(app.icon, null, r, bmp);
    }
}
