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
    /** Map matching remains deliberately lightweight for the RK3326. Direction is only used
     * while moving and continuity is a small tie-breaker, never a reason to retain an obsolete
     * limit after the vehicle has changed road. */
    private static final float MIN_NATIVE_BEARING_SPEED_MPS = 1.5f;
    private static final double MIN_DERIVED_BEARING_METERS = 8d;
    private static final long MAX_DERIVED_BEARING_INTERVAL_MS = 20_000L;
    private static final long MAX_BEARING_AGE_MS = 10_000L;
    /** A speed derived by {@link GpsSpeedProvider} is used only briefly for cadence/map matching
     * when the platform omitted {@code Location.getSpeed()}. It is never displayed as a CAN value
     * and expires before it can affect a later unrelated fix. */
    private static final long MAX_DERIVED_SPEED_AGE_MS = 10_000L;
    private static final double HEADING_PENALTY_METERS_PER_DEGREE = 0.32d;
    private static final double CONTINUITY_PREFERENCE_METERS = 6d;
    /** A new road must be materially more plausible, or remain plausible across two moving
     * fixes, before replacing the current trajectory.  This prevents a nearby parallel lane
     * from briefly changing an advisory sign while preserving immediate changes on the same
     * OSM way (for example a verified physical-sign boundary). */
    private static final double TRAJECTORY_SWITCH_ADVANTAGE_METERS = 12d;
    private static final float MAX_ACCEPTED_GPS_ACCURACY_METERS = 45f;
    /** Bump when a bundled provincial seed must be checked on top of an older installation. */
    /** v3 replaces the old maxspeed-only Alicante cache with the full road-class seed. */
    private static final int BUNDLED_SEED_VERSION = 5;
    /** The GPS listener may deliver up to two fixes per second. Local map work is throttled by
     * vehicle speed: parked fixes are reused, while motorway fixes are evaluated immediately. */
    private static final long PARKED_LOOKUP_INTERVAL_MS = 5_000L;
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
    /**
     * A stationary match intentionally survives ordinary GNSS drift.  It must not, however,
     * delay the first real lookup after the vehicle starts moving away from an intersection.
     */
    private volatile boolean cachedLookupWasParked;
    private volatile Double latestDerivedSpeedKmh;
    private volatile long latestDerivedSpeedAt;
    private volatile Location latestDerivedSpeedLocation;
    private Location bearingAnchor;
    private Float stableBearingDegrees;
    private long stableBearingAt;
    private String lastMatchedOsmId;
    private String pendingOsmId;
    private int pendingOsmIdObservations;
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
                    if (isValidated(network)) autoRefreshIfNeeded();
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

    void onLocation(Location location) { onLocation(location, null); }

    /** Receives the already-calculated GPS fallback speed without modifying the original fix.
     * Some Android location implementations provide course/position but omit {@code hasSpeed()};
     * treating those moving fixes as parked would make local road matching react too slowly. */
    void onLocation(Location location, Double derivedSpeedKmh) {
        if (location == null) return;
        if (!location.hasSpeed() && isUsableSpeed(derivedSpeedKmh)) {
            latestDerivedSpeedKmh = Math.max(0d, derivedSpeedKmh);
            latestDerivedSpeedAt = System.currentTimeMillis();
            latestDerivedSpeedLocation = new Location(location);
        }
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
        Float bearing = reliableBearing(location, now);
        double speedKmh = effectiveSpeedKmh(location, now);
        long lookupInterval = lookupIntervalForSpeedKmh(speedKmh);
        boolean leavingParkedMatch = shouldReevaluateAfterDeparture(cachedLookupWasParked,
                speedKmh);
        double stationaryTolerance = speedKmh < 3d
                ? Math.max(10d, location.hasAccuracy() ? location.getAccuracy() : 0d) : 15d;
        if (!leavingParkedMatch && now - cachedLookupAt < lookupInterval
                && distanceMeters(cachedLat, cachedLon, location.getLatitude(), location.getLongitude())
                < stationaryTolerance) {
            return cachedMatch;
        }
        if (leavingParkedMatch) {
            AppSessionLog.event(TAG, "Salida desde parado · reevaluando vía con GPS/ruta");
        }
        int matchRadius = matchRadiusFor(location);
        // First obtain the best geometrical candidate without letting source quality influence
        // road selection. Then retain a short, stateful trajectory so an isolated GNSS fix cannot
        // jump to a nearby road. This is deliberately a small local alternative to a full routing
        // engine: no network, no OEM service and constant work for the RK3326.
        Match raw = database.nearest(location.getLatitude(), location.getLongitude(), matchRadius,
                bearing, null);
        Match result = stabilizeTrajectory(raw, location, matchRadius, bearing, speedKmh);
        result = applyVerifiedAlicanteZones(result, bearing);
        result = applyContextualAdvisory(result);
        cachedLat = location.getLatitude();
        cachedLon = location.getLongitude();
        cachedLookupAt = now;
        cachedMatch = result;
        cachedLookupWasParked = speedKmh < 3d;
        if (result != null) {
            lastMatchedOsmId = result.osmId;
        }
        return result;
    }

    private double effectiveSpeedKmh(Location location, long now) {
        if (location != null && location.hasSpeed()) return Math.max(0d, location.getSpeed() * 3.6d);
        Location source = latestDerivedSpeedLocation;
        Double speed = latestDerivedSpeedKmh;
        if (!isUsableSpeed(speed) || source == null || now - latestDerivedSpeedAt > MAX_DERIVED_SPEED_AGE_MS
                || location == null || source.distanceTo(location) > 200f) return 0d;
        return Math.max(0d, speed);
    }

    static boolean isUsableSpeed(Double speedKmh) {
        return speedKmh != null && !speedKmh.isNaN() && !speedKmh.isInfinite()
                && speedKmh >= 0d && speedKmh <= 300d;
    }

    private Match stabilizeTrajectory(Match raw, Location location, int radiusMeters,
                                      Float bearing, double speedKmh) {
        Match previous = lastMatchedOsmId == null ? null : database.byId(lastMatchedOsmId,
                location.getLatitude(), location.getLongitude(), radiusMeters, bearing);
        if (raw == null) {
            pendingOsmId = null;
            pendingOsmIdObservations = 0;
            return speedKmh < 3d ? previous : null;
        }
        if (previous == null || raw.osmId == null || raw.osmId.equals(previous.osmId)) {
            pendingOsmId = null;
            pendingOsmIdObservations = 0;
            return raw;
        }
        // While parked, GNSS has no trustworthy direction or network progression. Keep the
        // previously confirmed road if it is still within the permitted map radius.
        if (speedKmh < 3d) return previous;

        double rawScore = mapMatchScore(raw.distanceMeters, raw.exact,
                raw.headingDifferenceDegrees, false);
        double previousScore = mapMatchScore(previous.distanceMeters, previous.exact,
                previous.headingDifferenceDegrees, true);
        boolean clearlyBetter = rawScore + TRAJECTORY_SWITCH_ADVANTAGE_METERS < previousScore;
        if (clearlyBetter) {
            pendingOsmId = null;
            pendingOsmIdObservations = 0;
            return raw;
        }
        if (raw.osmId.equals(pendingOsmId)) pendingOsmIdObservations++;
        else {
            pendingOsmId = raw.osmId;
            pendingOsmIdObservations = 1;
        }
        if (pendingOsmIdObservations >= 2) {
            pendingOsmId = null;
            pendingOsmIdObservations = 0;
            return raw;
        }
        return previous;
    }

    /** Physical signs verified by the user on the recurring test route. Values are applied only
     * after the OSM way and travel direction have been matched; they can never attract the fix
     * to a nearby parallel road. Distances follow the stored OSM geometry orientation. */
    static Match applyVerifiedAlicanteZones(Match match, Float vehicleBearing) {
        if (match == null || vehicleBearing == null || match.osmId == null
                || Double.isNaN(match.roadBearingDegrees)
                || Double.isNaN(match.alongMeters)) return match;
        boolean forward = Database.followsGeometryDirection(vehicleBearing,
                match.roadBearingDegrees);
        int verified = 0;
        if ("33908151".equals(match.osmId)) {
            if (!forward) verified = 50; // señal 50 norte, verificada en 38.2282969
            else if (match.alongMeters >= 1_104d && match.alongMeters <= 2_226d) verified = 40;
        } else if ("34145696".equals(match.osmId) && forward) {
            // The physical 60 sign is about 12.7 m from the OSM way origin and
            // the physical 40 sign about 111.6 m.  A few-metre GNSS/map
            // projection error must not leave either sign on the generic 50
            // advisory value.  The 389 m boundary is the observed end of the
            // 40 prohibition; after it the generic local value is retained
            // until the next explicit/verified 60 sign.
            if (match.alongMeters >= 0d && match.alongMeters < 105d) verified = 60;
            else if (match.alongMeters >= 105d && match.alongMeters < 389d) verified = 40;
            else if (match.alongMeters >= 926d && match.alongMeters < 2_683d) verified = 60;
            else if (match.alongMeters >= 2_683d) verified = 30;
        } else if ("229338846".equals(match.osmId) && !forward
                && match.alongMeters <= 775d) {
            verified = 60;
        }
        return verified <= 0 ? match : match.withVerifiedLimit(verified);
    }

    static long lookupIntervalForSpeedKmh(double speedKmh) {
        if (speedKmh < 3d) return PARKED_LOOKUP_INTERVAL_MS;
        if (speedKmh < 10d) return 1_500L;
        if (speedKmh < 40d) return 1_000L;
        if (speedKmh < 70d) return 750L;
        if (speedKmh < 100d) return 500L;
        return 350L;
    }

    /**
     * The five-second parked cadence protects against position jitter while stationary.  As soon
     * as a credible moving fix arrives, query the local map immediately rather than carrying a
     * 30 km/h junction match into the road being joined.  Road selection still goes through
     * {@link #stabilizeTrajectory(Match, Location, int, Float, double)} so nearby parallel
     * carriageways are not promoted merely because the car has started moving.
     */
    static boolean shouldReevaluateAfterDeparture(boolean cachedWasParked, double speedKmh) {
        return cachedWasParked && speedKmh >= 3d;
    }

    /** Uses Android's GNSS course when available, otherwise derives it from sufficiently
     * separated fixes. Small movements inside the GPS uncertainty circle are ignored. */
    private Float reliableBearing(Location location, long now) {
        if (location.hasBearing() && (!location.hasSpeed()
                || location.getSpeed() >= MIN_NATIVE_BEARING_SPEED_MPS)) {
            stableBearingDegrees = normalizeBearing(location.getBearing());
            stableBearingAt = now;
            bearingAnchor = new Location(location);
            return stableBearingDegrees;
        }
        if (bearingAnchor == null) {
            bearingAnchor = new Location(location);
        } else {
            long interval = Math.abs(location.getTime() - bearingAnchor.getTime());
            double uncertainty = Math.max(MIN_DERIVED_BEARING_METERS,
                    Math.max(location.hasAccuracy() ? location.getAccuracy() : 0f,
                            bearingAnchor.hasAccuracy() ? bearingAnchor.getAccuracy() : 0f) * 1.25d);
            double movement = bearingAnchor.distanceTo(location);
            if (interval >= 500L && interval <= MAX_DERIVED_BEARING_INTERVAL_MS
                    && movement >= uncertainty) {
                stableBearingDegrees = normalizeBearing(bearingAnchor.bearingTo(location));
                stableBearingAt = now;
                bearingAnchor = new Location(location);
            } else if (interval > MAX_DERIVED_BEARING_INTERVAL_MS) {
                bearingAnchor = new Location(location);
            }
        }
        return stableBearingDegrees != null && now - stableBearingAt <= MAX_BEARING_AGE_MS
                ? stableBearingDegrees : null;
    }

    private static float normalizeBearing(float bearing) {
        float normalized = bearing % 360f;
        return normalized < 0f ? normalized + 360f : normalized;
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
                : String.format(Locale.ROOT, "%s %d km/h · %.0f m · %s%s · precisión=%s",
                        match.exact ? "límite" : "recomendada/" + roadClassLabel(match.roadClass),
                        match.limitKmh, match.distanceMeters, provinceLabel(match.province),
                        Double.isNaN(match.headingDifferenceDegrees) ? ""
                                : String.format(Locale.ROOT, " · rumbo Δ%.0f°",
                                        match.headingDifferenceDegrees), accuracy);
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
        // A manual request may still use a vendor PAN link that Android has
        // not validated. Background work must be stricter: the radio logs
        // showed repeated DNS failures on such links, which only wastes CPU,
        // radio time and the diagnostic log.
        Network selectedNetwork = automatic ? validatedNetwork() : availableNetwork();
        if (selectedNetwork == null) {
            finish(callback, false, "Android no publica una conexión a Internet utilizable");
            return;
        }
        String province = requestedProvince == null ? "AUTO" : requestedProvince;
        Province selectedProvince = findProvince(province);
        boolean provincialUpdate = selectedProvince != null;
        if (location == null && !provincialUpdate) {
            finish(callback, false, "Esperando una posición GPS para actualizar la zona actual");
            return;
        }
        long now = System.currentTimeMillis();
        if (updateRunning || now - lastRefreshAt < MIN_REFRESH_INTERVAL_MS) {
            finish(callback, false, "Actualización limitada: espera unos segundos antes de repetir");
            return;
        }
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
                    connection.setRequestProperty("User-Agent", "BMW-E87-iDrive/1.20 (offline speed-limit cache)");
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
        if (!active || updateRunning || validatedNetwork() == null || lastLocation == null) return;
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
                    + "way(area.province)[\"highway\"]"
                    + "[\"highway\"~\"motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service\"];"
                    + "out tags geom;";
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
            connection.setRequestProperty("User-Agent", "BMW-E87-iDrive/1.20 (Alicante offline road map)");
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) throw new IOException("HTTP " + response);
            synchronized (databaseLock) {
                SQLiteDatabase db = database.getWritableDatabase();
                db.beginTransaction();
                try (BufferedReader reader = openSeedReader(connection.getInputStream())) {
                    String header = reader.readLine();
                    if (header == null || !(header.contains("e87-road-class-seed-v4")
                            || header.contains("e87-road-class-seed-v3"))) {
                        throw new IOException("La semilla publicada no contiene el mapa vial compatible");
                    }
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

    /** Imports v1 explicit-maxspeed, v2 road-class and v3 directional rows without loading the
     * compressed map into the Java heap. */
    private int importSeedReader(SQLiteDatabase db, BufferedReader reader, Province province, long now)
            throws IOException {
        int imported = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] columns = line.split("\\t", 8);
            boolean v4 = columns.length == 8 && ("EXACT".equals(columns[1])
                    || "ADVISORY".equals(columns[1]));
            boolean v3 = columns.length == 7 && ("EXACT".equals(columns[1])
                    || "ADVISORY".equals(columns[1]));
            boolean v2 = columns.length == 5 && ("EXACT".equals(columns[1])
                    || "ADVISORY".equals(columns[1]));
            String id = columns[0];
            int limit = parseLimit(v4 || v3 || v2 ? columns[2]
                    : (columns.length > 1 ? columns[1] : ""));
            String roadClass = v4 || v3 || v2 ? columns[3] : "";
            int forwardLimit = v4 || v3 ? parseLimit(columns[4]) : 0;
            int backwardLimit = v4 || v3 ? parseLimit(columns[5]) : 0;
            String roadRef = v4 ? columns[6] : "";
            String geometry = v4 ? columns[7] : v3 ? columns[6] : v2 ? columns[4]
                    : (columns.length > 2 ? columns[2] : "");
            boolean exact = !(v4 || v3 || v2) || "EXACT".equals(columns[1]);
            if (limit <= 0 || id.isEmpty() || geometry.isEmpty()) continue;
            imported += insertRecord(db, id, limit, geometry, now, province.code, exact,
                    roadClass, forwardLimit, backwardLimit, roadRef);
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
                    String roadRef = tags == null ? "" : tags.optString("ref", "");
                    int explicitLimit = parseLimit(tags == null ? null : tags.optString("maxspeed", ""));
                    int forwardLimit = parseLimit(tags == null ? null
                            : tags.optString("maxspeed:forward", ""));
                    int backwardLimit = parseLimit(tags == null ? null
                            : tags.optString("maxspeed:backward", ""));
                    int advisoryLimit = advisoryForRoadClass(roadClass);
                    JSONArray geometry = element.optJSONArray("geometry");
                    if (explicitLimit <= 0 && advisoryLimit <= 0 || geometry == null || geometry.length() < 2) continue;
                    String coordinates = geometryString(geometry);
                    if (coordinates.isEmpty()) continue;
                    String id = element.optString("id", "");
                    if (id.isEmpty()) continue;
                    imported += insertRecord(db, id,
                            explicitLimit > 0 ? explicitLimit : advisoryLimit,
                            coordinates, now, province, explicitLimit > 0, roadClass,
                            forwardLimit, backwardLimit, roadRef);
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
        return insertRecord(db, id, limit, geometry, updatedAt, province, exact, roadClass, 0, 0);
    }

    private static int insertRecord(SQLiteDatabase db, String id, int limit, String geometry,
                                    long updatedAt, String province, boolean exact, String roadClass,
                                    int forwardLimit, int backwardLimit) {
        return insertRecord(db, id, limit, geometry, updatedAt, province, exact, roadClass,
                forwardLimit, backwardLimit, "");
    }

    private static int insertRecord(SQLiteDatabase db, String id, int limit, String geometry,
                                    long updatedAt, String province, boolean exact, String roadClass,
                                    int forwardLimit, int backwardLimit, String roadRef) {
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
        values.put("forward_speed", Math.max(0, forwardLimit));
        values.put("backward_speed", Math.max(0, backwardLimit));
        values.put("road_ref", roadRef == null ? "" : roadRef.trim());
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

    /**
     * Returns an advisory only. It is intentionally conservative: exact DGT/OSM values are
     * never changed. OSM's `unclassified` has no uniform legal meaning; when it has no route
     * reference it is normally a local-access street in this offline provincial extract, so the
     * Spanish urban one-lane reference is 30 km/h. The UI labels this as an S-7 recommendation.
     * A road reference keeps the generic through-road classification instead.
     */
    static Match applyContextualAdvisory(Match match) {
        if (match == null || match.exact) return match;
        if ("unclassified".equalsIgnoreCase(match.roadClass)
                && match.roadRef.trim().isEmpty()) {
            return match.withAdvisoryLimit(30);
        }
        return match;
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

    /** Network Android has verified as actually reaching the Internet. */
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
        final String osmId;
        final double headingDifferenceDegrees;
        final double roadBearingDegrees;
        final double alongMeters;
        final String roadRef;
        Match(int limitKmh, double distanceMeters, long updatedAt, String province,
              boolean exact, String roadClass) {
            this(limitKmh, distanceMeters, updatedAt, province, exact, roadClass,
                    null, Double.NaN, Double.NaN, Double.NaN, "");
        }
        Match(int limitKmh, double distanceMeters, long updatedAt, String province,
              boolean exact, String roadClass, String osmId, double headingDifferenceDegrees,
              double roadBearingDegrees, double alongMeters) {
            this(limitKmh, distanceMeters, updatedAt, province, exact, roadClass, osmId,
                    headingDifferenceDegrees, roadBearingDegrees, alongMeters, "");
        }
        Match(int limitKmh, double distanceMeters, long updatedAt, String province,
              boolean exact, String roadClass, String osmId, double headingDifferenceDegrees,
              double roadBearingDegrees, double alongMeters, String roadRef) {
            this.limitKmh = limitKmh;
            this.distanceMeters = distanceMeters;
            this.updatedAt = updatedAt;
            this.province = province;
            this.exact = exact;
            this.roadClass = roadClass;
            this.osmId = osmId;
            this.headingDifferenceDegrees = headingDifferenceDegrees;
            this.roadBearingDegrees = roadBearingDegrees;
            this.alongMeters = alongMeters;
            this.roadRef = roadRef == null ? "" : roadRef;
        }

        Match withVerifiedLimit(int verifiedLimit) {
            return new Match(verifiedLimit, distanceMeters, updatedAt, province, true,
                    roadClass, osmId, headingDifferenceDegrees, roadBearingDegrees, alongMeters,
                    roadRef);
        }

        Match withAdvisoryLimit(int advisoryLimit) {
            return new Match(advisoryLimit, distanceMeters, updatedAt, province, false,
                    roadClass, osmId, headingDifferenceDegrees, roadBearingDegrees, alongMeters,
                    roadRef);
        }
    }

    /** Difference between vehicle course and an undirected road axis. A two-way road therefore
     * matches equally in both travel directions. */
    static double headingDifference(double vehicleBearing, double roadBearing) {
        if (Double.isNaN(vehicleBearing) || Double.isNaN(roadBearing)) return Double.NaN;
        double difference = Math.abs(vehicleBearing - roadBearing) % 360d;
        if (difference > 180d) difference = 360d - difference;
        return Math.min(difference, 180d - difference);
    }

    /** Lower is better. Source quality is intentionally absent here: first select the physically
     * plausible road, then resolve generic/directional limits on that selected road. */
    static double mapMatchScore(double distanceMeters, boolean exact,
                                double headingDifferenceDegrees, boolean continuous) {
        double score = distanceMeters;
        if (continuous) score -= CONTINUITY_PREFERENCE_METERS;
        if (!Double.isNaN(headingDifferenceDegrees)) {
            score += headingDifferenceDegrees * HEADING_PENALTY_METERS_PER_DEGREE;
        }
        return score;
    }

    private static final class Database extends SQLiteOpenHelper {
        private static final String NAME = "e87_speed_limits.db";
        private static final int VERSION = 6;

        Database(Context context) { super(context, NAME, null, VERSION); }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE speed_limits (osm_id TEXT PRIMARY KEY, maxspeed INTEGER NOT NULL, "
                    + "geometry TEXT NOT NULL, updated_at INTEGER NOT NULL, province TEXT NOT NULL DEFAULT '', "
                    + "record_kind TEXT NOT NULL DEFAULT 'EXACT', road_class TEXT NOT NULL DEFAULT '', "
                    + "road_ref TEXT NOT NULL DEFAULT '', "
                    + "forward_speed INTEGER NOT NULL DEFAULT 0, backward_speed INTEGER NOT NULL DEFAULT 0, "
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
            if (oldVersion < 5) {
                db.execSQL("ALTER TABLE speed_limits ADD COLUMN forward_speed INTEGER NOT NULL DEFAULT 0");
                db.execSQL("ALTER TABLE speed_limits ADD COLUMN backward_speed INTEGER NOT NULL DEFAULT 0");
            }
            if (oldVersion < 6) {
                db.execSQL("ALTER TABLE speed_limits ADD COLUMN road_ref TEXT NOT NULL DEFAULT ''");
            }
        }

        Match nearest(double latitude, double longitude, int maxDistanceMeters,
                      Float vehicleBearing, String previousOsmId) {
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
                    new String[]{"osm_id", "maxspeed", "geometry", "updated_at", "province",
                            "record_kind", "road_class", "forward_speed", "backward_speed", "road_ref"},
                    selection, args, null, null, null);
            Match nearest = null;
            double nearestScore = Double.MAX_VALUE;
            try {
                while (cursor.moveToNext()) {
                    String osmId = cursor.getString(0);
                    int genericLimit = cursor.getInt(1);
                    String geometry = cursor.getString(2);
                    GeometryMatch geometryMatch = nearestPolylineMatch(latitude, longitude, geometry);
                    if (geometryMatch.distanceMeters > maxDistanceMeters) continue;
                    boolean genericExact = "EXACT".equals(cursor.getString(5));
                    double directionDifference = vehicleBearing == null ? Double.NaN
                            : headingDifference(vehicleBearing, geometryMatch.roadBearingDegrees);
                    int limit = genericLimit;
                    boolean exact = genericExact;
                    if (vehicleBearing != null && !Double.isNaN(geometryMatch.roadBearingDegrees)) {
                        boolean forward = followsGeometryDirection(vehicleBearing,
                                geometryMatch.roadBearingDegrees);
                        int directionalLimit = cursor.getInt(forward ? 7 : 8);
                        if (directionalLimit > 0) {
                            limit = directionalLimit;
                            exact = true;
                        }
                    }
                    boolean continuous = previousOsmId != null && previousOsmId.equals(osmId);
                    double score = mapMatchScore(geometryMatch.distanceMeters, exact,
                            directionDifference, continuous);
                    Match candidate = new Match(limit, geometryMatch.distanceMeters,
                            cursor.getLong(3), cursor.getString(4), exact, cursor.getString(6),
                            osmId, directionDifference, geometryMatch.roadBearingDegrees,
                            geometryMatch.alongMeters, cursor.getString(9));
                    if (nearest == null || score < nearestScore) {
                        nearest = candidate;
                        nearestScore = score;
                    }
                }
            } finally { cursor.close(); }
            return nearest;
        }

        Match byId(String osmId, double latitude, double longitude, int maxDistanceMeters,
                   Float vehicleBearing) {
            if (osmId == null || osmId.isEmpty()) return null;
            SQLiteDatabase db = getReadableDatabase();
            try (Cursor cursor = db.query("speed_limits",
                    new String[]{"osm_id", "maxspeed", "geometry", "updated_at", "province",
                            "record_kind", "road_class", "forward_speed", "backward_speed", "road_ref"},
                    "osm_id = ?", new String[]{osmId}, null, null, null)) {
                if (!cursor.moveToFirst()) return null;
                GeometryMatch geometryMatch = nearestPolylineMatch(latitude, longitude, cursor.getString(2));
                if (geometryMatch.distanceMeters > maxDistanceMeters) return null;
                boolean exact = "EXACT".equals(cursor.getString(5));
                double difference = vehicleBearing == null ? Double.NaN
                        : headingDifference(vehicleBearing, geometryMatch.roadBearingDegrees);
                int limit = cursor.getInt(1);
                if (vehicleBearing != null && !Double.isNaN(geometryMatch.roadBearingDegrees)) {
                    boolean forward = followsGeometryDirection(vehicleBearing, geometryMatch.roadBearingDegrees);
                    int directional = cursor.getInt(forward ? 7 : 8);
                    if (directional > 0) { limit = directional; exact = true; }
                }
                return new Match(limit, geometryMatch.distanceMeters, cursor.getLong(3),
                        cursor.getString(4), exact, cursor.getString(6), cursor.getString(0),
                        difference, geometryMatch.roadBearingDegrees, geometryMatch.alongMeters,
                        cursor.getString(9));
            }
        }

        static boolean followsGeometryDirection(double vehicleBearing, double roadBearing) {
            double difference = Math.abs(vehicleBearing - roadBearing) % 360d;
            if (difference > 180d) difference = 360d - difference;
            return difference <= 90d;
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

        private static final class GeometryMatch {
            final double distanceMeters;
            final double roadBearingDegrees;
            final double alongMeters;
            GeometryMatch(double distanceMeters, double roadBearingDegrees, double alongMeters) {
                this.distanceMeters = distanceMeters;
                this.roadBearingDegrees = roadBearingDegrees;
                this.alongMeters = alongMeters;
            }
        }

        /** Distance and local direction of the closest segment of the complete OSM way. */
        private static GeometryMatch nearestPolylineMatch(double latitude, double longitude,
                                                          String geometry) {
            double nearest = Double.MAX_VALUE;
            double nearestBearing = Double.NaN;
            double nearestAlong = Double.NaN;
            double accumulated = 0d;
            if (geometry == null) return new GeometryMatch(nearest, nearestBearing, nearestAlong);
            double previousLat = Double.NaN;
            double previousLon = Double.NaN;
            for (String point : geometry.split(";")) {
                String[] pair = point.split(",");
                if (pair.length != 2) continue;
                try {
                    double currentLat = Double.parseDouble(pair[0]);
                    double currentLon = Double.parseDouble(pair[1]);
                    if (Double.isNaN(previousLat)) {
                        nearest = distanceMeters(latitude, longitude, currentLat, currentLon);
                    } else {
                        SegmentProjection projection = projectToSegment(latitude, longitude, previousLat,
                                previousLon, currentLat, currentLon);
                        double segmentLength = distanceMeters(previousLat, previousLon,
                                currentLat, currentLon);
                        if (projection.distanceMeters <= nearest) {
                            nearest = projection.distanceMeters;
                            nearestBearing = segmentBearing(latitude, previousLat, previousLon,
                                    currentLat, currentLon);
                            nearestAlong = accumulated + projection.fraction * segmentLength;
                        }
                        accumulated += segmentLength;
                    }
                    previousLat = currentLat;
                    previousLon = currentLon;
                } catch (Exception ignored) { }
            }
            return new GeometryMatch(nearest, nearestBearing, nearestAlong);
        }

        private static double segmentBearing(double referenceLatitude, double latA, double lonA,
                                             double latB, double lonB) {
            double north = (latB - latA) * 110_540d;
            double east = (lonB - lonA) * 111_320d
                    * Math.cos(Math.toRadians(referenceLatitude));
            double bearing = Math.toDegrees(Math.atan2(east, north));
            return bearing < 0d ? bearing + 360d : bearing;
        }

        private static final class SegmentProjection {
            final double distanceMeters;
            final double fraction;
            SegmentProjection(double distanceMeters, double fraction) {
                this.distanceMeters = distanceMeters;
                this.fraction = fraction;
            }
        }

        private static SegmentProjection projectToSegment(double latitude, double longitude,
                                                double latA, double lonA, double latB, double lonB) {
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
            if (lengthSquared <= 0.0001d) return new SegmentProjection(Math.hypot(ax, ay), 0d);
            double t = -(ax * dx + ay * dy) / lengthSquared;
            t = Math.max(0d, Math.min(1d, t));
            return new SegmentProjection(Math.hypot(ax + t * dx, ay + t * dy), t);
        }
    }
}
