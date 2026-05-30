package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class CubeSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new CubeSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_cube; }
}
