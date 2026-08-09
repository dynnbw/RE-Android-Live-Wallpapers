package com.reandroid.plugin;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.reandroid.gles.GLESScene;

import org.json.JSONObject;

import java.util.Map;

/**
 * Generic settings fragment that builds its UI from a plugin's layout.json.
 * Writes to plugin_{id} SharedPreferences, read by WallpaperEngine via host.
 */
public class PluginSettingsFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    /**
     * 全屏预览宿主（由 PluginSettingsActivity 实现）。
     * 场景创建/刷新/获取均委托给宿主，Fragment 不再持有 GL 视图。
     */
    public interface PreviewHost {
        GLESScene createScene(int width, int height);

        void refreshPreview();

        GLESScene getScene();
    }

    private static final String ARG_PLUGIN_ID = "plugin_id";
    private static final String KEY_CUSTOM_BG_URI = "pref_custom_background_uri";
    private boolean mRebuilding;
    private PreviewHost mHost;
    private ActivityResultLauncher<String> mImagePickerLauncher;

    public static PluginSettingsFragment newInstance(String pluginId) {
        PluginSettingsFragment f = new PluginSettingsFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PLUGIN_ID, pluginId);
        f.setArguments(args);
        return f;
    }

    public void setPreviewHost(PreviewHost host) {
        mHost = host;
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

        // Read info.json for VK plugin flag and required permissions
        JSONObject info = PluginResources.loadInfo(ctx, pluginId);
        String pluginVk = info != null ? info.optString("pluginVk", null) : null;

        // Request required permissions from info.json
        requestPermissionsIfNeeded(info);

        // "Set as Wallpaper" button
        Preference applyPref = new Preference(ctx);
        applyPref.setTitle(com.reandroid.wallpaper.R.string.pref_open_wallpaper_picker);
        applyPref.setLayoutResource(com.reandroid.wallpaper.R.layout.preference_modern_item);
        applyPref.setOnPreferenceClickListener(pref -> {
            ProxyWallpaperService.setActivePlugin(ctx, pluginId);
            com.reandroid.settings.MiuiPermissionHelper.launchLivePreview(
                    PluginSettingsFragment.this, ProxyWallpaperService.class);
            return true;
        });
        screen.addPreference(applyPref);

        // Vulkan renderer toggle (if a VK plugin is available)
        if (pluginVk != null) {
            SwitchPreferenceCompat vkSwitch = new SwitchPreferenceCompat(ctx);
            vkSwitch.setKey("use_vulkan");
            vkSwitch.setTitle(com.reandroid.wallpaper.R.string.pref_use_vulkan_title);
            vkSwitch.setSummary(com.reandroid.wallpaper.R.string.pref_use_vulkan_summary);
            vkSwitch.setDefaultValue(false);
            vkSwitch.setLayoutResource(com.reandroid.wallpaper.R.layout.preference_modern_item);
            screen.addPreference(vkSwitch);
        }

        // Restore defaults button (placed before dynamic prefs as separator)
        Preference resetPref = new Preference(ctx);
        resetPref.setTitle(com.reandroid.wallpaper.R.string.reset_current_settings_title);
        resetPref.setSummary(com.reandroid.wallpaper.R.string.reset_current_settings_summary);
        resetPref.setLayoutResource(com.reandroid.wallpaper.R.layout.preference_modern_item);
        resetPref.setOnPreferenceClickListener(pref -> {
            prefs.edit().clear().apply();
            // Remove only dynamic preferences (everything after reset button)
            PreferenceScreen scr = getPreferenceScreen();
            while (scr.getPreferenceCount() > 0) {
                Preference last = scr.getPreference(scr.getPreferenceCount() - 1);
                if (last == resetPref) break;
                scr.removePreference(last);
            }
            // Rebuild dynamic section
            JSONObject layout = PluginResources.loadLayout(requireContext(), pluginId);
            if (layout != null) {
                JSONObject language = PluginResources.loadLanguageForLocale(requireContext(), pluginId);
                DynamicPreferenceFactory.buildPreferences(requireContext(), prefs, layout,
                        scr::addPreference, language);
                Map<String, String> buttonActions = DynamicPreferenceFactory.collectButtonActions(layout);
                for (Map.Entry<String, String> entry : buttonActions.entrySet()) {
                    Preference btn = scr.findPreference(entry.getKey());
                    if (btn != null) {
                        wireButtonAction(btn, entry.getValue(), pluginId, prefs);
                    }
                }
            }
            if (mHost != null) mHost.refreshPreview();
            return true;
        });
        screen.addPreference(resetPref);

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
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        GLESScene scene = mHost != null ? mHost.getScene() : null;
        if (scene != null) {
            try {
                java.lang.reflect.Method m = scene.getClass()
                        .getMethod("setPluginPrefs", SharedPreferences.class);
                m.invoke(scene, prefs);
            } catch (Exception e) {
                Log.w("PluginSettingsFragment", "Failed to inject prefs into preview, refreshing scene", e);
                if (mHost != null) mHost.refreshPreview();
            }
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
                    if (mHost != null) mHost.refreshPreview();
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
        } catch (Exception e) { Log.w("PluginSettingsFragment", "Failed to take persistable URI permission", e); }

        prefs.edit().putString(KEY_CUSTOM_BG_URI, uri.toString()).apply();
        Toast.makeText(ctx, com.reandroid.wallpaper.R.string.fireworks_custom_background_set_toast,
                Toast.LENGTH_SHORT).show();

        // Update button summary and refresh preview
        Preference btn = findPreference("pref_custom_background");
        if (btn != null) updateBackgroundButtonSummary(btn, prefs);
        if (mHost != null) mHost.refreshPreview();
    }

    private void updateBackgroundButtonSummary(Preference btn, SharedPreferences prefs) {
        String uri = prefs.getString(KEY_CUSTOM_BG_URI, null);
        if (uri != null && !uri.isEmpty()) {
            btn.setSummary(com.reandroid.wallpaper.R.string.fireworks_custom_background_set_toast);
        }
    }

    private void requestPermissionsIfNeeded(JSONObject info) {
        if (info == null) return;
        org.json.JSONArray perms = info.optJSONArray("permissions");
        if (perms == null || perms.length() == 0) return;

        java.util.ArrayList<String> needed = new java.util.ArrayList<>();
        for (int i = 0; i < perms.length(); i++) {
            String perm = perms.optString(i);
            String androidPerm = mapPermission(perm);
            if (androidPerm != null && ContextCompat.checkSelfPermission(requireContext(), androidPerm)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(androidPerm);
            }
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), 0);
        }
    }

    private static String mapPermission(String name) {
        switch (name) {
            case "RECORD_AUDIO": return Manifest.permission.RECORD_AUDIO;
            case "CAMERA": return Manifest.permission.CAMERA;
            case "ACCESS_FINE_LOCATION": return Manifest.permission.ACCESS_FINE_LOCATION;
            case "ACCESS_COARSE_LOCATION": return Manifest.permission.ACCESS_COARSE_LOCATION;
            default: return null;
        }
    }
}
