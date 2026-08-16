package com.ug.e87idrive;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

import java.util.List;

/**
 * Uses Android's documented MediaSession API only. Transport controls are sent
 * exclusively to a session that explicitly publishes the matching standard action.
 * No OEM radio, CAN, UART, Android Auto or proprietary protocol is accessed.
 */
public final class MediaSessionProvider {
    public static final class Snapshot {
        public final String title, artist, source, state;
        public final Bitmap artwork;
        public final boolean accessGranted, listenerConnected, sessionAvailable;
        public final boolean canPlayPause, canPrevious, canNext;

        Snapshot(String title, String artist, String source, String state, Bitmap artwork,
                 boolean accessGranted, boolean listenerConnected, boolean sessionAvailable,
                 boolean canPlayPause,
                 boolean canPrevious, boolean canNext) {
            this.title = title;
            this.artist = artist;
            this.source = source;
            this.state = state;
            this.artwork = artwork;
            this.accessGranted = accessGranted;
            this.listenerConnected = listenerConnected;
            this.sessionAvailable = sessionAvailable;
            this.canPlayPause = canPlayPause;
            this.canPrevious = canPrevious;
            this.canNext = canNext;
        }
    }

    public enum Command { TOGGLE, PREVIOUS, NEXT }

    private final Context context;
    private Snapshot last = unavailable(false);
    private int lastArtworkGeneration = -1;
    private Bitmap lastArtwork;

    public MediaSessionProvider(Context context) { this.context = context.getApplicationContext(); }

    public Snapshot refresh() {
        try {
            List<MediaController> controllers = activeSessions();
            // A successful public API call means Android accepted the listener component.
            if (controllers == null || controllers.isEmpty()) return last = unavailable(isAccessConfigured());

            MediaController controller = preferredController(controllers);
            return last = fromController(controller);
        } catch (SecurityException ignored) {
            return last = unavailable(false);
        } catch (Exception ignored) {
            return last = unavailable(false);
        }
    }

