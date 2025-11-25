package com.appcloner.replica;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import com.android.apksig.ApkSigner;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlDocument;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.ValueType;
import java.io.*;
import org.json.JSONException;
import org.json.JSONObject;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.zip.CRC32;

public class ApkProcessor {
    private static final String TAG = "ApkProcessor";
    private static final String ANDROID_MANIFEST = "AndroidManifest.xml";
    private static final String E_MANIFEST     = "manifest";
    private static final String E_APPLICATION  = "application";
    private static final String E_ACTIVITY     = "activity";
    private static final String E_PROVIDER     = "provider";
    private static final String E_RECEIVER     = "receiver";
    private static final String E_INTENT_FILTER = "intent-filter";
    private static final String E_ACTION       = "action";
    private static final String E_USES_PERMISSION = "uses-permission";
    private static final String A_NAME         = "name";
    private static final String A_AUTHORITIES  = "authorities";
    private static final String A_EXPORTED     = "exported";
    private static final String A_INIT_ORDER   = "initOrder";
    private static final String A_PERMISSION   = "permission";
    private static final int ID_ANDROID_NAME        = 0x01010003;
    private static final int ID_ANDROID_AUTHORITIES = 0x01010018;
    private static final int ID_ANDROID_EXPORTED    = 0x0101001e;
    private static final int ID_ANDROID_INIT_ORDER  = 0x01010427;
    private static final int ID_ANDROID_MIN_SDK     = 0x0101020c;
    private static final int ID_ANDROID_PERMISSION  = 0x01010006;
    private static final int ID_ANDROID_THEME       = 0x01010000;
    private static final int ID_ANDROID_LABEL       = 0x01010001;
    private static final int ID_ANDROID_ICON        = 0x01010002;
    private static final int ID_ANDROID_EXTRACT_NATIVE_LIBS = 0x010104ea;
    private static final Pattern SIG_PATH = Pattern.compile(
            "^META-INF/(.+\\.(RSA|DSA|EC|SF)|MANIFEST\\.MF)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEX_NAME = Pattern.compile(
            "^classes(\\d*)\\.dex$", Pattern.CASE_INSENSITIVE);
    private static final String KEYSTORE_ASSET = "debug.p12";
    private static final String STORE_PWD      = "android";
    private static final String KEY_PWD        = "android";
    private static final String ALIAS          = "key0";

    private static final String IPC_PERMISSION = "com.appcloner.replica.permission.REPLICA_IPC";
    private static final String PERM_READ_EXTERNAL = "android.permission.READ_EXTERNAL_STORAGE";
    private static final String PERM_WRITE_EXTERNAL = "android.permission.WRITE_EXTERNAL_STORAGE";
    private static final String RECEIVER_NAME = "com.applisto.appcloner.DataExportReceiver";
    private static final String EXPORT_ACTION = "com.applisto.appcloner.ACTION_EXPORT_DATA";
    private static final String CAMERA_CONTROL_RECEIVER_NAME = "com.applisto.appcloner.CameraControlReceiver";
    private static final String FAKE_CAMERA_ACTIVITY_NAME = "com.applisto.appcloner.FakeCameraActivity";
    private static final String CLONING_MODE_KEY = "cloning_mode";
    private static final String CLONING_MODE_REPLACE = "replace_original";
    private static final String CLONING_MODE_GENERATE = "generate_new_package";

    // Use a static authority for the provider
    private static final String PROVIDER_AUTHORITY = "com.applisto.appcloner.DefaultProvider";

    private final Context ctx;

