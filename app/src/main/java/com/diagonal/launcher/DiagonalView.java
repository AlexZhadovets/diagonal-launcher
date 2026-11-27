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
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws every app as a coloured diagonal stripe (bottom-left → top-right).
 *
 * Coordinates: s = (x + y) / √2 is the position across the stripes (the "scroll"
 * axis, running top-left → bottom-right); t = (y − x) / √2 is the position along a stripe.
 * Rotating the canvas by 45° turns these into plain local x (= s) and y (= t).
 */
public class DiagonalView extends View {

    public interface Listener {
        void onLaunch(AppEntry app, View source, Rect bounds);
        void onOrderChanged(List<AppEntry> apps);
        void onReloadNeeded();
    }

    private static final float INV_SQRT2 = 0.70710677f;
    private static final float SQRT2 = 1.4142135f;
    private static final int BG = 0xFF101014;

    private final List<AppEntry> apps = new ArrayList<>();
    private final float dp;
    private final float spacing;
    private final float band;
    private final float capMargin;
    private final float iconSize;
    private final float iconGap;

    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();
    private final RectF iconRect = new RectF();

    private final OverScroller scroller;
    private final GestureDetector gestures;

    private Listener listener;
    private String emptyText = "";
    private float scroll;

    // Drag-to-reorder state.
    private int dragIndex = -1;
    private float dragS;
    private float lastTouchS;
    private boolean autoScrollPosted;
    private int dragPointerId = -1;
    private List<AppEntry> pendingApps;
    private boolean wasFlinging;
    private final float baselineOffset;
    private final Rect tapBounds = new Rect();

    public DiagonalView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        spacing = 84 * dp;
        band = 58 * dp;
        capMargin = 28 * dp;
        iconSize = 34 * dp;
        iconGap = 10 * dp;

        bandPaint.setStyle(Paint.Style.FILL);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(3 * dp);
        outlinePaint.setColor(0xFFFFFFFF);

        textPaint.setTextSize(17 * dp);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textPaint.setTextAlign(Paint.Align.LEFT);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        baselineOffset = -(fm.ascent + fm.descent) / 2f;

        hintPaint.setTextSize(16 * dp);
        hintPaint.setColor(0x99FFFFFF);
        hintPaint.setTextAlign(Paint.Align.CENTER);

