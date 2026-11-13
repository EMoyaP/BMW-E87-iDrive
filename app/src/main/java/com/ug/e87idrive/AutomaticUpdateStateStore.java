package com.ug.e87idrive;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent, independent progress channels shown only on the stationary dashboard. */
final class AutomaticUpdateStateStore {
    static final String FUEL = "fuel";
    static final String GIT = "git";
    private static final String PREFS = "e87_automatic_update_state";

    static final class State {
        final boolean visible;
        final int progress;
        final String message;
        State(boolean visible, int progress, String message) {
            this.visible = visible; this.progress = progress; this.message = message;
        }
    }

    private AutomaticUpdateStateStore() { }

    static void pending(Context context, String channel, String message) {
        write(context, channel, true, 0, message);
    }

    static void progress(Context context, String channel, int progress, String message) {
        write(context, channel, true, Math.max(0, Math.min(99, progress)), message);
    }

    static void complete(Context context, String channel, String message) {
        SharedPreferences preferences = preferences(context);
        preferences.edit().putBoolean(channel + "_visible", false)
                .putInt(channel + "_progress", 100)
                .putString(channel + "_message", message == null ? "" : message)
                .putLong(channel + "_changed", System.currentTimeMillis()).apply();
    }

    static void ready(Context context,String channel,String message){
        write(context,channel,true,100,message);
    }

    static State read(Context context, String channel) {
        SharedPreferences preferences = preferences(context);
        return new State(preferences.getBoolean(channel + "_visible", false),
                preferences.getInt(channel + "_progress", 0),
                preferences.getString(channel + "_message", ""));
    }

    private static void write(Context context, String channel, boolean visible, int progress,
                              String message) {
        preferences(context).edit().putBoolean(channel + "_visible", visible)
                .putInt(channel + "_progress", progress)
                .putString(channel + "_message", message == null ? "" : message)
                .putLong(channel + "_changed", System.currentTimeMillis()).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
