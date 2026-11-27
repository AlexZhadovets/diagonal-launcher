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
 * Level 1 peeks a little, level 2 covers the whole screen.
 * Visible region: every point with x + (height − y) ≤ legs.
 */
public class WidgetPanel extends FrameLayout {

    private final float dp;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tri = new Path();
    private float legs;
    private int level;
    private ValueAnimator anim;

    public WidgetPanel(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        bg.setColor(0xF7000000);
        edge.setColor(0x33FFFFFF);
        edge.setStrokeWidth(1.5f * dp);
        setWillNotDraw(false);
        setVisibility(GONE);
    }

    public int level() { return level; }

    public boolean contains(float x, float y) { return level > 0 && x + (getHeight() - y) <= legs; }

    public void setLevel(int newLevel) {
        level = Math.max(0, Math.min(2, newLevel));
        float target = level == 0 ? 0f : level == 1 ? getWidth() * 0.95f : getWidth() + getHeight() + 50 * dp;
        if (level > 0) setVisibility(VISIBLE);
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(legs, target);
        anim.setDuration(level == 2 ? 320 : 260);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> { legs = (float) a.getAnimatedValue(); invalidate(); });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (level == 0) setVisibility(GONE);
            }
        });
        anim.start();
    }

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

    @Override
    public boolean onTouchEvent(MotionEvent e) { return true; } // eat taps on empty panel space

    static View spacer(Context c, int px) {
        View v = new View(c);
        v.setMinimumHeight(px);
        return v;
    }
}
