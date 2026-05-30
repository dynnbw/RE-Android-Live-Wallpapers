package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class Galaxy4SettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new Galaxy4SettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_galaxy4; }
}
