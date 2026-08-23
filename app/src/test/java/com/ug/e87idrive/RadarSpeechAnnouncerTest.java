package com.ug.e87idrive;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
