package com.reandroid.plugin;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Creates AndroidX Preference objects from a plugin's layout.json definition.
 * Supported types: switch, seekbar, list, button, color.
 *
 * color 类型:内嵌的取色组件({@link InlineColorPreference},标题 + 色块 + 色调/饱和度/亮度 滑块),
 * 拖动即时写入 int(ARGB)设置,壁纸实时跟随;default 可写十六进制字符串或整数,
 * labels 给出滑块标签(个数 2 或 3 决定显示几个滑块)。
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

    /**
     * 依赖绑定：记录这一项依赖哪个键、默认值是什么、当前是否满足。
     *
     * <p>这几个参数原先只在构建的 Pass 2 里用一次就丢掉，于是父项变化后没有任何办法
     * 只重算受影响的子项 —— 只能把整块动态区拆掉重建（重建会重读 layout.json、
     * 丢掉滚动位置和焦点）。留下它们之后 {@link #refreshDependents} 就能做增量刷新。
     */
    private static final class DependencyState {
        final String dependency;
        final String disableOn;
        final boolean depDefaultTrue;
        final boolean dkDefaultFalse;
        boolean satisfied = true;

        DependencyState(String dependency, String disableOn,
                        boolean depDefaultTrue, boolean dkDefaultFalse) {
            this.dependency = dependency;
            this.disableOn = disableOn;
            this.depDefaultTrue = depDefaultTrue;
            this.dkDefaultFalse = dkDefaultFalse;
        }

        /** 这个 key 是否是本项的依赖父项。 */
        boolean dependsOn(String key) {
            return key.equals(dependency) || key.equals(disableOn);
        }

        void evaluate(SharedPreferences prefs) {
            satisfied = dependencySatisfied(prefs, dependency, disableOn, depDefaultTrue, dkDefaultFalse);
        }
    }

    /** 键是控件实例本身；控件被重建丢弃后条目可被回收，所以用 WeakHashMap。 */
    private static final java.util.WeakHashMap<Preference, DependencyState> sDepStates =
            new java.util.WeakHashMap<>();

    /**
     * 控件的值规格：key 与默认值。用于在不重建的前提下把值从 prefs 重读一遍。
     *
     * <p>重建动态区时每个控件都会重新构造、重读一次 prefs，所以"值被别的东西改了"
     * 能顺带刷对；只算依赖关系的增量刷新会漏掉这一维。
     */
    private static final class ValueSpec {
        final String key;
        final Object defaultValue;

        ValueSpec(String key, Object defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
        }
    }

    private static final java.util.WeakHashMap<Preference, ValueSpec> sValueSpecs =
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
                } else if (!(p instanceof InlineColorPreference)) {
                    // 内嵌取色器自带布局,不能被覆盖
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

            // 记录绑定，供父项变化时增量刷新（原先用完即弃，只能靠整块重建）
            final DependencyState state = new DependencyState(dep, dk, depDefaults[i], dkDefaults[i]);
            state.evaluate(prefs);
            sDepStates.put(p, state);

            // 值拦截（软禁用）：返回 false 时框架不会写入这个值
            p.setOnPreferenceChangeListener((pref, newValue) -> {
                state.evaluate(prefs);
                return state.satisfied;
            });

            // 初始视觉置灰
            applyDependencyVisual(p, state.satisfied);
        }
    }

    private static void registerValue(Preference pref, String key, Object defaultValue) {
        sValueSpecs.put(pref, new ValueSpec(key, defaultValue));
    }

    /**
     * 把 key 对应的那个控件的值从 prefs 重读一遍 —— "整块重建"另一半的增量版。
     *
     * <p>重建时每个控件都会重新构造，顺手把值也重读了，所以"值被别的东西改了"
     * 能顺带刷对；只重算依赖关系的增量刷新会漏掉这一维。这里补上。
     *
     * @return 是否刷新了
     */
    public static boolean refreshValue(PreferenceGroup root, SharedPreferences prefs,
                                       String changedKey) {
        if (root == null || prefs == null || changedKey == null) return false;
        boolean touched = false;
        for (int i = 0; i < root.getPreferenceCount(); i++) {
            Preference p = root.getPreference(i);
            if (p instanceof PreferenceGroup) {
                if (refreshValue((PreferenceGroup) p, prefs, changedKey)) {
                    touched = true;
                }
                continue;
            }
            ValueSpec spec = sValueSpecs.get(p);
            if (spec == null || !spec.key.equals(changedKey)) continue;
            if (applyValueFromPrefs(p, spec, prefs)) touched = true;
        }
        return touched;
    }

    /** 按控件类型把 prefs 里的值写回控件。写不进去就保持原样，不抛。 */
    private static boolean applyValueFromPrefs(Preference p, ValueSpec spec, SharedPreferences prefs) {
        Object stored = prefs.getAll().get(spec.key);
        if (stored == null) stored = spec.defaultValue;

        if (p instanceof SwitchPreferenceCompat) {
            boolean on = stored instanceof Boolean
                    ? (Boolean) stored : Boolean.parseBoolean(String.valueOf(stored));
            SwitchPreferenceCompat sp = (SwitchPreferenceCompat) p;
            if (sp.isChecked() != on) sp.setChecked(on);
            return true;
        }
        if (p instanceof SeekBarPreference) {
            int v = stored instanceof Number
                    ? ((Number) stored).intValue() : parseOrDefault(stored, (Integer) spec.defaultValue);
            SeekBarPreference sp = (SeekBarPreference) p;
            if (sp.getValue() != v) sp.setValue(v);
            return true;
        }
        if (p instanceof ListPreference) {
            String v = String.valueOf(stored);
            ListPreference lp = (ListPreference) p;
            if (!v.equals(lp.getValue())) lp.setValue(v);
            return true;
        }
        if (p instanceof InlineColorPreference) {
            // 它 setPersistent(false) 自己管 prefs，onBindViewHolder 里会重读颜色，
            // 所以这里只要触发一次重绑就够了。
            ((InlineColorPreference) p).refreshFromPrefs();
            return true;
        }
        return false;
    }

    private static int parseOrDefault(Object stored, Integer fallback) {
        try {
            return Integer.parseInt(String.valueOf(stored));
        } catch (NumberFormatException e) {
            return fallback != null ? fallback : 0;
        }
    }

    /**
     * 某个 prefs key 变化后，只重算依赖它的那几项 —— 取代"整块重建动态区"。
     *
     * <p>父项→子项的关系原先只在构建时用一次，父项自己没有监听器，所以子项的置灰
     * 状态只能靠拆掉整块重建来纠正。这里按 key 精确匹配，只碰受影响的控件。
     *
     * @return 是否刷新了至少一项
     */
    public static boolean refreshDependents(PreferenceGroup root, SharedPreferences prefs,
                                            String changedKey) {
        if (root == null || prefs == null || changedKey == null) return false;
        boolean touched = false;
        for (int i = 0; i < root.getPreferenceCount(); i++) {
            Preference p = root.getPreference(i);
            if (p instanceof PreferenceGroup) {
                if (refreshDependents((PreferenceGroup) p, prefs, changedKey)) {
                    touched = true;
                }
                continue;
            }
            DependencyState state = sDepStates.get(p);
            if (state == null || !state.dependsOn(changedKey)) continue;
            state.evaluate(prefs);
            applyDependencyVisual(p, state.satisfied);
            touched = true;
        }
        return touched;
    }

    /** 应用依赖视觉：内嵌取色器走自己的软禁用途径，其余靠 setGrayed 的 alpha。 */
    private static void applyDependencyVisual(Preference pref, boolean satisfied) {
        if (pref instanceof InlineColorPreference) {
            // 内嵌取色器:软禁用内部滑块(不动 setEnabled)
            ((InlineColorPreference) pref).setControlsActive(satisfied);
        } else {
            setGrayed(pref, satisfied);
        }
    }

    /** Update gray-out state and re-bind the preference row.
     *  setSelectable() 触发 notifyChanged() 重绑（onBindViewHolder 应用 alpha），
     *  并让整行不可点击；避免 setEnabled(false) 的 ANGLE 驱动崩溃。 */
    private static void setGrayed(Preference pref, boolean satisfied) {
        DependencyState state = sDepStates.get(pref);
        if (state != null) {
            state.satisfied = satisfied;
        }
        pref.setSelectable(satisfied);
    }

    /** 依赖条件：dependency 父项为 true 且 disableOn 父项不为 true。 */
    private static boolean dependencySatisfied(SharedPreferences prefs,
            String dep, String dk, boolean depDefTrue, boolean dkDefFalse) {
        if (dep != null && !dep.isEmpty() && !prefs.getBoolean(dep, depDefTrue)) return false;
        if (dk != null && !dk.isEmpty() && prefs.getBoolean(dk, dkDefFalse)) return false;
        return true;
    }

    /**
     * color 类型的滑块标签:labels 数组中的语言键依次对应 色调 / 饱和度 / 亮度。
     * 未配置时退回内置英文标签(Hue / Saturation / Brightness)。
     */
    private static String[] resolveSliderLabels(JSONObject item, JSONObject language) {
        JSONArray arr = item.optJSONArray("labels");
        if (arr == null || arr.length() == 0) {
            return new String[] {"Hue", "Saturation", "Brightness"};
        }
        int count = Math.min(3, arr.length());
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            out[i] = resolveLang(language, arr.optString(i));
        }
        return out;
    }

    /** color 类型的 default:支持 "#RRGGBB" / "#AARRGGBB" 字符串,或整数。 */
    private static int colorDefault(JSONObject item, int fallback) {
        Object raw = item.opt("default");
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        if (raw instanceof String) {
            return ColorPrefs.parseHex((String) raw, fallback);
        }
        return fallback;
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
                boolean defaultOn = item.optBoolean("default", false);
                sp.setDefaultValue(defaultOn);
                registerValue(sp, key, defaultOn);
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
                int defaultValue = item.optInt("default", 50);
                sp.setDefaultValue(defaultValue);
                sp.setShowSeekBarValue(true);
                registerValue(sp, key, defaultValue);
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
                String defaultEntry = item.optString("default", "");
                lp.setDefaultValue(defaultEntry);
                registerValue(lp, key, defaultEntry);
                return lp;
            }
            case "color": {
                // 内嵌取色组件(标题 + 色块 + 色调/饱和度/亮度 滑块),拖动即时写入设置;
                // labels 给出滑块标签(取自插件语言文件),个数决定显示 2 个还是 3 个滑块
                InlineColorPreference cp = new InlineColorPreference(context, prefs,
                        colorDefault(item, 0xFF000000), resolveSliderLabels(item, language));
                cp.setKey(key);
                cp.setTitle(title);
                if (!summary.isEmpty()) {
                    cp.setSummary(summary);
                }
                registerValue(cp, key, colorDefault(item, 0xFF000000));
                return cp;
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
        DependencyState state = sDepStates.get(pref);
        return (state != null && !state.satisfied) ? GRAY_ALPHA : 1.0f;
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
