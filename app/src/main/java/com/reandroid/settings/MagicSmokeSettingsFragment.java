package com.reandroid.settings;

import android.os.Bundle;

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
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_MAGICSMOKE);

        // 设置预览
        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory((width, height) -> new MagicSmokeGL(requireContext(), width, height));
        }

        // 打开壁纸选择器
        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, MagicSmokeWallpaper.class);
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
}
