package com.ug.e87idrive;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Passive, public-API Bluetooth connection reader. It never scans, pairs or connects. */
public final class BluetoothDeviceProvider {
    public interface Listener { void onBluetoothState(State state); }

    public static final class State {
        public final String terminalName, detail;
        public final boolean connected, readable;

        State(String terminalName, String detail, boolean connected, boolean readable) {
            this.terminalName = terminalName;
            this.detail = detail;
            this.connected = connected;
            this.readable = readable;
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private final AudioManager audioManager;
    private final JancarBluetoothProvider jancar;
    // Android's legacy PAN profile identifier. The constant is no longer in
    // the public BluetoothProfile interface on recent SDKs, but the profile
    // proxy API still accepts this framework identifier on compatible units.
    private static final int PROFILE_PAN = 5;
    private final Map<Integer, BluetoothProfile> proxies = new LinkedHashMap<>();
    private boolean active, receiverRegistered;
    private State state = new State("Comprobando Bluetooth…", "Lectura estándar de Android", false, false);

    private final AudioDeviceCallback audioCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            synchronized (BluetoothDeviceProvider.this) { evaluate(); }
        }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            synchronized (BluetoothDeviceProvider.this) { evaluate(); }
        }
    };

    private final BluetoothProfile.ServiceListener profileListener =
            new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            synchronized (BluetoothDeviceProvider.this) {
                proxies.put(profile, proxy);
                evaluate();
            }
        }

        @Override public void onServiceDisconnected(int profile) {
            synchronized (BluetoothDeviceProvider.this) {
                proxies.remove(profile);
                evaluate();
            }
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            synchronized (BluetoothDeviceProvider.this) { evaluate(); }
        }
    };

    public BluetoothDeviceProvider(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager manager = (BluetoothManager) this.context
                .getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        jancar = new JancarBluetoothProvider(this.context, () -> {
            synchronized (BluetoothDeviceProvider.this) { evaluate(); }
        });
    }

    public synchronized void start() {
        if (active) { evaluate(); return; }
        active = true;
        jancar.start();
        if (!hasPermission()) {
            publish(new State("Permiso Bluetooth necesario",
                    "Autoriza Dispositivos cercanos", false, false));
            return;
        }
        try { if (audioManager != null) audioManager.registerAudioDeviceCallback(audioCallback, main); }
        catch (Exception ignored) {}
        if (adapter == null) {
            evaluate();
            return;
        }
        if (!adapter.isEnabled()) {
            publish(new State("Ningún terminal conectado", "Bluetooth desactivado", false, true));
            registerReceiver();
            return;
        }
        registerReceiver();
        requestProfile(BluetoothProfile.HEADSET);
        requestProfile(BluetoothProfile.A2DP);
        // PAN is the public profile used by Bluetooth internet tethering. It
        // is also useful for identifying the phone when the radio exposes PAN
        // but does not expose an audio profile for Android Auto.
        requestProfile(PROFILE_PAN);
        publish(new State("Comprobando conexión…", "Bluetooth de Android", false, true));
        main.postDelayed(() -> {
            synchronized (BluetoothDeviceProvider.this) { if (active) evaluate(); }
        }, 1_500L);
    }

    public synchronized void stop() {
        active = false;
        jancar.stop();
        main.removeCallbacksAndMessages(null);
        try { if (audioManager != null) audioManager.unregisterAudioDeviceCallback(audioCallback); }
        catch (Exception ignored) {}
        if (receiverRegistered) {
            try { context.unregisterReceiver(receiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        if (adapter != null) {
            Map<Integer, BluetoothProfile> closing = new LinkedHashMap<>(proxies);
            proxies.clear();
            for (Map.Entry<Integer, BluetoothProfile> entry : closing.entrySet()) {
                try { adapter.closeProfileProxy(entry.getKey(), entry.getValue()); }
                catch (Exception ignored) {}
            }
        }
        proxies.clear();
    }

    public synchronized State getState() { return state; }
    public boolean hasPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestProfile(int profile) {
        try { adapter.getProfileProxy(context, profileListener, profile); }
        catch (Exception ignored) {}
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            receiverRegistered = true;
        } catch (Exception ignored) {}
    }

    private void evaluate() {
        if (!active) return;
        if (!hasPermission()) {
            publish(new State("Permiso Bluetooth necesario",
                    "Autoriza Dispositivos cercanos", false, false));
            return;
        }
        try {
            JancarBluetoothProvider.Snapshot oem = jancar.snapshot();
            if (oem.hasDevice()) {
                publish(new State(oem.deviceName,
                        "Conectado · servicio Bluetooth OEM", true, true));
                return;
            }
            LinkedHashMap<String, String> devices = new LinkedHashMap<>();
            collectBluetoothAudioDevices(devices);
            if (adapter != null && adapter.isEnabled()) {
                for (BluetoothProfile proxy : proxies.values()) {
                    List<BluetoothDevice> connected = proxy.getConnectedDevices();
                    if (connected == null) continue;
                    for (BluetoothDevice device : connected) {
                        String address = safeAddress(device);
                        String name = safeName(device);
                        devices.put(address.isEmpty() ? name : address, name);
                    }
                }
            }
            if (!devices.isEmpty()) {
                String first = devices.values().iterator().next();
                String shown = devices.size() > 1 ? first + "  +" + (devices.size() - 1) : first;
                publish(new State(shown, "Conectado por Bluetooth", true, true));
                return;
            }
            if (adapter == null) {
                publish(new State("Bluetooth no disponible",
                        "Android no expone un adaptador", false, false));
                return;
            }
            if (!adapter.isEnabled()) {
                publish(new State("Ningún terminal conectado", "Bluetooth desactivado", false, true));
                return;
            }
            publish(new State("Ningún terminal conectado",
                    proxies.isEmpty() ? "Estado no expuesto por Android"
                            : "No detectado por Android",
                    false, !proxies.isEmpty()));
        } catch (SecurityException denied) {
            publish(new State("Permiso Bluetooth necesario",
                    "Autoriza Dispositivos cercanos", false, false));
        } catch (Exception unavailable) {
            publish(new State("Estado Bluetooth no accesible",
                    "El firmware no expone los perfiles", false, false));
        }
    }

    private void collectBluetoothAudioDevices(Map<String, String> devices) {
        if (audioManager == null) return;
        AudioDeviceInfo[] outputs;
        AudioDeviceInfo[] inputs;
        try {
            outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        } catch (Exception ignored) { return; }
        collectBluetoothAudioDevices(devices, outputs);
        collectBluetoothAudioDevices(devices, inputs);
    }

    private void collectBluetoothAudioDevices(Map<String, String> devices,
                                              AudioDeviceInfo[] audioDevices) {
        for (AudioDeviceInfo info : audioDevices) {
            int type = info.getType();
            boolean bluetooth = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                bluetooth = bluetooth || type == AudioDeviceInfo.TYPE_HEARING_AID;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                bluetooth = bluetooth || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                        || type == AudioDeviceInfo.TYPE_BLE_SPEAKER;
            }
            if (!bluetooth) continue;
            CharSequence product = info.getProductName();
            String name = product == null ? "" : product.toString().trim();
            if (name.isEmpty()) name = "Terminal Bluetooth";
            String address = "";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try { address = info.getAddress(); } catch (Exception ignored) { address = ""; }
            }
            devices.put(address == null || address.isEmpty() ? "audio:" + name : address, name);
        }
    }

    private String safeName(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return "Terminal Bluetooth";
        try {
            String name = device.getName();
            return name == null || name.trim().isEmpty() ? "Terminal Bluetooth" : name.trim();
        } catch (Exception ignored) { return "Terminal Bluetooth"; }
    }

    private String safeAddress(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return "";
        try { return device.getAddress(); }
        catch (Exception ignored) { return ""; }
    }

    private void publish(State value) {
        state = value;
        if (listener != null) main.post(() -> listener.onBluetoothState(value));
    }
}
