package com.ug.e87idrive;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Declares the user-granted notification-listener component required by Android to
 * read active MediaSession metadata. It deliberately does not intercept, alter or
 * dismiss notifications.
 */
public final class MediaNotificationListener extends NotificationListenerService {
    static final class Snapshot {
        final String key, packageName, title, text;
        final long postedAt;

        Snapshot(String key, String packageName, String title, String text, long postedAt) {
            this.key = key;
            this.packageName = packageName;
            this.title = title;
            this.text = text;
            this.postedAt = postedAt;
        }
    }

    private static final Map<String, Snapshot> ACTIVE = new ConcurrentHashMap<>();
    private static volatile boolean connected;

    @Override public void onListenerConnected() {
        connected = true;
        ACTIVE.clear();
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) for (StatusBarNotification item : active) remember(item);
        } catch (Exception ignored) { }
    }

    @Override public void onListenerDisconnected() { connected = false; }

    @Override public void onNotificationPosted(StatusBarNotification item) { remember(item); }

    @Override public void onNotificationRemoved(StatusBarNotification item) {
        if (item != null) ACTIVE.remove(item.getKey());
    }

    private static void remember(StatusBarNotification item) {
        if (item == null || item.getNotification() == null) return;
        Bundle extras = item.getNotification().extras;
        if (extras == null) return;
        String title = value(extras.getCharSequence(Notification.EXTRA_TITLE));
        if (title.isEmpty()) title = value(extras.getCharSequence(Notification.EXTRA_TITLE_BIG));
        String text = value(extras.getCharSequence(Notification.EXTRA_TEXT));
        if (text.isEmpty()) text = value(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        if (title.isEmpty() && text.isEmpty()) return;
        ACTIVE.put(item.getKey(), new Snapshot(item.getKey(), item.getPackageName(),
                title, text, item.getPostTime()));
    }

    static Snapshot latestForPackage(String packageName) {
        if (packageName == null) return null;
        Snapshot latest = null;
        for (Snapshot item : ACTIVE.values()) {
            if (!packageName.equals(item.packageName)) continue;
            if (latest == null || item.postedAt > latest.postedAt) latest = item;
        }
        return latest;
    }

    static Snapshot latestForPackages(String... packageNames) {
        Snapshot latest = null;
        if (packageNames == null) return null;
        for (String packageName : packageNames) {
            Snapshot candidate = latestForPackage(packageName);
            if (candidate != null && (latest == null || candidate.postedAt > latest.postedAt)) {
                latest = candidate;
            }
        }
        return latest;
    }

    static boolean isConnected() { return connected; }

    static String diagnosticSummary() {
        StringBuilder out = new StringBuilder();
        out.append("listener conectado=").append(connected).append('\n');
        out.append("notificaciones legibles=").append(ACTIVE.size()).append('\n');
        for (Snapshot item : ACTIVE.values()) {
            out.append("- ").append(item.packageName).append(" · ")
                    .append(item.title.isEmpty() ? item.text : item.title).append('\n');
        }
        return out.toString();
    }

    private static String value(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }
}
