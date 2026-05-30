package com.reandroid.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.forest.ForestGL;
import com.reandroid.wallpaper.forest.ForestWallpaper;

public class ForestSettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        getPreferenceManager().setSharedPreferencesName("forest_prefs");
        setPreferencesFromResource(R.xml.prefs_forest, rootKey);

        Preference preview = findPreference("pref_preview");
        if (preview instanceof PreviewPreference) {
            ((PreviewPreference) preview).setSceneFactory((w, h) -> new ForestGL(w, h, requireContext()));
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, ForestWallpaper.class);
                return true;
            });
        }
    }
}
