package com.reandroid.plugin;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Creates AndroidX Preference objects from a plugin's layout.json definition.
 * Supported types: switch, seekbar, list.
 */
public final class DynamicPreferenceFactory {

    private DynamicPreferenceFactory() {}

    /**
     * Parse layout.json and add all preferences to the given screen.
     * @param context   context for preference creation
     * @param prefs     plugin-isolated SharedPreferences
     * @param layout    parsed layout.json
     * @param addAction callback to add each created preference
     */
    public static void buildPreferences(Context context, SharedPreferences prefs,
                                         JSONObject layout, AddPreferenceAction addAction,
                                         JSONObject language) {
        JSONArray items = layout.optJSONArray("prefs");
        if (items == null) return;

        int count = items.length();
        Preference[] created = new Preference[count];
        String[] dependencies = new String[count];
        String[] disableOnKeys = new String[count];
        boolean[] depDefaults = new boolean[count];   // default true value for dependency parent
        boolean[] dkDefaults = new boolean[count];    // default false value for disableOn parent

        // Pass 1: create preferences and add them
        for (int i = 0; i < count; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;

            String type = item.optString("type");
            Preference p = create(context, type, item, prefs, language);
            if (p != null) {
                dependencies[i] = item.optString("dependency", null);
                disableOnKeys[i] = item.optString("disableOn", null);
                // Resolve default values for dependency/disableOn parent keys
                depDefaults[i] = findDefaultBool(items, dependencies[i], true);
                dkDefaults[i] = findDefaultBool(items, disableOnKeys[i], false);
                if ("seekbar".equals(type)) {
                    p.setLayoutResource(com.reandroid.wallpaper.R.layout.preference_modern_seekbar);
                } else {
                    p.setLayoutResource(com.reandroid.wallpaper.R.layout.preference_modern_item);
                }
                addAction.add(p);
                created[i] = p;
            }
        }

        // Pass 2: set soft-dependency (no setEnabled to avoid SeekBar crash)
        for (int i = 0; i < count; i++) {
            Preference p = created[i];
            if (p == null) continue;
            String dep = dependencies[i];
            String dk = disableOnKeys[i];
            if ((dep != null && !dep.isEmpty()) || (dk != null && !dk.isEmpty())) {
                boolean depDefTrue = depDefaults[i];
                boolean dkDefFalse = dkDefaults[i];
                final String origSummary = p.getSummary() != null ? p.getSummary().toString() : null;
                // Initial state
                boolean blocked = false;
                if (dep != null && !dep.isEmpty()) blocked = !prefs.getBoolean(dep, depDefTrue);
                if (!blocked && dk != null && !dk.isEmpty()) blocked = prefs.getBoolean(dk, dkDefFalse);
                if (blocked) p.setSummary("[Disabled] " + (origSummary != null ? origSummary : ""));
                p.setOnPreferenceChangeListener((pref, newValue) -> {
                    if (dep != null && !dep.isEmpty() && !prefs.getBoolean(dep, depDefTrue)) return false;
                    if (dk != null && !dk.isEmpty() && prefs.getBoolean(dk, dkDefFalse)) return false;
                    return true;
                });
                prefs.registerOnSharedPreferenceChangeListener((sp, key) -> {
                    if (key.equals(dep) || key.equals(dk)) {
                        boolean nowBlocked = false;
                        if (dep != null && !dep.isEmpty()) nowBlocked = !sp.getBoolean(dep, depDefTrue);
                        if (!nowBlocked && dk != null && !dk.isEmpty()) nowBlocked = sp.getBoolean(dk, dkDefFalse);
                        p.setSummary(nowBlocked ? "[Disabled] " + (origSummary != null ? origSummary : "")
                                               : origSummary);
                    }
                });
            }
        }
    }

