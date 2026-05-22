package com.reandroid.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.aurora2.Aurora2GL;
import com.reandroid.wallpaper.aurora2.Aurora2Wallpaper;

public class Aurora2SettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_aurora2, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_AURORA2);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory(Aurora2GL::new);
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, Aurora2Wallpaper.class);
                return true;
            });
        }
    }
}
