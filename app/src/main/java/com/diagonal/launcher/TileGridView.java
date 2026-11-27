package com.diagonal.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Search results: rounded squares in the app's colour with its white glyph and name. */
public class TileGridView extends View {

    public interface Listener { void onTileClick(AppEntry app, View v, Rect bounds); }

    private static final int COLUMNS = 4;

    private final List<AppEntry> apps = new ArrayList<>();
    private final float dp;
    private final Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final GestureDetector gestures;
    private Listener listener;

    private float cell, tile, gap, labelH;

    public TileGridView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        labelPaint.setColor(0xDDFFFFFF);
        labelPaint.setTextSize(12 * dp);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                int i = indexAt(e.getX(), e.getY());
                if (i < 0 || listener == null) return false;
                Rect b = new Rect();
                tileRect(i);
                rect.round(b);
                listener.onTileClick(apps.get(i), TileGridView.this, b);
                return true;
            }
        });
    }

    public void setListener(Listener l) { listener = l; }

    public void setApps(List<AppEntry> list) {
        apps.clear();
        apps.addAll(list);
        requestLayout();
        invalidate();
    }

    public AppEntry first() { return apps.isEmpty() ? null : apps.get(0); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        gap = 14 * dp;
        cell = (w - gap) / COLUMNS;
        tile = cell - gap;
        labelH = 26 * dp;
        int rows = (apps.size() + COLUMNS - 1) / COLUMNS;
        int h = Math.round(rows * (tile + labelH + gap) + gap);
        setMeasuredDimension(w, h);
    }

    private void tileRect(int i) {
        int row = i / COLUMNS, col = i % COLUMNS;
        float x = gap + col * cell;
        float y = gap + row * (tile + labelH + gap);
        rect.set(x, y, x + tile, y + tile);
    }

    private int indexAt(float x, float y) {
        int col = (int) ((x - gap / 2f) / cell);
        int row = (int) ((y - gap / 2f) / (tile + labelH + gap));
        if (col < 0 || col >= COLUMNS || row < 0) return -1;
        int i = row * COLUMNS + col;
        return i < apps.size() ? i : -1;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float radius = tile * 0.24f;
        float icon = tile * 0.56f;
        for (int i = 0; i < apps.size(); i++) {
            AppEntry a = apps.get(i);
            tileRect(i);
            tilePaint.setColor(a.color);
            canvas.drawRoundRect(rect, radius, radius, tilePaint);
            if (a.icon != null) {
                float cx = rect.centerX(), cy = rect.centerY();
                rect.set(cx - icon / 2, cy - icon / 2, cx + icon / 2, cy + icon / 2);
                canvas.drawBitmap(a.icon, null, rect, iconPaint);
                tileRect(i);
            }
            CharSequence label = TextUtils.ellipsize(a.label, labelPaint, cell - 4 * dp, TextUtils.TruncateAt.END);
            canvas.drawText(label, 0, label.length(), rect.centerX(), rect.bottom + 18 * dp, labelPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return gestures.onTouchEvent(e) || super.onTouchEvent(e);
    }
}
