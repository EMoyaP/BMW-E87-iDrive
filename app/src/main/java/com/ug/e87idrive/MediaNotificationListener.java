package com.ug.e87idrive;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Declares the user-granted notification-listener component required by Android to
 * read active MediaSession metadata. It deliberately does not intercept, alter or
 * dismiss notifications.
 */
public final class MediaNotificationListener extends NotificationListenerService {
    static final class NavigationSnapshot {
        final String maneuver, distance, raw;
        final long postedAt;
        NavigationSnapshot(String maneuver, String distance, String raw, long postedAt) {
            this.maneuver = maneuver; this.distance = distance; this.raw = raw;
            this.postedAt = postedAt;
        }
    }

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
    private static final Map<String, NavigationSnapshot> NAVIGATION = new ConcurrentHashMap<>();
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
        if (item != null) {
            ACTIVE.remove(item.getKey());
            NAVIGATION.remove(item.getKey());
        }
    }

    private static void remember(StatusBarNotification item) {
        if (item == null || item.getNotification() == null) return;
        Bundle extras = item.getNotification().extras;
        if (extras == null) return;
        if ("com.google.android.apps.maps".equals(item.getPackageName())) {
            String title = value(extras.getCharSequence(Notification.EXTRA_TITLE));
            String text = value(extras.getCharSequence(Notification.EXTRA_TEXT));
            CharSequence big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (big != null && !big.toString().trim().isEmpty()) text = big.toString().trim();
            String combined = (title + " " + text).trim();
            NavigationSnapshot maneuver = parseNavigation(combined, item.getPostTime());
            if (maneuver == null) NAVIGATION.remove(item.getKey());
            else NAVIGATION.put(item.getKey(), maneuver);
        }
        // A notification listener is intentionally broad enough for Spotify and phone media,
        // but it must not surface unrelated car-bridge notifications as a media title.
        if (!Notification.CATEGORY_TRANSPORT.equals(item.getNotification().category)
                && !extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return;
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

    static NavigationSnapshot latestNavigation() {
        NavigationSnapshot latest = null;
        long now = System.currentTimeMillis();
        for (NavigationSnapshot candidate : NAVIGATION.values()) {
            if (now - candidate.postedAt > 30_000L) continue;
            if (latest == null || candidate.postedAt > latest.postedAt) latest = candidate;
        }
        return latest;
    }

    private static NavigationSnapshot parseNavigation(String raw, long postedAt) {
        if (raw == null || raw.isEmpty()) return null;
        String normalized = raw.toLowerCase(new Locale("es", "ES"));
        String kind;
        if (normalized.contains("rotonda") || normalized.contains("glorieta")) kind = "ROUNDABOUT";
        else if (normalized.contains("izquierda") || normalized.contains("left")) kind = "LEFT";
        else if (normalized.contains("derecha") || normalized.contains("right")) kind = "RIGHT";
        else if (normalized.contains("salida") || normalized.contains("exit")) kind = "EXIT";
        else if (normalized.contains("continúa") || normalized.contains("continua")
                || normalized.contains("sigue") || normalized.contains("straight")) kind = "STRAIGHT";
        else if (normalized.contains("gira") || normalized.contains("girar")
                || normalized.contains("tome") || normalized.contains("take")) return null;
        else return null;

        Matcher matcher = Pattern.compile("(?i)(?:en\\s*)?(\\d+(?:[.,]\\d+)?)\\s*(m|metros|km|kil[oó]metros)\\b")
                .matcher(raw);
        String distance = "AHORA";
        if (matcher.find()) {
            String number = matcher.group(1).replace(',', '.');
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            distance = number + (unit.startsWith("k") ? " km" : " m");
        }
        return new NavigationSnapshot(kind, distance, raw, postedAt);
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
