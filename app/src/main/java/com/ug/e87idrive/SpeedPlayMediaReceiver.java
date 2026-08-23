package com.ug.e87idrive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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
     * The receiver is declared in the manifest so the unit can deliver the
     * implicit SpeedPlay event even when the activity is not foreground. A
     * dynamic receiver alone was not sufficient on this Android 11 build.
     */
    public static void register(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (registered) return;
            registered = true;
            AppSessionLog.event("MULTIMEDIA", "Receptor pasivo SpeedPlay declarado en manifest · "
                    + ACTION_MEDIA_PLAY_INFO);
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
