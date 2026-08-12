package com.devgopi.offlineconnect.security;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/** Stores the app-lock preference and delegates authentication to Android's secure keyguard. */
public final class AppLockManager {
    private static final String PREFERENCES = "offline_connect_security";
    private static final String APP_LOCK_ENABLED = "app_lock_enabled";

    private AppLockManager() { }

    public static boolean isEnabled(@NonNull Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(APP_LOCK_ENABLED, false);
    }

    public static void setEnabled(@NonNull Context context, boolean enabled) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putBoolean(APP_LOCK_ENABLED, enabled)
                .apply();
    }

    public static boolean isDeviceSecure(@NonNull Context context) {
        KeyguardManager keyguard = ContextCompat.getSystemService(context, KeyguardManager.class);
        return keyguard != null && keyguard.isDeviceSecure();
    }

    @SuppressWarnings("deprecation")
    @Nullable
    public static Intent createAuthenticationIntent(@NonNull Context context, String title,
                                                    String description) {
        KeyguardManager keyguard = ContextCompat.getSystemService(context, KeyguardManager.class);
        if (keyguard == null || !keyguard.isDeviceSecure()) return null;
        return keyguard.createConfirmDeviceCredentialIntent(title, description);
    }
}
