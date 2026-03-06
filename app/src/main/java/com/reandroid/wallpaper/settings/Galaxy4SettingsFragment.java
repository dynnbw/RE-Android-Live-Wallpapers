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
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.galaxy4.Galaxy4GL;
import com.reandroid.wallpaper.galaxy4.Galaxy4Wallpaper;

public class Galaxy4SettingsFragment extends PreferenceFragmentCompat
        implements Preference.OnPreferenceChangeListener {
    private PreviewPreference previewPreference;
    
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_galaxy4, rootKey);

        previewPreference = findPreference("pref_preview");
        if (previewPreference != null) {
            previewPreference.setSceneFactory((width, height) -> new Galaxy4GL(width, height, requireContext()));
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                launchLivePreview(Galaxy4Wallpaper.class);
                return true;
            });
        }
        
        // Setup particle count preference listeners
        SeekBarPreference bgStarPref = findPreference("galaxy4_bg_star_count");
        if (bgStarPref != null) {
            bgStarPref.setOnPreferenceChangeListener(this);
        }
        
        SeekBarPreference cloudPref = findPreference("galaxy4_space_cloud_count");
        if (cloudPref != null) {
            cloudPref.setOnPreferenceChangeListener(this);
        }
    }
    
    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (previewPreference != null && previewPreference.getScene() instanceof Galaxy4GL) {
            Galaxy4GL scene = (Galaxy4GL) previewPreference.getScene();
            if ("galaxy4_bg_star_count".equals(preference.getKey())) {
                scene.setBgStarCount((Integer) newValue);
                return true;
            } else if ("galaxy4_space_cloud_count".equals(preference.getKey())) {
                scene.setSpaceCloudCount((Integer) newValue);
                return true;
            }
        }
        return false;
    }

    private void launchLivePreview(Class<?> wallpaperClass) {
        // 检测MIUI系统，仅第一次提示用户授予权限
        if (isMIUI() && !hasShownMIUIPermissionDialog()) {
            showMIUIPermissionDialog(wallpaperClass);
            return;
        }
        
        try {
            Intent intent = new Intent();
            ComponentName componentName = new ComponentName(requireContext(), wallpaperClass);
            intent.setAction("android.service.wallpaper.CHANGE_LIVE_WALLPAPER");
            intent.putExtra("android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT", componentName);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), "不支持壁纸预览", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isMIUI() {
        return !getSystemProperty("ro.miui.ui.version.name", "").isEmpty()
                || !getSystemProperty("ro.miui.ui.version.code", "").isEmpty();
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
        return requireContext().getSharedPreferences("wallpaper_prefs", 0)
                .getBoolean("miui_permission_dialog_shown", false);
    }

    private void setMIUIPermissionDialogShown() {
        requireContext().getSharedPreferences("wallpaper_prefs", 0)
                .edit()
                .putBoolean("miui_permission_dialog_shown", true)
                .apply();
    }

    private void showMIUIPermissionDialog(Class<?> wallpaperClass) {
        new AlertDialog.Builder(requireContext())
                .setTitle("需要授予权限")
                .setMessage("小米系统需要手动授予\"动态壁纸服务\"权限，否则无法正常打开壁纸预览。\n\n点击确定后，请在权限管理页面找到\"动态壁纸服务\"并开启。")
                .setPositiveButton("去设置", (dialog, which) -> {
                    setMIUIPermissionDialogShown();
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), "无法打开设置页面", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("继续", (dialog, which) -> {
                    setMIUIPermissionDialogShown();
                    launchLivePreview(wallpaperClass);
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
