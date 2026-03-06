package com.reandroid.wallpaper.settings;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.magicsmoke.MagicSmokeGL;
import com.reandroid.wallpaper.magicsmoke.MagicSmokeWallpaper;

/**
 * 魔法烟雾壁纸设置Fragment
 * 提供预设选择、预览等功能
 */
public class MagicSmokeSettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        // 设置使用magicsmoke作为SharedPreferences名称 (与原版一致)
        getPreferenceManager().setSharedPreferencesName("magicsmoke");
        setPreferencesFromResource(R.xml.prefs_magicsmoke, rootKey);

        // 设置预览
        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory((width, height) -> new MagicSmokeGL(requireContext(), width, height));
        }

        // 打开壁纸选择器
        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                launchLivePreview(MagicSmokeWallpaper.class);
                return true;
            });
        }

        // 预设选择器 - 设置摘要
        ListPreference presetPref = findPreference("preset");
        if (presetPref != null) {
            updatePresetSummary(presetPref);
            presetPref.setOnPreferenceChangeListener((preference, newValue) -> {
                updatePresetSummary((ListPreference) preference);
                return true;
            });
        }
    }

    private void updatePresetSummary(ListPreference preference) {
        CharSequence entry = preference.getEntry();
        if (entry != null) {
            preference.setSummary(entry);
        }
    }

    private void launchLivePreview(Class<?> wallpaperClass) {
        if (isMIUI()) {
            if (!hasShownMIUIPermissionDialog()) {
                showMIUIPermissionDialog(wallpaperClass);
                return;
            }
        }
        try {
            ComponentName componentName = new ComponentName(requireContext(), wallpaperClass);
            Intent intent = new Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            intent.putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, componentName);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.no_live_wallpaper_support, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isMIUI() {
        String manufacturer = android.os.Build.MANUFACTURER;
        String miuiVersion = getSystemProperty("ro.miui.ui.version.name", "");
        return "Xiaomi".equalsIgnoreCase(manufacturer) || !miuiVersion.isEmpty();
    }

    private String getSystemProperty(String key, String defaultValue) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean hasShownMIUIPermissionDialog() {
        return requireContext().getSharedPreferences("app_prefs", 0)
                .getBoolean("miui_permission_dialog_shown", false);
    }

    private void setMIUIPermissionDialogShown() {
        requireContext().getSharedPreferences("app_prefs", 0)
                .edit()
                .putBoolean("miui_permission_dialog_shown", true)
                .apply();
    }

    private void showMIUIPermissionDialog(Class<?> wallpaperClass) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.miui_permission_title)
                .setMessage(R.string.miui_permission_message)
                .setPositiveButton(R.string.miui_permission_go_settings, (dialog, which) -> {
                    setMIUIPermissionDialogShown();
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), R.string.miui_permission_open_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.miui_permission_continue, (dialog, which) -> {
                    setMIUIPermissionDialogShown();
                    launchLivePreview(wallpaperClass);
                })
                .show();
    }
}
