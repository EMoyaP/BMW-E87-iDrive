package com.ug.e87idrive;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrafficIncidentRepositoryTest {
    @Test public void classifiesCurrentDgtSlowTrafficToken() {
        TrafficIncidentRepository.Classification value =
                TrafficIncidentRepository.classify("AbnormalTraffic slowTraffic");
        assertNotNull(value);
        assertEquals(TrafficIncidentRepository.AMBER, value.severity);
        assertEquals("CIRCULACIÓN LENTA", value.title);
    }

    @Test public void classifiesStuckVehicleAsStopped() {
        TrafficIncidentRepository.Classification value =
                TrafficIncidentRepository.classify("vehicleObstruction vehicleStuck");
        assertNotNull(value);
        assertEquals(TrafficIncidentRepository.RED, value.severity);
        assertEquals("VEHÍCULO PARADO", value.title);
    }

    @Test public void prioritizesVehicleFireOverGenericObstruction() {
        TrafficIncidentRepository.Classification value =
                TrafficIncidentRepository.classify("vehicleObstruction vehicleOnFire");
        assertNotNull(value);
        assertEquals("INCIDENCIA EN LA VÍA", value.title);
    }

    @Test public void ignoresUnrelatedRoadworks() {
        assertNull(TrafficIncidentRepository.classify("roadMaintenance roadworks"));
    }

    @Test public void parsesTomTomJamAndConvertsGeoJsonLongitudeLatitude() throws Exception {
        String json="{\"incidents\":[{\"type\":\"Feature\",\"geometry\":{"+
                "\"type\":\"LineString\",\"coordinates\":[[-0.68,38.27],[-0.67,38.28]]},"+
                "\"properties\":{\"id\":\"jam-1\",\"iconCategory\":6,"+
                "\"roadNumbers\":[\"EL-20\"],\"events\":[{\"description\":\"Retención\"}]}}]}";
        java.util.ArrayList<TrafficIncidentRepository.Incident> values=
                TrafficIncidentRepository.parseTomTom(json,1_000L);
        assertEquals(1,values.size());
        TrafficIncidentRepository.Incident value=values.get(0);
        assertEquals("TOMTOM",value.source);
        assertEquals(TrafficIncidentRepository.AMBER,value.severity);
        assertEquals(38.27d,value.points[0],0.000001d);
        assertEquals(-0.68d,value.points[1],0.000001d);
        assertEquals("EL-20",value.road);
    }

    @Test public void ignoresTomTomWeatherCategoryNotUsedByDrivingUi() throws Exception {
        String json="{\"incidents\":[{\"geometry\":{\"type\":\"Point\","+
                "\"coordinates\":[-0.68,38.27]},\"properties\":{\"iconCategory\":4}}]}";
        assertTrue(TrafficIncidentRepository.parseTomTom(json,1_000L).isEmpty());
    }

    @Test public void keepsTomTomDelayForPrioritisationAndDisplay() throws Exception {
        String json="{\"incidents\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\","
                +"\"coordinates\":[[-0.60,38.20],[-0.61,38.21]]},\"properties\":{"
                +"\"id\":\"jam-delay\",\"iconCategory\":6,\"magnitudeOfDelay\":3,"
                +"\"delay\":420,\"length\":1800,\"roadNumbers\":[\"EL-20\"],"
                +"\"events\":[{\"description\":\"Tráfico lento\"}]}}]}";
        TrafficIncidentRepository.Incident value=
                TrafficIncidentRepository.parseTomTom(json,1_000L).get(0);
        assertEquals(420,value.delaySeconds);
        assertEquals(1800d,value.lengthMeters,0d);
        assertEquals(3,value.magnitude);
        org.junit.Assert.assertTrue(value.detail.contains("7 min"));
    }
}
