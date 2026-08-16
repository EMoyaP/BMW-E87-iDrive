package com.ug.e87idrive;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

import java.util.List;
import java.util.Locale;

public class GpsSpeedProvider implements LocationListener {
    public interface Listener { void onLocation(Location location, Double kmh); }
    private final Context context;
    private final LocationManager lm;
    private final Listener listener;
    private Double kmh;
    private long timestamp, locationTimestamp;
    private Location lastLocation;
    private String lastLoggedState = "";
    private long lastLoggedAt;

    public GpsSpeedProvider(Context c, Listener l) {
        context=c; listener=l;
        lm=(LocationManager)c.getSystemService(Context.LOCATION_SERVICE);
    }

    public void start() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            AppSessionLog.event("GPS", "Permiso de ubicación precisa no concedido");
            return;
        }
        try {
            if (lm == null) return;
            Location newest = null;
            for (String provider : new String[]{LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                if (!lm.isProviderEnabled(provider)) continue;
                // GPS is preferred for driving speed; network/passive make the fuel card useful
                // immediately while the receiver is acquiring a satellite fix.
                lm.requestLocationUpdates(provider, provider.equals(LocationManager.GPS_PROVIDER) ? 1000 : 5000,
                        0, this);
                Location last = lm.getLastKnownLocation(provider);
                if (last != null && (newest == null || last.getTime() > newest.getTime())) newest = last;
            }
            if (newest != null) onLocationChanged(newest);
        } catch(Exception error) {
            AppSessionLog.event("GPS", "Error al iniciar: " + error.getClass().getSimpleName());
        }
    }

    public void stop() { try { lm.removeUpdates(this); } catch(Exception ignored) {} }
    public Double getKmh() { return kmh; }
    public Double getLastValue() { return kmh; }
    public long getLastTimestamp() { return timestamp; }
    public Location getLastLocation() { return lastLocation == null ? null : new Location(lastLocation); }
    public long getLocationTimestamp() { return locationTimestamp; }

    public String diagnosticReport() {
        StringBuilder out = new StringBuilder(700);
        out.append("GPS ANDROID ESTÁNDAR · SOLO LECTURA\n");
        boolean granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        out.append("permiso ubicación precisa=").append(granted).append('\n');
        if (lm == null) {
            out.append("LocationManager=no disponible\n");
            return out.toString();
        }
        try {
            List<String> providers = lm.getAllProviders();
            out.append("proveedores=").append(providers).append('\n');
            out.append("gps habilitado=").append(lm.isProviderEnabled(LocationManager.GPS_PROVIDER)).append('\n');
            if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
                out.append("network habilitado=")
                        .append(lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)).append('\n');
            }
            if (providers.contains(LocationManager.PASSIVE_PROVIDER)) {
                out.append("pasivo habilitado=")
                        .append(lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)).append('\n');
            }
        } catch (Exception error) {
            out.append("estado proveedores no legible=").append(error.getClass().getSimpleName()).append('\n');
        }
        Location fix = lastLocation;
        if (fix == null) {
            out.append("última posición=(ninguna)\n");
        } else {
            long age = Math.max(0L, System.currentTimeMillis() - locationTimestamp);
            out.append("última posición: proveedor=").append(fix.getProvider())
                    .append(" · edad=").append(age).append(" ms")
                    .append(" · precisión=").append(fix.hasAccuracy()
                            ? String.format(Locale.ROOT, "%.1f m", fix.getAccuracy()) : "no publicada")
                    .append(" · velocidad=").append(fix.hasSpeed()
                            ? String.format(Locale.ROOT, "%.1f km/h", Math.max(0f, fix.getSpeed() * 3.6f))
                            : "no publicada").append('\n');
            out.append("coordenadas omitidas del diagnóstico por privacidad\n");
        }
        return out.toString();
    }

    @Override public void onLocationChanged(Location l) {
        Location previous = lastLocation;
        lastLocation = new Location(l);
        locationTimestamp = System.currentTimeMillis();
        if(l.hasSpeed()) {
            kmh=Math.max(0d,(double)l.getSpeed()*3.6);
            timestamp=locationTimestamp;
        } else if (LocationManager.GPS_PROVIDER.equals(l.getProvider()) && previous != null) {
            long deltaMs = Math.abs(l.getTime() - previous.getTime());
            if (deltaMs >= 500L && deltaMs <= 15_000L) {
                double distance = previous.distanceTo(l);
                double uncertainty = Math.max(3d, Math.max(
                        previous.hasAccuracy() ? previous.getAccuracy() : 0f,
                        l.hasAccuracy() ? l.getAccuracy() : 0f));
                kmh = distance <= uncertainty ? 0d
                        : Math.max(0d, distance / (deltaMs / 1000d) * 3.6d);
                timestamp = locationTimestamp;
            }
        }
        if(listener!=null) listener.onLocation(new Location(l), kmh);
        String logState = "proveedor=" + l.getProvider() + " · precisión="
                + (l.hasAccuracy() ? Math.round(l.getAccuracy()) + "m" : "no publicada")
                + " · velocidad=" + (kmh == null ? "no publicada"
                : Math.round(kmh) + " km/h")
                + (l.hasSpeed() ? " (directa)" : " (estimada/ausente)");
        long now = System.currentTimeMillis();
        if (!logState.equals(lastLoggedState) && now - lastLoggedAt >= 5_000L) {
            lastLoggedState = logState;
            lastLoggedAt = now;
            AppSessionLog.event("GPS", logState);
        }
    }
    @Override public void onStatusChanged(String p,int s,Bundle e) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}
}
