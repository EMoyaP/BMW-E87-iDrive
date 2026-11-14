package com.ug.e87idrive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;

/**
 * Passive observer for the media-information broadcast emitted by SpeedPlay.
 * It never sends commands, binds OEM services or changes the Android Auto link.
 */
public final class SpeedPlayMediaReceiver extends BroadcastReceiver {
    public static final String ACTION_MEDIA_PLAY_INFO =
            "com.suding.speedplay.ACTION_MEDIA_PLAY_INFO";
    private static final int MAX_TEXT_LENGTH = 256;
    private static final Object LOCK = new Object();
    private static volatile Snapshot latest;
    private static volatile boolean registered;
    private static SpeedPlayMediaReceiver foregroundReceiver;

    public static final class Snapshot {
        public final String title;
        public final String album;
        public final String artist;
        public final long position;
        public final long duration;
        public final int playState;
        public final long receivedAt;

        Snapshot(String title, String album, String artist, long position, long duration,
                 int playState, long receivedAt) {
            this.title = title;
            this.album = album;
            this.artist = artist;
            this.position = position;
            this.duration = duration;
            this.playState = playState;
            this.receivedAt = receivedAt;
        }

        public boolean hasContent() {
            return !title.isEmpty() || !album.isEmpty() || !artist.isEmpty()
                    || playState != 0;
        }

        public boolean isPlayingOrPaused() {
            return playState == 2 || playState == 3 || playState == 6
                    || playState == 8;
        }

        public String stateLabel() {
            switch (playState) {
                case 1: return "DETENIDO";
                case 2: return "EN PAUSA";
                case 3: return "REPRODUCIENDO";
                case 4: return "AVANCE RÁPIDO";
                case 5: return "RETROCESO";
                case 6: return "CARGANDO";
                case 7: return "ERROR";
                case 8: return "CONECTANDO";
                case 9: return "ANTERIOR";
                case 10: return "SIGUIENTE";
                default: return playState == 0 ? "SIN ESTADO" : "ESTADO " + playState;
            }
        }
    }

