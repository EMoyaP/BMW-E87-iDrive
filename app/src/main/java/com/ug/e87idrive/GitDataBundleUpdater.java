package com.ug.e87idrive;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Downloads and transactionally activates a desktop-built, hash-verified GitHub data bundle. */
final class GitDataBundleUpdater {
    interface Progress {
        void update(int percent, String message);
    }
    static final class Result {
        final boolean success;
        final boolean changed;
        final String message;
        Result(boolean success, boolean changed, String message) {
            this.success = success; this.changed = changed; this.message = message;
        }
    }

    private static final String RELEASES = "https://api.github.com/repos/EMoyaP/"
            + "BMW-E87-iDrive/releases?per_page=12";
    private static final int MAX_MANIFEST_BYTES = 1_000_000;
    private static final int MAX_ROAD_FILE_BYTES = 64 * 1024 * 1024;
    private final Context context;
    private final SharedPreferences preferences;

    GitDataBundleUpdater(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("e87_git_data_bundle", Context.MODE_PRIVATE);
    }

    Result checkAndInstall(SpeedLimitRepository speedLimits, Progress progress) {
        File staging = new File(context.getCacheDir(), "e87-data-bundle-staging");
        try {
            progress.update(5, "Consultando publicaciones de datos…");
            JSONArray releases = new JSONArray(readText(RELEASES, MAX_MANIFEST_BYTES));
            JSONObject release = latestDataRelease(releases);
            if (release == null) return new Result(true, false, "Sin paquete Git publicado");
            String version = release.optString("tag_name", "");
            if (version.equals(preferences.getString("installed_version", ""))) {
                return new Result(true, false, "Cartografía Git ya actualizada");
            }
            Map<String, String> assets = releaseAssets(release.optJSONArray("assets"));
            String manifestUrl = assets.get("manifest.json");
            if (manifestUrl == null) throw new IOException("Release sin manifest.json");
            JSONObject manifest = new JSONObject(readText(manifestUrl, MAX_MANIFEST_BYTES));
            if (!"e87-data-bundle-v1".equals(manifest.optString("schema"))) {
                throw new IOException("Esquema de paquete incompatible");
            }
            deleteTree(staging);
            if (!staging.mkdirs() && !staging.isDirectory()) throw new IOException("Sin carpeta temporal");
            JSONArray files = manifest.getJSONArray("files");
            Map<String, File> provincial = new HashMap<>();
            Map<String, Integer> expectedRows = new HashMap<>();
            int expectedProvinceFiles = 0;
            for (int i = 0; i < files.length(); i++) {
                JSONObject entry = files.getJSONObject(i);
                String name = entry.getString("name");
                String code = provinceCode(name);
                if (code == null) continue;
                expectedProvinceFiles++;
                String url = assets.get(name);
                if (url == null) throw new IOException("Asset ausente: " + name);
                progress.update(12 + i * 63 / Math.max(1, files.length()),
                        "Descargando " + code.toLowerCase(Locale.ROOT) + "…");
                File target = new File(staging, name);
                download(url, target, entry.getLong("bytes"), entry.getString("sha256"));
                provincial.put(code, target);
                expectedRows.put(code, entry.getInt("records"));
            }
            if (expectedProvinceFiles != 5 || provincial.size() != 5) {
                throw new IOException("Cobertura incompleta; se requieren cinco provincias");
            }
            progress.update(82, "Validando e instalando cartografía…");
            SpeedLimitRepository.UpdateResult installed = speedLimits.installBundleRoadSeeds(
                    provincial, expectedRows, System.currentTimeMillis());
            if (!installed.success) throw new IOException(installed.message);
            preferences.edit().putString("installed_version", version)
                    .putLong("installed_at", System.currentTimeMillis()).apply();
            progress.update(100, installed.message);
            deleteTree(staging);
            return new Result(true, true, installed.message);
        } catch (Exception error) {
            deleteTree(staging);
            AppSessionLog.event("DATOS GIT", "Actualización conservadora fallida · "
                    + error.getMessage());
            return new Result(false, false, "Pendiente de reintento: "
                    + error.getClass().getSimpleName());
        }
    }

    private static JSONObject latestDataRelease(JSONArray releases) {
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null || release.optBoolean("draft") || release.optBoolean("prerelease")) continue;
            if (release.optString("tag_name", "").startsWith("data-v")) return release;
        }
        return null;
    }

    private static Map<String, String> releaseAssets(JSONArray entries) {
        Map<String, String> result = new HashMap<>();
        if (entries == null) return result;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject asset = entries.optJSONObject(i);
            if (asset != null) result.put(asset.optString("name"),
                    asset.optString("browser_download_url"));
        }
        return result;
    }

    private static String provinceCode(String name) {
        if (name == null || !name.startsWith("e87_speed_limits_") || !name.endsWith(".tsv.gz")) return null;
        String value = name.substring("e87_speed_limits_".length(), name.length() - ".tsv.gz".length());
        switch (value) {
            case "alicante": return "ALICANTE"; case "murcia": return "MURCIA";
            case "valencia": return "VALENCIA"; case "albacete": return "ALBACETE";
            case "almeria": return "ALMERIA"; default: return null;
        }
    }

    private static String readText(String url, int maximum) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        HttpURLConnection connection = open(url);
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream())) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maximum) throw new IOException("Respuesta demasiado grande");
                output.write(buffer, 0, count);
            }
        } finally { connection.disconnect(); }
        return output.toString("UTF-8");
    }

    private static void download(String url, File target, long expectedBytes, String expectedHash)
            throws Exception {
        if (expectedBytes <= 0L || expectedBytes > MAX_ROAD_FILE_BYTES
                || expectedHash == null || !expectedHash.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("Metadatos de archivo inválidos: " + target.getName());
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0L;
        HttpURLConnection connection = open(url);
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[16_384]; int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > expectedBytes || total > MAX_ROAD_FILE_BYTES) {
                    throw new IOException("Tamaño excedido: " + target.getName());
                }
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        } finally { connection.disconnect(); }
        if (total != expectedBytes || !hex(digest.digest()).equalsIgnoreCase(expectedHash)) {
            throw new IOException("Integridad incorrecta: " + target.getName());
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000); connection.setReadTimeout(120_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        connection.setRequestProperty("User-Agent", "BMW-E87-iDrive/1.28 (verified data updater)");
        int response = connection.getResponseCode();
        if (response < 200 || response >= 300) {
            connection.disconnect(); throw new IOException("GitHub HTTP " + response);
        }
        return connection;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!file.delete()) AppSessionLog.event("DATOS GIT", "No se pudo limpiar " + file.getName());
    }
}
