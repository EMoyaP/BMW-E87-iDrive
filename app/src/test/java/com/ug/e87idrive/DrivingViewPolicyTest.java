package com.ug.e87idrive;

import org.junit.Test;
import static org.junit.Assert.*;

public class DrivingViewPolicyTest {
    @Test public void stopRequiresFullMinute() {
        DrivingViewPolicy p = new DrivingViewPolicy();
        assertFalse(p.accept(0, 20, true));
        assertTrue(p.accept(1000, 20, true));
        for (long t = 2000; t < 62000; t += 1000) assertTrue(p.accept(t, 0, true));
        assertFalse(p.accept(62000, 0, true));
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
        for (long t=2000;t<59000;t+=1000) p.accept(t,0,true);
        assertTrue(p.accept(59000, 3, true));
        assertTrue(p.accept(60000, 0, true));
    }
}
