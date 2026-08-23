package com.ug.e87idrive;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;

/**
 * Low-volume trace of every read-only vehicle source.  Sentinel values are
 * deliberately retained: 0, -1, Integer.MIN_VALUE, null and unknown values
 * are evidence during a physical capture and must not be filtered out.
 */
final class VehicleObservationTrace {
    private static final Object LOCK = new Object();
    private static final Map<String, String> LAST = new LinkedHashMap<>();

    private VehicleObservationTrace() { }

    static void reset() {
        synchronized (LOCK) { LAST.clear(); }
    }

    static void observe(String source, String field, Object raw, Object interpreted) {
        String sourceText = clean(source, "unknown");
        String fieldText = clean(field, "unknown");
        String rawText = value(raw);
        String interpretedText = value(interpreted);
        String key = sourceText + "." + fieldText;
        String current = "bruto=" + rawText + " · interpretado=" + interpretedText;
        synchronized (LOCK) {
            String previous = LAST.put(key, current);
            if (current.equals(previous)) return;
            AppSessionLog.event("VEHICLE TRACE", "campo=" + fieldText
                    + " · fuente=" + sourceText
                    + " · hora=" + System.currentTimeMillis()
                    + " · anterior=" + (previous == null ? "(sin muestra)" : previous)
                    + " → nuevo=" + current);
        }
    }

    static void guided(String action, String detail) {
        AppSessionLog.event("USB DEBUG · GUIADO", clean(action, "evento")
                + " · " + clean(detail, "sin detalle"));
    }

    private static String value(Object value) {
        if (value == null) return "(null/desconocido)";
        String text;
        if (value instanceof int[]) text = Arrays.toString((int[]) value);
        else if (value instanceof long[]) text = Arrays.toString((long[]) value);
        else if (value instanceof float[]) text = Arrays.toString((float[]) value);
        else if (value instanceof double[]) text = Arrays.toString((double[]) value);
        else if (value instanceof boolean[]) text = Arrays.toString((boolean[]) value);
        else if (value instanceof byte[]) text = Arrays.toString((byte[]) value);
        else if (value instanceof Object[]) text = Arrays.deepToString((Object[]) value);
        else text = String.valueOf(value);
        text = text.replace('\n', ' ').replace('\r', ' ').trim();
        return text.isEmpty() ? "(vacío/desconocido)" : text;
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
