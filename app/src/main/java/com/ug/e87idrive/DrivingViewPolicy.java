package com.ug.e87idrive;

/** GPS-only presentation state. Times are monotonic fix times, never wall-clock time. */
final class DrivingViewPolicy {
    private boolean driving;
    private int movingFixes;
    private long lastFix = -1, stoppedSince = -1;

    boolean accept(long fixMs, double kmh, boolean reliable) {
        if (fixMs <= lastFix) return driving;
        boolean gap = lastFix >= 0 && fixMs - lastFix > 10_000;
        lastFix = fixMs;
        if (!reliable || gap) {
            movingFixes = 0;
            stoppedSince = -1;
        }
        if (!reliable) return driving;
        if (!driving) {
            movingFixes = kmh > 5 ? movingFixes + 1 : 0;
            if (movingFixes >= 2) driving = true;
        }
        if (driving) {
            if (kmh < 2) {
                if (stoppedSince < 0) stoppedSince = fixMs;
                if (fixMs - stoppedSince >= 60_000) {
                    driving = false;
                    movingFixes = 0;
                    stoppedSince = -1;
                }
            } else stoppedSince = -1;
        }
        return driving;
    }
}
