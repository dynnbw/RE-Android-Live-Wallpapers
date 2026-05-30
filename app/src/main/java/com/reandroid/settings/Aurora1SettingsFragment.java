package com.reandroid.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.aurora1.Aurora1GL;
import com.reandroid.wallpaper.aurora1.Aurora1Wallpaper;

public class Aurora1SettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_aurora1, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_AURORA1);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory((w, h) -> new Aurora1GL(requireContext(), w, h));
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, Aurora1Wallpaper.class);
                return true;
            });
        }
    }
}