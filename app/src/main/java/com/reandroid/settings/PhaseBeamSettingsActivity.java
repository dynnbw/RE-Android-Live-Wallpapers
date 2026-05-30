package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class PhaseBeamSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new PhaseBeamSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_phasebeam; }
}
