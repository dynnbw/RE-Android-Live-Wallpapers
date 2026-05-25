package com.reandroid.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.cube.CubeGL;
import com.reandroid.wallpaper.cube.CubeWallpaper;

public class CubeSettingsFragment extends PreferenceFragmentCompat implements Preference.OnPreferenceChangeListener {

    private PreviewPreference mPreview;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        getPreferenceManager().setSharedPreferencesName(CubeGL.PREFS_NAME);
        setPreferencesFromResource(R.xml.prefs_cube, rootKey);

        mPreview = findPreference("pref_preview");
        if (mPreview != null) {
            mPreview.setSceneFactory((width, height) -> new CubeGL(width, height, requireContext()));
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, CubeWallpaper.class);
                return true;
            });
        }

        ListPreference shapePref = findPreference("cube_shape");
        if (shapePref != null) {
            shapePref.setOnPreferenceChangeListener(this);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if ("cube_shape".equals(preference.getKey())) {
            Context context = requireContext();
            Context app = context.getApplicationContext();
            if (app == null) app = context;

            app.getSharedPreferences(CubeGL.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(CubeGL.KEY_SHAPE, (String) newValue)
                    .commit();

            if (mPreview != null && mPreview.getScene() instanceof CubeGL) {
                ((CubeGL) mPreview.getScene()).onSharedPreferenceChanged(
                        app.getSharedPreferences(CubeGL.PREFS_NAME, Context.MODE_PRIVATE),
                        CubeGL.KEY_SHAPE);
            }
            return true;
        }
        return false;
    }
}
