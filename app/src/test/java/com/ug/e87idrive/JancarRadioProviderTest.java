package com.ug.e87idrive;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JancarRadioProviderTest {
    @Test public void formatsRk3326FmHundredths() {
        assertEquals("87.50 MHz", JancarRadioProvider.frequency(1, 8750));
    }

    @Test public void alsoAcceptsFmThousandths() {
        assertEquals("87.50 MHz", JancarRadioProvider.frequency(1, 87500));
    }

    @Test public void formatsAmAsKhz() {
        assertEquals("522 kHz", JancarRadioProvider.frequency(0, 522));
    }
}
