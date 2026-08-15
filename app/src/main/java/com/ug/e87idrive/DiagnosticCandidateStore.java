package com.ug.e87idrive;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Persistent evidence notebook for the visual diagnostic assistant.
 * Entries are observations only: this store never activates a vehicle mapping.
 */
final class DiagnosticCandidateStore {
    private static final int FORMAT_VERSION = 1;
    private static final int MIN_SCORE = 45;
    private static final int MAX_ENTRIES = 200;
    private static final int MAX_SESSIONS = 20;
    private static final int MAX_LABELS = 16;
    private static final int MAX_VALUES = 16;
    private static final int MAX_TEXT = 240;

    private final AtomicFile file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private String lastError = "";

    DiagnosticCandidateStore(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "vehicle_signal_candidates.json"));
        load();
    }

    synchronized void record(String plan, String step, long sessionId,
                             List<DiagnosticEngine.LiveCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return;
        boolean changed = false;
        long now = System.currentTimeMillis();
        String session = String.valueOf(sessionId);
        for (DiagnosticEngine.LiveCandidate candidate : candidates) {
            if (candidate.score() < MIN_SCORE) continue;
            String key = clipped(candidate.key());
            String id = clipped(plan) + "\u001f" + key;
            Entry entry = entries.get(id);
            if (entry == null) {
                entry = new Entry();
                entry.plan = clipped(plan);
                entry.key = key;
                entry.firstSeenAt = now;
                entries.put(id, entry);
            }
            entry.source = clipped(candidate.source());
            entry.lastSeenAt = now;
            entry.observations++;
            entry.lastScore = candidate.score();
            entry.maxScore = Math.max(entry.maxScore, candidate.score());
            entry.lastConfidence = clipped(candidate.confidence());
            entry.lastReason = clipped(candidate.reason());
            addBounded(entry.sessions, session, MAX_SESSIONS);
            if ("FUERTE".equals(candidate.confidence())) {
                addBounded(entry.strongSessions, session, MAX_SESSIONS);
            }
            addBounded(entry.steps, clipped(step), MAX_LABELS);
            addBounded(entry.values, clipped(candidate.baseline()), MAX_VALUES);
            addBounded(entry.values, clipped(candidate.current()), MAX_VALUES);
            changed = true;
        }
        trimEntries();
        if (changed) save();
    }

    synchronized int size() { return entries.size(); }

    synchronized void clear() {
        entries.clear();
        lastError = "";
        try {
            file.delete();
        } catch (Exception error) {
            lastError = "No se pudo borrar el registro: " + error.getClass().getSimpleName();
        }
    }

    synchronized String buildReport() {
        StringBuilder out = new StringBuilder(8_000);
        out.append("CANDIDATOS GUARDADOS EN LA APP (NO SON MAPEOS ACTIVOS)\n");
        out.append("Solo se conservan candidatos medios o fuertes. Ninguno controla la UI ni escribe en CAN/MCU.\n");
        if (!lastError.isEmpty()) out.append("Aviso de almacenamiento: ").append(lastError).append('\n');
        if (entries.isEmpty()) {
            out.append("(ninguno todavía)\n");
            return out.toString();
        }
        List<Entry> sorted = new ArrayList<>(entries.values());
        Collections.sort(sorted, (first, second) -> {
            int byStatus = Integer.compare(statusRank(second), statusRank(first));
            if (byStatus != 0) return byStatus;
            int byScore = Integer.compare(second.maxScore, first.maxScore);
            return byScore != 0 ? byScore : Long.compare(second.lastSeenAt, first.lastSeenAt);
        });
        int position = 1;
        for (Entry entry : sorted) {
            out.append('\n').append(position++).append(". [").append(statusFor(
                    entry.sessions.size(), entry.strongSessions.size())).append("] ")
                    .append(entry.key).append('\n');
            out.append("   prueba=").append(entry.plan)
                    .append("  sesiones=").append(entry.sessions.size())
                    .append("  fuertes=").append(entry.strongSessions.size())
                    .append("  observaciones=").append(entry.observations).append('\n');
            out.append("   puntuación última/máxima=").append(entry.lastScore).append('/')
                    .append(entry.maxScore).append("  fuente=").append(entry.source).append('\n');
            out.append("   pasos=").append(entry.steps).append("  valores=").append(entry.values).append('\n');
            out.append("   primera=").append(formatTime(entry.firstSeenAt))
                    .append("  última=").append(formatTime(entry.lastSeenAt)).append('\n');
            out.append("   criterio reciente=").append(entry.lastReason).append('\n');
        }
        out.append("\nLISTO PARA REVISAR exige al menos tres sesiones distintas con evidencia fuerte; ")
                .append("todavía requiere validar el significado en la unidad física.\n");
        return out.toString();
    }

    static String statusFor(int sessionCount, int strongSessionCount) {
        if (strongSessionCount >= 3) return "LISTO PARA REVISAR";
        if (sessionCount >= 2 || strongSessionCount >= 2) return "REPETIDO";
        return "OBSERVADO";
    }

    private static int statusRank(Entry entry) {
        if (entry.strongSessions.size() >= 3) return 3;
        if (entry.sessions.size() >= 2 || entry.strongSessions.size() >= 2) return 2;
        return 1;
    }

    private void load() {
        if (!file.getBaseFile().exists()) return;
        try {
            String json = new String(file.readFully(), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(json);
            JSONArray stored = root.optJSONArray("entries");
            if (stored == null) return;
            for (int index = 0; index < stored.length() && entries.size() < MAX_ENTRIES; index++) {
                JSONObject value = stored.optJSONObject(index);
                if (value == null) continue;
                Entry entry = Entry.fromJson(value);
                if (entry.key.isEmpty() || entry.plan.isEmpty()) continue;
                entries.put(entry.plan + "\u001f" + entry.key, entry);
            }
        } catch (Exception error) {
            entries.clear();
            lastError = "Registro anterior ilegible; se conservará intacto hasta la próxima captura: "
                    + error.getClass().getSimpleName();
        }
    }

    private void save() {
        FileOutputStream stream = null;
        try {
            JSONObject root = new JSONObject();
            root.put("format", FORMAT_VERSION);
            JSONArray stored = new JSONArray();
            for (Entry entry : entries.values()) stored.put(entry.toJson());
            root.put("entries", stored);
            stream = file.startWrite();
            stream.write(root.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
            lastError = "";
        } catch (Exception error) {
            if (stream != null) file.failWrite(stream);
            lastError = "No se pudo guardar el registro: " + error.getClass().getSimpleName();
        }
    }

    private void trimEntries() {
        while (entries.size() > MAX_ENTRIES) {
            String oldestKey = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, Entry> candidate : entries.entrySet()) {
                if (candidate.getValue().lastSeenAt < oldest) {
                    oldest = candidate.getValue().lastSeenAt;
                    oldestKey = candidate.getKey();
                }
            }
            if (oldestKey == null) return;
            entries.remove(oldestKey);
        }
    }

    private static void addBounded(Set<String> target, String value, int limit) {
        if (value == null || value.isEmpty() || "null".equals(value)) return;
        target.add(value);
        while (target.size() > limit) {
            Iterator<String> iterator = target.iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }

    private static String clipped(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text;
    }

    private static String formatTime(long millis) {
        if (millis <= 0) return "(desconocida)";
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(millis));
    }

    private static final class Entry {
        String plan = "";
        String key = "";
        String source = "";
        long firstSeenAt;
        long lastSeenAt;
        int observations;
        int maxScore;
        int lastScore;
        String lastConfidence = "";
        String lastReason = "";
        final Set<String> sessions = new LinkedHashSet<>();
        final Set<String> strongSessions = new LinkedHashSet<>();
        final Set<String> steps = new LinkedHashSet<>();
        final Set<String> values = new LinkedHashSet<>();

        JSONObject toJson() throws Exception {
            JSONObject out = new JSONObject();
            out.put("plan", plan);
            out.put("key", key);
            out.put("source", source);
            out.put("firstSeenAt", firstSeenAt);
            out.put("lastSeenAt", lastSeenAt);
            out.put("observations", observations);
            out.put("maxScore", maxScore);
            out.put("lastScore", lastScore);
            out.put("lastConfidence", lastConfidence);
            out.put("lastReason", lastReason);
            out.put("sessions", toJsonArray(sessions));
            out.put("strongSessions", toJsonArray(strongSessions));
            out.put("steps", toJsonArray(steps));
            out.put("values", toJsonArray(values));
            return out;
        }

        static Entry fromJson(JSONObject value) {
            Entry entry = new Entry();
            entry.plan = clipped(value.optString("plan"));
            entry.key = clipped(value.optString("key"));
            entry.source = clipped(value.optString("source"));
            entry.firstSeenAt = value.optLong("firstSeenAt");
            entry.lastSeenAt = value.optLong("lastSeenAt");
            entry.observations = Math.max(0, value.optInt("observations"));
            entry.maxScore = Math.max(0, value.optInt("maxScore"));
            entry.lastScore = Math.max(0, value.optInt("lastScore"));
            entry.lastConfidence = clipped(value.optString("lastConfidence"));
            entry.lastReason = clipped(value.optString("lastReason"));
            fromJsonArray(value.optJSONArray("sessions"), entry.sessions, MAX_SESSIONS);
            fromJsonArray(value.optJSONArray("strongSessions"), entry.strongSessions, MAX_SESSIONS);
            fromJsonArray(value.optJSONArray("steps"), entry.steps, MAX_LABELS);
            fromJsonArray(value.optJSONArray("values"), entry.values, MAX_VALUES);
            return entry;
        }

        private static JSONArray toJsonArray(Set<String> values) {
            JSONArray out = new JSONArray();
            for (String value : values) out.put(value);
            return out;
        }

        private static void fromJsonArray(JSONArray values, Set<String> target, int limit) {
            if (values == null) return;
            for (int index = 0; index < values.length() && target.size() < limit; index++) {
                addBounded(target, clipped(values.optString(index)), limit);
            }
        }
    }
}
