package com.reandroid.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.weatherwallpapers.windmill.WindmillGL;
import com.reandroid.wallpaper.weatherwallpapers.windmill.WindmillWallpaper;

public class WindmillSettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_windmill, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_WINDMILL);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory((width, height) -> new WindmillGL(width, height));
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, WindmillWallpaper.class);
                return true;
            });
        }
    }
}
