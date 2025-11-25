package com.applisto.appcloner;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;

public final class ClonerSettings {
    private static final String FILE = "cloner.json";
    private static ClonerSettings INSTANCE;
    private final JSONObject cfg;

    private ClonerSettings(Context c) {
        try (InputStream in = c.getAssets().open(FILE)) {
            byte[] buf = new byte[in.available()];
            in.read(buf);
            cfg = new JSONObject(new String(buf));
        } catch (Exception e) {
            throw new RuntimeException("Cannot load " + FILE, e);
        }
    }

    public static synchronized ClonerSettings get(Context c) {
        if (INSTANCE == null) INSTANCE = new ClonerSettings(c);
        return INSTANCE;
    }

    /* helpers for single-value getters */
    public String androidId() { return cfg.optString("android_id"); }
    public String wifiMac()   { return cfg.optString("wifi_mac");   }

    /* give BuildPropsHook raw access to the JSON object */
    public JSONObject raw()   { return cfg; }
}