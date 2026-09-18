package com.dingtalk.recall;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

final class Config {

    static final String PREFS_NAME = "anti_recall_state";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_MARKER = "show_marker";

    private static volatile boolean loaded = false;
    private static volatile boolean enabled = true;
    private static volatile boolean marker = true;

    private Config() {
    }

    static boolean enabled() {
        ensureLoaded();
        return enabled;
    }

    static boolean marker() {
        ensureLoaded();
        return marker;
    }

    static SharedPreferences prefs() {
        try {
            Application app = currentApplication();
            if (app == null) {
                return null;
            }
            return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        enabled = prefs.getBoolean(KEY_ENABLED, true);
        marker = prefs.getBoolean(KEY_MARKER, true);
        loaded = true;
    }

    static void reload() {
        loaded = false;
        ensureLoaded();
    }

    static void save(boolean enabledValue, boolean markerValue) {
        enabled = enabledValue;
        marker = markerValue;
        loaded = true;
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit()
                    .putBoolean(KEY_ENABLED, enabledValue)
                    .putBoolean(KEY_MARKER, markerValue)
                    .apply();
        }
    }

    private static Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = activityThread.getMethod("currentApplication").invoke(null);
            return app instanceof Application ? (Application) app : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
