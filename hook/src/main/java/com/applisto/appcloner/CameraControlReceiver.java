package com.applisto.appcloner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class CameraControlReceiver extends BroadcastReceiver {

    private static final String TAG = "CameraControlReceiver";

    public static final String ACTION_ROTATE_CLOCKWISE = "com.applisto.appcloner.ACTION_ROTATE_CLOCKWISE";
    public static final String ACTION_ROTATE_COUNTERCLOCKWISE = "com.applisto.appcloner.ACTION_ROTATE_COUNTERCLOCKWISE";
    public static final String ACTION_FLIP_HORIZONTALLY = "com.applisto.appcloner.ACTION_FLIP_HORIZONTALLY";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            CameraHook.install(context.getApplicationContext());

            String action = intent != null ? intent.getAction() : null;
            Log.i(TAG, "onReceive: " + action);

            if (ACTION_ROTATE_CLOCKWISE.equals(action)) {
                CameraHook.applyRotation(90);
            } else if (ACTION_ROTATE_COUNTERCLOCKWISE.equals(action)) {
                CameraHook.applyRotation(-90);
            } else if (ACTION_FLIP_HORIZONTALLY.equals(action)) {
                CameraHook.applyFlipHorizontally();
            } else if (CameraHook.ACTION_ZOOM_IN.equals(action)) {      // added
                CameraHook.applyZoomIn();
            } else if (CameraHook.ACTION_ZOOM_OUT.equals(action)) {     // added
                CameraHook.applyZoomOut();
            } else {
                Log.w(TAG, "Unexpected intent action: " + action);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in onReceive", t);
        }
    }
}
