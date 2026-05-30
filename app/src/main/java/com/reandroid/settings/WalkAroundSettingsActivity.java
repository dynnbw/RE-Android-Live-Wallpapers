package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class WalkAroundSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new WalkAroundSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_walkaround; }
}
