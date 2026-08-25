package com.ug.e87idrive;

import android.content.ContentValues;
import android.content.Context;
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
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Offline alert cache for DGT fixed and average-speed cameras.
 *
 * The app deliberately never imports, displays or infers mobile controls. The official DATEX II
 * publication is downloaded only on an explicit/once-per-day refresh. The national feed is
 * retained locally; province is a lookup/presentation concern, never a download restriction.
 */
final class RadarRepository {
    interface ProvinceResolver { String resolve(); }
    private static final String TAG = "RADARES DGT";
    private static final String DGT_ENDPOINT =
            "https://infocar.dgt.es/datex2/dgt/PredefinedLocationsPublication/radares/content.xml";
    private static final long REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1_000L;
    private static final long RETRY_INTERVAL_MS = 30L * 60L * 1_000L;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_LOOKUP_METERS = 1_250;
    private static final float MAX_GPS_ACCURACY_METERS = 35f;

    private final Context context;
    private final ConnectivityManager connectivity;
    private final SharedPreferences preferences;
    private final Database database;
    private final ProvinceResolver provinceResolver;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean active;
    private volatile boolean updateRunning;
    private volatile long lastAutomaticAttempt;
    private volatile Location lastLocation;
    private volatile String lastResult = "Base local de Alicante pendiente";
    private volatile String seedStatus = "Pendiente";
    private String trackedId;
    private double trackedDistance = Double.NaN;
    private String suppressedId;
    private double suppressedDistance = Double.NaN;
    private boolean networkCallbackRegistered;

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { autoRefreshIfNeeded(); }
                @Override public void onCapabilitiesChanged(Network network,
                                                            NetworkCapabilities capabilities) {
                    if (isValidated(network)) autoRefreshIfNeeded();
                }
            };

    RadarRepository(Context context, ProvinceResolver provinceResolver) {
        this.context = context.getApplicationContext();
        this.provinceResolver = provinceResolver;
        connectivity = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        preferences = this.context.getSharedPreferences("dgt_radar_updates", Context.MODE_PRIVATE);
        database = new Database(this.context);
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

    void close() {
        stop();
        database.close();
    }

    void onLocation(Location location) {
        if (location == null) return;
        lastLocation = new Location(location);
        autoRefreshIfNeeded();
    }

    /** Returns an alert only while the car is approaching/near a fixed or section camera. */
    Alert alert(Location location, Double speedKmh) {
        if (location == null || (location.hasAccuracy() && location.getAccuracy() > MAX_GPS_ACCURACY_METERS)) {
            clearTracking();
            return null;
        }
        int alertDistance = alertDistanceMeters(speedKmh);
        Record record = database.nearest(location.getLatitude(), location.getLongitude(), alertDistance);
        if (record == null) {
            clearTracking();
            return null;
        }
        double tolerance = Math.max(14d, location.hasAccuracy() ? location.getAccuracy() : 14d);
        if (record.id.equals(suppressedId)) {
            // Once the distance is clearly increasing, do not re-show the same alert merely
            // because a later GPS fix jitters by a few metres. It can reappear after a genuine
            // approach (for example, after turning around).
            if (record.distanceMeters >= suppressedDistance - tolerance) {
                suppressedDistance = Math.max(suppressedDistance, record.distanceMeters);
                return null;
            }
            suppressedId = null;
            suppressedDistance = Double.NaN;
        }
        boolean approaching = false;
        if (record.id.equals(trackedId) && Double.isFinite(trackedDistance)) {
            if (record.distanceMeters > trackedDistance + tolerance) {
                suppressedId = record.id;
                suppressedDistance = record.distanceMeters;
                trackedId = null;
                trackedDistance = Double.NaN;
                return null;
            }
            approaching = record.distanceMeters < trackedDistance - Math.min(12d, tolerance);
        }
        trackedId = record.id;
        trackedDistance = record.distanceMeters;
        return new Alert(record.id, record.type, record.road, record.direction,
                record.distanceMeters, record.province, approaching, record.updatedAt);
    }

    void refreshFromInternet(String province, UpdateCallback callback) {
        refreshFromInternet(province, callback, false);
    }

    void refreshNationalFromInternet(UpdateCallback callback) {
        refreshFromInternet("TODAS", callback, false);
    }

    private void refreshFromInternet(String province, UpdateCallback callback, boolean automatic) {
        Network network = automatic ? validatedNetwork() : availableNetwork();
        if (network == null) {
            finish(callback, false, "Android no publica una conexión a Internet utilizable");
            return;
        }
        final boolean national = "TODAS".equalsIgnoreCase(province);
        final String selected = national ? "TODAS" : normalizeProvince(province);
        long now = System.currentTimeMillis();
        if (updateRunning) {
            finish(callback, false, "Ya hay una actualización de radares en curso");
            return;
        }
        if (lastSuccessfulUpdate(selected) > 0L
                && now - lastSuccessfulUpdate(selected) < REFRESH_INTERVAL_MS) {
            finish(callback, false, label(selected) + " ya se actualizó correctamente hace menos de 24 h");
            return;
        }
        updateRunning = true;
        Thread worker = new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(DGT_ENDPOINT);
                connection = (HttpURLConnection) network.openConnection(url);
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(70_000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/xml,text/xml;q=0.9,*/*;q=0.1");
                connection.setRequestProperty("User-Agent", "BMW-E87-iDrive/1.16 (DGT fixed-camera cache)");
                int response = connection.getResponseCode();
                if (response < 200 || response >= 300) throw new IOException("HTTP " + response);
                ArrayList<RawRecord> records;
                try (InputStream input = new BoundedInputStream(connection.getInputStream(),
                        MAX_RESPONSE_BYTES)) {
                    records = parseDgt(input, selected);
                }
                if (records.isEmpty()) throw new IOException("La DGT no publicó fijos/tramo para " + label(selected));
                int imported = national
                        ? database.replaceAll(records, System.currentTimeMillis())
                        : database.replaceProvince(selected, records, System.currentTimeMillis());
                preferences.edit().putLong(successKey(selected), System.currentTimeMillis()).apply();
                lastResult = imported + " radares fijos/tramo guardados · " + label(selected) + " · DGT";
                AppSessionLog.event(TAG, "Actualización correcta · " + lastResult);
                finish(callback, true, lastResult);
            } catch (Exception error) {
                lastResult = "No se pudo actualizar: " + error.getClass().getSimpleName();
                AppSessionLog.event(TAG, "Actualización fallida · " + error.getMessage());
                finish(callback, false, lastResult);
            } finally {
                if (connection != null) connection.disconnect();
                updateRunning = false;
            }
        }, "e87-dgt-radar-update");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    boolean isInternetAvailable() { return availableNetwork() != null; }

    long lastSuccessfulUpdate(String province) {
        return preferences.getLong(successKey("TODAS".equalsIgnoreCase(province)
                ? "TODAS" : normalizeProvince(province)), 0L);
    }

    long lastNationalSuccessfulUpdate() {
        return preferences.getLong(successKey("TODAS"), 0L);
    }

    UpdateStamp lastSuccessfulUpdateStamp() {
        String[] provinces = {"TODAS", "ALICANTE", "MURCIA", "VALENCIA", "ALBACETE"};
        String newestProvince = null;
        long newest = 0L;
        for (String province : provinces) {
            long timestamp = lastSuccessfulUpdate(province);
            if (timestamp > newest) {
                newest = timestamp;
                newestProvince = province;
            }
        }
        return newestProvince == null ? null : new UpdateStamp(newestProvince, newest);
    }

    String diagnostic() {
        return "RADARES DGT LOCALES\n"
                + "registros=" + database.count() + "\n"
                + "semilla=" + seedStatus + "\n"
                + "última actualización=" + lastResult + "\n"
                + "fuente=" + DGT_ENDPOINT + "\n"
                + "solo=fijos y tramo; móviles excluidos\n"
                + "lectura=en local durante la marcha; feed nacional; actualización máx. una vez/24 h\n";
    }

    static String[] supportedProvinceLabels() {
        return new String[]{"España · inventario nacional", "Alicante · provincia completa", "Murcia · provincia completa",
                "Valencia · provincia completa", "Albacete · provincia completa"};
    }

    static String[] supportedProvinceCodes() {
        return new String[]{"TODAS", "ALICANTE", "MURCIA", "VALENCIA", "ALBACETE"};
    }

    static String label(String province) {
        if ("TODAS".equalsIgnoreCase(province)) return "España · nacional";
        switch (normalizeProvince(province)) {
            case "MURCIA": return "Murcia";
            case "VALENCIA": return "Valencia";
            case "ALBACETE": return "Albacete";
            default: return "Alicante";
        }
    }

    private void autoRefreshIfNeeded() {
        if (!active || updateRunning || validatedNetwork() == null) return;
        long now = System.currentTimeMillis();
        if (now - lastAutomaticAttempt < RETRY_INTERVAL_MS) return;
        if (lastNationalSuccessfulUpdate() > 0L
                && now - lastNationalSuccessfulUpdate() < REFRESH_INTERVAL_MS) return;
        lastAutomaticAttempt = now;
        AppSessionLog.event(TAG, "Comprobación automática al iniciar · inventario nacional");
        refreshFromInternet("TODAS", result -> AppSessionLog.event(TAG,
                "Actualización automática " + (result.success ? "correcta" : "omitida/fallida")
                        + " · " + result.message), true);
    }

    private void seedFromAssetsAsync() {
        Thread worker = new Thread(() -> {
            if (database.count() > 0) {
                seedStatus = "Base local existente";
                return;
            }
            try (InputStream allSpain = context.getAssets().open("e87_dgt_radars_spain.xml")) {
                ArrayList<RawRecord> records = parseDgt(allSpain, "TODAS");
                int imported = database.replaceAll(records, System.currentTimeMillis());
                seedStatus = imported + " fijos/tramo DGT nacionales";
                lastResult = "Base nacional inicial: " + seedStatus;
                AppSessionLog.event(TAG, "Semilla nacional local lista · " + lastResult);
            } catch (Exception allSpainUnavailable) {
                seedAlicanteFallback(allSpainUnavailable);
            }
        }, "e87-dgt-radar-seed");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    /** Keeps an Alicante-only starter base if a developer builds without the generated DGT asset. */
    private void seedAlicanteFallback(Exception allSpainUnavailable) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("e87_dgt_radars_alicante.tsv"), StandardCharsets.UTF_8))) {
                ArrayList<RawRecord> records = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') continue;
                    // Older compact seeds wrote the separator as the two literal
                    // characters "\\t" while generated TSV uses a real tab.
                    // Accept both forms so the offline Alicante fallback remains
                    // usable when the national DATEX seed is not packaged.
                    String[] row = line.contains("\\t")
                            ? line.split("\\\\t", 5)
                            : line.split("\\t", 5);
                    if (row.length < 5) continue;
                    records.add(new RawRecord(row[0], row[1], row[2], row[3], row[4], "ALICANTE"));
                }
                int imported = database.replaceProvince("ALICANTE", records, System.currentTimeMillis());
                seedStatus = imported + " fijos DGT de Alicante";
                lastResult = "Base inicial: " + seedStatus;
                AppSessionLog.event(TAG, "Semilla local lista · " + lastResult);
        } catch (Exception error) {
            seedStatus = "Error: " + error.getClass().getSimpleName();
            AppSessionLog.event(TAG, "Semilla local fallida · nacional="
                    + allSpainUnavailable.getClass().getSimpleName() + " · " + error.getMessage());
        }
    }

    private ArrayList<RawRecord> parseDgt(InputStream input, String desiredProvince) throws Exception {
        ArrayList<RawRecord> result = new ArrayList<>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(input, "UTF-8");
        String setType = null;
        RawBuilder current = null;
        int outerDepth = -1;
        String field = null;
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            int event = parser.getEventType();
            String name = parser.getName();
            if (event == XmlPullParser.START_TAG) {
                if ("predefinedLocationSet".equals(name)) {
                    String id = parser.getAttributeValue(null, "id");
                    setType = id != null && id.contains("VelocidadMedia") ? "TRAMO"
                            : id != null && id.contains("CabinasCinemometro") ? "FIJO" : null;
                } else if ("predefinedLocation".equals(name) && setType != null && current == null) {
                    String id = parser.getAttributeValue(null, "id");
                    if (id != null && !id.isEmpty()) {
                        current = new RawBuilder(id, setType, parser.getDepth());
                        outerDepth = parser.getDepth();
                    }
                } else if (current != null) {
                    if ("administrativeArea".equals(name)) field = "province";
                    else if ("roadNumber".equals(name)) field = "road";
                    else if ("directionNamed".equals(name)) field = "direction";
                    else if ("latitude".equals(name)) field = "latitude";
                    else if ("longitude".equals(name)) field = "longitude";
                }
            } else if (event == XmlPullParser.TEXT && current != null && field != null) {
                String value = parser.getText() == null ? "" : parser.getText().trim();
                if (!value.isEmpty()) current.accept(field, value);
            } else if (event == XmlPullParser.END_TAG) {
                if (current != null && "predefinedLocation".equals(name)
                        && parser.getDepth() == outerDepth) {
                    RawRecord record = current.build(desiredProvince);
                    if (record != null) result.add(record);
                    current = null;
                    outerDepth = -1;
                    field = null;
                } else if ("predefinedLocationSet".equals(name)) {
                    setType = null;
                } else if (current != null && ("administrativeArea".equals(name)
                        || "roadNumber".equals(name) || "directionNamed".equals(name)
                        || "latitude".equals(name) || "longitude".equals(name))) {
                    field = null;
                }
            }
        }
        return result;
    }

    private void clearTracking() {
        trackedId = null;
        trackedDistance = Double.NaN;
        suppressedId = null;
        suppressedDistance = Double.NaN;
    }

    private static int alertDistanceMeters(Double speedKmh) {
        double speed = speedKmh == null || !Double.isFinite(speedKmh) ? 0d : Math.max(0d, speedKmh);
        // Around 30 seconds of warning, bounded for urban use and GPS precision.
        return (int) Math.round(Math.max(400d, Math.min(MAX_LOOKUP_METERS, speed / 3.6d * 30d)));
    }

    private static String normalizeProvince(String province) {
        String canonical = canonicalProvince(province);
        return canonical == null ? "ALICANTE" : canonical;
    }

    private static String canonicalProvince(String province) {
        if (province == null) return null;
        String normalized = province.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("ALICANTE") || normalized.contains("ALACANT")) return "ALICANTE";
        if (normalized.contains("MURCIA")) return "MURCIA";
        if (normalized.contains("VALENCIA") || normalized.contains("VALÈNCIA")) return "VALENCIA";
        if (normalized.contains("ALBACETE")) return "ALBACETE";
        return null;
    }

    private static boolean provinceMatches(String dgtProvince, String desired) {
        if ("TODAS".equals(desired)) return true;
        String actual = canonicalProvince(dgtProvince);
        String expected = canonicalProvince(desired);
        return actual != null && actual.equals(expected);
    }

    private static String storageProvince(String dgtProvince) {
        String canonical = canonicalProvince(dgtProvince);
        if (canonical != null) return canonical;
        String upper = dgtProvince == null ? "" : dgtProvince.trim().toUpperCase(Locale.ROOT);
        return upper.isEmpty() ? "DGT" : upper;
    }

    private static String successKey(String province) {
        return "last_success_" + ("TODAS".equalsIgnoreCase(province)
                ? "TODAS" : normalizeProvince(province));
    }

    private Network availableNetwork() {
        if (connectivity == null) return null;
        try {
            Network activeNetwork = connectivity.getActiveNetwork();
            if (hasInternetCapability(activeNetwork)) return activeNetwork;
            for (Network candidate : connectivity.getAllNetworks()) {
                if (hasInternetCapability(candidate)) return candidate;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Background updates run only after Android confirms real Internet access. */
    private Network validatedNetwork() {
        if (connectivity == null) return null;
        try {
            Network activeNetwork = connectivity.getActiveNetwork();
            if (isValidated(activeNetwork)) return activeNetwork;
            for (Network candidate : connectivity.getAllNetworks()) {
                if (isValidated(candidate)) return candidate;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private boolean hasInternetCapability(Network network) {
        if (network == null || connectivity == null) return false;
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private boolean isValidated(Network network) {
        if (!hasInternetCapability(network)) return false;
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
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

    private void finish(UpdateCallback callback, boolean success, String message) {
        if (callback != null) main.post(() -> callback.onFinished(new UpdateResult(success, message)));
    }

    interface UpdateCallback { void onFinished(UpdateResult result); }

    static final class UpdateResult {
        final boolean success;
        final String message;
        UpdateResult(boolean success, String message) { this.success = success; this.message = message; }
    }

    static final class UpdateStamp {
        final String province;
        final long timestamp;
        UpdateStamp(String province, long timestamp) { this.province = province; this.timestamp = timestamp; }
    }

    static final class Alert {
        final String id, type, road, direction, province;
        final double distanceMeters;
        final boolean approaching;
        final long updatedAt;
        Alert(String id, String type, String road, String direction, double distanceMeters,
              String province, boolean approaching, long updatedAt) {
            this.id = id; this.type = type; this.road = road; this.direction = direction;
            this.distanceMeters = distanceMeters; this.province = province;
            this.approaching = approaching; this.updatedAt = updatedAt;
        }
    }

    private static final class RawRecord {
        final String id, type, road, direction, points, province;
        RawRecord(String id, String type, String road, String direction, String points, String province) {
            this.id = id; this.type = type; this.road = road; this.direction = direction;
            this.points = points; this.province = province;
        }
    }

    private static final class RawBuilder {
        final String id, type;
        String province = "", road = "", direction = "", pendingLatitude;
        final ArrayList<String> points = new ArrayList<>();
        RawBuilder(String id, String type, int ignoredDepth) { this.id = id; this.type = type; }
        void accept(String field, String value) {
            if ("province".equals(field) && province.isEmpty()) province = value;
            else if ("road".equals(field) && road.isEmpty()) road = value;
            else if ("direction".equals(field) && direction.isEmpty()) direction = value;
            else if ("latitude".equals(field)) pendingLatitude = value;
            else if ("longitude".equals(field) && pendingLatitude != null) {
                try {
                    double lat = Double.parseDouble(pendingLatitude);
                    double lon = Double.parseDouble(value);
                    if (Math.abs(lat) <= 90d && Math.abs(lon) <= 180d) {
                        points.add(String.format(Locale.ROOT, "%.6f,%.6f", lat, lon));
                    }
                } catch (Exception ignored) { }
                pendingLatitude = null;
            }
        }
        RawRecord build(String desiredProvince) {
            if (!provinceMatches(province, desiredProvince) || points.isEmpty()) return null;
            StringBuilder geometry = new StringBuilder();
            for (String point : points) {
                if (geometry.indexOf(point) >= 0) continue;
                if (geometry.length() > 0) geometry.append(';');
                geometry.append(point);
            }
            String storage = "TODAS".equals(desiredProvince)
                    ? storageProvince(province) : normalizeProvince(desiredProvince);
            return geometry.length() == 0 ? null : new RawRecord(id, type, road, direction,
                    geometry.toString(), storage);
        }
    }

    private static final class Record {
        final String id, type, road, direction, province, points;
        final double distanceMeters;
        final long updatedAt;
        Record(String id, String type, String road, String direction, String province, String points,
               double distanceMeters, long updatedAt) {
            this.id = id; this.type = type; this.road = road; this.direction = direction;
            this.province = province; this.points = points; this.distanceMeters = distanceMeters;
            this.updatedAt = updatedAt;
        }
    }

    private static final class Database extends SQLiteOpenHelper {
        private static final String NAME = "e87_dgt_radars.db";
        Database(Context context) { super(context, NAME, null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE radars (id TEXT PRIMARY KEY, type TEXT NOT NULL, road TEXT NOT NULL, "
                    + "direction TEXT NOT NULL, points TEXT NOT NULL, province TEXT NOT NULL, updated_at INTEGER NOT NULL, "
                    + "min_lat REAL NOT NULL, max_lat REAL NOT NULL, min_lon REAL NOT NULL, max_lon REAL NOT NULL)");
            db.execSQL("CREATE INDEX radar_bounds ON radars(min_lat, max_lat, min_lon, max_lon)");
            db.execSQL("CREATE INDEX radar_province ON radars(province)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

        synchronized int replaceProvince(String province, ArrayList<RawRecord> records, long now) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            int imported = 0;
            try {
                db.delete("radars", "province = ?", new String[]{province});
                imported = insertRecords(db, records, now);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            return imported;
        }

        synchronized int replaceAll(ArrayList<RawRecord> records, long now) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete("radars", null, null);
                int imported = insertRecords(db, records, now);
                db.setTransactionSuccessful();
                return imported;
            } finally { db.endTransaction(); }
        }

        private static int insertRecords(SQLiteDatabase db, ArrayList<RawRecord> records, long now) {
            int imported = 0;
            for (RawRecord record : records) {
                double[] bounds = bounds(record.points);
                if (bounds == null) continue;
                ContentValues values = new ContentValues();
                values.put("id", record.id);
                values.put("type", record.type);
                values.put("road", record.road == null ? "" : record.road);
                values.put("direction", record.direction == null ? "" : record.direction);
                values.put("points", record.points);
                values.put("province", record.province);
                values.put("updated_at", now);
                values.put("min_lat", bounds[0]); values.put("max_lat", bounds[1]);
                values.put("min_lon", bounds[2]); values.put("max_lon", bounds[3]);
                if (db.insertWithOnConflict("radars", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1) imported++;
            }
            return imported;
        }

        synchronized Record nearest(double latitude, double longitude, int maxDistanceMeters) {
            double latDelta = maxDistanceMeters / 111_320d;
            double lonDelta = maxDistanceMeters / Math.max(1d, 111_320d * Math.cos(Math.toRadians(latitude)));
            String selection = "min_lat <= ? AND max_lat >= ? AND min_lon <= ? AND max_lon >= ?";
            String[] args = {String.valueOf(latitude + latDelta), String.valueOf(latitude - latDelta),
                    String.valueOf(longitude + lonDelta), String.valueOf(longitude - lonDelta)};
            Record result = null;
            try (Cursor cursor = getReadableDatabase().query("radars",
                    new String[]{"id", "type", "road", "direction", "province", "points", "updated_at"},
                    selection, args, null, null, null)) {
                while (cursor.moveToNext()) {
                    String points = cursor.getString(5);
                    double distance = nearestDistance(latitude, longitude, points);
                    if (distance > maxDistanceMeters || (result != null && distance >= result.distanceMeters)) continue;
                    result = new Record(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                            cursor.getString(3), cursor.getString(4), points, distance, cursor.getLong(6));
                }
            }
            return result;
        }

        synchronized int count() {
            try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM radars", null)) {
                return cursor.moveToFirst() ? cursor.getInt(0) : 0;
            }
        }

        private static double[] bounds(String points) {
            double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
            int valid = 0;
            for (String point : points.split(";")) {
                String[] pair = point.split(",");
                if (pair.length != 2) continue;
                try {
                    double lat = Double.parseDouble(pair[0]), lon = Double.parseDouble(pair[1]);
                    minLat = Math.min(minLat, lat); maxLat = Math.max(maxLat, lat);
                    minLon = Math.min(minLon, lon); maxLon = Math.max(maxLon, lon); valid++;
                } catch (Exception ignored) { }
            }
            return valid == 0 ? null : new double[]{minLat, maxLat, minLon, maxLon};
        }

        private static double nearestDistance(double latitude, double longitude, String points) {
            double nearest = Double.MAX_VALUE;
            for (String point : points.split(";")) {
                String[] pair = point.split(",");
                if (pair.length != 2) continue;
                try {
                    float[] result = new float[1];
                    Location.distanceBetween(latitude, longitude, Double.parseDouble(pair[0]),
                            Double.parseDouble(pair[1]), result);
                    nearest = Math.min(nearest, result[0]);
                } catch (Exception ignored) { }
            }
            return nearest;
        }
    }

    /** Limits the official XML response before it can consume the head unit memory. */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream input;
        private final int maxBytes;
        private int read;
        BoundedInputStream(InputStream input, int maxBytes) { this.input = new BufferedInputStream(input); this.maxBytes = maxBytes; }
        @Override public int read() throws IOException {
            int value = input.read();
            if (value >= 0 && ++read > maxBytes) throw new IOException("Respuesta DGT demasiado grande");
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int value = input.read(buffer, offset, length);
            if (value > 0 && (read += value) > maxBytes) throw new IOException("Respuesta DGT demasiado grande");
            return value;
        }
        @Override public void close() throws IOException { input.close(); }
    }
}
