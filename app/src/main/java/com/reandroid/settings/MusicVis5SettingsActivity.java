package com.reandroid.settings;
import androidx.fragment.app.Fragment;
import com.reandroid.wallpaper.R;
public class MusicVis5SettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new MusicVis5SettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_vis5; }
}
