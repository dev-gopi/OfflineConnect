package com.devgopi.offlineconnect.communication;

import android.content.Context;

import androidx.annotation.NonNull;

import com.devgopi.offlineconnect.model.Device;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Combines peer updates from both radios and removes duplicate discoveries. */
public final class DeviceDiscoveryManager implements AutoCloseable {
    public interface Listener {
        void onDevicesChanged(List<Device> devices);
        void onDiscoveryChanged(boolean discovering);
        void onError(String message);
    }

    private final Map<String, Device> devices = new LinkedHashMap<>();
    private final Listener listener;
    private final BluetoothManager bluetooth;
    private final WifiDirectManager wifiDirect;
    private boolean bluetoothDiscovering;
    private boolean wifiDiscovering;

    public DeviceDiscoveryManager(@NonNull Context context, @NonNull Listener listener) {
        this.listener = listener;
        bluetooth = new BluetoothManager(context, new BluetoothManager.Listener() {
            @Override public void onDeviceFound(Device device) { add(device); }
            @Override public void onDiscoveryChanged(boolean value) {
                bluetoothDiscovering = value; publishState();
            }
            @Override public void onError(String message) { listener.onError(message); }
        });
        wifiDirect = new WifiDirectManager(context, new WifiDirectManager.Listener() {
            @Override public void onDeviceFound(Device device) { add(device); }
            @Override public void onConnectionChanged(boolean connected) { }
            @Override public void onDiscoveryChanged(boolean value) {
                wifiDiscovering = value; publishState();
            }
            @Override public void onError(String message) { listener.onError(message); }
        });
    }

    public void startDiscovery() {
        devices.clear();
        // Collections.emptyList works on every supported Android API level.
        listener.onDevicesChanged(Collections.emptyList());
        bluetooth.startDiscovery();
        wifiDirect.startDiscovery();
    }

    public void stopDiscovery() {
        bluetooth.stopDiscovery();
        wifiDirect.stopDiscovery();
        bluetoothDiscovering = false;
        wifiDiscovering = false;
        publishState();
    }

    private void add(Device device) {
        devices.put(device.getTransport() + ":" + device.getId(), device);
        listener.onDevicesChanged(new ArrayList<>(devices.values()));
    }

    private void publishState() {
        listener.onDiscoveryChanged(bluetoothDiscovering || wifiDiscovering);
    }

    @Override public void close() {
        bluetooth.close();
        wifiDirect.close();
    }
}
