package com.ug.e87idrive;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Invalidates app-owned source caches once per installed APK revision.
 *
 * Records the installed APK revision and removes only short-lived binary caches. Persistent
 * road, radar and surveillance SQLite databases are migrated by their repositories so weekly
 * deltas and already verified source history survive an application update.
 */
final class BundledDataUpgrade {
    private static final String PREFS = "bundled_data_upgrade";
    private static final String KEY_REVISION = "last_applied_app_version";
    private BundledDataUpgrade() { }

    static void apply(Context context) {
        if (context == null) return;
        SharedPreferences marker = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int currentVersion = installedAppVersion(context);
        if (marker.getInt(KEY_REVISION, 0) == currentVersion) return;

        int deletedFuelCaches = clearFuelCaches(context.getCacheDir());
        marker.edit().putInt(KEY_REVISION, currentVersion).apply();
        AppSessionLog.event("DATOS", "Migración por actualización · APK=" + currentVersion
                + " · cachés temporales=" + deletedFuelCaches
                + " · SQLite e historial incremental conservados");
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
            return 66;
        }
    }
}
