package com.diagonal.launcher;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The two corner panels:
 *  - swipe along the stripes towards the bottom-left → quick settings triangle (top-right);
 *    swipe again → the big version.
 *  - swipe along the stripes towards the top-right → widget window (bottom-left);
 *    peeks first, swipe again → full screen.
 * Tap outside or swipe the other way to close.
 */
final class Panels {

    // Quick-settings tile ids (also their order: the first 4 are the small triangle).
    private static final int T_BT = 0, T_NET = 1, T_WALLET = 2, T_TORCH = 3, T_WIFI = 4, T_SOUND = 5,
            T_DND = 6, T_ROTATE = 7, T_NFC = 8, T_HOTSPOT = 9, T_LOCATION = 10, T_ALARM = 11,
            T_PLANE = 12, T_CAMERA = 13, T_SHADE = 14, T_SETTINGS = 15;

    static final String CLAUDE_PKG = "com.anthropic.claude";

    private final MainActivity act;
    private final InfoHub info;
    private final SharedPreferences prefs;
    private final float dp;
    final QuickPanel quick;
    final WidgetPanel widgets;
    private final List<QuickPanel.Tile> tiles = new ArrayList<>();
    private final GestureDetector outside;
    private boolean downInside;
    private boolean flung;
    private boolean inList;   // the current gesture was an along-stripe fling (steps a panel)

    // own widgets
    private TextView musicTitle, musicArtist, musicPlay;
    private SlantCard musicCard;
    private final List<TextView> musicButtons = new ArrayList<>();
    private ImageView musicArt;
    private LinearLayout chatRow, claudeChips;
    private TextView chatHint;

    private boolean torchOn;
    private String torchId;

    Panels(MainActivity act, FrameLayout root, InfoHub info, SharedPreferences prefs) {
        this.act = act;
        this.info = info;
        this.prefs = prefs;
        dp = act.getResources().getDisplayMetrics().density;

        // ---- widget window (staircase of own cards) ------------------------
        widgets = new WidgetPanel(act);
        widgets.setOnEmptyTap(() -> widgets.setLevel(prefs.getBoolean(KEY_WIDGETS_ONE, false) ? 0 : widgets.level() - 1));
        buildWidgetCards();
        root.addView(widgets, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- quick settings triangle ---------------------------------------
        quick = new QuickPanel(act);
        quick.setListener(new QuickPanel.Listener() {
            @Override public void onTile(QuickPanel.Tile t) { Panels.this.onTile(t); }
            @Override public void onTilesReordered(List<QuickPanel.Tile> list) {
                tiles.clear();
                tiles.addAll(list);
                List<String> ids = new ArrayList<>();
                for (QuickPanel.Tile t : list) ids.add(String.valueOf(t.id));
                prefs.edit().putString(KEY_TILE_ORDER, TextUtils.join(",", ids)).apply();
            }
            @Override public void onApp(AppEntry app) { closeAll(); act.launch(app); }
            @Override public void onAppLongPress(int slot) {
                act.pickApp(act.getString(R.string.quick_app_title), app -> {
                    prefs.edit().putString(KEY_QUICK_APP + slot, app == null ? "" : app.key).apply();
                    quick.setApps(act.quickApps());
                });
            }
            @Override public void onNotification(QuickPanel.Row r) { }
            @Override public void onClearNotifications() { clearNotifications(); }
        });
        root.addView(quick, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildTiles();
        buildNotifList(root);

        outside = new GestureDetector(act, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (!downInside) closeAll();
                return false;   // a tap is not a swipe: never cancel taps on tiles/cards
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null) return false;
                float tx = e2.getX() - e1.getX(), ty = e2.getY() - e1.getY();
                float along = (tx - ty) * 0.7071f, across = (tx + ty) * 0.7071f;
                if (Math.abs(along) < 56 * dp || Math.abs(along) < Math.abs(across) * 2.4f) return false;
                flung = true;
                onCornerSwipe(along > 0 ? 1 : -1);
                return true;
            }
        });

