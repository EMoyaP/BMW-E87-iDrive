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
 * Read-only offline cache of DGT INVIVE speed-watch-intensification sections.
 * INVIVE is a surveillance-zone inventory, not a speed-limit or radar database.
 */
final class InviveRepository {
    private static final String TAG = "INVIVE DGT";
    private static final String ENDPOINT =
            "https://infocar.dgt.es/datex2/dgt/PredefinedLocationsPublication/tramos_invive/content.xml";
    private static final long UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1_000L;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int BUNDLED_SEED_VERSION = 1;
    private static final double STRICT_CORRIDOR_METERS = 180d;
    private static final long ACTIVE_HYSTERESIS_MS = 4_000L;

    private final Context context;
    private final ConnectivityManager connectivity;
    private final SharedPreferences preferences;
    private final Database database;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean updateRunning;
    private volatile String seedStatus = "Pendiente";
    private volatile String lastResult = "Base local · preparando INVIVE";
    private volatile String activeId;
    private volatile long activeSeenAt;
    private volatile boolean seedReady;
    private volatile String pendingAutoProvince = "ALICANTE";

    InviveRepository(Context context) {
        this.context = context.getApplicationContext();
        connectivity = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        preferences = this.context.getSharedPreferences("dgt_invive_updates", Context.MODE_PRIVATE);
        database = new Database(this.context);
        seedFromAssetsAsync();
    }

    void start(String province) {
        pendingAutoProvince = canonicalProvince(province);
        if (seedReady) autoRefreshIfNeeded(pendingAutoProvince);
    }

    void stop() { }

    void close() { database.close(); }

    Alert alert(Location location, SpeedLimitRepository.Match roadMatch, Double speedKmh) {
        if (location == null || location.getLatitude() == 0d && location.getLongitude() == 0d) return null;
        String currentRoad = canonicalRoad(roadMatch == null ? "" : roadMatch.roadRef);
        ArrayList<Record> candidates = database.near(location.getLatitude(), location.getLongitude(), 2_500d);
        Record best = null;
        double bestScore = Double.MAX_VALUE;
        boolean bestInside = false;
        double bestDistance = Double.MAX_VALUE;
        for (Record record : candidates) {
            boolean sameRoad = !currentRoad.isEmpty() && currentRoad.equals(canonicalRoad(record.road));
            if (!currentRoad.isEmpty() && !sameRoad) continue;
            Projection projection = project(location.getLatitude(), location.getLongitude(),
                    record.latA, record.lonA, record.latB, record.lonB);
            double endpointDistance = Math.min(distance(location.getLatitude(), location.getLongitude(),
                    record.latA, record.lonA), distance(location.getLatitude(), location.getLongitude(),
                    record.latB, record.lonB));
            double sectionLength = distance(record.latA, record.lonA, record.latB, record.lonB);
            double ellipseExcess = distance(location.getLatitude(), location.getLongitude(),
                    record.latA, record.lonA) + distance(location.getLatitude(), location.getLongitude(),
                    record.latB, record.lonB) - sectionLength;
            double curvedRoadTolerance = Math.max(1_200d, sectionLength * .12d);
            boolean inside = projection.fraction >= 0d && projection.fraction <= 1d
                    && (sameRoad ? ellipseExcess <= curvedRoadTolerance
                    : projection.distanceMeters <= STRICT_CORRIDOR_METERS && ellipseExcess <= 500d);
            double approachRange = speedKmh == null ? 500d : Math.max(500d, Math.min(1_200d, speedKmh * 8d));
            boolean approaching = !inside && endpointDistance <= approachRange
                    && isApproaching(location, record, endpointDistance)
                    && (sameRoad || endpointDistance <= STRICT_CORRIDOR_METERS);
            if (!inside && !approaching) continue;
            double score = (inside ? 0d : 10_000d) + (sameRoad ? 0d : 2_000d)
                    + Math.min(projection.distanceMeters, endpointDistance);
            if (score < bestScore) {
                best = record;
                bestScore = score;
                bestInside = inside;
                bestDistance = inside ? 0d : endpointDistance;
            }
        }
        long now = System.currentTimeMillis();
        if (best == null) {
            if (activeId != null && now - activeSeenAt <= ACTIVE_HYSTERESIS_MS) {
                Record retained = database.byId(activeId);
                if (retained != null) return new Alert(retained.id, retained.road, retained.province,
                        0d, true, false);
            }
            activeId = null;
            return null;
        }
        activeId = best.id;
        activeSeenAt = now;
        return new Alert(best.id, best.road, best.province, bestDistance, bestInside, !bestInside);
    }

