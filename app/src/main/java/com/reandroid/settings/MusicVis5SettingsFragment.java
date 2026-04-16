package com.reandroid.settings;

import com.reandroid.gles.GLESScene;
import com.reandroid.wallpaper.musicvis.MusicVisManyScene;
import com.reandroid.wallpaper.musicvis.MusicVisWallpaper5;

public class MusicVis5SettingsFragment extends BaseMusicVisSettingsFragment {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new MusicVisManyScene(width, height, requireContext());
    }

    @Override
    protected Class<?> getWallpaperClass() {
        return MusicVisWallpaper5.class;
    }
}
