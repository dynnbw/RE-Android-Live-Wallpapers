package com.reandroid.wallpaper.phasebeam;

import android.content.Context;

import com.reandroid.gles.GLESScene;
import com.reandroid.plugin.BasePluginEngine;
import com.reandroid.plugin.WallpaperPluginHost;

public class PhaseBeamEngine extends BasePluginEngine {
    public PhaseBeamEngine(Context context, WallpaperPluginHost host) {
        super(context, host);
    }

    @Override
    protected GLESScene createScene(int width, int height, Context context) {
        return new PhaseBeamGL(width, height, context);
    }
}
