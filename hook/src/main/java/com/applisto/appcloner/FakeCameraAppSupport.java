package com.applisto.appcloner;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import java.io.OutputStream;
import java.lang.reflect.Method;

public final class FakeCameraAppSupport extends ExecStartActivityHook {
    private static final String TAG = "FakeCameraAppSupport";

    // Runtime state for returning a result to the original caller
    private static Activity sActivity;
    private static int      sRequestCode;
    private static Uri      sUri;

    private static FakeCameraAppSupport INSTANCE;

    /** Call this once early (e.g., Application.onCreate). */
    public static void setup(Context ctx) {
        if (INSTANCE != null) {
            Log.d(TAG, "FakeCameraAppSupport already setup");
            return;
        }
        INSTANCE = new FakeCameraAppSupport();
        INSTANCE.install(ctx);   // registers into execStartActivity chain

        // Ensure CameraHook is also installed
        CameraHook.install(ctx);

        Log.i(TAG, "FakeCameraAppSupport installed successfully");
    }

    @Override
    protected boolean onExecStartActivity(ExecStartActivityHook.ExecStartActivityArgs args) {
        if (args == null || args.intent == null) return true;

        String activityName = ExecStartActivityHook.getActivityName(args.intent);
        Log.d(TAG, "onExecStartActivity: " + activityName);

        // Only intercept camera capture intents (and chooser wrapping it)
        if (!isCameraCapture(args.intent)) {
            Log.d(TAG, "Not a camera capture intent, allowing: " + activityName);
            return true;
        }

        if (!(args.who instanceof Activity)) {
            Log.w(TAG, "Camera intent from non-Activity context – ignored");
            return true;
        }
        Activity caller = (Activity) args.who;

        // The output URI the original app expects
        Uri output = args.intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
        if (output == null) {
            Log.w(TAG, "Camera intent without EXTRA_OUTPUT – allowing real camera");
            return true;
        }

        Log.i(TAG, "INTERCEPTING CAMERA INTENT with output: " + output);

        // Remember for result delivery
        sActivity    = caller;
        sRequestCode = args.requestCode;
        sUri         = output;

        // Launch our picker UI
        try {
            Class<?> pickerClass = Class.forName("com.applisto.appcloner.FakeCameraActivity");
            Intent fake = new Intent(caller, pickerClass);
            fake.putExtra("fake_camera_app", true);
            fake.putExtra(MediaStore.EXTRA_OUTPUT, output);
            fake.putExtra("requestCode", args.requestCode);
            fake.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            caller.startActivity(fake);

            // REMOVED: showTempNotification(caller); // This was causing the crash
            Log.i(TAG, "Fake picker launched successfully - CAMERA INTERCEPTED");

        } catch (ClassNotFoundException e) {
            Log.e(TAG, "Picker class not found", e);
            return true;  // Fallback to real camera if missing
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch fake picker", e);
            return true;
        }

        // Veto the real camera launch
        Log.i(TAG, "VETOING REAL CAMERA LAUNCH");
        return false;
    }

    private boolean isCameraCapture(Intent i) {
        if (i == null) return false;
        String a = i.getAction();
        if (a == null) return false;
        Log.d(TAG, "isCameraCapture: action=" + a);

        if (MediaStore.ACTION_IMAGE_CAPTURE.equals(a) ||
            "android.media.action.IMAGE_CAPTURE_SECURE".equals(a)) {
            Uri output = i.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
            Log.d(TAG, "isCameraCapture: output Uri=" + output);
            return output != null;
        }
        if (Intent.ACTION_CHOOSER.equals(a)) {
            // Null-safe check matching original
            Bundle extras = i.getExtras();
            if (extras != null) {
                Intent inner = extras.getParcelable(Intent.EXTRA_INTENT);
                if (inner != null) {
                    return isCameraCapture(inner);
                }
            }
        }
        return false;
    }

