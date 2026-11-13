package com.ug.e87idrive;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DataUpdateJobServiceTest {
    @Test public void acceptsEveryOperationPersistedByMainActivity() {
        assertTrue(DataUpdateJobService.supported("Todas las fuentes"));
        assertTrue(DataUpdateJobService.supported("Gasolineras"));
        assertTrue(DataUpdateJobService.supported("Radares DGT · ALICANTE"));
        assertTrue(DataUpdateJobService.supported("Radares DGT nacionales"));
        assertTrue(DataUpdateJobService.supported("Límites DGT nacionales"));
        assertTrue(DataUpdateJobService.supported("Zonas INVIVE nacionales"));
        assertTrue(DataUpdateJobService.supported("Límites OSM e INVIVE · MURCIA"));
        assertTrue(DataUpdateJobService.supported("OSM · Alicante"));
    }

    @Test public void rejectsEmptyOrUnknownOperations() {
        assertFalse(DataUpdateJobService.supported(null));
        assertFalse(DataUpdateJobService.supported(""));
        assertFalse(DataUpdateJobService.supported("Borrar datos"));
    }
}
