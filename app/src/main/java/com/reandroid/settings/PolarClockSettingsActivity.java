package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class PolarClockSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new PolarClockSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_clock; }
}
