package com.devgopi.offlineconnect.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.devgopi.offlineconnect.R;
import com.devgopi.offlineconnect.communication.DeviceDiscoveryManager;
import com.devgopi.offlineconnect.model.Device;

import java.util.ArrayList;
import java.util.List;

/** Displays nearby peers and owns discovery only while this screen is active. */
public final class DevicesActivity extends AppCompatActivity {
    private static final String TAG = "DevicesActivity";
    private final List<Device> devices = new ArrayList<>();
    private ArrayAdapter<Device> adapter;
    private DeviceDiscoveryManager discoveryManager;
    private TextView status;

    private final ActivityResultLauncher<String[]> permissionRequest =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (!hasRequiredPermissions()) {
                    status.setText(R.string.permission_required);
                } else {
                    startDiscoverySafely();
                }
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_devices);
        applySystemBarInsets();
        status = findViewById(R.id.txtDiscoveryStatus);
        ListView list = findViewById(R.id.listDevices);
        list.setEmptyView(findViewById(R.id.txtDevicesEmpty));
        Button scan = findViewById(R.id.btnScan);
        adapter = new ArrayAdapter<>(this, R.layout.item_device, R.id.txtDeviceName, devices);
        list.setAdapter(adapter);

        discoveryManager = new DeviceDiscoveryManager(this, new DeviceDiscoveryManager.Listener() {
            @Override public void onDevicesChanged(List<Device> found) {
                devices.clear(); devices.addAll(found); adapter.notifyDataSetChanged();
            }
            @Override public void onDiscoveryChanged(boolean active) {
                status.setText(active ? R.string.scanning : R.string.scan_complete);
            }
            @Override public void onError(String message) {
                Toast.makeText(DevicesActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
        scan.setOnClickListener(view -> requestPermissionsAndScan());
        list.setOnItemClickListener((parent, view, position, id) -> {
            Intent intent = new Intent(this, ChatActivity.class);
            intent.putExtra(ChatActivity.EXTRA_DEVICE, devices.get(position));
            startActivity(intent);
        });

        // Wait until view creation is complete before showing the system dialog.
        scan.post(this::requestPermissionsAndScan);
    }

    /** Keeps controls clear of gesture navigation and three-button navigation bars. */
    private void applySystemBarInsets() {
        View root = findViewById(R.id.devicesRoot);
        int initialLeft = root.getPaddingLeft();
        int initialTop = root.getPaddingTop();
        int initialRight = root.getPaddingRight();
        int initialBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(initialLeft + bars.left, initialTop + bars.top,
                    initialRight + bars.right, initialBottom + bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void requestPermissionsAndScan() {
        if (hasRequiredPermissions()) {
            startDiscoverySafely();
            return;
        }
        status.setText(R.string.permission_required);
        permissionRequest.launch(requiredPermissions());
    }

    private boolean hasRequiredPermissions() {
        for (String permission : requiredPermissions()) {
            if (!granted(permission)) return false;
        }
        return true;
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.NEARBY_WIFI_DEVICES};
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION};
        }
        return new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startDiscoverySafely() {
        if (discoveryManager == null || isFinishing() || isDestroyed()) return;
        try {
            discoveryManager.startDiscovery();
        } catch (RuntimeException exception) {
            // Vendor radio implementations occasionally fail; keep the screen usable.
            Log.e(TAG, "Nearby-device discovery failed", exception);
            status.setText(R.string.scan_failed);
            Toast.makeText(this, R.string.scan_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onStop() {
        if (discoveryManager != null) discoveryManager.stopDiscovery();
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (discoveryManager != null) discoveryManager.close();
        super.onDestroy();
    }
}
