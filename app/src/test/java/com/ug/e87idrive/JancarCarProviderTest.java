package com.ug.e87idrive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class JancarCarProviderTest {
    @Test public void odometerAcceptsPlausibleTotalDistance() {
        assertTrue(JancarCarProvider.validOdometer(90_000f));
        assertTrue(JancarCarProvider.validOdometer(1_999_999f));
    }

    @Test public void odometerRejectsUnknownEmptyAndCorruptValues() {
        assertFalse(JancarCarProvider.validOdometer(-1f));
        assertFalse(JancarCarProvider.validOdometer(0f));
        assertFalse(JancarCarProvider.validOdometer(Float.NaN));
        assertFalse(JancarCarProvider.validOdometer(Float.POSITIVE_INFINITY));
        assertFalse(JancarCarProvider.validOdometer(2_000_001f));
    }
}
