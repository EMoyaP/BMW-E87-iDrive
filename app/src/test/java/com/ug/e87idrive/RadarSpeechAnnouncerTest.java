package com.ug.e87idrive;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RadarSpeechAnnouncerTest {
    @Test public void fixedCameraUsesOnlyAnExplicitMapLimit() {
        RadarRepository.Alert fixed = new RadarRepository.Alert("dgt-1", "FIJO", "N-332", "", 400d,
                "ALICANTE", true, 1L);
        SpeedLimitRepository.Match exact = new SpeedLimitRepository.Match(120, 4d, 1L,
                "ALICANTE", true, "primary");
        SpeedLimitRepository.Match advisory = new SpeedLimitRepository.Match(50, 4d, 1L,
                "AUTO", false, "unclassified");
        assertEquals("Atención. Radar fijo. Límite 120 kilómetros por hora.",
                RadarSpeechAnnouncer.messageFor(fixed, exact));
        assertEquals("Atención. Radar fijo.", RadarSpeechAnnouncer.messageFor(fixed, advisory));
    }

    @Test public void firstWarningIsAtSixHundredMetersRegardlessOfSpeed() {
        assertEquals(600, RadarRepository.alertDistanceForSpeed(0d));
        assertEquals(600, RadarRepository.alertDistanceForSpeed(50d));
        assertEquals(600, RadarRepository.alertDistanceForSpeed(120d));
    }

    @Test public void fixedCameraUsesGlobalOneHundredMeterPassageMargin() {
        assertEquals(100d, RadarRepository.displayedDistanceFor("FIJO", 100d), 0d);
        assertEquals(300d, RadarRepository.displayedDistanceFor("FIJO", 300d), 0d);
        assertEquals(300d, RadarRepository.displayedDistanceFor("TRAMO", 300d), 0d);
        assertTrue(RadarRepository.fixedCameraPassageWithinTolerance("FIJO", true, 100d, 0d));
        assertTrue(RadarRepository.fixedCameraPassageWithinTolerance("FIJO", true, 109d, 14d));
        assertFalse(RadarRepository.fixedCameraPassageWithinTolerance("FIJO", false, 20d, 14d));
        assertFalse(RadarRepository.fixedCameraPassageWithinTolerance("TRAMO", true, 20d, 14d));
    }

    @Test public void secondVoiceReminderIsOneShotAtTheDisplayedThreeHundredMeters() {
        RadarRepository.Alert approaching = new RadarRepository.Alert("dgt-2", "FIJO", "N-332", "negative",
                300d, 300d, "ALICANTE", false, true, false, 1L);
        RadarRepository.Alert pastPoint = new RadarRepository.Alert("dgt-2", "FIJO", "N-332", "negative",
                0d, 50d, "ALICANTE", false, true, true, 1L);
        assertTrue(RadarSpeechAnnouncer.shouldIssueReminder(approaching, false));
        assertFalse(RadarSpeechAnnouncer.shouldIssueReminder(approaching, true));
        assertFalse(RadarSpeechAnnouncer.shouldIssueReminder(pastPoint, false));
    }

    @Test public void explicitCameraDirectionRejectsOppositeCarriageway() {
        assertTrue(RadarRepository.wrongExplicitDirection(190f, "11"));
        assertTrue(RadarRepository.wrongExplicitDirection(190f, "330"));
        assertFalse(RadarRepository.wrongExplicitDirection(190f, "190"));
        assertFalse(RadarRepository.wrongExplicitDirection(185f, "S"));
        assertFalse(RadarRepository.wrongExplicitDirection(190f, "CRECIENTE"));
    }

    @Test public void cameraDirectionUsesOppositeWindowAroundOneHundredEightyDegrees() {
        assertTrue(RadarRepository.wrongExplicitDirection(125f, "0"));
        assertTrue(RadarRepository.wrongExplicitDirection(180f, "0"));
        assertFalse(RadarRepository.wrongExplicitDirection(124f, "0"));
        assertFalse(RadarRepository.wrongExplicitDirection(90f, "0"));
        assertFalse(RadarRepository.wrongExplicitDirection(50f, "0"));
    }

    @Test public void explicitOsmCameraHeadingOverridesOpposingRoadCorridors() {
        String metadata = "{\"tags\":{\"direction\":\"330\"},"
                + "\"corridors\":[{\"fromLat\":38.2273,\"fromLon\":-0.5537,"
                + "\"toLat\":38.2258,\"toLon\":-0.5537},"
                + "{\"fromLat\":38.2249,\"fromLon\":-0.5537,"
                + "\"toLat\":38.2271,\"toLon\":-0.5537}]}";
        RadarRepository.RadarDirection direction = RadarRepository.radarDirection("", metadata);
        assertFalse(direction.bidirectional);
        assertEquals(1, direction.bearings.length);
        assertEquals(330f, direction.bearings[0], 0f);
    }

    @Test public void roadCorridorsAloneDoNotInventRadarDetectionDirection() {
        String metadata = "{\"corridors\":[{\"fromLat\":38.22,\"fromLon\":-0.55,"
                + "\"toLat\":38.23,\"toLon\":-0.55}]}";
        RadarRepository.RadarDirection direction = RadarRepository.radarDirection("", metadata);
        assertFalse(direction.bidirectional);
        assertEquals(0, direction.bearings.length);
    }

    @Test public void twoWayConeRequiresAnExplicitBothDirectionsValue() {
        RadarRepository.RadarDirection direction = RadarRepository.radarDirection("both", "");
        assertTrue(direction.bidirectional);
        assertEquals(0, direction.bearings.length);
    }

    @Test public void lufopZeroDirectionIsUnknownButOsmZeroIsNorth() {
        RadarRepository.RadarDirection lufop = RadarRepository.radarDirection("LUFOP", "0", "");
        assertFalse(lufop.bidirectional);
        assertEquals(0, lufop.bearings.length);

        RadarRepository.RadarDirection osm = RadarRepository.radarDirection("OSM", "0", "");
        assertFalse(osm.bidirectional);
        assertEquals(1, osm.bearings.length);
        assertEquals(0f, osm.bearings[0], 0.01f);
    }
}
