package com.ug.e87idrive;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Read-only client for the Bluetooth service contract recovered from the OEM
 * com.jancar.btservice APK exported by this unit. It only calls the two getters
 * used to identify the currently linked phone; it never pairs, connects or
 * changes Bluetooth state.
 */
final class JancarBluetoothProvider {
    interface Listener { void onChanged(); }

    static final class Snapshot {
        final String deviceName;
        final String address;
        final int state;
        final boolean serviceAvailable;
        final long updatedAt;

        Snapshot(String deviceName, String address, int state,
                 boolean serviceAvailable, long updatedAt) {
            this.deviceName = clean(deviceName);
            this.address = clean(address);
            this.state = state;
            this.serviceAvailable = serviceAvailable;
            this.updatedAt = updatedAt;
        }

        boolean hasDevice() { return state == 1 && !deviceName.isEmpty(); }
    }

    private static final String SERVICE_PACKAGE = "com.jancar.btservice";
    private static final String SERVICE_CLASS =
            "com.jancar.btservice.bluetooth.BluetoothService";
    private static final String SERVICE_ACTION =
            "com.jancar.btservice.action.bluetooth";
    private static final String SERVICE_DESCRIPTOR =
            "com.jancar.btservice.bluetooth.IBluetooth";
    private static final String EXEC_CALLBACK_DESCRIPTOR =
            "com.jancar.btservice.bluetooth.IBluetoothExecCallback";
    private static final String LINK_CALLBACK_DESCRIPTOR =
            "com.jancar.btservice.bluetooth.IBluetoothLinkDeviceCallback";

    // Exact transaction identifiers recovered from the exported OEM APK.
    private static final int TRANSACTION_GET_BLUETOOTH_STATE = 35;
    private static final int TRANSACTION_GET_CURRENT_DEVICE_NAME = 52;
    private static final int CALLBACK_SUCCESS = 1;
    private static final int CALLBACK_FAILURE = 2;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private IBinder remote;
    private boolean active;
    private boolean bound;
    private Snapshot snapshot = new Snapshot("", "", -1, false, 0L);

