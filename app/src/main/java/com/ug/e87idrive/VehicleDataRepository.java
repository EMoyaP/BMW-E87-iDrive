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
        if (field == VehicleField.SPEED) return selectSpeed();
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

    /**
     * The physical tests proved that the OEM CAN dashboard speed is not reliable on this unit:
     * it oscillates while the instrument cluster is stopped. GPS is therefore authoritative for
     * the user-facing speed whenever a fresh fix exists. CAN remains visible in the diagnostic
     * inspector and logs, but it is never painted as a current speed. If GPS is unavailable, a
     * live Jancar/Automotive value may be used; otherwise the dashboard stays blank.
     */
    private VehicleValue<?> selectSpeed() {
        VehicleValue<?> canValue = canbus.get(VehicleField.SPEED);
        VehicleValue<?> jancarValue = jancar.get(VehicleField.SPEED);
        VehicleValue<?> automotiveValue = automotive.get(VehicleField.SPEED);
        VehicleValue<?> gpsValue = gps.getLastValue() == null ? VehicleValue.unavailable()
                : VehicleValue.available(gps.getLastValue(), VehicleSource.GPS, gps.getLastTimestamp());

        boolean gpsFresh = gpsValue.isAvailable() && gpsValue.ageMs() <= GPS_SPEED_MAX_AGE_MS
                && numeric(gpsValue.value()) && number(gpsValue.value()) >= 0d
                && number(gpsValue.value()) <= 300d;
        if (gpsFresh) {
            if (canValue.isAvailable() && numeric(canValue.value())
                    && Math.abs(number(canValue.value()) - number(gpsValue.value())) > 3d) {
                AppSessionLog.sampledEvent("speed.can-gps-discrepancy", "VELOCIDAD", "CAN=" + canValue.value()
                        + " · GPS=" + gpsValue.value() + " · se usa GPS por fuente validada", 5_000L);
            }
            return selected(VehicleField.SPEED, gpsValue);
        }
        boolean jancarFresh = jancarValue.isAvailable() && jancarValue.ageMs() <= VEHICLE_SPEED_MAX_AGE_MS
                && numeric(jancarValue.value()) && number(jancarValue.value()) >= 0d
                && number(jancarValue.value()) <= 300d;
        if (jancarFresh) return selected(VehicleField.SPEED, jancarValue);
        if (automotiveValue.isAvailable() && automotiveValue.ageMs() <= VEHICLE_SPEED_MAX_AGE_MS
                && numeric(automotiveValue.value()) && number(automotiveValue.value()) >= 0d
                && number(automotiveValue.value()) <= 300d) {
            return selected(VehicleField.SPEED, automotiveValue);
        }
        if (canValue.isAvailable()) AppSessionLog.sampledEvent("speed.can-unvalidated", "VELOCIDAD",
                "CAN=" + canValue.value() + " · sin fuente validada actual · se oculta", 5_000L);
        return selected(VehicleField.SPEED, VehicleValue.unavailable());
    }

    private static boolean numeric(Object value) {
        return value instanceof Number && Double.isFinite(((Number) value).doubleValue());
    }

    private static double number(Object value) { return ((Number) value).doubleValue(); }

    private VehicleValue<?> selected(VehicleField field, VehicleValue<?> value) {
        VehicleObservationTrace.observe("SELECCIÓN", field.key(),
                value.isAvailable() ? value.value() : "NO EXPUESTO",
                value.isAvailable() ? value.source() + " · valor seleccionado" : "NO EXPUESTO");
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

    /**
     * Inspection-only view used by the USB DEBUG modal. It never changes the
     * automatic source priority used by the dashboard.
     */
    public String debugSourceReport(String source, boolean includeZero) {
        if ("CAN OEM".equals(source)) return canbus.debugReport(includeZero);
        if ("JCRK01 / CYA".equals(source)) {
            return debugKnownFields("JCRK01 / CYA · CAMPOS PUBLICADOS", jancar, includeZero);
        }
        if ("Android Automotive".equals(source)) {
            return debugKnownFields("ANDROID AUTOMOTIVE · CAMPOS PUBLICADOS", automotive, includeZero);
        }
        if ("GPS".equals(source)) return debugGps(includeZero);
        return debugKnownFields("FUENTE SELECCIONADA", this, includeZero);
    }

    private String debugKnownFields(String title, VehicleDataProvider provider, boolean includeZero) {
        StringBuilder out = new StringBuilder(1_000);
        int count = 0;
        out.append(title).append('\n');
        for (VehicleField field : VehicleField.values()) {
            VehicleValue<?> value = provider.get(field);
            if (!value.isAvailable() || (!includeZero && isZero(value.value()))) continue;
            out.append(field.key()).append(" · ").append(field.label()).append(" = ")
                    .append(value.value()).append(" · edad=").append(value.ageMs()).append(" ms\n");
            count++;
        }
        if (count == 0) {
            out.append("\nNo hay campos publicados en esta fuente");
            if (!includeZero) out.append(" distintos de 0");
            out.append(" en este momento.\n");
        }
        out.append("\nEsta vista es de inspección; la selección automática de la app no cambia.\n");
        return out.toString();
    }

    private String debugGps(boolean includeZero) {
        StringBuilder out = new StringBuilder(600);
        out.append("GPS · CAMPOS PUBLICADOS\n");
        Double speed = gps.getLastValue();
        if (speed != null && (includeZero || speed.doubleValue() != 0d)) {
            out.append("speed · Velocidad = ").append(speed).append(" km/h · edad=")
                    .append(Math.max(0L, System.currentTimeMillis() - gps.getLastTimestamp()))
                    .append(" ms\n");
        } else {
            out.append("\nNo hay velocidad GPS publicada");
            if (!includeZero) out.append(" distinta de 0");
            out.append(" en este momento.\n");
        }
        out.append("\nEl GPS solo es respaldo de velocidad; la posición se usa para gasolineras.\n");
        return out.toString();
    }

    private static boolean isZero(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue() == 0d;
        return "0".equals(String.valueOf(value));
    }

    public String diagnosticReport() {
        StringBuilder out = new StringBuilder(1_000);
        out.append("ORDENADOR DE A BORDO · PROCEDENCIA\n");
        VehicleField[] fields = {VehicleField.SPEED, VehicleField.RANGE, VehicleField.CONSUMPTION,
                VehicleField.EXTERIOR_TEMPERATURE, VehicleField.ENGINE_TEMPERATURE,
                VehicleField.GEAR, VehicleField.RPM};
        for (VehicleField field : fields) {
            VehicleValue<?> value = get(field);
            out.append(field.label()).append(" = ");
            if (!value.isAvailable()) out.append("no expuesto");
            else out.append(value.value()).append(" · fuente=").append(value.source())
                    .append(" · edad=").append(value.ageMs()).append(" ms");
            out.append('\n');
        }
        out.append("Velocidad: CAN se conserva si coincide; ante discrepancia material se valida con Jancar y GPS para evitar muestras estancadas.\n\n");
        out.append(canbus.capabilityReport()).append('\n');
        out.append(jancar.capabilityReport()).append('\n');
        out.append(gps.diagnosticReport());
        return out.toString();
    }
}
