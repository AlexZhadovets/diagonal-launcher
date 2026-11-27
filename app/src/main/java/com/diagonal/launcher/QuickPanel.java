package com.diagonal.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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
 * Quick settings in a black triangle growing out of the top-right corner
 * (every point with (width − x) + y ≤ legs).
 *
 * Small (level 1): the first 4 tiles plus 2 apps in the free corners of the triangle.
 * Big (level 2): all tiles in a compact 4×4 block, then notifications: the newest 3
 * with text, the rest as round icons flowing down into the corner.
 * Long-press a tile, then tap another one to swap them (the first 4 are the small set).
 */
public class QuickPanel extends View {

    public interface Listener {
        void onTile(Tile tile);
        void onTilesReordered(List<Tile> tiles);
        void onApp(AppEntry app);
        void onAppLongPress(int slot);
        void onNotification(Row row);
        void onClearNotifications();
    }

    /** One notification in the big triangle. */
    public static final class Row {
        String title, text, key;
        int color, ink;
        android.graphics.Bitmap icon;
        android.graphics.ColorFilter filter;
        android.app.PendingIntent intent;
        boolean autoCancel;
        final RectF box = new RectF();
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
    public static final int LEVEL1_APPS = 2;
    private static final int TEXT_ROWS = 3;

    private final List<Tile> tiles = new ArrayList<>();
    private final List<AppEntry> apps = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final RectF clearBox = new RectF();
    private boolean clearShown;
    private String clearLabel = "Clear all";
    private final List<float[]> cells = new ArrayList<>(); // cx, cy, d, |row-col|
    private final float dp;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint rowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint rowTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint rowText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final Path tri = new Path();
    private final GestureDetector gestures;
    private Listener listener;

    private float legs;          // current size of the triangle
    private int level;           // 0 closed, 1 small, 2 big
    private float radius, cell, topInset;
    private final float rowH, rowGap, rowRadius, bubble, bubbleGap;
    private int swapFrom = -1;   // tile waiting for its swap partner
    private ValueAnimator anim;

