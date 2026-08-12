package com.devgopi.offlineconnect;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

public class SplashActivity extends AppCompatActivity {

    /** Keeps the branded screen readable without making startup feel slow. */
    private static final long BRANDING_DURATION_MS = 850L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean navigationStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        animateBranding();
        mainHandler.postDelayed(this::openMainScreen, BRANDING_DURATION_MS);
    }

    private void animateBranding() {
        View branding = findViewById(R.id.splashBranding);
        branding.setAlpha(0f);
        branding.setTranslationY(18f);
        branding.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(450L)
                .start();
    }

    private void openMainScreen() {
        if (navigationStarted || isFinishing() || isDestroyed()) {
            return;
        }
        navigationStarted = true;
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
