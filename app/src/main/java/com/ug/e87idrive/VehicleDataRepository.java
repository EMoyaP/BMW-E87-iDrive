package com.ug.e87idrive;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Safe aggregation point for future JCRK01/CYA adapters.
 * The current passive diagnostic engine deliberately does not map arbitrary extras to CAN fields.
 */
public final class VehicleDataRepository implements VehicleDataProvider {
    private static final long VEHICLE_SPEED_MAX_AGE_MS = 3_000L;
    private static final long GPS_SPEED_MAX_AGE_MS = 10_000L;
    private final GpsSpeedProvider gps;
    private final DiagnosticEngine diagnostics;
    private final AndroidAutomotiveProvider automotive;

    public VehicleDataRepository(android.content.Context context, GpsSpeedProvider gps,
                                 DiagnosticEngine diagnostics, Runnable onValuesChanged) {
        this.gps = gps;
        this.diagnostics = diagnostics;
        this.automotive = new AndroidAutomotiveProvider(context, diagnostics, onValuesChanged);
    }

    @Override public VehicleValue<?> get(VehicleField field) {
        VehicleValue<?> standardValue = automotive.get(field);
        if (standardValue.isAvailable()
                && (field != VehicleField.SPEED || standardValue.ageMs() <= VEHICLE_SPEED_MAX_AGE_MS)) {
            return standardValue;
        }
        if (field == VehicleField.SPEED && gps.getLastValue() != null
                && System.currentTimeMillis() - gps.getLastTimestamp() <= GPS_SPEED_MAX_AGE_MS) {
            return VehicleValue.available(gps.getLastValue(), VehicleSource.GPS, gps.getLastTimestamp());
        }
        // No vendor value is returned until a confirmed, device-specific mapping exists.
        return VehicleValue.unavailable();
    }

    @Override public Set<VehicleField> supportedFields() {
        EnumSet<VehicleField> fields = EnumSet.noneOf(VehicleField.class);
        if (gps.getLastValue() != null) fields.add(VehicleField.SPEED);
        fields.addAll(automotive.supportedFields());
        return Collections.unmodifiableSet(fields);
    }

    @Override public void start() { gps.start(); automotive.start(); }
    @Override public void stop() { automotive.stop(); gps.stop(); }

    public DiagnosticEngine diagnostics() { return diagnostics; }
}
