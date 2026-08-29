package com.ug.e87idrive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpeedLimitMapMatchingTest {
    @Test public void roadBearingIsUndirected() {
        assertEquals(0d, SpeedLimitRepository.headingDifference(5d, 185d), 0.001d);
        assertEquals(10d, SpeedLimitRepository.headingDifference(350d, 0d), 0.001d);
        assertEquals(90d, SpeedLimitRepository.headingDifference(0d, 90d), 0.001d);
    }

    @Test public void sourceQualityDoesNotChangeRoadSelection() {
        double explicit40 = SpeedLimitRepository.mapMatchScore(1d, true, 2d, false);
        double advisory90 = SpeedLimitRepository.mapMatchScore(0.9d, false, 2d, false);
        assertTrue(advisory90 < explicit40);
    }

    @Test public void perpendicularExplicitRoadDoesNotOverrideAlignedRoad() {
        double perpendicularExact = SpeedLimitRepository.mapMatchScore(1d, true, 90d, false);
        double alignedAdvisory = SpeedLimitRepository.mapMatchScore(5d, false, 2d, false);
        assertTrue(alignedAdvisory < perpendicularExact);
    }

    @Test public void parkedVehicleKeepsPreviouslyConfirmedRoadAcrossGpsDrift() {
        double previousRoad = SpeedLimitRepository.mapMatchScore(7.1d, false, Double.NaN, true);
        double nearbyServiceLane = SpeedLimitRepository.mapMatchScore(4.5d, false, Double.NaN, false);
        assertTrue(previousRoad < nearbyServiceLane);
    }

    @Test public void lookupCadenceIncreasesWithVehicleSpeed() {
        assertEquals(5_000L, SpeedLimitRepository.lookupIntervalForSpeedKmh(0d));
        assertEquals(1_000L, SpeedLimitRepository.lookupIntervalForSpeedKmh(30d));
        assertEquals(750L, SpeedLimitRepository.lookupIntervalForSpeedKmh(50d));
        assertEquals(500L, SpeedLimitRepository.lookupIntervalForSpeedKmh(90d));
        assertEquals(350L, SpeedLimitRepository.lookupIntervalForSpeedKmh(120d));
    }

    @Test public void departureForcesImmediateLookupWithoutPredictingTheTurn() {
        assertTrue(!SpeedLimitRepository.shouldReevaluateAfterDeparture(true, 2.9d));
        assertTrue(SpeedLimitRepository.shouldReevaluateAfterDeparture(true, 3d));
        assertTrue(!SpeedLimitRepository.shouldReevaluateAfterDeparture(false, 20d));
    }

    @Test public void verifiedSignsAreAppliedOnlyAfterWayAndDirectionMatch() {
        SpeedLimitRepository.Match northbound = new SpeedLimitRepository.Match(30, 2d, 1L,
                "ALICANTE", false, "residential", "33908151", 2d, 180d, 1_500d);
        SpeedLimitRepository.Match northResult = SpeedLimitRepository
                .applyVerifiedAlicanteZones(northbound, 0f);
        assertEquals(50, northResult.limitKmh);
        assertTrue(northResult.exact);

        SpeedLimitRepository.Match southbound = new SpeedLimitRepository.Match(30, 2d, 1L,
                "ALICANTE", false, "residential", "33908151", 2d, 180d, 1_500d);
        assertEquals(40, SpeedLimitRepository.applyVerifiedAlicanteZones(southbound, 180f).limitKmh);

        SpeedLimitRepository.Match parallelRoad = new SpeedLimitRepository.Match(20, 1d, 1L,
                "ALICANTE", false, "service", "other", 2d, 180d, 1_500d);
        assertEquals(20, SpeedLimitRepository.applyVerifiedAlicanteZones(parallelRoad, 0f).limitKmh);
    }

    @Test public void verifiedPhysicalTransitionDoesNotFallThroughToGenericAdvisory() {
        SpeedLimitRepository.Match nearSixtyPanel = new SpeedLimitRepository.Match(50, 2d, 1L,
                "ALICANTE", false, "unclassified", "34145696", 2d, 70d, 12.7d);
        assertEquals(60, SpeedLimitRepository.applyVerifiedAlicanteZones(nearSixtyPanel, 70f).limitKmh);

        SpeedLimitRepository.Match nearFortyPanel = new SpeedLimitRepository.Match(50, 2d, 1L,
                "ALICANTE", false, "unclassified", "34145696", 2d, 82d, 111.6d);
        assertEquals(40, SpeedLimitRepository.applyVerifiedAlicanteZones(nearFortyPanel, 82f).limitKmh);
    }

    @Test public void localUnclassifiedWithoutRouteReferenceUsesBlueUrbanAdvice() {
        SpeedLimitRepository.Match local = new SpeedLimitRepository.Match(50, 2d, 1L,
                "ALICANTE", false, "unclassified", "34146385", 2d, 90d, 10d, "");
        SpeedLimitRepository.Match contextual = SpeedLimitRepository.applyContextualAdvisory(local);
        assertEquals(30, contextual.limitKmh);
        assertTrue(!contextual.exact);

        SpeedLimitRepository.Match numberedRoad = new SpeedLimitRepository.Match(50, 2d, 1L,
                "ALICANTE", false, "unclassified", "road", 2d, 90d, 10d, "CV-1");
        assertEquals(50, SpeedLimitRepository.applyContextualAdvisory(numberedRoad).limitKmh);
    }

    @Test public void aMovingTrajectoryNeedsEvidenceBeforeSwitchingParallelRoads() {
        assertTrue(SpeedLimitRepository.mapMatchScore(8d, false, 4d, true)
                < SpeedLimitRepository.mapMatchScore(7d, false, 4d, false));
    }

    @Test public void derivedGpsSpeedIsAcceptedOnlyWhenPlausible() {
        assertTrue(SpeedLimitRepository.isUsableSpeed(0d));
        assertTrue(SpeedLimitRepository.isUsableSpeed(120d));
        assertTrue(!SpeedLimitRepository.isUsableSpeed(-1d));
        assertTrue(!SpeedLimitRepository.isUsableSpeed(301d));
        assertTrue(!SpeedLimitRepository.isUsableSpeed(Double.NaN));
    }
}
