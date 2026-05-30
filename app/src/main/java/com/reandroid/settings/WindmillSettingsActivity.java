package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class WindmillSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new WindmillSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_windmill; }
}
