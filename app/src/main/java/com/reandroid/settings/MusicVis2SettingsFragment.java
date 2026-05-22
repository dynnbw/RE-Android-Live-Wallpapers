package com.reandroid.settings;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.reandroid.wallpaper.R;
import com.reandroid.wallpaper.musicvis.MusicVisWallpaper2;
import com.reandroid.wallpaper.musicvis.MusicVisWaveScene;

public class MusicVis2SettingsFragment extends PreferenceFragmentCompat
        implements Preference.OnPreferenceChangeListener {

    private SwitchPreferenceCompat mRecolorPref;
    private SeekBarPreference mHuePref, mSaturationPref, mBrightnessPref;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        getPreferenceManager().setSharedPreferencesName("musicvis2_prefs");
        setPreferencesFromResource(R.xml.prefs_musicvis2, rootKey);
        SettingsResetHelper.attachResetPreference(this, SettingsResetHelper.TARGET_MUSICVIS2);

        PreviewPreference preview = findPreference("pref_preview");
        if (preview != null) {
            preview.setSceneFactory((w, h) -> new MusicVisWaveScene(w, h, requireContext(),
                    MusicVisWaveScene.Mode.PCM, R.drawable.musicvis_fire));
        }

        mRecolorPref = findPreference("musicvis_recolor");
        mHuePref = findPreference("musicvis_hue");
        mSaturationPref = findPreference("musicvis_saturation");
        mBrightnessPref = findPreference("musicvis_brightness");
        if (mHuePref != null) mHuePref.setOnPreferenceChangeListener(this);
        if (mSaturationPref != null) mSaturationPref.setOnPreferenceChangeListener(this);
        if (mBrightnessPref != null) mBrightnessPref.setOnPreferenceChangeListener(this);

        Preference openPicker = findPreference("pref_open_wallpaper_picker");
        if (openPicker != null) {
            openPicker.setOnPreferenceClickListener(pref -> {
                launchLivePreview(MusicVisWallpaper2.class);
                return true;
            });
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if ((preference == mHuePref || preference == mSaturationPref || preference == mBrightnessPref)
                && !isRecolorEnabled()) {
            Toast.makeText(requireContext(), R.string.musicvis_enable_recolor_first, Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private boolean isRecolorEnabled() {
        return mRecolorPref != null && mRecolorPref.isChecked();
    }

    private void launchLivePreview(Class<?> wallpaperClass) {
        if (isMIUI() && !hasShownMIUIPermissionDialog()) {
            showMIUIPermissionDialog(wallpaperClass);
            return;
        }
        try {
            Intent intent = new Intent("android.service.wallpaper.CHANGE_LIVE_WALLPAPER");
            intent.putExtra("android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT",
                    new ComponentName(requireContext(), wallpaperClass));
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
            return (String) sp.getMethod("get", String.class, String.class).invoke(null, key, defaultValue);
        } catch (Exception e) { return defaultValue; }
    }

    private boolean hasShownMIUIPermissionDialog() {
        return requireContext().getSharedPreferences("wallpaper_prefs", 0)
                .getBoolean("miui_permission_dialog_shown", false);
    }

    private void showMIUIPermissionDialog(Class<?> wallpaperClass) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.miui_permission_title)
                .setMessage(R.string.miui_permission_message)
                .setPositiveButton(R.string.miui_permission_go_settings, (d, w) -> {
                    requireContext().getSharedPreferences("wallpaper_prefs", 0).edit()
                            .putBoolean("miui_permission_dialog_shown", true).apply();
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:" + requireContext().getPackageName()))); }
                    catch (Exception e) { Toast.makeText(requireContext(), R.string.miui_permission_open_failed, Toast.LENGTH_SHORT).show(); }
                })
                .setNeutralButton(R.string.miui_permission_continue, (d, w) -> {
                    requireContext().getSharedPreferences("wallpaper_prefs", 0).edit()
                            .putBoolean("miui_permission_dialog_shown", true).apply();
                    launchLivePreview(wallpaperClass);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
