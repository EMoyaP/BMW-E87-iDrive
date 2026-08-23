package com.ug.e87idrive;

import android.content.Context;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Small offline cache for OSM road speed information.
 *
 * The database is deliberately local: the dashboard never performs a network request. A manual
 * refresh can download only the current area over Wi-Fi, then the car can use those records
 * without connectivity. Numeric {@code maxspeed} is presented as a legal map limit. When no
 * such tag exists, a conservative speed recommendation can be derived from the OSM highway
 * class, is rendered with a distinct blue sign and is never treated as a legal limit.
 */
final class SpeedLimitRepository {
    private static final String TAG = "LÍMITES GPS";
    private static final String ENDPOINT = "https://overpass-api.de/api/interpreter";
    /** Compact, reviewed OSM snapshot published with the project. It carries all drivable
     * Alicante classes, unlike a maxspeed-only provincial Overpass request. */
    private static final String ALICANTE_ROADS_SEED_URL = "https://raw.githubusercontent.com/EMoyaP/"
            + "BMW-E87-iDrive/main/app/src/main/assets/e87_speed_limits_alicante.tsv.gz";
    private static final int QUERY_RADIUS_METERS = 5_000;
    /** A broad nearest-road match can pick a parallel carriageway. Prefer no limit over an
     * ambiguous one; this app is an aid, never a legal/realtime traffic-sign source. */
    private static final int MAX_MATCH_RADIUS_METERS = 90;
    private static final int MIN_MATCH_RADIUS_METERS = 25;
    /** A legal maxspeed just a few metres behind an untagged parallel geometry is more useful
     * than a class-derived recommendation. A materially nearer road still wins. */
    private static final double EXACT_PREFERENCE_TOLERANCE_METERS = 8d;
    private static final float MAX_ACCEPTED_GPS_ACCURACY_METERS = 45f;
    /** Bump when a bundled provincial seed must be checked on top of an older installation. */
    /** v3 replaces the old maxspeed-only Alicante cache with the full road-class seed. */
    private static final int BUNDLED_SEED_VERSION = 3;
    /** GPS is requested once per second while driving. Keep lookup reuse below that cadence so a
     * new road/maxspeed can be reflected on the next fix without any network request. */
    private static final long LIVE_LOOKUP_CACHE_MS = 750L;
    private static final double LIVE_LOOKUP_CACHE_METERS = 15d;
    private static final long MIN_REFRESH_INTERVAL_MS = 120_000L;
    /** One successful download per province per day avoids repeated Overpass traffic while the
     * local database remains current enough for this dashboard. */
    private static final long AUTO_REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1_000L;
    private static final long AUTO_RETRY_INTERVAL_MS = 30L * 60L * 1_000L;
    private static final long MAX_STALE_MS = 180L * 24L * 60L * 60L * 1_000L;
    private static final int PROVINCE_QUERY_TIMEOUT_SECONDS = 90;
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private static final Province[] SUPPORTED_PROVINCES = {
            new Province("ALICANTE", "Alicante", "e87_speed_limits_alicante.tsv.gz", 349012L),
            new Province("MURCIA", "Murcia", "e87_speed_limits_murcia.tsv.gz", 349047L),
            new Province("VALENCIA", "Valencia", "e87_speed_limits_valencia.tsv.gz", 349000L),
            new Province("ALBACETE", "Albacete", "e87_speed_limits_albacete.tsv.gz", 348989L)
    };

