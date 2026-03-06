package com.reandroid.wallpaper.settings;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.fireworks.FireworksGL;
import com.reandroid.wallpaper.fireworks.FireworksWallpaper;

import java.io.InputStream;

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
                            e.printStackTrace();
                        }
                        
                        Toast.makeText(requireContext(), "自定义背景已设置，请重新设置壁纸以应用更改", Toast.LENGTH_LONG).show();
                        updateCustomBackgroundSummary();
                        
                        // 提示用户重新设置壁纸
                        new AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                            .setTitle("背景已更换")
                            .setMessage("新背景已保存。是否立即重新设置壁纸以应用更改？")
                            .setPositiveButton("立即设置", (dialog, which) -> {
                                launchLivePreview(FireworksWallpaper.class);
                            })
                            .setNegativeButton("稍后", null)
                            .show();
                    }
                }
            }
        );
    }
    
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_fireworks, rootKey);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory((width, height) -> new FireworksGL(width, height));
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                launchLivePreview(FireworksWallpaper.class);
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
                Toast.makeText(requireContext(), "已恢复默认背景", Toast.LENGTH_SHORT).show();
                updateCustomBackgroundSummary();
                
                // 提示用户重新设置壁纸
                new AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                    .setTitle("背景已重置")
                    .setMessage("已恢复为默认背景。是否立即重新设置壁纸以应用更改？")
                    .setPositiveButton("立即设置", (dialog, which) -> {
                        launchLivePreview(FireworksWallpaper.class);
                    })
                    .setNegativeButton("稍后", null)
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
                customBackground.setSummary("已设置自定义背景");
            } else {
                customBackground.setSummary("点击选择自定义背景图片");
            }
        }
    }

    private void launchLivePreview(Class<?> wallpaperClass) {
        // 检测MIUI系统，仅第一次提示用户授予权限
        if (isMIUI() && !hasShownMIUIPermissionDialog()) {
            showMIUIPermissionDialog(wallpaperClass);
            return;
        }
        
        try {
            Intent intent = new Intent();
            ComponentName componentName = new ComponentName(requireContext(), wallpaperClass);
            intent.setAction("android.service.wallpaper.CHANGE_LIVE_WALLPAPER");
            intent.putExtra("android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT", componentName);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), "不支持壁纸预览", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isMIUI() {
        return !getSystemProperty("ro.miui.ui.version.name", "").isEmpty()
                || !getSystemProperty("ro.miui.ui.version.code", "").isEmpty();
    }

    private String getSystemProperty(String key, String defaultValue) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean hasShownMIUIPermissionDialog() {
        return requireContext().getSharedPreferences("wallpaper_prefs", 0)
                .getBoolean("miui_permission_dialog_shown", false);
    }

    private void setMIUIPermissionDialogShown() {
        requireContext().getSharedPreferences("wallpaper_prefs", 0)
                .edit()
                .putBoolean("miui_permission_dialog_shown", true)
                .apply();
    }

    private void showMIUIPermissionDialog(Class<?> wallpaperClass) {
        new AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                .setTitle("需要授予权限")
                .setMessage("小米系统需要手动授予\"动态壁纸服务\"权限，否则无法正常打开壁纸预览。\n\n点击确定后，请在权限管理页面找到\"动态壁纸服务\"并开启。")
                .setPositiveButton("去设置", (dialog, which) -> {
                    setMIUIPermissionDialogShown();
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), "无法打开设置页面", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("继续", (dialog, which) -> {
                    setMIUIPermissionDialogShown();
                    launchLivePreview(wallpaperClass);
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
