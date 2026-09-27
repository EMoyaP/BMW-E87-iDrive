package com.ug.e87idrive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DataUpdatePolicyTest {
    @Test public void olderDgtDeltaCannotOverwriteNewerRecord() {
        assertFalse(DgtSpeedRepository.shouldApplyChange(2_000L, 1_000L));
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
}
