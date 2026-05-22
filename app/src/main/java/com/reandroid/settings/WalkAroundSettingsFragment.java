package com.reandroid.settings;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.walkaround.WalkAroundGL;
import com.reandroid.wallpaper.walkaround.WalkAroundWallpaper;

public class WalkAroundSettingsFragment extends PreferenceFragmentCompat {
    private static final int REQ_CAMERA = 2001;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_walkaround, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_WALKAROUND);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory((width, height) -> new WalkAroundGL(width, height, requireContext()));
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                if (!ensureCameraPermission()) return true;
                MiuiPermissionHelper.launchLivePreview(this, WalkAroundWallpaper.class);
                return true;
            });
        }

        ensureCameraPermission();
    }

    private boolean ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(requireContext(), R.string.walkaround_permission_granted, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), R.string.walkaround_permission_denied, Toast.LENGTH_SHORT).show();
            }
        }
    }

}
