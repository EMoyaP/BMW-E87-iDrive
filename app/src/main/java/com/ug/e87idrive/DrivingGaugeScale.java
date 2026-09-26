package com.ug.e87idrive;

/** Expanded low-speed section from the approved cluster design. */
final class DrivingGaugeScale {
    private static final int[] SPEEDS = {0,20,40,60,100,140,180,220,260};
    private static final float[] ANGLES = {90,116,140,157,177,200,225,249,270};
    static float angle(double speed) {
        if (!Double.isFinite(speed) || speed <= 0) return 90;
        for (int i=1;i<SPEEDS.length;i++) if (speed <= SPEEDS[i])
            return ANGLES[i-1] + (float)((speed-SPEEDS[i-1])/(SPEEDS[i]-SPEEDS[i-1]))*(ANGLES[i]-ANGLES[i-1]);
        return 270;
    }
    static boolean exceeds(Double speed, Integer limit, boolean exact) {
        return speed != null && Double.isFinite(speed) && exact && limit != null && speed > limit;
    }
}
