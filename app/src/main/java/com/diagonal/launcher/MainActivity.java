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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.view.MotionEvent;
import android.view.WindowInsetsController;
import android.app.WallpaperManager;
import android.graphics.Color;

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

    private static final int HOME_SLOTS = 4;   // + the smart stripe in the middle
    private static final String KEY_HIDE_STATUS = "hide_status";
    private static final String KEY_LOCK_SNAPSHOT = "lock_snapshot";
    private static final String KEY_ASKED_NOTIF = "asked_notif";
    private static final String KEY_ASKED_LOCATION = "asked_location";
    private static final String KEY_SNAPSHOT_SIG = "snapshot_sig";
    private static final int REQ_LOCATION = 43;
    static final int REQ_CALENDAR = 44;
    static final String KEY_COLOR_PREFIX = "color:";
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
    private TextView homeButton;
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
    private android.app.Dialog shownDialog;
    private InfoHub info;
    private Panels panels;
    private AppEntry infoEntry;
    private Bitmap lastArt, lastArtScaled;
    private int lastArtColor = 0xFF16161C;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        launcherApps = getSystemService(LauncherApps.class);
        info = new InfoHub(this);
        info.setListener(this::updateInfoStripe);
        undoOldQuietMode();   // a short-lived earlier version changed the user's DND settings
        quietOff();           // in case the app was killed while the quiet mode was on
        infoEntry = new AppEntry(new ComponentName(getPackageName(), "info"));
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

        // "Home" button: back to the 5 home apps (bottom-left, same subtle style).
        homeButton = new TextView(this);
        homeButton.setText("⌂");
        homeButton.setTextSize(17);
        homeButton.setTextColor(0x66FFFFFF);
        homeButton.setGravity(Gravity.CENTER);
        homeButton.setContentDescription(getString(R.string.to_home));
        GradientDrawable homeBg = new GradientDrawable();
        homeBg.setColor(0x22000000);
        homeButton.setBackground(homeBg);
        homeButton.setOnClickListener(v -> diagonal.animateToCenter());
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.START);
        hp.setMargins(margin, margin, margin, margin);
        root.addView(homeButton, hp);

        panels = new Panels(this, root, info, prefs);
        buildSearch(root, dp);
        // search pill below the panels (panels cover it), search layer stays on top
        root.removeView(searchButton);
        root.addView(searchButton, root.indexOfChild(panels.widgets), searchButton.getLayoutParams());

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            FrameLayout.LayoutParams sp = (FrameLayout.LayoutParams) searchButton.getLayoutParams();
            sp.topMargin = insets.getSystemWindowInsetTop() + Math.round(6 * dp);
            searchButton.setLayoutParams(sp);
            // Keep the search box right above the keyboard.
            int bottom = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? insets.getInsets(WindowInsets.Type.ime() | WindowInsets.Type.navigationBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            searchLayer.setPadding(0, insets.getSystemWindowInsetTop(), 0, bottom);
            int cutTop = Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null
                    ? insets.getDisplayCutout().getSafeInsetTop() : 0;
            topInset = Math.max(insets.getSystemWindowInsetTop(), cutTop);
            panels.setInsets(Math.max(insets.getSystemWindowInsetTop(), cutTop),
                    insets.getSystemWindowInsetBottom());
            if (sp.topMargin < cutTop + Math.round(6 * dp)) {
                sp.topMargin = cutTop + Math.round(6 * dp);
                searchButton.setLayoutParams(sp);
            }
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) backButton.getLayoutParams();
            boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            p.bottomMargin = margin + insets.getSystemWindowInsetBottom();
            p.setMarginEnd(margin + (rtl ? insets.getSystemWindowInsetLeft() : insets.getSystemWindowInsetRight()));
            backButton.setLayoutParams(p);
            FrameLayout.LayoutParams hp2 = (FrameLayout.LayoutParams) homeButton.getLayoutParams();
            hp2.bottomMargin = p.bottomMargin;
            hp2.setMarginStart(margin + (rtl ? insets.getSystemWindowInsetRight() : insets.getSystemWindowInsetLeft()));
            homeButton.setLayoutParams(hp2);
            return insets;
        });

        setContentView(root);
        getWindow().setBackgroundDrawable(null); // the view paints the background; avoid overdraw
        launcherApps.registerCallback(appsCallback, main);
    }

    @Override
    protected void onStart() {
        super.onStart();
        info.start();
        panels.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
        info.stop();
        panels.onStop();
        // Leaving for another app: be back at the start when the user returns.
        closeSearch();
        panels.closeAll();
        recenterOnNextLoad = true;
        tabletPage = 0;
        currentPageId = null;
        diagonal.jumpToCenter();
        maybeSnapshotLockScreen();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        quietOff();      // pop-ups behave normally everywhere else
        main.removeCallbacks(hideBannerLater);
        hideBanner();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        quietOn();
        applySystemBars();
        loadApps();                    // usage times change while other apps run
        panels.refreshTiles();
        info.refresh();
        askFirstRunPermissions();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applySystemBars();
    }

    boolean superDispatch(MotionEvent ev) { return super.dispatchTouchEvent(ev); }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOCATION) { info.resetWeather(); info.refresh(); }
        if (requestCode == REQ_CALENDAR) panels.refreshCalendar();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!isSearchOpen() && panels.dispatch(ev)) return true;
        return super.dispatchTouchEvent(ev);
    }


    // ---- corner panels & centre stripe ---------------------------------

    @Override
    public void onCornerSwipe(int direction) { panels.onCornerSwipe(direction); }

    @Override
    public void onInfoClick() {
        if (currentPage instanceof NotifList.Item) {
            NotifList.Item it = (NotifList.Item) currentPage;
            boolean ok = false;
            try { if (it.intent != null) { InfoHub.sendAllowingStart(this, it.intent); ok = true; } } catch (Exception ignored) { }
            if (!ok) { AppEntry a = appFor(it.pkg); if (a != null) launch(a); }
            NotifService svc = NotifService.instance;
            if (it.autoCancel && svc != null) { try { svc.cancelNotification(it.key); } catch (RuntimeException ignored) { } }
            return;
        }
        if (currentPage instanceof InfoHub.Media) {
            InfoHub.Media pm = (InfoHub.Media) currentPage;
            boolean ok = false;
            try {
                if (pm.controller != null && pm.controller.getSessionActivity() != null) {
                    InfoHub.sendAllowingStart(this, pm.controller.getSessionActivity());
                    ok = true;
                }
            } catch (Exception ignored) { }
            if (!ok) { AppEntry a = appFor(pm.pkg); if (a != null) launch(a); }
            return;
        }
        if (info.notif != null) {
            String pkg = info.notif.pkg;
            if (!info.openNotification()) { AppEntry a = appFor(pkg); if (a != null) launch(a); }
            return;
        }
        if (false && info.media != null) {   // (players are their own pages now)
            InfoHub.Media mm = info.media;
            boolean opened = false;
            try {
                if (mm.controller != null && mm.controller.getSessionActivity() != null) {
                    InfoHub.sendAllowingStart(this, mm.controller.getSessionActivity());
                    opened = true;
                }
            } catch (Exception ignored) { }
            if (!opened) { AppEntry a = appFor(mm.pkg); if (a != null) launch(a); }
            return;
        }
        if (!info.hasNotificationAccess()) { openNotificationAccess(); return; }
        startFirst(new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS));
    }

    /** Music that plays > newest notification > clock + weather. */
    // ---- tablet pages: main view, other players, then notifications -------

    private int tabletPage;
    private final Map<String, NotifList.Item> pageItems = new HashMap<>();
    private final Map<Bitmap, Integer> artColors = new HashMap<>();
    private final Map<Bitmap, Bitmap> artSmall = new HashMap<>();

    /** Marker for the clock page of the tablet. */
    private static final Object CLOCK = new Object();

    /**
     * Tablet pages: players first (the one playing leads), then the clock, then notifications.
     * Without music the clock is the first page. Swiping along the tablet moves between them.
     */
    private List<Object> tabletPages() {
        List<Object> out = new ArrayList<>();
        if (info.media != null) {
            out.add(info.media);
            for (InfoHub.Media m : info.allMedia) if (m.controller != info.media.controller) out.add(m);
        }
        out.add(CLOCK);
        NotifService svc = NotifService.instance;
        if (svc != null) {
            android.service.notification.StatusBarNotification[] all = null;
            try { all = svc.getActiveNotifications(); } catch (RuntimeException ignored) { }
            if (all != null) {
                List<android.service.notification.StatusBarNotification> list = new ArrayList<>();
                for (android.service.notification.StatusBarNotification sbn : all) {
                    android.app.Notification nn = sbn.getNotification();
                    if (nn == null || sbn.isOngoing() || !sbn.isClearable()) continue;
                    if ((nn.flags & android.app.Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                    if (sbn.getPackageName().equals(getPackageName())) continue;
                    if (android.app.Notification.CATEGORY_TRANSPORT.equals(nn.category)) continue;
                    list.add(sbn);
                }
                list.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
                Map<String, NotifList.Item> keep = new HashMap<>();
                for (int i = 0; i < list.size() && i < 8; i++) {
                    android.service.notification.StatusBarNotification sbn = list.get(i);
                    String k = sbn.getKey() + "@" + sbn.getPostTime();
                    NotifList.Item it = pageItems.get(k);
                    if (it == null) it = NotifList.from(this, sbn);
                    keep.put(k, it);
                    if (!it.title.isEmpty() || !it.text.isEmpty()) out.add(it);
                }
                pageItems.clear();
                pageItems.putAll(keep);
            }
        }
        return out;
    }

    private Object currentPage;
    private Object currentPageId;   // keeps the same page when the list changes around it

    @Override
    public void onTabletDismiss() {
        if (!(currentPage instanceof NotifList.Item)) return;
        NotifList.Item it = (NotifList.Item) currentPage;
        NotifService svc = NotifService.instance;
        if (svc != null) { try { svc.cancelNotification(it.key); } catch (RuntimeException ignored) { } }
        currentPageId = null;            // stay at this position: the next notification slides in
        main.postDelayed(this::updateInfoStripe, 150);
    }

    @Override
    public void onTabletSwipe(int direction) {
        // swipe towards the bottom-left = next page (like pushing the current page away), towards the top-right = back
        tabletPage = Math.max(0, tabletPage - direction);
        currentPageId = null;
        updateInfoStripe();
    }

    /** Colour of a cover, worked out once on the worker thread. */
    private int artColor(Bitmap art) {
        if (art == null) return 0xFF26262E;
        Integer c = artColors.get(art);
        if (c != null) return c;
        artColors.put(art, 0xFF26262E);
        worker.execute(() -> {
            Bitmap sm = Bitmap.createScaledBitmap(art, 96, 96, true);
            int col = ColorExtractor.analyze(sm).color;
            main.post(() -> {
                if (artColors.size() > 20) { artColors.clear(); artSmall.clear(); }
                artColors.put(art, col);
                artSmall.put(art, sm);
                updateInfoStripe();
            });
        });
        return 0xFF26262E;
    }

    private static Object pageId(Object p) {
        if (p instanceof InfoHub.Media) return ((InfoHub.Media) p).pkg;
        if (p instanceof NotifList.Item) return ((NotifList.Item) p).key;
        return p;
    }

    private boolean hadPlaying;

    private void updateInfoStripe() {
        List<Object> pages = tabletPages();
        boolean playing = info.media != null && info.media.playing;
        if (playing && !hadPlaying) { tabletPage = 0; currentPageId = null; }   // music started: show the player
        hadPlaying = playing;
        // stay on the same thing if pages were added/removed before it
        if (currentPageId != null) {
            for (int i = 0; i < pages.size(); i++) {
                if (currentPageId.equals(pageId(pages.get(i)))) { tabletPage = i; break; }
            }
        }
        tabletPage = Math.min(tabletPage, pages.size() - 1);
        currentPage = pages.get(tabletPage);
        currentPageId = pageId(currentPage);
        diagonal.setPager(pages.size(), tabletPage);
        diagonal.setTabletDismissable(currentPage instanceof NotifList.Item);

        String notifLine = null;
        InfoHub.Notif n = info.notif;
        if (n != null) {
            AppEntry app = appFor(n.pkg);
            String from = app != null ? app.label : "";
            String who = n.title.isEmpty() ? from : (from.isEmpty() ? n.title : from + " · " + n.title);
            notifLine = "● " + who + (n.text.isEmpty() ? "" : ": " + n.text);
        }

        if (currentPage instanceof InfoHub.Media) {
            InfoHub.Media pm = (InfoHub.Media) currentPage;
            int c = artColor(pm.art);
            Bitmap small = pm.art != null ? artSmall.get(pm.art) : null;
            // a new message only replaces the artist line for a moment; the colour stays
            diagonal.setInfo(pm.title, notifLine != null ? notifLine : pm.artist, small, c,
                    ColorExtractor.textColorFor(c), true, pm.playing);
            diagonal.setMusicPosition(pm.position, pm.updateTime, pm.speed, pm.duration);
        } else if (currentPage instanceof NotifList.Item) {
            NotifList.Item it = (NotifList.Item) currentPage;
            AppEntry app = appFor(it.pkg);
            int c = app != null ? app.color : 0xFF26262E;
            String title = it.title.isEmpty() ? (app != null ? app.label : "") : it.title;
            String sub = (app != null ? app.label + " · " : "") + it.text;
            diagonal.setInfoCompact(title, sub, it.large, c, ColorExtractor.textColorFor(c));
        } else if (notifLine != null) {
            // clock stays; for a few seconds the tablet takes the app's colour and shows the message
            AppEntry na = appFor(n.pkg);
            int c = na != null ? na.color : 0xFF16161C;
            diagonal.setInfo(info.time(), notifLine, null, c, ColorExtractor.textColorFor(c));
        } else {
            String sub = info.date();
            if (!info.weather.isEmpty()) sub += "  ·  " + info.weather;
            diagonal.setInfo(info.time(), sub, null, 0xFF16161C, 0xFFFFFFFF);
        }
        updateStatus();
        panels.updateCards();
        if (panels.quick.level() == 2) panels.refreshNotifications();
        maybeShowBanner();
    }

    private void updateStatus() {
        int alert = "red".equals(info.alertLevel) ? 2 : "yellow".equals(info.alertLevel) ? 1 : 0;
        Intent bat = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int pct = -1;
        boolean chg = false;
        if (bat != null) {
            int level = bat.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = bat.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) pct = Math.round(level * 100f / scale);
            int st = bat.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            chg = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL;
        }
        diagonal.setStatus(alert, pct, chg);
    }

    int artColor() {
        InfoHub.Media m = info.media;
        if (m == null || m.art == null) return 0xFF26262E;
        Integer c = artColors.get(m.art);
        return c != null ? c : 0xFF26262E;
    }

    private boolean forcePlayer;
    private Bitmap placeholder;

    private Bitmap placeholderArt() {
        if (placeholder == null) {
            placeholder = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            placeholder.eraseColor(0xFF3A3A44);
        }
        return placeholder;
    }

    @Override
    public void onMusicControl(int which) {
        if (which == 3) { forcePlayer = true; updateInfoStripe(); return; }
        if (which == 4) {   // alert dot: the official alert app, else the live map
            Intent app = getPackageManager().getLaunchIntentForPackage("com.ukrainealarm");
            startFirst(app != null ? app : new Intent(Intent.ACTION_VIEW, Uri.parse("https://alerts.in.ua")),
                    new Intent(Intent.ACTION_VIEW, Uri.parse("https://alerts.in.ua")));
            return;
        }
        InfoHub.Media target = currentPage instanceof InfoHub.Media ? (InfoHub.Media) currentPage : info.media;
        if (which == 0) info.previous(target);
        else if (which == 2) info.next(target);
        else info.playPause(target);
    }

    AppEntry appFor(String pkg) {
        for (AppEntry a : allApps) if (a.component.getPackageName().equals(pkg)) return a;
        return null;
    }

    void openNotificationAccess() {
        startFirst(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    void showDialog(AlertDialog.Builder b) { shownDialog = b.show(); }

    void showDialog(AlertDialog d) { d.show(); shownDialog = d; }

    /** Launcher settings (the "Diagonal" tile in the big quick panel). */
    void showLauncherSettings() {
        String[] items = {getString(R.string.set_hide_status), getString(R.string.set_lock_snapshot),
                getString(R.string.set_quick_one), getString(R.string.set_widgets_one), getString(R.string.set_swap_swipes),
                getString(R.string.set_quiet)};
        boolean[] checked = {prefs.getBoolean(KEY_HIDE_STATUS, true), prefs.getBoolean(KEY_LOCK_SNAPSHOT, false),
                prefs.getBoolean(Panels.KEY_QUICK_ONE, false), prefs.getBoolean(Panels.KEY_WIDGETS_ONE, false),
                prefs.getBoolean(Panels.KEY_SWAP_SWIPES, false), prefs.getBoolean(KEY_QUIET, true)};
        shownDialog = dialog().setTitle(R.string.app_name)
                .setMultiChoiceItems(items, checked, (d, which, on) -> {
                    if (which == 0) { prefs.edit().putBoolean(KEY_HIDE_STATUS, on).apply(); applySystemBars(); }
                    else if (which == 1) {
                        prefs.edit().putBoolean(KEY_LOCK_SNAPSHOT, on).putInt(KEY_SNAPSHOT_SIG, 0).apply();
                    }
                    else if (which == 2) prefs.edit().putBoolean(Panels.KEY_QUICK_ONE, on).apply();
                    else if (which == 3) prefs.edit().putBoolean(Panels.KEY_WIDGETS_ONE, on).apply();
                    else if (which == 4) prefs.edit().putBoolean(Panels.KEY_SWAP_SWIPES, on).apply();
                    else {
                        prefs.edit().putBoolean(KEY_QUIET, on).apply();
                        if (on) {
                            if (!getSystemService(android.app.NotificationManager.class).isNotificationPolicyAccessGranted()) {
                                startFirst(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                            } else quietOn();
                        } else quietOff();
                    }
                })
                .setNeutralButton(R.string.set_notif_access, (d, w) -> openNotificationAccess())
                .setNegativeButton(R.string.alert_region, (d, w) -> chooseAlertRegion())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Region for the air-raid alert dot: auto (from location) or a chosen oblast; or switch it off. */
    private void chooseAlertRegion() {
        String[] regions = InfoHub.UA_REGIONS;
        String[] items = new String[regions.length + 2];
        items[0] = getString(R.string.alert_auto);
        items[1] = getString(R.string.alert_off);
        System.arraycopy(regions, 0, items, 2, regions.length);
        shownDialog = dialog().setTitle(R.string.alert_region)
                .setItems(items, (d, w) -> {
                    SharedPreferences.Editor e = prefs.edit();
                    if (w == 0) e.putString("alert_region", "").putBoolean("alerts_on", true);
                    else if (w == 1) e.putBoolean("alerts_on", false);
                    else e.putString("alert_region", regions[w - 2]).putBoolean("alerts_on", true);
                    e.apply();
                    info.resetAlerts();
                    info.refresh();
                })
                .show();
    }

    @SuppressWarnings("deprecation")
    private void applySystemBars() {
        boolean hide = prefs.getBoolean(KEY_HIDE_STATUS, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            if (hide) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.statusBars());
            } else {
                c.show(WindowInsets.Type.statusBars());
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (hide) flags |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    /** Puts a picture of the home screen on the lock screen (when switched on in settings). */
    private void maybeSnapshotLockScreen() {
        if (!prefs.getBoolean(KEY_LOCK_SNAPSHOT, false)) return;
        int w = diagonal.getWidth(), h = diagonal.getHeight();
        if (w == 0 || h == 0) return;
        int sig = diagonal.homeSignature() * 31 + w * 7 + h;
        if (sig == prefs.getInt(KEY_SNAPSHOT_SIG, 0)) return;
        prefs.edit().putInt(KEY_SNAPSHOT_SIG, sig).apply();
        Bitmap bmp;
        try {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            diagonal.drawSnapshot(new Canvas(bmp));
        } catch (RuntimeException | OutOfMemoryError e) {
            return;
        }
        final Bitmap pic = bmp;
        final WallpaperManager wm = WallpaperManager.getInstance(getApplicationContext());
        worker.execute(() -> {
            try { wm.setBitmap(pic, null, true, WallpaperManager.FLAG_LOCK); } catch (Exception ignored) { }
            pic.recycle();
        });
    }

    /** One question per visit: usage access, then notification access, then location (weather). */
    private void askFirstRunPermissions() {
        if (shownDialog != null && shownDialog.isShowing()) return;
        if (!prefs.getBoolean(KEY_ASKED_USAGE, false) && !hasUsageAccess()) { maybeAskForUsageAccess(); return; }
        if (!prefs.getBoolean(KEY_ASKED_NOTIF, false) && !info.hasNotificationAccess()) {
            prefs.edit().putBoolean(KEY_ASKED_NOTIF, true).apply();
            shownDialog = dialog().setTitle(R.string.notif_title)
                    .setMessage(R.string.notif_message)
                    .setPositiveButton(R.string.usage_allow, (d, w) -> openNotificationAccess())
                    .setNegativeButton(R.string.later, null)
                    .show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 29 && !prefs.getBoolean(KEY_QUIET_ASKED, false) && prefs.getBoolean(KEY_QUIET, true)
                && !getSystemService(android.app.NotificationManager.class).isNotificationPolicyAccessGranted()) {
            maybeAskQuietAccess();
            return;
        }
        if (!prefs.getBoolean(KEY_ASKED_LOCATION, false)
                && checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean(KEY_ASKED_LOCATION, true).apply();
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        }
    }

    @Override
    protected void onDestroy() {
        launcherApps.unregisterCallback(appsCallback);
        main.removeCallbacks(reload);
        panels.onDestroy();
        info.destroy();
        if (shownDialog != null) shownDialog.dismiss();
        main.removeCallbacks(hideBannerLater);
        quietOff();
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
            panels.closeAll();
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
        else if (panels.isOpen()) panels.closeAll();
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
            closeSearch();
            if (app != null) diagonal.showApp(app.key);   // go to its place and blink it
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

    void startFirst(Intent... candidates) {
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
        float dp = getResources().getDisplayMetrics().density;
        int pad = Math.round(18 * dp);
        String[] fav = readFavorites();
        int current = -1;
        for (int i = 0; i < HOME_SLOTS; i++) if (app.key.equals(fav[i])) current = i;
        final int from = current;

        android.app.Dialog dlg = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(new DiagonalBg(0xFF15151B, 26 * dp));
        box.setPadding(0, 0, 0, Math.round(10 * dp));

        // Header: the app as a little stripe + its name
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(pad, pad, pad, Math.round(6 * dp));
        AppTileView tile = new AppTileView(this, app);
        int is = Math.round(56 * dp);
        head.addView(tile, new LinearLayout.LayoutParams(Math.round(is * 1.5f), is));
        TextView name = menuText(app.label, 22, 0xFFFFFFFF, true);
        name.setPadding(Math.round(14 * dp), 0, 0, 0);
        head.addView(name);
        box.addView(head);

        // Colour (only two-tone apps, whose icon is a glyph)
        if (app.glyph) {
            box.addView(menuCaption(getString(R.string.menu_color)));
            int[] palette = {0, 0xFFE53935, 0xFFFF7043, 0xFFFFC107, 0xFF43A047, 0xFF00ACC1, 0xFF1E88E5,
                    0xFF3949AB, 0xFF8E24AA, 0xFFD81B60, 0xFF6D4C41, 0xFF16161C, 0xFFF2F2F2};
            android.widget.GridLayout grid = new android.widget.GridLayout(this);
            int dialogW = Math.round(Math.min(getResources().getDisplayMetrics().widthPixels - 32 * dp, 420 * dp));
            grid.setColumnCount(Math.max(4, Math.min(7, (dialogW - 2 * pad) / Math.round(42 * dp))));
            grid.setPadding(pad - Math.round(3 * dp), 0, pad, Math.round(6 * dp));
            int sw = Math.round(36 * dp);
            int saved = prefs.getInt(KEY_COLOR_PREFIX + app.key, 0);
            for (int c : palette) {
                TextView swatch = menuText(c == 0 ? "A" : (c == saved ? "✓" : ""), 13,
                        c == 0 ? 0xFF111111 : ColorExtractor.textColorFor(c), true);
                swatch.setGravity(Gravity.CENTER);
                android.graphics.drawable.GradientDrawable sb = new android.graphics.drawable.GradientDrawable();
                sb.setColor(c == 0 ? app.autoColor : c);
                sb.setStroke(Math.round(dp), 0x55FFFFFF);
                swatch.setBackground(sb);
                if (c == 0) swatch.setTextColor(ColorExtractor.textColorFor(app.autoColor));
                swatch.setOnClickListener(v -> {
                    dlg.dismiss();
                    if (c == 0) prefs.edit().remove(KEY_COLOR_PREFIX + app.key).apply();
                    else prefs.edit().putInt(KEY_COLOR_PREFIX + app.key, c).apply();
                    rearrange(false);
                });
                android.widget.GridLayout.LayoutParams gl = new android.widget.GridLayout.LayoutParams();
                gl.width = sw;
                gl.height = sw;
                gl.setMargins(Math.round(3 * dp), Math.round(3 * dp), Math.round(3 * dp), Math.round(3 * dp));
                grid.addView(swatch, gl);
            }
            box.addView(grid);
        }

        box.addView(menuRow(getString(R.string.app_info), () -> {
            dlg.dismiss();
            startFirst(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + app.component.getPackageName())));
        }));
        box.addView(menuRow(getString(R.string.uninstall), () -> {
            dlg.dismiss();
            startFirst(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.component.getPackageName())));
        }));
        if (!hasUsageAccess()) box.addView(menuRow(getString(R.string.usage_allow), () -> { dlg.dismiss(); openUsageSettings(); }));

        if (current >= 0) {
            box.addView(menuRow(getString(R.string.unpin), () -> {
                dlg.dismiss();
                lastHome[from] = null;
                String[] f = readFavorites(); f[from] = ""; saveFavorites(f); rearrange(false);
            }));
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        showStyled(dlg, sv);
    }

    private TextView menuText(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD));
        return t;
    }

    private TextView menuCaption(String s) {
        float dp = getResources().getDisplayMetrics().density;
        TextView t = menuText(s.toUpperCase(java.util.Locale.getDefault()), 11, 0x99FFFFFF, true);
        t.setLetterSpacing(0.15f);
        t.setPadding(Math.round(18 * dp), Math.round(14 * dp), Math.round(18 * dp), Math.round(8 * dp));
        return t;
    }

    private TextView menuRow(String s, Runnable action) {
        float dp = getResources().getDisplayMetrics().density;
        TextView t = menuText(s, 17, 0xFFFFFFFF, true);
        t.setPadding(Math.round(18 * dp), Math.round(14 * dp), Math.round(18 * dp), Math.round(14 * dp));
        t.setOnClickListener(v -> action.run());
        return t;
    }

    // ---- no pop-ups on the home screen (own banner instead) -------------

    static final String KEY_QUIET = "quiet_popups";
    private static final String KEY_QUIET_ON = "quiet_active";
    private static final String KEY_QUIET_ASKED = "quiet_asked";
    private boolean resumed;
    private String lastBannerKey;
    private View banner;

    /**
     * While Diagonal is on screen: Diagonal's OWN Do Not Disturb rule (it never touches the
     * user's DND settings or schedules). Calls, alarms, media, system and important
     * conversations still pop up; other notifications don't, and Diagonal shows its own banner.
     */
    private static final android.net.Uri ZEN_URI = android.net.Uri.parse("condition://com.diagonal.launcher/home");

    private String zenRuleId(android.app.NotificationManager nm) {
        if (Build.VERSION.SDK_INT < 29) return null;
        String id = prefs.getString("zen_rule", null);
        try {
            if (id != null && nm.getAutomaticZenRule(id) != null) return id;
            android.service.notification.ZenPolicy.Builder b = new android.service.notification.ZenPolicy.Builder()
                    .allowAlarms(true).allowMedia(true).allowSystem(true)
                    .allowCalls(android.service.notification.ZenPolicy.PEOPLE_TYPE_ANYONE)
                    .allowRepeatCallers(true)
                    .allowMessages(android.service.notification.ZenPolicy.PEOPLE_TYPE_NONE)
                    .allowReminders(false).allowEvents(false)
                    .showPeeking(false)                       // no pop-ups for the rest
                    .showInNotificationList(true).showStatusBarIcons(true).showBadges(true);
            if (Build.VERSION.SDK_INT >= 30) {
                b.allowConversations(android.service.notification.ZenPolicy.CONVERSATION_SENDERS_IMPORTANT);
            }
            android.app.AutomaticZenRule rule = new android.app.AutomaticZenRule(getString(R.string.quiet_title),
                    null, new ComponentName(this, MainActivity.class), ZEN_URI, b.build(),
                    android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
            id = nm.addAutomaticZenRule(rule);
            prefs.edit().putString("zen_rule", id).commit();
            return id;
        } catch (Throwable t) {
            return null;
        }
    }

    private void setZen(boolean on) {
        if (Build.VERSION.SDK_INT < 29) return;
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        try {
            if (!nm.isNotificationPolicyAccessGranted()) return;
            String id = on ? zenRuleId(nm) : prefs.getString("zen_rule", null);
            if (id == null) return;
            nm.setAutomaticZenRuleState(id, new android.service.notification.Condition(ZEN_URI,
                    getString(R.string.quiet_title),
                    on ? android.service.notification.Condition.STATE_TRUE : android.service.notification.Condition.STATE_FALSE));
            quietActive = on;
        } catch (Throwable ignored) { }
    }

    boolean quietActive;
    private int topInset;

    private void quietOn() {
        if (prefs.getBoolean(KEY_QUIET, true)) setZen(true);
    }

    private void quietOff() { setZen(false); }

    /** Puts back DND exactly as it was if build 27 left its global change behind. */
    private void undoOldQuietMode() {
        if (!prefs.getBoolean(KEY_QUIET_ON, false)) return;
        try {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm.isNotificationPolicyAccessGranted() && Build.VERSION.SDK_INT >= 28) {
                android.app.NotificationManager.Policy p = Build.VERSION.SDK_INT >= 30
                        ? new android.app.NotificationManager.Policy(prefs.getInt("qp_cat", 0), prefs.getInt("qp_calls", 0),
                                prefs.getInt("qp_msgs", 0), prefs.getInt("qp_fx", 0), prefs.getInt("qp_conv", 0))
                        : new android.app.NotificationManager.Policy(prefs.getInt("qp_cat", 0), prefs.getInt("qp_calls", 0),
                                prefs.getInt("qp_msgs", 0), prefs.getInt("qp_fx", 0));
                nm.setNotificationPolicy(p);
                if (nm.getCurrentInterruptionFilter() == android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                    nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL);
                }
            }
        } catch (Throwable ignored) { }
        prefs.edit().putBoolean(KEY_QUIET_ON, false).commit();
    }

    /** Diagonal's own pop-up for a message that the system didn't pop up. */
    private void maybeShowBanner() {
        InfoHub.Notif n = info.notif;
        if (n == null || !resumed) return;
        String stamp = n.key + "@" + NotifService.lastPostedAt;   // every new message, even in the same chat
        if (stamp.equals(lastBannerKey)) return;
        lastBannerKey = stamp;
        if (!quietActive || !NotifService.lastPostedIntercepted) return;   // the system already showed it
        AppEntry app = appFor(n.pkg);
        int color = app != null ? app.color : 0xFF26262E;
        int ink = ColorExtractor.textColorFor(color);
        float dp = getResources().getDisplayMetrics().density;
        FrameLayout root = (FrameLayout) diagonal.getParent();
        if (banner != null) root.removeView(banner);

        SlantCard card = new SlantCard(this, color);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int p = Math.round(14 * dp);
        card.setPadding(p, Math.round(10 * dp), p, Math.round(10 * dp));
        card.setCut(Math.round(16 * dp));
        if (app != null) {
            AppTileView tile = new AppTileView(this, app);
            int th = Math.round(34 * dp);
            card.addView(tile, new LinearLayout.LayoutParams(Math.round(th * 1.5f), th));
        }
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Math.round(12 * dp), 0, 0, 0);
        TextView t1 = menuText(n.title.isEmpty() && app != null ? app.label : n.title, 15, ink, true);
        TextView t2 = menuText(n.text, 13, (ink & 0x00FFFFFF) | 0xCC000000, false);
        texts.addView(t1);
        if (!n.text.isEmpty()) texts.addView(t2);
        card.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final InfoHub.Notif shown = n;
        card.setOnClickListener(v -> {
            hideBanner();
            boolean ok = false;
            try { if (shown.intent != null) { InfoHub.sendAllowingStart(this, shown.intent); ok = true; } }
            catch (Exception ignored) { }
            if (!ok) { AppEntry a = appFor(shown.pkg); if (a != null) launch(a); }
        });
        card.setElevation(8 * dp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        int m = Math.round(10 * dp);
        lp.setMargins(m, m + Math.round(8 * dp) + topInset, m, 0);
        root.addView(card, lp);
        banner = card;
        card.setTranslationY(-200 * dp);
        card.animate().translationY(0).setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        // swipe it up to hide
        card.setOnTouchListener(new View.OnTouchListener() {
            float y0;
            boolean gone;
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) { y0 = e.getRawY(); gone = false; }
                if (gone) return true;
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE && e.getRawY() - y0 < -24 * dp) {
                    gone = true;
                    v.setPressed(false);
                    android.view.MotionEvent c = android.view.MotionEvent.obtain(e);
                    c.setAction(android.view.MotionEvent.ACTION_CANCEL);
                    v.onTouchEvent(c);
                    c.recycle();
                    hideBanner();          // swipe up = just hide, don't open
                    return true;
                }
                return false;
            }
        });
        main.removeCallbacks(hideBannerLater);
        main.postDelayed(hideBannerLater, 4500);
    }

    private final Runnable hideBannerLater = this::hideBanner;

    private void hideBanner() {
        View b = banner;
        if (b == null) return;
        banner = null;
        b.animate().translationY(-b.getHeight() - 40).setDuration(200).withEndAction(() -> {
            if (b.getParent() != null) ((FrameLayout) b.getParent()).removeView(b);
        }).start();
    }

    private void maybeAskQuietAccess() {
        if (prefs.getBoolean(KEY_QUIET_ASKED, false) || !prefs.getBoolean(KEY_QUIET, true)) return;
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm.isNotificationPolicyAccessGranted()) return;
        prefs.edit().putBoolean(KEY_QUIET_ASKED, true).apply();
        shownDialog = dialog().setTitle(R.string.quiet_title)
                .setMessage(R.string.quiet_message)
                .setPositiveButton(R.string.usage_allow, (d, w) ->
                        startFirst(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)))
                .setNegativeButton(R.string.later, null)
                .show();
    }

    // ---- app picker & home-apps editor ----------------------------------

    interface AppChoice { void chosen(AppEntry app); }   // null = "auto"

    void launch(AppEntry app) {
        View v = diagonal;
        Rect b = new Rect(v.getWidth() / 2 - 40, v.getHeight() / 2 - 40, v.getWidth() / 2 + 40, v.getHeight() / 2 + 40);
        onLaunch(app, v, b);
    }

    /** Two apps for the free corners of the small quick triangle: user picks, else most used. */
    List<AppEntry> quickApps() {
        List<AppEntry> out = new ArrayList<>();
        java.util.Set<String> skip = new java.util.HashSet<>();
        for (AppEntry a : lastHome) if (a != null) skip.add(a.key);
        Map<String, AppEntry> byKey = new HashMap<>();
        for (AppEntry a : allApps) byKey.put(a.key, a);
        AppEntry[] chosen = new AppEntry[QuickPanel.LEVEL1_APPS];
        for (int i = 0; i < chosen.length; i++) {
            AppEntry a = byKey.get(prefs.getString(Panels.KEY_QUICK_APP + i, ""));
            if (a != null) { chosen[i] = a; skip.add(a.key); }
        }
        List<AppEntry> byUsage = new ArrayList<>(allApps);
        Map<String, Long> use = usage;
        byUsage.sort((a, b) -> Long.compare(use.getOrDefault(b.component.getPackageName(), 0L),
                use.getOrDefault(a.component.getPackageName(), 0L)));
        int next = 0;
        for (int i = 0; i < chosen.length; i++) {
            while (chosen[i] == null && next < byUsage.size()) {
                AppEntry a = byUsage.get(next++);
                if (skip.add(a.key)) chosen[i] = a;
            }
            if (chosen[i] != null) out.add(chosen[i]);
        }
        return out;
    }

    /** A clean app list (search + icons) used everywhere an app has to be chosen. */
    void pickApp(String title, AppChoice choice) {
        float dp = getResources().getDisplayMetrics().density;
        int pad = Math.round(18 * dp);
        android.app.Dialog dlg = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(new DiagonalBg(0xFF15151B, 26 * dp));

        TextView head = menuText(title, 20, 0xFFFFFFFF, true);
        head.setPadding(pad, pad, pad, Math.round(10 * dp));
        box.addView(head);

        EditText filter = new EditText(this);
        filter.setHint(R.string.search_hint);
        filter.setHintTextColor(0x66FFFFFF);
        filter.setTextColor(0xFFFFFFFF);
        filter.setSingleLine(true);
        GradientDrawable fb = new GradientDrawable();
        fb.setCornerRadius(14 * dp);
        fb.setColor(0xFF24242C);
        filter.setBackground(fb);
        filter.setPadding(Math.round(14 * dp), Math.round(10 * dp), Math.round(14 * dp), Math.round(10 * dp));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        flp.setMargins(pad, 0, pad, Math.round(8 * dp));
        box.addView(filter, flp);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f)));

        Collator collator = Collator.getInstance();
        List<AppEntry> sorted = new ArrayList<>(allApps);
        sorted.sort((a, b) -> collator.compare(a.label, b.label));
        Runnable fill = () -> {
            list.removeAllViews();
            String q = filter.getText().toString().trim().toLowerCase();
            if (q.isEmpty()) list.addView(appRow(null, getString(R.string.slot_auto_full), v -> { dlg.dismiss(); choice.chosen(null); }));
            for (AppEntry a : sorted) {
                if (!q.isEmpty() && !a.label.toLowerCase().contains(q)) continue;
                list.addView(appRow(a, a.label, v -> { dlg.dismiss(); choice.chosen(a); }));
            }
        };
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { fill.run(); }
        });
        fill.run();
        showStyled(dlg, box);
    }

    /** One row of the app list: a small diagonal tile in the app's colour with its icon, and the name. */
    private View appRow(AppEntry a, String name, View.OnClickListener click) {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(18 * dp), Math.round(6 * dp), Math.round(18 * dp), Math.round(6 * dp));
        row.setOnClickListener(click);
        AppTileView tile = new AppTileView(this, a);
        int s = Math.round(44 * dp);
        row.addView(tile, new LinearLayout.LayoutParams(Math.round(s * 1.5f), s));
        TextView t = menuText(name, 16, 0xFFFFFFFF, true);
        t.setPadding(Math.round(14 * dp), 0, 0, 0);
        row.addView(t);
        return row;
    }

    /** Long-press on the centre stripe: choose the 4 home apps. */
    @Override
    public void onInfoLongPress() {
        float dp = getResources().getDisplayMetrics().density;
        int pad = Math.round(18 * dp);
        android.app.Dialog dlg = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(new DiagonalBg(0xFF15151B, 26 * dp));
        box.setPadding(0, 0, 0, pad);

        TextView head = menuText(getString(R.string.home_title), 20, 0xFFFFFFFF, true);
        head.setPadding(pad, pad, pad, Math.round(4 * dp));
        box.addView(head);
        TextView hint = menuText(getString(R.string.home_hint), 13, 0x99FFFFFF, false);
        hint.setSingleLine(false);
        hint.setPadding(pad, 0, pad, Math.round(12 * dp));
        box.addView(hint);

        String[] fav = readFavorites();
        Map<String, AppEntry> byKey = new HashMap<>();
        for (AppEntry a : allApps) byKey.put(a.key, a);
        for (int slot = 0; slot < HOME_SLOTS; slot++) {
            boolean pinned = !fav[slot].isEmpty() && byKey.containsKey(fav[slot]);
            AppEntry shown = pinned ? byKey.get(fav[slot]) : lastHome[slot];
            final int s = slot;
            String label = (slot + 1) + "   " + (shown == null ? "—" : shown.label)
                    + (pinned ? "" : "   · " + getString(R.string.slot_auto));
            box.addView(appRow(shown, label, v -> {
                dlg.dismiss();
                pickApp(getString(R.string.home_place, s + 1), app -> {
                    if (app == null) {
                        lastHome[s] = null;
                        String[] f = readFavorites(); f[s] = ""; saveFavorites(f); rearrange(false);
                    } else {
                        String[] f = readFavorites();
                        int from = -1;
                        for (int i = 0; i < HOME_SLOTS; i++) if (app.key.equals(f[i])) from = i;
                        pin(app, s, from);
                    }
                    onInfoLongPress();   // back to the editor to see the result
                });
            }));
        }
        showStyled(dlg, box);
    }

    private void showStyled(android.app.Dialog dlg, View content) {
        float dp = getResources().getDisplayMetrics().density;
        dlg.setContentView(content);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            dlg.getWindow().setLayout(Math.round(Math.min(getResources().getDisplayMetrics().widthPixels - 32 * dp, 440 * dp)),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        shownDialog = dlg;
        dlg.show();
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
        // user colours (two-tone apps only)
        for (AppEntry a : apps) {
            int c = a.glyph ? prefs.getInt(KEY_COLOR_PREFIX + a.key, 0) : 0;
            a.color = c != 0 ? c : a.autoColor;
            a.textColor = ColorExtractor.textColorFor(a.color);
        }

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
        favs.add(Math.min(2, favs.size()), infoEntry);   // smart stripe in the very middle

        List<AppEntry> usageSide = new ArrayList<>();
        for (AppEntry a : byUsage) if (!taken.contains(a.key)) usageSide.add(a);
        Collections.reverse(usageSide);              // most used ends up next to the home apps

        List<AppEntry> alphaSide = new ArrayList<>();
        for (AppEntry a : apps) alphaSide.add(a);   // home apps stay findable in A–Z (not in "by usage")
        alphaSide.sort((a, b) -> collator.compare(a.label, b.label));

        List<AppEntry> all = new ArrayList<>(usageSide.size() + favs.size() + alphaSide.size());
        all.addAll(usageSide);
        all.addAll(favs);
        all.addAll(alphaSide);
        diagonal.setEmptyText(apps.isEmpty() ? getString(R.string.no_apps) : "");
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
                panels.onAppsLoaded();
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
                AppEntry e = new AppEntry(cn, label, r.glyph, r.color, r.textColor);
                e.glyph = r.isGlyph;
                list.add(e);
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
