// File: com/applisto/appcloner/AppDataManager.java (Extended Version)
package com.applisto.appcloner;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class AppDataManager {
    private static final String TAG = "AppDataManager";
    private static final String APP_DATA_FILENAME = "app_data_export.zip"; // Standard filename for exported data
    private static final int BUFFER_SIZE = 8192;

    // Names for the top-level directories within the ZIP file
    private static final String INTERNAL_DIR_NAME = "INTERNAL";
    private static final String EXTERNAL_DIR_NAME = "EXTERNAL";

    private final Context mContext;
    private final String mOriginalPackageName; // Needed for identification/context
    private final boolean mRestoreOnStart; // Whether to restore bundled data on every launch

    public AppDataManager(Context context, String originalPackageName, boolean restoreOnStart) {
        this.mContext = context.getApplicationContext();
        this.mOriginalPackageName = originalPackageName;
        this.mRestoreOnStart = restoreOnStart;
    }

    /**
     * Exports the app's private data (internal storage) AND external data directory to a ZIP file in internal storage.
     * The ZIP will contain two top-level folders: INTERNAL and EXTERNAL.
     * @return The File object representing the exported ZIP, or null on failure.
     * @throws IOException If an I/O error occurs.
     */
    public File exportAppData() throws IOException {
        File internalDir = mContext.getFilesDir().getParentFile(); // Points to /data/data/<package_name>
        File externalDir = mContext.getExternalFilesDir(null); // Points to /Android/data/<package_name>/files
        // Note: You might also want to include other external dirs like getExternalCacheDir(), getExternalFilesDirs() for other media types.

        if (internalDir == null || !internalDir.exists()) {
            throw new IOException("Could not access app's internal data directory.");
        }
        if (externalDir == null || !externalDir.exists()) {
            Log.w(TAG, "App's external data directory does not exist or is not accessible. Skipping external data.");
            // We'll still proceed with internal data.
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = mContext.getContentResolver();
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, APP_DATA_FILENAME);
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
            if (uri == null) {
                throw new IOException("Failed to create new MediaStore record.");
            }

            try (OutputStream os = resolver.openOutputStream(uri);
                 ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(os))) {

                addDirectoryToZip(zos, internalDir, internalDir.getAbsolutePath().length() + 1, INTERNAL_DIR_NAME + "/");
                if (externalDir != null && externalDir.exists()) {
                    addDirectoryToZip(zos, externalDir, externalDir.getAbsolutePath().length() + 1, EXTERNAL_DIR_NAME + "/");
                }

                Log.i(TAG, "App data exported successfully to: " + uri.toString());
                // We can't return a File object anymore, so we return null but the export was successful.
                // The caller should be updated to handle this.
                return null;

            } catch (IOException e) {
                Log.e(TAG, "Error exporting app data to MediaStore", e);
                resolver.delete(uri, null, null);
                throw e;
            }
        } else {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloadsDir == null || (!downloadsDir.exists() && !downloadsDir.mkdirs())) {
                Log.e(TAG, "Primary external storage is not available or could not create Downloads directory. Defaulting to internal storage.");
                downloadsDir = mContext.getFilesDir();
            }
            File outputFile = new File(downloadsDir, APP_DATA_FILENAME); // Save in public Downloads directory
            Log.d(TAG, "Exporting app data from INTERNAL: " + internalDir.getAbsolutePath() +
                    " and EXTERNAL: " + (externalDir != null ? externalDir.getAbsolutePath() : "N/A") +
                    " to: " + outputFile.getAbsolutePath());

            try (FileOutputStream fos = new FileOutputStream(outputFile);
                 ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos))) {

                // Export Internal Data (under the "INTERNAL/" prefix)
                if (internalDir.exists()) {
                    addDirectoryToZip(zos, internalDir, internalDir.getAbsolutePath().length() + 1, INTERNAL_DIR_NAME + "/");
                }

                // Export External Data (under the "EXTERNAL/" prefix)
                if (externalDir != null && externalDir.exists()) {
                    addDirectoryToZip(zos, externalDir, externalDir.getAbsolutePath().length() + 1, EXTERNAL_DIR_NAME + "/");
                }

                Log.i(TAG, "App data exported successfully to: " + outputFile.getAbsolutePath());
                return outputFile;

            } catch (IOException e) {
                Log.e(TAG, "Error exporting app data", e);
                if (outputFile.exists()) {
                    outputFile.delete(); // Clean up failed file
                }
                throw e;
            }
        }
    }

    /**
     * Imports app data from a ZIP file (typically from assets or external storage).
     * This method expects the ZIP to contain top-level "INTERNAL/" and "EXTERNAL/" directories.
     * @param sourceFile The ZIP file containing the app data.
     * @return true if import was successful, false otherwise.
     */
    public boolean importAppData(File sourceFile) {
        if (sourceFile == null || !sourceFile.exists()) {
            Log.e(TAG, "Source file for import is null or doesn't exist.");
            return false;
        }

        File internalDir = mContext.getFilesDir().getParentFile();
        File externalDir = mContext.getExternalFilesDir(null);
        if (internalDir == null) {
            Log.e(TAG, "Could not access app's internal data directory for import.");
            return false;
        }
        if (externalDir == null) {
            Log.e(TAG, "Could not access app's external data directory for import.");
            return false;
        }

        Log.d(TAG, "Importing app data from: " + sourceFile.getAbsolutePath() +
                " to INTERNAL: " + internalDir.getAbsolutePath() +
                " and EXTERNAL: " + externalDir.getAbsolutePath());

        try (FileInputStream fis = new FileInputStream(sourceFile);
             ZipInputStream zis = new ZipInputStream(new BufferedInputStream(fis))) {

            ZipEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                // Determine the target directory based on the entry path
                File targetDir;
                String relativePath;

                if (entryName.startsWith(EXTERNAL_DIR_NAME + "/")) {
                    // It's external data
                    targetDir = externalDir.getParentFile(); // This points to /Android/data/<package_name>
                    // Remove the "EXTERNAL/" prefix to get the relative path within the external storage
                    relativePath = entryName.substring(EXTERNAL_DIR_NAME.length() + 1); // +1 for the '/'
                } else if (entryName.startsWith(INTERNAL_DIR_NAME + "/")) {
                    // It's internal data
                    targetDir = internalDir;
                    // Remove the "INTERNAL/" prefix
                    relativePath = entryName.substring(INTERNAL_DIR_NAME.length() + 1);
                } else {
                    // Unknown top-level directory, skip it
                    Log.w(TAG, "Skipping unknown entry in ZIP: " + entryName);
                    zis.closeEntry();
                    continue;
                }

                if (entry.isDirectory()) {
                    File dir = new File(targetDir, relativePath);
                    if (!dir.exists() && !dir.mkdirs()) {
                        Log.w(TAG, "Could not create directory during import: " + dir.getAbsolutePath());
                    }
                } else {
                    File file = new File(targetDir, relativePath);
                    // Ensure parent directory exists
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    try (FileOutputStream fos = new FileOutputStream(file);
                         BufferedOutputStream bos = new BufferedOutputStream(fos, BUFFER_SIZE)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            bos.write(buffer, 0, len);
                        }
                        bos.flush();
                    }
                }
                zis.closeEntry();
            }
            Log.i(TAG, "App data imported successfully from: " + sourceFile.getAbsolutePath());
            return true;

        } catch (IOException e) {
            Log.e(TAG, "Error importing app data from: " + sourceFile.getAbsolutePath(), e);
            return false;
        }
    }

    /**
     * Checks for bundled app data (in assets) and imports it if found and conditions are met.
     * This is typically called once on first launch or every launch if mRestoreOnStart is true.
     * @return true if bundled data was found and imported, false otherwise.
     */
    public boolean importBundledAppDataIfAvailable() {
        try {
            String[] assets = mContext.getAssets().list("");
            if (assets != null) {
                for (String asset : assets) {
                    if (APP_DATA_FILENAME.equals(asset)) {
                        Log.d(TAG, "Found bundled app data in assets: " + asset);
                        File tempFile = new File(mContext.getCacheDir(), APP_DATA_FILENAME);
                        try (FileInputStream fis = mContext.getAssets().openFd(APP_DATA_FILENAME).createInputStream();
                             FileOutputStream fos = new FileOutputStream(tempFile)) {
                            byte[] buffer = new byte[BUFFER_SIZE];
                            int len;
                            while ((len = fis.read(buffer)) > 0) {
                                fos.write(buffer, 0, len);
                            }
                        } catch (IOException e) {
                            Log.e(TAG, "Error copying bundled app data asset to temp file", e);
                            return false;
                        }
                        boolean success = importAppData(tempFile);
                        if (tempFile.exists()) {
                            tempFile.delete(); // Clean up temp file
                        }
                        return success;
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Error listing assets or importing bundled data", e);
        }
        Log.d(TAG, "No bundled app data found in assets.");
        return false;
    }

    // --- Helper Methods ---
    /**
     * Adds a directory and its contents to the ZipOutputStream.
     * @param zos The ZipOutputStream to write to.
     * @param dir The directory to add.
     * @param basePathLength The length of the base path to strip off for relative naming.
     * @param zipPathPrefix The prefix to add to the entry name (e.g., "INTERNAL/").
     * @throws IOException If an I/O error occurs.
     */
    private void addDirectoryToZip(ZipOutputStream zos, File dir, int basePathLength, String zipPathPrefix) throws IOException {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                // Skip the output file itself to prevent infinite recursion/corruption
                if (file.getName().equals(APP_DATA_FILENAME)) {
                    continue;
                }

                // Exclude Cache Directories
                if (file.isDirectory()) {
                    if (file.getName().equalsIgnoreCase("cache") ||
                        file.getName().equalsIgnoreCase("code_cache") ||
                        file.getName().equalsIgnoreCase("lib") ||
                        file.getName().equalsIgnoreCase("no_backup")) {
                        Log.d(TAG, "Skipping directory: " + file.getName());
                        continue;
                    }
                    addDirectoryToZip(zos, file, basePathLength, zipPathPrefix);
                } else {
                    String relativePath = file.getAbsolutePath().substring(basePathLength);
                    relativePath = relativePath.replace(File.separatorChar, '/');
                    // Add the prefix (e.g., "INTERNAL/") to the relative path
                    String zipEntryName = zipPathPrefix + relativePath;
                    ZipEntry zipEntry = new ZipEntry(zipEntryName);
                    zos.putNextEntry(zipEntry);
                    try (FileInputStream fis = new FileInputStream(file);
                         BufferedInputStream bis = new BufferedInputStream(fis, BUFFER_SIZE)) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int len;
                        while ((len = bis.read(buffer)) > 0) {
                            zos.write(buffer, 0, len);
                        }
                    }
                    zos.closeEntry();
                }
            }
        }
    }

    // Overloaded method for backward compatibility with the original method (only internal data)
    // You can remove this if you are sure you only need the new version.
    private void addDirectoryToZip(ZipOutputStream zos, File dir, int basePathLength) throws IOException {
        addDirectoryToZip(zos, dir, basePathLength, "");
    }
}
