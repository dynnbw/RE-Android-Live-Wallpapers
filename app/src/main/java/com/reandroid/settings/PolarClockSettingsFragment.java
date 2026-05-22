package com.reandroid.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.polarclock.PolarClockGL;
import com.reandroid.wallpaper.polarclock.PolarClockWallpaper;

/**
 * PolarClock Settings Fragment
 * Displays preferences for PolarClock wallpaper
 */
public class PolarClockSettingsFragment extends PreferenceFragmentCompat {
    private PreviewPreference previewPreference;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        getPreferenceManager().setSharedPreferencesName(PolarClockWallpaper.SHARED_PREFS_NAME);
        setPreferencesFromResource(R.xml.polar_clock_prefs, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_POLARCLOCK);

        previewPreference = findPreference("pref_preview");
        if (previewPreference != null) {
            previewPreference.setSceneFactory((width, height) -> new PolarClockGL(width, height));
        }


        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, PolarClockWallpaper.class);
                return true;
            });
        }
    }
}