        try {
            CameraManager cm = act.getSystemService(CameraManager.class);
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) { torchId = id; break; }
            }
            cm.registerTorchCallback(torchCallback, null);
        } catch (Exception ignored) { }
    }

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId.equals(torchId)) { torchOn = enabled; refreshTiles(); }
        }
    };

    private volatile boolean destroyed;

    void onDestroy() {
        destroyed = true;
        shortcutWorker.shutdownNow();
        try { act.getSystemService(CameraManager.class).unregisterTorchCallback(torchCallback); }
        catch (RuntimeException ignored) { }
    }

    void setInsets(int top, int bottom) {
        quick.setTopInset(top);
        widgets.setInsets(top, bottom);
    }

    void onStart() { refreshShortcuts(); }

    void onStop() { }

    boolean isOpen() { return quick.level() > 0 || widgets.level() > 0; }

    void closeAll() {
        if (quick.level() > 0) quick.setLevel(0);
        if (widgets.level() > 0) widgets.setLevel(0);
    }

    /** +1 = swipe towards top-right (widgets), −1 = towards bottom-left (quick settings). */
    static final String KEY_TILE_ORDER = "tile_order";
    static final String KEY_SWAP_SWIPES = "swap_swipes";
    static final String KEY_QUICK_ONE = "quick_one_swipe";
    static final String KEY_WIDGETS_ONE = "widgets_one_swipe";
    static final String KEY_QUICK_APP = "quick_app";

    void onCornerSwipe(int dir) {
        if (prefs.getBoolean(KEY_SWAP_SWIPES, false)) dir = -dir;   // user swapped the two swipes
        boolean quickOne = prefs.getBoolean(KEY_QUICK_ONE, false);
        boolean widgetsOne = prefs.getBoolean(KEY_WIDGETS_ONE, false);
        if (dir < 0) {
            if (widgets.level() > 0) { widgets.setLevel(widgetsOne ? 0 : widgets.level() - 1); return; }
            refreshTiles();
            refreshNotifications();
            quick.setApps(act.quickApps());
            quick.setLevel(quickOne ? 2 : Math.min(2, quick.level() + 1));
        } else {
            if (quick.level() > 0) { quick.setLevel(quickOne ? 0 : quick.level() - 1); return; }
            if (widgets.level() == 0) { refreshShortcuts(); refreshCalendar(); }
            widgets.setLevel(widgetsOne ? 2 : Math.min(2, widgets.level() + 1));
        }
    }

    /** While a panel is open: outside taps close it, along-stripe flings step it. */
    boolean dispatch(MotionEvent e) {
        if (!isOpen()) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downInside = quick.contains(e.getX(), e.getY()) || widgets.contains(e.getX(), e.getY());
            inList = notifList.getVisibility() == View.VISIBLE && e.getY() >= notifList.getTop()
                    && quick.contains(e.getX(), e.getY());
            flung = false;
        }
        if (inList) return false;   // the list has its own swipes (dismiss) and scrolling
        outside.onTouchEvent(e);
        if (flung && downInside) {
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            act.superDispatch(cancel);   // let the panel's own views finish their gesture
            cancel.recycle();
            return true;
        }
        return !downInside; // touches outside never reach the stripes underneath
    }

    // ---- notifications in the big triangle (Android-style list) ----------

    NotifList notifList;
    private String notifSignature = "";

    private void buildNotifList(FrameLayout root) {
        notifList = new NotifList(act, new NotifList.Host() {
            @Override public AppEntry appFor(String pkg) { return act.appFor(pkg); }
            @Override public void open(NotifList.Item it) {
                closeAll();
                boolean ok = false;
                try { if (it.intent != null) { InfoHub.sendAllowingStart(act, it.intent); ok = true; } } catch (Exception ignored) { }
                if (!ok) { AppEntry a = act.appFor(it.pkg); if (a != null) act.launch(a); }   // no link: open the app
                NotifService svc = NotifService.instance;
                if (it.autoCancel && svc != null) {
                    try { svc.cancelNotification(it.key); } catch (RuntimeException ignored) { }
                }
            }
            @Override public void dismiss(List<NotifList.Item> items) {
                NotifService svc = NotifService.instance;
                if (svc != null) {
                    for (NotifList.Item it : items) {
                        try { svc.cancelNotification(it.key); } catch (RuntimeException ignored) { }
                    }
                }
                notifSignature = "";            // resync either way (card stays gone only if it really went)
                notifList.post(() -> refreshNotifications());
            }
            @Override public void clearAll() { clearNotifications(); }
            @Override public String clearLabel() { return act.getString(R.string.clear_all); }
            @Override public void openHistory() {
                closeAll();
                act.startFirst(new Intent("android.settings.NOTIFICATION_HISTORY"),
                        new Intent("android.settings.NOTIFICATION_SETTINGS"), new Intent(Settings.ACTION_SETTINGS));
            }
            @Override public void openSettings() {
                closeAll();
                act.startFirst(new Intent("android.settings.NOTIFICATION_SETTINGS"), new Intent(Settings.ACTION_SETTINGS));
            }
            @Override public String replyHint() { return act.getString(R.string.reply_hint); }
        });
        notifList.attachShape(quick);
        quick.setOnShapeChange(() -> {
            boolean show = quick.level() == 2;
            if (show != (notifList.getVisibility() == View.VISIBLE)) {
                notifList.setVisibility(show ? View.VISIBLE : View.GONE);
                if (!show) {
                    View f = notifList.findFocus();
                    if (f != null) {
                        act.getSystemService(android.view.inputmethod.InputMethodManager.class)
                                .hideSoftInputFromWindow(f.getWindowToken(), 0);
                        f.clearFocus();
                    }
                    notifList.applyPending();
                }
            }
            if (show) {
                int top = Math.round(quick.tilesEnd());
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) notifList.getLayoutParams();
                if (lp.topMargin != top) { lp.topMargin = top; notifList.setLayoutParams(lp); }
                notifList.setTopOnScreen(top);
            }
        });
        root.addView(notifList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void refreshNotifications() {
        List<NotifList.Item> out = new ArrayList<>();
        NotifService svc = NotifService.instance;
        android.service.notification.StatusBarNotification[] all = null;
        if (svc != null) {
            try { all = svc.getActiveNotifications(); } catch (RuntimeException ignored) { }
        }
        StringBuilder sig = new StringBuilder();
        if (all != null) {
            List<android.service.notification.StatusBarNotification> list = new ArrayList<>();
            for (android.service.notification.StatusBarNotification sbn : all) {
                android.app.Notification n = sbn.getNotification();
                if (n == null || sbn.isOngoing() || !sbn.isClearable()) continue;
                if ((n.flags & android.app.Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                if (sbn.getPackageName().equals(act.getPackageName())) continue;
                if (android.app.Notification.CATEGORY_TRANSPORT.equals(n.category)) continue;
                list.add(sbn);
            }
            list.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
            for (android.service.notification.StatusBarNotification sbn : list) {
                sig.append(sbn.getKey()).append('@').append(sbn.getPostTime()).append(';');
            }
            String s = sig.toString();
            if (s.equals(notifSignature)) return;   // nothing changed: don't rebuild (keeps a reply being typed)
            notifSignature = s;
            for (android.service.notification.StatusBarNotification sbn : list) {
                NotifList.Item it = NotifList.from(act, sbn);
                if (it.title.isEmpty() && it.text.isEmpty()) continue;
                out.add(it);
            }
        } else {
            if (notifSignature.isEmpty()) return;
            notifSignature = "";
        }
        quick.setHasNotifications(!out.isEmpty());
        notifList.setItems(out);
    }

    private void clearNotifications() {
        NotifService svc = NotifService.instance;
        if (svc != null) {
            try { svc.cancelAllNotifications(); } catch (RuntimeException ignored) { }
        }
        notifSignature = "";
        notifList.setItems(new ArrayList<>());
        quick.setHasNotifications(false);
    }

    // ---- quick settings -------------------------------------------------

    private void buildTiles() {
        String[] names = {"Bluetooth", act.getString(R.string.tile_internet), act.getString(R.string.tile_wallet),
                act.getString(R.string.tile_torch), "Wi-Fi", act.getString(R.string.tile_sound),
                act.getString(R.string.tile_dnd), act.getString(R.string.tile_rotate), "NFC",
                act.getString(R.string.tile_hotspot), act.getString(R.string.tile_location),
                act.getString(R.string.tile_alarm), act.getString(R.string.tile_plane),
                act.getString(R.string.tile_camera), act.getString(R.string.tile_shade), "Diagonal"};
        int[] icons = {Icons.BLUETOOTH, Icons.INTERNET, Icons.WALLET, Icons.TORCH, Icons.WIFI, Icons.SOUND,
                Icons.DND, Icons.ROTATE, Icons.NFC, Icons.HOTSPOT, Icons.LOCATION, Icons.ALARM,
                Icons.AIRPLANE, Icons.CAMERA, Icons.SHADE, Icons.SETTINGS};
        List<QuickPanel.Tile> all = new ArrayList<>();
        for (int i = 0; i < names.length; i++) all.add(new QuickPanel.Tile(i, icons[i], names[i]));
        // user order (long-press a tile, tap another to swap)
        for (String s : prefs.getString(KEY_TILE_ORDER, "").split(",")) {
            try {
                int id = Integer.parseInt(s);
                for (QuickPanel.Tile t : all) if (t.id == id && !tiles.contains(t)) tiles.add(t);
            } catch (NumberFormatException ignored) { }
        }
        for (QuickPanel.Tile t : all) if (!tiles.contains(t)) tiles.add(t);
        quick.setTiles(tiles);
    }

    void refreshTiles() {
        for (QuickPanel.Tile t : tiles) {
            try {
                switch (t.id) {
                    case T_BT: {
                        BluetoothManager bm = act.getSystemService(BluetoothManager.class);
                        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
                        t.active = a != null && a.isEnabled();
                        break;
                    }
                    case T_WIFI: {
                        WifiManager wm = act.getApplicationContext().getSystemService(WifiManager.class);
                        t.active = wm != null && wm.isWifiEnabled();
                        break;
                    }
                    case T_TORCH: t.active = torchOn; break;
                    case T_DND: {
                        NotificationManager nm = act.getSystemService(NotificationManager.class);
                        t.active = nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
                        break;
                    }
                    case T_ROTATE:
                        t.active = Settings.System.getInt(act.getContentResolver(),
                                Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
                        break;
                    case T_NFC: {
                        NfcAdapter n = NfcAdapter.getDefaultAdapter(act);
                        t.active = n != null && n.isEnabled();
                        break;
                    }
                    case T_LOCATION: {
                        LocationManager lm = act.getSystemService(LocationManager.class);
                        t.active = Build.VERSION.SDK_INT >= 28 && lm.isLocationEnabled();
                        break;
                    }
                    case T_PLANE:
                        t.active = Settings.Global.getInt(act.getContentResolver(),
                                Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
                        break;
                    case T_ALARM: {
                        AlarmManager am = act.getSystemService(AlarmManager.class);
                        AlarmManager.AlarmClockInfo next = am.getNextAlarmClock();
                        t.label = next == null ? act.getString(R.string.tile_alarm)
                                : new SimpleDateFormat("EE HH:mm", Locale.getDefault()).format(new Date(next.getTriggerTime()));
                        t.active = next != null;
                        break;
                    }
                    default: break;
                }
            } catch (RuntimeException ignored) {
                t.active = false;
            }
        }
        quick.invalidate();
    }

    private void onTile(QuickPanel.Tile t) {
        switch (t.id) {
            case T_BT:
                act.startFirst(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                break;
            case T_NET:
                act.startFirst(Build.VERSION.SDK_INT >= 29 ? new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                        : new Intent(Settings.ACTION_WIRELESS_SETTINGS), new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                break;
            case T_WIFI:
                act.startFirst(Build.VERSION.SDK_INT >= 29 ? new Intent(Settings.Panel.ACTION_WIFI)
                        : new Intent(Settings.ACTION_WIFI_SETTINGS), new Intent(Settings.ACTION_WIFI_SETTINGS));
                break;
            case T_WALLET: {
                Intent w = act.getPackageManager().getLaunchIntentForPackage("com.google.android.apps.walletnfcrel");
                if (w != null) act.startFirst(w);
                else act.startFirst(new Intent(Settings.ACTION_NFC_PAYMENT_SETTINGS), new Intent(Settings.ACTION_NFC_SETTINGS));
                break;
            }
            case T_TORCH:
                try {
                    if (torchId != null) act.getSystemService(CameraManager.class).setTorchMode(torchId, !torchOn);
                } catch (Exception ignored) { }
                return; // stay open; the callback refreshes the tile
            case T_SOUND: {
                AudioManager am = act.getSystemService(AudioManager.class);
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
                return;
            }
            case T_DND: {
                NotificationManager nm = act.getSystemService(NotificationManager.class);
                if (nm.isNotificationPolicyAccessGranted()) {
                    boolean on = nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
                    nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_ALL
                            : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                    refreshTiles();
                    return;
                }
                act.startFirst(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                break;
            }
            case T_ROTATE:
                if (Settings.System.canWrite(act)) {
                    boolean on = Settings.System.getInt(act.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
                    Settings.System.putInt(act.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, on ? 0 : 1);
                    refreshTiles();
                    return;
                }
                act.startFirst(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + act.getPackageName())));
                break;
            case T_NFC:
                act.startFirst(Build.VERSION.SDK_INT >= 29 ? new Intent(Settings.Panel.ACTION_NFC)
                        : new Intent(Settings.ACTION_NFC_SETTINGS), new Intent(Settings.ACTION_NFC_SETTINGS));
                break;
            case T_HOTSPOT:
                act.startFirst(new Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
                        new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                break;
            case T_LOCATION:
                act.startFirst(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                break;
            case T_ALARM:
                act.startFirst(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
                break;
            case T_PLANE:
                act.startFirst(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
                break;
            case T_CAMERA:
                act.startFirst(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
                break;
            case T_SHADE:
                closeAll();
                expandSystemQuickSettings();
                return;
            case T_SETTINGS:
                closeAll();
                act.showLauncherSettings();
                return;
            default: return;
        }
        closeAll();
    }

    @SuppressWarnings({"JavaReflectionMemberAccess", "WrongConstant"})
    private void expandSystemQuickSettings() {
        try {
            Object sbs = act.getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandSettingsPanel").invoke(sbs);
        } catch (Exception e) {
            act.startFirst(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    // ---- widget window: own cards that fit the triangle -----------------

    private final java.util.concurrent.ExecutorService shortcutWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private GradientDrawable cardBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(22 * dp);
        g.setColor(color);
        return g;
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD));
        return t;
    }

    /** Cards are added bottom-first: player (in the corner), chats, Claude. */
    private void buildWidgetCards() {
        int pad = Math.round(14 * dp);

        // Player
        musicCard = new SlantCard(act, 0xFF26262E);
        SlantCard music = musicCard;
        music.setOrientation(LinearLayout.HORIZONTAL);
        music.setGravity(Gravity.CENTER_VERTICAL);
        music.setPadding(pad, pad, pad, pad);
        musicArt = new ImageView(act);
        musicArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        musicArt.setClipToOutline(true);
        musicArt.setBackgroundColor(0x33000000);
        int art = Math.round(64 * dp);
        music.addView(musicArt, new LinearLayout.LayoutParams(art, art));
        LinearLayout texts = new LinearLayout(act);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(pad, 0, 0, 0);
        musicTitle = text(17, 0xFFFFFFFF, true);
        musicArtist = text(13, 0xBBFFFFFF, false);
        texts.addView(musicTitle);
        texts.addView(musicArtist);
        LinearLayout buttons = new LinearLayout(act);
        buttons.addView(control("⏮", v -> info.previous()));
        musicPlay = control("▶", v -> {
            if (!info.hasNotificationAccess()) act.openNotificationAccess();
            else info.playPause();
        });
        buttons.addView(musicPlay);
        buttons.addView(control("⏭", v -> info.next()));
        texts.addView(buttons);
        music.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        music.setOnClickListener(v -> {
            InfoHub.Media mm = info.media;
            if (mm != null && mm.controller != null && mm.controller.getSessionActivity() != null) {
                try { InfoHub.sendAllowingStart(act, mm.controller.getSessionActivity()); } catch (Exception ignored) { }
            }
        });
        music.setClickable(true);
        register("player", music, 128);

        // Chats: recent conversations (shortcuts published by messengers)
        SlantCard chats = new SlantCard(act, 0xFF1565C0);
        chats.setOrientation(LinearLayout.VERTICAL);
        chats.setPadding(pad, Math.round(10 * dp), pad, Math.round(10 * dp));
        chats.setClickable(true);
        TextView chatsTitle = text(12, 0x99FFFFFF, true);
        chatsTitle.setText(act.getString(R.string.chats_title));
        chatsTitle.setLetterSpacing(0.15f);
        chats.addView(chatsTitle);
        chatRow = new LinearLayout(act);
        chatRow.setOrientation(LinearLayout.HORIZONTAL);
        chatRow.setPadding(0, Math.round(6 * dp), 0, 0);
        chatHint = text(13, 0xAAFFFFFF, false);
        android.widget.HorizontalScrollView chatScroll = new android.widget.HorizontalScrollView(act);
        chatScroll.setHorizontalScrollBarEnabled(false);
        chatScroll.addView(chatRow);
        chats.addView(chatScroll);
        chats.addView(chatHint);
        register("chats", chats, 124);

        // Claude: ask box + Claude's own shortcuts
        SlantCard claude = new SlantCard(act, 0xFFD97757);
        claude.setOrientation(LinearLayout.VERTICAL);
        claude.setPadding(pad, Math.round(10 * dp), pad, Math.round(10 * dp));
        claude.setClickable(true);
        TextView claudeTitle = text(12, 0xCCFFFFFF, true);
        claudeTitle.setText("CLAUDE");
        claudeTitle.setLetterSpacing(0.15f);
        claude.addView(claudeTitle);
        TextView ask = text(16, 0xFF5A2A1A, true);
        ask.setText(act.getString(R.string.claude_ask));
        ask.setGravity(Gravity.CENTER_VERTICAL);
        ask.setPadding(pad, 0, pad, 0);
        GradientDrawable askBg = new GradientDrawable();
        askBg.setCornerRadius(18 * dp);
        askBg.setColor(0xFFF5E6DD);
        ask.setBackground(askBg);
        ask.setOnClickListener(v -> askClaude());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(44 * dp));
        alp.topMargin = Math.round(6 * dp);
        claude.addView(ask, alp);
        claudeChips = new LinearLayout(act);
        claudeChips.setOrientation(LinearLayout.HORIZONTAL);
        claudeChips.setPadding(0, Math.round(8 * dp), 0, 0);
        android.widget.HorizontalScrollView claudeScroll = new android.widget.HorizontalScrollView(act);
        claudeScroll.setHorizontalScrollBarEnabled(false);
        claudeScroll.addView(claudeChips);
        claude.addView(claudeScroll);
        register("claude", claude, 140);

        buildExtraCards();
        arrangeCards();
    }

    // ---- configurable card list -----------------------------------------

    static final String KEY_CARDS = "cards";
    static final String[] ALL_CARDS = {"player", "chats", "claude", "weather", "calendar", "battery", "alarm", "notes"};
    private final java.util.Map<String, View> cardViews = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> cardHeights = new java.util.HashMap<>();

    private void register(String id, View card, int heightDp) {
        cardViews.put(id, card);
        cardHeights.put(id, heightDp);
        setLongPress(card, id);
    }

    private void setLongPress(View v, String id) {
        v.setOnLongClickListener(x -> { cardMenu(id); return true; });
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int k = 0; k < g.getChildCount(); k++) setLongPress(g.getChildAt(k), id);
        }
    }

    private String cardName(String id) {
        switch (id) {
            case "player": return act.getString(R.string.card_player);
            case "chats": return act.getString(R.string.card_chats);
            case "claude": return "Claude";
            case "weather": return act.getString(R.string.card_weather);
            case "calendar": return act.getString(R.string.card_calendar);
            case "battery": return act.getString(R.string.card_battery);
            case "alarm": return act.getString(R.string.card_alarm);
            case "notes": return act.getString(R.string.card_notes);
            default: {
                AppEntry a = id.startsWith("app:") ? act.appFor(id.substring(4)) : null;
                return a != null ? a.label : id;
            }
        }
    }

    /** Card ids in order from the corner (bottom) up. The bottom one also shows in the small window. */
    private List<String> cardOrder() {
        List<String> out = new ArrayList<>();
        String saved = prefs.getString(KEY_CARDS, "player,chats,claude");
        for (String id : saved.split(",")) {
            if (id.startsWith("app:") && !cardViews.containsKey(id) && !buildAppCard(id)) continue;
            if (cardViews.containsKey(id) && !out.contains(id)) out.add(id);
        }
        return out;
    }

    /** App widgets need the app list; build them once it has loaded. */
    void onAppsLoaded() {
        boolean changed = false;
        for (String id : new ArrayList<>(cardViews.keySet())) {
            if (!id.startsWith("app:")) continue;
            String pkg = id.substring(4);
            AppEntry now = act.appFor(pkg);
            if (now == null || now != appCardEntries.get(pkg) || now.color != appCardColors.getOrDefault(pkg, 0)) {
                cardViews.remove(id);            // uninstalled, updated or recoloured: rebuild
                cardHeights.remove(id);
                appChipRows.remove(pkg);
                changed = true;
            }
        }
        for (String id : prefs.getString(KEY_CARDS, "").split(",")) {
            if (id.startsWith("app:") && !cardViews.containsKey(id)) changed = true;
        }
        if (changed) arrangeCards();
    }

    private final java.util.Map<String, AppEntry> appCardEntries = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> appCardColors = new java.util.HashMap<>();

    private void saveOrder(List<String> ids) {
        List<String> out = new ArrayList<>(ids);
        // keep app widgets whose app can't be found right now (disabled / updating)
        for (String id : prefs.getString(KEY_CARDS, "").split(",")) {
            if (id.startsWith("app:") && !cardViews.containsKey(id) && !out.contains(id)) out.add(id);
        }
        prefs.edit().putString(KEY_CARDS, TextUtils.join(",", out)).apply();
    }

    private View addCard;

    private void arrangeCards() {
        widgets.removeAllViews();
        for (String id : cardOrder()) {
            View v = cardViews.get(id);
            if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
            widgets.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(cardHeights.get(id) * dp)));
        }
        if (addCard == null) {
            SlantCard add = new SlantCard(act, 0xFF24242C);
            add.setGravity(Gravity.CENTER);
            TextView t = text(15, 0xCCFFFFFF, true);
            t.setText(act.getString(R.string.card_add));
            add.addView(t);
            add.setClickable(true);
            add.setOnClickListener(v -> addCardDialog());
            addCard = add;
        }
        refreshCalendar();
        refreshAppCards();
        widgets.addView(addCard, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(52 * dp)));
        updateCards();
    }

    private void cardMenu(String id) {
        List<String> order = cardOrder();
        int i = order.indexOf(id);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (i > 0) {
            labels.add(act.getString(R.string.card_down));
            actions.add(() -> { order.remove(i); order.add(i - 1, id); saveOrder(order); arrangeCards(); });
        }
        if (i >= 0 && i < order.size() - 1) {
            labels.add(act.getString(R.string.card_up));
            actions.add(() -> { order.remove(i); order.add(i + 1, id); saveOrder(order); arrangeCards(); });
        }
        if (i > 0) {
            labels.add(act.getString(R.string.card_to_corner));
            actions.add(() -> { order.remove(i); order.add(0, id); saveOrder(order); arrangeCards(); });
        }
        labels.add(act.getString(R.string.card_remove));
        actions.add(() -> { order.remove(id); saveOrder(order); arrangeCards(); });
        labels.add(act.getString(R.string.card_add).replace("+", "").trim());
        actions.add(this::addCardDialog);
        act.showDialog(new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(cardName(id))
                .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run()));
    }

    private void addCardDialog() {
        List<String> order = cardOrder();
        List<String> missing = new ArrayList<>();
        for (String id : ALL_CARDS) if (!order.contains(id)) missing.add(id);
        for (String pkg : POPULAR) {
            String id = "app:" + pkg;
            if (!order.contains(id) && act.appFor(pkg) != null) missing.add(id);
        }
        List<String> names = new ArrayList<>();
        for (String id : missing) names.add(cardName(id));
        names.add(act.getString(R.string.card_other_app));
        act.showDialog(new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(act.getString(R.string.card_add))
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    if (w < missing.size()) { addCardId(missing.get(w)); return; }
                    act.pickApp(act.getString(R.string.card_other_app), app -> {
                        if (app != null) addCardId("app:" + app.component.getPackageName());
                    });
                }));
    }

    private void addCardId(String id) {
        List<String> order = cardOrder();
        if (order.contains(id)) return;
        order.add(0, id);     // new widget goes into the corner, where it is seen first
        saveOrder(order);
        arrangeCards();
    }

    // ---- extra cards ------------------------------------------------------

    private TextView weatherBig, weatherSmall, batteryBig, batterySmall, alarmBig, notesText;
    private LinearLayout calendarList;
    private View batteryBar, batteryRest;

    private LinearLayout simpleCard(int color, String caption) {
        int pad = Math.round(14 * dp);
        SlantCard c = new SlantCard(act, color);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(pad, Math.round(10 * dp), pad, Math.round(10 * dp));
        c.setClickable(true);
        TextView t = text(12, 0x99FFFFFF, true);
        t.setText(caption.toUpperCase(Locale.getDefault()));
        t.setLetterSpacing(0.15f);
        c.addView(t);
        return c;
    }

    private void buildExtraCards() {
        LinearLayout weather = simpleCard(0xFF0B5CAD, act.getString(R.string.card_weather));
        weatherBig = text(34, 0xFFFFFFFF, true);
        weatherSmall = text(14, 0xCCFFFFFF, false);
        weather.addView(weatherBig);
        weather.addView(weatherSmall);
        register("weather", weather, 116);

        LinearLayout cal = simpleCard(0xFF6A3FB5, act.getString(R.string.card_calendar));
        calendarList = new LinearLayout(act);
        calendarList.setOrientation(LinearLayout.VERTICAL);
        cal.addView(calendarList);
        cal.setOnClickListener(v -> {
            if (act.checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                act.requestPermissions(new String[]{android.Manifest.permission.READ_CALENDAR}, MainActivity.REQ_CALENDAR);
            } else {
                android.net.Uri.Builder tb = android.provider.CalendarContract.CONTENT_URI.buildUpon().appendPath("time");
                android.content.ContentUris.appendId(tb, System.currentTimeMillis());
                act.startFirst(new Intent(Intent.ACTION_VIEW, tb.build()),
                        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR));
            }
        });
        register("calendar", cal, 150);

        LinearLayout bat = simpleCard(0xFF2E7D32, act.getString(R.string.card_battery));
        batteryBig = text(30, 0xFFFFFFFF, true);
        batterySmall = text(13, 0xCCFFFFFF, false);
        bat.addView(batteryBig);
        LinearLayout track = new LinearLayout(act);
        track.setBackgroundColor(0x33FFFFFF);
        batteryBar = new View(act);
        batteryBar.setBackgroundColor(0xFF4CD964);
        batteryRest = new View(act);
        track.addView(batteryBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 50));
        track.addView(batteryRest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 50));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(6 * dp));
        tl.topMargin = Math.round(4 * dp);
        bat.addView(track, tl);
        bat.addView(batterySmall);
        bat.setOnClickListener(v -> act.startFirst(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)));
        register("battery", bat, 112);

        LinearLayout alarm = simpleCard(0xFFE65100, act.getString(R.string.card_alarm));
        alarmBig = text(30, 0xFFFFFFFF, true);
        alarm.addView(alarmBig);
        alarm.setOnClickListener(v -> act.startFirst(new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        register("alarm", alarm, 96);

        LinearLayout notes = simpleCard(0xFF6D4C41, act.getString(R.string.card_notes));
        notesText = text(15, 0xFFFFFFFF, false);
        notesText.setSingleLine(false);
        notesText.setMaxLines(3);
        notes.addView(notesText);
        notes.setOnClickListener(v -> editNote());
        register("notes", notes, 124);
    }

    private void editNote() {
        android.widget.EditText input = new android.widget.EditText(act);
        input.setText(prefs.getString("note", ""));
        input.setMinLines(3);
        int p = Math.round(20 * dp);
        FrameLayout box = new FrameLayout(act);
        box.setPadding(p, Math.round(8 * dp), p, 0);
        box.addView(input);
        act.showDialog(new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(act.getString(R.string.card_notes))
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    prefs.edit().putString("note", input.getText().toString()).apply();
                    updateCards();
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void updateExtraCards() {
        if (weatherBig == null) return;
        String w = info.weather;
        int dot = w.indexOf(" · ");
        weatherBig.setText(w.isEmpty() ? "—" : (dot > 0 ? w.substring(0, dot) : w));
        weatherSmall.setText(dot > 0 && dot + 3 < w.length() ? w.substring(dot + 3)
                : w.isEmpty() ? act.getString(R.string.weather_loading) : "");

        Intent b = act.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            int status = b.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            int pct = scale > 0 ? Math.max(0, Math.min(100, Math.round(level * 100f / scale))) : 0;
            boolean charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
            batteryBig.setText(pct + "%");
            batterySmall.setText(act.getString(charging ? R.string.battery_charging : R.string.battery_on_battery));
            batteryBar.setBackgroundColor(pct <= 15 ? 0xFFFF453A : 0xFF4CD964);
            LinearLayout.LayoutParams a = (LinearLayout.LayoutParams) batteryBar.getLayoutParams();
            LinearLayout.LayoutParams r = (LinearLayout.LayoutParams) batteryRest.getLayoutParams();
            if (a.weight != pct) {
                a.weight = pct;
                r.weight = 100 - pct;
                batteryBar.setLayoutParams(a);
                batteryRest.setLayoutParams(r);
            }
        }

        try {
            AlarmManager am = act.getSystemService(AlarmManager.class);
            AlarmManager.AlarmClockInfo next = am.getNextAlarmClock();
            alarmBig.setText(next == null ? act.getString(R.string.alarm_none)
                    : new SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(new Date(next.getTriggerTime())));
        } catch (RuntimeException ignored) { }

        String note = prefs.getString("note", "");
        notesText.setText(note.isEmpty() ? act.getString(R.string.notes_hint) : note);
        notesText.setTextColor(note.isEmpty() ? 0x88FFFFFF : 0xFFFFFFFF);
    }

    /** Next events for the calendar card (read on a worker thread). */
    void refreshCalendar() {
        if (calendarList == null || destroyed || shortcutWorker.isShutdown()) return;
        boolean allowed = act.checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!allowed) { showEvents(null); return; }
        shortcutWorker.execute(() -> {
            List<String[]> events = new ArrayList<>();
            long now = System.currentTimeMillis();
            android.net.Uri.Builder ub = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon();
            android.content.ContentUris.appendId(ub, now);
            android.content.ContentUris.appendId(ub, now + 7L * 24 * 3600 * 1000);
            String[] proj = {android.provider.CalendarContract.Instances.TITLE,
                    android.provider.CalendarContract.Instances.BEGIN,
                    android.provider.CalendarContract.Instances.ALL_DAY};
            try (android.database.Cursor c = act.getContentResolver().query(ub.build(), proj,
                    android.provider.CalendarContract.Instances.VISIBLE + "=1", null,
                    android.provider.CalendarContract.Instances.BEGIN + " ASC")) {
                while (c != null && c.moveToNext() && events.size() < 3) {
                    String title = c.getString(0);
                    long begin = c.getLong(1);
                    boolean allDay = c.getInt(2) == 1;
                    SimpleDateFormat f = new SimpleDateFormat(allDay ? "EEE d MMM" : "EEE HH:mm", Locale.getDefault());
                    if (allDay) f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                    String when = f.format(new Date(begin));
                    events.add(new String[]{when, title == null ? "" : title});
                }
            } catch (RuntimeException ignored) { }
            act.runOnUiThread(() -> { if (!destroyed) showEvents(events); });
        });
    }

    private void showEvents(List<String[]> events) {
        calendarList.removeAllViews();
        if (events == null) {
            TextView t = text(14, 0xAAFFFFFF, false);
            t.setText(act.getString(R.string.calendar_allow));
            calendarList.addView(t);
            return;
        }
        if (events.isEmpty()) {
            TextView t = text(14, 0xAAFFFFFF, false);
            t.setText(act.getString(R.string.calendar_empty));
            calendarList.addView(t);
            return;
        }
        for (String[] e : events) {
            TextView t = text(15, 0xFFFFFFFF, false);
            t.setText(e[0] + "   " + e[1]);
            t.setPadding(0, Math.round(4 * dp), 0, 0);
            calendarList.addView(t);
        }
    }

    // ---- app widgets (Instagram, Viber, WhatsApp, Discord, …) -------------

    /** Popular apps offered first in "Add widget" when installed. */
    private static final String[] POPULAR = {"com.instagram.android", "com.viber.voip", "com.whatsapp",
            "com.discord", "org.telegram.messenger", "com.spotify.music", "com.google.android.youtube",
            "com.zhiliaoapp.musically", "com.google.android.apps.maps", "com.google.android.gm",
            "com.android.chrome", "com.ftband.mono", "ua.gov.diia.app", "com.google.android.apps.photos"};

    private final java.util.Map<String, LinearLayout> appChipRows = new java.util.HashMap<>();

    /** Builds the card for "app:<package>"; false when that app isn't installed. */
    private boolean buildAppCard(String id) {
        String pkg = id.substring(4);
        AppEntry app = act.appFor(pkg);
        if (app == null) return false;
        int pad = Math.round(14 * dp);
        SlantCard card = new SlantCard(act, app.color);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, Math.round(10 * dp), pad, Math.round(10 * dp));
        card.setClickable(true);
        card.setOnClickListener(v -> { closeAll(); act.launch(app); });

        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        AppTileView tile = new AppTileView(act, app);
        int th = Math.round(30 * dp);
        head.addView(tile, new LinearLayout.LayoutParams(Math.round(th * 1.5f), th));
        TextView name = text(17, app.textColor, true);
        name.setText(app.label);
        name.setPadding(Math.round(10 * dp), 0, 0, 0);
        head.addView(name);
        card.addView(head);

        LinearLayout chips = new LinearLayout(act);
        chips.setPadding(0, Math.round(10 * dp), 0, 0);
        android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(act);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chips);
        card.addView(chipScroll);
        appChipRows.put(pkg, chips);
        appCardEntries.put(pkg, app);
        appCardColors.put(pkg, app.color);
        register(id, card, 118);
        return true;
    }

    /** Fills every app card with that app's own quick actions / recent chats (worker thread). */
    private void refreshAppCards() {
        if (appChipRows.isEmpty() || destroyed || shortcutWorker.isShutdown()) return;
        final android.content.pm.LauncherApps la = act.getSystemService(android.content.pm.LauncherApps.class);
        final int density = act.getResources().getDisplayMetrics().densityDpi;
        final int iconPx = Math.round(26 * dp);
        final List<String> pkgs = new ArrayList<>(appChipRows.keySet());
        shortcutWorker.execute(() -> {
            java.util.Map<String, List<Sc>> result = new java.util.HashMap<>();
            boolean allowed;
            try { allowed = la.hasShortcutHostPermission(); } catch (RuntimeException e) { allowed = false; }
            for (String pkg : pkgs) {
                List<Sc> list = new ArrayList<>();
                if (allowed) {
                    try {
                        android.content.pm.LauncherApps.ShortcutQuery q = new android.content.pm.LauncherApps.ShortcutQuery();
                        q.setPackage(pkg);
                        q.setQueryFlags(android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                                | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                                | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
                        List<android.content.pm.ShortcutInfo> got = la.getShortcuts(q, Process.myUserHandle());
                        if (got != null) {
                            List<android.content.pm.ShortcutInfo> s = new ArrayList<>(got);
                            s.sort((a, b) -> a.isDeclaredInManifest() == b.isDeclaredInManifest()
                                    ? Integer.compare(a.getRank(), b.getRank())
                                    : (a.isDeclaredInManifest() ? -1 : 1));
                            for (android.content.pm.ShortcutInfo si : s) {
                                if (!si.isEnabled() || list.size() >= 10) continue;
                                list.add(load(la, si, density, iconPx));
                            }
                        }
                    } catch (RuntimeException ignored) { }
                }
                result.put(pkg, list);
            }
            act.runOnUiThread(() -> {
                if (destroyed || act.isDestroyed()) return;
                for (java.util.Map.Entry<String, List<Sc>> e : result.entrySet()) fillAppChips(e.getKey(), e.getValue());
            });
        });
    }

    private void fillAppChips(String pkg, List<Sc> list) {
        LinearLayout row = appChipRows.get(pkg);
        AppEntry app = act.appFor(pkg);
        if (row == null || app == null) return;
        row.removeAllViews();
        if (list.isEmpty()) {
            TextView t = text(13, app.textColor, false);
            t.setAlpha(0.7f);
            t.setText(act.getString(R.string.app_card_open));
            row.addView(t);
            return;
        }
        for (Sc s : list) {
            LinearLayout chip = new LinearLayout(act);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            int hp = Math.round(10 * dp);
            chip.setPadding(hp, 0, hp, 0);
            GradientDrawable cb = new GradientDrawable();
            cb.setCornerRadius(16 * dp);
            cb.setColor(ColorExtractor.textColorFor(app.color) == 0xFFFFFFFF ? 0x33000000 : 0x22FFFFFF);
            chip.setBackground(cb);
            if (s.icon != null) {
                ImageView iv = new ImageView(act);
                iv.setImageBitmap(s.icon);
                int is = Math.round(22 * dp);
                chip.addView(iv, new LinearLayout.LayoutParams(is, is));
            }
            TextView t = text(13, app.textColor, true);
            t.setText(s.label);
            t.setMaxWidth(Math.round(110 * dp));
            t.setPadding(Math.round(6 * dp), 0, 0, 0);
            chip.addView(t);
            chip.setOnClickListener(v -> startShortcut(s.info));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(36 * dp));
            lp.rightMargin = Math.round(6 * dp);
            row.addView(chip, lp);
        }
    }

    private TextView control(String label, View.OnClickListener l) {
        TextView t = text(22, 0xFFFFFFFF, true);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(l);
        musicButtons.add(t);
        int s = Math.round(44 * dp);
        t.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return t;
    }

    void updateCards() {
        if (musicTitle == null) return;
        updateExtraCards();
        InfoHub.Media m = info.media;
        if (!info.hasNotificationAccess()) {
            musicTitle.setText(act.getString(R.string.music));
            musicArtist.setText(act.getString(R.string.music_need_access));
            musicArt.setImageBitmap(null);
            musicPlay.setText("▶");
        } else if (m == null) {
            musicTitle.setText(act.getString(R.string.music_nothing));
            musicArtist.setText("");
            musicArt.setImageBitmap(null);
            musicPlay.setText("▶");
        } else {
            musicTitle.setText(m.title);
            musicArtist.setText(m.artist);
            musicArt.setImageBitmap(m.art);
            musicPlay.setText(m.playing ? "⏸" : "▶");
        }
        if (musicCard != null) {
            int col = m != null && m.art != null ? act.artColor() : 0xFF26262E;
            musicCard.setColor(col);
            int ink = ColorExtractor.textColorFor(col);
            musicTitle.setTextColor(ink);
            musicArtist.setTextColor((ink & 0x00FFFFFF) | 0xBB000000);
            for (TextView b : musicButtons) b.setTextColor(ink);
        }
    }

    // ---- Claude ---------------------------------------------------------

    private void askClaude() {
        android.widget.EditText input = new android.widget.EditText(act);
        input.setHint(act.getString(R.string.claude_question));
        input.setMinLines(2);
        int p = Math.round(20 * dp);
        FrameLayout box = new FrameLayout(act);
        box.setPadding(p, Math.round(8 * dp), p, 0);
        box.addView(input);
        AlertDialog d = new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Claude")
                .setView(box)
                .setPositiveButton(act.getString(R.string.send), (dlg, w) -> sendToClaude(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        d.setOnShowListener(x -> {
            input.requestFocus();
            if (d.getWindow() != null) {
                d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            }
        });
        act.showDialog(d);
    }

    /** Opens the Claude app with the question (share), or claude.ai if the app isn't installed. */
    private void sendToClaude(String q) {
        closeAll();
        if (q.isEmpty()) {
            Intent open = act.getPackageManager().getLaunchIntentForPackage(CLAUDE_PKG);
            act.startFirst(open != null ? open : new Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/new")));
            return;
        }
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, q).setPackage(CLAUDE_PKG);
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/new?q=" + Uri.encode(q)));
        act.startFirst(share, web);
    }

    // ---- shortcuts: chats + Claude chips ---------------------------------

    private volatile boolean perPackageTried, usePerPackage;

    private static final class Sc {
        android.content.pm.ShortcutInfo info;
        android.graphics.Bitmap icon;
        String label;
    }

    void refreshShortcuts() {
        final android.content.pm.LauncherApps la = act.getSystemService(android.content.pm.LauncherApps.class);
        final int density = act.getResources().getDisplayMetrics().densityDpi;
        final int iconPx = Math.round(46 * dp);
        if (destroyed || shortcutWorker.isShutdown()) return;
        shortcutWorker.execute(() -> {
            List<Sc> chats = new ArrayList<>();
            List<Sc> claude = new ArrayList<>();
            boolean allowed;
            try { allowed = la.hasShortcutHostPermission(); } catch (RuntimeException e) { allowed = false; }
            if (allowed) {
                List<android.content.pm.ShortcutInfo> all = new ArrayList<>();
                try {
                    android.content.pm.LauncherApps.ShortcutQuery q = new android.content.pm.LauncherApps.ShortcutQuery();
                    int flags = android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                            | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                            | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST;
                    if (Build.VERSION.SDK_INT >= 30) flags |= android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED;
                    q.setQueryFlags(flags);
                    List<android.content.pm.ShortcutInfo> got = la.getShortcuts(q, Process.myUserHandle());
                    if (got != null) all.addAll(got);
                    if (all.isEmpty() && (usePerPackage || !perPackageTried)) {
                        perPackageTried = true;
                        // some Android builds want one package per query
                        for (android.content.pm.LauncherActivityInfo ai : la.getActivityList(null, Process.myUserHandle())) {
                            q.setPackage(ai.getComponentName().getPackageName());
                            try {
                                List<android.content.pm.ShortcutInfo> one = la.getShortcuts(q, Process.myUserHandle());
                                if (one != null) all.addAll(one);
                            } catch (RuntimeException ignored) { }
                        }
                        if (!all.isEmpty()) usePerPackage = true;
                    }
                } catch (RuntimeException ignored) { }
                all.sort((a, b) -> Long.compare(b.getLastChangedTimestamp(), a.getLastChangedTimestamp()));
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (android.content.pm.ShortcutInfo si : all) {
                    if (!si.isEnabled()) continue;
                    String pkg = si.getPackage();
                    if (pkg.equals(act.getPackageName())) continue;
                    if (CLAUDE_PKG.equals(pkg)) {
                        if (claude.size() < 3 && seen.add(si.getId() + pkg)) claude.add(load(la, si, density, iconPx));
                        continue;
                    }
                    if (chats.size() >= 15 || !isConversation(si)) continue;
                    if (!seen.add(pkg + "/" + si.getShortLabel())) continue;
                    chats.add(load(la, si, density, iconPx));
                }
            }
            final boolean ok = allowed;
            act.runOnUiThread(() -> { if (!destroyed && !act.isDestroyed()) showShortcuts(chats, claude, ok); });
        });
    }

    private static boolean isConversation(android.content.pm.ShortcutInfo si) {
        String pkg = si.getPackage().toLowerCase(Locale.ROOT);
        boolean messenger = pkg.contains("telegram") || pkg.contains("ayugram") || pkg.contains("nova")
                || pkg.contains("whatsapp") || pkg.contains("viber") || pkg.contains("signal")
                || pkg.contains("orca") || pkg.contains("discord")
                || pkg.contains("messag") || pkg.contains("chat");
        if (messenger && (si.isDynamic() || si.isPinned())) return true;
        java.util.Set<String> cats = si.getCategories();
        if (cats == null) return false;
        for (String c : cats) {
            if (c.contains("conversation") || c.contains("SHARE_TARGET") || c.contains("share")) return true;
        }
        return false;
    }

    private Sc load(android.content.pm.LauncherApps la, android.content.pm.ShortcutInfo si, int density, int px) {
        Sc s = new Sc();
        s.info = si;
        CharSequence l = si.getShortLabel();
        s.label = l == null ? "" : l.toString();
        try {
            android.graphics.drawable.Drawable d = la.getShortcutIconDrawable(si, density);
            if (d == null) d = la.getShortcutBadgedIconDrawable(si, density);
            if (d != null) {
                android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(b);
                android.graphics.Path clip = new android.graphics.Path();
                clip.addCircle(px / 2f, px / 2f, px / 2f, android.graphics.Path.Direction.CW);
                c.clipPath(clip);   // round avatars
                d.setBounds(0, 0, px, px);
                d.draw(c);
                s.icon = b;
            }
        } catch (RuntimeException ignored) { }
        return s;
    }

    private void showShortcuts(List<Sc> chats, List<Sc> claude, boolean allowed) {
        chatRow.removeAllViews();
        if (!allowed) chatHint.setText(act.getString(R.string.chats_need_home));
        else if (chats.isEmpty()) chatHint.setText(act.getString(R.string.chats_empty));
        else chatHint.setText("");
        chatHint.setVisibility(chatHint.getText().length() == 0 ? View.GONE : View.VISIBLE);
        int cell = Math.round(62 * dp);
        for (Sc s : chats) {
            LinearLayout item = new LinearLayout(act);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView iv = new ImageView(act);
            iv.setImageBitmap(s.icon);
            int a = Math.round(46 * dp);
            item.addView(iv, new LinearLayout.LayoutParams(a, a));
            TextView name = text(11, 0xDDFFFFFF, false);
            name.setText(s.label);
            name.setGravity(Gravity.CENTER);
            item.addView(name, new LinearLayout.LayoutParams(cell, ViewGroup.LayoutParams.WRAP_CONTENT));
            item.setOnClickListener(v -> startShortcut(s.info));
            chatRow.addView(item, new LinearLayout.LayoutParams(cell, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        claudeChips.removeAllViews();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (int k = 0; k < claude.size(); k++) {
            Sc s = claude.get(k);
            labels.add(s.label);
            actions.add(() -> startShortcut(s.info));
        }
        if (claude.isEmpty()) {
            labels.add(act.getString(R.string.claude_open));
            actions.add(() -> sendToClaude(""));
        }
        for (int i = 0; i < labels.size(); i++) {
            TextView chip = text(13, 0xFFFFFFFF, true);
            chip.setText(labels.get(i));
            chip.setGravity(Gravity.CENTER);
            int hp = Math.round(12 * dp);
            chip.setPadding(hp, 0, hp, 0);
            chip.setMaxWidth(Math.round(140 * dp));
            GradientDrawable cb = new GradientDrawable();
            cb.setCornerRadius(14 * dp);
            cb.setColor(0x33000000);
            chip.setBackground(cb);
            final Runnable r = actions.get(i);
            chip.setOnClickListener(v -> r.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(32 * dp));
            lp.rightMargin = Math.round(8 * dp);
            claudeChips.addView(chip, lp);
        }
    }

    private void startShortcut(android.content.pm.ShortcutInfo si) {
        closeAll();
        try {
            act.getSystemService(android.content.pm.LauncherApps.class)
                    .startShortcut(si.getPackage(), si.getId(), null, null, si.getUserHandle());
        } catch (RuntimeException e) {
            Intent open = act.getPackageManager().getLaunchIntentForPackage(si.getPackage());
            if (open != null) act.startFirst(open);
        }
    }
}
