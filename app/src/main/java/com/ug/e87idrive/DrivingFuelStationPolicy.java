package com.ug.e87idrive;

import android.location.Location;
import java.util.List;

/** Chooses the nearest station inside the fixed driving radius. */
final class DrivingFuelStationPolicy {
    static final double DRIVING_RADIUS_KM = 7d;

    private DrivingFuelStationPolicy() { }

    static FuelStationProvider.Station select(Location location,
                                               List<FuelStationProvider.Station> stations) {
        if (location == null) return null;
        return select(stations);
    }

    static FuelStationProvider.Station select(double latitude, double longitude, Float bearing,
                                               List<FuelStationProvider.Station> stations) {
        return select(stations);
    }

    static FuelStationProvider.Station select(List<FuelStationProvider.Station> stations) {
        if (stations == null || stations.isEmpty()) return null;
        FuelStationProvider.Station selected = null;
        for (FuelStationProvider.Station station : stations) {
            if (station == null || !Double.isFinite(station.distanceKm)
                    || station.distanceKm > DRIVING_RADIUS_KM) continue;
            if (selected == null || station.distanceKm < selected.distanceKm
                    || (station.distanceKm == selected.distanceKm
                    && station.price < selected.price)) selected = station;
        }
        return selected;
    }

    static float angularDifference(float first, float second) {
        float difference = Math.abs(normalize(first) - normalize(second));
        return difference > 180f ? 360f - difference : difference;
    }

    private static float normalize(float value) {
        float normalized = value % 360f;
        return normalized < 0f ? normalized + 360f : normalized;
    }
}
