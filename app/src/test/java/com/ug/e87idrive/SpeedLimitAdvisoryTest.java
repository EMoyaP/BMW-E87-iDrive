package com.ug.e87idrive;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SpeedLimitAdvisoryTest {
    @Test public void advisorySpeedsFollowGenericDgtRoadContext() {
        assertEquals(120, SpeedLimitRepository.advisoryForRoadClass("motorway"));
        assertEquals(90, SpeedLimitRepository.advisoryForRoadClass("trunk"));
        assertEquals(90, SpeedLimitRepository.advisoryForRoadClass("primary"));
        assertEquals(90, SpeedLimitRepository.advisoryForRoadClass("secondary"));
        assertEquals(90, SpeedLimitRepository.advisoryForRoadClass("tertiary"));
        assertEquals(50, SpeedLimitRepository.advisoryForRoadClass("unclassified"));
        assertEquals(30, SpeedLimitRepository.advisoryForRoadClass("residential"));
        assertEquals(20, SpeedLimitRepository.advisoryForRoadClass("living_street"));
        assertEquals(20, SpeedLimitRepository.advisoryForRoadClass("service"));
        assertEquals(-1, SpeedLimitRepository.advisoryForRoadClass("footway"));
    }
}
