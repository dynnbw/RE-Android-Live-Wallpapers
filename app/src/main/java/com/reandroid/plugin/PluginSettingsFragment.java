package com.reandroid.plugin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.reandroid.gles.GLESScene;

import com.reandroid.settings.PreviewPreference;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.Map;

/**
 * Generic settings fragment that builds its UI from a plugin's layout.json.
 * Writes to plugin_{id} SharedPreferences, read by WallpaperEngine via host.
 */
public class PluginSettingsFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String ARG_PLUGIN_ID = "plugin_id";
    private static final String KEY_CUSTOM_BG_URI = "pref_custom_background_uri";
    private boolean mRebuilding;
    private PreviewPreference mPreview;
    private String mPreviewClass;
    private ActivityResultLauncher<String> mImagePickerLauncher;

    public static PluginSettingsFragment newInstance(String pluginId) {
        PluginSettingsFragment f = new PluginSettingsFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PLUGIN_ID, pluginId);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mImagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                this::onImagePicked);
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

        // Use plugin_{id} prefs — the same file that the engine's host exposes
        String prefsName = "plugin_" + pluginId;
        getPreferenceManager().setSharedPreferencesName(prefsName);
        SharedPreferences prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(this);

        // Read info.json for preview class and optional VK plugin
        String previewClass = null;
        String pluginVk = null;
        try (InputStream is = ctx.getAssets().open(pluginId + "/info.json")) {
            byte[] buf = new byte[is.available()];
            is.read(buf);
            JSONObject info = new JSONObject(new String(buf, "UTF-8"));
            previewClass = info.optString("previewClass", null);
            pluginVk = info.optString("pluginVk", null);
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

        // Vulkan renderer toggle (if a VK plugin is available)
        if (pluginVk != null) {
            SwitchPreferenceCompat vkSwitch = new SwitchPreferenceCompat(ctx);
            vkSwitch.setKey("use_vulkan");
            vkSwitch.setTitle("Vulkan Renderer");
            vkSwitch.setSummary("Use Vulkan for better performance (reapply wallpaper to take effect)");
            vkSwitch.setDefaultValue(false);
            vkSwitch.setLayoutResource(com.reandroid.wallpaper.R.layout.preference_modern_item);
            screen.addPreference(vkSwitch);
        }

        // Dynamic preferences from layout.json
        JSONObject layout = PluginResources.loadLayout(ctx, pluginId);
        if (layout != null) {
            JSONObject language = PluginResources.loadLanguageForLocale(ctx, pluginId);
            DynamicPreferenceFactory.buildPreferences(ctx, prefs, layout,
                    screen::addPreference, language);

            // Wire up button-type preferences (custom background / reset background)
            Map<String, String> buttonActions = DynamicPreferenceFactory.collectButtonActions(layout);
            for (Map.Entry<String, String> entry : buttonActions.entrySet()) {
                Preference btn = screen.findPreference(entry.getKey());
                if (btn != null) {
                    wireButtonAction(btn, entry.getValue(), pluginId, prefs);
                }
            }
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
            GLESScene scene = null;

            // Try constructor patterns in order: (int,int,Context), (Context,int,int), (int,int)
            try {
                scene = (GLESScene) clz.getConstructor(int.class, int.class, Context.class)
                        .newInstance(w, h, ctx);
            } catch (NoSuchMethodException e1) {
                try {
                    scene = (GLESScene) clz.getConstructor(Context.class, int.class, int.class)
                            .newInstance(ctx, w, h);
                } catch (NoSuchMethodException e2) {
                    scene = (GLESScene) clz.getConstructor(int.class, int.class)
                            .newInstance(w, h);
                }
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

    private void wireButtonAction(Preference btn, String action, String pluginId,
                                   SharedPreferences prefs) {
        switch (action) {
            case "pickBackground":
                btn.setOnPreferenceClickListener(pref -> {
                    mImagePickerLauncher.launch("image/*");
                    return true;
                });
                updateBackgroundButtonSummary(btn, prefs);
                break;
            case "resetBackground":
                btn.setOnPreferenceClickListener(pref -> {
                    prefs.edit().remove(KEY_CUSTOM_BG_URI).apply();
                    Toast.makeText(requireContext(),
                            com.reandroid.wallpaper.R.string.fireworks_background_reset_toast,
                            Toast.LENGTH_SHORT).show();
                    updateBackgroundButtonSummary(btn, prefs);
                    if (mPreview != null) mPreview.refreshScene();
                    return true;
                });
                updateBackgroundButtonSummary(btn, prefs);
                break;
        }
    }

    private void onImagePicked(Uri uri) {
        if (uri == null) return;
        String pluginId = getPluginId();
        if (pluginId == null) return;
        Context ctx = requireContext();
        SharedPreferences prefs = ctx.getSharedPreferences("plugin_" + pluginId, Context.MODE_PRIVATE);

        // Take persistable permission so URI survives reboots
        try {
            ctx.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}

        prefs.edit().putString(KEY_CUSTOM_BG_URI, uri.toString()).apply();
        Toast.makeText(ctx, com.reandroid.wallpaper.R.string.fireworks_custom_background_set_toast,
                Toast.LENGTH_SHORT).show();

        // Update button summary and refresh preview
        Preference btn = findPreference("pref_custom_background");
        if (btn != null) updateBackgroundButtonSummary(btn, prefs);
        if (mPreview != null) mPreview.refreshScene();
    }

    private void updateBackgroundButtonSummary(Preference btn, SharedPreferences prefs) {
        String uri = prefs.getString(KEY_CUSTOM_BG_URI, null);
        if (uri != null && !uri.isEmpty()) {
            btn.setSummary(com.reandroid.wallpaper.R.string.fireworks_custom_background_set_toast);
        }
    }
}
