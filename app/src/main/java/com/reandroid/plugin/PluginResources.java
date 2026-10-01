package com.reandroid.plugin;

import android.content.Context;
import android.util.Log;

import com.reandroid.utils.IoUtils;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Loads plugin-localized resources from assets/{pluginId}/language/{locale}.json.
 * Falls back to "default" if the requested locale is not found.
 */
public final class PluginResources {

    private static final Map<String, JSONObject> sJsonCache = new HashMap<>();

    private PluginResources() {}

    /**
     * Read a localized string from the plugin's language bundle.
     * Load order: {locale}.json → default.json → null
     */
    public static String getString(Context context, String pluginId, String key) {
        String lang = Locale.getDefault().getLanguage();
        JSONObject json = loadLanguage(context, pluginId, lang);
        if (json == null) json = loadLanguage(context, pluginId, "default");
        if (json == null) return null;

        return json.optString(key, null);
    }

    /**
     * Load the full language JSON for a plugin.
     */
    public static JSONObject loadLanguage(Context context, String pluginId, String lang) {
        String path = pluginId + "/language/" + lang + ".json";
        JSONObject cached = sJsonCache.get(path);
        if (cached != null) return cached;
        try (InputStream is = context.getAssets().open(path)) {
            JSONObject json = new JSONObject(new String(IoUtils.readAllBytes(is), "UTF-8"));
            sJsonCache.put(path, json);
            return json;
        } catch (Exception e) {
            Log.w("PluginResources", "Failed to load language JSON: " + path, e);
            return null;
        }
    }

    /**
     * Load language for the current device locale, with default.json as fallback base.
     * Tries full locale (e.g. zh-rCN) first, then language-only (zh), merged onto default.
     * Keys missing in the locale file automatically fall back to default (English).
     */
    public static JSONObject loadLanguageForLocale(Context context, String pluginId) {
        // Always load default.json as base
        JSONObject base = loadLanguage(context, pluginId, "default");
        if (base == null) base = new JSONObject();

        Locale locale = Locale.getDefault();
        String fullTag = toAssetLocaleTag(locale);
        JSONObject localeJson = loadLanguage(context, pluginId, fullTag);
        if (localeJson == null) {
            localeJson = loadLanguage(context, pluginId, locale.getLanguage());
        }

        // Merge locale overrides onto base (keys not in locale fall back to base)
        if (localeJson != null) {
            try {
                JSONObject merged = new JSONObject();
                for (java.util.Iterator<String> it = base.keys(); it.hasNext(); ) {
                    String key = it.next();
                    merged.put(key, localeJson.has(key) ? localeJson.get(key) : base.get(key));
                }
                for (java.util.Iterator<String> it = localeJson.keys(); it.hasNext(); ) {
                    String key = it.next();
                    if (!merged.has(key)) merged.put(key, localeJson.get(key));
                }
                return merged;
            } catch (org.json.JSONException e) {
                Log.w("PluginResources", "Failed to merge language JSON", e);
            }
        }
        return base;
    }

    private static String toAssetLocaleTag(Locale locale) {
        String lang = locale.getLanguage();
        String country = locale.getCountry();
        if (country != null && !country.isEmpty()) {
            return lang + "-r" + country;
        }
        return lang;
    }

    /**
     * Load the layout definition for a plugin's settings UI.
     */
    public static JSONObject loadLayout(Context context, String pluginId) {
        String path = pluginId + "/layout.json";
        JSONObject cached = sJsonCache.get(path);
        if (cached != null) return cached;
        try (InputStream is = context.getAssets().open(path)) {
            JSONObject json = new JSONObject(new String(IoUtils.readAllBytes(is), "UTF-8"));
            sJsonCache.put(path, json);
            return json;
        } catch (Exception e) {
            Log.w("PluginResources", "Failed to load layout JSON: " + path, e);
            return null;
        }
    }

    /**
     * Load the plugin's info.json (label, previewClass, pluginVk, permissions).
     */
    public static JSONObject loadInfo(Context context, String pluginId) {
        String path = pluginId + "/info.json";
        JSONObject cached = sJsonCache.get(path);
        if (cached != null) return cached;
        try (InputStream is = context.getAssets().open(path)) {
            JSONObject json = new JSONObject(new String(IoUtils.readAllBytes(is), "UTF-8"));
            sJsonCache.put(path, json);
            return json;
        } catch (Exception e) {
            Log.w("PluginResources", "Failed to load info JSON: " + path, e);
            return null;
        }
    }

    /**
     * Parse a "@string/name" label reference; returns the resource name,
     * or null when the label is a plain string (or any other @ prefix).
     */
    public static String parseLabelRef(String label) {
        if (label == null) return null;
        final String PREFIX = "@string/";
        return label.startsWith(PREFIX) ? label.substring(PREFIX.length()) : null;
    }

    /**
     * 壁纸显示名。
     *
     * <p><b>名字的主要来源是这个壁纸自己的 assets</b>（{@code language/<locale>.json} 的
     * {@code title}）—— 这样新增或改动一个壁纸，不必再去动 res 和十几个语言文件。
     *
     * <p>三条兜底各有各的用处：
     * <ul>
     *   <li>{@code info.json} 里的**字面文字**：个别插件就想硬写一个名字，不进语言包。</li>
     *   <li>{@code @string/} 引用：**清单里那 8 个壁纸只能走这条** ——
     *       {@code AndroidManifest} 的 label 只能来自 res（系统壁纸选择器读的就是它）。</li>
     *   <li>插件 id：什么都没有时不至于显示空白。</li>
     * </ul>
     */
    public static String resolveLabel(Context context, String pluginId, JSONObject info) {
        String label = info != null ? info.optString("label", null) : null;
        String ref = parseLabelRef(label);
        String plain = ref == null ? label : null;

        String resValue = null;
        if (ref != null) {
            int id = context.getResources().getIdentifier(ref, "string", context.getPackageName());
            resValue = id != 0 ? context.getString(id) : ref;
        }

        JSONObject bundle = loadLanguageForLocale(context, pluginId);
        String bundleTitle = bundle != null ? bundle.optString("title", null) : null;

        return labelFrom(plain, bundleTitle, resValue, pluginId);
    }

    /**
     * 名字的取舍顺序（纯函数，单测直接跑它：{@link #resolveLabel} 要 Context，进不了 JVM 单测）。
     *
     * @param plainLabel  info.json 里的字面名字，没有就 null
     * @param bundleTitle 插件自己语言包里的 title，没有就 null
     * @param resValue    {@code @string/} 解析出来的值，没有就 null
     * @param pluginId    最后的兜底
     */
    static String labelFrom(String plainLabel, String bundleTitle, String resValue, String pluginId) {
        if (plainLabel != null && !plainLabel.isEmpty()) return plainLabel;
        if (bundleTitle != null && !bundleTitle.isEmpty()) return bundleTitle;
        if (resValue != null && !resValue.isEmpty()) return resValue;
        return pluginId;
    }
}
