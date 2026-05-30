package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class NoiseFieldSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new NoiseFieldSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_noisefield; }
}
