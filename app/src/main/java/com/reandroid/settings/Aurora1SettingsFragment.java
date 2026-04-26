package com.reandroid.settings;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.aurora1.Aurora1GL;
import com.reandroid.wallpaper.aurora1.Aurora1Wallpaper;
import com.reandroid.wallpaper.R;

public class Aurora1SettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_aurora1, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_AURORA1);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory(Aurora1GL::new);
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                launchLivePreview();
                return true;
            });
        }
    }

    private void launchLivePreview() {
        try {
            ComponentName componentName = new ComponentName(requireContext(), Aurora1Wallpaper.class);
            Intent intent = new Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            intent.putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, componentName);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.no_live_wallpaper_support, Toast.LENGTH_SHORT).show();
        }
    }
}