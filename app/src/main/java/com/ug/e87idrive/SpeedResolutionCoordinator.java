package com.ug.e87idrive;

import android.location.Location;

/** Resolves one road limit using the same source priority for every dashboard widget. */
final class SpeedResolutionCoordinator {
    private final SpeedLimitRepository speedLimits;
    private final DgtSpeedRepository dgtSpeeds;
    private final TomTomRoadDataRepository tomTomRoadData;
    private String observedRoadId;
    private int observedRoadFixes;
    private long lastObservedFixToken = Long.MIN_VALUE;

    SpeedResolutionCoordinator(SpeedLimitRepository speedLimits, DgtSpeedRepository dgtSpeeds) {
        this(speedLimits, dgtSpeeds, null);
    }

    SpeedResolutionCoordinator(SpeedLimitRepository speedLimits, DgtSpeedRepository dgtSpeeds,
                               TomTomRoadDataRepository tomTomRoadData) {
        this.speedLimits = speedLimits;
        this.dgtSpeeds = dgtSpeeds;
        this.tomTomRoadData = tomTomRoadData;
    }

    Result resolve(Location location) {
        if (speedLimits == null) return new Result(null, false);
        SpeedLimitRepository.Match osm = speedLimits.lookup(location);
        long fixToken = fixToken(location);
        boolean newFix = fixToken != lastObservedFixToken;
        if (newFix) lastObservedFixToken = fixToken;
        if (osm == null || osm.osmId == null || osm.osmId.isEmpty()) {
            observedRoadId = null;
            observedRoadFixes = 0;
        } else if (newFix && osm.osmId.equals(observedRoadId)) {
            observedRoadFixes = Math.min(3, observedRoadFixes + 1);
        } else if (newFix) {
            observedRoadId = osm.osmId;
            observedRoadFixes = 1;
        }
        // An explicit OSM maxspeed has already passed the repository's two-fix confirmation.
        // DGT can therefore refine it immediately; a DGT point on an advisory-only road waits
        // for the same road to be seen twice before becoming a legal displayed limit.
        boolean roadConfirmed = osm != null && (osm.exact || observedRoadFixes >= 2);
        if (dgtSpeeds != null) {
            DgtSpeedRepository.Match dgt = roadConfirmed ? dgtSpeeds.lookup(location, osm) : null;
            if (dgt != null) return new Result(dgt.asSpeedMatch(osm), true, false);
        }
        SpeedLimitRepository.Match tomTom = roadConfirmed && tomTomRoadData != null
                ? tomTomRoadData.speedMatch(location, osm) : null;
        if (tomTom != null) {
            if (osm != null && osm.exact && osm.limitKmh != tomTom.limitKmh) {
                AppSessionLog.sampledEvent("tomtom-limit-conflict-" + osm.osmId,
                        "CONFLICTO DE LÍMITE", "OSM " + osm.limitKmh + " km/h · TomTom "
                                + tomTom.limitKmh + " km/h · " + osm.roadRef,
                        30L * 60L * 1_000L);
            }
            return new Result(tomTom, false, true);
        }
        return new Result(osm, false, false);
    }

    private static long fixToken(Location location) {
        if (location == null) return Long.MIN_VALUE;
        long elapsed = location.getElapsedRealtimeNanos();
        return elapsed > 0L ? elapsed : location.getTime();
    }

    static final class Result {
        final SpeedLimitRepository.Match match;
        final boolean dgt;
        final boolean tomTom;

        Result(SpeedLimitRepository.Match match, boolean dgt) {
            this(match, dgt, false);
        }

        Result(SpeedLimitRepository.Match match, boolean dgt, boolean tomTom) {
            this.match = match;
            this.dgt = dgt;
            this.tomTom = tomTom;
        }
    }
}
