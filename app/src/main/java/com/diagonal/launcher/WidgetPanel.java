package com.diagonal.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * Widget window growing out of the bottom-left corner with a diagonal edge.
 * Visible region: every point with x + (height − y) ≤ legs.
 *
 * Cards are stacked from the bottom up and each one is made exactly as wide as the
 * triangle allows at its height, so they form a staircase that fits the shape.
 * Cards that would be too narrow are hidden. Level 1 peeks (only the bottom card),
 * level 2 is the full window.
 */
public class WidgetPanel extends FrameLayout {

    private final float dp;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tri = new Path();
    private float legs;
    private int level;
    private ValueAnimator anim;
    private int insetTop, insetBottom;
    private Runnable onEmptyTap;

    public WidgetPanel(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        bg.setColor(0xF7000000);
        edge.setColor(0x33FFFFFF);
        edge.setStrokeWidth(1.5f * dp);
        setWillNotDraw(false);
        setVisibility(INVISIBLE);   // laid out from the start, so the first swipe already has a size

    }

    public void setOnEmptyTap(Runnable r) { onEmptyTap = r; }

    public void setInsets(int top, int bottom) { insetTop = top; insetBottom = bottom; requestLayout(); }

    public int level() { return level; }

    public boolean contains(float x, float y) { return level > 0 && x + (getHeight() - y) <= legs; }

    private float level1Legs(int w) {
        // just enough for the bottom card (≈128dp tall) to be ≈300dp wide
        int first = getChildCount() > 0 ? getChildAt(0).getLayoutParams().height : Math.round(128 * dp);
        float want = insetBottom + 12 * dp + first + 300 * dp + 24 * dp;
        return Math.min(want, w + first);
    }

    /** Full window: still a triangle (top-right corner stays open), cards above get a slanted edge. */
    private float level2Legs(int w, int h) { return w + h * 0.78f; }

    private float targetLegs(int lvl) {
        if (lvl == 0) return 0f;
        return lvl == 1 ? level1Legs(getWidth()) : level2Legs(getWidth(), getHeight());
    }

    public void setLevel(int newLevel) {
        level = Math.max(0, Math.min(2, newLevel));
        if (getWidth() == 0) { final int l = level; post(() -> setLevel(l)); return; }
        if (level > 0) setVisibility(VISIBLE);
        requestLayout();   // card widths depend on the level
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(legs, targetLegs(level));
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

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (level > 0 && ow > 0) {
            if (anim != null) anim.cancel();
            legs = targetLegs(level);
        }
    }

    // ---- staircase layout ----------------------------------------------

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        float limit = level == 2 ? level2Legs(w, h) : level1Legs(w);
        int pad = Math.round(12 * dp);
        int minW = Math.round(150 * dp);
        int b = insetBottom + pad;              // distance of the next card's bottom from the screen bottom
        boolean stop = false;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            int ch = c.getLayoutParams().height;
            if (stop) {   // keep the order: once a card doesn't fit, none above it shows
                c.setTag(R.id.hidden_card, Boolean.TRUE);
                c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY));
                continue;
            }
            int top = b + ch;
            // width at the card's bottom edge; the triangle edge cuts its top-right corner at 45°
            int cw = Math.round(Math.min(w - 2 * pad, limit - b - 2 * pad));
            int topWidth = Math.round(limit - top - 2 * pad);
            int cut = Math.max(0, Math.min(ch, cw - topWidth));
            if (cw - cut < minW || h - top < insetTop) {
                stop = true;
                c.setTag(R.id.hidden_card, Boolean.TRUE);
                c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY));
                continue;
            }
            c.setTag(R.id.hidden_card, null);
            if (c instanceof SlantCard) ((SlantCard) c).setCut(cut);
            c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
            b = top + pad;
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int bottom) {
        int h = bottom - t;
        int pad = Math.round(12 * dp);
        int b = insetBottom + pad;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getTag(R.id.hidden_card) != null) { c.layout(0, 0, 0, 0); continue; }
            int ch = c.getMeasuredHeight();
            c.layout(pad, h - b - ch, pad + c.getMeasuredWidth(), h - b);
            b += ch + pad;
        }
    }

    // ---- drawing & touch ------------------------------------------------

    private void buildPath() {
        int h = getHeight();
        tri.reset();
        tri.moveTo(0, h);
        tri.lineTo(legs, h);
        tri.lineTo(0, h - legs);
        tri.close();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (legs <= 0) return;
        buildPath();
        canvas.save();
        canvas.clipPath(tri);
        canvas.drawPath(tri, bg);
        super.dispatchDraw(canvas);
        canvas.restore();
        canvas.drawLine(legs, getHeight(), 0, getHeight() - legs, edge);
    }

    private float downX, downY;

    /** Only a real tap (no drag) on empty space, not on a card, steps the window back. */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = e.getX(); downY = e.getY(); }
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            float slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if (Math.abs(e.getX() - downX) < slop && Math.abs(e.getY() - downY) < slop && onEmptyTap != null) {
                onEmptyTap.run();
            }
        }
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && !contains(e.getX(), e.getY())) return false;
        return super.dispatchTouchEvent(e);
    }
}
