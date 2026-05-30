package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class NebulaSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new NebulaSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_nebula; }
}
