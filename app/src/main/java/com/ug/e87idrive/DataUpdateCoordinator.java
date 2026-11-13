package com.ug.e87idrive;

import android.location.Location;

/** Runs the source update chain without making MainActivity own repository sequencing. */
final class DataUpdateCoordinator {
    interface Listener {
        void start(int index, String message);
        void finish(int index, boolean success, String message);
        void complete();
    }

    private final SpeedLimitRepository speedLimits;
    private final FuelStationProvider fuelStations;
    private final RadarRepository radars;
    private final DgtSpeedRepository dgtSpeeds;
    private final InviveRepository invive;

    DataUpdateCoordinator(SpeedLimitRepository speedLimits, FuelStationProvider fuelStations,
                          RadarRepository radars, DgtSpeedRepository dgtSpeeds,
                          InviveRepository invive) {
        this.speedLimits = speedLimits;
        this.fuelStations = fuelStations;
        this.radars = radars;
        this.dgtSpeeds = dgtSpeeds;
        this.invive = invive;
    }

    void all(Location location, Listener listener) {
        listener.start(0, "Descargando y consolidando OSM de Alicante…");
        speedLimits.refreshFromInternet(location, "ALICANTE", osm -> {
            listener.finish(0, osm.success, osm.message);
            listener.start(1, "Descargando precios y cobertura de gasolineras…");
            FuelStationProvider.Snapshot before = fuelStations.getSnapshot();
            long beforeUpdated = before == null ? 0L : before.updatedAt;
            fuelStations.forceRefresh(() -> {
                FuelStationProvider.Snapshot snapshot = fuelStations.getSnapshot();
                boolean ok = snapshot != null && snapshot.updatedAt > beforeUpdated
                        && !snapshot.loading
                        && !lower(snapshot.message).contains("sin internet");
                listener.finish(1, ok, ok ? "Precios descargados e instalados"
                        : "Sin descarga nueva; se conserva la caché");
                listener.start(2, "Descargando inventario nacional de radares DGT…");
                radars.refreshNationalFromInternet(radar -> {
                    listener.finish(2, radar.success, radar.message);
                    listener.start(3, "Aplicando cambios nacionales de límites DGT…");
                    dgtSpeeds.refreshFromInternet(dgt -> {
                        listener.finish(3, dgt.success, dgt.message);
                        listener.start(4, "Descargando zonas INVIVE nacionales…");
                        invive.refreshNationalFromInternet(surveillance -> {
                            listener.finish(4, surveillance.success, surveillance.message);
                            listener.start(5, "Verificando índices y datos locales…");
                            listener.finish(5, true,
                                    "Fuentes consolidadas y listas para usar sin conexión");
                            listener.complete();
                        });
                    });
                });
            });
        });
    }

    void speedAndSurveillance(Location location, String province, Listener listener) {
        listener.start(0, "Actualizando límites locales…");
        speedLimits.refreshFromInternet(location, province, result -> {
            listener.finish(0, result.success, result.message);
            listener.start(1, "Actualizando zonas de vigilancia…");
            invive.refreshFromInternet(province, surveillance -> {
                listener.finish(1, surveillance.success, surveillance.message);
                listener.complete();
            });
        });
    }

    void osm(Location location, Listener listener) {
        listener.start(0, "Descargando mapa vial parcial de Alicante…");
        speedLimits.refreshFromInternet(location, "ALICANTE", result -> {
            listener.finish(0, result.success, result.message);
            listener.complete();
        });
    }

    void fuel(Listener listener) {
        listener.start(0, "Descargando cobertura y precios…");
        FuelStationProvider.Snapshot before = fuelStations.getSnapshot();
        long beforeUpdated = before == null ? 0L : before.updatedAt;
        fuelStations.forceRefresh(() -> {
            FuelStationProvider.Snapshot snapshot = fuelStations.getSnapshot();
            boolean ok = snapshot != null && snapshot.updatedAt > beforeUpdated
                    && !snapshot.loading && !lower(snapshot.message).contains("sin internet");
            listener.finish(0, ok, ok ? "Precios descargados e instalados"
                    : "Sin descarga nueva; se conserva la caché");
            listener.complete();
        });
    }

    void radars(Listener listener) {
        listener.start(0, "Descargando inventario nacional…");
        radars.refreshNationalFromInternet(result -> {
            listener.finish(0, result.success, result.message);
            listener.complete();
        });
    }

    void radars(String province, Listener listener) {
        listener.start(0, "Descargando inventario de " + RadarRepository.label(province) + "…");
        radars.refreshFromInternet(province, result -> {
            listener.finish(0, result.success, result.message);
            listener.complete();
        });
    }

    void dgt(Listener listener) {
        listener.start(0, "Descargando delta semanal nacional…");
        dgtSpeeds.refreshFromInternet(result -> {
            listener.finish(0, result.success, result.message);
            listener.complete();
        });
    }

    void surveillance(Listener listener) {
        listener.start(0, "Descargando zonas de vigilancia…");
        invive.refreshNationalFromInternet(result -> {
            listener.finish(0, result.success, result.message);
            listener.complete();
        });
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
    }
}
