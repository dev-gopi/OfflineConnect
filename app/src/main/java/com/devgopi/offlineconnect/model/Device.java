package com.devgopi.offlineconnect.model;

import androidx.annotation.NonNull;

import java.io.Serializable;
import java.util.Objects;

/** Immutable description of a peer exposed by a local radio. */
public final class Device implements Serializable {
    public enum Transport { BLUETOOTH, WIFI_DIRECT }

    private final String id;
    private final String name;
    private final Transport transport;
    private final boolean connected;

    public Device(@NonNull String id, String name, @NonNull Transport transport,
                  boolean connected) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name == null || name.isBlank() ? "Unknown device" : name;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.connected = connected;
    }

    @NonNull public String getId() { return id; }
    @NonNull public String getName() { return name; }
    @NonNull public Transport getTransport() { return transport; }
    public boolean isConnected() { return connected; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Device)) return false;
        Device device = (Device) other;
        return id.equals(device.id) && transport == device.transport;
    }

    @Override public int hashCode() { return Objects.hash(id, transport); }
    @Override public String toString() { return name + " (" + transport + ")"; }
}
