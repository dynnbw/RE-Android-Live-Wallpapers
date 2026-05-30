package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class ForestSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new ForestSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_forest; }
}
