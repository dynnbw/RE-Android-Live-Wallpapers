package com.reandroid.wallpaper.walkaround;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class WalkAroundPlugin implements WallpaperPlugin {
    @Override
    public String getId() {
        return "walkaround";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Walk Around";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new WalkAroundEngine(context, host);
    }

    @Override
    public void release() {
    }
}
