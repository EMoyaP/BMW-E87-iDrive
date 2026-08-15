package com.ug.e87idrive;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.content.res.Configuration;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Passive, read-only discovery for an unknown JCRK01/CYA Android build. */
public final class DiagnosticEngine {
    private static final int MAX_EVENTS = 500;
    private final Context context;
    private final Map<String, String> observed = new LinkedHashMap<>();
    private final List<DiagnosticEvent> events = new ArrayList<>();
    private BroadcastReceiver receiver;
    private ContentObserver settingsObserver;
    private CorrelationSession correlation;
    private String platformVehicleReport = "PLATAFORMA ANDROID AUTOMOTIVE\nSonda aún no ejecutada.\n";
    private volatile String inventoryReport;
    private volatile boolean inventoryScanning;

    // Heuristic actions only. The app never broadcasts any of them.
    private static final String[] CANDIDATE_ACTIONS = {
            "com.microntek.canbusbackview", "com.microntek.bootcheck", "com.microntek.irkeyDown",
            "com.microntek.eqchange", "com.microntek.VOLUME_CHANGED", "com.microntek.POWER_KEY",
            "com.microntek.canbus", "com.szchoiceway.eventcenter.EventUtils", "com.zjinnova.zlink",
            "com.syu.ms", "com.syu.bt"
    };
    private static final String[] NEEDLES = {
            "canbus", "canbox", "mcu", "vehicle", "radio", "bluetooth", "btmusic",
            "s-play", "zlink", "tlink", "jancar", "hiworld", "choiceway", "eventcenter", "cya",
            "jcrk", "syu", "fyt", "microntek", "climate", "aircondition", "backcar", "music"
    };
    private static final Map<String, List<String>> ROLE_NEEDLES = roleNeedles();

