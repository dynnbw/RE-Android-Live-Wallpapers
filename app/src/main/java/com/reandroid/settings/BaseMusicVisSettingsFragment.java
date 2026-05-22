package com.reandroid.settings;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.gles.GLESScene;

public abstract class BaseMusicVisSettingsFragment extends PreferenceFragmentCompat {
    private static final int REQ_AUDIO = 1001;

    protected abstract GLESScene createScene(int width, int height);
    protected abstract Class<?> getWallpaperClass();

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_musicvis, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_MUSICVIS);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory((width, height) -> createScene(width, height));
        }

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                if (!ensureAudioPermission()) return true;
                MiuiPermissionHelper.launchLivePreview(this, getWallpaperClass());
                return true;
            });
        }

        ensureAudioPermission();
    }

    private boolean ensureAudioPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(requireContext(), R.string.musicvis_permission_granted, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), R.string.musicvis_permission_denied, Toast.LENGTH_SHORT).show();
            }
        }
    }
}
