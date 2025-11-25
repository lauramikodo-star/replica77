package com.applisto.appcloner;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public abstract class AbstractContentProvider extends ContentProvider {

    private static final String TAG = "AbstractContentProvider";

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Log.i(TAG, "call, method: " + method + ", arg: " + arg + ", extras: " + extras);
        if (extras == null) extras = new Bundle();
        try {
            switch (method) {
                case "list_prefs":
                    return listPrefs();
                case "get_prefs":
                    return getPrefs(arg);
                case "put_pref": {
                    String file = extras.getString("file");
                    String key = extras.getString("key");
                    return putPref(file, key, extras);
                }
                case "remove_pref": {
                    String file = extras.getString("file");
                    String key = extras.getString("key");
                    return removePref(file, key);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, e);
            Bundle b = new Bundle();
            b.putBoolean("ok", false);
            b.putString("error", e.toString());
            return b;
        }
        return null;
    }

    private Bundle listPrefs() {
        File prefsDir = new File(getContext().getApplicationInfo().dataDir, "shared_prefs");
        ArrayList<String> files = new ArrayList<>();
        if (prefsDir.exists() && prefsDir.isDirectory()) {
            for (File f : prefsDir.listFiles()) {
                if (f.getName().endsWith(".xml")) {
                    files.add(f.getName().substring(0, f.getName().length() - 4));
                }
            }
        }
        Bundle b = new Bundle();
        b.putStringArrayList("files", files);
        return b;
    }

    private Bundle getPrefs(String file) {
        SharedPreferences sp = getContext().getSharedPreferences(file, Context.MODE_PRIVATE);
        Bundle b = new Bundle();
        for (Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
            Object val = entry.getValue();
            if (val instanceof String) b.putString(entry.getKey(), (String) val);
            else if (val instanceof Integer) b.putInt(entry.getKey(), (Integer) val);
            else if (val instanceof Long) b.putLong(entry.getKey(), (Long) val);
            else if (val instanceof Boolean) b.putBoolean(entry.getKey(), (Boolean) val);
            else if (val instanceof Float) b.putFloat(entry.getKey(), (Float) val);
            else if (val instanceof Set) {
                // Bundle doesn't have putStringSet, so we convert to ArrayList<String>
                b.putStringArrayList(entry.getKey(), new ArrayList<>((Set<String>) val));
            }
        }
        return b;
    }

    private Bundle putPref(String file, String key, Bundle extras) {
        if (file == null || key == null) {
            Bundle b = new Bundle();
            b.putBoolean("ok", false);
            b.putString("error", "File or key is null");
            return b;
        }
        SharedPreferences sp = getContext().getSharedPreferences(file, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        String type = extras.getString("type");
        if (type == null) {
            Bundle b = new Bundle();
            b.putBoolean("ok", false);
            b.putString("error", "Type is null");
            return b;
        }
        switch (type) {
            case "String":
                ed.putString(key, extras.getString("value"));
                break;
            case "Integer":
                ed.putInt(key, extras.getInt("value"));
                break;
            case "Long":
                ed.putLong(key, extras.getLong("value"));
                break;
            case "Boolean":
                ed.putBoolean(key, extras.getBoolean("value"));
                break;
            case "Float":
                ed.putFloat(key, extras.getFloat("value"));
                break;
            case "StringSet":
                ArrayList<String> list = extras.getStringArrayList("value");
                if (list != null) {
                    ed.putStringSet(key, new HashSet<>(list));
                }
                break;
            default:
                Bundle b = new Bundle();
                b.putBoolean("ok", false);
                b.putString("error", "Unsupported type: " + type);
                return b;
        }
        ed.apply(); // Use apply() instead of commit()
        Bundle b = new Bundle();
        b.putBoolean("ok", true);
        return b;
    }

    private Bundle removePref(String file, String key) {
        if (file == null || key == null) {
            Bundle b = new Bundle();
            b.putBoolean("ok", false);
            b.putString("error", "File or key is null");
            return b;
        }
        SharedPreferences sp = getContext().getSharedPreferences(file, Context.MODE_PRIVATE);
        sp.edit().remove(key).apply(); // Use apply() instead of commit()
        Bundle b = new Bundle();
        b.putBoolean("ok", true);
        return b;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
