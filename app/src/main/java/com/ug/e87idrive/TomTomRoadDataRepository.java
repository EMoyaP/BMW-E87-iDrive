package com.ug.e87idrive;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional connected road context. DGT/OSM remain fully functional without it. */
final class TomTomRoadDataRepository implements AutoCloseable {
    interface Listener { void onChanged(); }

    private static final String PREFERENCES = "tomtom_traffic";
    private static final long SNAP_INTERVAL_MS = 5L * 60L * 1_000L;
    private static final long SNAP_TTL_MS = 7L * 60L * 1_000L;
    private static final long FLOW_INTERVAL_MS = 90_000L;
    private static final long FLOW_TTL_MS = 3L * 60L * 1_000L;
    private static final int MAX_HISTORY = 6;
    private static final float MAX_ACCURACY_METERS = 30f;
    private static final double MIN_CONFIDENCE = .65d;

    private final SharedPreferences preferences;
    private final ConnectivityManager connectivity;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ArrayDeque<Location> history = new ArrayDeque<>();
    private volatile Candidate candidate;
    private volatile List<OfflineRoadMapView.TrafficFlow> flows = Collections.emptyList();
    private volatile boolean active, snapLoading, flowLoading;
    private volatile long lastSnapAttempt, lastFlowAttempt;
    private String historyProvider = "";

    TomTomRoadDataRepository(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        connectivity = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.listener = listener;
    }

    void start() { active = true; }
    void stop() { active = false; }

    synchronized void onConfigurationChanged() {
        if (!enabled()) {
            candidate = null;
            flows = Collections.emptyList();
            history.clear();
            historyProvider = "";
        }
        notifyChanged();
    }

    synchronized void onLocation(Location location, Double speedKmh) {
        if (!active || location == null || !valid(location)) return;
        double speed = speedKmh != null && Double.isFinite(speedKmh) ? speedKmh
                : location.hasSpeed() ? location.getSpeed() * 3.6d : 0d;
        if (speed < 5d || (location.hasAccuracy() && location.getAccuracy() > MAX_ACCURACY_METERS)) {
            if (speed < 2d) history.clear();
            return;
        }
        String provider = location.getProvider() == null ? "" : location.getProvider();
        if (!historyProvider.isEmpty() && !provider.equals(historyProvider)) history.clear();
        historyProvider = provider;
        if (!history.isEmpty() && location.getTime() <= history.peekLast().getTime()) return;
        while (!history.isEmpty() && location.getTime() - history.peekFirst().getTime() > 60_000L)
            history.removeFirst();
        history.addLast(new Location(location));
        while (history.size() > MAX_HISTORY) history.removeFirst();
        if (!enabled() || !hasInternet()) return;
        long now = System.currentTimeMillis();
        if (!flowLoading && now - lastFlowAttempt >= FLOW_INTERVAL_MS
                && TomTomUsageBudget.reserve(preferences, TomTomUsageBudget.FLOW,
                TomTomUsageBudget.FLOW_LIMIT, now)) {
            flowLoading = true;
            lastFlowAttempt = now;
            Location copy = new Location(location);
            executor.execute(() -> downloadFlow(copy, now));
        }
        if (history.size() >= 2 && !snapLoading && now - lastSnapAttempt >= SNAP_INTERVAL_MS
                && TomTomUsageBudget.reserve(preferences, TomTomUsageBudget.SNAP,
                TomTomUsageBudget.SNAP_LIMIT, now)) {
            snapLoading = true;
            lastSnapAttempt = now;
            ArrayList<Location> fixes = new ArrayList<>();
            for (Location fix : history) fixes.add(new Location(fix));
            executor.execute(() -> downloadSnap(fixes, now));
        }
    }

    SpeedLimitRepository.Match speedMatch(Location location, SpeedLimitRepository.Match road) {
        Candidate value = candidate;
        long now = System.currentTimeMillis();
        if (value == null || location == null || now - value.fetchedAt > SNAP_TTL_MS
                || location.distanceTo(value.projected) > 85f) return null;
        String roadClass = road == null ? "" : road.roadClass;
        String osmId = road == null ? "" : road.osmId;
        double headingDifference = road == null ? Double.NaN : road.headingDifferenceDegrees;
        double roadBearing = road == null ? Double.NaN : road.roadBearingDegrees;
        double along = road == null ? Double.NaN : road.alongMeters;
        String roadRef = !value.roadRef.isEmpty() ? value.roadRef : road == null ? "" : road.roadRef;
        String roadName = !value.roadName.isEmpty() ? value.roadName : road == null ? "" : road.roadName;
        return new SpeedLimitRepository.Match(value.limitKmh,
                location.distanceTo(value.projected), value.fetchedAt, "TOMTOM", true,
                roadClass, osmId, headingDifference, roadBearing, along, roadRef, roadName);
    }

    List<OfflineRoadMapView.TrafficFlow> mapFlows() {
        List<OfflineRoadMapView.TrafficFlow> value = flows;
        if (value.isEmpty() || System.currentTimeMillis() - value.get(0).fetchedAt > FLOW_TTL_MS)
            return Collections.emptyList();
        return value;
    }

