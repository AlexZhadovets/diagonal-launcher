package com.diagonal.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The notification list in the big quick-settings triangle, working like the Android shade:
 * grouped by app, swipe sideways to dismiss, tap to open, chevron to expand long text and the
 * action buttons, inline reply for messengers, "Clear all" at the end.
 * It is clipped by the triangle's diagonal edge.
 */
public class NotifList extends ScrollView {

    public interface Host {
        AppEntry appFor(String pkg);
        void open(Item item);
        void dismiss(List<Item> items);
        void clearAll();
        String clearLabel();
        void openHistory();
        void openSettings();
        String replyHint();
    }

    public static final class Item {
        String key, pkg, title, text, big;
        long when;
        PendingIntent intent;
        boolean autoCancel;
        Bitmap large;
        final List<Action> actions = new ArrayList<>();
    }

    public static final class Action {
        CharSequence title;
        PendingIntent intent;
        RemoteInput[] inputs;
    }

    private final float dp;
    private final LinearLayout column;
    private final Path clip = new Path();
    private final Host host;
    private QuickPanel shape;           // where the triangle edge is
    private float topOnScreen;
    private final java.util.Set<String> expanded = new java.util.HashSet<>();

    public NotifList(Context c, Host host) {
        super(c);
        this.host = host;
        dp = getResources().getDisplayMetrics().density;
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(12 * dp);
        column.setPadding(p, Math.round(4 * dp), p, Math.round(200 * dp));
        addView(column);
        setVisibility(GONE);
    }

    void attachShape(QuickPanel q) { shape = q; }

    void setTopOnScreen(float y) { topOnScreen = y; invalidate(); }

    // ---- clipping to the triangle --------------------------------------

