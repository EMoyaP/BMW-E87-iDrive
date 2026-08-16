package com.ug.e87idrive;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Read-only probe for the public Android Automotive CarProperty API.
 *
 * This class uses reflection so the same APK remains installable on ordinary Android head units.
 * It never calls setProperty(), sends vendor broadcasts or opens CAN/MCU/UART devices.
 */
@SuppressLint("PrivateApi") // android.car is public on AAOS but absent from the standard Android SDK stub.
public final class AndroidAutomotiveProvider implements VehicleDataProvider {
    private static final long POLL_MS = 1_000L;

    private final Context context;
    private final DiagnosticEngine diagnostics;
    private final Runnable onValuesChanged;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<VehicleField, VehicleValue<?>> values = new EnumMap<>(VehicleField.class);
    private final Map<String, String> capability = new LinkedHashMap<>();
    private HandlerThread thread;
    private Handler handler;
    private Object car;
    private Object propertyManager;
    private Class<?> propertyIds;
    private volatile boolean running;
    private String lastUiSnapshot = "";
    private String lastLogSnapshot = "";
    private long lastLogSnapshotAt;

    public AndroidAutomotiveProvider(Context context, DiagnosticEngine diagnostics, Runnable onValuesChanged) {
        this.context = context.getApplicationContext();
        this.diagnostics = diagnostics;
        this.onValuesChanged = onValuesChanged;
    }

    @Override public synchronized VehicleValue<?> get(VehicleField field) {
        VehicleValue<?> value = values.get(field);
        return value == null ? VehicleValue.unavailable() : value;
    }

    @Override public synchronized Set<VehicleField> supportedFields() {
        return Collections.unmodifiableSet(values.isEmpty()
                ? EnumSet.noneOf(VehicleField.class) : EnumSet.copyOf(values.keySet()));
    }

