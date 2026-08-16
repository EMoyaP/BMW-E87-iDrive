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
    private volatile String connectionState = "Sonda aún no iniciada";
    private volatile String lastRaw = "sin lectura";
    private String lastSnapshot = "";
    private long lastLoggedAt;
    private volatile Dashboard latestDashboard;
    private volatile Light latestLight;
    private volatile Cabin latestCabin;

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

    String debugReport(boolean includeZero) {
        Dashboard dashboard = latestDashboard;
        Light light = latestLight;
        Cabin cabin = latestCabin;
        StringBuilder out = new StringBuilder(2_400);
        int[] count = {0};
        out.append("CAN OEM · DATOS OBSERVADOS EN VIVO\n");
        out.append("estado=").append(connectionState).append('\n');
        out.append("getDashBoardInfo / getCabinInfo / getLightInfo · ")
                .append(includeZero ? "incluyendo ceros" : "ocultando ceros")
                .append("\n\n");
        if (dashboard != null) {
            out.append("DASHBOARDINFO\n");
            appendInt(out, count, "gearShiftPosition", dashboard.gear, includeZero);
            appendInt(out, count, "odo", dashboard.odo, includeZero);
            appendInt(out, count, "cruisingRange", dashboard.range, includeZero);
            appendInt(out, count, "rotationRate", dashboard.rpm, includeZero);
            appendInt(out, count, "speed", dashboard.speed, includeZero);
            appendInt(out, count, "runningTime", dashboard.runningTime, includeZero);
            appendInt(out, count, "fuelTankage", dashboard.fuelTankage, includeZero);
            appendFloat(out, count, "fuel", dashboard.fuel, includeZero);
            appendFloat(out, count, "fuelPercentage", dashboard.fuelPercentage, includeZero);
            appendFloat(out, count, "avgFuelCont", dashboard.avgFuel, includeZero);
            appendFloat(out, count, "instFuelCont", dashboard.instantFuel, includeZero);
            appendFloat(out, count, "coolantTemp", dashboard.coolant, includeZero);
            appendFloat(out, count, "engineOilTemp", dashboard.oil, includeZero);
            appendFloat(out, count, "inletTemp", dashboard.inlet, includeZero);
            appendFloat(out, count, "ambientTemp", dashboard.ambient, includeZero);
            appendInt(out, count, "accPedal", dashboard.accel, includeZero);
            appendInt(out, count, "brakePedal", dashboard.brake, includeZero);
            appendInt(out, count, "throttlePos", dashboard.throttle, includeZero);
            out.append('\n');
        } else out.append("DASHBOARDINFO: sin lectura\n\n");
        if (cabin != null) {
            out.append("CABININFO\n");
            appendInt(out, count, "bonnetStatus", cabin.bonnet, includeZero);
            appendInt(out, count, "flDoorStatus", cabin.fl, includeZero);
            appendInt(out, count, "frDoorStatus", cabin.fr, includeZero);
            appendInt(out, count, "rlDoorStatus", cabin.rl, includeZero);
            appendInt(out, count, "rrDoorStatus", cabin.rr, includeZero);
            appendInt(out, count, "trunkStatus", cabin.trunk, includeZero);
            appendInt(out, count, "electricTrunk", cabin.electricTrunk, includeZero);
            appendInt(out, count, "electricTrunkDirection", cabin.electricTrunkDirection, includeZero);
            appendInt(out, count, "leftSafetyBelt", cabin.leftBelt, includeZero);
            appendInt(out, count, "rightSafetyBelt", cabin.rightBelt, includeZero);
            appendInt(out, count, "sunroofStatus", cabin.sunroof, includeZero);
            appendInt(out, count, "sunroofSunshadeStatus", cabin.sunshade, includeZero);
            appendInt(out, count, "flWindowStatus", cabin.flWindow, includeZero);
            appendInt(out, count, "frWindowStatus", cabin.frWindow, includeZero);
            appendInt(out, count, "rlWindowStatus", cabin.rlWindow, includeZero);
            appendInt(out, count, "rrWindowStatus", cabin.rrWindow, includeZero);
            appendInt(out, count, "lockCarStatus", cabin.lock, includeZero);
            appendInt(out, count, "seatUpDownMoveStatus", cabin.seatUpDown, includeZero);
            appendInt(out, count, "seatFrontRearMoveStatus", cabin.seatFrontRear, includeZero);
            appendInt(out, count, "seatHighLowMoveStatus", cabin.seatHighLow, includeZero);
            appendInt(out, count, "seatBackUpDownMoveStatus", cabin.seatBackUpDown, includeZero);
            appendInt(out, count, "seatBackFrontRearMoveStatus", cabin.seatBackFrontRear, includeZero);
            out.append('\n');
        } else out.append("CABININFO: sin lectura\n\n");
        if (light != null) {
            out.append("LIGHTINFO\n");
            appendInt(out, count, "leftDirectionIndicator", light.left, includeZero);
            appendInt(out, count, "rightDirectionIndicator", light.right, includeZero);
            appendInt(out, count, "emergencyFlasher", light.emergency, includeZero);
        } else out.append("LIGHTINFO: sin lectura\n");
        if (count[0] == 0) out.append("\nNo hay datos distintos de 0 en esta captura. Activa una señal del vehículo y espera la siguiente lectura.\n");
        out.append("\nLos nombres corresponden a los campos Parcel verificados en la APK OEM exportada; no son códigos CAN crudos.\n");
        return out.toString();
    }

    private static void appendInt(StringBuilder out, int[] count, String name, int value, boolean includeZero) {
        if (value == Integer.MIN_VALUE) {
            if (includeZero) out.append(name).append(" = NO EXPUESTO (Integer.MIN_VALUE)\n");
            return;
        }
        if (!includeZero && value == 0) return;
        out.append(name).append(" = ").append(value).append('\n');
        count[0]++;
    }

    private static void appendFloat(StringBuilder out, int[] count, String name, float value, boolean includeZero) {
        if (!Float.isFinite(value) || value == (float) Integer.MIN_VALUE) {
            if (includeZero) out.append(name).append(" = NO EXPUESTO (sentinel OEM)\n");
            return;
        }
        if (!includeZero && value == 0f) return;
        out.append(name).append(" = ").append(value).append('\n');
        count[0]++;
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
            latestDashboard = dashboard;
            if (dashboard != null) applyDashboard(dashboard, now); else clearDashboard();
            Light light = readLight();
            latestLight = light;
            if (light != null) applyLight(light, now); else clear(VehicleField.LIGHTS);
            Cabin cabin = readCabin();
            latestCabin = cabin;
            if (cabin != null) applyCabin(cabin, now); else clearCabin();
        } catch (Exception error) {
            latestDashboard = null;
            latestLight = null;
            latestCabin = null;
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
            c.trunk = reply.readInt(); c.electricTrunk = reply.readInt();
            c.electricTrunkDirection = reply.readInt();
            c.leftBelt = reply.readInt(); c.rightBelt = reply.readInt();
            c.sunroof = reply.readInt(); c.sunshade = reply.readInt();
            c.flWindow = reply.readInt(); c.frWindow = reply.readInt();
            c.rlWindow = reply.readInt(); c.rrWindow = reply.readInt();
            c.lock = reply.readInt();
            c.seatUpDown = reply.readInt(); c.seatFrontRear = reply.readInt();
            c.seatHighLow = reply.readInt(); c.seatBackUpDown = reply.readInt();
            c.seatBackFrontRear = reply.readInt();
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
        // Integer.MIN_VALUE is the OEM's unpublished sentinel. It must not become
        // "emergency" merely because it is non-zero.
        boolean leftKnown = l.left != Integer.MIN_VALUE;
        boolean rightKnown = l.right != Integer.MIN_VALUE;
        boolean emergencyKnown = l.emergency != Integer.MIN_VALUE;
        if (!leftKnown && !rightKnown && !emergencyKnown) {
            clear(VehicleField.LIGHTS);
            return;
        }
        if ((leftKnown && l.left != 0) || (rightKnown && l.right != 0)
                || (emergencyKnown && l.emergency != 0)) {
            boolean emergency = emergencyKnown && l.emergency != 0;
            boolean left = leftKnown && l.left != 0;
            boolean right = rightKnown && l.right != 0;
            String state = emergency ? "Emergencia"
                    : left && right ? "Intermitentes"
                    : left ? "Intermitente izq." : "Intermitente der.";
            put(VehicleField.LIGHTS, state, now);
        } else clear(VehicleField.LIGHTS);
    }

    private void applyCabin(Cabin c, long now) {
        // The exported Parcel fields are visible, but this unit does not publish
        // their 0/1 semantics in a public contract. Do not turn an unverified
        // value such as frDoorStatus=1 into a false dashboard warning. The
        // verified Jancar getters remain the UI source until a guided capture
        // identifies these CAN values on this exact vehicle.
        clear(VehicleField.DOORS);
        clear(VehicleField.SEATBELT);
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
        int bonnet, fl, fr, rl, rr, trunk, electricTrunk, electricTrunkDirection;
        int leftBelt, rightBelt, sunroof, sunshade, flWindow, frWindow, rlWindow, rrWindow, lock;
        int seatUpDown, seatFrontRear, seatHighLow, seatBackUpDown, seatBackFrontRear;
    }
    private static final class RemoteReadException extends Exception {
        RemoteReadException(String message) { super(message); }
    }
}
