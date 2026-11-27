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
 * Draws every app as a big square-ended diagonal stripe (bottom-left → top-right).
 * About {@link #VISIBLE} stripes fit on the screen.
 *
 * Layout of the list (top-left → bottom-right):
 *   [ by usage time, least used … most used ] [ 5 home apps ] [ alphabetical A … Z ]
 * The screen opens centred on the 5 home apps; swiping up reveals A–Z, swiping down
 * reveals the most-used apps.
 *
 * Coordinates: s = (x + y) / √2 runs across the stripes, t = (y − x) / √2 along a stripe.
 * canvas.rotate(45) turns them into local x (= s) and y (= t).
 */
public class DiagonalView extends View {

    public interface Listener {
        void onLaunch(AppEntry app, View source, Rect bounds);
        void onLongPress(AppEntry app);
    }

    public static final int VISIBLE = 5;

    private static final float INV_SQRT2 = 0.70710677f;
    private static final float SQRT2 = 1.4142135f;
    private static final int BG = 0xFF101014;

    private final List<AppEntry> items = new ArrayList<>();
    private int centerIndex;
    private int favStart, favEnd; // [favStart, favEnd)

    private final float dp;
    private float spacing, band, iconSize, iconGap, capMargin;

    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sectionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();
    private final RectF iconRect = new RectF();
    private final float[] tRange = new float[2];
    private float baselineOffset;
    private float textSizeUsed = -1f;

    private final OverScroller scroller;
    private final GestureDetector gestures;

    private Listener listener;
    private String emptyText = "";
    private String labelUsage = "", labelAlpha = "";
    private float scroll;
    private boolean pendingCenter = true;
    private boolean wasFlinging;

    public DiagonalView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;

        bandPaint.setStyle(Paint.Style.FILL);
        dividerPaint.setColor(0x55FFFFFF);
        dividerPaint.setStrokeWidth(2 * dp);

        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textPaint.setTextAlign(Paint.Align.LEFT);

        hintPaint.setTextSize(18 * dp);
        hintPaint.setColor(0x99FFFFFF);
        hintPaint.setTextAlign(Paint.Align.CENTER);

        sectionPaint.setTextSize(13 * dp);
        sectionPaint.setColor(0x99FFFFFF);
        sectionPaint.setTextAlign(Paint.Align.CENTER);
        sectionPaint.setLetterSpacing(0.15f);

        scroller = new OverScroller(context);
        gestures = new GestureDetector(context, new Gestures());
        setHapticFeedbackEnabled(true);
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        computeSizes(m.widthPixels, m.heightPixels);
    }

    public void setListener(Listener l) { listener = l; }

    public void setEmptyText(String text) { emptyText = text; invalidate(); }

    public void setSectionLabels(String usage, String alpha) { labelUsage = usage; labelAlpha = alpha; }

    /**
     * @param list     all stripes in display order
     * @param favStart index of the first home app
     * @param favCount number of home apps
     * @param recenter jump to the home apps
     */
    public void setContent(List<AppEntry> list, int favStart, int favCount, boolean recenter) {
        items.clear();
        items.addAll(list);
        this.favStart = favStart;
        this.favEnd = favStart + favCount;
        centerIndex = favCount > 0 ? favStart + favCount / 2 : Math.max(0, list.size() / 2);
        if (recenter) jumpToCenter(); else clampScroll();
        invalidate();
    }

    /** Size of the icon bitmaps to prepare. */
    public int iconSizePx() { return Math.round(iconSize); }

    public void jumpToCenter() {
        scroller.forceFinished(true);
        if (getWidth() == 0) { pendingCenter = true; return; }
        scroll = centerScroll();
        clampScroll();
        invalidate();
    }

    public void animateToCenter() {
        scroller.forceFinished(true);
        int from = Math.round(scroll);
        scroller.startScroll(from, 0, Math.round(centerScroll()) - from, 0, 350);
        postInvalidateOnAnimation();
    }

    // ---- geometry -------------------------------------------------------

    private void computeSizes(int w, int h) {
        float S = (w + h) * INV_SQRT2;
        spacing = S / VISIBLE;
        band = spacing * 0.9f;
        capMargin = 14 * dp;
        iconSize = Math.min(band * 0.5f, 96 * dp);
        iconGap = Math.max(12 * dp, band * 0.1f);
        float ts = Math.max(18 * dp, Math.min(band * 0.19f, 34 * dp));
        if (ts != textSizeUsed) {
            textSizeUsed = ts;
            textPaint.setTextSize(ts);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            baselineOffset = -(fm.ascent + fm.descent) / 2f;
            for (AppEntry a : items) { a.labelWidth = -1f; a.ellBucket = -1; }
        }
    }

    private float screenS() { return (getWidth() + getHeight()) * INV_SQRT2; }

    private float slotS(int i) { return (i + 0.5f) * spacing - scroll; }

    private static float sOf(float x, float y) { return (x + y) * INV_SQRT2; }

    private float centerScroll() { return (centerIndex + 0.5f) * spacing - screenS() / 2f; }

    private float minScroll() { return Math.min(0f, centerScroll()); }

    private float maxScroll() { return Math.max(items.size() * spacing - screenS(), centerScroll()); }

    private void clampScroll() {
        float min = minScroll(), max = maxScroll();
        if (scroll < min) scroll = min;
        if (scroll > max) scroll = max;
    }

    /** Visible part of the stripe at position s: tRange[0]=tMin, tRange[1]=tMax. */
    private void visibleT(float s) {
        float w = getWidth(), h = getHeight();
        float sc = Math.max(1f, Math.min(s, screenS() - 1f));
        tRange[0] = Math.max(sc - SQRT2 * w, -sc);
        tRange[1] = Math.min(sc, SQRT2 * h - sc);
    }

    private int indexAt(float x, float y) {
        if (items.isEmpty()) return -1;
        float s = sOf(x, y);
        int i = (int) Math.floor((s + scroll) / spacing);
        if (i < 0 || i >= items.size()) return -1;
        if (Math.abs(s - slotS(i)) > band / 2f + 2 * dp) return -1; // gap between stripes
        return i;
    }

    // ---- drawing --------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(BG);
        int n = items.size();
        if (n == 0) {
            canvas.drawText(emptyText, getWidth() / 2f, getHeight() / 2f, hintPaint);
            return;
        }
        float S = screenS();
        int first = Math.max(0, (int) Math.floor((scroll - band) / spacing));
        int last = Math.min(n - 1, (int) Math.ceil((scroll + S + band) / spacing));

        canvas.save();
        canvas.rotate(45f);
        for (int i = first; i <= last; i++) drawStripe(canvas, items.get(i), slotS(i));
        // Thin dividers + captions between the three sections.
        if (favEnd > favStart) {
            if (favStart > 0) drawDivider(canvas, favStart * spacing - scroll, labelUsage, true);
            if (favEnd < n) drawDivider(canvas, favEnd * spacing - scroll, labelAlpha, false);
        }
        canvas.restore();
    }

    private void drawDivider(Canvas canvas, float s, String caption, boolean before) {
        if (s < 0 || s > screenS()) return;
        visibleT(s);
        canvas.drawLine(s, tRange[0], s, tRange[1], dividerPaint);
        if (caption.isEmpty()) return;
        float mid = (tRange[0] + tRange[1]) / 2f;
        canvas.save();
        canvas.rotate(-90f, s, mid);
        // caption sits on the side of the section it names
        float off = before ? -6 * dp : 6 * dp - sectionPaint.ascent();
        canvas.drawText(caption, s, mid + off, sectionPaint);
        canvas.restore();
    }

    private void drawStripe(Canvas canvas, AppEntry app, float s) {
        visibleT(s);
        float tMin = tRange[0] + capMargin;
        float tMax = tRange[1] - capMargin;
        float half = band / 2f;
        if (tMax - tMin < 2 * half) {               // near a corner: keep a small square
            float mid = (tRange[0] + tRange[1]) / 2f;
            tMin = mid - half;
            tMax = mid + half;
        }
        tmpRect.set(s - half, tMin, s + half, tMax);
        bandPaint.setColor(app.color);
        canvas.drawRect(tmpRect, bandPaint);         // sharp, square ends

        float length = tMax - tMin;
        if (length < iconSize + 2 * iconGap) return;
        float avail = length - 3 * iconGap - iconSize;
        float mid = (tMin + tMax) / 2f;

        textPaint.setColor(app.textColor);
        if (app.labelWidth < 0) app.labelWidth = textPaint.measureText(app.label);
        CharSequence label;
        float textW;
        if (avail <= 40 * dp) {
            label = "";
            textW = 0f;
        } else if (app.labelWidth <= avail) {
            label = app.label;                       // common case: no allocation
            textW = app.labelWidth;
        } else {
            int bucket = (int) (avail / (6 * dp));   // quantised so we re-ellipsize rarely
            if (bucket != app.ellBucket) {
                app.ellLabel = TextUtils.ellipsize(app.label, textPaint, bucket * 6 * dp, TextUtils.TruncateAt.END);
                app.ellWidth = textPaint.measureText(app.ellLabel, 0, app.ellLabel.length());
                app.ellBucket = bucket;
            }
            label = app.ellLabel;
            textW = app.ellWidth;
        }
        float groupW = iconSize + (textW > 0 ? iconGap + textW : 0f);

        canvas.save();
        canvas.rotate(-90f, s, mid);                 // text reads bottom-left → top-right
        float x0 = s - groupW / 2f;
        iconRect.set(x0, mid - iconSize / 2f, x0 + iconSize, mid + iconSize / 2f);
        if (app.icon != null) canvas.drawBitmap(app.icon, null, iconRect, iconPaint);
        if (textW > 0) {
            canvas.drawText(label, 0, label.length(), x0 + iconSize + iconGap, mid + baselineOffset, textPaint);
        }
        canvas.restore();
    }

    // ---- touch ----------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean handled = gestures.onTouchEvent(e);
        return handled || super.onTouchEvent(e);
    }

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
        float oldSpacing = spacing;
        float oldMiddle = scroll + (oldw + oldh) * INV_SQRT2 / 2f;
        computeSizes(w, h);
        if (pendingCenter || oldw == 0) {
            pendingCenter = false;
            scroll = centerScroll();
        } else {
            // keep the same stripe in the middle after rotation
            scroll = oldMiddle / oldSpacing * spacing - screenS() / 2f;
        }
        clampScroll();
    }

    private final class Gestures extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDown(MotionEvent e) {
            wasFlinging = !scroller.isFinished();
            scroller.forceFinished(true);
            return true;
        }

        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            scroll += (dx + dy) * INV_SQRT2;
            clampScroll();
            invalidate();
            return true;
        }

        @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            int v = Math.round(-(vx + vy) * INV_SQRT2);
            scroller.fling(Math.round(scroll), 0, v, 0,
                    Math.round(minScroll()), Math.round(maxScroll()), 0, 0);
            postInvalidateOnAnimation();
            return true;
        }

        @Override public boolean onSingleTapUp(MotionEvent e) {
            if (wasFlinging) return true;            // a tap during a fling only stops it
            int i = indexAt(e.getX(), e.getY());
            if (i < 0 || listener == null) return false;
            int r = Math.round(band / 2f);
            Rect bounds = new Rect(Math.round(e.getX()) - r, Math.round(e.getY()) - r,
                    Math.round(e.getX()) + r, Math.round(e.getY()) + r);
            listener.onLaunch(items.get(i), DiagonalView.this, bounds);
            return true;
        }

        @Override public void onLongPress(MotionEvent e) {
            int i = indexAt(e.getX(), e.getY());
            if (i < 0 || listener == null) return;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            listener.onLongPress(items.get(i));
        }
    }
}
