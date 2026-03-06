package com.reandroid.wallpaper.settings;

import android.Manifest;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.grass.GrassGL;
import com.reandroid.wallpaper.grass.GrassWallpaper;

public class GrassSettingsFragment extends PreferenceFragmentCompat {
    private static final int REQ_LOCATION = 1001;
    private SwitchPreferenceCompat mAccurateSunPref;
    private SwitchPreferenceCompat mSunPref;
    private SwitchPreferenceCompat mMoonPref;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_grass, rootKey);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory((width, height) -> new GrassGL(width, height));
        }

        mAccurateSunPref = findPreference("pref_grass_accurate_sun");
        mSunPref = findPreference("pref_grass_sun");
        mMoonPref = findPreference("pref_grass_moon");

        // 新增互斥逻辑
        SwitchPreferenceCompat legacyParticlesPref = findPreference("pref_grass_legacy_particles");
        SwitchPreferenceCompat dandelionPref = findPreference("pref_grass_dandelion");
        SwitchPreferenceCompat fireflyPref = findPreference("pref_grass_firefly");

        if (legacyParticlesPref != null) {
            legacyParticlesPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enabled = newValue instanceof Boolean && (Boolean) newValue;
                if (dandelionPref != null) dandelionPref.setEnabled(!enabled);
                if (fireflyPref != null) fireflyPref.setEnabled(!enabled);
                return true;
            });
        }
        if (dandelionPref != null) {
            dandelionPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enabled = newValue instanceof Boolean && (Boolean) newValue;
                if (legacyParticlesPref != null) legacyParticlesPref.setEnabled(!enabled);
                return true;
            });
        }
        if (fireflyPref != null) {
            fireflyPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enabled = newValue instanceof Boolean && (Boolean) newValue;
                if (legacyParticlesPref != null) legacyParticlesPref.setEnabled(!enabled);
                return true;
            });
        }

        // 保持原有太阳/月亮互斥逻辑
        if (mAccurateSunPref != null) {
            mAccurateSunPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enable = newValue instanceof Boolean && (Boolean) newValue;
                if (!enable) {
                    updateSunToggleState(false);
                    updateMoonToggleState(false);
                    return true;
                }
                if (getContext() == null) return false;
                boolean hasFine = ContextCompat.checkSelfPermission(
                        requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
                boolean hasCoarse = ContextCompat.checkSelfPermission(
                        requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
                if (hasFine || hasCoarse) {
                    updateSunToggleState(true);
                    updateMoonToggleState(true);
                    return true;
                }
                requestPermissions(new String[] {
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, REQ_LOCATION);
                return false;
            });
        }

        if (mSunPref != null) {
            mSunPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enableSun = newValue instanceof Boolean && (Boolean) newValue;
                boolean accurateSunEnabled = mAccurateSunPref != null && mAccurateSunPref.isChecked();
                if (enableSun && !accurateSunEnabled) {
                    mSunPref.setChecked(false);
                    return false;
                }
                return true;
            });
        }

        updateSunToggleState(mAccurateSunPref != null && mAccurateSunPref.isChecked());
        updateMoonToggleState(mAccurateSunPref != null && mAccurateSunPref.isChecked());

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                launchLivePreview(GrassWallpaper.class);
                return true;
            });
        }
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && mAccurateSunPref != null) {
            boolean granted = false;
            if (grantResults != null) {
                for (int result : grantResults) {
                    if (result == PackageManager.PERMISSION_GRANTED) {
                        granted = true;
                        break;
                    }
                }
            }
            mAccurateSunPref.setChecked(granted);
            updateSunToggleState(granted);
            updateMoonToggleState(granted);
        }
    }

    private void updateSunToggleState(boolean accurateSunEnabled) {
        if (mSunPref == null) return;
        if (!accurateSunEnabled) {
            mSunPref.setChecked(false);
            mSunPref.setEnabled(false);
        } else {
            mSunPref.setEnabled(true);
        }
    }

    private void updateMoonToggleState(boolean accurateSunEnabled) {
        if (mMoonPref == null) return;
        if (!accurateSunEnabled) {
            mMoonPref.setChecked(false);
            mMoonPref.setEnabled(false);
        } else {
            mMoonPref.setEnabled(true);
        }
    }
}
