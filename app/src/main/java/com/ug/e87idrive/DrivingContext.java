package com.ug.e87idrive;

import android.location.Location;
import android.os.SystemClock;

/**
 * Single short lived snapshot shared by the driving widgets. Repositories remain responsible for
 * reading their own databases; this class prevents each widget from choosing a different GPS fix
 * while one callback is being painted.
 */
final class DrivingContext {
    private static final long MAX_FIX_AGE_MS = 10_000L;
    private Location location;
    private long receivedAtElapsed;
    private Double speedKmh;
    private SpeedLimitRepository.Match road;
    private RadarRepository.Alert radar;
    private InviveRepository.Alert surveillance;

    synchronized void position(Location value, Double speed) {
        location = value == null ? null : new Location(value);
        receivedAtElapsed = value == null ? 0L : SystemClock.elapsedRealtime();
        speedKmh = speed;
        road = null;
        radar = null;
        surveillance = null;
    }

    synchronized void road(SpeedLimitRepository.Match value) { road = value; }

    synchronized void alerts(RadarRepository.Alert radarValue,
                             InviveRepository.Alert surveillanceValue) {
        radar = radarValue;
        surveillance = surveillanceValue;
    }

    synchronized Snapshot snapshot() {
        if (location != null && (receivedAtElapsed <= 0L
                || SystemClock.elapsedRealtime() - receivedAtElapsed > MAX_FIX_AGE_MS)) {
            return new Snapshot(null, null, null, null, null);
        }
        return new Snapshot(location, speedKmh, road, radar, surveillance);
    }

    static final class Snapshot {
        final Location location;
        final Double speedKmh;
        final SpeedLimitRepository.Match road;
        final RadarRepository.Alert radar;
        final InviveRepository.Alert surveillance;

        Snapshot(Location location, Double speedKmh, SpeedLimitRepository.Match road,
                 RadarRepository.Alert radar, InviveRepository.Alert surveillance) {
            this.location = location == null ? null : new Location(location);
            this.speedKmh = speedKmh;
            this.road = road;
            this.radar = radar;
            this.surveillance = surveillance;
        }
    }
}
