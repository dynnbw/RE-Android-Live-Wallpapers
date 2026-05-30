package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class NightSkySettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new NightSkySettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_nightsky; }
}
