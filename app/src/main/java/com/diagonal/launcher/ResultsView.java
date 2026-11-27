package com.diagonal.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Search results: full-width rounded bars in each app's colour, stacked from the
 * bottom (best match, right above the search box) upwards, appearing one by one.
 */
public class ResultsView extends View {

    public interface Listener { void onResultClick(AppEntry app, View v, Rect bounds); }

    private static final long APPEAR_MS = 200;
    private static final long STAGGER_MS = 35;

    private final List<AppEntry> apps = new ArrayList<>();
    private final Map<String, Long> appearAt = new HashMap<>();
    private final float dp;
    private final float rowH, gap, margin, radius, iconSize;
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF iconRect = new RectF();
    private final float baselineOffset;
    private final GestureDetector gestures;
    private Listener listener;

    public ResultsView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        rowH = 68 * dp;
        gap = 8 * dp;
        margin = 12 * dp;
        radius = 22 * dp;
        iconSize = 42 * dp;
        textPaint.setTextSize(21 * dp);
        textPaint.setTypeface(android.os.Build.VERSION.SDK_INT >= 28
                ? Typeface.create(Typeface.SANS_SERIF, 900, false)
                : Typeface.create("sans-serif-black", Typeface.BOLD));
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        baselineOffset = -(fm.ascent + fm.descent) / 2f;
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                int i = indexAt(e.getY());
                if (i < 0) { if (listener != null) listener.onResultClick(null, ResultsView.this, null); return true; }
                rowRect(i);
                Rect b = new Rect();
                rect.round(b);
                if (listener != null) listener.onResultClick(apps.get(i), ResultsView.this, b);
                return true;
            }
        });
    }

    public void setListener(Listener l) { listener = l; }

    public AppEntry first() { return apps.isEmpty() ? null : apps.get(0); }

    public void setApps(List<AppEntry> list) {
        long now = SystemClock.uptimeMillis();
        Map<String, Long> next = new HashMap<>();
        int fresh = 0;
        for (AppEntry a : list) {
            Long t = appearAt.get(a.key);
            if (t == null) t = now + (fresh++) * STAGGER_MS;   // new bars come in one by one
            next.put(a.key, t);
        }
        appearAt.clear();
        appearAt.putAll(next);
        apps.clear();
        apps.addAll(list);
        postInvalidateOnAnimation();
    }

    /** Row 0 sits at the bottom. */
    private void rowRect(int i) {
        float bottom = getHeight() - gap - i * (rowH + gap);
        rect.set(margin, bottom - rowH, getWidth() - margin, bottom);
    }

    private int indexAt(float y) {
        float fromBottom = getHeight() - gap - y;
        if (fromBottom < 0) return -1;
        int i = (int) (fromBottom / (rowH + gap));
        float within = fromBottom - i * (rowH + gap);
        if (within > rowH || i >= apps.size()) return -1;
        return i;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        boolean animating = false;
        for (int i = 0; i < apps.size(); i++) {
            rowRect(i);
            if (rect.bottom < 0) break;                       // the rest are off screen
            AppEntry a = apps.get(i);
            Long start = appearAt.get(a.key);
            float p = start == null ? 1f : Math.min(1f, Math.max(0f, (now - start) / (float) APPEAR_MS));
            if (p < 1f) animating = true;
            if (p <= 0f) continue;
            float ease = 1f - (1f - p) * (1f - p);
            float dy = (1f - ease) * 24 * dp;
            rect.offset(0, dy);
            int alpha = Math.round(255 * ease);

            barPaint.setColor(a.color);
            barPaint.setAlpha(alpha);
            canvas.drawRoundRect(rect, radius, radius, barPaint);

            float cy = rect.centerY();
            float ix = rect.left + 14 * dp;
            iconRect.set(ix, cy - iconSize / 2f, ix + iconSize, cy + iconSize / 2f);
            if (a.icon != null) {
                iconPaint.setAlpha(alpha);
                canvas.drawBitmap(a.icon, null, iconRect, iconPaint);
            }
            textPaint.setColor(a.textColor);
            textPaint.setAlpha(alpha);
            float tx = iconRect.right + 16 * dp;
            CharSequence label = TextUtils.ellipsize(a.label, textPaint, rect.right - 16 * dp - tx,
                    TextUtils.TruncateAt.END);
            canvas.drawText(label, 0, label.length(), tx, cy + baselineOffset, textPaint);
        }
        if (animating) postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return gestures.onTouchEvent(e) || super.onTouchEvent(e);
    }
}
