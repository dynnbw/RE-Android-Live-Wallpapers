package com.reandroid.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.bluesea.BlueSeaGL;
import com.reandroid.wallpaper.bluesea.BlueSeaWallpaper;

public class BlueSeaSettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_bluesea, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_BLUESEA);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory((width, height) -> new BlueSeaGL(width, height));
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, BlueSeaWallpaper.class);
                return true;
            });
        }
    }
}
