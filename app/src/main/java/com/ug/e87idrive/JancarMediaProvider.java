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

/**
 * Optional read-only media bridge exposed by the Jancar services APK found on
 * this unit.  The descriptor, component, transaction numbers and callback
 * layout are verified against the exported MediaService APK used by this
 * radio.  It is deliberately isolated from CAN/UART and is used only for the
 * Android Auto/third-party media type (20).  Native radio, A2DP and other
 * media types are never claimed by this provider.
 */
final class JancarMediaProvider {
    static final int TYPE_THIRD_PARTY = 20;
    private static final String PACKAGE = "com.jancar.services";
    private static final String SERVICE = "com.jancar.services.media.MediaService";
    private static final String ACTION = "com.jancar.services.action.media";
    private static final String DESCRIPTOR = "com.jancar.services.media.IMedia";
    private static final String CALLBACK_DESCRIPTOR =
            "com.jancar.services.media.IMediaInfoCallback";

    private static final int REGISTER_INFO = 0x05;
    private static final int UNREGISTER_INFO = 0x06;
    private static final int GET_ACTIVE_MEDIA = 0x07;
    private static final int PLAY_PAUSE = 0x11;
    private static final int PREVIOUS = 0x12;
    private static final int NEXT = 0x13;
    private static final int REQUEST_INFO = 0x16;

    static final class Snapshot {
        final boolean available;
        final int mediaType, playState, position, duration, activeMedia;
        final String title, artist;
        final long updatedAt;

        Snapshot(boolean available, int mediaType, int playState, int position,
                 int duration, int activeMedia, String title, String artist,
                 long updatedAt) {
            this.available = available;
            this.mediaType = mediaType;
            this.playState = playState;
            this.position = position;
            this.duration = duration;
            this.activeMedia = activeMedia;
            this.title = title == null ? "" : title.trim();
            this.artist = artist == null ? "" : artist.trim();
            this.updatedAt = updatedAt;
        }

        boolean thirdParty() { return mediaType == TYPE_THIRD_PARTY; }

        boolean hasContent() {
            return available && thirdParty() && (!title.isEmpty() || !artist.isEmpty());
        }

        boolean canControl() {
            return hasContent() && activeMedia == TYPE_THIRD_PARTY;
        }

        String stateLabel() {
            if (playState == 3) return "REPRODUCIENDO";
            if (playState == 2) return "EN PAUSA";
            if (playState == 1) return "DETENIDO";
            return "SESIÓN ACTIVA";
        }
    }

    private final Context context;
    private final Runnable listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MediaInfoCallback callback = new MediaInfoCallback();
    private IBinder media;
    private boolean running;
    private boolean bound;
    private boolean registered;
    private String state = "Sonda aún no iniciada";
    private volatile Snapshot snapshot = unavailable();

    JancarMediaProvider(Context context, Runnable listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        AppSessionLog.event("MULTIMEDIA JANCAR", "Iniciando sonda MediaService opcional");
        main.post(this::bindExistingService);
    }

    synchronized void stop() {
        running = false;
        unregister();
        if (bound) {
            try { context.unbindService(connection); } catch (Exception ignored) { }
        }
        bound = false;
        media = null;
        state = "Sonda detenida";
    }

    Snapshot snapshot() { return snapshot; }

    String diagnosticReport() {
        Snapshot value = snapshot;
        return "JANCAR MEDIASERVICE · PUENTE TERCEROS / ANDROID AUTO\n"
                + "servicio=" + PACKAGE + "/" + SERVICE + "\n"
                + "estado=" + state + " · enlazado=" + bound
                + " · callback=" + registered + "\n"
                + "tipo activo=" + value.activeMedia + " · tipo recibido=" + value.mediaType + "\n"
                + "título=" + (value.title.isEmpty() ? "no publicado" : value.title) + "\n"
                + "artista/detalle=" + (value.artist.isEmpty() ? "no publicado" : value.artist) + "\n"
                + "reproducción=" + value.stateLabel() + " · posición=" + value.position
                + " · duración=" + value.duration + "\n"
                + "controles permitidos=" + value.canControl() + "\n"
                + "Nota: la app solo registra el callback y envía play/pausa/anterior/siguiente cuando el servicio publica tipo 20 (terceros).\n";
    }

