package com.ug.e87idrive;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Read-only bridge to the exact CAN service exported by this unit.
 *
 * The exported APK exposes ICanUI -> CanBusManager -> ICanBus. We use only the
 * getters verified in that APK and bind without BIND_AUTO_CREATE, so this
 * provider never starts the CAN service, writes CAN/UART, or changes OEM state.
 */
final class CanbusServiceProvider implements VehicleDataProvider {
    static final String PACKAGE_NAME = "com.can.activity";
    private static final String SERVICE_CLASS = "com.can.ui.CanPopWind";
    private static final String ACTION = "com.jancar.canservice";
    private static final String UI_DESCRIPTOR = "com.autoai.canbus.ICanUI";
    private static final String CAN_DESCRIPTOR = "com.autoai.canbus.ICanBus";
    private static final String CAN_SERVICE_NAME = "CanBusManager";
    private static final long POLL_MS = 1_000L;

    // Transaction ids taken from ICanBus$Stub in the exported com.can.activity APK.
    private static final int GET_CABIN_INFO = 0x0e;
    private static final int GET_LIGHT_INFO = 0x11;
    private static final int GET_DASHBOARD_INFO = 0x12;

    private final Context context;
    private final DiagnosticEngine diagnostics;
    private final Runnable onValuesChanged;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final EnumMap<VehicleField, VehicleValue<?>> values =
            new EnumMap<>(VehicleField.class);
    private final Object lock = new Object();
    private HandlerThread thread;
    private Handler worker;
    private IBinder canBus;
    private boolean running;
    private boolean bound;
    private String connectionState = "Sonda aún no iniciada";
    private String lastRaw = "sin lectura";
    private String lastSnapshot = "";
    private long lastLoggedAt;

    CanbusServiceProvider(Context context, DiagnosticEngine diagnostics, Runnable onValuesChanged) {
        this.context = context.getApplicationContext();
        this.diagnostics = diagnostics;
        this.onValuesChanged = onValuesChanged;
    }

    @Override public synchronized void start() {
        if (running) return;
        running = true;
        AppSessionLog.event("CAN OEM", "Iniciando lectura pasiva de CanBusManager");
        thread = new HandlerThread("e87-can-service-readonly");
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
        synchronized (lock) { canBus = null; }
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

    String capabilityReport() {
        StringBuilder out = new StringBuilder(900);
        out.append("CANBUS OEM · ICanUI / CanBusManager · LECTURA PASIVA\n");
        out.append("paquete=").append(PACKAGE_NAME).append('\n');
        out.append("servicio=").append(SERVICE_CLASS).append(" · acción=").append(ACTION).append('\n');
        out.append("estado=").append(connectionState).append('\n');
        out.append("raw dashboard=").append(lastRaw).append('\n');
        out.append("Método: getDashBoardInfo/getCabinInfo/getLightInfo verificados en la APK exportada; ");
        out.append("sin callbacks, sin comandos y sin escritura CAN/UART.\n");
        return out.toString();
    }

    private void bindExistingService() {
        synchronized (this) { if (!running || bound) return; }
        Intent intent = new Intent(ACTION)
                .setComponent(new ComponentName(PACKAGE_NAME, SERVICE_CLASS));
        try {
            // Do not create or start the OEM CAN service from this third-party app.
            boolean result = context.bindService(intent, connection, 0);
            synchronized (this) { bound = result; }
            connectionState = result ? "Esperando CanBusManager persistente" : "Servicio CAN no disponible";
            AppSessionLog.event("CAN OEM", "bind existente=" + result);
        } catch (Exception error) {
            connectionState = "No se pudo enlazar: " + error.getClass().getSimpleName();
            AppSessionLog.event("CAN OEM", connectionState);
        }
        publishReport();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            Handler target = worker;
            if (target != null) target.post(() -> acceptUiService(service));
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            synchronized (lock) { canBus = null; }
            clearValues();
            connectionState = "CanBusManager desconectado";
            AppSessionLog.event("CAN OEM", connectionState);
            publishReport();
        }

        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
    };

