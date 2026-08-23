package com.ug.e87idrive;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Conservative, device-specific reader for the Jancar service found in the
 * exported firmware. It talks only to documented getter transactions and
 * passive callback transactions of the existing persistent CarService. No
 * command is sent to CAN/MCU/UART and no OEM configuration provider is queried.
 */
final class JancarCarProvider implements VehicleDataProvider {
    static final String PACKAGE_NAME = "com.jancar.services";
    private static final String ACTION_CAR = "com.jancar.services.action.car";
    private static final String DESCRIPTOR = "com.jancar.services.car.ICar";
    private static final long POLL_MS = 1_000L;
    private static final long ALERTS_MS = 12_000L;
    private static final long MAINTENANCE_MS = 60_000L;

    // Transaction numbers from the ICar AIDL stub in the APK exported from this unit.
    private static final int GET_CAR_ID = 0x02;
    private static final int GET_REALTIME = 0x09;
    private static final int GET_HANDBRAKE = 0x0b;
    private static final int GET_DOORS = 0x0c;
    private static final int GET_LIGHTS = 0x0d;
    private static final int GET_HEADLIGHT = 0x0e;
    private static final int GET_CLIMATE = 0x0f;
    private static final int GET_OUTSIDE_TEMP = 0x18;
    private static final int GET_TRIP = 0x19;
    private static final int GET_EXTRA = 0x1a;
    private static final int GET_MAINTENANCE_MILEAGE = 0x26;
    private static final int GET_MAINTENANCE_DAYS = 0x27;
    private static final int GET_REPORT_ARRAY = 0x2a;
    private static final int IS_FAST_REVERSE = 0x1e;
    private static final int GET_CAN_VERSION = 0x42;
    private static final int REGISTER_CALLBACK = 0x03;
    private static final int UNREGISTER_CALLBACK = 0x04;
    private static final int REGISTER_REALTIME = 0x05;
    private static final int UNREGISTER_REALTIME = 0x06;
    private static final String CALLBACK_DESCRIPTOR = "com.jancar.services.car.ICarCallback";
    private static final long CALLBACK_MAX_AGE_MS = 5_000L;

    // IVICar.RealTimeInfo.Id
    private static final int RT_SPEED = 1;
    private static final int RT_CONSUMPTION = 2;
    private static final int RT_RPM = 3;
    // IVICar.ExtraState.Id
    private static final int EXTRA_BATTERY_VOLTAGE = 0;
    private static final int EXTRA_REMAIN_FUEL_DISTANCE = 2;
    private static final int EXTRA_GEAR = 3;
    private static final int EXTRA_SEAT_BELT = 5;
    // Climate.Id
    private static final int CLIMATE_LEFT_TEMP = 1;
    private static final int CLIMATE_RIGHT_TEMP = 2;
    private static final int CLIMATE_FAN_LEVEL = 16;
    private static final int CLIMATE_AC = 19;

    private final Context context;
    private final DiagnosticEngine diagnostics;
    private final Runnable onValuesChanged;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final EnumMap<VehicleField, VehicleValue<?>> values =
            new EnumMap<>(VehicleField.class);
    private final Object lock = new Object();
    private HandlerThread thread;
    private Handler worker;
    private IBinder car;
    private boolean running, bound;
    private long lastAlertRead, lastMaintenanceRead;
    private String connectionState = "Sonda aún no iniciada";
    private String canVersion = "";
    private volatile String lastRawTelemetry = "sin lectura";
    private volatile String lastRawVehicle = "sin lectura";
    private volatile String lastRawClimate = "sin lectura";
    private final IBinder callback = new PassiveCarCallback();
    private volatile boolean callbacksRegistered;
    private volatile long callbackLastAt;
    private volatile long callbackSpeedAt, callbackConsumptionAt, callbackRpmAt,
            callbackGearAt, callbackSeatbeltAt, callbackDoorsAt, callbackLightsAt, callbackOutsideTempAt,
            callbackHeadlightAt, callbackHandbrakeAt, callbackReverseAt;
    private volatile Float callbackSpeed, callbackConsumption, callbackRpm, callbackGear, callbackSeatbelt;
    private volatile Integer callbackDoors, callbackLights, callbackOutsideTemp;
    private volatile Boolean callbackHeadlight, callbackHandbrake, callbackReverse;
    private String lastLoggedRaw = "";
    private long lastLoggedRawAt;
    private MaintenanceSnapshot maintenance = MaintenanceSnapshot.unavailable("Sin lectura OEM");
    private String lastUiSnapshot = "";

    JancarCarProvider(Context context, DiagnosticEngine diagnostics, Runnable onValuesChanged) {
        this.context = context.getApplicationContext();
        this.diagnostics = diagnostics;
        this.onValuesChanged = onValuesChanged;
    }

