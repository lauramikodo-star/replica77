package com.applisto.appcloner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import java.io.File;

public class DefaultProvider extends AbstractContentProvider {

    private static final String TAG = "DefaultProvider";

    // Dynamic receiver reference
    private static DataExportReceiver sExportReceiver;

    // =============================
    // Android ContentProvider Lifecycle: onCreate()
    // =============================
    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            Log.e(TAG, "Context is null in onCreate()");
            return false;
        }

        Log.i(TAG, "=== DEFAULT PROVIDER INITIALIZATION STARTED ===");
        Log.i(TAG, "Package: " + context.getPackageName());

        try {
            // 1. CRITICAL: EARLY CAMERA HOOK INSTALLATION - MUST BE FIRST
            Log.i(TAG, "Installing camera hooks...");
            CameraHook.install(context);
            Log.i(TAG, "Camera hooks installed successfully");

            // 2. Fake camera app support for activity interception
            Log.i(TAG, "Setting up FakeCameraAppSupport...");
            FakeCameraAppSupport.setup(context);
            Log.i(TAG, "FakeCameraAppSupport setup completed");

            // 3. Network proxy initialization
            Log.i(TAG, "Initializing network proxy...");
            Socks5ProxyHook.initEarly(context);
            Log.i(TAG, "Network proxy initialized");

            // 4. Data manager for import/export functionality
            Log.i(TAG, "Initializing AppDataManager...");
            new AppDataManager(context, context.getPackageName(), false);
            Log.i(TAG, "AppDataManager initialized");

            // 5. Register export broadcast receiver
            Log.i(TAG, "Registering export receiver...");
            // Use dynamic registration as backup for reliability
            if (sExportReceiver == null) {
                sExportReceiver = new DataExportReceiver();
                IntentFilter filter = new IntentFilter(DataExportReceiver.ACTION_EXPORT_DATA);
                // Handle Android 13+ receiver flag
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                     context.registerReceiver(sExportReceiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                     context.registerReceiver(sExportReceiver, filter);
                }
            }
            Log.i(TAG, "Export receiver registered dynamically");

            Log.i(TAG, "=== DEFAULT PROVIDER INITIALIZED SUCCESSFULLY ===");
            Log.i(TAG, "All hooks and services are now active");
            return true;

        } catch (Throwable t) {
            Log.e(TAG, "=== CRITICAL FAILURE DURING PROVIDER INIT ===", t);
            return false;
        }
    }

    // Optional: Clean up on process death (if needed)
    @Override
    public void shutdown() {
        super.shutdown();
        Context context = getContext();
        if (context != null && sExportReceiver != null) {
            try {
                context.unregisterReceiver(sExportReceiver);
                Log.d(TAG, "Export receiver unregistered");
            } catch (Exception ignored) {}
        }
    }
}