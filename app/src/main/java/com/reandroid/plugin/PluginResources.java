package com.reandroid.plugin;

import android.content.Context;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.Locale;

/**
 * Loads plugin-localized resources from assets/{pluginId}/language/{locale}.json.
 * Falls back to "default" if the requested locale is not found.
 */
public final class PluginResources {

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
        // Try assets/{pluginId}/language/{lang}.json
        String path = pluginId + "/language/" + lang + ".json";
        try (InputStream is = context.getAssets().open(path)) {
            byte[] buf = new byte[is.available()];
            is.read(buf);
            return new JSONObject(new String(buf, "UTF-8"));
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Load language for the current device locale, falling back to "default".
     * Tries full locale (e.g. zh-rCN) first, then language-only (zh), then default.
     */
    public static JSONObject loadLanguageForLocale(Context context, String pluginId) {
        Locale locale = Locale.getDefault();
        // Try full locale: zh-rCN, pt-rBR, etc.
        String fullTag = toAssetLocaleTag(locale);
        JSONObject json = loadLanguage(context, pluginId, fullTag);
        // Try language only: zh, pt, etc.
        if (json == null) {
            json = loadLanguage(context, pluginId, locale.getLanguage());
        }
        // Fallback to default
        if (json == null) {
            json = loadLanguage(context, pluginId, "default");
        }
        return json;
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
        try (InputStream is = context.getAssets().open(path)) {
            byte[] buf = new byte[is.available()];
            is.read(buf);
            return new JSONObject(new String(buf, "UTF-8"));
        } catch (Exception ignored) {
            return null;
        }
    }
}
