package com.applisto.appcloner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.io.IOException;

public class DataExportReceiver extends BroadcastReceiver {
    private static final String TAG = "DataExportReceiver";
    public static final String ACTION_EXPORT_DATA = "com.applisto.appcloner.ACTION_EXPORT_DATA";

    // Static lock to prevent concurrent exports (e.g. from double receiver registration)
    private static final Object LOCK = new Object();
    private static boolean sIsExporting = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_EXPORT_DATA.equals(intent.getAction())) {
            Log.i(TAG, "Received export data request.");

            synchronized (LOCK) {
                if (sIsExporting) {
                    Log.w(TAG, "Export already in progress, skipping duplicate request.");
                    return;
                }
                sIsExporting = true;
            }

            try {
                String packageName = context.getPackageName();
                Log.i(TAG, "Starting export for package: " + packageName);

                // Instantiate AppDataManager
                AppDataManager dataManager = new AppDataManager(context, packageName, false);

            // Perform the export
            Intent resultIntent = new Intent("com.appcloner.replica.EXPORT_COMPLETED");
            resultIntent.setPackage("com.appcloner.replica");
            resultIntent.putExtra("exported_package", packageName);

            try {
                File exportedFile = dataManager.exportAppData();
                if (exportedFile != null) {
                    resultIntent.putExtra("export_success", true);
                    resultIntent.putExtra("export_path", exportedFile.getAbsolutePath());
                    Log.d(TAG, "Export successful, notifying cloner app at: " + exportedFile.getAbsolutePath());
                } else {
                    resultIntent.putExtra("export_success", true);
                    resultIntent.putExtra("export_path", "Downloads");
                    Log.d(TAG, "Export successful, notifying cloner app.");
                }
            } catch (IOException e) {
                Log.e(TAG, "Export failed", e);
                resultIntent.putExtra("export_success", false);
                resultIntent.putExtra("error_message", e.getMessage());
            }
            context.sendBroadcast(resultIntent);

            } finally {
                synchronized (LOCK) {
                    sIsExporting = false;
                }
            }
        }
    }
}