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
    private final Path raw = new Path();
    private final Paint rounder = new Paint();
    private int cut;            // how far the top-right corner is cut in, px (0..height)
    private int baseRight = -1;

    SlantCard(Context c, int color) {
        super(c);
        paint.setColor(color);
        float r = 20 * c.getResources().getDisplayMetrics().density;
        rounder.setPathEffect(new android.graphics.CornerPathEffect(r));   // rounds every corner, the slanted one too
        rounder.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
    }

    void setColor(int color) { paint.setColor(color); invalidate(); }

    int color() { return paint.getColor(); }

    /** Called by WidgetPanel before measuring: keeps content out of the cut-off corner. */
    void setCut(int c) {
        if (baseRight < 0) baseRight = getPaddingRight();
        if (c == cut) return;
        cut = c;
        if ((getGravity() & android.view.Gravity.HORIZONTAL_GRAVITY_MASK) == android.view.Gravity.CENTER_HORIZONTAL) {
            setPadding(cut / 2, getPaddingTop(), baseRight + cut / 2, getPaddingBottom());   // keep centred text centred
        } else {
            setPadding(getPaddingLeft(), getPaddingTop(), baseRight + cut, getPaddingBottom());
        }
        invalidate();
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        // the cut-off corner is not part of the card
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && e.getX() > getWidth() - cut + e.getY()) {
            return false;
        }
        return super.dispatchTouchEvent(e);
    }

    private void buildPath() {
        int w = getWidth(), h = getHeight();
        int c = Math.min(cut, h);
        raw.reset();
        raw.moveTo(0, 0);
        raw.lineTo(w - c, 0);
        raw.lineTo(w, c);
        raw.lineTo(w, h);
        raw.lineTo(0, h);
        raw.close();
        path.reset();
        rounder.getFillPath(raw, path);
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
