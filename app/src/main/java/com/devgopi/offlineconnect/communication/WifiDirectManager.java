package com.devgopi.offlineconnect.communication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.WifiManager;
import android.location.LocationManager;
import android.provider.Settings;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.devgopi.offlineconnect.model.Device;
import com.devgopi.offlineconnect.R;

import java.net.InetAddress;

/** Wraps Wi-Fi Direct discovery, connection requests, and broadcast registration. */
public final class WifiDirectManager implements AutoCloseable {
    private static final int MAX_BUSY_RETRIES = 2;
    private static final long BUSY_RETRY_DELAY_MS = 1_200L;
    private static final int MAX_CONNECT_RETRIES = 3;
    private static final long CONNECT_RETRY_DELAY_MS = 750L;
    private static final long CONNECTION_TIMEOUT_MS = 30_000L;
    private static final int CONNECTION_INFO_RETRIES = 5;
    public interface Listener {
        void onDeviceFound(Device device);
        void onConnectionChanged(boolean connected);
        default void onConnectionReady(InetAddress groupOwnerAddress, boolean groupOwner) { }
        void onDiscoveryChanged(boolean discovering);
        void onError(String message);
    }

    private final Context context;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final WifiManager wifiManager;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;
    private String pendingDeviceAddress;
    private int connectionGeneration;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                requestPeers();
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                boolean connected = isConnected(intent);
                listener.onConnectionChanged(connected);
                if (connected) {
                    clearConnectionTimeout();
                    requestConnectionInfo(CONNECTION_INFO_RETRIES);
                }
            } else if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED);
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    listener.onError(context.getString(R.string.wifi_direct_turned_off));
                }
            }
        }
    };

    public WifiDirectManager(@NonNull Context context, @NonNull Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        wifiManager = ContextCompat.getSystemService(this.context, WifiManager.class);
        WifiP2pManager foundManager = ContextCompat.getSystemService(
                this.context, WifiP2pManager.class);
        WifiP2pManager.Channel foundChannel = null;
        if (foundManager != null) {
            try {
                foundChannel = foundManager.initialize(this.context, Looper.getMainLooper(),
                        () -> listener.onError(context.getString(R.string.wifi_direct_channel_lost)));
            } catch (SecurityException exception) {
                // A malformed manifest or vendor service must not crash device discovery.
                listener.onError(context.getString(R.string.wifi_direct_permission_unavailable));
            }
        }
        manager = foundManager;
        channel = foundChannel;
    }

    public boolean isSupported() { return manager != null && channel != null; }

    @SuppressLint("MissingPermission") // ready() verifies the version-specific runtime permission.
    public void startDiscovery() {
        if (!ready()) return;
        registerReceiver();
        discoverPeers(MAX_BUSY_RETRIES);
    }

    /** Registers for an incoming Wi-Fi Direct invitation without starting peer discovery. */
    public void prepareForConnection() {
        if (!ready()) return;
        registerReceiver();
        // A group may already exist before this chat opens; query it immediately instead of
        // relying only on a future broadcast from the vendor Wi-Fi implementation.
        requestConnectionInfo(CONNECTION_INFO_RETRIES);
    }

    @SuppressLint("MissingPermission")
    private void discoverPeers(int retriesRemaining) {
        try {
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    listener.onDiscoveryChanged(true);
                }

                @Override public void onFailure(int reason) {
                    if (reason == WifiP2pManager.BUSY && retriesRemaining > 0) {
                        mainHandler.postDelayed(() -> discoverPeers(retriesRemaining - 1),
                                BUSY_RETRY_DELAY_MS);
                        return;
                    }
                    listener.onDiscoveryChanged(false);
                    listener.onError(reasonMessage(reason));
                }
            });
        } catch (SecurityException exception) {
            pendingDeviceAddress = null;
            listener.onError(context.getString(R.string.nearby_permission_revoked));
        }
    }

    @SuppressLint("MissingPermission") // ready() verifies the version-specific runtime permission.
    public void connect(String deviceAddress) {
        if (!ready()) return;
        registerReceiver();
        if (deviceAddress == null || deviceAddress.trim().isEmpty()) {
            listener.onError(context.getString(R.string.wifi_device_address_invalid));
            return;
        }
        pendingDeviceAddress = deviceAddress;
        int generation = ++connectionGeneration;
        try {
            manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { connectAttempt(generation, 0); }
                @Override public void onFailure(int reason) { connectAttempt(generation, 0); }
            });
        } catch (SecurityException exception) {
            pendingDeviceAddress = null;
            listener.onError(context.getString(R.string.nearby_permission_revoked));
        }
    }

    @SuppressLint("MissingPermission")
    private void connectAttempt(int generation, int attempt) {
        if (generation != connectionGeneration || pendingDeviceAddress == null || !ready()) return;
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = pendingDeviceAddress;
        // A neutral intent lets Android deterministically negotiate the owner between phones.
        config.groupOwnerIntent = 7;
        try {
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    scheduleConnectionTimeout(generation);
                }
                @Override public void onFailure(int reason) {
                    if ((reason == WifiP2pManager.BUSY || reason == WifiP2pManager.ERROR)
                            && attempt + 1 < MAX_CONNECT_RETRIES) {
                        recoverAndRetry(generation, attempt + 1);
                        return;
                    }
                    pendingDeviceAddress = null;
                    listener.onError(context.getString(R.string.wifi_operation_failed,
                            context.getString(R.string.wifi_connection_request),
                            reasonMessage(reason)));
                }
            });
        } catch (SecurityException exception) {
            pendingDeviceAddress = null;
            listener.onError(context.getString(R.string.nearby_permission_revoked));
        }
    }

    /**
     * Vendor Wi-Fi stacks commonly retain an incomplete invitation or stale group after ERROR.
     * Serialize cleanup callbacks before retrying so connect() does not race cancel/removeGroup().
     */
    @SuppressLint("MissingPermission")
    private void recoverAndRetry(int generation, int nextAttempt) {
        if (generation != connectionGeneration || pendingDeviceAddress == null) return;
        try {
            manager.cancelConnect(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { removeStaleGroupAndRetry(generation, nextAttempt); }
                @Override public void onFailure(int reason) {
                    removeStaleGroupAndRetry(generation, nextAttempt);
                }
            });
        } catch (SecurityException exception) {
            pendingDeviceAddress = null;
            listener.onError(context.getString(R.string.nearby_permission_revoked));
        }
    }

    @SuppressLint("MissingPermission")
    private void removeStaleGroupAndRetry(int generation, int nextAttempt) {
        if (generation != connectionGeneration || pendingDeviceAddress == null) return;
        try {
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { scheduleConnectRetry(generation, nextAttempt); }
                @Override public void onFailure(int reason) {
                    // NO_GROUP and vendor ERROR both mean there is no useful group to preserve.
                    scheduleConnectRetry(generation, nextAttempt);
                }
            });
        } catch (SecurityException exception) {
            pendingDeviceAddress = null;
            listener.onError(context.getString(R.string.nearby_permission_revoked));
        }
    }

    private void scheduleConnectRetry(int generation, int nextAttempt) {
        mainHandler.postDelayed(() -> connectAttempt(generation, nextAttempt),
                CONNECT_RETRY_DELAY_MS);
    }

    public void stopDiscovery() {
        try {
            if (manager != null && channel != null && hasPermission()) {
                manager.stopPeerDiscovery(channel,
                        action(context.getString(R.string.wifi_discovery_stopped), false));
            }
        } catch (SecurityException exception) {
            pendingDeviceAddress = null;
            listener.onError(context.getString(R.string.nearby_permission_revoked));
        }
    }

    private boolean ready() {
        if (!isSupported()) { listener.onError(context.getString(R.string.wifi_direct_unsupported)); return false; }
        if (wifiManager == null || !wifiManager.isWifiEnabled()) {
            listener.onError(context.getString(com.devgopi.offlineconnect.R.string.wifi_disabled));
            return false;
        }
        if (!hasPermission()) { listener.onError(context.getString(R.string.nearby_permission_required)); return false; }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !isLocationEnabled()) {
            listener.onError(context.getString(R.string.location_services_required));
            return false;
        }
        return true;
    }

    private boolean isLocationEnabled() {
        LocationManager location = ContextCompat.getSystemService(context, LocationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return location != null && location.isLocationEnabled();
        }
        try {
            return Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF;
        } catch (Settings.SettingNotFoundException exception) {
            return false;
        }
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    @SuppressLint("MissingPermission") // ready() verifies the version-specific runtime permission.
    private void requestPeers() {
        if (!ready()) return;
        try {
            manager.requestPeers(channel, peers -> {
                for (WifiP2pDevice peer : peers.getDeviceList()) {
                    listener.onDeviceFound(new Device(peer.deviceAddress, peer.deviceName,
                            Device.Transport.WIFI_DIRECT,
                            peer.status == WifiP2pDevice.CONNECTED));
                }
            });
        } catch (SecurityException exception) {
            listener.onError(context.getString(R.string.nearby_permission_revoked));
        }
    }

    @SuppressLint("MissingPermission")
    private void requestConnectionInfo(int retriesRemaining) {
        if (!ready()) return;
        try {
            manager.requestConnectionInfo(channel, info -> {
                if (info.groupFormed && info.groupOwnerAddress != null) {
                    pendingDeviceAddress = null;
                    listener.onConnectionReady(info.groupOwnerAddress, info.isGroupOwner);
                } else if (info.groupFormed && retriesRemaining > 0) {
                    mainHandler.postDelayed(() -> requestConnectionInfo(retriesRemaining - 1),
                            500L);
                }
            });
        } catch (SecurityException exception) {
            listener.onError(context.getString(R.string.nearby_permission_revoked));
        }
    }

    @SuppressLint("MissingPermission")
    private void cancelConnectQuietly() {
        try {
            manager.cancelConnect(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { }
                @Override public void onFailure(int reason) { }
            });
        } catch (SecurityException ignored) { }
    }

    private void scheduleConnectionTimeout(int generation) {
        mainHandler.postDelayed(() -> {
            if (generation != connectionGeneration || pendingDeviceAddress == null) return;
            pendingDeviceAddress = null;
            cancelConnectQuietly();
            listener.onConnectionChanged(false);
            listener.onError(context.getString(R.string.wifi_connection_timeout));
        }, CONNECTION_TIMEOUT_MS);
    }

    private void clearConnectionTimeout() {
        connectionGeneration++;
        pendingDeviceAddress = null;
    }

    private WifiP2pManager.ActionListener action(String successMessage, boolean discovery) {
        return new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                if (discovery) listener.onDiscoveryChanged(true);
            }
            @Override public void onFailure(int reason) {
                listener.onDiscoveryChanged(false);
                listener.onError(context.getString(R.string.wifi_operation_failed,
                        successMessage, reasonMessage(reason)));
            }
        };
    }

    private String reasonMessage(int reason) {
        if (reason == WifiP2pManager.BUSY) {
            return context.getString(com.devgopi.offlineconnect.R.string.wifi_direct_busy);
        }
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
            return context.getString(com.devgopi.offlineconnect.R.string.wifi_direct_unsupported);
        }
        return context.getString(R.string.wifi_operation_unknown_error);
    }

    private boolean hasPermission() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private static boolean isConnected(Intent intent) {
        NetworkInfo info = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
        return info != null && info.isConnected();
    }

    @Override public void close() {
        connectionGeneration++;
        pendingDeviceAddress = null;
        mainHandler.removeCallbacksAndMessages(null);
        stopDiscovery();
        if (receiverRegistered) {
            context.unregisterReceiver(receiver);
            receiverRegistered = false;
        }
    }
}
