package com.ug.e87idrive;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;

/** Persistent app roles and six quick slots. Manual choices always win over detection. */
public final class AppRepository {
    public static final String[] ROLES = {"media", "radio", "nav", "auto", "phone"};
    private final Context context;
    private final SharedPreferences preferences;

    public AppRepository(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences("apps", Context.MODE_PRIVATE);
    }

    public String getPackage(String key) { return preferences.getString(key, null); }
    public String getActivity(String key) { return preferences.getString(key + ".activity", null); }
    public boolean isManual(String key) { return preferences.getBoolean(key + ".manual", false); }

    public void assign(String key, LaunchableApp app, boolean manual) {
        SharedPreferences.Editor editor = preferences.edit();
        if (app == null) {
            editor.remove(key).remove(key + ".activity").remove(key + ".manual");
        } else {
            editor.putString(key, app.packageName).putString(key + ".activity", app.activityName)
                    .putBoolean(key + ".manual", manual);
        }
        editor.apply();
    }

    public void clearAll() { preferences.edit().clear().apply(); }

    public Intent launchIntent(String key) {
        String pkg = getPackage(key);
        if (pkg == null) return null;
        String activity = getActivity(key);
        if (activity != null) {
            Intent explicit = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(pkg, activity));
            if (explicit.resolveActivity(context.getPackageManager()) != null) return explicit;
        }
        return context.getPackageManager().getLaunchIntentForPackage(pkg);
    }

    public String label(String pkg) {
        if (pkg == null) return "";
        try { return String.valueOf(context.getPackageManager().getApplicationLabel(
                context.getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (Exception e) { return pkg; }
    }

    public Drawable icon(String pkg) {
        try { return context.getPackageManager().getApplicationIcon(pkg); }
        catch (Exception e) { return null; }
    }

    public List<LaunchableApp> launchableApps() {
        return launchableApps(true);
    }

    private List<LaunchableApp> launchableApps(boolean includeIcons) {
        PackageManager pm = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved;
        try { resolved = pm.queryIntentActivities(query, PackageManager.GET_META_DATA); }
        catch (Exception e) { return Collections.emptyList(); }
        List<LaunchableApp> result = new ArrayList<>();
        for (ResolveInfo info : resolved) {
            result.add(new LaunchableApp(info.activityInfo.packageName, info.activityInfo.name,
                    String.valueOf(info.loadLabel(pm)), includeIcons ? info.loadIcon(pm) : null));
        }
        Collections.sort(result, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return result;
    }

    public LaunchableApp detect(String role) {
        return detect(role, launchableApps(false));
    }

    /** Scores every role against one lightweight package query, without decoding app icons. */
    public Map<String, LaunchableApp> detectRoles() {
        List<LaunchableApp> available = launchableApps(false);
        Map<String, LaunchableApp> detected = new LinkedHashMap<>();
        for (String role : ROLES) {
            LaunchableApp match = detect(role, available);
            if (match != null) detected.put(role, match);
        }
        return detected;
    }

    private LaunchableApp detect(String role, List<LaunchableApp> available) {
        List<String> needles;
        switch (role) {
            case "media": needles = Arrays.asList("music", "multimedia", "player", "spotify"); break;
            case "radio": needles = Arrays.asList("radio", "fm", "tuner"); break;
            case "nav": needles = Arrays.asList("maps", "navigation", "navi", "waze", "here"); break;
            case "auto": needles = Arrays.asList("s-play", "splay", "zlink", "tlink", "carlink", "autokit", "android auto", "easyconnection", "carbitlink", "phonelink"); break;
            case "phone": needles = Arrays.asList("dialer", "phone", "contacts", "contact", "bluetooth", "handsfree"); break;
            default: return null;
        }
        LaunchableApp best = null;
        int bestScore = 0;
        for (LaunchableApp app : available) {
            String haystack = (app.label + " " + app.packageName + " " + app.activityName).toLowerCase(Locale.ROOT);
            int score = 0;
            for (String needle : needles) if (haystack.contains(needle)) score += needle.length() > 4 ? 5 : 3;
            if ("phone".equals(role) && (haystack.contains("btmusic")
                    || haystack.contains("bt music") || haystack.contains("music"))) score -= 8;
            if (score > bestScore) { bestScore = score; best = app; }
        }
        return bestScore > 0 ? best : null;
    }

    public static final class LaunchableApp {
        public final String packageName;
        public final String activityName;
        public final String label;
        public final Drawable icon;

        LaunchableApp(String packageName, String activityName, String label, Drawable icon) {
            this.packageName = packageName;
            this.activityName = activityName;
            this.label = label;
            this.icon = icon;
        }
    }
}
