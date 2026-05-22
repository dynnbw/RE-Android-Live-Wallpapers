package com.reandroid.settings;

import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.musicvis.MusicVisWallpaper3;
import com.reandroid.wallpaper.musicvis.MusicVisWaveScene;

public class MusicVis3SettingsFragment extends PreferenceFragmentCompat
        implements Preference.OnPreferenceChangeListener {

    private SwitchPreferenceCompat mRecolorPref;
    private SeekBarPreference mHuePref, mSaturationPref, mBrightnessPref;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        getPreferenceManager().setSharedPreferencesName("musicvis3_prefs");
        setPreferencesFromResource(R.xml.prefs_musicvis3, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_MUSICVIS3);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory((w, h) -> new MusicVisWaveScene(w, h, requireContext(),
                    MusicVisWaveScene.Mode.FFT, R.drawable.musicvis_ice));
        }

        mRecolorPref = findPreference("musicvis_recolor");
        mHuePref = findPreference("musicvis_hue");
        mSaturationPref = findPreference("musicvis_saturation");
        mBrightnessPref = findPreference("musicvis_brightness");
        if (mHuePref != null) mHuePref.setOnPreferenceChangeListener(this);
        if (mSaturationPref != null) mSaturationPref.setOnPreferenceChangeListener(this);
        if (mBrightnessPref != null) mBrightnessPref.setOnPreferenceChangeListener(this);

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, MusicVisWallpaper3.class);
                return true;
            });
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if ((preference == mHuePref || preference == mSaturationPref || preference == mBrightnessPref)
                && !isRecolorEnabled()) {
            Toast.makeText(requireContext(), R.string.musicvis_enable_recolor_first, Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private boolean isRecolorEnabled() {
        return mRecolorPref != null && mRecolorPref.isChecked();
    }

}
