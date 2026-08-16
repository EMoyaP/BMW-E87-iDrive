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
 * exported firmware. It talks only to documented getter transactions of the
 * existing persistent CarService. No callback is registered, no command is
 * sent to CAN/MCU/UART and no OEM configuration provider is queried.
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

    // IVICar.RealTimeInfo.Id
    private static final int RT_SPEED = 1;
    private static final int RT_CONSUMPTION = 2;
    private static final int RT_RPM = 3;
    // IVICar.ExtraState.Id
    private static final int EXTRA_BATTERY_VOLTAGE = 0;
    private static final int EXTRA_REMAIN_FUEL_DISTANCE = 2;
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
        main.post(() -> {
            if (bound) {
                try { context.unbindService(connection); } catch (Exception ignored) { }
            }
            bound = false;
        });
        synchronized (lock) { car = null; }
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
        out.append("Método: getters AIDL validados en la APK exportada; sin callbacks, sin provider CAN, sin escrituras.\n");
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
        float speed = readFloat(GET_REALTIME, RT_SPEED);
        float consumption = readFloat(GET_REALTIME, RT_CONSUMPTION);
        float rpm = readFloat(GET_REALTIME, RT_RPM);
        float range = readFloat(GET_EXTRA, EXTRA_REMAIN_FUEL_DISTANCE);
        int rawTemp = readInt(GET_OUTSIDE_TEMP);
        lastRawTelemetry = "velocidad=" + speed + " · consumo=" + consumption
                + " · rpm=" + rpm + " · autonomía=" + range
                + " · tempExterior=0x" + Integer.toHexString(rawTemp) + " (" + rawTemp + ")";
        putNumber(VehicleField.SPEED, speed, now);
        putNumber(VehicleField.CONSUMPTION, consumption, now);
        putNumber(VehicleField.RPM, rpm, now);
        putNumber(VehicleField.RANGE, range, now);
        Double temp = outsideTempCelsius(rawTemp);
        if (temp == null) clear(VehicleField.EXTERIOR_TEMPERATURE);
        else put(VehicleField.EXTERIOR_TEMPERATURE, temp, now);
    }

    private void readVehicleState(long now) {
        int doors = readInt(GET_DOORS);
        if (doors < 0) clear(VehicleField.DOORS);
        else put(VehicleField.DOORS, doorsText(doors), now);

        int lights = readInt(GET_LIGHTS);
        Boolean headlight = readBoolean(GET_HEADLIGHT);
        if (lights < 0) clear(VehicleField.LIGHTS);
        else put(VehicleField.LIGHTS, lightsText(lights, headlight), now);

        int brake = readInt(GET_HANDBRAKE);
        if (brake == 0) put(VehicleField.PARKING_BRAKE, "Liberado", now);
        else if (brake == 1) put(VehicleField.PARKING_BRAKE, "Activado", now);
        else clear(VehicleField.PARKING_BRAKE);

        float belt = readFloat(GET_EXTRA, EXTRA_SEAT_BELT);
        if (belt == 0f) put(VehicleField.SEATBELT, "Abrochado", now);
        else if (belt == 1f) put(VehicleField.SEATBELT, "Sin abrochar", now);
        else clear(VehicleField.SEATBELT);

        Boolean reverse = readBoolean(GET_FAST_REVERSE);
        if (reverse == null) clear(VehicleField.REVERSE);
        else put(VehicleField.REVERSE, reverse ? "Activa" : "Inactiva", now);
        lastRawVehicle = "puertas=" + doors + " · luces=" + lights
                + " · faros=" + headlight + " · freno=" + brake
                + " · cinturón=" + belt + " · marchaAtrás=" + reverse;
    }

    private static final int GET_FAST_REVERSE = IS_FAST_REVERSE;

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
    }

    private void readAlerts(long now, boolean includeMaintenanceInfo) {
        LinkedHashSet<MaintenanceAlert> active = new LinkedHashSet<>();
        List<String> information = new ArrayList<>();
        boolean known = false;
        int carId = readInt(GET_CAR_ID);
        if (carId >= 0) {
            for (int reportType = 0; reportType <= 2; reportType++) {
                int[] codes = readIntArray(GET_REPORT_ARRAY, carId, reportType);
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
        if (mileage < 0 && days < 0) return false;
        StringBuilder line = new StringBuilder(label).append(" OEM: ");
        if (mileage >= 0) line.append(mileage).append(" km");
        if (mileage >= 0 && days >= 0) line.append(" · ");
        if (days >= 0) line.append(days).append(" días");
        information.add(line.append(" · pendiente de validar unidad").toString());
        return true;
    }

    private IBinder currentCar() { synchronized (lock) { return car; } }

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
