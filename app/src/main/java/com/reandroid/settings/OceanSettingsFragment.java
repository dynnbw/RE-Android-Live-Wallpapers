package com.reandroid.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.weatherwallpapers.ocean.OceanWeatherGL;
import com.reandroid.wallpaper.weatherwallpapers.ocean.OceanWallpaper;

public class OceanSettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_ocean, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_OCEAN);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory((width, height) -> new OceanWeatherGL(requireContext(), width, height));
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, OceanWallpaper.class);
                return true;
            });
        }
    }
}
