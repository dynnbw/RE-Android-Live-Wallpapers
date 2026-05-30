package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class DeepSeaSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new DeepSeaSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_deepsea; }
}