        scroller = new OverScroller(context);
        gestures = new GestureDetector(context, new Gestures());
        gestures.setIsLongpressEnabled(true);
        setHapticFeedbackEnabled(true);
    }

    public void setListener(Listener l) { listener = l; }

    public void setEmptyText(String text) { emptyText = text; invalidate(); }

    public void setApps(List<AppEntry> list) {
        if (dragIndex >= 0) { pendingApps = new ArrayList<>(list); return; } // applied when the drag ends
        apps.clear();
        apps.addAll(list);
        clampScroll();
        invalidate();
    }

    public int iconSizePx() { return Math.round(iconSize); }

    public void scrollToStart() {
        scroller.forceFinished(true);
        scroller.startScroll(Math.round(scroll), 0, -Math.round(scroll), 0, 350);
        postInvalidateOnAnimation();
    }

    // ---- geometry -------------------------------------------------------

    private float startOffset() { return spacing; }

    private float screenS() { return (getWidth() + getHeight()) * INV_SQRT2; }

    private float slotS(int i) { return startOffset() + i * spacing - scroll; }

    private static float sOf(float x, float y) { return (x + y) * INV_SQRT2; }

    private float maxScroll() {
        if (apps.isEmpty()) return 0f;
        float content = startOffset() * 2 + (apps.size() - 1) * spacing;
        return Math.max(0f, content - screenS());
    }

    private void clampScroll() {
        float max = maxScroll();
        if (scroll < 0) scroll = 0;
        if (scroll > max) scroll = max;
    }

    /** Visible part of the stripe at position s: fills out[0]=tMin, out[1]=tMax. */
    private void visibleT(float s, float[] out) {
        float w = getWidth(), h = getHeight();
        float sc = Math.max(1f, Math.min(s, screenS() - 1f));
        out[0] = Math.max(sc - SQRT2 * w, -sc);
        out[1] = Math.min(sc, SQRT2 * h - sc);
    }

    private final float[] tRange = new float[2];

    private int indexAt(float x, float y) {
        if (apps.isEmpty()) return -1;
        float s = sOf(x, y);
        int i = Math.round((s + scroll - startOffset()) / spacing);
        if (i < 0 || i >= apps.size()) return -1;
        if (Math.abs(s - slotS(i)) > band / 2f + 2 * dp) return -1; // tap in the gap between stripes
        float t = (y - x) * INV_SQRT2;
        visibleT(slotS(i), tRange);
        if (t < tRange[0] + capMargin - band / 2f || t > tRange[1] - capMargin + band / 2f) return -1;
        return i;
    }

    // ---- drawing --------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(BG);
        int n = apps.size();
        if (n == 0) {
            canvas.drawText(emptyText, getWidth() / 2f, getHeight() / 2f, hintPaint);
            return;
        }
        float S = screenS();
        int first = Math.max(0, (int) Math.floor((scroll - startOffset() - band) / spacing));
        int last = Math.min(n - 1, (int) Math.ceil((scroll + S - startOffset() + band) / spacing));

        canvas.save();
        canvas.rotate(45f);
        for (int i = first; i <= last; i++) {
            if (i == dragIndex) continue;
            drawStripe(canvas, apps.get(i), slotS(i), false);
        }
        if (dragIndex >= 0 && dragIndex < n) {
            drawStripe(canvas, apps.get(dragIndex), dragS, true);
        }
        canvas.restore();
    }

    private void drawStripe(Canvas canvas, AppEntry app, float s, boolean lifted) {
        visibleT(s, tRange);
        float tMin = tRange[0] + capMargin;
        float tMax = tRange[1] - capMargin;
        float half = (lifted ? band * 1.12f : band) / 2f;
        if (tMax - tMin < 2 * half) {               // stripe near a corner: keep a round dot
            float mid = (tRange[0] + tRange[1]) / 2f;
            tMin = mid - half;
            tMax = mid + half;
        }
        tmpRect.set(s - half, tMin, s + half, tMax);
        bandPaint.setColor(app.color);
        canvas.drawRoundRect(tmpRect, half, half, bandPaint);
        if (lifted) canvas.drawRoundRect(tmpRect, half, half, outlinePaint);

        // Icon + label, reading bottom-left → top-right (local −t direction).
        float length = tMax - tMin;
        float avail = length - 2 * half - iconSize - iconGap;
        if (length < iconSize + 2 * half * 0.5f) return;
        float mid = (tMin + tMax) / 2f;

        textPaint.setColor(app.textColor);
        if (app.labelWidth < 0) app.labelWidth = textPaint.measureText(app.label);
        CharSequence label;
        float textW;
        if (avail <= 24 * dp) {
            label = "";
            textW = 0f;
        } else if (app.labelWidth <= avail) {
            label = app.label;                       // common case: no allocation
            textW = app.labelWidth;
        } else {
            int bucket = (int) (avail / (4 * dp));   // quantised so we re-ellipsize rarely
            if (bucket != app.ellBucket) {
                app.ellLabel = TextUtils.ellipsize(app.label, textPaint, bucket * 4 * dp, TextUtils.TruncateAt.END);
                app.ellWidth = textPaint.measureText(app.ellLabel, 0, app.ellLabel.length());
                app.ellBucket = bucket;
            }
            label = app.ellLabel;
            textW = app.ellWidth;
        }
        float groupW = iconSize + (textW > 0 ? iconGap + textW : 0f);

        canvas.save();
        canvas.rotate(-90f, s, mid);
        float x0 = s - groupW / 2f;
        iconRect.set(x0, mid - iconSize / 2f, x0 + iconSize, mid + iconSize / 2f);
        if (app.icon != null) canvas.drawBitmap(app.icon, null, iconRect, iconPaint);
        if (textW > 0) {
            float baseline = mid + baselineOffset;
            canvas.drawText(label, 0, label.length(), x0 + iconSize + iconGap, baseline, textPaint);
        }
        canvas.restore();
    }

    // ---- touch ----------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean handled = gestures.onTouchEvent(e);
        int action = e.getActionMasked();
        if (dragIndex >= 0) {
            int pi = e.findPointerIndex(dragPointerId);
            if (action == MotionEvent.ACTION_POINTER_UP && e.getPointerId(e.getActionIndex()) == dragPointerId) {
                endDrag();
            } else if (action == MotionEvent.ACTION_MOVE && pi >= 0) {
                float s = sOf(e.getX(pi), e.getY(pi));
                dragS += s - lastTouchS;
                lastTouchS = s;
                updateDragSlot();
                ensureAutoScroll();
                invalidate();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                endDrag();
            }
            return true;
        }
        return handled || super.onTouchEvent(e);
    }

    private void updateDragSlot() {
        int target = Math.round((dragS + scroll - startOffset()) / spacing);
        target = Math.max(0, Math.min(apps.size() - 1, target));
        if (target != dragIndex) {
            AppEntry moved = apps.remove(dragIndex);
            apps.add(target, moved);
            dragIndex = target;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    private void endDrag() {
        dragIndex = -1;
        autoScrollPosted = false;
        removeCallbacks(autoScroll);
        dragPointerId = -1;
        if (listener != null) listener.onOrderChanged(new ArrayList<>(apps));
        if (pendingApps != null) {      // an app was installed/removed during the drag
            List<AppEntry> p = pendingApps;
            pendingApps = null;
            if (listener != null) listener.onReloadNeeded();
            else setApps(p);
        }
        invalidate();
    }

    private void ensureAutoScroll() {
        if (!autoScrollPosted) {
            autoScrollPosted = true;
            postOnAnimation(autoScroll);
        }
    }

    private final Runnable autoScroll = new Runnable() {
        @Override public void run() {
            if (dragIndex < 0) { autoScrollPosted = false; return; }
            float edge = spacing * 1.2f;
            float S = screenS();
            float speed = 0f;
            if (lastTouchS < edge) speed = -(edge - lastTouchS) / edge * 18 * dp;
            else if (lastTouchS > S - edge) speed = (lastTouchS - (S - edge)) / edge * 18 * dp;
            if (speed != 0f) {
                float before = scroll;
                scroll += speed;
                clampScroll();
                if (scroll != before) {
                    updateDragSlot();
                    invalidate();
                }
                postOnAnimation(this);
            } else {
                autoScrollPosted = false;
            }
        }
    };

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.getCurrX();
            clampScroll();
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        clampScroll();
    }

    private final class Gestures extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDown(MotionEvent e) {
            wasFlinging = !scroller.isFinished();
            scroller.forceFinished(true);
            return true;
        }

        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            if (dragIndex >= 0) return true;
            scroll += (dx + dy) * INV_SQRT2;
            clampScroll();
            invalidate();
            return true;
        }

        @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            if (dragIndex >= 0) return true;
            int v = Math.round(-(vx + vy) * INV_SQRT2);
            scroller.fling(Math.round(scroll), 0, v, 0, 0, Math.round(maxScroll()), 0, 0);
            postInvalidateOnAnimation();
            return true;
        }

        @Override public boolean onSingleTapUp(MotionEvent e) {
            if (wasFlinging) return true;           // a tap during a fling only stops it
            int i = indexAt(e.getX(), e.getY());
            if (i < 0 || listener == null) return false;
            int r = Math.round(band / 2f);
            tapBounds.set(Math.round(e.getX()) - r, Math.round(e.getY()) - r,
                    Math.round(e.getX()) + r, Math.round(e.getY()) + r);
            listener.onLaunch(apps.get(i), DiagonalView.this, new Rect(tapBounds));
            return true;
        }

        @Override public void onLongPress(MotionEvent e) {
            int i = indexAt(e.getX(), e.getY());
            if (i < 0) return;
            scroller.forceFinished(true);
            dragIndex = i;
            dragPointerId = e.getPointerId(0);
            dragS = slotS(i);
            lastTouchS = sOf(e.getX(), e.getY());
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            getParent().requestDisallowInterceptTouchEvent(true);
            invalidate();
        }
    }
}
