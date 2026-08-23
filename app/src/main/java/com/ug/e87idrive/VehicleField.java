package com.ug.e87idrive;

/** Vehicle fields known to the UI. Values are only shown when a provider has a real value. */
public enum VehicleField {
    SPEED("speed", "Velocidad", true),
    GEAR("gear", "Caja", true),
    EXTERIOR_TEMPERATURE("temp_ext", "Temperatura exterior", true),
    PDC("pdc", "PDC / Radar", true),
    REVERSE("reverse", "Marcha atrás", true),
    CLIMATE_TEMPERATURE("climate_temp", "Temperatura clima", true),
    CLIMATE_FAN("climate_fan", "Velocidad ventilador", true),
    CLIMATE_STATE("climate_state", "Estado climatizador", true),
    DOORS("doors", "Puertas", false),
    LIGHTS("lights", "Luces", false),
    PARKING_BRAKE("parking_brake", "Freno de mano", false),
    SEATBELT("seatbelt", "Cinturón", false),
    RPM("rpm", "RPM", false),
    ENGINE_TEMPERATURE("temp_engine", "Temperatura motor", false),
    RANGE("range", "Autonomía", false),
    CONSUMPTION("consumption", "Consumo medio", false);

    private final String key;
    private final String label;
    private final boolean confirmedByUnit;

    VehicleField(String key, String label, boolean confirmedByUnit) {
        this.key = key;
        this.label = label;
        this.confirmedByUnit = confirmedByUnit;
    }

    public String key() { return key; }
    public String label() { return label; }
    public boolean confirmedByUnit() { return confirmedByUnit; }
}
