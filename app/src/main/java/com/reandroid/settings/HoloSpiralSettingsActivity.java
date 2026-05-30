package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class HoloSpiralSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new HoloSpiralSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_holospiral; }
}
