package com.ug.e87idrive;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class OsmRadarDataTest {
    @Test public void rejectsConditionalAndAmbiguousLimits() {
        assertEquals(90,OsmRadarData.parseLimit("90 km/h"));
        assertEquals(0,OsmRadarData.parseLimit("50;90"));
        assertEquals(0,OsmRadarData.parseLimit("signals"));
        assertEquals(0,OsmRadarData.parseLimit("50 mph"));
    }
    @Test public void noGuessFromOpticalDirection() throws Exception {
        var rows=OsmRadarData.parse(new JSONObject("{\"elements\":[{\"type\":\"node\",\"id\":1,\"lat\":38,\"lon\":-0.5,\"tags\":{\"highway\":\"speed_camera\",\"maxspeed\":\"90\",\"direction\":\"180\"}}]}"));
        assertEquals(1,rows.size());
        assertEquals(0,new JSONObject(rows.get(0).metadata).getJSONArray("corridors").length());
    }
    @Test(expected=java.io.IOException.class) public void partialOverpassNeverReplacesCache() throws Exception {
        OsmRadarData.parse(new JSONObject("{\"remark\":\"timeout\",\"elements\":[]}"));
    }
    @Test public void reverseAndParallelApproachesRejected() {
        assertTrue(OsmRadarMatch.matches(0,100,0,500,0,5));
        assertFalse(OsmRadarMatch.matches(0,100,0,500,180,5));
        assertFalse(OsmRadarMatch.matches(30,100,0,500,0,5));
        assertFalse(OsmRadarMatch.matches(0,-100,0,500,0,5));
        assertFalse(OsmRadarMatch.matches(0,100,0,500,0,50));
    }
}
