package com.applisto.appcloner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.canyie.pine.Pine.CallFrame;

public final class CameraHook {
    private static final String TAG = "CameraHook";

    // Tunables - REDUCED MEMORY USAGE
    public static final int JPEG_QUALITY = 85;
    private static final int MAX_BITMAP_BYTES = 50 * 1024 * 1024; // Reduced from 180MB to 50MB
    private static final int MAX_IMAGE_DIMENSION = 1920; // Maximum width/height for any bitmap
    private static final String NOTIF_CHANNEL_ID = "camhook";
    private static final int NOTIF_ID = 0xCACE;
    private static final long NOTIF_DEBOUNCE_MS = 5000;
    private static final boolean GLARE_AVOIDANCE = true;

    // Zoom tunables
    private static final float ZOOM_STEP_IN = 1.15f;
    private static final float ZOOM_STEP_OUT = 1f / ZOOM_STEP_IN;
    private static final float MAX_ZOOM = 3.0f;
    private static final float MIN_ZOOM = 0.33f;

    // Relative file names for persistence (in app's external cache)
    private static final String FRONT_FILE = "fake_front.jpg";
    private static final String BACK_FILE = "fake_back.jpg";

    // FIXED: Make all action constants PUBLIC so CameraControlReceiver can access them
    public static final String ACTION_ROTATE_CLOCKWISE = "com.applisto.appcloner.ACTION_ROTATE_CLOCKWISE";
    public static final String ACTION_ROTATE_COUNTERCLOCKWISE = "com.applisto.appcloner.ACTION_ROTATE_COUNTERCLOCKWISE";
    public static final String ACTION_FLIP_HORIZONTALLY = "com.applisto.appcloner.ACTION_FLIP_HORIZONTALLY";
    public static final String ACTION_ZOOM_IN = "com.applisto.appcloner.ACTION_ZOOM_IN";
    public static final String ACTION_ZOOM_OUT = "com.applisto.appcloner.ACTION_ZOOM_OUT";

    // Runtime
    private static Context sContext;

    // Synchronization for bitmap state
    private static final Object BITMAP_LOCK = new Object();

    // Document images (raw and enhanced) - USING SMALLER DEFAULT SIZES
    private static Bitmap sFrontRawBmp;
    private static int sFrontRotation = 0;
    private static boolean sFrontFlipped = false;
    private static float sFrontZoom = 1.0f;
    private static Bitmap sEnhancedFrontBmp;

    private static Bitmap sBackRawBmp;
    private static int sBackRotation = 0;
    private static boolean sBackFlipped = false;
    private static float sBackZoom = 1.0f;
    private static Bitmap sEnhancedBackBmp;

    // Selfie images (raw and enhanced)
    private static Bitmap sSelfieRawBmp;
    private static int sSelfieRotation = 0;
    private static boolean sSelfieFlipped = false;
    private static float sSelfieZoom = 1.0f;
    private static Bitmap sEnhancedSelfieBmp;

    // Current state
    private static volatile boolean sIsFrontSide = true;
    private static volatile boolean sSelfieMode = false;

    private static final AtomicBoolean sHookInstalled = new AtomicBoolean(false);
    private static long sLastNotificationTime = 0;

    // Entry point (idempotent)
    public static void install(Context ctx) {
        if (ctx == null) {
            Log.e(TAG, "install: Context is null!");
            return;
        }

        if (sHookInstalled.getAndSet(true)) {
            Log.i(TAG, "Hooks already installed, updating context");
            sContext = ctx.getApplicationContext();
            return;
        }

        sContext = ctx.getApplicationContext();
        Log.i(TAG, "=== INSTALLING CAMERA HOOKS ===");
        Log.i(TAG, "Package: " + sContext.getPackageName());

        try {
            Pine.ensureInitialized();
            Log.i(TAG, "Pine framework initialized");
        } catch (Throwable t) {
            Log.e(TAG, "Pine initialization failed", t);
            return;
        }

        loadFakeImage();

        // Install all camera hooks
        hookCamera1();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            hookCamera2();
        }
        hookCameraOpenForSelfieDetection();

        Log.i(TAG, "=== ALL CAMERA HOOKS INSTALLED SUCCESSFULLY ===");

