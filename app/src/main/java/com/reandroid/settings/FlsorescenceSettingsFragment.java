package com.reandroid.settings;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.flsorescence.FlsorescenceGL;
import com.reandroid.wallpaper.flsorescence.FlsorescenceWallpaper;

public class FlsorescenceSettingsFragment extends PreferenceFragmentCompat {
    private static final String KEY_FLSORESCENCE_SHAPE = "flsorescence_shape";
    private static final String KEY_FLSORESCENCE_COLOR = "flsorescence_color";
    private static final String KEY_FLSORESCENCE_SCALE = "flsorescence_scale";
    private static final String KEY_FLSORESCENCE_SPEED = "flsorescence_speed";
    private static final String KEY_FLSORESCENCE_PATTERN = "flsorescence_pattern";
    private static final String KEY_FLSORESCENCE_AMOUNT = "flsorescence_amount";

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_flsorescence, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_FLSORESCENCE);
        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory((width, height) -> new FlsorescenceGL(width, height));
        }
        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, FlsorescenceWallpaper.class);
                return true;
            });
        }
        initPreferences();
    }

    private void initPreferences() {
        updatePreferenceSummary(KEY_FLSORESCENCE_SHAPE);
        updatePreferenceSummary(KEY_FLSORESCENCE_COLOR);
        updatePreferenceSummary(KEY_FLSORESCENCE_SCALE);
        updatePreferenceSummary(KEY_FLSORESCENCE_SPEED);
        updatePreferenceSummary(KEY_FLSORESCENCE_PATTERN);
        updatePreferenceSummary(KEY_FLSORESCENCE_AMOUNT);
    }

    private void updatePreferenceSummary(String key) {
        Preference preference = findPreference(key);
        if (preference == null) return;
        if (preference instanceof androidx.preference.ListPreference) {
            androidx.preference.ListPreference listPreference = (androidx.preference.ListPreference) preference;
            int index = listPreference.findIndexOfValue(listPreference.getValue());
            CharSequence summary = index >= 0 ? listPreference.getEntries()[index] : null;
            preference.setSummary(summary);
        } else if (preference instanceof androidx.preference.SeekBarPreference) {
            androidx.preference.SeekBarPreference seekBarPreference = (androidx.preference.SeekBarPreference) preference;
            int progress = seekBarPreference.getValue();
            String summary = String.valueOf(progress);
            switch (key) {
                case KEY_FLSORESCENCE_SCALE:
                    float scale = 0.5f + (progress - 50) * 0.01f;
                    summary = progress + " (" + String.format("%.2f", scale) + ")";
                    break;
                case KEY_FLSORESCENCE_SPEED:
                    float speed = 0.5f + (progress - 50) * 0.01f;
                    summary = progress + " (" + String.format("%.2f", speed) + ")";
                    break;
                case KEY_FLSORESCENCE_AMOUNT:
                    summary = progress + " dots";
                    break;
            }
            preference.setSummary(summary);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // dot color scheme
        androidx.preference.ListPreference colorPref = findPreference(KEY_FLSORESCENCE_COLOR);
        if (colorPref != null) {
            colorPref.setOnPreferenceChangeListener((preference, newValue) -> {
                int colorScheme = 0;
                try { colorScheme = Integer.parseInt(newValue.toString()); } catch (Exception ignored) {}
                PreviewPreference preview = findPreference("pref_preview");
                if (preview != null && preview.getScene() instanceof FlsorescenceGL) {
                    ((FlsorescenceGL) preview.getScene()).setColorScheme(colorScheme);
                }
                return true;
            });
        }
        // dot scale
        androidx.preference.SeekBarPreference scalePref = findPreference(KEY_FLSORESCENCE_SCALE);
        if (scalePref != null) {
            scalePref.setOnPreferenceChangeListener((preference, newValue) -> {
                int progress = (int) newValue;
                float scale = 0.5f + (progress - 50) * 0.01f;
                PreviewPreference preview = findPreference("pref_preview");
                if (preview != null && preview.getScene() instanceof FlsorescenceGL) {
                    ((FlsorescenceGL) preview.getScene()).setScale(scale);
                }
                return true;
            });
        }
        // motion speed
        androidx.preference.SeekBarPreference speedPref = findPreference(KEY_FLSORESCENCE_SPEED);
        if (speedPref != null) {
            speedPref.setOnPreferenceChangeListener((preference, newValue) -> {
                int progress = (int) newValue;
                float speed = 0.5f + (progress - 50) * 0.01f;
                PreviewPreference preview = findPreference("pref_preview");
                if (preview != null && preview.getScene() instanceof FlsorescenceGL) {
                    ((FlsorescenceGL) preview.getScene()).setSpeed(speed);
                }
                return true;
            });
        }
        // dot quantity
        androidx.preference.SeekBarPreference amountPref = findPreference(KEY_FLSORESCENCE_AMOUNT);
        if (amountPref != null) {
            amountPref.setOnPreferenceChangeListener((preference, newValue) -> {
                int dots = (int) newValue;
                PreviewPreference preview = findPreference("pref_preview");
                if (preview != null && preview.getScene() instanceof FlsorescenceGL) {
                    ((FlsorescenceGL) preview.getScene()).setActiveDots(dots);
                }
                return true;
            });
        }
        // motion pattern
        androidx.preference.ListPreference patternPref = findPreference(KEY_FLSORESCENCE_PATTERN);
        if (patternPref != null) {
            patternPref.setOnPreferenceChangeListener((preference, newValue) -> {
                int pattern = 0;
                try { pattern = Integer.parseInt(newValue.toString()); } catch (Exception ignored) {}
                PreviewPreference preview = findPreference("pref_preview");
                if (preview != null && preview.getScene() instanceof FlsorescenceGL) {
                    ((FlsorescenceGL) preview.getScene()).setPattern(pattern);
                }
                return true;
            });
        }
        // dot shape
        androidx.preference.ListPreference shapePref = findPreference(KEY_FLSORESCENCE_SHAPE);
        if (shapePref != null) {
            shapePref.setOnPreferenceChangeListener((preference, newValue) -> {
                int shape = 0;
                try { shape = Integer.parseInt(newValue.toString()); } catch (Exception ignored) {}
                PreviewPreference preview = findPreference("pref_preview");
                if (preview != null && preview.getScene() instanceof FlsorescenceGL) {
                    ((FlsorescenceGL) preview.getScene()).setShape(shape);
                }
                return true;
            });
        }
    }
}
