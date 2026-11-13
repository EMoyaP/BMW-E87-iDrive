package com.ug.e87idrive;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.location.Location;
import android.os.PersistableBundle;

/**
 * Repeats an interrupted, explicit data refresh after Android kills the Activity or process.
 * Every repository imports transactionally, so repeating a partially downloaded source is safe.
 */
public final class DataUpdateJobService extends JobService {
    private volatile boolean stopped;
    private SpeedLimitRepository speedLimits;
    private FuelStationProvider fuelStations;
    private RadarRepository radars;
    private DgtSpeedRepository dgtSpeeds;
    private InviveRepository invive;

    @Override public boolean onStartJob(JobParameters parameters) {
        if (UpdateProgressStore.isRunning(this)) {
            jobFinished(parameters, true);
            return true;
        }
        PersistableBundle extras = parameters.getExtras();
        String operation = extras.getString(BackgroundUpdateScheduler.EXTRA_OPERATION, "");
        if (!supported(operation)) {
            UpdateProgressStore.finish(this, "Actualización", "Operación pendiente no reconocida");
            BackgroundUpdateScheduler.cancel(this);
            return false;
        }
        Location location = location(extras);
        stopped = false;
        UpdateProgressStore.begin(this, operation);
        AppSessionLog.event("ACTUALIZACIÓN", "Recuperación persistente iniciada · " + operation);

        fuelStations = new FuelStationProvider(this, snapshot -> { });
        speedLimits = new SpeedLimitRepository(this);
        dgtSpeeds = new DgtSpeedRepository(this);
        radars = new RadarRepository(this, () -> speedLimits.automaticProvince(location));
        invive = new InviveRepository(this);
        DataUpdateCoordinator coordinator = new DataUpdateCoordinator(speedLimits, fuelStations,
                radars, dgtSpeeds, invive);
        DataUpdateCoordinator.Listener listener = listener(parameters, operation);
        dispatch(coordinator, operation, location, listener);
        return true;
    }

    private void dispatch(DataUpdateCoordinator coordinator, String operation, Location location,
                          DataUpdateCoordinator.Listener listener) {
        if (operation.startsWith("Todas las fuentes")) coordinator.all(location, listener);
        else if (operation.startsWith("Gasolineras")) coordinator.fuel(listener);
        else if (operation.startsWith("Radares DGT · ")) {
            coordinator.radars(afterSeparator(operation), listener);
        } else if (operation.startsWith("Radares DGT")) coordinator.radars(listener);
        else if (operation.startsWith("Límites DGT")) coordinator.dgt(listener);
        else if (operation.startsWith("Zonas INVIVE")) coordinator.surveillance(listener);
        else if (operation.startsWith("Límites OSM e INVIVE · ")) {
            coordinator.speedAndSurveillance(location, afterSeparator(operation), listener);
        } else coordinator.osm(location, listener);
    }

    private DataUpdateCoordinator.Listener listener(JobParameters parameters, String operation) {
        return new DataUpdateCoordinator.Listener() {
            @Override public void start(int index, String message) {
                UpdateProgressStore.progress(DataUpdateJobService.this, operation, message);
            }

            @Override public void finish(int index, boolean success, String message) {
                UpdateProgressStore.progress(DataUpdateJobService.this, operation, message);
            }

            @Override public void complete() {
                DataUpdateJobService.this.finish(parameters, operation,
                        "Actualización recuperada y terminada", true);
            }
        };
    }

    private synchronized void finish(JobParameters parameters, String operation, String message,
                                     boolean success) {
        UpdateProgressStore.finish(this, operation, message);
        BackgroundUpdateScheduler.cancel(this);
        closeRepositories();
        AppSessionLog.event("ACTUALIZACIÓN", message + " · " + operation);
        if (parameters != null && !stopped) jobFinished(parameters, !success);
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        stopped = true;
        // Repository network/import callbacks are transactional but are not synchronously
        // cancellable. Do not close SQLite under an in-flight callback; Android will retry this
        // persisted job and a surviving callback will close its resources when it completes.
        return true;
    }

    private synchronized void closeRepositories() {
        if (fuelStations != null) { fuelStations.close(); fuelStations = null; }
        if (speedLimits != null) { speedLimits.close(); speedLimits = null; }
        if (dgtSpeeds != null) { dgtSpeeds.close(); dgtSpeeds = null; }
        if (radars != null) { radars.close(); radars = null; }
        if (invive != null) { invive.close(); invive = null; }
    }

    private static Location location(PersistableBundle extras) {
        if (!extras.getBoolean(BackgroundUpdateScheduler.EXTRA_HAS_LOCATION, false)) return null;
        Location location = new Location("background-update");
        location.setLatitude(extras.getDouble(BackgroundUpdateScheduler.EXTRA_LATITUDE));
        location.setLongitude(extras.getDouble(BackgroundUpdateScheduler.EXTRA_LONGITUDE));
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
        return location;
    }

    private static String afterSeparator(String operation) {
        int separator = operation.lastIndexOf('·');
        return separator < 0 ? "AUTO" : operation.substring(separator + 1).trim();
    }

    static boolean supported(String operation) {
        if (operation == null) return false;
        return operation.startsWith("Todas las fuentes")
                || operation.startsWith("Gasolineras")
                || operation.startsWith("Radares DGT")
                || operation.startsWith("Límites DGT")
                || operation.startsWith("Zonas INVIVE")
                || operation.startsWith("Límites OSM e INVIVE · ")
                || operation.startsWith("OSM");
    }
}
