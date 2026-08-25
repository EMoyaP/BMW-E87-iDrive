package com.ug.e87idrive;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class InviveRepositoryTest {
    @Test
    public void canonicalProvinceNormalizesOfficialAlicanteNames() {
        assertEquals("ALICANTE", InviveRepository.canonicalProvince("Alacant/Alicante"));
        assertEquals("ALICANTE", InviveRepository.canonicalProvince("Alicante"));
        assertEquals("ALICANTE", InviveRepository.canonicalProvince("Alacant"));
    }

    @Test
    public void canonicalProvinceKeepsSupportedNeighbouringProvinces() {
        assertEquals("VALENCIA", InviveRepository.canonicalProvince("València/Valencia"));
        assertEquals("MURCIA", InviveRepository.canonicalProvince("Murcia"));
        assertEquals("ALBACETE", InviveRepository.canonicalProvince("Albacete"));
    }

    @Test
    public void headingDifferenceWrapsAcrossNorth() {
        assertEquals(20d, InviveRepository.angularDifference(350d, 10d), 0.001d);
        assertEquals(180d, InviveRepository.angularDifference(90d, 270d), 0.001d);
    }
}
