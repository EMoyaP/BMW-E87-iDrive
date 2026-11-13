package com.ug.e87idrive;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WhereAmIProviderTest {
    @Test public void formatsSpanishLocalityProvinceAndRoad() {
        assertEquals("Elche (Alicante) · Avenida de Novelda",
                WhereAmIProvider.format("Elche", "Alicante", "Avenida de Novelda"));
    }

    @Test public void doesNotRepeatProvinceWhenLocalityHasSameName() {
        assertEquals("Alicante · A-70",
                WhereAmIProvider.format("Alicante", "Alicante", "A-70"));
    }

    @Test public void keepsOfflineRoadWhenMunicipalityIsUnavailable() {
        assertEquals("Provincia de Murcia · RM-1",
                WhereAmIProvider.format("", "Murcia", "RM-1"));
    }
}
