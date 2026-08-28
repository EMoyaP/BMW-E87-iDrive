package com.ug.e87idrive;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One-shot, local voice warning for a DGT fixed camera currently shown on the dashboard.
 *
 * Speech is pre-rendered into small OGG assets, so it neither carries a neural model in the APK
 * nor depends on a TTS engine installed by the radio. It asks Android for a short MAY_DUCK focus
 * window so compatible media apps lower their volume while the warning is audible. It sends no
 * OEM command and deliberately ignores section/mobile controls.
 */
final class RadarSpeechAnnouncer {
    static final String PREFERENCE_ENABLED = "dgt_fixed_radar_voice";
    static final double REMINDER_DISTANCE_METERS = 300d;
    private static final long FOCUS_HOLD_MS = 5_000L;

    private final Context context;
    private final SharedPreferences preferences;
    private final SoundPool soundPool;
    private final AudioManager audioManager;
    private final AudioAttributes audioAttributes;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioManager.OnAudioFocusChangeListener legacyFocusListener = focusChange -> { };
    private final Runnable releaseAudioFocus = this::abandonTransientAudioFocus;
    private final Map<Integer, Integer> soundIds = new HashMap<>();
    private final Set<Integer> loadedResources = new HashSet<>();
    private String announcedId;
    private boolean reminderAnnounced;
    private int pendingResource;
    private boolean pendingReminder;
    private AudioFocusRequest audioFocusRequest;
    private boolean audioFocusHeld;

    RadarSpeechAnnouncer(Context context, SharedPreferences preferences) {
        this.context = context.getApplicationContext();
        this.preferences = preferences;
        audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttributes)
                    .setOnAudioFocusChangeListener(legacyFocusListener)
                    .build();
        }
        soundPool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(audioAttributes).build();
        soundPool.setOnLoadCompleteListener((pool, soundId, status) -> {
            Integer resource = resourceForSoundId(soundId);
            if (status != 0 || resource == null) {
                AppSessionLog.event("RADARES DGT", "No se pudo preparar audio local · status=" + status);
                return;
            }
            loadedResources.add(resource);
            if (pendingResource == resource) {
                pendingResource = 0;
                boolean reminder = pendingReminder;
                pendingReminder = false;
                play(resource, reminder);
            }
        });
        preload();
    }

    void onAlert(RadarRepository.Alert alert, SpeedLimitRepository.Match limit) {
        if (alert == null || !"FIJO".equalsIgnoreCase(alert.type)) {
            onAlertCleared();
            return;
        }
        if (!preferences.getBoolean(PREFERENCE_ENABLED, true)) return;
        if (!alert.id.equals(announcedId)) {
            announcedId = alert.id;
            // When the dashboard is opened inside the reminder radius, the first notice is
            // already sufficient; do not play the same phrase twice in immediate succession.
            reminderAnnounced = alert.distanceMeters <= REMINDER_DISTANCE_METERS;
            announce(alert, limit, false);
            return;
        }
        if (shouldIssueReminder(alert, reminderAnnounced)) {
            reminderAnnounced = true;
            announce(alert, limit, true);
        }
    }

    private void announce(RadarRepository.Alert alert, SpeedLimitRepository.Match limit, boolean reminder) {
        int resource = resourceFor(limit);
        if (loadedResources.contains(resource)) play(resource, reminder);
        else {
            pendingResource = resource;
            pendingReminder = reminder;
            AppSessionLog.event("RADARES DGT", (reminder
                    ? "Recordatorio de voz local pendiente · 300 m · "
                    : "Aviso inicial de voz local pendiente · ") + messageFor(alert, limit));
        }
    }

    void onAlertCleared() {
        announcedId = null;
        reminderAnnounced = false;
        pendingResource = 0;
        pendingReminder = false;
    }

    void stop() {
        pendingResource = 0;
        pendingReminder = false;
        handler.removeCallbacks(releaseAudioFocus);
        abandonTransientAudioFocus();
    }

    void close() {
        stop();
        try { soundPool.release(); } catch (Exception ignored) { }
    }

    private void preload() {
        int[] resources = {R.raw.radar_fijo_generico, R.raw.radar_fijo_30, R.raw.radar_fijo_40,
                R.raw.radar_fijo_50, R.raw.radar_fijo_60, R.raw.radar_fijo_70,
                R.raw.radar_fijo_80, R.raw.radar_fijo_90, R.raw.radar_fijo_100,
                R.raw.radar_fijo_110, R.raw.radar_fijo_120};
        for (int resource : resources) soundIds.put(resource, soundPool.load(context, resource, 1));
    }

    private Integer resourceForSoundId(int soundId) {
        for (Map.Entry<Integer, Integer> entry : soundIds.entrySet()) {
            if (entry.getValue() == soundId) return entry.getKey();
        }
        return null;
    }

    private void play(int resource, boolean reminder) {
        Integer soundId = soundIds.get(resource);
        if (soundId == null) return;
        try {
            requestTransientAudioFocus();
            int stream = soundPool.play(soundId, 1f, 1f, 1, 0, 1f);
            String label = reminder ? "Recordatorio de voz local emitido · 300 m" : "Aviso inicial de voz local emitido";
            AppSessionLog.event("RADARES DGT", stream == 0 ? "Audio local no reproducido · recurso=" + resource
                    : label + " · recurso=" + resource);
        } catch (Exception error) {
            AppSessionLog.event("RADARES DGT", "Audio local fallido · " + error.getClass().getSimpleName());
        }
    }

    private void requestTransientAudioFocus() {
        if (audioManager == null) return;
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            result = audioManager.requestAudioFocus(legacyFocusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        }
        audioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        handler.removeCallbacks(releaseAudioFocus);
        if (audioFocusHeld) {
            handler.postDelayed(releaseAudioFocus, FOCUS_HOLD_MS);
            AppSessionLog.event("RADARES DGT", "Foco transitorio solicitado para aviso de radar");
        } else {
            AppSessionLog.event("RADARES DGT", "Foco transitorio no concedido; aviso mezclado sin atenuación");
        }
    }

    private void abandonTransientAudioFocus() {
        if (!audioFocusHeld || audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(legacyFocusListener);
        }
        audioFocusHeld = false;
    }

    private static int resourceFor(SpeedLimitRepository.Match limit) {
        if (limit == null || !limit.exact) return R.raw.radar_fijo_generico;
        switch (limit.limitKmh) {
            case 30: return R.raw.radar_fijo_30;
            case 40: return R.raw.radar_fijo_40;
            case 50: return R.raw.radar_fijo_50;
            case 60: return R.raw.radar_fijo_60;
            case 70: return R.raw.radar_fijo_70;
            case 80: return R.raw.radar_fijo_80;
            case 90: return R.raw.radar_fijo_90;
            case 100: return R.raw.radar_fijo_100;
            case 110: return R.raw.radar_fijo_110;
            case 120: return R.raw.radar_fijo_120;
            default: return R.raw.radar_fijo_generico;
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

    static boolean shouldIssueReminder(RadarRepository.Alert alert, boolean reminderAlreadyIssued) {
        return alert != null
                && "FIJO".equalsIgnoreCase(alert.type)
                && !reminderAlreadyIssued
                && alert.trajectoryConfirmed
                && !alert.passageMarginActive
                && Double.isFinite(alert.distanceMeters)
                && alert.distanceMeters <= REMINDER_DISTANCE_METERS;
    }
}
