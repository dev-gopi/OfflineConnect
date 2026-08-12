package com.devgopi.offlineconnect;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {
    private static final String STATE_UNLOCKED = "main_unlocked";
    private View mainContent;
    private boolean unlocked;

    private final ActivityResultLauncher<Intent> unlockApp = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    unlocked = true;
                    mainContent.setVisibility(View.VISIBLE);
                } else {
                    finishAffinity();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mainContent = findViewById(R.id.mainContent);
        unlocked = savedInstanceState != null && savedInstanceState.getBoolean(STATE_UNLOCKED);
        applySystemBarInsets();
        findViewById(R.id.btnFindDevices).setOnClickListener(view ->
                startActivity(new Intent(this,
                        com.devgopi.offlineconnect.ui.DevicesActivity.class)));
        findViewById(R.id.btnMyDevice).setOnClickListener(view ->
                new AlertDialog.Builder(this)
                        .setTitle(R.string.device_details)
                        .setMessage(getString(R.string.device_details_format,
                                android.os.Build.MODEL, android.os.Build.VERSION.RELEASE))
                        .setPositiveButton(R.string.close, null)
                        .show());
        findViewById(R.id.btnSettings).setOnClickListener(view ->
                startActivity(new Intent(this,
                        com.devgopi.offlineconnect.ui.SettingsActivity.class)));
        enforceAppLock();
    }

    private void enforceAppLock() {
        if (!com.devgopi.offlineconnect.security.AppLockManager.isEnabled(this) || unlocked) {
            mainContent.setVisibility(View.VISIBLE);
            return;
        }
        mainContent.setVisibility(View.INVISIBLE);
        Intent intent = com.devgopi.offlineconnect.security.AppLockManager
                .createAuthenticationIntent(this, getString(R.string.unlock_app),
                        getString(R.string.unlock_app_description));
        if (intent == null) {
            // Do not permanently lock a user out if the device credential was removed.
            com.devgopi.offlineconnect.security.AppLockManager.setEnabled(this, false);
            mainContent.setVisibility(View.VISIBLE);
            return;
        }
        unlockApp.launch(intent);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(STATE_UNLOCKED, unlocked);
        super.onSaveInstanceState(state);
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.mainRoot);
        int left = root.getPaddingLeft();
        int top = root.getPaddingTop();
        int right = root.getPaddingRight();
        int bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(left + bars.left, top + bars.top,
                    right + bars.right, bottom + bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
