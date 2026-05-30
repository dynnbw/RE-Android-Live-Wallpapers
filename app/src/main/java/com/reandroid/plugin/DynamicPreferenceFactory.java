package com.reandroid.plugin;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import org.json.JSONArray;
import org.json.JSONObject;

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
                                         JSONObject layout, AddPreferenceAction addAction) {
        JSONArray items = layout.optJSONArray("prefs");
        if (items == null) return;

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;

            String type = item.optString("type");
            Preference p = create(context, type, item, prefs);
            if (p != null) {
                addAction.add(p);
            }
        }
    }

    private static Preference create(Context context, String type, JSONObject item,
                                      SharedPreferences prefs) {
        String key = item.optString("key");
        String title = item.optString("title");
        if (key.isEmpty()) return null;

        switch (type) {
            case "switch": {
                SwitchPreferenceCompat sp = new SwitchPreferenceCompat(context);
                sp.setKey(key);
                sp.setTitle(title);
                sp.setDefaultValue(item.optBoolean("default", false));
                return sp;
            }
            case "seekbar": {
                SeekBarPreference sp = new SeekBarPreference(context);
                sp.setKey(key);
                sp.setTitle(title);
                sp.setMin(item.optInt("min", 0));
                sp.setMax(item.optInt("max", 100));
                sp.setDefaultValue(item.optInt("default", 50));
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
                    String[] l = labels != null ? new String[labels.length()] : v.clone();
                    for (int i = 0; i < vals.length(); i++) v[i] = vals.optString(i);
                    for (int i = 0; i < (labels != null ? labels.length() : vals.length()); i++) {
                        l[i] = labels != null ? labels.optString(i) : v[i];
                    }
                    lp.setEntryValues(v);
                    lp.setEntries(l);
                }
                lp.setDefaultValue(item.optString("default", ""));
                return lp;
            }
        }
        return null;
    }

    /** Callback for each created preference. */
    public interface AddPreferenceAction {
        void add(Preference preference);
    }
}
