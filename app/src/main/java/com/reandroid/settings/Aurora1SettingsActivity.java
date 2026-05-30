package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class Aurora1SettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new Aurora1SettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_aurora1; }
}
