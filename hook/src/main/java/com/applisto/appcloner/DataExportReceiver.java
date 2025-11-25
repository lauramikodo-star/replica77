package com.applisto.appcloner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.io.File;

public class DataExportReceiver extends BroadcastReceiver {
    private static final String TAG = "DataExportReceiver";
    private static final String ACTION_EXPORT_DATA = "com.applisto.appcloner.ACTION_EXPORT_DATA";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_EXPORT_DATA.equals(intent.getAction())) {
            Log.d(TAG, "Received export data request.");
            String packageName = context.getPackageName();

            // Instantiate AppDataManager
            AppDataManager dataManager = new AppDataManager(context, packageName, false);

            // Perform the export
            File exportedFile = dataManager.exportAppData();

            // Send result back to the cloner app
            Intent resultIntent = new Intent("com.appcloner.replica.EXPORT_COMPLETED");
            resultIntent.setPackage("com.appcloner.replica");
            resultIntent.putExtra("exported_package", packageName);
            if (exportedFile != null) {
                resultIntent.putExtra("export_success", true);
                resultIntent.putExtra("export_path", exportedFile.getAbsolutePath());
                Log.d(TAG, "Export successful, notifying cloner app at: " + exportedFile.getAbsolutePath());
            } else {
                resultIntent.putExtra("export_success", false);
                resultIntent.putExtra("error_message", "AppDataManager.exportAppData() returned null");
                Log.e(TAG, "Export failed: AppDataManager returned null.");
            }
            context.sendBroadcast(resultIntent);
        }
    }
}