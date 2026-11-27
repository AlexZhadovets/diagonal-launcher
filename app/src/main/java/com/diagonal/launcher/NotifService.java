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

    private final Handler main = new Handler(Looper.getMainLooper());

    private void changed() {
        Runnable r = onChange;
        if (r != null) main.post(r);
    }

    @Override public void onListenerConnected() { instance = this; changed(); }

    @Override public void onListenerDisconnected() { instance = null; changed(); }

    @Override public void onNotificationPosted(StatusBarNotification sbn) { changed(); }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) { changed(); }
}
