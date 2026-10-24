package com.golda.patchertiktok;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.Map;

/**
 * Exposes the module's SharedPreferences via ContentProvider so that
 * when ModSettingsActivity is run in the standalone module app,
 * TikTok can read and sync settings seamlessly across process boundaries.
 */
public final class PrefsProvider extends ContentProvider {
    public static final String AUTHORITY = "com.golda.patchertiktok.prefs";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

    public static final String METHOD_GET_ALL = "getAll";
    public static final String METHOD_SET_BOOLEAN = "setBoolean";
    public static final String METHOD_SET_STRING = "setString";
    public static final String METHOD_SET_LONG = "setLong";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        if (ctx == null) return null;
        SharedPreferences sp = ctx.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);

        Bundle result = new Bundle();
        if (METHOD_GET_ALL.equals(method)) {
            Map<String, ?> all = sp.getAll();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                if (val instanceof Boolean) {
                    result.putBoolean(key, (Boolean) val);
                } else if (val instanceof String) {
                    result.putString(key, (String) val);
                } else if (val instanceof Long) {
                    result.putLong(key, (Long) val);
                } else if (val instanceof Integer) {
                    result.putInt(key, (Integer) val);
                } else if (val instanceof Float) {
                    result.putFloat(key, (Float) val);
                }
            }
            return result;
        } else if (METHOD_SET_BOOLEAN.equals(method) && arg != null && extras != null) {
            boolean val = extras.getBoolean("val", false);
            sp.edit().putBoolean(arg, val).apply();
            result.putBoolean("success", true);
            return result;
        } else if (METHOD_SET_STRING.equals(method) && arg != null && extras != null) {
            String val = extras.getString("val", "");
            sp.edit().putString(arg, val).apply();
            result.putBoolean("success", true);
            return result;
        } else if (METHOD_SET_LONG.equals(method) && arg != null && extras != null) {
            long val = extras.getLong("val", 0L);
            sp.edit().putLong(arg, val).apply();
            result.putBoolean("success", true);
            return result;
        }
        return null;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
