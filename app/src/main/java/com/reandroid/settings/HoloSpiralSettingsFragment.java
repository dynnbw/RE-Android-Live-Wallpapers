package com.reandroid.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.holospiral.HoloSpiralGL;
import com.reandroid.wallpaper.holospiral.HoloSpiralWallpaper;

public class HoloSpiralSettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_holospiral, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_HOLOSPIRAL);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory((width, height) -> new HoloSpiralGL(requireContext(), width, height));
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, HoloSpiralWallpaper.class);
                return true;
            });
        }
    }
}
