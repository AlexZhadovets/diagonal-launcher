package com.diagonal.launcher;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Everything the centre stripe and the widget window show: clock, weather,
 * the newest notification and the music that is playing.
 */
final class InfoHub {

    interface Listener { void onInfoChanged(); }

    static final class Media {
        String title, artist, pkg;
        Bitmap art;
        boolean playing;
        MediaController controller;
    }

    static final class Notif {
        String pkg, title, text, key;
        PendingIntent intent;
        boolean autoCancel;
    }

    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000L;
    private static final long SHOW_PAUSED_MS = 5 * 60 * 1000L;
    /** A new notification shows on the centre stripe only for this long after it arrives. */
    static final long NOTIF_SHOW_MS = 8000L;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ComponentName listenerCn;
    private Listener listener;

    Media media;
    Notif notif;
    String weather = "";       // e.g. "3° · Хмарно"
    private volatile long weatherAt;
    private long lastPlayingAt;
    private final Map<MediaController, MediaController.Callback> callbacks = new HashMap<>();
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private boolean started;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };

    InfoHub(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        listenerCn = new ComponentName(ctx, NotifService.class);
    }

    void setListener(Listener l) { listener = l; }

    void start() {
        if (started) return;
        started = true;
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(tick, f, Context.RECEIVER_NOT_EXPORTED);
        else ctx.registerReceiver(tick, f);
        NotifService.onChange = this::refresh;
        watchSessions();
        refresh();
    }

    void stop() {
        if (!started) return;
        started = false;
        try { ctx.unregisterReceiver(tick); } catch (RuntimeException ignored) { }
        NotifService.onChange = null;
        main.removeCallbacks(expire);
        unwatchSessions();
    }

    boolean hasNotificationAccess() {
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            return nm != null && nm.isNotificationListenerAccessGranted(listenerCn);
        }
        String s = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(listenerCn.flattenToString());
    }

    void destroy() { main.removeCallbacks(expire); net.shutdownNow(); }

    void resetWeather() { weatherAt = 0; }

    private final Runnable expire = this::refresh;

    void refresh() {
        readMedia();
        readNotification();
        if (System.currentTimeMillis() - weatherAt > WEATHER_EVERY_MS) fetchWeather();
        if (listener != null) listener.onInfoChanged();
    }

    // ---- clock ----------------------------------------------------------

    String time() { return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()); }

    String date() { return new SimpleDateFormat("EEE, d MMM", new Locale("uk")).format(new Date()); }

    // ---- music ----------------------------------------------------------

    private void watchSessions() {
        if (!hasNotificationAccess()) return;
        try {
            MediaSessionManager msm = ctx.getSystemService(MediaSessionManager.class);
            sessionsListener = controllers -> { rewireCallbacks(controllers); refresh(); };
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerCn, main);
            rewireCallbacks(msm.getActiveSessions(listenerCn));
        } catch (RuntimeException ignored) {
            sessionsListener = null;
        }
    }

    private void unwatchSessions() {
        try {
            if (sessionsListener != null) {
                ctx.getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(sessionsListener);
            }
        } catch (RuntimeException ignored) { }
        sessionsListener = null;
        for (Map.Entry<MediaController, MediaController.Callback> e : callbacks.entrySet()) {
            try { e.getKey().unregisterCallback(e.getValue()); } catch (RuntimeException ignored) { }
        }
        callbacks.clear();
    }

    private void rewireCallbacks(List<MediaController> controllers) {
        for (Map.Entry<MediaController, MediaController.Callback> e : callbacks.entrySet()) {
            try { e.getKey().unregisterCallback(e.getValue()); } catch (RuntimeException ignored) { }
        }
        callbacks.clear();
        if (controllers == null) return;
        for (MediaController c : controllers) {
            MediaController.Callback cb = new MediaController.Callback() {
                @Override public void onPlaybackStateChanged(PlaybackState state) { refresh(); }
                @Override public void onMetadataChanged(MediaMetadata metadata) { refresh(); }
                @Override public void onSessionDestroyed() { refresh(); }
            };
            try { c.registerCallback(cb, main); callbacks.put(c, cb); } catch (RuntimeException ignored) { }
        }
    }

    private void readMedia() {
        Media prev = media;
        media = null;
        if (!hasNotificationAccess()) return;
        if (sessionsListener == null && started) watchSessions();
        List<MediaController> list;
        try {
            list = ctx.getSystemService(MediaSessionManager.class).getActiveSessions(listenerCn);
        } catch (RuntimeException e) {
            return;
        }
        MediaController best = null;
        MediaMetadata bestMd = null;
        boolean bestPlaying = false;
        for (MediaController c : list) {
            PlaybackState st = c.getPlaybackState();
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            MediaMetadata cmd = c.getMetadata();
            if (cmd == null) continue;
            if (best == null || (playing && !bestPlaying)) { best = c; bestMd = cmd; bestPlaying = playing; }
        }
        long now = System.currentTimeMillis();
        if (bestPlaying) lastPlayingAt = now;
        if (best == null || (!bestPlaying && now - lastPlayingAt > SHOW_PAUSED_MS)) return;

        MediaMetadata md = bestMd;
        Media m = new Media();
        m.controller = best;
        m.pkg = best.getPackageName();
        m.playing = bestPlaying;
        m.title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (m.title == null) m.title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        m.artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (m.artist == null) m.artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        m.art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (m.art == null) m.art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (m.title == null) m.title = "";
        if (m.artist == null) m.artist = "";
        // Keep the same Bitmap object for the same track, so callers can cache work on it.
        if (prev != null && m.title.equals(prev.title) && m.artist.equals(prev.artist)
                && m.pkg.equals(prev.pkg) && prev.art != null) {
            m.art = prev.art;
        }
        media = m;
    }

    void playPause() {
        if (media == null || media.controller == null) {
            // nothing active: ask the last music app to resume
            android.media.AudioManager am = ctx.getSystemService(android.media.AudioManager.class);
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY));
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY));
            return;
        }
        try {
            if (media.playing) media.controller.getTransportControls().pause();
            else media.controller.getTransportControls().play();
        } catch (RuntimeException ignored) { }
    }

    void next() {
        if (media != null && media.controller != null) {
            try { media.controller.getTransportControls().skipToNext(); } catch (RuntimeException ignored) { }
        }
    }

    void previous() {
        if (media != null && media.controller != null) {
            try { media.controller.getTransportControls().skipToPrevious(); } catch (RuntimeException ignored) { }
        }
    }

    // ---- notifications --------------------------------------------------

    private void readNotification() {
        notif = null;
        // Only the notification that has *just* arrived, for a few seconds — not ones that sit in the shade.
        StatusBarNotification best = NotifService.lastPosted;
        long age = System.currentTimeMillis() - NotifService.lastPostedAt;
        if (best == null || age > NOTIF_SHOW_MS) return;
        Notification bn = best.getNotification();
        if (bn == null || best.isOngoing() || !best.isClearable()) return;
        if (best.getPackageName().equals(ctx.getPackageName())) return;
        if ((bn.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (Notification.CATEGORY_TRANSPORT.equals(bn.category)) return;
        main.removeCallbacks(expire);
        main.postDelayed(expire, NOTIF_SHOW_MS - age + 50);
        Notification n = best.getNotification();
        Bundle ex = n.extras;
        Notif out = new Notif();
        out.pkg = best.getPackageName();
        out.key = best.getKey();
        out.intent = n.contentIntent;
        out.autoCancel = (n.flags & Notification.FLAG_AUTO_CANCEL) != 0;
        CharSequence title = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TEXT);
        out.title = title == null ? "" : title.toString();
        out.text = text == null ? "" : text.toString();
        if (out.title.isEmpty() && out.text.isEmpty()) return;
        notif = out;
    }

    void openNotification() {
        Notif n = notif;
        if (n == null) return;
        try {
            if (n.intent != null) sendAllowingStart(ctx, n.intent);
        } catch (PendingIntent.CanceledException ignored) { }
        NotifService svc = NotifService.instance;
        if (n.autoCancel && svc != null) {
            try { svc.cancelNotification(n.key); } catch (RuntimeException ignored) { }
        }
    }

    /** Android 14+ blocks activity starts from PendingIntents unless the sender opts in. */
    static void sendAllowingStart(Context c, PendingIntent pi) throws PendingIntent.CanceledException {
        Bundle o = null;
        if (Build.VERSION.SDK_INT >= 34) {
            o = android.app.ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
        }
        pi.send(c, 0, null, null, null, null, o);
    }

    void dismissNotification() {
        Notif n = notif;
        NotifService svc = NotifService.instance;
        if (n != null && svc != null) {
            try { svc.cancelNotification(n.key); } catch (RuntimeException ignored) { }
        }
    }

    // ---- weather (Open-Meteo, no key needed) -----------------------------

    private void fetchWeather() {
        if (net.isShutdown()) return;
        weatherAt = System.currentTimeMillis();
        double[] ll = location();
        net.execute(() -> {
            String result = null;
            HttpURLConnection con = null;
            try {
                URL url = new URL(String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                                + "&current=temperature_2m,weather_code&timezone=auto", ll[0], ll[1]));
                con = (HttpURLConnection) url.openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(8000);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONObject cur = new JSONObject(sb.toString()).getJSONObject("current");
                long t = Math.round(cur.getDouble("temperature_2m"));
                int code = cur.getInt("weather_code");
                result = t + "° · " + describe(code);
            } catch (Exception e) {
                weatherAt = 0; // try again next minute
            } finally {
                if (con != null) con.disconnect();
            }
            if (result != null) {
                final String w = result;
                main.post(() -> { weather = w; if (listener != null) listener.onInfoChanged(); });
            }
        });
    }

    private double[] location() {
        double[] kyiv = {50.45, 30.52};
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return kyiv;
        }
        try {
            LocationManager lm = ctx.getSystemService(LocationManager.class);
            Location best = null;
            for (String p : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER,
                    LocationManager.GPS_PROVIDER}) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (RuntimeException ignored) { }   // some providers need FINE location
            }
            if (best != null) return new double[]{best.getLatitude(), best.getLongitude()};
        } catch (RuntimeException ignored) { }
        return kyiv;
    }

    private static String describe(int code) {
        if (code == 0) return "Ясно";
        if (code <= 2) return "Мінлива хмарність";
        if (code == 3) return "Хмарно";
        if (code == 45 || code == 48) return "Туман";
        if (code >= 51 && code <= 57) return "Мряка";
        if (code >= 61 && code <= 67) return "Дощ";
        if (code >= 71 && code <= 77) return "Сніг";
        if (code >= 80 && code <= 82) return "Злива";
        if (code == 85 || code == 86) return "Снігопад";
        if (code >= 95) return "Гроза";
        return "";
    }

    static List<String> nothing() { return new ArrayList<>(); }
}
