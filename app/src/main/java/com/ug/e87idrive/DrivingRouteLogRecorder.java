package com.ug.e87idrive;

import android.location.Location;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes a compact GPS breadcrumb and speed summary for each moving journey. */
final class DrivingRouteLogRecorder {
    private static final double START_SPEED_KMH = 5d;
    private static final long SAMPLE_INTERVAL_MS = 5_000L;
    private static final long END_AFTER_STATIONARY_MS = 8L * 60L * 1_000L;

    private long routeStartMs;
    private long lastMovingMs;
    private long lastSampleMs;
    private long previousSampleMs;
    private double minSpeed = Double.POSITIVE_INFINITY;
    private double maxSpeed = Double.NEGATIVE_INFINITY;
    private double weightedSpeedSeconds;
    private double measuredSeconds;
    private double distanceMeters;
    private Location previousSample;
    private String routeId = "";

    void onLocation(Location location, Double speedKmh, long timestampMs) {
        if (location == null || timestampMs <= 0L) return;
        double speed = speedKmh == null || !Double.isFinite(speedKmh)
                ? Double.NaN : Math.max(0d, speedKmh);

        if (routeStartMs != 0L && timestampMs - lastMovingMs >= END_AFTER_STATIONARY_MS) {
            finish();
        }
        if (routeStartMs == 0L) {
            if (!Double.isFinite(speed) || speed < START_SPEED_KMH) return;
            start(timestampMs);
        }

        if (routeStartMs == 0L) return;
        if (Double.isFinite(speed) && speed >= START_SPEED_KMH) lastMovingMs = timestampMs;

        if (lastSampleMs > 0L && timestampMs - lastSampleMs < SAMPLE_INTERVAL_MS) return;
        recordSample(location, speed, timestampMs);
    }

    private void start(long timestampMs) {
        routeStartMs = timestampMs;
        lastMovingMs = timestampMs;
        lastSampleMs = 0L;
        previousSampleMs = 0L;
        minSpeed = Double.POSITIVE_INFINITY;
        maxSpeed = Double.NEGATIVE_INFINITY;
        weightedSpeedSeconds = 0d;
        measuredSeconds = 0d;
        distanceMeters = 0d;
        previousSample = null;
        routeId = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(timestampMs));
        AppSessionLog.event("RUTA GPS", "INICIO · id=" + routeId
                + " · umbral movimiento=" + Math.round(START_SPEED_KMH) + " km/h");
    }

    private void recordSample(Location location, double speedKmh, long timestampMs) {
        if (Double.isFinite(speedKmh)) {
            minSpeed = Math.min(minSpeed, speedKmh);
            maxSpeed = Math.max(maxSpeed, speedKmh);
            if (previousSampleMs > 0L) {
                double elapsed = (timestampMs - previousSampleMs) / 1_000d;
                if (elapsed > 0d && elapsed <= 30d && Double.isFinite(previousSpeed)) {
                    weightedSpeedSeconds += (previousSpeed + speedKmh) * .5d * elapsed;
                    measuredSeconds += elapsed;
                }
            }
        }
        if (previousSample != null) {
            long gap = timestampMs - previousSampleMs;
            if (gap > 0L && gap <= 30_000L) distanceMeters += previousSample.distanceTo(location);
        }
        AppSessionLog.event("RUTA GPS", "PUNTO · id=" + routeId
                + String.format(Locale.ROOT, " · lat=%.6f · lon=%.6f", location.getLatitude(), location.getLongitude())
                + " · velocidad=" + (Double.isFinite(speedKmh)
                ? String.format(Locale.ROOT, "%.1f km/h", speedKmh) : "sin dato")
                + " · precisión=" + (location.hasAccuracy()
                ? String.format(Locale.ROOT, "%.0f m", location.getAccuracy()) : "sin dato"));
        previousSample = new Location(location);
        previousSampleMs = timestampMs;
        previousSpeed = speedKmh;
        lastSampleMs = timestampMs;
    }

    private double previousSpeed = Double.NaN;

    private void finish() {
        if (routeStartMs == 0L) return;
        long durationMs = Math.max(0L, lastMovingMs - routeStartMs);
        String stats = "FIN · id=" + routeId
                + " · inicio=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(routeStartMs))
                + " · fin=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(lastMovingMs))
                + " · tiempo=" + formatDuration(durationMs)
                + " · distancia=" + String.format(Locale.ROOT, "%.2f km", distanceMeters / 1_000d)
                + " · velocidad mínima=" + formatSpeed(minSpeed)
                + " · velocidad media=" + (measuredSeconds > 0d
                ? String.format(Locale.ROOT, "%.1f km/h", weightedSpeedSeconds / measuredSeconds) : "sin dato")
                + " · velocidad máxima=" + formatSpeed(maxSpeed);
        AppSessionLog.event("RUTA GPS", stats);
        routeStartMs = 0L;
        lastMovingMs = 0L;
        lastSampleMs = 0L;
    }

    private static String formatSpeed(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.1f km/h", value) : "sin dato";
    }

    private static String formatDuration(long ms) {
        long minutes = ms / 60_000L;
        return String.format(Locale.ROOT, "%d h %02d min", minutes / 60L, minutes % 60L);
    }
}
