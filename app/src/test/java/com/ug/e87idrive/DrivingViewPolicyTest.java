package com.ug.e87idrive;

import org.junit.Test;
import static org.junit.Assert.*;

public class DrivingViewPolicyTest {
    @Test public void stopRequiresThirtyContinuousSeconds() {
        DrivingViewPolicy p = new DrivingViewPolicy();
        assertFalse(p.accept(0, 20, true));
        assertTrue(p.accept(1000, 20, true));
        for (long t = 2000; t < 32000; t += 1000) assertTrue(p.accept(t, 0, true));
        assertFalse(p.accept(32000, 0, true));
    }
    @Test public void lossOfGpsDoesNotCountAsStopped() {
        DrivingViewPolicy p = new DrivingViewPolicy();
        p.accept(0, 20, true); p.accept(1000, 20, true);
        p.accept(2000, 0, true);
        assertTrue(p.accept(90000, 0, true));
        assertTrue(p.accept(91000, 0, false));
    }
    @Test public void repeatedFixDoesNotActivateAndMovementResetsStop() {
        DrivingViewPolicy p = new DrivingViewPolicy();
        assertFalse(p.accept(0, 20, true));
        assertFalse(p.accept(0, 20, true));
        assertTrue(p.accept(1000, 20, true));
        for (long t=2000;t<30000;t+=1000) p.accept(t,0,true);
        assertTrue(p.accept(30000, 3, true));
        for (long t=31000;t<=60000;t+=1000) assertTrue(p.accept(t,0,true));
        assertFalse(p.accept(61000, 0, true));
    }
}