    static boolean findDefaultBool(JSONArray items, String key, boolean fallback) {
        if (key == null) return fallback;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && key.equals(item.optString("key"))) {
                return item.optBoolean("default", fallback);
            }
        }
        return fallback;
    }

    private static String resolveLang(JSONObject lang, String key) {
        if (lang != null) {
            String val = lang.optString(key, null);
            if (val != null && !val.isEmpty()) return val;
        }
        return key; // fallback: use the key as display text
    }

    private static Preference create(Context context, String type, JSONObject item,
                                      SharedPreferences prefs, JSONObject language) {
        String key = item.optString("key");
        String title = resolveLang(language, item.optString("title"));
        String summary = resolveLang(language, item.optString("summary", ""));
        // Resolve @string/ references (language JSON has priority, @string/ as fallback)
        title = resolveStringRef(context, title);
        if (!summary.isEmpty()) summary = resolveStringRef(context, summary);
        if (key.isEmpty()) return null;

        switch (type) {
            case "switch": {
                SwitchPreferenceCompat sp = new SwitchPreferenceCompat(context);
                sp.setKey(key);
                sp.setTitle(title);
                if (!summary.isEmpty()) sp.setSummary(summary);
                sp.setDefaultValue(item.optBoolean("default", false));
                return sp;
            }
            case "seekbar": {
                SeekBarPreference sp = new SeekBarPreference(context);
                sp.setKey(key);
                sp.setTitle(title);
                if (!summary.isEmpty()) sp.setSummary(summary);
                sp.setMin(item.optInt("min", 0));
                sp.setMax(item.optInt("max", 100));
                sp.setDefaultValue(item.optInt("default", 50));
                try {
                    // Show the value label (app:showSeekBarValue="true" equivalent)
                    java.lang.reflect.Field f = SeekBarPreference.class.getDeclaredField("mShowSeekBarValue");
                    f.setAccessible(true);
                    f.setBoolean(sp, true);
                } catch (Exception ignored) {}
                return sp;
            }
            case "list": {
                ListPreference lp = new ListPreference(context);
                lp.setKey(key);
                lp.setTitle(title);
                JSONArray vals = item.optJSONArray("values");
                JSONArray labels = item.optJSONArray("labels");
                if (vals != null) {
                    String[] v = new String[vals.length()];
                    String[] l = new String[vals.length()];
                    for (int i = 0; i < vals.length(); i++) v[i] = vals.optString(i);
                    for (int i = 0; i < vals.length(); i++) {
                        String raw = labels != null ? labels.optString(i, v[i]) : v[i];
                        // Try language JSON: {key}_label_{value}
                        String langKey = key + "_label_" + v[i];
                        String langVal = resolveLang(language, langKey);
                        if (!langVal.equals(langKey)) {
                            l[i] = resolveStringRef(context, langVal);
                        } else {
                            l[i] = resolveStringRef(context, raw);
                        }
                    }
                    lp.setEntryValues(v);
                    lp.setEntries(l);
                }
                lp.setDefaultValue(item.optString("default", ""));
                return lp;
            }
            case "button": {
                Preference bp = new Preference(context);
                bp.setKey(key);
                bp.setTitle(title);
                if (!summary.isEmpty()) bp.setSummary(summary);
                bp.setPersistent(false); // Button doesn't store a value
                return bp;
            }
        }
        return null;
    }

    /**
     * Returns a map of button key → action string for all button-type prefs in the layout.
     * Call after buildPreferences() to wire up click handlers.
     */
    public static Map<String, String> collectButtonActions(JSONObject layout) {
        Map<String, String> actions = new LinkedHashMap<>();
        JSONArray items = layout != null ? layout.optJSONArray("prefs") : null;
        if (items == null) return actions;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && "button".equals(item.optString("type"))) {
                String key = item.optString("key");
                String action = item.optString("action", "");
                if (!key.isEmpty()) actions.put(key, action);
            }
        }
        return actions;
    }

    private static String resolveStringRef(Context ctx, String s) {
        if (s != null && s.startsWith("@string/")) {
            int id = ctx.getResources().getIdentifier(
                    s.substring(8), "string", ctx.getPackageName());
            if (id != 0) return ctx.getString(id);
        }
        return s != null ? s : "";
    }

    /** Callback for each created preference. */
    public interface AddPreferenceAction {
        void add(Preference preference);
    }
}
