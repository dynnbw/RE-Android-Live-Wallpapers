package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class FlsorescenceSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new FlsorescenceSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_flsorescence; }
}
