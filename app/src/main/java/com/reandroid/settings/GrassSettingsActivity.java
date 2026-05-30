package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class GrassSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new GrassSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_grass; }
}
