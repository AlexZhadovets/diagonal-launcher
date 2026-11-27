package com.diagonal.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.widget.LinearLayout;

/**
 * A widget card in the launcher's style: square corners, a solid colour, and the right edge
 * cut at 45° exactly along the edge of the widget triangle (set by {@link WidgetPanel}).
 */
final class SlantCard extends LinearLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int cut;            // how far the top-right corner is cut in, px (0..height)
    private int baseRight = -1;

    SlantCard(Context c, int color) {
        super(c);
        paint.setColor(color);
        setWillNotDraw(false);
    }

    void setColor(int color) { paint.setColor(color); invalidate(); }

    int color() { return paint.getColor(); }

    /** Called by WidgetPanel before measuring: keeps content out of the cut-off corner. */
    void setCut(int c) {
        if (baseRight < 0) baseRight = getPaddingRight();
        if (c == cut) return;
        cut = c;
        setPadding(getPaddingLeft(), getPaddingTop(), baseRight + cut, getPaddingBottom());
        invalidate();
    }

    private void buildPath() {
        int w = getWidth(), h = getHeight();
        int c = Math.min(cut, h);
        path.reset();
        path.moveTo(0, 0);
        path.lineTo(w - c, 0);
        path.lineTo(w, c);
        path.lineTo(w, h);
        path.lineTo(0, h);
        path.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        buildPath();
        canvas.drawPath(path, paint);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        buildPath();
        canvas.save();
        canvas.clipPath(path);
        super.dispatchDraw(canvas);
        canvas.restore();
    }
}
