package com.reandroid.settings;

import android.Manifest;
import android.app.WallpaperManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.widget.Toast;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.grass.GrassGL;
import com.reandroid.wallpaper.grass.GrassWallpaper;

public class GrassSettingsFragment extends PreferenceFragmentCompat
    implements Preference.OnPreferenceChangeListener {
    private static final int REQ_LOCATION = 1001;
    private SwitchPreferenceCompat mAccurateSunPref;
    private SwitchPreferenceCompat mSunPref;
    private SwitchPreferenceCompat mMoonPref;
    private SwitchPreferenceCompat mProceduralSunPref;
    private SwitchPreferenceCompat mLegacyParticlesPref;
    private SwitchPreferenceCompat mDandelionPref;
    private SwitchPreferenceCompat mFireflyPref;
    private SeekBarPreference mDandelionCountPref;
    private SeekBarPreference mDandelionSpeedPref;
    private SeekBarPreference mFireflyCountPref;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_grass, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_GRASS);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory((width, height) -> new GrassGL(width, height));
        }

        mAccurateSunPref = findPreference("pref_grass_accurate_sun");
        mSunPref = findPreference("pref_grass_sun");
        mMoonPref = findPreference("pref_grass_moon");
        mProceduralSunPref = findPreference("pref_grass_procedural_sun");

        mLegacyParticlesPref = findPreference("pref_grass_legacy_particles");
        mDandelionPref = findPreference("pref_grass_dandelion");
        mFireflyPref = findPreference("pref_grass_firefly");
        mDandelionCountPref = findPreference("pref_grass_dandelion_count");
        mDandelionSpeedPref = findPreference("pref_grass_dandelion_speed");
        mFireflyCountPref = findPreference("pref_grass_firefly_count");

        if (mLegacyParticlesPref != null) {
            mLegacyParticlesPref.setOnPreferenceChangeListener(this);
        }
        if (mDandelionPref != null) {
            mDandelionPref.setOnPreferenceChangeListener(this);
        }
        if (mFireflyPref != null) {
            mFireflyPref.setOnPreferenceChangeListener(this);
        }
        if (mDandelionCountPref != null) {
            mDandelionCountPref.setOnPreferenceChangeListener(this);
        }
        if (mDandelionSpeedPref != null) {
            mDandelionSpeedPref.setOnPreferenceChangeListener(this);
        }
        if (mFireflyCountPref != null) {
            mFireflyCountPref.setOnPreferenceChangeListener(this);
        }

        // 保持原有太阳/月亮互斥逻辑
        if (mAccurateSunPref != null) {
            mAccurateSunPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enable = newValue instanceof Boolean && (Boolean) newValue;
                if (!enable) {
                    updateSunToggleState(false);
                    updateMoonToggleState(false);
                    updateProceduralSunToggleState(false);
                    return true;
                }
                if (getContext() == null) return false;
                boolean hasFine = ContextCompat.checkSelfPermission(
                        requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
                boolean hasCoarse = ContextCompat.checkSelfPermission(
                        requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
                if (hasFine || hasCoarse) {
                    updateSunToggleState(true);
                    updateMoonToggleState(true);
                    updateProceduralSunToggleState(true);
                    return true;
                }
                requestPermissions(new String[] {
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, REQ_LOCATION);
                return false;
            });
        }

        if (mSunPref != null) {
            mSunPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enableSun = newValue instanceof Boolean && (Boolean) newValue;
                boolean accurateSunEnabled = mAccurateSunPref != null && mAccurateSunPref.isChecked();
                if (enableSun && !accurateSunEnabled) {
                    mSunPref.setChecked(false);
                    return false;
                }
                updateProceduralSunToggleState(enableSun && accurateSunEnabled);
                return true;
            });
        }

        updateSunToggleState(mAccurateSunPref != null && mAccurateSunPref.isChecked());
        updateMoonToggleState(mAccurateSunPref != null && mAccurateSunPref.isChecked());
        updateProceduralSunToggleState(mSunPref != null && mSunPref.isChecked()
                && mAccurateSunPref != null && mAccurateSunPref.isChecked());

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, GrassWallpaper.class);
                return true;
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && mAccurateSunPref != null) {
            boolean granted = false;
            if (grantResults != null) {
                for (int result : grantResults) {
                    if (result == PackageManager.PERMISSION_GRANTED) {
                        granted = true;
                        break;
                    }
                }
            }
            mAccurateSunPref.setChecked(granted);
            updateSunToggleState(granted);
            updateMoonToggleState(granted);
        }
    }

    private void updateSunToggleState(boolean accurateSunEnabled) {
        if (mSunPref == null) return;
        if (!accurateSunEnabled) {
            mSunPref.setChecked(false);
            mSunPref.setEnabled(false);
        } else {
            mSunPref.setEnabled(true);
        }
    }

    private void updateMoonToggleState(boolean accurateSunEnabled) {
        if (mMoonPref == null) return;
        if (!accurateSunEnabled) {
            mMoonPref.setChecked(false);
            mMoonPref.setEnabled(false);
        } else {
            mMoonPref.setEnabled(true);
        }
    }

    private void updateProceduralSunToggleState(boolean sunEnabled) {
        if (mProceduralSunPref == null) return;
        mProceduralSunPref.setEnabled(sunEnabled);
    }

    private boolean isLegacyParticlesEnabled() {
        return mLegacyParticlesPref != null && mLegacyParticlesPref.isChecked();
    }

    private boolean isDandelionEnabled() {
        return mDandelionPref != null && mDandelionPref.isChecked();
    }

    private boolean isFireflyEnabled() {
        return mFireflyPref != null && mFireflyPref.isChecked();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();

        if ("pref_grass_legacy_particles".equals(key)) {
            boolean enableLegacy = newValue instanceof Boolean && (Boolean) newValue;
            if (enableLegacy && (isDandelionEnabled() || isFireflyEnabled())) {
                Toast.makeText(requireContext(), R.string.grass_disable_dandelion_firefly_first, Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }

        if ("pref_grass_dandelion".equals(key) || "pref_grass_firefly".equals(key)) {
            boolean enableParticle = newValue instanceof Boolean && (Boolean) newValue;
            if (enableParticle && isLegacyParticlesEnabled()) {
                Toast.makeText(requireContext(), R.string.grass_disable_legacy_particles_first, Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }

        if ("pref_grass_dandelion_count".equals(key) || "pref_grass_dandelion_speed".equals(key)) {
            if (isLegacyParticlesEnabled()) {
                Toast.makeText(requireContext(), R.string.grass_disable_legacy_particles_first, Toast.LENGTH_SHORT).show();
                return false;
            }
            if (!isDandelionEnabled()) {
                Toast.makeText(requireContext(), R.string.grass_enable_dandelion_first, Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }

        if ("pref_grass_firefly_count".equals(key)) {
            if (isLegacyParticlesEnabled()) {
                Toast.makeText(requireContext(), R.string.grass_disable_legacy_particles_first, Toast.LENGTH_SHORT).show();
                return false;
            }
            if (!isFireflyEnabled()) {
                Toast.makeText(requireContext(), R.string.grass_enable_firefly_first, Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }

        return true;
    }
}
