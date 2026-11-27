package com.diagonal.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Square-cornered panel with the top-right and bottom-left corners cut on the diagonal (the launcher's look). */
final class DiagonalBg extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float cut;

    DiagonalBg(int color, float cut) {
        paint.setColor(color);
        this.cut = cut;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        path.reset();
        path.moveTo(b.left, b.top);
        path.lineTo(b.right - cut, b.top);
        path.lineTo(b.right, b.top + cut);
        path.lineTo(b.right, b.bottom);
        path.lineTo(b.left + cut, b.bottom);
        path.lineTo(b.left, b.bottom - cut);
        path.close();
    }

    @Override public void draw(Canvas c) { c.drawPath(path, paint); }
    @Override public void setAlpha(int a) { paint.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    @Override
    public void getOutline(android.graphics.Outline outline) {
        if (android.os.Build.VERSION.SDK_INT >= 30) outline.setPath(path);
        else outline.setRect(getBounds());
    }
}
