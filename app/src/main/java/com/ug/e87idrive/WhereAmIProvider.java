package com.ug.e87idrive;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.Locale;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Produces a compact, honest description of the current position. The matched OSM road and
 * packaged province are always available offline; locality is added only when Android's reverse
 * geocoder actually returns it.
 */
final class WhereAmIProvider implements AutoCloseable {
    interface Listener { void onLabel(String label); }

    private static final float MIN_REQUEST_DISTANCE_METERS = 80f;
    private static final long MIN_REQUEST_INTERVAL_MS = 15_000L;
    private static final long MAX_REUSE_INTERVAL_MS = 90_000L;

    private final Geocoder geocoder;
    private final SharedPreferences tomTomPreferences;
    private final ConnectivityManager connectivity;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Location lastRequestedLocation;
    private long lastRequestedAt;
    private String lastRoadKey = "";
    private String lastPublished = "";
    private String cachedAddressLabel = "";
    private String cachedAddressRoadKey = "";
    private Location cachedAddressLocation;
    private long generation;
    private long lastFailureLoggedAt;
    private boolean closed;

    WhereAmIProvider(Context context, Listener listener) {
        this.listener = listener;
        Context app=context.getApplicationContext();
        geocoder = Geocoder.isPresent() ? new Geocoder(app, new Locale("es", "ES")) : null;
        tomTomPreferences=app.getSharedPreferences("tomtom_traffic",Context.MODE_PRIVATE);
        connectivity=(ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    void resolve(Location location, SpeedLimitRepository.Match match) {
        if (closed) return;
        String fallback = offlineLabel(match);
        if (location == null || !valid(location)) {
            publish(fallback);
            return;
        }

        String roadKey = roadKey(match);
        boolean roadChanged = !roadKey.equals(lastRoadKey);
        if (roadChanged) {
            // A result already in flight belongs to the previous road. Invalidate it even when
            // the request throttle prevents an immediate replacement at a junction.
            generation++;
            lastRoadKey = roadKey;
            cachedAddressLabel = "";
            cachedAddressRoadKey = "";
            cachedAddressLocation = null;
        }
        String base = cachedAddressLabel;
        if (base.isEmpty() || !roadKey.equals(cachedAddressRoadKey)
                || cachedAddressLocation == null
                || cachedAddressLocation.distanceTo(location) > 250f) base = fallback;
        publish(withCoordinates(base, location));
        if (geocoder == null && !tomTomAvailable()) return;

        long now = android.os.SystemClock.elapsedRealtime();
        float movement = lastRequestedLocation == null ? Float.MAX_VALUE
                : lastRequestedLocation.distanceTo(location);
        long age = now - lastRequestedAt;
        if (!roadChanged && movement < MIN_REQUEST_DISTANCE_METERS
                && age < MAX_REUSE_INTERVAL_MS) return;
        if (age < MIN_REQUEST_INTERVAL_MS && movement < MIN_REQUEST_DISTANCE_METERS) return;

        lastRequestedLocation = new Location(location);
        lastRequestedAt = now;
        long request = ++generation;
        Location requested = new Location(location);
        executor.execute(() -> reverseGeocode(request, requested, match, roadKey, fallback));
    }

    @SuppressWarnings("deprecation")
    private void reverseGeocode(long request, Location location,
                                SpeedLimitRepository.Match match, String roadKey, String fallback) {
        String label = fallback;
        boolean hasLocality=false;
        try {
            if(geocoder!=null){
                List<Address> addresses = geocoder.getFromLocation(
                        location.getLatitude(), location.getLongitude(), 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address address=addresses.get(0);
                    label = addressLabel(address, match);
                    hasLocality=!first(address.getLocality(),address.getSubLocality()).isEmpty();
                }
            }
        } catch (Exception exception) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (lastFailureLoggedAt == 0L
                    || now - lastFailureLoggedAt >= 30L * 60L * 1_000L) {
                lastFailureLoggedAt = now;
                AppSessionLog.event("UBICACIÓN",
                        "Geocodificación no disponible; se mantiene vía offline");
            }
        }
        if(!hasLocality&&tomTomAvailable()&&TomTomUsageBudget.reserve(tomTomPreferences,
                TomTomUsageBudget.REVERSE,TomTomUsageBudget.REVERSE_LIMIT,
                System.currentTimeMillis())){
            try{
                String online=tomTomLabel(location,match);
                if(!online.isEmpty())label=online;
            }catch(Exception exception){
                AppSessionLog.sampledEvent("tomtom-reverse","UBICACIÓN TOMTOM",
                        "Consulta aplazada · "+exception.getClass().getSimpleName(),
                        30L*60L*1_000L);
            }
        }
        String result = clean(label);
        main.post(() -> {
            if (closed || request != generation) return;
            cachedAddressLabel = result.isEmpty() ? fallback : result;
            cachedAddressRoadKey = roadKey;
            cachedAddressLocation = new Location(location);
            publish(withCoordinates(cachedAddressLabel, location));
        });
    }

    private static String addressLabel(Address address, SpeedLimitRepository.Match match) {
        String locality = first(address.getLocality(), address.getSubLocality());
        String province = match == null ? "" : SpeedLimitRepository.provinceLabel(match.province);
        if (province.isEmpty() || "AUTO".equalsIgnoreCase(province)) {
            province = first(address.getSubAdminArea(), address.getAdminArea());
        }

        String matchedRoad = preferredRoad(match);
        String geocodedRoad = clean(address.getThoroughfare());
        String road = first(matchedRoad, geocodedRoad);
        return format(locality, province, road);
    }

    private String tomTomLabel(Location location,SpeedLimitRepository.Match match)throws Exception{
        String key=tomTomPreferences.getString("api_key","").trim();
        String position=String.format(Locale.ROOT,"%.7f,%.7f",
                location.getLatitude(),location.getLongitude());
        Uri uri=Uri.parse("https://api.tomtom.com/search/2/reverseGeocode/"+position+".json")
                .buildUpon().appendQueryParameter("key",key)
                .appendQueryParameter("language","es-ES").build();
        HttpURLConnection connection=(HttpURLConnection)new URL(uri.toString()).openConnection();
        connection.setConnectTimeout(12_000);connection.setReadTimeout(20_000);
        connection.setRequestProperty("User-Agent","BMW-E87-iDrive/1.28 (reverse geocode)");
        try{
            int status=connection.getResponseCode();if(status!=200)throw new IOException("HTTP "+status);
            JSONObject root=new JSONObject(read(connection.getInputStream(),512*1024));
            JSONArray addresses=root.optJSONArray("addresses");
            JSONObject wrapper=addresses==null?null:addresses.optJSONObject(0);
            JSONObject address=wrapper==null?null:wrapper.optJSONObject("address");
            if(address==null)return "";
            String locality=first(address.optString("municipality"),
                    address.optString("municipalitySubdivision"));
            String province=first(address.optString("countrySecondarySubdivision"),
                    address.optString("countrySubdivision"));
            String road=first(preferredRoad(match),address.optString("streetName"));
            return format(locality,province,road);
        }finally{connection.disconnect();}
    }

    private boolean tomTomAvailable(){
        if(!tomTomPreferences.getBoolean("enabled",false)
                ||tomTomPreferences.getString("api_key","").trim().isEmpty()
                ||connectivity==null)return false;
        Network network=connectivity.getActiveNetwork();
        NetworkCapabilities capabilities=network==null?null:connectivity.getNetworkCapabilities(network);
        return capabilities!=null&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static String read(InputStream input,int maximum)throws IOException{
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(InputStream in=input){byte[] buffer=new byte[4096];int count,total=0;
            while((count=in.read(buffer))>=0){total+=count;if(total>maximum)throw new IOException("Respuesta demasiado grande");output.write(buffer,0,count);}}
        return output.toString("UTF-8");
    }

    static String offlineLabel(SpeedLimitRepository.Match match) {
        if (match == null) return "Esperando identificación de la vía";
        String province = SpeedLimitRepository.provinceLabel(match.province);
        if ("AUTO".equalsIgnoreCase(province)) province = "";
        String road = preferredRoad(match);
        if (province.isEmpty() && road.isEmpty()) return "Vía sin identificar";
        if (province.isEmpty()) return road;
        if (road.isEmpty()) return "Provincia de " + province;
        return "Provincia de " + province + " · " + road;
    }

    static String format(String locality, String province, String road) {
        locality = clean(locality);
        province = clean(province);
        road = clean(road);

        String place = locality;
        if (!locality.isEmpty() && !province.isEmpty()
                && !locality.equalsIgnoreCase(province)) {
            place = locality + " (" + province + ")";
        } else if (place.isEmpty() && !province.isEmpty()) {
            place = "Provincia de " + province;
        }

        if (place.isEmpty()) return road.isEmpty() ? "Ubicación sin identificar" : road;
        return road.isEmpty() ? place : place + " · " + road;
    }

    static String withCoordinates(String label, Location location) {
        if (location == null || !valid(location)) return clean(label);
        return String.format(Locale.ROOT, "%s · GPS %.6f, %.6f",
                clean(label), location.getLatitude(), location.getLongitude());
    }

    void locationUnavailable() {
        if (closed) return;
        generation++;
        cachedAddressLabel = "";
        cachedAddressRoadKey = "";
        cachedAddressLocation = null;
        publish("Ubicación GPS no disponible");
    }

    private static String preferredRoad(SpeedLimitRepository.Match match) {
        if (match == null) return "";
        String name = clean(match.roadName);
        String ref = clean(match.roadRef);
        boolean numberedRoad = !ref.isEmpty() && ("motorway".equals(match.roadClass)
                || "trunk".equals(match.roadClass) || "primary".equals(match.roadClass)
                || "secondary".equals(match.roadClass));
        if (numberedRoad) return ref;
        return first(name, ref);
    }

    private static String roadKey(SpeedLimitRepository.Match match) {
        return match == null ? "" : clean(match.osmId) + '|' + preferredRoad(match);
    }

    private static String first(String... values) {
        for (String value : values) {
            String cleaned = clean(value);
            if (!cleaned.isEmpty()) return cleaned;
        }
        return "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ')
                .replaceAll("\\s+", " ").trim();
    }

    private static boolean valid(Location location) {
        return Double.isFinite(location.getLatitude()) && Double.isFinite(location.getLongitude())
                && Math.abs(location.getLatitude()) <= 90d && Math.abs(location.getLongitude()) <= 180d;
    }

    private void publish(String label) {
        String cleaned = clean(label);
        if (cleaned.isEmpty() || cleaned.equals(lastPublished)) return;
        lastPublished = cleaned;
        listener.onLabel(cleaned);
    }

    @Override public void close() {
        closed = true;
        generation++;
        main.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }
}
