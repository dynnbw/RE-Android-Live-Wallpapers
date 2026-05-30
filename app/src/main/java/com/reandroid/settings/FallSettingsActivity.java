package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class FallSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new FallSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_fall; }
}
