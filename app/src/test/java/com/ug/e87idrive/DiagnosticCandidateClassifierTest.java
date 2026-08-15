package com.ug.e87idrive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class DiagnosticCandidateClassifierTest {
    @Test public void repeatedSemanticBroadcastBecomesStrong() {
        DiagnosticCandidateClassifier.Score score = DiagnosticCandidateClassifier.score(
                "broadcast", "broadcast.vendor.light.state", "0", "1", 3, 2,
                false, Arrays.asList("light", "illum"));
        assertEquals("FUERTE", score.confidence);
        assertTrue(score.value >= 70);
    }

    @Test public void unrelatedGpsNoiseStaysWeakForDoorTest() {
        DiagnosticCandidateClassifier.Score score = DiagnosticCandidateClassifier.score(
                "vehicle", "vehicle.speed", "0", "1", 1, 1,
                false, Arrays.asList("door", "open", "puerta"));
        assertEquals("DÉBIL", score.confidence);
    }

    @Test public void semanticSettingTransitionIsStrongButStillOnlyCandidate() {
        DiagnosticCandidateClassifier.Score score = DiagnosticCandidateClassifier.score(
                "settings", "settings.system.illumination", "0", "1", 1, 2,
                false, Arrays.asList("light", "illum"));
        assertEquals("FUERTE", score.confidence);
        assertTrue(score.reason.contains("ajuste Android observable"));
    }

    @Test public void StablePublicTemperatureCanBeRanked() {
        DiagnosticCandidateClassifier.Score score = DiagnosticCandidateClassifier.score(
                "vehicle", "vehicle.temp_ext.celsius", "22.0", "22.0", 0, 1,
                true, Arrays.asList("temp", "outside", "exterior"));
        assertEquals("FUERTE", score.confidence);
    }
}