    /** Prefers the assigned Android Auto bridge when it exposes a MediaSession, then falls back safely. */
    public Snapshot refreshPreferred(String packageName) {
        try {
            List<MediaController> controllers = activeSessions();
            MediaController controller = findController(controllers, packageName);
            if (controller == null) {
                MediaNotificationListener.Snapshot notification =
                        MediaNotificationListener.latestForPackage(packageName);
                if (notification == null) {
                    controller = findKnownMediaController(controllers);
                    if (controller != null) return last = fromController(controller);
                    notification = MediaNotificationListener.latestForPackages(
                            "com.spotify.music", "com.suding.speedplay");
                }
                if (notification != null) return last = fromNotification(notification);
                return last = unavailable(isAccessConfigured());
            }
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
                        null, true, MediaNotificationListener.isConnected(), true,
                        false, false, false);
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

    public String mediaDiagnostic(String preferredPackage) {
        StringBuilder out = new StringBuilder("MULTIMEDIA / ANDROID AUTO · API ESTÁNDAR\n");
        out.append("paquete Android Auto asignado=")
                .append(preferredPackage == null ? "(ninguno)" : preferredPackage).append('\n');
        out.append("acceso configurado=").append(isAccessConfigured()).append('\n');
        out.append(MediaNotificationListener.diagnosticSummary());
        try {
            List<MediaController> controllers = activeSessions();
            out.append("MediaSession activas=").append(controllers == null ? 0 : controllers.size()).append('\n');
            if (controllers != null) for (MediaController controller : controllers) {
                PlaybackState playback = controller.getPlaybackState();
                out.append("- ").append(controller.getPackageName())
                        .append(" · estado=").append(playback == null ? "sin estado" : playback.getState())
                        .append(" · acciones=0x")
                        .append(Long.toHexString(playback == null ? 0L : playback.getActions()))
                        .append('\n');
            }
        } catch (Exception error) {
            out.append("lectura de sesiones=").append(error.getClass().getSimpleName()).append('\n');
        }
        out.append("SpeedPlay solo puede leerse si publica una MediaSession o notificación; "
                + "iDrive no inicia su servicio privado ni usa protocolos propietarios.\n");
        return out.toString();
    }

    public void requestListenerRebind() {
        if (!isAccessConfigured() || MediaNotificationListener.isConnected()
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        try {
            NotificationListenerService.requestRebind(
                    new ComponentName(context, MediaNotificationListener.class));
        } catch (Exception ignored) { }
    }

    /**
     * Sends a documented transport command only when the currently published
     * MediaSession advertises it. This works with Spotify/SpeedPlay if that
     * Android Auto session is exposed; it intentionally never guesses radio APIs.
     */
    public boolean control(Command command, String preferredPackage) {
        try {
            List<MediaController> controllers = activeSessions();
            MediaController controller = findController(controllers, preferredPackage);
            if (controller == null) controller = findKnownMediaController(controllers);
            if (controller == null) return false;
            PlaybackState playback = controller.getPlaybackState();
            long actions = playback == null ? 0L : playback.getActions();
            MediaController.TransportControls controls = controller.getTransportControls();
            if (command == Command.PREVIOUS && (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L) {
                controls.skipToPrevious();
                return true;
            }
            if (command == Command.NEXT && (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0L) {
                controls.skipToNext();
                return true;
            }
            if (command == Command.TOGGLE) {
                boolean playing = playback != null && playback.getState() == PlaybackState.STATE_PLAYING;
                if (playing && ((actions & PlaybackState.ACTION_PAUSE) != 0L
                        || (actions & PlaybackState.ACTION_PLAY_PAUSE) != 0L)) {
                    controls.pause();
                    return true;
                }
                if (!playing && ((actions & PlaybackState.ACTION_PLAY) != 0L
                        || (actions & PlaybackState.ACTION_PLAY_PAUSE) != 0L)) {
                    controls.play();
                    return true;
                }
            }
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }
        return false;
    }

    private List<MediaController> activeSessions() {
        MediaSessionManager manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        ComponentName listener = new ComponentName(context, MediaNotificationListener.class);
        return manager == null ? null : manager.getActiveSessions(listener);
    }

    private static MediaController findController(List<MediaController> controllers, String preferredPackage) {
        if (controllers == null || controllers.isEmpty()) return null;
        if (preferredPackage != null && !preferredPackage.trim().isEmpty()) {
            for (MediaController controller : controllers) {
                if (preferredPackage.equals(controller.getPackageName())) return controller;
            }
            return null;
        }
        return findKnownMediaController(controllers);
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
        PlaybackState playback = controller.getPlaybackState();
        long actions = playback == null ? 0L : playback.getActions();
        boolean canPlayPause = (actions & (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE)) != 0L;
        return new Snapshot(title, artist, controller.getPackageName(), state(controller),
                artwork(metadata), true, MediaNotificationListener.isConnected(), true,
                canPlayPause,
                (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
                (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0L);
    }

    private Snapshot fromNotification(MediaNotificationListener.Snapshot notification) {
        String title = notification.title.isEmpty() ? notification.text : notification.title;
        String detail = notification.text.isEmpty() || notification.text.equals(title)
                ? "Información publicada por Android" : notification.text;
        return new Snapshot(title, detail, notification.packageName, "NOTIFICACIÓN",
                null, true, MediaNotificationListener.isConnected(), true,
                false, false, false);
    }

    private static MediaController preferredController(List<MediaController> controllers) {
        for (MediaController controller : controllers) if (controller.getMetadata() != null) return controller;
        return controllers.get(0);
    }

    private static MediaController findKnownMediaController(List<MediaController> controllers) {
        if (controllers == null) return null;
        for (MediaController controller : controllers) {
            String packageName = controller.getPackageName();
            if (packageName != null && (packageName.contains("spotify")
                    || packageName.contains("speedplay") || packageName.contains("music"))) {
                return controller;
            }
        }
        return null;
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
        return new Snapshot(granted ? "No hay reproducción expuesta" : "Acceso multimedia pendiente",
                granted ? (MediaNotificationListener.isConnected()
                        ? "SpeedPlay no publica la sesión de Android Auto"
                        : "Reconectando lector multimedia…")
                        : "Actívalo en Ajustes para leer carátula y título",
                "MediaSession estándar", "", null, granted,
                MediaNotificationListener.isConnected(), false, false, false, false);
    }

    private static Snapshot radioUnavailable(boolean granted, String packageName) {
        return new Snapshot(granted ? "Emisora no expuesta" : "Acceso multimedia pendiente",
                granted ? "La aplicación OEM no publica datos estándar"
                        : "Activa el acceso multimedia en Ajustes",
                packageName == null ? "Radio sin asignar" : packageName,
                "", null, granted, MediaNotificationListener.isConnected(),
                false, false, false, false);
    }

    private boolean isAccessConfigured() {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    "enabled_notification_listeners");
            ComponentName listener = new ComponentName(context, MediaNotificationListener.class);
            return enabled != null && (enabled.contains(listener.flattenToString())
                    || enabled.contains(listener.flattenToShortString()));
        } catch (Exception ignored) { return false; }
    }
}
