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
        setClickable(true);
        setOnClickListener(v -> { if (onEmptyTap != null) onEmptyTap.run(); });
    }

    public void setOnEmptyTap(Runnable r) { onEmptyTap = r; }

    public void setInsets(int top, int bottom) { insetTop = top; insetBottom = bottom; requestLayout(); }

    public int level() { return level; }

    public boolean contains(float x, float y) { return level > 0 && x + (getHeight() - y) <= legs; }

    private float targetLegs(int lvl) {
        if (lvl == 0) return 0f;
        return lvl == 1 ? getWidth() * 0.95f : getWidth() + getHeight() + 50 * dp;
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
        float limit = w * 0.95f;
        if (level == 2) limit = w + h + 50 * dp;
        int pad = Math.round(12 * dp);
        int minW = Math.round(150 * dp);
        int b = insetBottom + pad;              // distance of the next card's bottom from the screen bottom
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            int ch = c.getLayoutParams().height;
            int top = b + ch;
            int cw = Math.round(Math.min(w - 2 * pad, limit - top - 2 * pad));
            if (cw < minW || h - top < insetTop) {
                c.setTag(R.id.hidden_card, Boolean.TRUE);
                c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY));
                continue;
            }
            c.setTag(R.id.hidden_card, null);
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

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && !contains(e.getX(), e.getY())) return false;
        return super.dispatchTouchEvent(e);
    }
}