    public DiagnosticEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public void startPassiveProbe() {
        if (receiver == null) {
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    recordBroadcast(intent);
                }
            };
            IntentFilter filter = new IntentFilter();
            for (String action : CANDIDATE_ACTIONS) filter.addAction(action);
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(receiver, filter);
                }
            } catch (Exception ignored) {
                receiver = null;
            }
        }
        registerSettingsObserver();
        startInventoryScan();
    }

    public void stopPassiveProbe() {
        if (receiver != null) {
            try { context.unregisterReceiver(receiver); } catch (Exception ignored) {}
            receiver = null;
        }
        if (settingsObserver != null) {
            try { context.getContentResolver().unregisterContentObserver(settingsObserver); } catch (Exception ignored) {}
            settingsObserver = null;
        }
    }

    public synchronized void startCorrelation(String label) {
        correlation = new CorrelationSession(label, System.currentTimeMillis(), new LinkedHashMap<>(observed),
                snapshotRelevantSettings());
    }

    public synchronized String stopCorrelation() {
        if (correlation == null) return "No hay una sesión activa.";
        correlation.endedAt = System.currentTimeMillis();
        String report = correlationReport(correlation);
        correlation = null;
        return report;
    }

    public synchronized boolean isCorrelationRunning() { return correlation != null; }

    public synchronized String correlationState() {
        if (correlation == null) return "Sin sesión de correlación activa.";
        return "Capturando «" + correlation.label + "» desde " + formatTime(correlation.startedAt)
                + ". Haz la prueba con el vehículo detenido y pulsa DETENER.";
    }

    public String getObservedValue(String key) {
        // Deliberately empty until a real JCRK01/CYA mapping is identified on this unit.
        return observed.get("vehicle." + key);
    }

    public synchronized String buildReport() {
        StringBuilder out = new StringBuilder(16_000);
        out.append("BMW E87 iDrive — diagnóstico pasivo JCRK01/CYA\n");
        out.append("Generado: ").append(formatTime(System.currentTimeMillis())).append('\n');
        out.append("Build.MANUFACTURER=").append(Build.MANUFACTURER).append('\n');
        out.append("Build.MODEL=").append(Build.MODEL).append('\n');
        out.append("Build.DEVICE=").append(Build.DEVICE).append('\n');
        out.append("Build.PRODUCT=").append(Build.PRODUCT).append('\n');
        out.append("Build.BOARD=").append(Build.BOARD).append('\n');
        out.append("SDK=").append(Build.VERSION.SDK_INT).append("  Android=")
                .append(Build.VERSION.RELEASE).append("  app=").append(context.getPackageName()).append("\n\n");

        appendRuntimeProfile(out);

        out.append(platformVehicleReport).append('\n');

        String inventory = inventoryReport;
        if (inventory == null) {
            startInventoryScan();
            out.append("INVENTARIO DE COMPONENTES\nPreparándose en segundo plano; vuelve a abrir o exporta en unos segundos.\n");
        } else {
            out.append(inventory);
        }

        out.append("\nEVENTOS OBSERVADOS (últimos valores)\n");
        if (observed.isEmpty()) out.append("(ninguno todavía)\n");
        for (Map.Entry<String, String> entry : observed.entrySet()) {
            out.append(entry.getKey()).append(" = ").append(entry.getValue()).append('\n');
        }

        out.append("\nACCIONES PASIVAS ESCUCHADAS\n");
        for (String action : CANDIDATE_ACTIONS) out.append(action).append('\n');
        if (correlation != null) {
            out.append("\nSESIÓN EN CURSO\n").append(correlationState()).append('\n');
        }
        out.append("\nNOTA DE SEGURIDAD\n");
        out.append("Este módulo solo lee metadatos Android y recibe broadcasts pasivamente.\n");
        out.append("No escribe CAN/UART, no transmite broadcasts propietarios y no cambia MCU/Hiworld.\n");
        return out.toString();
    }

    public synchronized void setPlatformVehicleReport(String report) {
        if (report != null && !report.trim().isEmpty()) platformVehicleReport = report;
    }

    private void recordBroadcast(Intent intent) {
        long now = System.currentTimeMillis();
        String action = intent.getAction() == null ? "(sin acción)" : intent.getAction();
        Map<String, String> values = new LinkedHashMap<>();
        Bundle extras = intent.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                Object value;
                try { value = extras.get(key); } catch (Exception e) { value = "<error>"; }
                if (value != null) values.put(key, compactValue(value));
            }
        }
        synchronized (this) {
            observed.put("last_action", action);
            observed.put("last_timestamp", formatTime(now));
            for (Map.Entry<String, String> entry : values.entrySet()) {
                observed.put("broadcast." + entry.getKey(), entry.getValue());
            }
            events.add(new DiagnosticEvent(now, action, values));
            while (events.size() > MAX_EVENTS) events.remove(0);
        }
    }

    private void registerSettingsObserver() {
        if (settingsObserver != null) return;
        settingsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange, Uri uri) { recordSettingChange(uri); }
        };
        try { context.getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, settingsObserver); }
        catch (Exception ignored) {}
        try { context.getContentResolver().registerContentObserver(Settings.Global.CONTENT_URI, true, settingsObserver); }
        catch (Exception ignored) {}
    }

    private void recordSettingChange(Uri uri) {
        if (uri == null) return;
        String key = uri.getLastPathSegment();
        if (key == null || !interestingSetting(key)) return;
        String namespace = uri.toString().startsWith(Settings.Global.CONTENT_URI.toString()) ? "global" : "system";
        String value = null;
        try {
            value = "global".equals(namespace) ? Settings.Global.getString(context.getContentResolver(), key)
                    : Settings.System.getString(context.getContentResolver(), key);
        } catch (Exception ignored) {}
        long now = System.currentTimeMillis();
        Map<String, String> extras = new LinkedHashMap<>();
        extras.put("key", key);
        extras.put("value", value == null ? "(no legible)" : compactValue(value));
        synchronized (this) {
            observed.put("settings.last_change", namespace + "." + key);
            observed.put("settings.last_value", extras.get("value"));
            observed.put("settings.last_timestamp", formatTime(now));
            events.add(new DiagnosticEvent(now, "settings." + namespace, extras));
            while (events.size() > MAX_EVENTS) events.remove(0);
        }
    }

    private Map<String, String> snapshotRelevantSettings() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        appendSettings(snapshot, "system", Settings.System.CONTENT_URI);
        appendSettings(snapshot, "global", Settings.Global.CONTENT_URI);
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        snapshot.put("runtime.ui_mode_night", String.valueOf(night));
        try { snapshot.put("runtime.screen_brightness", String.valueOf(Settings.System.getInt(
                context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS))); } catch (Exception ignored) {}
        try { snapshot.put("runtime.screen_brightness_mode", String.valueOf(Settings.System.getInt(
                context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE))); } catch (Exception ignored) {}
        return snapshot;
    }

    private void appendSettings(Map<String, String> target, String namespace, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{"name", "value"}, null, null, null)) {
            if (cursor == null) return;
            int nameColumn = cursor.getColumnIndex("name");
            int valueColumn = cursor.getColumnIndex("value");
            int count = 0;
            while (cursor.moveToNext() && count < 1_000) {
                String name = nameColumn < 0 ? null : cursor.getString(nameColumn);
                if (name == null || !interestingSetting(name)) continue;
                String value = valueColumn < 0 ? null : cursor.getString(valueColumn);
                target.put(namespace + "." + name, compactValue(value == null ? "(null)" : value));
                count++;
            }
        } catch (Exception ignored) {}
    }

    private boolean interestingSetting(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String[] tokens = {"bright", "light", "night", "illum", "lamp", "park", "brake", "door",
                "belt", "reverse", "backcar", "vehicle", "car_", "mcu", "canbus", "headlight"};
        for (String token : tokens) if (lower.contains(token)) return true;
        return false;
    }

    private void startInventoryScan() {
        synchronized (this) {
            if (inventoryReport != null || inventoryScanning) return;
            inventoryScanning = true;
        }
        Thread scan = new Thread(() -> {
            try {
                StringBuilder out = new StringBuilder(24_000);
                appendPackages(out);
                out.append("\nCANDIDATOS HEURÍSTICOS POR ROL\n");
                appendCandidates(out);
                inventoryReport = out.toString();
            } catch (Throwable error) {
                inventoryReport = "INVENTARIO DE COMPONENTES\nError durante el análisis: "
                        + error.getClass().getSimpleName() + "\n";
            } finally {
                inventoryScanning = false;
            }
        }, "e87-package-inventory");
        scan.setPriority(Thread.MIN_PRIORITY);
        scan.start();
    }

    private void appendRuntimeProfile(StringBuilder out) {
        out.append("RECURSOS Y RENDIMIENTO DE ESTA UNIDAD\n");
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memory);
            out.append("RAM física total=").append(formatMiB(memory.totalMem))
                    .append("  disponible=").append(formatMiB(memory.availMem))
                    .append("  memoria baja=").append(memory.lowMemory).append('\n');
            out.append("Límite heap por app=").append(manager.getMemoryClass()).append(" MiB")
                    .append("  low-RAM-device=").append(manager.isLowRamDevice()).append('\n');
        }
        Runtime runtime = Runtime.getRuntime();
        long javaUsed = runtime.totalMemory() - runtime.freeMemory();
        out.append("Heap Java usado=").append(formatMiB(javaUsed))
                .append("  máximo=").append(formatMiB(runtime.maxMemory())).append('\n');
        out.append("PSS proceso en este instante=").append(formatKiB(Debug.getPss()))
                .append("  CPU lógicas=").append(runtime.availableProcessors()).append('\n');
        try {
            long apkBytes = new java.io.File(context.getApplicationInfo().sourceDir).length();
            out.append("APK instalado=").append(formatMiB(apkBytes)).append('\n');
        } catch (Exception ignored) {}
        out.append("ABI=").append(Arrays.toString(Build.SUPPORTED_ABIS)).append("\n\n");
    }

    private static String formatMiB(long bytes) {
        return String.format(Locale.ROOT, "%.1f MiB", bytes / 1_048_576.0);
    }

    private static String formatKiB(long kibibytes) {
        return String.format(Locale.ROOT, "%.1f MiB", kibibytes / 1024.0);
    }

    private void appendPackages(StringBuilder out) {
        out.append("APLICACIONES Y COMPONENTES RELEVANTES\n");
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> packages;
        try {
            packages = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                    | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS | PackageManager.GET_META_DATA);
        } catch (Exception e) {
            out.append("No se pudo enumerar paquetes: ").append(e).append('\n');
            return;
        }
        Collections.sort(packages, (a, b) -> a.packageName.compareToIgnoreCase(b.packageName));
        int relevant = 0;
        for (PackageInfo pkg : packages) {
            CharSequence label;
            try { label = pm.getApplicationLabel(pkg.applicationInfo); } catch (Exception e) { label = "?"; }
            int score = relevanceScore((pkg.packageName + " " + label).toLowerCase(Locale.ROOT));
            List<String> components = new ArrayList<>();
            score += collectRelevantComponents(components, "activity", pkg.activities);
            score += collectRelevantComponents(components, "service", pkg.services);
            score += collectRelevantComponents(components, "receiver", pkg.receivers);
            score += collectRelevantComponents(components, "provider", pkg.providers);
            if (score == 0) continue;
            relevant++;
            out.append("\n- ").append(pkg.packageName).append(" [").append(label).append("] score=").append(score).append('\n');
            for (String component : components) out.append(component).append('\n');
            appendIntentFilters(out, pm, pkg.packageName);
            if (relevant >= 100) { out.append("Límite de 100 paquetes relevantes alcanzado.\n"); break; }
        }
        out.append("Total instalados visibles=").append(packages.size()).append(", relevantes=").append(relevant).append('\n');
    }

    private int collectRelevantComponents(List<String> out, String type, Object[] components) {
        if (components == null) return 0;
        int score = 0;
        for (Object component : components) {
            String name = componentName(component);
            int componentScore = relevanceScore(name.toLowerCase(Locale.ROOT));
            if (componentScore == 0) continue;
            score += componentScore;
            if (out.size() < 40) out.add("  " + type + ": " + name);
        }
        return score;
    }

    private String componentName(Object component) {
        if (component instanceof ActivityInfo) return ((ActivityInfo) component).name;
        if (component instanceof ServiceInfo) return ((ServiceInfo) component).name;
        if (component instanceof ProviderInfo) return ((ProviderInfo) component).name;
        return String.valueOf(component);
    }

    private void appendIntentFilters(StringBuilder out, PackageManager pm, String packageName) {
        try {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName);
            for (ResolveInfo info : pm.queryIntentActivities(query, PackageManager.GET_RESOLVED_FILTER)) {
                if (info.filter != null) out.append("  launcher-filter: ").append(info.filter).append('\n');
            }
        } catch (Exception ignored) {}
    }

    private void appendCandidates(StringBuilder out) {
        PackageManager pm = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities;
        try { activities = pm.queryIntentActivities(query, PackageManager.GET_META_DATA); }
        catch (Exception e) { out.append("No se pudieron calcular candidatos: ").append(e).append('\n'); return; }
        for (Map.Entry<String, List<String>> role : ROLE_NEEDLES.entrySet()) {
            List<String> matches = new ArrayList<>();
            for (ResolveInfo info : activities) {
                String haystack = (String.valueOf(info.loadLabel(pm)) + " " + info.activityInfo.packageName + " "
                        + info.activityInfo.name).toLowerCase(Locale.ROOT);
                int score = scoreFor(haystack, role.getValue());
                if (score > 0) matches.add(score + " — " + info.activityInfo.packageName + "/" + info.activityInfo.name
                        + " [" + info.loadLabel(pm) + "]");
            }
            Collections.sort(matches, (a, b) -> Integer.compare(parseScore(b), parseScore(a)));
            out.append(role.getKey()).append(": ").append(matches.isEmpty() ? "(ninguno)" : String.join(" | ", matches)).append('\n');
        }
    }

    private int relevanceScore(String haystack) {
        int score = 0;
        for (String needle : NEEDLES) if (haystack.contains(needle)) score += 2;
        return score;
    }

    private static int scoreFor(String haystack, List<String> needles) {
        int score = 0;
        for (String needle : needles) if (haystack.contains(needle)) score += 5;
        return score;
    }

    private static int parseScore(String value) {
        try { return Integer.parseInt(value.substring(0, value.indexOf(' '))); } catch (Exception e) { return 0; }
    }

    private synchronized String correlationReport(CorrelationSession session) {
        StringBuilder out = new StringBuilder();
        out.append("SESIÓN DE CORRELACIÓN: ").append(session.label).append('\n');
        out.append("Inicio: ").append(formatTime(session.startedAt)).append('\n');
        out.append("Fin: ").append(formatTime(session.endedAt)).append("\n\n");
        out.append("CAMBIOS EN EL SNAPSHOT OBSERVABLE\n");
        boolean changed = false;
        for (Map.Entry<String, String> entry : observed.entrySet()) {
            String oldValue = session.baseline.get(entry.getKey());
            if (!entry.getValue().equals(oldValue)) {
                changed = true;
                out.append(entry.getKey()).append(": ").append(oldValue == null ? "(ausente)" : oldValue)
                        .append(" -> ").append(entry.getValue()).append('\n');
            }
        }
        if (!changed) out.append("(sin cambios)\n");
        out.append("\nEVENTOS DURANTE LA SESIÓN\n");
        int count = 0;
        for (DiagnosticEvent event : events) {
            if (event.timestamp < session.startedAt || event.timestamp > session.endedAt) continue;
            out.append(formatTime(event.timestamp)).append(" | ").append(event.action);
            if (!event.extras.isEmpty()) out.append(" | ").append(event.extras);
            out.append('\n');
            count++;
        }
        if (count == 0) out.append("(ninguno)\n");
        out.append("\nCAMBIOS EN AJUSTES ANDROID RELEVANTES\n");
        Map<String, String> currentSettings = snapshotRelevantSettings();
        boolean settingChanged = false;
        for (Map.Entry<String, String> entry : currentSettings.entrySet()) {
            String oldValue = session.settingsBaseline.get(entry.getKey());
            if (!entry.getValue().equals(oldValue)) {
                settingChanged = true;
                out.append(entry.getKey()).append(": ").append(oldValue == null ? "(ausente)" : oldValue)
                        .append(" -> ").append(entry.getValue()).append('\n');
            }
        }
        if (!settingChanged) out.append("(sin cambios relevantes)\n");
        out.append("\nInterpretación: los cambios son observaciones Android, no una prueba de que exista una API CAN.\n");
        return out.toString();
    }

    private static String compactValue(Object value) {
        String text = String.valueOf(value).replace('\n', ' ');
        return text.length() > 240 ? text.substring(0, 240) + "…" : text;
    }

    private static String formatTime(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(millis));
    }

    private static Map<String, List<String>> roleNeedles() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("Multimedia", Arrays.asList("music", "multimedia", "player", "spotify"));
        map.put("Radio", Arrays.asList("radio", "fm", "tuner"));
        map.put("Navegación", Arrays.asList("maps", "navigation", "navi", "waze", "here"));
        map.put("Android Auto / S-Play", Arrays.asList("s-play", "splay", "zlink", "tlink", "carlink", "autokit", "android auto", "easyconnection", "carbitlink", "phonelink"));
        map.put("Teléfono / Bluetooth", Arrays.asList("bluetooth", "btmusic", "bt music", "phone", "dialer", "phonelink"));
        return map;
    }

    private static final class DiagnosticEvent {
        final long timestamp;
        final String action;
        final Map<String, String> extras;

        DiagnosticEvent(long timestamp, String action, Map<String, String> extras) {
            this.timestamp = timestamp;
            this.action = action;
            this.extras = extras;
        }
    }

    private static final class CorrelationSession {
        final String label;
        final long startedAt;
        final Map<String, String> baseline;
        final Map<String, String> settingsBaseline;
        long endedAt;

        CorrelationSession(String label, long startedAt, Map<String, String> baseline,
                           Map<String, String> settingsBaseline) {
            this.label = label;
            this.startedAt = startedAt;
            this.baseline = baseline;
            this.settingsBaseline = settingsBaseline;
        }
    }
}
