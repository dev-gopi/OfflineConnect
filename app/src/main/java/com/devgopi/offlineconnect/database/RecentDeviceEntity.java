package com.devgopi.offlineconnect.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;

import com.devgopi.offlineconnect.model.Device;

@Entity(tableName = "recent_devices", primaryKeys = {"id", "transport"})
public class RecentDeviceEntity {
    @NonNull public String id;
    @NonNull public String name;
    @NonNull public String transport;
    public long lastConnectedAt;

    public RecentDeviceEntity(@NonNull String id, @NonNull String name,
                              @NonNull String transport, long lastConnectedAt) {
        this.id = id; this.name = name; this.transport = transport;
        this.lastConnectedAt = lastConnectedAt;
    }

    public static RecentDeviceEntity from(Device device) {
        return new RecentDeviceEntity(device.getId(), device.getName(),
                device.getTransport().name(), System.currentTimeMillis());
    }

    public Device toDevice() {
        return new Device(id, name, Device.Transport.valueOf(transport), false);
    }
}
