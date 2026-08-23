package com.ug.e87idrive;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/**
 * One-shot, local speech warning for a DGT fixed camera currently shown on the dashboard.
 *
 * It never requests audio focus, sends no OEM command and deliberately ignores section/mobile
 * controls. If the Android speech engine is missing or unavailable the visual alert continues
 * normally and the failure remains visible in the session log.
 */
final class RadarSpeechAnnouncer implements TextToSpeech.OnInitListener {
    static final String PREFERENCE_ENABLED = "dgt_fixed_radar_voice";

    private final Context context;
    private final SharedPreferences preferences;
    private TextToSpeech speech;
    private boolean ready;
    private boolean unavailable;
    private String announcedId;
    private String pendingMessage;

    RadarSpeechAnnouncer(Context context, SharedPreferences preferences) {
        this.context = context.getApplicationContext();
        this.preferences = preferences;
    }

    void onAlert(RadarRepository.Alert alert, SpeedLimitRepository.Match limit) {
        if (alert == null || !"FIJO".equalsIgnoreCase(alert.type)) {
            onAlertCleared();
            return;
        }
        if (!preferences.getBoolean(PREFERENCE_ENABLED, true)) return;
        if (alert.id.equals(announcedId)) return;
        announcedId = alert.id;
        String message = messageFor(alert, limit);
        if (unavailable) {
            AppSessionLog.event("RADARES DGT", "Locución no disponible · " + message);
            return;
        }
        if (speech == null) {
            pendingMessage = message;
            try {
                speech = new TextToSpeech(context, this);
            } catch (Exception error) {
                unavailable = true;
                AppSessionLog.event("RADARES DGT", "No se pudo iniciar voz · " + error.getClass().getSimpleName());
            }
            return;
        }
        if (ready) speak(message);
        else pendingMessage = message;
    }

    void onAlertCleared() {
        announcedId = null;
    }

    void stop() {
        if (speech != null) {
            try { speech.stop(); } catch (Exception ignored) { }
        }
    }

    void close() {
        stop();
        if (speech != null) {
            try { speech.shutdown(); } catch (Exception ignored) { }
        }
        speech = null;
        ready = false;
    }

    @Override public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || speech == null) {
            unavailable = true;
            pendingMessage = null;
            AppSessionLog.event("RADARES DGT", "Motor de voz Android no disponible");
            return;
        }
        int language = speech.setLanguage(new Locale("es", "ES"));
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            unavailable = true;
            pendingMessage = null;
            AppSessionLog.event("RADARES DGT", "Voz española no disponible en la unidad");
            return;
        }
        ready = true;
        String pending = pendingMessage;
        pendingMessage = null;
        if (pending != null) speak(pending);
    }

    private void speak(String message) {
        if (!ready || speech == null || message == null || message.isEmpty()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                speech.speak(message, TextToSpeech.QUEUE_FLUSH, null, "dgt-fixed-" + announcedId);
            } else {
                speech.speak(message, TextToSpeech.QUEUE_FLUSH, null);
            }
            AppSessionLog.event("RADARES DGT", "Locución emitida · " + message);
        } catch (Exception error) {
            AppSessionLog.event("RADARES DGT", "Locución fallida · " + error.getClass().getSimpleName());
        }
    }

    static String messageFor(RadarRepository.Alert alert, SpeedLimitRepository.Match limit) {
        if (alert == null || !"FIJO".equalsIgnoreCase(alert.type)) return "";
        // A blue classification guidance is not a statutory radar limit; do not voice it as one.
        if (limit != null && limit.exact && limit.limitKmh > 0) {
            return "Atención. Radar fijo. Límite " + limit.limitKmh + " kilómetros por hora.";
        }
        return "Atención. Radar fijo.";
    }
}
