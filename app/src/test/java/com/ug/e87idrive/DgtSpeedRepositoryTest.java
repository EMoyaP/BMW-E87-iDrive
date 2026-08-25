package com.ug.e87idrive;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DgtSpeedRepositoryTest {
    @Test public void inferredUtm30CoordinatesStayInSpain() {
        double[] point = DgtSpeedRepository.utm30ToWgs84(4573173.06589931, 586682.47564207);
        assertEquals(41.30536, point[0], 0.01);
        assertEquals(-1.96453, point[1], 0.01);
    }
}
