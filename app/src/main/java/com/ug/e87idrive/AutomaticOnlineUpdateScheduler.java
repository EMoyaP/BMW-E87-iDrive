package com.ug.e87idrive;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

/** Network-constrained automatic fuel and Git data checks. */
final class AutomaticOnlineUpdateScheduler {
    static final int NOW_JOB_ID = 0xE8721;
    static final int PERIODIC_JOB_ID = 0xE8722;
    private AutomaticOnlineUpdateScheduler() { }

    static void ensure(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        ComponentName service = new ComponentName(context, AutomaticOnlineUpdateJobService.class);
        try {
            if (!hasJob(scheduler, NOW_JOB_ID)) {
                scheduler.schedule(new JobInfo.Builder(NOW_JOB_ID, service)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                        .setPersisted(true).build());
            }
            if (!hasJob(scheduler, PERIODIC_JOB_ID)) {
                scheduler.schedule(new JobInfo.Builder(PERIODIC_JOB_ID, service)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(6L * 60L * 60L * 1000L)
                        .setPersisted(true).build());
            }
        } catch (Exception error) {
            AppSessionLog.event("AUTO ACTUALIZACIÓN", "JobScheduler no disponible · "
                    + error.getClass().getSimpleName());
        }
    }

    private static boolean hasJob(JobScheduler scheduler, int id) {
        for (JobInfo job : scheduler.getAllPendingJobs()) if (job.getId() == id) return true;
        return false;
    }
}
