package com.ug.e87idrive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DgtSpeedRepositoryTest {
    @Test public void inferredUtm30CoordinatesStayInSpain() {
        double[] point = DgtSpeedRepository.utm30ToWgs84(4573173.06589931, 586682.47564207);
        assertEquals(41.30536, point[0], 0.01);
        assertEquals(-1.96453, point[1], 0.01);
    }

    @Test public void directionalKilometreMetadataIsNotMappedOntoOsmWayOrder() {
        assertTrue(DgtSpeedRepository.directionApplies("both"));
        assertTrue(DgtSpeedRepository.directionApplies(""));
        assertFalse(DgtSpeedRepository.directionApplies("increasing"));
        assertFalse(DgtSpeedRepository.directionApplies("decreasing"));
    }
}
