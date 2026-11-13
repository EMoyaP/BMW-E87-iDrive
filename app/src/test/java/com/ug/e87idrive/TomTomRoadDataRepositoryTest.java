package com.ug.e87idrive;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class TomTomRoadDataRepositoryTest {
    @Test public void parsesTrafficFlowGeometryAndSpeeds() throws Exception {
        String json="{\"flowSegmentData\":{\"currentSpeed\":35,\"freeFlowSpeed\":70,"
                +"\"confidence\":0.86,\"roadClosure\":false,\"coordinates\":{\"coordinate\":["
                +"{\"latitude\":38.2,\"longitude\":-0.6},{\"latitude\":38.21,\"longitude\":-0.61}]}}}";
        OfflineRoadMapView.TrafficFlow value=TomTomRoadDataRepository.parseFlow(json,1234L);
        assertNotNull(value);
        assertEquals(35,value.currentSpeedKmh);
        assertEquals(70,value.freeFlowSpeedKmh);
        assertFalse(value.closed);
        assertEquals(4,value.points.length);
        assertEquals(1234L,value.fetchedAt);
    }

    @Test public void rejectsLowConfidenceTrafficFlow() throws Exception {
        String json="{\"flowSegmentData\":{\"currentSpeed\":35,\"freeFlowSpeed\":70,"
                +"\"confidence\":0.1,\"roadClosure\":false,\"coordinates\":{\"coordinate\":["
                +"{\"latitude\":38.2,\"longitude\":-0.6},{\"latitude\":38.21,\"longitude\":-0.61}]}}}";
        assertNull(TomTomRoadDataRepository.parseFlow(json,1234L));
    }
}
