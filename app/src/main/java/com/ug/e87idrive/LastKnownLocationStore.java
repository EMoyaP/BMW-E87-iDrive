package com.ug.e87idrive;

import android.content.Context;
import android.location.Location;

/** Private last fix used only to choose regional online updates after the UI process is gone. */
final class LastKnownLocationStore {
    private static final String PREFS = "e87_last_location";
    private LastKnownLocationStore() { }

    static void save(Context context, Location location) {
        if (location == null || !Double.isFinite(location.getLatitude())
                || !Double.isFinite(location.getLongitude())) return;
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("lat", Double.doubleToRawLongBits(location.getLatitude()))
                .putLong("lon", Double.doubleToRawLongBits(location.getLongitude()))
                .putLong("time", System.currentTimeMillis()).apply();
    }

    static Location read(Context context) {
        android.content.SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!preferences.contains("lat") || !preferences.contains("lon")) return null;
        Location location = new Location("persisted-update-location");
        location.setLatitude(Double.longBitsToDouble(preferences.getLong("lat", 0L)));
        location.setLongitude(Double.longBitsToDouble(preferences.getLong("lon", 0L)));
        location.setTime(preferences.getLong("time", System.currentTimeMillis()));
        location.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
        return location;
    }
}
