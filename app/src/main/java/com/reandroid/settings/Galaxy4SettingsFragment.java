package com.reandroid.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.galaxy4.Galaxy4GL;
import com.reandroid.wallpaper.galaxy4.Galaxy4Wallpaper;

public class Galaxy4SettingsFragment extends PreferenceFragmentCompat
        implements Preference.OnPreferenceChangeListener {
    private PreviewPreference previewPreference;
    
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_galaxy4, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_GALAXY4);

        previewPreference = findPreference("pref_preview");
        if (previewPreference != null) {
            previewPreference.setSceneFactory((width, height) -> new Galaxy4GL(width, height, requireContext()));
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, Galaxy4Wallpaper.class);
                return true;
            });
        }
        
        // Setup particle count preference listeners
        SeekBarPreference bgStarPref = findPreference("galaxy4_bg_star_count");
        if (bgStarPref != null) {
            bgStarPref.setOnPreferenceChangeListener(this);
        }
        
        SeekBarPreference cloudPref = findPreference("galaxy4_space_cloud_count");
        if (cloudPref != null) {
            cloudPref.setOnPreferenceChangeListener(this);
        }
    }
    
    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (previewPreference != null && previewPreference.getScene() instanceof Galaxy4GL) {
            Galaxy4GL scene = (Galaxy4GL) previewPreference.getScene();
            if ("galaxy4_bg_star_count".equals(preference.getKey())) {
                scene.setBgStarCount((Integer) newValue);
                return true;
            } else if ("galaxy4_space_cloud_count".equals(preference.getKey())) {
                scene.setSpaceCloudCount((Integer) newValue);
                return true;
            }
        }
        return false;
    }

}
