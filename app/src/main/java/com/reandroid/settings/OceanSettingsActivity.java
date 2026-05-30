package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class OceanSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new OceanSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_ocean; }
}
