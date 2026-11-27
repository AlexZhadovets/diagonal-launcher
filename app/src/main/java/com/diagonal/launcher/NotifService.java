package com.diagonal.launcher;

import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Lets the launcher see notifications and music sessions (the user enables it in
 * Settings → Notification access). Only used to show them on the centre stripe.
 */
public class NotifService extends NotificationListenerService {

    static volatile NotifService instance;
    static volatile Runnable onChange;
    /** The notification that just arrived (shown briefly on the centre stripe). */
    static volatile StatusBarNotification lastPosted;
    static volatile long lastPostedAt;

    private final Handler main = new Handler(Looper.getMainLooper());

    private void changed() {
        Runnable r = onChange;
        if (r != null) main.post(r);
    }

    @Override public void onListenerConnected() { instance = this; changed(); }

    @Override public void onListenerDisconnected() { instance = null; changed(); }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        StatusBarNotification prev = lastPosted;
        boolean silent = prev != null && prev.getKey().equals(sbn.getKey())
                && (sbn.getNotification().flags & android.app.Notification.FLAG_ONLY_ALERT_ONCE) != 0;
        if (!silent && worthShowing(sbn)) {   // only real new ones flash on the centre stripe
            lastPosted = sbn;
            lastPostedAt = System.currentTimeMillis();
        }
        changed();                            // the list always updates (e.g. after an inline reply)
    }

    private boolean worthShowing(StatusBarNotification sbn) {
        android.app.Notification n = sbn.getNotification();
        if (n == null || sbn.isOngoing() || !sbn.isClearable()) return false;
        if (sbn.getPackageName().equals(getPackageName())) return false;
        if ((n.flags & android.app.Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        if (android.app.Notification.CATEGORY_TRANSPORT.equals(n.category)) return false;
        CharSequence t = n.extras == null ? null : n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE);
        CharSequence x = n.extras == null ? null : n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT);
        return (t != null && t.length() > 0) || (x != null && x.length() > 0);
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        StatusBarNotification cur = lastPosted;
        if (cur != null && cur.getKey().equals(sbn.getKey())) lastPosted = null;
        changed();
    }
}
