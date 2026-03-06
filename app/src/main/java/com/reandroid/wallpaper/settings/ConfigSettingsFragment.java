package com.reandroid.wallpaper.settings;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.EditTextPreference;

import com.reandroid.wallpaper.R;

public class ConfigSettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_config, rootKey);

        EditTextPreference apiKeyPref = findPreference("openweather_api_key");
        if (apiKeyPref != null) {
            updateApiKeySummary(apiKeyPref, apiKeyPref.getText());
            apiKeyPref.setOnPreferenceChangeListener((preference, newValue) -> {
                updateApiKeySummary(apiKeyPref, newValue);
                return true;
            });
        }

        EditTextPreference previewRatioPref = findPreference("pref_preview_ratio");
        if (previewRatioPref != null) {
            updatePreviewRatioSummary(previewRatioPref, previewRatioPref.getText());
            previewRatioPref.setOnPreferenceChangeListener((preference, newValue) -> {
                String normalized = normalizeRatio(newValue);
                if (normalized == null) {
                    Toast.makeText(requireContext(), R.string.pref_preview_ratio_invalid, Toast.LENGTH_SHORT).show();
                    return false;
                }
                String raw = newValue == null ? "" : newValue.toString().trim();
                if (!normalized.equals(raw)) {
                    previewRatioPref.setText(normalized);
                    updatePreviewRatioSummary(previewRatioPref, normalized);
                    return false;
                }
                updatePreviewRatioSummary(previewRatioPref, normalized);
                return true;
            });
        }

        Preference applyApi = findPreference("pref_apply_api");
        if (applyApi != null) {
            applyApi.setOnPreferenceClickListener(pref -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://zhuanlan.zhihu.com/p/656012235"));
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(requireContext(), "无法打开浏览器", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }
    }

    private void updateApiKeySummary(EditTextPreference preference, Object value) {
        String text = value == null ? "" : value.toString();
        if (TextUtils.isEmpty(text)) {
            preference.setSummary(R.string.pref_openweather_api_key_summary_unset);
        } else {
            preference.setSummary(R.string.pref_openweather_api_key_summary_set);
        }
    }

    private void updatePreviewRatioSummary(EditTextPreference preference, Object value) {
        String text = value == null ? "" : value.toString().trim();
        if (TextUtils.isEmpty(text)) {
            preference.setSummary(R.string.pref_preview_ratio_summary);
        } else {
            preference.setSummary(text);
        }
    }

    @Nullable
    private String normalizeRatio(Object value) {
        if (value == null) {
            return null;
        }
        String raw = value.toString().trim();
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        String[] parts = raw.split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            if (w <= 0 || h <= 0) {
                return null;
            }
            return w + ":" + h;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
