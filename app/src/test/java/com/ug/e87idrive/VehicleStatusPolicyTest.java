package com.ug.e87idrive;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VehicleStatusPolicyTest {
    @Test public void unavailableAndNormalStatesStayHidden() {
        assertFalse(VehicleStatusPolicy.isActive(VehicleField.LIGHTS, null));
        assertFalse(VehicleStatusPolicy.isActive(VehicleField.LIGHTS, "Apagadas"));
        assertFalse(VehicleStatusPolicy.isActive(VehicleField.PARKING_BRAKE, "Liberado"));
        assertFalse(VehicleStatusPolicy.isActive(VehicleField.SEATBELT, "Abrochado"));
        assertFalse(VehicleStatusPolicy.isActive(VehicleField.DOORS, "Cerradas"));
        assertFalse(VehicleStatusPolicy.isActive(VehicleField.REVERSE, "Inactiva"));
    }

    @Test public void activeVehicleStatesAreVisible() {
        assertTrue(VehicleStatusPolicy.isActive(VehicleField.LIGHTS, "Cruce"));
        assertTrue(VehicleStatusPolicy.isActive(VehicleField.LIGHTS, "Largas"));
        assertTrue(VehicleStatusPolicy.isActive(VehicleField.PARKING_BRAKE, "Activado"));
        assertTrue(VehicleStatusPolicy.isActive(VehicleField.SEATBELT, "Sin abrochar"));
        assertTrue(VehicleStatusPolicy.isActive(VehicleField.DOORS, "1 abierta"));
        assertTrue(VehicleStatusPolicy.isActive(VehicleField.REVERSE, "Activa"));
    }
}
