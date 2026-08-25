package com.ug.e87idrive;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded, privacy-conscious log for the current app process / vehicle session. */
final class AppSessionLog {
    static final String FILE_NAME = "e87_runtime_session.log";
    private static final long MAX_BYTES = 512L * 1024L;
    private static final Object LOCK = new Object();
    /**
     * The CAN service can callback several times per second with identical
     * parcels. Keep the session log useful on the head unit by allowing those
     * high-volume raw samples at a controlled cadence. Semantic field changes
     * are still recorded separately by {@link VehicleObservationTrace}.
     */
    private static final Map<String, Long> LAST_SAMPLED_AT = new HashMap<>();
    private static File file;

    private AppSessionLog() { }

    static void initialize(Context context) {
        synchronized (LOCK) {
            VehicleObservationTrace.reset();
            LAST_SAMPLED_AT.clear();
            file = new File(context.getFilesDir(), FILE_NAME);
            String header = "BMW E87 iDrive · REGISTRO DE SESIÓN\n"
                    + "Inicio=" + timestamp() + "\n"
                    + "Android SDK=" + Build.VERSION.SDK_INT + " · dispositivo="
                    + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                    + (GpsSpeedProvider.coordinateLoggingEnabled(context)
                    ? "Privacidad: coordenadas GPS habilitadas explícitamente para DEBUG.\n\n"
                    : "Privacidad: no se guardan coordenadas GPS.\n\n");
            write(header, false);
        }
    }

    static void event(String source, String message) {
        if (message == null || message.trim().isEmpty()) return;
        synchronized (LOCK) {
            if (file == null) return;
            if (file.length() >= MAX_BYTES) {
                write("REGISTRO REINICIADO POR LÍMITE DE 512 KiB · " + timestamp() + "\n", false);
            }
            write(timestamp() + " [" + source + "] "
                    + message.replace('\n', ' ').replace('\r', ' ') + "\n", true);
        }
    }

    /**
     * Records a noisy raw diagnostic sample no more than once per interval.
     * This intentionally does not compare the message: OEM dashboard parcels
     * often contain a changing but untrusted counter/sentinel, which would
     * otherwise fill the 512 KiB export during a short drive.
     */
    static void sampledEvent(String key, String source, String message, long intervalMs) {
        if (key == null || key.trim().isEmpty() || message == null || message.trim().isEmpty()) return;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            long previous = LAST_SAMPLED_AT.containsKey(key) ? LAST_SAMPLED_AT.get(key) : 0L;
            if (previous > 0L && now - previous < Math.max(1_000L, intervalMs)) return;
            LAST_SAMPLED_AT.put(key, now);
            event(source, message);
        }
    }

    static String read() {
        synchronized (LOCK) {
            if (file == null || !file.isFile()) return "";
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] data = new byte[(int) Math.min(MAX_BYTES, file.length())];
                int offset = 0, count;
                while (offset < data.length
                        && (count = input.read(data, offset, data.length - offset)) > 0) offset += count;
                return new String(data, 0, offset, StandardCharsets.UTF_8);
            } catch (Exception ignored) { return ""; }
        }
    }

    static File file() { synchronized (LOCK) { return file; } }

    private static void write(String text, boolean append) {
        if (file == null) return;
        try (FileOutputStream output = new FileOutputStream(file, append)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
    }
}
