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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * Offline cache for the national DGT speed-limit TN-ITS feed.
 *
 * The feed is a weekly delta, not a complete road graph.  Records are therefore kept as an
 * append-only change history plus an active/tombstone table.  A bundled feed is explicitly
 * labelled as a partial weekly seed; OSM remains the fallback when this layer has no verified
 * record for the current road and direction.
 */
final class DgtSpeedRepository {
    interface UpdateCallback { void onFinished(UpdateResult result); }

    static final String ENDPOINT = "https://infocar.dgt.es/tnits/limitesVelocidad.xml";
    private static final String XML_ASSET = "e87_dgt_speed_limits_spain.xml";
    private static final String TSV_ASSET = "e87_dgt_speed_limits_weekly_seed.tsv";
    private static final String TAG = "LÍMITES DGT";
    private static final long UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1_000L;
    private static final long RETRY_INTERVAL_MS = 30L * 60L * 1_000L;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final double MAX_MATCH_METERS = 180d;
    private static final int BUNDLED_SEED_VERSION = 1;

    private final Context context;
    private final ConnectivityManager connectivity;
    private final SharedPreferences preferences;
    private final Database database;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean active;
    private volatile boolean updateRunning;
    private volatile long lastAutomaticAttempt;
    private volatile String lastResult = "Base DGT nacional pendiente";
    private volatile String seedStatus = "Pendiente";

    DgtSpeedRepository(Context context) {
        this.context = context.getApplicationContext();
        connectivity = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        preferences = this.context.getSharedPreferences("dgt_speed_updates", Context.MODE_PRIVATE);
        database = new Database(this.context);
        seedFromAssetsAsync();
    }

    void start() {
        active = true;
        autoRefreshIfNeeded();
    }

    void stop() { active = false; }

    void close() {
        stop();
        database.close();
    }

    /** National update. Province is only a presentation/filter concern, never a download scope. */
    void refreshFromInternet(UpdateCallback callback) {
        Network network = availableNetwork();
        if (network == null) {
            finish(callback, false, "Android no publica una conexión a Internet utilizable");
            return;
        }
        long now = System.currentTimeMillis();
        if (updateRunning) {
            finish(callback, false, "Ya hay una actualización DGT nacional en curso");
            return;
        }
        long last = preferences.getLong("last_success_national", 0L);
        if (last > 0L && now - last < UPDATE_INTERVAL_MS) {
            finish(callback, false, "DGT nacional ya se descargó correctamente hace menos de 24 h");
            return;
        }
        updateRunning = true;
        new Thread(() -> download(network, callback), "e87-dgt-speed-update").start();
    }

    boolean isInternetAvailable() { return availableNetwork() != null; }

    long lastSuccessfulUpdate() {
        return preferences.getLong("last_success_national", 0L);
    }

    long lastChecked() { return preferences.getLong("last_checked", 0L); }

    String seedStatus() { return seedStatus; }

