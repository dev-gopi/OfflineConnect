package com.devgopi.offlineconnect;

import android.os.Bundle;
import android.content.Intent;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.btnFindDevices).setOnClickListener(view ->
                startActivity(new Intent(this,
                        com.devgopi.offlineconnect.ui.DevicesActivity.class)));
        findViewById(R.id.btnMyDevice).setOnClickListener(view ->
                Toast.makeText(this, android.os.Build.MODEL, Toast.LENGTH_SHORT).show());
    }
}
