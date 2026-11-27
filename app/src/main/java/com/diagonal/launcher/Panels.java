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
    private boolean flung;   // the current gesture was an along-stripe fling (steps a panel)

    // own widgets
    private TextView musicTitle, musicArtist, musicPlay;
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
        widgets.setOnEmptyTap(() -> widgets.setLevel(widgets.level() - 1));
        buildWidgetCards();
        root.addView(widgets, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- quick settings triangle ---------------------------------------
        quick = new QuickPanel(act);
        quick.setListener(this::onTile);
        root.addView(quick, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildTiles();

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
    void onCornerSwipe(int dir) {
        if (dir < 0) {
            if (widgets.level() > 0) { widgets.setLevel(widgets.level() - 1); return; }
            refreshTiles();
            quick.setLevel(Math.min(2, quick.level() + 1));
        } else {
            if (quick.level() > 0) { quick.setLevel(quick.level() - 1); return; }
            if (widgets.level() == 0) refreshShortcuts();
            widgets.setLevel(Math.min(2, widgets.level() + 1));
        }
    }

    /** While a panel is open: outside taps close it, along-stripe flings step it. */
    boolean dispatch(MotionEvent e) {
        if (!isOpen()) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downInside = quick.contains(e.getX(), e.getY()) || widgets.contains(e.getX(), e.getY());
            flung = false;
        }
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

    // ---- quick settings -------------------------------------------------

    private void buildTiles() {
        String[] names = {"Bluetooth", "Інтернет", "Гаманець", "Ліхтарик", "Wi-Fi", "Звук",
                "Не турбувати", "Автоповорот", "NFC", "Точка доступу", "Геолокація", "Будильник",
                "Політ", "Камера", "Шторка", "Diagonal"};
        int[] icons = {Icons.BLUETOOTH, Icons.INTERNET, Icons.WALLET, Icons.TORCH, Icons.WIFI, Icons.SOUND,
                Icons.DND, Icons.ROTATE, Icons.NFC, Icons.HOTSPOT, Icons.LOCATION, Icons.ALARM,
                Icons.AIRPLANE, Icons.CAMERA, Icons.SHADE, Icons.SETTINGS};
        for (int i = 0; i < names.length; i++) tiles.add(new QuickPanel.Tile(i, icons[i], names[i]));
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
                        t.label = next == null ? "Будильник"
                                : new SimpleDateFormat("EE HH:mm", new Locale("uk")).format(new Date(next.getTriggerTime()));
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
        LinearLayout music = new LinearLayout(act);
        music.setOrientation(LinearLayout.HORIZONTAL);
        music.setGravity(Gravity.CENTER_VERTICAL);
        music.setPadding(pad, pad, pad, pad);
        music.setBackground(cardBg(0xFF26262E));
        musicArt = new ImageView(act);
        musicArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        musicArt.setClipToOutline(true);
        musicArt.setBackground(cardBg(0xFF33333C));
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
        widgets.addView(music, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(128 * dp)));

        // Chats: recent conversations (shortcuts published by messengers)
        LinearLayout chats = new LinearLayout(act);
        chats.setOrientation(LinearLayout.VERTICAL);
        chats.setPadding(pad, Math.round(10 * dp), pad, Math.round(10 * dp));
        chats.setBackground(cardBg(0xFF1E2433));
        chats.setClickable(true);
        TextView chatsTitle = text(12, 0x99FFFFFF, true);
        chatsTitle.setText("ЧАТИ");
        chatsTitle.setLetterSpacing(0.15f);
        chats.addView(chatsTitle);
        chatRow = new LinearLayout(act);
        chatRow.setOrientation(LinearLayout.HORIZONTAL);
        chatRow.setPadding(0, Math.round(6 * dp), 0, 0);
        chatHint = text(13, 0xAAFFFFFF, false);
        chats.addView(chatRow);
        chats.addView(chatHint);
        widgets.addView(chats, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(124 * dp)));

        // Claude: ask box + Claude's own shortcuts
        LinearLayout claude = new LinearLayout(act);
        claude.setOrientation(LinearLayout.VERTICAL);
        claude.setPadding(pad, Math.round(10 * dp), pad, Math.round(10 * dp));
        claude.setBackground(cardBg(0xFFC96442));
        claude.setClickable(true);
        TextView claudeTitle = text(12, 0xCCFFFFFF, true);
        claudeTitle.setText("CLAUDE");
        claudeTitle.setLetterSpacing(0.15f);
        claude.addView(claudeTitle);
        TextView ask = text(16, 0xFF5A2A1A, true);
        ask.setText("Запитай Claude…");
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
        claude.addView(claudeChips);
        widgets.addView(claude, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(140 * dp)));

        updateCards();
    }

    private TextView control(String label, View.OnClickListener l) {
        TextView t = text(22, 0xFFFFFFFF, true);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(l);
        int s = Math.round(44 * dp);
        t.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return t;
    }

    void updateCards() {
        if (musicTitle == null) return;
        InfoHub.Media m = info.media;
        if (!info.hasNotificationAccess()) {
            musicTitle.setText("Музика");
            musicArtist.setText("Дозволь доступ до сповіщень");
            musicArt.setImageBitmap(null);
            musicPlay.setText("▶");
        } else if (m == null) {
            musicTitle.setText("Нічого не грає");
            musicArtist.setText("");
            musicArt.setImageBitmap(null);
            musicPlay.setText("▶");
        } else {
            musicTitle.setText(m.title);
            musicArtist.setText(m.artist);
            musicArt.setImageBitmap(m.art);
            musicPlay.setText(m.playing ? "⏸" : "▶");
        }
    }

    // ---- Claude ---------------------------------------------------------

    private void askClaude() {
        android.widget.EditText input = new android.widget.EditText(act);
        input.setHint("Твоє питання");
        input.setMinLines(2);
        int p = Math.round(20 * dp);
        FrameLayout box = new FrameLayout(act);
        box.setPadding(p, Math.round(8 * dp), p, 0);
        box.addView(input);
        AlertDialog d = new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Claude")
                .setView(box)
                .setPositiveButton("Надіслати", (dlg, w) -> sendToClaude(input.getText().toString().trim()))
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
                    if (chats.size() >= 5 || !isConversation(si)) continue;
                    if (!seen.add(pkg + "/" + si.getShortLabel())) continue;
                    chats.add(load(la, si, density, iconPx));
                }
            }
            final boolean ok = allowed;
            act.runOnUiThread(() -> { if (!destroyed && !act.isDestroyed()) showShortcuts(chats, claude, ok); });
        });
    }

    private static boolean isConversation(android.content.pm.ShortcutInfo si) {
        if (Build.VERSION.SDK_INT >= 30 && si.isLongLived()) return true;
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
        if (!allowed) chatHint.setText("Зроби Diagonal головним екраном, щоб бачити чати");
        else if (chats.isEmpty()) chatHint.setText("Месенджери ще не показали останні чати");
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
        for (int k = 0; k < Math.min(2, claude.size()); k++) {
            Sc s = claude.get(k);
            labels.add(s.label);
            actions.add(() -> startShortcut(s.info));
        }
        if (claude.isEmpty()) {
            labels.add("Відкрити Claude");
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
