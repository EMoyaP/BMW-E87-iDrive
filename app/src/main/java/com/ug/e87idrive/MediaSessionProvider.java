package com.ug.e87idrive;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;

import java.util.List;

/**
 * Reads metadata exposed by Android's documented MediaSession API only. The app
 * never sends media buttons and never communicates with Android Auto or OEM APIs.
 */
public final class MediaSessionProvider {
    public static final class Snapshot {
        public final String title, artist, source, state;
        public final Bitmap artwork;
        public final boolean accessGranted, sessionAvailable;

        Snapshot(String title, String artist, String source, String state, Bitmap artwork,
                 boolean accessGranted, boolean sessionAvailable) {
            this.title = title;
            this.artist = artist;
            this.source = source;
            this.state = state;
            this.artwork = artwork;
            this.accessGranted = accessGranted;
            this.sessionAvailable = sessionAvailable;
        }
    }

    private final Context context;
    private Snapshot last = unavailable(false);
    private int lastArtworkGeneration = -1;
    private Bitmap lastArtwork;

    public MediaSessionProvider(Context context) { this.context = context.getApplicationContext(); }

    public Snapshot refresh() {
        try {
            List<MediaController> controllers = activeSessions();
            // A successful public API call means Android accepted the listener component.
            if (controllers == null || controllers.isEmpty()) return last = unavailable(true);

            MediaController controller = preferredController(controllers);
            return last = fromController(controller);
        } catch (SecurityException ignored) {
            return last = unavailable(false);
        } catch (Exception ignored) {
            return last = unavailable(false);
        }
    }

    /** Returns metadata only from the assigned radio app; never controls that session. */
    public Snapshot refreshForPackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return radioUnavailable(false, null);
        try {
            List<MediaController> controllers = activeSessions();
            if (controllers != null) {
                for (MediaController controller : controllers) {
                    if (packageName.equals(controller.getPackageName())) return fromController(controller);
                }
            }
            MediaNotificationListener.Snapshot notification =
                    MediaNotificationListener.latestForPackage(packageName);
            if (notification != null) {
                String title = notification.title.isEmpty() ? notification.text : notification.title;
                String detail = notification.text.isEmpty() || notification.text.equals(title)
                        ? "Información publicada por la radio" : notification.text;
                return new Snapshot(title, detail, packageName, "NOTIFICACIÓN",
                        null, true, true);
            }
            return radioUnavailable(true, packageName);
        } catch (SecurityException ignored) {
            return radioUnavailable(false, packageName);
        } catch (Exception ignored) {
            return radioUnavailable(false, packageName);
        }
    }

    public String radioDiagnostic(String packageName) {
        StringBuilder out = new StringBuilder("LECTURA DE EMISORA (SOLO APIs ANDROID)\n");
        if (packageName == null) return out.append("Aplicación de radio: sin asignar\n").toString();
        out.append("Aplicación asignada: ").append(packageName).append('\n');
        Snapshot snapshot = refreshForPackage(packageName);
        out.append("Acceso a notificaciones: ").append(snapshot.accessGranted ? "concedido" : "no concedido").append('\n');
        out.append("Fuente encontrada: ").append(snapshot.sessionAvailable ? snapshot.state : "ninguna").append('\n');
        if (snapshot.sessionAvailable) {
            out.append("Título publicado: ").append(snapshot.title).append('\n');
            out.append("Detalle publicado: ").append(snapshot.artist).append('\n');
        } else {
            out.append("Resultado: la app OEM no expone una MediaSession ni una notificación legible.\n");
        }
        return out.toString();
    }

    private List<MediaController> activeSessions() {
        MediaSessionManager manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        ComponentName listener = new ComponentName(context, MediaNotificationListener.class);
        return manager == null ? null : manager.getActiveSessions(listener);
    }

    private Snapshot fromController(MediaController controller) {
        MediaMetadata metadata = controller.getMetadata();
        String title = firstText(metadata, "Emisora sin identificar",
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_ALBUM);
        String artist = firstText(metadata, "Sin información RDS",
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
                MediaMetadata.METADATA_KEY_GENRE);
        return new Snapshot(title, artist, controller.getPackageName(), state(controller),
                artwork(metadata), true, true);
    }

    private static MediaController preferredController(List<MediaController> controllers) {
        for (MediaController controller : controllers) if (controller.getMetadata() != null) return controller;
        return controllers.get(0);
    }

    private static String firstText(MediaMetadata metadata, String fallback, String... keys) {
        if (metadata != null) for (String key : keys) {
            CharSequence value = metadata.getText(key);
            if (value != null && !value.toString().trim().isEmpty()) return value.toString().trim();
        }
        return fallback;
    }

    private Bitmap artwork(MediaMetadata metadata) {
        if (metadata == null) return null;
        Bitmap albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        Bitmap source = albumArt != null ? albumArt : metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (source == null) {
            lastArtworkGeneration = -1;
            lastArtwork = null;
            return null;
        }
        int generation = source.getGenerationId();
        if (generation == lastArtworkGeneration && lastArtwork != null && !lastArtwork.isRecycled()) return lastArtwork;
        final int maxSide = 256;
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= maxSide && height <= maxSide) {
            lastArtwork = source;
        } else {
            float scale = Math.min(maxSide / (float) width, maxSide / (float) height);
            lastArtwork = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(width * scale)),
                    Math.max(1, Math.round(height * scale)), true);
        }
        lastArtworkGeneration = generation;
        return lastArtwork;
    }

    private static String state(MediaController controller) {
        PlaybackState playback = controller.getPlaybackState();
        if (playback == null) return "SESIÓN ACTIVA";
        if (playback.getState() == PlaybackState.STATE_PLAYING) return "REPRODUCIENDO";
        if (playback.getState() == PlaybackState.STATE_PAUSED) return "EN PAUSA";
        return "SESIÓN ACTIVA";
    }

    private static Snapshot unavailable(boolean granted) {
        return new Snapshot(granted ? "No hay reproducción activa" : "Acceso multimedia pendiente",
                granted ? "Abre Spotify, radio u otra app" : "Actívalo en Ajustes para leer carátula y título",
                "MediaSession estándar", "", null, granted, false);
    }

    private static Snapshot radioUnavailable(boolean granted, String packageName) {
        return new Snapshot(granted ? "Emisora no expuesta" : "Acceso multimedia pendiente",
                granted ? "La aplicación OEM no publica datos estándar"
                        : "Activa el acceso multimedia en Ajustes",
                packageName == null ? "Radio sin asignar" : packageName,
                "", null, granted, false);
    }
}
