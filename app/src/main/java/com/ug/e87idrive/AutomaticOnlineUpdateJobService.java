package com.ug.e87idrive;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.location.Location;

/** Updates fuel, verified Git cartography and the official DGT feeds without an Activity. */
public final class AutomaticOnlineUpdateJobService extends JobService {
    private volatile boolean stopped;
    private FuelStationProvider fuel;
    private SpeedLimitRepository speedLimits;
    private DgtSpeedRepository dgtSpeeds;
    private RadarRepository radars;
    private InviveRepository invive;

    @Override public boolean onStartJob(JobParameters parameters) {
        if (UpdateProgressStore.isRunning(this)) {
            jobFinished(parameters, true);
            return true;
        }
        stopped = false;
        Location location = LastKnownLocationStore.read(this);
        AutomaticUpdateStateStore.pending(this, AutomaticUpdateStateStore.FUEL,
                location == null ? "Esperando ubicación" : "Actualizando precios…");
        AutomaticUpdateStateStore.pending(this, AutomaticUpdateStateStore.GIT,
                "Comprobando cartografía…");
        speedLimits = new SpeedLimitRepository(this);
        fuel = new FuelStationProvider(this, snapshot -> { });
        if (location == null) {
            AutomaticUpdateStateStore.pending(this, AutomaticUpdateStateStore.FUEL,
                    "Pendiente de una posición GPS");
            updateGit(parameters, true);
            return true;
        }
        fuel.start(location);
        AutomaticUpdateStateStore.progress(this, AutomaticUpdateStateStore.FUEL, 20,
                "Descargando precios oficiales…");
        FuelStationProvider.Snapshot before = fuel.getSnapshot();
        long previous = before == null ? 0L : before.updatedAt;
        fuel.forceRefresh(() -> {
            FuelStationProvider.Snapshot after = fuel.getSnapshot();
            boolean success = after != null && after.updatedAt > previous && !after.loading
                    && !lower(after.message).contains("sin internet");
            if (success) AutomaticUpdateStateStore.complete(this,
                    AutomaticUpdateStateStore.FUEL, "Precios instalados");
            else AutomaticUpdateStateStore.pending(this, AutomaticUpdateStateStore.FUEL,
                    "Sin completar; se reintentará con Internet");
            updateGit(parameters, !success);
        });
        return true;
    }

    private void updateGit(JobParameters parameters, boolean retryAlreadyNeeded) {
        new Thread(() -> {
            GitDataBundleUpdater.Result result = new GitDataBundleUpdater(this)
                    .checkAndInstall(speedLimits, (percent, message) ->
                            AutomaticUpdateStateStore.progress(this,
                                    AutomaticUpdateStateStore.GIT, percent, message));
            updateOfficialFeeds(parameters, retryAlreadyNeeded || !result.success,
                    result.success, result.message);
        }, "e87-git-data-update").start();
    }

    private void updateOfficialFeeds(JobParameters parameters, boolean retry,
            boolean gitSuccess, String gitMessage) {
        if (stopped) { finish(parameters, true); return; }
        dgtSpeeds = new DgtSpeedRepository(this);
        radars = new RadarRepository(this, () -> "TODAS");
        invive = new InviveRepository(this);
        AutomaticUpdateStateStore.progress(this, AutomaticUpdateStateStore.GIT, 86,
                "Actualizando límites DGT…");
        dgtSpeeds.refreshFromInternet(dgt -> {
            if (stopped) { finish(parameters, true); return; }
            AutomaticUpdateStateStore.progress(this, AutomaticUpdateStateStore.GIT, 91,
                    "Actualizando radares DGT…");
            radars.refreshNationalFromInternet(radar -> {
                if (stopped) { finish(parameters, true); return; }
                AutomaticUpdateStateStore.progress(this, AutomaticUpdateStateStore.GIT, 96,
                        "Actualizando zonas INVIVE…");
                invive.refreshNationalFromInternet(zones -> {
                    boolean success = gitSuccess && dgt.success && radar.success && zones.success;
                    String detail = success ? "Cartografía y datos oficiales instalados"
                            : firstFailure(gitSuccess, gitMessage, dgt, radar, zones);
                    checkApplicationUpdate(parameters,retry || !success,success,detail);
                });
            });
        });
    }

    private void checkApplicationUpdate(JobParameters parameters,boolean retry,
            boolean dataSuccess,String dataMessage){
        new Thread(()->{
            AppReleaseUpdateManager.Result app=new AppReleaseUpdateManager(this)
                    .checkAndDownload((percent,message)->AutomaticUpdateStateStore.progress(
                            this,AutomaticUpdateStateStore.GIT,percent,message));
            if(app.ready) AutomaticUpdateStateStore.ready(this,AutomaticUpdateStateStore.GIT,app.message);
            else if(dataSuccess&&app.success) AutomaticUpdateStateStore.complete(this,
                    AutomaticUpdateStateStore.GIT,dataMessage+" · "+app.message);
            else AutomaticUpdateStateStore.pending(this,AutomaticUpdateStateStore.GIT,
                    !dataSuccess?dataMessage:app.message);
            finish(parameters,retry||!app.success);
        },"e87-app-release-check").start();
    }

    private static String firstFailure(boolean gitSuccess, String gitMessage,
            DgtSpeedRepository.UpdateResult dgt, RadarRepository.UpdateResult radar,
            InviveRepository.UpdateResult zones) {
        if (!gitSuccess) return gitMessage;
        if (!dgt.success) return dgt.message;
        if (!radar.success) return radar.message;
        return zones.message;
    }

    private synchronized void finish(JobParameters parameters, boolean retry) {
        if (fuel != null) { fuel.close(); fuel = null; }
        if (speedLimits != null) { speedLimits.close(); speedLimits = null; }
        if (dgtSpeeds != null) { dgtSpeeds.close(); dgtSpeeds = null; }
        if (radars != null) { radars.close(); radars = null; }
        if (invive != null) { invive.close(); invive = null; }
        if (!stopped) jobFinished(parameters, retry);
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        stopped = true;
        if (fuel != null) fuel.stop();
        if (dgtSpeeds != null) dgtSpeeds.stop();
        if (radars != null) radars.stop();
        if (invive != null) invive.stop();
        return true;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
    }
}
