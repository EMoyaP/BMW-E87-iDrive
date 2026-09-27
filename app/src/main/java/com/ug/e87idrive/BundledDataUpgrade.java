package com.ug.e87idrive;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Invalidates app-owned source caches once per installed APK revision.
 *
 * Android deliberately preserves an application's data directory during an update. That is
 * useful for user preferences, but it is unsafe for bundled road/radar seeds: an older SQLite
 * cache could otherwise remain alongside the new APK assets. This class removes only source
 * datasets and update markers; vehicle/app/UI preferences are intentionally left untouched.
 */
final class BundledDataUpgrade {
    private static final String PREFS = "bundled_data_upgrade";
    private static final String KEY_REVISION = "last_applied_app_version";
    private static final String[] DATA_DATABASES = {
            "e87_speed_limits.db",
            "e87_dgt_speed.db",
            "e87_dgt_radars.db",
            "e87_dgt_invive.db"
    };
    private static final String[] DATA_PREFERENCES = {
            "speed_limit_updates",
            "dgt_speed_updates",
            "dgt_radar_updates",
            "osm_radar_updates",
            "dgt_invive_updates"
    };

    private BundledDataUpgrade() { }

    static void apply(Context context) {
        if (context == null) return;
        SharedPreferences marker = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int currentVersion = installedAppVersion(context);
        if (marker.getInt(KEY_REVISION, 0) == currentVersion) return;

        int deletedDatabases = 0;
        for (String database : DATA_DATABASES) {
            try {
                if (context.deleteDatabase(database)) deletedDatabases++;
            } catch (Exception error) {
                AppSessionLog.event("DATOS", "No se pudo limpiar " + database + " · "
                        + error.getClass().getSimpleName());
            }
        }
        for (String preferences : DATA_PREFERENCES) {
            try {
                context.getSharedPreferences(preferences, Context.MODE_PRIVATE)
                        .edit().clear().apply();
            } catch (Exception error) {
                AppSessionLog.event("DATOS", "No se pudo reiniciar preferencias " + preferences
                        + " · " + error.getClass().getSimpleName());
            }
        }
        int deletedFuelCaches = clearFuelCaches(context.getCacheDir());
        marker.edit().putInt(KEY_REVISION, currentVersion).apply();
        AppSessionLog.event("DATOS", "Cachés de fuentes reiniciadas por actualización · APK="
                + currentVersion + " · bases=" + deletedDatabases + " · gasolineras="
                + deletedFuelCaches + " · configuración personal conservada");
    }

    private static int clearFuelCaches(File cacheDirectory) {
        if (cacheDirectory == null) return 0;
        File[] files = cacheDirectory.listFiles(file -> file != null
                && file.isFile() && file.getName().startsWith("fuel-widget-"));
        if (files == null) return 0;
        int deleted = 0;
        for (File file : files) {
            try {
                if (file.delete()) deleted++;
            } catch (Exception ignored) { }
        }
        return deleted;
    }

    @SuppressWarnings("deprecation")
    private static int installedAppVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception ignored) {
            // The fallback keeps the migration deterministic if package metadata is unavailable.
            return 65;
        }
    }
}
