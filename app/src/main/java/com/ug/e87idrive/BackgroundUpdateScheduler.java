package com.ug.e87idrive;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.location.Location;
import android.os.PersistableBundle;

/** Durable safety net for an update initially started from the visible dashboard. */
final class BackgroundUpdateScheduler {
    static final int JOB_ID = 0xE8701;
    static final String EXTRA_OPERATION = "operation";
    static final String EXTRA_HAS_LOCATION = "has_location";
    static final String EXTRA_LATITUDE = "latitude";
    static final String EXTRA_LONGITUDE = "longitude";

    private BackgroundUpdateScheduler() { }

    static void schedule(Context context, String operation, Location location) {
        try {
            PersistableBundle extras = new PersistableBundle();
            extras.putString(EXTRA_OPERATION, operation == null ? "" : operation);
            if (location != null && Double.isFinite(location.getLatitude())
                    && Double.isFinite(location.getLongitude())) {
                extras.putBoolean(EXTRA_HAS_LOCATION, true);
                extras.putDouble(EXTRA_LATITUDE, location.getLatitude());
                extras.putDouble(EXTRA_LONGITUDE, location.getLongitude());
            }
            JobInfo job = new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, DataUpdateJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(120_000L)
                    .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPersisted(true)
                    .setExtras(extras)
                    .build();
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            if (scheduler == null || scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                AppSessionLog.event("ACTUALIZACIÓN",
                        "Android no aceptó la recuperación persistente; continúa el trabajo actual");
            }
        } catch (Exception error) {
            AppSessionLog.event("ACTUALIZACIÓN", "Recuperación persistente no disponible · "
                    + error.getClass().getSimpleName());
        }
    }

    static void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(JOB_ID);
    }
}
