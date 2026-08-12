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
import android.widget.EditText;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

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
import com.devgopi.offlineconnect.database.AppDatabase;
import com.devgopi.offlineconnect.database.RecentDeviceEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays nearby peers and owns discovery only while this screen is active. */
public final class DevicesActivity extends AppCompatActivity {
    private static final String TAG = "DevicesActivity";
    private final List<Device> devices = new ArrayList<>();
    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();
    private ArrayAdapter<Device> adapter;
    private DeviceDiscoveryManager discoveryManager;
    private TextView status;
    private Button scanButton;
    private View scanProgress;
    private boolean discovering;

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
        scanButton = findViewById(R.id.btnScan);
        scanProgress = findViewById(R.id.progressDiscovery);
        adapter = new DeviceAdapter();
        list.setAdapter(adapter);
        EditText search = findViewById(R.id.editDeviceSearch);
        SearchClearController.attach(search, findViewById(R.id.btnClearDeviceSearch));
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.getFilter().filter(s);
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        loadRecentDevices();

        discoveryManager = new DeviceDiscoveryManager(this, new DeviceDiscoveryManager.Listener() {
            @Override public void onDevicesChanged(List<Device> found) {
                for (Device item : found) {
                    int index = devices.indexOf(item);
                    if (index >= 0) devices.set(index, item); else devices.add(item);
                }
                adapter.notifyDataSetChanged();
            }
            @Override public void onDiscoveryChanged(boolean active) {
                discovering = active;
                status.setText(active ? R.string.scanning : R.string.scan_complete);
                scanButton.setText(active ? R.string.stop_scan : R.string.scan);
                scanProgress.setVisibility(active ? View.VISIBLE : View.GONE);
            }
            @Override public void onError(String message) {
                status.setText(message);
                Toast.makeText(DevicesActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
        scanButton.setOnClickListener(view -> {
            if (discovering) discoveryManager.stopDiscovery();
            else requestPermissionsAndScan();
        });
        findViewById(R.id.btnBackDevices).setOnClickListener(view -> finish());
        list.setOnItemClickListener((parent, view, position, id) -> {
            Intent intent = new Intent(this, ChatActivity.class);
            Device selected = adapter.getItem(position);
            if (selected == null) return;
            intent.putExtra(ChatActivity.EXTRA_DEVICE, selected);
            databaseExecutor.execute(() -> AppDatabase.getInstance(this).recentDeviceDao()
                    .upsert(RecentDeviceEntity.from(selected)));
            startActivity(intent);
        });

        // Wait until view creation is complete before showing the system dialog.
        scanButton.post(this::requestPermissionsAndScan);
    }

    private void loadRecentDevices() {
        databaseExecutor.execute(() -> {
            List<RecentDeviceEntity> recent = AppDatabase.getInstance(this)
                    .recentDeviceDao().getRecent();
            runOnUiThread(() -> {
                for (RecentDeviceEntity entity : recent) {
                    Device item = entity.toDevice();
                    if (!devices.contains(item)) devices.add(item);
                }
                adapter.notifyDataSetChanged();
            });
        });
    }

    private final class DeviceAdapter extends ArrayAdapter<Device> {
        DeviceAdapter() { super(DevicesActivity.this, R.layout.item_device, devices); }

        @Override public View getView(int position, View reusable, ViewGroup parent) {
            View row = reusable;
            if (row == null) row = getLayoutInflater().inflate(R.layout.item_device, parent, false);
            Device item = getItem(position);
            ((TextView) row.findViewById(R.id.txtDeviceName)).setText(item.getName());
            int transport = item.getTransport() == Device.Transport.BLUETOOTH
                    ? R.string.bluetooth_transport : R.string.wifi_direct_transport;
            String state = item.isConnected() ? getString(R.string.connected)
                    : getString(R.string.connection_ready);
            ((TextView) row.findViewById(R.id.txtDeviceTransport)).setText(
                    getString(transport) + " · " + state);
            return row;
        }
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
        databaseExecutor.shutdown();
        super.onDestroy();
    }
}
