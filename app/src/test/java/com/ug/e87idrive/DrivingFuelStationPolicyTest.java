package com.ug.e87idrive;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class DrivingFuelStationPolicyTest {
    private static FuelStationProvider.Station station(String id, double lat, double lon,
                                                        double price, double distance) {
        return new FuelStationProvider.Station(id, "03", id, "", lat, lon, price, distance);
    }

    @Test public void displaysCheapestAmongUsefulStations() {
        FuelStationProvider.Station nearest = station("CERCA", 38.01d, -.5d, 1.72d, 1.1d);
        FuelStationProvider.Station cheaper = station("BARATA", 38.02d, -.5d, 1.55d, 2.2d);
        assertSame(cheaper, DrivingFuelStationPolicy.select(38d, -.5d, 0f,
                Arrays.asList(cheaper, nearest)));
    }

    @Test public void hidesNearestWhenItIsBehindAndNotClose() {
        FuelStationProvider.Station behind = station("DETRAS", 37.99d, -.5d, 1.70d, 1.1d);
        assertNull(DrivingFuelStationPolicy.select(38d, -.5d, 0f, Arrays.asList(behind)));
    }

    @Test public void closeStationIsVisibleRegardlessOfHeading() {
        FuelStationProvider.Station close = station("CERCA", 37.999d, -.5d, 1.70d, .2d);
        assertSame(close, DrivingFuelStationPolicy.select(38d, -.5d, 0f, Arrays.asList(close)));
    }
}