    public QuickPanel(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        rowH = 62 * dp;
        rowGap = 10 * dp;
        rowRadius = 18 * dp;
        bubble = 42 * dp;
        bubbleGap = 10 * dp;
        bg.setColor(0xF7000000);
        edge.setColor(0x33FFFFFF);
        edge.setStrokeWidth(1.5f * dp);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * dp);
        ring.setColor(0xFFFFFFFF);
        label.setColor(0xDDFFFFFF);
        label.setTextSize(11 * dp);
        label.setTextAlign(Paint.Align.CENTER);
        rowTitle.setTextSize(15 * dp);
        rowTitle.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD));
        rowText.setTextSize(13 * dp);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) { tap(e.getX(), e.getY()); return true; }
            @Override public void onLongPress(MotionEvent e) { longPress(e.getX(), e.getY()); }
        });
        gestures.setOnDoubleTapListener(null);   // quick taps on tiles must each count
        setVisibility(INVISIBLE);   // laid out from the start, so the first swipe already has a size
    }

    public void setListener(Listener l) { listener = l; }

    public void setTiles(List<Tile> list) { tiles.clear(); tiles.addAll(list); invalidate(); }

    public void setApps(List<AppEntry> list) { apps.clear(); apps.addAll(list); invalidate(); }

    public void setNotifications(List<Row> list, String clearText) {
        rows.clear();
        rows.addAll(list);
        clearLabel = clearText;
        if (level == 2) {
            float target = bigLegs();
            if (anim != null && anim.isRunning()) anim.setFloatValues(legs, target);
            else if (Math.abs(target - legs) > 1f) animateTo(target);
        }
        invalidate();
    }

    public void setTopInset(float px) { topInset = px; layoutCells(); invalidate(); }

    public int level() { return level; }

    public boolean contains(float x, float y) { return level > 0 && (getWidth() - x) + y <= legs; }

    public void setLevel(int newLevel) {
        level = Math.max(0, Math.min(2, newLevel));
        swapFrom = -1;
        if (getWidth() == 0) { final int l = level; post(() -> setLevel(l)); return; }
        float target = level == 0 ? 0f : level == 1 ? smallLegs() : bigLegs();
        if (level > 0) setVisibility(VISIBLE);
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(legs, target);
        anim.setDuration(level == 2 ? 320 : 260);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> { legs = (float) a.getAnimatedValue(); invalidate(); });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (level == 0) setVisibility(INVISIBLE);
            }
        });
        anim.start();
    }

    /** Resize without resetting a tile swap in progress. */
    private void animateTo(float target) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(legs, target);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> { legs = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutCells();
    }

    /** A 4×4 block of cells in the corner, nearest-to-the-corner first. */
    private void layoutCells() {
        int w = getWidth();
        if (w == 0) return;
        cells.clear();
        cell = Math.min(w / 4.2f, 96 * dp);
        radius = cell * 0.30f;
        float pad = 6 * dp;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                float cx = w - pad - cell * (col + 0.5f);
                float cy = topInset + pad + cell * (row + 0.5f) - 6 * dp;
                cells.add(new float[]{cx, cy, (w - cx) + cy, Math.abs(row - col), row + col});
            }
        }
        cells.sort((a, b) -> a[4] != b[4] ? Float.compare(a[4], b[4]) : Float.compare(a[3], b[3]));
        if (level > 0) {
            if (anim != null) anim.cancel();
            legs = level == 1 ? smallLegs() : bigLegs();
        }
    }

    private float legsFor(int count) {
        if (cells.isEmpty() || count == 0) return 0f;
        float[] c = cells.get(Math.min(count, cells.size()) - 1);
        return c[2] + cell * 0.75f + 16 * dp;
    }

    /** Small triangle: 4 tiles + 2 apps (the apps sit in the two cells next to tile 4). */
    private float smallLegs() { return legsFor(LEVEL1_TILES + LEVEL1_APPS); }

    private float tilesBottom() {
        float bottom = topInset;
        for (int i = 0; i < tiles.size() && i < cells.size(); i++) {
            bottom = Math.max(bottom, cells.get(i)[1] + radius + 22 * dp);
        }
        return bottom;
    }

    /** Big triangle: tile block, then 3 text rows; the edge must pass below the last full-width row. */
    private float bigLegs() {
        float tilesOnly = legsFor(tiles.size());
        if (rows.isEmpty()) return tilesOnly;
        int text = Math.min(rows.size(), TEXT_ROWS);
        float textBottom = tilesBottom() + 8 * dp + text * (rowH + rowGap);
        int more = rows.size() - text;
        float extra = more > 0 ? Math.min(3, (more + 5) / 6) * (bubble + bubbleGap) + 50 * dp : 50 * dp;
        float need = getWidth() + textBottom + extra + 60 * dp;
        return Math.max(tilesOnly, Math.min(need, getWidth() + getHeight()));
    }

    // ---- touch ------------------------------------------------------------

    private int tileAt(float x, float y) {
        int visible = level == 2 ? tiles.size() : Math.min(LEVEL1_TILES, tiles.size());
        for (int i = 0; i < visible && i < cells.size(); i++) {
            if (hit(cells.get(i), x, y)) return i;
        }
        return -1;
    }

    private int appAt(float x, float y) {
        if (level != 1) return -1;
        for (int k = 0; k < LEVEL1_APPS && k < apps.size(); k++) {
            int ci = LEVEL1_TILES + k;
            if (ci < cells.size() && hit(cells.get(ci), x, y)) return k;
        }
        return -1;
    }

    private boolean hit(float[] c, float x, float y) {
        float dx = x - c[0], dy = y - c[1] - radius * 0.3f;
        return dx * dx + dy * dy <= (cell * 0.5f) * (cell * 0.5f);
    }

    private void tap(float x, float y) {
        if (listener == null) return;
        if (level == 2) {
            for (Row r : rows) {
                if (r.shown && r.box.contains(x, y)) { listener.onNotification(r); return; }
            }
            if (clearShown && clearBox.contains(x, y)) { listener.onClearNotifications(); return; }
        }
        int a = appAt(x, y);
        if (a >= 0) { listener.onApp(apps.get(a)); return; }
        int i = tileAt(x, y);
        if (i < 0) { swapFrom = -1; invalidate(); return; }
        if (swapFrom >= 0) {
            if (i != swapFrom) {
                Tile t = tiles.get(swapFrom);
                tiles.set(swapFrom, tiles.get(i));
                tiles.set(i, t);
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                listener.onTilesReordered(new ArrayList<>(tiles));
            }
            swapFrom = -1;
            invalidate();
            return;
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        listener.onTile(tiles.get(i));
    }

    private void longPress(float x, float y) {
        if (listener == null) return;
        int a = appAt(x, y);
        if (a >= 0) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            listener.onAppLongPress(a);
            return;
        }
        int i = tileAt(x, y);
        if (i >= 0) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            swapFrom = i;      // next tap on another tile swaps them
            invalidate();
        }
    }

    // ---- drawing ----------------------------------------------------------

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
            if (i == swapFrom) canvas.drawCircle(c[0], c[1], radius + 5 * dp, ring);
            iconPaint.setColor(t.active ? 0xFF111111 : 0xFFFFFFFF);
            Icons.draw(canvas, t.icon, c[0], c[1], radius * 1.1f, iconPaint);
            CharSequence l = TextUtils.ellipsize(t.label, label, cell - 4 * dp, TextUtils.TruncateAt.END);
            canvas.drawText(l, 0, l.length(), c[0], c[1] + radius + 14 * dp, label);
        }
        if (level == 1) drawApps(canvas);
        if (level != 1) drawNotifications(canvas, w);
    }

    /** Two apps in the free corners of the small triangle. */
    private void drawApps(Canvas canvas) {
        for (int k = 0; k < LEVEL1_APPS && k < apps.size(); k++) {
            int ci = LEVEL1_TILES + k;
            if (ci >= cells.size()) break;
            float[] c = cells.get(ci);
            if (c[2] + radius * 1.5f > legs) continue;
            AppEntry a = apps.get(k);
            circle.setColor(a.color);
            canvas.drawCircle(c[0], c[1], radius, circle);
            drawIcon(canvas, a.icon, a.iconFilter(), c[0], c[1], radius * 1.25f);
            CharSequence l = TextUtils.ellipsize(a.label, label, cell - 4 * dp, TextUtils.TruncateAt.END);
            canvas.drawText(l, 0, l.length(), c[0], c[1] + radius + 14 * dp, label);
        }
    }

    private void drawIcon(Canvas canvas, android.graphics.Bitmap icon, android.graphics.ColorFilter f,
                          float cx, float cy, float size) {
        if (icon == null) return;
        tmp.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
        bmpPaint.setColorFilter(f);
        canvas.drawBitmap(icon, null, tmp, bmpPaint);
        bmpPaint.setColorFilter(null);
    }

    /** Newest 3 with text, the rest as round icons flowing down towards the corner. */
    private void drawNotifications(Canvas canvas, int w) {
        float pad = 12 * dp;
        float y = tilesBottom() + 8 * dp;
        clearShown = false;
        for (Row r : rows) r.shown = false;
        if (rows.isEmpty()) return;

        int text = Math.min(rows.size(), TEXT_ROWS);
        for (int i = 0; i < text; i++) {
            Row r = rows.get(i);
            float bottom = y + rowH;
            float width = Math.min(w - 2 * pad, legs - bottom - pad);
            if (width < 180 * dp) break;
            r.box.set(w - pad - width, y, w - pad, bottom);
            r.shown = true;
            rowPaint.setColor(r.color);
            canvas.drawRoundRect(r.box, rowRadius, rowRadius, rowPaint);
            float is = 34 * dp;
            float x = r.box.left + 14 * dp;
            drawIcon(canvas, r.icon, r.filter, x + is / 2, r.box.centerY(), is);
            x += is + 12 * dp;
            float avail = r.box.right - 16 * dp - x;
            rowTitle.setColor(r.ink);
            rowText.setColor(r.ink);
            rowText.setAlpha(200);
            CharSequence t1 = TextUtils.ellipsize(r.title, rowTitle, avail, TextUtils.TruncateAt.END);
            CharSequence t2 = TextUtils.ellipsize(r.text, rowText, avail, TextUtils.TruncateAt.END);
            canvas.drawText(t1, 0, t1.length(), x, r.box.top + 26 * dp, rowTitle);
            canvas.drawText(t2, 0, t2.length(), x, r.box.top + 46 * dp, rowText);
            y = bottom + rowGap;
        }

        // the rest: icons, right-aligned rows that get shorter towards the corner
        float ph = 38 * dp;
        float pw = Math.max(rowTitle.measureText(clearLabel) + 32 * dp, 120 * dp);
        int i = text;
        while (i < rows.size()) {
            float bottom = y + bubble;
            // always leave room for "Clear all" and stay on screen
            if (bottom + bubbleGap + ph > Math.min(legs - pad - pw, getHeight() - pad)) break;
            float width = Math.min(w - 2 * pad, legs - bottom - pad);
            int fit = (int) ((width + bubbleGap) / (bubble + bubbleGap));
            if (fit <= 0) break;
            float x = w - pad - bubble;
            for (int k = 0; k < fit && i < rows.size(); k++, i++) {
                Row r = rows.get(i);
                r.box.set(x, y, x + bubble, bottom);
                r.shown = true;
                rowPaint.setColor(r.color);
                canvas.drawCircle(r.box.centerX(), r.box.centerY(), bubble / 2, rowPaint);
                drawIcon(canvas, r.icon, r.filter, r.box.centerX(), r.box.centerY(), bubble * 0.6f);
                x -= bubble + bubbleGap;
            }
            y = bottom + bubbleGap;
        }

        // "Clear all" pill
        float bottom = y + ph;
        if (legs - bottom - pad >= pw) {
            clearBox.set(w - pad - pw, y, w - pad, bottom);
            clearShown = true;
            rowPaint.setColor(0xFF2A2A31);
            canvas.drawRoundRect(clearBox, ph / 2, ph / 2, rowPaint);
            rowTitle.setColor(0xFFFFFFFF);
            float tw = rowTitle.measureText(clearLabel);
            canvas.drawText(clearLabel, clearBox.centerX() - tw / 2, clearBox.centerY() + 5 * dp, rowTitle);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && !contains(e.getX(), e.getY())) return false;
        return gestures.onTouchEvent(e) || super.onTouchEvent(e);
    }
}