    @Override public synchronized void start() {
        if (running) return;
        running = true;
        AppSessionLog.event("COCHE OEM", "Iniciando sonda Jancar de solo lectura");
        if (!installed()) {
            connectionState = "Paquete Jancar no instalado";
            AppSessionLog.event("COCHE OEM", connectionState);
            publishReport();
            return;
        }
        thread = new HandlerThread("e87-jancar-readonly");
        thread.start();
        worker = new Handler(thread.getLooper());
        main.post(this::bindExistingService);
    }

    @Override public synchronized void stop() {
        running = false;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        unregisterPassiveCallbacks();
        main.post(() -> {
            if (bound) {
                try { context.unbindService(connection); } catch (Exception ignored) { }
            }
            bound = false;
        });
        synchronized (lock) { car = null; }
        resetCallbackTimestamps();
        if (thread != null) thread.quitSafely();
        worker = null;
        thread = null;
    }

    @Override public VehicleValue<?> get(VehicleField field) {
        synchronized (values) {
            VehicleValue<?> value = values.get(field);
            return value == null ? VehicleValue.unavailable() : value;
        }
    }

    @Override public Set<VehicleField> supportedFields() {
        synchronized (values) {
            return values.isEmpty() ? Collections.emptySet()
                    : Collections.unmodifiableSet(EnumSet.copyOf(values.keySet()));
        }
    }

    MaintenanceSnapshot maintenanceSnapshot() { return maintenance; }

    boolean hasLiveRealtimeSpeed() {
        return callbackFresh(callbackSpeedAt) && callbackSpeed != null && validNumber(callbackSpeed);
    }

    String capabilityReport() {
        StringBuilder out = new StringBuilder(1_200);
        out.append("JANCAR CARSERVICE · LECTURA BINDER PASIVA\n");
        out.append("paquete=").append(PACKAGE_NAME).append(" instalado=").append(installed()).append('\n');
        out.append("acción=").append(ACTION_CAR).append('\n');
        out.append("descriptor esperado=").append(DESCRIPTOR).append('\n');
        out.append("estado=").append(connectionState).append('\n');
        if (!canVersion.isEmpty()) out.append("CAN OEM=").append(canVersion).append('\n');
        out.append("raw ordenador=").append(lastRawTelemetry).append('\n');
        out.append("raw estado=").append(lastRawVehicle).append('\n');
        out.append("raw clima=").append(lastRawClimate).append('\n');
        out.append("Método: getters y callbacks AIDL verificados en la APK exportada; "
                + "solo lectura, sin provider CAN ni escrituras.\n");
        out.append("callbacks activos=").append(callbacksRegistered)
                .append(" · última señal=").append(callbackLastAt > 0
                        ? Math.max(0L, System.currentTimeMillis() - callbackLastAt) + " ms" : "nunca")
                .append('\n');
        out.append("Avisos activos leídos=").append(maintenance.active.size())
                .append(" · última lectura=").append(maintenance.timestamp > 0
                        ? Math.max(0L, System.currentTimeMillis() - maintenance.timestamp) + " ms" : "nunca")
                .append('\n');
        return out.toString();
    }

    private boolean installed() {
        try {
            PackageInfo ignored = context.getPackageManager().getPackageInfo(PACKAGE_NAME, 0);
            return ignored != null;
        } catch (Exception ignored) { return false; }
    }