    String quotaSummary() { return TomTomUsageBudget.summary(preferences, System.currentTimeMillis()); }

    private void downloadSnap(List<Location> fixes, long requestedAt) {
        HttpURLConnection connection = null;
        try {
            String key = key();
            if (key.isEmpty()) return;
            StringBuilder points = new StringBuilder();
            StringBuilder headings = new StringBuilder();
            StringBuilder timestamps = new StringBuilder();
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            for (Location fix : fixes) {
                if (points.length() > 0) { points.append(';'); headings.append(';'); timestamps.append(';'); }
                points.append(String.format(Locale.ROOT, "%.7f,%.7f",
                        fix.getLongitude(), fix.getLatitude()));
                if (fix.hasBearing()) headings.append(Math.round(fix.getBearing()));
                timestamps.append(iso.format(new Date(fix.getTime())));
            }
            String fields = "{route{type,geometry{type,coordinates},properties{id,"
                    + "speedLimits{value,unit,type},address{roadName,roadNumbers,municipality,"
                    + "countrySubdivision},confidence}}}";
            Uri uri = Uri.parse("https://api.tomtom.com/snapToRoads/1").buildUpon()
                    .appendQueryParameter("key", key).appendQueryParameter("points", points.toString())
                    .appendQueryParameter("headings", headings.toString())
                    .appendQueryParameter("timestamps", timestamps.toString())
                    .appendQueryParameter("fields", fields)
                    .appendQueryParameter("vehicleType", "PassengerCar")
                    .appendQueryParameter("measurementSystem", "auto")
                    .appendQueryParameter("offroadMargin", "45").build();
            connection = open(uri, 30_000);
            Candidate parsed = parseSnap(read(connection.getInputStream(), 2 * 1024 * 1024),
                    fixes.get(fixes.size() - 1), requestedAt);
            if (parsed != null) {
                candidate = parsed;
                AppSessionLog.event("TOMTOM LÍMITE", parsed.limitKmh + " km/h · "
                        + first(parsed.roadRef, parsed.roadName, "vía identificada"));
            }
        } catch (Exception error) {
            AppSessionLog.sampledEvent("tomtom-snap", "TOMTOM LÍMITE",
                    "Consulta aplazada · " + error.getClass().getSimpleName(), SNAP_INTERVAL_MS);
        } finally {
            if (connection != null) connection.disconnect();
            snapLoading = false;
            notifyChanged();
        }
    }

    private void downloadFlow(Location location, long requestedAt) {
        HttpURLConnection connection = null;
        try {
            String key = key();
            if (key.isEmpty()) return;
            String point = String.format(Locale.ROOT, "%.7f,%.7f",
                    location.getLatitude(), location.getLongitude());
            Uri uri = Uri.parse("https://api.tomtom.com/traffic/services/4/"
                    + "flowSegmentData/absolute/15/json").buildUpon()
                    .appendQueryParameter("key", key).appendQueryParameter("point", point)
                    .appendQueryParameter("unit", "kmph").appendQueryParameter("thickness", "10")
                    .appendQueryParameter("openLr", "false").build();
            connection = open(uri, 25_000);
            OfflineRoadMapView.TrafficFlow flow = parseFlow(
                    read(connection.getInputStream(), 1024 * 1024), requestedAt);
            flows = flow == null ? Collections.emptyList() : Collections.singletonList(flow);
        } catch (Exception error) {
            AppSessionLog.sampledEvent("tomtom-flow", "TRÁFICO TOMTOM",
                    "Flujo aplazado · " + error.getClass().getSimpleName(), FLOW_INTERVAL_MS);
        } finally {
            if (connection != null) connection.disconnect();
            flowLoading = false;
            notifyChanged();
        }
    }

