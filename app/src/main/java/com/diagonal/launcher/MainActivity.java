package com.diagonal.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements DiagonalView.Listener {

    private static final String PREFS = "diagonal";
    private static final String KEY_ORDER = "order";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, AppEntry> cache = new HashMap<>();

    private DiagonalView diagonal;
    private TextView backButton;
    private LauncherApps launcherApps;
    private SharedPreferences prefs;
    private int loadGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        launcherApps = getSystemService(LauncherApps.class);
        goEdgeToEdge();

        float dp = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);

        diagonal = new DiagonalView(this);
        diagonal.setListener(this);
        diagonal.setEmptyText(getString(R.string.loading));
        root.addView(diagonal, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        backButton = new TextView(this);
        backButton.setText("↩");
        backButton.setTextSize(30);
        backButton.setTextColor(0xFFFFFFFF);
        backButton.setGravity(Gravity.CENTER);
        backButton.setContentDescription(getString(R.string.back_to_default));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.RECTANGLE); // square, no rounding
        circle.setColor(0xE6202028);
        circle.setStroke(Math.round(1.5f * dp), 0x66FFFFFF);
        backButton.setBackground(circle);
        backButton.setElevation(6 * dp);
        backButton.setOnClickListener(v -> openDefaultLauncherSettings());
        int size = Math.round(64 * dp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.END);
        int margin = Math.round(20 * dp);
        lp.setMargins(margin, margin, margin, margin);
        root.addView(backButton, lp);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) backButton.getLayoutParams();
            p.bottomMargin = margin + insets.getSystemWindowInsetBottom();
            boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            p.setMarginEnd(margin + (rtl ? insets.getSystemWindowInsetLeft() : insets.getSystemWindowInsetRight()));
            backButton.setLayoutParams(p);
            return insets;
        });

        setContentView(root);
        getWindow().setBackgroundDrawable(null); // the view paints the background; avoid overdraw
        launcherApps.registerCallback(appsCallback, main);
        loadApps();
    }

    @Override
    protected void onDestroy() {
        launcherApps.unregisterCallback(appsCallback);
        main.removeCallbacks(reload);
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Pressing Home while already on the launcher jumps back to the first apps.
        boolean alreadyOnHome = hasWindowFocus()
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0;
        if (alreadyOnHome && Intent.ACTION_MAIN.equals(intent.getAction()) && diagonal != null) {
            diagonal.scrollToStart();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // A home screen has nowhere to go back to.
    }

    @SuppressWarnings("deprecation")
    private void goEdgeToEdge() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    // ---- "back to the normal desktop" button --------------------------

    private void openDefaultLauncherSettings() {
        Toast.makeText(this, R.string.hint_default, Toast.LENGTH_LONG).show();
        Intent[] candidates = {
                new Intent(Settings.ACTION_HOME_SETTINGS),
                new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS),
        };
        for (Intent i : candidates) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // try the next one
            }
        }
    }

    // ---- launching ------------------------------------------------------

    @Override
    public void onLaunch(AppEntry app, View source, Rect bounds) {
        try {
            Bundle opts = ActivityOptions.makeScaleUpAnimation(source,
                    bounds.left, bounds.top, bounds.width(), bounds.height()).toBundle();
            launcherApps.startMainActivity(app.component, Process.myUserHandle(), bounds, opts);
        } catch (Exception e) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show();
            loadApps();
        }
    }

    @Override
    public void onReloadNeeded() { loadApps(); }

    @Override
    public void onOrderChanged(List<AppEntry> apps) {
        StringBuilder sb = new StringBuilder();
        for (AppEntry a : apps) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(a.key);
        }
        prefs.edit().putString(KEY_ORDER, sb.toString()).apply();
    }

    // ---- loading apps ---------------------------------------------------

    /** Drops cached icons/colours of the given packages (they may have been updated), then reloads. */
    private void refresh(UserHandle user, String... pkgs) {
        if (!Process.myUserHandle().equals(user)) return; // only the main profile is shown
        for (String pkg : pkgs) {
            cache.values().removeIf(a -> a.component.getPackageName().equals(pkg));
        }
        main.removeCallbacks(reload);           // collapse bursts of package events
        main.postDelayed(reload, 300);
    }

    private final Runnable reload = this::loadApps;

    private void loadApps() {
        final int gen = ++loadGeneration;
        final int iconPx = diagonal.iconSizePx();
        final String savedOrder = prefs.getString(KEY_ORDER, "");
        final Map<String, AppEntry> known = new HashMap<>(cache);
        worker.execute(() -> {
            List<AppEntry> result = queryApps(iconPx, savedOrder, known);
            main.post(() -> {
                if (gen != loadGeneration || isDestroyed()) return;
                cache.clear();
                for (AppEntry a : result) cache.put(a.key, a);
                diagonal.setEmptyText(result.isEmpty() ? getString(R.string.no_apps) : "");
                diagonal.setApps(result);
            });
        });
    }

    private List<AppEntry> queryApps(int iconPx, String savedOrder, Map<String, AppEntry> known) {
        UserHandle user = Process.myUserHandle();
        List<LauncherActivityInfo> infos = launcherApps.getActivityList(null, user);
        List<AppEntry> list = new ArrayList<>(infos.size());
        for (LauncherActivityInfo info : infos) {
            try {
                ComponentName cn = info.getComponentName();
                if (cn.getPackageName().equals(getPackageName())) continue;
                String key = cn.flattenToString();
                String label = String.valueOf(info.getLabel());
                AppEntry cached = known.get(key);
                if (cached != null && cached.label.equals(label)) {
                    list.add(cached);
                    continue;
                }
                Drawable d = info.getIcon(0);
                if (d == null) d = getPackageManager().getDefaultActivityIcon();
                Bitmap icon = toBitmap(d, iconPx);
                int color = ColorExtractor.dominantColor(icon);
                list.add(new AppEntry(cn, label, icon, color, ColorExtractor.textColorFor(color)));
            } catch (RuntimeException | OutOfMemoryError e) {
                // One broken app must never crash the home screen.
            }
        }

        Map<String, Integer> pos = new HashMap<>();
        if (!savedOrder.isEmpty()) {
            String[] keys = savedOrder.split("\n");
            for (int i = 0; i < keys.length; i++) pos.put(keys[i], i);
        }
        Collator collator = Collator.getInstance();
        list.sort((a, b) -> {
            Integer pa = pos.get(a.key), pb = pos.get(b.key);
            if (pa != null && pb != null) return Integer.compare(pa, pb);
            if (pa != null) return -1;   // remembered apps first, new ones at the end
            if (pb != null) return 1;
            return collator.compare(a.label, b.label);
        });
        return list;
    }

    private static Bitmap toBitmap(Drawable d, int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return bmp;
    }

    private final LauncherApps.Callback appsCallback = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String pkg, UserHandle user) { refresh(user, pkg); }
        @Override public void onPackageAdded(String pkg, UserHandle user) { refresh(user, pkg); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { refresh(user, pkg); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { refresh(user, pkgs); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { refresh(user, pkgs); }
    };
}
