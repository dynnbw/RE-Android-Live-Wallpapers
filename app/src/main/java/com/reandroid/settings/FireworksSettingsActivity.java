package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class FireworksSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new FireworksSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_fireworks; }
}
