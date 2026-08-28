package com.ug.e87idrive;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded, privacy-conscious log for the current app process / vehicle session. */
final class AppSessionLog {
    static final String FILE_NAME = "e87_runtime_session_000.log";
    private static final String FILE_PREFIX = "e87_runtime_session_";
    private static final long CHUNK_BYTES = 1L * 1024L * 1024L;
    private static final int MAX_CHUNKS_PER_SESSION = 8;
    private static final Object LOCK = new Object();
    /**
     * The CAN service can callback several times per second with identical
     * parcels. Keep the session log useful on the head unit by allowing those
     * high-volume raw samples at a controlled cadence. Semantic field changes
     * are still recorded separately by {@link VehicleObservationTrace}.
     */
    private static final Map<String, Long> LAST_SAMPLED_AT = new HashMap<>();
    private static File file;
    private static int chunkIndex;
    private static boolean sessionCapacityReached;

    private AppSessionLog() { }

    static void initialize(Context context) {
        synchronized (LOCK) {
            VehicleObservationTrace.reset();
            LAST_SAMPLED_AT.clear();
            clearPreviousSession(context.getFilesDir());
            chunkIndex = 0;
            sessionCapacityReached = false;
            file = chunkFile(context.getFilesDir(), chunkIndex);
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
            if (file == null || sessionCapacityReached) return;
            String line = timestamp() + " [" + source + "] "
                    + message.replace('\n', ' ').replace('\r', ' ') + "\n";
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            if (file.length() + bytes.length > CHUNK_BYTES) rotateChunk();
            if (!sessionCapacityReached) write(line, true);
        }
    }

    /**
     * Records a noisy raw diagnostic sample no more than once per interval.
     * This intentionally does not compare the message: OEM dashboard parcels
     * often contain a changing but untrusted counter/sentinel, which would
     * otherwise fill the diagnostic export during a short drive.
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
            StringBuilder joined = new StringBuilder();
            for (File part : sessionFilesLocked()) joined.append(readFile(part));
            return joined.toString();
        }
    }

    /** All chunks from the current app/vehicle session, in chronological order. */
    static List<File> sessionFiles() {
        synchronized (LOCK) { return new ArrayList<>(sessionFilesLocked()); }
    }

    static String readFile(File source) {
        if (source == null || !source.isFile()) return "";
        try (FileInputStream input = new FileInputStream(source)) {
            byte[] data = new byte[(int) Math.min(CHUNK_BYTES + 512L, source.length())];
            int offset = 0, count;
            while (offset < data.length
                    && (count = input.read(data, offset, data.length - offset)) > 0) offset += count;
            return new String(data, 0, offset, StandardCharsets.UTF_8);
        } catch (Exception ignored) { return ""; }
    }

    static File file() { synchronized (LOCK) { return file; } }

    private static void rotateChunk() {
        if (file == null) return;
        if (chunkIndex + 1 >= MAX_CHUNKS_PER_SESSION) {
            write("LÍMITE DE SESIÓN: se conservaron " + MAX_CHUNKS_PER_SESSION
                    + " archivos de 1 MiB; se detiene el registro para no sobrescribir datos.\n", true);
            sessionCapacityReached = true;
            return;
        }
        chunkIndex++;
        file = chunkFile(file.getParentFile(), chunkIndex);
        write("BMW E87 iDrive · CONTINUACIÓN DE REGISTRO · parte " + (chunkIndex + 1)
                + " de " + MAX_CHUNKS_PER_SESSION + "\nInicio=" + timestamp() + "\n\n", false);
    }

    private static File chunkFile(File directory, int index) {
        return new File(directory, String.format(Locale.ROOT, "%s%03d.log", FILE_PREFIX, index));
    }

    private static void clearPreviousSession(File directory) {
        File[] previous = directory.listFiles(candidate -> candidate != null
                && (candidate.getName().equals("e87_runtime_session.log")
                || (candidate.getName().startsWith(FILE_PREFIX)
                && candidate.getName().endsWith(".log"))));
        if (previous == null) return;
        for (File candidate : previous) {
            try { candidate.delete(); } catch (Exception ignored) { }
        }
    }

    private static List<File> sessionFilesLocked() {
        if (file == null || file.getParentFile() == null) return new ArrayList<>();
        File[] parts = file.getParentFile().listFiles(candidate -> candidate != null
                && candidate.getName().startsWith(FILE_PREFIX) && candidate.getName().endsWith(".log"));
        if (parts == null || parts.length == 0) return new ArrayList<>();
        Arrays.sort(parts, (left, right) -> left.getName().compareTo(right.getName()));
        return new ArrayList<>(Arrays.asList(parts));
    }

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