    /** Matches only a DGT record on the same road and close to the current mapped geometry. */
    Match lookup(Location location, SpeedLimitRepository.Match roadMatch) {
        if (location == null || roadMatch == null || roadMatch.roadRef.trim().isEmpty()) return null;
        String road = normalizeRoad(roadMatch.roadRef);
        if (road.isEmpty()) return null;
        int radius = (int) Math.round(Math.max(110d,
                Math.min(MAX_MATCH_METERS, location.hasAccuracy() ? location.getAccuracy() * 2d : 150d)));
        ArrayList<Record> candidates = database.near(location.getLatitude(), location.getLongitude(), radius);
        Record best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Record record : candidates) {
            if (!road.equals(normalizeRoad(record.roadRef)) || record.speedKmh <= 0) continue;
            if (!directionApplies(record, location, roadMatch)) continue;
            double distance = nearestDistance(location.getLatitude(), location.getLongitude(), record.geometry);
            if (distance > radius || distance >= bestDistance) continue;
            best = record;
            bestDistance = distance;
        }
        if (best == null) return null;
        return new Match(best.id, best.speedKmh, best.roadRef, best.direction,
                bestDistance, best.changedAt, best.geometryCrs);
    }

    String diagnostic() {
        return "LÍMITES DGT NACIONALES\n"
                + "registros activos=" + database.count("ACTIVE")
                + " · bajas/tombstones=" + database.count("REMOVED") + "\n"
                + "semilla=" + seedStatus + "\n"
                + "última descarga=" + lastResult + "\n"
                + "última correcta=" + lastSuccessfulUpdate()
                + " · última comprobación=" + lastChecked() + "\n"
                + "fuente=" + ENDPOINT + "\n"
                + "alcance=feed nacional completo; consulta local por carretera y sentido\n"
                + "geometría=EPSG explícito si se publica; fallback observado UTM30 guardado como inferido\n"
                + "prioridad=DGT confirmado > OSM maxspeed > recomendación por clase > sin dato\n";
    }

    private void download(Network network, UpdateCallback callback) {
        HttpURLConnection connection = null;
        boolean success = false;
        String message;
        try {
            connection = (HttpURLConnection) network.openConnection(new URL(ENDPOINT));
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(70_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/xml,text/xml;q=0.9,*/*;q=0.1");
            connection.setRequestProperty("User-Agent", "BMW-E87-iDrive/1.23 (DGT offline speed cache)");
            String etag = preferences.getString("etag", "");
            String modified = preferences.getString("last_modified", "");
            if (!etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);
            if (!modified.isEmpty()) connection.setRequestProperty("If-Modified-Since", modified);
            int response = connection.getResponseCode();
            long checked = System.currentTimeMillis();
            preferences.edit().putLong("last_checked", checked).apply();
            if (response == HttpURLConnection.HTTP_NOT_MODIFIED) {
                success = true;
                message = "DGT nacional sin cambios (304); se conserva la base local";
            } else {
                if (response < 200 || response >= 300) throw new IOException("HTTP " + response);
                Dataset dataset;
                try (InputStream input = new LimitedInputStream(connection.getInputStream(), MAX_RESPONSE_BYTES)) {
                    dataset = parseXml(input);
                }
                if (dataset.records.isEmpty()) throw new IOException("feed DGT vacío");
                int applied = database.apply(dataset.records, dataset.datasetCreatedAt,
                        "DGT_WEEKLY_DELTA");
                SharedPreferences.Editor editor = preferences.edit()
                        .putLong("last_success_national", checked)
                        .putString("seed_mode", "weekly_delta_merged")
                        .putInt("last_applied", applied);
                String responseEtag = connection.getHeaderField("ETag");
                String responseModified = connection.getHeaderField("Last-Modified");
                if (responseEtag != null && !responseEtag.isEmpty()) editor.putString("etag", responseEtag);
                if (responseModified != null && !responseModified.isEmpty()) {
                    editor.putString("last_modified", responseModified);
                }
                editor.apply();
                success = true;
                message = applied + " cambios DGT nacionales aplicados en local";
            }
            lastResult = message;
            AppSessionLog.event(TAG, "Actualización correcta · " + message);
        } catch (Exception error) {
            message = "No se pudo actualizar DGT: " + error.getClass().getSimpleName();
            lastResult = message;
            AppSessionLog.event(TAG, "Actualización fallida · " + error.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
            updateRunning = false;
        }
        boolean resultSuccess = success;
        String finalMessage = message;
        main.post(() -> { if (callback != null) callback.onFinished(new UpdateResult(resultSuccess, finalMessage)); });
    }

    private void seedFromAssetsAsync() {
        new Thread(() -> {
            if (database.count("ACTIVE") > 0) {
                seedStatus = database.count("ACTIVE") + " límites DGT conservados en local";
                return;
            }
            int imported = 0;
            try (InputStream input = context.getAssets().open(XML_ASSET)) {
                Dataset dataset = parseXml(input);
                imported = database.apply(dataset.records, dataset.datasetCreatedAt,
                        "DGT_WEEKLY_SEED");
                if (imported <= 0) throw new IOException("semilla XML vacía");
                seedStatus = imported + " cambios DGT nacionales · semilla semanal parcial";
            } catch (Exception xmlError) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        context.getAssets().open(TSV_ASSET), StandardCharsets.UTF_8))) {
                    ArrayList<FeatureRecord> records = parseTsv(reader);
                    imported = database.apply(records, 0L, "DGT_WEEKLY_SEED");
                    seedStatus = imported + " cambios DGT nacionales · semilla TSV semanal parcial";
                } catch (Exception tsvError) {
                    seedStatus = "Sin semilla DGT local · " + tsvError.getClass().getSimpleName();
                    AppSessionLog.event(TAG, "Semilla DGT fallida · XML="
                            + xmlError.getClass().getSimpleName() + " · TSV="
                            + tsvError.getClass().getSimpleName());
                    return;
                }
            }
            preferences.edit().putInt("bundled_seed_version", BUNDLED_SEED_VERSION)
                    .putString("seed_mode", "weekly_delta_partial").apply();
            AppSessionLog.event(TAG, "Semilla instalada · " + seedStatus);
        }, "e87-dgt-speed-seed").start();
    }

    private void autoRefreshIfNeeded() {
        if (!active || updateRunning || availableNetwork() == null) return;
        long now = System.currentTimeMillis();
        if (now - lastAutomaticAttempt < RETRY_INTERVAL_MS) return;
        long last = lastSuccessfulUpdate();
        if (last > 0L && now - last < UPDATE_INTERVAL_MS) return;
        lastAutomaticAttempt = now;
        refreshFromInternet(result -> AppSessionLog.event(TAG,
                "Actualización automática " + (result.success ? "correcta" : "omitida/fallida")
                        + " · " + result.message));
    }

    private Network availableNetwork() {
        if (connectivity == null) return null;
        try {
            Network activeNetwork = connectivity.getActiveNetwork();
            if (usable(activeNetwork)) return activeNetwork;
            for (Network candidate : connectivity.getAllNetworks()) if (usable(candidate)) return candidate;
        } catch (Exception ignored) { }
        return null;
    }

    private boolean usable(Network network) {
        if (network == null || connectivity == null) return false;
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void finish(UpdateCallback callback, boolean success, String message) {
        main.post(() -> { if (callback != null) callback.onFinished(new UpdateResult(success, message)); });
    }

    private static boolean directionApplies(Record record, Location location,
                                            SpeedLimitRepository.Match roadMatch) {
        String direction = record.direction == null ? "" : record.direction.toLowerCase(Locale.ROOT);
        if (direction.isEmpty() || direction.contains("both")) return true;
        if (direction.contains("unknown")) return false;
        if (!location.hasBearing()) return false;
        double roadBearing = roadMatch != null && Double.isFinite(roadMatch.roadBearingDegrees)
                ? roadMatch.roadBearingDegrees : geometryBearing(record.geometry);
        if (!Double.isFinite(roadBearing)) return false;
        double difference = angularDifference(location.getBearing(), roadBearing);
        if (direction.contains("increasing")) return difference <= 45d;
        if (direction.contains("decreasing")) return Math.abs(180d - difference) <= 45d;
        return false;
    }

    static String normalizeRoad(String value) {
        if (value == null) return "";
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    static double angularDifference(double a, double b) {
        double difference = Math.abs(a - b) % 360d;
        if (difference > 180d) difference = 360d - difference;
        return difference;
    }

    /** Observed TN-ITS fallback used only when the feed omits srsName and the result is plausible. */
    static double[] utm30ToWgs84(double northing, double easting) {
        double a = 6378137.0;
        double eccSquared = 0.00669438;
        double k0 = 0.9996;
        double eccPrimeSquared = eccSquared / (1 - eccSquared);
        double e1 = (1 - Math.sqrt(1 - eccSquared)) / (1 + Math.sqrt(1 - eccSquared));
        double x = easting - 500000.0;
        double y = northing;
        double m = y / k0;
        double mu = m / (a * (1 - eccSquared / 4 - 3 * eccSquared * eccSquared / 64
                - 5 * eccSquared * eccSquared * eccSquared / 256));
        double phi1Rad = mu + (3 * e1 / 2 - 27 * Math.pow(e1, 3) / 32) * Math.sin(2 * mu)
                + (21 * e1 * e1 / 16 - 55 * Math.pow(e1, 4) / 32) * Math.sin(4 * mu)
                + (151 * Math.pow(e1, 3) / 96) * Math.sin(6 * mu)
                + (1097 * Math.pow(e1, 4) / 512) * Math.sin(8 * mu);
        double n1 = a / Math.sqrt(1 - eccSquared * Math.sin(phi1Rad) * Math.sin(phi1Rad));
        double t1 = Math.tan(phi1Rad) * Math.tan(phi1Rad);
        double c1 = eccPrimeSquared * Math.cos(phi1Rad) * Math.cos(phi1Rad);
        double r1 = a * (1 - eccSquared)
                / Math.pow(1 - eccSquared * Math.sin(phi1Rad) * Math.sin(phi1Rad), 1.5);
        double d = x / (n1 * k0);
        double lat = phi1Rad - (n1 * Math.tan(phi1Rad) / r1)
                * (d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * eccPrimeSquared)
                * Math.pow(d, 4) / 24 + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1
                - 252 * eccPrimeSquared - 3 * c1 * c1) * Math.pow(d, 6) / 720);
        double lon = (d - (1 + 2 * t1 + c1) * Math.pow(d, 3) / 6
                + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * eccPrimeSquared
                + 24 * t1 * t1) * Math.pow(d, 5) / 120) / Math.cos(phi1Rad);
        // UTM zone 30 has its central meridian at 3 degrees west.  The public
        // TN-ITS feed omits srsName, but its coordinates are observed ETRS89/UTM30;
        // using +3 here would shift the whole national layer roughly six degrees east.
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon) - 3d};
    }

    private static Dataset parseXml(InputStream input) throws Exception {
        ArrayList<FeatureRecord> records = new ArrayList<>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(input, "UTF-8");
        FeatureBuilder current = null;
        String field = null;
        boolean inUpdateInfo = false;
        long datasetCreatedAt = 0L;
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            int event = parser.getEventType();
            String name = local(parser.getName());
            if (event == XmlPullParser.START_TAG) {
                if ("GenericSafetyFeature".equals(name)) {
                    current = new FeatureBuilder();
                } else if ("UpdateInfo".equals(name)) {
                    inUpdateInfo = true;
                } else if (current != null) {
                    if ("LineString".equals(name)) {
                        String srs = parser.getAttributeValue(null, "srsName");
                        if (srs == null) srs = parser.getAttributeValue(null, "srsname");
                        current.geometryCrs = srs == null || srs.isEmpty()
                                ? "INFERRED_ETRS89_UTM30" : srs;
                    }
                    field = name;
                } else if ("datasetCreationTime".equals(name)) {
                    field = name;
                }
            } else if (event == XmlPullParser.TEXT && field != null) {
                String value = parser.getText() == null ? "" : parser.getText().trim();
                if (!value.isEmpty()) {
                    if (current != null) current.accept(field, value, inUpdateInfo);
                    else if ("datasetCreationTime".equals(field)) datasetCreatedAt = parseTime(value);
                }
            } else if (event == XmlPullParser.END_TAG) {
                if ("GenericSafetyFeature".equals(name) && current != null) {
                    FeatureRecord record = current.build();
                    if (record != null) records.add(record);
                    current = null;
                }
                if ("UpdateInfo".equals(name)) inUpdateInfo = false;
                field = null;
            }
        }
        return new Dataset(records, datasetCreatedAt);
    }

    private static ArrayList<FeatureRecord> parseTsv(BufferedReader reader) throws IOException {
        ArrayList<FeatureRecord> result = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty() || line.charAt(0) == '#') continue;
            String[] fields = line.split("\\|", -1);
            if (fields.length < 12) continue;
            try {
                result.add(new FeatureRecord(fields[0], fields[1], fields[2], fields[3], fields[4],
                        fields[5], parseDouble(fields[6]), parseDouble(fields[7]),
                        (int) Math.round(parseDouble(fields[8])), fields[9], fields[10],
                        fields[11], System.currentTimeMillis()));
            } catch (Exception ignored) { }
        }
        return result;
    }

    private static String local(String name) {
        if (name == null) return "";
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static long parseTime(String value) {
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"};
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date parsed = format.parse(value);
                if (parsed != null) return parsed.getTime();
            } catch (ParseException ignored) { }
        }
        return 0L;
    }

    private static double parseDouble(String value) {
        if (value == null || value.trim().isEmpty()) return Double.NaN;
        return Double.parseDouble(value.trim().replace(',', '.'));
    }

    private static double nearestDistance(double lat, double lon, String geometry) {
        double best = Double.MAX_VALUE;
        String[] points = geometry == null ? new String[0] : geometry.split(";");
        double previousLat = Double.NaN, previousLon = Double.NaN;
        for (String point : points) {
            String[] pair = point.split(",");
            if (pair.length != 2) continue;
            double pointLat, pointLon;
            try { pointLat = Double.parseDouble(pair[0]); pointLon = Double.parseDouble(pair[1]); }
            catch (Exception ignored) { continue; }
            best = Math.min(best, distance(lat, lon, pointLat, pointLon));
            if (Double.isFinite(previousLat)) {
                Projection projection = project(lat, lon, previousLat, previousLon, pointLat, pointLon);
                best = Math.min(best, projection.distanceMeters);
            }
            previousLat = pointLat;
            previousLon = pointLon;
        }
        return best;
    }

    private static double geometryBearing(String geometry) {
        if (geometry == null || geometry.trim().isEmpty()) return Double.NaN;
        String[] points = geometry.split(";");
        double[] first = null;
        double[] last = null;
        for (String point : points) {
            String[] pair = point.split(",");
            if (pair.length != 2) continue;
            try {
                double lat = Double.parseDouble(pair[0]);
                double lon = Double.parseDouble(pair[1]);
                if (first == null) first = new double[]{lat, lon};
                last = new double[]{lat, lon};
            } catch (NumberFormatException ignored) { }
        }
        if (first == null || last == null || first == last
                || (first[0] == last[0] && first[1] == last[1])) return Double.NaN;
        double y = Math.sin(Math.toRadians(last[1] - first[1])) * Math.cos(Math.toRadians(last[0]));
        double x = Math.cos(Math.toRadians(first[0])) * Math.sin(Math.toRadians(last[0]))
                - Math.sin(Math.toRadians(first[0])) * Math.cos(Math.toRadians(last[0]))
                * Math.cos(Math.toRadians(last[1] - first[1]));
        return (Math.toDegrees(Math.atan2(y, x)) + 360d) % 360d;
    }

    private static double distance(double latA, double lonA, double latB, double lonB) {
        float[] result = new float[1];
        Location.distanceBetween(latA, lonA, latB, lonB, result);
        return result[0];
    }

    private static Projection project(double lat, double lon, double latA, double lonA,
                                      double latB, double lonB) {
        double cos = Math.cos(Math.toRadians(lat));
        double ax = (lonA - lon) * 111_320d * cos;
        double ay = (latA - lat) * 111_320d;
        double bx = (lonB - lon) * 111_320d * cos;
        double by = (latB - lat) * 111_320d;
        double dx = bx - ax, dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        double fraction = lengthSquared <= 0d ? 0d : Math.max(0d, Math.min(1d,
                -(ax * dx + ay * dy) / lengthSquared));
        double px = ax + fraction * dx, py = ay + fraction * dy;
        return new Projection(Math.hypot(px, py));
    }

    private static final class Projection {
        final double distanceMeters;
        Projection(double distanceMeters) { this.distanceMeters = distanceMeters; }
    }

    static final class Match {
        final String id, roadRef, direction, geometryCrs;
        final int limitKmh;
        final double distanceMeters;
        final long updatedAt;
        Match(String id, int limitKmh, String roadRef, String direction,
              double distanceMeters, long updatedAt, String geometryCrs) {
            this.id = id; this.limitKmh = limitKmh; this.roadRef = roadRef;
            this.direction = direction; this.distanceMeters = distanceMeters;
            this.updatedAt = updatedAt; this.geometryCrs = geometryCrs;
        }

        SpeedLimitRepository.Match asSpeedMatch(SpeedLimitRepository.Match road) {
            return new SpeedLimitRepository.Match(limitKmh, distanceMeters, updatedAt, "DGT",
                    true, road == null ? "" : road.roadClass, "DGT:" + id,
                    road == null ? Double.NaN : road.headingDifferenceDegrees,
                    road == null ? Double.NaN : road.roadBearingDegrees,
                    road == null ? Double.NaN : road.alongMeters,
                    road == null ? roadRef : road.roadRef);
        }
    }

    private static final class Dataset {
        final ArrayList<FeatureRecord> records;
        final long datasetCreatedAt;
        Dataset(ArrayList<FeatureRecord> records, long datasetCreatedAt) {
            this.records = records; this.datasetCreatedAt = datasetCreatedAt;
        }
    }

    private static final class FeatureBuilder {
        String id = "", provider = "", action = "ADD", road = "", direction = "",
                character = "", geometryCrs = "INFERRED_ETRS89_UTM30", posList = "";
        double fromPk = Double.NaN, toPk = Double.NaN;
        int speed = 0;
        void accept(String field, String value, boolean inUpdateInfo) {
            if (inUpdateInfo && "type".equals(field)) action = value.toUpperCase(Locale.ROOT);
            else if ("providerId".equals(field)) provider = value;
            else if ("id".equals(field) && id.isEmpty()) id = value;
            else if ("road".equals(field) && road.isEmpty()) road = value;
            else if ("applicableDirection".equals(field)) direction = value;
            else if ("fromPosition".equals(field)) fromPk = parseSafe(value);
            else if ("toPosition".equals(field)) toPk = parseSafe(value);
            else if ("caracter".equals(field)) character = value;
            else if ("measure".equals(field)) speed = (int) Math.round(parseSafe(value));
            else if ("posList".equals(field)) posList += (posList.isEmpty() ? "" : " ") + value;
        }
        FeatureRecord build() {
            if (id.isEmpty()) return null;
            String geometry = geometryFromPosList(posList, geometryCrs);
            if ("REMOVE".equals(action)) return new FeatureRecord(id, provider, action, road, direction,
                    character, fromPk, toPk, speed, geometry, geometryCrs, "", System.currentTimeMillis());
            if (speed <= 0 || geometry.isEmpty()) return null;
            return new FeatureRecord(id, provider, action, road, direction, character, fromPk, toPk,
                    speed, geometry, geometryCrs, "", System.currentTimeMillis());
        }
        private static double parseSafe(String value) {
            try { return parseDouble(value); } catch (Exception ignored) { return Double.NaN; }
        }
    }

    private static String geometryFromPosList(String posList, String crs) {
        if (posList == null || posList.trim().isEmpty()) return "";
        String[] values = posList.trim().split("[,\\s]+");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i + 1 < values.length; i += 2) {
            try {
                double first = parseDouble(values[i]);
                double second = parseDouble(values[i + 1]);
                double[] point = toWgs84(first, second, crs);
                if (point == null) continue;
                if (result.length() > 0) result.append(';');
                result.append(String.format(Locale.ROOT, "%.7f,%.7f", point[0], point[1]));
            } catch (Exception ignored) { }
        }
        return result.toString();
    }

    private static double[] toWgs84(double first, double second, String crs) {
        String lower = crs == null ? "" : crs.toLowerCase(Locale.ROOT);
        double[] point;
        if (lower.contains("4326") || (Math.abs(first) <= 90d && Math.abs(second) <= 180d)) {
            point = new double[]{first, second};
        } else {
            point = utm30ToWgs84(first, second);
        }
        if (point[0] < 35d || point[0] > 44.5d || point[1] < -10d || point[1] > 4d) return null;
        return point;
    }

    private static final class FeatureRecord {
        final String id, provider, action, road, direction, character, geometry, geometryCrs;
        final double fromPk, toPk;
        final int speed;
        final long changedAt;
        FeatureRecord(String id, String provider, String action, String road, String direction,
                      String character, double fromPk, double toPk, int speed, String geometry,
                      String geometryCrs, String unused, long changedAt) {
            this.id = id; this.provider = provider; this.action = action; this.road = road;
            this.direction = direction; this.character = character; this.fromPk = fromPk;
            this.toPk = toPk; this.speed = speed; this.geometry = geometry;
            this.geometryCrs = geometryCrs; this.changedAt = changedAt;
        }
    }

    private static final class Record {
        final String id, roadRef, direction, geometry, geometryCrs;
        final int speedKmh;
        final long changedAt;
        Record(String id, String roadRef, String direction, int speedKmh, String geometry,
               String geometryCrs, long changedAt) {
            this.id = id; this.roadRef = roadRef; this.direction = direction;
            this.speedKmh = speedKmh; this.geometry = geometry; this.geometryCrs = geometryCrs;
            this.changedAt = changedAt;
        }
    }

    private static final class Database extends SQLiteOpenHelper {
        private static final String NAME = "e87_dgt_speed.db";
        Database(Context context) { super(context, NAME, null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE dgt_speed_limits (id TEXT PRIMARY KEY, provider_id TEXT NOT NULL, "
                    + "status TEXT NOT NULL, road_ref TEXT NOT NULL, direction TEXT NOT NULL, "
                    + "road_character TEXT NOT NULL, from_pk REAL, to_pk REAL, speed_kmh INTEGER NOT NULL, "
                    + "geometry TEXT NOT NULL, geometry_crs TEXT NOT NULL, changed_at INTEGER NOT NULL, "
                    + "min_lat REAL NOT NULL DEFAULT 0, max_lat REAL NOT NULL DEFAULT 0, "
                    + "min_lon REAL NOT NULL DEFAULT 0, max_lon REAL NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX dgt_speed_status_bounds ON dgt_speed_limits(status,min_lat,max_lat,min_lon,max_lon)");
            db.execSQL("CREATE INDEX dgt_speed_road ON dgt_speed_limits(status,road_ref)");
            db.execSQL("CREATE TABLE dgt_speed_changes (change_key TEXT PRIMARY KEY, id TEXT NOT NULL, "
                    + "action TEXT NOT NULL, speed_kmh INTEGER NOT NULL, changed_at INTEGER NOT NULL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

        synchronized int apply(ArrayList<FeatureRecord> records, long datasetAt, String source) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            int applied = 0;
            try {
                for (FeatureRecord record : records) {
                    String action = record.action == null ? "ADD" : record.action.toUpperCase(Locale.ROOT);
                    if ("REMOVE".equals(action)) {
                        ContentValues removed = new ContentValues();
                        removed.put("status", "REMOVED");
                        removed.put("changed_at", record.changedAt);
                        if (db.update("dgt_speed_limits", removed, "id = ?", new String[]{record.id}) == 0) {
                            ContentValues tombstone = values(record, "REMOVED", 0, "");
                            db.insertWithOnConflict("dgt_speed_limits", null, tombstone,
                                    SQLiteDatabase.CONFLICT_REPLACE);
                        }
                    } else {
                        ContentValues values = values(record, "ACTIVE", record.speed, record.geometry);
                        db.insertWithOnConflict("dgt_speed_limits", null, values,
                                SQLiteDatabase.CONFLICT_REPLACE);
                    }
                    String key = record.id + "|" + action + "|" + record.speed + "|" + record.changedAt;
                    ContentValues change = new ContentValues();
                    change.put("change_key", key); change.put("id", record.id);
                    change.put("action", action); change.put("speed_kmh", record.speed);
                    change.put("changed_at", record.changedAt);
                    db.insertWithOnConflict("dgt_speed_changes", null, change,
                            SQLiteDatabase.CONFLICT_IGNORE);
                    applied++;
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            return applied;
        }

        private static ContentValues values(FeatureRecord record, String status, int speed,
                                             String geometry) {
            ContentValues values = new ContentValues();
            values.put("id", record.id); values.put("provider_id", record.provider);
            values.put("status", status); values.put("road_ref", record.road);
            values.put("direction", record.direction); values.put("road_character", record.character);
            if (Double.isFinite(record.fromPk)) values.put("from_pk", record.fromPk); else values.putNull("from_pk");
            if (Double.isFinite(record.toPk)) values.put("to_pk", record.toPk); else values.putNull("to_pk");
            values.put("speed_kmh", speed); values.put("geometry", geometry == null ? "" : geometry);
            values.put("geometry_crs", record.geometryCrs); values.put("changed_at", record.changedAt);
            double[] bounds = bounds(geometry);
            if (bounds != null) {
                values.put("min_lat", bounds[0]); values.put("max_lat", bounds[1]);
                values.put("min_lon", bounds[2]); values.put("max_lon", bounds[3]);
            } else {
                values.put("min_lat", 0d); values.put("max_lat", 0d);
                values.put("min_lon", 0d); values.put("max_lon", 0d);
            }
            return values;
        }

        synchronized ArrayList<Record> near(double latitude, double longitude, int radiusMeters) {
            double latDelta = radiusMeters / 111_320d;
            double lonDelta = radiusMeters / Math.max(1d, 111_320d * Math.cos(Math.toRadians(latitude)));
            ArrayList<Record> result = new ArrayList<>();
            String selection = "status = 'ACTIVE' AND ((min_lat = 0 AND max_lat = 0) OR "
                    + "(min_lat <= ? AND max_lat >= ? AND min_lon <= ? AND max_lon >= ?))";
            String[] args = {String.valueOf(latitude + latDelta), String.valueOf(latitude - latDelta),
                    String.valueOf(longitude + lonDelta), String.valueOf(longitude - lonDelta)};
            try (Cursor cursor = getReadableDatabase().query("dgt_speed_limits",
                    new String[]{"id", "road_ref", "direction", "speed_kmh", "geometry", "geometry_crs", "changed_at"},
                    selection, args, null, null, null)) {
                while (cursor.moveToNext()) result.add(new Record(cursor.getString(0), cursor.getString(1),
                        cursor.getString(2), cursor.getInt(3), cursor.getString(4), cursor.getString(5),
                        cursor.getLong(6)));
            }
            return result;
        }

        synchronized int count(String status) {
            try (Cursor cursor = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM dgt_speed_limits WHERE status = ?", new String[]{status})) {
                return cursor.moveToFirst() ? cursor.getInt(0) : 0;
            }
        }

        private static double[] bounds(String geometry) {
            if (geometry == null || geometry.isEmpty()) return null;
            double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
            int count = 0;
            for (String point : geometry.split(";")) {
                String[] pair = point.split(",");
                if (pair.length != 2) continue;
                try {
                    double lat = Double.parseDouble(pair[0]);
                    double lon = Double.parseDouble(pair[1]);
                    minLat = Math.min(minLat, lat); maxLat = Math.max(maxLat, lat);
                    minLon = Math.min(minLon, lon); maxLon = Math.max(maxLon, lon); count++;
                } catch (Exception ignored) { }
            }
            return count == 0 ? null : new double[]{minLat, maxLat, minLon, maxLon};
        }
    }

    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private final int limit;
        private int read;
        LimitedInputStream(InputStream delegate, int limit) { this.delegate = delegate; this.limit = limit; }
        @Override public int read() throws IOException {
            if (read >= limit) throw new IOException("respuesta DGT demasiado grande");
            int value = delegate.read(); if (value >= 0) read++; return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (read >= limit) throw new IOException("respuesta DGT demasiado grande");
            int allowed = Math.min(length, limit - read);
            int count = delegate.read(buffer, offset, allowed); if (count > 0) read += count; return count;
        }
        @Override public void close() throws IOException { delegate.close(); }
    }

    static final class UpdateResult {
        final boolean success;
        final String message;
        UpdateResult(boolean success, String message) { this.success = success; this.message = message; }
    }
}
