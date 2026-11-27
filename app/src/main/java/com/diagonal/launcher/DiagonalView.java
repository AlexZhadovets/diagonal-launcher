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
        void onInfoClick();
        /** Swipe along the stripes: +1 towards the top-right, −1 towards the bottom-left. */
        void onCornerSwipe(int direction);
    }

    public static final int VISIBLE = 5;

    private static final float INV_SQRT2 = 0.70710677f;
    private static final float SQRT2 = 1.4142135f;
    private static final int BG = 0xFF101014;

    private final List<AppEntry> items = new ArrayList<>();
    private float centerPos;
    private int favStart, favEnd; // [favStart, favEnd)

    private final float dp;
    private float spacing, band, iconSize, iconGap, capMargin;

    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint seamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float edgeWidth, sectionBaseline;
    // One white blink on a stripe after jumping to it from search.
    private String flashKey;
    private int flashIndex = -1;
    private long flashStart;
    private static final long FLASH_MS = 650;
    private static final int JUMP_MS = 450;
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

    // Swipes along the stripes open the corner panels instead of scrolling.
    private float downX, downY;
    private int touchMode;            // 0 undecided, 1 scrolling, 2 corner swipe
    private final float cornerMin;

    // Centre "info" stripe content.
    private String infoTitle = "", infoSubtitle = "";
    private android.graphics.Bitmap infoIcon;
    private int infoColor = 0xFF16161C, infoText = 0xFFFFFFFF;
    private final TextPaint infoTitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint infoSubPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private boolean snapshotMode;

    public DiagonalView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;

        bandPaint.setStyle(Paint.Style.FILL);
        dividerPaint.setColor(0x55FFFFFF);
        dividerPaint.setStrokeWidth(2 * dp);

        textPaint.setTypeface(android.os.Build.VERSION.SDK_INT >= 28
                ? Typeface.create(Typeface.SANS_SERIF, 900, false)   // extra bold
                : Typeface.create("sans-serif-black", Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.LEFT);

        hintPaint.setTextSize(18 * dp);
        hintPaint.setColor(0x99FFFFFF);
        hintPaint.setTextAlign(Paint.Align.CENTER);

        sectionPaint.setTextSize(12 * dp);
        sectionPaint.setColor(0xCCFFFFFF);
        sectionPaint.setTextAlign(Paint.Align.CENTER);
        sectionPaint.setLetterSpacing(0.2f);
        sectionPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        Paint.FontMetrics sfm = sectionPaint.getFontMetrics();
        sectionBaseline = -(sfm.ascent + sfm.descent) / 2f;
        edgeWidth = 22 * dp;
        edgePaint.setColor(BG);
        flashPaint.setColor(0xFFFFFFFF);

        cornerMin = 56 * dp;
        Typeface heavy = android.os.Build.VERSION.SDK_INT >= 28
                ? Typeface.create(Typeface.SANS_SERIF, 900, false)
                : Typeface.create("sans-serif-black", Typeface.BOLD);
        infoTitlePaint.setTypeface(heavy);
        infoTitlePaint.setTextAlign(Paint.Align.LEFT);
        infoSubPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        infoSubPaint.setTextAlign(Paint.Align.LEFT);

        scroller = new OverScroller(context);
        gestures = new GestureDetector(context, new Gestures());
        setHapticFeedbackEnabled(true);
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        computeSizes(m.widthPixels, m.heightPixels);
    }

    public void setListener(Listener l) { listener = l; }

    /** Content of the centre stripe. */
    public void setInfo(String title, String subtitle, android.graphics.Bitmap icon, int color, int textColor) {
        infoTitle = title == null ? "" : title;
        infoSubtitle = subtitle == null ? "" : subtitle;
        infoIcon = icon;
        infoColor = color;
        infoText = textColor;
        invalidate();
    }

    /** Draws the home screen as it looks on open, for the lock-screen picture. */
    public void drawSnapshot(Canvas c) {
        float saved = scroll;
        scroll = centerScroll();
        snapshotMode = true;
        draw(c);
        snapshotMode = false;
        scroll = saved;
    }

    /** Identifies what the home screen looks like (apps and colours), to skip identical snapshots. */
    public int homeSignature() {
        int h = 17;
        for (int i = Math.max(0, favStart - 3); i < Math.min(items.size(), favEnd + 3); i++) {
            h = h * 31 + items.get(i).key.hashCode();
            h = h * 31 + items.get(i).color;
        }
        return h;
    }

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
        centerPos = favCount > 0 ? favStart + favCount / 2f : list.size() / 2f;
        if (recenter) jumpToCenter(); else clampScroll();
        invalidate();
    }

    /** Size of the icon bitmaps to prepare. */
    public int iconSizePx() { return Math.round(Math.min(iconSize, 72 * dp)); } // caps icon memory

    public void jumpToCenter() {
        scroller.forceFinished(true);
        if (getWidth() == 0) { pendingCenter = true; return; }
        scroll = centerScroll();
        clampScroll();
        invalidate();
    }

    /** Scrolls to the app (its A–Z place, or its home slot) and blinks it white once. */
    public void showApp(String key) {
        int idx = -1;
        for (int i = favEnd; i < items.size(); i++) if (items.get(i).key.equals(key)) { idx = i; break; }
        if (idx < 0) for (int i = 0; i < items.size(); i++) if (items.get(i).key.equals(key)) { idx = i; break; }
        if (idx < 0) return;
        float target = (idx + 0.5f) * spacing - screenS() / 2f;
        target = Math.max(minScroll(), Math.min(maxScroll(), target));
        scroller.forceFinished(true);
        int from = Math.round(scroll);
        scroller.startScroll(from, 0, Math.round(target) - from, 0, JUMP_MS);
        flashKey = key;
        flashIndex = idx;
        flashStart = android.os.SystemClock.uptimeMillis() + JUMP_MS;
        postInvalidateOnAnimation();
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
        band = spacing + 1f;                 // no gaps (1px overlap hides seams)
        capMargin = 24 * dp;
        iconSize = Math.min(band * 0.5f, 96 * dp);
        iconGap = Math.max(12 * dp, band * 0.1f);
        float ts = Math.max(18 * dp, Math.min(band * 0.19f, 34 * dp));
        infoTitlePaint.setTextSize(Math.max(28 * dp, Math.min(band * 0.36f, 64 * dp)));
        infoSubPaint.setTextSize(Math.max(14 * dp, Math.min(band * 0.12f, 20 * dp)));
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

    private float centerScroll() { return centerPos * spacing - screenS() / 2f; }

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
        for (int i = first; i <= last; i++) {
            AppEntry a = items.get(i);
            if (a.info) drawInfoStripe(canvas, slotS(i));
            else drawStripe(canvas, a, slotS(i));
            if (flashKey != null && i == flashIndex) drawFlash(canvas, slotS(i));
        }
        if (flashKey != null) {
            // stop blinking even if the stripe scrolled out of view
            if (android.os.SystemClock.uptimeMillis() > flashStart + FLASH_MS) flashKey = null;
            else postInvalidateOnAnimation();
        }
        // Thin, slightly greyer seam where two neighbouring stripes have the same colour.
        float far = S * 2f, seam = 2.5f * dp;
        for (int i = first; i < last; i++) {
            int a = colorOf(items.get(i)), b = colorOf(items.get(i + 1));
            if (ColorExtractor.dist(a, b) > 60) continue;
            float s = (i + 1) * spacing - scroll;
            seamPaint.setColor(ColorExtractor.seamColor(a));
            tmpRect.set(s - seam / 2f, -far, s + seam / 2f, far);
            canvas.drawRect(tmpRect, seamPaint);
        }
        // Thick edges with captions where the sections meet.
        if (favEnd > favStart) {
            if (favStart > 0) drawEdge(canvas, favStart * spacing - scroll, labelUsage);
            if (favEnd < n) drawEdge(canvas, favEnd * spacing - scroll, labelAlpha);
        }
        canvas.restore();
    }

    private int colorOf(AppEntry a) { return a.info ? infoColor : a.color; }

    private void drawInfoStripe(Canvas canvas, float s) {
        float half = band / 2f, far = screenS() * 2f;
        tmpRect.set(s - half, -far, s + half, far);
        bandPaint.setColor(infoColor);
        canvas.drawRect(tmpRect, bandPaint);
        if (snapshotMode) return;

        visibleT(s);
        float tMin = tRange[0] + capMargin, tMax = tRange[1] - capMargin;
        float mid = (tMin + tMax) / 2f;
        float iconW = infoIcon != null ? iconSize + iconGap : 0f;
        float avail = (tMax - tMin) - 2 * iconGap - iconW;
        if (avail < 40 * dp) return;
        infoTitlePaint.setColor(infoText);
        infoSubPaint.setColor(infoText);
        infoSubPaint.setAlpha(200);
        CharSequence t = fit(infoTitle, infoTitlePaint, avail);
        CharSequence st = fit(infoSubtitle, infoSubPaint, avail);
        float tw = infoTitlePaint.measureText(t, 0, t.length());
        float sw = infoSubPaint.measureText(st, 0, st.length());
        float blockW = iconW + Math.max(tw, sw);

        Paint.FontMetrics a = infoTitlePaint.getFontMetrics();
        Paint.FontMetrics b = infoSubPaint.getFontMetrics();
        float titleH = a.descent - a.ascent, subH = st.length() > 0 ? b.descent - b.ascent : 0f;
        float top = -(titleH + subH) / 2f;

        canvas.save();
        canvas.rotate(-90f, s, mid);
        float x0 = s - blockW / 2f;
        if (infoIcon != null) {
            iconRect.set(x0, mid - iconSize / 2f, x0 + iconSize, mid + iconSize / 2f);
            canvas.drawBitmap(infoIcon, null, iconRect, iconPaint);
        }
        float tx = x0 + iconW;
        canvas.drawText(t, 0, t.length(), tx, mid + top - a.ascent, infoTitlePaint);
        if (st.length() > 0) canvas.drawText(st, 0, st.length(), tx, mid + top + titleH - b.ascent, infoSubPaint);
        canvas.restore();
    }

    private static CharSequence fit(String text, TextPaint p, float avail) {
        if (p.measureText(text) <= avail) return text;
        return TextUtils.ellipsize(text, p, avail, TextUtils.TruncateAt.END);
    }

    private void drawFlash(Canvas canvas, float s) {
        long t = android.os.SystemClock.uptimeMillis() - flashStart;
        if (t < 0) return;
        if (t > FLASH_MS) { flashKey = null; return; }
        float a = (float) Math.sin(Math.PI * t / FLASH_MS);
        flashPaint.setAlpha(Math.round(220 * a));
        float half = band / 2f, far = screenS() * 2f;
        tmpRect.set(s - half, -far, s + half, far);
        canvas.drawRect(tmpRect, flashPaint);
    }

    private void drawEdge(Canvas canvas, float s, String caption) {
        if (s < -edgeWidth || s > screenS() + edgeWidth) return;
        float far = screenS() * 2f;
        tmpRect.set(s - edgeWidth / 2f, -far, s + edgeWidth / 2f, far);
        canvas.drawRect(tmpRect, edgePaint);
        if (caption.isEmpty()) return;
        visibleT(s);
        float mid = (tRange[0] + tRange[1]) / 2f;
        canvas.save();
        canvas.rotate(-90f, s, mid);
        canvas.drawText(caption, s, mid + sectionBaseline, sectionPaint);
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
        float half = band / 2f;
        float far = screenS() * 2f;
        // The stripe runs past both screen edges, so there are no empty corners.
        tmpRect.set(s - half, -far, s + half, far);
        bandPaint.setColor(app.color);
        canvas.drawRect(tmpRect, bandPaint);

        // Icon + label go on the part of the centre line that is on screen.
        visibleT(s);
        float tMin = tRange[0] + capMargin;
        float tMax = tRange[1] - capMargin;

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
        if (app.icon != null) {
            iconPaint.setColorFilter(app.iconFilter());
            canvas.drawBitmap(app.icon, null, iconRect, iconPaint);
            iconPaint.setColorFilter(null);
        }
        if (textW > 0) {
            canvas.drawText(label, 0, label.length(), x0 + iconSize + iconGap, mid + baselineOffset, textPaint);
        }
        canvas.restore();
    }

    // ---- touch ----------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean handled = gestures.onTouchEvent(e);
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_UP && touchMode == 2) {
            float along = ((e.getX() - downX) - (e.getY() - downY)) * INV_SQRT2; // + = towards top-right
            if (Math.abs(along) >= cornerMin && listener != null) listener.onCornerSwipe(along > 0 ? 1 : -1);
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touchMode = 0;
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
        scroller.forceFinished(true);
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
            downX = e.getX();
            downY = e.getY();
            touchMode = 0;
            return true;
        }

        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            if (touchMode == 0) {
                float tx = e2.getX() - downX, ty = e2.getY() - downY;
                if (tx * tx + ty * ty < (24 * dp) * (24 * dp)) {
                    // not sure yet: scroll a little anyway unless it clearly runs along the stripes
                    float al = Math.abs(tx - ty), ac = Math.abs(tx + ty);
                    if (al > ac * 2.4f) return true;
                } else {
                    float along = Math.abs(tx - ty), across = Math.abs(tx + ty);
                    touchMode = along > across * 2.4f ? 2 : 1;   // within ±22° of the stripes
                }
            }
            if (touchMode == 2) return true;   // swipe along the stripes: corner panel
            scroll += (dx + dy) * INV_SQRT2;
            clampScroll();
            invalidate();
            return true;
        }

        @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            if (touchMode == 2) return true;
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
            if (items.get(i).info) { listener.onInfoClick(); return true; }
            int r = Math.round(band / 2f);
            Rect bounds = new Rect(Math.round(e.getX()) - r, Math.round(e.getY()) - r,
                    Math.round(e.getX()) + r, Math.round(e.getY()) + r);
            listener.onLaunch(items.get(i), DiagonalView.this, bounds);
            return true;
        }

        @Override public void onLongPress(MotionEvent e) {
            int i = indexAt(e.getX(), e.getY());
            if (i < 0 || listener == null || items.get(i).info) return;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            listener.onLongPress(items.get(i));
        }
    }
}