    private void acceptUiService(IBinder uiService) {
        if (!running || uiService == null) return;
        try {
            if (!UI_DESCRIPTOR.equals(uiService.getInterfaceDescriptor())) {
                connectionState = "Binder UI no compatible: " + uiService.getInterfaceDescriptor();
                publishReport();
                return;
            }
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(UI_DESCRIPTOR);
                data.writeString(CAN_SERVICE_NAME);
                if (!uiService.transact(1, data, reply, 0)) throw new RemoteReadException("getCanService rechazado");
                reply.readException();
                IBinder service = reply.readStrongBinder();
                if (service == null || !CAN_DESCRIPTOR.equals(service.getInterfaceDescriptor())) {
                    throw new RemoteReadException("CanBusManager no compatible");
                }
                synchronized (lock) { canBus = service; }
            } finally {
                reply.recycle();
                data.recycle();
            }
            connectionState = "CanBusManager conectado · sólo lectura";
            AppSessionLog.event("CAN OEM", connectionState);
            poll();
        } catch (Exception error) {
            connectionState = "Binder CAN rechazado: " + error.getClass().getSimpleName();
            AppSessionLog.event("CAN OEM", connectionState);
            publishReport();
        }
    }

    private void poll() {
        if (!running || currentCanBus() == null) return;
        long now = System.currentTimeMillis();
        try {
            Dashboard dashboard = readDashboard();
            if (dashboard != null) applyDashboard(dashboard, now); else clearDashboard();
            Light light = readLight();
            if (light != null) applyLight(light, now); else clear(VehicleField.LIGHTS);
            Cabin cabin = readCabin();
            if (cabin != null) applyCabin(cabin, now); else clearCabin();
        } catch (Exception error) {
            clearValues();
            AppSessionLog.event("CAN OEM", "lectura fallida: " + error.getClass().getSimpleName());
        }
        publishReport();
        notifyIfChanged();
        Handler target = worker;
        if (running && target != null) target.postDelayed(this::poll, POLL_MS);
    }

    private Dashboard readDashboard() throws RemoteReadException {
        Parcel reply = transactNoArgs(GET_DASHBOARD_INFO);
        try {
            if (reply.readInt() == 0) return null;
            Dashboard d = new Dashboard();
            d.gear = reply.readInt();
            d.odo = reply.readInt();
            d.range = reply.readInt();
            d.rpm = reply.readInt();
            d.speed = reply.readInt();
            d.runningTime = reply.readInt();
            d.fuelTankage = reply.readInt();
            d.fuel = reply.readFloat();
            d.fuelPercentage = reply.readFloat();
            d.avgFuel = reply.readFloat();
            d.instantFuel = reply.readFloat();
            d.coolant = reply.readFloat();
            d.oil = reply.readFloat();
            d.inlet = reply.readFloat();
            d.ambient = reply.readFloat();
            d.accel = reply.readInt();
            d.brake = reply.readInt();
            d.throttle = reply.readInt();
            lastRaw = "gear=" + d.gear + " · odo=" + d.odo + " · speed=" + d.speed
                    + " · range=" + d.range + " · fuelTankage=" + d.fuelTankage
                    + " · fuel=" + d.fuel + " · fuelPct=" + d.fuelPercentage
                    + " · avgFuel=" + d.avgFuel + " · instantFuel=" + d.instantFuel
                    + " · ambient=" + d.ambient + " · coolant=" + d.coolant
                    + " · oil=" + d.oil + " · inlet=" + d.inlet + " · rpm=" + d.rpm;
            return d;
        } finally { reply.recycle(); }
    }

    private Light readLight() throws RemoteReadException {
        Parcel reply = transactNoArgs(GET_LIGHT_INFO);
        try {
            if (reply.readInt() == 0) return null;
            Light l = new Light();
            l.left = reply.readInt();
            l.right = reply.readInt();
            l.emergency = reply.readInt();
            return l;
        } finally { reply.recycle(); }
    }

    private Cabin readCabin() throws RemoteReadException {
        Parcel reply = transactNoArgs(GET_CABIN_INFO);
        try {
            if (reply.readInt() == 0) return null;
            Cabin c = new Cabin();
            c.bonnet = reply.readInt();
            c.fl = reply.readInt(); c.fr = reply.readInt(); c.rl = reply.readInt(); c.rr = reply.readInt();
            c.trunk = reply.readInt(); c.electricTrunk = reply.readInt(); reply.readInt();
            c.leftBelt = reply.readInt(); c.rightBelt = reply.readInt();
            // sunroof, shade and four window states precede the lock state.
            for (int i = 0; i < 6; i++) reply.readInt();
            c.lock = reply.readInt();
            // Five seat movement fields complete CabinInfo; they are not used here.
            for (int i = 0; i < 5; i++) reply.readInt();
            return c;
        } finally { reply.recycle(); }
    }

    private Parcel transactNoArgs(int transaction) throws RemoteReadException {
        IBinder service = currentCanBus();
        if (service == null) throw new RemoteReadException("servicio ausente");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CAN_DESCRIPTOR);
            if (!service.transact(transaction, data, reply, 0)) {
                throw new RemoteReadException("transacción " + transaction + " rechazada");
            }
            reply.readException();
            data.recycle();
            return reply;
        } catch (RemoteReadException error) {
            data.recycle(); reply.recycle(); throw error;
        } catch (Exception error) {
            data.recycle(); reply.recycle();
            throw new RemoteReadException(error.getClass().getSimpleName());
        }
    }

    private void applyDashboard(Dashboard d, long now) {
        putNumber(VehicleField.SPEED, d.speed, now);
        putNumber(VehicleField.RANGE, d.range, now);
        putNumber(VehicleField.CONSUMPTION, d.avgFuel, now);
        putNumber(VehicleField.RPM, d.rpm, now);
        putNumber(VehicleField.EXTERIOR_TEMPERATURE, d.ambient, now);
        putNumber(VehicleField.ENGINE_TEMPERATURE, d.coolant, now);
    }

    private void clearDashboard() {
        synchronized (values) {
            values.remove(VehicleField.SPEED);
            values.remove(VehicleField.RANGE);
            values.remove(VehicleField.CONSUMPTION);
            values.remove(VehicleField.RPM);
            values.remove(VehicleField.EXTERIOR_TEMPERATURE);
            values.remove(VehicleField.ENGINE_TEMPERATURE);
        }
    }

    private void applyLight(Light l, long now) {
        if (l.left != 0 || l.right != 0 || l.emergency != 0) {
            String state = l.emergency != 0 ? "Emergencia"
                    : l.left != 0 && l.right != 0 ? "Intermitentes"
                    : l.left != 0 ? "Intermitente izq." : "Intermitente der.";
            put(VehicleField.LIGHTS, state, now);
        } else clear(VehicleField.LIGHTS);
    }

    private void applyCabin(Cabin c, long now) {
        int open = (c.bonnet != 0 ? 1 : 0) + (c.fl != 0 ? 1 : 0) + (c.fr != 0 ? 1 : 0)
                + (c.rl != 0 ? 1 : 0) + (c.rr != 0 ? 1 : 0) + (c.trunk != 0 ? 1 : 0);
        put(VehicleField.DOORS, open == 0 ? "Cerradas" : open == 1 ? "1 abierta" : open + " abiertas", now);
        if (c.leftBelt == 0 && c.rightBelt == 0) put(VehicleField.SEATBELT, "Abrochado", now);
        else if (c.leftBelt != 0 || c.rightBelt != 0) put(VehicleField.SEATBELT, "Sin abrochar", now);
        else clear(VehicleField.SEATBELT);
    }

    private void clearCabin() {
        synchronized (values) {
            values.remove(VehicleField.DOORS);
            values.remove(VehicleField.SEATBELT);
        }
    }

    private void putNumber(VehicleField field, int value, long now) {
        if (value >= 0) put(field, (double) value, now); else clear(field);
    }

    private void putNumber(VehicleField field, float value, long now) {
        if (Float.isFinite(value) && value >= 0f) put(field, (double) value, now); else clear(field);
    }

    private void put(VehicleField field, Object value, long now) {
        synchronized (values) { values.put(field, VehicleValue.available(value, VehicleSource.OEM_CAN_SERVICE, now)); }
        diagnostics.recordVehicleObservation(field.key(), value, VehicleSource.OEM_CAN_SERVICE.name());
    }

    private void clear(VehicleField field) { synchronized (values) { values.remove(field); } }
    private void clearValues() { synchronized (values) { values.clear(); } }
    private IBinder currentCanBus() { synchronized (lock) { return canBus; } }
    private void publishReport() { diagnostics.setPlatformVehicleReport(capabilityReport()); }

    private void notifyIfChanged() {
        StringBuilder current = new StringBuilder();
        synchronized (values) {
            for (Map.Entry<VehicleField, VehicleValue<?>> entry : values.entrySet()) {
                current.append(entry.getKey()).append('=').append(entry.getValue().value()).append(';');
            }
        }
        if (current.toString().equals(lastSnapshot)) return;
        lastSnapshot = current.toString();
        if (onValuesChanged != null) main.post(onValuesChanged);
        long now = System.currentTimeMillis();
        if (lastLoggedAt == 0L || now - lastLoggedAt >= 5_000L) {
            lastLoggedAt = now;
            AppSessionLog.event("CAN OEM", lastRaw);
        }
    }

    private static final class Dashboard {
        int gear, odo, range, rpm, speed, runningTime, fuelTankage, accel, brake, throttle;
        float fuel, fuelPercentage, avgFuel, instantFuel, coolant, oil, inlet, ambient;
    }
    private static final class Light { int left, right, emergency; }
    private static final class Cabin {
        int bonnet, fl, fr, rl, rr, trunk, electricTrunk, leftBelt, rightBelt, lock;
    }
    private static final class RemoteReadException extends Exception {
        RemoteReadException(String message) { super(message); }
    }
}
