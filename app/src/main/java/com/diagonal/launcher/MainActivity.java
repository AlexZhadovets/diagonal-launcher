package com.diagonal.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements DiagonalView.Listener {

    private static final int HOME_SLOTS = DiagonalView.VISIBLE;
    private static final String PREFS = "diagonal";
    private static final String KEY_FAV = "favorites";
    private static final String KEY_ASKED_USAGE = "asked_usage";
    private static final String KEY_LAUNCH_PREFIX = "launch:";
    private static final long USAGE_WINDOW_MS = 14L * 24 * 60 * 60 * 1000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, AppEntry> cache = new HashMap<>();

    private DiagonalView diagonal;
    private TextView backButton;
    private TextView searchButton;
    private FrameLayout searchLayer;
    private EditText searchField;
    private ResultsView searchResults;
    private LauncherApps launcherApps;
    private SharedPreferences prefs;
    private int loadGeneration;
    private boolean recenterOnNextLoad = true;

    /** Last loaded data, kept so pin/unpin can re-arrange without reloading icons. */
    private List<AppEntry> allApps = new ArrayList<>();
    private Map<String, Long> usage = new HashMap<>();
    private AppEntry[] lastHome = new AppEntry[HOME_SLOTS];
    private AlertDialog shownDialog;

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
        diagonal.setSectionLabels(getString(R.string.section_usage), getString(R.string.section_alpha));
        root.addView(diagonal, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        backButton = new TextView(this);
        backButton.setText("↩");
        backButton.setTextSize(16);
        backButton.setTextColor(0x66FFFFFF);
        backButton.setGravity(Gravity.CENTER);
        backButton.setContentDescription(getString(R.string.back_to_default));
        GradientDrawable square = new GradientDrawable();
        square.setShape(GradientDrawable.RECTANGLE); // no rounding
        square.setColor(0x22000000);                 // barely visible
        backButton.setBackground(square);
        backButton.setOnClickListener(v -> openDefaultLauncherSettings());
        int size = Math.round(32 * dp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.END);
        int margin = Math.round(10 * dp);
        lp.setMargins(margin, margin, margin, margin);
        root.addView(backButton, lp);

        buildSearch(root, dp);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            FrameLayout.LayoutParams sp = (FrameLayout.LayoutParams) searchButton.getLayoutParams();
            sp.topMargin = insets.getSystemWindowInsetTop() + Math.round(6 * dp);
            searchButton.setLayoutParams(sp);
            // Keep the search box right above the keyboard.
            int bottom = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? insets.getInsets(WindowInsets.Type.ime() | WindowInsets.Type.navigationBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            searchLayer.setPadding(0, insets.getSystemWindowInsetTop(), 0, bottom);
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) backButton.getLayoutParams();
            boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            p.bottomMargin = margin + insets.getSystemWindowInsetBottom();
            p.setMarginEnd(margin + (rtl ? insets.getSystemWindowInsetLeft() : insets.getSystemWindowInsetRight()));
            backButton.setLayoutParams(p);
            return insets;
        });

        setContentView(root);
        getWindow().setBackgroundDrawable(null); // the view paints the background; avoid overdraw
        launcherApps.registerCallback(appsCallback, main);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();                    // usage times change while other apps run
        maybeAskForUsageAccess();
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
        // Every Home press brings back the 5 home apps in the middle.
        if (Intent.ACTION_MAIN.equals(intent.getAction()) && diagonal != null) {
            if (shownDialog != null && shownDialog.isShowing()) shownDialog.dismiss();
            closeSearch();
            if (hasWindowFocus()) {
                diagonal.animateToCenter();
            } else {
                diagonal.jumpToCenter();
                recenterOnNextLoad = true;   // re-sort by fresh usage once, then stay put
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (isSearchOpen()) closeSearch();
        else if (diagonal != null) diagonal.animateToCenter(); // Back = return to the middle
    }

    // ---- search ---------------------------------------------------------

    private void buildSearch(FrameLayout root, float dp) {
        // Small, nearly transparent "search" pill at the top.
        searchButton = new TextView(this);
        searchButton.setText(R.string.search);
        searchButton.setTextSize(13);
        searchButton.setTextColor(0x88FFFFFF);
        searchButton.setGravity(Gravity.CENTER);
        searchButton.setPadding(Math.round(18 * dp), 0, Math.round(18 * dp), 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(16 * dp);
        pill.setColor(0x33000000);
        searchButton.setBackground(pill);
        searchButton.setOnClickListener(v -> openSearch());
        root.addView(searchButton, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, Math.round(32 * dp), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        // Search layer: results stacked upwards, a small search box at the bottom above the keyboard.
        searchLayer = new FrameLayout(this);
        searchLayer.setBackgroundColor(0xE6101014);
        searchLayer.setVisibility(View.GONE);
        searchLayer.setClickable(true);

        int pad = Math.round(12 * dp);
        int boxH = Math.round(56 * dp);

        searchResults = new ResultsView(this);
        searchResults.setListener((app, v, b) -> {
            if (app == null) { closeSearch(); return; }   // tap on empty space closes search
            onLaunch(app, v, b);
            closeSearch();
        });
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        rp.bottomMargin = boxH + pad;
        searchLayer.addView(searchResults, rp);

        searchField = new EditText(this);
        searchField.setHint(R.string.search_hint);
        searchField.setHintTextColor(0x66FFFFFF);
        searchField.setTextColor(0xFFFFFFFF);
        searchField.setTextSize(20);
        searchField.setSingleLine(true);
        searchField.setImeOptions(EditorInfo.IME_ACTION_GO);
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setCornerRadius(22 * dp);
        fieldBg.setColor(0xFF22222A);
        searchField.setBackground(fieldBg);
        int ip = Math.round(18 * dp);
        searchField.setPadding(ip, 0, ip, 0);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, boxH, Gravity.BOTTOM);
        fp.setMargins(pad, 0, pad, pad);
        searchLayer.addView(searchField, fp);

        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filterSearch(s.toString()); }
        });
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER);
            AppEntry top = searchResults.first();
            if (go && top != null) {
                Rect b = new Rect(0, 0, v.getWidth(), v.getHeight());
                onLaunch(top, v, b);
                closeSearch();
                return true;
            }
            return false;
        });

        root.addView(searchLayer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private boolean isSearchOpen() { return searchLayer != null && searchLayer.getVisibility() == View.VISIBLE; }

    private void openSearch() {
        searchField.setText("");
        filterSearch("");
        searchLayer.setAlpha(0f);
        searchLayer.setVisibility(View.VISIBLE);
        searchLayer.animate().alpha(1f).setDuration(150).start();
        searchField.requestFocus();
        searchField.post(() -> getSystemService(InputMethodManager.class)
                .showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT));
    }

    private void closeSearch() {
        if (!isSearchOpen()) return;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        searchLayer.setVisibility(View.GONE);
    }

    /** Names starting with the query first, then names containing it; A–Z inside each group. */
    private void filterSearch(String query) {
        String q = query.trim().toLowerCase();
        Collator collator = Collator.getInstance();
        List<AppEntry> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (AppEntry a : allApps) {
            String l = a.label.toLowerCase();
            if (q.isEmpty() || l.startsWith(q)) starts.add(a);
            else if (l.contains(q)) contains.add(a);
        }
        starts.sort((a, b) -> collator.compare(a.label, b.label));
        contains.sort((a, b) -> collator.compare(a.label, b.label));
        starts.addAll(contains);
        searchResults.setApps(q.isEmpty() ? new ArrayList<>() : starts); // nothing until you type
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

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    // ---- "back to the normal desktop" button --------------------------

    private void openDefaultLauncherSettings() {
        Toast.makeText(this, R.string.hint_default, Toast.LENGTH_LONG).show();
        startFirst(new Intent(Settings.ACTION_HOME_SETTINGS),
                new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS));
    }

    private void startFirst(Intent... candidates) {
        for (Intent i : candidates) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // try the next one
            }
        }
    }

    // ---- usage access ---------------------------------------------------

    @SuppressWarnings("deprecation")
    private boolean hasUsageAccess() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED
                || (mode == AppOpsManager.MODE_DEFAULT
                && checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }

    private void maybeAskForUsageAccess() {
        if (prefs.getBoolean(KEY_ASKED_USAGE, false) || hasUsageAccess()) return;
        prefs.edit().putBoolean(KEY_ASKED_USAGE, true).apply();
        shownDialog = dialog().setTitle(R.string.usage_title)
                .setMessage(R.string.usage_message)
                .setPositiveButton(R.string.usage_allow, (d, w) -> openUsageSettings())
                .setNegativeButton(R.string.later, null)
                .show();
    }

    private void openUsageSettings() {
        startFirst(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                        Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
    }

    /** Foreground time per package over the last two weeks, or our own launch counts. */
    private Map<String, Long> readUsage() {
        Map<String, Long> out = new HashMap<>();
        if (hasUsageAccess()) {
            try {
                UsageStatsManager usm = getSystemService(UsageStatsManager.class);
                long now = System.currentTimeMillis();
                Map<String, UsageStats> stats = usm.queryAndAggregateUsageStats(now - USAGE_WINDOW_MS, now);
                for (Map.Entry<String, UsageStats> e : stats.entrySet()) {
                    out.put(e.getKey(), e.getValue().getTotalTimeInForeground());
                }
                if (!out.isEmpty()) return out;
            } catch (RuntimeException ignored) {
                // fall back to launch counts
            }
        }
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith(KEY_LAUNCH_PREFIX) && e.getValue() instanceof Integer) {
                out.put(e.getKey().substring(KEY_LAUNCH_PREFIX.length()), (long) (Integer) e.getValue());
            }
        }
        return out;
    }

    // ---- launching & long-press menu -----------------------------------

    @Override
    public void onLaunch(AppEntry app, View source, Rect bounds) {
        String pkg = app.component.getPackageName();
        prefs.edit().putInt(KEY_LAUNCH_PREFIX + pkg, prefs.getInt(KEY_LAUNCH_PREFIX + pkg, 0) + 1).apply();
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
    public void onLongPress(AppEntry app) {
        String[] fav = readFavorites();
        int current = -1;
        for (int i = 0; i < HOME_SLOTS; i++) if (app.key.equals(fav[i])) current = i;

        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        Map<String, AppEntry> byKey = new HashMap<>();
        for (AppEntry a : allApps) byKey.put(a.key, a);

        for (int slot = 0; slot < HOME_SLOTS; slot++) {
            if (slot == current) continue;
            AppEntry occupant = fav[slot].isEmpty() ? null : byKey.get(fav[slot]);
            String who = occupant != null ? occupant.label
                    : lastHome[slot] != null ? lastHome[slot].label + ", " + getString(R.string.slot_auto)
                    : getString(R.string.slot_auto);
            labels.add(getString(R.string.pin_to_slot, slot + 1, who));
            final int s = slot;
            final int from = current;
            actions.add(() -> pin(app, s, from));
        }
        if (current >= 0) {
            labels.add(getString(R.string.unpin));
            final int from = current;
            actions.add(() -> { String[] f = readFavorites(); f[from] = ""; saveFavorites(f); rearrange(false); });
        }
        labels.add(getString(R.string.app_info));
        actions.add(() -> startFirst(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + app.component.getPackageName()))));
        labels.add(getString(R.string.uninstall));
        actions.add(() -> startFirst(new Intent(Intent.ACTION_DELETE,
                Uri.parse("package:" + app.component.getPackageName()))));
        if (!hasUsageAccess()) {
            labels.add(getString(R.string.usage_allow));
            actions.add(this::openUsageSettings);
        }

        shownDialog = dialog().setTitle(app.label)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    /** Puts app on a home slot; if it was already on another slot, the two swap places. */
    private void pin(AppEntry app, int slot, int fromSlot) {
        String[] f = readFavorites();
        if (fromSlot >= 0) f[fromSlot] = f[slot];
        f[slot] = app.key;
        saveFavorites(f);
        rearrange(false);
    }

    private String[] readFavorites() {
        String[] out = new String[HOME_SLOTS];
        String[] saved = prefs.getString(KEY_FAV, "").split("\n", -1);
        for (int i = 0; i < HOME_SLOTS; i++) out[i] = i < saved.length ? saved[i] : "";
        return out;
    }

    private void saveFavorites(String[] f) {
        prefs.edit().putString(KEY_FAV, String.join("\n", f)).apply();
    }

    // ---- arranging ------------------------------------------------------

    /** Builds  [usage, least → most] [5 home apps] [A → Z]  and hands it to the view. */
    private void rearrange(boolean recenter) {
        List<AppEntry> apps = allApps;
        Map<String, Long> use = usage;
        Collator collator = Collator.getInstance();

        List<AppEntry> byUsage = new ArrayList<>(apps);
        byUsage.sort((a, b) -> {
            long ua = use.getOrDefault(a.component.getPackageName(), 0L);
            long ub = use.getOrDefault(b.component.getPackageName(), 0L);
            if (ua != ub) return Long.compare(ub, ua);
            return collator.compare(a.label, b.label);
        });

        // Home slots: pinned apps; empty slots keep their previous app, else the most-used one.
        Map<String, AppEntry> byKey = new HashMap<>();
        for (AppEntry a : apps) byKey.put(a.key, a);
        String[] saved = readFavorites();
        AppEntry[] home = new AppEntry[HOME_SLOTS];
        Set<String> taken = new HashSet<>();
        for (int i = 0; i < HOME_SLOTS; i++) {
            AppEntry a = saved[i].isEmpty() ? null : byKey.get(saved[i]);
            if (a != null && taken.add(a.key)) home[i] = a;
        }
        for (int i = 0; i < HOME_SLOTS; i++) {
            if (home[i] != null || !saved[i].isEmpty() || lastHome[i] == null) continue;
            AppEntry prev = byKey.get(lastHome[i].key);
            if (prev != null && !recenter && taken.add(prev.key)) home[i] = prev;
        }
        int next = 0;
        for (int i = 0; i < HOME_SLOTS; i++) {
            if (home[i] != null) continue;
            while (next < byUsage.size() && taken.contains(byUsage.get(next).key)) next++;
            if (next < byUsage.size()) { home[i] = byUsage.get(next); taken.add(home[i].key); }
        }
        lastHome = home;
        List<AppEntry> favs = new ArrayList<>();
        for (AppEntry a : home) if (a != null) favs.add(a);

        List<AppEntry> usageSide = new ArrayList<>();
        for (AppEntry a : byUsage) if (!taken.contains(a.key)) usageSide.add(a);
        Collections.reverse(usageSide);              // most used ends up next to the home apps

        List<AppEntry> alphaSide = new ArrayList<>();
        for (AppEntry a : apps) if (!taken.contains(a.key)) alphaSide.add(a);
        alphaSide.sort((a, b) -> collator.compare(a.label, b.label));

        List<AppEntry> all = new ArrayList<>(usageSide.size() + favs.size() + alphaSide.size());
        all.addAll(usageSide);
        all.addAll(favs);
        all.addAll(alphaSide);
        diagonal.setEmptyText(all.isEmpty() ? getString(R.string.no_apps) : "");
        diagonal.setContent(all, usageSide.size(), favs.size(), recenter);
    }

    // ---- loading apps ---------------------------------------------------

    /** Drops cached icons/colours of the given packages (they may have been updated), then reloads. */
    private void refresh(UserHandle user, String... pkgs) {
        if (!Process.myUserHandle().equals(user)) return; // only the main profile is shown
        for (String pkg : pkgs) {
            cache.values().removeIf(a -> a.component.getPackageName().equals(pkg));
        }
        loadGeneration++;                       // discard an in-flight load holding stale icons
        main.removeCallbacks(reload);           // collapse bursts of package events
        main.postDelayed(reload, 300);
    }

    private final Runnable reload = this::loadApps;

    private void loadApps() {
        final int gen = ++loadGeneration;
        final int iconPx = Math.max(diagonal.iconSizePx(), 48);
        final Map<String, AppEntry> known = new HashMap<>(cache);
        worker.execute(() -> {
            List<AppEntry> result;
            Map<String, Long> use;
            try {
                result = queryApps(iconPx, known);
                use = readUsage();
            } catch (RuntimeException e) {
                return;                         // never crash the home screen from the worker
            }
            main.post(() -> {
                if (gen != loadGeneration || isDestroyed()) return;
                cache.clear();
                for (AppEntry a : result) cache.put(a.key, a);
                allApps = result;
                boolean recenter = recenterOnNextLoad;
                recenterOnNextLoad = false;
                // Re-sort by usage only when the home screen is (re)opened, so stripes don't
                // shuffle under the finger after every app launch.
                if (recenter || usage.isEmpty()) usage = use;
                rearrange(recenter);
            });
        });
    }

    private List<AppEntry> queryApps(int iconPx, Map<String, AppEntry> known) {
        UserHandle user = Process.myUserHandle();
        List<LauncherActivityInfo> infos = launcherApps.getActivityList(null, user);
        List<AppEntry> list = new ArrayList<>(infos.size());
        for (LauncherActivityInfo info : infos) {
            ComponentName cn = info.getComponentName();
            if (cn.getPackageName().equals(getPackageName())) continue;
            String label;
            try { label = String.valueOf(info.getLabel()); } catch (RuntimeException e) { label = cn.getPackageName(); }
            try {
                String key = cn.flattenToString();
                AppEntry cached = known.get(key);
                if (cached != null && cached.label.equals(label)
                        && cached.icon != null && cached.icon.getWidth() == iconPx) {
                    list.add(cached);
                    continue;
                }
                Drawable d = info.getIcon(0);
                if (d == null) d = getPackageManager().getDefaultActivityIcon();
                Bitmap icon = toBitmap(d, iconPx);
                ColorExtractor.Result r = ColorExtractor.analyze(icon);
                list.add(new AppEntry(cn, label, r.glyph, r.color, r.textColor));
            } catch (RuntimeException | OutOfMemoryError e) {
                // Broken icon: still show the app, just without an icon.
                list.add(new AppEntry(cn, label, null, 0xFF808080, 0xFFFFFFFF));
            }
        }
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
