package com.devgopi.offlineconnect.communication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
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
import com.devgopi.offlineconnect.R;

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
                publishPeer(peer, false);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                listener.onDiscoveryChanged(true);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                listener.onDiscoveryChanged(false);
            }
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            // The scan filter already verified Offline Connect's BLE service UUID.
            publishPeer(result.getDevice(), true);
        }

        @Override public void onScanFailed(int errorCode) {
            leScanning = false;
            listener.onError(context.getString(R.string.bluetooth_le_scan_failed, errorCode));
        }
    };

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
        }

        @Override public void onStartFailure(int errorCode) {
            advertising = false;
            if (errorCode != ADVERTISE_FAILED_ALREADY_STARTED) {
                listener.onError(context.getString(R.string.bluetooth_advertising_failed, errorCode));
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
        if (adapter == null) { listener.onError(context.getString(R.string.bluetooth_not_supported)); return; }
        if (!hasScanPermission() || !hasConnectPermission()) {
            listener.onError(context.getString(R.string.bluetooth_permission_required)); return;
        }
        if (!adapter.isEnabled()) { listener.onError(context.getString(R.string.bluetooth_turned_off)); return; }
        registerReceiver();
        try {
            publishBondedDevices();
            startBleDiscovery();
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            if (!adapter.startDiscovery()) listener.onError(context.getString(R.string.bluetooth_discovery_failed));
        } catch (SecurityException exception) {
            listener.onError(context.getString(R.string.bluetooth_permission_revoked));
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
            listener.onError(context.getString(R.string.bluetooth_permission_revoked));
        }
    }

    @SuppressLint("MissingPermission")
    private void publishBondedDevices() {
        for (BluetoothDevice bonded : adapter.getBondedDevices()) publishPeer(bonded, false);
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
    private void publishPeer(BluetoothDevice peer, boolean verifiedAppPeer) {
        if (peer == null || !hasConnectPermission()) return;
        try {
            if (!verifiedAppPeer && !isPhone(peer)) return;
            listener.onDeviceFound(new Device(peer.getAddress(), peer.getName(),
                    Device.Transport.BLUETOOTH, false));
        } catch (SecurityException exception) {
            listener.onError(context.getString(R.string.bluetooth_permission_revoked));
        }
    }

    /** Classic discovery exposes a device class; only phone-class peers are useful here. */
    @SuppressLint("MissingPermission")
    private static boolean isPhone(BluetoothDevice peer) {
        BluetoothClass deviceClass = peer.getBluetoothClass();
        return deviceClass != null
                && deviceClass.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE;
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
