package com.ug.e87idrive;
import org.junit.Test;
import static org.junit.Assert.*;

public class DrivingGaugeScaleTest {
    @Test public void colorRequiresExplicitLimitAndActualExcess() {
        assertFalse(DrivingGaugeScale.exceeds(90d,90,true));
        assertTrue(DrivingGaugeScale.exceeds(91d,90,true));
        assertFalse(DrivingGaugeScale.exceeds(120d,90,false));
        assertFalse(DrivingGaugeScale.exceeds(120d,null,true));
        assertFalse(DrivingGaugeScale.exceeds(null,90,true));
    }
    @Test public void fillFollowsTicksAndNeverReverses() {
        assertEquals(90f,DrivingGaugeScale.angle(0),0);
        assertEquals(157f,DrivingGaugeScale.angle(60),0);
        assertEquals(270f,DrivingGaugeScale.angle(300),0);
        for(int i=1;i<=260;i++) assertTrue(DrivingGaugeScale.angle(i)>DrivingGaugeScale.angle(i-1));
    }
}
