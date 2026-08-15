package com.ug.e87idrive;

/** Immutable value plus provenance, so GPS is never confused with CAN. */
public final class VehicleValue<T> {
    private final T value;
    private final boolean available;
    private final VehicleSource source;
    private final long timestamp;

    private VehicleValue(T value, boolean available, VehicleSource source, long timestamp) {
        this.value = value;
        this.available = available;
        this.source = source;
        this.timestamp = timestamp;
    }

    public static <T> VehicleValue<T> available(T value, VehicleSource source, long timestamp) {
        return new VehicleValue<>(value, true, source, timestamp);
    }

    public static <T> VehicleValue<T> unavailable() {
        return new VehicleValue<>(null, false, VehicleSource.NONE, 0L);
    }

    public T value() { return value; }
    public boolean isAvailable() { return available; }
    public VehicleSource source() { return source; }
    public long timestamp() { return timestamp; }

    public long ageMs() {
        return timestamp <= 0L ? Long.MAX_VALUE : Math.max(0L, System.currentTimeMillis() - timestamp);
    }
}