    @Override public synchronized void start() {
        if (running) return;
        running = true;
        thread = new HandlerThread("e87-aaos-readonly");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::connectAndPoll);
    }

    @Override public synchronized void stop() {
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        disconnect();
        if (thread != null) thread.quitSafely();
        handler = null;
        thread = null;
    }

    public synchronized String capabilityReport() {
        StringBuilder out = new StringBuilder();
        out.append("PLATAFORMA ANDROID AUTOMOTIVE (solo lectura)\n");
        out.append("feature android.hardware.type.automotive = ")
                .append(context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)).append('\n');
        if (capability.isEmpty()) out.append("Sonda aún no ejecutada.\n");
        for (Map.Entry<String, String> entry : capability.entrySet()) {
            out.append(entry.getKey()).append(" = ").append(entry.getValue()).append('\n');
        }
        out.append("La sonda no escribe propiedades ni accede a buses CAN/UART.\n");
        return out.toString();
    }

    private void connectAndPoll() {
        if (!running) return;
        try {
            Class<?> carClass = Class.forName("android.car.Car");
            propertyIds = Class.forName("android.car.VehiclePropertyIds");
            Method createCar = carClass.getMethod("createCar", Context.class);
            car = createCar.invoke(null, context);
            String serviceName = String.valueOf(carClass.getField("PROPERTY_SERVICE").get(null));
            propertyManager = carClass.getMethod("getCarManager", String.class).invoke(car, serviceName);
            setCapability("android.car.Car", propertyManager == null ? "gestor de propiedades ausente" : "disponible");
        } catch (ClassNotFoundException e) {
            setCapability("android.car.Car", "no incluido en este sistema Android");
            publishReport();
            return;
        } catch (Throwable error) {
            setCapability("android.car.Car", "no accesible: " + shortError(error));
            publishReport();
            return;
        }
        poll();
    }

    private void poll() {
        if (!running || propertyManager == null) return;
        long now = System.currentTimeMillis();
        readVehicleSpeed(now);
        readBoardComputer(now);
        readExteriorTemperature(now);
        readReverse(now);
        readParkingBrake(now);
        readDoors(now);
        readDriverSeatbelt(now);
        readLights(now);
        readClimate(now);
        publishReport();
        notifyIfValuesChanged();
        Handler current = handler;
        if (running && current != null) current.postDelayed(this::poll, POLL_MS);
    }

    private void readVehicleSpeed(long now) {
        Number value = asNumber(readGlobal("PERF_VEHICLE_SPEED_DISPLAY", Float.class));
        String property = "PERF_VEHICLE_SPEED_DISPLAY";
        if (value == null) {
            value = asNumber(readGlobal("PERF_VEHICLE_SPEED", Float.class));
            property = "PERF_VEHICLE_SPEED";
        }
        if (value == null) {
            clear(VehicleField.SPEED);
            return;
        }
        double metersPerSecond = value.doubleValue();
        double kmh = Math.abs(metersPerSecond) * 3.6d;
        diagnostics.recordVehicleObservation("speed_mps", metersPerSecond,
                VehicleSource.ANDROID_AUTOMOTIVE.name() + "." + property);
        put(VehicleField.SPEED, kmh, now);
    }

    private void readBoardComputer(long now) {
        Number remainingMeters = asNumber(readGlobal("RANGE_REMAINING", Float.class));
        if (remainingMeters == null) {
            clear(VehicleField.RANGE);
        } else {
            double rangeKm = Math.max(0d, remainingMeters.doubleValue() / 1_000d);
            diagnostics.recordVehicleObservation("range_remaining.meters", remainingMeters,
                    VehicleSource.ANDROID_AUTOMOTIVE.name() + ".RANGE_REMAINING");
            put(VehicleField.RANGE, rangeKm, now);
        }

        Number consumption = asNumber(readGlobal("INSTANTANEOUS_FUEL_ECONOMY", Float.class));
        if (consumption == null) {
            clear(VehicleField.CONSUMPTION);
        } else {
            double litersPer100Km = Math.max(0d, consumption.doubleValue());
            diagnostics.recordVehicleObservation("fuel_economy.l_per_100km", litersPer100Km,
                    VehicleSource.ANDROID_AUTOMOTIVE.name() + ".INSTANTANEOUS_FUEL_ECONOMY");
            put(VehicleField.CONSUMPTION, litersPer100Km, now);
        }
    }

    private void readExteriorTemperature(long now) {
        Number value = asNumber(readGlobal("ENV_OUTSIDE_TEMPERATURE", Float.class));
        if (value == null) {
            clear(VehicleField.EXTERIOR_TEMPERATURE);
            return;
        }
        diagnostics.recordVehicleObservation("temp_ext.celsius", value,
                VehicleSource.ANDROID_AUTOMOTIVE.name() + ".ENV_OUTSIDE_TEMPERATURE");
        put(VehicleField.EXTERIOR_TEMPERATURE, value.doubleValue(), now);
    }

    private void readReverse(long now) {
        Number selected = asNumber(readGlobal("GEAR_SELECTION", Integer.class));
        String property = "GEAR_SELECTION";
        if (selected == null) {
            selected = asNumber(readGlobal("CURRENT_GEAR", Integer.class));
            property = "CURRENT_GEAR";
        }
        Integer reverse = vehicleGearId("GEAR_REVERSE");
        if (selected == null || reverse == null) {
            clear(VehicleField.REVERSE);
            return;
        }
        int gear = selected.intValue();
        diagnostics.recordVehicleObservation("gear.raw", gear,
                VehicleSource.ANDROID_AUTOMOTIVE.name() + "." + property);
        put(VehicleField.REVERSE, gear == reverse ? "Activa" : "Inactiva", now);
    }

    private void readParkingBrake(long now) {
        Object value = readGlobal("PARKING_BRAKE_ON", Boolean.class);
        if (value instanceof Boolean) {
            put(VehicleField.PARKING_BRAKE, (Boolean) value ? "Activado" : "Liberado", now);
        } else clear(VehicleField.PARKING_BRAKE);
    }

    private void readDoors(long now) {
        AreaRead doors = readAreas("DOOR_POS", Integer.class);
        if (!doors.readable || doors.values.isEmpty()) {
            clear(VehicleField.DOORS);
            return;
        }
        int open = 0;
        for (Object value : doors.values.values()) {
            if (value instanceof Number && ((Number) value).intValue() != 0) open++;
        }
        put(VehicleField.DOORS, open == 0 ? "Cerradas" : open == 1 ? "1 abierta" : open + " abiertas", now);
    }

    private void readDriverSeatbelt(long now) {
        Object driverSeat = readGlobal("INFO_DRIVER_SEAT", Integer.class);
        if (!(driverSeat instanceof Integer)) {
            setCapability("SEAT_BELT_BUCKLED", "sin área de asiento del conductor verificable");
            clear(VehicleField.SEATBELT);
            return;
        }
        Object value = readArea("SEAT_BELT_BUCKLED", Boolean.class, (Integer) driverSeat);
        if (value instanceof Boolean) {
            put(VehicleField.SEATBELT, (Boolean) value ? "Abrochado" : "Sin abrochar", now);
        } else clear(VehicleField.SEATBELT);
    }

    private void readLights(long now) {
        Integer head = asInteger(readGlobal("HEADLIGHTS_STATE", Integer.class));
        Integer high = asInteger(readGlobal("HIGH_BEAM_LIGHTS_STATE", Integer.class));
        Integer frontFog = asInteger(readGlobal("FRONT_FOG_LIGHTS_STATE", Integer.class));
        Integer rearFog = asInteger(readGlobal("REAR_FOG_LIGHTS_STATE", Integer.class));
        Integer hazard = asInteger(readGlobal("HAZARD_LIGHTS_STATE", Integer.class));
        if (head == null && high == null && frontFog == null && rearFog == null && hazard == null) {
            clear(VehicleField.LIGHTS);
            return;
        }
        String state;
        if (isOn(hazard)) state = "Emergencia";
        else if (isOn(high)) state = "Largas";
        else if (isOn(rearFog)) state = "Antiniebla tras.";
        else if (isOn(frontFog)) state = "Antiniebla del.";
        else if (head != null && head == 2) state = "Diurnas";
        else if (isOn(head)) state = "Cruce";
        else state = "Apagadas";
        put(VehicleField.LIGHTS, state, now);
    }

    private void readClimate(long now) {
        AreaRead temperatures = readAreas("HVAC_TEMPERATURE_SET", Float.class);
        if (temperatures.readable && !temperatures.values.isEmpty()) {
            recordAreaValues("climate_temp", "HVAC_TEMPERATURE_SET", temperatures);
            put(VehicleField.CLIMATE_TEMPERATURE, summarizeNumbers(temperatures.values, " °C"), now);
        } else clear(VehicleField.CLIMATE_TEMPERATURE);

        AreaRead fans = readAreas("HVAC_FAN_SPEED", Integer.class);
        if (fans.readable && !fans.values.isEmpty()) {
            recordAreaValues("climate_fan", "HVAC_FAN_SPEED", fans);
            put(VehicleField.CLIMATE_FAN, summarizeNumbers(fans.values, ""), now);
        } else clear(VehicleField.CLIMATE_FAN);

        AreaRead power = readAreas("HVAC_POWER_ON", Boolean.class);
        if (power.readable && !power.values.isEmpty()) {
            recordAreaValues("climate_power", "HVAC_POWER_ON", power);
            boolean anyOn = false;
            for (Object value : power.values.values()) if (Boolean.TRUE.equals(value)) anyOn = true;
            put(VehicleField.CLIMATE_STATE, anyOn ? "Encendido" : "Apagado", now);
        } else clear(VehicleField.CLIMATE_STATE);
    }

    private void recordAreaValues(String field, String property, AreaRead areas) {
        for (Map.Entry<Integer, Object> entry : areas.values.entrySet()) {
            diagnostics.recordVehicleObservation(field + ".area_0x"
                            + Integer.toHexString(entry.getKey()), entry.getValue(),
                    VehicleSource.ANDROID_AUTOMOTIVE.name() + "." + property);
        }
    }

    private Object summarizeNumbers(Map<Integer, Object> values, String suffix) {
        if (values.size() == 1) {
            Object only = values.values().iterator().next();
            if (only instanceof Number && suffix.isEmpty()) return ((Number) only).intValue();
            if (only instanceof Number) return ((Number) only).doubleValue();
            return String.valueOf(only);
        }
        StringBuilder summary = new StringBuilder();
        for (Object value : values.values()) {
            if (summary.length() > 0) summary.append(" / ");
            if (value instanceof Float || value instanceof Double) {
                summary.append(String.format(java.util.Locale.ROOT, "%.1f", ((Number) value).doubleValue()));
            } else summary.append(value);
        }
        return summary.append(suffix).toString();
    }

    private boolean isOn(Integer value) { return value != null && value == 1; }
    private Integer asInteger(Object value) { return value instanceof Integer ? (Integer) value : null; }
    private Number asNumber(Object value) { return value instanceof Number ? (Number) value : null; }

    private Integer vehicleGearId(String name) {
        try {
            Class<?> gears = Class.forName("android.car.VehicleGear");
            return gears.getField(name).getInt(null);
        } catch (Throwable error) {
            setCapability(name, "constante de marcha no disponible: " + shortError(error));
            return null;
        }
    }

    private Object readGlobal(String name, Class<?> type) {
        return readArea(name, type, 0);
    }

    private Object readArea(String name, Class<?> type, int areaId) {
        Integer propertyId = propertyId(name);
        if (propertyId == null || propertyManager == null) return null;
        try {
            Method configMethod = propertyManager.getClass().getMethod("getCarPropertyConfig", int.class);
            Object config = configMethod.invoke(propertyManager, propertyId);
            if (config == null) {
                setCapability(name, "no implementada por el vehículo/sistema");
                return null;
            }
            Method getter = propertyManager.getClass().getMethod("getProperty", Class.class, int.class, int.class);
            Object value = getter.invoke(propertyManager, type, propertyId, areaId);
            setCapability(name, "legible");
            return value;
        } catch (Throwable error) {
            setCapability(name, classifyReadError(error));
            return null;
        }
    }

    private AreaRead readAreas(String name, Class<?> type) {
        Integer propertyId = propertyId(name);
        AreaRead result = new AreaRead();
        if (propertyId == null || propertyManager == null) return result;
        try {
            Method configMethod = propertyManager.getClass().getMethod("getCarPropertyConfig", int.class);
            Object config = configMethod.invoke(propertyManager, propertyId);
            if (config == null) {
                setCapability(name, "no implementada por el vehículo/sistema");
                return result;
            }
            int[] areaIds = (int[]) config.getClass().getMethod("getAreaIds").invoke(config);
            Method getter = propertyManager.getClass().getMethod("getProperty", Class.class, int.class, int.class);
            for (int areaId : areaIds) {
                Object value = getter.invoke(propertyManager, type, propertyId, areaId);
                result.values.put(areaId, value);
            }
            result.readable = true;
            setCapability(name, "legible · áreas=" + areaIds.length);
        } catch (Throwable error) {
            setCapability(name, classifyReadError(error));
        }
        return result;
    }

    private Integer propertyId(String name) {
        if (propertyIds == null) return null;
        try {
            Field field = propertyIds.getField(name);
            return field.getInt(null);
        } catch (Throwable error) {
            setCapability(name, "constante no incluida en esta versión de android.car");
            return null;
        }
    }

    private String classifyReadError(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof SecurityException) return "bloqueada por permiso del fabricante";
        String name = cause.getClass().getSimpleName();
        if (name.contains("NotAvailable") || name.contains("Unavailable")) return "temporalmente no disponible";
        return "error de lectura: " + shortError(cause);
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof InvocationTargetException && current.getCause() != null) current = current.getCause();
        return current;
    }

    private String shortError(Throwable error) {
        Throwable cause = unwrap(error);
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) return cause.getClass().getSimpleName();
        return cause.getClass().getSimpleName() + ": " + message.replace('\n', ' ');
    }

    private synchronized void put(VehicleField field, Object value, long now) {
        values.put(field, VehicleValue.available(value, VehicleSource.ANDROID_AUTOMOTIVE, now));
        diagnostics.recordVehicleObservation(field.key(), value, VehicleSource.ANDROID_AUTOMOTIVE.name());
    }

    private synchronized void clear(VehicleField field) { values.remove(field); }

    private synchronized void setCapability(String key, String value) {
        String previous = capability.put(key, value);
        if (!value.equals(previous)) AppSessionLog.event("ANDROID CAR", key + "=" + value);
    }

    private void publishReport() { diagnostics.setPlatformVehicleReport(capabilityReport()); }

    private void notifyIfValuesChanged() {
        StringBuilder snapshot = new StringBuilder();
        synchronized (this) {
            for (Map.Entry<VehicleField, VehicleValue<?>> entry : values.entrySet()) {
                snapshot.append(entry.getKey()).append('=').append(entry.getValue().value()).append(';');
            }
        }
        String current = snapshot.toString();
        long now = System.currentTimeMillis();
        if (!current.equals(lastLogSnapshot) && now - lastLogSnapshotAt >= 5_000L) {
            lastLogSnapshot = current;
            lastLogSnapshotAt = now;
            AppSessionLog.event("ANDROID CAR", current.isEmpty()
                    ? "No hay propiedades de vehículo publicadas" : current);
        }
        if (current.equals(lastUiSnapshot)) return;
        lastUiSnapshot = current;
        if (onValuesChanged != null) mainHandler.post(onValuesChanged);
    }

    private synchronized void disconnect() {
        if (car != null) {
            try { car.getClass().getMethod("disconnect").invoke(car); } catch (Throwable ignored) {}
        }
        car = null;
        propertyManager = null;
        propertyIds = null;
    }

    private static final class AreaRead {
        boolean readable;
        final Map<Integer, Object> values = new LinkedHashMap<>();
    }
}
