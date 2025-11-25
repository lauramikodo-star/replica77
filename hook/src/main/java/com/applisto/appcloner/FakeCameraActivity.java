package com.applisto.appcloner;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class FakeCameraActivity extends Activity {
    private static final String TAG = "FakeCameraActivity";
    private static final int REQ_PICK_IMAGE = 1001;

    private String modeExtra;          // "selfie", "front", "back" (may be null)
    private boolean usedOpenDocument;  // track which picker path we launched
    private boolean isFakeCameraAppMode;  // From "fake_camera_app" extra

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Optional: ensure hooks exist if launched directly
        try { CameraHook.install(getApplicationContext()); } catch (Throwable ignored) {}

        modeExtra = getIntent() != null ? getIntent().getStringExtra("mode") : null;
        isFakeCameraAppMode = getIntent() != null && getIntent().getBooleanExtra("fake_camera_app", false);
        Log.d(TAG, "Received mode from intent: " + modeExtra + ", fake_camera_app: " + isFakeCameraAppMode);
        Log.d(TAG, "Intent extras: " + (getIntent() != null ? getIntent().getExtras() : "null"));

        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= 33) {
                // Android 13+ system photo picker
                intent = new Intent("android.provider.action.PICK_IMAGES");
                intent.putExtra("android.provider.extra.ALLOW_MULTIPLE", false);  // Enforce single selection
                usedOpenDocument = false;
            } else {
                // Storage Access Framework
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                usedOpenDocument = true;
            }
            //noinspection deprecation
            startActivityForResult(intent, REQ_PICK_IMAGE);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to launch image picker", t);
            Toast.makeText(this, "Unable to open image picker", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQ_PICK_IMAGE) {
            finish();
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Uri uri = data.getData();

        // Persist access for ACTION_OPEN_DOCUMENT path
        if (usedOpenDocument && Build.VERSION.SDK_INT >= 19) {
            final int flags = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (SecurityException ignored) {}
        }

        Bitmap selected = null;
        String resolvedMode = modeExtra != null ? modeExtra : "front";  // Default
        if (isFakeCameraAppMode) {
            resolvedMode = "app";  // Or resolve via CameraHook state
            Log.d(TAG, "Using app-clone mode for bitmap set");
        }
        try {
            // 1) Probe bounds and pick a safe inSampleSize
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, bounds);
            }
            int srcW = Math.max(1, bounds.outWidth);
            int srcH = Math.max(1, bounds.outHeight);

            final int maxDim = 2048; // keep memory reasonable
            int sample = 1;
            while ((srcW / sample) > maxDim || (srcH / sample) > maxDim) {
                sample <<= 1;
            }

            // 2) Decode with sampling
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inDither = false;
            opts.inScaled = false;
            try (InputStream is2 = getContentResolver().openInputStream(uri)) {
                selected = BitmapFactory.decodeStream(is2, null, opts);
            }

            if (selected == null) {
                Log.e(TAG, "Decoded bitmap is null");
                Toast.makeText(this, "Failed to decode image", Toast.LENGTH_SHORT).show();
                // Inform the FakeCameraAppSupport that selection failed
                FakeCameraAppSupport.setImage(null, null);
                finish();
                return;
            }

            // 3) Optionally transform the bitmap: mirror for front/selfie cameras
            boolean transformed = false;
            if ("selfie".equalsIgnoreCase(modeExtra) || "front".equalsIgnoreCase(modeExtra)) {
                Matrix m = new Matrix();
                m.preScale(-1f, 1f); // mirror horizontally
                Bitmap mirrored = Bitmap.createBitmap(selected, 0, 0, selected.getWidth(), selected.getHeight(), m, true);
                if (mirrored != null) {
                    selected.recycle();
                    selected = mirrored;
                    transformed = true;
                }
            }

            // You can add rotation handling based on EXIF here if desired.

            // 4) Compress to JPEG bytes and hand off to FakeCameraAppSupport which will write
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            final int jpegQuality = 95;
            boolean compressOk = selected.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos);
            byte[] jpegBytes = null;
            if (compressOk) {
                jpegBytes = baos.toByteArray();
            } else {
                Log.e(TAG, "Failed to compress selected bitmap to JPEG");
            }

            if (jpegBytes != null) {
                // Pass JPEG bytes to FakeCameraAppSupport which will write them to the saved output Uri.
                FakeCameraAppSupport.setImage(null, jpegBytes);
                Toast.makeText(this, "Image selected", Toast.LENGTH_SHORT).show();
            } else {
                // Fallback: tell support there's no image (will deliver canceled result)
                FakeCameraAppSupport.setImage(null, null);
                Toast.makeText(this, "Failed to prepare image", Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing selected image", e);
            Toast.makeText(this, "Error processing image", Toast.LENGTH_SHORT).show();
            FakeCameraAppSupport.setImage(null, null);
        } finally {
            if (selected != null && !selected.isRecycled()) {
                try { selected.recycle(); } catch (Throwable ignored) {}
            }
            // We are done — finish this picker activity
            finish();
        }
    }
}