package com.reandroid.settings;

import com.reandroid.gles.GLESScene;
import com.reandroid.wallpaper.musicvis.MusicVisVuScene;
import com.reandroid.wallpaper.musicvis.MusicVisWallpaper4;

public class MusicVis4SettingsFragment extends BaseMusicVisSettingsFragment {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new MusicVisVuScene(width, height, requireContext());
    }

    @Override
    protected Class<?> getWallpaperClass() {
        return MusicVisWallpaper4.class;
    }
}
