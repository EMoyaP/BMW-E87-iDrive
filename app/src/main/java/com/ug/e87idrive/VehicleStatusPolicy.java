package com.ug.e87idrive;

import java.util.Locale;

/** Keeps the bottom strip focused on current, actionable vehicle states. */
final class VehicleStatusPolicy {
    private VehicleStatusPolicy() {}

    static boolean isActive(VehicleField field, String displayedValue) {
        if (field == null || displayedValue == null || displayedValue.trim().isEmpty()) return false;
        String value = displayedValue.toLowerCase(Locale.ROOT);
        if (value.equals("—") || value.contains("no disponible") || value.contains("sin fuente")) return false;
        if (field == VehicleField.LIGHTS) {
            return !(value.contains("apagad") || value.contains("inactiv") || value.equals("off"));
        }
        if (field == VehicleField.PARKING_BRAKE) {
            if (value.contains("liberad") || value.contains("inactiv") || value.equals("off")) return false;
            return value.contains("activ") || value.contains("aplic") || value.contains("puesto") || value.equals("on");
        }
        if (field == VehicleField.REVERSE) {
            if (value.contains("inactiv") || value.contains("liberad") || value.equals("off")) return false;
            return value.contains("activ") || value.contains("puesta") || value.equals("on");
        }
        if (field == VehicleField.SEATBELT) {
            return value.contains("sin abrochar") || value.contains("desabroch")
                    || value.contains("no abroch") || value.contains("unbuckled");
        }
        if (field == VehicleField.DOORS) {
            return !(value.contains("cerrad") || value.contains("inactiv") || value.equals("off"));
        }
        return false;
    }
}