    static Candidate parseSnap(String json, Location current, long fetchedAt) throws Exception {
        JSONArray route = new JSONObject(json).optJSONArray("route");
        Candidate best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; route != null && i < route.length(); i++) {
            JSONObject element = route.optJSONObject(i);
            if (element == null) continue;
            JSONObject properties = element.optJSONObject("properties");
            JSONObject geometry = element.optJSONObject("geometry");
            if (properties == null || geometry == null) continue;
            JSONObject speed = properties.optJSONObject("speedLimits");
            if (speed == null || !"Maximum".equalsIgnoreCase(speed.optString("type"))
                    || !"kmph".equalsIgnoreCase(speed.optString("unit"))) continue;
            int limit = speed.optInt("value", 0);
            double confidence = properties.optDouble("confidence", 1d);
            if (limit < 10 || limit > 140 || confidence < MIN_CONFIDENCE) continue;
            ArrayList<Double> points = new ArrayList<>();
            collectGeoJson(geometry.optJSONArray("coordinates"), points);
            Location nearest = nearest(current, points);
            if (nearest == null) continue;
            double distance = current.distanceTo(nearest);
            if (distance >= bestDistance) continue;
            JSONObject address = properties.optJSONObject("address");
            String roadName = address == null ? "" : address.optString("roadName", "");
            String roadRef = address == null ? "" : join(address.optJSONArray("roadNumbers"));
            best = new Candidate(limit, roadRef, roadName, confidence, nearest, fetchedAt);
            bestDistance = distance;
        }
        return bestDistance <= 85d ? best : null;
    }

    static OfflineRoadMapView.TrafficFlow parseFlow(String json, long fetchedAt) throws Exception {
        JSONObject flow = new JSONObject(json).optJSONObject("flowSegmentData");
        if (flow == null) return null;
        int current = flow.optInt("currentSpeed", -1);
        int free = flow.optInt("freeFlowSpeed", -1);
        double confidence = flow.optDouble("confidence", 0d);
        boolean closed = flow.optBoolean("roadClosure", false);
        JSONObject coordinates = flow.optJSONObject("coordinates");
        Object raw = coordinates == null ? null : coordinates.opt("coordinate");
        ArrayList<Double> points = new ArrayList<>();
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int i = 0; i < array.length(); i++) appendCoordinate(array.optJSONObject(i), points);
        } else if (raw instanceof JSONObject) appendCoordinate((JSONObject) raw, points);
        if (points.size() < 4 || current < 0 || free <= 0 || confidence < .25d) return null;
        double[] geometry = new double[points.size()];
        for (int i = 0; i < geometry.length; i++) geometry[i] = points.get(i);
        return new OfflineRoadMapView.TrafficFlow(current, free, confidence, closed,
                geometry, fetchedAt);
    }

    private static void appendCoordinate(JSONObject value, ArrayList<Double> out) {
        if (value == null) return;
        double lat = value.optDouble("latitude", Double.NaN);
        double lon = value.optDouble("longitude", Double.NaN);
        if (Double.isFinite(lat) && Double.isFinite(lon)) { out.add(lat); out.add(lon); }
    }

    private static void collectGeoJson(JSONArray values, ArrayList<Double> out) {
        if (values == null || values.length() == 0) return;
        if (values.opt(0) instanceof Number && values.length() >= 2) {
            double lon = values.optDouble(0, Double.NaN), lat = values.optDouble(1, Double.NaN);
            if (Double.isFinite(lat) && Double.isFinite(lon)) { out.add(lat); out.add(lon); }
            return;
        }
        for (int i = 0; i < values.length(); i++) collectGeoJson(values.optJSONArray(i), out);
    }

    private static Location nearest(Location current, ArrayList<Double> points) {
        Location best = null; float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i + 1 < points.size(); i += 2) {
            Location point = new Location("tomtom");
            point.setLatitude(points.get(i)); point.setLongitude(points.get(i + 1));
            float distance = current.distanceTo(point);
            if (distance < bestDistance) { best = point; bestDistance = distance; }
        }
        return best;
    }

    private HttpURLConnection open(Uri uri, int readTimeout) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setConnectTimeout(15_000); connection.setReadTimeout(readTimeout);
        connection.setRequestProperty("User-Agent", "BMW-E87-iDrive/1.28 (connected road data)");
        int status = connection.getResponseCode();
        if (status != 200) { connection.disconnect(); throw new IOException("HTTP " + status); }
        return connection;
    }

    private String key() { return preferences.getString("api_key", "").trim(); }
    private boolean enabled() { return !key().isEmpty() && preferences.getBoolean("enabled", false); }
    private boolean hasInternet() {
        if (connectivity == null) return false;
        Network network = connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null
                : connectivity.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
    private void notifyChanged() { main.post(() -> { if (listener != null) listener.onChanged(); }); }
    private static String read(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream in = input) {
            byte[] buffer = new byte[8192]; int count; int total = 0;
            while ((count = in.read(buffer)) >= 0) {
                total += count; if (total > maximum) throw new IOException("Respuesta demasiado grande");
                output.write(buffer, 0, count);
            }
        }
        return output.toString("UTF-8");
    }
    private static String join(JSONArray values) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; values != null && i < values.length(); i++) {
            String value = values.optString(i, "").trim();
            if (!value.isEmpty()) { if (result.length() > 0) result.append(" · "); result.append(value); }
        }
        return result.toString();
    }
    private static String first(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }
    private static boolean valid(Location location) {
        return Double.isFinite(location.getLatitude()) && Double.isFinite(location.getLongitude())
                && Math.abs(location.getLatitude()) <= 90d && Math.abs(location.getLongitude()) <= 180d;
    }

    @Override public synchronized void close() {
        active = false; history.clear(); historyProvider = "";
        main.removeCallbacksAndMessages(null); executor.shutdownNow();
    }

    static final class Candidate {
        final int limitKmh; final String roadRef, roadName; final double confidence;
        final Location projected; final long fetchedAt;
        Candidate(int limitKmh, String roadRef, String roadName, double confidence,
                  Location projected, long fetchedAt) {
            this.limitKmh = limitKmh; this.roadRef = roadRef == null ? "" : roadRef;
            this.roadName = roadName == null ? "" : roadName; this.confidence = confidence;
            this.projected = new Location(projected); this.fetchedAt = fetchedAt;
        }
    }
}
