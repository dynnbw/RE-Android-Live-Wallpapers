package com.reandroid.settings;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
        Context ctx = requireContext();
        getPreferenceManager().setSharedPreferencesName("forest_prefs");
        setPreferencesFromResource(R.xml.prefs_forest, rootKey);

        Preference preview = findPreference("pref_preview");
        if (preview instanceof PreviewPreference) {
            ((PreviewPreference) preview).setSceneFactory((w, h) -> new ForestGL(w, h));
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                try {
                    Intent intent = new Intent("android.service.wallpaper.CHANGE_LIVE_WALLPAPER");
                    intent.putExtra("android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT",
                            new ComponentName(ctx, ForestWallpaper.class));
                    startActivity(intent);
                } catch (Exception ignored) {
                }
                return true;
            });
        }
    }
}