    private final IBinder.DeathRecipient deathRecipient = () -> main.post(() -> {
        remote = null;
        snapshot = new Snapshot("", "", -1, false, System.currentTimeMillis());
        notifyChanged();
        if (active) scheduleRead(1_000L);
    });

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            remote = service;
            try { service.linkToDeath(deathRecipient, 0); } catch (Exception ignored) { }
            AppSessionLog.event("BLUETOOTH",
                    "Servicio OEM Jancar enlazado en modo lectura");
            readNow();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null;
            snapshot = new Snapshot("", "", -1, false, System.currentTimeMillis());
            notifyChanged();
        }
    };

    JancarBluetoothProvider(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        if (active) { readNow(); return; }
        active = true;
        Intent intent = new Intent(SERVICE_ACTION)
                .setComponent(new ComponentName(SERVICE_PACKAGE, SERVICE_CLASS));
        try {
            // Flags 0: observe only an already-running persistent OEM service.
            // Never start or recreate the Bluetooth stack from this app.
            bound = context.bindService(intent, connection, 0);
            if (!bound) {
                snapshot = new Snapshot("", "", -1, false, System.currentTimeMillis());
                notifyChanged();
            }
        } catch (Exception error) {
            bound = false;
            snapshot = new Snapshot("", "", -1, false, System.currentTimeMillis());
            AppSessionLog.event("BLUETOOTH", "Servicio OEM no accesible · "
                    + error.getClass().getSimpleName());
            notifyChanged();
        }
    }

    void stop() {
        active = false;
        main.removeCallbacksAndMessages(null);
        IBinder service = remote;
        remote = null;
        if (service != null) {
            try { service.unlinkToDeath(deathRecipient, 0); } catch (Exception ignored) { }
        }
        if (bound) {
            try { context.unbindService(connection); } catch (Exception ignored) { }
            bound = false;
        }
    }

    Snapshot snapshot() { return snapshot; }

    void readNow() {
        if (!active) return;
        IBinder service = remote;
        if (service == null) return;
        requestLinkState(service);
        requestCurrentName(service);
        scheduleRead(5_000L);
    }

    private void requestCurrentName(IBinder service) {
        Binder callback = new Binder() {
            { attachInterface(null, EXEC_CALLBACK_DESCRIPTOR); }

            @Override protected boolean onTransact(int code, Parcel data, Parcel reply,
                                                    int flags) throws RemoteException {
                if (code == INTERFACE_TRANSACTION) {
                    if (reply != null) reply.writeString(EXEC_CALLBACK_DESCRIPTOR);
                    return true;
                }
                data.enforceInterface(EXEC_CALLBACK_DESCRIPTOR);
                if (code == CALLBACK_SUCCESS) {
                    String value = data.readString();
                    main.post(() -> updateName(value));
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                if (code == CALLBACK_FAILURE) {
                    int failure = data.readInt();
                    AppSessionLog.event("BLUETOOTH",
                            "getCurrentDeviceName OEM fallo=" + failure);
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        transactCallback(service, TRANSACTION_GET_CURRENT_DEVICE_NAME, callback);
    }

    private void requestLinkState(IBinder service) {
        Binder callback = new Binder() {
            { attachInterface(null, LINK_CALLBACK_DESCRIPTOR); }

            @Override protected boolean onTransact(int code, Parcel data, Parcel reply,
                                                    int flags) throws RemoteException {
                if (code == INTERFACE_TRANSACTION) {
                    if (reply != null) reply.writeString(LINK_CALLBACK_DESCRIPTOR);
                    return true;
                }
                data.enforceInterface(LINK_CALLBACK_DESCRIPTOR);
                if (code == CALLBACK_SUCCESS) {
                    int linkState = data.readInt();
                    String address = data.readString();
                    String value = data.readString();
                    main.post(() -> updateLink(linkState, value, address));
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                if (code == CALLBACK_FAILURE) {
                    int failure = data.readInt();
                    AppSessionLog.event("BLUETOOTH", "getBluetoothState OEM fallo=" + failure);
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        transactCallback(service, TRANSACTION_GET_BLUETOOTH_STATE, callback);
    }

    private void transactCallback(IBinder service, int transaction, IBinder callback) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeStrongBinder(callback);
            service.transact(transaction, data, reply, 0);
            reply.readException();
        } catch (Exception error) {
            AppSessionLog.event("BLUETOOTH", "Lectura OEM transacción=" + transaction
                    + " · " + error.getClass().getSimpleName());
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void updateName(String name) {
        String cleanName = clean(name);
        Snapshot previous = snapshot;
        snapshot = new Snapshot(cleanName.isEmpty() ? previous.deviceName : cleanName,
                previous.address, previous.state, true, System.currentTimeMillis());
        AppSessionLog.event("BLUETOOTH", "Nombre OEM="
                + (snapshot.deviceName.isEmpty() ? "—" : snapshot.deviceName));
        notifyChanged();
    }

    private void updateLink(int state, String name, String address) {
        Snapshot previous = snapshot;
        String cleanName = clean(name);
        snapshot = new Snapshot(cleanName.isEmpty() ? previous.deviceName : cleanName,
                address, state, true, System.currentTimeMillis());
        AppSessionLog.event("BLUETOOTH", "Estado OEM=" + state + " · terminal="
                + (snapshot.deviceName.isEmpty() ? "—" : snapshot.deviceName));
        notifyChanged();
    }

    private void scheduleRead(long delayMs) {
        main.removeCallbacks(periodicRead);
        if (active) main.postDelayed(periodicRead, delayMs);
    }

    private final Runnable periodicRead = this::readNow;

    private void notifyChanged() {
        if (listener != null) listener.onChanged();
    }

    private static String clean(String value) {
        if (value == null) return "";
        String result = value.replace('\n', ' ').replace('\r', ' ').trim();
        return result.length() > 96 ? result.substring(0, 96) : result;
    }
}
