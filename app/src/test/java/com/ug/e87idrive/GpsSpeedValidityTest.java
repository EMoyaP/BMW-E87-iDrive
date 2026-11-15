package com.ug.e87idrive;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GpsSpeedValidityTest {
    @Test public void rejectsImpossibleSpeedsAndAcceptsStationaryAndRoadSpeeds() {
        assertTrue(GpsSpeedProvider.isPlausibleSpeedKmh(0d));
        assertTrue(GpsSpeedProvider.isPlausibleSpeedKmh(120d));
        assertTrue(GpsSpeedProvider.isPlausibleSpeedKmh(250d));
        assertFalse(GpsSpeedProvider.isPlausibleSpeedKmh(300d));
        assertFalse(GpsSpeedProvider.isPlausibleSpeedKmh(Double.NaN));
        assertFalse(GpsSpeedProvider.isPlausibleSpeedKmh(Double.POSITIVE_INFINITY));
    }
}
