package com.reandroid.settings;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.nexus.NexusGL;
import com.reandroid.wallpaper.nexus.NexusWallpaper;

public class NexusSettingsFragment extends PreferenceFragmentCompat {
    private PreviewPreference previewPreference;
    private ActivityResultLauncher<Intent> imagePickerLauncher;
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            requireContext().getSharedPreferences("wallpaper_prefs", 0)
                                    .edit()
                                    .putString("nexus_custom_background_uri", uri.toString())
                                    .apply();

                            try {
                                requireContext().getContentResolver().takePersistableUriPermission(
                                        uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                );
                            } catch (Exception e) {
                            }

                            Toast.makeText(requireContext(),
                                    getString(R.string.nexus_custom_background_set_toast),
                                    Toast.LENGTH_LONG).show();
                            updateCustomBackgroundSummary();

                            new AlertDialog.Builder(requireContext())
                                    .setTitle(R.string.nexus_background_changed_title)
                                    .setMessage(R.string.nexus_background_changed_message)
                                    .setPositiveButton(R.string.action_apply_now,
                                            (dialog, which) -> MiuiPermissionHelper.launchLivePreview(this, NexusWallpaper.class))
                                    .setNegativeButton(R.string.action_later, null)
                                    .show();
                        }
                    }
                }
        );
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        // 迁移旧的String类型配置到int类型（必须在setPreferencesFromResource之前执行）
        migrateOldPreferences();
        
        setPreferencesFromResource(R.xml.prefs_nexus, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_NEXUS);

        previewPreference = findPreference("pref_preview");
        if (previewPreference != null) {
            previewPreference.setSceneFactory((width, height) -> new NexusGL(width, height));
        }

        ListPreference preset = findPreference("nexus_background_preset");
        if (preset != null) {
            preset.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, NexusWallpaper.class);
                return true;
            });
        }

        Preference customBackground = findPreference("pref_nexus_custom_background");
        if (customBackground != null) {
            customBackground.setOnPreferenceClickListener(pref -> {
                openImagePicker();
                return true;
            });
        }

        Preference resetBackground = findPreference("pref_nexus_reset_background");
        if (resetBackground != null) {
            resetBackground.setOnPreferenceClickListener(pref -> {
                requireContext().getSharedPreferences("wallpaper_prefs", 0)
                        .edit()
                        .remove("nexus_custom_background_uri")
                        .apply();
                Toast.makeText(requireContext(),
                        getString(R.string.nexus_background_reset_toast),
                        Toast.LENGTH_SHORT).show();
                updateCustomBackgroundSummary();

                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.nexus_background_reset_title)
                        .setMessage(R.string.nexus_background_reset_message)
                        .setPositiveButton(R.string.action_apply_now,
                                (dialog, which) -> MiuiPermissionHelper.launchLivePreview(this, NexusWallpaper.class))
                        .setNegativeButton(R.string.action_later, null)
                        .show();
                return true;
            });
        }

        updateCustomBackgroundSummary();
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        imagePickerLauncher.launch(intent);
    }

    private void updateCustomBackgroundSummary() {
        Preference customBackground = findPreference("pref_nexus_custom_background");
        if (customBackground != null) {
            String uriString = requireContext().getSharedPreferences("wallpaper_prefs", 0)
                    .getString("nexus_custom_background_uri", null);
            if (uriString != null) {
                customBackground.setSummary(R.string.pref_nexus_custom_background_set_summary);
            } else {
                customBackground.setSummary(R.string.pref_nexus_custom_background_summary);
            }
        }
    }

    /**
     * 迁移旧的String类型配置到int类型
     * 避免从EditTextPreference切换到SeekBarPreference时的类型冲突
     */
    private void migrateOldPreferences() {
        if (getContext() == null) return;
        
        android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(getContext());
        android.content.SharedPreferences.Editor editor = prefs.edit();
        boolean needsMigration = false;

        // 检查并转换整数参数
        String[] intKeys = {
            "nexus_max_pulses",
            "nexus_max_extras", 
            "nexus_pulse_size",
            "nexus_glow_size",
            "nexus_trail_size"
        };
        int[] intDefaults = {20, 40, 14, 64, 40};

        for (int i = 0; i < intKeys.length; i++) {
            String key = intKeys[i];
            if (prefs.contains(key)) {
                try {
                    // 尝试读取String类型（旧格式）
                    String oldValue = prefs.getString(key, null);
                    if (oldValue != null) {
                        // 转换为int并保存
                        int newValue = Integer.parseInt(oldValue);
                        editor.putInt(key, newValue);
                        needsMigration = true;
                    }
                } catch (ClassCastException e) {
                    // 已经是int类型，无需迁移
                }
            }
        }

        // 检查并转换浮点数参数（需要转换为整数表示）
        if (prefs.contains("nexus_speed")) {
            try {
                String oldValue = prefs.getString("nexus_speed", null);
                if (oldValue != null) {
                    float floatValue = Float.parseFloat(oldValue);
                    int intValue = (int)(floatValue * 100); // 0.2 -> 20
                    editor.putInt("nexus_speed", intValue);
                    needsMigration = true;
                }
            } catch (ClassCastException e) {
                // 已经是int类型
            }
        }

        if (prefs.contains("nexus_speed_delta_min")) {
            try {
                String oldValue = prefs.getString("nexus_speed_delta_min", null);
                if (oldValue != null) {
                    float floatValue = Float.parseFloat(oldValue);
                    int intValue = (int)(floatValue * 100); // 0.7 -> 70
                    editor.putInt("nexus_speed_delta_min", intValue);
                    needsMigration = true;
                }
            } catch (ClassCastException e) {
                // 已经是int类型
            }
        }

        if (prefs.contains("nexus_speed_delta_max")) {
            try {
                String oldValue = prefs.getString("nexus_speed_delta_max", null);
                if (oldValue != null) {
                    float floatValue = Float.parseFloat(oldValue);
                    int intValue = (int)(floatValue * 100); // 1.7 -> 170
                    editor.putInt("nexus_speed_delta_max", intValue);
                    needsMigration = true;
                }
            } catch (ClassCastException e) {
                // 已经是int类型
            }
        }

        if (needsMigration) {
            editor.apply();
        }
    }
}
