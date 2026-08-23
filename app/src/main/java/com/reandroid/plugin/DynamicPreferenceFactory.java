package com.reandroid.plugin;

import android.content.Context;
import android.content.SharedPreferences;

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
 * Supported types: switch, seekbar, list, button.
 */
public final class DynamicPreferenceFactory {

    private DynamicPreferenceFactory() {}

    /**
     * Gray-out visual state for soft-disabled preferences.
     * setEnabled(false) is avoided on purpose: rendering a disabled SeekBar
     * crashes the ANGLE→Vulkan stack on some devices (fatal signal 11 in
     * vkCmdBeginRenderPass). Alpha keeps the control interactive-looking but
     * visibly disabled; the change-listener still blocks value writes.
     */
    private static final float GRAY_ALPHA = 0.4f;
    private static final java.util.WeakHashMap<Preference, Boolean> sGrayedStates =
            new java.util.WeakHashMap<>();

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

        // Pass 2: soft-dependency with initial visual gray-out.
        // 值拦截（change-listener）软禁用，避免 SeekBar setEnabled 崩溃；
        // 初始视觉用 alpha 置灰（onBindViewHolder 读取）。依赖父项变化时由
        // PluginSettingsFragment 触发动态区 rebuild（视觉必然正确）。
        for (int i = 0; i < count; i++) {
            Preference p = created[i];
            if (p == null) continue;
            String dep = dependencies[i];
            String dk = disableOnKeys[i];
            boolean hasDep = dep != null && !dep.isEmpty();
            boolean hasDk = dk != null && !dk.isEmpty();
            if (!hasDep && !hasDk) continue;

            final String fDep = dep;
            final String fDk = dk;
            final boolean depDefTrue = depDefaults[i];
            final boolean dkDefFalse = dkDefaults[i];

            // 值拦截（软禁用）
            p.setOnPreferenceChangeListener((pref, newValue) ->
                    dependencySatisfied(prefs, fDep, fDk, depDefTrue, dkDefFalse));

            // 初始视觉置灰
            setGrayed(p, dependencySatisfied(prefs, fDep, fDk, depDefTrue, dkDefFalse));
        }
    }

    /** Update gray-out state and re-bind the preference row.
     *  setSelectable() 触发 notifyChanged() 重绑（onBindViewHolder 应用 alpha），
     *  并让整行不可点击；避免 setEnabled(false) 的 ANGLE 驱动崩溃。 */
    private static void setGrayed(Preference pref, boolean satisfied) {
        sGrayedStates.put(pref, !satisfied);
        pref.setSelectable(satisfied);
    }

    /** 依赖条件：dependency 父项为 true 且 disableOn 父项不为 true。 */
    private static boolean dependencySatisfied(SharedPreferences prefs,
            String dep, String dk, boolean depDefTrue, boolean dkDefFalse) {
        if (dep != null && !dep.isEmpty() && !prefs.getBoolean(dep, depDefTrue)) return false;
        if (dk != null && !dk.isEmpty() && prefs.getBoolean(dk, dkDefFalse)) return false;
        return true;
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
                SwitchPreferenceCompat sp = new SwitchPreferenceCompat(context) {
                    @Override
                    public void onBindViewHolder(androidx.preference.PreferenceViewHolder holder) {
                        super.onBindViewHolder(holder);
                        applyGrayAlpha(holder, this);
                    }
                };
                sp.setKey(key);
                sp.setTitle(title);
                if (!summary.isEmpty()) sp.setSummary(summary);
                sp.setDefaultValue(item.optBoolean("default", false));
                return sp;
            }
            case "seekbar": {
                SeekBarPreference sp = new SeekBarPreference(context) {
                    @Override
                    public void onBindViewHolder(androidx.preference.PreferenceViewHolder holder) {
                        super.onBindViewHolder(holder);
                        applyGrayAlpha(holder, this);
                    }
                };
                sp.setKey(key);
                sp.setTitle(title);
                if (!summary.isEmpty()) sp.setSummary(summary);
                sp.setMin(item.optInt("min", 0));
                sp.setMax(item.optInt("max", 100));
                sp.setDefaultValue(item.optInt("default", 50));
                sp.setShowSeekBarValue(true);
                return sp;
            }
            case "list": {
                ListPreference lp = new ListPreference(context) {
                    @Override
                    public void onBindViewHolder(androidx.preference.PreferenceViewHolder holder) {
                        super.onBindViewHolder(holder);
                        applyGrayAlpha(holder, this);
                    }
                };
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
                Preference bp = new Preference(context) {
                    @Override
                    public void onBindViewHolder(androidx.preference.PreferenceViewHolder holder) {
                        super.onBindViewHolder(holder);
                        applyGrayAlpha(holder, this);
                    }
                };
                bp.setKey(key);
                bp.setTitle(title);
                if (!summary.isEmpty()) bp.setSummary(summary);
                bp.setPersistent(false); // Button doesn't store a value
                return bp;
            }
        }
        return null;
    }

    /** Alpha for the grayed-out visual state of a soft-disabled preference. */
    private static float grayAlpha(Preference pref) {
        Boolean grayed = sGrayedStates.get(pref);
        return (grayed != null && grayed) ? GRAY_ALPHA : 1.0f;
    }

    /** Apply gray-out alpha in onBindViewHolder of the anonymous preference subclasses. */
    private static void applyGrayAlpha(androidx.preference.PreferenceViewHolder holder, Preference pref) {
        holder.itemView.setAlpha(grayAlpha(pref));
    }

    /**
     * Returns a map of button key → {action, disableOnKey} for all button-type prefs
     * in the layout. Call after buildPreferences() to wire up click handlers.
     * disableOnKey is checked at click time because buttons store no value, so the
     * change-listener soft-disable does not apply to them.
     */
    public static Map<String, String[]> collectButtonSpecs(JSONObject layout) {
        Map<String, String[]> specs = new LinkedHashMap<>();
        JSONArray items = layout != null ? layout.optJSONArray("prefs") : null;
        if (items == null) return specs;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && "button".equals(item.optString("type"))) {
                String key = item.optString("key");
                String action = item.optString("action", "");
                if (!key.isEmpty()) specs.put(key, new String[]{action, item.optString("disableOn", null)});
            }
        }
        return specs;
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
