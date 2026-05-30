package com.reandroid.settings;

import android.app.WallpaperManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.fall.FallGL;
import com.reandroid.wallpaper.fall.FallWallpaper;

public class FallSettingsFragment extends PreferenceFragmentCompat {
    private Class<?> mPendingWallpaperClass = null;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        getPreferenceManager().setSharedPreferencesName("plugin_fall");
        setPreferencesFromResource(R.xml.prefs_fall, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_FALL);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory((width, height) -> new FallGL(requireContext(), width, height));
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                com.reandroid.plugin.ProxyWallpaperService.applyPluginAndOpenPreview(requireContext(), "fall");
                return true;
            });
        }
    }

}
