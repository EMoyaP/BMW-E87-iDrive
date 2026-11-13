package com.ug.e87idrive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.json.JSONObject;

public class DataUpdatePolicyTest {
    @Test public void olderDgtDeltaCannotOverwriteNewerRecord() {
        assertFalse(DgtSpeedRepository.shouldApplyChange(2_000L, 1_000L));
        assertFalse(DgtSpeedRepository.shouldApplyChange(2_000L, 0L));
        assertTrue(DgtSpeedRepository.shouldApplyChange(1_000L, 2_000L));
        assertTrue(DgtSpeedRepository.shouldApplyChange(2_000L, 2_000L));
    }

    @Test public void networkFixCannotDisplaceRecentMoreAccurateGps() {
        assertFalse(GpsSpeedProvider.preferCandidate(2_000L, true, false,
                6f, 45f, 2_000L));
        assertTrue(GpsSpeedProvider.preferCandidate(13_000L, true, false,
                6f, 45f, 13_000L));
        assertTrue(GpsSpeedProvider.preferCandidate(500L, true, true,
                12f, 5f, 500L));
    }

    @Test public void onlyKnownRoadSeedSchemasAreAccepted() {
        assertTrue(SpeedLimitRepository.compatibleSeedHeader(
                "# schema=e87-road-class-seed-v5 source=OpenStreetMap"));
        assertFalse(SpeedLimitRepository.compatibleSeedHeader("<html>Error</html>"));
        assertFalse(SpeedLimitRepository.compatibleSeedHeader(null));
    }

    @Test public void incompleteOverpassPayloadIsRejectedBeforeReplacingProvince() throws Exception {
        try {
            SpeedLimitRepository.validateOverpassResponse(
                    new JSONObject("{\"remark\":\"runtime error: timeout\",\"elements\":[]}"), true);
            throw new AssertionError("Se esperaba rechazo de Overpass incompleto");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("incompleto"));
        }
        try {
            SpeedLimitRepository.validateOverpassResponse(new JSONObject("{\"elements\":[]}"), true);
            throw new AssertionError("Se esperaba rechazo de instantánea vacía");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("demasiado pequeña"));
        }
    }

    @Test public void invalidOverpassCoordinatesAreRejectedBeforeReplacingProvince() throws Exception {
        String invalid = "{\"elements\":[{\"id\":\"way/1\",\"tags\":{\"highway\":\"primary\"},"
                + "\"geometry\":[{\"lat\":38.1,\"lon\":-0.5},{\"lat\":999,\"lon\":-0.4}]}]}";
        try {
            SpeedLimitRepository.validateOverpassResponse(new JSONObject(invalid), false);
            throw new AssertionError("Se esperaba rechazo de coordenadas inválidas");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("Geometría"));
        }
    }
}