    public ApkProcessor(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void injectHook(Uri inApk, Uri outApk, File hookDex, File clonerJson, File nativeLibDir, File bundledDataFile) throws Exception {
        if (inApk == null || outApk == null || hookDex == null || clonerJson == null) {
            throw new IllegalArgumentException("Required parameters cannot be null");
        }
        File tempRoot = new File(ctx.getCacheDir(), "apk_" + System.currentTimeMillis());
        if (!tempRoot.mkdirs()) throw new IOException("mkdir failed: " + tempRoot);
        Set<Integer> dexNumbers = new HashSet<>();
        Set<String> abiDirs = new HashSet<>();
        byte[] manifestRaw = null;

        String basePath;
        try {
            basePath = tempRoot.getCanonicalPath() + File.separator;
        } catch (IOException e) {
            deleteRec(tempRoot);
            throw e;
        }

        try (InputStream is = ctx.getContentResolver().openInputStream(inApk);
             ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is))) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                String name = ze.getName();
                if (name == null || name.isEmpty()) continue;
                if (SIG_PATH.matcher(name).matches()) continue;

                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    int slash = name.indexOf('/', 4);
                    if (slash > 0) abiDirs.add(name.substring(0, slash + 1));
                }

                Matcher m = DEX_NAME.matcher(name);
                if (m.matches()) {
                    int idx = m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
                    dexNumbers.add(idx);
                    Log.d(TAG, "Found DEX: " + name + " -> index " + idx);
                }

                if (ANDROID_MANIFEST.equals(name)) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    copyStream(zis, baos);
                    manifestRaw = baos.toByteArray();
                    continue;
                }

                File out = safeResolve(tempRoot, basePath, name);
                if (ze.isDirectory()) {
                    if (!out.exists() && !out.mkdirs()) {
                        throw new IOException("Failed to create dir: " + out);
                    }
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Failed to create parent: " + parent);
                }
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    copyStream(zis, fos);
                }
            }
        }

        if (manifestRaw == null) {
            deleteRec(tempRoot);
            throw new IOException("AndroidManifest.xml missing in APK");
        }

        JSONObject clonerConfig;
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(clonerJson), "UTF-8")) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer, 0, buffer.length)) != -1) {
                sb.append(buffer, 0, read);
            }
            clonerConfig = new JSONObject(sb.toString());
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to read or parse cloner.json, using default empty config.", e);
            clonerConfig = new JSONObject();
        }

        ManifestPatchResult manifestResult = patchManifest(manifestRaw, clonerConfig);
        byte[] patchedManifest = manifestResult.manifestBytes;

        int nextIdx = dexNumbers.isEmpty() ? 2 : Collections.max(dexNumbers) + 1;
        String dexName = (nextIdx == 1) ? "classes.dex" : "classes" + nextIdx + ".dex";
        Log.d(TAG, "Adding hook DEX as: " + dexName + " (nextIdx=" + nextIdx + ")");
        copyFile(hookDex, new File(tempRoot, dexName));

        File assetsDir = new File(tempRoot, "assets");
        if (!assetsDir.exists() && !assetsDir.mkdirs()) {
            deleteRec(tempRoot);
            throw new IOException("Failed to create assets dir");
        }
        copyFile(clonerJson, new File(assetsDir, "cloner.json"));

        if (bundledDataFile != null && bundledDataFile.exists()) {
            try {
                if (isProbablyZip(bundledDataFile) && bundledDataFile.length() <= 100L * 1024 * 1024) {
                    copyFile(bundledDataFile, new File(assetsDir, "app_data_export.zip"));
                    Log.d(TAG, "App data bundled into assets/app_data_export.zip");
                } else {
                    Log.w(TAG, "Bundled data file rejected (not a ZIP or too large). Skipping.");
                }
            } catch (IOException e) {
                Log.e(TAG, "Error bundling app data. Skipping.", e);
            }
        }

        boolean hasArm64 = abiDirs.contains("lib/arm64-v8a/");
        List<String> targetAbiDirs = new ArrayList<>();
        if (hasArm64) {
            targetAbiDirs.add("lib/arm64-v8a/");
        } else {
            targetAbiDirs.add("lib/armeabi-v7a/");
        }
        File[] soFiles = nativeLibDir.listFiles((d, n) -> n.endsWith(".so"));
        if (soFiles != null) {
            for (String abi : targetAbiDirs) {
                File dstDir = new File(tempRoot, abi);
                if (!dstDir.exists() && !dstDir.mkdirs()) {
                    deleteRec(tempRoot);
                    throw new IOException("Failed to create ABI dir: " + dstDir);
                }
                for (File so : soFiles) {
                    copyFile(so, new File(dstDir, so.getName()));
                }
            }
        }

        File unsignedApk = new File(ctx.getCacheDir(), "unsigned_" + System.nanoTime() + ".apk");
        zipDir(tempRoot, unsignedApk, patchedManifest);
        deleteRec(tempRoot);

        File signedApk = new File(ctx.getCacheDir(), "signed_" + System.nanoTime() + ".apk");
        try {
            signApk(unsignedApk, signedApk);
        } catch (Exception e) {
            Log.e(TAG, "Signing failed", e);
            unsignedApk.delete();
            signedApk.delete();
            throw e;
        }
        unsignedApk.delete();

        try (OutputStream os = ctx.getContentResolver().openOutputStream(outApk)) {
            if (os == null) {
                throw new IOException("Cannot open output stream for: " + outApk);
            }
            copyFileToStream(signedApk, os);
        } catch (Exception e) {
            Log.e(TAG, "Error writing output APK", e);
            signedApk.delete();
            throw e;
        }
        signedApk.delete();
        Log.i(TAG, "APK injection + signing completed successfully");
    }

    private ManifestPatchResult patchManifest(byte[] raw, JSONObject clonerConfig) throws IOException {
        ResXmlDocument doc = new ResXmlDocument();
        doc.readBytes(new ByteArrayInputStream(raw));
        ResXmlElement root = doc.getDocumentElement();
        if (root == null || !E_MANIFEST.equals(root.getName()))
            throw new IOException("Invalid manifest");
        ResXmlAttribute pkgAttr = root.searchAttributeByName("package");
        if (pkgAttr == null) throw new IOException("No package attribute");
        String pkg = pkgAttr.getValueAsString();
        String originalPkg = pkg;

        // Remove build metadata attributes that might cause parsing errors
        removeAttribute(root, "android:compileSdkVersion");
        removeAttribute(root, "android:compileSdkVersionCodename");
        removeAttribute(root, "platformBuildVersionCode");
        removeAttribute(root, "platformBuildVersionName");

        ResXmlElement app = root.getElement(E_APPLICATION);
        if (app == null) throw new IOException("<application> missing");

        // Force extractNativeLibs="true" because we compress .so files
        ResXmlAttribute extractNativeLibsAttr = app.searchAttributeByResourceId(ID_ANDROID_EXTRACT_NATIVE_LIBS);
        if (extractNativeLibsAttr == null) {
            extractNativeLibsAttr = app.searchAttributeByName("android:extractNativeLibs");
        }
        if (extractNativeLibsAttr == null) {
            extractNativeLibsAttr = app.createAndroidAttribute("extractNativeLibs", ID_ANDROID_EXTRACT_NATIVE_LIBS);
        }
        extractNativeLibsAttr.setValueAsBoolean(true);

        String cloningMode = CLONING_MODE_REPLACE;
        if (clonerConfig != null) {
            cloningMode = clonerConfig.optString(CLONING_MODE_KEY, CLONING_MODE_REPLACE);
        }
        if (CLONING_MODE_GENERATE.equalsIgnoreCase(cloningMode)) {
            String generatedPkg = generateVariantPackageName(pkg);
            if (generatedPkg != null && !generatedPkg.isEmpty() && !generatedPkg.equals(pkg)) {
                Log.d(TAG, "Cloning mode enabled: package will change from " + pkg + " to " + generatedPkg);
                pkgAttr.setValueAsString(generatedPkg);
                pkg = generatedPkg;
                ensureAbsoluteComponentNames(root, app, originalPkg);
                updateProviderAuthorities(app, originalPkg, pkg);
            } else {
                Log.w(TAG, "Cloning mode requested package rename but no valid variant was produced. Keeping original package.");
            }
        }

        String auth = PROVIDER_AUTHORITY;
        Log.d(TAG, "Processing package: " + pkg + ", provider authority: " + auth);

        addPermissionIfMissing(root, PERM_READ_EXTERNAL);
        addPermissionIfMissing(root, PERM_WRITE_EXTERNAL);
        addPermissionIfMissing(root, IPC_PERMISSION);

        boolean providerInjectedOrPresent = false;
        for (ResXmlElement p : app.listElements(E_PROVIDER)) {
            ResXmlAttribute a = p.searchAttributeByResourceId(ID_ANDROID_AUTHORITIES);
            if (a != null && auth.equals(a.getValueAsString())) {
                Log.d(TAG, "Provider already present.");
                providerInjectedOrPresent = true;
                break;
            }
        }

        boolean needInitOrder = true;
        ResXmlElement usesSdk = root.getElement("uses-sdk");
        if (usesSdk != null) {
            ResXmlAttribute minA = usesSdk.searchAttributeByResourceId(ID_ANDROID_MIN_SDK);
            if (minA != null) {
                try {
                    int min = -1;
                    ValueType vt = minA.getValueType();
                    if (vt == ValueType.DEC || vt == ValueType.HEX) {
                        min = minA.getData();
                    } else {
                        String s = minA.getValueAsString();
                        if (s != null) {
                            String numericOnly = s.replaceAll("[^0-9]", "");
                            if (!numericOnly.isEmpty()) min = Integer.parseInt(numericOnly);
                        }
                    }
                    if (min >= 0) {
                        needInitOrder = min >= 24;
                        Log.d(TAG, "MinSdk: " + min + ", needInitOrder: " + needInitOrder);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not parse minSdk, using default", e);
                }
            }
        }

        if (!providerInjectedOrPresent) {
            Log.d(TAG, "Injecting DefaultProvider");
            ResXmlElement prov = app.newElement(E_PROVIDER);
            prov.getOrCreateAndroidAttribute(A_NAME, ID_ANDROID_NAME)
                .setValueAsString("com.applisto.appcloner.DefaultProvider");
            prov.getOrCreateAndroidAttribute(A_AUTHORITIES, ID_ANDROID_AUTHORITIES)
                .setValueAsString(auth);
            prov.getOrCreateAndroidAttribute(A_EXPORTED, ID_ANDROID_EXPORTED)
                .setValueAsBoolean(true);
            prov.getOrCreateAndroidAttribute(A_PERMISSION, ID_ANDROID_PERMISSION)
                .setValueAsString(IPC_PERMISSION);
            if (needInitOrder) {
                ResXmlAttribute orderAttr = prov.getOrCreateAndroidAttribute(A_INIT_ORDER, ID_ANDROID_INIT_ORDER);
                orderAttr.setData(Integer.MAX_VALUE);
                orderAttr.setValueType(ValueType.DEC);
            }
        }

        boolean receiverExists = false;
        for (ResXmlElement r : app.listElements(E_RECEIVER)) {
            ResXmlAttribute nameAttr = r.searchAttributeByResourceId(ID_ANDROID_NAME);
            if (nameAttr != null && RECEIVER_NAME.equals(nameAttr.getValueAsString())) {
                receiverExists = true;
                break;
            }
        }
        if (!receiverExists) {
            Log.d(TAG, "Injecting DataExportReceiver");
            ResXmlElement receiver = app.newElement(E_RECEIVER);
            receiver.getOrCreateAndroidAttribute(A_NAME, ID_ANDROID_NAME)
                .setValueAsString(RECEIVER_NAME);
            receiver.getOrCreateAndroidAttribute(A_EXPORTED, ID_ANDROID_EXPORTED)
                .setValueAsBoolean(true);
            receiver.getOrCreateAndroidAttribute(A_PERMISSION, ID_ANDROID_PERMISSION)
                .setValueAsString(IPC_PERMISSION);

            ResXmlElement intentFilter = receiver.newElement(E_INTENT_FILTER);
            ResXmlElement action = intentFilter.newElement(E_ACTION);
            action.getOrCreateAndroidAttribute(A_NAME, ID_ANDROID_NAME)
                .setValueAsString(EXPORT_ACTION);
        }

        boolean cameraControlReceiverExists = false;
        for (ResXmlElement r : app.listElements(E_RECEIVER)) {
            ResXmlAttribute nameAttr = r.searchAttributeByResourceId(ID_ANDROID_NAME);
            if (nameAttr != null && CAMERA_CONTROL_RECEIVER_NAME.equals(nameAttr.getValueAsString())) {
                cameraControlReceiverExists = true;
                break;
            }
        }
        if (!cameraControlReceiverExists) {
            Log.d(TAG, "Injecting CameraControlReceiver");
            ResXmlElement receiver = app.newElement(E_RECEIVER);
            receiver.getOrCreateAndroidAttribute(A_NAME, ID_ANDROID_NAME)
                .setValueAsString(CAMERA_CONTROL_RECEIVER_NAME);
            receiver.getOrCreateAndroidAttribute(A_EXPORTED, ID_ANDROID_EXPORTED)
                .setValueAsBoolean(false);

            ResXmlElement intentFilter = receiver.newElement(E_INTENT_FILTER);

            String[] actions = new String[] {
                "com.applisto.appcloner.ACTION_ROTATE_CLOCKWISE",
                "com.applisto.appcloner.ACTION_ROTATE_COUNTERCLOCKWISE",
                "com.applisto.appcloner.ACTION_FLIP_HORIZONTALLY",
                "com.applisto.appcloner.ACTION_ZOOM_IN",
                "com.applisto.appcloner.ACTION_ZOOM_OUT"
            };

            for (String actionName : actions) {
                ResXmlElement action = intentFilter.newElement(E_ACTION);
                action.getOrCreateAndroidAttribute(A_NAME, ID_ANDROID_NAME)
                    .setValueAsString(actionName);
            }
        }

        boolean fakeCameraActivityExists = false;
        ResXmlElement fakeCameraActivity = null;
        for (ResXmlElement a : app.listElements(E_ACTIVITY)) {
            ResXmlAttribute nameAttr = a.searchAttributeByResourceId(ID_ANDROID_NAME);
            if (nameAttr != null && FAKE_CAMERA_ACTIVITY_NAME.equals(nameAttr.getValueAsString())) {
                fakeCameraActivityExists = true;
                fakeCameraActivity = a;
                break;
            }
        }
        if (!fakeCameraActivityExists) {
            Log.d(TAG, "Injecting FakeCameraActivity");
            ResXmlElement activity = app.newElement(E_ACTIVITY);
            activity.getOrCreateAndroidAttribute(A_NAME, ID_ANDROID_NAME)
                .setValueAsString(FAKE_CAMERA_ACTIVITY_NAME);
            activity.getOrCreateAndroidAttribute(A_EXPORTED, ID_ANDROID_EXPORTED)
                .setValueAsBoolean(true);
            fakeCameraActivity = activity;
        }

        // ensureFakeCameraActivityTheme(fakeCameraActivity);

        doc.refresh();

        ManifestPatchResult result = new ManifestPatchResult();
        result.manifestBytes = doc.getBytes();
        return result;
    }

    private String generateVariantPackageName(String pkg) {
        if (pkg == null) {
            return null;
        }
        String trimmed = pkg.trim();
        if (trimmed.isEmpty()) {
            return pkg;
        }
        char[] chars = trimmed.toCharArray();
        for (int i = chars.length - 1; i >= 0; i--) {
            char c = chars[i];
            if (Character.isLetter(c)) {
                if (Character.isUpperCase(c)) {
                    chars[i] = (c == 'Z') ? 'A' : (char) (c + 1);
                } else {
                    chars[i] = (c == 'z') ? 'a' : (char) (c + 1);
                }
                return new String(chars);
            }
        }
        return trimmed + "a";
    }

    private void ensureAbsoluteComponentNames(ResXmlElement root, ResXmlElement app, String originalPkg) {
        if (originalPkg == null || originalPkg.isEmpty()) {
            return;
        }
        if (app != null) {
            ensureAbsoluteClassReference(app.searchAttributeByResourceId(ID_ANDROID_NAME), originalPkg);
            ensureAbsoluteClassReference(app.searchAttributeByName("android:name"), originalPkg);
            ensureAbsoluteClassReference(app.searchAttributeByName("android:backupAgent"), originalPkg);
            ensureAbsoluteClassReference(app.searchAttributeByName("android:manageSpaceActivity"), originalPkg);
        }

        if (app != null) {
            normalizeComponentCollection(app.listElements(E_ACTIVITY), originalPkg, true);
            normalizeComponentCollection(app.listElements("activity-alias"), originalPkg, true);
            normalizeComponentCollection(app.listElements("service"), originalPkg, false);
            normalizeComponentCollection(app.listElements(E_RECEIVER), originalPkg, false);
            normalizeComponentCollection(app.listElements(E_PROVIDER), originalPkg, false);
        }

        for (ResXmlElement instrumentation : root.listElements("instrumentation")) {
            ensureAbsoluteClassReference(instrumentation.searchAttributeByResourceId(ID_ANDROID_NAME), originalPkg);
            ensureAbsoluteClassReference(instrumentation.searchAttributeByName("android:name"), originalPkg);
        }
    }

    private void normalizeComponentCollection(Iterable<ResXmlElement> elements, String originalPkg, boolean includeActivityExtras) {
        if (elements == null) {
            return;
        }
        for (ResXmlElement element : elements) {
            if (element == null) {
                continue;
            }
            ensureAbsoluteClassReference(element.searchAttributeByResourceId(ID_ANDROID_NAME), originalPkg);
            ensureAbsoluteClassReference(element.searchAttributeByName("android:name"), originalPkg);
            if (includeActivityExtras) {
                ensureAbsoluteClassReference(element.searchAttributeByName("android:targetActivity"), originalPkg);
                ensureAbsoluteClassReference(element.searchAttributeByName("android:parentActivityName"), originalPkg);
            }
        }
    }

    private void ensureAbsoluteClassReference(ResXmlAttribute attr, String originalPkg) {
        if (attr == null) {
            return;
        }
        String value = attr.getValueAsString();
        if (value == null || value.isEmpty()) {
            return;
        }
        if (value.startsWith(".")) {
            attr.setValueAsString(originalPkg + value);
        } else if (!value.contains(".")) {
            attr.setValueAsString(originalPkg + "." + value);
        }
    }

    private void updateProviderAuthorities(ResXmlElement app, String originalPkg, String newPkg) {
        if (app == null || originalPkg == null || originalPkg.isEmpty() || newPkg == null || newPkg.isEmpty()) {
            return;
        }
        for (ResXmlElement provider : app.listElements(E_PROVIDER)) {
            ResXmlAttribute authAttr = provider.searchAttributeByResourceId(ID_ANDROID_AUTHORITIES);
            if (authAttr == null) {
                authAttr = provider.searchAttributeByName("android:authorities");
            }
            if (authAttr == null) {
                continue;
            }
            String value = authAttr.getValueAsString();
            if (value == null || value.isEmpty()) {
                continue;
            }
            String updated = rewriteAuthorityValue(value, originalPkg, newPkg);
            if (updated != null && !updated.equals(value)) {
                authAttr.setValueAsString(updated);
            }
        }
    }

    private String rewriteAuthorityValue(String value, String originalPkg, String newPkg) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return value;
        }
        if (originalPkg == null || originalPkg.isEmpty() || newPkg == null || newPkg.isEmpty()) {
            return value;
        }
        String delimiter = null;
        if (value.contains(";")) {
            delimiter = ";";
        } else if (value.contains(",")) {
            delimiter = ",";
        }
        if (delimiter == null) {
            String updatedSingle = rewriteSingleAuthority(trimmed, originalPkg, newPkg);
            return updatedSingle.equals(trimmed) ? value : updatedSingle;
        }
        String regex = "\\s*" + Pattern.quote(delimiter) + "\\s*";
        String[] parts = value.split(regex);
        boolean changed = false;
        for (int i = 0; i < parts.length; i++) {
            String originalPart = parts[i].trim();
            String updatedPart = rewriteSingleAuthority(originalPart, originalPkg, newPkg);
            if (!updatedPart.equals(originalPart)) {
                parts[i] = updatedPart;
                changed = true;
            } else {
                parts[i] = originalPart;
            }
        }
        if (!changed) {
            return value;
        }
        return String.join(delimiter, Arrays.asList(parts));
    }

    private String rewriteSingleAuthority(String authority, String originalPkg, String newPkg) {
        if (authority == null || authority.isEmpty()) {
            return authority;
        }
        if (authority.equals(originalPkg)) {
            return newPkg;
        }
        if (authority.startsWith(originalPkg + ".")) {
            return newPkg + authority.substring(originalPkg.length());
        }
        return authority;
    }

    private void ensureFakeCameraActivityTheme(ResXmlElement activityElement) {
        if (activityElement == null) {
            return;
        }
        ResXmlAttribute themeAttr = activityElement.getOrCreateAndroidAttribute("theme", ID_ANDROID_THEME);
        try {
            themeAttr.setValueAsString("@android:style/Theme.NoTitleBar.Fullscreen");
            themeAttr.setValueType(ValueType.REFERENCE);
            themeAttr.setData(android.R.style.Theme_NoTitleBar_Fullscreen);
            if (themeAttr.getData() == 0) {
                throw new IllegalStateException("Theme reference resolved to ID 0");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to encode FakeCameraActivity theme; removing theme attribute to avoid installer parse errors.", t);
            themeAttr.removeSelf();
        }
    }

    private void addPermissionIfMissing(ResXmlElement root, String permission) {
        for (ResXmlElement perm : root.listElements(E_USES_PERMISSION)) {
            ResXmlAttribute nameAttr = perm.searchAttributeByResourceId(ID_ANDROID_NAME);
            if (nameAttr != null && permission.equals(nameAttr.getValueAsString())) {
                return;
            }
        }
        ResXmlElement permElement = root.newElement(E_USES_PERMISSION);
        permElement.getOrCreateAndroidAttribute(A_NAME, ID_ANDROID_NAME)
            .setValueAsString(permission);
    }

    private void zipDir(File root, File outFile, byte[] manifestBytes) throws IOException {
        try (OutputStream os = new FileOutputStream(outFile);
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(os))) {
            zos.setLevel(9);
            writeZip(root, zos, manifestBytes);
        }
    }

    private void writeZip(File root, ZipOutputStream zos, byte[] manifestBytes) throws IOException {
        ZipEntry manifestEntry = createZipEntry(ANDROID_MANIFEST, manifestBytes);
        zos.putNextEntry(manifestEntry);
        zos.write(manifestBytes);
        zos.closeEntry();
        addRec(root, root.getAbsolutePath(), zos);
    }

    private void addRec(File node, String base, ZipOutputStream zos) throws IOException {
        if (node.isDirectory()) {
            File[] kids = node.listFiles();
            if (kids != null) for (File k : kids) addRec(k, base, zos);
            return;
        }
        String rel = node.getAbsolutePath().substring(base.length() + 1).replace(File.separatorChar, '/');
        if (ANDROID_MANIFEST.equals(rel)) return;
        ZipEntry entry = createZipEntry(rel, node);
        zos.putNextEntry(entry);
        try (FileInputStream fis = new FileInputStream(node)) {
            copyStream(fis, zos);
        }
        zos.closeEntry();
    }

    private ZipEntry createZipEntry(String name, byte[] data) {
        ZipEntry e = new ZipEntry(name);
        e.setTime(0L);
        e.setMethod(ZipEntry.DEFLATED);
        return e;
    }

    private ZipEntry createZipEntry(String name, File file) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(0L);
        String lower = name.toLowerCase(Locale.US);
        // Only STORE (uncompressed) resources.arsc.
        // Native libs (.so) and DEX files (.dex) should be DEFLATED (compressed).
        // This ensures that native libs are automatically extracted by the system during install,
        -        // avoiding the requirement for 4KB page alignment of uncompressed libs in the APK.
        boolean store = lower.endsWith(".arsc");
        if (store) {
            e.setMethod(ZipEntry.STORED);
            long size = file.length();
            e.setSize(size);
            e.setCompressedSize(size);
            CRC32 crc = new CRC32();
            try (InputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    crc.update(buf, 0, r);
                }
            }
            e.setCrc(crc.getValue());
        } else {
            e.setMethod(ZipEntry.DEFLATED);
        }
        return e;
    }

    private void signApk(File in, File out) throws Exception {
        Log.d(TAG, "Loading signer config...");
        ApkSigner.SignerConfig signer = loadSignerConfig();
        Log.d(TAG, "Signer config loaded. Building ApkSigner...");
        ApkSigner apkSigner = new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)  // Disabled for better compatibility
                .build();
        Log.d(TAG, "Calling apkSigner.sign()...");
        apkSigner.sign();
        Log.d(TAG, "APK signed successfully.");

        try {
            Log.d(TAG, "Attempting APK verification...");
            Class<?> builderClass = Class.forName("com.android.apksig.ApkVerifier$Builder");
            Object builderInstance = builderClass.getConstructor(File.class).newInstance(out);
            Object apkVerifierInstance = builderClass.getMethod("build").invoke(builderInstance);
            Object vRes = apkVerifierInstance.getClass().getMethod("verify").invoke(apkVerifierInstance);
            Boolean isVerified = (Boolean) vRes.getClass().getMethod("isVerified").invoke(vRes);
            if (isVerified != null && !isVerified) {
                Log.w(TAG, "APK verification result: NOT VERIFIED (continuing)");
            } else {
                Log.d(TAG, "APK signature verification passed (or skipped).");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Verification skipped due to error", t);
        }
        Log.d(TAG, "Signing process completed.");
    }

    private ApkSigner.SignerConfig loadSignerConfig() throws Exception {
        Log.d(TAG, "Loading keystore: " + KEYSTORE_ASSET + " with alias: " + ALIAS);
        try (InputStream ksStream = ctx.getAssets().open(KEYSTORE_ASSET)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(ksStream, STORE_PWD.toCharArray());
            PrivateKey key = (PrivateKey) ks.getKey(ALIAS, KEY_PWD.toCharArray());
            if (key == null) throw new IllegalStateException("Private key is null for alias: " + ALIAS);
            X509Certificate cert = (X509Certificate) ks.getCertificate(ALIAS);
            if (cert == null) throw new IllegalStateException("Certificate is null for alias: " + ALIAS);
            return new ApkSigner.SignerConfig.Builder(ALIAS, key, Collections.singletonList(cert)).build();
        }
    }

    private static File safeResolve(File root, String basePath, String entryName) throws IOException {
        File out = new File(root, entryName);
        String outPath = out.getCanonicalPath();
        if (!outPath.startsWith(basePath)) {
            throw new IOException("Blocked zip path traversal: " + entryName);
        }
        return out;
    }

    private static boolean isProbablyZip(File f) {
        if (!f.isFile() || f.length() < 4) return false;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            int b0 = raf.read();
            int b1 = raf.read();
            int b2 = raf.read();
            int b3 = raf.read();
            return b0 == 0x50 && b1 == 0x4b && (b2 == 0x03 || b2 == 0x05 || b2 == 0x07) && (b3 == 0x04 || b3 == 0x06 || b3 == 0x08);
        } catch (IOException e) {
            return false;
        }
    }

    private static void copyFile(File source, File dest) throws IOException {
        if (source == null || dest == null) {
            throw new IllegalArgumentException("Source and destination files cannot be null");
        }
        if (!source.exists() || !source.isFile()) {
            throw new IOException("Source file does not exist: " + source);
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent);
        }
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(dest)) {
            copyStream(in, out);
        }
    }

    private static void copyFileToStream(File source, OutputStream dest) throws IOException {
        if (source == null || dest == null) {
            throw new IllegalArgumentException("Source file and destination stream cannot be null");
        }
        if (!source.exists() || !source.isFile()) {
            throw new IOException("Source file does not exist: " + source);
        }
        try (InputStream in = new FileInputStream(source)) {
            copyStream(in, dest);
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    private static void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        f.delete();
    }

    private void removeAttribute(ResXmlElement element, String name) {
        if (element == null || name == null) return;
        ResXmlAttribute attr = element.searchAttributeByName(name);
        if (attr != null) {
            attr.removeSelf();
        }
    }

    private static class ManifestPatchResult {
        byte[] manifestBytes;
    }
}
