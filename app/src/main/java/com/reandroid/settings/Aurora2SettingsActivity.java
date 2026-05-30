package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class Aurora2SettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new Aurora2SettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_aurora2; }
}
