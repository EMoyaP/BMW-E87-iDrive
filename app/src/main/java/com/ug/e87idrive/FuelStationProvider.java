package com.ug.e87idrive;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Official fuel-price reader. A national stream bootstraps a 150 km moving
 * cache; later refreshes use the province/product endpoint around the GPS.
 */
public final class FuelStationProvider {
    public interface Listener { void onSnapshot(Snapshot snapshot); }

    public static final class Fuel {
        public final int productId;
        public final String label;
        Fuel(int productId, String label) { this.productId = productId; this.label = label; }
    }

    public static final Fuel[] FUELS = {
            new Fuel(4, "Diésel"), new Fuel(5, "Diésel Premium"),
            new Fuel(1, "Gasolina 95"), new Fuel(3, "Gasolina 98"),
            new Fuel(17, "GLP"), new Fuel(18, "GNC"), new Fuel(19, "GNL"),
            new Fuel(27, "Diésel renovable")
    };

    public static final class Station {
        public final String id, provinceId, brand, address;
        public final double latitude, longitude, price, distanceKm;
        Station(String id, String provinceId, String brand, String address,
                double latitude, double longitude, double price, double distanceKm) {
            this.id = id; this.provinceId = provinceId; this.brand = brand; this.address = address;
            this.latitude = latitude; this.longitude = longitude;
            this.price = price; this.distanceKm = distanceKm;
        }
    }

    public static final class Snapshot {
        public final String fuelLabel, datasetDate, message;
        public final int radiusKm;
        public final Station cheapest, nearest;
        public final boolean loading, cached;
        /** Local time when this cache was actually refreshed, not the provider's dataset date. */
        public final long updatedAt;
        Snapshot(String fuelLabel, int radiusKm, String datasetDate, String message,
                 Station cheapest, Station nearest, boolean loading, boolean cached, long updatedAt) {
            this.fuelLabel = fuelLabel; this.radiusKm = radiusKm;
            this.datasetDate = datasetDate; this.message = message;
            this.cheapest = cheapest; this.nearest = nearest;
            this.loading = loading; this.cached = cached;
            this.updatedAt = updatedAt;
        }
    }

    private static final String API_BASE = "https://sedeaplicaciones.minetur.gob.es/"
            + "ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/";
    private static final String API_NATIONAL = API_BASE + "FiltroProducto/";
    private static final String API_PROVINCE = API_BASE + "FiltroProvinciaProducto/";
    private static final String PREFS = "fuel_widget";
    private static final String PREF_PRODUCT = "product_id";
    private static final String PREF_RADIUS = "radius_km";
    public static final int DEFAULT_PRODUCT_ID = 4;
    public static final int DEFAULT_RADIUS_KM = 7;
    public static final int CACHE_COVERAGE_KM = 150;
    private static final long LOCAL_REFRESH_MS = 10L * 60L * 1000L;
    private static final long COVERAGE_REFRESH_MS = 24L * 60L * 60L * 1000L;
    private static final long MIN_RETRY_INTERVAL_MS = 10L * 60L * 1000L;
    private static final int CACHE_VERSION = 2;
    private static final int MAX_LOCAL_PROVINCES = 4;

    private enum RequestType { COVERAGE, LOCAL_PRICES }

