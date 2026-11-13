package com.ug.e87idrive;

import android.location.Location;
import java.util.List;

/** Chooses the cheapest station among those useful to a moving driver. */
final class DrivingFuelStationPolicy {
    static final double NEARBY_KM = .60d;
    static final double AHEAD_MAX_KM = 5d;
    static final float AHEAD_TOLERANCE_DEGREES = 55f;

    private DrivingFuelStationPolicy() { }

    static FuelStationProvider.Station select(Location location,
                                               List<FuelStationProvider.Station> stations) {
        if (location == null) return null;
        return select(location.getLatitude(), location.getLongitude(),
                location.hasBearing() ? location.getBearing() : null, stations);
    }

    static FuelStationProvider.Station select(double latitude, double longitude, Float bearing,
                                               List<FuelStationProvider.Station> stations) {
        if (stations == null || stations.isEmpty()) return null;
        FuelStationProvider.Station selected = null;
        for (FuelStationProvider.Station station : stations) {
            if (station == null || !Double.isFinite(station.distanceKm)) continue;
            boolean useful = station.distanceKm <= NEARBY_KM;
            if (!useful && station.distanceKm <= AHEAD_MAX_KM && bearing != null) {
                useful = angularDifference(bearing,
                        initialBearing(latitude, longitude, station.latitude, station.longitude))
                        <= AHEAD_TOLERANCE_DEGREES;
            }
            if (!useful) continue;
            if (selected == null || station.price < selected.price
                    || (station.price == selected.price
                    && station.distanceKm < selected.distanceKm)) selected = station;
        }
        return selected;
    }

    private static float initialBearing(double firstLatitude, double firstLongitude,
                                        double secondLatitude, double secondLongitude) {
        double first = Math.toRadians(firstLatitude);
        double second = Math.toRadians(secondLatitude);
        double longitude = Math.toRadians(secondLongitude - firstLongitude);
        double y = Math.sin(longitude) * Math.cos(second);
        double x = Math.cos(first) * Math.sin(second)
                - Math.sin(first) * Math.cos(second) * Math.cos(longitude);
        return normalize((float) Math.toDegrees(Math.atan2(y, x)));
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
