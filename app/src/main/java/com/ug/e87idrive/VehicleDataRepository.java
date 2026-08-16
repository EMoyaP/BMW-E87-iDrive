package com.ug.e87idrive;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Aggregates only confirmed, read-only sources. The Jancar bridge is bound to
 * the exact OEM CarService discovered in the physical unit export; arbitrary
 * broadcasts and the mutable CAN configuration provider remain excluded.
 */
public final class VehicleDataRepository implements VehicleDataProvider {
    private static final long VEHICLE_SPEED_MAX_AGE_MS = 3_000L;
    private static final long GPS_SPEED_MAX_AGE_MS = 10_000L;
    private final GpsSpeedProvider gps;
    private final DiagnosticEngine diagnostics;
    private final AndroidAutomotiveProvider automotive;
    private final CanbusServiceProvider canbus;
    private final JancarCarProvider jancar;
    private final Map<VehicleField, String> lastSelections = new EnumMap<>(VehicleField.class);
    private final Map<VehicleField, Long> lastSelectionTimes = new EnumMap<>(VehicleField.class);

    public VehicleDataRepository(android.content.Context context, GpsSpeedProvider gps,
                                 DiagnosticEngine diagnostics, Runnable onValuesChanged) {
        this.gps = gps;
        this.diagnostics = diagnostics;
        this.automotive = new AndroidAutomotiveProvider(context, diagnostics, onValuesChanged);
        this.canbus = new CanbusServiceProvider(context, diagnostics, onValuesChanged);
        this.jancar = new JancarCarProvider(context, diagnostics, onValuesChanged);
    }

    @Override public VehicleValue<?> get(VehicleField field) {
        VehicleValue<?> canbusValue = canbus.get(field);
        if (canbusValue.isAvailable()
                && (field != VehicleField.SPEED || canbusValue.ageMs() <= VEHICLE_SPEED_MAX_AGE_MS)) {
            return selected(field, canbusValue);
        }
        VehicleValue<?> jancarValue = jancar.get(field);
        if (jancarValue.isAvailable()
                && (field != VehicleField.SPEED || jancarValue.ageMs() <= VEHICLE_SPEED_MAX_AGE_MS)) {
            return selected(field, jancarValue);
        }
        VehicleValue<?> standardValue = automotive.get(field);
        if (standardValue.isAvailable()
                && (field != VehicleField.SPEED || standardValue.ageMs() <= VEHICLE_SPEED_MAX_AGE_MS)) {
            return selected(field, standardValue);
        }
        if (field == VehicleField.SPEED && gps.getLastValue() != null
                && System.currentTimeMillis() - gps.getLastTimestamp() <= GPS_SPEED_MAX_AGE_MS) {
            return selected(field, VehicleValue.available(
                    gps.getLastValue(), VehicleSource.GPS, gps.getLastTimestamp()));
        }
        // No vendor value is returned until a confirmed, device-specific mapping exists.
        return selected(field, VehicleValue.unavailable());
    }

    private VehicleValue<?> selected(VehicleField field, VehicleValue<?> value) {
        String selection = value.isAvailable()
                ? value.source() + "=" + value.value() : "NO EXPUESTO";
        synchronized (lastSelections) {
            String previous = lastSelections.get(field);
            long now = System.currentTimeMillis();
            long previousTime = lastSelectionTimes.containsKey(field)
                    ? lastSelectionTimes.get(field) : 0L;
            String source = selection.contains("=")
                    ? selection.substring(0, selection.indexOf('=')) : selection;
            String previousSource = previous != null && previous.contains("=")
                    ? previous.substring(0, previous.indexOf('=')) : previous;
            boolean sourceChanged = previous == null || !source.equals(previousSource);
            if (!selection.equals(previous) && (sourceChanged || now - previousTime >= 5_000L)) {
                lastSelections.put(field, selection);
                lastSelectionTimes.put(field, now);
                AppSessionLog.event("DATO SELECCIONADO", field.label() + " · " + selection);
            }
        }
        return value;
    }

    @Override public Set<VehicleField> supportedFields() {
        EnumSet<VehicleField> fields = EnumSet.noneOf(VehicleField.class);
        if (gps.getLastValue() != null) fields.add(VehicleField.SPEED);
        fields.addAll(jancar.supportedFields());
        fields.addAll(canbus.supportedFields());
        fields.addAll(automotive.supportedFields());
        return Collections.unmodifiableSet(fields);
    }

    @Override public void start() { gps.start(); automotive.start(); canbus.start(); jancar.start(); }
    @Override public void stop() { jancar.stop(); canbus.stop(); automotive.stop(); gps.stop(); }

    public DiagnosticEngine diagnostics() { return diagnostics; }
    public JancarCarProvider.MaintenanceSnapshot maintenanceSnapshot() { return jancar.maintenanceSnapshot(); }

    public String diagnosticReport() {
        StringBuilder out = new StringBuilder(1_000);
        out.append("ORDENADOR DE A BORDO · PROCEDENCIA\n");
        VehicleField[] fields = {VehicleField.SPEED, VehicleField.RANGE, VehicleField.CONSUMPTION,
                VehicleField.EXTERIOR_TEMPERATURE, VehicleField.ENGINE_TEMPERATURE};
        for (VehicleField field : fields) {
            VehicleValue<?> value = get(field);
            out.append(field.label()).append(" = ");
            if (!value.isAvailable()) out.append("no expuesto");
            else out.append(value.value()).append(" · fuente=").append(value.source())
                    .append(" · edad=").append(value.ageMs()).append(" ms");
            out.append('\n');
        }
        out.append("Prioridad de velocidad: CanBusManager OEM verificado, Jancar OEM, Android Automotive y después GPS (máx. 10 s).\n\n");
        out.append(canbus.capabilityReport()).append('\n');
        out.append(jancar.capabilityReport()).append('\n');
        out.append(gps.diagnosticReport());
        return out.toString();
    }
}
