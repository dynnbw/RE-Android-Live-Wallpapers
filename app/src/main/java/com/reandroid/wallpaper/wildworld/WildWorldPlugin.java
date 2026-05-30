package com.reandroid.wallpaper.wildworld;

import android.content.Context;

import com.reandroid.plugin.WallpaperEngine;
import com.reandroid.plugin.WallpaperPlugin;
import com.reandroid.plugin.WallpaperPluginHost;

public class WildWorldPlugin implements WallpaperPlugin {
    @Override
    public String getId() {
        return "wildworld";
    }

    @Override
    public String getDisplayName(Context context) {
        return "Wild World";
    }

    @Override
    public WallpaperEngine createEngine(Context context, WallpaperPluginHost host) {
        return new WildWorldEngine(context, host);
    }

    @Override
    public void release() {
    }
}
