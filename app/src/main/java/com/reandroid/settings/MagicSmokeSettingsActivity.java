package com.reandroid.settings;

import androidx.fragment.app.Fragment;

import com.reandroid.wallpaper.R;

public class MagicSmokeSettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new MagicSmokeSettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_magicsmoke; }
}
