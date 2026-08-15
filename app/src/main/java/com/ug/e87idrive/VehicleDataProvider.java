package com.ug.e87idrive;

import java.util.Set;

public interface VehicleDataProvider {
    VehicleValue<?> get(VehicleField field);
    Set<VehicleField> supportedFields();
    void start();
    void stop();
}