    private final Context context;
    private final SharedPreferences preferences;
    private final ConnectivityManager connectivityManager;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "e87-fuel-stations");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private volatile HttpURLConnection activeConnection;
    private volatile boolean active;
    private boolean networkCallbackRegistered, networkAvailable, taskRunning;
    private long lastAttemptAt;
    private Location location, lastSelectionLocation;
    private Cache cache;
    private Snapshot snapshot;

    private final Runnable periodicRefresh = () -> {
        synchronized (FuelStationProvider.this) {
            if (!active) return;
            refresh(false);
            scheduleNextRefresh();
        }
    };

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            synchronized (FuelStationProvider.this) {
                boolean recovered = !networkAvailable;
                networkAvailable = true;
                if (recovered) lastAttemptAt = 0L;
                refresh(false);
                scheduleNextRefresh();
            }
        }
        @Override public void onLost(Network network) {
            synchronized (FuelStationProvider.this) { networkAvailable = hasActiveInternet(); }
        }
    };

    public FuelStationProvider(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        connectivityManager = (ConnectivityManager) this.context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        snapshot = unavailable("Esperando ubicación GPS");
    }

    public synchronized void start(Location currentLocation) {
        active = true;
        registerNetworkCallback();
        if (currentLocation != null) location = new Location(currentLocation);
        refresh(false);
        scheduleNextRefresh();
    }

    public synchronized void stop() {
        active = false;
        main.removeCallbacks(periodicRefresh);
        unregisterNetworkCallback();
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }

    public synchronized void close() { stop(); executor.shutdownNow(); }

    public synchronized void onLocation(Location newLocation) {
        if (newLocation == null) return;
        location = new Location(newLocation);
        if (!active) return;
        boolean moved = lastSelectionLocation == null
                || lastSelectionLocation.distanceTo(location) >= 500f;
        if (moved) {
            lastSelectionLocation = new Location(location);
            refresh(false);
        }
    }

    public synchronized Snapshot getSnapshot() { return snapshot; }
    public int getProductId() { return preferences.getInt(PREF_PRODUCT, DEFAULT_PRODUCT_ID); }
    public int getRadiusKm() { return preferences.getInt(PREF_RADIUS, DEFAULT_RADIUS_KM); }

    public synchronized void configure(int productId, int radiusKm) {
        int previousProduct = getProductId();
        preferences.edit().putInt(PREF_PRODUCT, productId)
                .putInt(PREF_RADIUS, Math.max(1, Math.min(50, radiusKm))).apply();
        if (previousProduct != productId) {
            cache = null;
            lastAttemptAt = 0L;
        }
        refresh(false);
        scheduleNextRefresh();
    }

    public synchronized void forceRefresh() { lastAttemptAt = 0L; refresh(true); }

    private void refresh(boolean forceNetwork) {
        if (!active) return;
        if (location == null) { publish(unavailable("Esperando ubicación GPS")); return; }

        int productId = getProductId();
        int displayRadiusKm = getRadiusKm();
        Fuel fuel = fuel(productId);
        if (cache == null || cache.productId != productId) cache = readCache(productId);
        if (cache != null) publish(select(cache, location, fuel.label, false));

        long now = System.currentTimeMillis();
        boolean coverageUsable = isCoverageUsable(cache, location, displayRadiusKm);
        RequestType type;
        Set<String> provinceIds = Collections.emptySet();
        String provinceSignature = "";
        boolean needsNetwork;
        if (!coverageUsable) {
            type = RequestType.COVERAGE;
            needsNetwork = true;
        } else {
            provinceIds = nearbyProvinceIds(cache, location, displayRadiusKm);
            provinceSignature = provinceSignature(provinceIds);
            type = RequestType.LOCAL_PRICES;
            needsNetwork = forceNetwork
                    || now - cache.localUpdatedAt >= LOCAL_REFRESH_MS
                    || !provinceSignature.equals(cache.localProvinceSignature);
            if (provinceIds.isEmpty()) {
                type = RequestType.COVERAGE;
                needsNetwork = forceNetwork
                        || now - cache.coverageFetchedAt >= COVERAGE_REFRESH_MS;
            }
        }

        if (!forceNetwork && now - lastAttemptAt < MIN_RETRY_INTERVAL_MS) needsNetwork = false;
        if (!needsNetwork || taskRunning) return;
        taskRunning = true;
        lastAttemptAt = now;

        Snapshot selected = cache == null ? null : select(cache, location, fuel.label, false);
        String message = type == RequestType.COVERAGE
                ? "Preparando zona de 150 km…" : "Actualizando precios cercanos…";
        publish(new Snapshot(fuel.label, displayRadiusKm,
                selected == null ? "" : selected.datasetDate, message,
                selected == null ? null : selected.cheapest,
                selected == null ? null : selected.nearest, true, cache != null,
                selected == null ? 0L : selected.updatedAt));

        Location requestLocation = new Location(location);
        Cache requestCache = cache;
        Set<String> requestedProvinces = new LinkedHashSet<>(provinceIds);
        String requestedSignature = provinceSignature;
        RequestType requestType = type;
        executor.execute(() -> download(requestType, productId, fuel.label, requestLocation,
                requestCache, requestedProvinces, requestedSignature));
    }

    private boolean isCoverageUsable(Cache source, Location current, int displayRadiusKm) {
        if (source == null || source.coverageRadiusKm < CACHE_COVERAGE_KM) return false;
        if (System.currentTimeMillis() - source.coverageFetchedAt >= COVERAGE_REFRESH_MS) return false;
        double traveled = distanceKm(source.centerLatitude, source.centerLongitude,
                current.getLatitude(), current.getLongitude());
        double safeDistance = Math.max(10d, source.coverageRadiusKm - displayRadiusKm - 5d);
        return traveled <= safeDistance;
    }

    private void download(RequestType type, int productId, String fuelLabel, Location center,
                          Cache requestCache, Set<String> provinceIds, String provinceSignature) {
        Cache downloaded = null;
        String error = null;
        try {
            downloaded = type == RequestType.COVERAGE || requestCache == null
                    ? fetchCoverage(productId, center)
                    : refreshLocalPrices(requestCache, productId, provinceIds, provinceSignature);
            writeCache(downloaded);
        } catch (Exception exception) {
            error = exception.getClass().getSimpleName();
        } finally { activeConnection = null; }

        synchronized (this) {
            taskRunning = false;
            if (getProductId() != productId) { cache = null; refresh(false); return; }
            if (downloaded != null) cache = downloaded;
            if (!active) return;
            if (cache != null) {
                Snapshot selected = select(cache, location, fuelLabel, downloaded == null);
                if (downloaded == null) selected = new Snapshot(selected.fuelLabel,
                        selected.radiusKm, selected.datasetDate, "Sin red · mostrando caché",
                        selected.cheapest, selected.nearest, false, true, selected.updatedAt);
                publish(selected);
            } else {
                publish(new Snapshot(fuelLabel, getRadiusKm(), "",
                        "No se pudieron descargar los precios"
                                + (error == null ? "" : " · " + error),
                        null, null, false, false, 0L));
            }
            scheduleNextRefresh();
        }
    }

    private Cache fetchCoverage(int productId, Location center) throws Exception {
        FetchResult result = fetchStations(API_NATIONAL + productId, center, CACHE_COVERAGE_KM);
        long now = System.currentTimeMillis();
        Cache provisional = new Cache(productId, CACHE_COVERAGE_KM,
                center.getLatitude(), center.getLongitude(), now, now, "",
                result.datasetDate, result.stations);
        String signature = provinceSignature(nearbyProvinceIds(provisional, center, getRadiusKm()));
        return new Cache(productId, CACHE_COVERAGE_KM, center.getLatitude(),
                center.getLongitude(), now, now, signature, result.datasetDate, result.stations);
    }

    private Cache refreshLocalPrices(Cache source, int productId, Set<String> provinceIds,
                                     String provinceSignature) throws Exception {
        Map<String, Station> merged = new LinkedHashMap<>();
        for (Station station : source.stations) {
            if (!provinceIds.contains(station.provinceId)) merged.put(station.id, station);
        }
        Location cacheCenter = new Location("fuel-cache");
        cacheCenter.setLatitude(source.centerLatitude);
        cacheCenter.setLongitude(source.centerLongitude);
        String datasetDate = source.datasetDate;
        for (String provinceId : provinceIds) {
            FetchResult result = fetchStations(API_PROVINCE + provinceId + "/" + productId,
                    cacheCenter, source.coverageRadiusKm);
            if (!result.datasetDate.isEmpty()) datasetDate = result.datasetDate;
            for (Station station : result.stations) merged.put(station.id, station);
        }
        return new Cache(productId, source.coverageRadiusKm, source.centerLatitude,
                source.centerLongitude, source.coverageFetchedAt, System.currentTimeMillis(),
                provinceSignature, datasetDate, new ArrayList<>(merged.values()));
    }

    private FetchResult fetchStations(String endpoint, Location center, int retainRadiusKm)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        activeConnection = connection;
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(25_000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Language", "es-ES");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        List<Station> stations = new ArrayList<>();
        String datasetDate = "";
        try (InputStream stream = new BufferedInputStream(connection.getInputStream());
             JsonReader reader = new JsonReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("Fecha".equals(name)) datasetDate = nextString(reader);
                else if ("ListaEESSPrecio".equals(name)) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        Station station = readStation(reader, center, retainRadiusKm);
                        if (station != null) stations.add(station);
                    }
                    reader.endArray();
                } else reader.skipValue();
            }
            reader.endObject();
        } finally { connection.disconnect(); }
        return new FetchResult(datasetDate, stations);
    }

    private Station readStation(JsonReader reader, Location center, int radiusKm) throws Exception {
        String id = "", provinceId = "", brand = "Sin marca", address = "", locality = "";
        double latitude = Double.NaN, longitude = Double.NaN, price = Double.NaN;
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            String value;
            switch (key) {
                case "IDEESS": id = nextString(reader); break;
                case "IDProvincia": provinceId = nextString(reader); break;
                case "Rótulo": brand = nextString(reader); break;
                case "Dirección": address = nextString(reader); break;
                case "Localidad": locality = nextString(reader); break;
                case "Latitud": value = nextString(reader); latitude = parseSpanish(value); break;
                case "Longitud (WGS84)": value = nextString(reader); longitude = parseSpanish(value); break;
                case "PrecioProducto": value = nextString(reader); price = parseSpanish(value); break;
                default: reader.skipValue();
            }
        }
        reader.endObject();
        if (id.isEmpty() || provinceId.isEmpty() || !Double.isFinite(latitude)
                || !Double.isFinite(longitude) || !Double.isFinite(price) || price <= 0d) return null;
        double distance = distanceKm(center.getLatitude(), center.getLongitude(), latitude, longitude);
        if (distance > radiusKm) return null;
        String fullAddress = locality.isEmpty() ? address : address + ", " + locality;
        return new Station(id, provinceId, clean(brand, 80), clean(fullAddress, 180),
                latitude, longitude, price, distance);
    }

    private Set<String> nearbyProvinceIds(Cache source, Location current, int displayRadiusKm) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        double searchRadius = Math.max(30d, displayRadiusKm + 20d);
        Station nearest = null;
        for (Station station : source.stations) {
            Station candidate = atCurrentDistance(station, current);
            if (nearest == null || candidate.distanceKm < nearest.distanceKm) nearest = candidate;
            if (candidate.distanceKm <= searchRadius && !candidate.provinceId.isEmpty()) {
                ids.add(candidate.provinceId);
                if (ids.size() >= MAX_LOCAL_PROVINCES) break;
            }
        }
        if (ids.isEmpty() && nearest != null && !nearest.provinceId.isEmpty()) ids.add(nearest.provinceId);
        return ids;
    }

    private static String provinceSignature(Set<String> provinceIds) {
        List<String> sorted = new ArrayList<>(provinceIds);
        Collections.sort(sorted);
        return String.join(",", sorted);
    }

    private synchronized Snapshot select(Cache source, Location current, String fuelLabel,
                                         boolean cached) {
        int radiusKm = getRadiusKm();
        Station cheapest = selectCheapest(source, current, radiusKm);
        Station nearest = selectNearest(source, current, radiusKm);
        String message = cheapest == null ? "Sin estaciones en " + radiusKm + " km" : "";
        return new Snapshot(fuelLabel, radiusKm, source.datasetDate, message,
                cheapest, nearest, false, cached, source.localUpdatedAt);
    }

    private Station selectCheapest(Cache source, Location current, int radiusKm) {
        Station best = null;
        for (Station original : source.stations) {
            Station candidate = atCurrentDistance(original, current);
            if (candidate.distanceKm > radiusKm) continue;
            if (best == null || candidate.price < best.price
                    || (candidate.price == best.price && candidate.distanceKm < best.distanceKm)) best = candidate;
        }
        return best;
    }

    private Station selectNearest(Cache source, Location current, int radiusKm) {
        Station best = null;
        for (Station original : source.stations) {
            Station candidate = atCurrentDistance(original, current);
            if (candidate.distanceKm > radiusKm) continue;
            if (best == null || candidate.distanceKm < best.distanceKm) best = candidate;
        }
        return best;
    }

    private Station atCurrentDistance(Station station, Location current) {
        double distance = distanceKm(current.getLatitude(), current.getLongitude(),
                station.latitude, station.longitude);
        return new Station(station.id, station.provinceId, station.brand, station.address,
                station.latitude, station.longitude, station.price, distance);
    }

    private Snapshot unavailable(String message) {
        Fuel fuel = fuel(getProductId());
        return new Snapshot(fuel.label, getRadiusKm(), "", message, null, null, false, false, 0L);
    }

    private void publish(Snapshot value) {
        snapshot = value;
        if (listener != null) main.post(() -> listener.onSnapshot(value));
    }

    private void scheduleNextRefresh() {
        main.removeCallbacks(periodicRefresh);
        if (!active) return;
        long delay = cache == null ? LOCAL_REFRESH_MS
                : Math.max(60_000L, cache.localUpdatedAt + LOCAL_REFRESH_MS
                - System.currentTimeMillis());
        main.postDelayed(periodicRefresh, delay);
    }

    private File cacheFile(int productId) {
        return new File(context.getCacheDir(), "fuel-widget-" + productId + ".bin");
    }

    private void registerNetworkCallback() {
        if (networkCallbackRegistered || connectivityManager == null) return;
        try {
            networkAvailable = hasActiveInternet();
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            connectivityManager.registerNetworkCallback(request, networkCallback);
            networkCallbackRegistered = true;
        } catch (Exception ignored) {}
    }

    private void unregisterNetworkCallback() {
        if (!networkCallbackRegistered || connectivityManager == null) return;
        try { connectivityManager.unregisterNetworkCallback(networkCallback); }
        catch (Exception ignored) {}
        networkCallbackRegistered = false;
    }

    private boolean hasActiveInternet() {
        if (connectivityManager == null) return false;
        try {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
            return capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception ignored) { return false; }
    }

    private Cache readCache(int productId) {
        File file = cacheFile(productId);
        if (!file.isFile()) return null;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != CACHE_VERSION) return null;
            int storedProduct = in.readInt(), coverageRadius = in.readInt();
            double latitude = in.readDouble(), longitude = in.readDouble();
            long coverageFetchedAt = in.readLong(), localUpdatedAt = in.readLong();
            String provinceSignature = in.readUTF(), datasetDate = in.readUTF();
            int count = Math.max(0, Math.min(10_000, in.readInt()));
            List<Station> stations = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                stations.add(new Station(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(),
                        in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble()));
            }
            return storedProduct == productId
                    ? new Cache(storedProduct, coverageRadius, latitude, longitude,
                    coverageFetchedAt, localUpdatedAt, provinceSignature, datasetDate, stations) : null;
        } catch (Exception ignored) { return null; }
    }

    private void writeCache(Cache value) {
        File destination = cacheFile(value.productId);
        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(temporary)))) {
            out.writeInt(CACHE_VERSION); out.writeInt(value.productId);
            out.writeInt(value.coverageRadiusKm); out.writeDouble(value.centerLatitude);
            out.writeDouble(value.centerLongitude); out.writeLong(value.coverageFetchedAt);
            out.writeLong(value.localUpdatedAt); out.writeUTF(clean(value.localProvinceSignature, 120));
            out.writeUTF(clean(value.datasetDate, 120)); out.writeInt(value.stations.size());
            for (Station station : value.stations) {
                out.writeUTF(clean(station.id, 80)); out.writeUTF(clean(station.provinceId, 20));
                out.writeUTF(clean(station.brand, 80)); out.writeUTF(clean(station.address, 180));
                out.writeDouble(station.latitude); out.writeDouble(station.longitude);
                out.writeDouble(station.price); out.writeDouble(station.distanceKm);
            }
        } catch (Exception ignored) { return; }
        if (!temporary.renameTo(destination)) {
            try (InputStream input = new FileInputStream(temporary);
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[8_192]; int read;
                while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            } catch (Exception ignored) {}
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }

    private static Fuel fuel(int productId) {
        for (Fuel fuel : FUELS) if (fuel.productId == productId) return fuel;
        return FUELS[0];
    }

    private static String nextString(JsonReader reader) throws Exception {
        switch (reader.peek()) {
            case NULL: reader.nextNull(); return "";
            case STRING: return reader.nextString();
            case NUMBER: return String.valueOf(reader.nextDouble());
            case BOOLEAN: return String.valueOf(reader.nextBoolean());
            default: reader.skipValue(); return "";
        }
    }

    private static double parseSpanish(String value) {
        if (value == null) return Double.NaN;
        try { return Double.parseDouble(value.trim().replace(',', '.')); }
        catch (Exception ignored) { return Double.NaN; }
    }

    private static String clean(String value, int max) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double earth = 6_371.0088d, dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2d) * Math.sin(dLat / 2d)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2d) * Math.sin(dLon / 2d);
        return earth * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }

    private static final class FetchResult {
        final String datasetDate; final List<Station> stations;
        FetchResult(String datasetDate, List<Station> stations) {
            this.datasetDate = datasetDate == null ? "" : datasetDate; this.stations = stations;
        }
    }

    private static final class Cache {
        final int productId, coverageRadiusKm;
        final double centerLatitude, centerLongitude;
        final long coverageFetchedAt, localUpdatedAt;
        final String localProvinceSignature, datasetDate;
        final List<Station> stations;
        Cache(int productId, int coverageRadiusKm, double centerLatitude,
              double centerLongitude, long coverageFetchedAt, long localUpdatedAt,
              String localProvinceSignature, String datasetDate, List<Station> stations) {
            this.productId = productId; this.coverageRadiusKm = coverageRadiusKm;
            this.centerLatitude = centerLatitude; this.centerLongitude = centerLongitude;
            this.coverageFetchedAt = coverageFetchedAt; this.localUpdatedAt = localUpdatedAt;
            this.localProvinceSignature = localProvinceSignature == null ? "" : localProvinceSignature;
            this.datasetDate = datasetDate == null ? "" : datasetDate;
            this.stations = Collections.unmodifiableList(new ArrayList<>(stations));
        }
    }
}
