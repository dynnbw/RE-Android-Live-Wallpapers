package com.reandroid.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.reandroid.gles.GLESScene;

import com.reandroid.settings.PreviewPreference;

import org.json.JSONObject;

import java.io.InputStream;

/**
 * Generic settings fragment that builds its UI from a plugin's layout.json.
 * Writes to plugin_{id} SharedPreferences, read by WallpaperEngine via host.
 */
public class PluginSettingsFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String ARG_PLUGIN_ID = "plugin_id";
    private boolean mRebuilding;
    private PreviewPreference mPreview;
    private String mPreviewClass;

    public static PluginSettingsFragment newInstance(String pluginId) {
        PluginSettingsFragment f = new PluginSettingsFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PLUGIN_ID, pluginId);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        String pluginId = getArguments() != null
                ? getArguments().getString(ARG_PLUGIN_ID)
                : ProxyWallpaperService.getActivePlugin(requireContext());
        if (pluginId == null) return;

        Context ctx = requireContext();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(ctx);
        setPreferenceScreen(screen);

        String prefsName = "plugin_" + pluginId;
        getPreferenceManager().setSharedPreferencesName(prefsName);
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(this);

        // Read info.json for preview class
        String previewClass = null;
        try (InputStream is = ctx.getAssets().open(pluginId + "/info.json")) {
            byte[] buf = new byte[is.available()];
            is.read(buf);
            JSONObject info = new JSONObject(new String(buf, "UTF-8"));
            previewClass = info.optString("previewClass", null);
        } catch (Exception ignored) {}
        mPreviewClass = previewClass;

        // Live preview
        if (previewClass != null) {
            mPreview = new PreviewPreference(ctx, null);
            mPreview.setKey("pref_preview");
            mPreview.setTitle(com.reandroid.wallpaper.R.string.pref_live_preview);
            mPreview.setSceneFactory((w, h) -> createPreviewScene(mPreviewClass, w, h, ctx));
            screen.addPreference(mPreview);
        }

        // "Set as Wallpaper" button
        Preference applyPref = new Preference(ctx);
        applyPref.setTitle(com.reandroid.wallpaper.R.string.pref_open_wallpaper_picker);
        applyPref.setLayoutResource(com.reandroid.wallpaper.R.layout.preference_modern_item);
        applyPref.setOnPreferenceClickListener(pref -> {
            ProxyWallpaperService.applyPluginAndOpenPreview(ctx, pluginId);
            return true;
        });
        screen.addPreference(applyPref);

        // Dynamic preferences from layout.json
        JSONObject layout = PluginResources.loadLayout(ctx, pluginId);
        if (layout != null) {
            JSONObject language = PluginResources.loadLanguageForLocale(ctx, pluginId);
            DynamicPreferenceFactory.buildPreferences(ctx, prefs, layout,
                    screen::addPreference, language);
        }

        // Restore defaults button
        Preference resetPref = new Preference(ctx);
        resetPref.setTitle(com.reandroid.wallpaper.R.string.reset_current_settings_title);
        resetPref.setSummary(com.reandroid.wallpaper.R.string.reset_current_settings_summary);
        resetPref.setLayoutResource(com.reandroid.wallpaper.R.layout.preference_modern_item);
        resetPref.setOnPreferenceClickListener(pref -> {
            prefs.edit().clear().apply();
            // Recreate the preference screen
            getPreferenceScreen().removeAll();
            onCreatePreferences(null, null);
            return true;
        });
        screen.addPreference(resetPref);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (mPreview != null) {
            mPreview.refreshScene();
        }
    }

    private GLESScene createPreviewScene(String className, int w, int h, Context ctx) {
        try {
            Class<?> clz = Class.forName(className);
            GLESScene scene;
            try {
                scene = (GLESScene) clz.getConstructor(int.class, int.class, Context.class)
                        .newInstance(w, h, ctx);
            } catch (NoSuchMethodException e) {
                scene = (GLESScene) clz.getConstructor(Context.class, int.class, int.class)
                        .newInstance(ctx, w, h);
            }
            // Inject plugin prefs so the preview reads current settings
            String pluginId = getPluginId();
            if (pluginId != null) {
                SharedPreferences prefs = ctx.getSharedPreferences(
                        "plugin_" + pluginId, Context.MODE_PRIVATE);
                try {
                    java.lang.reflect.Method m = scene.getClass()
                            .getMethod("setPluginPrefs", SharedPreferences.class);
                    m.invoke(scene, prefs);
                } catch (Exception ignored) {}
            }
            return scene;
        } catch (Exception e) {
            return null;
        }
    }

    private String getPluginId() {
        return getArguments() != null ? getArguments().getString(ARG_PLUGIN_ID) : null;
    }
}
