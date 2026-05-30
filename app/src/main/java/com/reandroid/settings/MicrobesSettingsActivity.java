package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class MicrobesSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new MicrobesSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_microbes; }
}
