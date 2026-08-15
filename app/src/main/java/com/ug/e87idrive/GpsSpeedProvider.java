package com.ug.e87idrive;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

public class GpsSpeedProvider implements LocationListener {
    public interface Listener { void onLocation(Location location, Double kmh); }
    private final Context context;
    private final LocationManager lm;
    private final Listener listener;
    private Double kmh;
    private long timestamp, locationTimestamp;
    private Location lastLocation;

    public GpsSpeedProvider(Context c, Listener l) {
        context=c; listener=l;
        lm=(LocationManager)c.getSystemService(Context.LOCATION_SERVICE);
    }

    public void start() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return;
        try {
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return;
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this);
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) onLocationChanged(last);
        } catch(Exception ignored) {}
    }

    public void stop() { try { lm.removeUpdates(this); } catch(Exception ignored) {} }
    public Double getKmh() { return kmh; }
    public Double getLastValue() { return kmh; }
    public long getLastTimestamp() { return timestamp; }
    public Location getLastLocation() { return lastLocation == null ? null : new Location(lastLocation); }
    public long getLocationTimestamp() { return locationTimestamp; }

    @Override public void onLocationChanged(Location l) {
        lastLocation = new Location(l);
        locationTimestamp = System.currentTimeMillis();
        if(l.hasSpeed()) {
            kmh=Math.max(0d,(double)l.getSpeed()*3.6);
            timestamp=locationTimestamp;
        }
        if(listener!=null) listener.onLocation(new Location(l), kmh);
    }
    @Override public void onStatusChanged(String p,int s,Bundle e) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}
}
