package com.ug.e87idrive;

import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Shared monthly guardrails for optional TomTom calls. No credential is stored here. */
final class TomTomUsageBudget {
    static final String SNAP = "snap";
    static final String FLOW = "flow";
    static final String INCIDENTS = "incidents";
    static final String REVERSE = "reverse";

    // Eighty percent of the documented Freemium quotas leaves room for manual diagnostics.
    static final int SNAP_LIMIT = 2_000;
    static final int FLOW_LIMIT = 16_000;
    static final int INCIDENT_LIMIT = 2_000;
    static final int REVERSE_LIMIT = 16_000;

    private static final Object LOCK = new Object();

    private TomTomUsageBudget() {}

    static boolean reserve(SharedPreferences preferences, String service, int limit, long now) {
        if (preferences == null || service == null || limit <= 0) return false;
        synchronized (LOCK) {
            String month = month(now);
            String monthKey = "quota_" + service + "_month";
            String countKey = "quota_" + service + "_count";
            String storedMonth = preferences.getString(monthKey, "");
            int count = month.equals(storedMonth) ? preferences.getInt(countKey, 0) : 0;
            if (count >= limit) return false;
            preferences.edit().putString(monthKey, month).putInt(countKey, count + 1).apply();
            return true;
        }
    }

    static int used(SharedPreferences preferences, String service, long now) {
        if (preferences == null) return 0;
        synchronized (LOCK) {
            String month = month(now);
            return month.equals(preferences.getString("quota_" + service + "_month", ""))
                    ? preferences.getInt("quota_" + service + "_count", 0) : 0;
        }
    }

    static String summary(SharedPreferences preferences, long now) {
        return String.format(Locale.ROOT, "Uso mensual protegido · incidencias %d/%d · "
                        + "tráfico %d/%d · límites %d/%d · direcciones %d/%d",
                used(preferences, INCIDENTS, now), INCIDENT_LIMIT,
                used(preferences, FLOW, now), FLOW_LIMIT,
                used(preferences, SNAP, now), SNAP_LIMIT,
                used(preferences, REVERSE, now), REVERSE_LIMIT);
    }

    private static String month(long now) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        calendar.setTimeInMillis(now);
        return String.format(Locale.ROOT, "%04d-%02d", calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1);
    }
}