    private void bindExistingService() {
        synchronized (this) { if (!running || bound) return; }
        Intent intent = new Intent(ACTION_CAR)
                .setComponent(new ComponentName(PACKAGE_NAME, "com.jancar.services.car.CarService"));
        try {
            // Deliberately no BIND_AUTO_CREATE: never starts or keeps alive an OEM service.
            boolean result = context.bindService(intent, connection, 0);
            synchronized (this) { bound = result; }
            connectionState = result ? "Esperando servicio OEM persistente" : "CarService no está disponible";
            AppSessionLog.event("COCHE OEM", "bind existente=" + result);
        } catch (Exception error) {
            connectionState = "No se pudo enlazar: " + error.getClass().getSimpleName();
            AppSessionLog.event("COCHE OEM", connectionState);
        }
        publishReport();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            Handler target = worker;
            if (target != null) target.post(() -> acceptService(service));
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            callbacksRegistered = false;
            resetCallbackTimestamps();
            synchronized (lock) { car = null; }
            connectionState = "CarService desconectado";
            AppSessionLog.event("COCHE OEM", connectionState);
            publishReport();
        }

        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
    };

    private void acceptService(IBinder service) {
        if (!running || service == null) return;
        try {
            String descriptor = service.getInterfaceDescriptor();
            if (!DESCRIPTOR.equals(descriptor)) {
                connectionState = "Descriptor OEM no compatible: " + descriptor;
                publishReport();
                return;
            }
            synchronized (lock) { car = service; }
            connectionState = "CarService conectado · sólo lectura";
            canVersion = readString(GET_CAN_VERSION);
            registerPassiveCallbacks();
            AppSessionLog.event("COCHE OEM", connectionState + " · CAN="
                    + (canVersion.isEmpty() ? "no publicado" : canVersion));
            poll();
        } catch (Exception error) {
            connectionState = "Servicio rechazado: " + error.getClass().getSimpleName();
            AppSessionLog.event("COCHE OEM", connectionState);
            publishReport();
        }
    }

    private void poll() {
        if (!running || currentCar() == null) return;
        long now = System.currentTimeMillis();
        readBoardComputer(now);
        readVehicleState(now);
        readClimate(now);
        if (now - lastAlertRead >= ALERTS_MS) {
            lastAlertRead = now;
            readAlerts(now, false);
        }
        if (now - lastMaintenanceRead >= MAINTENANCE_MS) {
            lastMaintenanceRead = now;
            readAlerts(now, true);
        }
        publishReport();
        notifyIfChanged();
        Handler target = worker;
        if (running && target != null) target.postDelayed(this::poll, POLL_MS);
    }

    private void readBoardComputer(long now) {
        float speed = callbackFresh(callbackSpeedAt) && callbackSpeed != null
                ? callbackSpeed : readFloat(GET_REALTIME, RT_SPEED);
        float consumption = callbackFresh(callbackConsumptionAt) && callbackConsumption != null
                ? callbackConsumption : readFloat(GET_REALTIME, RT_CONSUMPTION);
        float rpm = callbackFresh(callbackRpmAt) && callbackRpm != null
                ? callbackRpm : readFloat(GET_REALTIME, RT_RPM);
        float range = readFloat(GET_EXTRA, EXTRA_REMAIN_FUEL_DISTANCE);
        float gear = callbackFresh(callbackGearAt) && callbackGear != null
                ? callbackGear : readFloat(GET_EXTRA, EXTRA_GEAR);
        int rawTemp = callbackFresh(callbackOutsideTempAt) && callbackOutsideTemp != null
                ? callbackOutsideTemp : readInt(GET_OUTSIDE_TEMP);
        lastRawTelemetry = "velocidad=" + speed + " · consumo=" + consumption
                + " · rpm=" + rpm + " · autonomía=" + range
                + " · marcha=" + gear + " (" + gearText(gear) + ")"
                + " · tempExterior=0x" + Integer.toHexString(rawTemp) + " (" + rawTemp + ")";
        putNumber(VehicleField.SPEED, speed, now);
        putNumber(VehicleField.CONSUMPTION, consumption, now);
        putNumber(VehicleField.RPM, rpm, now);
        putNumber(VehicleField.RANGE, range, now);
        String gearLabel = gearText(gear);
        if (gearLabel == null) clear(VehicleField.GEAR);
        else put(VehicleField.GEAR, gearLabel, now);
        Double temp = outsideTempCelsius(rawTemp);
        if (temp == null) clear(VehicleField.EXTERIOR_TEMPERATURE);
        else put(VehicleField.EXTERIOR_TEMPERATURE, temp, now);
        trace("board.speed", speed, speed < 0 ? "desconocido" : speed + " km/h");
        trace("board.consumption", consumption, consumption < 0 ? "desconocido" : consumption + " l/100 km");
        trace("board.rpm", rpm, rpm < 0 ? "desconocido" : rpm + " rpm");
        trace("board.range", range, range < 0 ? "desconocido" : range + " km");
        trace("board.gear", gear, gearLabel == null ? "desconocido" : gearLabel);
        trace("board.outsideTemperature", rawTemp, temp == null ? "desconocido" : temp + " °C");
    }

    private void readVehicleState(long now) {
        int doors = callbackFresh(callbackDoorsAt) && callbackDoors != null ? callbackDoors : readInt(GET_DOORS);
        if (doors < 0) clear(VehicleField.DOORS);
        else put(VehicleField.DOORS, doorsText(doors), now);

        int lights = callbackFresh(callbackLightsAt) && callbackLights != null ? callbackLights : readInt(GET_LIGHTS);
        Boolean headlight = callbackFresh(callbackHeadlightAt) && callbackHeadlight != null
                ? callbackHeadlight : readBoolean(GET_HEADLIGHT);
        if (lights < 0) clear(VehicleField.LIGHTS);
        else put(VehicleField.LIGHTS, lightsText(lights, headlight), now);

        int brake = callbackFresh(callbackHandbrakeAt) && callbackHandbrake != null
                ? (callbackHandbrake ? 1 : 0) : readInt(GET_HANDBRAKE);
        if (brake == 0) put(VehicleField.PARKING_BRAKE, "Liberado", now);
        else if (brake == 1) put(VehicleField.PARKING_BRAKE, "Activado", now);
        else clear(VehicleField.PARKING_BRAKE);

        float belt = callbackFresh(callbackSeatbeltAt) && callbackSeatbelt != null
                ? callbackSeatbelt : readFloat(GET_EXTRA, EXTRA_SEAT_BELT);
        if (belt == 0f) put(VehicleField.SEATBELT, "Abrochado", now);
        else if (belt == 1f) put(VehicleField.SEATBELT, "Sin abrochar", now);
        else clear(VehicleField.SEATBELT);

        Boolean reverse = callbackFresh(callbackReverseAt) && callbackReverse != null
                ? callbackReverse : readBoolean(GET_FAST_REVERSE);
        if (reverse == null) clear(VehicleField.REVERSE);
        else put(VehicleField.REVERSE, reverse ? "Activa" : "Inactiva", now);
        lastRawVehicle = "puertas=" + doors + " · luces=" + lights
                + " · faros=" + headlight + " · freno=" + brake
                + " · cinturón=" + belt + " · marchaAtrás=" + reverse;
        trace("state.doors", doors, doorsText(doors));
        trace("state.lights", lights, lightsText(lights, headlight));
        trace("state.headlight", headlight, headlight == null ? "desconocido" : headlight ? "activo" : "apagado");
        trace("state.parkingBrake", brake, brake == 0 ? "Liberado" : brake == 1 ? "Activado" : "desconocido");
        trace("state.seatbelt", belt, belt == 0f ? "Abrochado" : belt == 1f ? "Sin abrochar" : "desconocido");
        trace("state.reverse", reverse, reverse == null ? "desconocido" : reverse ? "Activa" : "Inactiva");
    }

    private static final int GET_FAST_REVERSE = IS_FAST_REVERSE;

    private static void trace(String field, Object raw, Object interpreted) {
        VehicleObservationTrace.observe("JCRK01 / CYA", field, raw, interpreted);
    }

    private void readClimate(long now) {
        int rawLeft = readInt(GET_CLIMATE, CLIMATE_LEFT_TEMP);
        int rawRight = readInt(GET_CLIMATE, CLIMATE_RIGHT_TEMP);
        String left = climateTemp(rawLeft);
        String right = climateTemp(rawRight);
        if (left == null && right == null) clear(VehicleField.CLIMATE_TEMPERATURE);
        else if (left == null) put(VehicleField.CLIMATE_TEMPERATURE, "Der. " + right, now);
        else if (right == null || left.equals(right)) put(VehicleField.CLIMATE_TEMPERATURE, left, now);
        else put(VehicleField.CLIMATE_TEMPERATURE, "Izq. " + left + " · Der. " + right, now);

        int fan = readInt(GET_CLIMATE, CLIMATE_FAN_LEVEL);
        if (fan < 0) clear(VehicleField.CLIMATE_FAN);
        else put(VehicleField.CLIMATE_FAN, "Nivel " + fan, now);

        int ac = readInt(GET_CLIMATE, CLIMATE_AC);
        if (ac < 0) clear(VehicleField.CLIMATE_STATE);
        else put(VehicleField.CLIMATE_STATE, ac == 0 ? "A/C apagado" : "A/C OEM " + ac, now);
        lastRawClimate = "izquierda=" + rawLeft + " · derecha=" + rawRight
                + " · ventilador=" + fan + " · ac=" + ac;
        trace("climate.leftTemperature", rawLeft, left == null ? "desconocido" : left);
        trace("climate.rightTemperature", rawRight, right == null ? "desconocido" : right);
        trace("climate.fan", fan, fan < 0 ? "desconocido" : "Nivel " + fan);
        trace("climate.ac", ac, ac < 0 ? "desconocido" : ac == 0 ? "A/C apagado" : "A/C OEM " + ac);
    }

    private void readAlerts(long now, boolean includeMaintenanceInfo) {
        LinkedHashSet<MaintenanceAlert> active = new LinkedHashSet<>();
        List<String> information = new ArrayList<>();
        boolean known = false;
        int carId = readInt(GET_CAR_ID);
        trace("alerts.carId", carId, carId < 0 ? "desconocido" : "identificador publicado");
        if (carId >= 0) {
            for (int reportType = 0; reportType <= 2; reportType++) {
                int[] codes = readIntArray(GET_REPORT_ARRAY, carId, reportType);
                trace("alerts.reportType" + reportType, codes,
                        codes == null ? "no publicado" : codes.length + " código(s) publicados");
                if (codes == null) continue;
                known = true;
                for (int code : codes) {
                    // The exported API has no public dictionary for report codes. Zero is the
                    // observed empty sentinel, so it must never turn the UI orange by itself.
                    if (code > 0) active.add(new MaintenanceAlert("AVISO OEM", reportTypeName(reportType)
                            + " · código " + code, MaintenanceAlert.Severity.WARNING));
                }
            }
        }
        if (includeMaintenanceInfo) {
            known |= appendMaintenanceInfo(information, 0, "Intervalo");
            known |= appendMaintenanceInfo(information, 1, "Inspección");
            float voltage = readFloat(GET_EXTRA, EXTRA_BATTERY_VOLTAGE);
            trace("alerts.batteryVoltage", voltage,
                    validNumber(voltage) ? voltage + " V" : "desconocido");
            if (validNumber(voltage)) {
                known = true;
                information.add(String.format(Locale.ROOT, "Batería OEM: %.1f V", voltage));
            }
        }
        maintenance = new MaintenanceSnapshot(known, now, new ArrayList<>(active), information,
                !known ? "La unidad no ha publicado avisos OEM verificables"
                        : active.isEmpty() ? "Sin avisos OEM activos"
                        : active.size() + " aviso(s) OEM activo(s)");
    }

    private boolean appendMaintenanceInfo(List<String> information, int id, String label) {
        int mileage = readInt(GET_MAINTENANCE_MILEAGE, id);
        int days = readInt(GET_MAINTENANCE_DAYS, id);
        trace("maintenance." + label + ".mileage", mileage,
                mileage < 0 ? "desconocido" : mileage + " km");
        trace("maintenance." + label + ".days", days,
                days < 0 ? "desconocido" : days + " días");
        if (mileage < 0 && days < 0) return false;
        StringBuilder line = new StringBuilder(label).append(" OEM: ");
        if (mileage >= 0) line.append(mileage).append(" km");
        if (mileage >= 0 && days >= 0) line.append(" · ");
        if (days >= 0) line.append(days).append(" días");
        information.add(line.append(" · pendiente de validar unidad").toString());
        return true;
    }

    /** Registers only read-side observers verified in ICar/ICarCallback in the exported APK. */
    private void registerPassiveCallbacks() {
        IBinder service = currentCar();
        if (service == null) return;
        boolean all = false;
        try {
            all = transactCallback(service, REGISTER_CALLBACK, null);
            for (int id : new int[]{RT_SPEED, RT_CONSUMPTION, RT_RPM}) {
                all &= transactCallback(service, REGISTER_REALTIME, id);
            }
            callbacksRegistered = all;
            resetCallbackTimestamps();
            AppSessionLog.event("COCHE OEM", "callbacks pasivos Jancar=" + all
                    + " · realtime=velocidad/consumo/rpm");
        } catch (Exception error) {
            callbacksRegistered = false;
            AppSessionLog.event("COCHE OEM", "callbacks pasivos no disponibles · "
                    + error.getClass().getSimpleName());
        }
    }

    private void unregisterPassiveCallbacks() {
        IBinder service = currentCar();
        if (service == null || !callbacksRegistered) return;
        try {
            transactCallback(service, UNREGISTER_CALLBACK, null);
            for (int id : new int[]{RT_SPEED, RT_CONSUMPTION, RT_RPM}) {
                transactCallback(service, UNREGISTER_REALTIME, id);
            }
        } catch (Exception ignored) { }
        callbacksRegistered = false;
        resetCallbackTimestamps();
    }

    private boolean transactCallback(IBinder service, int transaction, Integer realtimeId)
            throws RemoteReadException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (realtimeId != null) data.writeInt(realtimeId);
            data.writeStrongBinder(callback);
            if (!service.transact(transaction, data, reply, 0)) {
                throw new RemoteReadException("callback transaction " + transaction + " rechazada");
            }
            reply.readException();
            return true;
        } catch (RemoteReadException error) {
            throw error;
        } catch (Exception error) {
            throw new RemoteReadException(error.getClass().getSimpleName());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callbackFresh(long updatedAt) {
        return callbacksRegistered && updatedAt > 0L
                && System.currentTimeMillis() - updatedAt <= CALLBACK_MAX_AGE_MS;
    }

    private void resetCallbackTimestamps() {
        callbackLastAt = 0L;
        callbackSpeedAt = 0L;
        callbackConsumptionAt = 0L;
        callbackRpmAt = 0L;
        callbackGearAt = 0L;
        callbackSeatbeltAt = 0L;
        callbackDoorsAt = 0L;
        callbackLightsAt = 0L;
        callbackOutsideTempAt = 0L;
        callbackHeadlightAt = 0L;
        callbackHandbrakeAt = 0L;
        callbackReverseAt = 0L;
    }

    private void onRealtimeCallback(int id, float value) {
        long now = System.currentTimeMillis();
        callbackLastAt = now;
        if (id == RT_SPEED) { callbackSpeed = value; callbackSpeedAt = now; }
        else if (id == RT_CONSUMPTION) { callbackConsumption = value; callbackConsumptionAt = now; }
        else if (id == RT_RPM) { callbackRpm = value; callbackRpmAt = now; }
        VehicleObservationTrace.observe("JCRK01 / CYA CALLBACK", "realtime." + id,
                value, validNumber(value) ? value : "desconocido");
    }

    private void onGearCallback(float value) {
        long now = System.currentTimeMillis();
        callbackLastAt = now;
        callbackGear = value;
        callbackGearAt = now;
        VehicleObservationTrace.observe("JCRK01 / CYA CALLBACK", "gear", value,
                gearText(value) == null ? "desconocido" : gearText(value));
    }

    private void onCallbackState(Integer doors, Integer lights, Boolean headlight,
                                 Boolean handbrake, Float belt, Boolean reverse,
                                 Integer outsideTemp) {
        long now = System.currentTimeMillis();
        callbackLastAt = now;
        if (doors != null) {
            callbackDoors = doors;
            callbackDoorsAt = now;
            trace("callback.doors", doors, doorsText(doors));
        }
        if (lights != null) {
            callbackLights = lights;
            callbackLightsAt = now;
            trace("callback.lights", lights, lightsText(lights, callbackHeadlight));
        }
        if (headlight != null) {
            callbackHeadlight = headlight;
            callbackHeadlightAt = now;
            trace("callback.headlight", headlight, headlight ? "activo" : "apagado");
        }
        if (handbrake != null) {
            callbackHandbrake = handbrake;
            callbackHandbrakeAt = now;
            trace("callback.parkingBrake", handbrake, handbrake ? "Activado" : "Liberado");
        }
        if (belt != null) {
            callbackSeatbelt = belt;
            callbackSeatbeltAt = now;
            trace("callback.seatbelt", belt,
                    belt == 0f ? "Abrochado" : belt == 1f ? "Sin abrochar" : "desconocido");
        }
        if (reverse != null) {
            callbackReverse = reverse;
            callbackReverseAt = now;
            trace("callback.reverse", reverse, reverse ? "Activa" : "Inactiva");
        }
        if (outsideTemp != null) {
            callbackOutsideTemp = outsideTemp;
            callbackOutsideTempAt = now;
            Double celsius = outsideTempCelsius(outsideTemp);
            trace("callback.outsideTemperature", outsideTemp,
                    celsius == null ? "desconocido" : celsius + " °C");
        }
    }

    /** Minimal local Binder implementation of the verified passive callback contract. */
    private final class PassiveCarCallback extends Binder {
        PassiveCarCallback() { attachInterface(null, CALLBACK_DESCRIPTOR); }

        @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }
            try { data.enforceInterface(CALLBACK_DESCRIPTOR); } catch (Exception ignored) { }
            switch (code) {
                case 0x04: // onHandbrakeChanged
                    onCallbackState(null, null, null, data.readInt() != 0, null, null, null);
                    break;
                case 0x05: // onDoorChanged
                    data.readInt();
                    onCallbackState(data.readInt(), null, null, null, null, null, null);
                    break;
                case 0x06: // onLightChanged
                    data.readInt();
                    onCallbackState(null, data.readInt(), null, null, null, null, null);
                    break;
                case 0x07: // onHeadLightChanged
                    onCallbackState(null, null, data.readInt() != 0, null, null, null, null);
                    break;
                case 0x09: // onOutsideTempChanged
                    onCallbackState(null, null, null, null, null, null, data.readInt());
                    break;
                case 0x0d: // onRealTimeInfoChanged
                    onRealtimeCallback(data.readInt(), data.readFloat());
                    break;
                case 0x0e: // onExtraStateChanged (seat belt is id 5)
                    int extraId = data.readInt();
                    float extraValue = data.readFloat();
                    if (extraId == EXTRA_GEAR) {
                        onGearCallback(extraValue);
                    } else if (extraId == EXTRA_SEAT_BELT) {
                        onCallbackState(null, null, null, null, extraValue, null, null);
                    }
                    break;
                case 0x13: // onMaintenanceChanged
                    int maintenanceId = data.readInt();
                    int maintenanceMileage = data.readInt();
                    int maintenanceDays = data.readInt();
                    VehicleObservationTrace.observe("JCRK01 / CYA CALLBACK",
                            "maintenance." + maintenanceId,
                            maintenanceMileage + "/" + maintenanceDays,
                            "mantenimiento publicado");
                    break;
                case 0x15: // onCarReportChanged
                    int carId = data.readInt();
                    int reportType = data.readInt();
                    int[] reports = data.createIntArray();
                    VehicleObservationTrace.observe("JCRK01 / CYA CALLBACK",
                            "report." + carId + "." + reportType, reports,
                            reports == null ? "no publicado" : reports.length + " código(s)");
                    break;
                case 0x18: // onFastReverseChanged
                    onCallbackState(null, null, null, null, null, data.readInt() != 0, null);
                    break;
                default:
                    // Registered callback can receive other read-only OEM events. Acknowledge
                    // them without interpreting or forwarding any payload.
                    break;
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    }

    private IBinder currentCar() { synchronized (lock) { return car; } }

    private static final class RemoteReadException extends Exception {
        RemoteReadException(String message) { super(message); }
    }

    private int readInt(int transaction) { return readInt(transaction, null, -1); }
    private int readInt(int transaction, Integer argument) { return readInt(transaction, argument, -1); }
    private int readInt(int transaction, Integer argument, int unavailable) {
        IBinder service = currentCar();
        if (service == null) return unavailable;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (argument != null) data.writeInt(argument);
            if (!service.transact(transaction, data, reply, 0)) return unavailable;
            reply.readException();
            return reply.readInt();
        } catch (Exception ignored) { return unavailable; }
        finally { reply.recycle(); data.recycle(); }
    }

    private float readFloat(int transaction, int argument) {
        IBinder service = currentCar();
        if (service == null) return -1f;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(argument);
            if (!service.transact(transaction, data, reply, 0)) return -1f;
            reply.readException();
            return reply.readFloat();
        } catch (Exception ignored) { return -1f; }
        finally { reply.recycle(); data.recycle(); }
    }

    private Boolean readBoolean(int transaction) {
        IBinder service = currentCar();
        if (service == null) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!service.transact(transaction, data, reply, 0)) return null;
            reply.readException();
            return reply.readInt() != 0;
        } catch (Exception ignored) { return null; }
        finally { reply.recycle(); data.recycle(); }
    }

    private String readString(int transaction) {
        IBinder service = currentCar();
        if (service == null) return "";
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!service.transact(transaction, data, reply, 0)) return "";
            reply.readException();
            String value = reply.readString();
            return value == null ? "" : value;
        } catch (Exception ignored) { return ""; }
        finally { reply.recycle(); data.recycle(); }
    }

    private int[] readIntArray(int transaction, int first, int second) {
        IBinder service = currentCar();
        if (service == null) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(first);
            data.writeInt(second);
            if (!service.transact(transaction, data, reply, 0)) return null;
            reply.readException();
            return reply.createIntArray();
        } catch (Exception ignored) { return null; }
        finally { reply.recycle(); data.recycle(); }
    }

    private void putNumber(VehicleField field, float value, long now) {
        if (!validNumber(value)) clear(field); else put(field, (double) value, now);
    }

    private static boolean validNumber(float value) { return Float.isFinite(value) && value >= 0f; }

    private void put(VehicleField field, Object value, long now) {
        synchronized (values) { values.put(field, VehicleValue.available(value, VehicleSource.JCRK01_CYA, now)); }
        diagnostics.recordVehicleObservation(field.key(), value, VehicleSource.JCRK01_CYA.name());
    }

    private void clear(VehicleField field) { synchronized (values) { values.remove(field); } }

    private static Double outsideTempCelsius(int raw) {
        if (raw < 0 || raw == 0xff) return null;
        int high = (raw & 0xff00) >> 8;
        int dot = (raw & 0xc0) >> 6;
        boolean fahrenheit = (raw & 0x01) != 0;
        double value = fahrenheit ? high - 60d : high - 100d;
        if (dot == 2) value += value < 0 ? -0.5d : 0.5d;
        return fahrenheit ? (value - 32d) * 5d / 9d : value;
    }

    private static String climateTemp(int raw) {
        if (raw < 0 || raw == 0xff) return null;
        if (raw == 0xf0) return "HI";
        if (raw == 0) return "LO";
        if (raw >= 20 && raw <= 80) return String.format(Locale.ROOT, "%.1f °C", raw / 2d);
        if (raw >= 100 && raw <= 200) return String.format(Locale.ROOT, "%.1f °C", ((raw / 2d) - 32d) * 5d / 9d);
        return null;
    }

    /** Exact values from IVICar.Gear in the exported Jancar SDK. */
    private static String gearText(float raw) {
        if (!Float.isFinite(raw)) return null;
        int id = Math.round(raw);
        if (Math.abs(raw - id) > 0.01f) return null;
        switch (id) {
            case 0: return "P";
            case 1: return "R";
            case 2: return "N";
            case 3: return "D";
            case 4: return "S";
            case 5: return "1";
            case 6: return "2";
            case 7: return "3";
            case 8: return "4";
            case 9: return "5";
            case 10: return "6";
            case 11: return "L";
            case 16: return "M1";
            case 17: return "M2";
            case 18: return "M3";
            case 19: return "M4";
            case 20: return "M5";
            case 21: return "M6";
            default: return null;
        }
    }

    private static String doorsText(int mask) {
        if (mask == 0) return "Cerradas";
        String[] labels = {"conductor", "acompañante", "trasera izq.", "trasera der.", "capó", "tapón", "maletero"};
        List<String> open = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) if ((mask & (1 << i)) != 0) open.add(labels[i]);
        return open.isEmpty() ? "Abierta" : String.join(", ", open) + " abierta";
    }

    private static String lightsText(int mask, Boolean headlight) {
        List<String> values = new ArrayList<>();
        if ((mask & (1 << 3)) != 0) values.add("Emergencia");
        if ((mask & (1 << 6)) != 0) values.add("Largas");
        if ((mask & (1 << 7)) != 0) values.add("Cruce");
        if ((mask & (1 << 2)) != 0) values.add("Posición");
        if ((mask & (1 << 8)) != 0) values.add("Diurnas");
        if ((mask & (1 << 4)) != 0) values.add("Antiniebla del.");
        if ((mask & (1 << 5)) != 0) values.add("Antiniebla tras.");
        if ((mask & (1 << 0)) != 0) values.add("Intermitente izq.");
        if ((mask & (1 << 1)) != 0) values.add("Intermitente der.");
        if (values.isEmpty() && Boolean.TRUE.equals(headlight)) values.add("Faros activos");
        return values.isEmpty() ? "Apagadas" : String.join(" · ", values);
    }

    private static String reportTypeName(int type) {
        if (type == 1) return "Start/Stop";
        if (type == 2) return "Climatización";
        return "Vehículo";
    }

    private void publishReport() { diagnostics.setPlatformVehicleReport(capabilityReport()); }

    private void notifyIfChanged() {
        StringBuilder current = new StringBuilder();
        synchronized (values) {
            for (VehicleField field : VehicleField.values()) {
                VehicleValue<?> value = values.get(field);
                if (value != null) current.append(field.name()).append('=').append(value.value()).append(';');
            }
        }
        current.append("alerts=").append(maintenance.active.hashCode());
        String raw = lastRawTelemetry + " | " + lastRawVehicle + " | " + lastRawClimate
                + " | avisos=" + maintenance.message;
        long now = System.currentTimeMillis();
        if (!raw.equals(lastLoggedRaw) && now - lastLoggedRawAt >= 5_000L) {
            lastLoggedRaw = raw;
            lastLoggedRawAt = now;
            AppSessionLog.event("COCHE OEM", raw);
        }
        if (current.toString().equals(lastUiSnapshot)) return;
        lastUiSnapshot = current.toString();
        if (onValuesChanged != null) main.post(onValuesChanged);
    }

    static final class MaintenanceSnapshot {
        final boolean known;
        final long timestamp;
        final List<MaintenanceAlert> active;
        final List<String> information;
        final String message;
        MaintenanceSnapshot(boolean known, long timestamp, List<MaintenanceAlert> active,
                            List<String> information, String message) {
            this.known = known; this.timestamp = timestamp;
            this.active = Collections.unmodifiableList(new ArrayList<>(active));
            this.information = Collections.unmodifiableList(new ArrayList<>(information));
            this.message = message;
        }
        static MaintenanceSnapshot unavailable(String message) {
            return new MaintenanceSnapshot(false, 0L, Collections.emptyList(), Collections.emptyList(), message);
        }
    }

    static final class MaintenanceAlert {
        enum Severity { INFO, WARNING }
        final String category, text;
        final Severity severity;
        MaintenanceAlert(String category, String text, Severity severity) {
            this.category = category; this.text = text; this.severity = severity;
        }
        @Override public boolean equals(Object object) {
            if (!(object instanceof MaintenanceAlert)) return false;
            MaintenanceAlert other = (MaintenanceAlert) object;
            return category.equals(other.category) && text.equals(other.text) && severity == other.severity;
        }
        @Override public int hashCode() { return (category + '\u0000' + text + severity).hashCode(); }
    }
}
