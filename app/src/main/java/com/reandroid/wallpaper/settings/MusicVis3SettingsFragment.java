package com.reandroid.wallpaper.settings;

import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.musicvis.MusicVisWaveScene;
import com.reandroid.wallpaper.musicvis.MusicVisWallpaper3;
import com.reandroid.wallpaper.R;

public class MusicVis3SettingsFragment extends BaseMusicVisSettingsFragment {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new MusicVisWaveScene(width, height, requireContext(),
                MusicVisWaveScene.Mode.FFT, R.drawable.musicvis_ice);
    }

    @Override
    protected Class<?> getWallpaperClass() {
        return MusicVisWallpaper3.class;
    }
}