    /** Inside the triangle: (width − x) + screenY ≤ legs. */
    private boolean inside(float x, float yInView) {
        if (shape == null) return true;
        return (getWidth() - x) + topOnScreen + yInView <= shape.legs();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (shape == null) { super.dispatchDraw(canvas); return; }
        float w = getWidth();
        float k = shape.legs() - w - topOnScreen + getScrollY();   // content y of the edge at x = 0
        clip.reset();
        clip.moveTo(0, getScrollY() - 10);
        clip.lineTo(w, getScrollY() - 10);
        clip.lineTo(w, w + k);
        clip.lineTo(0, k);
        clip.close();
        canvas.save();
        canvas.clipPath(clip);
        super.dispatchDraw(canvas);
        canvas.restore();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && !inside(e.getX(), e.getY())) return false;
        return super.dispatchTouchEvent(e);
    }

    // ---- content -----------------------------------------------------------

    private List<Item> pending;

    /** Applies an update that arrived while a reply was being typed. */
    void applyPending() {
        if (pending != null) { List<Item> p = pending; pending = null; setItems(p); }
    }

    void setItems(List<Item> items) {
        if (findFocus() instanceof EditText) { pending = items; return; }   // don't throw away a reply in progress
        pending = null;
        column.removeAllViews();
        Map<String, List<Item>> groups = new LinkedHashMap<>();
        for (Item it : items) {
            List<Item> g = groups.get(it.pkg);
            if (g == null) { g = new ArrayList<>(); groups.put(it.pkg, g); }
            g.add(it);
        }
        for (List<Item> g : groups.values()) column.addView(groupView(g));
        if (!items.isEmpty()) column.addView(bottomBar());
        invalidate();
    }

    /** [history]  [ Clear all ]  [settings] — like the Android shade. */
    private View bottomBar() {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int h = Math.round(44 * dp);
        View hist = roundButton(Icons.ALARM, v -> host.openHistory());
        bar.addView(hist, new LinearLayout.LayoutParams(Math.round(64 * dp), h));
        TextView clear = text(15, 0xFFFFFFFF, true);
        clear.setText(host.clearLabel());
        clear.setGravity(Gravity.CENTER);
        clear.setBackground(pill(0xFF2C2F38, h / 2f));
        clear.setOnClickListener(v -> host.clearAll());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, h, 1f);
        cl.setMargins(Math.round(8 * dp), 0, Math.round(8 * dp), 0);
        bar.addView(clear, cl);
        View set = roundButton(Icons.SETTINGS, v -> host.openSettings());
        bar.addView(set, new LinearLayout.LayoutParams(Math.round(64 * dp), h));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(6 * dp);
        bar.setLayoutParams(lp);
        return bar;
    }

    private View roundButton(int icon, OnClickListener click) {
        View v = new View(getContext()) {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                p.setColor(0xFFFFFFFF);
                Icons.draw(c, icon, getWidth() / 2f, getHeight() / 2f, 20 * dp, p);
            }
        };
        v.setBackground(pill(0xFF2C2F38, 22 * dp));
        v.setOnClickListener(click);
        return v;
    }

    private static GradientDrawable pill(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(r);
        g.setColor(color);
        return g;
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    /** Card background: the shade's dark grey with a touch of the app's colour. */
    private static int tint(int app) {
        int base = 0xFF23262E;
        float k = 0.22f;
        int r = Math.round(((base >> 16) & 255) * (1 - k) + ((app >> 16) & 255) * k);
        int g = Math.round(((base >> 8) & 255) * (1 - k) + ((app >> 8) & 255) * k);
        int b = Math.round((base & 255) * (1 - k) + (app & 255) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** One app's notifications in one rounded card (several of them are separated by a thin gap). */
    private View groupView(List<Item> group) {
        Item head = group.get(0);
        AppEntry app = host.appFor(head.pkg);
        int accent = app != null ? app.color : 0xFF8888AA;
        boolean open = expanded.contains(head.pkg);
        int bg = tint(accent);

        LinearLayout stack = new LinearLayout(getContext());
        stack.setOrientation(LinearLayout.VERTICAL);
        int show = open ? group.size() : Math.min(group.size(), 2);
        for (int k = 0; k < show; k++) {
            boolean first = k == 0, last = k == show - 1;
            float big = 26 * dp, small = 8 * dp;
            GradientDrawable g = new GradientDrawable();
            g.setColor(bg);
            g.setCornerRadii(new float[]{first ? big : small, first ? big : small, first ? big : small, first ? big : small,
                    last ? big : small, last ? big : small, last ? big : small, last ? big : small});
            View entry = entryView(group.get(k), open, accent, app, k == 0, group, stack);
            entry.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (!first) lp.topMargin = Math.round(3 * dp);
            stack.addView(entry, lp);
        }
        if (!open && group.size() > 2) {
            TextView more = text(12, 0x99FFFFFF, true);
            more.setText("+" + (group.size() - 2));
            more.setPadding(Math.round(70 * dp), Math.round(4 * dp), 0, 0);
            stack.addView(more);
        }

        Swipe swipe = new Swipe(getContext(), () -> host.dismiss(group));
        swipe.addView(stack);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(12 * dp);
        swipe.setLayoutParams(lp);
        return swipe;
    }

    private static View swipeParent(View card) { return (View) card.getParent(); }

    private boolean hasMore(Item it) { return !it.actions.isEmpty() || (it.big != null && !it.big.equals(it.text)); }

    /** Avatar (contact picture with the app as a badge, or the app icon), text, expand button, actions. */
    private View entryView(Item it, boolean open, int accent, AppEntry app, boolean showChevron,
                           List<Item> group, LinearLayout stack) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(14 * dp);
        box.setPadding(p, p, p, p);

        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.TOP);
        row.addView(avatar(it, app), new LinearLayout.LayoutParams(Math.round(44 * dp), Math.round(44 * dp)));

        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Math.round(12 * dp), 0, Math.round(6 * dp), 0);
        TextView header = text(12, 0xB3FFFFFF, true);
        header.setText((app != null ? app.label : it.pkg) + " • " + ago(it.when));
        TextView title = text(15, 0xFFFFFFFF, true);
        title.setText(it.title);
        TextView body = text(14, 0xD9FFFFFF, false);
        String b = open && it.big != null ? it.big : it.text;
        body.setText(b);
        if (open) { body.setSingleLine(false); body.setMaxLines(8); }
        texts.addView(header);
        if (!it.title.isEmpty()) texts.addView(title);
        if (b != null && !b.isEmpty()) texts.addView(body);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean expandable = group.size() > 1 || hasMore(it);
        if (showChevron && expandable) {
            TextView chev = text(13, 0xFFFFFFFF, true);
            chev.setText(open ? "⌃" : "⌄");
            chev.setGravity(Gravity.CENTER);
            chev.setBackground(pill(0x22FFFFFF, 13 * dp));
            chev.setOnClickListener(v -> {
                if (expanded.contains(it.pkg)) expanded.remove(it.pkg); else expanded.add(it.pkg);
                View sw = swipeParent(stack);
                int i = column.indexOfChild(sw);
                if (i < 0) return;
                column.removeViewAt(i);
                column.addView(groupView(group), i);
            });
            row.addView(chev, new LinearLayout.LayoutParams(Math.round(40 * dp), Math.round(26 * dp)));
        }
        box.addView(row);
        box.setOnClickListener(v -> host.open(it));

        if (open && !it.actions.isEmpty()) {
            LinearLayout acts = new LinearLayout(getContext());
            acts.setPadding(Math.round(50 * dp), Math.round(8 * dp), 0, 0);
            int ink = lighten(accent);
            for (Action a : it.actions) {
                TextView btn = text(14, ink, true);
                btn.setText(a.title);
                btn.setPadding(Math.round(6 * dp), Math.round(6 * dp), Math.round(18 * dp), Math.round(6 * dp));
                btn.setOnClickListener(v -> {
                    if (freeForm(a) != null) showReply(box, acts, it, a, accent);
                    else send(a.intent, null);
                });
                acts.addView(btn);
            }
            box.addView(acts);
        }
        return box;
    }

    /** Contact picture with a small app badge, or the app icon on the app's colour. */
    private View avatar(Item it, AppEntry app) {
        int accent = app != null ? app.color : 0xFF555566;
        return new View(getContext()) {
            final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            final android.graphics.Paint bmp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG
                    | android.graphics.Paint.FILTER_BITMAP_FLAG);
            final android.graphics.RectF r = new android.graphics.RectF();
            @Override protected void onDraw(Canvas c) {
                float s = getWidth();
                if (it.large != null) {
                    r.set(0, 0, s, s);
                    c.drawBitmap(it.large, null, r, bmp);
                    if (app != null && app.icon != null) {      // badge
                        float bs = s * 0.42f, bx = s - bs + 2 * dp, by = s - bs + 2 * dp;
                        fill.setColor(accent);
                        c.drawCircle(bx + bs / 2, by + bs / 2, bs / 2, fill);
                        r.set(bx + bs * 0.18f, by + bs * 0.18f, bx + bs * 0.82f, by + bs * 0.82f);
                        bmp.setColorFilter(app.iconFilter());
                        c.drawBitmap(app.icon, null, r, bmp);
                        bmp.setColorFilter(null);
                    }
                } else {
                    fill.setColor(accent);
                    c.drawCircle(s / 2, s / 2, s / 2, fill);
                    if (app != null && app.icon != null) {
                        r.set(s * 0.2f, s * 0.2f, s * 0.8f, s * 0.8f);
                        bmp.setColorFilter(app.iconFilter());
                        c.drawBitmap(app.icon, null, r, bmp);
                        bmp.setColorFilter(null);
                    }
                }
            }
        };
    }

    /** Inline reply, like in the Android shade. */
    private void showReply(LinearLayout box, View actions, Item it, Action a, int accent) {
        actions.setVisibility(GONE);
        LinearLayout reply = new LinearLayout(getContext());
        reply.setGravity(Gravity.CENTER_VERTICAL);
        reply.setPadding(0, Math.round(6 * dp), 0, 0);
        EditText field = new EditText(getContext());
        field.setHint(host.replyHint());
        int fieldInk = 0xFFFFFFFF;
        field.setHintTextColor((fieldInk & 0x00FFFFFF) | 0x77000000);
        field.setTextColor(fieldInk);
        field.setTextSize(14);
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEND);
        GradientDrawable fb = new GradientDrawable();
        fb.setCornerRadius(16 * dp);
        fb.setColor(fieldInk == 0xFFFFFFFF ? 0x33000000 : 0x22FFFFFF);
        field.setBackground(fb);
        int hp = Math.round(12 * dp);
        field.setPadding(hp, Math.round(8 * dp), hp, Math.round(8 * dp));
        reply.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView sendBtn = text(18, 0xFFFFFFFF, true);
        sendBtn.setText("➤");
        sendBtn.setGravity(Gravity.CENTER);
        int s = Math.round(40 * dp);
        reply.addView(sendBtn, new LinearLayout.LayoutParams(s, s));
        Runnable go = () -> {
            String msg = field.getText().toString().trim();
            if (msg.isEmpty()) return;
            RemoteInput[] inputs = freeForm(a);
            Bundle results = new Bundle();
            for (RemoteInput ri : inputs) results.putCharSequence(ri.getResultKey(), msg);
            Intent fill = new Intent();
            RemoteInput.addResultsToIntent(inputs, fill, results);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT);
            }
            send(a.intent, fill);
            hideKeyboard(field);
            field.clearFocus();
            reply.setVisibility(GONE);
            actions.setVisibility(VISIBLE);
            post(this::applyPending);
        };
        sendBtn.setOnClickListener(v -> go.run());
        field.setOnEditorActionListener((v, id, ev) -> { go.run(); return true; });
        box.addView(reply);
        field.requestFocus();
        field.post(() -> getContext().getSystemService(android.view.inputmethod.InputMethodManager.class)
                .showSoftInput(field, 0));
    }

    /** The text inputs of an action (choice-only / data-only inputs are left out). */
    private static RemoteInput[] freeForm(Action a) {
        if (a.inputs == null) return null;
        List<RemoteInput> out = new ArrayList<>();
        for (RemoteInput ri : a.inputs) {
            if (!ri.getAllowFreeFormInput()) continue;
            if (android.os.Build.VERSION.SDK_INT >= 29 && ri.isDataOnly()) continue;
            out.add(ri);
        }
        return out.isEmpty() ? null : out.toArray(new RemoteInput[0]);
    }

    private void hideKeyboard(View v) {
        getContext().getSystemService(android.view.inputmethod.InputMethodManager.class)
                .hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private void send(PendingIntent pi, Intent fill) {
        if (pi == null) return;
        try {
            Bundle o = null;
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                o = android.app.ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
            }
            pi.send(getContext(), 0, fill, null, null, null, o);
        } catch (PendingIntent.CanceledException ignored) { }
    }

    private static String ago(long when) {
        return DateUtils.getRelativeTimeSpanString(when, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString();
    }

    /** The app colour made readable on the dark card. */
    private static int lighten(int c) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(c, hsv);
        if (hsv[2] < 0.75f) hsv[2] = 0.75f;
        if (hsv[1] > 0.75f) hsv[1] = 0.75f;
        return android.graphics.Color.HSVToColor(hsv);
    }

    /** Build an Item from a status-bar notification. */
    static Item from(Context ctx, android.service.notification.StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle ex = n.extras;
        Item it = new Item();
        it.key = sbn.getKey();
        it.pkg = sbn.getPackageName();
        it.when = n.when != 0 ? n.when : sbn.getPostTime();
        it.intent = n.contentIntent;
        it.autoCancel = (n.flags & Notification.FLAG_AUTO_CANCEL) != 0;
        CharSequence t = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence x = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence big = ex == null ? null : ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
        it.title = t == null ? "" : t.toString();
        it.text = x == null ? "" : x.toString();
        it.big = big == null ? null : big.toString();
        if (n.actions != null) {
            for (Notification.Action a : n.actions) {
                if (a == null || a.title == null || a.actionIntent == null) continue;
                Action act = new Action();
                act.title = a.title;
                act.intent = a.actionIntent;
                act.inputs = a.getRemoteInputs();
                it.actions.add(act);
            }
        }
        try {
            android.graphics.drawable.Icon li = n.getLargeIcon();
            if (li != null) {
                android.graphics.drawable.Drawable d = li.loadDrawable(ctx);
                if (d != null) {
                    int px = Math.round(36 * ctx.getResources().getDisplayMetrics().density);
                    Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(bmp);
                    Path circle = new Path();
                    circle.addCircle(px / 2f, px / 2f, px / 2f, Path.Direction.CW);
                    c.clipPath(circle);
                    d.setBounds(0, 0, px, px);
                    d.draw(c);
                    it.large = bmp;
                }
            }
        } catch (RuntimeException ignored) { }
        return it;
    }

    /** Swipe sideways to dismiss, like in the shade. */
    private static final class Swipe extends FrameLayout {
        private final Runnable onDismiss;
        private final int slop;
        private float downX, downY;
        private boolean dragging;
        private VelocityTracker vt;

        Swipe(Context c, Runnable onDismiss) {
            super(c);
            this.onDismiss = onDismiss;
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    dragging = false;
                    if (vt != null) vt.recycle();
                    vt = VelocityTracker.obtain();
                    vt.addMovement(e);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (vt != null) vt.addMovement(e);
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        dragging = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = e.getRawX();
                downY = e.getRawY();
                dragging = false;
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
            }
            if (vt != null) vt.addMovement(e);
            float dx = e.getRawX() - downX;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(e.getRawY() - downY) * 1.5f) {
                        dragging = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) {
                        setTranslationX(dx);
                        setAlpha(Math.max(0.2f, 1f - Math.abs(dx) / getWidth()));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float v = 0;
                    if (vt != null) { vt.computeCurrentVelocity(1000); v = vt.getXVelocity(); vt.recycle(); vt = null; }
                    boolean gone = dragging && (Math.abs(dx) > getWidth() * 0.35f
                            || (Math.abs(v) > 1200 && Math.signum(v) == Math.signum(dx)));
                    if (gone) {
                        animate().translationX(Math.signum(dx) * getWidth()).alpha(0f).setDuration(180)
                                .setListener(new AnimatorListenerAdapter() {
                                    @Override public void onAnimationEnd(Animator a) {
                                        setVisibility(GONE);
                                        onDismiss.run();
                                    }
                                }).start();
                    } else {
                        animate().translationX(0).alpha(1f).setDuration(160).setListener(null).start();
                    }
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        }
    }
}
