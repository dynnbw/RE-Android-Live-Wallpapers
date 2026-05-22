package com.reandroid.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.noisefield.NoiseFieldGL;
import com.reandroid.wallpaper.noisefield.NoiseFieldWallpaper;

public class NoiseFieldSettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_noisefield, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_NOISEFIELD);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory((width, height) -> new NoiseFieldGL(width, height, requireContext()));
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, NoiseFieldWallpaper.class);
                return true;
            });
        }
    }
}
