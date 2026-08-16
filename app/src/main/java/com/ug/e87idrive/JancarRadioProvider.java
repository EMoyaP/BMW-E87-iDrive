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

import java.util.Locale;

/**
 * Read-only view of the exact Jancar RadioService exported from this unit.
 * Only the verified getFreq, getBand and getPSText transactions are used.
 */
final class JancarRadioProvider {
    static final class Snapshot {
        final boolean available;
        final int rawBand, rawFrequency;
        final String band, station, detail;

        Snapshot(boolean available, int rawBand, int rawFrequency,
                 String band, String station, String detail) {
            this.available = available;
            this.rawBand = rawBand;
            this.rawFrequency = rawFrequency;
            this.band = band;
            this.station = station;
            this.detail = detail;
        }
    }

    private static final String PACKAGE = "com.jancar.services";
    private static final String SERVICE = "com.jancar.services.radio.RadioService";
    private static final String ACTION = "com.jancar.services.action.radio";
    private static final String DESCRIPTOR = "com.jancar.services.radio.IRadio";
    private static final int GET_FREQ = 0x04;
    private static final int GET_BAND = 0x06;
    private static final int GET_PS_TEXT = 0x16;

    private final Context context;
    private final Runnable listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler worker;
    private IBinder radio;
    private boolean running, bound;
    private String state = "Sonda aún no iniciada";
    private Snapshot snapshot = unavailable();

    JancarRadioProvider(Context context, Runnable listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        AppSessionLog.event("RADIO OEM", "Iniciando sonda Jancar de solo lectura");
        thread = new HandlerThread("e87-jancar-radio-readonly");
        thread.start();
        worker = new Handler(thread.getLooper());
        main.post(this::bindExistingService);
    }

    synchronized void stop() {
        running = false;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (bound) {
            try { context.unbindService(connection); } catch (Exception ignored) { }
        }
        bound = false;
        radio = null;
        if (thread != null) thread.quitSafely();
        thread = null;
        worker = null;
    }

    Snapshot snapshot() { return snapshot; }

    String diagnosticReport() {
        Snapshot value = snapshot;
        return "RADIO OEM JANCAR · LECTURA BINDER PASIVA\n"
                + "servicio=" + PACKAGE + "/" + SERVICE + "\n"
                + "estado=" + state + "\n"
                + "raw banda=" + value.rawBand + " · frecuencia=" + value.rawFrequency + "\n"
                + "PS=" + (value.available ? value.station : "no publicado") + "\n"
                + "Nota: RadioService no expone un getter remoto de abierto/cerrado; la frecuencia puede ser la última sintonizada.\n";
    }

    private void bindExistingService() {
        synchronized (this) { if (!running || bound) return; }
        Intent intent = new Intent(ACTION).setComponent(new ComponentName(PACKAGE, SERVICE));
        try {
            boolean result = context.bindService(intent, connection, 0);
            synchronized (this) { bound = result; }
            state = result ? "Esperando RadioService existente" : "RadioService no disponible";
            AppSessionLog.event("RADIO OEM", "bind existente=" + result);
        } catch (Exception error) {
            state = "No se pudo enlazar: " + error.getClass().getSimpleName();
            AppSessionLog.event("RADIO OEM", state);
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            Handler target = worker;
            if (target != null) target.post(() -> accept(service));
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            radio = null;
            state = "RadioService desconectado";
            AppSessionLog.event("RADIO OEM", state);
            publish(unavailable());
        }
    };

    private void accept(IBinder service) {
        if (!running || service == null) return;
        try {
            String descriptor = service.getInterfaceDescriptor();
            if (!DESCRIPTOR.equals(descriptor)) {
                state = "Descriptor incompatible: " + descriptor;
                return;
            }
            radio = service;
            state = "RadioService conectado · solo getters";
            AppSessionLog.event("RADIO OEM", state);
            poll();
        } catch (Exception error) {
            state = "Servicio rechazado: " + error.getClass().getSimpleName();
            AppSessionLog.event("RADIO OEM", state);
        }
    }

    private void poll() {
        if (!running || radio == null) return;
        int frequency = readInt(GET_FREQ, null);
        int band = readInt(GET_BAND, null);
        if (frequency > 0 && (band == 0 || band == 1)) {
            String ps = clean(readString(GET_PS_TEXT, frequency));
            String formatted = frequency(band, frequency);
            publish(new Snapshot(true, band, frequency, band == 1 ? "FM" : "AM",
                    ps.isEmpty() ? formatted : ps, ps.isEmpty()
                    ? "Frecuencia publicada por RadioService"
                    : formatted + " · RDS/PS OEM"));
        } else publish(new Snapshot(false, band, frequency, "RADIO", "", "Sin frecuencia OEM"));
        Handler target = worker;
        if (running && target != null) target.postDelayed(this::poll, 1_000L);
    }

    private void publish(Snapshot next) {
        Snapshot previous = snapshot;
        snapshot = next;
        if (listener != null && (previous.available != next.available
                || previous.rawBand != next.rawBand || previous.rawFrequency != next.rawFrequency
                || !previous.station.equals(next.station))) {
            AppSessionLog.event("RADIO OEM", "disponible=" + next.available + " · banda="
                    + next.rawBand + " · frecuencia=" + next.rawFrequency + " · PS="
                    + (next.station.isEmpty() ? "no publicado" : next.station));
            main.post(listener);
        }
    }

    private int readInt(int transaction, Integer argument) {
        IBinder service = radio;
        if (service == null) return -1;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (argument != null) data.writeInt(argument);
            if (!service.transact(transaction, data, reply, 0)) return -1;
            reply.readException();
            return reply.readInt();
        } catch (Exception ignored) { return -1; }
        finally { reply.recycle(); data.recycle(); }
    }

    private String readString(int transaction, int argument) {
        IBinder service = radio;
        if (service == null) return "";
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(argument);
            if (!service.transact(transaction, data, reply, 0)) return "";
            reply.readException();
            String value = reply.readString();
            return value == null ? "" : value;
        } catch (Exception ignored) { return ""; }
        finally { reply.recycle(); data.recycle(); }
    }

    static String frequency(int band, int raw) {
        if (band == 1) {
            double mhz = raw >= 50_000 ? raw / 1_000d : raw / 100d;
            return String.format(Locale.ROOT, "%.2f MHz", mhz);
        }
        return raw + " kHz";
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').trim();
    }

    private static Snapshot unavailable() {
        return new Snapshot(false, -1, -1, "RADIO", "", "Sin lectura OEM");
    }
}
