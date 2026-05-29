package com.reandroid.settings;

import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.phasebeam.PhaseBeamGL;
import com.reandroid.wallpaper.phasebeam.PhaseBeamWallpaper;

public class PhaseBeamSettingsFragment extends PreferenceFragmentCompat implements Preference.OnPreferenceChangeListener {
    private static final int SEEKBAR_MAX = 1000;

    private PreviewPreference mPreview;
    private SwitchPreferenceCompat mEnableRecolor;
    private SeekBarPreference mHue;
    private SeekBarPreference mSaturation;
    private SeekBarPreference mBrightness;
    private ListPreference mTheme;

    private boolean isRecolorEnabled() {
        return mEnableRecolor != null && mEnableRecolor.isChecked();
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        getPreferenceManager().setSharedPreferencesName(PhaseBeamGL.PREFS_NAME);
        setPreferencesFromResource(R.xml.prefs_phasebeam, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_PHASEBEAM);

        mPreview = findPreference("pref_preview");
        if (mPreview != null) {
            mPreview.setSceneFactory((width, height) -> new PhaseBeamGL(width, height, requireContext()));
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, PhaseBeamWallpaper.class);
                return true;
            });
        }

        mEnableRecolor = findPreference("phasebeam_recolor_enabled");
        mHue = findPreference("phasebeam_hue");
        mSaturation = findPreference("phasebeam_saturation");
        mBrightness = findPreference("phasebeam_brightness");

        if (mEnableRecolor != null) {
            mEnableRecolor.setOnPreferenceChangeListener(this);
        }
        if (mHue != null) mHue.setOnPreferenceChangeListener(this);
        if (mSaturation != null) mSaturation.setOnPreferenceChangeListener(this);
        if (mBrightness != null) mBrightness.setOnPreferenceChangeListener(this);
        mTheme = findPreference("theme");
        if (mTheme != null) mTheme.setOnPreferenceChangeListener(this);

        syncFromPrefs();
    }

    private void syncFromPrefs() {
        Context context = requireContext();
        Context app = context.getApplicationContext();
        if (app == null) app = context;

        boolean enabled = app.getSharedPreferences(PhaseBeamGL.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PhaseBeamGL.KEY_ENABLED, getResources().getBoolean(R.bool.recolor_enabled));
        float hue = app.getSharedPreferences(PhaseBeamGL.PREFS_NAME, Context.MODE_PRIVATE)
                .getFloat(PhaseBeamGL.KEY_HUE, Float.parseFloat(getString(R.string.hue)));
        float saturation = app.getSharedPreferences(PhaseBeamGL.PREFS_NAME, Context.MODE_PRIVATE)
                .getFloat(PhaseBeamGL.KEY_SATURATION, Float.parseFloat(getString(R.string.saturation)));
        float brightness = app.getSharedPreferences(PhaseBeamGL.PREFS_NAME, Context.MODE_PRIVATE)
                .getFloat(PhaseBeamGL.KEY_BRIGHTNESS, Float.parseFloat(getString(R.string.brightness)));

        if (mEnableRecolor != null) mEnableRecolor.setChecked(enabled);
        if (mHue != null) mHue.setValue(floatToProgress(hue, 0.0f, 1.0f));
        if (mSaturation != null) mSaturation.setValue(floatToProgress(saturation, 0.0f, 1.0f));
        if (mBrightness != null) mBrightness.setValue(floatToProgress(brightness, 0.5f, 1.5f));

        updatePreview();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        Context context = requireContext();
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        String key = preference.getKey();

        if (("phasebeam_hue".equals(key)
                || "phasebeam_saturation".equals(key)
                || "phasebeam_brightness".equals(key))
                && !isRecolorEnabled()) {
            Toast.makeText(requireContext(), R.string.phasebeam_enable_recolor_first, Toast.LENGTH_SHORT).show();
            return false;
        }

        if ("phasebeam_recolor_enabled".equals(key)) {
            boolean enabled = (Boolean) newValue;
            app.getSharedPreferences(PhaseBeamGL.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PhaseBeamGL.KEY_ENABLED, enabled)
                    .commit();
            return true;
        } else if ("phasebeam_hue".equals(key)) {
            float value = progressToFloat((Integer) newValue, 0.0f, 1.0f);
            app.getSharedPreferences(PhaseBeamGL.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putFloat(PhaseBeamGL.KEY_HUE, value)
                    .commit();
            return true;
        } else if ("phasebeam_saturation".equals(key)) {
            float value = progressToFloat((Integer) newValue, 0.0f, 1.0f);
            app.getSharedPreferences(PhaseBeamGL.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putFloat(PhaseBeamGL.KEY_SATURATION, value)
                    .commit();
            return true;
        } else if ("phasebeam_brightness".equals(key)) {
            float value = progressToFloat((Integer) newValue, 0.5f, 1.5f);
            app.getSharedPreferences(PhaseBeamGL.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putFloat(PhaseBeamGL.KEY_BRIGHTNESS, value)
                    .commit();
            return true;
        }

        if ("theme".equals(key)) {
            app.getSharedPreferences(PhaseBeamGL.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PhaseBeamGL.KEY_THEME, (String) newValue)
                    .commit();
            if (mPreview != null && mPreview.getScene() instanceof PhaseBeamGL) {
                ((PhaseBeamGL) mPreview.getScene()).onSharedPreferenceChanged(
                        app.getSharedPreferences(PhaseBeamGL.PREFS_NAME, Context.MODE_PRIVATE),
                        PhaseBeamGL.KEY_THEME);
            }
            return true;
        }
        return false;
    }

    private void updatePreview() {
        if (mPreview != null && mPreview.getScene() instanceof PhaseBeamGL) {
            ((PhaseBeamGL) mPreview.getScene()).reloadPreferences();
        }
    }

    private int floatToProgress(float value, float min, float max) {
        float clamped = Math.max(min, Math.min(max, value));
        return Math.round((clamped - min) * SEEKBAR_MAX / (max - min));
    }

    private float progressToFloat(int progress, float min, float max) {
        return min + (max - min) * progress / (float) SEEKBAR_MAX;
    }
}
