package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class WildWorldSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new WildWorldSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_wildworld; }
}
