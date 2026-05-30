package com.reandroid.wallpaper.phasebeam;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class PhaseBeamPlugin implements WallpaperPlugin {
    @Override
    public String getId() {
        return "phasebeam";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Phase Beam";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new PhaseBeamEngine(context, host);
    }

    @Override
    public void release() {
    }
}
