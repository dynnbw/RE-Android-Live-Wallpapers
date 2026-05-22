package com.reandroid.settings;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_DEEPSEA);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory(DeepSeaGL::new);
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                MiuiPermissionHelper.launchLivePreview(this, DeepSeaWallpaper.class);
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
        final java.lang.ref.WeakReference<DeepSeaSettingsFragment> selfRef =
                new java.lang.ref.WeakReference<>(DeepSeaSettingsFragment.this);
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
            java.lang.ref.WeakReference<DeepSeaSettingsFragment> ref = selfRef;
            android.app.Activity activity = ref.get() != null ? ref.get().getActivity() : null;
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                DeepSeaSettingsFragment fragment = ref.get();
                if (fragment == null || !fragment.isAdded()) return;
                Context safeContext = fragment.getContext();
                if (safeContext == null) return;
                if (finalSuccess) {
                    safeContext.getSharedPreferences(DeepSeaGL.PREFS_NAME, 0)
                            .edit()
                            .putString(DeepSeaGL.KEY_BACKGROUND_IMAGE_TYPE, "1")
                            .putLong(DeepSeaGL.KEY_BACKGROUND_UPDATED, System.currentTimeMillis())
                            .apply();
                    fragment.updateCustomBackgroundSummary();
                    fragment.showBackgroundChangedDialog();
                } else {
                    Toast.makeText(safeContext, R.string.deepsea_custom_background_failed, Toast.LENGTH_LONG).show();
                    fragment.setBackgroundTypeValue("0");
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
                .setPositiveButton(R.string.deepsea_apply_now, (dialog, which) -> MiuiPermissionHelper.launchLivePreview(this, DeepSeaWallpaper.class))
                .setNegativeButton(R.string.deepsea_apply_later, null)
                .show();
    }

    private void showBackgroundResetDialog() {
        new AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_WallpaperSettings_AppCompatDialog)
                .setTitle(R.string.deepsea_background_reset_title)
                .setMessage(R.string.deepsea_background_reset_message)
                .setPositiveButton(R.string.deepsea_apply_now, (dialog, which) -> MiuiPermissionHelper.launchLivePreview(this, DeepSeaWallpaper.class))
                .setNegativeButton(R.string.deepsea_apply_later, null)
                .show();
    }
}
