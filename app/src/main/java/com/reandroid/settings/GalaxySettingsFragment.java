package com.reandroid.settings;

import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.galaxy.GalaxyGL;
import com.reandroid.wallpaper.galaxy.GalaxyWallpaper;

public class GalaxySettingsFragment extends PreferenceFragmentCompat 
    implements Preference.OnPreferenceChangeListener {

    private PreviewPreference previewPreference;

    private boolean isPreciseShapeKey(String key) {
        return "galaxy_arm_count".equals(key)
                || "galaxy_arm_offset".equals(key)
                || "galaxy_pitch_angle_deg".equals(key)
                || "galaxy_inner_scatter".equals(key)
                || "galaxy_outer_scatter".equals(key)
                || "galaxy_turbulence".equals(key)
                || "galaxy_forbidden_radius".equals(key)
                || "galaxy_ellipse_ratio".equals(key)
                || "galaxy_ellipse_twist".equals(key);
    }

    private boolean isPreciseCalcEnabled() {
        SwitchPreferenceCompat preciseCalcPref = findPreference("galaxy_precise_calc");
        return preciseCalcPref != null && preciseCalcPref.isChecked();
    }
    
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_galaxy, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_GALAXY);

        previewPreference = findPreference("pref_preview");
        if (previewPreference != null) {
            previewPreference.setSceneFactory((width, height) -> new GalaxyGL(width, height, requireContext()));
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, GalaxyWallpaper.class);
                return true;
            });
        }
        
        // Setup particle count preference listener
        SeekBarPreference particleCountPref = findPreference("galaxy_particle_count");
        if (particleCountPref != null) {
            particleCountPref.setOnPreferenceChangeListener(this);
        }

        SeekBarPreference particleAlphaPref = findPreference("galaxy_particle_alpha");
        if (particleAlphaPref != null) {
            particleAlphaPref.setOnPreferenceChangeListener(this);
        }

        SwitchPreferenceCompat preciseCalcPref = findPreference("galaxy_precise_calc");
        if (preciseCalcPref != null) {
            preciseCalcPref.setOnPreferenceChangeListener(this);
        }

        SeekBarPreference armCountPref = findPreference("galaxy_arm_count");
        if (armCountPref != null) armCountPref.setOnPreferenceChangeListener(this);
        SeekBarPreference armOffsetPref = findPreference("galaxy_arm_offset");
        if (armOffsetPref != null) armOffsetPref.setOnPreferenceChangeListener(this);
        SeekBarPreference pitchAnglePref = findPreference("galaxy_pitch_angle_deg");
        if (pitchAnglePref != null) pitchAnglePref.setOnPreferenceChangeListener(this);
        SeekBarPreference innerScatterPref = findPreference("galaxy_inner_scatter");
        if (innerScatterPref != null) innerScatterPref.setOnPreferenceChangeListener(this);
        SeekBarPreference outerScatterPref = findPreference("galaxy_outer_scatter");
        if (outerScatterPref != null) outerScatterPref.setOnPreferenceChangeListener(this);
        SeekBarPreference turbulencePref = findPreference("galaxy_turbulence");
        if (turbulencePref != null) turbulencePref.setOnPreferenceChangeListener(this);
        SeekBarPreference forbiddenRadiusPref = findPreference("galaxy_forbidden_radius");
        if (forbiddenRadiusPref != null) forbiddenRadiusPref.setOnPreferenceChangeListener(this);
        SeekBarPreference ellipseRatioPref = findPreference("galaxy_ellipse_ratio");
        if (ellipseRatioPref != null) ellipseRatioPref.setOnPreferenceChangeListener(this);
        SeekBarPreference ellipseTwistPref = findPreference("galaxy_ellipse_twist");
        if (ellipseTwistPref != null) ellipseTwistPref.setOnPreferenceChangeListener(this);
    }
    
    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        if (isPreciseShapeKey(key) && !isPreciseCalcEnabled()) {
            Toast.makeText(requireContext(), R.string.galaxy_enable_precise_calc_first, Toast.LENGTH_SHORT).show();
            return false;
        }

        if ("galaxy_particle_count".equals(preference.getKey())) {
            // Update preview if available
            if (previewPreference != null && previewPreference.getScene() instanceof GalaxyGL) {
                GalaxyGL scene = (GalaxyGL) previewPreference.getScene();
                scene.setParticleCount((Integer) newValue);
            }
            return true;
        } else if ("galaxy_particle_alpha".equals(preference.getKey())) {
            if (previewPreference != null && previewPreference.getScene() instanceof GalaxyGL) {
                GalaxyGL scene = (GalaxyGL) previewPreference.getScene();
                scene.setParticleAlphaPercent((Integer) newValue);
            }
            return true;
        } else if ("galaxy_precise_calc".equals(preference.getKey())) {
            if (previewPreference != null && previewPreference.getScene() instanceof GalaxyGL) {
                GalaxyGL scene = (GalaxyGL) previewPreference.getScene();
                scene.setPreciseCalculation((Boolean) newValue);
            }
            return true;
        } else if ("galaxy_arm_count".equals(preference.getKey())) {
            if (previewPreference != null && previewPreference.getScene() instanceof GalaxyGL) {
                ((GalaxyGL) previewPreference.getScene()).setArmCount((Integer) newValue);
            }
            return true;
        } else if ("galaxy_arm_offset".equals(preference.getKey())) {
            if (previewPreference != null && previewPreference.getScene() instanceof GalaxyGL) {
                ((GalaxyGL) previewPreference.getScene()).setArmOffset(((Integer) newValue) / 1000.0f);
            }
            return true;
        } else if ("galaxy_pitch_angle_deg".equals(preference.getKey())) {
            if (previewPreference != null && previewPreference.getScene() instanceof GalaxyGL) {
                ((GalaxyGL) previewPreference.getScene()).setPitchAngleDeg(((Integer) newValue) / 10.0f);
            }
            return true;
        } else if ("galaxy_inner_scatter".equals(preference.getKey())) {
            if (previewPreference != null && previewPreference.getScene() instanceof GalaxyGL) {
                ((GalaxyGL) previewPreference.getScene()).setInnerScatter(((Integer) newValue) / 100.0f);
            }
            return true;
        } else if ("galaxy_outer_scatter".equals(preference.getKey())) {
            if (previewPreference != null && previewPreference.getScene() instanceof GalaxyGL) {
                ((GalaxyGL) previewPreference.getScene()).setOuterScatter(((Integer) newValue) / 100.0f);
            }
            return true;
        } else if ("galaxy_turbulence".equals(preference.getKey())) {
            if (previewPreference != null && previewPreference.getScene() instanceof GalaxyGL) {
                ((GalaxyGL) previewPreference.getScene()).setTurbulence(((Integer) newValue) / 100.0f);
            }
            return true;
        } else if ("galaxy_forbidden_radius".equals(preference.getKey())) {
            if (previewPreference != null && previewPreference.getScene() instanceof GalaxyGL) {
                ((GalaxyGL) previewPreference.getScene()).setForbiddenRadius(((Integer) newValue) / 100.0f);
            }
            return true;
        } else if ("galaxy_ellipse_ratio".equals(preference.getKey())) {
            if (previewPreference != null && previewPreference.getScene() instanceof GalaxyGL) {
                ((GalaxyGL) previewPreference.getScene()).setEllipseRatio(((Integer) newValue) / 1000.0f);
            }
            return true;
        } else if ("galaxy_ellipse_twist".equals(preference.getKey())) {
            if (previewPreference != null && previewPreference.getScene() instanceof GalaxyGL) {
                ((GalaxyGL) previewPreference.getScene()).setEllipseTwist((((Integer) newValue) - 100) / 1000.0f);
            }
            return true;
        }
        return false;
    }

}
