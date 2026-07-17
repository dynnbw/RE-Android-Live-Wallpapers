package com.reandroid.wallpaper.nixietube;

import android.content.Context;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class NixieTubeEngine extends BasePluginEngine {

    public NixieTubeEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new NixieTubeGL(width, height, context);
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
        super.onVisibilityChanged(visible);   // GLESScene.start/stop lifecycle
        if (mScene instanceof NixieTubeGL) {
            NixieTubeGL gl = (NixieTubeGL) mScene;
            if (visible) {
                gl.startAudio();
            } else {
                gl.stopAudio();
                gl.resetModeToTime();    // show clock immediately when returning
            }
        }
    }
}
