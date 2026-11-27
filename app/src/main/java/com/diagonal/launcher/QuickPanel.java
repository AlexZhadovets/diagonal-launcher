package com.diagonal.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * Quick settings in a black triangle growing out of the top-right corner.
 * Level 1 shows the first {@link #LEVEL1_TILES} tiles, level 2 shows all of them.
 * The triangle is every point with (width − x) + y ≤ legs.
 */
public class QuickPanel extends View {

    public interface Listener {
        void onTile(Tile tile);
        void onNotification(Row row);
        void onClearNotifications();
    }

    /** One notification line in the big triangle. */
    public static final class Row {
        String title, text, key;
        int color, ink;
        android.graphics.Bitmap icon;
        android.graphics.ColorFilter filter;
        android.app.PendingIntent intent;
        boolean autoCancel;
        final android.graphics.RectF box = new android.graphics.RectF();
        boolean shown;
    }

    public static final class Tile {
        final int id;
        final int icon;
        String label;
        boolean active;

        Tile(int id, int icon, String label) {
            this.id = id;
            this.icon = icon;
            this.label = label;
        }
    }

    public static final int LEVEL1_TILES = 4;

    private final List<Tile> tiles = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Row clearRow = new Row();
    private final Paint rowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint rowTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint rowText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF tmpIcon = new android.graphics.RectF();
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private String clearLabel = "Clear all";
    private final List<float[]> cells = new ArrayList<>(); // cx, cy, d
    private final float dp;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path tri = new Path();
    private final GestureDetector gestures;
    private Listener listener;

    private float legs;          // current size of the triangle
    private int level;           // 0 closed, 1 small, 2 big
    private float radius, cell, topInset;
    private ValueAnimator anim;

    public QuickPanel(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        bg.setColor(0xF7000000);
        edge.setColor(0x33FFFFFF);
        edge.setStrokeWidth(1.5f * dp);
        label.setColor(0xDDFFFFFF);
        label.setTextSize(11 * dp);
        label.setTextAlign(Paint.Align.CENTER);
        rowTitle.setTextSize(14 * dp);
        rowTitle.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD));
        rowText.setTextSize(13 * dp);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (level == 2 && listener != null) {
                    for (Row r : rows) {
                        if (r.shown && r.box.contains(e.getX(), e.getY())) { listener.onNotification(r); return true; }
                    }
                    if (clearRow.shown && clearRow.box.contains(e.getX(), e.getY())) {
                        listener.onClearNotifications();
                        return true;
                    }
                }
                int i = tileAt(e.getX(), e.getY());
                if (i >= 0 && listener != null) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    listener.onTile(tiles.get(i));
                }
                return true;
            }
        });
        gestures.setOnDoubleTapListener(null);   // quick taps on tiles must each count
        gestures.setIsLongpressEnabled(false);
        setVisibility(INVISIBLE);   // laid out from the start, so the first swipe already has a size
    }

    public void setListener(Listener l) { listener = l; }

    public void setTiles(List<Tile> list) { tiles.clear(); tiles.addAll(list); invalidate(); }

    public void setNotifications(List<Row> list, String clearText) {
        rows.clear();
        rows.addAll(list);
        clearLabel = clearText;
        if (level == 2 && Math.abs(bigLegs() - legs) > 1f && (anim == null || !anim.isRunning())) setLevel(2);
        invalidate();
    }

    public void setTopInset(float px) { topInset = px; layoutCells(); invalidate(); }

    public int level() { return level; }

    public boolean contains(float x, float y) { return level > 0 && (getWidth() - x) + y <= legs; }

    public void setLevel(int newLevel) {
        level = Math.max(0, Math.min(2, newLevel));
        if (getWidth() == 0) { final int l = level; post(() -> setLevel(l)); return; }
        float target = level == 0 ? 0f : level == 1 ? legsFor(LEVEL1_TILES) : bigLegs();
        if (level > 0) setVisibility(VISIBLE);
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(legs, target);
        anim.setDuration(260);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> { legs = (float) a.getAnimatedValue(); invalidate(); });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (level == 0) setVisibility(INVISIBLE);
            }
        });
        anim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutCells();
    }

    /** Grid cells sorted by distance from the top-right corner (nearest first). */
    private void layoutCells() {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        cells.clear();
        cell = Math.min(w / 4.2f, 96 * dp);
        ROW_H = 54 * dp;
        ROW_GAP = 6 * dp;
        radius = cell * 0.30f;
        float pad = 6 * dp;
        int cols = (int) ((w - pad) / cell);
        int rows = (int) ((h - topInset) / cell);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                float cx = w - pad - cell * (col + 0.5f);
                float cy = topInset + pad + cell * (row + 0.5f) - 6 * dp;
                float d = (w - cx) + cy;
                cells.add(new float[]{cx, cy, d, Math.abs(row - col)});
            }
        }
        cells.sort((a, b) -> a[2] != b[2] ? Float.compare(a[2], b[2]) : Float.compare(a[3], b[3]));
        if (level > 0) {
            if (anim != null) anim.cancel();
            legs = level == 1 ? legsFor(LEVEL1_TILES) : bigLegs();
        }
    }

    /** Big triangle: all tiles, plus room for notification rows under them. */
    private float bigLegs() {
        float tilesOnly = legsFor(tiles.size());
        if (rows.isEmpty()) return tilesOnly;
        float rowsNeed = tilesBottom() + (Math.min(rows.size(), 5) + 1) * (ROW_H + ROW_GAP) + 190 * dp;
        return Math.max(tilesOnly, Math.min(rowsNeed, getWidth() + getHeight() * 0.8f));
    }

    private float tilesBottom() {
        float bottom = topInset;
        for (int i = 0; i < tiles.size() && i < cells.size(); i++) {
            bottom = Math.max(bottom, cells.get(i)[1] + radius + 22 * dp);
        }
        return bottom;
    }

    private float ROW_H, ROW_GAP;

    private float legsFor(int count) {
        if (cells.isEmpty() || count == 0) return 0f;
        float[] c = cells.get(Math.min(count, cells.size()) - 1);
        return c[2] + cell * 0.75f + 16 * dp;
    }

    private int tileAt(float x, float y) {
        int visible = level == 2 ? tiles.size() : Math.min(LEVEL1_TILES, tiles.size());
        for (int i = 0; i < visible && i < cells.size(); i++) {
            float[] c = cells.get(i);
            float dx = x - c[0], dy = y - c[1] - radius * 0.3f;
            if (dx * dx + dy * dy <= (cell * 0.5f) * (cell * 0.5f)) return i;
        }
        return -1;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (legs <= 0) return;
        int w = getWidth();
        tri.reset();
        tri.moveTo(w, 0);
        tri.lineTo(w - legs, 0);
        tri.lineTo(w, legs);
        tri.close();
        canvas.drawPath(tri, bg);
        canvas.drawLine(w - legs, 0, w, legs, edge);

        int visible = level == 1 ? Math.min(LEVEL1_TILES, tiles.size()) : tiles.size();
        for (int i = 0; i < visible && i < cells.size(); i++) {
            float[] c = cells.get(i);
            if (c[2] + radius * 1.5f > legs) continue;          // not inside the triangle (yet)
            Tile t = tiles.get(i);
            circle.setColor(t.active ? 0xFFFFFFFF : 0xFF2A2A31);
            canvas.drawCircle(c[0], c[1], radius, circle);
            iconPaint.setColor(t.active ? 0xFF111111 : 0xFFFFFFFF);
            Icons.draw(canvas, t.icon, c[0], c[1], radius * 1.1f, iconPaint);
            CharSequence l = TextUtils.ellipsize(t.label, label, cell - 4 * dp, TextUtils.TruncateAt.END);
            canvas.drawText(l, 0, l.length(), c[0], c[1] + radius + 14 * dp, label);
        }
        if (level != 1) drawRows(canvas, w);
    }

    /** Notification rows: right-aligned, each as wide as the triangle allows at its height. */
    private void drawRows(Canvas canvas, int w) {
        float pad = 10 * dp;
        float y = tilesBottom() + 8 * dp;
        int count = Math.min(rows.size(), 5);
        for (int i = 0; i <= count; i++) {
            Row r = i < count ? rows.get(i) : clearRow;
            r.shown = false;
            if (i == count && count == 0) break;
            float bottom = y + (i < count ? ROW_H : ROW_H * 0.7f);
            float width = Math.min(w - 2 * pad, legs - bottom - pad);
            if (width < 170 * dp) break;
            r.box.set(w - pad - width, y, w - pad, bottom);
            r.shown = true;
            if (i < count) {
                rowPaint.setColor(r.color);
                canvas.drawRect(r.box, rowPaint);
                float is = ROW_H - 20 * dp;
                float x = r.box.left + 10 * dp;
                if (r.icon != null) {
                    tmpIcon.set(x, r.box.centerY() - is / 2, x + is, r.box.centerY() + is / 2);
                    bmpPaint.setColorFilter(r.filter);
                    canvas.drawBitmap(r.icon, null, tmpIcon, bmpPaint);
                    x += is + 10 * dp;
                }
                float avail = r.box.right - 10 * dp - x;
                rowTitle.setColor(r.ink);
                rowText.setColor(r.ink);
                rowText.setAlpha(200);
                CharSequence t1 = TextUtils.ellipsize(r.title, rowTitle, avail, TextUtils.TruncateAt.END);
                CharSequence t2 = TextUtils.ellipsize(r.text, rowText, avail, TextUtils.TruncateAt.END);
                canvas.drawText(t1, 0, t1.length(), x, r.box.top + 22 * dp, rowTitle);
                canvas.drawText(t2, 0, t2.length(), x, r.box.top + 41 * dp, rowText);
            } else {
                rowPaint.setColor(0xFF2A2A31);
                canvas.drawRect(r.box, rowPaint);
                rowTitle.setColor(0xFFFFFFFF);
                float tw = rowTitle.measureText(clearLabel);
                canvas.drawText(clearLabel, r.box.centerX() - tw / 2, r.box.centerY() + 5 * dp, rowTitle);
            }
            y = bottom + ROW_GAP;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && !contains(e.getX(), e.getY())) return false;
        return gestures.onTouchEvent(e) || super.onTouchEvent(e);
    }
}
