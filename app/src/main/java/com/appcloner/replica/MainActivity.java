package com.appcloner.replica;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.android.apksig.ApkSigner; // Ensure ApkProcessor.java is present and this import is valid
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String INJECTED_PROVIDER_SUFFIX = ".com.applisto.appcloner.DefaultProvider";
    private static final Pattern SIG_PATH = Pattern.compile(
            "^META-INF/(.+\\.(RSA|DSA|EC|SF)|MANIFEST\\.MF)$", Pattern.CASE_INSENSITIVE);
    private static final String ANDROID_MANIFEST = "AndroidManifest.xml";
    private static final String KEYSTORE_ASSET = "debug.p12";
    private static final String STORE_PWD      = "android";
    private static final String KEY_PWD        = "android";
    private static final String ALIAS          = "key0";
    private static final String IPC_PERMISSION = "com.appcloner.replica.permission.REPLICA_IPC";
    private static final String BUNDLE_DATA_SETTING_KEY = "bundle_app_data";
    private static final String CLONING_MODE_KEY = "cloning_mode";
    private static final String CLONING_MODE_REPLACE = "replace_original";
    private static final String CLONING_MODE_GENERATE = "generate_new_package";
    private static final int WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 101;
    private static final Map<String, String> SPECIAL_TOKENS;
    private static final Map<String, List<String>> GROUP_CHILD_ORDER;

    static {
        Map<String, String> tokens = new HashMap<>();
        tokens.put("id", "ID");
        tokens.put("mac", "MAC");
        tokens.put("ua", "UA");
        tokens.put("api", "API");
        tokens.put("gps", "GPS");
        tokens.put("imei", "IMEI");
        tokens.put("imsi", "IMSI");
        tokens.put("bt", "BT");
        tokens.put("wifi", "Wi-Fi");
        tokens.put("dns", "DNS");
        tokens.put("ip", "IP");
        SPECIAL_TOKENS = Collections.unmodifiableMap(tokens);

        Map<String, List<String>> order = new HashMap<>();
        order.put("build", Arrays.asList("enabled", "name", "manufacturer", "brand", "model", "product", "device", "hardware", "fingerprint"));
        order.put("socks_proxy", Arrays.asList("enabled", "host", "port", "user", "pass", "alternative_mode", "api", "enable_placeholders", "list_random_proxy"));
        GROUP_CHILD_ORDER = Collections.unmodifiableMap(order);
    }
    private FloatingActionButton selectApkBtn;
    private FloatingActionButton processApkBtn;
    private TextView selectedTxt, statusTxt;
    private ListView installedAppsList, clonedAppsList;
    private TabLayout tabLayout;
    private FrameLayout settingsEditorContainer;
    private View currentSettingsEditorView = null;
    private File tempClonerJsonForEditor = null;
    private File sourceApkFileForEditor = null;
    private File bundledDataFileForCloning = null;
    private String bundledDataDisplayName = null;
    private Setting bundleDataSetting = null;
    private SettingsAdapter currentSettingsAdapter = null;
    private TextView bundleDataDialogSummary = null;
    private Button bundleDataDialogClearButton = null;
    private JSONObject currentSettingsJson = null;
    private File currentSettingsFile = null;
    private boolean currentSettingsIsClonedApp = false;
    private AppInfo currentSettingsApp = null;
    private Uri inputApkUri, outputApkUri;
    private List<AppInfo> allApps = new ArrayList<>();
    private List<AppInfo> clonedApps = new ArrayList<>();
    private AppListAdapter allAppsAdapter, clonedAppsAdapter;
    private SelectedAppInfo selectedAppInfo = null;
    private String pendingUninstallPackageName = null;
    private String pendingUninstallAppName = null;
    private File clonerJsonFile;
    private SecureRandom random = new SecureRandom();
    private BroadcastReceiver exportResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("com.appcloner.replica.EXPORT_COMPLETED".equals(action)) {
                String packageName = intent.getStringExtra("exported_package");
                String exportPath = intent.getStringExtra("export_path");
                boolean success = intent.getBooleanExtra("export_success", false);
                String errorMessage = intent.getStringExtra("error_message");
                runOnUiThread(() -> {
                    if (success && exportPath != null) {
                        statusTxt.setText("Data export completed for " + packageName);
                        Toast.makeText(MainActivity.this, "Data exported to: " + exportPath, Toast.LENGTH_LONG).show();
                    } else {
                        statusTxt.setText("Data export failed for " + packageName);
                        String message = "Export failed";
                        if (errorMessage != null) {
                            message += ": " + errorMessage;
                        }
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }
    };
    private final ActivityResultLauncher<Intent> pickApk =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() == Activity.RESULT_OK && res.getData() != null) {
                    inputApkUri = res.getData().getData();
                    if (inputApkUri != null) {
                        if (!isValidApk(inputApkUri)) {
                             statusTxt.setText("Error: Invalid APK file selected");
                             inputApkUri = null;
                             selectedTxt.setText("Select an APK");
                             processApkBtn.setEnabled(false);
                             selectedAppInfo = null;
                             hideSettingsEditor();
                             return;
                        }
                        selectedTxt.setText("Selected: " + inputApkUri.getLastPathSegment());
                        statusTxt.setText("APK selected. Ready to process.");
                        selectedAppInfo = null;
                        AppInfo externalApkInfo = new AppInfo();
                        externalApkInfo.appName = "External APK: " + inputApkUri.getLastPathSegment();
                        externalApkInfo.packageName = "external.apk";
                        showSettingsEditorForApp(externalApkInfo, false);
                    }
                }
            });
    private final ActivityResultLauncher<Intent> createApk =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() == Activity.RESULT_OK && res.getData() != null) {
                    outputApkUri = res.getData().getData();
                    if (outputApkUri != null) {
                        statusTxt.setText("Processing...");
                        startProcessing();
                    }
                }
            });
    private final ActivityResultLauncher<Intent> pickDataFile =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() == Activity.RESULT_OK && res.getData() != null) {
                    Uri dataUri = res.getData().getData();
                    if (dataUri != null) {
                        File targetFile = null;
                        try {
                            File cacheDir = getCacheDir();
                            targetFile = new File(cacheDir, "bundled_data_for_cloning.zip");
                            try (InputStream is = getContentResolver().openInputStream(dataUri);
                                 FileOutputStream fos = new FileOutputStream(targetFile)) {
                                byte[] buffer = new byte[8192];
                                int len;
                                while ((len = is.read(buffer)) > 0) {
                                    fos.write(buffer, 0, len);
                                }
                            }
                            bundledDataFileForCloning = targetFile;
                            String displayName = dataUri.getLastPathSegment();
                            if (displayName == null || displayName.trim().isEmpty()) {
                                displayName = targetFile.getName();
                            }
                            bundledDataDisplayName = displayName;
                            Toast.makeText(this, "Data file selected: " + displayName, Toast.LENGTH_SHORT).show();
                        } catch (IOException e) {
                            Log.e(TAG, "Error copying selected data file", e);
                            Toast.makeText(this, "Error selecting data file: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            if (targetFile != null && targetFile.exists() && !targetFile.delete()) {
                                Log.w(TAG, "Failed to delete temporary bundle data file after error: " + targetFile);
                            }
                            bundledDataFileForCloning = null;
                            bundledDataDisplayName = null;
                        }
                        refreshBundleDataUi();
                    }
                }
            });
    private final ActivityResultLauncher<Intent> uninstallLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                int resultCode = res.getResultCode();
                Intent data = res.getData();
                String reportedPackage = data != null ? data.getStringExtra(Intent.EXTRA_PACKAGE_NAME) : null;
                String targetPackage = reportedPackage != null ? reportedPackage : pendingUninstallPackageName;
                String targetLabel = pendingUninstallAppName;

                pendingUninstallPackageName = null;
                pendingUninstallAppName = null;

                if (resultCode == Activity.RESULT_OK) {
                    String displayName = targetLabel != null ? targetLabel : targetPackage;
                    if (statusTxt != null) {
                        statusTxt.setText(displayName != null
                                ? "Uninstalled " + displayName
                                : "Uninstall completed");
                    }
                    Toast.makeText(MainActivity.this,
                            displayName != null ? "Uninstalled " + displayName : "Uninstall completed",
                            Toast.LENGTH_SHORT).show();
                } else if (resultCode == Activity.RESULT_CANCELED) {
                    String displayName = targetLabel != null ? targetLabel : targetPackage;
                    if (statusTxt != null) {
                        statusTxt.setText("Uninstall canceled");
                    }
                    Toast.makeText(MainActivity.this,
                            displayName != null ? "Uninstall canceled for " + displayName : "Uninstall canceled",
                            Toast.LENGTH_SHORT).show();
                } else {
                    String displayName = targetLabel != null ? targetLabel : targetPackage;
                    if (statusTxt != null) {
                        statusTxt.setText(displayName != null
                                ? "Uninstall failed for " + displayName
                                : "Uninstall failed");
                    }
                    Toast.makeText(MainActivity.this,
                            displayName != null ? "Failed to uninstall " + displayName : "Uninstall failed",
                            Toast.LENGTH_LONG).show();
                }

                loadInstalledApplications();
            });
    private boolean receiverRegistered = false;
    
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        initViews();
        setupTabs();
        loadInstalledApplications();
        setupClickListeners();
        clonerJsonFile = new File(getCacheDir(), "cloner.json");
        IntentFilter filter = new IntentFilter("com.appcloner.replica.EXPORT_COMPLETED");
        registerReceiver(exportResultReceiver, filter);
        receiverRegistered = true;
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (receiverRegistered) {
            try {
                unregisterReceiver(exportResultReceiver);
                receiverRegistered = false;
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Receiver not registered", e);
            }
        }
    }

    private void showEditorDialogForSetting(final Setting setting, final JSONObject json, final BaseAdapter adapter) {
        if (setting == null) {
            return;
        }

        if (BUNDLE_DATA_SETTING_KEY.equals(setting.key)) {
            showBundleDataDialog(setting);
            return;
        }

        if (CLONING_MODE_KEY.equals(setting.key)) {
            showCloningModeDialog(setting, json, adapter);
            return;
        }

        if (setting.isGroup()) {
            showGroupedSettingsDialog(setting, json, adapter);
            return;
        }

        if ("android_id".equals(setting.key)) {
            showAndroidIdDialog(setting, json, adapter);
            return;
        }

        if ("wifi_mac".equals(setting.key)) {
            showWifiMacDialog(setting, json, adapter);
            return;
        }

        Object value = setting.value;
        if (value instanceof Boolean) {
            showBooleanEditorDialog(setting, json, adapter);
        } else if (value instanceof JSONObject) {
            showJsonObjectEditorDialog(setting, json, adapter);
        } else {
            showTextEditorDialog(setting, json, adapter);
        }
    }

    private void showBooleanEditorDialog(final Setting setting, final JSONObject json, final BaseAdapter adapter) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        String displayName = formatSettingName(setting.key);
        builder.setTitle("Edit " + displayName);

        final Switch switchView = new Switch(this);
        boolean current = false;
        if (setting.value instanceof Boolean) {
            current = (Boolean) setting.value;
        }
        switchView.setChecked(current);
        switchView.setPadding(48, 24, 48, 24);
        builder.setView(switchView);

        builder.setPositiveButton("OK", (dialog, which) -> {
            try {
                boolean newValue = switchView.isChecked();
                setting.value = newValue;
                setting.valueClass = Boolean.class;
                json.put(setting.key, newValue);
                adapter.notifyDataSetChanged();
            } catch (JSONException e) {
                Log.e(TAG, "Error saving boolean setting: " + setting.key, e);
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.create().show();
    }

    private void showTextEditorDialog(final Setting setting, final JSONObject json, final BaseAdapter adapter) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        String displayName = formatSettingName(setting.key);
        builder.setTitle("Edit " + displayName);

        final EditText input = new EditText(this);
        Object value = setting.value;
        String currentValue = "";
        if (value != null && !JSONObject.NULL.equals(value)) {
            currentValue = value.toString();
        }
        input.setText(currentValue);
        input.setHint("Enter value for " + displayName);
        input.setSingleLine(true);
        Class<?> valueClass = setting.valueClass != null ? setting.valueClass : inferValueClass(value);
        if (Number.class.isAssignableFrom(valueClass)) {
            if (valueClass == Integer.class || valueClass == Long.class) {
                input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
            } else {
                input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
            }
        }
        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = 48;
        params.rightMargin = 48;
        input.setLayoutParams(params);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("OK", (dialog, which) -> {
            try {
                String text = input.getText().toString().trim();
                Object newValue;
                if (text.isEmpty()) {
                    newValue = JSONObject.NULL;
                } else if (Number.class.isAssignableFrom(valueClass)) {
                    if (valueClass == Integer.class) {
                        newValue = Integer.parseInt(text);
                    } else if (valueClass == Long.class) {
                        newValue = Long.parseLong(text);
                    } else if (valueClass == Float.class) {
                        newValue = Float.parseFloat(text);
                    } else {
                        newValue = Double.parseDouble(text);
                    }
                } else {
                    newValue = text;
                }

                setting.value = newValue;
                if (newValue != JSONObject.NULL) {
                    setting.valueClass = newValue.getClass();
                    json.put(setting.key, newValue);
                } else {
                    json.put(setting.key, JSONObject.NULL);
                }
                adapter.notifyDataSetChanged();
            } catch (NumberFormatException e) {
                Toast.makeText(MainActivity.this, "Invalid value. Please check your input.", Toast.LENGTH_SHORT).show();
            } catch (JSONException e) {
                Log.e(TAG, "Error saving text setting: " + setting.key, e);
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.create().show();
    }

    private void showGroupedSettingsDialog(final Setting groupSetting, final JSONObject json, final BaseAdapter adapter) {
        if (groupSetting == null || !groupSetting.isGroup()) {
            return;
        }

        final String title = formatGroupTitle(groupSetting.key);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);

        float density = getResources().getDisplayMetrics().density;
        int padding = (int) (16 * density);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, padding, padding, padding);
        scrollView.addView(container);

        final Map<String, View> inputs = new LinkedHashMap<>();

        List<Setting> orderedChildren = new ArrayList<>(groupSetting.children);
        sortGroupChildren(groupSetting.key, orderedChildren);

        Setting toggleSetting = null;
        for (Setting child : orderedChildren) {
            if (isToggleKey(groupSetting.key, child.key)) {
                toggleSetting = child;
                break;
            }
        }

        if (toggleSetting != null) {
            CheckBox enableCheckBox = new CheckBox(this);
            enableCheckBox.setText("Enable " + title);
            boolean checked = toggleSetting.value instanceof Boolean && (Boolean) toggleSetting.value;
            enableCheckBox.setChecked(checked);
            container.addView(enableCheckBox);
            inputs.put(toggleSetting.key, enableCheckBox);
        }

        for (Setting child : orderedChildren) {
            if (toggleSetting != null && toggleSetting.key.equals(child.key)) {
                continue;
            }

            Object rawValue = child.value;
            String suffix = getChildSuffix(groupSetting.key, child.key);
            String labelText = formatChildLabel(groupSetting.key, suffix);

            if (rawValue instanceof Boolean) {
                CheckBox checkBox = new CheckBox(this);
                checkBox.setText(labelText);
                boolean checked = rawValue instanceof Boolean && (Boolean) rawValue;
                checkBox.setChecked(checked);
                container.addView(checkBox);
                inputs.put(child.key, checkBox);
            } else {
                TextView label = new TextView(this);
                label.setText(labelText);
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                labelParams.topMargin = (int) (8 * density);
                label.setLayoutParams(labelParams);
                container.addView(label);

                EditText input = new EditText(this);
                input.setSingleLine(true);
                String text = "";
                if (rawValue != null && !JSONObject.NULL.equals(rawValue)) {
                    text = rawValue.toString();
                }
                input.setText(text);
                Class<?> valueClass = child.valueClass != null ? child.valueClass : inferValueClass(rawValue);
                if (Number.class.isAssignableFrom(valueClass)) {
                    if (valueClass == Integer.class || valueClass == Long.class) {
                        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
                    } else {
                        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
                    }
                }
                LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                inputParams.topMargin = (int) (4 * density);
                input.setLayoutParams(inputParams);
                container.addView(input);
                inputs.put(child.key, input);
            }
        }

        if (inputs.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText("No configurable values available.");
            container.addView(emptyView);
        }

        builder.setView(scrollView);
        builder.setNegativeButton("Cancel", null);
        builder.setPositiveButton("Save", null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            saveButton.setOnClickListener(v -> {
                try {
                    for (Setting child : groupSetting.children) {
                        View inputView = inputs.get(child.key);
                        if (inputView == null) {
                            continue;
                        }
                        if (inputView instanceof CheckBox) {
                            boolean checked = ((CheckBox) inputView).isChecked();
                            child.value = checked;
                            child.valueClass = Boolean.class;
                            json.put(child.key, checked);
                        } else if (inputView instanceof EditText) {
                            EditText editText = (EditText) inputView;
                            String text = editText.getText().toString().trim();
                            Class<?> targetClass = child.valueClass != null ? child.valueClass : inferValueClass(child.value);
                            Object newValue;
                            if (text.isEmpty()) {
                                newValue = JSONObject.NULL;
                            } else if (Number.class.isAssignableFrom(targetClass)) {
                                if (targetClass == Integer.class) {
                                    newValue = Integer.parseInt(text);
                                } else if (targetClass == Long.class) {
                                    newValue = Long.parseLong(text);
                                } else if (targetClass == Float.class) {
                                    newValue = Float.parseFloat(text);
                                } else {
                                    newValue = Double.parseDouble(text);
                                }
                            } else {
                                newValue = text;
                            }

                            child.value = newValue;
                            if (newValue != JSONObject.NULL) {
                                child.valueClass = newValue.getClass();
                                json.put(child.key, newValue);
                            } else {
                                json.put(child.key, JSONObject.NULL);
                            }
                        }
                    }
                    adapter.notifyDataSetChanged();
                    dialog.dismiss();
                } catch (NumberFormatException ex) {
                    Toast.makeText(MainActivity.this, "Invalid value. Please check your input.", Toast.LENGTH_SHORT).show();
                } catch (JSONException ex) {
                    Log.e(TAG, "Error saving grouped setting: " + groupSetting.key, ex);
                    Toast.makeText(MainActivity.this, "Failed to save settings.", Toast.LENGTH_SHORT).show();
                }
            });
        });
        dialog.show();
    }

    private void showAndroidIdDialog(final Setting setting, final JSONObject json, final BaseAdapter adapter) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Change Android ID");

        float density = getResources().getDisplayMetrics().density;
        int padding = (int) (16 * density);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, padding, padding, padding);

        TextView modeLabel = new TextView(this);
        modeLabel.setText("Mode");
        container.addView(modeLabel);

        final String[] modes = {"Keep Original", "Custom", "Random"};
        Spinner modeSpinner = new Spinner(this);
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modes);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modeAdapter);
        container.addView(modeSpinner);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = (int) (12 * density);
        inputRow.setLayoutParams(rowParams);

        final EditText input = new EditText(this);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        input.setLayoutParams(inputParams);
        input.setSingleLine(true);
        input.setHint("Enter Android ID");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(16)});
        input.setKeyListener(DigitsKeyListener.getInstance("0123456789ABCDEF"));
        inputRow.addView(input);

        final Button generateButton = new Button(this);
        generateButton.setText("Generate");
        inputRow.addView(generateButton);

        container.addView(inputRow);

        Object rawValue = setting.value;
        String currentValue = (rawValue == null || JSONObject.NULL.equals(rawValue)) ? "" : rawValue.toString().toUpperCase(Locale.US);
        input.setText(currentValue);

        final int[] selectedMode = new int[1];
        final String[] customHolder = new String[]{currentValue};

        selectedMode[0] = currentValue.isEmpty() ? 0 : 1;
        modeSpinner.setSelection(selectedMode[0]);

        generateButton.setOnClickListener(v -> {
            String generated = randomAndroidId().toUpperCase(Locale.US);
            input.setText(generated);
            input.setSelection(generated.length());
            customHolder[0] = generated;
        });

        final boolean[] firstSelection = {true};
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int previous = selectedMode[0];
                if (!firstSelection[0] && previous == 1) {
                    customHolder[0] = input.getText().toString().trim();
                }
                selectedMode[0] = position;

                if (position == 0) {
                    input.setEnabled(false);
                    generateButton.setEnabled(false);
                    input.setText("");
                } else if (position == 1) {
                    input.setEnabled(true);
                    generateButton.setEnabled(true);
                    input.setText(customHolder[0]);
                    input.setSelection(input.getText().length());
                } else {
                    input.setEnabled(false);
                    generateButton.setEnabled(false);
                    String generated = randomAndroidId().toUpperCase(Locale.US);
                    input.setText(generated);
                }
                firstSelection[0] = false;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // no-op
            }
        });

        builder.setView(container);
        builder.setNegativeButton("Cancel", null);
        builder.setPositiveButton("OK", null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            saveButton.setOnClickListener(v -> {
                try {
                    int mode = selectedMode[0];
                    if (mode == 0) {
                        setting.value = JSONObject.NULL;
                        setting.valueClass = String.class;
                        json.put(setting.key, JSONObject.NULL);
                    } else {
                        String valueText = input.getText().toString().trim();
                        if (valueText.length() != 16) {
                            Toast.makeText(MainActivity.this, "Android ID must be 16 hex characters.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        setting.value = valueText;
                        setting.valueClass = String.class;
                        json.put(setting.key, valueText);
                    }
                    adapter.notifyDataSetChanged();
                    dialog.dismiss();
                } catch (JSONException e) {
                    Log.e(TAG, "Error saving android_id", e);
                    Toast.makeText(MainActivity.this, "Failed to save Android ID.", Toast.LENGTH_SHORT).show();
                }
            });
        });
        dialog.show();
    }

    private void showWifiMacDialog(final Setting setting, final JSONObject json, final BaseAdapter adapter) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Change WiFi MAC");

        float density = getResources().getDisplayMetrics().density;
        int padding = (int) (16 * density);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, padding, padding, padding);

        TextView modeLabel = new TextView(this);
        modeLabel.setText("Mode");
        container.addView(modeLabel);

        final String[] modes = {"Keep Original", "Custom", "Random"};
        Spinner modeSpinner = new Spinner(this);
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modes);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modeAdapter);
        container.addView(modeSpinner);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = (int) (12 * density);
        inputRow.setLayoutParams(rowParams);

        final EditText input = new EditText(this);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        input.setLayoutParams(inputParams);
        input.setSingleLine(true);
        input.setHint("Enter WiFi MAC (XX:XX:XX:XX:XX:XX)");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(17)});
        input.setKeyListener(DigitsKeyListener.getInstance("0123456789ABCDEF:"));
        inputRow.addView(input);

        final Button generateButton = new Button(this);
        generateButton.setText("Generate");
        inputRow.addView(generateButton);

        container.addView(inputRow);

        Object rawValue = setting.value;
        String currentValue = (rawValue == null || "NO_CHANGE".equals(rawValue)) ? "" : rawValue.toString().toUpperCase(Locale.US);
        input.setText(currentValue);

        final int[] selectedMode = new int[1];
        final String[] customHolder = new String[]{currentValue};

        selectedMode[0] = currentValue.isEmpty() ? 0 : 1;
        modeSpinner.setSelection(selectedMode[0]);

        generateButton.setOnClickListener(v -> {
            String generated = randomMac().toUpperCase(Locale.US);
            input.setText(generated);
            input.setSelection(generated.length());
            customHolder[0] = generated;
        });

        final boolean[] firstSelection = {true};
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int previous = selectedMode[0];
                if (!firstSelection[0] && previous == 1) {
                    customHolder[0] = input.getText().toString().trim();
                }
                selectedMode[0] = position;

                if (position == 0) {
                    input.setEnabled(false);
                    generateButton.setEnabled(false);
                    input.setText("");
                } else if (position == 1) {
                    input.setEnabled(true);
                    generateButton.setEnabled(true);
                    input.setText(customHolder[0]);
                    input.setSelection(input.getText().length());
                } else {
                    input.setEnabled(false);
                    generateButton.setEnabled(false);
                    String generated = randomMac().toUpperCase(Locale.US);
                    input.setText(generated);
                }
                firstSelection[0] = false;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // no-op
            }
        });

        builder.setView(container);
        builder.setNegativeButton("Cancel", null);
        builder.setPositiveButton("OK", null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            saveButton.setOnClickListener(v -> {
                try {
                    int mode = selectedMode[0];
                    if (mode == 0) {
                        setting.value = "NO_CHANGE";
                        setting.valueClass = String.class;
                        json.put(setting.key, "NO_CHANGE");
                    } else {
                        String valueText = input.getText().toString().trim();
                        if (valueText.length() != 17 || !valueText.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) {
                            Toast.makeText(MainActivity.this, "WiFi MAC must be 17 characters in format XX:XX:XX:XX:XX:XX (hex).", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        setting.value = valueText;
                        setting.valueClass = String.class;
                        json.put(setting.key, valueText);
                    }
                    adapter.notifyDataSetChanged();
                    dialog.dismiss();
                } catch (JSONException e) {
                    Log.e(TAG, "Error saving wifi_mac", e);
                    Toast.makeText(MainActivity.this, "Failed to save WiFi MAC.", Toast.LENGTH_SHORT).show();
                }
            });
        });
        dialog.show();
    }

    private void showBundleDataDialog(final Setting setting) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Bundle App Data");

        bundleDataSetting = setting;

        float density = getResources().getDisplayMetrics().density;
        int padding = (int) (16 * density);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, padding, padding, padding);

        TextView statusText = new TextView(this);
        container.addView(statusText);

        TextView hintText = new TextView(this);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = (int) (8 * density);
        hintText.setLayoutParams(hintParams);
        hintText.setText("Select a ZIP archive to package alongside the cloned APK.");
        container.addView(hintText);

        Button selectButton = new Button(this);
        LinearLayout.LayoutParams selectParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        selectParams.topMargin = (int) (16 * density);
        selectButton.setLayoutParams(selectParams);
        selectButton.setText("Select Data ZIP");
        container.addView(selectButton);

        Button clearButton = new Button(this);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clearParams.topMargin = (int) (12 * density);
        clearButton.setLayoutParams(clearParams);
        clearButton.setText("Remove Selection");
        container.addView(clearButton);

        bundleDataDialogSummary = statusText;
        bundleDataDialogClearButton = clearButton;
        statusText.setText(getBundleDataDialogStatusText());
        clearButton.setEnabled(bundledDataFileForCloning != null);

        selectButton.setOnClickListener(v -> {
            bundleDataDialogSummary = statusText;
            bundleDataDialogClearButton = clearButton;
            pickDataFile();
        });

        clearButton.setOnClickListener(v -> {
            if (bundledDataFileForCloning != null && bundledDataFileForCloning.exists() && !bundledDataFileForCloning.delete()) {
                Log.w(TAG, "Failed to delete bundled data file when clearing selection");
            }
            bundledDataFileForCloning = null;
            bundledDataDisplayName = null;
            Toast.makeText(MainActivity.this, "Bundled data cleared", Toast.LENGTH_SHORT).show();
            refreshBundleDataUi();
        });

        builder.setView(container);
        builder.setNegativeButton("Close", (dialog, which) -> {
            bundleDataDialogSummary = null;
            bundleDataDialogClearButton = null;
        });

        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> {
            bundleDataDialogSummary = null;
            bundleDataDialogClearButton = null;
        });
        dialog.show();
    }

    private void showJsonObjectEditorDialog(final Setting setting, final JSONObject json, final BaseAdapter adapter) {
        JSONObject object = (JSONObject) setting.value;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit " + formatSettingName(setting.key));

        ScrollView scrollView = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding, padding, padding);
        scrollView.addView(container);

        List<String> childKeys = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            childKeys.add(iterator.next());
        }
        Collections.sort(childKeys, String.CASE_INSENSITIVE_ORDER);

        final List<JsonObjectField> fields = new ArrayList<>();

        if (childKeys.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText("No configurable values available.");
            container.addView(emptyView);
        } else {
            for (int i = 0; i < childKeys.size(); i++) {
                String childKey = childKeys.get(i);
                Object rawValue = object.opt(childKey);
                if (JSONObject.NULL.equals(rawValue)) {
                    rawValue = null;
                }

                if (rawValue instanceof Boolean) {
                    CheckBox checkBox = new CheckBox(this);
                    checkBox.setText(formatSettingName(childKey));
                    checkBox.setChecked((Boolean) rawValue);
                    container.addView(checkBox);
                    fields.add(new JsonObjectField(childKey, checkBox, Boolean.class));
                } else {
                    TextView label = new TextView(this);
                    label.setText(formatSettingName(childKey));
                    if (i > 0) {
                        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                        labelParams.topMargin = (int) (12 * getResources().getDisplayMetrics().density);
                        label.setLayoutParams(labelParams);
                    }
                    container.addView(label);

                    EditText input = new EditText(this);
                    input.setText(rawValue != null ? String.valueOf(rawValue) : "");
                    Class<?> valueClass = (rawValue != null) ? rawValue.getClass() : String.class;
                    if (Number.class.isAssignableFrom(valueClass)) {
                        if (valueClass == Integer.class || valueClass == Long.class) {
                            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
                        } else {
                            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
                        }
                    }
                    container.addView(input);
                    fields.add(new JsonObjectField(childKey, input, valueClass));
                }
            }
        }

        builder.setView(scrollView);
        builder.setNegativeButton("Cancel", null);
        builder.setPositiveButton("Save", null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            saveButton.setOnClickListener(v -> {
                try {
                    for (JsonObjectField field : fields) {
                        if (field.valueClass == Boolean.class && field.inputView instanceof CheckBox) {
                            object.put(field.key, ((CheckBox) field.inputView).isChecked());
                        } else if (field.inputView instanceof EditText) {
                            EditText editText = (EditText) field.inputView;
                            String text = editText.getText().toString().trim();
                            if (text.isEmpty()) {
                                object.put(field.key, JSONObject.NULL);
                            } else if (field.valueClass == Integer.class) {
                                object.put(field.key, Integer.parseInt(text));
                            } else if (field.valueClass == Long.class) {
                                object.put(field.key, Long.parseLong(text));
                            } else if (Number.class.isAssignableFrom(field.valueClass)) {
                                object.put(field.key, Double.parseDouble(text));
                            } else {
                                object.put(field.key, text);
                            }
                        }
                    }
                    setting.value = object;
                    json.put(setting.key, object);
                    adapter.notifyDataSetChanged();
                    dialog.dismiss();
                } catch (NumberFormatException | JSONException e) {
                    Log.e(TAG, "Error saving JSON object setting: " + setting.key, e);
                    Toast.makeText(MainActivity.this, "Invalid value. Please check your input.", Toast.LENGTH_SHORT).show();
                }
            });
        });
        dialog.show();
    }

    private void refreshBundleDataUi() {
        if (bundleDataSetting != null) {
            bundleDataSetting.value = bundledDataDisplayName;
            bundleDataSetting.valueClass = String.class;
        }
        if (bundleDataDialogSummary != null) {
            bundleDataDialogSummary.setText(getBundleDataDialogStatusText());
        }
        if (bundleDataDialogClearButton != null) {
            bundleDataDialogClearButton.setEnabled(bundledDataFileForCloning != null);
        }
        if (currentSettingsAdapter != null) {
            currentSettingsAdapter.notifyDataSetChanged();
        }
    }

    private String getBundleDataSummaryText() {
        if (bundledDataFileForCloning != null) {
            String name = bundledDataDisplayName;
            if (name == null || name.trim().isEmpty()) {
                name = bundledDataFileForCloning.getName();
            }
            return "Selected: " + name;
        }
        return "Tap to select data ZIP";
    }

    private String getBundleDataDialogStatusText() {
        if (bundledDataFileForCloning != null) {
            String name = bundledDataDisplayName;
            if (name == null || name.trim().isEmpty()) {
                name = bundledDataFileForCloning.getName();
            }
            return "Selected file: " + name;
        }
        return "No ZIP selected.";
    }

    private void showCloningModeDialog(final Setting setting, final JSONObject json, final BaseAdapter adapter) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Cloning Mode");

        final String[] values = {CLONING_MODE_REPLACE, CLONING_MODE_GENERATE};
        final String[] labels = {
                "Replace original (keep package)",
                "Generate new package variant"
        };

        String currentValue = setting.value instanceof String ? (String) setting.value : CLONING_MODE_REPLACE;
        int selectedIndex = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(currentValue)) {
                selectedIndex = i;
                break;
            }
        }

        final int[] chosenIndex = {selectedIndex};
        builder.setSingleChoiceItems(labels, selectedIndex, (dialog, which) -> chosenIndex[0] = which);
        builder.setPositiveButton("OK", (dialog, which) -> {
            try {
                String newValue = values[chosenIndex[0]];
                setting.value = newValue;
                setting.valueClass = String.class;
                json.put(CLONING_MODE_KEY, newValue);
                adapter.notifyDataSetChanged();
            } catch (JSONException e) {
                Log.e(TAG, "Error saving cloning mode", e);
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.create().show();
    }

    private List<Setting> buildSettingsList(JSONObject json) throws JSONException {
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = json.keys();
        while (iterator.hasNext()) {
            keys.add(iterator.next());
        }
        Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);

        Map<String, List<Setting>> grouped = new LinkedHashMap<>();
        List<Setting> singles = new ArrayList<>();

        for (String key : keys) {
            if (key.startsWith("icon_")) {
                continue;
            }
            Object value = json.get(key);
            Setting setting = new Setting(key, value, inferValueClass(value));
            if (value instanceof JSONObject) {
                singles.add(setting);
                continue;
            }
            String base = getGroupBase(key);
            if (base != null) {
                grouped.computeIfAbsent(base, k -> new ArrayList<>()).add(setting);
            } else {
                singles.add(setting);
            }
        }

        List<Setting> result = new ArrayList<>(singles);
        for (Map.Entry<String, List<Setting>> entry : grouped.entrySet()) {
            List<Setting> children = entry.getValue();
            if (children.size() > 1) {
                sortGroupChildren(entry.getKey(), children);
                Setting group = new Setting(entry.getKey(), null, null);
                for (Setting child : children) {
                    child.parent = group;
                }
                group.children = children;
                result.add(group);
            } else if (!children.isEmpty()) {
                result.add(children.get(0));
            }
        }

        Collections.sort(result, (a, b) -> getDisplayName(a).compareToIgnoreCase(getDisplayName(b)));
        return result;
    }

    private String getGroupBase(String key) {
        if (key == null) {
            return null;
        }
        int index = key.lastIndexOf('_');
        if (index <= 0) {
            return null;
        }
        return key.substring(0, index);
    }

    private void sortGroupChildren(String groupKey, List<Setting> children) {
        List<String> order = GROUP_CHILD_ORDER.get(groupKey);
        Collections.sort(children, (a, b) -> {
            String suffixA = getChildSuffix(groupKey, a.key).toLowerCase(Locale.US);
            String suffixB = getChildSuffix(groupKey, b.key).toLowerCase(Locale.US);
            if (order != null) {
                int indexA = order.indexOf(suffixA);
                int indexB = order.indexOf(suffixB);
                if (indexA != indexB) {
                    if (indexA == -1) indexA = Integer.MAX_VALUE;
                    if (indexB == -1) indexB = Integer.MAX_VALUE;
                    return Integer.compare(indexA, indexB);
                }
            }
            return suffixA.compareToIgnoreCase(suffixB);
        });
    }

    private String getDisplayName(Setting setting) {
        if (setting == null) {
            return "";
        }
        return setting.isGroup() ? formatGroupTitle(setting.key) : formatSettingName(setting.key);
    }

    private String formatGroupTitle(String key) {
        if (key == null) {
            return "";
        }
        String normalized = key.toLowerCase(Locale.US);
        switch (normalized) {
            case "build":
                return "Builds Props";
            case "socks_proxy":
                return "Socks Proxy";
            default:
                return formatSettingName(key);
        }
    }

    private String getGroupSummary(Setting groupSetting) {
        if (groupSetting == null || !groupSetting.isGroup()) {
            return "";
        }

        Setting toggleSetting = null;
        int configured = 0;

        for (Setting child : groupSetting.children) {
            if (isToggleKey(groupSetting.key, child.key)) {
                if (child.value instanceof Boolean) {
                    toggleSetting = child;
                }
                continue;
            }
            Object value = child.value;
            if (value instanceof Boolean) {
                if ((Boolean) value) {
                    configured++;
                }
            } else if (value != null && !JSONObject.NULL.equals(value) && !value.toString().trim().isEmpty()) {
                configured++;
            }
        }

        if (toggleSetting != null && toggleSetting.value instanceof Boolean) {
            return (Boolean) toggleSetting.value ? "Enabled" : "Disabled";
        }

        if (configured == 0) {
            return "Tap to configure";
        }
        return configured == 1 ? "1 value set" : configured + " values set";
    }

    private boolean isToggleKey(String groupKey, String childKey) {
        String suffix = getChildSuffix(groupKey, childKey).toLowerCase(Locale.US);
        return suffix.equals("enabled") || suffix.equals("enable") || suffix.equals("active");
    }

    private String getChildSuffix(String groupKey, String fullKey) {
        if (groupKey == null || fullKey == null) {
            return "";
        }
        String prefix = groupKey + "_";
        if (fullKey.startsWith(prefix)) {
            return fullKey.substring(prefix.length());
        }
        return fullKey;
    }

    private String formatChildLabel(String groupKey, String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return formatGroupTitle(groupKey);
        }
        String groupTitle = formatGroupTitle(groupKey);
        if (isToggleSuffix(suffix)) {
            return groupTitle.isEmpty() ? "Enable" : "Enable " + groupTitle;
        }
        String childTitle = formatSettingName(suffix);
        if (groupTitle.isEmpty()) {
            return childTitle;
        }
        return groupTitle + " " + childTitle;
    }

    private boolean isToggleSuffix(String suffix) {
        String normalized = suffix.toLowerCase(Locale.US);
        return normalized.equals("enabled") || normalized.equals("enable") || normalized.equals("active");
    }

    private Class<?> inferValueClass(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) {
            return String.class;
        }
        if (value instanceof Integer) {
            return Integer.class;
        }
        if (value instanceof Long) {
            return Long.class;
        }
        if (value instanceof Float) {
            return Float.class;
        }
        if (value instanceof Double) {
            return Double.class;
        }
        if (value instanceof Number) {
            return value.getClass();
        }
        if (value instanceof Boolean) {
            return Boolean.class;
        }
        return String.class;
    }

    private String formatSettingName(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        String[] parts = key.split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            String token = part.toLowerCase(Locale.getDefault());
            String replacement = SPECIAL_TOKENS.get(token);
            if (replacement != null) {
                builder.append(replacement);
            } else if (token.length() <= 2) {
                builder.append(token.toUpperCase(Locale.getDefault()));
            } else {
                builder.append(Character.toUpperCase(token.charAt(0))).append(token.substring(1));
            }
        }
        return builder.toString();
    }

    private void initViews() {
        selectApkBtn  = findViewById(R.id.selectApkButton);
        processApkBtn = findViewById(R.id.processApkButton);
        selectedTxt   = findViewById(R.id.selectedApkText);
        statusTxt     = findViewById(R.id.statusText);
        installedAppsList = findViewById(R.id.installedAppsList);
        clonedAppsList = findViewById(R.id.clonedAppsList);
        tabLayout = findViewById(R.id.tabLayout);
        settingsEditorContainer = findViewById(R.id.settingsEditorContainer);
        processApkBtn.setEnabled(false);
        processApkBtn.hide();
        selectApkBtn.show();
    }
    private void setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("All Apps"));
        tabLayout.addTab(tabLayout.newTab().setText("Cloned Apps"));
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                switch (tab.getPosition()) {
                    case 0:
                        installedAppsList.setVisibility(View.VISIBLE);
                        clonedAppsList.setVisibility(View.GONE);
                        break;
                    case 1:
                        installedAppsList.setVisibility(View.GONE);
                        clonedAppsList.setVisibility(View.VISIBLE);
                        break;
                }
                hideSettingsEditor();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }
    private void setupClickListeners() {
        selectApkBtn.setOnClickListener(v -> pickInput());
        processApkBtn.setOnClickListener(v -> handleProcessAction());
        installedAppsList.setOnItemClickListener((parent, view, position, id) -> {
            AppInfo app = allApps.get(position);
            hideSettingsEditor();
            showSettingsEditorForApp(app, false);
            try {
                ApplicationInfo appInfo = getPackageManager().getApplicationInfo(app.packageName, 0);
                String sourceApkPath = appInfo.sourceDir;
                if (sourceApkPath == null || sourceApkPath.isEmpty()) {
                    Log.e(TAG, "Could not get source APK path for: " + app.packageName);
                    Toast.makeText(MainActivity.this, "Error: Cannot access APK for " + app.appName, Toast.LENGTH_SHORT).show();
                    clearSelection();
                    return;
                }
                File sourceApkFile = new File(sourceApkPath);
                if (!sourceApkFile.exists()) {
                    Log.e(TAG, "Source APK file does not exist: " + sourceApkPath);
                    Toast.makeText(MainActivity.this, "Error: APK file not found on disk.", Toast.LENGTH_SHORT).show();
                    clearSelection();
                    return;
                }
                File cachedApkDir = new File(getCacheDir(), "apk_cache");
                cachedApkDir.mkdirs();
                File cachedApkFile = new File(cachedApkDir, app.packageName + "_source.apk");
                try (FileInputStream fis = new FileInputStream(sourceApkFile);
                     FileOutputStream fos = new FileOutputStream(cachedApkFile);
                     FileChannel inChannel = fis.getChannel();
                     FileChannel outChannel = fos.getChannel()) {
                    inChannel.transferTo(0, inChannel.size(), outChannel);
                    inputApkUri = Uri.fromFile(cachedApkFile);
                    selectedAppInfo = new SelectedAppInfo(app.packageName, app.appName, cachedApkFile);
                    selectedTxt.setText("Selected App: " + app.appName);
                    statusTxt.setText("App selected. Ready to process.");
                    Toast.makeText(MainActivity.this, "Selected: " + app.appName, Toast.LENGTH_SHORT).show();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to copy APK to cache", e);
                    Toast.makeText(MainActivity.this, "Error: Failed to access app APK.", Toast.LENGTH_SHORT).show();
                    clearSelection();
                    cachedApkFile.delete();
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "App not found: " + app.packageName, e);
                Toast.makeText(MainActivity.this, "Error: App not found", Toast.LENGTH_SHORT).show();
                clearSelection();
            }
        });
        clonedAppsList.setOnItemClickListener((parent, view, position, id) -> {
            AppInfo app = clonedApps.get(position);
            hideSettingsEditor();
            showSettingsEditorForApp(app, true);
        });
    }
    private void showSettingsEditorForApp(AppInfo app, boolean isClonedApp) {
        hideSettingsEditor();
        File clonerJsonToUse = null;
        if (isClonedApp) {
            try {
                ApplicationInfo appInfo = getPackageManager().getApplicationInfo(app.packageName, 0);
                String sourceApkPath = appInfo.sourceDir;
                if (sourceApkPath != null && !sourceApkPath.isEmpty()) {
                    File sourceApkFile = new File(sourceApkPath);
                    if (sourceApkFile.exists()) {
                        tempClonerJsonForEditor = new File(getCacheDir(), app.packageName + "_temp_cloner.json");
                        boolean jsonExtracted = false;
                        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(sourceApkFile))) {
                            ZipEntry entry;
                            while ((entry = zis.getNextEntry()) != null) {
                                if ("assets/cloner.json".equals(entry.getName())) {
                                    try (FileOutputStream fos = new FileOutputStream(tempClonerJsonForEditor)) {
                                        copyStream(zis, fos);
                                        jsonExtracted = true;
                                    }
                                    break;
                                }
                            }
                        } catch (IOException e) {
                            Log.e(TAG, "Error extracting cloner.json from cloned APK: " + app.packageName, e);
                        }
                        if (jsonExtracted) {
                            clonerJsonToUse = tempClonerJsonForEditor;
                            sourceApkFileForEditor = sourceApkFile;
                        } else {
                            try {
                                copyAsset("cloner.json", tempClonerJsonForEditor);
                                clonerJsonToUse = tempClonerJsonForEditor;
                                sourceApkFileForEditor = sourceApkFile;
                            } catch (IOException e) {
                                Log.e(TAG, "Failed to copy default cloner.json for cloned app: " + app.packageName, e);
                                Toast.makeText(this, "Error: Could not prepare settings file for " + app.appName, Toast.LENGTH_LONG).show();
                                tempClonerJsonForEditor.delete();
                                tempClonerJsonForEditor = null;
                                return;
                            }
                        }
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Cloned app not found: " + app.packageName, e);
                Toast.makeText(this, "Error: Cloned app not found: " + app.appName, Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            clonerJsonToUse = clonerJsonFile;
            if (!clonerJsonToUse.exists()) {
                try {
                    copyAsset("cloner.json", clonerJsonToUse);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to copy default cloner.json", e);
                    Toast.makeText(this, "Error: Could not prepare settings file.", Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }
        if (clonerJsonToUse == null || !clonerJsonToUse.exists()) {
            Toast.makeText(this, "Settings file not found.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            final JSONObject json = new JSONObject(readString(clonerJsonToUse));
            if (!json.has(CLONING_MODE_KEY)) {
                json.put(CLONING_MODE_KEY, CLONING_MODE_REPLACE);
            }

            ListView settingsListView = new ListView(this);
            final List<Setting> settingsList = buildSettingsList(json);
            if (!isClonedApp) {
                bundleDataSetting = new Setting(BUNDLE_DATA_SETTING_KEY, bundledDataDisplayName, String.class);
                settingsList.add(bundleDataSetting);
            } else {
                bundleDataSetting = null;
            }
            final SettingsAdapter adapter = new SettingsAdapter(settingsList, json);
            settingsListView.setAdapter(adapter);
            currentSettingsAdapter = isClonedApp ? null : adapter;
            if (!isClonedApp) {
                refreshBundleDataUi();
            }

            settingsListView.setOnItemClickListener((parent, view, position, id) -> {
                Setting setting = settingsList.get(position);
                showEditorDialogForSetting(setting, json, adapter);
            });

            LinearLayout container = new LinearLayout(this);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

            LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
            settingsListView.setLayoutParams(listParams);
            container.addView(settingsListView);

            currentSettingsJson = json;
            currentSettingsFile = clonerJsonToUse;
            currentSettingsIsClonedApp = isClonedApp;
            currentSettingsApp = app;

            currentSettingsEditorView = container;
            settingsEditorContainer.removeAllViews();
            settingsEditorContainer.addView(currentSettingsEditorView);
            settingsEditorContainer.setVisibility(View.VISIBLE);
            processApkBtn.show();
            processApkBtn.setEnabled(true);
            processApkBtn.setContentDescription(isClonedApp ? "Save and install updated clone" : "Process and patch APK");
            selectApkBtn.hide();
        } catch (JSONException | IOException e) {
            Log.e(TAG, "Error reading or parsing cloner.json for app: " + app.packageName, e);
            Toast.makeText(this, "Error opening settings editor: " + e.getMessage(), Toast.LENGTH_LONG).show();
            if (tempClonerJsonForEditor != null) {
                tempClonerJsonForEditor.delete();
                tempClonerJsonForEditor = null;
            }
            sourceApkFileForEditor = null;
        }
    }
    private void hideSettingsEditor() {
        if (currentSettingsEditorView != null) {
            settingsEditorContainer.removeView(currentSettingsEditorView);
            currentSettingsEditorView = null;
        }
        settingsEditorContainer.setVisibility(View.GONE);
        processApkBtn.hide();
        processApkBtn.setEnabled(false);
        selectApkBtn.show();
        if (tempClonerJsonForEditor != null && tempClonerJsonForEditor.exists()) {
            tempClonerJsonForEditor.delete();
        }
        tempClonerJsonForEditor = null;
        sourceApkFileForEditor = null;
        if (bundledDataFileForCloning != null && bundledDataFileForCloning.exists()) {
            bundledDataFileForCloning.delete();
        }
        bundledDataFileForCloning = null;
        bundledDataDisplayName = null;
        bundleDataSetting = null;
        currentSettingsAdapter = null;
        bundleDataDialogSummary = null;
        bundleDataDialogClearButton = null;
        currentSettingsJson = null;
        currentSettingsFile = null;
        currentSettingsApp = null;
        currentSettingsIsClonedApp = false;
    }
    private void loadInstalledApplications() {
        statusTxt.setText("Loading applications...");
        new Thread(() -> {
            try {
                allApps.clear();
                clonedApps.clear();
                PackageManager pm = getPackageManager();
                List<PackageInfo> packages = pm.getInstalledPackages(
                    PackageManager.GET_PROVIDERS | PackageManager.GET_META_DATA);
                int clonedCount = 0;
                for (PackageInfo packageInfo : packages) {
                if ((packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    continue;
                }
                boolean isCloned = hasInjectedProvider(packageInfo);
                AppInfo appInfo = new AppInfo();
                appInfo.packageName = packageInfo.packageName;
                appInfo.appName = packageInfo.applicationInfo.loadLabel(pm).toString();
                appInfo.icon = packageInfo.applicationInfo.loadIcon(pm);
                if (isCloned) {
                    clonedApps.add(appInfo);
                    clonedCount++;
                    continue;
                }
                allApps.add(appInfo);
            }
                final int finalClonedCount = clonedCount;
                runOnUiThread(() -> {
                    updateAppLists();
                    statusTxt.setText("Loaded " + allApps.size() + " apps (" + finalClonedCount + " cloned)");
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading applications", e);
                runOnUiThread(() -> {
                    statusTxt.setText("Error loading applications: " + e.getMessage());
                    Toast.makeText(MainActivity.this, "Failed to load apps", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
    private boolean hasInjectedProvider(PackageInfo packageInfo) {
        try {
            if (packageInfo.providers != null) {
                for (android.content.pm.ProviderInfo provider : packageInfo.providers) {
                    if (provider != null && provider.authority != null) {
                        if (provider.authority.endsWith(INJECTED_PROVIDER_SUFFIX)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking providers for " + packageInfo.packageName, e);
            return false;
        }
    }
    private void updateAppLists() {
        allAppsAdapter = new AppListAdapter(allApps);
        clonedAppsAdapter = new AppListAdapter(clonedApps);
        installedAppsList.setAdapter(allAppsAdapter);
        clonedAppsList.setAdapter(clonedAppsAdapter);
        installedAppsList.setVisibility(View.VISIBLE);
        clonedAppsList.setVisibility(View.GONE);
        if (allAppsAdapter != null) allAppsAdapter.notifyDataSetChanged();
        if (clonedAppsAdapter != null) clonedAppsAdapter.notifyDataSetChanged();
    }
    private void pickInput() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/vnd.android.package-archive");
            pickApk.launch(i);
        } catch (Exception e) {
            Log.e(TAG, "Error launching APK picker", e);
            Toast.makeText(this, "Error opening file picker: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    private void pickOutput() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/vnd.android.package-archive")
                    .putExtra(Intent.EXTRA_TITLE, "patched.apk");
            createApk.launch(i);
        } catch (Exception e) {
            Log.e(TAG, "Error launching output file picker", e);
            Toast.makeText(this, "Error opening file picker: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    private void handleProcessAction() {
        if (currentSettingsIsClonedApp) {
            if (!persistCurrentSettings()) {
                return;
            }
            if (currentSettingsApp == null || tempClonerJsonForEditor == null || sourceApkFileForEditor == null) {
                Toast.makeText(MainActivity.this, "Unable to update clone. Please reopen the editor and try again.", Toast.LENGTH_LONG).show();
                return;
            }
            injectUpdatedJsonAndInstall(tempClonerJsonForEditor, sourceApkFileForEditor, currentSettingsApp);
            hideSettingsEditor();
        } else {
            if (!persistCurrentSettings()) {
                return;
            }
            pickOutput();
        }
    }
    private boolean persistCurrentSettings() {
        if (currentSettingsJson == null || currentSettingsFile == null) {
            return true;
        }
        try (FileWriter fw = new FileWriter(currentSettingsFile)) {
            fw.write(currentSettingsJson.toString(2));
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error writing settings file", e);
            Toast.makeText(MainActivity.this, "Failed to save settings: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } catch (JSONException e) {
            Log.e(TAG, "Error serializing settings JSON", e);
            Toast.makeText(MainActivity.this, "Failed to prepare settings: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        return false;
    }
    private void pickDataFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE, "Select Data ZIP to Bundle");
            pickDataFile.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error launching file picker", e);
            Toast.makeText(this, "Error opening file picker: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    private void clearSelection() {
        if (selectedAppInfo != null && selectedAppInfo.cachedApkFile != null && selectedAppInfo.cachedApkFile.exists()) {
            selectedAppInfo.cachedApkFile.delete();
        }
        inputApkUri = null;
        selectedAppInfo = null;
        selectedTxt.setText("Select an APK");
        processApkBtn.setEnabled(false);
        hideSettingsEditor();
    }
    private void startProcessing() {
        if (inputApkUri == null || outputApkUri == null) {
            Toast.makeText(this, "Pick input & output first", Toast.LENGTH_LONG).show();
            return;
        }
        if (!isValidApk(inputApkUri)) {
            statusTxt.setText("Error: Invalid APK file");
            return;
        }
        try {
            File hookDex    = new File(getCacheDir(), "hook.dex");
            File libWorkDir = new File(getCacheDir(), "hook_libs");
            libWorkDir.mkdirs();
            copyAsset("hook.dex", hookDex);
            if (!clonerJsonFile.exists()) {
                copyAsset("cloner.json", clonerJsonFile);
            }
            copyAsset("lib/arm64-v8a/libpine.so",     new File(libWorkDir, "libpine.so"));
            copyAsset("lib/arm64-v8a/libsandhook.so", new File(libWorkDir, "libsandhook.so"));
            new Thread(() -> {
                try {
                    // Ensure ApkProcessor.java is present in the correct package location
                    new ApkProcessor(MainActivity.this).injectHook(
                            inputApkUri, outputApkUri, hookDex, clonerJsonFile, libWorkDir, bundledDataFileForCloning);
                    runOnUiThread(() -> {
                        statusTxt.setText("Done");
                        Toast.makeText(this, "APK patched successfully!", Toast.LENGTH_LONG).show();
                        boolean hadBundledData = bundledDataFileForCloning != null || bundledDataDisplayName != null;
                        if (bundledDataFileForCloning != null && bundledDataFileForCloning.exists()) {
                            bundledDataFileForCloning.delete();
                        }
                        if (hadBundledData) {
                            bundledDataFileForCloning = null;
                            bundledDataDisplayName = null;
                            refreshBundleDataUi();
                        }
                        boolean installerLaunched = launchInstallerForUri(
                                outputApkUri,
                                "Launching installer...",
                                "Installer launched. Confirm installation to finish."
                        );
                        if (installerLaunched) {
                            loadInstalledApplications();
                            clearSelection();
                            outputApkUri = null;
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Patch error", e);
                    runOnUiThread(() -> {
                        statusTxt.setText("Error: " + e.getMessage());
                        Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        boolean hadBundledData = bundledDataFileForCloning != null || bundledDataDisplayName != null;
                        if (bundledDataFileForCloning != null && bundledDataFileForCloning.exists()) {
                            bundledDataFileForCloning.delete();
                        }
                        if (hadBundledData) {
                            bundledDataFileForCloning = null;
                            bundledDataDisplayName = null;
                            refreshBundleDataUi();
                        }
                        clearSelection();
                        outputApkUri = null;
                    });
                }
            }).start();
        } catch (IOException e) {
            Log.e(TAG, "Asset copy error", e);
            statusTxt.setText("Asset error: " + e.getMessage());
            boolean hadBundledData = bundledDataFileForCloning != null || bundledDataDisplayName != null;
            if (bundledDataFileForCloning != null && bundledDataFileForCloning.exists()) {
                bundledDataFileForCloning.delete();
            }
            if (hadBundledData) {
                bundledDataFileForCloning = null;
                bundledDataDisplayName = null;
                refreshBundleDataUi();
            }
            clearSelection();
            outputApkUri = null;
        }
    }
    private boolean launchInstallerForUri(Uri apkUri, String successToast, String successStatus) {
        if (apkUri == null) {
            Log.e(TAG, "launchInstallerForUri: APK URI is null");
            statusTxt.setText("Installer Error: Missing APK");
            Toast.makeText(this, "Installer error: missing APK output.", Toast.LENGTH_LONG).show();
            return false;
        }
        Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        installIntent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        installIntent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            installIntent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, getPackageName());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            installIntent.setClipData(ClipData.newRawUri("processed_apk", apkUri));
        }
        if (getPackageManager().queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) {
            Log.w(TAG, "No handler for ACTION_INSTALL_PACKAGE, falling back to ACTION_VIEW");
            Intent fallback = new Intent(Intent.ACTION_VIEW);
            fallback.setDataAndType(apkUri, "application/vnd.android.package-archive");
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                fallback.setClipData(ClipData.newRawUri("processed_apk", apkUri));
            }
            if (getPackageManager().queryIntentActivities(fallback, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) {
                Log.e(TAG, "No application available to handle APK install for URI: " + apkUri);
                statusTxt.setText("Installer Error: App not found");
                Toast.makeText(this, "Error: Cannot find app to install APK. Check file manager.", Toast.LENGTH_LONG).show();
                return false;
            }
            installIntent = fallback;
        }
        try {
            startActivity(installIntent);
            if (successToast != null && !successToast.isEmpty()) {
                Toast.makeText(this, successToast, Toast.LENGTH_SHORT).show();
            }
            if (successStatus != null && !successStatus.isEmpty()) {
                statusTxt.setText(successStatus);
            }
            return true;
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Installer activity not found for URI: " + apkUri, e);
            statusTxt.setText("Installer Error: App not found");
            Toast.makeText(this, "Error: Cannot find app to install APK. Check file manager.", Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException while launching installer for URI: " + apkUri, e);
            statusTxt.setText("Installer Error: Permission denied");
            Toast.makeText(this, "Error launching installer: permission denied.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error launching installer for URI: " + apkUri, e);
            statusTxt.setText("Installer Error: " + e.getMessage());
            Toast.makeText(this, "Error launching installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        return false;
    }
    private boolean isValidApk(Uri apkUri) {
        try {
            String mimeType = getContentResolver().getType(apkUri);
            return "application/vnd.android.package-archive".equals(mimeType) ||
                   (apkUri.getLastPathSegment() != null && apkUri.getLastPathSegment().endsWith(".apk"));
        } catch (Exception e) {
            Log.w(TAG, "Error validating APK URI", e);
            return false;
        }
    }
    private void copyAsset(String assetPath, File dst) throws IOException {
        if (assetPath == null || assetPath.isEmpty()) {
            throw new IllegalArgumentException("Asset path cannot be null or empty");
        }
        if (dst == null) {
            throw new IllegalArgumentException("Destination file cannot be null");
        }
        try (InputStream in = getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8_192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
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

    private static byte[] readAllBytes(File file) throws IOException {
        if (file == null || !file.exists() || !file.isFile()) {
            throw new IOException("File does not exist: " + file);
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             InputStream in = new FileInputStream(file)) {
            copyStream(in, baos);
            return baos.toByteArray();
        }
    }
    private String readString(File file) throws IOException {
        if (file == null || !file.exists()) {
            throw new IOException("File does not exist: " + file);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }
    private String randomAndroidId() {
        return String.format(Locale.US, "%016X", random.nextLong());
    }

    // FIXED: Correctly initialize the byte array with size 6
    private String randomMac() {
        byte[] macAddress = new byte[6]; // MAC addresses are 6 bytes
        random.nextBytes(macAddress);
        // Set the locally administered bit and unicast bit
        macAddress[0] = (byte) ((macAddress[0] & (byte) 252) | (byte) 2);
        StringBuilder sb = new StringBuilder(18);
        for (int i = 0; i < macAddress.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02x", macAddress[i] & 0xFF));
        }
        return sb.toString();
    }

    private String randomLatitude() {
        double lat = (random.nextDouble() * 180.0) - 90.0;
        return String.format("%.6f", lat);
    }
    private String randomLongitude() {
        double lon = (random.nextDouble() * 360.0) - 180.0;
        return String.format("%.6f", lon);
    }
    private void injectUpdatedJsonAndInstall(File updatedClonerJson, File sourceApkFile, AppInfo clonedApp) {
        statusTxt.setText("Updating & Installing...");
        new Thread(() -> {
            File tempRoot = new File(getCacheDir(), "update_" + System.currentTimeMillis());
            try {
                if (!tempRoot.mkdirs()) throw new IOException("Failed to create temp directory: " + tempRoot);
                String basePath = tempRoot.getCanonicalPath() + File.separator;
                boolean manifestFound = false;
                try (ZipInputStream zis = new ZipInputStream(new FileInputStream(sourceApkFile))) {
                    ZipEntry ze;
                    while ((ze = zis.getNextEntry()) != null) {
                        String name = ze.getName();
                        if (name == null || name.isEmpty()) continue;
                        if (SIG_PATH.matcher(name).matches()) continue;
                        if ("assets/cloner.json".equals(name)) continue;
                        if (ANDROID_MANIFEST.equals(name)) {
                            manifestFound = true;
                        }
                        File outFile = safeResolve(tempRoot, basePath, name);
                        if (ze.isDirectory()) {
                            if (!outFile.exists() && !outFile.mkdirs()) {
                                throw new IOException("Failed to mkdirs: " + outFile);
                            }
                            continue;
                        }
                        File parent = outFile.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            throw new IOException("Failed to create parent: " + parent);
                        }
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            copyStream(zis, fos);
                        }
                    }
                }
                if (!manifestFound) {
                    throw new IOException("AndroidManifest.xml not found in source APK");
                }
                File assetsDir = new File(tempRoot, "assets");
                if (!assetsDir.exists() && !assetsDir.mkdirs()) {
                    throw new IOException("Failed to create assets dir");
                }
                copyFile(updatedClonerJson, new File(assetsDir, "cloner.json"));
                File unsignedApk = new File(getCacheDir(), "unsigned_updated_" + System.nanoTime() + ".apk");
                File manifestFile = new File(tempRoot, ANDROID_MANIFEST);
                byte[] manifestBytes = readAllBytes(manifestFile);
                zipDir(tempRoot, unsignedApk, manifestBytes);
                File signedApk = new File(getCacheDir(), "signed_updated_" + System.nanoTime() + ".apk");
                try {
                    signUpdatedApk(unsignedApk, signedApk);
                } finally {
                    unsignedApk.delete();
                    deleteRec(tempRoot);
                }
                runOnUiThread(() -> installApk(signedApk, clonedApp));
            } catch (Exception e) {
                Log.e(TAG, "Error updating/cloning app: " + clonedApp.packageName, e);
                runOnUiThread(() -> {
                    statusTxt.setText("Update Error: " + e.getMessage());
                    Toast.makeText(MainActivity.this, "Failed to update " + clonedApp.appName + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
                    if (updatedClonerJson != null && updatedClonerJson.exists()) {
                        updatedClonerJson.delete();
                    }
                    hideSettingsEditor();
                });
                deleteRec(tempRoot);
            }
        }).start();
    }
    private void signUpdatedApk(File unsignedApk, File signedApk) throws Exception {
        ApkSigner.SignerConfig signer = loadSignerConfigForUpdate();
        ApkSigner apkSigner = new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(unsignedApk)
                .setOutputApk(signedApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)  // Disabled for better compatibility
                .build();
        apkSigner.sign();
        try {
            Class<?> builderClass = Class.forName("com.android.apksig.ApkVerifier$Builder");
            Object builderInstance = builderClass.getConstructor(File.class).newInstance(signedApk);
            Object apkVerifierInstance = builderClass.getMethod("build").invoke(builderInstance);
            Object vRes = apkVerifierInstance.getClass().getMethod("verify").invoke(apkVerifierInstance);
            Boolean isVerified = (Boolean) vRes.getClass().getMethod("isVerified").invoke(vRes);
            if (isVerified != null && !isVerified) {
                Log.w(TAG, "Updated APK verification result: NOT VERIFIED (continuing)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Verification skipped due to error", t);
        }
    }
    private void installApk(File apkToInstall, AppInfo clonedApp) {
        Log.d(TAG, "Preparing to install updated APK: " + apkToInstall.getName());
        try {
            String authority = getPackageName() + ".fileprovider";
            Uri apkUri = FileProvider.getUriForFile(this, authority, apkToInstall);
            boolean launched = launchInstallerForUri(
                    apkUri,
                    "Launching installer for updated " + clonedApp.appName,
                    "Installer launched for " + clonedApp.appName
            );
            if (!launched) {
                apkToInstall.delete();
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid FileProvider configuration for APK installation", e);
            Toast.makeText(this, "Installer error: invalid output location.", Toast.LENGTH_LONG).show();
            statusTxt.setText("Installer Error: Invalid output");
            apkToInstall.delete();
        }
    }
    private ApkSigner.SignerConfig loadSignerConfigForUpdate() throws Exception {
        try (InputStream ksStream = getAssets().open(KEYSTORE_ASSET)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(ksStream, STORE_PWD.toCharArray());
            PrivateKey key = (PrivateKey) ks.getKey(ALIAS, KEY_PWD.toCharArray());
            if (key == null) throw new IllegalStateException("Private key is null for alias: " + ALIAS);
            X509Certificate cert = (X509Certificate) ks.getCertificate(ALIAS);
            if (cert == null) throw new IllegalStateException("Certificate is null for alias: " + ALIAS);
            return new ApkSigner.SignerConfig.Builder(ALIAS, key, Collections.singletonList(cert)).build();
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
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        e.setCrc(crc.getValue());
        return e;
    }
    private ZipEntry createZipEntry(String name, File file) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(0L);
        String lower = name.toLowerCase(Locale.US);
        boolean store = lower.endsWith(".so") || lower.endsWith(".arsc") || lower.endsWith(".dex");
        if (store) {
            e.setMethod(ZipEntry.STORED);
            e.setSize(file.length());
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
    private static File safeResolve(File root, String basePath, String entryName) throws IOException {
        File out = new File(root, entryName);
        String outPath = out.getCanonicalPath();
        if (!outPath.startsWith(basePath)) {
            throw new IOException("Blocked zip path traversal: " + entryName);
        }
        return out;
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == WRITE_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission was granted, try the export again
                if (currentSettingsApp != null) {
                    triggerExportData(currentSettingsApp.packageName);
                }
            } else {
                Toast.makeText(this, "Write permission is required to export data.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void triggerExportData(String targetPackageName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
                return;
            }
        }

        Log.d(TAG, "Triggering data export for: " + targetPackageName);
        statusTxt.setText("Requesting data export for " + targetPackageName + "...");
        Intent exportIntent = new Intent("com.applisto.appcloner.ACTION_EXPORT_DATA");
        exportIntent.setPackage(targetPackageName);
        try {
            sendBroadcast(exportIntent, IPC_PERMISSION);
            Toast.makeText(this, "Export request sent to " + targetPackageName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error sending export broadcast to " + targetPackageName, e);
            statusTxt.setText("Export Error: Failed to contact app " + targetPackageName);
            Toast.makeText(this, "Failed to request export from " + targetPackageName + ". Is the app correctly cloned?", Toast.LENGTH_LONG).show();
        }
    }
    private void confirmAndUninstallApp(AppInfo appInfo) {
        if (appInfo == null || appInfo.packageName == null || appInfo.packageName.trim().isEmpty()) {
            Toast.makeText(this, "Unable to determine which package to uninstall.", Toast.LENGTH_LONG).show();
            return;
        }
        final String appDisplayName = appInfo.appName != null && !appInfo.appName.trim().isEmpty()
                ? appInfo.appName
                : appInfo.packageName;

        new AlertDialog.Builder(this)
                .setTitle("Uninstall App")
                .setMessage("Are you sure you want to uninstall '" + appDisplayName + "'?")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("Uninstall", (dialog, which) -> {
                    Uri packageUri = Uri.parse("package:" + appInfo.packageName);
                    Intent uninstallIntent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, packageUri);
                    uninstallIntent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                    if (uninstallIntent.resolveActivity(getPackageManager()) != null) {
                        pendingUninstallPackageName = appInfo.packageName;
                        pendingUninstallAppName = appInfo.appName;
                        try {
                            uninstallLauncher.launch(uninstallIntent);
                            if (statusTxt != null) {
                                statusTxt.setText("Opening uninstall dialog for " + appDisplayName + "...");
                            }
                            Toast.makeText(MainActivity.this,
                                    "Opening uninstall dialog for " + appDisplayName,
                                    Toast.LENGTH_SHORT).show();
                        } catch (ActivityNotFoundException | SecurityException e) {
                            pendingUninstallPackageName = null;
                            pendingUninstallAppName = null;
                            Log.e(TAG, "Unable to launch uninstall intent for " + appInfo.packageName, e);
                            Toast.makeText(MainActivity.this,
                                    "Cannot uninstall " + appDisplayName + ": " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    } else {
                        Toast.makeText(MainActivity.this,
                                "Cannot uninstall " + appDisplayName + ". No system handler found.",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
    private static class SelectedAppInfo {
        String packageName;
        String appName;
        File cachedApkFile;
        SelectedAppInfo(String packageName, String appName, File cachedApkFile) {
            this.packageName = packageName;
            this.appName = appName;
            this.cachedApkFile = cachedApkFile;
        }
    }
    private static class AppInfo {
        String appName;
        String packageName;
        Drawable icon;
    }

    private class MenuAdapter extends ArrayAdapter<MenuItem> {
        public MenuAdapter(Context context, List<MenuItem> items) {
            super(context, 0, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_menu_item, parent, false);
            }

            MenuItem menuItem = getItem(position);

            ImageView iconView = convertView.findViewById(R.id.menu_item_icon);
            TextView titleView = convertView.findViewById(R.id.menu_item_title);

            iconView.setImageDrawable(menuItem.getIcon());
            titleView.setText(menuItem.getTitle());

            return convertView;
        }
    }
    private class AppListAdapter extends BaseAdapter {
        private final List<AppInfo> apps;
        private final boolean isClonedAppsAdapter;
        public AppListAdapter(List<AppInfo> apps) {
            this.apps = apps;
            this.isClonedAppsAdapter = (apps == clonedApps);
        }
        @Override
        public int getCount() {
            return apps != null ? apps.size() : 0;
        }
        @Override
        public Object getItem(int position) {
            return apps != null && position < apps.size() ? apps.get(position) : null;
        }
        @Override
        public long getItemId(int position) {
            return position;
        }
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.app_list_item, parent, false);
            }
            if (apps != null && position >= 0 && position < apps.size()) {
                AppInfo app = apps.get(position);
                TextView appNameText = convertView.findViewById(R.id.appName);
                TextView packageNameText = convertView.findViewById(R.id.packageName);
                ImageView appIcon = convertView.findViewById(R.id.appIcon);
                ImageButton menuButton = convertView.findViewById(R.id.menuButton);
                appNameText.setText(app.appName != null ? app.appName : "Unknown");
                packageNameText.setText(app.packageName != null ? app.packageName : "Unknown");
                appIcon.setImageDrawable(app.icon);
                if (isClonedAppsAdapter) {
                    menuButton.setVisibility(View.VISIBLE);
                    menuButton.setTag(app);
                    menuButton.setOnClickListener(v -> {
                        AppInfo clickedApp = (AppInfo) v.getTag();
                        if (clickedApp != null) {
                            PopupMenu tempPopup = new PopupMenu(MainActivity.this, v);
                            Menu menu = tempPopup.getMenu();
                            getMenuInflater().inflate(R.menu.cloned_app_menu, menu);

                            List<MenuItem> menuItems = new ArrayList<>();
                            for (int i = 0; i < menu.size(); i++) {
                                menuItems.add(menu.getItem(i));
                            }

                            MenuAdapter adapter = new MenuAdapter(MainActivity.this, menuItems);

                            new com.google.android.material.dialog.MaterialAlertDialogBuilder(MainActivity.this)
                                    .setTitle(clickedApp.appName)
                                    .setAdapter(adapter, (dialog, which) -> {
                                        MenuItem selectedItem = menuItems.get(which);
                                        int itemId = selectedItem.getItemId();
                                        if (itemId == R.id.action_edit_prefs) {
                                            Intent i = new Intent(MainActivity.this, PrefsEditorActivity.class);
                                            i.putExtra("pkg", clickedApp.packageName);
                                            i.putExtra("appName", clickedApp.appName);
                                            startActivity(i);
                                        } else if (itemId == R.id.action_export_data) {
                                            triggerExportData(clickedApp.packageName);
                                        } else if (itemId == R.id.action_uninstall) {
                                            confirmAndUninstallApp(clickedApp);
                                        }
                                    })
                                    .show();
                        }
                    });
                } else {
                    menuButton.setVisibility(View.GONE);
                    menuButton.setOnClickListener(null);
                    menuButton.setTag(null);
                }
            }
            return convertView;
        }
    }

    private static class JsonObjectField {
        final String key;
        final View inputView;
        final Class<?> valueClass;

        JsonObjectField(String key, View inputView, Class<?> valueClass) {
            this.key = key;
            this.inputView = inputView;
            this.valueClass = valueClass;
        }
    }

    private static class Setting {
        final String key;
        Object value;
        Class<?> valueClass;
        Setting parent;
        List<Setting> children;

        Setting(String key, Object value, Class<?> valueClass) {
            this.key = key;
            this.value = value;
            this.valueClass = valueClass;
        }

        boolean isGroup() {
            return children != null && !children.isEmpty();
        }
    }

    private class SettingsAdapter extends BaseAdapter {
        private final List<Setting> settings;
        private final JSONObject json;

        public SettingsAdapter(List<Setting> settings, JSONObject json) {
            this.settings = settings;
            this.json = json;
        }

        @Override
        public int getCount() {
            return settings.size();
        }

        @Override
        public Object getItem(int position) {
            return settings.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.setting_list_item, parent, false);
            }

            final Setting setting = settings.get(position);

            TextView nameTextView = convertView.findViewById(R.id.setting_name);
            TextView valueTextView = convertView.findViewById(R.id.setting_value);
            Button generateButton = convertView.findViewById(R.id.generate_button);

            String displayName = getDisplayName(setting);
            nameTextView.setText(displayName);

            valueTextView.setVisibility(View.VISIBLE);
            if (setting.isGroup()) {
                valueTextView.setText(getGroupSummary(setting));
                generateButton.setVisibility(View.GONE);
                generateButton.setOnClickListener(null);
                return convertView;
            }

            if (BUNDLE_DATA_SETTING_KEY.equals(setting.key)) {
                valueTextView.setText(getBundleDataSummaryText());
                generateButton.setVisibility(View.GONE);
                generateButton.setOnClickListener(null);
                return convertView;
            }

            Object value = setting.value;
            if (value instanceof JSONObject) {
                valueTextView.setText("Tap to edit values");
            } else if (value instanceof Boolean) {
                valueTextView.setText((Boolean) value ? "Enabled" : "Disabled");
            } else if (CLONING_MODE_KEY.equals(setting.key)) {
                valueTextView.setText(getCloningModeSummary(value));
            } else if (value == null || JSONObject.NULL.equals(value) || value.toString().trim().isEmpty()) {
                valueTextView.setText("Not set");
            } else {
                valueTextView.setText(value.toString());
            }

            if ("android_id".equals(setting.key) || "wifi_mac".equals(setting.key)) {
                generateButton.setVisibility(View.GONE);
                generateButton.setOnClickListener(null);
            } else if ("latitude".equals(setting.key) || "longitude".equals(setting.key)) {
                generateButton.setVisibility(View.VISIBLE);
                generateButton.setOnClickListener(v -> {
                    String newValue = "";
                    if ("latitude".equals(setting.key)) {
                        newValue = randomLatitude();
                    } else if ("longitude".equals(setting.key)) {
                        newValue = randomLongitude();
                    }
                    setting.value = newValue;
                    setting.valueClass = String.class;
                    try {
                        json.put(setting.key, newValue);
                    } catch (JSONException e) {
                        Log.e(TAG, "Error saving generated value", e);
                    }
                    notifyDataSetChanged();
                });
            } else {
                generateButton.setVisibility(View.GONE);
                generateButton.setOnClickListener(null);
            }

            return convertView;
        }
    }

    private String getCloningModeSummary(Object value) {
        String mode = value instanceof String ? (String) value : CLONING_MODE_REPLACE;
        if (CLONING_MODE_GENERATE.equals(mode)) {
            return "Generate new package variant";
        }
        return "Replace original (keep package)";
    }
}
