package com.devgopi.offlineconnect.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.devgopi.offlineconnect.R;
import com.devgopi.offlineconnect.security.AppLockManager;

/** Security settings backed by Android's system credential confirmation screen. */
public final class SettingsActivity extends AppCompatActivity {
    private SwitchCompat appLockSwitch;
    private TextView appLockStatus;
    private boolean updatingSwitch;

    private final ActivityResultLauncher<Intent> enableLock = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                boolean enabled = result.getResultCode() == Activity.RESULT_OK;
                AppLockManager.setEnabled(this, enabled);
                renderState();
                Toast.makeText(this, enabled ? R.string.app_lock_enabled_message
                        : R.string.app_lock_not_enabled, Toast.LENGTH_SHORT).show();
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        applySystemBarInsets();
        appLockSwitch = findViewById(R.id.switchAppLock);
        appLockStatus = findViewById(R.id.txtAppLockStatus);
        findViewById(R.id.btnBackSettings).setOnClickListener(view -> finish());
        appLockSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (updatingSwitch) return;
            if (!checked) {
                AppLockManager.setEnabled(this, false);
                renderState();
                return;
            }
            requestEnableLock();
        });
        renderState();
    }

    private void requestEnableLock() {
        if (!AppLockManager.isDeviceSecure(this)) {
            renderState();
            Toast.makeText(this, R.string.device_lock_required, Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = AppLockManager.createAuthenticationIntent(this,
                getString(R.string.enable_app_lock), getString(R.string.confirm_device_lock));
        if (intent == null) {
            renderState();
            Toast.makeText(this, R.string.app_lock_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        enableLock.launch(intent);
    }

    private void renderState() {
        boolean enabled = AppLockManager.isEnabled(this);
        updatingSwitch = true;
        appLockSwitch.setChecked(enabled);
        updatingSwitch = false;
        appLockStatus.setText(enabled ? R.string.app_lock_status_enabled
                : R.string.app_lock_status_disabled);
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.settingsRoot);
        int left = root.getPaddingLeft();
        int top = root.getPaddingTop();
        int right = root.getPaddingRight();
        int bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(left + bars.left, top + bars.top,
                    right + bars.right, bottom + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
