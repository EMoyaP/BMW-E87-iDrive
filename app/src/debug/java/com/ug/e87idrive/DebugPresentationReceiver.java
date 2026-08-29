package com.ug.e87idrive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.SystemClock;

/**
 * Debug-build-only helper for reproducible documentation screenshots. It never ships in a
 * release APK and it does not feed, alter or emulate CAN, MCU, PDC or OEM services.
 */
public final class DebugPresentationReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || (context.getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        boolean active = intent.getBooleanExtra("active", false);
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit()
                .putBoolean("debug_presentation_active", active)
                .apply();
        if (!intent.hasExtra("latitude") || !intent.hasExtra("longitude")) return;
        injectGpsFix(context, intent);
    }

    /** Injects a complete GPS fix only into the debug emulator after adb grants mock-location. */
    @SuppressWarnings("deprecation")
    private static void injectGpsFix(Context context, Intent intent) {
        try {
            LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (manager == null) return;
            try {
                addGpsTestProvider(manager);
            } catch (IllegalArgumentException ignored) {
                // The test provider is already installed by a prior playback fix.
            }
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            Location fix = new Location(LocationManager.GPS_PROVIDER);
            fix.setLatitude(intent.getDoubleExtra("latitude", 0d));
            fix.setLongitude(intent.getDoubleExtra("longitude", 0d));
            fix.setAccuracy((float) intent.getDoubleExtra("accuracy", 4d));
            fix.setSpeed((float) (intent.getDoubleExtra("speed_kmh", 0d) / 3.6d));
            fix.setBearing((float) intent.getDoubleExtra("bearing", 0d));
            fix.setTime(System.currentTimeMillis());
            fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, fix);
        } catch (SecurityException ignored) {
            // adb did not grant mock-location; production behaviour remains untouched.
        }
    }

    /**
     * ProviderProperties values are compile-time ints. Android's legacy test-provider method
     * accepts the same integer contract on API 30, so the constants are inlined and no API-31
     * class is resolved by a radio running API 30.
     */
    @android.annotation.SuppressLint("NewApi")
    private static void addGpsTestProvider(LocationManager manager) {
        manager.addTestProvider(LocationManager.GPS_PROVIDER,
                false, false, false, false, true, true, true,
                ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE);
    }
}
