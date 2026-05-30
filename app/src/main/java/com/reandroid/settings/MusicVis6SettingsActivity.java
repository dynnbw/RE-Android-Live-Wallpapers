package com.reandroid.settings;
import androidx.fragment.app.Fragment;
import com.reandroid.wallpaper.R;
public class MusicVis6SettingsActivity extends BaseWallpaperSettingsActivity {
    @Override protected Fragment createFragment() { return new MusicVis6SettingsFragment(); }
    @Override protected int getTitleResId() { return R.string.wallpaper_vis6; }
}