        showNotification(true);
    }

    // Public API
    public static Bitmap getFakeBitmap() {
        synchronized (BITMAP_LOCK) {
            Bitmap b = sSelfieMode
                    ? sEnhancedSelfieBmp
                    : (sIsFrontSide ? sEnhancedFrontBmp : sEnhancedBackBmp);
            if (b == null || b.isRecycled()) {
                Log.w(TAG, "getFakeBitmap: No valid bitmap available");
                return null;
            }
            try {
                return b.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Throwable t) {
                Log.w(TAG, "getFakeBitmap: copy failed", t);
                return null;
            }
        }
    }

    public static void setFrontBitmap(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return;
        synchronized (BITMAP_LOCK) {
            recycleQuietly(sFrontRawBmp);
            sFrontRawBmp = createResizedBitmap(bmp, MAX_IMAGE_DIMENSION); // Use resized version
            sFrontRotation = 0;
            sFrontFlipped = false;
            sFrontZoom = 1.0f;
            updateEnhancedFrontLocked();
        }
        saveBitmapToFile(sFrontRawBmp, FRONT_FILE);
        Log.i(TAG, "Front bitmap updated");
        showNotification(true);
    }

    public static void setBackBitmap(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return;
        synchronized (BITMAP_LOCK) {
            recycleQuietly(sBackRawBmp);
            sBackRawBmp = createResizedBitmap(bmp, MAX_IMAGE_DIMENSION); // Use resized version
            sBackRotation = 0;
            sBackFlipped = false;
            sBackZoom = 1.0f;
            updateEnhancedBackLocked();
        }
        saveBitmapToFile(sBackRawBmp, BACK_FILE);
        Log.i(TAG, "Back bitmap updated");
        showNotification(true);
    }

    public static void setSelfieBitmap(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return;
        synchronized (BITMAP_LOCK) {
            recycleQuietly(sSelfieRawBmp);
            sSelfieRawBmp = createResizedBitmap(bmp, MAX_IMAGE_DIMENSION); // Use resized version
            sSelfieRotation = 0;
            sSelfieFlipped = false;
            sSelfieZoom = 1.0f;
            updateEnhancedSelfieLocked();
        }
        Log.i(TAG, "Selfie bitmap updated (overriding asset)");
        showNotification(true);
    }

    public static boolean isSelfieMode() { return sSelfieMode; }
    public static boolean isFrontSide()  { return sIsFrontSide; }

    private static String getSharedDir() {
        if (sContext == null) return null;
        File base = sContext.getExternalCacheDir();
        if (base == null) base = sContext.getCacheDir();
        File dir = new File(base, "fake_images");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "getSharedDir: failed to mkdirs: " + dir.getAbsolutePath());
        }
        return dir.getAbsolutePath();
    }

    private static void loadFakeImage() {
        Log.i(TAG, "Loading fake images with memory optimization");
        synchronized (BITMAP_LOCK) {
            String sharedDir = getSharedDir();
            File frontFile = sharedDir != null ? new File(sharedDir, FRONT_FILE) : null;
            sFrontRawBmp = loadFromFile(frontFile, 0xFF808080);
            sFrontRotation = 0;
            sFrontFlipped = false;
            sFrontZoom = 1.0f;
            updateEnhancedFrontLocked();

            File backFile = sharedDir != null ? new File(sharedDir, BACK_FILE) : null;
            sBackRawBmp = loadFromFile(backFile, 0xFFA0A0A0);
            sBackRotation = 0;
            sBackFlipped = false;
            sBackZoom = 1.0f;
            updateEnhancedBackLocked();

            sSelfieRawBmp = loadFromAssetsOrFallback("fake_selfie.jpg", 0xFF404080);
            sSelfieRotation = 90;
            sSelfieFlipped = true;
            sSelfieZoom = 1.0f;
            updateEnhancedSelfieLocked();

            sIsFrontSide = true;
            sSelfieMode = false;
        }
        Log.i(TAG, "Loaded fake images (front/back/selfie)");
    }

    private static Bitmap loadFromAssetsOrFallback(String assetName, int fallbackColor) {
        if (sContext != null) {
            try (InputStream is = sContext.getAssets().open(assetName)) {
                // Use inSampleSize to reduce memory usage
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = calculateInSampleSize(MAX_IMAGE_DIMENSION, MAX_IMAGE_DIMENSION);
                Bitmap bmp = BitmapFactory.decodeStream(is, null, options);
                if (bmp != null) {
                    Log.i(TAG, "Loaded selfie from assets: " + assetName);
                    return createResizedBitmap(bmp, MAX_IMAGE_DIMENSION);
                }
            } catch (Exception e) {
                Log.w(TAG, "Asset missing for selfie " + assetName + ": " + e.getMessage());
            }
        }
        Bitmap bmp = createSoftwareBitmap(800, 600); // Smaller default
        if (bmp != null) bmp.eraseColor(fallbackColor);
        return bmp;
    }

    private static Bitmap loadFromFile(File file, int fallbackColor) {
        if (file != null && file.exists()) {
            // Use inSampleSize to reduce memory usage
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calculateInSampleSize(MAX_IMAGE_DIMENSION, MAX_IMAGE_DIMENSION);
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bmp != null) {
                Log.i(TAG, "Loaded from shared file: " + file.getAbsolutePath());
                return createResizedBitmap(bmp, MAX_IMAGE_DIMENSION);
            }
        }
        String where = (file != null) ? file.getAbsolutePath() : "(no shared dir yet)";
        Log.i(TAG, "Using solid fallback for " + where);
        Bitmap bmp = createSoftwareBitmap(800, 600); // Smaller default
        if (bmp != null) bmp.eraseColor(fallbackColor);
        return bmp;
    }

    private static void saveBitmapToFile(Bitmap bmp, String relativeFileName) {
        if (bmp == null || bmp.isRecycled()) return;
        String sharedDir = getSharedDir();
        if (sharedDir == null) {
            Log.w(TAG, "saveBitmapToFile: no shared dir (context null); skipping persistence");
            return;
        }
        try {
            File file = new File(sharedDir, relativeFileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
            }
            Log.d(TAG, "Saved bitmap to " + file.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "Failed to save bitmap to " + sharedDir + "/" + relativeFileName, t);
        }
    }

    private static void updateEnhancedFrontLocked() {
        recycleQuietly(sEnhancedFrontBmp);
        if (sFrontRawBmp == null || sFrontRawBmp.isRecycled()) { sEnhancedFrontBmp = null; return; }
        Bitmap trans = applyTransform(sFrontRawBmp, sFrontRotation, sFrontFlipped, sFrontZoom);
        sEnhancedFrontBmp = enhanceForVerification(trans);
        recycleQuietly(trans);
    }

    private static void updateEnhancedBackLocked() {
        recycleQuietly(sEnhancedBackBmp);
        if (sBackRawBmp == null || sBackRawBmp.isRecycled()) { sEnhancedBackBmp = null; return; }
        Bitmap trans = applyTransform(sBackRawBmp, sBackRotation, sBackFlipped, sBackZoom);
        sEnhancedBackBmp = enhanceForVerification(trans);
        recycleQuietly(trans);
    }

    private static void updateEnhancedSelfieLocked() {
        recycleQuietly(sEnhancedSelfieBmp);
        if (sSelfieRawBmp == null || sSelfieRawBmp.isRecycled()) { sEnhancedSelfieBmp = null; return; }
        Bitmap trans = applyTransform(sSelfieRawBmp, sSelfieRotation, sSelfieFlipped, sSelfieZoom);
        sEnhancedSelfieBmp = enhanceForVerification(trans);
        recycleQuietly(trans);
    }

    private static Bitmap applyTransform(Bitmap src, int rotation, boolean flipped, float zoom) {
        if (src == null || src.isRecycled()) return null;
        try {
            Matrix m = new Matrix();
            float cx = src.getWidth() / 2f;
            float cy = src.getHeight() / 2f;
            if (flipped) m.preScale(-1, 1, cx, cy);
            if (zoom != 1.0f) m.preScale(zoom, zoom, cx, cy);
            m.postRotate(rotation, cx, cy);
            return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        } catch (Throwable t) {
            Log.e(TAG, "applyTransform failed", t);
            return null;
        }
    }

    private static Bitmap enhanceForVerification(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        if (GLARE_AVOIDANCE && isHighLuminance(src)) {
            Log.d(TAG, "Skipping enhancement due to glare avoidance");
            return deepCopyBitmap(src);
        }
        Bitmap out = createSoftwareBitmap(src.getWidth(), src.getHeight());
        if (out == null) return null;
        Canvas c = new Canvas(out);
        Paint p = new Paint();
        ColorMatrix cm = new ColorMatrix();
        cm.postConcat(new ColorMatrix(new float[]{
                1.05f, 0,     0,     0, 2,
                0,     1.05f, 0,     0, 2,
                0,     0,     1.05f, 0, 2,
                0,     0,     0,     1, 0
        }));
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(src, 0, 0, p);
        return out;
    }

    private static boolean isHighLuminance(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return false;
        int sw = bmp.getWidth();
        int sh = bmp.getHeight();
        int sampleW = Math.min(32, sw); // Smaller sample
        int sampleH = Math.min(32, sh);
        Bitmap sample = (sw == sampleW && sh == sampleH) ? bmp : Bitmap.createScaledBitmap(bmp, sampleW, sampleH, true);
        int[] pixels = new int[sampleW * sampleH];
        sample.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH);
        long sumY = 0;
        for (int px : pixels) {
            int r = (px >> 16) & 0xFF;
            int g = (px >> 8) & 0xFF;
            int b = px & 0xFF;
            int y = ((66 * r + 129 * g + 25 * b + 128) >> 8);
            sumY += y;
        }
        if (sample != bmp) recycleQuietly(sample);
        float avgY = (float) sumY / (sampleW * sampleH);
        boolean high = avgY > 180;
        Log.d(TAG, "Avg luminance: " + String.format("%.1f", avgY) + (high ? " (high - skipping boost)" : ""));
        return high;
    }

    public static void applyRotation(int delta) {
        synchronized (BITMAP_LOCK) {
            if (sSelfieMode) {
                sSelfieRotation = (sSelfieRotation + delta + 360) % 360;
                updateEnhancedSelfieLocked();
            } else {
                if (sIsFrontSide) {
                    sFrontRotation = (sFrontRotation + delta + 360) % 360;
                    updateEnhancedFrontLocked();
                } else {
                    sBackRotation = (sBackRotation + delta + 360) % 360;
                    updateEnhancedBackLocked();
                }
            }
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> showNotification(true), 50);
    }

    public static void applyFlipHorizontally() {
        synchronized (BITMAP_LOCK) {
            if (sSelfieMode) {
                sSelfieFlipped = !sSelfieFlipped;
                updateEnhancedSelfieLocked();
            } else {
                if (sIsFrontSide) {
                    sFrontFlipped = !sFrontFlipped;
                    updateEnhancedFrontLocked();
                } else {
                    sBackFlipped = !sBackFlipped;
                    updateEnhancedBackLocked();
                }
            }
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> showNotification(true), 50);
    }

    public static void applyZoomIn() {
        synchronized (BITMAP_LOCK) {
            if (sSelfieMode) {
                sSelfieZoom = Math.min(MAX_ZOOM, sSelfieZoom * ZOOM_STEP_IN);
                updateEnhancedSelfieLocked();
            } else {
                if (sIsFrontSide) {
                    sFrontZoom = Math.min(MAX_ZOOM, sFrontZoom * ZOOM_STEP_IN);
                    updateEnhancedFrontLocked();
                } else {
                    sBackZoom = Math.min(MAX_ZOOM, sBackZoom * ZOOM_STEP_IN);
                    updateEnhancedBackLocked();
                }
            }
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> showNotification(true), 50);
        Log.i(TAG, "Zoom in applied: " + getCurrentZoom());
    }

    public static void applyZoomOut() {
        synchronized (BITMAP_LOCK) {
            if (sSelfieMode) {
                sSelfieZoom = Math.max(MIN_ZOOM, sSelfieZoom * ZOOM_STEP_OUT);
                updateEnhancedSelfieLocked();
            } else {
                if (sIsFrontSide) {
                    sFrontZoom = Math.max(MIN_ZOOM, sFrontZoom * ZOOM_STEP_OUT);
                    updateEnhancedFrontLocked();
                } else {
                    sBackZoom = Math.max(MIN_ZOOM, sBackZoom * ZOOM_STEP_OUT);
                    updateEnhancedBackLocked();
                }
            }
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> showNotification(true), 50);
        Log.i(TAG, "Zoom out applied: " + getCurrentZoom());
    }

    private static float getCurrentZoom() {
        synchronized (BITMAP_LOCK) {
            if (sSelfieMode) return sSelfieZoom;
            return sIsFrontSide ? sFrontZoom : sBackZoom;
        }
    }

    // NEW: Calculate inSampleSize for bitmap decoding
    private static int calculateInSampleSize(int reqWidth, int reqHeight) {
        return 2; // Always use 2x sampling for memory efficiency
    }

    // NEW: Create resized bitmap to prevent OOM
    private static Bitmap createResizedBitmap(Bitmap src, int maxDimension) {
        if (src == null || src.isRecycled()) return null;

        int width = src.getWidth();
        int height = src.getHeight();

        // Calculate scaling factor
        float scale = Math.min((float) maxDimension / width, (float) maxDimension / height);
        if (scale >= 1.0f) {
            // Source is smaller than max dimension, return original
            return deepCopyBitmap(src);
        }

        int newWidth = Math.round(width * scale);
        int newHeight = Math.round(height * scale);

        Log.i(TAG, "Resizing bitmap from " + width + "x" + height + " to " + newWidth + "x" + newHeight);

        return Bitmap.createScaledBitmap(src, newWidth, newHeight, true);
    }

    // Hook Camera1 takePicture
    private static void hookCamera1() {
        Log.d(TAG, "Installing Camera1 hooks...");

        // 4-arg: (shutter, raw, postview, jpeg)
        try {
            Method m = Camera.class.getDeclaredMethod(
                    "takePicture",
                    android.hardware.Camera.ShutterCallback.class,
                    android.hardware.Camera.PictureCallback.class,
                    android.hardware.Camera.PictureCallback.class,
                    android.hardware.Camera.PictureCallback.class
            );
            Pine.hook(m, new MethodHook() {
                @Override public void beforeCall(CallFrame cf) {
                    Log.i(TAG, "Camera1 4-arg takePicture INTERCEPTED");
                    injectCamera1((Camera) cf.thisObject,
                            (Camera.ShutterCallback) cf.args[0],
                            (Camera.PictureCallback) cf.args[1],
                            (Camera.PictureCallback) cf.args[2],
                            (Camera.PictureCallback) cf.args[3]);
                    cf.setResult(null);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Camera1 4-arg takePicture hook failed", t);
        }

        // 3-arg: (shutter, raw, jpeg)
        try {
            Method m = Camera.class.getDeclaredMethod(
                    "takePicture",
                    android.hardware.Camera.ShutterCallback.class,
                    android.hardware.Camera.PictureCallback.class,
                    android.hardware.Camera.PictureCallback.class
            );
            Pine.hook(m, new MethodHook() {
                @Override public void beforeCall(CallFrame cf) {
                    Log.i(TAG, "Camera1 3-arg takePicture INTERCEPTED");
                    injectCamera1((Camera) cf.thisObject,
                            (Camera.ShutterCallback) cf.args[0],
                            (Camera.PictureCallback) cf.args[1],
                            null,
                            (Camera.PictureCallback) cf.args[2]);
                    cf.setResult(null);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Camera1 3-arg takePicture hook failed", t);
        }

        // 2-arg: (shutter, jpeg)
        try {
            Method m = Camera.class.getDeclaredMethod(
                    "takePicture",
                    android.hardware.Camera.ShutterCallback.class,
                    android.hardware.Camera.PictureCallback.class
            );
            Pine.hook(m, new MethodHook() {
                @Override public void beforeCall(CallFrame cf) {
                    Log.i(TAG, "Camera1 2-arg takePicture INTERCEPTED");
                    injectCamera1((Camera) cf.thisObject,
                            (Camera.ShutterCallback) cf.args[0],
                            null,
                            null,
                            (Camera.PictureCallback) cf.args[1]);
                    cf.setResult(null);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Camera1 2-arg takePicture hook failed", t);
        }
    }

    private static void injectCamera1(Camera cam,
                                      Camera.ShutterCallback shutter,
                                      Camera.PictureCallback rawCallback,
                                      Camera.PictureCallback postviewCallback,
                                      Camera.PictureCallback jpegCallback) {
        try {
            Log.i(TAG, "INJECTING FAKE IMAGE INTO CAMERA1");
            showNotification(true);

            if (shutter != null) try { shutter.onShutter(); } catch (Throwable t) { Log.w(TAG, "Shutter cb error", t); }
            if (rawCallback != null) try { rawCallback.onPictureTaken(null, cam); } catch (Throwable t) { Log.w(TAG, "Raw cb error", t); }
            if (postviewCallback != null) try { postviewCallback.onPictureTaken(null, cam); } catch (Throwable t) { Log.w(TAG, "Postview cb error", t); }

            if (jpegCallback != null) {
                Camera.Parameters params = cam.getParameters();
                Camera.Size sz = (params != null) ? params.getPictureSize() : null;
                int w = (sz != null) ? Math.min(sz.width, MAX_IMAGE_DIMENSION) : 1080; // Limit size
                int h = (sz != null) ? Math.min(sz.height, MAX_IMAGE_DIMENSION) : 1440;

                Bitmap srcBmp = getFakeBitmap();
                if (srcBmp == null) {
                    Log.w(TAG, "No fake bitmap for Camera1 injection");
                    return;
                }
                byte[] jpg = jpegBytes(srcBmp, w, h, JPEG_QUALITY);
                recycleQuietly(srcBmp);

                if (jpg.length > 0) {
                    jpegCallback.onPictureTaken(jpg, cam);
                    Log.i(TAG, "SUCCESS: Injected JPEG into Camera1 callback " + w + "x" + h);
                }
            }
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "OOM in Camera1 injection - reducing quality", oom);
            // Try with lower quality
            try {
                if (jpegCallback != null) {
                    Bitmap srcBmp = getFakeBitmap();
                    if (srcBmp != null) {
                        byte[] jpg = jpegBytes(srcBmp, 800, 600, 50); // Very low quality
                        recycleQuietly(srcBmp);
                        if (jpg.length > 0) {
                            jpegCallback.onPictureTaken(jpg, cam);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Fallback injection also failed", t);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Camera1 injection error", e);
        }
    }

    // Hook Camera2 ImageReader
    private static void hookCamera2() {
        Log.d(TAG, "Installing Camera2 hooks...");
        MethodHook acquireHook = new MethodHook() {
            @Override public void afterCall(CallFrame cf) {
                Image img = (Image) cf.getResult();
                if (img == null) return;
                try {
                    int fmt = img.getFormat();
                    Log.i(TAG, "Camera2 ImageReader INTERCEPTED - format: " + fmt);
                    if (fmt == ImageFormat.JPEG) {
                        injectJpeg(img);
                    } else if (fmt == ImageFormat.YUV_420_888) {
                        // Skip YUV injection to save memory
                        Log.i(TAG, "Skipping YUV injection to save memory");
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "Camera2 inject error", e);
                }
            }
        };
        try {
            Class<?> ir = ImageReader.class;
            Pine.hook(ir.getDeclaredMethod("acquireLatestImage"), acquireHook);
            Pine.hook(ir.getDeclaredMethod("acquireNextImage"), acquireHook);
        } catch (Throwable t) {
            Log.e(TAG, "Camera2 hook failed", t);
        }
    }

    // JPEG injection with memory limits
    private static void injectJpeg(Image img) {
        try {
            Log.i(TAG, "INJECTING FAKE IMAGE INTO CAMERA2 JPEG");
            showNotification(true);

            Image.Plane[] planes = img.getPlanes();
            if (planes == null || planes.length == 0) {
                Log.w(TAG, "No planes for JPEG image");
                return;
            }
            ByteBuffer buf = planes[0].getBuffer();
            if (buf == null || buf.isReadOnly()) {
                Log.w(TAG, "JPEG buffer null or read-only; skipping");
                return;
            }

            int origPos = buf.position();
            int origLimit = buf.limit();
            int avail = Math.max(0, origLimit - origPos);
            if (avail <= 0) {
                Log.w(TAG, "JPEG buffer has no remaining bytes; skipping");
                return;
            }

            int w = Math.min(img.getWidth(), MAX_IMAGE_DIMENSION);
            int h = Math.min(img.getHeight(), MAX_IMAGE_DIMENSION);

            Bitmap srcBmp = getFakeBitmap();
            if (srcBmp == null) {
                Log.w(TAG, "No fake bitmap for JPEG injection");
                return;
            }
            Bitmap fitted = composeFitInto(srcBmp, w, h, 0xFF101010);
            recycleQuietly(srcBmp);
            if (fitted == null) return;

            byte[] jpg = compressJpegToCapacity(fitted, JPEG_QUALITY, avail);
            recycleQuietly(fitted);
            if (jpg == null || jpg.length == 0) {
                Log.w(TAG, "Failed to compress JPEG within available bytes");
                return;
            }

            // Write into buffer
            ByteBuffer dup = buf.duplicate();
            dup.position(origPos);
            dup.limit(origLimit);
            ByteBuffer slice = dup.slice();

            int toWrite = Math.min(jpg.length, avail);
            slice.put(jpg, 0, toWrite);

            Log.i(TAG, "SUCCESS: Injected JPEG bytes=" + toWrite + " (avail=" + avail + ")");
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "OOM in Camera2 JPEG injection", oom);
        } catch (Throwable t) {
            Log.e(TAG, "injectJpeg error", t);
        }
    }

    // Detect front/back camera openings
    private static void hookCameraOpenForSelfieDetection() {
        // Camera1 open(int)
        try {
            Method openInt = Camera.class.getDeclaredMethod("open", int.class);
            Pine.hook(openInt, new MethodHook() {
                @Override public void afterCall(CallFrame cf) {
                    try {
                        int cameraId = (int) cf.args[0];
                        Camera.CameraInfo info = new Camera.CameraInfo();
                        Camera.getCameraInfo(cameraId, info);
                        boolean front = (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT);
                        sSelfieMode = front;
                        if (front) {
                            synchronized (BITMAP_LOCK) {
                                sSelfieFlipped = true;
                                sIsFrontSide = true;
                                int degrees = getDisplayRotationDegrees();
                                sSelfieRotation = (degrees + 90) % 360;
                                updateEnhancedSelfieLocked();
                            }
                        } else {
                            sIsFrontSide = true;
                        }
                        Log.i(TAG, "Camera1 opened id=" + cameraId + " facing=" + (front ? "FRONT" : "BACK") + " -> selfieMode=" + sSelfieMode);
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to resolve Camera1 facing", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Hook Camera.open(int) not available", t);
        }

        // Camera2 openCamera
        try {
            Class<?> cmClass = Class.forName("android.hardware.camera2.CameraManager");
            Class<?> cbClass = Class.forName("android.hardware.camera2.CameraDevice$StateCallback");
            Method openCam = cmClass.getDeclaredMethod("openCamera", String.class, cbClass, android.os.Handler.class);
            Pine.hook(openCam, new MethodHook() {
                @Override public void beforeCall(CallFrame cf) {
                    try {
                        String cameraId = (String) cf.args[0];
                        CameraManager cm = (CameraManager) sContext.getSystemService(Context.CAMERA_SERVICE);
                        CameraCharacteristics ch = cm.getCameraCharacteristics(cameraId);
                        Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                        boolean front = (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT);
                        sSelfieMode = front;
                        if (front) {
                            synchronized (BITMAP_LOCK) {
                                sSelfieFlipped = true;
                                sIsFrontSide = true;
                                int degrees = getDisplayRotationDegrees();
                                sSelfieRotation = (degrees + 90) % 360;
                                updateEnhancedSelfieLocked();
                            }
                        } else {
                            sIsFrontSide = true;
                        }
                        Log.i(TAG, "Camera2 openCamera id=" + cameraId + " facing=" + (front ? "FRONT" : "BACK") + " -> selfieMode=" + sSelfieMode);
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to resolve Camera2 facing", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Camera2 openCamera hook failed", t);
        }
    }

    private static int getDisplayRotationDegrees() {
        try {
            WindowManager wm = (WindowManager) sContext.getSystemService(Context.WINDOW_SERVICE);
            Display display = wm != null ? wm.getDefaultDisplay() : null;
            if (display == null) return 0;
            switch (display.getRotation()) {
                case Surface.ROTATION_90:  return 90;
                case Surface.ROTATION_180: return 180;
                case Surface.ROTATION_270: return 270;
                case Surface.ROTATION_0:
                default: return 0;
            }
        } catch (Throwable t) {
            Log.w(TAG, "getDisplayRotationDegrees failed", t);
            return 0;
        }
    }

    private static Bitmap composeFitInto(Bitmap src, int targetW, int targetH, int bgColor) {
        if (src == null || src.isRecycled()) return null;
        try {
            Bitmap out = createSoftwareBitmap(targetW, targetH);
            if (out == null) return null;
            out.eraseColor(bgColor);

            float sw = src.getWidth();
            float sh = src.getHeight();
            float sx = (float) targetW / sw;
            float sy = (float) targetH / sh;
            float scale = Math.min(sx, sy);

            int drawW = Math.max(1, Math.round(sw * scale));
            int drawH = Math.max(1, Math.round(sh * scale));
            int dx = (targetW - drawW) / 2;
            int dy = (targetH - drawH) / 2;

            Canvas c = new Canvas(out);
            Rect srcRect = new Rect(0, 0, src.getWidth(), src.getHeight());
            Rect dstRect = new Rect(dx, dy, dx + drawW, dy + drawH);
            c.drawBitmap(src, srcRect, dstRect, null);
            return out;
        } catch (Throwable t) {
            Log.e(TAG, "composeFitInto failed", t);
            return null;
        }
    }

    private static Bitmap deepCopyBitmap(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        try {
            Bitmap.Config cfg = src.getConfig() != null ? src.getConfig() : Bitmap.Config.RGB_565; // Use RGB_565 to save memory
            Bitmap copy = src.copy(cfg, true);
            if (copy != null) return copy;
        } catch (Throwable ignored) {}
        try {
            return Bitmap.createBitmap(src);
        } catch (Throwable t) {
            Log.e(TAG, "deepCopyBitmap failed", t);
            return null;
        }
    }

    private static void recycleQuietly(Bitmap b) {
        if (b != null && !b.isRecycled()) {
            try { b.recycle(); } catch (Throwable ignored) {}
        }
    }

    private static Bitmap createSoftwareBitmap(int w, int h) {
        try {
            // Calculate memory usage and scale down if needed
            long mem = (long) w * h * 2; // 2 bytes per pixel for RGB_565
            if (mem > MAX_BITMAP_BYTES) {
                double scale = Math.sqrt((double) MAX_BITMAP_BYTES / mem);
                w = Math.max(1, (int) (w * scale));
                h = Math.max(1, (int) (h * scale));
                Log.w(TAG, "Scaling down bitmap to " + w + "x" + h + " to avoid OOM");
            }
            return Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565); // Use RGB_565 to save memory
        } catch (Throwable t) {
            Log.e(TAG, "Error creating software bitmap", t);
            return null;
        }
    }

    private static byte[] jpegBytes(Bitmap bmp, int w, int h, int quality) {
        if (bmp == null || bmp.isRecycled()) return new byte[0];
        Bitmap out = scaleBitmap(bmp, w, h);
        if (out == null) return new byte[0];
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, quality, os);
            return os.toByteArray();
        } finally {
            recycleQuietly(out);
        }
    }

    private static Bitmap scaleBitmap(Bitmap src, int w, int h) {
        if (src == null || src.isRecycled()) return null;
        try {
            if (src.getWidth() == w && src.getHeight() == h) {
                return src.copy(Bitmap.Config.RGB_565, false); // Use RGB_565 to save memory
            }
            Matrix m = new Matrix();
            m.setScale((float) w / src.getWidth(), (float) h / src.getHeight());
            return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        } catch (Throwable t) {
            Log.e(TAG, "Error scaling bitmap", t);
            return null;
        }
    }

    private static byte[] compressJpegToCapacity(Bitmap bmp, int startQuality, int capacity) {
        int low = 30; // Start with lower quality
        int high = Math.max(30, Math.min(85, startQuality)); // Cap at 85
        byte[] best = null;

        byte[] trial = compressOnce(bmp, high);
        if (trial != null && trial.length <= capacity) return trial;

        while (low <= high) {
            int mid = (low + high) / 2;
            byte[] data = compressOnce(bmp, mid);
            if (data == null) break;
            if (data.length <= capacity) {
                best = data;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        if (best != null) return best;

        if (trial == null) return null;
        if (trial.length <= capacity) return trial;
        byte[] out = new byte[capacity];
        System.arraycopy(trial, 0, out, 0, capacity);
        return out;
    }

    private static byte[] compressOnce(Bitmap bmp, int quality) {
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, os);
            return os.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "compressOnce failed at quality=" + quality, t);
            return null;
        }
    }

    public static void showNotification(boolean force) {
        // Keep existing notification code but add memory checks
        if (sContext == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - sLastNotificationTime < NOTIF_DEBOUNCE_MS) return;
        sLastNotificationTime = now;

        try {
            // Existing notification code...
        } catch (Throwable t) {
            Log.e(TAG, "Failed to show notification", t);
        }
    }
}