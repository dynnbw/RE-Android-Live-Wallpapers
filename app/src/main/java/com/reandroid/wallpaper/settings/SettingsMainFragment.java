package com.reandroid.wallpaper.settings;

import android.app.WallpaperManager;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;

public class SettingsMainFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_settings, rootKey);

        Preference openChooser = findPreference("pref_open_wallpaper_chooser");
        if (openChooser != null) {
            openChooser.setOnPreferenceClickListener(pref -> {
                openLiveWallpaperChooser();
                return true;
            });
        }
    }

    private void openLiveWallpaperChooser() {
        Intent intent = new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER);
        startActivity(intent);
    }
}
