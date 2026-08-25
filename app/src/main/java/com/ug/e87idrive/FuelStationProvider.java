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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
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
    private static final int CACHE_VERSION = 3;
    private static final int MAX_LOCAL_PROVINCES = 4;
    private static final String ALICANTE_DIESEL_ASSET =
            "e87_fuel_stations_alicante_diesel.json";
    private static final double ALICANTE_SEED_LATITUDE = 38.345d;
    private static final double ALICANTE_SEED_LONGITUDE = -0.481d;
    private static final int ALICANTE_SEED_COVERAGE_KM = 180;

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
    private String lastLogSignature = "";
    private Runnable pendingForceCallback;

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
                boolean nowAvailable = hasActiveInternet();
                boolean recovered = !networkAvailable;
                networkAvailable = nowAvailable;
                if (recovered && nowAvailable) lastAttemptAt = 0L;
                refresh(false);
                scheduleNextRefresh();
            }
        }
        @Override public void onCapabilitiesChanged(Network network,
                                                    NetworkCapabilities networkCapabilities) {
            synchronized (FuelStationProvider.this) {
                boolean nowAvailable = hasActiveInternet();
                boolean recovered = !networkAvailable && nowAvailable;
                networkAvailable = nowAvailable;
                if (recovered) lastAttemptAt = 0L;
                if (recovered) refresh(false);
                scheduleNextRefresh();
            }
        }
        @Override public void onLost(Network network) {
            synchronized (FuelStationProvider.this) {
                networkAvailable = hasActiveInternet();
                publishNetworkStateIfNeeded();
            }
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
    /** True when Android exposes an IP-capable network, including a Bluetooth PAN. */
    public boolean isInternetAvailable() { return hasActiveInternet(); }
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

    /** Manual update entry point used by the update progress modal. */
    public synchronized void forceRefresh(Runnable callback) {
        pendingForceCallback = callback;
        lastAttemptAt = 0L;
        refresh(true);
    }

    private void refresh(boolean forceNetwork) {
        if (!active) { completeForceCallback(); return; }
        if (location == null) {
            publish(unavailable("Esperando ubicación GPS"));
            completeForceCallback();
            return;
        }

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
        if (!needsNetwork) { completeForceCallback(); return; }
        if (taskRunning) return;
        if (!hasActiveInternet()) {
            Snapshot selected = cache == null ? null : select(cache, location, fuel.label, true);
            publish(new Snapshot(fuel.label, displayRadiusKm,
                    selected == null ? "" : selected.datasetDate,
                    "Android sin Internet · activa hotspot, PAN o Wi-Fi",
                    selected == null ? null : selected.cheapest,
                    selected == null ? null : selected.nearest,
                    false, selected != null, selected == null ? 0L : selected.updatedAt));
            completeForceCallback();
            return;
        }
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

        Runnable callback;
        synchronized (this) {
            taskRunning = false;
            if (getProductId() != productId) {
                cache = null;
                callback = pendingForceCallback;
                pendingForceCallback = null;
                refresh(false);
                completeCallback(callback);
                return;
            }
            if (downloaded != null) cache = downloaded;
            if (!active) {
                callback = pendingForceCallback;
                pendingForceCallback = null;
                completeCallback(callback);
                return;
            }
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
            callback = pendingForceCallback;
            pendingForceCallback = null;
        }
        completeCallback(callback);
    }

    private synchronized void completeForceCallback() {
        Runnable callback = pendingForceCallback;
        pendingForceCallback = null;
        completeCallback(callback);
    }

    private void completeCallback(Runnable callback) {
        if (callback != null) main.post(callback);
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
        Network network = validatedNetwork();
        if (network == null) throw new IllegalStateException("Android no publica una red IP utilizable");
        HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL(endpoint));
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
        String signature = value.message + '|' + value.loading + '|' + value.cached + '|'
                + value.updatedAt + '|' + networkLabel();
        if (!signature.equals(lastLogSignature)) {
            lastLogSignature = signature;
            AppSessionLog.event("GASOLINERAS", "red=" + networkLabel() + " · estado="
                    + (value.message == null || value.message.isEmpty() ? "datos disponibles" : value.message)
                    + " · caché=" + value.cached + " · cargando=" + value.loading);
        }
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
        return validatedNetwork() != null;
    }

    /**
     * Uses a validated network first, then any Android network that advertises
     * INTERNET. Some head units mark the phone/PAN link as unvalidated even
     * though URL traffic works; the request itself remains bounded and cached
     * on failure.
     */
    private Network validatedNetwork() {
        if (connectivityManager == null) return null;
        try {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (isValidated(activeNetwork)) return activeNetwork;
            for (Network candidate : connectivityManager.getAllNetworks()) {
                if (isValidated(candidate)) return candidate;
            }
            if (hasInternetCapability(activeNetwork)) return activeNetwork;
            for (Network candidate : connectivityManager.getAllNetworks()) {
                if (hasInternetCapability(candidate)) return candidate;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private boolean hasInternetCapability(Network network) {
        if (network == null) return false;
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private boolean isValidated(Network network) {
        if (network == null) return false;
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    public String networkLabel() {
        if (connectivityManager == null) return "SIN RED";
        try {
            Network selected = validatedNetwork();
            if (selected == null) selected = connectivityManager.getActiveNetwork();
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(selected);
            if (capabilities == null) return "SIN RED";
            String transport;
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transport = "ETHERNET";
            else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transport = "WI-FI";
            else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transport = "MÓVIL";
            else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transport = "BT PAN";
            else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transport = "VPN";
            else transport = "RED";
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    ? transport : transport + " SIN INTERNET";
        } catch (Exception ignored) { return "RED DESCONOCIDA"; }
    }

    public String networkDiagnostic() {
        StringBuilder out = new StringBuilder("RED PARA GASOLINERAS\n");
        out.append("red activa=").append(networkLabel()).append('\n');
        Network selected = validatedNetwork();
        NetworkCapabilities selectedCapabilities = selected == null ? null
                : connectivityManager.getNetworkCapabilities(selected);
        out.append("red IP utilizable publicada por Android=").append(selected != null).append('\n');
        out.append("Internet validado por Android=").append(selectedCapabilities != null
                && selectedCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).append('\n');
        if (connectivityManager != null) try {
            Network[] networks = connectivityManager.getAllNetworks();
            out.append("redes publicadas por Android=").append(networks.length).append('\n');
            for (Network network : networks) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                out.append("- ").append(network).append(" · ")
                        .append(capabilities == null ? "sin capacidades" : capabilities.toString()).append('\n');
            }
        } catch (Exception error) {
            out.append("inventario de redes=").append(error.getClass().getSimpleName()).append('\n');
        }
        out.append("La app usa la red IP de Android de la radio. Una proyección Android Auto "
                + "no se considera acceso a Internet mientras Android no publique una red validada.\n");
        out.append("Bluetooth PAN se usa automáticamente si el sistema lo publica como red validada; "
                + "la app no puede activar el tethering del teléfono mediante una API pública.\n");
        return out.toString();
    }

    private void publishNetworkStateIfNeeded() {
        if (!active || snapshot == null || hasActiveInternet()) return;
        AppSessionLog.event("GASOLINERAS", networkDiagnostic().replace('\n', ' ').trim());
        publish(new Snapshot(snapshot.fuelLabel, snapshot.radiusKm, snapshot.datasetDate,
                "Android sin Internet · activa hotspot, PAN o Wi-Fi",
                snapshot.cheapest, snapshot.nearest, false,
                snapshot.cheapest != null, snapshot.updatedAt));
    }

    private Cache readCache(int productId) {
        File file = cacheFile(productId);
        if (file.isFile()) {
            try (DataInputStream in = new DataInputStream(
                    new BufferedInputStream(new FileInputStream(file)))) {
                if (in.readInt() == CACHE_VERSION) {
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
                    if (storedProduct == productId) {
                        return new Cache(storedProduct, coverageRadius, latitude, longitude,
                                coverageFetchedAt, localUpdatedAt, provinceSignature,
                                datasetDate, stations);
                    }
                }
            } catch (Exception ignored) { }
        }
        // A new app version deliberately ignores older binary caches and starts from the
        // dated official Alicante snapshot packaged in the APK. It is immediately usable
        // offline and is replaced by the normal Ministry endpoint whenever Android has IP.
        return readBundledAlicanteSeed(productId);
    }

    private Cache readBundledAlicanteSeed(int productId) {
        if (productId != DEFAULT_PRODUCT_ID) return null;
        Location center = new Location("alicante-fuel-seed");
        center.setLatitude(ALICANTE_SEED_LATITUDE);
        center.setLongitude(ALICANTE_SEED_LONGITUDE);
        String datasetDate = "";
        List<Station> stations = new ArrayList<>();
        try (InputStream stream = new BufferedInputStream(
                context.getAssets().open(ALICANTE_DIESEL_ASSET));
             JsonReader reader = new JsonReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("Fecha".equals(name)) datasetDate = nextString(reader);
                else if ("ListaEESSPrecio".equals(name)) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        Station station = readStation(reader, center, ALICANTE_SEED_COVERAGE_KM);
                        if (station != null) stations.add(station);
                    }
                    reader.endArray();
                } else reader.skipValue();
            }
            reader.endObject();
        } catch (Exception error) {
            AppSessionLog.event("GASOLINERAS", "Semilla Alicante no disponible · "
                    + error.getClass().getSimpleName());
            return null;
        }
        if (stations.isEmpty()) return null;
        long sourceTime = parseDatasetDate(datasetDate);
        AppSessionLog.event("GASOLINERAS", "Semilla oficial Alicante cargada · "
                + stations.size() + " estaciones · " + datasetDate);
        return new Cache(productId, ALICANTE_SEED_COVERAGE_KM,
                ALICANTE_SEED_LATITUDE, ALICANTE_SEED_LONGITUDE,
                sourceTime, sourceTime, "03", datasetDate, stations);
    }

    private static long parseDatasetDate(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try {
            SimpleDateFormat format = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ROOT);
            format.setLenient(false);
            format.setTimeZone(TimeZone.getTimeZone("Europe/Madrid"));
            Date parsed = format.parse(value.trim());
            return parsed == null ? 0L : parsed.getTime();
        } catch (Exception ignored) { return 0L; }
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
