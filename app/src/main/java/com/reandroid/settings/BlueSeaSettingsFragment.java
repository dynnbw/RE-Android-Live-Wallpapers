package com.reandroid.settings;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

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
                launchLivePreview();
                return true;
            });
        }
    }

    private void launchLivePreview() {
        try {
            ComponentName componentName = new ComponentName(requireContext(), BlueSeaWallpaper.class);
            Intent intent = new Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            intent.putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, componentName);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.no_live_wallpaper_support, Toast.LENGTH_SHORT).show();
        }
    }
}
