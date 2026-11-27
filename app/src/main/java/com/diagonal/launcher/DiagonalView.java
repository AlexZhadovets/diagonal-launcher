package com.diagonal.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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
        void onInfoLongPress();
        /** 0 previous, 1 play/pause, 2 next, 3 back to the player (from the paused mini cover), 4 alert dot. */
        void onMusicControl(int which);
        /** Swipe along the tablet: +1 next page (players, then notifications), −1 back. */
        void onTabletSwipe(int direction);
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
    private boolean downOnTablet;
    private int pageCount = 1, pageIndex;

    /** Little dots on the tablet: which page (main / players / notifications) is shown. */
    public void setPager(int count, int index) {
        if (count == pageCount && index == pageIndex) return;
        pageCount = count;
        pageIndex = index;
        invalidate();
    }

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

    // ---- centre stripe: clock / notification / music player -------------

    /** What the centre stripe shows; swapped with a smooth cross-fade. */
    private static final class InfoContent {
        String title = "", sub = "";
        android.graphics.Bitmap icon;
        int text = 0xFFFFFFFF;
        boolean music, playing;
        android.graphics.Bitmap mini;   // paused music: small cover + ▶ in the corner of the clock
    }

    private InfoContent infoNow = new InfoContent(), infoPrev;
    private float infoFade = 1f;               // 0 → 1 while switching
    private int infoColorFrom = 0xFF16161C, infoColorTo = 0xFF16161C;
    private android.animation.ValueAnimator infoAnim;
    private final android.animation.ArgbEvaluator argb = new android.animation.ArgbEvaluator();
    private final Paint ctlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path ctlPath = new Path();
    // music position: base + time since update × speed
    private long mPos, mUpd, mDur;
    // corner of the tablet: air-raid alert (0 none, 1 yellow, 2 red) + battery
    private int alert, battery = -1;
    private boolean charging;
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public void setStatus(int alertLevel, int batteryPct, boolean isCharging) {
        if (alertLevel == alert && batteryPct == battery && isCharging == charging) return;
        alert = alertLevel;
        battery = batteryPct;
        charging = isCharging;
        invalidate();
    }
    private float mSpeed;

    /** Content of the centre stripe. A change fades smoothly from the old look to the new one. */
    public void setInfo(String title, String subtitle, android.graphics.Bitmap icon, int color, int textColor) {
        setInfo(title, subtitle, icon, color, textColor, false, false);
    }

    public void setInfo(String title, String subtitle, android.graphics.Bitmap icon, int color, int textColor,
                        boolean music, boolean playing) {
        setInfo(title, subtitle, icon, color, textColor, music, playing, null);
    }

    public void setInfo(String title, String subtitle, android.graphics.Bitmap icon, int color, int textColor,
                        boolean music, boolean playing, android.graphics.Bitmap mini) {
        InfoContent n = new InfoContent();
        n.mini = mini;
        n.title = title == null ? "" : title;
        n.sub = subtitle == null ? "" : subtitle;
        n.icon = icon;
        n.text = textColor;
        n.music = music;
        n.playing = playing;
        boolean sameLook = n.title.equals(infoNow.title) && n.sub.equals(infoNow.sub) && n.icon == infoNow.icon
                && n.music == infoNow.music && n.text == infoNow.text && color == infoColorTo && n.mini == infoNow.mini;
        if (sameLook) {
            infoNow.playing = playing;
            invalidate();
            return;
        }
        boolean onlyText = n.music == infoNow.music && n.icon == infoNow.icon && color == infoColorTo;
        infoColorFrom = currentInfoColor();
        infoColorTo = color;
        infoPrev = infoNow;
        infoNow = n;
        infoText = textColor;
        if (infoAnim != null) infoAnim.cancel();
        infoFade = 0f;
        infoAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        infoAnim.setDuration(onlyText ? 260 : 420);
        infoAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        infoAnim.addUpdateListener(a -> { infoFade = (float) a.getAnimatedValue(); invalidate(); });
        infoAnim.start();
    }

    /** Music position for the progress bar (all times in ms, update time from elapsedRealtime). */
    public void setMusicPosition(long position, long updateTime, float speed, long duration) {
        mPos = position;
        mUpd = updateTime;
        mSpeed = speed;
        mDur = duration;
        invalidate();
    }

    private final float[] rowRange = new float[2];

    /**
     * Visible stretch (in the rotated reading frame, x from left to right) of the row that sits
     * `offset` px across the tablet from its centre line. Rows near the tablet's top and bottom
     * edges see a different part of the screen than the centre line, so each row gets its own.
     */
    private float[] rowRange(float s, float mid, float offset) {
        visibleT(s + offset);
        float tA = tRange[0] + capMargin, tB = tRange[1] - capMargin;
        rowRange[0] = s + mid - tB;
        rowRange[1] = s + mid - tA;
        return rowRange;
    }

    private int currentInfoColor() {
        return infoFade >= 1f ? infoColorTo : (int) argb.evaluate(infoFade, infoColorFrom, infoColorTo);
    }

    private void drawInfoStripe(Canvas canvas, float s) {
        float half = band / 2f, far = screenS() * 2f;
        tmpRect.set(s - half, -far, s + half, far);
        infoColor = currentInfoColor();
        bandPaint.setColor(infoColor);
        canvas.drawRect(tmpRect, bandPaint);
        if (snapshotMode) return;

        visibleT(s);
        float tMin = tRange[0] + capMargin, tMax = tRange[1] - capMargin;
        if (tMax - tMin < 80 * dp) return;
        canvas.save();
        canvas.rotate(-90f, s, (tMin + tMax) / 2f);   // text reads bottom-left → top-right
        if (infoFade < 1f && infoPrev != null) {
            // cross-fade: old content out in the first half, new content in during the second
            if (infoFade < 0.5f) drawInfoContent(canvas, infoPrev, s, tMin, tMax, 1f - infoFade * 2f);
            else drawInfoContent(canvas, infoNow, s, tMin, tMax, infoFade * 2f - 1f);
        } else {
            drawInfoContent(canvas, infoNow, s, tMin, tMax, 1f);
        }
        drawStatus(canvas, s, tMin, tMax);
        drawPager(canvas, s, tMin, tMax);
        canvas.restore();
        if (infoNow.music) postInvalidateDelayed(infoNow.playing ? 500 : 15000);   // progress bar + clock
    }

    /** Draws one look of the centre stripe in the rotated (reading) frame, centred on x = s. */
    private void drawInfoContent(Canvas canvas, InfoContent c, float s, float tMin, float tMax, float alpha) {
        int a = Math.round(255 * alpha);
        float mid = (tMin + tMax) / 2f;
        float len = tMax - tMin;
        float left = s - len / 2f, right = s + len / 2f;
        float btn = 44 * dp, gap = 10 * dp;

        float blockL = left + iconGap, blockR = right - iconGap;
        float miniS = 52 * dp;
        if (!c.music && c.mini != null) {
            // paused music: small cover with ▶ at the top end of the stripe
            float mx = right - gap - miniS;
            iconRect.set(mx, mid - miniS / 2f, mx + miniS, mid + miniS / 2f);
            iconPaint.setAlpha(a);
            canvas.save();
            ctlPath.reset();
            ctlPath.addRoundRect(iconRect, 12 * dp, 12 * dp, Path.Direction.CW);
            canvas.clipPath(ctlPath);
            canvas.drawBitmap(c.mini, null, iconRect, iconPaint);
            canvas.restore();
            iconPaint.setAlpha(255);
            ctlPaint.setColor(0x99000000);
            ctlPaint.setAlpha(Math.round(150 * alpha));
            canvas.drawCircle(iconRect.centerX(), iconRect.centerY(), miniS * 0.3f, ctlPaint);
            ctlPaint.setColor(0xFFFFFFFF);
            ctlPaint.setAlpha(a);
            drawPlayPause(canvas, iconRect.centerX() + 2 * dp, iconRect.centerY(), miniS * 0.22f, false);
            blockR = mx - gap;
        }
        if (c.music) {
            blockL = left + btn + 2 * gap;
            blockR = right - 2 * btn - 3 * gap;
        }
        float avail = blockR - blockL;
        float iconW = c.icon != null ? iconSize + iconGap : 0f;
        float textAvail = avail - iconW;
        if (textAvail < 40 * dp) return;

        infoTitlePaint.setColor(c.text);
        infoTitlePaint.setAlpha(a);
        infoSubPaint.setColor(c.text);
        infoSubPaint.setAlpha(Math.round(200 * alpha));
        CharSequence t = fit(c.title, infoTitlePaint, textAvail);
        CharSequence st = fit(c.sub, infoSubPaint, textAvail);
        float tw = infoTitlePaint.measureText(t, 0, t.length());
        float sw = infoSubPaint.measureText(st, 0, st.length());
        float blockW = iconW + Math.max(tw, sw);
        Paint.FontMetrics fa = infoTitlePaint.getFontMetrics();
        Paint.FontMetrics fb = infoSubPaint.getFontMetrics();
        float titleH = fa.descent - fa.ascent, subH = st.length() > 0 ? fb.descent - fb.ascent : 0f;
        float top = -(titleH + subH) / 2f;
        float x0 = (blockL + blockR) / 2f - blockW / 2f;
        if (c.icon != null) {
            iconRect.set(x0, mid - iconSize / 2f, x0 + iconSize, mid + iconSize / 2f);
            iconPaint.setAlpha(a);
            canvas.drawBitmap(c.icon, null, iconRect, iconPaint);
            iconPaint.setAlpha(255);
        }
        float tx = x0 + iconW;
        canvas.drawText(t, 0, t.length(), tx, mid + top - fa.ascent, infoTitlePaint);
        if (st.length() > 0) canvas.drawText(st, 0, st.length(), tx, mid + top + titleH - fb.ascent, infoSubPaint);

        if (!c.music) return;
        // controls: ⏮ at the start, ⏯ and ⏭ at the end
        ctlPaint.setColor(c.text);
        ctlPaint.setAlpha(a);
        float prevX = left + gap + btn / 2f;
        float nextX = right - gap - btn / 2f;
        float playX = nextX - btn - gap;
        drawSkip(canvas, prevX, mid, btn * 0.42f, false);
        drawSkip(canvas, nextX, mid, btn * 0.42f, true);
        drawPlayPause(canvas, playX, mid, btn * 0.5f, c.playing);

        // the current time, always visible on the player (top end, above the controls' row)
        timePaint.setColor(c.text);
        timePaint.setAlpha(Math.round(230 * alpha));
        timePaint.setTextSize(15 * dp);
        timePaint.setTypeface(infoTitlePaint.getTypeface());
        String now = nowClock();
        float nowW = timePaint.measureText(now);
        float topRight = rowRange(s, mid, -band / 2f + 20 * dp)[1];
        canvas.drawText(now, Math.min(right, topRight) - gap - nowW, mid - band / 2f + 26 * dp, timePaint);

        // progress along the bottom edge, time above its start
        if (mDur > 0) {
            long pos = mPos;
            if (c.playing && mUpd > 0) pos += (long) ((android.os.SystemClock.elapsedRealtime() - mUpd) * mSpeed);
            pos = Math.max(0, Math.min(mDur, pos));
            float by = mid + band / 2f - 14 * dp;
            float[] rr = rowRange(s, mid, band / 2f - 14 * dp);
            float bl = Math.max(left, rr[0]) + gap, br = Math.min(right, rr[1]) - gap;
            if (br - bl < 60 * dp) return;
            barPaint.setStrokeWidth(3 * dp);
            barPaint.setStrokeCap(Paint.Cap.ROUND);
            barPaint.setColor(c.text);
            barPaint.setAlpha(Math.round(70 * alpha));
            canvas.drawLine(bl, by, br, by, barPaint);
            barPaint.setAlpha(a);
            canvas.drawLine(bl, by, bl + (br - bl) * pos / (float) mDur, by, barPaint);
            timePaint.setColor(c.text);
            timePaint.setAlpha(Math.round(190 * alpha));
            timePaint.setTextSize(12 * dp);
            timePaint.setTypeface(infoSubPaint.getTypeface());
            canvas.drawText(clock(pos) + " / " + clock(mDur), bl, by - 7 * dp, timePaint);
        }
    }

    private long clockMinute = -1;
    private String clockText = "";

    /** "HH:mm" of right now, rebuilt once a minute. */
    private String nowClock() {
        long min = System.currentTimeMillis() / 60000;
        if (min != clockMinute) {
            clockMinute = min;
            clockText = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date());
        }
        return clockText;
    }

    private void drawPager(Canvas canvas, float s, float tMin, float tMax) {
        if (pageCount <= 1) return;
        float mid = (tMin + tMax) / 2f;
        float y = mid + band / 2f - (infoNow.music ? 30 : 16) * dp;
        float r = 3 * dp, step = 11 * dp;
        float x0 = s - (pageCount - 1) * step / 2f;
        for (int i = 0; i < pageCount; i++) {
            statusPaint.setStyle(Paint.Style.FILL);
            statusPaint.setColor(infoNow.text);
            statusPaint.setAlpha(i == pageIndex ? 255 : 90);
            canvas.drawCircle(x0 + i * step, y, i == pageIndex ? r * 1.3f : r, statusPaint);
        }
        statusPaint.setAlpha(255);
    }

    /** Top-left corner of the tablet: alert dot (red / yellow) and battery %. */
    private void drawStatus(Canvas canvas, float s, float tMin, float tMax) {
        if (alert == 0 && battery < 0) return;
        float len = tMax - tMin, mid = (tMin + tMax) / 2f;
        float cy = mid - band / 2f + 22 * dp;
        float x = Math.max(s - len / 2f, rowRange(s, mid, cy - mid)[0]) + 10 * dp;
        int ink = infoNow.text;
        if (alert != 0) {
            float r = 10 * dp;
            statusPaint.setStyle(Paint.Style.FILL);
            statusPaint.setColor(alert == 2 ? 0xFFE53935 : 0xFFFFC107);
            canvas.drawCircle(x + r, cy, r, statusPaint);
            // siren: a little dome with a base
            statusPaint.setColor(alert == 2 ? 0xFFFFFFFF : 0xFF2B2100);
            tmpRect.set(x + r - r * 0.45f, cy - r * 0.5f, x + r + r * 0.45f, cy + r * 0.4f);
            canvas.drawArc(tmpRect, 180, 180, true, statusPaint);
            canvas.drawRect(x + r - r * 0.6f, cy + r * 0.0f, x + r + r * 0.6f, cy + r * 0.45f, statusPaint);
            x += 2 * r + 8 * dp;
        }
        if (battery >= 0) {
            float bw = 18 * dp, bh = 10 * dp;
            statusPaint.setStyle(Paint.Style.STROKE);
            statusPaint.setStrokeWidth(1.5f * dp);
            statusPaint.setColor(ink);
            tmpRect.set(x, cy - bh / 2, x + bw, cy + bh / 2);
            canvas.drawRoundRect(tmpRect, 2.5f * dp, 2.5f * dp, statusPaint);
            statusPaint.setStyle(Paint.Style.FILL);
            canvas.drawRect(x + bw, cy - bh * 0.22f, x + bw + 2 * dp, cy + bh * 0.22f, statusPaint);
            statusPaint.setColor(battery <= 15 && !charging ? 0xFFE53935 : ink);
            tmpRect.set(x + 2 * dp, cy - bh / 2 + 2 * dp, x + 2 * dp + (bw - 4 * dp) * battery / 100f, cy + bh / 2 - 2 * dp);
            canvas.drawRect(tmpRect, statusPaint);
            timePaint.setColor(ink);
            timePaint.setAlpha(230);
            timePaint.setTextSize(13 * dp);
            timePaint.setTypeface(infoTitlePaint.getTypeface());
            canvas.drawText(battery + "%" + (charging ? " ⚡" : ""), x + bw + 6 * dp, cy + 4.5f * dp, timePaint);
        }
    }

    private static String clock(long ms) {
        long sec = ms / 1000;
        return (sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
    }

    private void drawSkip(Canvas canvas, float cx, float cy, float r, boolean forward) {
        float d = forward ? 1 : -1;
        ctlPath.reset();
        ctlPath.moveTo(cx - d * r * 0.7f, cy - r * 0.7f);
        ctlPath.lineTo(cx + d * r * 0.4f, cy);
        ctlPath.lineTo(cx - d * r * 0.7f, cy + r * 0.7f);
        ctlPath.close();
        canvas.drawPath(ctlPath, ctlPaint);
        float bx = cx + d * r * 0.55f;
        canvas.drawRect(Math.min(bx, bx + d * r * 0.22f), cy - r * 0.7f, Math.max(bx, bx + d * r * 0.22f), cy + r * 0.7f, ctlPaint);
    }

    private void drawPlayPause(Canvas canvas, float cx, float cy, float r, boolean playing) {
        if (playing) {
            canvas.drawRect(cx - r * 0.55f, cy - r * 0.7f, cx - r * 0.15f, cy + r * 0.7f, ctlPaint);
            canvas.drawRect(cx + r * 0.15f, cy - r * 0.7f, cx + r * 0.55f, cy + r * 0.7f, ctlPaint);
        } else {
            ctlPath.reset();
            ctlPath.moveTo(cx - r * 0.5f, cy - r * 0.75f);
            ctlPath.lineTo(cx + r * 0.75f, cy);
            ctlPath.lineTo(cx - r * 0.5f, cy + r * 0.75f);
            ctlPath.close();
            canvas.drawPath(ctlPath, ctlPaint);
        }
    }

    /** Which music control is under a touch on the centre stripe: 0 prev, 1 play/pause, 2 next, −1 none. */
    private int musicControlAt(int index, float x, float y) {
        if (alert != 0) {   // the alert dot in the top-left corner
            float s0 = slotS(index);
            visibleT(s0);
            float tMax0 = tRange[1] - capMargin;
            float t0 = (y - x) * INV_SQRT2;
            float across = sOf(x, y) - s0;   // negative = top side of the tablet
            if (tMax0 - t0 >= 0 && tMax0 - t0 < 56 * dp && across < -band / 2f + 48 * dp) return 4;
        }
        if (!infoNow.music) {
            if (infoNow.mini == null) return -1;
            float s0 = slotS(index);
            visibleT(s0);
            float tMin0 = tRange[0] + capMargin;
            float t0 = (y - x) * INV_SQRT2;
            return t0 - tMin0 >= 0 && t0 - tMin0 < 52 * dp + 24 * dp ? 3 : -1;   // the mini cover
        }
        float s = slotS(index);
        visibleT(s);
        float tMin = tRange[0] + capMargin, tMax = tRange[1] - capMargin;
        float t = (y - x) * INV_SQRT2;
        float fromLeft = tMax - t;                 // distance from the start (bottom-left end) of the stripe
        float fromRight = t - tMin;
        float btn = 44 * dp, gap = 10 * dp;
        if (fromLeft >= 0 && fromLeft < btn + 2 * gap) return 0;
        if (fromRight >= 0 && fromRight < btn + 1.5f * gap) return 2;
        if (fromRight >= 0 && fromRight < 2 * btn + 3 * gap) return 1;
        return -1;
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
            if (Math.abs(along) >= cornerMin && listener != null) {
                if (downOnTablet) listener.onTabletSwipe(along > 0 ? 1 : -1);   // page the tablet
                else listener.onCornerSwipe(along > 0 ? 1 : -1);
            }
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
            int di = indexAt(downX, downY);
            downOnTablet = di >= 0 && items.get(di).info && pageCount > 1;
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
            if (items.get(i).info) {
                int ctl = musicControlAt(i, e.getX(), e.getY());
                if (ctl >= 0) { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); listener.onMusicControl(ctl); }
                else listener.onInfoClick();
                return true;
            }
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
            if (items.get(i).info) { listener.onInfoLongPress(); return; }
            listener.onLongPress(items.get(i));
        }
    }
}