    /** Called by FakeCameraActivity after user selects an image. */
    public static void setImage(final Bitmap bmp, final byte[] jpegBytes) {
        if (bmp == null && jpegBytes == null) {
            Log.w(TAG, "setImage: no data");
            // Deliver cancelled result and clear state
            deliverResult(false);
            return;
        }

        final Activity caller = sActivity;
        final Uri outputUri = sUri;
        final int requestCode = sRequestCode;

        if (outputUri == null) {
            Log.w(TAG, "setImage: no output Uri saved, cannot write image");
            deliverResult(false);
            return;
        }

        // Write image on background thread to avoid blocking UI
        new Thread(() -> {
            boolean writeOk = false;
            OutputStream out = null;
            try {
                Context ctx = (caller != null) ? caller : AppCloner.getAppContextFallback();
                if (ctx == null) {
                    Log.e(TAG, "No context available to open output stream");
                    writeOk = false;
                } else {
                    out = ctx.getContentResolver().openOutputStream(outputUri);
                    if (out == null) {
                        Log.e(TAG, "Unable to open output stream for uri: " + outputUri);
                        writeOk = false;
                    } else {
                        if (jpegBytes != null) {
                            out.write(jpegBytes);
                        } else {
                            // compress bitmap to JPEG
                            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                                Log.e(TAG, "Bitmap.compress returned false");
                                writeOk = false;
                            }
                        }
                        out.flush();
                        writeOk = true;
                        Log.i(TAG, "Image written to " + outputUri);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to write image to uri: " + outputUri, e);
                writeOk = false;
            } finally {
                if (out != null) {
                    try { out.close(); } catch (Exception ignored) {}
                }
            }

            final boolean success = writeOk;
            // Deliver result on main thread
            new Handler(Looper.getMainLooper()).post(() -> {
                if (caller == null) {
                    Log.w(TAG, "Original caller Activity is no longer available; cannot deliver result");
                } else {
                    deliverResultToActivity(caller, requestCode, success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, outputUri);
                }
                // Clear saved state regardless
                sActivity = null;
                sRequestCode = 0;
                sUri = null;
            });
        }).start();
    }

    // Helper: deliver result by invoking onActivityResult on the original Activity
    private static void deliverResultToActivity(Activity activity, int requestCode, int resultCode, Uri dataUri) {
        try {
            Intent data = new Intent();
            if (dataUri != null) data.setData(dataUri);

            // Try to call the Activity's onActivityResult via reflection (it's protected)
            Method m = Activity.class.getDeclaredMethod("onActivityResult", int.class, int.class, Intent.class);
            m.setAccessible(true);
            m.invoke(activity, requestCode, resultCode, data);
            Log.i(TAG, "Delivered fake camera result: requestCode=" + requestCode + " resultCode=" + resultCode + " uri=" + dataUri);
        } catch (NoSuchMethodException nsme) {
            Log.e(TAG, "onActivityResult method not found on Activity class", nsme);
        } catch (Exception e) {
            Log.e(TAG, "Failed to invoke onActivityResult on caller Activity", e);
        }
    }

    // Convenience helper to deliver a simple success/cancel result using saved state.
    private static void deliverResult(final boolean success) {
        final Activity caller = sActivity;
        final int requestCode = sRequestCode;
        final Uri outputUri = sUri;

        new Handler(Looper.getMainLooper()).post(() -> {
            if (caller != null) {
                deliverResultToActivity(caller, requestCode, success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, outputUri);
            } else {
                Log.w(TAG, "deliverResult: caller Activity is null; nothing to deliver");
            }
            // Clear saved state
            sActivity = null;
            sRequestCode = 0;
            sUri = null;
        });
    }

    // Optional: small fallback to obtain a Context if caller is null.
    // Replace or remove AppCloner.getAppContextFallback() usage above with your own app context accessor.
    private static final class AppCloner {
        static Context getAppContextFallback() {
            // Provide a fallback app context if you have a static reference to Application;
            // otherwise return null. Replace with your actual application context accessor.
            return null;
        }
    }
}