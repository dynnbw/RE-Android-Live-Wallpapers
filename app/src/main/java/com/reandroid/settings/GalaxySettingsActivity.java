package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class GalaxySettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new GalaxySettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_galaxy; }
}