    private final Context context;
    private final ConnectivityManager connectivity;
    private final SharedPreferences updatePreferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Database database;
    private final Object databaseLock = new Object();
    private volatile long lastRefreshAt;
    private volatile long lastAutomaticAttemptAt;
    private volatile String lastResult = "Base local · cargando provincias iniciales";
    private volatile String seedStatus = "Pendiente";
    private volatile Match cachedMatch;
    private volatile long cachedLookupAt;
    private volatile double cachedLat;
    private volatile double cachedLon;
    private volatile Location lastLocation;
    private volatile String lastLookupResult = "Sin consulta GPS todavía";
    private volatile String lastLoggedLookup = "";
    private volatile boolean active;
    private volatile boolean updateRunning;
    private boolean networkCallbackRegistered;

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { autoRefreshIfNeeded(); }
                @Override public void onCapabilitiesChanged(Network network,
                                                            NetworkCapabilities capabilities) {
                    if (hasInternetCapability(network)) autoRefreshIfNeeded();
                }
            };

    SpeedLimitRepository(Context context) {
        this.context = context.getApplicationContext();
        this.connectivity = (ConnectivityManager) this.context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        this.updatePreferences = this.context.getSharedPreferences("speed_limit_updates",
                Context.MODE_PRIVATE);
        this.database = new Database(this.context);
        seedFromAssetsAsync();
    }

    void start(Location location) {
        active = true;
        if (location != null) lastLocation = new Location(location);
        registerNetworkCallback();
        autoRefreshIfNeeded();
    }

    void stop() {
        active = false;
        unregisterNetworkCallback();
    }

    void onLocation(Location location) {
        if (location == null) return;
        lastLocation = new Location(location);
        logLookupResult(location, lookup(location));
        autoRefreshIfNeeded();
    }

    static String[] supportedProvinceLabels() {
        return new String[]{"Zona GPS actual · 5 km", "Alicante · provincia completa", "Murcia · provincia completa",
                "Valencia · provincia completa", "Albacete · provincia completa"};
    }

    static String[] supportedProvinceCodes() {
        return new String[]{"AUTO", "ALICANTE", "MURCIA", "VALENCIA", "ALBACETE"};
    }

    static String provinceLabel(String code) {
        if (code == null || code.isEmpty() || "AUTO".equals(code)) return "zona GPS actual";
        for (Province province : SUPPORTED_PROVINCES) {
            if (province.code.equals(code)) return province.label;
        }
        return code;
    }

    Match lookup(Location location) {
        if (location == null) return null;
        if (location.hasAccuracy() && location.getAccuracy() > MAX_ACCEPTED_GPS_ACCURACY_METERS) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - cachedLookupAt < LIVE_LOOKUP_CACHE_MS
                && distanceMeters(cachedLat, cachedLon, location.getLatitude(), location.getLongitude())
                < LIVE_LOOKUP_CACHE_METERS) {
            return cachedMatch;
        }
        int matchRadius = matchRadiusFor(location);
        Match result = database.nearest(location.getLatitude(), location.getLongitude(), matchRadius);
        cachedLat = location.getLatitude();
        cachedLon = location.getLongitude();
        cachedLookupAt = now;
        cachedMatch = result;
        return result;
    }

    private static int matchRadiusFor(Location location) {
        if (location == null || !location.hasAccuracy()) return MAX_MATCH_RADIUS_METERS;
        return Math.max(MIN_MATCH_RADIUS_METERS, Math.min(MAX_MATCH_RADIUS_METERS,
                Math.round(location.getAccuracy() * 2f)));
    }

    /** Logs the matching decision without coordinates, so an exported session explains a blank sign. */
    private void logLookupResult(Location location, Match match) {
        String accuracy = location.hasAccuracy()
                ? String.format(Locale.ROOT, "%.0f m", location.getAccuracy()) : "no publicada";
        String result = match == null
                ? "sin límite · precisión=" + accuracy + " · radio=" + matchRadiusFor(location) + " m"
                : String.format(Locale.ROOT, "%s %d km/h · %.0f m · %s · precisión=%s",
                        match.exact ? "límite" : "recomendada/" + roadClassLabel(match.roadClass),
                        match.limitKmh, match.distanceMeters, provinceLabel(match.province), accuracy);
        lastLookupResult = result;
        if (!result.equals(lastLoggedLookup)) {
            lastLoggedLookup = result;
            AppSessionLog.event(TAG, "Consulta local " + result);
        }
    }

    void refreshFromInternet(Location location, UpdateCallback callback) {
        refreshFromInternet(location, "AUTO", callback, false);
    }

    void refreshFromInternet(Location location, String requestedProvince, UpdateCallback callback) {
        refreshFromInternet(location, requestedProvince, callback, false);
    }

    private void refreshFromInternet(Location location, String requestedProvince,
                                     UpdateCallback callback, boolean automatic) {
        Network selectedNetwork = availableNetwork();
        if (selectedNetwork == null) {
            finish(callback, false, "Android no publica una conexión a Internet utilizable");
            return;
        }
        if (location == null) {
            finish(callback, false, "Esperando una posición GPS para actualizar los límites");
            return;
        }
        long now = System.currentTimeMillis();
        if (updateRunning || now - lastRefreshAt < MIN_REFRESH_INTERVAL_MS) {
            finish(callback, false, "Actualización limitada: espera unos segundos antes de repetir");
            return;
        }
        String province = requestedProvince == null ? "AUTO" : requestedProvince;
        Province selectedProvince = findProvince(province);
        boolean provincialUpdate = selectedProvince != null;
        if (provincialUpdate) {
            long lastSuccess = lastSuccessfulUpdate(selectedProvince.code);
            if (lastSuccess > 0L && now - lastSuccess < AUTO_REFRESH_INTERVAL_MS) {
                finish(callback, false, "" + selectedProvince.label + " ya se actualizó correctamente "
                        + "hace menos de 24 h");
                return;
            }
        }
        lastRefreshAt = now;
        updateRunning = true;
        Thread worker = new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                int imported;
                if (provincialUpdate && "ALICANTE".equals(selectedProvince.code)) {
                    // Loading an 84 MB Overpass JSON response into the head unit would be
                    // wasteful and fragile. This compact stream carries every road class and
                    // explicit maxspeed without accumulating it in the Java heap.
                    imported = replaceAlicanteFromPublishedSeed(selectedNetwork, selectedProvince);
                } else {
                    String query = buildQuery(location, selectedProvince);
                    String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
                    URL url = new URL(ENDPOINT + "?data=" + encoded);
                    // Bind the request to the network Android has actually exposed. This covers
                    // Wi-Fi, Ethernet and BT PAN if the OEM creates a usable IP interface.
                    connection = (HttpURLConnection) selectedNetwork.openConnection(url);
                    connection.setConnectTimeout(15_000);
                    connection.setReadTimeout((PROVINCE_QUERY_TIMEOUT_SECONDS + 20) * 1_000);
                    connection.setRequestMethod("GET");
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("User-Agent", "BMW-E87-iDrive/1.18 (offline speed-limit cache)");
                    int response = connection.getResponseCode();
                    if (response < 200 || response >= 300) {
                        throw new IOException("HTTP " + response);
                    }
                    String json = read(connection, MAX_RESPONSE_BYTES);
                    imported = importResponse(new JSONObject(json),
                            provincialUpdate ? selectedProvince.code : province, provincialUpdate);
                }
                lastResult = provincialUpdate
                        ? imported + " tramos provinciales guardados en local · " + selectedProvince.label
                        + " · OpenStreetMap"
                        : imported + " tramos guardados en local · " + provinceLabel(province)
                        + " · OpenStreetMap";
                if (provincialUpdate || automatic) {
                    updatePreferences.edit().putLong(successPreference(
                                    provincialUpdate ? selectedProvince.code : province),
                            System.currentTimeMillis()).apply();
                }
                AppSessionLog.event(TAG, "Actualización Wi-Fi correcta · " + lastResult);
                finish(callback, true, lastResult);
            } catch (Exception error) {
                lastResult = "No se pudo actualizar: " + error.getClass().getSimpleName();
                AppSessionLog.event(TAG, "Actualización Wi-Fi fallida · " + error.getMessage());
                finish(callback, false, lastResult);
            } finally {
                if (connection != null) connection.disconnect();
                updateRunning = false;
            }
        }, automatic ? "e87-speed-limit-auto" : "e87-speed-limit-update");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    boolean isInternetAvailable() { return availableNetwork() != null; }

    long lastSuccessfulUpdate() {
        long newest = 0L;
        for (Province province : SUPPORTED_PROVINCES) {
            newest = Math.max(newest, updatePreferences.getLong(successPreference(province.code), 0L));
        }
        return newest;
    }

    long lastSuccessfulUpdate(String provinceCode) {
        return updatePreferences.getLong(successPreference(provinceCode), 0L);
    }

    UpdateStamp lastSuccessfulUpdateStamp() {
        String newestProvince = null;
        long newest = 0L;
        for (Province province : SUPPORTED_PROVINCES) {
            long candidate = lastSuccessfulUpdate(province.code);
            if (candidate > newest) {
                newest = candidate;
                newestProvince = province.code;
            }
        }
        return newestProvince == null ? null : new UpdateStamp(newestProvince, newest);
    }

    String automaticProvince(Location location) {
        if (location != null) {
            Match match = lookup(location);
            if (match != null && findProvince(match.province) != null) return match.province;
        }
        // A broad administrative rectangle can overlap Alicante, Murcia and Albacete. Until
        // a local way proves the province, refresh only the 5 km GPS area instead of storing a
        // provincial update under the wrong name.
        return "AUTO";
    }

    String diagnostic() {
        int exact = database.countKind("EXACT");
        int advisory = database.countKind("ADVISORY");
        return "LÍMITES GPS LOCALES\n"
                + "registros=" + database.count() + " · explícitos=" + exact
                + " · clases DGT=" + advisory + "\n"
                + "semillas=" + seedStatus + "\n"
                + "última actualización=" + lastResult + "\n"
                + "última consulta=" + lastLookupResult + "\n"
                + "actualización automática=Internet Android · máximo una vez por provincia/24 h\n"
                + "radio de descarga=" + QUERY_RADIUS_METERS + " m · radio de lectura="
                + MIN_MATCH_RADIUS_METERS + "–" + MAX_MATCH_RADIUS_METERS + " m según precisión GPS\n"
                + "semilla provincial=Alicante, Murcia, Valencia, Albacete\n"
                + "Alicante=mapa completo local · maxspeed + clasificación de vía\n"
                + "actualización Alicante=instantánea OSM compacta publicada · importación en flujo\n"
                + "fuente=OpenStreetMap · límite rojo solo con maxspeed; recomendación azul por clase DGT\n";
    }

    void close() { stop(); database.close(); }

    private void autoRefreshIfNeeded() {
        if (!active || updateRunning || availableNetwork() == null || lastLocation == null) return;
        long now = System.currentTimeMillis();
        if (now - lastAutomaticAttemptAt < AUTO_RETRY_INTERVAL_MS) return;
        String province = automaticProvince(lastLocation);
        long lastSuccess = lastSuccessfulUpdate(province);
        if (lastSuccess > 0L && now - lastSuccess < AUTO_REFRESH_INTERVAL_MS) return;
        lastAutomaticAttemptAt = now;
        AppSessionLog.event(TAG, "Actualización automática solicitada · provincia="
                + provinceLabel(province) + " · red=" + networkLabel());
        refreshFromInternet(new Location(lastLocation), province, result -> {
            AppSessionLog.event(TAG, "Actualización automática "
                    + (result.success ? "correcta" : "omitida/fallida") + " · " + result.message);
        }, true);
    }

    private static Province findProvince(String code) {
        if (code == null) return null;
        for (Province province : SUPPORTED_PROVINCES) {
            if (province.code.equals(code)) return province;
        }
        return null;
    }

    private static String successPreference(String provinceCode) {
        return "last_success_" + (provinceCode == null ? "" : provinceCode);
    }

    /** Broad GPS fallback only. A nearby local OSM road (above) always takes precedence. */
    private static String provinceFromBounds(Location location) {
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        if (lat >= 38.00 && lat <= 39.95 && lon >= -2.98 && lon <= -0.68) return "ALBACETE";
        if (lat >= 38.55 && lat <= 40.92 && lon >= -1.92 && lon <= 0.02) return "VALENCIA";
        if (lat >= 37.35 && lat <= 38.80 && lon >= -2.40 && lon <= -0.62) return "MURCIA";
        if (lat >= 37.80 && lat <= 38.90 && lon >= -1.62 && lon <= 0.40) return "ALICANTE";
        return null;
    }

    private static String buildQuery(Location location, Province province) {
        if (province != null) {
            long areaId = 3_600_000_000L + province.osmRelationId;
            return "[out:json][timeout:" + PROVINCE_QUERY_TIMEOUT_SECONDS + "];"
                    + "area(" + areaId + ")->.province;"
                    + "way(area.province)[\"highway\"][\"maxspeed\"];out tags geom;";
        }
        // The automatic five-kilometre update also keeps road classification. This gives an
        // offline advisory sign after one small local refresh, without trying to download an
        // entire province of every OSM road onto the head unit or overloading Overpass.
        return "[out:json][timeout:25];way(around:" + QUERY_RADIUS_METERS + ","
                + String.format(Locale.ROOT, "%.6f,%.6f", location.getLatitude(), location.getLongitude())
                + ")[\"highway\"~\"motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service\"];out tags geom;";
    }

    private void seedFromAssetsAsync() {
        Thread worker = new Thread(() -> {
            synchronized (databaseLock) {
                boolean needsSeedCheck = updatePreferences.getInt("bundled_seed_version", 0)
                        < BUNDLED_SEED_VERSION;
                seedStatus = needsSeedCheck ? "Verificando semillas provinciales" : "Verificando base local";
                int imported = 0;
                boolean replaceOldMapCache = updatePreferences.getInt("bundled_seed_version", 0)
                        < BUNDLED_SEED_VERSION;
                SQLiteDatabase db = database.getWritableDatabase();
                long now = System.currentTimeMillis();
                db.beginTransaction();
                try {
                    // This intentionally resets only iDrive's own speed-map cache. It does not
                    // touch fuel prices, diagnostics, OEM settings or any radio application.
                    // Keeping the old maxspeed-only cache would prevent the bundled Alicante
                    // class rows from replacing it after an APK upgrade.
                    if (replaceOldMapCache) db.delete("speed_limits", null, null);
                    for (Province province : SUPPORTED_PROVINCES) {
                        // Older builds could retain generic records and therefore omit the
                        // packaged provincial base. Do not replace a province already updated
                        // by the user; only fill one that is absent.
                        if (replaceOldMapCache || database.countProvince(province.code) == 0) {
                            imported += importSeedAsset(db, province, now);
                        }
                    }
                    db.setTransactionSuccessful();
                } catch (Exception error) {
                    seedStatus = "Error: " + error.getClass().getSimpleName();
                    AppSessionLog.event(TAG, "Semillas locales fallidas · " + error.getMessage());
                } finally {
                    db.endTransaction();
                }
                if (imported > 0) {
                    seedStatus = imported + " tramos importados";
                    lastResult = imported + " tramos iniciales · Alicante/Murcia/Valencia/Albacete";
                    AppSessionLog.event(TAG, "Semillas locales listas · " + lastResult);
                    cachedLookupAt = 0L;
                } else {
                    seedStatus = "Base local existente";
                }
                SharedPreferences.Editor seedEditor = updatePreferences.edit()
                        .putInt("bundled_seed_version", BUNDLED_SEED_VERSION);
                if (replaceOldMapCache) {
                    for (Province province : SUPPORTED_PROVINCES) {
                        seedEditor.remove(successPreference(province.code));
                    }
                }
                seedEditor.apply();
                main.post(SpeedLimitRepository.this::autoRefreshIfNeeded);
            }
        }, "e87-speed-limit-seed");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private int importSeedAsset(SQLiteDatabase db, Province province, long now) {
        int imported = 0;
        try (BufferedReader reader = openSeedReader(province)) {
            imported = importSeedReader(db, reader, province, now);
        } catch (IOException error) {
            AppSessionLog.event(TAG, "Semilla no disponible · " + province.label + " · " + error.getMessage());
        }
        return imported;
    }

    /** Replaces only iDrive's Alicante road map with a compact OSM snapshot. This never
     * accesses CAN, MCU, OEM settings, fuel caches or any other application data. */
    private int replaceAlicanteFromPublishedSeed(Network network, Province province) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(ALICANTE_ROADS_SEED_URL + "?revision=" + System.currentTimeMillis());
            connection = (HttpURLConnection) network.openConnection(url);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(90_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/gzip, application/octet-stream");
            connection.setRequestProperty("User-Agent", "BMW-E87-iDrive/1.18 (Alicante offline road map)");
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) throw new IOException("HTTP " + response);
            synchronized (databaseLock) {
                SQLiteDatabase db = database.getWritableDatabase();
                db.beginTransaction();
                try (BufferedReader reader = openSeedReader(connection.getInputStream())) {
                    db.delete("speed_limits", "province = ?", new String[]{province.code});
                    int imported = importSeedReader(db, reader, province, System.currentTimeMillis());
                    if (imported <= 0) throw new IOException("Semilla Alicante vacía");
                    db.setTransactionSuccessful();
                    cachedLookupAt = 0L;
                    return imported;
                } finally {
                    db.endTransaction();
                }
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Imports v1 explicit-maxspeed rows and v2 complete road-class rows without loading the
     * compressed map into the Java heap. */
    private int importSeedReader(SQLiteDatabase db, BufferedReader reader, Province province, long now)
            throws IOException {
        int imported = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] columns = line.split("\\t", 5);
            boolean v2 = columns.length == 5 && ("EXACT".equals(columns[1])
                    || "ADVISORY".equals(columns[1]));
            String id = columns[0];
            int limit = parseLimit(v2 ? columns[2] : (columns.length > 1 ? columns[1] : ""));
            String roadClass = v2 ? columns[3] : "";
            String geometry = v2 ? columns[4] : (columns.length > 2 ? columns[2] : "");
            boolean exact = !v2 || "EXACT".equals(columns[1]);
            if (limit <= 0 || id.isEmpty() || geometry.isEmpty()) continue;
            imported += insertRecord(db, id, limit, geometry, now, province.code, exact, roadClass);
        }
        return imported;
    }

    /**
     * AAPT may unpack an asset ending in .gz and store it as .tsv in the APK. Accept both the
     * source-tree representation and the packaged representation, and sniff the payload so a
     * future build-tool change cannot silently leave the local database empty.
     */
    private BufferedReader openSeedReader(Province province) throws IOException {
        InputStream raw = null;
        IOException failure = null;
        String[] candidates = province.asset.endsWith(".gz")
                ? new String[]{province.asset, province.asset.substring(0, province.asset.length() - 3)}
                : new String[]{province.asset};
        for (String candidate : candidates) {
            try {
                raw = context.getAssets().open(candidate);
                break;
            } catch (IOException error) {
                failure = error;
            }
        }
        if (raw == null) throw failure == null ? new IOException("Asset no encontrado") : failure;
        return openSeedReader(raw);
    }

    private BufferedReader openSeedReader(InputStream raw) throws IOException {
        BufferedInputStream input = new BufferedInputStream(raw);
        input.mark(2);
        int first = input.read();
        int second = input.read();
        input.reset();
        InputStream payload = first == 0x1f && second == 0x8b
                ? new GZIPInputStream(input) : input;
        return new BufferedReader(new InputStreamReader(payload, StandardCharsets.UTF_8));
    }

    private int importResponse(JSONObject root, String province, boolean replaceProvince) {
        JSONArray elements = root.optJSONArray("elements");
        if (elements == null) return 0;
        synchronized (databaseLock) {
            SQLiteDatabase db = database.getWritableDatabase();
            int imported = 0;
            long now = System.currentTimeMillis();
            db.beginTransaction();
            try {
                if (replaceProvince) {
                    db.delete("speed_limits", "province = ?", new String[]{province});
                }
                for (int i = 0; i < elements.length(); i++) {
                    JSONObject element = elements.optJSONObject(i);
                    if (element == null) continue;
                    JSONObject tags = element.optJSONObject("tags");
                    String roadClass = tags == null ? "" : tags.optString("highway", "");
                    int explicitLimit = parseLimit(tags == null ? null : tags.optString("maxspeed", ""));
                    int advisoryLimit = advisoryForRoadClass(roadClass);
                    JSONArray geometry = element.optJSONArray("geometry");
                    if (explicitLimit <= 0 && advisoryLimit <= 0 || geometry == null || geometry.length() < 2) continue;
                    String coordinates = geometryString(geometry);
                    if (coordinates.isEmpty()) continue;
                    String id = element.optString("id", "");
                    if (id.isEmpty()) continue;
                    imported += insertRecord(db, id,
                            explicitLimit > 0 ? explicitLimit : advisoryLimit,
                            coordinates, now, province, explicitLimit > 0, roadClass);
                }
                db.delete("speed_limits", "updated_at < ?", new String[]{String.valueOf(now - MAX_STALE_MS)});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            cachedLookupAt = 0L;
            return imported;
        }
    }

    private static int insertRecord(SQLiteDatabase db, String id, int limit, String geometry,
                                    long updatedAt, String province) {
        return insertRecord(db, id, limit, geometry, updatedAt, province, true, "");
    }

    private static int insertRecord(SQLiteDatabase db, String id, int limit, String geometry,
                                    long updatedAt, String province, boolean exact, String roadClass) {
        double[] bounds = geometryBounds(geometry);
        if (bounds == null) return 0;
        ContentValues values = new ContentValues();
        values.put("osm_id", id);
        values.put("maxspeed", limit);
        values.put("geometry", geometry);
        values.put("updated_at", updatedAt);
        values.put("province", province == null ? "" : province);
        values.put("record_kind", exact ? "EXACT" : "ADVISORY");
        values.put("road_class", roadClass == null ? "" : roadClass);
        values.put("min_lat", bounds[0]);
        values.put("max_lat", bounds[1]);
        values.put("min_lon", bounds[2]);
        values.put("max_lon", bounds[3]);
        return db.insertWithOnConflict("speed_limits", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1 ? 0 : 1;
    }

    private static String geometryString(JSONArray geometry) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < geometry.length(); i++) {
            JSONObject point = geometry.optJSONObject(i);
            if (point == null || !point.has("lat") || !point.has("lon")) continue;
            if (result.length() > 0) result.append(';');
            result.append(String.format(Locale.ROOT, "%.6f,%.6f", point.optDouble("lat"), point.optDouble("lon")));
        }
        return result.toString();
    }

    private static double[] geometryBounds(String geometry) {
        if (geometry == null || geometry.isEmpty()) return null;
        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE;
        int valid = 0;
        for (String point : geometry.split(";")) {
            String[] pair = point.split(",");
            if (pair.length != 2) continue;
            try {
                double lat = Double.parseDouble(pair[0]);
                double lon = Double.parseDouble(pair[1]);
                minLat = Math.min(minLat, lat);
                maxLat = Math.max(maxLat, lat);
                minLon = Math.min(minLon, lon);
                maxLon = Math.max(maxLon, lon);
                valid++;
            } catch (Exception ignored) { }
        }
        return valid == 0 ? null : new double[]{minLat, maxLat, minLon, maxLon};
    }

    private static int parseLimit(String value) {
        if (value == null) return -1;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.contains("signals") || normalized.contains("variable")
                || normalized.contains("none") || normalized.contains("unknown")) return -1;
        String first = normalized.split(";")[0].trim();
        boolean mph = first.contains("mph");
        String number = first.replace("km/h", "").replace("kmh", "").replace("mph", "").trim();
        try {
            double parsed = Double.parseDouble(number);
            if (mph) parsed *= 1.609344d;
            int result = (int) Math.round(parsed);
            return result >= 5 && result <= 250 ? result : -1;
        } catch (Exception ignored) { return -1; }
    }

    /**
     * Generic DGT-reference guidance for roads with no OSM maxspeed tag. These values are not
     * presented as legal limits, do not trigger the orange dial state and are kept in a separate
     * record kind. A road class alone cannot know the actual sign at the driver's position.
     *
     * The table reflects the generic Spanish context for a passenger car: 120 on motorway,
     * 90 on a conventional through road and lower contextual values inside local areas. It is
     * deliberately rendered as a blue advisory sign, never as a red regulatory signal.
     */
    static int advisoryForRoadClass(String roadClass) {
        if (roadClass == null) return -1;
        switch (roadClass.trim().toLowerCase(Locale.ROOT)) {
            case "motorway": return 120;
            case "trunk": return 90;
            case "primary": return 90;
            // OSM marks the CV-851 at the validated test point as a secondary road without
            // maxspeed. For a passenger car, the generic Spanish value for a conventional road
            // is 90 km/h; this remains a blue reference, not an asserted legal limit.
            case "secondary": return 90;
            case "tertiary": return 90;
            case "unclassified": return 50;
            case "residential": return 30;
            case "living_street": return 20;
            case "service": return 20;
            default: return -1;
        }
    }

    static String roadClassLabel(String roadClass) {
        if (roadClass == null) return "vía";
        switch (roadClass.trim().toLowerCase(Locale.ROOT)) {
            case "motorway": return "autopista";
            case "trunk": return "vía rápida";
            case "primary": return "principal";
            case "secondary": return "secundaria";
            case "tertiary": return "terciaria";
            case "unclassified": return "local";
            case "residential": return "residencial";
            case "living_street": return "residencial";
            case "service": return "servicio";
            default: return "vía";
        }
    }

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        float[] result = new float[1];
        Location.distanceBetween(lat1, lon1, lat2, lon2, result);
        return result[0];
    }

    private Network availableNetwork() {
        if (connectivity == null) return null;
        try {
            Network activeNetwork = connectivity.getActiveNetwork();
            if (isValidated(activeNetwork)) return activeNetwork;
            for (Network candidate : connectivity.getAllNetworks()) if (isValidated(candidate)) return candidate;
            if (hasInternetCapability(activeNetwork)) return activeNetwork;
            for (Network candidate : connectivity.getAllNetworks()) {
                if (hasInternetCapability(candidate)) return candidate;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private boolean hasInternetCapability(Network network) {
        if (network == null || connectivity == null) return false;
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private boolean isValidated(Network network) {
        if (!hasInternetCapability(network)) return false;
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private String networkLabel() {
        Network network = availableNetwork();
        if (network == null || connectivity == null) return "sin red";
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
        if (capabilities == null) return "sin red";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return "Bluetooth PAN";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "datos móviles";
        return "Android";
    }

    private void registerNetworkCallback() {
        if (networkCallbackRegistered || connectivity == null) return;
        try {
            connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback);
            networkCallbackRegistered = true;
        } catch (Exception ignored) { }
    }

    private void unregisterNetworkCallback() {
        if (!networkCallbackRegistered || connectivity == null) return;
        try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) { }
        networkCallbackRegistered = false;
    }

    private static String read(HttpURLConnection connection, int maxBytes) throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > maxBytes) throw new IOException("Respuesta demasiado grande para la radio");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void finish(UpdateCallback callback, boolean success, String message) {
        if (callback == null) return;
        main.post(() -> callback.onFinished(new UpdateResult(success, message)));
    }

    interface UpdateCallback { void onFinished(UpdateResult result); }

    private static final class Province {
        final String code;
        final String label;
        final String asset;
        final long osmRelationId;

        Province(String code, String label, String asset, long osmRelationId) {
            this.code = code;
            this.label = label;
            this.asset = asset;
            this.osmRelationId = osmRelationId;
        }
    }

    static final class UpdateResult {
        final boolean success;
        final String message;
        UpdateResult(boolean success, String message) { this.success = success; this.message = message; }
    }

    static final class UpdateStamp {
        final String province;
        final long timestamp;
        UpdateStamp(String province, long timestamp) {
            this.province = province;
            this.timestamp = timestamp;
        }
    }

    static final class Match {
        final int limitKmh;
        final double distanceMeters;
        final long updatedAt;
        final String province;
        final boolean exact;
        final String roadClass;
        Match(int limitKmh, double distanceMeters, long updatedAt, String province,
              boolean exact, String roadClass) {
            this.limitKmh = limitKmh;
            this.distanceMeters = distanceMeters;
            this.updatedAt = updatedAt;
            this.province = province;
            this.exact = exact;
            this.roadClass = roadClass;
        }
    }

    private static final class Database extends SQLiteOpenHelper {
        private static final String NAME = "e87_speed_limits.db";
        private static final int VERSION = 4;

        Database(Context context) { super(context, NAME, null, VERSION); }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE speed_limits (osm_id TEXT PRIMARY KEY, maxspeed INTEGER NOT NULL, "
                    + "geometry TEXT NOT NULL, updated_at INTEGER NOT NULL, province TEXT NOT NULL DEFAULT '', "
                    + "record_kind TEXT NOT NULL DEFAULT 'EXACT', road_class TEXT NOT NULL DEFAULT '', "
                    + "min_lat REAL NOT NULL DEFAULT 0, max_lat REAL NOT NULL DEFAULT 0, "
                    + "min_lon REAL NOT NULL DEFAULT 0, max_lon REAL NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX speed_limits_updated ON speed_limits(updated_at)");
            db.execSQL("CREATE INDEX speed_limits_bounds ON speed_limits(min_lat, max_lat, min_lon, max_lon)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE speed_limits ADD COLUMN province TEXT NOT NULL DEFAULT ''");
                db.execSQL("ALTER TABLE speed_limits ADD COLUMN min_lat REAL NOT NULL DEFAULT 0");
                db.execSQL("ALTER TABLE speed_limits ADD COLUMN max_lat REAL NOT NULL DEFAULT 0");
                db.execSQL("ALTER TABLE speed_limits ADD COLUMN min_lon REAL NOT NULL DEFAULT 0");
                db.execSQL("ALTER TABLE speed_limits ADD COLUMN max_lon REAL NOT NULL DEFAULT 0");
                db.execSQL("CREATE INDEX speed_limits_bounds ON speed_limits(min_lat, max_lat, min_lon, max_lon)");
            }
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE speed_limits ADD COLUMN record_kind TEXT NOT NULL DEFAULT 'EXACT'");
                db.execSQL("ALTER TABLE speed_limits ADD COLUMN road_class TEXT NOT NULL DEFAULT ''");
            }
            if (oldVersion < 4) {
                // Reclassify existing local advisory rows so an app upgrade does not retain a
                // previously cached generic value until the province is downloaded again.
                db.execSQL("UPDATE speed_limits SET maxspeed = CASE lower(road_class) "
                        + "WHEN 'motorway' THEN 120 WHEN 'trunk' THEN 90 "
                        + "WHEN 'primary' THEN 90 WHEN 'secondary' THEN 90 "
                        + "WHEN 'tertiary' THEN 90 WHEN 'unclassified' THEN 50 "
                        + "WHEN 'residential' THEN 30 WHEN 'living_street' THEN 20 "
                        + "WHEN 'service' THEN 20 ELSE maxspeed END "
                        + "WHERE record_kind = 'ADVISORY'");
            }
        }

        Match nearest(double latitude, double longitude, int maxDistanceMeters) {
            SQLiteDatabase db = getReadableDatabase();
            double latDelta = maxDistanceMeters / 111_320d;
            double lonDelta = maxDistanceMeters / Math.max(1d, 111_320d * Math.cos(Math.toRadians(latitude)));
            String selection = "((min_lat = 0 AND max_lat = 0 AND min_lon = 0 AND max_lon = 0)"
                    + " OR (min_lat <= ? AND max_lat >= ? AND min_lon <= ? AND max_lon >= ?))";
            String[] args = {
                    String.valueOf(latitude + latDelta), String.valueOf(latitude - latDelta),
                    String.valueOf(longitude + lonDelta), String.valueOf(longitude - lonDelta)
            };
            Cursor cursor = db.query("speed_limits",
                    new String[]{"maxspeed", "geometry", "updated_at", "province", "record_kind", "road_class"},
                    selection, args, null, null, null);
            Match nearest = null;
            try {
                while (cursor.moveToNext()) {
                    int limit = cursor.getInt(0);
                    String geometry = cursor.getString(1);
                    double distance = nearestPolylineDistance(latitude, longitude, geometry);
                    if (distance > maxDistanceMeters) continue;
                    Match candidate = new Match(limit, distance, cursor.getLong(2), cursor.getString(3),
                            "EXACT".equals(cursor.getString(4)), cursor.getString(5));
                    if (nearest == null || preferCandidate(nearest, candidate)) {
                        nearest = candidate;
                    }
                }
            } finally { cursor.close(); }
            return nearest;
        }

        /**
         * OSM often has separate geometries for the road edge, a service lane and the real
         * carriageway. Prefer an explicit maxspeed when both candidates are practically on the
         * same road, but never let it override an obviously nearer road segment.
         */
        private static boolean preferCandidate(Match current, Match candidate) {
            if (current.exact == candidate.exact) {
                return candidate.distanceMeters < current.distanceMeters;
            }
            if (candidate.exact) {
                return candidate.distanceMeters <= current.distanceMeters
                        + EXACT_PREFERENCE_TOLERANCE_METERS;
            }
            return candidate.distanceMeters + EXACT_PREFERENCE_TOLERANCE_METERS
                    < current.distanceMeters;
        }

        int count() {
            SQLiteDatabase db = getReadableDatabase();
            try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM speed_limits", null)) {
                return cursor.moveToFirst() ? cursor.getInt(0) : 0;
            }
        }

        int countProvince(String province) {
            SQLiteDatabase db = getReadableDatabase();
            try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM speed_limits WHERE province = ?",
                    new String[]{province == null ? "" : province})) {
                return cursor.moveToFirst() ? cursor.getInt(0) : 0;
            }
        }

        int countKind(String kind) {
            SQLiteDatabase db = getReadableDatabase();
            try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM speed_limits WHERE record_kind = ?",
                    new String[]{kind})) {
                return cursor.moveToFirst() ? cursor.getInt(0) : 0;
            }
        }

        /** Distance to the complete OSM way, not just to its individual vertices. */
        private static double nearestPolylineDistance(double latitude, double longitude, String geometry) {
            double nearest = Double.MAX_VALUE;
            if (geometry == null) return nearest;
            double previousLat = Double.NaN;
            double previousLon = Double.NaN;
            for (String point : geometry.split(";")) {
                String[] pair = point.split(",");
                if (pair.length != 2) continue;
                try {
                    double currentLat = Double.parseDouble(pair[0]);
                    double currentLon = Double.parseDouble(pair[1]);
                    double pointDistance = distanceMeters(latitude, longitude, currentLat, currentLon);
                    if (pointDistance < nearest) nearest = pointDistance;
                    if (!Double.isNaN(previousLat)) {
                        double segmentDistance = distanceToSegment(latitude, longitude, previousLat,
                                previousLon, currentLat, currentLon);
                        if (segmentDistance < nearest) nearest = segmentDistance;
                    }
                    previousLat = currentLat;
                    previousLon = currentLon;
                } catch (Exception ignored) { }
            }
            return nearest;
        }

        private static double distanceToSegment(double latitude, double longitude, double latA,
                                                double lonA, double latB, double lonB) {
            // Local equirectangular projection is accurate enough for an OSM road segment and
            // prevents 100–300 m gaps between OSM vertices from becoming blank road limits.
            double metersLat = 110_540d;
            double metersLon = 111_320d * Math.cos(Math.toRadians(latitude));
            double ax = (lonA - longitude) * metersLon;
            double ay = (latA - latitude) * metersLat;
            double bx = (lonB - longitude) * metersLon;
            double by = (latB - latitude) * metersLat;
            double dx = bx - ax;
            double dy = by - ay;
            double lengthSquared = dx * dx + dy * dy;
            if (lengthSquared <= 0.0001d) return Math.hypot(ax, ay);
            double t = -(ax * dx + ay * dy) / lengthSquared;
            t = Math.max(0d, Math.min(1d, t));
            return Math.hypot(ax + t * dx, ay + t * dy);
        }
    }
}
