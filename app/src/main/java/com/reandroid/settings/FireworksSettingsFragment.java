package com.reandroid.settings;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.fireworks.FireworksGL;
import com.reandroid.wallpaper.fireworks.FireworksWallpaper;

public class FireworksSettingsFragment extends PreferenceFragmentCompat {

    private ActivityResultLauncher<Intent> imagePickerLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 注册图片选择器
        imagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        // 保存URI到SharedPreferences
                        requireContext().getSharedPreferences("wallpaper_prefs", 0)
                            .edit()
                            .putString("fireworks_custom_background_uri", uri.toString())
                            .apply();

                        // 请求持久化权限
                        try {
                            requireContext().getContentResolver().takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );
                        } catch (Exception e) {
                        }

                        Toast.makeText(requireContext(), R.string.fireworks_custom_background_set_toast, Toast.LENGTH_LONG).show();
                        updateCustomBackgroundSummary();

                        // 提示用户重新设置壁纸
                        new AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                            .setTitle(R.string.fireworks_background_changed_title)
                            .setMessage(R.string.fireworks_background_changed_message)
                            .setPositiveButton(R.string.action_apply_now, (dialog, which) -> {
                                MiuiPermissionHelper.launchLivePreview(this, FireworksWallpaper.class);
                            })
                            .setNegativeButton(R.string.action_later, null)
                            .show();
                    }
                }
            }
        );
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_fireworks, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_FIREWORKS);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory((width, height) -> new FireworksGL(width, height));
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, FireworksWallpaper.class);
                return true;
            });
        }

        Preference customBackground = findPreference("pref_custom_background");
        if (customBackground != null) {
            customBackground.setOnPreferenceClickListener(pref -> {
                openImagePicker();
                return true;
            });
        }

        Preference resetBackground = findPreference("pref_reset_background");
        if (resetBackground != null) {
            resetBackground.setOnPreferenceClickListener(pref -> {
                requireContext().getSharedPreferences("wallpaper_prefs", 0)
                    .edit()
                    .remove("fireworks_custom_background_uri")
                    .apply();
                Toast.makeText(requireContext(), R.string.fireworks_background_reset_toast, Toast.LENGTH_SHORT).show();
                updateCustomBackgroundSummary();

                // 提示用户重新设置壁纸
                new AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                    .setTitle(R.string.fireworks_background_reset_title)
                    .setMessage(R.string.fireworks_background_reset_message)
                    .setPositiveButton(R.string.action_apply_now, (dialog, which) -> {
                        MiuiPermissionHelper.launchLivePreview(this, FireworksWallpaper.class);
                    })
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
        Preference customBackground = findPreference("pref_custom_background");
        if (customBackground != null) {
            String uriString = requireContext().getSharedPreferences("wallpaper_prefs", 0)
                .getString("fireworks_custom_background_uri", null);
            if (uriString != null) {
                customBackground.setSummary(R.string.fireworks_custom_background_set_summary);
            } else {
                customBackground.setSummary(R.string.fireworks_custom_background_click_summary);
            }
        }
    }
}
