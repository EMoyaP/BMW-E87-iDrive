package com.ug.e87idrive;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class OemPackageInspectorTest {
    @Test public void api30OverridesMisleadingReleaseLabel() {
        String result = OemPackageInspector.platformAssessment(30, "13");
        assertTrue(result.contains("API 30 corresponde a Android 11"));
        assertTrue(result.contains("tratar compatibilidad como API 30"));
    }

    @Test public void matchingReleaseIsReportedWithoutWarning() {
        String result = OemPackageInspector.platformAssessment(35, "15");
        assertTrue(result.contains("Android 15"));
        assertTrue(result.contains("API efectiva 35"));
    }
}
