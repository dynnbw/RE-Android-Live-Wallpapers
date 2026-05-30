package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class BlueSeaSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new BlueSeaSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_bluesea; }
}