    boolean control(int command) {
        Snapshot value = snapshot;
        if (!value.canControl()) {
            AppSessionLog.event("MULTIMEDIA JANCAR", "Control rechazado: no hay tercero activo");
            return false;
        }
        int transaction = command == 0 ? PLAY_PAUSE : command == 1 ? PREVIOUS : NEXT;
        IBinder service = media;
        if (service == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!service.transact(transaction, data, reply, 0)) return false;
            reply.readException();
            AppSessionLog.event("MULTIMEDIA JANCAR", "Control enviado transacción=0x"
                    + Integer.toHexString(transaction) + " · tipo=20");
            return true;
        } catch (Exception error) {
            AppSessionLog.event("MULTIMEDIA JANCAR", "Control fallido transacción=0x"
                    + Integer.toHexString(transaction) + " · " + error.getClass().getSimpleName());
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void bindExistingService() {
        synchronized (this) { if (!running || bound) return; }
        Intent intent = new Intent(ACTION).setComponent(new ComponentName(PACKAGE, SERVICE));
        try {
            boolean result = context.bindService(intent, connection, 0);
            synchronized (this) { bound = result; }
            state = result ? "Esperando MediaService existente" : "MediaService no disponible";
            AppSessionLog.event("MULTIMEDIA JANCAR", "bind existente=" + result);
        } catch (Exception error) {
            state = "No se pudo enlazar: " + error.getClass().getSimpleName();
            AppSessionLog.event("MULTIMEDIA JANCAR", state);
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            accept(service);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            unregister();
            media = null;
            state = "MediaService desconectado";
            AppSessionLog.event("MULTIMEDIA JANCAR", state);
            publish(unavailable());
        }
    };

    private void accept(IBinder service) {
        if (!running || service == null) return;
        try {
            String descriptor = service.getInterfaceDescriptor();
            if (!DESCRIPTOR.equals(descriptor)) {
                state = "Descriptor incompatible: " + descriptor;
                AppSessionLog.event("MULTIMEDIA JANCAR", state);
                return;
            }
            media = service;
            state = "MediaService conectado · bridge verificado";
            AppSessionLog.event("MULTIMEDIA JANCAR", state);
            int active = readActiveMedia();
            Snapshot current = snapshot;
            snapshot = new Snapshot(current.available, current.mediaType, current.playState,
                    current.position, current.duration, active, current.title, current.artist,
                    current.updatedAt);
            AppSessionLog.event("MULTIMEDIA JANCAR", "getActiveMedia raw=" + active);
            register();
            requestInfo();
        } catch (Exception error) {
            state = "Servicio rechazado: " + error.getClass().getSimpleName();
            AppSessionLog.event("MULTIMEDIA JANCAR", state);
        }
    }

    private void register() {
        IBinder service = media;
        if (service == null) return;
        if (transactBinder(REGISTER_INFO, callback)) {
            registered = true;
            AppSessionLog.event("MULTIMEDIA JANCAR", "callback IMediaInfoCallback registrado");
        } else {
            state = "MediaService conectado · callback rechazado";
            AppSessionLog.event("MULTIMEDIA JANCAR", state);
        }
    }

    private void unregister() {
        if (!registered || media == null) return;
        transactBinder(UNREGISTER_INFO, callback);
        registered = false;
    }

    private void requestInfo() {
        IBinder service = media;
        if (service == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (service.transact(REQUEST_INFO, data, reply, 0)) {
                reply.readException();
                AppSessionLog.event("MULTIMEDIA JANCAR", "solicitud de snapshot enviada");
            }
        } catch (Exception error) {
            AppSessionLog.event("MULTIMEDIA JANCAR", "snapshot no solicitado: "
                    + error.getClass().getSimpleName());
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean transactBinder(int transaction, IBinder binder) {
        IBinder service = media;
        if (service == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStrongBinder(binder);
            if (!service.transact(transaction, data, reply, 0)) return false;
            reply.readException();
            return true;
        } catch (Exception error) {
            AppSessionLog.event("MULTIMEDIA JANCAR", "transacción 0x"
                    + Integer.toHexString(transaction) + " fallida: "
                    + error.getClass().getSimpleName());
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void onMediaInfo(int mediaType, String title, String artist,
                             int artWidth, int artHeight, int artBytes,
                             int index, int total, boolean popup) {
        Snapshot old = snapshot;
        Snapshot next = new Snapshot(true, mediaType, old.playState, old.position, old.duration,
                old.activeMedia, title, artist, System.currentTimeMillis());
        publish(next);
        AppSessionLog.event("MULTIMEDIA JANCAR", "onMediaChange raw tipo=" + mediaType
                + " · título=" + safe(title) + " · artista/detalle=" + safe(artist)
                + " · arte=" + artWidth + "x" + artHeight + "/" + artBytes
                + " bytes · índice=" + index + "/" + total + " · popup=" + popup);
    }

    private void onPlayState(int mediaType, int playState, int position, int duration) {
        Snapshot old = snapshot;
        Snapshot next = new Snapshot(true, mediaType, playState, position, duration,
                old.activeMedia, old.title, old.artist, System.currentTimeMillis());
        publish(next);
        AppSessionLog.event("MULTIMEDIA JANCAR", "onPlayStateChange raw tipo=" + mediaType
                + " · estado=" + playState + "(" + next.stateLabel() + ")"
                + " · posición=" + position + " · duración=" + duration);
    }

    private void publish(Snapshot next) {
        Snapshot previous = snapshot;
        snapshot = next;
        if (listener != null && (previous.mediaType != next.mediaType
                || previous.playState != next.playState
                || !previous.title.equals(next.title)
                || !previous.artist.equals(next.artist))) {
            main.post(listener);
        }
    }

    private final class MediaInfoCallback extends Binder {
        MediaInfoCallback() { attachInterface(null, CALLBACK_DESCRIPTOR); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }
            try {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                switch (code) {
                    case 0x01:
                        int type = data.readInt();
                        String title = data.readString();
                        String artist = data.readString();
                        int width = data.readInt();
                        int height = data.readInt();
                        byte[] art = data.createByteArray();
                        int index = data.readInt();
                        int total = data.readInt();
                        boolean popup = data.readInt() != 0;
                        onMediaInfo(type, title, artist, width, height,
                                art == null ? 0 : art.length, index, total, popup);
                        break;
                    case 0x02:
                        onPlayState(data.readInt(), data.readInt(), data.readInt(), data.readInt());
                        break;
                    case 0x03:
                        int zone = data.readInt();
                        int zoneValue = data.readInt();
                        AppSessionLog.event("MULTIMEDIA JANCAR", "onMediaZoneChanged raw zone="
                                + zone + " · valor=" + zoneValue);
                        break;
                    case 0x04:
                        AppSessionLog.event("MULTIMEDIA JANCAR", "onCurrentShownMediaTypeChanged raw tipo="
                                + data.readInt());
                        break;
                    default:
                        return super.onTransact(code, data, reply, flags);
                }
                reply.writeNoException();
                return true;
            } catch (Exception error) {
                AppSessionLog.event("MULTIMEDIA JANCAR", "callback 0x"
                        + Integer.toHexString(code) + " inválido: "
                        + error.getClass().getSimpleName());
                return false;
            }
        }
    }

    private int readActiveMedia() {
        IBinder service = media;
        if (service == null) return -1;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!service.transact(GET_ACTIVE_MEDIA, data, reply, 0)) return -1;
            reply.readException();
            return reply.readInt();
        } catch (Exception ignored) {
            return -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static String safe(String value) { return value == null ? "(null)" : value; }

    private static Snapshot unavailable() {
        return new Snapshot(false, -1, -1, -1, -1, -1, "", "", 0L);
    }
}