    private static boolean isApproaching(Location location, Record record, double nearestDistance) {
        if (!location.hasBearing() || !location.hasSpeed() || location.getSpeed() < 2.5f) return false;
        Location a = point(record.latA, record.lonA);
        Location b = point(record.latB, record.lonB);
        Location target = location.distanceTo(a) <= location.distanceTo(b) ? a : b;
        double difference = angularDifference(location.getBearing(), location.bearingTo(target));
        return nearestDistance < 80d || difference <= 50d;
    }

    void refreshFromInternet(String requestedProvince, UpdateCallback callback) {
        boolean national = "TODAS".equalsIgnoreCase(requestedProvince);
        String province = national ? "TODAS" : canonicalProvince(requestedProvince);
        if (!seedReady) {
            finish(callback, false, "Preparando la base local INVIVE");
            return;
        }
        if (updateRunning) {
            finish(callback, false, "Ya hay una actualización INVIVE en curso");
            return;
        }
        long last = preferences.getLong("success_" + province, 0L);
        if (last > 0L && System.currentTimeMillis() - last < UPDATE_INTERVAL_MS) {
            finish(callback, false, provinceLabel(province) + " ya tiene INVIVE actualizado hace menos de 24 h");
            return;
        }
        Network network = validatedNetwork();
        if (network == null) {
            finish(callback, false, "Sin Internet validado para actualizar INVIVE");
            return;
        }
        updateRunning = true;
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) network.openConnection(new URL(ENDPOINT));
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(70_000);
                connection.setRequestProperty("User-Agent", "BMW-E87-iDrive/1.23 (DGT INVIVE offline cache)");
                int response = connection.getResponseCode();
                if (response < 200 || response >= 300) throw new IOException("HTTP " + response);
                ArrayList<Record> records;
                try (InputStream input = limited(connection.getInputStream(), MAX_RESPONSE_BYTES)) {
                    records = parseDgt(input, province);
                }
                if (records.isEmpty()) throw new IOException("INVIVE no publicó tramos para " + provinceLabel(province));
                int imported = national ? database.replaceAll(records) : database.replaceProvince(province, records);
                long now = System.currentTimeMillis();
                preferences.edit().putLong("success_" + province, now).apply();
                lastResult = imported + " zonas guardadas · " + provinceLabel(province) + " · DGT";
                AppSessionLog.event(TAG, "Actualización correcta · " + lastResult);
                finish(callback, true, lastResult);
            } catch (Exception error) {
                lastResult = "No se pudo actualizar INVIVE: " + error.getClass().getSimpleName();
                AppSessionLog.event(TAG, "Actualización fallida · " + error.getMessage());
                finish(callback, false, lastResult);
            } finally {
                updateRunning = false;
                if (connection != null) connection.disconnect();
            }
        }, "e87-invive-update").start();
    }

    void refreshNationalFromInternet(UpdateCallback callback) {
        refreshFromInternet("TODAS", callback);
    }

    long lastSuccessfulUpdate(String province) {
        return preferences.getLong("success_" + ("TODAS".equalsIgnoreCase(province)
                ? "TODAS" : canonicalProvince(province)), 0L);
    }

    String diagnostic() {
        return "ZONAS INVIVE DGT\n"
                + "registros locales=" + database.count() + "\n"
                + "semilla=" + seedStatus + "\n"
                + "última actualización=" + lastResult + "\n"
                + "fuente=" + ENDPOINT + "\n"
                + "uso=aviso de zona de vigilancia; no radar y no límite de velocidad\n";
    }

    private void autoRefreshIfNeeded(String province) {
        String selected = "TODAS";
        if (validatedNetwork() == null || System.currentTimeMillis()
                - preferences.getLong("success_" + selected, 0L) < UPDATE_INTERVAL_MS) return;
        refreshNationalFromInternet(result -> AppSessionLog.event(TAG,
                "Actualización automática " + (result.success ? "correcta" : "omitida/fallida")
                        + " · " + result.message));
    }

    private void seedFromAssetsAsync() {
        new Thread(() -> {
            try {
                int installedVersion = preferences.getInt("bundled_seed_version", 0);
                if (installedVersion >= BUNDLED_SEED_VERSION && database.count() > 0) {
                    seedStatus = database.count() + " zonas locales conservadas";
                    return;
                }
                try (InputStream national = context.getAssets().open("e87_dgt_invive_spain.xml")) {
                    ArrayList<Record> records = parseDgt(national, "TODAS");
                    int imported = database.replaceAll(records);
                    if (imported <= 0) throw new IOException("Semilla INVIVE nacional vacía");
                    seedStatus = imported + " zonas INVIVE nacionales";
                    preferences.edit().putInt("bundled_seed_version", BUNDLED_SEED_VERSION).apply();
                    AppSessionLog.event(TAG, "Semilla instalada · " + seedStatus);
                    return;
                } catch (Exception ignored) { }
                int imported = importAlicanteFallback();
                seedStatus = imported + " zonas INVIVE de Alicante";
                if (imported > 0) preferences.edit()
                        .putInt("bundled_seed_version", BUNDLED_SEED_VERSION).apply();
                AppSessionLog.event(TAG, "Semilla de respaldo instalada · " + seedStatus);
            } finally {
                seedReady = true;
                main.post(() -> autoRefreshIfNeeded(pendingAutoProvince));
            }
        }, "e87-invive-seed").start();
    }

    private int importAlicanteFallback() {
        ArrayList<Record> records = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("e87_dgt_invive_alicante.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] c = line.split("\\t", -1);
                if (c.length < 10) continue;
                records.add(new Record(c[0], canonicalProvince(c[1]), c[2], c[3],
                        number(c[4]), number(c[5]), number(c[6]), number(c[7]),
                        number(c[8]), number(c[9])));
            }
        } catch (Exception error) {
            AppSessionLog.event(TAG, "Semilla de respaldo fallida · " + error.getMessage());
        }
        return database.replaceProvince("ALICANTE", records);
    }

    private static ArrayList<Record> parseDgt(InputStream input, String desiredProvince) throws Exception {
        ArrayList<Record> records = new ArrayList<>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(input), StandardCharsets.UTF_8.name());
        Builder current = null;
        int currentDepth = -1;
        boolean inCoordinates = false;
        boolean inAdministrativeArea = false;
        String pendingLat = null;
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            String name = parser.getName();
            if (event == XmlPullParser.START_TAG) {
                if ("predefinedLocation".equals(name) && parser.getAttributeValue(null, "id") != null) {
                    current = new Builder(parser.getAttributeValue(null, "id"));
                    currentDepth = parser.getDepth();
                } else if (current != null && "pointCoordinates".equals(name)) {
                    inCoordinates = true;
                    pendingLat = null;
                } else if (current != null && "administrativeArea".equals(name)) {
                    inAdministrativeArea = true;
                } else if (current != null && ("latitude".equals(name) || "longitude".equals(name)
                        || "roadNumber".equals(name) || "directionRelative".equals(name)
                        || "referencePointDistance".equals(name)
                        || inAdministrativeArea && "value".equals(name))) {
                    String text = parser.nextText().trim();
                    if ("latitude".equals(name) && inCoordinates) pendingLat = text;
                    else if ("longitude".equals(name) && inCoordinates && pendingLat != null) {
                        current.coordinates.add(new double[]{number(pendingLat), number(text)});
                    } else if ("roadNumber".equals(name) && current.road.isEmpty()) current.road = text;
                    else if ("directionRelative".equals(name) && current.direction.isEmpty()) current.direction = text;
                    else if ("referencePointDistance".equals(name)) current.pk.add(number(text));
                    else if (inAdministrativeArea && "value".equals(name) && current.province.isEmpty()) {
                        current.province = canonicalProvince(text);
                    }
                }
            } else if (event == XmlPullParser.END_TAG && current != null) {
                if ("pointCoordinates".equals(name)) inCoordinates = false;
                if ("administrativeArea".equals(name)) inAdministrativeArea = false;
                if ("predefinedLocation".equals(name) && parser.getDepth() == currentDepth) {
                    Record record = current.build();
                    if (record != null && ("TODAS".equals(desiredProvince)
                            || desiredProvince.equals(record.province))) records.add(record);
                    current = null;
                    currentDepth = -1;
                }
            }
        }
        return records;
    }

    private Network validatedNetwork() {
        if (connectivity == null) return null;
        try {
            Network active = connectivity.getActiveNetwork();
            if (isValidated(active)) return active;
            for (Network candidate : connectivity.getAllNetworks()) if (isValidated(candidate)) return candidate;
        } catch (Exception ignored) { }
        return null;
    }

    private boolean isValidated(Network network) {
        if (network == null || connectivity == null) return false;
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void finish(UpdateCallback callback, boolean success, String message) {
        if (callback != null) main.post(() -> callback.onFinished(new UpdateResult(success, message)));
    }

    private static InputStream limited(InputStream input, int maxBytes) {
        return new java.io.FilterInputStream(input) {
            int read;
            @Override public int read() throws IOException {
                int value = super.read();
                if (value >= 0 && ++read > maxBytes) throw new IOException("Respuesta INVIVE demasiado grande");
                return value;
            }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                int count = super.read(buffer, offset, length);
                if (count > 0 && (read += count) > maxBytes) throw new IOException("Respuesta INVIVE demasiado grande");
                return count;
            }
        };
    }

    static String canonicalProvince(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("ALICANTE") || normalized.contains("ALACANT")) return "ALICANTE";
        if (normalized.contains("MURCIA")) return "MURCIA";
        if (normalized.contains("VALENCIA") || normalized.contains("VALÈNCIA")) return "VALENCIA";
        if (normalized.contains("ALBACETE")) return "ALBACETE";
        if (normalized.isEmpty() || "AUTO".equals(normalized)) return "ALICANTE";
        return "TODAS".equals(normalized) ? "TODAS" : normalized;
    }

    static String provinceLabel(String value) {
        String province = canonicalProvince(value);
        if ("ALICANTE".equals(province)) return "Alicante";
        if ("MURCIA".equals(province)) return "Murcia";
        if ("VALENCIA".equals(province)) return "Valencia";
        if ("ALBACETE".equals(province)) return "Albacete";
        return "España";
    }

    private static String canonicalRoad(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replace(" ", "").trim();
    }

    private static double number(String value) {
        try { return Double.parseDouble(value); } catch (Exception ignored) { return 0d; }
    }

    private static Location point(double lat, double lon) {
        Location result = new Location("invive");
        result.setLatitude(lat);
        result.setLongitude(lon);
        return result;
    }

    private static double distance(double latA, double lonA, double latB, double lonB) {
        float[] result = new float[1];
        Location.distanceBetween(latA, lonA, latB, lonB, result);
        return result[0];
    }

    static double angularDifference(double first, double second) {
        double difference = Math.abs(first - second) % 360d;
        return difference > 180d ? 360d - difference : difference;
    }

    private static Projection project(double lat, double lon, double latA, double lonA,
                                      double latB, double lonB) {
        double metersLat = 110_540d;
        double metersLon = 111_320d * Math.cos(Math.toRadians(lat));
        double ax = (lonA - lon) * metersLon;
        double ay = (latA - lat) * metersLat;
        double bx = (lonB - lon) * metersLon;
        double by = (latB - lat) * metersLat;
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared <= .0001d) return new Projection(Math.hypot(ax, ay), 0d);
        double fraction = -(ax * dx + ay * dy) / lengthSquared;
        double clamped = Math.max(0d, Math.min(1d, fraction));
        return new Projection(Math.hypot(ax + clamped * dx, ay + clamped * dy), fraction);
    }

    interface UpdateCallback { void onFinished(UpdateResult result); }

    static final class UpdateResult {
        final boolean success;
        final String message;
        UpdateResult(boolean success, String message) { this.success = success; this.message = message; }
    }

    static final class Alert {
        final String id;
        final String road;
        final String province;
        final double distanceMeters;
        final boolean inside;
        final boolean approaching;
        Alert(String id, String road, String province, double distanceMeters,
              boolean inside, boolean approaching) {
            this.id = id;
            this.road = road;
            this.province = province;
            this.distanceMeters = distanceMeters;
            this.inside = inside;
            this.approaching = approaching;
        }
    }

    private static final class Projection {
        final double distanceMeters;
        final double fraction;
        Projection(double distanceMeters, double fraction) {
            this.distanceMeters = distanceMeters;
            this.fraction = fraction;
        }
    }

    private static final class Builder {
        final String id;
        final ArrayList<double[]> coordinates = new ArrayList<>();
        final ArrayList<Double> pk = new ArrayList<>();
        String road = "";
        String province = "";
        String direction = "";
        Builder(String id) { this.id = id; }
        Record build() {
            if (coordinates.size() < 2 || road.isEmpty() || province.isEmpty()) return null;
            double[] a = coordinates.get(0);
            double[] b = coordinates.get(1);
            return new Record(id, province, road, direction.isEmpty() ? "both" : direction,
                    a[0], a[1], b[0], b[1], pk.isEmpty() ? 0d : pk.get(0),
                    pk.size() < 2 ? 0d : pk.get(1));
        }
    }

    private static final class Record {
        final String id, province, road, direction;
        final double latA, lonA, latB, lonB, pkA, pkB;
        Record(String id, String province, String road, String direction, double latA,
               double lonA, double latB, double lonB, double pkA, double pkB) {
            this.id = id;
            this.province = province;
            this.road = road;
            this.direction = direction;
            this.latA = latA;
            this.lonA = lonA;
            this.latB = latB;
            this.lonB = lonB;
            this.pkA = pkA;
            this.pkB = pkB;
        }
    }

    private static final class Database extends SQLiteOpenHelper {
        private static final String NAME = "e87_dgt_invive.db";
        Database(Context context) { super(context, NAME, null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE invive (id TEXT PRIMARY KEY, province TEXT NOT NULL, road TEXT NOT NULL, "
                    + "direction TEXT NOT NULL, lat_a REAL NOT NULL, lon_a REAL NOT NULL, lat_b REAL NOT NULL, "
                    + "lon_b REAL NOT NULL, pk_a REAL NOT NULL, pk_b REAL NOT NULL, min_lat REAL NOT NULL, "
                    + "max_lat REAL NOT NULL, min_lon REAL NOT NULL, max_lon REAL NOT NULL)");
            db.execSQL("CREATE INDEX invive_bounds ON invive(min_lat,max_lat,min_lon,max_lon)");
            db.execSQL("CREATE INDEX invive_province ON invive(province)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }
        int replaceAll(ArrayList<Record> records) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete("invive", null, null);
                int count = insert(db, records);
                db.setTransactionSuccessful();
                return count;
            } finally { db.endTransaction(); }
        }
        int replaceProvince(String province, ArrayList<Record> records) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete("invive", "province=?", new String[]{province});
                int count = insert(db, records);
                db.setTransactionSuccessful();
                return count;
            } finally { db.endTransaction(); }
        }
        private int insert(SQLiteDatabase db, ArrayList<Record> records) {
            int count = 0;
            for (Record record : records) {
                ContentValues values = new ContentValues();
                values.put("id", record.id);
                values.put("province", record.province);
                values.put("road", record.road);
                values.put("direction", record.direction);
                values.put("lat_a", record.latA);
                values.put("lon_a", record.lonA);
                values.put("lat_b", record.latB);
                values.put("lon_b", record.lonB);
                values.put("pk_a", record.pkA);
                values.put("pk_b", record.pkB);
                values.put("min_lat", Math.min(record.latA, record.latB));
                values.put("max_lat", Math.max(record.latA, record.latB));
                values.put("min_lon", Math.min(record.lonA, record.lonB));
                values.put("max_lon", Math.max(record.lonA, record.lonB));
                if (db.insertWithOnConflict("invive", null, values,
                        SQLiteDatabase.CONFLICT_REPLACE) != -1) count++;
            }
            return count;
        }
        ArrayList<Record> near(double lat, double lon, double radiusMeters) {
            double latDelta = radiusMeters / 111_320d;
            double lonDelta = radiusMeters / Math.max(1d, 111_320d * Math.cos(Math.toRadians(lat)));
            String selection = "min_lat<=? AND max_lat>=? AND min_lon<=? AND max_lon>=?";
            String[] args = {String.valueOf(lat + latDelta), String.valueOf(lat - latDelta),
                    String.valueOf(lon + lonDelta), String.valueOf(lon - lonDelta)};
            ArrayList<Record> result = new ArrayList<>();
            try (Cursor cursor = getReadableDatabase().query("invive", null, selection, args,
                    null, null, null)) {
                while (cursor.moveToNext()) result.add(read(cursor));
            }
            return result;
        }
        Record byId(String id) {
            try (Cursor cursor = getReadableDatabase().query("invive", null, "id=?",
                    new String[]{id}, null, null, null)) {
                return cursor.moveToFirst() ? read(cursor) : null;
            }
        }
        int count() {
            try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM invive", null)) {
                return cursor.moveToFirst() ? cursor.getInt(0) : 0;
            }
        }
        private static Record read(Cursor cursor) {
            return new Record(cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    cursor.getString(cursor.getColumnIndexOrThrow("province")),
                    cursor.getString(cursor.getColumnIndexOrThrow("road")),
                    cursor.getString(cursor.getColumnIndexOrThrow("direction")),
                    cursor.getDouble(cursor.getColumnIndexOrThrow("lat_a")),
                    cursor.getDouble(cursor.getColumnIndexOrThrow("lon_a")),
                    cursor.getDouble(cursor.getColumnIndexOrThrow("lat_b")),
                    cursor.getDouble(cursor.getColumnIndexOrThrow("lon_b")),
                    cursor.getDouble(cursor.getColumnIndexOrThrow("pk_a")),
                    cursor.getDouble(cursor.getColumnIndexOrThrow("pk_b")));
        }
    }
}
