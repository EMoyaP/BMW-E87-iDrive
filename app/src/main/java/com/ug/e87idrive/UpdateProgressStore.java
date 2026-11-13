package com.ug.e87idrive;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Process-wide ownership and a small recoverable status for the explicit data-update workflow.
 *
 * <p>Repositories already use application context and complete their network callbacks after an
 * Activity is covered or recreated. This class prevents a second screen from starting a competing
 * replacement/import while that first operation owns the local SQLite databases.</p>
 */
final class UpdateProgressStore {
    private static final String PREFS = "e87_update_progress";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_LABEL = "label";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_OPERATION = "operation";
    private static final String KEY_CHANGED_AT = "changed_at";
    private static boolean active;

    private UpdateProgressStore() { }

    static synchronized boolean begin(Context context, String label) {
        if (active) return false;
        active = true;
        write(context, true, label, "Preparando actualización…", label);
        return true;
    }

    static synchronized boolean isRunning(Context context) {
        // A persisted `running` value from a killed process cannot represent a live worker. The
        // in-memory flag is the source of truth for mutual exclusion in this process.
        return active;
    }

    static synchronized void progress(Context context, String label, String message) {
        if (!active) return;
        write(context, true, label, message, null);
    }

    static synchronized void finish(Context context, String label, String message) {
        active = false;
        write(context, false, label, message, "");
    }

    /** Returns an operation abandoned by a killed process and marks it ready to be restarted. */
    static synchronized String consumeInterruptedOperation(Context context) {
        if (active) return "";
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!preferences.getBoolean(KEY_RUNNING, false)) return "";
        String operation = preferences.getString(KEY_OPERATION, "");
        preferences.edit().putBoolean(KEY_RUNNING, false)
                .putString(KEY_MESSAGE, "Interrumpida; pendiente de reanudación")
                .putLong(KEY_CHANGED_AT, System.currentTimeMillis()).apply();
        return operation == null ? "" : operation;
    }

    static String lastFinishedMessage(Context context) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (preferences.getBoolean(KEY_RUNNING, false)) return "";
        long changed = preferences.getLong(KEY_CHANGED_AT, 0L);
        if (changed <= 0L || System.currentTimeMillis() - changed > 90_000L) return "";
        String message = preferences.getString(KEY_MESSAGE, "");
        return message == null ? "" : message;
    }

    static String currentMessage(Context context) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String label = preferences.getString(KEY_LABEL, "Actualización");
        String message = preferences.getString(KEY_MESSAGE, "");
        return (label == null ? "Actualización" : label)
                + (message == null || message.isEmpty() ? "" : " · " + message);
    }

    private static void write(Context context, boolean running, String label, String message,
                              String operation) {
        SharedPreferences.Editor editor = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, running)
                .putString(KEY_LABEL, label == null ? "Actualización" : label)
                .putString(KEY_MESSAGE, message == null ? "" : message)
                .putLong(KEY_CHANGED_AT, System.currentTimeMillis());
        if (operation != null) editor.putString(KEY_OPERATION, operation);
        editor.apply();
    }
}
