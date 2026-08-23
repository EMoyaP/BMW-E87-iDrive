package com.ug.e87idrive;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Locale;
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
    private static final String CAN_LISTENER_DESCRIPTOR = "com.autoai.canbus.ICanBusListener";
    private static final String CAN_SERVICE_NAME = "CanBusManager";
    private static final long POLL_MS = 1_000L;

    // Verified in the exported ICanBus/ICanBusListener AIDL stubs.
    private static final int REGISTER_LISTENER = 1;
    private static final int UNREGISTER_LISTENER = 2;
    private static final int CALLBACK_HVAC_INFO = 1;
    private static final int CALLBACK_CABIN_INFO = 2;
    private static final int CALLBACK_RADAR_INFO = 3;
    private static final int CALLBACK_STEER_WHEEL_INFO = 4;
    private static final int CALLBACK_LIGHT_INFO = 5;
    private static final int CALLBACK_DASHBOARD_INFO = 6;

    // Transaction ids taken from ICanBus$Stub in the exported com.can.activity APK.
    private static final int GET_HVAC_INFO = 0x0d;
    private static final int GET_CABIN_INFO = 0x0e;
    private static final int GET_RADAR_INFO = 0x0f;
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
    private volatile Dashboard latestGetterDashboard;
    private volatile Dashboard latestCallbackDashboard;
    private volatile Hvac latestHvac;
    private volatile Light latestLight;
    private volatile Cabin latestCabin;
    private volatile Radar latestRadar;
    private volatile SteerWheel latestSteerWheel;
    private volatile long lastDashboardCallbackAt;
    private volatile long lastHvacCallbackAt;
    private volatile long lastLightCallbackAt;
    private volatile long lastCabinCallbackAt;
    private volatile long lastRadarCallbackAt;
    private volatile long lastSteerWheelCallbackAt;
    private volatile long callbackCount;
    private long pollCount;
    private volatile boolean listenerRegistered;
    private final CanListenerBinder passiveListener = new CanListenerBinder();

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
        unregisterPassiveListener();
        if (worker != null) worker.removeCallbacksAndMessages(null);
        main.post(() -> {
            if (bound) {
                try { context.unbindService(connection); } catch (Exception ignored) { }
            }
            bound = false;
        });
        synchronized (lock) { canBus = null; }
        clearCallbackState();
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
        out.append("listener pasivo=").append(listenerRegistered ? "registrado" : "no registrado")
                .append(" · callbacks=").append(callbackCount).append('\n');
        out.append("Método: callbacks ICanBusListener y getters getHvacInfo/getCabinInfo/getRadarInfo/")
                .append("getDashBoardInfo/getLightInfo ")
                .append("verificados en la APK exportada; sin comandos y sin escritura CAN/UART.\n");
        return out.toString();
    }

    String debugReport(boolean includeZero) {
        Dashboard dashboard = latestDashboard;
        Hvac hvac = latestHvac;
        Light light = latestLight;
        Cabin cabin = latestCabin;
        Radar radar = latestRadar;
        SteerWheel steerWheel = latestSteerWheel;
        StringBuilder out = new StringBuilder(5_000);
        int[] count = {0};
        out.append("CAN OEM · DATOS OBSERVADOS EN VIVO\n");
        out.append("estado=").append(connectionState).append('\n');
        out.append("listener=").append(listenerRegistered ? "registrado" : "no registrado")
                .append(" · callbacks=").append(callbackCount).append('\n');
        appendCallbackAge(out, "dashboard", lastDashboardCallbackAt);
        appendCallbackAge(out, "HVAC", lastHvacCallbackAt);
        appendCallbackAge(out, "radar/PDC", lastRadarCallbackAt);
        appendCallbackAge(out, "volante", lastSteerWheelCallbackAt);
        out.append("ICanBusListener + getters HVAC/Cabin/Radar/Dashboard/Light · ")
                .append(includeZero ? "incluyendo ceros" : "ocultando ceros")
                .append("\n\n");
        Dashboard callbackDashboard = latestCallbackDashboard;
        if (callbackDashboard != null) {
            out.append("DASHBOARDINFO · CALLBACK PASIVO\n");
            appendDashboard(out, count, callbackDashboard, includeZero);
            out.append('\n');
        } else out.append("DASHBOARDINFO · CALLBACK PASIVO: aún no recibido\n\n");
        Dashboard getterDashboard = latestGetterDashboard;
        if (getterDashboard != null) {
            out.append("DASHBOARDINFO · GETTER (diagnóstico/respaldo)\n");
            appendDashboard(out, count, getterDashboard, includeZero);
            out.append('\n');
        } else out.append("DASHBOARDINFO · GETTER: sin lectura\n\n");
        if (dashboard != null) {
            out.append("DASHBOARD SELECCIONADO INTERNAMENTE = ")
                    .append(callbackDashboard != null ? "CALLBACK PASIVO" : "GETTER").append("\n\n");
        }
        if (hvac != null) {
            out.append("HVACINFO\n");
            appendInt(out, count, "airOnOff", hvac.airOnOff, includeZero);
            appendInt(out, count, "acOnOff", hvac.acOnOff, includeZero);
            appendInt(out, count, "frontTempUnit", hvac.frontTempUnit, includeZero);
            appendInt(out, count, "frontRightTempUnit", hvac.frontRightTempUnit, includeZero);
            appendInt(out, count, "outsideTempUnit", hvac.outsideTempUnit, includeZero);
            appendFloat(out, count, "frontLeftTemp", hvac.frontLeftTemp, includeZero);
            appendFloat(out, count, "frontRightTemp", hvac.frontRightTemp, includeZero);
            appendFloat(out, count, "outsideTemp", hvac.outsideTemp, includeZero);
            appendFloat(out, count, "innerSideTemp", hvac.innerSideTemp, includeZero);
            appendInt(out, count, "isCommunicationFailure", hvac.communicationFailure, includeZero);
            appendInt(out, count, "windIntensity", hvac.windIntensity, includeZero);
            appendInt(out, count, "maxWindLevel", hvac.maxWindLevel, includeZero);
            appendInt(out, count, "frontWindLevel", hvac.frontWindLevel, includeZero);
            appendInt(out, count, "frontRightWindLevel", hvac.frontRightWindLevel, includeZero);
            appendInt(out, count, "power", hvac.power, includeZero);
            appendInt(out, count, "climateOnOff", hvac.climateOnOff, includeZero);
            appendInt(out, count, "autoAc", hvac.autoAc, includeZero);
            out.append('\n');
        } else out.append("HVACINFO: sin lectura\n\n");
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
        if (radar != null) {
            out.append("\nRADARINFO / PDC\n");
            appendInt(out, count, "frontRadarOnOff", radar.frontOn, includeZero);
            appendInt(out, count, "rearRadarOnOff", radar.rearOn, includeZero);
            appendInt(out, count, "leftRightRadarOnOff", radar.sideOn, includeZero);
            appendInt(out, count, "distanceUnit", radar.distanceUnit, includeZero);
            appendInt(out, count, "frontLeftDistance", radar.frontLeft, includeZero);
            appendInt(out, count, "frontLeftMidDistance", radar.frontLeftMid, includeZero);
            appendInt(out, count, "frontRightMidDistance", radar.frontRightMid, includeZero);
            appendInt(out, count, "frontRightDistance", radar.frontRight, includeZero);
            appendInt(out, count, "rearLeftDistance", radar.rearLeft, includeZero);
            appendInt(out, count, "rearLeftMidDistance", radar.rearLeftMid, includeZero);
            appendInt(out, count, "rearRightMidDistance", radar.rearRightMid, includeZero);
            appendInt(out, count, "rearRightDistance", radar.rearRight, includeZero);
            appendInt(out, count, "radarShowOnOff", radar.show, includeZero);
            appendFloat(out, count, "distanceValue", radar.distanceValue, includeZero);
        } else out.append("\nRADARINFO / PDC: sin lectura\n");
        if (steerWheel != null) {
            out.append("\nSTEERWHEELINFO\n");
            appendInt(out, count, "eps", steerWheel.eps, includeZero);
            appendInt(out, count, "omega", steerWheel.omega, includeZero);
        }
        if (count[0] == 0) out.append("\nNo hay datos distintos de 0 en esta captura. Activa una señal del vehículo y espera la siguiente lectura.\n");
        out.append("\nLos nombres corresponden a los campos Parcel verificados en la APK OEM exportada; no son códigos CAN crudos.\n");
        return out.toString();
    }

    private static void appendCallbackAge(StringBuilder out, String name, long timestamp) {
        if (timestamp <= 0L) return;
        out.append("último callback ").append(name).append(" hace ")
                .append(Math.max(0L, System.currentTimeMillis() - timestamp)).append(" ms\n");
    }

    private static void appendDashboard(StringBuilder out, int[] count, Dashboard dashboard,
                                        boolean includeZero) {
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
            listenerRegistered = false;
            clearCallbackState();
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
                registerPassiveListener(service);
            } finally {
                reply.recycle();
                data.recycle();
            }
            connectionState = "CanBusManager conectado · sólo lectura · listener "
                    + (listenerRegistered ? "activo" : "no disponible");
            AppSessionLog.event("CAN OEM", connectionState);
            poll();
        } catch (Exception error) {
            connectionState = "Binder CAN rechazado: " + error.getClass().getSimpleName();
            AppSessionLog.event("CAN OEM", connectionState);
            publishReport();
        }
    }

    private void registerPassiveListener(IBinder service) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CAN_DESCRIPTOR);
            data.writeStrongBinder(passiveListener.asBinder());
            if (!service.transact(REGISTER_LISTENER, data, reply, 0)) {
                throw new RemoteReadException("registerListener rechazado");
            }
            reply.readException();
            listenerRegistered = true;
            AppSessionLog.event("CAN OEM", "ICanBusListener pasivo registrado");
        } catch (Exception error) {
            listenerRegistered = false;
            AppSessionLog.event("CAN OEM", "listener pasivo no disponible: "
                    + error.getClass().getSimpleName());
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void unregisterPassiveListener() {
        IBinder service = currentCanBus();
        if (!listenerRegistered || service == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CAN_DESCRIPTOR);
            data.writeStrongBinder(passiveListener.asBinder());
            if (service.transact(UNREGISTER_LISTENER, data, reply, 0)) reply.readException();
        } catch (Exception error) {
            AppSessionLog.event("CAN OEM", "unregisterListener: "
                    + error.getClass().getSimpleName());
        } finally {
            listenerRegistered = false;
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Local implementation of the exact listener declared by the exported OEM APK.
     * It only receives Binder callbacks; it exposes no method capable of transmitting CAN.
     */
    private final class CanListenerBinder extends android.os.Binder implements IInterface {
        CanListenerBinder() { attachInterface(this, CAN_LISTENER_DESCRIPTOR); }

        @Override public IBinder asBinder() { return this; }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CAN_LISTENER_DESCRIPTOR);
                return true;
            }
            if (code < 1 || code > 17) return super.onTransact(code, data, reply, flags);
            try {
                data.enforceInterface(CAN_LISTENER_DESCRIPTOR);
                callbackCount++;
                if (code == CALLBACK_HVAC_INFO) {
                    Hvac hvac = data.readInt() == 0 ? null : readHvacParcel(data);
                    dispatchHvacCallback(hvac);
                } else if (code == CALLBACK_DASHBOARD_INFO) {
                    Dashboard dashboard = data.readInt() == 0 ? null : readDashboardParcel(data);
                    dispatchDashboardCallback(dashboard);
                } else if (code == CALLBACK_RADAR_INFO) {
                    Radar radar = data.readInt() == 0 ? null : readRadarParcel(data);
                    dispatchRadarCallback(radar);
                } else if (code == CALLBACK_STEER_WHEEL_INFO) {
                    SteerWheel steerWheel = data.readInt() == 0 ? null : readSteerWheelParcel(data);
                    dispatchSteerWheelCallback(steerWheel);
                } else if (code == CALLBACK_LIGHT_INFO) {
                    Light light = data.readInt() == 0 ? null : readLightParcel(data);
                    dispatchLightCallback(light);
                } else if (code == CALLBACK_CABIN_INFO) {
                    Cabin cabin = data.readInt() == 0 ? null : readCabinParcel(data);
                    dispatchCabinCallback(cabin);
                } else {
                    AppSessionLog.event("CAN OEM CALLBACK", "tx=" + code + " · "
                            + callbackName(code) + " · recibido sin decodificar");
                    VehicleObservationTrace.observe("CAN OEM", "callback.tx" + code,
                            "recibido", "callback pasivo no interpretado");
                }
                if (reply != null) reply.writeNoException();
            } catch (Exception error) {
                AppSessionLog.event("CAN OEM", "callback tx=" + code + " no interpretable: "
                        + error.getClass().getSimpleName());
                // The OEM proxy is synchronous. Return a successful empty reply so
                // diagnostic decoding can never destabilise its service process.
                if (reply != null) reply.writeNoException();
            }
            return true;
        }
    }

    private void dispatchHvacCallback(Hvac hvac) {
        Handler target = worker;
        if (target == null) return;
        target.post(() -> {
            if (!running) return;
            long now = System.currentTimeMillis();
            latestHvac = hvac;
            lastHvacCallbackAt = now;
            traceHvac("hvac.callback", hvac);
            AppSessionLog.event("CAN OEM CALLBACK", hvac == null
                    ? "tx=1 · HvacInfo=null" : "tx=1 · " + hvacRaw(hvac));
            if (hvac == null) clearHvac(); else applyHvac(hvac, now);
            publishReport();
            notifyIfChanged();
        });
    }

    private void dispatchDashboardCallback(Dashboard dashboard) {
        Handler target = worker;
        if (target == null) return;
        target.post(() -> {
            if (!running) return;
            long now = System.currentTimeMillis();
            latestCallbackDashboard = dashboard;
            latestDashboard = dashboard != null ? dashboard : latestGetterDashboard;
            lastDashboardCallbackAt = now;
            traceDashboard("dashboard.callback", dashboard);
            if (dashboard != null) {
                lastRaw = "callback · " + dashboardRaw(dashboard);
                applyDashboard(dashboard, now, true);
                applyGetterFallback(latestGetterDashboard, now);
            } else if (latestGetterDashboard != null) {
                applyDashboard(latestGetterDashboard, now, false);
            } else clearDashboard();
            AppSessionLog.event("CAN OEM CALLBACK", dashboard == null
                    ? "dashboard=null" : dashboardRaw(dashboard));
            publishReport();
            notifyIfChanged();
        });
    }

    private void dispatchLightCallback(Light light) {
        Handler target = worker;
        if (target == null) return;
        target.post(() -> {
            if (!running) return;
            long now = System.currentTimeMillis();
            latestLight = light;
            lastLightCallbackAt = now;
            traceLight("light.callback", light);
            AppSessionLog.event("CAN OEM CALLBACK", light == null
                    ? "tx=5 · LightInfo=null" : "tx=5 · " + lightRaw(light));
            if (light == null) clear(VehicleField.LIGHTS); else applyLight(light, now);
            notifyIfChanged();
        });
    }

    private void dispatchCabinCallback(Cabin cabin) {
        Handler target = worker;
        if (target == null) return;
        target.post(() -> {
            if (!running) return;
            long now = System.currentTimeMillis();
            latestCabin = cabin;
            lastCabinCallbackAt = now;
            traceCabin("cabin.callback", cabin);
            AppSessionLog.event("CAN OEM CALLBACK", cabin == null
                    ? "tx=2 · CabinInfo=null" : "tx=2 · " + cabinRaw(cabin));
            if (cabin == null) clearCabin(); else applyCabin(cabin, now);
            notifyIfChanged();
        });
    }

    private void dispatchRadarCallback(Radar radar) {
        Handler target = worker;
        if (target == null) return;
        target.post(() -> {
            if (!running) return;
            long now = System.currentTimeMillis();
            latestRadar = radar;
            lastRadarCallbackAt = now;
            traceRadar("radar.callback", radar);
            AppSessionLog.event("CAN OEM CALLBACK", radar == null
                    ? "tx=3 · RadarInfo=null" : "tx=3 · " + radarRaw(radar));
            if (radar == null) clear(VehicleField.PDC); else applyRadar(radar, now);
            publishReport();
            notifyIfChanged();
        });
    }

    private void dispatchSteerWheelCallback(SteerWheel steerWheel) {
        Handler target = worker;
        if (target == null) return;
        target.post(() -> {
            if (!running) return;
            latestSteerWheel = steerWheel;
            lastSteerWheelCallbackAt = System.currentTimeMillis();
            traceSteerWheel("steer.callback", steerWheel);
            AppSessionLog.event("CAN OEM CALLBACK", steerWheel == null
                    ? "tx=4 · SteerWheelInfo=null" : "tx=4 · eps=" + steerWheel.eps
                    + " · omega=" + steerWheel.omega);
            publishReport();
        });
    }

    private void poll() {
        if (!running || currentCanBus() == null) return;
        long now = System.currentTimeMillis();
        pollCount++;
        try {
            Dashboard dashboard = readDashboard();
            latestGetterDashboard = dashboard;
            Dashboard callbackDashboard = latestCallbackDashboard;
            latestDashboard = callbackDashboard != null ? callbackDashboard : dashboard;
            traceDashboard("dashboard.getter", dashboard);
            if (callbackDashboard != null) {
                applyDashboard(callbackDashboard, now, true);
                applyGetterFallback(dashboard, now);
            } else if (dashboard != null) {
                applyDashboard(dashboard, now, false);
            } else clearDashboard();
            Light light = readLight();
            if (lastLightCallbackAt == 0L) latestLight = light;
            traceLight("light.getter", light);
            if (lastLightCallbackAt == 0L) {
                if (light != null) applyLight(light, now); else clear(VehicleField.LIGHTS);
            }
            Cabin cabin = readCabin();
            if (lastCabinCallbackAt == 0L) latestCabin = cabin;
            traceCabin("cabin.getter", cabin);
            if (lastCabinCallbackAt == 0L) {
                if (cabin != null) applyCabin(cabin, now); else clearCabin();
            }
            // HVAC and the eight PDC distances are diagnostic-heavy Parcels. A
            // 2 s getter fallback is enough when this firmware emits no callback
            // and avoids needless Binder/GC load on the RK3326 unit.
            if ((pollCount & 1L) == 0L) {
                Hvac hvac = null;
                Radar radar = null;
                try {
                    hvac = readHvac();
                    if (lastHvacCallbackAt == 0L) latestHvac = hvac;
                    traceHvac("hvac.getter", hvac);
                    if (lastHvacCallbackAt == 0L) {
                        if (hvac != null) applyHvac(hvac, now); else clearHvac();
                    }
                } catch (Exception error) {
                    AppSessionLog.event("CAN OEM HVAC", "getter no disponible: "
                            + error.getClass().getSimpleName());
                }
                try {
                    radar = readRadar();
                    if (lastRadarCallbackAt == 0L) latestRadar = radar;
                    traceRadar("radar.getter", radar);
                    if (lastRadarCallbackAt == 0L) {
                        if (radar != null) applyRadar(radar, now); else clear(VehicleField.PDC);
                    }
                } catch (Exception error) {
                    AppSessionLog.event("CAN OEM RADAR", "getter no disponible: "
                            + error.getClass().getSimpleName());
                }
                AppSessionLog.event("CAN OEM GETTERS", "dashboard="
                        + (dashboard == null ? "null" : dashboardRaw(dashboard))
                        + " || cabin=" + (cabin == null ? "null" : cabinRaw(cabin))
                        + " || light=" + (light == null ? "null" : lightRaw(light))
                        + " || hvac=" + (hvac == null ? "null" : hvacRaw(hvac))
                        + " || radar=" + (radar == null ? "null" : radarRaw(radar)));
            }
        } catch (Exception error) {
            latestDashboard = latestCallbackDashboard;
            if (latestCallbackDashboard == null) clearValues();
            AppSessionLog.event("CAN OEM", "lectura fallida: " + error.getClass().getSimpleName());
        }
        publishReport();
        notifyIfChanged();
        Handler target = worker;
        if (running && target != null) target.postDelayed(this::poll, POLL_MS);
    }

    private Hvac readHvac() throws RemoteReadException {
        Parcel reply = transactNoArgs(GET_HVAC_INFO);
        try {
            if (reply.readInt() == 0) return null;
            return readHvacParcel(reply);
        } finally { reply.recycle(); }
    }

    /** Exact HvacInfo CREATOR order recovered from the exported OEM CAN APK. */
    private static Hvac readHvacParcel(Parcel parcel) {
        int[] head = new int[24];
        for (int index = 0; index < head.length; index++) head[index] = parcel.readInt();
        float[] temperatures = new float[16];
        for (int index = 0; index < temperatures.length; index++) {
            temperatures[index] = parcel.readFloat();
        }
        int[] tail = new int[81];
        for (int index = 0; index < tail.length; index++) tail[index] = parcel.readInt();
        Hvac h = new Hvac();
        h.airOnOff = head[0];
        h.acOnOff = head[2];
        h.frontTempUnit = head[20];
        h.frontRightTempUnit = head[21];
        h.outsideTempUnit = head[23];
        h.frontLeftTemp = temperatures[10];
        h.frontRightTemp = temperatures[11];
        h.outsideTemp = temperatures[14];
        h.innerSideTemp = temperatures[15];
        h.communicationFailure = tail[0];
        h.windIntensity = tail[6];
        h.maxWindLevel = tail[8];
        h.frontWindLevel = tail[9];
        h.frontRightWindLevel = tail[10];
        h.power = tail[39];
        h.climateOnOff = tail[41];
        h.autoAc = tail[77];
        return h;
    }

    private Radar readRadar() throws RemoteReadException {
        Parcel reply = transactNoArgs(GET_RADAR_INFO);
        try {
            if (reply.readInt() == 0) return null;
            return readRadarParcel(reply);
        } finally { reply.recycle(); }
    }

    /** Exact RadarInfo CREATOR order recovered from the exported OEM CAN APK. */
    private static Radar readRadarParcel(Parcel parcel) {
        int[] raw = new int[28];
        for (int index = 0; index < raw.length; index++) raw[index] = parcel.readInt();
        Radar r = new Radar();
        r.frontOn = raw[0]; r.rearOn = raw[1]; r.sideOn = raw[2]; r.distanceUnit = raw[3];
        r.frontLeft = raw[8]; r.frontLeftMid = raw[9];
        r.frontRightMid = raw[10]; r.frontRight = raw[11];
        r.rearLeft = raw[12]; r.rearLeftMid = raw[13];
        r.rearRightMid = raw[14]; r.rearRight = raw[15];
        r.leftFront = raw[16]; r.leftFrontMid = raw[17];
        r.leftRearMid = raw[18]; r.leftRear = raw[19];
        r.rightFront = raw[20]; r.rightFrontMid = raw[21];
        r.rightRearMid = raw[22]; r.rightRear = raw[23];
        r.show = raw[24]; r.radarVolume = raw[27];
        r.distanceValue = parcel.readFloat();
        return r;
    }

    private static SteerWheel readSteerWheelParcel(Parcel parcel) {
        SteerWheel steerWheel = new SteerWheel();
        steerWheel.eps = parcel.readInt();
        steerWheel.omega = parcel.readInt();
        return steerWheel;
    }

    private Dashboard readDashboard() throws RemoteReadException {
        Parcel reply = transactNoArgs(GET_DASHBOARD_INFO);
        try {
            if (reply.readInt() == 0) return null;
            Dashboard d = readDashboardParcel(reply);
            lastRaw = "getter · " + dashboardRaw(d);
            return d;
        } finally { reply.recycle(); }
    }

    private static Dashboard readDashboardParcel(Parcel parcel) {
        Dashboard d = new Dashboard();
        d.gear = parcel.readInt();
        d.odo = parcel.readInt();
        d.range = parcel.readInt();
        d.rpm = parcel.readInt();
        d.speed = parcel.readInt();
        d.runningTime = parcel.readInt();
        d.fuelTankage = parcel.readInt();
        d.fuel = parcel.readFloat();
        d.fuelPercentage = parcel.readFloat();
        d.avgFuel = parcel.readFloat();
        d.instantFuel = parcel.readFloat();
        d.coolant = parcel.readFloat();
        d.oil = parcel.readFloat();
        d.inlet = parcel.readFloat();
        d.ambient = parcel.readFloat();
        d.accel = parcel.readInt();
        d.brake = parcel.readInt();
        d.throttle = parcel.readInt();
        return d;
    }

    private static String dashboardRaw(Dashboard d) {
        return "gear=" + d.gear + "(" + String.valueOf(gearLabel(d.gear)) + ")"
                + " · odo=" + d.odo + " · speed=" + d.speed + " · range=" + d.range
                + " · fuelTankage=" + d.fuelTankage + " · fuel=" + d.fuel
                + " · fuelPct=" + d.fuelPercentage + " · avgFuel=" + d.avgFuel
                + " · instantFuel=" + d.instantFuel + " · ambient=" + d.ambient
                + " · coolant=" + d.coolant + " · oil=" + d.oil + " · inlet=" + d.inlet
                + " · rpm=" + d.rpm;
    }

    private static String hvacRaw(Hvac h) {
        return "HvacInfo air=" + h.airOnOff + " · ac=" + h.acOnOff
                + " · tempL=" + h.frontLeftTemp + " · tempR=" + h.frontRightTemp
                + " · outside=" + h.outsideTemp + " · inside=" + h.innerSideTemp
                + " · unitOutside=" + h.outsideTempUnit + " · commFailure="
                + h.communicationFailure + " · wind=" + h.windIntensity
                + " · windL=" + h.frontWindLevel + " · windR=" + h.frontRightWindLevel
                + " · power=" + h.power + " · climate=" + h.climateOnOff
                + " · autoAc=" + h.autoAc;
    }

    private static String radarRaw(Radar r) {
        return "RadarInfo frontOn=" + r.frontOn + " · rearOn=" + r.rearOn
                + " · sideOn=" + r.sideOn + " · show=" + r.show
                + " · FL=" + r.frontLeft + "/" + r.frontLeftMid
                + " · FR=" + r.frontRightMid + "/" + r.frontRight
                + " · RL=" + r.rearLeft + "/" + r.rearLeftMid
                + " · RR=" + r.rearRightMid + "/" + r.rearRight
                + " · sideL=" + r.leftFront + "/" + r.leftFrontMid + "/"
                + r.leftRearMid + "/" + r.leftRear
                + " · sideR=" + r.rightFront + "/" + r.rightFrontMid + "/"
                + r.rightRearMid + "/" + r.rightRear
                + " · unit=" + r.distanceUnit + " · distanceValue=" + r.distanceValue;
    }

    private static String lightRaw(Light l) {
        return "LightInfo left=" + l.left + " · right=" + l.right
                + " · emergency=" + l.emergency;
    }

    private static String cabinRaw(Cabin c) {
        return "CabinInfo bonnet=" + c.bonnet + " · doors=" + c.fl + "/" + c.fr
                + "/" + c.rl + "/" + c.rr + " · trunk=" + c.trunk
                + " · belts=" + c.leftBelt + "/" + c.rightBelt
                + " · lock=" + c.lock;
    }

    private static String callbackName(int code) {
        switch (code) {
            case 1: return "HvacInfo";
            case 2: return "CabinInfo";
            case 3: return "RadarInfo";
            case 4: return "SteerWheelInfo";
            case 5: return "LightInfo";
            case 6: return "DashboardInfo";
            case 7: return "TirePressureInfo";
            case 8: return "DiagnosticTroubleCode";
            case 9: return "BackView";
            case 10: return "RightView";
            case 11: return "Panoramic";
            case 12: return "ParkingAssist";
            case 13: return "AudioInfo";
            case 14: return "PropertyList";
            case 15: return "Diagnose";
            case 16: return "AlarmList";
            case 17: return "CarPropertyValues";
            default: return "desconocido";
        }
    }

    private Light readLight() throws RemoteReadException {
        Parcel reply = transactNoArgs(GET_LIGHT_INFO);
        try {
            if (reply.readInt() == 0) return null;
            return readLightParcel(reply);
        } finally { reply.recycle(); }
    }

    private static Light readLightParcel(Parcel parcel) {
        Light l = new Light();
        l.left = parcel.readInt();
        l.right = parcel.readInt();
        l.emergency = parcel.readInt();
        return l;
    }

    private Cabin readCabin() throws RemoteReadException {
        Parcel reply = transactNoArgs(GET_CABIN_INFO);
        try {
            if (reply.readInt() == 0) return null;
            return readCabinParcel(reply);
        } finally { reply.recycle(); }
    }

    private static Cabin readCabinParcel(Parcel parcel) {
        Cabin c = new Cabin();
        c.bonnet = parcel.readInt();
        c.fl = parcel.readInt(); c.fr = parcel.readInt(); c.rl = parcel.readInt(); c.rr = parcel.readInt();
        c.trunk = parcel.readInt(); c.electricTrunk = parcel.readInt();
        c.electricTrunkDirection = parcel.readInt();
        c.leftBelt = parcel.readInt(); c.rightBelt = parcel.readInt();
        c.sunroof = parcel.readInt(); c.sunshade = parcel.readInt();
        c.flWindow = parcel.readInt(); c.frWindow = parcel.readInt();
        c.rlWindow = parcel.readInt(); c.rrWindow = parcel.readInt();
        c.lock = parcel.readInt();
        c.seatUpDown = parcel.readInt(); c.seatFrontRear = parcel.readInt();
        c.seatHighLow = parcel.readInt(); c.seatBackUpDown = parcel.readInt();
        c.seatBackFrontRear = parcel.readInt();
        return c;
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

    private void applyDashboard(Dashboard d, long now, boolean callback) {
        if (callback) {
            putIntInRange(VehicleField.SPEED, d.speed, 0, 300, now);
            putIntInRange(VehicleField.RPM, d.rpm, 0, 9_000, now);
        } else {
            // The physical logs proved that these getter fields are not live:
            // speed ramps up to 254 and RPM is a sentinel. Keep those two in
            // USB DEBUG, but never paint them as vehicle truth.
            clear(VehicleField.SPEED);
            clear(VehicleField.RPM);
        }
        // The P/R guided capture physically confirmed the getter enum on this
        // exact unit (0=P, 1=R). Other enum labels come from GearShiftPosition
        // in the same exported OEM APK and remain logged if not emitted.
        String gear = gearLabel(d.gear);
        if (gear == null) clear(VehicleField.GEAR); else put(VehicleField.GEAR, gear, now);
        if ("R".equals(gear)) put(VehicleField.REVERSE, "Activa", now);
        else clear(VehicleField.REVERSE);
        putIntInRange(VehicleField.RANGE, d.range, 0, 2_500, now);
        putFloatInRange(VehicleField.CONSUMPTION, d.avgFuel, 0f, 100f, now);
        if (isFloatInRange(d.ambient, -60f, 100f)) {
            put(VehicleField.EXTERIOR_TEMPERATURE, (double) d.ambient, now);
        } else if (latestHvac != null) {
            putHvacOutsideTemperature(latestHvac, now);
        } else clear(VehicleField.EXTERIOR_TEMPERATURE);
        putFloatInRange(VehicleField.ENGINE_TEMPERATURE, d.coolant, -40f, 180f, now);
    }

    private void applyGetterFallback(Dashboard d, long now) {
        if (d == null) return;
        if (!get(VehicleField.RANGE).isAvailable()) putIntInRange(VehicleField.RANGE, d.range, 0, 2_500, now);
        if (!get(VehicleField.CONSUMPTION).isAvailable()) putFloatInRange(VehicleField.CONSUMPTION, d.avgFuel, 0f, 100f, now);
        if (!get(VehicleField.EXTERIOR_TEMPERATURE).isAvailable()) putFloatInRange(VehicleField.EXTERIOR_TEMPERATURE, d.ambient, -60f, 100f, now);
        if (!get(VehicleField.ENGINE_TEMPERATURE).isAvailable()) putFloatInRange(VehicleField.ENGINE_TEMPERATURE, d.coolant, -40f, 180f, now);
    }

    private void applyHvac(Hvac h, long now) {
        putHvacOutsideTemperature(h, now);

        boolean leftValid = isFloatInRange(h.frontLeftTemp, 10f, 40f);
        boolean rightValid = isFloatInRange(h.frontRightTemp, 10f, 40f);
        if (leftValid || rightValid) {
            String temperature = leftValid && rightValid
                    ? String.format(Locale.ROOT, "I %.1f °C · D %.1f °C",
                    h.frontLeftTemp, h.frontRightTemp)
                    : String.format(Locale.ROOT, "%.1f °C",
                    leftValid ? h.frontLeftTemp : h.frontRightTemp);
            put(VehicleField.CLIMATE_TEMPERATURE, temperature, now);
        } else clear(VehicleField.CLIMATE_TEMPERATURE);

        int fan = isIntInRange(h.frontWindLevel, 0, 20) ? h.frontWindLevel
                : isIntInRange(h.windIntensity, 0, 20) ? h.windIntensity : Integer.MIN_VALUE;
        if (fan != Integer.MIN_VALUE) put(VehicleField.CLIMATE_FAN, "Nivel " + fan, now);
        else clear(VehicleField.CLIMATE_FAN);

        Integer active = firstKnownSwitch(h.climateOnOff, h.power, h.airOnOff, h.acOnOff);
        if (active == null) {
            clear(VehicleField.CLIMATE_STATE);
        } else if (active == 0) {
            put(VehicleField.CLIMATE_STATE, "Climatización apagada", now);
        } else {
            put(VehicleField.CLIMATE_STATE, h.acOnOff > 0 ? "A/C activo" : "Climatización activa", now);
        }
    }

    private void putHvacOutsideTemperature(Hvac h, long now) {
        if (isFloatInRange(h.outsideTemp, -60f, 100f)) {
            put(VehicleField.EXTERIOR_TEMPERATURE, (double) h.outsideTemp, now);
        } else if (!get(VehicleField.EXTERIOR_TEMPERATURE).isAvailable()) {
            clear(VehicleField.EXTERIOR_TEMPERATURE);
        }
    }

    private void clearHvac() {
        synchronized (values) {
            values.remove(VehicleField.CLIMATE_TEMPERATURE);
            values.remove(VehicleField.CLIMATE_FAN);
            values.remove(VehicleField.CLIMATE_STATE);
            if (!isFloatInRange(latestDashboard == null ? Float.NaN : latestDashboard.ambient,
                    -60f, 100f)) {
                values.remove(VehicleField.EXTERIOR_TEMPERATURE);
            }
        }
    }

    private void applyRadar(Radar r, long now) {
        Integer active = firstKnownSwitch(r.show, r.frontOn, r.rearOn, r.sideOn);
        if (active == null || active == 0) clear(VehicleField.PDC);
        else put(VehicleField.PDC, "Radar activo", now);
    }

    private static Integer firstKnownSwitch(int... values) {
        boolean sawZero = false;
        for (int value : values) {
            if (value == 1) return 1;
            if (value == 0) sawZero = true;
            // -1, 255, Integer.MIN_VALUE and any other proprietary enum are
            // diagnostic-only until a physical capture confirms their meaning.
        }
        return sawZero ? 0 : null;
    }

    private static boolean isIntInRange(int value, int minimum, int maximum) {
        return value >= minimum && value <= maximum;
    }

    private static boolean isFloatInRange(float value, float minimum, float maximum) {
        return Float.isFinite(value) && value != (float) Integer.MIN_VALUE
                && value >= minimum && value <= maximum;
    }

    private void traceDashboard(String group, Dashboard d) {
        trace(group, "gearShiftPosition", d == null ? null : d.gear,
                d == null ? "no publicado" : d.gear + " · " + String.valueOf(gearLabel(d.gear)));
        trace(group, "odo", d == null ? null : d.odo, d == null ? "no publicado" : d.odo);
        trace(group, "cruisingRange", d == null ? null : d.range, d == null ? "no publicado" : d.range + " km");
        trace(group, "rotationRate", d == null ? null : d.rpm, d == null ? "no publicado" : d.rpm + " rpm");
        trace(group, "speed", d == null ? null : d.speed, d == null ? "no publicado" : d.speed + " km/h");
        trace(group, "runningTime", d == null ? null : d.runningTime, d == null ? "no publicado" : d.runningTime);
        trace(group, "fuelTankage", d == null ? null : d.fuelTankage, d == null ? "no publicado" : d.fuelTankage);
        trace(group, "fuel", d == null ? null : d.fuel, d == null ? "no publicado" : d.fuel);
        trace(group, "fuelPercentage", d == null ? null : d.fuelPercentage, d == null ? "no publicado" : d.fuelPercentage + " %");
        trace(group, "avgFuelCont", d == null ? null : d.avgFuel, d == null ? "no publicado" : d.avgFuel + " l/100 km");
        trace(group, "instFuelCont", d == null ? null : d.instantFuel, d == null ? "no publicado" : d.instantFuel + " l/100 km");
        trace(group, "coolantTemp", d == null ? null : d.coolant, d == null ? "no publicado" : d.coolant + " °C");
        trace(group, "engineOilTemp", d == null ? null : d.oil, d == null ? "no publicado" : d.oil + " °C");
        trace(group, "inletTemp", d == null ? null : d.inlet, d == null ? "no publicado" : d.inlet + " °C");
        trace(group, "ambientTemp", d == null ? null : d.ambient, d == null ? "no publicado" : d.ambient + " °C");
        trace(group, "accPedal", d == null ? null : d.accel, d == null ? "no publicado" : d.accel);
        trace(group, "brakePedal", d == null ? null : d.brake, d == null ? "no publicado" : d.brake);
        trace(group, "throttlePos", d == null ? null : d.throttle, d == null ? "no publicado" : d.throttle);
    }

    private void traceHvac(String group, Hvac h) {
        trace(group, "airOnOff", h == null ? null : h.airOnOff, state(h == null ? null : h.airOnOff));
        trace(group, "acOnOff", h == null ? null : h.acOnOff, state(h == null ? null : h.acOnOff));
        trace(group, "frontTempUnit", h == null ? null : h.frontTempUnit,
                h == null ? "no publicado" : h.frontTempUnit);
        trace(group, "outsideTempUnit", h == null ? null : h.outsideTempUnit,
                h == null ? "no publicado" : h.outsideTempUnit);
        trace(group, "frontLeftTemp", h == null ? null : h.frontLeftTemp,
                h == null ? "no publicado" : h.frontLeftTemp + " unidad OEM");
        trace(group, "frontRightTemp", h == null ? null : h.frontRightTemp,
                h == null ? "no publicado" : h.frontRightTemp + " unidad OEM");
        trace(group, "outsideTemp", h == null ? null : h.outsideTemp,
                h == null ? "no publicado" : h.outsideTemp + " unidad OEM");
        trace(group, "innerSideTemp", h == null ? null : h.innerSideTemp,
                h == null ? "no publicado" : h.innerSideTemp + " unidad OEM");
        trace(group, "isCommunicationFailure", h == null ? null : h.communicationFailure,
                h == null ? "no publicado" : h.communicationFailure);
        trace(group, "windIntensity", h == null ? null : h.windIntensity,
                h == null ? "no publicado" : h.windIntensity);
        trace(group, "frontWindLevel", h == null ? null : h.frontWindLevel,
                h == null ? "no publicado" : h.frontWindLevel);
        trace(group, "frontRightWindLevel", h == null ? null : h.frontRightWindLevel,
                h == null ? "no publicado" : h.frontRightWindLevel);
        trace(group, "power", h == null ? null : h.power, state(h == null ? null : h.power));
        trace(group, "climateOnOff", h == null ? null : h.climateOnOff,
                state(h == null ? null : h.climateOnOff));
        trace(group, "autoAc", h == null ? null : h.autoAc, state(h == null ? null : h.autoAc));
    }

    private void traceRadar(String group, Radar r) {
        trace(group, "frontRadarOnOff", r == null ? null : r.frontOn, state(r == null ? null : r.frontOn));
        trace(group, "rearRadarOnOff", r == null ? null : r.rearOn, state(r == null ? null : r.rearOn));
        trace(group, "leftRightRadarOnOff", r == null ? null : r.sideOn, state(r == null ? null : r.sideOn));
        trace(group, "radarShowOnOff", r == null ? null : r.show, state(r == null ? null : r.show));
        trace(group, "frontLeftDistance", r == null ? null : r.frontLeft,
                r == null ? "no publicado" : r.frontLeft);
        trace(group, "frontLeftMidDistance", r == null ? null : r.frontLeftMid,
                r == null ? "no publicado" : r.frontLeftMid);
        trace(group, "frontRightMidDistance", r == null ? null : r.frontRightMid,
                r == null ? "no publicado" : r.frontRightMid);
        trace(group, "frontRightDistance", r == null ? null : r.frontRight,
                r == null ? "no publicado" : r.frontRight);
        trace(group, "rearLeftDistance", r == null ? null : r.rearLeft,
                r == null ? "no publicado" : r.rearLeft);
        trace(group, "rearLeftMidDistance", r == null ? null : r.rearLeftMid,
                r == null ? "no publicado" : r.rearLeftMid);
        trace(group, "rearRightMidDistance", r == null ? null : r.rearRightMid,
                r == null ? "no publicado" : r.rearRightMid);
        trace(group, "rearRightDistance", r == null ? null : r.rearRight,
                r == null ? "no publicado" : r.rearRight);
        trace(group, "distanceValue", r == null ? null : r.distanceValue,
                r == null ? "no publicado" : r.distanceValue);
    }

    private void traceSteerWheel(String group, SteerWheel steerWheel) {
        trace(group, "eps", steerWheel == null ? null : steerWheel.eps,
                steerWheel == null ? "no publicado" : steerWheel.eps);
        trace(group, "omega", steerWheel == null ? null : steerWheel.omega,
                steerWheel == null ? "no publicado" : steerWheel.omega);
    }

    private void traceLight(String group, Light l) {
        trace(group, "leftDirectionIndicator", l == null ? null : l.left,
                l == null ? "no publicado" : l.left == Integer.MIN_VALUE ? "desconocido" : l.left == 0 ? "apagado" : "activo");
        trace(group, "rightDirectionIndicator", l == null ? null : l.right,
                l == null ? "no publicado" : l.right == Integer.MIN_VALUE ? "desconocido" : l.right == 0 ? "apagado" : "activo");
        trace(group, "emergencyFlasher", l == null ? null : l.emergency,
                l == null ? "no publicado" : l.emergency == Integer.MIN_VALUE ? "desconocido" : l.emergency == 0 ? "apagado" : "activo");
    }

    private void traceCabin(String group, Cabin c) {
        trace(group, "bonnetStatus", c == null ? null : c.bonnet, state(c == null ? null : c.bonnet));
        trace(group, "flDoorStatus", c == null ? null : c.fl, state(c == null ? null : c.fl));
        trace(group, "frDoorStatus", c == null ? null : c.fr, state(c == null ? null : c.fr));
        trace(group, "rlDoorStatus", c == null ? null : c.rl, state(c == null ? null : c.rl));
        trace(group, "rrDoorStatus", c == null ? null : c.rr, state(c == null ? null : c.rr));
        trace(group, "trunkStatus", c == null ? null : c.trunk, state(c == null ? null : c.trunk));
        trace(group, "leftSafetyBelt", c == null ? null : c.leftBelt, state(c == null ? null : c.leftBelt));
        trace(group, "rightSafetyBelt", c == null ? null : c.rightBelt, state(c == null ? null : c.rightBelt));
        trace(group, "lockCarStatus", c == null ? null : c.lock, state(c == null ? null : c.lock));
    }

    private static String state(Integer value) {
        if (value == null) return "no publicado";
        if (value == Integer.MIN_VALUE) return "desconocido";
        return value == 0 ? "inactivo/cerrado (semántica pendiente)" : "activo/abierto (semántica pendiente)";
    }

    private static void trace(String group, String field, Object raw, Object interpreted) {
        VehicleObservationTrace.observe("CAN OEM", group + "." + field, raw, interpreted);
    }

    private void clearDashboard() {
        synchronized (values) {
            values.remove(VehicleField.SPEED);
            values.remove(VehicleField.GEAR);
            values.remove(VehicleField.REVERSE);
            values.remove(VehicleField.RANGE);
            values.remove(VehicleField.CONSUMPTION);
            values.remove(VehicleField.RPM);
            if (!isFloatInRange(latestHvac == null ? Float.NaN : latestHvac.outsideTemp,
                    -60f, 100f)) {
                values.remove(VehicleField.EXTERIOR_TEMPERATURE);
            }
            values.remove(VehicleField.ENGINE_TEMPERATURE);
        }
    }

    private void clearCallbackState() {
        latestCallbackDashboard = null;
        latestGetterDashboard = null;
        latestDashboard = null;
        latestHvac = null;
        latestLight = null;
        latestCabin = null;
        latestRadar = null;
        latestSteerWheel = null;
        lastDashboardCallbackAt = 0L;
        lastHvacCallbackAt = 0L;
        lastLightCallbackAt = 0L;
        lastCabinCallbackAt = 0L;
        lastRadarCallbackAt = 0L;
        lastSteerWheelCallbackAt = 0L;
        pollCount = 0L;
        listenerRegistered = false;
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

    private void putIntInRange(VehicleField field, int value, int minimum, int maximum, long now) {
        if (value >= minimum && value <= maximum) put(field, (double) value, now);
        else clear(field);
    }

    private void putFloatInRange(VehicleField field, float value, float minimum, float maximum,
                                 long now) {
        if (Float.isFinite(value) && value >= minimum && value <= maximum) {
            put(field, (double) value, now);
        } else clear(field);
    }

    /** Exact GearShiftPosition enum recovered from the exported CAN APK. */
    private static String gearLabel(int raw) {
        switch (raw) {
            case 0: return "P";
            case 1: return "R";
            case 2: return "N";
            case 3: return "D";
            case 4: return "S";
            case 5: return "M";
            case 6: return "L";
            case 7: return "1";
            case 8: return "2";
            case 9: return "3";
            case 10: return "4";
            case 11: return "5";
            case 12: return "6";
            default: return null;
        }
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
    private static final class Hvac {
        int airOnOff, acOnOff, frontTempUnit, frontRightTempUnit, outsideTempUnit;
        int communicationFailure, windIntensity, maxWindLevel, frontWindLevel;
        int frontRightWindLevel, power, climateOnOff, autoAc;
        float frontLeftTemp, frontRightTemp, outsideTemp, innerSideTemp;
    }
    private static final class Radar {
        int frontOn, rearOn, sideOn, distanceUnit;
        int frontLeft, frontLeftMid, frontRightMid, frontRight;
        int rearLeft, rearLeftMid, rearRightMid, rearRight;
        int leftFront, leftFrontMid, leftRearMid, leftRear;
        int rightFront, rightFrontMid, rightRearMid, rightRear;
        int show, radarVolume;
        float distanceValue;
    }
    private static final class SteerWheel { int eps, omega; }
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
