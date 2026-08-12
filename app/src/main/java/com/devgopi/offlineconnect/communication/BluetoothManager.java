package com.devgopi.offlineconnect.communication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.devgopi.offlineconnect.model.Device;

import java.util.Collections;
import java.util.UUID;

/** Discovers classic peers and advertises/scans for other Offline Connect BLE peers. */
public final class BluetoothManager implements AutoCloseable {
    private static final ParcelUuid SERVICE_UUID = new ParcelUuid(
            UUID.fromString("7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e"));
    public interface Listener {
        void onDeviceFound(Device device);
        void onDiscoveryChanged(boolean discovering);
        void onError(String message);
    }

    private final Context context;
    private final BluetoothAdapter adapter;
    private final Listener listener;
    private BluetoothLeScanner leScanner;
    private BluetoothLeAdvertiser leAdvertiser;
    private boolean leScanning;
    private boolean advertising;
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice peer = getBluetoothDevice(intent);
                publishPeer(peer);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                listener.onDiscoveryChanged(true);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                listener.onDiscoveryChanged(false);
            }
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            publishPeer(result.getDevice());
        }

        @Override public void onScanFailed(int errorCode) {
            leScanning = false;
            listener.onError("Bluetooth LE scan failed (code " + errorCode + ")");
        }
    };

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
        }

        @Override public void onStartFailure(int errorCode) {
            advertising = false;
            if (errorCode != ADVERTISE_FAILED_ALREADY_STARTED) {
                listener.onError("Bluetooth advertising failed (code " + errorCode + ")");
            }
        }
    };

    public BluetoothManager(@NonNull Context context, @NonNull Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        android.bluetooth.BluetoothManager service = ContextCompat.getSystemService(this.context,
                android.bluetooth.BluetoothManager.class);
        this.adapter = service == null ? null : service.getAdapter();
    }

    public boolean isSupported() { return adapter != null; }

    @SuppressLint("MissingPermission") // Permission is checked immediately before radio access.
    public void startDiscovery() {
        if (adapter == null) { listener.onError("Bluetooth is not supported"); return; }
        if (!hasScanPermission() || !hasConnectPermission()) {
            listener.onError("Bluetooth permission is required"); return;
        }
        if (!adapter.isEnabled()) { listener.onError("Bluetooth is turned off"); return; }
        registerReceiver();
        try {
            publishBondedDevices();
            startBleDiscovery();
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            if (!adapter.startDiscovery()) listener.onError("Bluetooth discovery could not start");
        } catch (SecurityException exception) {
            listener.onError("Bluetooth permission was revoked");
        }
    }

    @SuppressLint("MissingPermission") // Permission is checked immediately before radio access.
    public void stopDiscovery() {
        try {
            stopBleDiscovery();
            if (adapter != null && hasScanPermission() && adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
        } catch (SecurityException exception) {
            listener.onError("Bluetooth permission was revoked");
        }
    }

    @SuppressLint("MissingPermission")
    private void publishBondedDevices() {
        for (BluetoothDevice bonded : adapter.getBondedDevices()) publishPeer(bonded);
    }

    @SuppressLint("MissingPermission")
    private void startBleDiscovery() {
        leScanner = adapter.getBluetoothLeScanner();
        if (leScanner != null && !leScanning) {
            ScanFilter filter = new ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            leScanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            leScanning = true;
        }

        if (!hasAdvertisePermission()) return;
        leAdvertiser = adapter.getBluetoothLeAdvertiser();
        if (leAdvertiser != null && !advertising) {
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(SERVICE_UUID)
                    .setIncludeDeviceName(false)
                    .build();
            leAdvertiser.startAdvertising(settings, data, advertiseCallback);
        }
    }

    @SuppressLint("MissingPermission")
    private void stopBleDiscovery() {
        if (leScanner != null && leScanning) leScanner.stopScan(scanCallback);
        if (leAdvertiser != null && advertising && hasAdvertisePermission()) {
            leAdvertiser.stopAdvertising(advertiseCallback);
        }
        leScanning = false;
        advertising = false;
    }

    @SuppressLint("MissingPermission") // Permission is checked before reading protected peer data.
    private void publishPeer(BluetoothDevice peer) {
        if (peer == null || !hasConnectPermission()) return;
        try {
            listener.onDeviceFound(new Device(peer.getAddress(), peer.getName(),
                    Device.Transport.BLUETOOTH, false));
        } catch (SecurityException exception) {
            listener.onError("Bluetooth permission was revoked");
        }
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    private boolean hasScanPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAdvertisePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
                        == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private static BluetoothDevice getBluetoothDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    @Override public void close() {
        stopDiscovery();
        if (receiverRegistered) {
            context.unregisterReceiver(receiver);
            receiverRegistered = false;
        }
    }
}
