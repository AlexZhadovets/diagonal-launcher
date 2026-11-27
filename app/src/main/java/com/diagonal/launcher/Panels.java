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

    static final int REQ_BIND = 41, REQ_CONFIG = 42;
    private static final int HOST_ID = 7301;
    private static final String KEY_WIDGETS = "widgets";

    // Quick-settings tile ids (also their order: the first 4 are the small triangle).
    private static final int T_BT = 0, T_NET = 1, T_WALLET = 2, T_TORCH = 3, T_WIFI = 4, T_SOUND = 5,
            T_DND = 6, T_ROTATE = 7, T_NFC = 8, T_HOTSPOT = 9, T_LOCATION = 10, T_ALARM = 11,
            T_PLANE = 12, T_CAMERA = 13, T_SHADE = 14, T_SETTINGS = 15;

    private final MainActivity act;
    private final InfoHub info;
    private final SharedPreferences prefs;
    private final float dp;
    final QuickPanel quick;
    final WidgetPanel widgets;
    private final LinearLayout widgetList;
    private final AppWidgetHost host;
    private final AppWidgetManager awm;
    private final List<QuickPanel.Tile> tiles = new ArrayList<>();
    private final GestureDetector outside;
    private boolean downInside;
    boolean expectingResult;

    // built-in cards
    private TextView musicTitle, musicArtist, musicPlay, weatherBig, weatherSmall;
    private ImageView musicArt;
    private View musicCard;

    private boolean torchOn;
    private String torchId;
    private int pendingWidgetId = -1;
    private AppWidgetProviderInfo pendingInfo;

    Panels(MainActivity act, FrameLayout root, InfoHub info, SharedPreferences prefs) {
        this.act = act;
        this.info = info;
        this.prefs = prefs;
        dp = act.getResources().getDisplayMetrics().density;
        awm = AppWidgetManager.getInstance(act);
        host = new AppWidgetHost(act.getApplicationContext(), HOST_ID);

        // ---- widget window -------------------------------------------------
        widgets = new WidgetPanel(act);
        ScrollView scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        widgetList = new LinearLayout(act);
        widgetList.setOrientation(LinearLayout.VERTICAL);
        widgetList.setGravity(Gravity.BOTTOM);
        // tap on empty space in the widget window = step back one level (full screen → peek → closed)
        widgetList.setOnClickListener(v -> widgets.setLevel(widgets.level() - 1));
        int pad = Math.round(12 * dp);
        widgetList.setPadding(pad, pad, pad, pad);
        scroll.addView(widgetList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        widgets.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null) return false;
                float tx = e2.getX() - e1.getX(), ty = e2.getY() - e1.getY();
                float along = (tx - ty) * 0.7071f, across = (tx + ty) * 0.7071f;
                if (Math.abs(along) < 56 * dp || Math.abs(along) < Math.abs(across) * 1.3f) return false;
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

    void onDestroy() {
        try { act.getSystemService(CameraManager.class).unregisterTorchCallback(torchCallback); }
        catch (RuntimeException ignored) { }
    }

    void setInsets(int top, int bottom) {
        quick.setTopInset(top);
        widgetList.setPadding(widgetList.getPaddingLeft(), top + Math.round(12 * dp),
                widgetList.getPaddingRight(), bottom + Math.round(12 * dp));
    }

    void onStart() {
        try { host.startListening(); } catch (RuntimeException ignored) { }
        rebuildWidgets();
    }

    void onStop() {
        try { host.stopListening(); } catch (RuntimeException ignored) { }
    }

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
            widgets.setLevel(Math.min(2, widgets.level() + 1));
        }
    }

    /** While a panel is open: outside taps close it, along-stripe swipes step it. */
    boolean dispatch(MotionEvent e) {
        if (!isOpen()) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downInside = quick.contains(e.getX(), e.getY()) || widgets.contains(e.getX(), e.getY());
        }
        boolean swiped = outside.onTouchEvent(e);
        if (swiped && downInside) {
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            act.superDispatch(cancel);   // let the panel's own views finish their gesture
            cancel.recycle();
            return true;
        }
        return !downInside || swiped; // outside touches never reach the stripes underneath
    }

    // ---- quick settings -------------------------------------------------

    private void buildTiles() {
        String[] names = {"Bluetooth", "Інтернет", "Гаманець", "Ліхтарик", "Wi-Fi", "Звук",
                "Не турбувати", "Автоповорот", "NFC", "Точка доступу", "Геолокація", "Будильник",
                "Політ", "Камера", "Шторка", "Діагональ"};
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

    // ---- widget window --------------------------------------------------

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

    private LinearLayout.LayoutParams cardLp(int heightPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx);
        lp.topMargin = Math.round(10 * dp);
        return lp;
    }

    /** [ + add widget ] [ hosted widgets… ] [ weather ] [ music ]  (music sits in the corner). */
    void rebuildWidgets() {
        widgetList.removeAllViews();
        int pad = Math.round(16 * dp);

        TextView add = text(15, 0xCCFFFFFF, true);
        add.setText("+  Додати віджет");
        add.setGravity(Gravity.CENTER);
        add.setBackground(cardBg(0xFF1E1E25));
        add.setOnClickListener(v -> pickWidget());
        widgetList.addView(add, cardLp(Math.round(52 * dp)));

        for (int id : savedWidgetIds()) {
            AppWidgetProviderInfo wi = awm.getAppWidgetInfo(id);
            if (wi == null) continue;
            FrameLayout card = new FrameLayout(act);
            card.setBackground(cardBg(0xFF1E1E25));
            card.setClipToOutline(true);
            AppWidgetHostView hv = host.createView(act.getApplicationContext(), id, wi);
            card.addView(hv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            TextView remove = text(14, 0x99FFFFFF, true);
            remove.setText("×");
            remove.setGravity(Gravity.CENTER);
            int rs = Math.round(28 * dp);
            remove.setOnClickListener(v -> removeWidget(id));
            card.addView(remove, new FrameLayout.LayoutParams(rs, rs, Gravity.TOP | Gravity.END));
            int h = Math.max(wi.minHeight, Math.round(120 * dp));
            int wDp = Math.round((act.getResources().getDisplayMetrics().widthPixels - 24 * dp) / dp);
            int hDp = Math.round(h / dp);
            try {
                Bundle opts = new Bundle();
                opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp);
                opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp);
                opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp);
                opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp);
                awm.updateAppWidgetOptions(id, opts);
            } catch (RuntimeException ignored) { }
            widgetList.addView(card, cardLp(h));
        }

        LinearLayout weather = new LinearLayout(act);
        weather.setOrientation(LinearLayout.VERTICAL);
        weather.setGravity(Gravity.CENTER_VERTICAL);
        weather.setPadding(pad, pad, pad, pad);
        weather.setBackground(cardBg(0xFF1B2A4A));
        weatherBig = text(40, 0xFFFFFFFF, true);
        weatherSmall = text(15, 0xCCFFFFFF, false);
        weather.addView(weatherBig);
        weather.addView(weatherSmall);
        weather.setOnClickListener(v -> act.startFirst(new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        widgetList.addView(weather, cardLp(Math.round(130 * dp)));

        LinearLayout music = new LinearLayout(act);
        music.setOrientation(LinearLayout.HORIZONTAL);
        music.setGravity(Gravity.CENTER_VERTICAL);
        music.setPadding(pad, pad, pad, pad);
        music.setBackground(cardBg(0xFF26262E));
        musicArt = new ImageView(act);
        musicArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        musicArt.setClipToOutline(true);
        musicArt.setBackground(cardBg(0xFF33333C));
        int art = Math.round(72 * dp);
        music.addView(musicArt, new LinearLayout.LayoutParams(art, art));
        LinearLayout texts = new LinearLayout(act);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(pad, 0, pad / 2, 0);
        musicTitle = text(18, 0xFFFFFFFF, true);
        musicArtist = text(14, 0xBBFFFFFF, false);
        texts.addView(musicTitle);
        texts.addView(musicArtist);
        LinearLayout buttons = new LinearLayout(act);
        buttons.setPadding(0, Math.round(6 * dp), 0, 0);
        TextView prev = control("⏮", v -> { info.previous(); });
        musicPlay = control("▶", v -> {
            if (!info.hasNotificationAccess()) act.openNotificationAccess();
            else info.playPause();
        });
        TextView next = control("⏭", v -> { info.next(); });
        buttons.addView(prev);
        buttons.addView(musicPlay);
        buttons.addView(next);
        texts.addView(buttons);
        music.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        musicCard = music;
        widgetList.addView(music, cardLp(Math.round(140 * dp)));

        updateCards();
    }

    private TextView control(String label, View.OnClickListener l) {
        TextView t = text(22, 0xFFFFFFFF, true);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(l);
        int s = Math.round(48 * dp);
        t.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return t;
    }

    void updateCards() {
        if (weatherBig == null) return;
        weatherBig.setText(info.time());
        String w = info.weather.isEmpty() ? info.date() : info.date() + "  ·  " + info.weather;
        weatherSmall.setText(w);

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
        if (musicCard != null) {
            musicCard.setOnClickListener(v -> {
                InfoHub.Media mm = info.media;
                if (mm != null && mm.controller != null && mm.controller.getSessionActivity() != null) {
                    try { InfoHub.sendAllowingStart(act, mm.controller.getSessionActivity()); } catch (Exception ignored) { }
                }
            });
        }
    }

    private List<Integer> savedWidgetIds() {
        List<Integer> out = new ArrayList<>();
        for (String s : prefs.getString(KEY_WIDGETS, "").split(",")) {
            try { if (!s.isEmpty()) out.add(Integer.parseInt(s)); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private void saveWidgetIds(List<Integer> ids) {
        prefs.edit().putString(KEY_WIDGETS, TextUtils.join(",", ids)).apply();
    }

    private void removeWidget(int id) {
        List<Integer> ids = savedWidgetIds();
        ids.remove(Integer.valueOf(id));
        saveWidgetIds(ids);
        try { host.deleteAppWidgetId(id); } catch (RuntimeException ignored) { }
        rebuildWidgets();
    }

    private void pickWidget() {
        List<AppWidgetProviderInfo> list;
        try {
            list = new ArrayList<>(awm.getInstalledProvidersForProfile(Process.myUserHandle()));
        } catch (RuntimeException e) {
            return;
        }
        Collator collator = Collator.getInstance();
        java.util.Map<String, String> appNames = new java.util.HashMap<>();
        for (AppWidgetProviderInfo wi : list) {
            String pkg = wi.provider.getPackageName();
            if (!appNames.containsKey(pkg)) appNames.put(pkg, appLabel(wi));
        }
        list.sort((a, b) -> collator.compare(appNames.get(a.provider.getPackageName()),
                appNames.get(b.provider.getPackageName())));
        List<String> labels = new ArrayList<>();
        List<AppWidgetProviderInfo> infos = new ArrayList<>();
        for (AppWidgetProviderInfo wi : list) {
            String widgetName = wi.loadLabel(act.getPackageManager());
            labels.add(appNames.get(wi.provider.getPackageName()) + " — " + widgetName);
            infos.add(wi);
        }
        act.showDialog(new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Віджет")
                .setItems(labels.toArray(new String[0]), (d, which) -> addWidget(infos.get(which))));
    }

    private String appLabel(AppWidgetProviderInfo wi) {
        try {
            return String.valueOf(act.getPackageManager().getApplicationLabel(
                    act.getPackageManager().getApplicationInfo(wi.provider.getPackageName(), 0)));
        } catch (Exception e) {
            return wi.provider.getPackageName();
        }
    }

    private void addWidget(AppWidgetProviderInfo wi) {
        int id = host.allocateAppWidgetId();
        if (awm.bindAppWidgetIdIfAllowed(id, wi.provider)) {
            configureOrAdd(id, wi);
        } else {
            pendingWidgetId = id;
            pendingInfo = wi;
            expectingResult = true;
            Intent bind = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, wi.provider);
            try {
                act.startActivityForResult(bind, REQ_BIND);
            } catch (RuntimeException e) {
                expectingResult = false;
                host.deleteAppWidgetId(id);
            }
        }
    }

    private void configureOrAdd(int id, AppWidgetProviderInfo wi) {
        if (wi.configure != null) {
            pendingWidgetId = id;
            pendingInfo = wi;
            expectingResult = true;
            try {
                host.startAppWidgetConfigureActivityForResult(act, id, 0, REQ_CONFIG, null);
                return;
            } catch (RuntimeException e) {
                expectingResult = false;  // configuration screen unavailable: add as is
            }
        }
        List<Integer> ids = savedWidgetIds();
        ids.add(id);
        saveWidgetIds(ids);
        rebuildWidgets();
        widgets.setLevel(2);
    }

    void onActivityResult(int request, int result, Intent data) {
        expectingResult = false;
        int id = pendingWidgetId;
        AppWidgetProviderInfo wi = pendingInfo;
        pendingWidgetId = -1;
        pendingInfo = null;
        if (id < 0) return;
        if (result != android.app.Activity.RESULT_OK) {
            try { host.deleteAppWidgetId(id); } catch (RuntimeException ignored) { }
            return;
        }
        if (request == REQ_BIND && wi != null) {
            configureOrAdd(id, wi);
        } else if (request == REQ_CONFIG) {
            List<Integer> ids = savedWidgetIds();
            ids.add(id);
            saveWidgetIds(ids);
            rebuildWidgets();
            widgets.setLevel(2);
        }
    }

    static Bundle none() { return new Bundle(); }
}