    /**
     * SpeedPlay sends this as an implicit app broadcast. Android 8+ may suppress
     * implicit broadcasts to manifest receivers, so listen dynamically while the
     * dashboard is visible. The manifest receiver remains a compatibility path
     * on older Android releases.
     */
    public static void register(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (registered) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                SpeedPlayMediaReceiver receiver = new SpeedPlayMediaReceiver();
                IntentFilter filter = new IntentFilter(ACTION_MEDIA_PLAY_INFO);
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                    } else {
                        context.registerReceiver(receiver, filter);
                    }
                    foregroundReceiver = receiver;
                    registered = true;
                    AppSessionLog.event("MULTIMEDIA", "Receptor dinámico SpeedPlay activo · "
                            + ACTION_MEDIA_PLAY_INFO);
                } catch (Exception error) {
                    AppSessionLog.event("MULTIMEDIA", "No se pudo registrar receptor SpeedPlay · "
                            + error.getClass().getSimpleName());
                }
            } else {
                registered = true;
                AppSessionLog.event("MULTIMEDIA", "Receptor SpeedPlay delegado al manifest · "
                        + ACTION_MEDIA_PLAY_INFO);
            }
        }
    }

    public static void unregister(Context context) {
        synchronized (LOCK) {
            SpeedPlayMediaReceiver receiver = foregroundReceiver;
            foregroundReceiver = null;
            registered = false;
            if (receiver != null && context != null) {
                try {
                    context.unregisterReceiver(receiver);
                    AppSessionLog.event("MULTIMEDIA", "Receptor dinámico SpeedPlay detenido");
                } catch (Exception error) {
                    AppSessionLog.event("MULTIMEDIA", "No se pudo detener receptor SpeedPlay · "
                            + error.getClass().getSimpleName());
                }
            }
        }
    }

    public static Snapshot latest() {
        return latest;
    }

    public static String diagnosticSummary() {
        Snapshot snapshot = latest;
        if (snapshot == null) {
            return "SpeedPlay ACTION_MEDIA_PLAY_INFO · sin eventos recibidos";
        }
        return "SpeedPlay ACTION_MEDIA_PLAY_INFO · último evento=" + snapshot.receivedAt
                + " · estado=" + snapshot.stateLabel()
                + " · título=" + display(snapshot.title)
                + " · artista=" + display(snapshot.artist)
                + " · álbum=" + display(snapshot.album)
                + " · posición=" + snapshot.position
                + " · duración=" + snapshot.duration;
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_MEDIA_PLAY_INFO.equals(intent.getAction())) return;
        if (!trustedSender(context)) return;
        String title = textExtra(intent, "title");
        String album = textExtra(intent, "album");
        String artist = textExtra(intent, "artist");
        long position = numberExtra(intent, "position", -1L);
        long duration = numberExtra(intent, "duration", -1L);
        int playState = (int) numberExtra(intent, "playState", 0L);
        long receivedAt = System.currentTimeMillis();
        Snapshot snapshot = new Snapshot(title, album, artist, position, duration,
                playState, receivedAt);
        latest = snapshot;
        AppSessionLog.event("MULTIMEDIA", "SpeedPlay broadcast recibido · fuente=SpeedPlay"
                + " · estado=" + snapshot.stateLabel()
                + " · título=" + display(title)
                + " · artista=" + display(artist)
                + " · álbum=" + display(album)
                + " · posición=" + position + " · duración=" + duration);
    }

    /**
     * Android 14 exposes the package which sent an explicit/implicit broadcast. On those builds
     * accept only system-installed software (SpeedPlay is an OEM application on the head unit),
     * plus our own package for instrumentation. Older head units do not expose this identity, so
     * retaining the read-only legacy path is necessary for compatibility.
     */
    private boolean trustedSender(Context context) {
        if (Build.VERSION.SDK_INT < 34) return true;
        String sender = getSentFromPackage();
        if (sender != null && sender.equals(context.getPackageName())) return true;
        if (sender != null && !sender.trim().isEmpty()) {
            if (!"com.suding.speedplay".equals(sender)) {
                AppSessionLog.sampledEvent("speedplay_sender_rejected_" + sender, "SEGURIDAD",
                        "SpeedPlay descartado: emisor no esperado " + sender, 60_000L);
                return false;
            }
            return isSystemSpeedPlay(context);
        }

        // Some vendor Android 15 builds deliver this implicit OEM broadcast without
        // package attribution. Use the sending UID when available; only accept the
        // OEM SpeedPlay UID. If the firmware hides both fields, require its installed
        // system package as a compatibility fallback (this event only updates UI text).
        int senderUid = getSentFromUid();
        if (senderUid >= 0) {
            String[] packages = context.getPackageManager().getPackagesForUid(senderUid);
            if (packages != null) {
                for (String packageName : packages) {
                    if ("com.suding.speedplay".equals(packageName)
                            && isSystemSpeedPlay(context)) return true;
                }
            }
            if (BuildConfig.DEBUG && senderUid == android.os.Process.SHELL_UID) return true;
            AppSessionLog.sampledEvent("speedplay_sender_rejected_uid_" + senderUid, "SEGURIDAD",
                    "SpeedPlay descartado: UID emisor no es el OEM (" + senderUid + ")", 60_000L);
            return false;
        }
        if (BuildConfig.DEBUG && isEmulator()) {
            AppSessionLog.sampledEvent("speedplay_sender_emulator_test", "MULTIMEDIA",
                    "SpeedPlay de prueba aceptado en emulador sin identidad de emisor", 60_000L);
            return true;
        }
        if (isSystemSpeedPlay(context)) {
            AppSessionLog.sampledEvent("speedplay_sender_unattributed", "MULTIMEDIA",
                    "SpeedPlay aceptado: Android no facilita identidad del broadcast; paquete OEM verificado", 60_000L);
            return true;
        }
        AppSessionLog.sampledEvent("speedplay_sender_missing", "SEGURIDAD",
                "SpeedPlay descartado: emisor sin identificar y app OEM no verificada", 60_000L);
        return false;
    }

    private static boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.FINGERPRINT.toLowerCase(java.util.Locale.ROOT).contains("sdk_phone")
                || Build.MODEL.toLowerCase(java.util.Locale.ROOT).contains("emulator")
                || Build.MODEL.toLowerCase(java.util.Locale.ROOT).contains("sdk_gphone")
                || Build.MODEL.toLowerCase(java.util.Locale.ROOT).contains("sdk built for");
    }

    private static boolean isSystemSpeedPlay(Context context) {
        try {
            ApplicationInfo application = context.getPackageManager()
                    .getApplicationInfo("com.suding.speedplay", 0);
            int systemFlags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            if ((application.flags & systemFlags) != 0) return true;
        } catch (Exception ignored) { }
        return false;
    }

    private static String textExtra(Intent intent, String name) {
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) return "";
            Object value = extras.get(name);
            if (value instanceof CharSequence) return clean(value.toString());
            if (value instanceof String) return clean((String) value);
        } catch (Exception ignored) { }
        return "";
    }

    private static long numberExtra(Intent intent, String name, long fallback) {
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) return fallback;
            Object value = extras.get(name);
            return value instanceof Number ? ((Number) value).longValue() : fallback;
        } catch (Exception ignored) { return fallback; }
    }

    private static String clean(String value) {
        if (value == null) return "";
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').trim();
        return cleaned.length() > MAX_TEXT_LENGTH
                ? cleaned.substring(0, MAX_TEXT_LENGTH) : cleaned;
    }

    private static String display(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }
}
