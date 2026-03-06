package com.reandroid.wallpaper.settings;

import com.reandroid.wallpaper.gles.GLESScene;
import com.reandroid.wallpaper.musicvis.MusicVisWaveScene;
import com.reandroid.wallpaper.musicvis.MusicVisWallpaper2;
import com.reandroid.wallpaper.R;

public class MusicVis2SettingsFragment extends BaseMusicVisSettingsFragment {
    @Override
    protected GLESScene createScene(int width, int height) {
        return new MusicVisWaveScene(width, height, requireContext(),
                MusicVisWaveScene.Mode.PCM, R.drawable.musicvis_fire);
    }

    @Override
    protected Class<?> getWallpaperClass() {
        return MusicVisWallpaper2.class;
    }
}
