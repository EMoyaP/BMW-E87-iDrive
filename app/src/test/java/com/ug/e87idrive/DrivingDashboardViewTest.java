package com.ug.e87idrive;

import org.junit.Test;
import static org.junit.Assert.*;

public class DrivingDashboardViewTest {
    @Test public void nullableGaugeLimitDoesNotRequireUnboxing() {
        Integer road = null;
        Integer camera = null;
        Integer gauge = road;
        if (camera != null) gauge = camera;
        assertNull(gauge);
    }
}
