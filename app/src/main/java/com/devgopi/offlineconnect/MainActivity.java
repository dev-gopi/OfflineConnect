package com.devgopi.offlineconnect;

import android.os.Bundle;
import android.content.Intent;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
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
