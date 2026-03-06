package com.reandroid.wallpaper.settings;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.deepsea.DeepSeaGL;
import com.reandroid.wallpaper.deepsea.DeepSeaWallpaper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class DeepSeaSettingsFragment extends PreferenceFragmentCompat {
    private ActivityResultLauncher<Intent> imagePickerLauncher;
    private Preference mCustomBackground;
    private Preference mResetBackground;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            saveCustomBackground(uri);
                        }
                    } else {
                        if (!hasCustomBackgroundFile()) {
                            setBackgroundTypeValue("0");
                        }
                    }
                }
        );
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        getPreferenceManager().setSharedPreferencesName(DeepSeaGL.PREFS_NAME);
        setPreferencesFromResource(R.xml.prefs_deepsea, rootKey);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory(DeepSeaGL::new);
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                launchLivePreview(DeepSeaWallpaper.class);
                return true;
            });
        }

        mCustomBackground = findPreference("pref_deepsea_custom_background");
        if (mCustomBackground != null) {
            mCustomBackground.setOnPreferenceClickListener(pref -> {
                openImagePicker();
                return true;
            });
        }

        mResetBackground = findPreference("pref_deepsea_reset_background");
        if (mResetBackground != null) {
            mResetBackground.setOnPreferenceClickListener(pref -> {
                clearCustomBackground();
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

    private void saveCustomBackground(Uri uri) {
        Context context = requireContext().getApplicationContext();
        File outFile = getBackgroundFile(context);

        new Thread(() -> {
            boolean success = false;
            try (InputStream boundsStream = context.getContentResolver().openInputStream(uri)) {
                if (boundsStream == null) {
                    throw new IllegalStateException("Failed to open image stream");
                }
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(boundsStream, null, bounds);

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, 1024);
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

                try (InputStream decodeStream = context.getContentResolver().openInputStream(uri)) {
                    Bitmap decoded = BitmapFactory.decodeStream(decodeStream, null, opts);
                    if (decoded == null) {
                        throw new IllegalStateException("Failed to decode image");
                    }

                    Bitmap cropped = centerCropToSquare(decoded);
                    Bitmap scaled = scaleIfNeeded(cropped, 1024);

                    try (FileOutputStream output = new FileOutputStream(outFile)) {
                        success = scaled.compress(Bitmap.CompressFormat.JPEG, 90, output);
                    }

                    if (scaled != cropped) {
                        scaled.recycle();
                    }
                    if (cropped != decoded) {
                        cropped.recycle();
                    }
                    decoded.recycle();
                }
            } catch (Exception e) {
                success = false;
            }

            boolean finalSuccess = success;
            requireActivity().runOnUiThread(() -> {
                if (finalSuccess) {
                    requireContext().getSharedPreferences(DeepSeaGL.PREFS_NAME, 0)
                            .edit()
                            .putString(DeepSeaGL.KEY_BACKGROUND_IMAGE_TYPE, "1")
                            .putLong(DeepSeaGL.KEY_BACKGROUND_UPDATED, System.currentTimeMillis())
                            .apply();
                    updateCustomBackgroundSummary();
                    showBackgroundChangedDialog();
                } else {
                    Toast.makeText(requireContext(), R.string.deepsea_custom_background_failed, Toast.LENGTH_LONG).show();
                    setBackgroundTypeValue("0");
                }
            });
        }).start();
    }

    private void clearCustomBackground() {
        Context context = requireContext().getApplicationContext();
        File outFile = getBackgroundFile(context);
        if (outFile.exists()) {
            outFile.delete();
        }
        requireContext().getSharedPreferences(DeepSeaGL.PREFS_NAME, 0)
                .edit()
                .putString(DeepSeaGL.KEY_BACKGROUND_IMAGE_TYPE, "0")
                .putLong(DeepSeaGL.KEY_BACKGROUND_UPDATED, System.currentTimeMillis())
                .apply();
        updateCustomBackgroundSummary();
        showBackgroundResetDialog();
    }

    private void setBackgroundTypeValue(String value) {
        requireContext().getSharedPreferences(DeepSeaGL.PREFS_NAME, 0)
                .edit()
                .putString(DeepSeaGL.KEY_BACKGROUND_IMAGE_TYPE, value)
                .putLong(DeepSeaGL.KEY_BACKGROUND_UPDATED, System.currentTimeMillis())
                .apply();
    }

    private void updateCustomBackgroundSummary() {
        boolean hasCustom = hasCustomBackgroundFile();
        if (mCustomBackground != null) {
            mCustomBackground.setSummary(hasCustom
                    ? getString(R.string.deepsea_custom_background_set_summary)
                    : getString(R.string.deepsea_custom_background_summary));
        }
    }

    private boolean hasCustomBackgroundFile() {
        Context context = requireContext().getApplicationContext();
        File outFile = getBackgroundFile(context);
        return outFile.exists() && outFile.length() > 0;
    }

    private File getBackgroundFile(Context context) {
        File cacheDir = context.getExternalCacheDir() != null
                ? context.getExternalCacheDir()
                : context.getCacheDir();
        return new File(cacheDir, "deepseabackground.jpg");
    }

    private static int computeSampleSize(int width, int height, int targetSize) {
        int largest = Math.max(width, height);
        int sample = 1;
        while (largest / sample > targetSize) {
            sample *= 2;
        }
        return Math.max(1, sample);
    }

    private static Bitmap centerCropToSquare(Bitmap bitmap) {
        int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
        int x = (bitmap.getWidth() - size) / 2;
        int y = (bitmap.getHeight() - size) / 2;
        return Bitmap.createBitmap(bitmap, x, y, size, size);
    }

    private static Bitmap scaleIfNeeded(Bitmap bitmap, int targetSize) {
        int size = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (size <= targetSize) {
            return bitmap;
        }
        return Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true);
    }

    private void showBackgroundChangedDialog() {
        new AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                .setTitle(R.string.deepsea_background_changed_title)
                .setMessage(R.string.deepsea_background_changed_message)
                .setPositiveButton(R.string.deepsea_apply_now, (dialog, which) -> launchLivePreview(DeepSeaWallpaper.class))
                .setNegativeButton(R.string.deepsea_apply_later, null)
                .show();
    }

    private void showBackgroundResetDialog() {
        new AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                .setTitle(R.string.deepsea_background_reset_title)
                .setMessage(R.string.deepsea_background_reset_message)
                .setPositiveButton(R.string.deepsea_apply_now, (dialog, which) -> launchLivePreview(DeepSeaWallpaper.class))
                .setNegativeButton(R.string.deepsea_apply_later, null)
                .show();
    }

    private void launchLivePreview(Class<?> wallpaperClass) {
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
            Toast.makeText(requireContext(), R.string.pref_open_wallpaper_picker_failed, Toast.LENGTH_SHORT).show();
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
                .setTitle(R.string.miui_permission_title)
                .setMessage(R.string.miui_permission_message)
                .setPositiveButton(R.string.miui_permission_go_settings, (dialog, which) -> {
                    setMIUIPermissionDialogShown();
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), R.string.miui_permission_open_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton(R.string.miui_permission_continue, (dialog, which) -> {
                    setMIUIPermissionDialogShown();
                    launchLivePreview(wallpaperClass);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
